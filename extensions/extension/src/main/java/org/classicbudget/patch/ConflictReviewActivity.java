package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import org.json.JSONObject;

/** Shows the actual versions from the active budget and permits supported, explicit repairs. */
public final class ConflictReviewActivity extends Activity {
    private LinearLayout content;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Sync conflicts", "Konflikty synchronizacji"));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(16);
        content.setPadding(inset, inset, inset, inset);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        load();
    }

    private void load() {
        content.removeAllViews();
        String id = SyncHealth.selected(this);
        if (id == null) {
            text(tr("Open a synced budget first.", "Najpierw otwórz synchronizowany budżet."), 16);
            return;
        }
        text(tr("Checking saved conflicts…", "Sprawdzam zapisane konflikty…"), 16);
        Context app = getApplicationContext();
        SyncHealth.READER.execute(() -> {
            try {
                JSONObject records = ConflictStore.active(app, id);
                ArrayList<Card> cards = new ArrayList<>();
                try (SQLiteDatabase db = ConflictResolution.read(app, id)) {
                    boolean writable = canWrite(db);
                    for (Iterator<String> keys = records.keys(); keys.hasNext();) {
                        String key = keys.next();
                        JSONObject record = records.getJSONObject(key);
                        JSONObject payload = new JSONObject(record.getString("payload"));
                        String chosen = ConflictResolution.resolvedVersion(db, key);
                        ConflictResolution.Options options = chosen == null && writable
                            ? ConflictResolution.options(db, key, payload) : null;
                        GroupedConflictResolution.Plan group = chosen == null && writable && options == null
                            ? GroupedConflictResolution.options(db, records, key) : null;
                        if (group != null && !key.equals(group.key())) continue;
                        cards.add(new Card(key, record.getString("sha256"), payload, options, group, chosen,
                            sendState(db, payload, chosen)));
                    }
                }
                cards.sort(java.util.Comparator.comparing(card -> card.key));
                boolean folder = BackendSelector.FOLDER.equals(BackendSelector.chosen());
                java.util.List<FolderConflictReview.Entry> files = Collections.emptyList();
                String fileIssue = null;
                if (folder) {
                    try {
                        files = FolderConflictReview.scan(app, id);
                    } catch (Exception unreadable) {
                        fileIssue = tr("Could not list conflict files. Check access to the shared folder.",
                            "Nie udało się odczytać kopii konfliktowych. Sprawdź dostęp do udostępnionego folderu.");
                    }
                }
                java.util.List<FolderConflictReview.Entry> shown = files;
                String problem = fileIssue;
                runOnUiThread(() -> render(id, cards, folder, shown, problem));
            } catch (Exception unreadable) {
                runOnUiThread(() -> unavailable(tr("Saved conflicts could not be read.",
                    "Nie udało się odczytać zachowanych konfliktów.")));
            }
        });
    }

    private static boolean canWrite(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'", null)) {
            return cursor.moveToFirst() && ConflictResolution.enabled(cursor.getString(0));
        }
    }

    private static String sendState(SQLiteDatabase db, JSONObject payload, String version) throws Exception {
        if (version == null) return null;
        String guid = payload.getJSONObject("local").getJSONObject("row").getString("guid");
        try (Cursor cursor = db.rawQuery("SELECT version,synced FROM txnTable WHERE guid=?", new String[]{guid})) {
            if (!cursor.moveToFirst() || !version.equals(cursor.getString(0))) return "changed";
            return cursor.getInt(1) == 0 ? "pending" : "sent";
        }
    }

    private void render(String id, java.util.List<Card> records, boolean folder,
                        java.util.List<FolderConflictReview.Entry> files, String fileIssue) {
        if (isFinishing() || isDestroyed()) return;
        content.removeAllViews();
        text(tr("Transaction conflicts", "Konflikty transakcji"), 20);
        if (records.isEmpty()) text(tr("No saved transaction conflicts for this budget.",
            "W tym budżecie nie ma zapisanych konfliktów transakcji."), 16);
        for (Card card : records) {
            if (card.chosen != null) {
                switch (card.sendState) {
                    case "sent": text(folder ? tr("Resolution written to the shared folder", "Rozstrzygnięcie zapisane w udostępnionym folderze")
                        : tr("Resolution sent", "Rozstrzygnięcie wysłane"), 17); break;
                    case "pending": text(tr("Resolved on this phone · waiting to sync", "Rozwiązano na telefonie · czeka na synchronizację"), 17); break;
                    default: text(tr("Reviewed · the transaction changed again", "Sprawdzono · transakcja zmieniła się ponownie"), 17);
                }
            }
            if (card.group != null) {
                text(groupTitle(card.group.kind), 18);
                text(tr("On this phone", "Na tym telefonie"), 17);
                text(groupDetails(card.group, false), 15);
                text(tr("From the other device", "Z drugiego urządzenia"), 17);
                text(groupDetails(card.group, true), 15);
                button(tr("Choose a version", "Wybierz wersję"), "conflict-choose-version:" + card.key,
                    () -> confirmGroup(id, card));
                continue;
            }
            JSONObject local = card.payload.optJSONObject("local").optJSONObject("row");
            JSONObject incoming = card.payload.optJSONObject("incoming");
            JSONObject names = card.payload.optJSONObject("display");
            text(tr("On this phone", "Na tym telefonie"), 17);
            text(details(local, names, "local", true), 15);
            text(tr("From the other device", "Z drugiego urządzenia"), 17);
            text(details(incoming, names, "incoming", false), 15);
            if (card.options != null && (card.options.amountDiffers() || card.options.categoryDiffers())) {
                button(tr("Choose values", "Wybierz wartości"), "conflict-choose-values",
                    () -> confirmFinancial(id, card, note(local), note(incoming)));
            } else if (card.options != null) {
                button(tr("Keep the note from this phone", "Zachowaj notatkę z telefonu"), "conflict-keep-local",
                    () -> confirmNote(id, card, note(local), false));
                button(tr("Keep the note from the other device", "Zachowaj notatkę z drugiego urządzenia"), "conflict-keep-remote",
                    () -> confirmNote(id, card, note(incoming), false));
                button(tr("Edit the result", "Edytuj wynik"), "conflict-edit",
                    () -> confirmNote(id, card, note(local), true));
            } else if (card.chosen == null) {
                text(tr("You can compare both versions here. Selecting a result requires a supported transaction "
                    + "in a test budget and both versions synchronized to this phone.",
                    "Tu można porównać obie wersje. Zapis wyniku wymaga obsługiwanej transakcji "
                    + "w budżecie testowym oraz synchronizacji obu wersji na tym telefonie."), 14);
            }
        }
        if (folder) {
            text(tr("Conflicting files in the shared folder", "Konflikty plików w udostępnionym folderze"), 20);
            if (fileIssue != null) text(fileIssue, 15);
            else if (files.isEmpty()) text(tr("No conflict copies found.", "Nie znaleziono kopii konfliktowych."), 15);
            for (FolderConflictReview.Entry entry : files) {
                text((entry.folder.isEmpty() ? "" : entry.folder + "/") + entry.name, 15);
                if (entry.duplicate) {
                    button(tr("Archive identical copy", "Zarchiwizuj identyczną kopię"), "conflict-archive-file",
                        () -> confirmFile(id, entry));
                } else {
                    text(tr("Keep both files for review. A difference or a missing original needs a separate recovery.",
                        "Zachowaj oba pliki do sprawdzenia. Różnica treści lub brak oryginału wymaga osobnego odzyskiwania."), 14);
                }
            }
        }
    }

    private static String groupTitle(GroupedConflictResolution.Kind kind) {
        switch (kind) {
            case TRANSFER: return tr("Transfer conflict", "Konflikt przelewu");
            case SPLIT: return tr("Split transaction conflict", "Konflikt podziału");
            default: return tr("Deletion and edit conflict", "Usunięcie i równoległa edycja");
        }
    }

    private static String groupDetails(GroupedConflictResolution.Plan group, boolean remote) {
        ArrayList<String> lines = new ArrayList<>();
        for (GroupedConflictResolution.Entry entry : group.entries) {
            lines.add(entry.root.accountName);
            for (GroupedConflictResolution.Row row : entry.rows) {
                if (row.deleted(remote)) {
                    lines.add(tr("Deleted", "Usunięta"));
                    continue;
                }
                String category = remote ? row.incomingCategoryName : row.localCategoryName;
                String amount = formatMoney(BigDecimal.valueOf(row.amount(remote), 2));
                String memo = row.memo(remote);
                lines.add(amount + (category.isEmpty() ? "" : " · " + category)
                    + (memo == null || memo.isEmpty() ? "" : " · " + memo));
            }
        }
        return android.text.TextUtils.join("\n", lines);
    }

    private void confirmGroup(String id, Card card) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        fields.setPadding(padding, 0, padding, dp(8));
        String local = card.group.kind == GroupedConflictResolution.Kind.DELETION
            && card.group.entries.get(0).root.deleted(false) ? tr("Keep the deletion", "Zachowaj usunięcie")
            : tr("Use the version on this phone", "Użyj wersji z telefonu");
        String other = card.group.kind == GroupedConflictResolution.Kind.DELETION
            && card.group.entries.get(0).root.deleted(true) ? tr("Keep the deletion", "Zachowaj usunięcie")
            : tr("Use the other device's version", "Użyj wersji z drugiego urządzenia");
        FieldChoices choice = addChoices(fields, tr("Version", "Wersja"), local, other, "version");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(groupTitle(card.group.kind)).setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(tr("Save result", "Zapisz wynik"), (ignored, which) ->
                runRepair(() -> GroupedConflictResolution.resolve(getApplicationContext(), id, card.key,
                    card.group.revision(), remote(choice)),
                    tr("The result was saved on this phone.", "Wynik zapisano na tym telefonie."))).show();
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setEnabled(false);
        choice.group.setOnCheckedChangeListener((group, checked) -> save.setEnabled(selected(choice)));
    }

    private void confirmNote(String id, Card card, String memo, boolean edit) {
        AlertDialog.Builder confirmation = new AlertDialog.Builder(this)
            .setTitle(tr("Resolve note conflict", "Rozwiąż konflikt notatki"))
            .setMessage(tr("This saves one new version of the same transaction. Plus will recheck the saved versions first.",
                "Powstanie jedna nowa wersja tej samej transakcji. Plus najpierw ponownie sprawdzi zachowane wersje."))
            .setNegativeButton(android.R.string.cancel, null);
        EditText input = new EditText(this);
        if (edit) {
            input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
            input.setText(memo);
            input.setHint(tr("Note to keep", "Notatka do zachowania"));
            confirmation.setView(input);
        }
        confirmation.setPositiveButton(tr("Save result", "Zapisz wynik"), (dialog, which) -> {
            String chosen = edit ? input.getText().toString() : memo;
            runRepair(() -> ConflictResolution.resolve(getApplicationContext(), id, card.key, card.hash, chosen, false, false),
                tr("The result was saved on this phone.", "Wynik zapisano na tym telefonie."));
        }).show();
    }

    private void confirmFinancial(String id, Card card, String localMemo, String incomingMemo) {
        ConflictResolution.Options options = card.options;
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(20);
        fields.setPadding(padding, 0, padding, dp(8));
        FieldChoices amounts = options.amountDiffers() ? addChoices(fields, tr("Amount", "Kwota"),
            formatMoney(BigDecimal.valueOf(options.localAmount, 2)),
            formatMoney(BigDecimal.valueOf(options.incomingAmount, 2)), "amount") : null;
        FieldChoices categories = options.categoryDiffers() ? addChoices(fields, tr("Category", "Kategoria"),
            options.localCategory.name, options.incomingCategory.name, "category") : null;
        FieldChoices notes = options.noteDiffers ? addChoices(fields, tr("Note", "Notatka"),
            shownNote(localMemo), shownNote(incomingMemo), "note") : null;
        ScrollView scroll = new ScrollView(this);
        scroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle(tr("Resolve transaction conflict", "Rozwiąż konflikt transakcji"))
            .setMessage(tr("Choose a value for each difference. Plus will save one new version of this transaction.",
                "Wybierz wartość dla każdej różnicy. Plus zapisze jedną nową wersję tej transakcji."))
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(tr("Save result", "Zapisz wynik"), (ignored, which) -> {
                String memo = remote(notes) ? incomingMemo : localMemo;
                runRepair(() -> ConflictResolution.resolve(getApplicationContext(), id, card.key, card.hash, memo,
                    remote(amounts), remote(categories)),
                    tr("The result was saved on this phone.", "Wynik zapisano na tym telefonie."));
            }).show();
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setEnabled(false);
        RadioGroup.OnCheckedChangeListener selection = (group, checked) ->
            save.setEnabled(selected(amounts) && selected(categories) && selected(notes));
        for (FieldChoices field : new FieldChoices[]{amounts, categories, notes}) {
            if (field != null) field.group.setOnCheckedChangeListener(selection);
        }
    }

    private FieldChoices addChoices(LinearLayout fields, String title, String local, String incoming, String field) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextSize(17);
        heading.setPadding(0, dp(9), 0, 0);
        fields.addView(heading);
        RadioGroup group = new RadioGroup(this);
        group.setOrientation(RadioGroup.VERTICAL);
        group.addView(choice(tr("This phone · ", "Ten telefon · ") + local, field + "-local"));
        RadioButton other = choice(tr("Other device · ", "Drugie urządzenie · ") + incoming, field + "-remote");
        group.addView(other);
        fields.addView(group);
        return new FieldChoices(group, other.getId());
    }

    private RadioButton choice(String value, String field) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(value);
        button.setContentDescription("conflict-" + field);
        button.setMinHeight(dp(48));
        return button;
    }

    private static boolean selected(FieldChoices choices) {
        return choices == null || choices.group.getCheckedRadioButtonId() != -1;
    }

    private static boolean remote(FieldChoices choices) {
        return choices != null && choices.group.getCheckedRadioButtonId() == choices.remote;
    }

    private void confirmFile(String id, FolderConflictReview.Entry entry) {
        new AlertDialog.Builder(this).setTitle(tr("Archive identical copy?", "Zarchiwizować identyczną kopię?"))
            .setMessage(tr("Plus will verify both files again. The original stays in place. The copy gets a backup name in its folder.",
                "Plus ponownie porówna oba pliki. Oryginał zostanie na miejscu. Kopia dostanie nazwę zapasową w swoim folderze."))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(tr("Archive", "Archiwizuj"), (dialog, which) -> runRepair(() -> {
                FolderConflictReview.archiveDuplicate(getApplicationContext(), id, entry);
                SyncHealth.retry(getApplicationContext(), id);
            }, tr("The identical copy was archived.", "Zarchiwizowano identyczną kopię.")))
            .show();
    }

    private interface Repair { void run() throws Exception; }

    private void runRepair(Repair operation, String success) {
        SyncHealth.READER.execute(() -> {
            String message;
            try {
                operation.run();
                PlusDiagnostics.record(this, PlusDiagnostics.Event.CONFLICT_REPAIR_OK);
                message = success;
            } catch (Exception failure) {
                PlusDiagnostics.record(this, PlusDiagnostics.Event.CONFLICT_REPAIR_FAILED);
                message = tr("Plus could not confirm the repair. Review the latest state.",
                    "Plus nie mógł potwierdzić naprawy. Sprawdź aktualny stan.");
            }
            String feedback = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, feedback, Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    private void unavailable(String message) {
        if (isFinishing() || isDestroyed()) return;
        content.removeAllViews();
        text(message, 16);
    }

    private static String details(JSONObject row, JSONObject names, String side, boolean local) {
        String money = tr("unknown", "brak danych");
        try {
            BigDecimal amount = new BigDecimal(row.getString("amount"));
            if (local) amount = amount.movePointLeft(2);
            money = formatMoney(amount);
        } catch (Exception absent) { }
        String memo = note(row);
        return tr("Note · ", "Notatka · ") + shownNote(memo) + "\n"
            + tr("Amount · ", "Kwota · ") + money + "\n"
            + displayField(names, side, "Account", tr("Account", "Konto"))
            + displayField(names, side, "Payee", tr("Payee", "Odbiorca"))
            + displayField(names, side, "Category", tr("Category", "Kategoria"))
            + tr("Date · ", "Data · ") + row.optString("date")
            + ((local ? row.optInt("deleted") != 0 : row.optBoolean("isTombstone")) ? tr("\nDeleted", "\nUsunięta") : "");
    }

    private static String note(JSONObject row) {
        return row.isNull("memo") ? "" : row.optString("memo", "");
    }

    private static String shownNote(String value) {
        return value.isEmpty() ? tr("empty", "pusta") : value;
    }

    private static String formatMoney(BigDecimal amount) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.getDefault());
        format.setMinimumFractionDigits(2);
        return format.format(amount);
    }

    private static String displayField(JSONObject names, String side, String field, String label) {
        String value = names.optString(side + field, "");
        return value.isEmpty() ? "" : label + " · " + value + "\n";
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private void text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(7), 0, dp(7));
        content.addView(view);
    }

    private void button(String label, String description, Runnable action) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setContentDescription(description);
        view.setOnClickListener(v -> action.run());
        content.addView(view);
    }

    private static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    private static final class FieldChoices {
        final RadioGroup group;
        final int remote;

        FieldChoices(RadioGroup group, int remote) {
            this.group = group;
            this.remote = remote;
        }
    }

    private static final class Card {
        final String key, hash, chosen, sendState;
        final JSONObject payload;
        final ConflictResolution.Options options;
        final GroupedConflictResolution.Plan group;

        Card(String key, String hash, JSONObject payload, ConflictResolution.Options options,
             GroupedConflictResolution.Plan group, String chosen, String sendState) {
            this.key = key;
            this.hash = hash;
            this.payload = payload;
            this.options = options;
            this.group = group;
            this.chosen = chosen;
            this.sendState = sendState;
        }
    }
}

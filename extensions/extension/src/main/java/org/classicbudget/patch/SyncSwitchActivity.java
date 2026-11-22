package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import org.json.*;

/**
 * "Change sync method" without clearing the app data by hand.
 *
 * The profile lock (BackendSelector) exists because the local database (device ID,
 * knowledge, budget) belongs to one connection. Changing the method therefore means
 * the same as Android's "Clear data", except that Plus settings are kept:
 * - refused while anything is not sent yet (rows with synced=0, unpublished write
 *   journal entries, captured conflicts): that work would be lost;
 * - after confirmation SyncResetActivity (own process) removes the local budget,
 *   connection and original app state, keeps cb-plus-* settings, releases the folder
 *   permission and starts the app again, ready to choose a method.
 * The budget on the server/folder is not touched; the old connection keeps this
 * phone's registration file, as after a reinstall of the original app.
 */
public final class SyncSwitchActivity extends Activity {
    static final String DROPBOX_FOLDER = "dropbox-folder-change";
    private DropboxFolderChoice folderChoice;

    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    static Intent dropboxFolderIntent(Context context) {
        return new Intent(context, SyncSwitchActivity.class).putExtra(DROPBOX_FOLDER, true);
    }

    private boolean folderOnly() {
        return getIntent().getBooleanExtra(DROPBOX_FOLDER, false) || !BackendSelector.alternateBackends(this);
    }

    /** Not sent yet: one line per reason; empty = safe to switch. */
    static java.util.List<String> pending(android.content.Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        android.content.SharedPreferences transfer = c.getSharedPreferences(WritePolicy.DEVICE_TAKEOVER_PREFS, 0);
        if (transfer.contains("pending") || transfer.contains("pendingNew")) {
            out.add(tr("A device transfer is in progress.", "Trwa przejmowanie miejsca urządzenia."));
        }
        for (String name : c.databaseList()) {
            if (!SyncLifecycle.budgetDatabaseName(name)) continue;
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(c.getDatabasePath(name).getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try {
                    int n = 0;
                    for (String table : new String[]{"txnTable", "scheduledTxnTable", "payeeTable", "payeeLocationsTable", "cbBudgetOutbox"}) {
                        try (Cursor columns = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
                            if (!columns.moveToFirst()) continue;
                        }
                        try (Cursor rows = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE synced = 0", null)) {
                            if (rows.moveToFirst()) n += rows.getInt(0);
                        }
                    }
                    if (n > 0) out.add(tr("Changes not synced yet: ", "Niezsynchronizowane zmiany: ") + n);
                    try {
                        int unresolved = ConflictResolution.unresolved(c, name, ConflictStore.active(c, name));
                        if (unresolved > 0) out.add(tr("Unresolved conflicts: ", "Nierozwiązane konflikty: ") + unresolved);
                    } catch (Exception unreadable) {
                        out.add(tr("Saved conflicts could not be checked.", "Nie udało się sprawdzić zapisanych konfliktów."));
                    }
                } finally { db.close(); }
            } catch (RuntimeException unreadable) { out.add(tr("The local budget could not be checked.", "Nie udało się sprawdzić lokalnego budżetu.")); }
        }
        File ledger = new File(c.getFilesDir(), "write-ledger.json");
        if (ledger.exists()) try {
            JSONObject entries = new JSONObject(read(ledger)).optJSONObject("entries"); int n = 0;
            if (entries != null) for (java.util.Iterator<String> k = entries.keys(); k.hasNext();) { JSONObject e = entries.optJSONObject(k.next()); if (e != null && !e.optBoolean("published")) n++; }
            if (n > 0) out.add(tr("Files waiting to be sent: ", "Pliki czekające na wysłanie: ") + n);
        } catch (Exception broken) { out.add(tr("The send queue could not be read.", "Nie udało się odczytać kolejki wysyłania.")); }
        File conflicts = new File(c.getFilesDir(), "conflicts.json");
        if (conflicts.exists() && conflicts.length() > 2) out.add(tr("Unresolved sync conflicts are recorded.", "Są zapisane nierozwiązane konflikty synchronizacji."));
        return out;
    }

    static String read(File f) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        try (InputStream in = new FileInputStream(f)) { while ((n = in.read(b)) != -1) o.write(b, 0, n); }
        return new String(o.toByteArray(), "UTF-8");
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean changeFolder = folderOnly();
        setTitle(changeFolder ? tr("Dropbox folder", "Folder Dropbox") : tr("Change sync method", "Zmień synchronizację"));
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); l.setPadding(pad, pad, pad, pad);
        String chosen = BackendSelector.chosen();
        boolean dropbox = chosen == null || BackendSelector.DROPBOX.equals(chosen);
        if (changeFolder && (!dropbox || PlusEdition.TEST_ONLY)) {
            new AlertDialog.Builder(this).setMessage(tr("Open a production Dropbox profile first.", "Najpierw otwórz produkcyjny profil Dropbox."))
                .setPositiveButton("OK", (d, w) -> finish()).setOnCancelListener(d -> finish()).show();
            return;
        }
        TextView now = new TextView(this); now.setTextSize(17); now.setContentDescription("switch-current");
        String currentFolder = changeFolder ? DropboxProfile.folder() : null;
        now.setText(tr("Current: ", "Obecnie: ") + (changeFolder ? ("/".equals(currentFolder) ? DropboxFolderChoice.defaultLabel() : currentFolder)
            : (chosen == null ? tr("not connected", "brak połączenia") : BackendChooserActivity.label(chosen))));
        l.addView(now);
        TextView info = new TextView(this);
        info.setText(changeFolder ? tr("\nThe app reloads the budgets from the selected location. Your Dropbox sign-in is kept.",
            "\nAplikacja wczyta budżety z wybranego położenia. Logowanie do Dropboxa zostanie.")
            : tr("\nThis phone forgets its local copy of the budget and the current connection, then the app starts again and you choose a new sync method. "
            + "The budget itself on the server or in the folder is not changed. Plus settings (language, display, rules, reports) are kept.\n\n"
            + "Allowed only when everything has been sent.",
            "\nTelefon zapomni lokalną kopię budżetu i obecne połączenie, potem aplikacja uruchomi się od nowa i wybierzesz nowy sposób synchronizacji. "
            + "Sam budżet na serwerze ani w folderze się nie zmienia. Ustawienia Plus (język, wygląd, reguły, raporty) zostają.\n\n"
            + "Możliwe tylko wtedy, gdy wszystko zostało wysłane."));
        l.addView(info);
        String staged = DropboxProfile.pendingFolder(this);
        if (changeFolder) {
            folderChoice = new DropboxFolderChoice(this, staged == null ? currentFolder : staged, staged == null,
                "switch-dropbox-folder", state);
            l.addView(folderChoice);
        }
        final java.util.List<String> pending = pending(this);
        TextView status = new TextView(this); status.setTextSize(15); status.setContentDescription("switch-status");
        StringBuilder s = new StringBuilder("\n");
        if (pending.isEmpty()) s.append(tr("✓ Nothing is waiting to be sent.", "✓ Nic nie czeka na wysłanie."));
        else { s.append(tr("Not possible yet — first sync (open the budget with a connection):", "Jeszcze niemożliwe — najpierw zsynchronizuj (otwórz budżet przy połączeniu):")); for (String p : pending) s.append("\n• ").append(p); }
        status.setText(s); l.addView(status);
        Button go = new Button(this);
        go.setText(changeFolder ? (staged == null ? tr("Change folder", "Zmień folder") : tr("Resume folder change", "Dokończ zmianę folderu"))
            : tr("Change sync method", "Zmień synchronizację"));
        go.setContentDescription("switch-go"); go.setEnabled(pending.isEmpty());
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirm(); } });
        l.addView(go);
        l.setFocusableInTouchMode(true);
        l.requestFocus();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ScrollView scroll = new ScrollView(this); scroll.addView(l); setContentView(scroll);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (folderChoice != null) folderChoice.save(state);
    }

    void confirm() {
        boolean changeFolder = folderOnly();
        String path = changeFolder ? folderChoice.selectedFolder() : null;
        if (changeFolder && (path == null || path.equalsIgnoreCase(DropboxProfile.folder()))) {
            folderChoice.showError(path == null ? tr("Enter a valid Dropbox folder path.", "Wpisz poprawną ścieżkę folderu Dropbox.")
                : tr("This folder is already selected.", "Ten folder jest już wybrany."));
            return;
        }
        String destination = "/".equals(path) ? tr("the default Dropbox location", "domyślnego położenia Dropbox") : path;
        new AlertDialog.Builder(this).setTitle(changeFolder ? tr("Change Dropbox folder?", "Zmienić folder Dropbox?") : tr("Change sync method?", "Zmienić synchronizację?"))
            .setMessage(changeFolder ? tr("The app will restart and load the budgets from ", "Aplikacja uruchomi się ponownie i wczyta budżety z ") + destination
                : tr("The local copy of the budget on this phone will be removed and the app restarts. Continue?", "Lokalna kopia budżetu na tym telefonie zostanie usunięta, a aplikacja uruchomi się ponownie. Kontynuować?"))
            .setPositiveButton(tr("Change", "Zmień"), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                if (!pending(SyncSwitchActivity.this).isEmpty()) { recreate(); return; }   // re-check right before
                if (changeFolder && !DropboxProfile.stageFolder(SyncSwitchActivity.this, path)) {
                    folderChoice.showError(tr("Could not save the folder change.", "Nie udało się zapisać zmiany folderu."));
                    return;
                }
                Intent reset = new Intent(SyncSwitchActivity.this, SyncResetActivity.class).putExtra("pid", android.os.Process.myPid());
                reset.putExtra(DROPBOX_FOLDER, changeFolder);
                reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(reset);
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());   // nothing of the old profile may write after this point
            } })
            .setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }
}

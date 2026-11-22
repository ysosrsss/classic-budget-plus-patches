package org.classicbudget.patch;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Toast;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import org.json.JSONException;
import org.json.JSONObject;

/** Filter controls for the all-accounts search, scoped to the database actually being shown. */
final class TransactionFilterControls {
    private final ReportTransactions list;
    private final ReportsActivity activity;
    private final SharedPreferences prefs;
    private final String budget;
    TransactionFilter current = new TransactionFilter();
    private Button criteria;

    TransactionFilterControls(ReportTransactions list, String budget) {
        this.list = list;
        this.activity = list.a;
        this.budget = budget;
        this.prefs = activity.getSharedPreferences("cb-plus-transaction-filters", 0);
    }

    private String tr(String en, String pl) {
        return ReportsActivity.tr(en, pl);
    }

    View controls() {
        LinearLayout row = new LinearLayout(activity);
        criteria = activity.chip("", "transaction-filter-criteria", view -> editCriteria());
        row.addView(criteria, new LinearLayout.LayoutParams(0, -2, 1));
        Button saved = activity.chip(tr("Saved filters", "Zapisane filtry"), "transaction-saved-filters", view -> saved());
        row.addView(saved, new LinearLayout.LayoutParams(0, -2, 1));
        Button select = activity.chip(tr("Select", "Zaznacz"), "transaction-select", view -> list.toggleSelection());
        row.addView(select, new LinearLayout.LayoutParams(0, -2, 1));
        return row;
    }

    void refresh() {
        int active = (current.first.isEmpty() && current.last.isEmpty() ? 0 : 1)
            + (current.status == 0 ? 0 : 1) + (current.field == 0 ? 0 : 1) + (current.needsCategory ? 1 : 0)
            + (current.flag == 0 ? 0 : 1) + (current.approval == 0 ? 0 : 1) + (current.comparison == 0 ? 0 : 1);
        criteria.setText(tr("Filters", "Filtry") + (active == 0 ? "" : " · " + active));
        list.search.setHint(current.field == 0 ? tr("Search transactions", "Szukaj transakcji")
            : tr("Search in field · ", "Szukaj w polu · ") + TransactionFilter.fields()[current.field]);
    }

    private void editCriteria() {
        TransactionFilter draft = snapshot();
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(activity.px(20), activity.px(8), activity.px(20), activity.px(12));
        body.addView(activity.text(tr("Search field", "Pole wyszukiwania"), 14, ReportsActivity.TEXT));
        Spinner field = spinner(TransactionFilter.fields(), draft.field, "transaction-search-field");
        body.addView(field);
        body.addView(activity.text(tr("Clearance status", "Status rozliczenia"), 14, ReportsActivity.TEXT));
        Spinner status = spinner(TransactionFilter.statuses(), draft.status, "transaction-clearance");
        body.addView(status);
        Button first = dateButton(true, draft);
        Button last = dateButton(false, draft);
        body.addView(first);
        body.addView(last);
        Button clear = activity.chip(tr("Clear dates", "Wyczyść daty"), "transaction-clear-dates", v -> {
            draft.first = "";
            draft.last = "";
            dateLabel(first, true, draft.first);
            dateLabel(last, false, draft.last);
        });
        body.addView(clear);
        CheckBox uncategorized = new CheckBox(activity);
        uncategorized.setText(tr("Needs a category", "Bez kategorii"));
        uncategorized.setContentDescription("transaction-needs-category");
        uncategorized.setChecked(draft.needsCategory);
        body.addView(uncategorized);
        Spinner approval = spinner(TransactionFilter.approvals(), draft.approval, "transaction-approval");
        body.addView(approval);
        Spinner flag = spinner(TransactionFilter.flags(), draft.flag, "transaction-flag");
        body.addView(flag);
        Spinner comparison = spinner(TransactionFilter.comparisons(), draft.comparison, "transaction-amount-comparison");
        body.addView(comparison);
        EditText amount = new EditText(activity);
        amount.setSingleLine(true);
        amount.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amount.setContentDescription("transaction-amount-threshold");
        amount.setHint(tr("Amount without sign", "Kwota bez znaku"));
        if (draft.comparison != 0) amount.setText(BigDecimal.valueOf(draft.threshold, 2).toPlainString());
        body.addView(amount);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(tr("Transaction filters", "Filtry transakcji"))
            .setView(scroll).setNegativeButton(tr("Cancel", "Anuluj"), null)
            .setPositiveButton(tr("Apply", "Zastosuj"), null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!draft.first.isEmpty() && !draft.last.isEmpty() && draft.first.compareTo(draft.last) > 0) {
                Toast.makeText(activity, tr("The start date must be before or on the end date.",
                    "Data początkowa musi przypadać przed datą końcową lub tego samego dnia."), Toast.LENGTH_LONG).show();
                return;
            }
            draft.field = field.getSelectedItemPosition();
            draft.status = status.getSelectedItemPosition();
            draft.needsCategory = uncategorized.isChecked();
            draft.approval = approval.getSelectedItemPosition();
            draft.flag = flag.getSelectedItemPosition();
            draft.comparison = comparison.getSelectedItemPosition();
            if (draft.comparison != 0) {
                try {
                    String value = amount.getText().toString().trim().replace(',', '.');
                    if (!value.matches("[0-9]+(\\.[0-9]{1,2})?")) throw new NumberFormatException();
                    draft.threshold = new BigDecimal(value).movePointRight(2).longValueExact();
                } catch (ArithmeticException | NumberFormatException error) {
                    amount.setError(tr("Enter an amount with up to two decimal places", "Podaj kwotę z maksymalnie dwoma miejscami po przecinku"));
                    amount.requestFocus();
                    return;
                }
            }
            dialog.dismiss();
            list.applyFilter(draft);
        });
    }

    private Spinner spinner(String[] labels, int selected, String description) {
        Spinner spinner = new Spinner(activity, Spinner.MODE_DIALOG);
        spinner.setContentDescription(description);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
        spinner.setMinimumHeight(activity.px(44));
        return spinner;
    }

    private Button dateButton(boolean start, TransactionFilter draft) {
        Button button = activity.chip("", start ? "transaction-date-from" : "transaction-date-to", null);
        dateLabel(button, start, start ? draft.first : draft.last);
        button.setOnClickListener(view -> {
            Calendar today = Calendar.getInstance();
            int year = today.get(Calendar.YEAR);
            int month = today.get(Calendar.MONTH);
            int day = today.get(Calendar.DAY_OF_MONTH);
            String value = start ? draft.first : draft.last;
            if (!value.isEmpty()) {
                year = Integer.parseInt(value.substring(0, 4));
                month = Integer.parseInt(value.substring(5, 7)) - 1;
                day = Integer.parseInt(value.substring(8, 10));
            }
            new DatePickerDialog(activity, (picker, y, m, d) -> {
                String selected = String.format(Locale.ROOT, "%04d-%02d-%02d", y, m + 1, d);
                if (start) draft.first = selected;
                else draft.last = selected;
                dateLabel(button, start, selected);
            }, year, month, day).show();
        });
        return button;
    }

    private void dateLabel(Button button, boolean start, String value) {
        String label = start ? tr("Date from", "Data od") : tr("Date through", "Data do");
        button.setText(label + " · " + (value.isEmpty() ? tr("Any", "Dowolna") : value));
    }

    private TransactionFilter snapshot() {
        current.query = list.search.getText().toString();
        current.account = list.account;
        current.payee = list.payee;
        current.sign = list.sign;
        current.order = list.order;
        try {
            return TransactionFilter.decode(current.encode());
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private JSONObject stored() {
        try {
            return new JSONObject(prefs.getString(budget, "{}"));
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private void saved() {
        JSONObject values = stored();
        List<String> names = new ArrayList<>();
        Iterator<String> keys = values.keys();
        while (keys.hasNext()) names.add(keys.next());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        List<String> choices = new ArrayList<>();
        choices.add(tr("Needs a category", "Bez kategorii"));
        choices.add(tr("Unreconciled", "Nieuzgodnione"));
        choices.addAll(names);
        choices.add(tr("Save current filter…", "Zapisz bieżący filtr…"));
        if (!names.isEmpty()) choices.add(tr("Delete a saved filter…", "Usuń zapisany filtr…"));
        new AlertDialog.Builder(activity).setTitle(tr("Saved filters", "Zapisane filtry"))
            .setItems(choices.toArray(new String[0]), (dialog, which) -> {
                if (which < 2) {
                    TransactionFilter shortcut = new TransactionFilter();
                    if (which == 0) shortcut.needsCategory = true;
                    else shortcut.status = TransactionFilter.UNRECONCILED;
                    list.applyFilter(shortcut);
                } else if (which < names.size() + 2) {
                    JSONObject value = values.optJSONObject(names.get(which - 2));
                    if (value != null) list.applyFilter(TransactionFilter.decode(value));
                } else if (which == names.size() + 2) {
                    save();
                } else {
                    delete(names);
                }
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    private void save() {
        TransactionFilter filter = snapshot();
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint(tr("Filter name", "Nazwa filtra"));
        name.setContentDescription("transaction-filter-name");
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(tr("Save filter", "Zapisz filtr"))
            .setView(name).setNegativeButton(tr("Cancel", "Anuluj"), null)
            .setPositiveButton(tr("Save", "Zapisz"), null).create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String key = name.getText().toString().trim();
            if (key.isEmpty()) {
                name.setError(tr("Enter a name", "Podaj nazwę"));
                return;
            }
            JSONObject values = stored();
            if (values.has(key)) {
                new AlertDialog.Builder(activity).setTitle(tr("Replace saved filter?", "Zastąpić zapisany filtr?"))
                    .setMessage(key).setNegativeButton(tr("Cancel", "Anuluj"), null)
                    .setPositiveButton(tr("Replace", "Zastąp"), (confirm, ignored) -> store(values, key, filter, dialog)).show();
            } else {
                store(values, key, filter, dialog);
            }
        });
    }

    private void store(JSONObject values, String key, TransactionFilter filter, AlertDialog dialog) {
        try {
            values.put(key, filter.encode());
            prefs.edit().putString(budget, values.toString()).apply();
            dialog.dismiss();
        } catch (JSONException error) {
            Toast.makeText(activity, tr("Could not save filter", "Nie udało się zapisać filtra"), Toast.LENGTH_SHORT).show();
        }
    }

    private void delete(List<String> names) {
        new AlertDialog.Builder(activity).setTitle(tr("Delete a saved filter", "Usuń zapisany filtr"))
            .setItems(names.toArray(new String[0]), (picker, index) -> {
                String name = names.get(index);
                new AlertDialog.Builder(activity).setTitle(tr("Delete saved filter?", "Usunąć zapisany filtr?"))
                    .setMessage(name).setNegativeButton(tr("Cancel", "Anuluj"), null)
                    .setPositiveButton(tr("Delete", "Usuń"), (confirm, ignored) -> {
                        JSONObject values = stored();
                        values.remove(name);
                        prefs.edit().putString(budget, values.toString()).apply();
                    }).show();
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }
}

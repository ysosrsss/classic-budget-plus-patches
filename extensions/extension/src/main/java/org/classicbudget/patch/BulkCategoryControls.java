package org.classicbudget.patch;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Category picker and explicit preview over the selection in the transaction search. */
final class BulkCategoryControls {
    private final ReportTransactions list;
    private final ReportsActivity activity;
    private final String budget;
    private final Button button;
    private boolean busy;

    BulkCategoryControls(ReportTransactions list, String budget) {
        this.list = list;
        activity = list.a;
        this.budget = budget;
        button = activity.chip(tr("Change category", "Zmień kategorię"), "transaction-bulk-category", v -> choose());
        button.setMaxLines(2);
    }

    private String tr(String en, String pl) { return ReportsActivity.tr(en, pl); }

    Button button() { return button; }

    void update() { button.setEnabled(!busy && !list.selected.isEmpty()); }

    private void choose() {
        Map<String, String> selection = list.selectionRevisions();
        if (selection.isEmpty() || busy) return;
        List<BulkCategory.Category> categories = BulkCategory.categories(activity.data.db);
        if (categories.isEmpty()) {
            Toast.makeText(activity, tr("No available categories", "Brak dostępnych kategorii"), Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(activity.px(12), 0, activity.px(12), 0);
        body.setFocusableInTouchMode(true);
        EditText query = new EditText(activity);
        query.setSingleLine(true);
        query.setHint(tr("Find a category", "Znajdź kategorię"));
        query.setContentDescription("transaction-bulk-category-search");
        body.addView(query);
        ArrayAdapter<String> labels = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        List<BulkCategory.Category> visible = new ArrayList<>();
        ListView picker = new ListView(activity);
        picker.setContentDescription("transaction-bulk-category-options");
        picker.setAdapter(labels);
        int height = Math.min(activity.px(360),
            Math.max(activity.px(120), activity.getResources().getDisplayMetrics().heightPixels - activity.px(240)));
        body.addView(picker, new LinearLayout.LayoutParams(-1, height));
        Runnable filter = () -> {
            labels.setNotifyOnChange(false);
            labels.clear();
            visible.clear();
            for (BulkCategory.Category category : categories) {
                if (!SearchText.matches(category.label, query.getText())) continue;
                visible.add(category);
                labels.add(category.label);
            }
            labels.notifyDataSetChanged();
        };
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) { }
        });
        filter.run();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(tr("Change category", "Zmień kategorię"))
            .setView(body).setNegativeButton(tr("Cancel", "Anuluj"), null).create();
        picker.setOnItemClickListener((parent, view, position, id) -> {
            BulkCategory.Category category = visible.get(position);
            dialog.dismiss();
            prepare(selection, category);
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        body.requestFocus();
    }

    private void prepare(Map<String, String> selection, BulkCategory.Category category) {
        launch(() -> {
            BulkCategory.Plan plan = BulkCategory.prepare(activity.getApplicationContext(), budget, selection, category);
            return () -> confirm(plan);
        });
    }

    private void confirm(BulkCategory.Plan plan) {
        String message = plan.category.label + "\n\n"
            + tr("To change: ", "Do zmiany: ") + plan.changes.size() + "\n"
            + tr("Already in this category: ", "Już w tej kategorii: ") + plan.unchanged + "\n"
            + tr("Skipped: ", "Pominięte: ") + plan.excluded;
        if (plan.excluded > 0) message += "\n" + tr("This action skips splits, transfers, reconciled and tracking transactions, and special categories.",
            "Ta akcja pomija podziały, przelewy, wpisy uzgodnione i pozabudżetowe oraz kategorie specjalne.");
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(tr("Review category change", "Sprawdź zmianę kategorii")).setMessage(message);
        if (plan.changes.isEmpty()) {
            dialog.setPositiveButton(tr("Close", "Zamknij"), null);
        } else {
            dialog.setNegativeButton(tr("Cancel", "Anuluj"), null)
                .setPositiveButton(tr("Apply change", "Zastosuj zmianę"), (review, which) -> save(plan));
        }
        dialog.show();
    }

    private void save(BulkCategory.Plan plan) {
        launch(() -> {
            int changed = BulkCategory.apply(activity.getApplicationContext(), budget, plan);
            return () -> {
                if (list.selecting) list.toggleSelection();
                list.reload(ReportTable.allTransactions(activity.data));
                activity.render();
                Toast.makeText(activity, tr("Transactions updated: ", "Zmieniono transakcje: ") + changed, Toast.LENGTH_LONG).show();
            };
        });
    }

    private void launch(java.util.concurrent.Callable<Runnable> operation) {
        busy = true;
        update();
        new Thread(() -> {
            Runnable result;
            try {
                result = operation.call();
            } catch (Exception error) {
                result = () -> new AlertDialog.Builder(activity).setTitle(tr("Category change unavailable", "Zmiana kategorii niedostępna"))
                    .setMessage(tr("The budget may have changed. Refresh the results and select the transactions again.",
                        "Budżet mógł się zmienić. Odśwież wyniki i ponownie zaznacz transakcje."))
                    .setPositiveButton(tr("Refresh results", "Odśwież wyniki"), (dialog, which) ->
                        list.reload(ReportTable.allTransactions(activity.data))).show();
            }
            Runnable done = result;
            activity.runOnUiThread(() -> {
                busy = false;
                update();
                if (!activity.isFinishing() && !activity.isDestroyed() && button.isShown()) done.run();
            });
        }, "bulk-category").start();
    }
}

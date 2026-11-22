package org.classicbudget.patch;

import android.app.AlertDialog;
import android.content.Intent;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Actions on one monthly category cell, with the existing BudgetStore write path. */
final class BudgetCellActions {
    interface Chosen { void accept(BudgetStore.Row row); }
    private final BudgetActivity activity;
    private final BudgetGoals goals;

    BudgetCellActions(BudgetActivity activity, BudgetGoals goals) {
        this.activity = activity;
        this.goals = goals;
    }

    private static String tr(String en, String pl) { return BudgetActivity.tr(en, pl); }

    void show(BudgetStore.Row row) {
        String[] labels = {
            tr("Budget amount", "Kwota budżetu"),
            tr("Amount suggestions and calculator", "Podpowiedzi kwot i kalkulator"),
            tr("Cell note", "Notatka komórki"),
            tr("Overspending", "Obsługa przekroczenia"),
            tr("Move money to another category", "Przenieś kwotę do innej kategorii"),
            tr("Category goal", "Cel kategorii"),
            tr("Category activity", "Transakcje kategorii")
        };
        Runnable[] actions = {() -> activity.edit(row), () -> suggestions(row), () -> note(row),
            () -> overspending(row), () -> move(row), () -> goals.edit(row), () -> activity(row)};
        new AlertDialog.Builder(activity).setTitle(row.group + " · " + row.name)
            .setItems(labels, (d, which) -> actions[which].run())
            .setNegativeButton(tr("Close", "Zamknij"), null).show();
    }

    void activity(BudgetStore.Row row) {
        Intent intent = new Intent(activity, ReportsActivity.class);
        intent.putExtra("budget-activity-category", row.categoryGuid);
        intent.putExtra("budget-activity-month", activity.month);
        intent.putExtra("budget-activity-title", row.name + " · " + activity.monthLabel(activity.month));
        activity.startActivity(intent);
    }

    private boolean canEdit(BudgetStore.Row row) {
        String reason = activity.store.blocked(activity.month, row);
        if (reason == null) return true;
        message(row.name, reason);
        return false;
    }

    private void message(String title, String details) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(details).setPositiveButton("OK", null).show();
    }

    private void note(BudgetStore.Row row) {
        if (!canEdit(row)) return;
        BudgetNotes.Note current = activity.store.note(row);
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setText(current.text);
        input.setContentDescription("budget-cell-note");
        new AlertDialog.Builder(activity).setTitle(tr("Cell note", "Notatka komórki") + " · " + row.name)
            .setView(input).setNegativeButton(tr("Cancel", "Anuluj"), null)
            .setPositiveButton(tr("Save", "Zapisz"), (d, w) -> {
                String text = input.getText().toString();
                activity.save(Collections.singletonList(BudgetStore.Change.note(row, text.isEmpty() ? null : text)));
            }).show();
    }

    void overspending(BudgetStore.Row row) {
        if (!canEdit(row)) return;
        String[] labels = {tr("Deduct from next month's To be budgeted", "Odejmij od kwoty do zabudżetowania w następnym miesiącu"),
            tr("Carry the negative balance in this category", "Przenieś ujemne saldo tej kategorii")};
        int[] selected = {row.confined ? 1 : 0};
        new AlertDialog.Builder(activity).setTitle(row.name + " · " + tr("Overspending", "Obsługa przekroczenia"))
            .setSingleChoiceItems(labels, selected[0], (d, which) -> selected[0] = which)
            .setNegativeButton(tr("Cancel", "Anuluj"), null)
            .setPositiveButton(tr("Save", "Zapisz"), (d, w) ->
                activity.save(Collections.singletonList(BudgetStore.Change.overspending(row, selected[0] == 1)))).show();
    }

    private void move(BudgetStore.Row source) {
        if (!canEdit(source)) return;
        if (source.balance <= 0) {
            message(source.name, tr("Choose a source category with money available.", "Wybierz kategorię źródłową z dodatnim saldem."));
            return;
        }
        pick(activity, activity.rows, source, tr("Move to category", "Przenieś do kategorii"), target -> {
            if (!canEdit(target)) return;
            EditText input = amount(activity, "budget-move-amount", null);
            input.setHint(tr("Amount", "Kwota"));
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(source.name + " → " + target.name)
                .setMessage(tr("Available to move: ", "Dostępne do przeniesienia: ") + activity.shown(source.balance))
                .setView(input).setNegativeButton(tr("Cancel", "Anuluj"), null).setPositiveButton(tr("Move", "Przenieś"), null).show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    List<BudgetStore.Change> changes = BudgetStore.move(source, target, BudgetActivity.parse(input.getText().toString()));
                    dialog.dismiss();
                    activity.save(changes);
                } catch (IllegalArgumentException | ArithmeticException invalid) {
                    input.setError(tr("Enter a positive amount within the available balance.",
                        "Podaj dodatnią kwotę w granicach dostępnego salda."));
                }
            });
        });
    }

    private void suggestions(BudgetStore.Row row) {
        List<BudgetSuggestions.Option> options = BudgetSuggestions.options(activity.store.db, activity.month, row);
        List<String> names = new ArrayList<>();
        for (BudgetSuggestions.Option option : options) names.add(option.label + " · " + activity.shown(option.amount));
        names.add(tr("Calculator", "Kalkulator"));
        new AlertDialog.Builder(activity).setTitle(row.name + " · " + tr("Amount suggestions", "Podpowiedzi kwoty"))
            .setItems(names.toArray(new String[0]), (d, which) -> {
                if (which == options.size()) calculator(row);
                else activity.edit(row, options.get(which).amount);
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    private void calculator(BudgetStore.Row row) {
        String[] operators = {"+", "−", "×", "÷"};
        new AlertDialog.Builder(activity).setTitle(tr("Calculator", "Kalkulator"))
            .setItems(operators, (d, operation) -> {
                EditText operand = amount(activity, "budget-calculator-operand", null);
                AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(activity.shown(row.budgeted) + " " + operators[operation]).setView(operand)
                    .setNegativeButton(tr("Cancel", "Anuluj"), null).setPositiveButton(tr("Calculate", "Oblicz"), null).show();
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        BigDecimal left = BigDecimal.valueOf(row.budgeted, 2);
                        BigDecimal right = BigDecimal.valueOf(BudgetActivity.parse(operand.getText().toString()), 2);
                        BigDecimal result;
                        switch (operation) {
                            case 0: result = left.add(right); break;
                            case 1: result = left.subtract(right); break;
                            case 2: result = left.multiply(right); break;
                            default: result = left.divide(right, 2, RoundingMode.HALF_UP);
                        }
                        long amount = result.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
                        dialog.dismiss();
                        activity.edit(row, amount);
                    } catch (IllegalArgumentException | ArithmeticException invalid) {
                        operand.setError(tr("Invalid calculation", "Nie można wykonać obliczenia"));
                    }
                });
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    static EditText amount(BudgetActivity activity, String id, Long amount) {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        if (amount != null) input.setText(BigDecimal.valueOf(amount, 2).toPlainString());
        input.setSelectAllOnFocus(true);
        input.setContentDescription(id);
        return input;
    }

    static void pick(BudgetActivity activity, List<BudgetStore.Row> rows, BudgetStore.Row excluded, String title, Chosen chosen) {
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setFocusableInTouchMode(true);
        EditText search = new EditText(activity);
        search.setSingleLine(true);
        search.setHint(tr("Find a category", "Znajdź kategorię"));
        search.setContentDescription("budget-category-search");
        body.addView(search);
        ListView list = new ListView(activity);
        ArrayAdapter<String> labels = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1);
        List<BudgetStore.Row> visible = new ArrayList<>();
        list.setAdapter(labels);
        int height = Math.min(activity.px(350), Math.max(activity.px(140),
            activity.getResources().getDisplayMetrics().heightPixels - activity.px(220)));
        body.addView(list, new LinearLayout.LayoutParams(-1, height));
        Runnable filter = () -> {
            labels.setNotifyOnChange(false);
            labels.clear();
            visible.clear();
            SearchText.Query query = new SearchText.Query(search.getText());
            for (BudgetStore.Row row : rows) {
                String label = row.group + " / " + row.name;
                if (row == excluded || !query.matches(label)) continue;
                visible.add(row);
                labels.add(label);
            }
            labels.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { filter.run(); }
            public void afterTextChanged(Editable s) { }
        });
        filter.run();
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(body)
            .setNegativeButton(tr("Cancel", "Anuluj"), null).create();
        list.setOnItemClickListener((p, v, index, id) -> {
            BudgetStore.Row row = visible.get(index);
            dialog.dismiss();
            chosen.accept(row);
        });
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        body.requestFocus();
    }
}

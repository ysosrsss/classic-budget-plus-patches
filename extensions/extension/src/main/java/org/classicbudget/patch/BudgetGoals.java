package org.classicbudget.patch;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Local planning targets. Progress uses assignments and the balance brought into the month. */
final class BudgetGoals {
    private final BudgetActivity activity;
    private final SharedPreferences prefs;

    BudgetGoals(BudgetActivity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("cb-plus-budget-goals", 0);
    }

    private String key(BudgetStore.Row row) {
        return activity.store.budgetFolder + "/" + activity.store.dataFolder + "/" + row.categoryGuid;
    }

    private static String tr(String en, String pl) { return BudgetActivity.tr(en, pl); }

    private static final class Goal {
        final long amount;
        final boolean refill;
        Goal(long amount, boolean refill) {
            this.amount = amount;
            this.refill = refill;
        }
    }

    private Goal goal(BudgetStore.Row row) {
        String stored = prefs.getString(key(row), "");
        if (stored.length() < 2) return null;
        try {
            long amount = Long.parseLong(stored.substring(1));
            return amount > 0 ? new Goal(amount, stored.charAt(0) == 'R') : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    static long remaining(long amount, boolean refill, long assigned, long opening) {
        BigDecimal result = BigDecimal.valueOf(amount).subtract(BigDecimal.valueOf(assigned));
        if (refill) result = result.subtract(BigDecimal.valueOf(opening));
        return result.max(BigDecimal.ZERO).min(BigDecimal.valueOf(Long.MAX_VALUE)).longValueExact();
    }

    void show() {
        List<BudgetStore.Row> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BudgetStore.Row row : activity.rows) {
            Goal goal = goal(row);
            if (goal == null) continue;
            rows.add(row);
            long left = remaining(goal.amount, goal.refill, row.budgeted, row.opening);
            labels.add(row.name + "\n" + (left == 0 ? tr("Funded", "Cel osiągnięty")
                : tr("Still needed: ", "Brakuje: ") + activity.shown(left)));
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(tr("Category goals", "Cele kategorii") + " · " + activity.monthLabel(activity.month))
            .setNegativeButton(tr("Close", "Zamknij"), null);
        if (rows.isEmpty()) dialog.setMessage(tr("Choose a category to set its goal.", "Wybierz kategorię, aby ustawić jej cel."));
        else dialog.setItems(labels.toArray(new String[0]), (d, i) -> edit(rows.get(i)));
        dialog.setNeutralButton(tr("Choose category", "Wybierz kategorię"), (d, i) ->
            BudgetCellActions.pick(activity, activity.rows, null, tr("Choose category", "Wybierz kategorię"), this::edit));
        dialog.show();
    }

    void edit(BudgetStore.Row row) {
        Goal stored = goal(row);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(activity.px(16), 0, activity.px(16), 0);
        RadioGroup modes = new RadioGroup(activity);
        RadioButton fixed = new RadioButton(activity);
        fixed.setId(android.view.View.generateViewId());
        fixed.setText(tr("Set aside each month", "Odłóż co miesiąc"));
        RadioButton refill = new RadioButton(activity);
        refill.setId(android.view.View.generateViewId());
        refill.setText(tr("Refill using the opening balance", "Uzupełnij do kwoty, uwzględniając saldo z poprzedniego miesiąca"));
        modes.addView(fixed);
        modes.addView(refill);
        modes.check(stored != null && stored.refill ? refill.getId() : fixed.getId());
        layout.addView(modes);
        EditText amount = BudgetCellActions.amount(activity, "budget-goal-amount", stored == null ? null : stored.amount);
        amount.setHint(tr("Target amount", "Kwota celu"));
        layout.addView(amount);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(row.name + " · " + tr("Goal", "Cel"))
            .setView(layout).setNegativeButton(tr("Cancel", "Anuluj"), null)
            .setPositiveButton(tr("Save", "Zapisz"), null);
        if (stored != null) builder.setNeutralButton(tr("Remove goal", "Usuń cel"), (d, i) ->
            prefs.edit().remove(key(row)).apply());
        AlertDialog dialog = builder.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                long cents = BudgetActivity.parse(amount.getText().toString());
                if (cents <= 0) throw new NumberFormatException();
                String mode = modes.getCheckedRadioButtonId() == refill.getId() ? "R" : "M";
                prefs.edit().putString(key(row), mode + cents).apply();
                Toast.makeText(activity, tr("Goal saved on this phone", "Cel zapisany na tym telefonie"), Toast.LENGTH_SHORT).show();
                dialog.dismiss();
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                amount.setError(tr("Enter a positive amount", "Podaj dodatnią kwotę"));
            }
        });
    }
}

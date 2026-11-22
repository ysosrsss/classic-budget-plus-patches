package org.classicbudget.patch;

import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.List;
import java.util.Locale;

/** Compact optional report cards; no writes or budget-editor adapter. */
final class ReportExtensionViews {
    final ReportsActivity a;

    ReportExtensionViews(ReportsActivity activity) { a = activity; }

    void note(String text, String description) {
        TextView view = a.text(text, 13, ReportsActivity.MUTED);
        view.setContentDescription(description);
        view.setPadding(a.px(8), a.px(6), a.px(8), a.px(10));
        a.root.addView(view);
    }

    void budget() {
        String month = a.prefs.getString("analysis.month", ReportData.thisMonth());
        LinearLayout controls = new LinearLayout(a);
        for (int shift : new int[]{-1, 0, 1}) {
            String title = shift < 0 ? "‹" : shift > 0 ? "›" : ReportsActivity.label(month);
            Button button = a.chip(title, "analysis-month:" + shift, view -> {
                a.prefs.edit().putString("analysis.month", shift == 0 ? ReportData.thisMonth() : ReportData.shift(month, shift)).apply();
                a.render();
            });
            controls.addView(button, new LinearLayout.LayoutParams(0, -2, shift == 0 ? 3 : 1));
        }
        a.root.addView(controls);
        note(ReportsActivity.tr("All budget accounts. Remaining includes category carryover; tap the month to return to today.",
            "Wszystkie konta budżetowe. Pozostało uwzględnia przenoszenie sald kategorii; dotknij miesiąca, aby wrócić do bieżącego."), "analysis-scope");
        BudgetMath.Result math = BudgetMath.compute(a.data.db, month);
        note(ReportsActivity.tr("Available to budget: ", "Dostępne do budżetowania: ") + a.cash(math.available), "analysis-available");
        List<ReportExtensions.BudgetRow> rows = ReportExtensions.budget(a.data, month);
        long budgeted = 0, spent = 0, balance = 0;
        for (ReportExtensions.BudgetRow row : rows) {
            budgeted += row.budgeted;
            spent += row.spent;
            balance += row.balance;
        }
        card(ReportsActivity.tr("All categories", "Wszystkie kategorie"), budgetLine(budgeted, spent, balance), "analysis-total", null);
        String group = null;
        for (ReportExtensions.BudgetRow row : rows) {
            if (row.budgeted == 0 && row.spent == 0 && row.balance == 0 && row.prior == 0) continue;
            if (!row.group.equals(group)) {
                note(row.group, "analysis-group");
                group = row.group;
            }
            card(row.name, budgetLine(row.budgeted, row.spent, row.balance)
                + "\n" + ReportsActivity.tr("Carried in: ", "Przeniesiono: ") + a.cash(row.prior), "analysis-category:" + row.id, null);
        }
    }

    String budgetLine(long budgeted, long spent, long balance) {
        return ReportsActivity.tr("Budgeted ", "Budżetowane ") + a.cash(budgeted)
            + ReportsActivity.tr(" · Spent ", " · Wydane ") + a.cash(spent)
            + ReportsActivity.tr("\nRemaining ", "\nPozostało ") + a.cash(balance);
    }

    void comparison(ReportData.Filter filter) {
        ReportData.Filter previous = ReportExtensions.previous(filter);
        note(ReportsActivity.tr("Compared with ", "Porównanie z ") + ReportsActivity.label(previous.first)
            + " – " + ReportsActivity.label(previous.last), "comparison-period");
        if (filter.last.compareTo(ReportData.thisMonth()) >= 0) {
            note(ReportsActivity.tr("The current month is incomplete. These are calendar-month totals, not equal elapsed days.",
                "Bieżący miesiąc jest niepełny. To sumy miesięcy kalendarzowych, a nie równych odcinków dni."), "comparison-incomplete");
        }
        List<ReportExtensions.ComparisonRow> rows = ReportExtensions.comparison(a.data, filter);
        long current = 0, old = 0;
        for (ReportExtensions.ComparisonRow row : rows) {
            current += row.current;
            old += row.previous;
        }
        card(ReportsActivity.tr("Total spending", "Wydatki razem"), comparisonLine(current, old), "comparison-total", null);
        for (ReportExtensions.ComparisonRow row : rows) {
            card(row.name, comparisonLine(row.current, row.previous), "comparison-category:" + row.category, () -> {
                String[] periods = {ReportsActivity.tr("Selected period", "Wybrany okres"), ReportsActivity.tr("Previous period", "Poprzedni okres")};
                ReportChoiceDialog.show(a, row.name, periods, -1, index -> {
                    ReportTable.Row scope = ReportTable.row(row.key, row.name, "expense", 1);
                    scope.category = row.category;
                    new DesktopReports(a).details(index == 0 ? filter : previous, scope, null);
                });
            });
        }
        if (rows.isEmpty()) note(ReportsActivity.tr("No spending in either period.", "Brak wydatków w obu okresach."), "comparison-empty");
    }

    String comparisonLine(long current, long previous) {
        String percent = a.hide ? "•••" : previous <= 0 ? "—" : String.format(Locale.getDefault(), "%+.1f%%", 100.0 * (current - previous) / previous);
        return ReportsActivity.tr("Selected ", "Wybrany ") + a.cash(current)
            + ReportsActivity.tr(" · Previous ", " · Poprzedni ") + a.cash(previous)
            + ReportsActivity.tr("\nChange ", "\nZmiana ") + a.cash(current - previous) + " (" + percent + ")";
    }

    void card(String title, String body, String description, Runnable click) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(a.px(12), a.px(10), a.px(12), a.px(10));
        box.setBackground(a.controlBackground(0xffeff5f7));
        box.setContentDescription(description);
        TextView heading = a.text(title, 15, ReportsActivity.ACCENT);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        box.addView(heading);
        box.addView(a.text(body, 14, ReportsActivity.TEXT));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.bottomMargin = a.px(6);
        a.root.addView(box, layout);
        if (click != null) {
            box.setFocusable(true);
            box.setOnClickListener(view -> click.run());
        }
    }
}

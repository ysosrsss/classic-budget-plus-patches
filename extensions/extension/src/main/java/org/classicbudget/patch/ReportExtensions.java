package org.classicbudget.patch;

import android.database.Cursor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Optional, read-only reports. Budget balances use the existing YNAB4 rollover engine. */
public final class ReportExtensions {
    public static final class BudgetRow {
        public long id, budgeted, spent, balance, prior;
        public String name, group;
    }

    public static List<BudgetRow> budget(ReportData data, String month) {
        BudgetMath.Result math = BudgetMath.compute(data.db, month);
        Map<Long, BudgetRow> rows = new LinkedHashMap<Long, BudgetRow>();
        String sql = "SELECT c._id,c.name,m.name,COALESCE(b.amount,0)"
            + " FROM categoryTable c JOIN masterCategoryTable m ON m._id=c.masterCategoryId"
            + " LEFT JOIN monthlyBudgetTable b ON b.categoryId=c._id AND b.deleted=0 AND b.date=?"
            + " WHERE c.deleted=0 AND m.deleted=0 AND m.guid!='MasterCategory/__Internal__'"
            + " ORDER BY CASE WHEN m.guid='MasterCategory/__Hidden__' THEN 1 ELSE 0 END,m.sequence,c.sequence";
        try (Cursor cursor = data.db.rawQuery(sql, new String[]{month + "-01"})) {
            while (cursor.moveToNext()) {
                BudgetRow row = new BudgetRow();
                row.id = cursor.getLong(0);
                row.name = ReportTable.localized(cursor.getString(1));
                row.group = ReportTable.localized(cursor.getString(2));
                row.budgeted = cursor.getLong(3);
                rows.put(row.id, row);
            }
        }
        BudgetRow uncategorized = new BudgetRow();
        uncategorized.id = BudgetMath.UNCATEGORIZED;
        uncategorized.name = ReportTable.localized("Uncategorized");
        uncategorized.group = uncategorized.name;
        rows.put(uncategorized.id, uncategorized);
        String activity = "SELECT COALESCE(t.categoryId,-1),-SUM(t.amount) FROM txnTable t"
            + " JOIN accountTable a ON a._id=t.accountId"
            + " LEFT JOIN accountTable target ON target._id=t.targAccountId"
            + " WHERE t.deleted=0 AND a.deleted=0 AND a.onBudget=1 AND substr(t.date,1,7)=?"
            + " AND NOT EXISTS(SELECT 1 FROM txnTable child WHERE child.parentId=t._id AND child.deleted=0)"
            + " AND (t.categoryId IS NOT NULL OR COALESCE(target.onBudget,0)=0) GROUP BY t.categoryId";
        try (Cursor cursor = data.db.rawQuery(activity, new String[]{month})) {
            while (cursor.moveToNext()) {
                BudgetRow row = rows.get(cursor.getLong(0));
                if (row != null) row.spent = cursor.getLong(1);
            }
        }
        for (BudgetRow row : rows.values()) {
            row.balance = BudgetMath.get(math.balance, row.id);
            row.prior = row.balance - row.budgeted + row.spent;
        }
        return new ArrayList<BudgetRow>(rows.values());
    }

    public static final class ComparisonRow {
        public String key, name;
        public long category, current, previous;
        public long delta() { return current - previous; }
    }

    public static ReportData.Filter previous(ReportData.Filter current) {
        int count = ReportData.months(current.first, current.last).size();
        ReportData.Filter result = new ReportData.Filter();
        result.first = ReportData.shift(current.first, -count);
        result.last = ReportData.shift(current.last, -count);
        result.excludedCategories.addAll(current.excludedCategories);
        result.excludedAccounts.addAll(current.excludedAccounts);
        result.excludedPayees.addAll(current.excludedPayees);
        result.enteredBy = current.enteredBy;
        result.mine.addAll(current.mine);
        return result;
    }

    public static List<ComparisonRow> comparison(ReportData data, ReportData.Filter current) {
        Map<String, ComparisonRow> rows = new LinkedHashMap<String, ComparisonRow>();
        // The ring model omits groups whose net total is zero. Comparisons must retain
        // offsetting expense/refund categories inside those groups.
        add(rows, new ReportTable(data, current), false);
        add(rows, new ReportTable(data, previous(current)), true);
        List<ComparisonRow> result = new ArrayList<ComparisonRow>(rows.values());
        java.util.Iterator<ComparisonRow> iterator = result.iterator();
        while (iterator.hasNext()) {
            ComparisonRow row = iterator.next();
            if (row.current == 0 && row.previous == 0) iterator.remove();
        }
        Collections.sort(result, (left, right) -> {
            int amount = Long.compare(right.current, left.current);
            return amount == 0 ? left.name.compareToIgnoreCase(right.name) : amount;
        });
        return result;
    }

    private static void add(Map<String, ComparisonRow> rows, ReportTable table, boolean previous) {
        for (ReportTable.Row group : table.groups) {
            for (ReportTable.Row category : group.children) {
                ComparisonRow row = rows.get(category.key);
                if (row == null) {
                    row = new ComparisonRow();
                    row.key = category.key;
                    row.category = category.category;
                    row.name = ReportTable.localized(group.name) + " · " + ReportTable.localized(category.name);
                    rows.put(row.key, row);
                }
                if (previous) row.previous = -category.total();
                else row.current = -category.total();
            }
        }
    }
}

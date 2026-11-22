package org.classicbudget.patch;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Single-cell amount suggestions from the desktop's MonthColumnSubCategoryRenderer. */
final class BudgetSuggestions {
    static final class Option {
        final String label;
        final long amount;
        Option(String label, long amount) {
            this.label = label;
            this.amount = amount;
        }
    }

    private static String tr(String en, String pl) { return BudgetActivity.tr(en, pl); }

    static List<Option> options(SQLiteDatabase db, String month, BudgetStore.Row row) {
        String previous = BudgetStore.shift(month, -1);
        String category = Long.toString(row.categoryId);
        long previousAmount = 0;
        try (Cursor cursor = db.rawQuery("SELECT amount FROM monthlyBudgetTable WHERE date=? AND categoryId=? AND deleted=0",
            new String[]{previous + "-01", category})) {
            if (cursor.moveToFirst()) previousAmount = cursor.getLong(0);
        }
        List<Option> options = new ArrayList<>();
        options.add(new Option(tr("Budgeted last month", "Budżet z poprzedniego miesiąca"), previousAmount));
        options.add(new Option(tr("Outflows last month", "Wypływy z poprzedniego miesiąca"), spent(db, previous, previous, category)));
        Calendar now = Calendar.getInstance();
        String current = BudgetStore.monthKey(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
        String last = month.compareTo(current) > 0 ? current : BudgetStore.shift(current, -1);
        Set<String> months = activeMonths(db, BudgetStore.shift(last, -11), last);
        int shortHistory = depth(months, last, 3);
        int longHistory = depth(months, last, 12);
        if (shortHistory > 1) options.add(average(db, shortHistory, last, category));
        if (longHistory > 3) options.add(average(db, longHistory, last, category));
        options.add(new Option(tr("Scheduled outflows this month", "Zaplanowane wypływy w tym miesiącu"), scheduled(db, month, category)));
        options.add(new Option(tr("Balance to zero", "Saldo do zera"), row.budgeted - row.balance));
        return options;
    }

    private static Option average(SQLiteDatabase db, int count, String last, String category) {
        long amount = spent(db, BudgetStore.shift(last, 1 - count), last, category);
        return new Option(tr("Average outflows · months ", "Średnie wypływy · miesiące ") + count,
            Math.round((double) amount / count));
    }

    private static int depth(Set<String> months, String end, int limit) {
        for (int count = limit; count > 0; count--) {
            if (months.contains(BudgetStore.shift(end, 1 - count))) return count;
        }
        return 0;
    }

    private static Set<String> activeMonths(SQLiteDatabase db, String first, String last) {
        Set<String> active = new HashSet<>();
        try (Cursor cursor = db.rawQuery("SELECT substr(date,1,7) FROM monthlyBudgetTable WHERE deleted=0"
            + " AND date>=? AND date<? GROUP BY 1 HAVING SUM(amount)<>0",
            new String[]{first, BudgetStore.shift(last, 1)})) {
            while (cursor.moveToNext()) active.add(cursor.getString(0));
        }
        String sql = "SELECT substr(t.date,1,7),COALESCE(c.guid,'')"
            + " FROM txnTable t JOIN accountTable a ON a._id=t.accountId"
            + " LEFT JOIN accountTable ta ON ta._id=t.targAccountId LEFT JOIN categoryTable c ON c._id=t.categoryId"
            + " WHERE t.deleted=0 AND a.deleted=0 AND a.onBudget=1 AND COALESCE(ta.onBudget,0)=0"
            + " AND t.date>=? AND t.date<?" + ReportData.LEAF
            + " GROUP BY 1,CASE WHEN c.guid IN ('Category/__ImmediateIncome__','Category/__DeferredIncome__') THEN c.guid ELSE '' END"
            + " HAVING SUM(t.amount)<>0";
        try (Cursor cursor = db.rawQuery(sql, new String[]{BudgetStore.shift(first, -1), BudgetStore.shift(last, 1)})) {
            while (cursor.moveToNext()) {
                String month = cursor.getString(0);
                if ("Category/__DeferredIncome__".equals(cursor.getString(1))) month = BudgetStore.shift(month, 1);
                if (month.compareTo(first) >= 0 && month.compareTo(last) <= 0) active.add(month);
            }
        }
        return active;
    }

    private static long spent(SQLiteDatabase db, String first, String last, String category) {
        String sql = "SELECT -COALESCE(SUM(t.amount),0) FROM txnTable t JOIN accountTable a ON a._id=t.accountId"
            + " WHERE t.deleted=0 AND a.deleted=0 AND a.onBudget=1 AND t.categoryId=? AND t.date>=? AND t.date<?"
            + ReportData.LEAF;
        try (Cursor cursor = db.rawQuery(sql, new String[]{category, first, BudgetStore.shift(last, 1)})) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    static long scheduled(SQLiteDatabase db, String month, String category) {
        Calendar first = ReportData.day(month + "-01");
        Calendar end = ReportData.day(BudgetStore.shift(month, 1) + "-01");
        String sql = "SELECT s.date,s.frequency,s.twiceAMonthStartDay,"
            + " CASE WHEN NOT EXISTS (SELECT 1 FROM scheduledTxnTable x WHERE x.parentId=s._id AND x.deleted=0)"
            + " AND s.categoryId=? AND s.amount<0 THEN -s.amount ELSE 0 END"
            + " + COALESCE((SELECT SUM(-c.amount) FROM scheduledTxnTable c WHERE c.parentId=s._id AND c.deleted=0"
            + " AND c.categoryId=? AND c.amount<0),0)"
            + " FROM scheduledTxnTable s WHERE s.deleted=0 AND s.parentId IS NULL";
        long sum = 0;
        try (Cursor cursor = db.rawQuery(sql, new String[]{category, category})) {
            while (cursor.moveToNext()) {
                String date = cursor.getString(0);
                long perOccurrence = cursor.getLong(3);
                if (perOccurrence == 0 || date == null || date.length() < 10) continue;
                Calendar day = ReportData.day(date);
                int initialDay = day.get(Calendar.DAY_OF_MONTH);
                for (int step = 0; day != null && day.before(end) && step < 100000; step++) {
                    if (!day.before(first)) sum += perOccurrence;
                    day = ReportData.next(day, cursor.getString(1), cursor.getInt(2), initialDay);
                }
            }
        }
        return sum;
    }
}

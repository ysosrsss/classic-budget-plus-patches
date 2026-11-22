package org.classicbudget.patch;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * YNAB 4 desktop budget math, ported from the decompiled desktop classes MonthlySubCategoryBudget,
 * MonthlyMasterCategoryBudget and MonthlyBudget (reference: android/budget_math_reference.py).
 *
 *   balance   = prior + budgeted - spent
 *   prior     = previous balance if >= 0, or if negative and the previous month is Confined; else 0
 *   handling  = explicit cell value (DB: 1 AffectsBuffer, 0 Confined), else previous month's, default AffectsBuffer
 *   carried   = sum of negative balances with AffectsBuffer (all outflow groups, hidden and uncategorized included)
 *   start(M)  = deferred income of M-1 + available(M-1) + carried(M-1)
 *   available = start + income of M - total budgeted of M              ("To be budgeted")
 *
 * Verified on a real-budget copy: current-month balances equal the original engine's currentBal for
 * all categories, and available + sum(balances) equals on-budget money minus next-month income.
 */
public final class BudgetMath {
    public static final long UNCATEGORIZED = -1;

    public static final class Result {
        public final Map<Long, Long> balance = new HashMap<Long, Long>();
        final Map<Long, Long> opening = new HashMap<>();
        final Set<Long> confined = new HashSet<>();
        public long available, income, budgeted, carriedFromPrevious;
        /** Desktop month header: "Not Budgeted in <previous>" (negative = "Overbudgeted"). */
        public long previousAvailable;
        /** Income categorized "for next month" in the previous month; desktop adds it to "Income for <month>". */
        public long deferredFromPrevious;

        /** Desktop "Income for <month>" = this month's income + income deferred from the previous month. */
        public long incomeForMonth() { return income + deferredFromPrevious; }
    }

    static int index(String month) { return Integer.parseInt(month.substring(0, 4)) * 12 + Integer.parseInt(month.substring(5, 7)) - 1; }

    static long get(Map<Long, Long> m, long key) { Long v = m.get(key); return v == null ? 0 : v; }

    static Map<Long, Long> at(Map<Integer, Map<Long, Long>> byMonth, int month) {
        Map<Long, Long> m = byMonth.get(month);
        if (m == null) { m = new HashMap<Long, Long>(); byMonth.put(month, m); }
        return m;
    }

    public static Result compute(SQLiteDatabase db, String target) {
        Set<Long> outflow = new HashSet<Long>();
        try (Cursor c = db.rawQuery("SELECT c._id FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId"
                + " WHERE c.deleted = 0 AND m.guid != 'MasterCategory/__Internal__'", null)) {
            while (c.moveToNext()) outflow.add(c.getLong(0));
        }
        long incomeId = Long.MIN_VALUE, deferredId = Long.MIN_VALUE;
        try (Cursor c = db.rawQuery("SELECT _id, guid FROM categoryTable WHERE guid IN ('Category/__ImmediateIncome__', 'Category/__DeferredIncome__')", null)) {
            while (c.moveToNext()) { if (c.getString(1).endsWith("ImmediateIncome__")) incomeId = c.getLong(0); else deferredId = c.getLong(0); }
        }
        Set<Long> onBudget = new HashSet<Long>();
        try (Cursor c = db.rawQuery("SELECT _id FROM accountTable WHERE onBudget = 1 AND deleted = 0", null)) { while (c.moveToNext()) onBudget.add(c.getLong(0)); }

        int last = index(target), first = last;
        Map<Integer, Map<Long, Long>> budget = new HashMap<Integer, Map<Long, Long>>(), spent = new HashMap<Integer, Map<Long, Long>>();
        Map<Integer, Map<Long, Long>> explicitConfined = new HashMap<Integer, Map<Long, Long>>(); // value 1 = Confined, 0 = AffectsBuffer
        Map<Integer, Long> income = new HashMap<Integer, Long>(), deferred = new HashMap<Integer, Long>();
        try (Cursor c = db.rawQuery("SELECT date, categoryId, amount, overspendHandling FROM monthlyBudgetTable WHERE deleted = 0", null)) {
            while (c.moveToNext()) {
                int m = index(c.getString(0)); if (m > last) continue; first = Math.min(first, m);
                long cat = c.getLong(1);
                at(budget, m).put(cat, c.isNull(2) ? 0 : c.getLong(2));
                if (!c.isNull(3)) at(explicitConfined, m).put(cat, c.getInt(3) == 1 ? 0L : 1L);
            }
        }
        String leaf = "SELECT t.date, t.categoryId, t.amount, t.targAccountId, t.accountId FROM txnTable t WHERE t.deleted = 0"
            + " AND NOT EXISTS (SELECT 1 FROM txnTable child WHERE child.parentId = t._id AND child.deleted = 0)";
        try (Cursor c = db.rawQuery(leaf, null)) {
            while (c.moveToNext()) {
                if (!onBudget.contains(c.getLong(4))) continue;
                int m = index(c.getString(0)); if (m > last) continue; first = Math.min(first, m);
                long amount = c.getLong(2);
                Long cat = c.isNull(1) ? null : c.getLong(1);
                if (cat != null && cat == incomeId) income.put(m, (income.containsKey(m) ? income.get(m) : 0) + amount);
                else if (cat != null && cat == deferredId) deferred.put(m, (deferred.containsKey(m) ? deferred.get(m) : 0) + amount);
                else if (cat != null && outflow.contains(cat)) { Map<Long, Long> s = at(spent, m); s.put(cat, get(s, cat) - amount); }
                else if (cat == null && !(c.isNull(3) ? false : onBudget.contains(c.getLong(3)))) {
                    Map<Long, Long> s = at(spent, m); s.put(UNCATEGORIZED, get(s, UNCATEGORIZED) - amount);
                }
            }
        }
        Set<Long> cells = new HashSet<Long>(outflow);
        cells.add(UNCATEGORIZED);
        Map<Long, Long> previousBalance = new HashMap<Long, Long>();
        Map<Long, Boolean> previousConfined = new HashMap<Long, Boolean>();
        long available = 0, carried = 0, previousDeferred = 0;
        Result result = new Result();
        for (int m = first; m <= last; m++) {
            long start = previousDeferred + available + carried;
            Map<Long, Long> balance = new HashMap<Long, Long>();
            Map<Long, Boolean> confined = new HashMap<Long, Boolean>();
            Map<Long, Long> b = budget.get(m), s = spent.get(m), e = explicitConfined.get(m);
            long totalBudgeted = 0, carriedNow = 0;
            for (long cat : cells) {
                Boolean wasConfined = previousConfined.get(cat);
                boolean isConfined = e != null && e.containsKey(cat) ? e.get(cat) == 1L : wasConfined != null && wasConfined;
                long p = get(previousBalance, cat);
                long prior = p >= 0 || (wasConfined != null && wasConfined) ? p : 0;
                if (m == last) {
                    result.opening.put(cat, prior);
                    if (isConfined) result.confined.add(cat);
                }
                long budgeted = b == null ? 0 : get(b, cat);
                long value = prior + budgeted - (s == null ? 0 : get(s, cat));
                totalBudgeted += budgeted;
                if (value < 0 && !isConfined) carriedNow += value;
                balance.put(cat, value);
                confined.put(cat, isConfined);
            }
            long incomeNow = income.containsKey(m) ? income.get(m) : 0;
            if (m == last) {
                result.balance.putAll(balance);
                result.income = incomeNow;
                result.budgeted = totalBudgeted;
                result.carriedFromPrevious = carried;
                // Still the previous month's values here: they are replaced only after this branch.
                result.previousAvailable = available;
                result.deferredFromPrevious = previousDeferred;
            }
            available = start + incomeNow - totalBudgeted;
            carried = carriedNow;
            previousBalance = balance; previousConfined = confined;
            previousDeferred = deferred.containsKey(m) ? deferred.get(m) : 0;
        }
        result.available = available;
        return result;
    }
}

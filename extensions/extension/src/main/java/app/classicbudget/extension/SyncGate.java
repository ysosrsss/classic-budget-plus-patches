package app.classicbudget.extension;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.classicbudget.patch.BackendSelector;

/** Gates for original behaviour that is only verified with Dropbox. */
public final class SyncGate {
    private SyncGate() {}

    /**
     * Scheduled transactions are entered on the phone only with Dropbox (original behaviour).
     * With Koofr / WebDAV / folder the desktop enters them, as in the tested MULTI build.
     */
    public static boolean scheduledAllowed() {
        try {
            String kind = BackendSelector.chosen();
            return kind == null || BackendSelector.DROPBOX.equals(kind);
        } catch (Throwable t) {
            return true;
        }
    }

    /*
     * Balances after imports (13.11, real-budget copy on a folder backend): after transactions from
     * the desktop were imported, categoryTable.currentBal (shown by the original category lists)
     * stayed as before, e.g. -1.23 instead of -8.13; account balances only caught up at the next
     * budget open. The original full recalculation fixes it but took 73 s on 10 000 transactions
     * while holding a database transaction, so only the categories touched by the import are
     * recalculated, with the original per-category routine (DbTableCategory.a(budget, ids)).
     *
     * DiffImporter.a(context, localBudget, provider) is wrapped: beforeDiffImport at its start
     * records version and category of every transaction and monthly budget row, afterDiffImport
     * at its end compares. SyncService runs imports on one thread, so one snapshot is enough.
     */
    private static Map<Long, String> snapshot;

    private static final String ROWS =
            "SELECT 't'||_id, version, categoryId, deleted FROM txnTable "
            + "UNION ALL SELECT 'm'||_id, version, categoryId, 0 FROM monthlyBudgetTable";

    public static void beforeDiffImport(Object localBudget) {
        snapshot = null;
        try {
            if (scheduledAllowed() || localBudget == null) return;
            snapshot = rows(database(budgetOf(localBudget)));
        } catch (Throwable t) {
            android.util.Log.w("ClassicBudgetPlus", "SyncGate.beforeDiffImport", t);
        }
    }

    /** With Dropbox nothing changes; otherwise recalculates what files from other devices changed. */
    public static void afterDiffImport(Object localBudget, Object result) {
        Map<Long, String> before = snapshot;
        snapshot = null;
        try {
            if (scheduledAllowed() || localBudget == null || result == null) return;
            int imported = result.getClass().getField("a").getInt(result);
            if (imported <= 0) return;
            Object budget = budgetOf(localBudget);
            if (before == null) {
                android.util.Log.w("ClassicBudgetPlus", "SyncGate: no snapshot, full recalculation");
                recomputeAll(budget);
                return;
            }
            Map<Long, String> after = rows(database(budget));
            Set<Long> categories = new LinkedHashSet<Long>();
            collectChanged(before, after, categories);
            collectChanged(after, before, categories);
            recompute(budget, categories);
        } catch (Throwable t) {
            android.util.Log.w("ClassicBudgetPlus", "SyncGate.afterDiffImport", t);
        }
    }

    /** Categories of rows that are new, gone or have another version/category/deleted flag in "other". */
    static void collectChanged(Map<Long, String> rows, Map<Long, String> other, Set<Long> categories) {
        for (Map.Entry<Long, String> row : rows.entrySet()) {
            if (row.getValue().equals(other.get(row.getKey()))) continue;
            String category = row.getValue().split("\\|", -1)[1];
            if (!category.isEmpty()) categories.add(Long.valueOf(category));
        }
    }

    /** key: row id, doubled, +1 for monthly budget rows; value: "version|categoryId|deleted". */
    static Map<Long, String> rows(SQLiteDatabase db) {
        Map<Long, String> rows = new HashMap<Long, String>();
        Cursor c = db.rawQuery(ROWS, null);
        try {
            while (c.moveToNext()) {
                String key = c.getString(0);
                long id = Long.parseLong(key.substring(1)) * 2 + (key.charAt(0) == 't' ? 0 : 1);
                String category = c.isNull(2) ? "" : Long.toString(c.getLong(2));
                rows.put(id, c.getString(1) + "|" + category + "|" + c.getInt(3));
            }
        } finally {
            c.close();
        }
        return rows;
    }

    static Object budgetOf(Object localBudget) throws Exception {
        return localBudget.getClass().getMethod("i").invoke(localBudget);
    }

    static SQLiteDatabase database(Object budget) throws Exception {
        return (SQLiteDatabase) budget.getClass().getMethod("c").invoke(budget);
    }

    /** Original routines: DbTableAccount.c(db) for accounts, DbTableCategory.a(budget, ids) for categories. */
    static void recompute(Object budget, Set<Long> categories) throws Exception {
        long started = System.currentTimeMillis();
        SQLiteDatabase db = database(budget);
        Object accounts = Class.forName("com.youneedabudget.ynab.core.c.l").getMethod("d").invoke(null);
        db.beginTransaction();
        try {
            accounts.getClass().getMethod("c", SQLiteDatabase.class).invoke(accounts, db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        long accountsDone = System.currentTimeMillis();
        if (!categories.isEmpty()) {
            Class<?> budgetClass = Class.forName("com.youneedabudget.ynab.core.c.f");
            Object table = Class.forName("com.youneedabudget.ynab.core.c.m").getMethod("d").invoke(null);
            // The original routine opens its own transaction.
            table.getClass().getMethod("a", budgetClass, Iterable.class).invoke(table, budget, categories);
        }
        budget.getClass().getMethod("d").invoke(budget);
        android.util.Log.i("ClassicBudgetPlus", "SyncGate recompute done categories=" + categories.size()
                + " accountsMs=" + (accountsDone - started) + " ms=" + (System.currentTimeMillis() - started));
    }

    /** Fallback only: the original full recalculation used by the full import (slow on big budgets). */
    static void recomputeAll(Object budget) throws Exception {
        long started = System.currentTimeMillis();
        SQLiteDatabase db = database(budget);
        Class<?> budgetClass = Class.forName("com.youneedabudget.ynab.core.c.f");
        Object accounts = Class.forName("com.youneedabudget.ynab.core.c.l").getMethod("d").invoke(null);
        Object categories = Class.forName("com.youneedabudget.ynab.core.c.m").getMethod("d").invoke(null);
        db.beginTransaction();
        try {
            accounts.getClass().getMethod("c", SQLiteDatabase.class).invoke(accounts, db);
            categories.getClass().getMethod("b", budgetClass).invoke(categories, budget);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        budget.getClass().getMethod("d").invoke(budget);
        android.util.Log.i("ClassicBudgetPlus", "SyncGate recompute done full ms=" + (System.currentTimeMillis() - started));
    }
}

package org.classicbudget.patch;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Collections;

/**
 * Budgeted amounts for one month, written like YNAB 4 desktop does (monthlyCategoryBudget items).
 *
 * The original Android app only reads budgeted amounts. Plus writes the local row the way the
 * original importer leaves it (amount in cents, version, MCB/yyyy-MM/categoryGuid) and queues an
 * outbox row in the same database transaction; BudgetExport hands the outbox to the original diff
 * exporter. Safety rules: the month entity must be known, the note of the replaced version must be
 * known (desktop overwrites notes), and a row changed meanwhile by an import refuses the edit.
 */
public final class BudgetStore {
    static final String C = "com.youneedabudget.ynab.core.";
    static final String OUTBOX = "CREATE TABLE IF NOT EXISTS cbBudgetOutbox(guid TEXT NOT NULL, version TEXT NOT NULL, amount INTEGER NOT NULL,"
        + " categoryGuid TEXT NOT NULL, month TEXT NOT NULL, note TEXT, overspend TEXT, synced INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(guid, version))";

    public static final class Row {
        public long categoryId; public String categoryGuid, name, group, groupGuid;
        public long budgeted, activity, balance; public String guid, version;
        long opening;
        boolean confined;
        /** DB code of the cell's own overspending handling: 1 AffectsBuffer, 0 Confined, null = inherited. */
        public Integer overspendCode;
        /** Desktop name for the same value (what a monthlyCategoryBudget item carries), or null. */
        public String overspendName() { return overspendCode == null ? null : overspendCode == 1 ? "AffectsBuffer" : "Confined"; }
        public boolean hasRow;
    }
    public static final class Change {
        final Row row; final long amount;
        final boolean balanceTarget;
        final boolean editNote;
        final String note;
        final Integer overspending;
        public Change(Row row, long amount) { this(row, amount, false); }
        Change(Row row, long amount, boolean balanceTarget) {
            this(row, amount, balanceTarget, false, null, null);
        }
        private Change(Row row, long amount, boolean balanceTarget, boolean editNote, String note, Integer overspending) {
            this.row = row;
            this.amount = amount;
            this.balanceTarget = balanceTarget;
            this.editNote = editNote;
            this.note = note;
            this.overspending = overspending;
        }
        static Change note(Row row, String text) {
            return new Change(row, row.budgeted, false, true, text, null);
        }
        static Change overspending(Row row, boolean confined) {
            return new Change(row, row.budgeted, false, false, null, confined ? 0 : 1);
        }
        boolean requested() {
            return amount != row.budgeted || editNote
                || overspending != null && !overspending.equals(row.overspendCode);
        }
    }

    final Context context; final Object budget, adapter; final SQLiteDatabase db;
    public final String budgetFolder, dataFolder;

    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    static Object no(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }

    /** The budget the user has open (same lookup as the Plus schedule editor). */
    public static BudgetStore open(Context context) throws Exception {
        Object app = Class.forName(C + "app.d").getMethod("j").invoke(null);
        Field field = Class.forName(C + "app.d").getDeclaredField("h");
        field.setAccessible(true);
        Map<?, ?> map = (Map<?, ?>) field.get(app);
        java.io.File selected = ReportData.database(context);
        if (selected == null) throw new Exception(PlusLang.tr("Open a budget first.", "Najpierw otwórz budżet."));
        Object current = null;
        synchronized (map) {
            for (Object b : map.values()) if (selected.getName().equals(no(b, "j"))) {
                if (current != null) throw new Exception("Ambiguous budget");
                current = b;
            }
        }
        if (current == null) throw new Exception(PlusLang.tr("Open the budget home screen first.", "Najpierw otwórz ekran budżetu."));
        return new BudgetStore(context, current);
    }

    BudgetStore(Context context, Object budget) throws Exception {
        this.context = context.getApplicationContext();
        this.budget = budget;
        adapter = no(budget, "i");
        db = (SQLiteDatabase) no(adapter, "c");
        budgetFolder = value("budgetFolder");
        dataFolder = value("dataFolder");
        String folder = budgetFolder == null ? "" : budgetFolder.toUpperCase(Locale.ROOT);
        // Experimental writer: only TEST budgets and local COPY budgets, never the family budget itself.
        if (PlusEdition.TEST_ONLY && !folder.contains("TEST") && !folder.contains("COPY"))
            throw new Exception(PlusLang.tr("Budgeting from the phone is limited to TEST and COPY budgets for now.",
                "Budżetowanie z telefonu działa na razie tylko w budżetach TEST i COPY."));
        db.execSQL(OUTBOX);
    }

    /**
     * Starts the original manual sync of this budget, the same call as a pull-down on the accounts
     * screen (app.q$1 -> app.cloud.b -> core.cloud.o.a(Context, g, o$a.a)). False when the budget is
     * not a synced one or the original classes are not as expected; nothing is changed then.
     */
    public boolean syncNow() {
        try {
            Class<?> synced = Class.forName(C + "cloud.g");
            if (!synced.isInstance(budget)) return false;
            Class<?> kind = Class.forName(C + "cloud.o$a");
            Object manual = kind.getField("a").get(null);
            java.lang.reflect.Method start = Class.forName(C + "cloud.o").getDeclaredMethod("a", Context.class, synced, kind);
            start.setAccessible(true);
            start.invoke(null, context, budget, manual);
            return true;
        } catch (Throwable unexpected) {
            return false;
        }
    }

    String value(String name) {
        try (Cursor c = db.rawQuery("SELECT value FROM keyValue WHERE name=?", new String[]{name})) { return c.moveToFirst() ? c.getString(0) : null; }
    }

    public static String monthKey(int year, int month) { return String.format(Locale.ROOT, "%04d-%02d", year, month); }

    /** Visible categories of normal groups (no Hidden/Income/internal), in desktop order, with amounts of the month. */
    public List<Row> rows(String month) {
        Map<Long, Row> out = new LinkedHashMap<Long, Row>();
        String sql = "SELECT c._id, c.guid, c.name, m.name, m.guid, b.amount, b.version, b.guid, b.overspendHandling"
            + " FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId"
            + " LEFT JOIN monthlyBudgetTable b ON b.categoryId = c._id AND b.date = ? AND b.deleted = 0"
            + " WHERE c.deleted = 0 AND m.deleted = 0 AND m.guid NOT LIKE 'MasterCategory/\\_\\_%' ESCAPE '\\'"
            + " ORDER BY m.sequence, c.sequence";
        try (Cursor c = db.rawQuery(sql, new String[]{month + "-01"})) {
            while (c.moveToNext()) {
                Row r = new Row();
                r.categoryId = c.getLong(0); r.categoryGuid = c.getString(1); r.name = c.getString(2);
                r.group = c.getString(3); r.groupGuid = c.getString(4);
                r.hasRow = !c.isNull(6);
                r.budgeted = c.isNull(5) ? 0 : c.getLong(5);
                r.version = c.isNull(6) ? null : c.getString(6);
                r.guid = c.isNull(7) ? guid(month, r.categoryGuid) : c.getString(7);
                r.overspendCode = c.isNull(8) ? null : c.getInt(8);
                out.put(r.categoryId, r);
            }
        }
        // Activity: on-budget leaf transactions of the month by category (split parts count, their parent does not).
        String activity = "SELECT t.categoryId, SUM(t.amount) FROM txnTable t JOIN accountTable a ON a._id = t.accountId"
            + " WHERE t.deleted = 0 AND a.deleted = 0 AND a.onBudget = 1 AND substr(t.date, 1, 7) = ?"
            + " AND NOT EXISTS (SELECT 1 FROM txnTable child WHERE child.parentId = t._id AND child.deleted = 0)"
            + " AND t.categoryId IS NOT NULL GROUP BY t.categoryId";
        try (Cursor c = db.rawQuery(activity, new String[]{month})) {
            while (c.moveToNext()) { Row r = out.get(c.getLong(0)); if (r != null) r.activity = c.getLong(1); }
        }
        lastMath = BudgetMath.compute(db, month);
        for (Row r : out.values()) {
            r.balance = BudgetMath.get(lastMath.balance, r.categoryId);
            r.opening = BudgetMath.get(lastMath.opening, r.categoryId);
            r.confined = lastMath.confined.contains(r.categoryId);
        }
        return new ArrayList<Row>(out.values());
    }

    /** Desktop math of the month last listed by rows(): available ("To be budgeted"), uncategorized balance. */
    public BudgetMath.Result lastMath;

    static String guid(String month, String categoryGuid) { return "MCB/" + month + "/" + categoryGuid; }

    public int unsent() {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM cbBudgetOutbox WHERE synced = 0", null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    /** Why a cell cannot be changed now, or null. */
    public String blocked(String month, Row row) {
        if (!BudgetNotes.monthKnown(context, dataFolder, month))
            return PlusLang.tr("This month is not in the budget files yet (desktop creates months). Refresh the index or pick another month.",
                "Tego miesiąca nie ma jeszcze w plikach budżetu (tworzy je komputer). Odśwież indeks albo wybierz inny miesiąc.");
        if (row.hasRow && !note(row).known)
            return PlusLang.tr("The note of this budget cell is unknown, so the desktop could lose it. Use “Refresh index” once.",
                "Nie znam notatki tej komórki budżetu, więc komputer mógłby ją skasować. Użyj raz „Odśwież indeks”.");
        return null;
    }

    BudgetNotes.Note note(Row row) {
        BudgetNotes.Note result = new BudgetNotes.Note();
        if (!row.hasRow) {
            result.known = true;
            return result;
        }
        // The outbox is committed together with an edit, including if Android exits before indexing its note.
        try (Cursor cursor = db.rawQuery("SELECT note FROM cbBudgetOutbox WHERE guid=? AND version=?",
            new String[]{row.guid, row.version})) {
            if (cursor.moveToFirst()) {
                result.known = true;
                result.text = cursor.getString(0);
                return result;
            }
        }
        return BudgetNotes.lookup(context, row.guid, row.version);
    }

    static List<Change> move(Row source, Row target, long amount) {
        if (source.categoryId == target.categoryId || amount <= 0 || amount > source.balance)
            throw new IllegalArgumentException(PlusLang.tr("Enter an amount within the source balance.", "Podaj kwotę w granicach dostępnego salda źródła."));
        long reduced = java.math.BigDecimal.valueOf(source.budgeted).subtract(java.math.BigDecimal.valueOf(amount)).longValueExact();
        long increased = java.math.BigDecimal.valueOf(target.budgeted).add(java.math.BigDecimal.valueOf(amount)).longValueExact();
        return java.util.Arrays.asList(new Change(source, reduced, true), new Change(target, increased, true));
    }

    /** Applies all changes atomically, in one original database transaction. */
    public int apply(final String month, final List<Change> changes) throws Exception {
        for (Change change : changes) {
            if (!change.requested()) continue;
            String why = blocked(month, change.row);
            if (why != null) throw new Exception(change.row.name + ": " + why);
        }
        final int[] written = {0};
        final List<String[]> notes = new ArrayList<String[]>();
        final Exception[] error = new Exception[1];
        Runnable operation = new Runnable() { public void run() { try {
            Object knowledge = no(budget, "e");
            BudgetMath.Result currentMath = null;
            for (Change change : changes) {
                if (change.balanceTarget) {
                    // Spending or a previous month's budget can change without this cell's version changing.
                    currentMath = BudgetMath.compute(db, month);
                    break;
                }
            }
            for (Change ch : changes) {
                if (!ch.requested()) continue;
                if (ch.balanceTarget && BudgetMath.get(currentMath.balance, ch.row.categoryId) != ch.row.balance)
                    throw new Exception(PlusLang.tr("The category balance changed meanwhile (sync). Reopen the month.",
                        "Saldo kategorii zmieniło się w międzyczasie (synchronizacja). Otwórz miesiąc ponownie."));
                // The row may have been replaced by an import since the screen was drawn.
                String current = null;
                try (Cursor c = db.rawQuery("SELECT version FROM monthlyBudgetTable WHERE guid = ? AND deleted = 0", new String[]{ch.row.guid})) {
                    if (c.moveToFirst()) current = c.getString(0);
                }
                if (current == null ? ch.row.hasRow : !current.equals(ch.row.version))
                    throw new Exception(PlusLang.tr("The budget changed meanwhile (sync). Reopen the month.", "Budżet zmienił się w międzyczasie (synchronizacja). Otwórz miesiąc ponownie."));
                BudgetNotes.Note previous = note(ch.row);
                if (!previous.known) throw new IllegalStateException(blocked(month, ch.row));
                String note = ch.editNote ? ch.note : previous.text;
                Integer handling = ch.overspending == null ? ch.row.overspendCode : ch.overspending;
                if (ch.amount == ch.row.budgeted && java.util.Objects.equals(note, previous.text)
                    && java.util.Objects.equals(handling, ch.row.overspendCode)) continue;
                String version = (String) no(knowledge, "f"); // original counter, inside this transaction
                ContentValues v = new ContentValues();
                v.put("guid", ch.row.guid); v.put("version", version); v.put("date", month + "-01");
                v.put("amount", ch.amount); v.put("categoryId", ch.row.categoryId); v.put("deleted", 0); v.put("resolvedConflict", 0);
                v.putNull("madeWithKnowledge"); v.putNull("importedAmount");
                if (handling == null) v.putNull("overspendHandling"); else v.put("overspendHandling", handling);
                if (ch.row.hasRow) db.update("monthlyBudgetTable", v, "guid = ?", new String[]{ch.row.guid});
                else db.insertOrThrow("monthlyBudgetTable", null, v);
                ContentValues o = new ContentValues();
                o.put("guid", ch.row.guid); o.put("version", version); o.put("amount", ch.amount);
                o.put("categoryGuid", ch.row.categoryGuid); o.put("month", month);
                if (note == null) o.putNull("note"); else o.put("note", note);
                if (handling == null) o.putNull("overspend"); else o.put("overspend", handling == 1 ? "AffectsBuffer" : "Confined");
                o.put("synced", 0);
                db.insertOrThrow("cbBudgetOutbox", null, o);
                notes.add(new String[]{ch.row.guid, version, note});
                written[0]++;
            }
        } catch (Exception problem) { error[0] = problem; throw new RuntimeException(problem); } } };
        try { call(adapter, "a", new Class<?>[]{Runnable.class}, operation); }
        catch (Exception failed) { throw error[0] != null ? error[0] : failed; }
        if (written[0] == 0) return 0;
        for (String[] n : notes) BudgetNotes.record(context, n[0], n[1], n[2]);
        afterCommit();
        return written[0];
    }

    /** Same follow-ups as the original after a local edit: notify, recompute balances, queue the sync. */
    void afterCommit() {
        // Recompute the original category balances (categoryTable.currentBal) the way MonthlyRolloverHandler
        // does. The handler itself runs this only once per month (lastRolloverTimestamp), so after a phone edit
        // the original Budget tab would keep showing old balances until the next month.
        try {
            Object categories = Class.forName(C + "c.m").getMethod("d").invoke(null);
            db.beginTransaction();
            try {
                categories.getClass().getMethod("b", Class.forName(C + "c.f")).invoke(categories, adapter);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) { DavProvider.diagnostic(e); }
        try { no(adapter, "d"); } catch (Exception e) { DavProvider.diagnostic(e); }
        try {
            Class<?> reason = Class.forName(C + "cloud.o$a");
            Class.forName(C + "cloud.o").getMethod("a", Context.class, Class.forName(C + "cloud.g"), reason)
                .invoke(null, context, budget, reason.getField("a").get(null));
        } catch (Exception e) { DavProvider.diagnostic(e); }
    }

    // ---- Quick budget (desktop's choices) ------------------------------------------------------

    public static final int BUDGETED_LAST_MONTH = 0, SPENT_LAST_MONTH = 1, AVERAGE_BUDGETED = 2, AVERAGE_SPENT = 3, ZERO = 4;
    public static final int BALANCE_ZERO = 5;

    static String shift(String month, int delta) {
        // Months since year 0; plain arithmetic (Math.floorDiv needs API 24, the app supports older phones).
        int index = Integer.parseInt(month.substring(0, 4)) * 12 + Integer.parseInt(month.substring(5, 7)) - 1 + delta;
        return monthKey(index / 12, index % 12 + 1);
    }

    /** Fill zero-valued budget cells like the desktop's month menu; explicit ZERO also clears nonzero cells. */
    public List<Change> quick(String month, List<Row> rows, int mode) {
        return quick(month, rows, mode, Collections.<String>emptySet());
    }

    /** Exclusions affect explicit bulk operations, by stable category GUID, never by display name. */
    public List<Change> quick(String month, List<Row> rows, int mode, Set<String> excluded) {
        if (mode < BUDGETED_LAST_MONTH || mode > BALANCE_ZERO) throw new IllegalArgumentException("Unknown budget operation");
        List<Change> out = new ArrayList<Change>();
        if (mode == BALANCE_ZERO) {
            for (Row row : rows) {
                if (excluded.contains(row.categoryGuid)) continue;
                // Same as desktop MonthColumnSubCategoryRenderer's Balance to 0.00, applied to selected rows.
                out.add(new Change(row, row.budgeted - row.balance, true));
            }
            return out;
        }
        int count = mode == AVERAGE_BUDGETED || mode == AVERAGE_SPENT ? 3 : 1;
        List<Map<Long, Row>> past = new ArrayList<Map<Long, Row>>();
        if (mode != ZERO) for (int i = 1; i <= count; i++) {
            Map<Long, Row> byId = new LinkedHashMap<Long, Row>();
            for (Row r : rows(shift(month, -i))) byId.put(r.categoryId, r);
            past.add(byId);
        }
        for (Row r : rows) {
            if (excluded.contains(r.categoryGuid)) continue;
            // Desktop MonthColumnTitle.quickBudget skips any nonzero budgeted amount (including negatives).
            if (mode != ZERO && r.budgeted != 0) continue;
            long sum = 0;
            for (Map<Long, Row> m : past) {
                Row p = m.get(r.categoryId);
                if (p == null) continue;
                sum += (mode == BUDGETED_LAST_MONTH || mode == AVERAGE_BUDGETED) ? p.budgeted : Math.max(0, -p.activity);
            }
            long amount = mode == ZERO ? 0 : Math.round((double) sum / count);
            out.add(new Change(r, amount));
        }
        return out;
    }
}

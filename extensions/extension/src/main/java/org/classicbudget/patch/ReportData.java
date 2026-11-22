package org.classicbudget.patch;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.text.NumberFormat;
import java.util.*;

/**
 * Module "Reports": read-only queries on the local budget database, with the
 * filters of the YNAB4 desktop reports (dates, categories, accounts, payees).
 * Never writes, never touches sync. Amounts are hundredths of the currency unit.
 */
public final class ReportData {
    public static final String PREFS = "cb-plus-reports";
    final SQLiteDatabase db; final Context context;

    /** One category, account or payee that a filter can include or exclude. */
    public static final class Item { public long id; public String key, name, group; public boolean onBudget = true, closed, hiddenGroup; }
    public static final class Group { public String name, key, parentKey; public long id, groupId; public long amount; public final List<Group> children = new ArrayList<Group>(); }
    public static final class Month {
        public String month; public long income, spending, assets, debts;
        public final Map<String, Long> groups = new LinkedHashMap<String, Long>();
        public long net() { return assets + debts; }
    }

    /** What a report shows: an inclusive month range and the excluded categories, accounts and payees. */
    public static final class Filter {
        public String first, last;
        public final Set<Long> excludedCategories = new HashSet<Long>(), excludedPayees = new HashSet<Long>();
        public final Set<String> excludedAccounts = new HashSet<String>();
        /** "all", "me" (this phone + my other devices) or "others": who last changed the transaction. */
        public String enteredBy = "all";
        public final Set<String> mine = new HashSet<String>();
    }

    /** The budget the original app has open ("lastBudget" in its default preferences, equal to the
     * database name). The newest file is only a fallback: with two budgets it picked the wrong one
     * after the other budget had been synced (13.11). */
    static File database(Context context) {
        String open = context.getSharedPreferences(context.getPackageName() + "_preferences", 0).getString("lastBudget", null);
        if (open != null && open.contains("#") && Arrays.asList(context.databaseList()).contains(open)) return context.getDatabasePath(open);
        File best = null;
        for (String name : context.databaseList()) {
            if (!name.contains("#") || name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")) continue;
            File f = context.getDatabasePath(name);
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    public static ReportData open(Context context) {
        File f = database(context); if (f == null) return null;
        return new ReportData(context, SQLiteDatabase.openDatabase(f.getPath(), null, SQLiteDatabase.OPEN_READONLY));
    }

    ReportData(Context context, SQLiteDatabase db) {
        this.context = context;
        this.db = db;
        // Chart colours follow the budget's own group order, read once per opened budget.
        ReportColors.load(db);
    }
    static SharedPreferences prefs(Context context) { return context.getSharedPreferences(PREFS, 0); }
    public void close() { db.close(); }

    public NumberFormat money() {
        String lang = value("curLocaleLang"), country = value("curLocaleCountry");
        NumberFormat f = NumberFormat.getCurrencyInstance(lang == null ? Locale.getDefault() : new Locale(lang, country == null ? "" : country));
        return f;
    }

    String value(String key) {
        Cursor c = db.rawQuery("SELECT value FROM keyValue WHERE name = ?", new String[]{key});
        try { return c.moveToFirst() ? c.getString(0) : null; } finally { c.close(); }
    }

    public String budgetName() { String folder = value("budgetFolder"); if (folder == null) return ""; folder = folder.substring(folder.lastIndexOf('/') + 1); int t = folder.indexOf('~'); return t > 0 ? folder.substring(0, t) : folder.replace(".ynab4", ""); }

    public static String thisMonth() { Calendar now = Calendar.getInstance(); return String.format(Locale.ROOT, "%04d-%02d", now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1); }

    /** First and last month with any transaction ("YYYY-MM"); today's month when empty. */
    public String[] span() {
        Cursor c = db.rawQuery("SELECT MIN(substr(date, 1, 7)), MAX(substr(date, 1, 7)) FROM txnTable WHERE deleted = 0", null);
        try { if (c.moveToFirst() && c.getString(0) != null) return new String[]{c.getString(0), c.getString(1)}; } finally { c.close(); }
        return new String[]{thisMonth(), thisMonth()};
    }

    public static String shift(String month, int delta) {
        int y = Integer.parseInt(month.substring(0, 4)), m = Integer.parseInt(month.substring(5, 7)) - 1 + delta;
        y += Math.floor(m / 12.0); m = ((m % 12) + 12) % 12;
        return String.format(Locale.ROOT, "%04d-%02d", y, m + 1);
    }

    public static List<String> months(String first, String last) {
        List<String> out = new ArrayList<String>();
        for (String m = first; m.compareTo(last) <= 0 && out.size() < 600; m = shift(m, 1)) out.add(m);
        return out;
    }

    // --- filter choices -------------------------------------------------------------------------

    /** All non-deleted spending categories, with Hidden Categories last; other groups keep budget order. */
    public List<Item> categories() {
        List<Item> out = new ArrayList<Item>();
        Cursor c = db.rawQuery("SELECT c._id, c.name, m.name, m.guid FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId"
            + " WHERE c.deleted = 0 AND m.deleted = 0 ORDER BY CASE WHEN m.guid = 'MasterCategory/__Hidden__' OR m.name = '__Hidden__' THEN 1 ELSE 0 END, m.sequence, c.sequence", null);
        try { while (c.moveToNext()) { Item i = new Item(); i.id = c.getLong(0); i.name = c.getString(1); i.group = c.getString(2); i.hiddenGroup="MasterCategory/__Hidden__".equals(c.getString(3))||"__Hidden__".equals(i.group); out.add(i); } } finally { c.close(); }
        Item uncategorized=new Item();uncategorized.id=-1;uncategorized.name=PlusLang.tr("Uncategorized","Bez kategorii");uncategorized.group=uncategorized.name;int beforeHidden=out.size();for(int i=0;i<out.size();i++)if(out.get(i).hiddenGroup){beforeHidden=i;break;}out.add(beforeHidden,uncategorized);
        return out;
    }

    public List<Item> accounts() {
        List<Item> out = new ArrayList<Item>();
        Cursor c = db.rawQuery("SELECT _id, guid, name, onBudget, hidden FROM accountTable WHERE deleted = 0 ORDER BY onBudget DESC, hidden, sequence", null);
        try { while (c.moveToNext()) { Item i = new Item(); i.id = c.getLong(0); i.key = c.getString(1); i.name = c.getString(2); i.onBudget = c.getInt(3) == 1; i.closed = c.getInt(4) == 1; out.add(i); } } finally { c.close(); }
        return out;
    }

    /**
     * Payee choices with the scope and order of the desktop filter (Windows 4.3.857, compared 20.11 on
     * the real-budget copy, §152): No payee, Starting Balance, transfer payees, then all other payees,
     * each group alphabetical. A transfer payee ("Payee/Transfer:" + account guid) is offered only when
     * some transaction on a budget account moves money to an existing off-budget account under it:
     * a transfer between budget accounts is never spending, so the desktop leaves those out. Before,
     * all 28 transfer payees were mixed into the T section and 10 of them always gave an empty report.
     */
    public List<Item> payees() {
        Set<Long> spendingTransfers = new HashSet<Long>();
        Cursor t = db.rawQuery("SELECT DISTINCT t.payeeId FROM txnTable t JOIN accountTable a ON a._id = t.accountId"
            + " JOIN accountTable ta ON ta._id = t.targAccountId"
            + " WHERE t.deleted = 0 AND a.deleted = 0 AND ta.deleted = 0 AND a.onBudget = 1 AND ta.onBudget = 0 AND t.payeeId IS NOT NULL", null);
        try { while (t.moveToNext()) spendingTransfers.add(t.getLong(0)); } finally { t.close(); }
        List<Item> starting = new ArrayList<Item>();
        List<Item> transfers = new ArrayList<Item>();
        List<Item> others = new ArrayList<Item>();
        Cursor c = db.rawQuery("SELECT _id, name, guid FROM payeeTable WHERE deleted = 0 ORDER BY name COLLATE NOCASE", null);
        try {
            while (c.moveToNext()) {
                Item i = new Item();
                i.id = c.getLong(0);
                i.name = c.getString(1);
                String guid = c.getString(2);
                boolean transfer = guid != null && guid.startsWith("Payee/Transfer:");
                if (transfer) {
                    if (spendingTransfers.contains(i.id)) transfers.add(i);
                } else if ("Starting Balance".equals(i.name)) {
                    // The desktop's own payee for opening balances; listed second there.
                    starting.add(i);
                } else {
                    others.add(i);
                }
            }
        } finally { c.close(); }
        List<Item> out = new ArrayList<Item>();
        Item noPayee=new Item();noPayee.id=-1;noPayee.name=PlusLang.tr("No payee","Bez płatnika");out.add(noPayee);
        out.addAll(starting);
        out.addAll(transfers);
        out.addAll(others);
        return out;
    }

    // --- queries --------------------------------------------------------------------------------

    static String ids(Set<Long> values) { StringBuilder s = new StringBuilder(); for (Long v : values) s.append(s.length() == 0 ? "" : ",").append(v.longValue()); return s.toString(); }

    /** WHERE fragment and bind arguments for one filter; `spending` limits to categorized rows on budget accounts. */
    static final class Where { final StringBuilder sql = new StringBuilder(); final List<String> args = new ArrayList<String>(); }

    Where where(Filter f, boolean spending, boolean onBudgetOnly) {
        Where w = new Where();
        w.sql.append(" WHERE t.deleted = 0 AND a.deleted = 0 AND substr(t.date, 1, 7) BETWEEN ? AND ?"); w.args.add(f.first); w.args.add(f.last);
        if (spending) w.sql.append(EXPENSE);
        if (onBudgetOnly) w.sql.append(" AND a.onBudget = 1");
        if (!f.excludedAccounts.isEmpty()) {
            w.sql.append(" AND a.guid NOT IN (");
            int n = 0; for (String g : f.excludedAccounts) { w.sql.append(n++ == 0 ? "?" : ",?"); w.args.add(g); }
            w.sql.append(")");
        }
        if (spending && !f.excludedCategories.isEmpty()) w.sql.append(" AND COALESCE(c._id, -1) NOT IN (").append(ids(f.excludedCategories)).append(")");
        if (!"all".equals(f.enteredBy) && !f.mine.isEmpty()) {
            w.sql.append(" AND ").append(DEVICE).append("me".equals(f.enteredBy) ? " IN (" : " NOT IN (");
            int n = 0; for (String d : f.mine) { w.sql.append(n++ == 0 ? "?" : ",?"); w.args.add(d); }
            w.sql.append(")");
        }
        if (spending && !f.excludedPayees.isEmpty()) w.sql.append(" AND COALESCE(t.payeeId, pt.payeeId, -1) NOT IN (").append(ids(f.excludedPayees)).append(")");
        return w;
    }

    /** Device letter of the last change (t.version = "<device>-<n>"). */
    static final String DEVICE = "substr(t.version, 1, instr(t.version, '-') - 1)";

    /** This phone's device letter plus the user's other devices from the accounts module ("My devices"). */
    public Set<String> mineDevices() {
        Set<String> out = new LinkedHashSet<String>(); String own = value("deviceId"); if (own != null) out.add(own);
        for (String d : context.getSharedPreferences("cb-plus-accounts", 0).getString("extra_devices", "").split("[,; ]+")) if (d.matches("[A-Z]{1,3}")) out.add(d);
        return out;
    }

    /** Spending by the device that entered (last changed) each transaction. */
    public List<Group> byDevice(Filter f) {
        Where w = where(f, true, true); List<Group> out = new ArrayList<Group>();
        Cursor c = db.rawQuery("SELECT " + DEVICE + ", -SUM(t.amount)" + SPENT_FROM + w.sql + " GROUP BY 1", w.args.toArray(new String[0]));
        try { while (c.moveToNext()) { if (c.getLong(1) == 0) continue; Group g = new Group(); g.name = c.getString(0); g.amount = c.getLong(1); out.add(g); } } finally { c.close(); }
        Collections.sort(out, ORDER); return out;
    }

    /** One expanded occurrence of a scheduled transaction. */

    /** Next occurrence after `date` for a YNAB4 frequency; null when it does not repeat. */
    static Calendar next(Calendar c, String frequency, int twiceDay, int firstDay) {
        Calendar n = (Calendar) c.clone();
        if ("Daily".equals(frequency)) n.add(Calendar.DAY_OF_MONTH, 1);
        else if ("Weekly".equals(frequency)) n.add(Calendar.DAY_OF_MONTH, 7);
        else if ("EveryOtherWeek".equals(frequency)) n.add(Calendar.DAY_OF_MONTH, 14);
        else if ("Every4Weeks".equals(frequency)) n.add(Calendar.DAY_OF_MONTH, 28);
        else if ("Monthly".equals(frequency)) n.add(Calendar.MONTH, 1);
        else if ("EveryOtherMonth".equals(frequency)) n.add(Calendar.MONTH, 2);
        else if ("Every3Months".equals(frequency)) n.add(Calendar.MONTH, 3);
        else if ("Every4Months".equals(frequency)) n.add(Calendar.MONTH, 4);
        else if ("TwiceAYear".equals(frequency)) n.add(Calendar.MONTH, 6);
        else if ("Yearly".equals(frequency) || "EveryOtherYear".equals(frequency)) {
            // AIR Date.fullYearUTC preserves overflow (29 Feb becomes 1 Mar), unlike Calendar.add.
            int y = n.get(Calendar.YEAR), m = n.get(Calendar.MONTH), d = n.get(Calendar.DAY_OF_MONTH);
            n.set(Calendar.DAY_OF_MONTH, 1); n.set(Calendar.YEAR, y + ("Yearly".equals(frequency) ? 1 : 2));
            n.set(Calendar.MONTH, m); n.set(Calendar.DAY_OF_MONTH, d);
        }
        else if ("TwiceAMonth".equals(frequency)) {
            int d = n.get(Calendar.DAY_OF_MONTH);
            int base = twiceDay > 0 ? twiceDay : (d <= 15 ? d : d - 15);
            if (d == base) n.set(Calendar.DAY_OF_MONTH, Math.min(d + 15, n.getActualMaximum(Calendar.DAY_OF_MONTH)));
            else { n.set(Calendar.DAY_OF_MONTH, 1); n.add(Calendar.MONTH, 1); n.set(Calendar.DAY_OF_MONTH, base); }
        } else return null;
        return n;
    }

    static Calendar day(String iso) { Calendar c = Calendar.getInstance(); c.clear(); c.set(Integer.parseInt(iso.substring(0, 4)), Integer.parseInt(iso.substring(5, 7)) - 1, Integer.parseInt(iso.substring(8, 10))); return c; }
    static String iso(Calendar c) { return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH)); }

    /** Scheduled transactions expanded from today to today + days, on accounts not excluded (transfers count on both sides). */
    /** Current balance of the accounts not excluded (on-budget only when onBudgetOnly). */
    public long balance(Filter f, boolean onBudgetOnly) {
        long sum = 0;
        Cursor c = db.rawQuery("SELECT guid, currentBal, onBudget FROM accountTable WHERE deleted = 0", null);
        try { while (c.moveToNext()) if (!f.excludedAccounts.contains(c.getString(0)) && (!onBudgetOnly || c.getInt(2) == 1)) sum += c.getLong(1); } finally { c.close(); }
        return sum;
    }

    static final String LEAF = " AND NOT EXISTS (SELECT 1 FROM txnTable child WHERE child.parentId=t._id AND child.deleted=0)";
    static final String EXPENSE = " AND (c.masterCategoryId IS NOT NULL OR t.categoryId IS NULL) AND COALESCE(ta.onBudget,0)=0" + LEAF;
    static final String JOINS = " FROM txnTable t JOIN accountTable a ON a._id=t.accountId"
        + " LEFT JOIN categoryTable c ON c._id=t.categoryId LEFT JOIN masterCategoryTable m ON m._id=c.masterCategoryId"
        + " LEFT JOIN txnTable pt ON pt._id=t.parentId LEFT JOIN accountTable ta ON ta._id=t.targAccountId";
    static final String SPENT_FROM = JOINS;

    /** Spending by category group in the range (net of refunds); split parts count, not the split parent. */
    public List<Group> byCategory(Filter f) {
        Where w = where(f, true, true);
        Map<Long, Group> groups = new LinkedHashMap<Long, Group>();
        Cursor c = db.rawQuery("SELECT COALESCE(m.name,'Uncategorized'), COALESCE(c.name,'Uncategorized'), -SUM(t.amount), COALESCE(c._id,-1), COALESCE(m._id,-1),m.guid,c.guid" + SPENT_FROM + w.sql + " GROUP BY m._id, c._id ORDER BY CASE WHEN m.guid='MasterCategory/__Hidden__' OR m.name='__Hidden__' THEN 1 ELSE 0 END,m.sequence,c.sequence", w.args.toArray(new String[0]));
        try {
            while (c.moveToNext()) {
                Group g = groups.get(c.getLong(4));
                if (g == null) { g = new Group(); g.name = c.getString(0); g.groupId=c.getLong(4); g.key="group:"+c.getString(5); groups.put(g.groupId, g); }
                Group child = new Group(); child.name = c.getString(1); child.id=c.getLong(3); child.groupId=g.groupId; child.key="category:"+c.getString(6); child.parentKey=g.key; child.amount = c.getLong(2);
                g.amount += child.amount; if (child.amount != 0) g.children.add(child);
            }
        } finally { c.close(); }
        List<Group> out = new ArrayList<Group>();
        for (Group g : groups.values()) if (g.amount != 0) { Collections.sort(g.children, ORDER); out.add(g); }
        Collections.sort(out, ORDER); return out;
    }

    /** Spending by payee in the range (the payee of a split is the parent's). */
    public List<Group> byPayee(Filter f) {
        Where w = where(f, true, true);
        List<Group> out = new ArrayList<Group>();
        Cursor c = db.rawQuery("SELECT COALESCE(p.name, '—'), -SUM(t.amount), COALESCE(p._id,-1),p.guid" + SPENT_FROM + " LEFT JOIN payeeTable p ON p._id = COALESCE(t.payeeId, pt.payeeId)"
            + w.sql + " GROUP BY COALESCE(t.payeeId, pt.payeeId)", w.args.toArray(new String[0]));
        try { while (c.moveToNext()) { if (c.getLong(1) == 0) continue; Group g = new Group(); g.name = c.getString(0); g.id=c.getLong(2); g.key="payee:"+c.getString(3); g.amount = c.getLong(1); out.add(g); } } finally { c.close(); }
        Collections.sort(out, ORDER); return out;
    }

    static final Comparator<Group> ORDER = new Comparator<Group>() { public int compare(Group a, Group b) { return a.amount == b.amount ? 0 : a.amount < b.amount ? 1 : -1; } };

    /** Per month: income, spending, spending by group, and end-of-month assets/debts (net worth). */
    public List<Month> monthly(Filter f) {
        Map<String, Month> out = new LinkedHashMap<String, Month>();
        for (String m : months(f.first, f.last)) { Month x = new Month(); x.month = m; out.put(m, x); }
        Where w = where(f, true, true);
        Cursor c = db.rawQuery("SELECT substr(t.date, 1, 7), COALESCE(m.name,'Uncategorized'), -SUM(t.amount)" + SPENT_FROM + w.sql + " GROUP BY 1, m._id", w.args.toArray(new String[0]));
        try { while (c.moveToNext()) { Month x = out.get(c.getString(0)); if (x == null) continue; x.spending += c.getLong(2); x.groups.put(c.getString(1), c.getLong(2)); } } finally { c.close(); }
        // Income shares account, payee and last-editor filters with expense leaves.
        Where wi = where(f, true, true);
        // Remove expense-only category/transfer clause; income categories themselves are not in the expense selector.
        wi.sql.replace(0,wi.sql.length(),wi.sql.toString().replace(EXPENSE, ""));
        c = db.rawQuery("SELECT substr(t.date,1,7),SUM(t.amount)"+JOINS+wi.sql
            +" AND c.guid IN ('Category/__ImmediateIncome__','Category/__DeferredIncome__')"+LEAF+" GROUP BY 1",wi.args.toArray(new String[0]));
        try { while(c.moveToNext()){Month x=out.get(c.getString(0));if(x!=null)x.income=c.getLong(1);} } finally {c.close();}
        // Net worth: every selected account, off-budget included; balance = all rows up to the month (split parts skipped).
        Filter all = new Filter(); all.first = "0000-00"; all.last = f.last; all.excludedAccounts.addAll(f.excludedAccounts);
        Where wn = where(all, false, false);
        c = db.rawQuery("SELECT a._id, substr(t.date, 1, 7), SUM(t.amount) FROM txnTable t JOIN accountTable a ON a._id = t.accountId"
            + wn.sql + " AND t.parentId IS NULL GROUP BY 1, 2 ORDER BY 2", wn.args.toArray(new String[0]));
        Map<Long, Long> balance = new HashMap<Long, Long>(); List<String> keys = new ArrayList<String>(out.keySet()); int next = 0;
        try {
            while (c.moveToNext()) {
                String month = c.getString(1);
                while (next < keys.size() && keys.get(next).compareTo(month) < 0) settle(out.get(keys.get(next++)), balance);
                Long old = balance.get(c.getLong(0)); balance.put(c.getLong(0), (old == null ? 0 : old) + c.getLong(2));
            }
        } finally { c.close(); }
        while (next < keys.size()) settle(out.get(keys.get(next++)), balance);
        return new ArrayList<Month>(out.values());
    }

    static void settle(Month m, Map<Long, Long> balance) {
        for (long b : balance.values()) { if (b >= 0) m.assets += b; else m.debts += b; }
    }
}

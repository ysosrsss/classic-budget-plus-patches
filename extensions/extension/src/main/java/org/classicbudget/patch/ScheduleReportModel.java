package org.classicbudget.patch;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.util.*;

/** Read-only schedule occurrences. Display transfers once; forecast only the selected budget accounts. */
public final class ScheduleReportModel {
    public static final class Entry {
        public String key, date, entered, account, accountGuid, target, targetGuid, payee, payeeGuid, memo, frequency;
        public long amount, forecast;
        public boolean transfer, onBudget, targetOnBudget, forecastKnown = true;
        public int cleared;
        boolean primary;
    }

    private static final String ACCOUNTS = " JOIN accountTable a ON a._id=s.accountId"
        + " LEFT JOIN accountTable ta ON ta._id=s.targAccountId AND ta.deleted=0"
        + " LEFT JOIN payeeTable p ON p._id=s.payeeId";

    public static List<Entry> future(SQLiteDatabase db, Set<String> excluded, String today, int days) {
        Calendar first = ReportData.day(today);
        Calendar last = (Calendar) first.clone();
        last.add(Calendar.DAY_OF_MONTH, days);
        Map<String, Entry> found = new LinkedHashMap<String, Entry>();
        String sql = "SELECT s.guid,s.date,s.frequency,s.amount,a.name,a.guid,a.onBudget,ta.name,ta.guid,ta.onBudget,"
            + "COALESCE(p.name,''),p.guid,s.twiceAMonthStartDay,COALESCE(s.memo,''),s.transferGuid,"
            + "(SELECT COUNT(*) FROM scheduledTxnTable child WHERE child.parentId=s._id AND child.deleted=0 AND child.targAccountId>0)"
            + " FROM scheduledTxnTable s" + ACCOUNTS
            + " WHERE s.deleted=0 AND s.parentId IS NULL AND a.deleted=0 ORDER BY s.date,s._id";
        try (Cursor cursor = db.rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                String raw = cursor.getString(1);
                if (raw == null || raw.length() < 10) continue;
                Calendar date = ReportData.day(raw);
                int initialDay = date.get(Calendar.DAY_OF_MONTH);
                int twiceDay = cursor.isNull(12) ? 0 : cursor.getInt(12);
                // Same supported iteration bound as the original preview; no historical rows are invented.
                for (int step = 0; date != null && !date.after(last) && step < 100000; step++) {
                    if (!date.before(first)) {
                        Entry entry = base(cursor);
                        entry.date = ReportData.iso(date);
                        entry.frequency = cursor.getString(2);
                        entry.memo = cursor.getString(13);
                        entry.forecastKnown = cursor.getInt(15) == 0;
                        entry.key = pairKey(cursor.getString(0), cursor.getString(14)) + "@" + entry.date;
                        if (scope(entry, excluded)) keep(found, entry);
                    }
                    date = ReportData.next(date, cursor.getString(2), twiceDay, initialDay);
                }
            }
        }
        return sorted(found, false);
    }

    public static List<Entry> history(SQLiteDatabase db, Set<String> excluded, String today, int days) {
        Calendar first = ReportData.day(today);
        first.add(Calendar.DAY_OF_MONTH, 1 - days);
        Map<String, Entry> found = new LinkedHashMap<String, Entry>();
        String sql = "SELECT s.guid,s.date,'',s.amount,a.name,a.guid,a.onBudget,ta.name,ta.guid,ta.onBudget,"
            + "COALESCE(p.name,''),p.guid,COALESCE(s.memo,''),s.dateEnteredFromSchedule,s.transferGuid,s.cleared"
            + " FROM txnTable s" + ACCOUNTS
            + " WHERE s.deleted=0 AND s.parentId IS NULL AND a.deleted=0"
            + " AND substr(s.dateEnteredFromSchedule,1,10) BETWEEN ? AND ? ORDER BY s.date DESC,s._id DESC";
        try (Cursor cursor = db.rawQuery(sql, new String[]{ReportData.iso(first), today})) {
            while (cursor.moveToNext()) {
                Entry entry = base(cursor);
                entry.date = cursor.getString(1);
                entry.memo = cursor.getString(12);
                entry.entered = cursor.getString(13);
                entry.cleared = cursor.getInt(15);
                entry.key = pairKey(cursor.getString(0), cursor.getString(14));
                if (scope(entry, excluded)) keep(found, entry);
            }
        }
        return sorted(found, true);
    }

    private static Entry base(Cursor cursor) {
        Entry entry = new Entry();
        entry.amount = cursor.getLong(3);
        entry.account = cursor.getString(4);
        entry.accountGuid = cursor.getString(5);
        entry.onBudget = cursor.getInt(6) != 0;
        entry.target = cursor.getString(7);
        entry.targetGuid = cursor.getString(8);
        entry.targetOnBudget = cursor.getInt(9) != 0;
        entry.transfer = entry.targetGuid != null;
        entry.payee = cursor.getString(10);
        entry.payeeGuid = cursor.getString(11);
        return entry;
    }

    private static String pairKey(String guid, String other) {
        if (other == null || other.isEmpty()) return guid;
        return guid.compareTo(other) <= 0 ? guid + ":" + other : other + ":" + guid;
    }

    private static void reverse(Entry entry) {
        String name = entry.account;
        entry.account = entry.target;
        entry.target = name;
        String guid = entry.accountGuid;
        entry.accountGuid = entry.targetGuid;
        entry.targetGuid = guid;
        boolean budget = entry.onBudget;
        entry.onBudget = entry.targetOnBudget;
        entry.targetOnBudget = budget;
        entry.amount = -entry.amount;
        entry.cleared = -1;
    }

    private static boolean scope(Entry entry, Set<String> excluded) {
        boolean source = !excluded.contains(entry.accountGuid);
        boolean target = entry.transfer && !excluded.contains(entry.targetGuid);
        if (!source && !target) return false;
        entry.primary = source && (!entry.transfer || !target || entry.amount <= 0);
        entry.forecast = source && entry.onBudget ? entry.amount : 0;
        if (target && entry.targetOnBudget) entry.forecast -= entry.amount;
        // If only the receiving account is selected, show its inflow. When both
        // accounts are visible, the paying side identifies the one displayed transfer.
        if (entry.transfer && (!source || target && entry.amount > 0)) reverse(entry);
        return true;
    }

    private static void keep(Map<String, Entry> found, Entry entry) {
        Entry previous = found.get(entry.key);
        if (previous != null) {
            boolean consistent = previous.forecast == entry.forecast && previous.amount == entry.amount;
            boolean known = previous.forecastKnown && entry.forecastKnown && consistent;
            if (previous.primary && !entry.primary) {
                previous.forecastKnown = known;
                return;
            }
            entry.forecastKnown = known;
        }
        found.put(entry.key, entry);
    }

    private static List<Entry> sorted(Map<String, Entry> found, boolean history) {
        List<Entry> result = new ArrayList<Entry>(found.values());
        Collections.sort(result, (left, right) -> {
            String a = history ? left.entered : left.date;
            String b = history ? right.entered : right.date;
            int order = a.compareTo(b);
            if (history) order = -order;
            return order == 0 ? left.key.compareTo(right.key) : order;
        });
        return result;
    }

    public static long forecast(List<Entry> entries) {
        long total = 0;
        for (Entry entry : entries) {
            if (!entry.forecastKnown) throw new IllegalStateException("Split transfers need a complete linked forecast");
            total += entry.forecast;
        }
        return total;
    }
}

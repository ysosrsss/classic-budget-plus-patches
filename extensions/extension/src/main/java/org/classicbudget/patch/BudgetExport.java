package org.classicbudget.patch;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the Plus budget outbox into the original diff exporter (core/backend/e).
 *
 * Three hooks, all in the same export run:
 *  items()     end of e.e(f): appends one monthlyCategoryBudget item per unsent outbox row;
 *  serialize() start of the item serializer e$a: writes those items field for field as desktop does
 *              (the original serializer knows only transactions, schedules, payees and locations);
 *  sent()      start of e.a(f, items[]): marks our rows sent and removes them from the array, because the
 *              original marker looks up a table per entity type and would fail on an unknown type.
 * The marker runs only after the diff file was written, inside the transaction that advances knowledge.
 */
public final class BudgetExport {
    static final String ITEM = "com.youneedabudget.ynab.core.backend.h$e";
    static final String TYPE = "monthlyCategoryBudget";
    /** Notes travel beside the item (the original item class has no note field). */
    static final Map<String, String> NOTES = new HashMap<String, String>();

    static SQLiteDatabase database(Object adapter) throws Exception {
        return (SQLiteDatabase) adapter.getClass().getMethod("c").invoke(adapter);
    }

    static boolean outboxExists(SQLiteDatabase db) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cbBudgetOutbox'", null)) { return c.moveToFirst(); }
    }

    static void set(Object item, String name, Object value) throws Exception {
        Field f = item.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(item, value);
    }

    static Object get(Object item, String name) {
        try { Field f = item.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(item); }
        catch (Exception e) { return null; }
    }

    /** Hook: returns the original items plus our budget items (same array component type). */
    public static Object[] items(Object adapter, Object[] original) {
        try {
            SQLiteDatabase db = database(adapter);
            if (!outboxExists(db)) return original;
            Class<?> type = Class.forName(ITEM);
            Constructor<?> make = type.getDeclaredConstructor();
            make.setAccessible(true);
            List<Object> extra = new ArrayList<Object>();
            List<String[]> superseded = new ArrayList<String[]>();
            String sql = "SELECT o.guid, o.version, o.amount, o.categoryGuid, o.month, o.note, o.overspend, b.version"
                + " FROM cbBudgetOutbox o LEFT JOIN monthlyBudgetTable b ON b.guid = o.guid AND b.deleted = 0 WHERE o.synced = 0";
            try (Cursor c = db.rawQuery(sql, null)) {
                while (c.moveToNext()) {
                    String guid = c.getString(0), version = c.getString(1);
                    // An import replaced our row (desktop or another phone won): do not resend the older value.
                    if (!version.equals(c.getString(7))) { superseded.add(new String[]{guid, version}); continue; }
                    Object item = make.newInstance();
                    set(item, "entityId", guid);
                    set(item, "entityType", TYPE);
                    set(item, "entityVersion", version);
                    set(item, "isTombstone", Boolean.FALSE);
                    set(item, "isResolvedConflict", Boolean.FALSE);
                    set(item, "budgeted", BigDecimal.valueOf(c.getLong(2), 2).toPlainString());
                    set(item, "categoryId", c.getString(3));
                    set(item, "parentMonthlyBudgetId", "MB/" + c.getString(4));
                    set(item, "overspendingHandling", c.isNull(6) ? null : c.getString(6));
                    synchronized (NOTES) { NOTES.put(guid + "|" + version, c.isNull(5) ? null : c.getString(5)); }
                    extra.add(item);
                }
            }
            for (String[] s : superseded) db.execSQL("UPDATE cbBudgetOutbox SET synced = 2 WHERE guid = ? AND version = ?", s);
            if (extra.isEmpty()) return original;
            Object[] out = (Object[]) Array.newInstance(type, original.length + extra.size());
            System.arraycopy(original, 0, out, 0, original.length);
            for (int i = 0; i < extra.size(); i++) out[original.length + i] = extra.get(i);
            return out;
        } catch (Throwable error) {
            // Never break the export of transactions; the budget rows stay unsent and are retried.
            DavProvider.diagnostic(error);
            return original;
        }
    }

    /** Hook: JSON object for our items, null for every other item (original serializer continues). */
    public static Object serialize(Object item) {
        if (!TYPE.equals(get(item, "entityType"))) return null;
        try {
            Class<?> objectType = Class.forName("com.d.a.o");
            Object json = objectType.newInstance();
            java.lang.reflect.Method text = objectType.getMethod("a", String.class, String.class);
            java.lang.reflect.Method number = objectType.getMethod("a", String.class, Number.class);
            java.lang.reflect.Method flag = objectType.getMethod("a", String.class, Boolean.class);
            String guid = (String) get(item, "entityId"), version = (String) get(item, "entityVersion");
            String note;
            synchronized (NOTES) { note = NOTES.get(guid + "|" + version); }
            // Field set and order as desktop writes them (see real .ydiff files).
            number.invoke(json, "budgeted", new BigDecimal((String) get(item, "budgeted")));
            text.invoke(json, "categoryId", get(item, "categoryId"));
            text.invoke(json, "overspendingHandling", get(item, "overspendingHandling"));
            text.invoke(json, "entityId", guid);
            text.invoke(json, "entityType", TYPE);
            text.invoke(json, "note", note);
            text.invoke(json, "entityVersion", version);
            flag.invoke(json, "isTombstone", Boolean.FALSE);
            text.invoke(json, "madeWithKnowledge", null);
            flag.invoke(json, "isResolvedConflict", Boolean.FALSE);
            text.invoke(json, "parentMonthlyBudgetId", get(item, "parentMonthlyBudgetId"));
            return json;
        } catch (Throwable error) {
            // A half-written budget item must not be published: fail this export run.
            throw new IllegalStateException("BUDGET_ITEM_SERIALIZE", error);
        }
    }

    /** Hook: marks our items sent and returns the array without them for the original marker. */
    public static Object[] sent(Object adapter, Object[] items) throws Exception {
        if (items == null) return null;
        List<Object> keep = new ArrayList<Object>();
        SQLiteDatabase db = null;
        for (Object item : items) {
            if (!TYPE.equals(get(item, "entityType"))) { keep.add(item); continue; }
            if (db == null) db = database(adapter);
            db.execSQL("UPDATE cbBudgetOutbox SET synced = 1 WHERE guid = ? AND version = ?",
                new Object[]{get(item, "entityId"), get(item, "entityVersion")});
        }
        if (keep.size() == items.length) return items;
        Object[] out = (Object[]) Array.newInstance(items.getClass().getComponentType(), keep.size());
        return keep.toArray(out);
    }
}

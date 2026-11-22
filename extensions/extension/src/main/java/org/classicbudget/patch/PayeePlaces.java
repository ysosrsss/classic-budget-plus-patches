package org.classicbudget.patch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Reads and versions the locations used by the original payee suggestion query. */
final class PayeePlaces {
    private static final String ACTIVE = "SELECT l._id,l.guid,l.version,l.payeeGuid,l.lat,l.lng,p.name "
        + "FROM payeeLocationsTable l JOIN payeeTable p ON p.guid=l.payeeGuid WHERE l.deleted=0 AND p.deleted=0";

    private PayeePlaces() { }

    static List<Place> read(Context context, String id) {
        return readRows(context, id, ACTIVE + " ORDER BY p.name COLLATE NOCASE,p.guid,l.lat,l.lng,l.guid", null);
    }

    static Place find(Context context, String id, String guid, String version) {
        List<Place> places = readRows(context, id, ACTIVE + " AND l.guid=? AND l.version=?", new String[]{guid, version});
        return places.size() == 1 ? places.get(0) : null;
    }

    private static List<Place> readRows(Context context, String id, String sql, String[] args) {
        for (int delay = 125; ; delay *= 2) {
            requireSelected(context, id);
            List<Place> places = new ArrayList<>();
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(context.getDatabasePath(id).getPath(), null,
                    SQLiteDatabase.OPEN_READONLY);
                 Cursor cursor = db.rawQuery(sql, args)) {
                while (cursor.moveToNext()) places.add(new Place(cursor));
                return places;
            } catch (SQLiteDatabaseLockedException busy) {
                // Native sync can briefly hold an exclusive write transaction on the budget.
                if (delay > 2000) throw busy;
                SystemClock.sleep(delay);
            }
        }
    }

    static void remove(Context context, String id, Place shown) throws Exception {
        change(context, id, shown, null);
    }

    static void move(Context context, String id, Place shown, MapPoint point) throws Exception {
        change(context, id, shown, Objects.requireNonNull(point));
    }

    private static void change(Context context, String id, Place shown, MapPoint point) throws Exception {
        requireSelected(context, id);
        Class<?> budgetType = Class.forName(BudgetStore.C + "cloud.g");
        Object budget = budgetType.getMethod("d", String.class).invoke(null, id);
        Object adapter = BudgetStore.no(budget, "i");
        SQLiteDatabase db = (SQLiteDatabase) BudgetStore.no(adapter, "c");
        if (!context.getDatabasePath(id).getAbsolutePath().equals(db.getPath())) throw new IllegalStateException();

        // Use the native transaction wrapper so the version counter and the location commit together.
        Runnable update = () -> {
            try {
                requireSelected(context, id);
                write(db, adapter, BudgetStore.no(budget, "e"), shown, point);
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        };
        BudgetStore.call(adapter, "a", new Class<?>[]{Runnable.class}, update);
        SyncHealth.retry(context, id);
    }

    private static void requireSelected(Context context, String id) {
        if (id == null || !id.equals(SyncHealth.selected(context))) throw new IllegalStateException();
    }

    private static void write(SQLiteDatabase db, Object adapter, Object clock, Place shown, MapPoint point) throws Exception {
        try (Cursor cursor = db.rawQuery(ACTIVE + " AND l.guid=?", new String[]{shown.guid})) {
            if (!cursor.moveToFirst() || !shown.sameAs(new Place(cursor)) || cursor.moveToNext())
                throw new IllegalStateException();
        }
        boolean deleted = point == null;
        double lat = deleted ? shown.lat : point.lat;
        double lng = deleted ? shown.lng : point.lng;
        if (!deleted && point.matches(shown.lat, shown.lng)) return;
        String knowledge = LocalEditKnowledge.capture(db);
        ConflictResolution.verifies(WritePolicy.knowledge(knowledge), shown.version);
        String version = (String) BudgetStore.no(clock, "f");
        Object builder = Class.forName(BudgetStore.C + "c.ab").getConstructor().newInstance();
        Class<?> adapterType = Class.forName(BudgetStore.C + "c.f");
        if (!deleted) {
            BudgetStore.call(builder, "a", new Class<?>[]{double.class}, lat);
            BudgetStore.call(builder, "b", new Class<?>[]{double.class}, lng);
        }
        BudgetStore.call(builder, "c", new Class<?>[]{boolean.class}, deleted);
        BudgetStore.call(builder, "d", new Class<?>[]{boolean.class}, false);
        BudgetStore.call(builder, "f", new Class<?>[]{String.class}, version);
        BudgetStore.call(builder, "g", new Class<?>[]{String.class}, knowledge);
        BudgetStore.call(builder, "a", new Class<?>[]{adapterType, long.class}, adapter, shown.id);

        // Native location diffs retain the parent and coordinates even on a tombstone.
        try (Cursor cursor = db.rawQuery("SELECT guid,version,payeeGuid,lat,lng,deleted,synced,madeWithKnowledge "
                + "FROM payeeLocationsTable WHERE _id=?", new String[]{Long.toString(shown.id)})) {
            if (!cursor.moveToFirst() || !shown.guid.equals(cursor.getString(0)) || !version.equals(cursor.getString(1))
                || !shown.payee.equals(cursor.getString(2)) || Double.compare(lat, cursor.getDouble(3)) != 0
                || Double.compare(lng, cursor.getDouble(4)) != 0 || cursor.getInt(5) != (deleted ? 1 : 0)
                || cursor.getInt(6) != 0 || !knowledge.equals(cursor.getString(7))) throw new IllegalStateException();
        }
    }

    static final class Place {
        final long id;
        final String guid, version, payee, name;
        final double lat, lng;

        Place(Cursor cursor) {
            id = cursor.getLong(0);
            guid = cursor.getString(1);
            version = cursor.getString(2);
            payee = cursor.getString(3);
            lat = cursor.getDouble(4);
            lng = cursor.getDouble(5);
            name = cursor.getString(6);
        }

        boolean sameAs(Place other) {
            return id == other.id && guid.equals(other.guid) && version.equals(other.version)
                && payee.equals(other.payee) && Objects.equals(name, other.name)
                && Double.compare(lat, other.lat) == 0 && Double.compare(lng, other.lng) == 0;
        }
    }
}

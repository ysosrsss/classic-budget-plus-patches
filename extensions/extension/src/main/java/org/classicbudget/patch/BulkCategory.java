package org.classicbudget.patch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** A category-only edit of selected ordinary transactions, using the native version clock and exporter. */
final class BulkCategory {
    // Chunk SQL bindings below the native SQLite variable limit while preserving a single write transaction.
    private static final int QUERY_CHUNK = 400;
    private static final String CATEGORY_SQL = "SELECT c._id,c.guid,c.version,c.name,m.name FROM categoryTable c"
        + " JOIN masterCategoryTable m ON m._id=c.masterCategoryId"
        + " WHERE c.deleted=0 AND m.deleted=0 AND m.guid NOT LIKE 'MasterCategory/\\_\\_%' ESCAPE '\\'"
        + " AND m.name<>'__Hidden__' AND c.guid NOT LIKE 'Category/%'";
    private static final String SELECTED_SQL = "SELECT t._id,t.guid,t.version,t.categoryId,"
        + " CASE WHEN t.deleted=0 AND a.deleted=0 AND a.onBudget=1 AND t.cleared IN (0,1)"
        + " AND COALESCE(t.parentId,0)=0 AND t.targAccountId IS NULL AND COALESCE(t.transferGuid,'')=''"
        + " AND (c.guid IS NULL OR c.guid NOT LIKE 'Category/%')"
        + " AND NOT EXISTS (SELECT 1 FROM txnTable child WHERE child.parentId=t._id AND child.deleted=0)"
        + " THEN 1 ELSE 0 END FROM txnTable t LEFT JOIN accountTable a ON a._id=t.accountId"
        + " LEFT JOIN categoryTable c ON c._id=t.categoryId WHERE t.guid IN (";

    static boolean enabled(String folder) {
        if (folder == null) return false;
        if (!PlusEdition.TEST_ONLY) return true;
        String name = folder.toUpperCase(Locale.ROOT);
        return name.contains("TEST") || name.contains("COPY");
    }

    static final class Category {
        final long id;
        final String guid, version, label;

        Category(Cursor cursor) {
            id = cursor.getLong(0);
            guid = cursor.getString(1);
            version = cursor.getString(2);
            label = cursor.getString(4) + " / " + cursor.getString(3);
        }

        boolean same(Category other) {
            return other != null && id == other.id && guid.equals(other.guid)
                && Objects.equals(version, other.version) && label.equals(other.label);
        }
    }

    static final class Plan {
        final Category category;
        final Map<String, String> revisions;
        final Map<String, Long> changes = new LinkedHashMap<>();
        int excluded;
        int unchanged;

        Plan(Category category, Map<String, String> revisions) {
            this.category = category;
            this.revisions = new LinkedHashMap<>(revisions);
        }
    }

    private BulkCategory() { }

    static List<Category> categories(SQLiteDatabase db) {
        List<Category> choices = new ArrayList<>();
        try (Cursor cursor = db.rawQuery(CATEGORY_SQL + " ORDER BY m.sequence,c.sequence,c._id", null)) {
            while (cursor.moveToNext()) choices.add(new Category(cursor));
        }
        return choices;
    }

    private static Category category(SQLiteDatabase db, String guid) {
        try (Cursor cursor = db.rawQuery(CATEGORY_SQL + " AND c.guid=?", new String[]{guid})) {
            return cursor.moveToFirst() ? new Category(cursor) : null;
        }
    }

    static Plan prepare(Context context, String budget, Map<String, String> revisions, Category category) throws Exception {
        check(budget.equals(SyncHealth.selected(context)));
        try (SQLiteDatabase db = ConflictResolution.read(context, budget)) {
            return inspect(db, revisions, category);
        }
    }

    static Plan inspect(SQLiteDatabase db, Map<String, String> revisions, Category category) throws Exception {
        check(category.same(category(db, category.guid)));
        Plan plan = new Plan(category, revisions);
        List<String> keys = new ArrayList<>(revisions.keySet());
        int seen = 0;
        for (int start = 0; start < keys.size(); start += QUERY_CHUNK) {
            int end = Math.min(start + QUERY_CHUNK, keys.size());
            String[] arguments = keys.subList(start, end).toArray(new String[0]);
            String sql = SELECTED_SQL + android.text.TextUtils.join(",", Collections.nCopies(arguments.length, "?")) + ")";
            try (Cursor cursor = db.rawQuery(sql, arguments)) {
                while (cursor.moveToNext()) {
                    seen++;
                    String guid = cursor.getString(1);
                    String version = cursor.getString(2);
                    check(Objects.equals(version, revisions.get(guid)));
                    if (cursor.getInt(4) == 0 || version == null) {
                        plan.excluded++;
                    } else if (!cursor.isNull(3) && cursor.getLong(3) == category.id) {
                        plan.unchanged++;
                    } else {
                        plan.changes.put(guid, cursor.getLong(0));
                    }
                }
            }
        }
        check(seen == revisions.size());
        return plan;
    }

    static int apply(Context context, String budget, Plan reviewed) throws Exception {
        check(budget.equals(SyncHealth.selected(context)));
        BudgetStore store = BudgetStore.open(context);
        check(enabled(store.budgetFolder) && budget.equals(new File(store.db.getPath()).getName()));
        Exception[] failed = {null};
        Runnable update = () -> {
            try {
                check(budget.equals(SyncHealth.selected(context)));
                apply(store, reviewed);
            } catch (Exception error) {
                failed[0] = error;
                throw new IllegalStateException(error);
            }
        };
        try {
            BudgetStore.call(store.adapter, "a", new Class<?>[]{Runnable.class}, update);
        } catch (Exception error) {
            throw failed[0] == null ? error : failed[0];
        }
        int changed = reviewed.changes.size();
        if (changed > 0) store.afterCommit();
        return changed;
    }

    private static void apply(BudgetStore store, Plan reviewed) throws Exception {
        Plan current = inspect(store.db, reviewed.revisions, reviewed.category);
        check(current.changes.equals(reviewed.changes) && current.excluded == reviewed.excluded
            && current.unchanged == reviewed.unchanged);
        if (current.changes.isEmpty()) return;
        String learned = LocalEditKnowledge.capture(store.db);
        Map<String, Integer> knowledge = WritePolicy.knowledge(learned);
        for (String guid : current.changes.keySet()) ConflictResolution.verifies(knowledge, current.revisions.get(guid));
        Object clock = BudgetStore.no(store.budget, "e");
        Class<?> builderType = Class.forName(BudgetStore.C + "c.al");
        Class<?> adapterType = Class.forName(BudgetStore.C + "c.f");
        for (long rowId : current.changes.values()) {
            String version = (String) BudgetStore.no(clock, "f");
            Object builder = builderType.getConstructor().newInstance();
            BudgetStore.call(builder, "b", new Class<?>[]{adapterType, long.class}, store.adapter, current.category.id);
            BudgetStore.call(builder, "f", new Class<?>[]{String.class}, version);
            BudgetStore.call(builder, "g", new Class<?>[]{String.class}, learned);
            BudgetStore.call(builder, "d", new Class<?>[]{boolean.class}, false);
            BudgetStore.call(builder, "a", new Class<?>[]{adapterType, long.class}, store.adapter, rowId);
        }
    }

    private static void check(boolean current) throws Exception {
        if (!current) throw new IllegalStateException("BULK_CATEGORY_STALE_SELECTION");
    }
}

package org.classicbudget.patch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONObject;

/** Atomic resolution of supported differences in a plain transaction. */
final class ConflictResolution {
    static final String SCHEMA = "CREATE TABLE IF NOT EXISTS cbConflictChoice("
        + "record TEXT PRIMARY KEY, version TEXT NOT NULL)";
    static final Set<String> WIRE_FIELDS = new HashSet<>(Arrays.asList(
        "accepted", "accountId", "amount", "categoryId", "checkNumber", "cleared", "date",
        "dateEnteredFromSchedule", "entityId", "entityType", "entityVersion", "flag", "isResolvedConflict",
        "isTombstone", "madeWithKnowledge", "memo", "parentTransactionId", "payeeId", "subTransactions",
        "targetAccountId", "transferTransactionId"));
    private static final Set<String> UPDATED_COLUMNS = new HashSet<>(Arrays.asList(
        "amount", "categoryId", "madeWithKnowledge", "memo", "synced", "version"));
    private static final String CATEGORY_GUID = "[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}";

    private ConflictResolution() { }

    static boolean enabled(String folder) {
        // Enable writes only after both clients and Windows have been qualified on isolated copies.
        return PlusEdition.TEST_ONLY && folder != null
            && folder.toUpperCase(java.util.Locale.ROOT).matches(".*(?:TEST|COPY).*");
    }

    static int unresolved(Context context, String id, JSONObject records) throws Exception {
        if (records.length() == 0) return 0;
        try (SQLiteDatabase db = read(context, id)) {
            int unresolved = 0;
            for (Iterator<String> keys = records.keys(); keys.hasNext();) {
                if (resolvedVersion(db, keys.next()) == null) unresolved++;
            }
            return unresolved;
        }
    }

    static SQLiteDatabase read(Context context, String id) {
        File file = context.getDatabasePath(id);
        return SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }

    static String resolvedVersion(SQLiteDatabase db, String key) {
        try (Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='cbConflictChoice'", null)) {
            if (!c.moveToFirst()) return null;
        }
        try (Cursor c = db.rawQuery("SELECT version FROM cbConflictChoice WHERE record=?", new String[]{key})) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    /** A fresh eligibility check is always repeated inside the native write transaction. */
    static Options options(SQLiteDatabase db, String key, JSONObject payload) {
        try {
            return validate(db, key, payload);
        } catch (Exception unsupported) {
            return null;
        }
    }

    static void require(boolean allowed) throws Exception {
        if (!allowed) throw new Exception("CONFLICT_REQUIRES_REVIEW");
    }

    static JSONObject transaction(SQLiteDatabase db, String guid) throws Exception {
        try (Cursor c = db.query("txnTable", null, "guid=?", new String[]{guid}, null, null, null)) {
            require(c.moveToFirst());
            JSONObject row = ConflictStore.row(c);
            require(!c.moveToNext());
            return row;
        }
    }

    static void verifyReference(SQLiteDatabase db, String table, JSONObject local, JSONObject incoming,
                                String localField, String wireField) throws Exception {
        if (local.isNull(localField)) {
            require(incoming.isNull(wireField));
            return;
        }
        try (Cursor c = db.query(table, new String[]{"guid"}, "_id=? AND deleted=0",
            new String[]{local.getString(localField)}, null, null, null)) {
            require(c.moveToFirst() && c.getString(0).equals(incoming.optString(wireField)) && !c.moveToNext());
        }
    }

    private static Options validate(SQLiteDatabase db, String key, JSONObject payload) throws Exception {
        require(resolvedVersion(db, key) == null);
        JSONObject family = payload.getJSONObject("local");
        JSONObject previous = family.getJSONObject("row");
        JSONObject incoming = payload.getJSONObject("incoming");
        String guid = previous.getString("guid");
        JSONObject current = transaction(db, guid);
        for (Iterator<String> fields = previous.keys(); fields.hasNext();) {
            String field = fields.next();
            if (!"synced".equals(field)) require(sameValue(previous.opt(field), current.opt(field)));
        }
        require("transaction".equals(incoming.optString("entityType")) && guid.equals(incoming.optString("entityId")));
        require(current.optInt("synced") == 1 && current.optInt("deleted") == 0 && !incoming.optBoolean("isTombstone"));
        require(current.optInt("resolvedConflict") == 0 && !incoming.optBoolean("isResolvedConflict"));
        require(current.isNull("parentId") && family.isNull("parent") && family.getJSONArray("subTransactions").length() == 0);
        require(incoming.isNull("parentTransactionId") && (incoming.isNull("subTransactions")
            || (incoming.optJSONArray("subTransactions") != null && incoming.getJSONArray("subTransactions").length() == 0)));
        try (Cursor c = db.rawQuery("SELECT 1 FROM txnTable WHERE parentId=? LIMIT 1", new String[]{current.getString("_id")})) {
            require(!c.moveToFirst());
        }
        require(current.isNull("transferGuid") && current.isNull("targAccountId")
            && incoming.isNull("targetAccountId") && incoming.isNull("transferTransactionId"));
        require(current.optInt("cleared") == 0 && "Uncleared".equals(incoming.optString("cleared")));
        require(current.optInt("accepted") == 0 && !incoming.optBoolean("accepted"));
        long localAmount = current.getLong("amount");
        long incomingAmount = new BigDecimal(incoming.getString("amount")).movePointRight(2).longValueExact();
        // The native importer stores an absent wire flag as integer zero in SQLite.
        require(current.optInt("flag", -1) == 0 && incoming.isNull("flag"));
        require(sameText(current, "date", incoming, "date")
            && sameText(current, "checkNo", incoming, "checkNumber")
            && sameText(current, "dateEnteredFromSchedule", incoming, "dateEnteredFromSchedule"));
        verifyReference(db, "accountTable", current, incoming, "accountId", "accountId");
        verifyReference(db, "payeeTable", current, incoming, "payeeId", "payeeId");
        Long localCategory = categoryId(current);
        String incomingCategory = incoming.isNull("categoryId") ? null : incoming.getString("categoryId");
        Category leftCategory = null;
        Category rightCategory = null;
        if (!sameCategory(db, localCategory, incomingCategory)) {
            require(localCategory != null && incomingCategory != null && incomingCategory.matches(CATEGORY_GUID));
            leftCategory = category(db, "c._id", Long.toString(localCategory));
            rightCategory = category(db, "c.guid", incomingCategory);
            require(leftCategory.id != rightCategory.id);
        }
        for (Iterator<String> fields = incoming.keys(); fields.hasNext();) {
            String field = fields.next();
            require(WIRE_FIELDS.contains(field) || incoming.isNull(field));
        }
        boolean noteDiffers = !sameText(current, "memo", incoming, "memo");
        require(noteDiffers || localAmount != incomingAmount || rightCategory != null);
        verifiesKnown(db, current.getString("version"));
        verifiesKnown(db, incoming.getString("entityVersion"));
        return new Options(current, localAmount, incomingAmount, leftCategory, rightCategory, noteDiffers);
    }

    private static Long categoryId(JSONObject row) throws Exception {
        return row.isNull("categoryId") ? null : row.getLong("categoryId");
    }

    private static boolean sameCategory(SQLiteDatabase db, Long local, String incoming) throws Exception {
        if (local == null) return incoming == null;
        try (Cursor c = db.query("categoryTable", new String[]{"guid"}, "_id=? AND deleted=0",
            new String[]{Long.toString(local)}, null, null, null)) {
            require(c.moveToFirst());
            return c.getString(0).equals(incoming);
        }
    }

    private static Category category(SQLiteDatabase db, String column, String value) throws Exception {
        // The native table uses named synthetic GUIDs for special income and split categories.
        // A changed category in this workflow must be a regular, existing budget category.
        try (Cursor c = db.rawQuery("SELECT c._id,c.guid,c.name,m.name FROM categoryTable c "
            + "JOIN masterCategoryTable m ON m._id=c.masterCategoryId WHERE " + column
            + "=? AND c.deleted=0 AND m.deleted=0", new String[]{value})) {
            require(c.moveToFirst() && c.getLong(0) > 0 && c.getString(1).matches(CATEGORY_GUID));
            Category result = new Category(c.getLong(0), c.getString(3) + " / " + c.getString(2));
            require(!c.moveToNext());
            return result;
        }
    }

    static boolean sameText(JSONObject first, String key, JSONObject second, String otherKey) {
        String a = first.isNull(key) ? "" : first.optString(key);
        String b = second.isNull(otherKey) ? "" : second.optString(otherKey);
        return a.equals(b);
    }

    static boolean sameValue(Object first, Object second) {
        // A stored JSON integer can be decoded as Integer while the cursor reads Long.
        if (first instanceof Number && second instanceof Number)
            return new BigDecimal(first.toString()).compareTo(new BigDecimal(second.toString())) == 0;
        return Objects.equals(first, second);
    }

    static void verifies(Map<String, Integer> learned, String version) throws Exception {
        require(version.matches(WritePolicy.SHORT_ID + "-[0-9]+"));
        int separator = version.indexOf('-');
        Integer known = learned.get(version.substring(0, separator));
        require(known != null && known >= Integer.parseInt(version.substring(separator + 1)));
    }

    static void verifiesKnown(SQLiteDatabase db, String version) throws Exception {
        require(version.matches(WritePolicy.SHORT_ID + "-[0-9]+"));
        int separator = version.indexOf('-');
        String device = version.substring(0, separator);
        int target = Integer.parseInt(version.substring(separator + 1));
        try (Cursor c = db.rawQuery("SELECT version FROM versionVector WHERE name=?", new String[]{device})) {
            if (c.moveToFirst() && c.getInt(0) >= target) return;
        }
        // The native local counter can be newer than the imported version vector for this device.
        try (Cursor c = db.rawQuery("SELECT value FROM counters WHERE name='version' AND EXISTS "
            + "(SELECT 1 FROM keyValue WHERE name='deviceId' AND value=?)", new String[]{device})) {
            require(c.moveToFirst() && c.getInt(0) >= target);
        }
    }

    static boolean resolve(Context context, String id, String key, String expected, String memo,
                           boolean useIncomingAmount, boolean useIncomingCategory) throws Exception {
        require(id.equals(SyncHealth.selected(context)) && memo != null && memo.length() <= 4000);
        JSONObject record = ConflictStore.active(context, id).getJSONObject(key);
        require(record.getString("sha256").equals(expected));
        JSONObject payload = new JSONObject(record.getString("payload"));
        return write(context, id, (db, adapter, knowledge) ->
            apply(db, adapter, knowledge, key, payload, memo, useIncomingAmount, useIncomingCategory));
    }

    interface Update {
        void run(SQLiteDatabase db, Object adapter, Object knowledge) throws Exception;
    }

    static boolean write(Context context, String id, Update operation) throws Exception {
        require(id.equals(SyncHealth.selected(context)));
        BudgetStore store = BudgetStore.open(context);
        require(enabled(store.budgetFolder) && id.equals(new File(store.db.getPath()).getName()));
        final Exception[] problem = new Exception[1];
        Runnable update = () -> {
            try {
                operation.run(store.db, store.adapter, BudgetStore.no(store.budget, "e"));
            } catch (Exception failure) {
                problem[0] = failure;
                throw new IllegalStateException(failure);
            }
        };
        try {
            BudgetStore.call(store.adapter, "a", new Class<?>[]{Runnable.class}, update);
        } catch (Exception failure) {
            throw problem[0] == null ? failure : problem[0];
        }
        // The SQLite transaction has committed. Notification or network failure cannot undo the choice.
        try { BudgetStore.no(store.adapter, "d"); } catch (Exception ignored) { }
        SyncHealth.prefs(context).edit().putLong(id + ":conflictChanged", System.currentTimeMillis()).apply();
        return store.syncNow();
    }

    private static void apply(SQLiteDatabase db, Object adapter, Object knowledge, String key, JSONObject payload,
                              String memo, boolean useIncomingAmount, boolean useIncomingCategory) throws Exception {
        Options options = validate(db, key, payload);
        require(!useIncomingAmount || options.amountDiffers());
        require(!useIncomingCategory || options.categoryDiffers());
        JSONObject current = options.current;
        long amount = useIncomingAmount ? options.incomingAmount : options.localAmount;
        Long category = useIncomingCategory ? Long.valueOf(options.incomingCategory.id) : categoryId(current);
        String learned = LocalEditKnowledge.capture(db);
        Map<String, Integer> knowledgeBefore = WritePolicy.knowledge(learned);
        verifies(knowledgeBefore, current.getString("version"));
        verifies(knowledgeBefore, payload.getJSONObject("incoming").getString("entityVersion"));
        String version = (String) BudgetStore.no(knowledge, "f");
        Object builder = Class.forName(BudgetStore.C + "c.al").getConstructor().newInstance();
        Class<?> adapterType = Class.forName(BudgetStore.C + "c.f");
        if (useIncomingAmount) BudgetStore.call(builder, "d", new Class<?>[]{long.class}, amount);
        if (useIncomingCategory) BudgetStore.call(builder, "b", new Class<?>[]{adapterType, long.class}, adapter, category);
        BudgetStore.call(builder, "d", new Class<?>[]{String.class}, memo);
        BudgetStore.call(builder, "f", new Class<?>[]{String.class}, version);
        BudgetStore.call(builder, "g", new Class<?>[]{String.class}, learned);
        BudgetStore.call(builder, "d", new Class<?>[]{boolean.class}, false);
        BudgetStore.call(builder, "a", new Class<?>[]{adapterType, long.class}, adapter, current.getLong("_id"));
        JSONObject saved = transaction(db, current.getString("guid"));
        require(version.equals(saved.optString("version")) && saved.getInt("synced") == 0
            && learned.equals(saved.optString("madeWithKnowledge")) && amount == saved.getLong("amount")
            && Objects.equals(category, categoryId(saved))
            && sameText(saved, "memo", new JSONObject().put("memo", memo), "memo"));
        for (Iterator<String> fields = current.keys(); fields.hasNext();) {
            String field = fields.next();
            if (!UPDATED_COLUMNS.contains(field)) require(sameValue(current.opt(field), saved.opt(field)));
        }
        db.execSQL(SCHEMA);
        db.execSQL("INSERT INTO cbConflictChoice(record, version) VALUES(?, ?)", new Object[]{key, version});
    }

    static final class Options {
        final JSONObject current;
        final long localAmount, incomingAmount;
        final Category localCategory, incomingCategory;
        final boolean noteDiffers;

        Options(JSONObject current, long localAmount, long incomingAmount, Category localCategory,
                Category incomingCategory, boolean noteDiffers) {
            this.current = current;
            this.localAmount = localAmount;
            this.incomingAmount = incomingAmount;
            this.localCategory = localCategory;
            this.incomingCategory = incomingCategory;
            this.noteDiffers = noteDiffers;
        }

        boolean amountDiffers() { return localAmount != incomingAmount; }
        boolean categoryDiffers() { return incomingCategory != null; }
    }

    static final class Category {
        final long id;
        final String name;

        Category(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }
}

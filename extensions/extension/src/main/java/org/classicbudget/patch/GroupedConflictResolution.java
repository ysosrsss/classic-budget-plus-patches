package org.classicbudget.patch;

import static org.classicbudget.patch.ConflictResolution.require;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** A complete version of a transfer, split, or deletion conflict is selected and published atomically. */
final class GroupedConflictResolution {
    enum Kind { TRANSFER, SPLIT, DELETION }
    private static final Set<String> MUTABLE = new HashSet<>(Arrays.asList(
        "amount", "categoryId", "deleted", "madeWithKnowledge", "memo", "synced", "version"));

    private GroupedConflictResolution() { }

    static Plan options(SQLiteDatabase db, JSONObject records, String key) {
        try {
            return validate(db, records, key);
        } catch (Exception unsupported) {
            return null;
        }
    }

    private static Plan validate(SQLiteDatabase db, JSONObject records, String key) throws Exception {
        Entry entry = entry(db, records, key);
        JSONObject row = entry.root.current;
        JSONObject wire = entry.root.incoming;
        JSONArray children = entry.family.getJSONArray("subTransactions");
        require(row.isNull("parentId") && entry.family.isNull("parent") && wire.isNull("parentTransactionId"));
        if (!row.isNull("transferGuid")) {
            require(children.length() == 0 && childCount(db, row) == 0 && emptyChildren(wire));
            require(!entry.root.deleted(false) && !entry.root.deleted(true));
            String otherGuid = row.getString("transferGuid");
            Entry other = partner(db, records, entry, otherGuid);
            JSONObject opposite = other.root.current;
            JSONObject otherWire = other.root.incoming;
            require(!other.root.deleted(false) && !other.root.deleted(true) && opposite.isNull("parentId")
                && otherWire.isNull("parentTransactionId") && emptyChildren(otherWire)
                && childCount(db, opposite) == 0);
            require(row.getString("guid").equals(opposite.getString("transferGuid"))
                && row.getLong("accountId") == opposite.getLong("targAccountId")
                && opposite.getLong("accountId") == row.getLong("targAccountId")
                && row.getLong("accountId") != opposite.getLong("accountId"));
            require(otherGuid.equals(wire.getString("transferTransactionId"))
                && row.getString("guid").equals(otherWire.getString("transferTransactionId")));
            ConflictResolution.verifyReference(db, "accountTable", row, wire, "targAccountId", "targetAccountId");
            ConflictResolution.verifyReference(db, "accountTable", opposite, otherWire, "targAccountId", "targetAccountId");
            require(oppositeAmounts(entry.root, other.root, false) && oppositeAmounts(entry.root, other.root, true));
            return entry.key.compareTo(other.key) < 0 ? new Plan(Kind.TRANSFER, entry, other)
                : new Plan(Kind.TRANSFER, other, entry);
        }
        require(row.isNull("targAccountId") && wire.isNull("targetAccountId") && wire.isNull("transferTransactionId"));
        require(childCount(db, row) == children.length());
        if (children.length() > 0) {
            validateSplit(db, entry, children);
            return new Plan(Kind.SPLIT, entry);
        }
        require(emptyChildren(wire) && entry.root.deleted(false) != entry.root.deleted(true)
            && !"Category/__Split__".equals(categoryGuid(db, entry.root.category(false)))
            && !"Category/__Split__".equals(categoryGuid(db, entry.root.category(true))));
        return new Plan(Kind.DELETION, entry);
    }

    private static Entry entry(SQLiteDatabase db, JSONObject records, String key) throws Exception {
        require(ConflictResolution.resolvedVersion(db, key) == null);
        JSONObject record = records.getJSONObject(key);
        JSONObject payload = new JSONObject(record.getString("payload"));
        JSONObject family = payload.getJSONObject("local");
        Row root = row(db, family.getJSONObject("row"), payload.getJSONObject("incoming"));
        verifyRootFields(db, root);
        return new Entry(key, record.getString("sha256"), family, root);
    }

    private static Entry partner(SQLiteDatabase db, JSONObject records, Entry entry, String guid) throws Exception {
        Entry match = null;
        for (Iterator<String> keys = records.keys(); keys.hasNext();) {
            String key = keys.next();
            JSONObject incoming = new JSONObject(records.getJSONObject(key).getString("payload")).getJSONObject("incoming");
            if (!guid.equals(incoming.optString("entityId"))) continue;
            if (!ConflictResolution.sameText(entry.root.incoming, "madeWithKnowledge", incoming, "madeWithKnowledge")) continue;
            require(match == null);
            match = entry(db, records, key);
        }
        require(match != null && ConflictResolution.sameText(entry.root.current, "madeWithKnowledge",
            match.root.current, "madeWithKnowledge"));
        return match;
    }

    private static void validateSplit(SQLiteDatabase db, Entry entry, JSONArray children) throws Exception {
        JSONObject root = entry.root.current;
        JSONObject wire = entry.root.incoming;
        require(!entry.root.deleted(false) && !entry.root.deleted(true)
            && "Category/__Split__".equals(categoryGuid(db, entry.root.category(false)))
            && "Category/__Split__".equals(categoryGuid(db, entry.root.category(true))));
        JSONArray incoming = wire.getJSONArray("subTransactions");
        Map<String, JSONObject> parts = new HashMap<>();
        for (int i = 0; i < incoming.length(); i++) {
            JSONObject part = incoming.getJSONObject(i);
            require(parts.put(part.getString("entityId"), part) == null);
        }
        require(parts.size() == children.length());
        BigDecimal localTotal = BigDecimal.ZERO;
        BigDecimal remoteTotal = BigDecimal.ZERO;
        for (int i = 0; i < children.length(); i++) {
            JSONObject previous = children.getJSONObject(i);
            JSONObject part = parts.remove(previous.getString("guid"));
            require(part != null);
            Row child = row(db, previous, part);
            require(!child.deleted(false) && !child.deleted(true)
                && child.current.getLong("parentId") == root.getLong("_id")
                && child.current.getLong("accountId") == root.getLong("accountId")
                && child.current.isNull("transferGuid") && child.current.isNull("targAccountId")
                && part.isNull("transferTransactionId") && part.isNull("targetAccountId") && emptyChildren(part)
                && root.getString("guid").equals(part.getString("parentTransactionId"))
                && root.getString("version").equals(child.current.getString("version"))
                && wire.getString("entityVersion").equals(part.getString("entityVersion")));
            require(part.isNull("accountId") && part.isNull("payeeId") && part.isNull("date")
                && part.isNull("cleared") && part.isNull("checkNumber") && part.isNull("flag")
                && part.isNull("accepted") && part.isNull("dateEnteredFromSchedule"));
            entry.rows.add(child);
            localTotal = localTotal.add(BigDecimal.valueOf(child.amount(false)));
            remoteTotal = remoteTotal.add(BigDecimal.valueOf(child.amount(true)));
        }
        require(parts.isEmpty() && localTotal.compareTo(BigDecimal.valueOf(entry.root.amount(false))) == 0
            && remoteTotal.compareTo(BigDecimal.valueOf(entry.root.amount(true))) == 0);
    }

    private static boolean oppositeAmounts(Row first, Row second, boolean remote) {
        long amount = second.amount(remote);
        return amount != Long.MIN_VALUE && first.amount(remote) == -amount;
    }

    private static Row row(SQLiteDatabase db, JSONObject snapshot, JSONObject incoming) throws Exception {
        JSONObject current = ConflictResolution.transaction(db, snapshot.getString("guid"));
        require(current.getInt("synced") == 1 && current.optInt("resolvedConflict") == 0
            && !incoming.optBoolean("isResolvedConflict") && current.getString("guid").equals(incoming.getString("entityId"))
            && "transaction".equals(incoming.getString("entityType")));
        sameExcept(snapshot, current, java.util.Collections.singleton("synced"));
        for (Iterator<String> fields = incoming.keys(); fields.hasNext();) {
            String field = fields.next();
            require(ConflictResolution.WIRE_FIELDS.contains(field) || incoming.isNull(field));
        }
        ConflictResolution.verifiesKnown(db, current.getString("version"));
        ConflictResolution.verifiesKnown(db, incoming.getString("entityVersion"));
        Long category = current.isNull("categoryId") ? null : current.getLong("categoryId");
        boolean deleted = incoming.optBoolean("isTombstone");
        Long target = deleted ? category : incomingCategory(db, incoming);
        return new Row(current, incoming, category, target,
            deleted ? current.getLong("amount") : new BigDecimal(incoming.getString("amount")).movePointRight(2).longValueExact(),
            name(db, "categoryTable", category), name(db, "categoryTable", target),
            name(db, "accountTable", current.getLong("accountId")));
    }

    private static void verifyRootFields(SQLiteDatabase db, Row row) throws Exception {
        JSONObject current = row.current;
        JSONObject incoming = row.incoming;
        require(current.optLong("accountId") > 0);
        if (row.deleted(true)) return;
        ConflictResolution.verifyReference(db, "accountTable", current, incoming, "accountId", "accountId");
        ConflictResolution.verifyReference(db, "payeeTable", current, incoming, "payeeId", "payeeId");
        require(ConflictResolution.sameText(current, "date", incoming, "date")
            && ConflictResolution.sameText(current, "checkNo", incoming, "checkNumber")
            && ConflictResolution.sameText(current, "dateEnteredFromSchedule", incoming, "dateEnteredFromSchedule")
            && current.optInt("flag", -1) == 0 && incoming.isNull("flag")
            && current.optInt("cleared") == 0 && "Uncleared".equals(incoming.optString("cleared"))
            && current.optInt("accepted") == (incoming.optBoolean("accepted") ? 1 : 0));
    }

    private static boolean emptyChildren(JSONObject wire) {
        return wire.isNull("subTransactions") || (wire.optJSONArray("subTransactions") != null
            && wire.optJSONArray("subTransactions").length() == 0);
    }

    private static int childCount(SQLiteDatabase db, JSONObject row) throws Exception {
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM txnTable WHERE parentId=?", new String[]{row.getString("_id")})) {
            require(c.moveToFirst());
            return c.getInt(0);
        }
    }

    private static Long incomingCategory(SQLiteDatabase db, JSONObject wire) throws Exception {
        if (wire.isNull("categoryId")) return null;
        try (Cursor c = db.query("categoryTable", new String[]{"_id"}, "guid=? AND deleted=0",
            new String[]{wire.getString("categoryId")}, null, null, null)) {
            require(c.moveToFirst());
            long id = c.getLong(0);
            require(!c.moveToNext());
            return id;
        }
    }

    private static String categoryGuid(SQLiteDatabase db, Long id) throws Exception {
        if (id == null) return null;
        try (Cursor c = db.query("categoryTable", new String[]{"guid"}, "_id=? AND deleted=0",
            new String[]{id.toString()}, null, null, null)) {
            require(c.moveToFirst());
            return c.getString(0);
        }
    }

    private static String name(SQLiteDatabase db, String table, Long id) {
        return id == null ? "" : ConflictStore.name(db, table, "_id", id.toString());
    }

    private static void sameExcept(JSONObject before, JSONObject after, Set<String> exceptions) throws Exception {
        for (Iterator<String> fields = before.keys(); fields.hasNext();) {
            String field = fields.next();
            if (!exceptions.contains(field)) require(ConflictResolution.sameValue(before.opt(field), after.opt(field)));
        }
    }

    static boolean resolve(Context context, String id, String key, String revision, boolean remote) throws Exception {
        JSONObject records = ConflictStore.active(context, id);
        return ConflictResolution.write(context, id, (db, adapter, knowledge) -> {
            Plan plan = validate(db, records, key);
            require(plan.revision().equals(revision));
            String learned = LocalEditKnowledge.capture(db);
            Map<String, Integer> known = WritePolicy.knowledge(learned);
            for (Entry entry : plan.entries) {
                for (Row row : entry.rows) {
                    ConflictResolution.verifies(known, row.current.getString("version"));
                    ConflictResolution.verifies(known, row.incoming.getString("entityVersion"));
                }
            }
            db.execSQL(ConflictResolution.SCHEMA);
            for (Entry entry : plan.entries) {
                // The original serializes split parts inside their parent version. Transfer legs are separate items.
                String version = (String) BudgetStore.no(knowledge, "f");
                for (Row row : entry.rows) write(db, adapter, row, remote, version, learned);
                db.execSQL("INSERT INTO cbConflictChoice(record, version) VALUES(?, ?)", new Object[]{entry.key, version});
            }
        });
    }

    private static void write(SQLiteDatabase db, Object adapter, Row row, boolean remote, String version,
                              String learned) throws Exception {
        Object builder = Class.forName(BudgetStore.C + "c.al").getConstructor().newInstance();
        Class<?> adapterType = Class.forName(BudgetStore.C + "c.f");
        Class<?>[] bool = {boolean.class};
        Class<?>[] text = {String.class};
        Long category = row.category(remote);
        if (row.amount(remote) != row.amount(false))
            BudgetStore.call(builder, "d", new Class<?>[]{long.class}, row.amount(remote));
        if (!Objects.equals(category, row.category(false)))
            BudgetStore.call(builder, "b", new Class<?>[]{adapterType, String.class}, adapter, categoryGuid(db, category));
        if (!Objects.equals(row.memo(remote), row.memo(false))) BudgetStore.call(builder, "d", text, row.memo(remote));
        if (row.deleted(remote) != row.deleted(false)) BudgetStore.call(builder, "c", bool, row.deleted(remote));
        BudgetStore.call(builder, "f", text, version);
        BudgetStore.call(builder, "g", text, learned);
        BudgetStore.call(builder, "d", bool, false);
        BudgetStore.call(builder, "a", new Class<?>[]{adapterType, long.class}, adapter, row.current.getLong("_id"));
        JSONObject saved = ConflictResolution.transaction(db, row.current.getString("guid"));
        Long savedCategory = saved.isNull("categoryId") ? null : saved.getLong("categoryId");
        require(version.equals(saved.optString("version")) && learned.equals(saved.optString("madeWithKnowledge"))
            && saved.getInt("synced") == 0 && saved.getLong("amount") == row.amount(remote)
            && saved.getInt("deleted") == (row.deleted(remote) ? 1 : 0) && Objects.equals(category, savedCategory)
            && Objects.equals(row.memo(remote), saved.isNull("memo") ? null : saved.getString("memo")));
        sameExcept(row.current, saved, MUTABLE);
    }

    static final class Plan {
        final Kind kind;
        final List<Entry> entries;

        Plan(Kind kind, Entry... entries) {
            this.kind = kind;
            this.entries = Arrays.asList(entries);
        }

        String key() { return entries.get(0).key; }

        String revision() {
            StringBuilder value = new StringBuilder();
            for (Entry entry : entries) value.append(entry.key).append(entry.hash);
            return value.toString();
        }
    }

    static final class Entry {
        final String key, hash;
        final JSONObject family;
        final Row root;
        final List<Row> rows = new ArrayList<>();

        Entry(String key, String hash, JSONObject family, Row root) {
            this.key = key;
            this.hash = hash;
            this.family = family;
            this.root = root;
            rows.add(root);
        }
    }

    static final class Row {
        final JSONObject current, incoming;
        final Long localCategory, incomingCategory;
        final long incomingAmount;
        final String localCategoryName, incomingCategoryName, accountName;

        Row(JSONObject current, JSONObject incoming, Long localCategory, Long incomingCategory, long incomingAmount,
            String localCategoryName, String incomingCategoryName, String accountName) {
            this.current = current;
            this.incoming = incoming;
            this.localCategory = localCategory;
            this.incomingCategory = incomingCategory;
            this.incomingAmount = incomingAmount;
            this.localCategoryName = localCategoryName;
            this.incomingCategoryName = incomingCategoryName;
            this.accountName = accountName;
        }

        Long category(boolean remote) { return remote ? incomingCategory : localCategory; }
        long amount(boolean remote) { return remote ? incomingAmount : current.optLong("amount"); }
        boolean deleted(boolean remote) { return remote ? incoming.optBoolean("isTombstone") : current.optInt("deleted") != 0; }
        String memo(boolean remote) {
            JSONObject row = remote && !deleted(true) ? incoming : current;
            return row.isNull("memo") ? null : row.optString("memo");
        }
    }
}

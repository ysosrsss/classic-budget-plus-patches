package org.classicbudget.patch;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * Module "Podpowiadanie kont" (account suggestions). Two hooks in the original
 * code replace a SQL constant with a call here:
 *  - TxnEntryActivity: account and category after applying a payee (one parameter: payee name);
 *  - core/backend/o.a(f): default account of a new transaction (no parameters).
 * Settings are local to this phone (SharedPreferences), never in the budget.
 * Mode "original" returns the original SQL byte for byte.
 */
public final class AccountSuggest {
    public static final String PREFS = "cb-plus-accounts";
    public static final String ORIGINAL = "original", MINE = "mine", MY_ACCOUNTS = "my_accounts";
    /** Default preset when the module is present: prefer my entries, honour "my accounts". */
    static final String DEFAULT_MODE = MY_ACCOUNTS;

    public static final String ORIGINAL_PAYEE_SQL = "SELECT t.accountId FROM   txnTable t        INNER JOIN payeeTable p          ON t.payeeId = p._id        INNER JOIN accountTable a          ON a._id = t.accountId WHERE  p.name = ?   AND  a.deleted = 0   AND  a.hidden = 0 ORDER  BY t._id DESC LIMIT  1 ";
    public static final String ORIGINAL_DEFAULT_SQL = "SELECT     t.accountId FROM       txnTable t LEFT OUTER JOIN accountTable a ON t.accountId = a._id WHERE      a.hidden = 0 AND t.deleted = 0 ORDER BY   t._id DESC LIMIT 1";

    static SharedPreferences prefs() {
        Context context = com.youneedabudget.ynab.core.app.d.i();
        return context == null ? null : context.getSharedPreferences(PREFS, 0);
    }

    static String mode(String key) {
        SharedPreferences p = prefs(); String value = p == null ? ORIGINAL : p.getString(key, DEFAULT_MODE);
        return ORIGINAL.equals(value) || MINE.equals(value) || MY_ACCOUNTS.equals(value) ? value : ORIGINAL;
    }

    /** Account GUIDs that are not "mine" (e.g. the partner's accounts). Never suggested in MY_ACCOUNTS mode. */
    static List<String> excluded() {
        SharedPreferences p = prefs(); List<String> out = new ArrayList<String>();
        if (p == null) return out;
        for (String g : p.getStringSet("excluded", Collections.<String>emptySet())) if (g.matches("[A-Za-z0-9-]{1,64}")) out.add(g);
        Collections.sort(out); return out;
    }

    /** Extra short device IDs counted as "mine" (old phone, desktop), besides this phone's own ID. */
    static List<String> extraDevices() {
        SharedPreferences p = prefs(); List<String> out = new ArrayList<String>();
        if (p == null) return out;
        for (String d : p.getString("extra_devices", "").split("[,; ]+")) if (d.matches("[A-Z]{1,3}")) out.add(d);
        return out;
    }

    static String quoted(List<String> values) {
        StringBuilder s = new StringBuilder();
        for (String v : values) s.append(s.length() == 0 ? "" : ",").append('\'').append(v).append('\'');
        return s.toString();
    }

    /** t.version is "<device>-<n>": the device that last changed the row. */
    static String mine() {
        String devices = "(SELECT value FROM keyValue WHERE name = 'deviceId')";
        List<String> extra = extraDevices(); if (!extra.isEmpty()) devices += "," + quoted(extra);
        return "substr(t.version, 1, instr(t.version, '-') - 1) IN (" + devices + ")";
    }

    static String allowed(boolean myAccounts) {
        String sql = "a.deleted = 0 AND a.hidden = 0";
        List<String> ex = excluded();
        if (myAccounts && !ex.isEmpty()) sql += " AND a.guid NOT IN (" + quoted(ex) + ")";
        return sql;
    }

    // --- payee rules (like Actual Budget rules): "payee X -> account Y / category Z", local to this phone ---

    /** Original category suggestion (core/app/a/a.b(f,String)J), byte-identical; ?1 = payee name. */
    public static final String ORIGINAL_CATEGORY_SQL = "SELECT t.categoryId, t._id FROM   txnTable t        INNER JOIN payeeTable p          ON t.payeeId = p._id        INNER JOIN categoryTable c          ON c._id = t.categoryId        LEFT JOIN  masterCategoryTable m          ON m._id = c.masterCategoryId WHERE  p.name = ?1   AND  m.name != \"__Hidden__\"    AND (m.guid != \"MasterCategory/__Hidden__\"      OR m.guid IS NULL)  UNION ALL  SELECT t.categoryId, t._id FROM   txnTable t        INNER JOIN payeeTable p          ON t.payeeId = p._id        INNER JOIN categoryTable c          ON c._id = t.categoryId         AND c.masterCategoryId IS NULL          AND c.guid != \"Category/__Split__\"WHERE  p.name = ?1 ORDER  BY t._id DESC LIMIT  1 ";

    /** Rules as JSON [{"match":"is|contains","text":"...","account":"guid|''","category":"guid|''"}], in order. */
    public static org.json.JSONArray rules() {
        SharedPreferences p = prefs();
        try { return new org.json.JSONArray(p == null ? "[]" : p.getString("rules", "[]")); } catch (org.json.JSONException e) { return new org.json.JSONArray(); }
    }
    static String q(String s) { return "'" + s.replace("'", "''") + "'"; }

    /** CASE expression giving the rule's target GUID for payee ?1, or null when no rule targets this field. */
    static String ruleCase(String field) {
        StringBuilder s = new StringBuilder(); org.json.JSONArray rules = rules();
        for (int i = 0; i < rules.length() && i < 200; i++) {
            org.json.JSONObject r = rules.optJSONObject(i); if (r == null) continue;
            String text = r.optString("text", ""), target = r.optString(field, "");
            if (text.length() == 0 || !target.matches("[A-Za-z0-9/_-]{1,80}")) continue;
            String cond = "contains".equals(r.optString("match")) ? "(instr(?1, " + q(text) + ") > 0 OR instr(lower(?1), lower(" + q(text) + ")) > 0)" : "(?1 = " + q(text) + " OR lower(?1) = lower(" + q(text) + "))";
            s.append(" WHEN ").append(cond).append(" THEN ").append(q(target));
        }
        return s.length() == 0 ? null : "CASE" + s + " END";
    }

    /** Category after choosing a payee: a matching rule first, otherwise the original suggestion. */
    public static String categorySql() {
        try {
            String rule = ruleCase("category"); if (rule == null) return ORIGINAL_CATEGORY_SQL;
            return "SELECT categoryId, cb_remember FROM ("
                + " SELECT c._id AS categoryId, 1 AS cb_remember FROM categoryTable c WHERE c.deleted = 0 AND c.guid = (" + rule + ")"
                + " UNION ALL SELECT categoryId, 0 FROM (" + ORIGINAL_CATEGORY_SQL + ")) ORDER BY cb_remember DESC LIMIT 1";
        } catch (RuntimeException error) { return ORIGINAL_CATEGORY_SQL; }
    }

    /** Account after choosing a payee: a matching rule first, then the chosen suggestion mode. */
    public static String payeeSql() {
        String base = suggestionSql();
        try {
            String rule = ruleCase("account"); if (rule == null) return base;
            // The historical query binds its parameter first. The rule's ?1 refers to the same payee.
            return "WITH previous(accountId) AS (" + base + ") SELECT accountId, cb_remember FROM ("
                + " SELECT a._id AS accountId, 1 AS cb_remember FROM accountTable a WHERE a.deleted = 0 AND a.guid = (" + rule + ")"
                + " UNION ALL SELECT accountId, 0 FROM previous) ORDER BY cb_remember DESC LIMIT 1";
        } catch (RuntimeException error) { return base; }
    }

    /** Account suggestion by mode (no rules). Parameter ?1 = payee name. */
    static String suggestionSql() {
        try {
            String mode = mode("payee_mode");
            if (ORIGINAL.equals(mode)) return ORIGINAL_PAYEE_SQL;
            String ok = allowed(MY_ACCOUNTS.equals(mode)), mine = mine();
            return "SELECT accountId FROM ("
                + " SELECT t.accountId, 0 AS pri, t._id AS id FROM txnTable t JOIN payeeTable p ON t.payeeId = p._id JOIN accountTable a ON a._id = t.accountId"
                + "  WHERE p.name = ?1 AND t.deleted = 0 AND " + ok + " AND " + mine
                + " UNION ALL SELECT t.accountId, 1, t._id FROM txnTable t JOIN accountTable a ON a._id = t.accountId"
                + "  WHERE t.deleted = 0 AND " + ok + " AND " + mine + " AND ?1 IS NOT NULL"
                + " UNION ALL SELECT t.accountId, 2, t._id FROM txnTable t JOIN payeeTable p ON t.payeeId = p._id JOIN accountTable a ON a._id = t.accountId"
                + "  WHERE p.name = ?1 AND " + ok
                + ") ORDER BY pri, id DESC LIMIT 1";
        } catch (RuntimeException error) { return ORIGINAL_PAYEE_SQL; }
    }

    /** Default account of a new transaction. No parameters. */
    public static String defaultSql() {
        try {
            String mode = mode("default_mode");
            if (ORIGINAL.equals(mode)) return ORIGINAL_DEFAULT_SQL;
            String ok = allowed(MY_ACCOUNTS.equals(mode));
            return "SELECT accountId FROM ("
                + " SELECT t.accountId, 0 AS pri, t._id AS id FROM txnTable t JOIN accountTable a ON a._id = t.accountId WHERE t.deleted = 0 AND " + ok + " AND " + mine()
                + " UNION ALL SELECT t.accountId, 1, t._id FROM txnTable t JOIN accountTable a ON a._id = t.accountId WHERE t.deleted = 0 AND " + ok
                + " UNION ALL SELECT t.accountId, 2, t._id FROM txnTable t LEFT OUTER JOIN accountTable a ON t.accountId = a._id WHERE a.hidden = 0 AND t.deleted = 0"
                + ") ORDER BY pri, id DESC LIMIT 1";
        } catch (RuntimeException error) { return ORIGINAL_DEFAULT_SQL; }
    }
}

package org.classicbudget.patch;

import android.app.Activity;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import com.youneedabudget.ynab.app.edit.TxnEntryActivity;
import com.youneedabudget.ynab.core.c.f;
import java.util.WeakHashMap;

/** Tracks which values in a new transaction were filled by a saved payee rule. */
public final class SuggestionSource {
    static final String SHOW_RULE_STATUS = "suggestion_source_icons";
    private static final String STATE = "cb.transaction_rule_fields";
    private static final int ACCOUNT = 0x10;
    private static final int CATEGORY = 0x02;
    private static final String TRANSACTION_ID = "com.youneedabudget.ynab.intent.extra.TRANSACTION_ID";
    private static final WeakHashMap<Activity, Integer> ruleFields = new WeakHashMap<>();

    public static long lookupAccount(TxnEntryActivity activity, f database, String payee) {
        return lookup(activity, database, payee, ACCOUNT, AccountSuggest.payeeSql());
    }

    public static long lookupCategory(TxnEntryActivity activity, f database, String payee) {
        return lookup(activity, database, payee, CATEGORY, AccountSuggest.categorySql());
    }

    private static long lookup(TxnEntryActivity activity, f database, String payee, int field, String sql) {
        long id = -1;
        boolean appliedRule = false;
        if (payee != null && !payee.isEmpty()) {
            try (Cursor cursor = database.c().rawQuery(sql, new String[]{payee})) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    id = cursor.getLong(0);
                    int rule = cursor.getColumnIndex("cb_remember");
                    appliedRule = id > 0 && rule >= 0 && cursor.getInt(rule) == 1;
                }
            }
        }
        // The native setters only apply suggestions when the corresponding flag is enabled.
        if (activity.b(field)) set(activity, field, appliedRule);
        return id;
    }

    public static void defaultAccount(TxnEntryActivity activity) {
        if (activity.b(ACCOUNT)) set(activity, ACCOUNT, false);
    }

    public static void manualAccount(Activity activity) {
        set(activity, ACCOUNT, false);
    }

    public static void manualCategory(Activity activity) {
        set(activity, CATEGORY, false);
    }

    public static void save(Activity activity, Bundle state) {
        state.putInt(STATE, fields(activity));
    }

    public static void restore(Activity activity, Bundle state) {
        if (state == null) return;
        int fields = state.getInt(STATE);
        if (fields != 0) ruleFields.put(activity, fields);
    }

    private static void set(Activity activity, int field, boolean appliedRule) {
        if (activity.getIntent().getLongExtra(TRANSACTION_ID, -1) != -1) return;
        int previous = fields(activity);
        int updated = appliedRule ? previous | field : previous & ~field;
        if (previous == updated) return;
        if (updated == 0) ruleFields.remove(activity);
        else ruleFields.put(activity, updated);
        TextView status = TransactionPayeeRule.ruleStatus(activity);
        if (status != null) bind(activity, status);
    }

    private static int fields(Activity activity) {
        Integer current = ruleFields.get(activity);
        return current == null ? 0 : current;
    }

    static void bind(Activity activity, TextView status) {
        boolean enabled = activity.getSharedPreferences(Display.PREFS, 0).getBoolean(SHOW_RULE_STATUS, true);
        int fields = enabled ? fields(activity) : 0;
        status.setVisibility(fields == 0 ? View.GONE : View.VISIBLE);
        String applied = null;
        if (fields == (ACCOUNT | CATEGORY)) {
            applied = PlusLang.tr("Rule filled account and category", "Reguła uzupełniła konto i kategorię");
        } else if (fields == ACCOUNT) {
            applied = PlusLang.tr("Rule filled account", "Reguła uzupełniła konto");
        } else if (fields == CATEGORY) {
            applied = PlusLang.tr("Rule filled category", "Reguła uzupełniła kategorię");
        }
        status.setContentDescription(applied);
        if (Build.VERSION.SDK_INT >= 26) status.setTooltipText(applied);
    }
}

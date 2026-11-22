package org.classicbudget.patch;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.youneedabudget.ynab.app.edit.TxnEntryActivity;
import com.youneedabudget.ynab.core.c.f;
import java.util.WeakHashMap;

/** Source of values applied to the current native transaction draft. */
public final class SuggestionSource {
    private static final String STATE = "cb.transaction_suggestion_sources";
    private static final String TAG = "transaction-source-";
    private static final String[] FIELDS = {"payee", "account", "category"};
    private static final int PAYEE = 0;
    private static final int ACCOUNT = 1;
    private static final int CATEGORY = 2;
    private static final int HISTORY = 1;
    private static final int REMEMBER = 2;
    private static final int GPS = 3;
    private static final int ACCOUNT_SUGGESTION = 0x10;
    private static final int CATEGORY_SUGGESTION = 0x02;
    private static final String TRANSACTION_ID = "com.youneedabudget.ynab.intent.extra.TRANSACTION_ID";
    private static final WeakHashMap<Activity, int[]> sources = new WeakHashMap<>();

    public static long lookupAccount(TxnEntryActivity activity, f database, String payee) {
        return lookup(activity, database, payee, ACCOUNT, ACCOUNT_SUGGESTION, AccountSuggest.payeeSql());
    }

    public static long lookupCategory(TxnEntryActivity activity, f database, String payee) {
        return lookup(activity, database, payee, CATEGORY, CATEGORY_SUGGESTION, AccountSuggest.categorySql());
    }

    private static long lookup(TxnEntryActivity activity, f database, String payee, int field, int mask, String sql) {
        long id = -1;
        int source = 0;
        if (payee != null && !payee.isEmpty()) {
            try (Cursor cursor = database.c().rawQuery(sql, new String[]{payee})) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    id = cursor.getLong(0);
                    int rule = cursor.getColumnIndex("cb_remember");
                    if (id > 0) source = rule >= 0 && cursor.getInt(rule) == 1 ? REMEMBER : HISTORY;
                }
            }
        }
        // The native setters only apply suggestions when the corresponding flag is enabled.
        if (activity.b(mask)) set(activity, field, source);
        return id;
    }

    public static void defaultAccount(TxnEntryActivity activity, long id) {
        if (activity.b(ACCOUNT_SUGGESTION)) set(activity, ACCOUNT, id > 0 ? HISTORY : 0);
    }

    public static void locationPayee(Activity activity, String payee) {
        set(activity, PAYEE, payee == null || payee.isEmpty() ? 0 : GPS);
    }

    public static void manualPayee(Activity activity) {
        set(activity, PAYEE, 0);
    }

    public static void manualAccount(Activity activity) {
        set(activity, ACCOUNT, 0);
    }

    public static void manualCategory(Activity activity) {
        set(activity, CATEGORY, 0);
    }

    public static void save(Activity activity, Bundle state) {
        int[] values = sources.get(activity);
        if (values != null) state.putIntArray(STATE, values.clone());
    }

    public static void restore(Activity activity, Bundle state) {
        if (state == null) return;
        int[] values = state.getIntArray(STATE);
        if (values != null && values.length == FIELDS.length) sources.put(activity, values);
    }

    public static void attach(View form) {
        Context context = form.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        if (!newTransaction(activity)) return;
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        for (int i = 0; i < FIELDS.length; i++) {
            String tag = TAG + FIELDS[i];
            if (form.findViewWithTag(tag) != null) continue;
            int id = activity.getResources().getIdentifier(FIELDS[i] + "_click_target", "id", activity.getPackageName());
            View target = form.findViewById(id);
            if (target == null || !(target.getParent() instanceof LinearLayout)) continue;
            TextView label = new TextView(activity);
            label.setTag(tag);
            label.setContentDescription(tag);
            label.setGravity(Gravity.END);
            label.setTextColor(0xff475b66);
            label.setTextSize(12);
            label.setPadding(padding, 0, padding, padding / 2);
            // Category already wraps its row and divider. Let its native visibility gate the label too.
            if (i == CATEGORY && target instanceof LinearLayout) {
                ((LinearLayout) target).addView(label);
            } else {
                LinearLayout parent = (LinearLayout) target.getParent();
                parent.addView(label, parent.indexOfChild(target) + 1);
            }
            int[] values = sources.get(activity);
            render(label, values == null ? 0 : values[i]);
        }
    }

    private static boolean newTransaction(Activity activity) {
        return activity.getIntent().getLongExtra(TRANSACTION_ID, -1) == -1;
    }

    private static void set(Activity activity, int field, int source) {
        if (!newTransaction(activity)) return;
        int[] values = sources.get(activity);
        if (values == null) {
            values = new int[FIELDS.length];
            sources.put(activity, values);
        }
        values[field] = source;
        View label = activity.getWindow().getDecorView().findViewWithTag(TAG + FIELDS[field]);
        if (label instanceof TextView) render((TextView) label, source);
    }

    private static void render(TextView label, int source) {
        switch (source) {
            case GPS:
                label.setText(PlusLang.tr("GPS place", "Miejsce GPS"));
                break;
            case REMEMBER:
                label.setText(PlusLang.tr("Remember rule", "Reguła Remember"));
                break;
            case HISTORY:
                label.setText(PlusLang.tr("Transaction history", "Historia transakcji"));
                break;
            default:
                label.setText("");
        }
        label.setVisibility(source == 0 ? View.GONE : View.VISIBLE);
    }
}

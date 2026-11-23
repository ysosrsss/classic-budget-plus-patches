package org.classicbudget.patch;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.lang.reflect.Field;
import java.util.WeakHashMap;

/** Bridge between the native new-transaction form and the existing payee rule editor. */
public final class TransactionPayeeRule {
    private static final String CHOICE = "cb.remember_payee_rule";
    private static final String STATUS = "cb.applied_payee_rule";
    static final String DRAFT = "cb.payee_rule_draft";
    static final String BUDGET = "cb.payee_rule_budget";
    private static final WeakHashMap<Activity, Boolean> choices = new WeakHashMap<>();
    private static final WeakHashMap<Object, Long> savedRows = new WeakHashMap<>();
    private static final WeakHashMap<Object, SavedTransaction> savedTransactions = new WeakHashMap<>();

    private static final class SavedTransaction {
        final String databasePath;
        final long rowId;

        SavedTransaction(String databasePath, long rowId) {
            this.databasePath = databasePath;
            this.rowId = rowId;
        }
    }

    public static void attach(View form) {
        Context context = form.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            context = ((ContextWrapper) context).getBaseContext();
        }
        if (!(context instanceof Activity)) return;
        Activity activity = (Activity) context;
        if (!newTransaction(activity) || form.findViewWithTag(CHOICE) != null) return;
        int id = activity.getResources().getIdentifier("date_click_target", "id", activity.getPackageName());
        if (id == 0) return;
        View date = form.findViewById(id);
        if (date == null || !(date.getParent() instanceof LinearLayout)) return;
        LinearLayout parent = (LinearLayout) date.getParent();
        LinearLayout row = new LinearLayout(activity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(date.getPaddingLeft(), 0, date.getPaddingRight(), 0);
        float density = activity.getResources().getDisplayMetrics().density;
        int controlSize = Math.round(44 * density);
        row.setMinimumHeight(controlSize);

        TextView label = new TextView(activity);
        label.setText(PlusLang.tr("Remember rule", "Zapamiętaj regułę"));
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setMinHeight(controlSize);

        CheckBox checkbox = new CheckBox(activity);
        checkbox.setTag(CHOICE);
        checkbox.setContentDescription("remember-payee-rule");
        checkbox.setGravity(Gravity.CENTER_VERTICAL);
        checkbox.setPadding(0, 0, 0, 0);

        TextView status = new TextView(activity);
        status.setTag(STATUS);
        status.setText(PlusLang.tr("Rule applied", "Użyto reguły"));
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setAlpha(0.78f);
        status.setPadding(Math.round(8 * density), 0, 0, 0);
        status.setVisibility(View.GONE);
        int labelId = activity.getResources().getIdentifier("account_label", "id", activity.getPackageName());
        View accountLabel = form.findViewById(labelId);
        if (accountLabel instanceof TextView) {
            TextView nativeLabel = (TextView) accountLabel;
            label.setTextColor(nativeLabel.getTextColors());
            label.setTextSize(TypedValue.COMPLEX_UNIT_PX, nativeLabel.getTextSize());
            label.setTypeface(nativeLabel.getTypeface());
            status.setTextColor(nativeLabel.getCurrentTextColor());
        }
        alignCheckbox(checkbox, label.getCurrentTextColor());
        checkbox.setChecked(Boolean.TRUE.equals(choices.get(activity)));
        checkbox.setOnCheckedChangeListener((view, checked) -> choices.put(activity, checked));
        label.setOnClickListener(view -> checkbox.performClick());
        row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        row.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(checkbox, new LinearLayout.LayoutParams(controlSize, controlSize));
        parent.addView(row, parent.indexOfChild(date) + 1, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        SuggestionSource.bind(activity, status);
    }

    private static void alignCheckbox(CheckBox checkbox, int textColor) {
        if (Build.VERSION.SDK_INT < 21) return;
        ColorStateList tint = new ColorStateList(new int[][]{{android.R.attr.state_checked}, {}},
            new int[]{0xff31a5bb, textColor});
        if (Build.VERSION.SDK_INT >= 23) {
            Drawable indicator = checkbox.getButtonDrawable();
            if (indicator != null) {
                // The original manifest uses left-to-right layout. Place the stock stateful icon
                // in the right drawable slot without changing the checkbox's click or checked state.
                checkbox.setButtonDrawable((Drawable) null);
                indicator = indicator.mutate();
                indicator.setTintList(tint);
                checkbox.setCompoundDrawablesWithIntrinsicBounds(null, null, indicator, null);
                return;
            }
        }
        checkbox.setButtonTintList(tint);
    }

    static TextView ruleStatus(Activity activity) {
        return activity.getWindow().getDecorView().findViewWithTag(STATUS);
    }

    private static boolean newTransaction(Activity activity) {
        return activity.getIntent().getLongExtra("com.youneedabudget.ynab.intent.extra.TRANSACTION_ID", -1) == -1;
    }

    public static void saveChoice(Activity activity, Bundle state) {
        state.putBoolean(CHOICE, Boolean.TRUE.equals(choices.get(activity)));
    }

    public static void restoreChoice(Activity activity, Bundle state) {
        if (state != null && state.getBoolean(CHOICE)) choices.put(activity, true);
    }

    static String openBudget(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", 0).getString("lastBudget", "");
    }

    /** The first native write in the save operation returns the parent row identity. */
    public static synchronized void captureSavedRow(Object operation, long rowId) {
        if (rowId > 0) savedRows.put(operation, rowId);
    }

    public static synchronized void bindSaveResult(Object operation, Object result) {
        Long rowId = savedRows.remove(operation);
        if (rowId == null || result == null) return;
        try {
            if (!result.getClass().getField("a").getBoolean(result)) return;
            // Read the database held by this write, including when the current budget changes.
            Field adapterField = operation.getClass().getDeclaredField("a");
            adapterField.setAccessible(true);
            Object adapter = adapterField.get(operation);
            SQLiteDatabase db = (SQLiteDatabase) adapter.getClass().getMethod("c").invoke(adapter);
            savedTransactions.put(result, new SavedTransaction(db.getPath(), rowId));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Rule discovery cannot affect the native save result.
        }
    }

    private static synchronized SavedTransaction takeSavedTransaction(Object result) {
        return savedTransactions.remove(result);
    }

    public static void afterSave(Activity activity, Object result) {
        SavedTransaction saved = takeSavedTransaction(result);
        boolean remember = newTransaction(activity) && Boolean.TRUE.equals(choices.get(activity));
        try {
            boolean success = result != null && result.getClass().getField("a").getBoolean(result);
            PlusDiagnostics.record(activity, success ? PlusDiagnostics.Event.TRANSACTION_SAVED
                : PlusDiagnostics.Event.TRANSACTION_SAVE_FAILED);
            if (!success || !remember) return;
            choices.remove(activity);
            String budget = openBudget(activity);
            if (saved == null || !budget.contains("#")) {
                explainUnavailable(activity);
                return;
            }
            File file = activity.getDatabasePath(budget);
            if (!file.isFile() || !file.getAbsolutePath().equals(saved.databasePath)) {
                explainUnavailable(activity);
                return;
            }
            Bundle draft = readDraft(file, saved.rowId);
            if (draft == null) {
                explainUnavailable(activity);
                return;
            }
            Intent intent = new Intent(activity, PayeeRulesActivity.class);
            intent.putExtra(DRAFT, draft);
            intent.putExtra(BUDGET, budget);
            activity.startActivity(intent);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A rules failure must leave the native save result and navigation intact.
            if (remember) explainUnavailable(activity);
        }
    }

    private static Bundle readDraft(File file, long rowId) {
        String sql = "SELECT p.name, a.guid, c.guid FROM txnTable t "
            + "JOIN payeeTable p ON p._id=t.payeeId AND p.deleted=0 "
            + "LEFT JOIN accountTable a ON a._id=t.accountId AND a.deleted=0 AND a.hidden=0 "
            + "LEFT JOIN categoryTable c ON c._id=t.categoryId AND c.deleted=0 "
            + "WHERE t._id=? AND t.deleted=0 AND t.parentId IS NULL AND t.targAccountId IS NULL "
            + "AND COALESCE(t.transferGuid, '')='' AND COALESCE(c.guid, '')!='Category/__Split__' "
            + "AND NOT EXISTS (SELECT 1 FROM txnTable child WHERE child.parentId=t._id AND child.deleted=0)";
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = db.rawQuery(sql, new String[]{Long.toString(rowId)})) {
            if (!cursor.moveToFirst() || cursor.isNull(0) || (cursor.isNull(1) && cursor.isNull(2))) return null;
            String payee = cursor.getString(0).trim();
            if (payee.isEmpty() || payee.startsWith("Transfer : ")) return null;
            Bundle draft = new Bundle();
            draft.putString("text", payee);
            draft.putString("account", cursor.isNull(1) ? "" : cursor.getString(1));
            draft.putString("category", cursor.isNull(2) ? "" : cursor.getString(2));
            return draft;
        }
    }

    private static void explainUnavailable(Activity activity) {
        Toast.makeText(activity, PlusLang.tr("A payee rule needs a regular transaction with a payee.",
            "Reguła płatnika wymaga zwykłej transakcji z płatnikiem."), Toast.LENGTH_SHORT).show();
    }
}

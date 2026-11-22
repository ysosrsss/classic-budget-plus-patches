package org.classicbudget.patch;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;

/**
 * Which edition this build is. TEST keeps the writers (budget amounts, schedule definitions) on
 * TEST and COPY budgets only and shows an orange action bar, so it is never mistaken for the
 * production copy installed next to it. The "Production edition" patch turns edition() to false.
 */
public final class PlusEdition {
    private PlusEdition() { }

    /** True while editors must refuse budgets whose folder name has neither TEST nor COPY. */
    public static final boolean TEST_ONLY = edition();

    /** Replaced by the "Production edition" patch (const true → false). Not inlined: a method, not a constant. */
    static boolean edition() {
        return true;
    }

    private static String warned;

    /**
     * Production edition: the budget open on the budget screen looks like a test one (its name has
     * TEST or COPY). An isolated copy can be opened intentionally from the Dropbox folder selector.
     * Confirm once per budget, since the same production app can also write to that copy.
     */
    public static void checkBudget(final android.app.Activity activity) {
        if (TEST_ONLY || activity == null) return;
        try {
            final android.content.SharedPreferences ui = activity.getSharedPreferences(PlusLang.PREFS, 0);
            String db = activity.getSharedPreferences(activity.getPackageName() + "_preferences", 0).getString("lastBudget", null);
            final String name = budgetName(db);
            if (name == null || name.equals(warned) || ui.getBoolean("edition_ok:" + name, false)) return;
            String upper = name.toUpperCase(java.util.Locale.ROOT);
            if (!upper.contains("TEST") && !upper.contains("COPY")) return;
            warned = name;
            android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(activity)
                .setTitle(PlusLang.tr("Test budget in YNAB Plus?", "Budżet testowy w YNAB Plus?"))
                .setMessage(PlusLang.tr(
                    "\"" + name + "\" looks like a TEST or COPY budget. YNAB Plus can sync changes to this copy. "
                        + "Choose \"It's fine\" if you opened it intentionally.",
                    "„" + name + "” wygląda na budżet TEST lub COPY. YNAB Plus może synchronizować zmiany w tej kopii. "
                        + "Wybierz „W porządku”, jeśli otwarto ją celowo."))
                .setPositiveButton(PlusLang.tr("Understood", "Rozumiem"), null)
                .setNegativeButton(PlusLang.tr("It's fine", "W porządku"), (d, w) -> ui.edit().putBoolean("edition_ok:" + name, true).apply())
                .create();
            dialog.show();
            android.view.View message = dialog.findViewById(android.R.id.message);
            if (message != null) message.setContentDescription("edition-budget-warning");
        } catch (RuntimeException unexpected) {
            // Never break the budget screen for a warning.
        }
    }

    /** "U3ludGhldGljIEJ1ZGdldCBURVNU#dropbox" (Base64 of the budget name, then the provider) → "Synthetic Budget TEST". */
    static String budgetName(String database) {
        if (database == null || database.indexOf('#') <= 0) return null;
        try {
            byte[] raw = android.util.Base64.decode(database.substring(0, database.indexOf('#')), android.util.Base64.DEFAULT);
            String name = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
            return name.isEmpty() ? null : name;
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }

    /** TEST edition: orange action bar on the screen (original and Plus screens alike). */
    public static void mark(Activity activity) {
        if (!TEST_ONLY || activity == null) return;
        try {
            android.app.ActionBar bar = activity.getActionBar();
            if (bar != null) bar.setBackgroundDrawable(new ColorDrawable(0xffd35400));
        } catch (RuntimeException unexpected) {
            // Never break a screen for a colour.
        }
    }
}

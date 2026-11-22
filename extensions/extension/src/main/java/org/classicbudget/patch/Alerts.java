package org.classicbudget.patch;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.Bundle;
import android.widget.Toast;
import java.text.NumberFormat;
import java.util.*;

/**
 * Overspending alert: when the budget overview opens (at most once a day), list
 * categories whose balance is below zero. In-app message, no notification
 * permission. Local setting "alerts" in cb-plus-reports: "on" / "off" (default).
 * Installed by Display.install when this class is present (optional module).
 */
public final class Alerts {
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    public static boolean enabled(Context c) { return "on".equals(ReportData.prefs(c).getString("alerts", "off")); }

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity a) {
                if (a.getClass().getName().endsWith(".OverviewActivity")) {
                    try { check(a); } catch (RuntimeException ignored) { }
                    BudgetWidget.refresh(a);   // home-screen widget follows new data
                }
            }
            public void onActivityCreated(Activity a, Bundle b) { }
            public void onActivityStarted(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    static void check(Activity a) {
        if (!enabled(a)) return;
        SharedPreferences p = ReportData.prefs(a);
        String today = String.format(Locale.ROOT, "%1$tY-%1$tm-%1$td", Calendar.getInstance());
        if (today.equals(p.getString("alerts.last", ""))) return;
        ReportData data = ReportData.open(a); if (data == null) return;
        List<String> lines = new ArrayList<String>(); int count = 0;
        try {
            NumberFormat money = data.money();
            Cursor c = data.db.rawQuery("SELECT c.name, c.currentBal FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId"
                + " WHERE c.deleted = 0 AND m.deleted = 0 AND c.currentBal < 0 AND m.name NOT LIKE 'Hidden%' AND m.guid NOT LIKE '%__Hidden__%' ORDER BY c.currentBal", null);
            try { while (c.moveToNext()) { count++; if (lines.size() < 4) lines.add(c.getString(0) + " " + money.format(c.getLong(1) / 100.0)); } } finally { c.close(); }
        } finally { data.close(); }
        p.edit().putString("alerts.last", today).putInt("alerts.count", count).apply();
        android.util.Log.i("CBPlusAlert", "overspent categories: " + count);   // count only, never names or amounts
        if (count == 0) return;
        StringBuilder s = new StringBuilder(tr("Overspent: ", "Przekroczone: "));
        for (int i = 0; i < lines.size(); i++) s.append(i == 0 ? "" : ", ").append(lines.get(i));
        if (count > lines.size()) s.append(tr(" and ", " i ")).append(count - lines.size()).append(tr(" more", " więcej"));
        Toast.makeText(a, s.toString(), Toast.LENGTH_LONG).show();
    }
}

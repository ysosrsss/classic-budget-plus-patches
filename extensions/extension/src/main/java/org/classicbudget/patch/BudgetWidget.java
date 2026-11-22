package org.classicbudget.patch;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.widget.RemoteViews;
import java.text.NumberFormat;
import java.util.*;

/**
 * Home-screen widget (read only): this month's spending, overspent categories,
 * scheduled payments in the next 7 days. With the app passcode on, or "Hide
 * amounts" in Reports, no amounts are shown. A tap opens the app normally
 * (through its passcode screen), never a screen directly.
 */
public final class BudgetWidget extends AppWidgetProvider {
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    public void onUpdate(Context c, AppWidgetManager m, int[] ids) { for (int id : ids) m.updateAppWidget(id, views(c)); }

    /** Called when the budget overview resumes, so the widget follows new data. */
    public static void refresh(Context c) {
        try {
            AppWidgetManager m = AppWidgetManager.getInstance(c);
            int[] ids = m.getAppWidgetIds(new ComponentName(c, BudgetWidget.class));
            if (ids.length > 0) for (int id : ids) m.updateAppWidget(id, views(c));
        } catch (RuntimeException ignored) { }
    }

    static int id(Context c, String type, String name) { return c.getResources().getIdentifier(name, type, c.getPackageName()); }

    static RemoteViews views(Context c) {
        RemoteViews v = new RemoteViews(c.getPackageName(), id(c, "layout", "cb_widget"));
        Intent open = c.getPackageManager().getLaunchIntentForPackage(c.getPackageName());
        if (open != null) v.setOnClickPendingIntent(id(c, "id", "cb_widget_root"), PendingIntent.getActivity(c, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0)));
        boolean locked = PreferenceManager.getDefaultSharedPreferences(c).getBoolean("usePasscode", false);
        boolean hide = locked || ReportData.prefs(c).getBoolean("hide_amounts", false);
        String title = tr("Budget", "Budżet"), line1 = "", line2 = "", line3 = "";
        ReportData data = null;
        try {
            data = ReportData.open(c);
            if (data == null) line1 = tr("No budget yet", "Brak budżetu");
            else if (locked) line1 = tr("Locked — open the app", "Zablokowane — otwórz aplikację");
            else {
                NumberFormat money = data.money(); title = data.budgetName();
                String month = ReportData.thisMonth();
                ReportData.Filter f = new ReportData.Filter(); f.first = month; f.last = month;
                long spent = 0; for (ReportData.Group g : data.byCategory(f)) spent += g.amount;
                int over = 0;
                Cursor cur = data.db.rawQuery("SELECT COUNT(*) FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId WHERE c.deleted = 0 AND m.deleted = 0 AND c.currentBal < 0 AND m.name NOT LIKE 'Hidden%' AND m.guid NOT LIKE '%__Hidden__%'", null);
                try { if (cur.moveToFirst()) over = cur.getInt(0); } finally { cur.close(); }
                List<ScheduleReportModel.Entry> soon = ScheduleReportModel.future(data.db, Collections.<String>emptySet(), ReportData.iso(Calendar.getInstance()), 7);
                long out = 0;
                boolean complete = true;
                for (ScheduleReportModel.Entry entry : soon) {
                    complete &= entry.forecastKnown;
                    if (entry.forecast < 0) out -= entry.forecast;
                }
                line1 = tr("Spent this month: ", "Wydane w tym miesiącu: ") + (hide ? "•••" : money.format(spent / 100.0));
                line2 = over == 0 ? tr("No overspent categories", "Brak przekroczonych kategorii") : tr("Overspent categories: ", "Przekroczone kategorie: ") + over;
                line3 = complete ? tr("Next 7 days: ", "Najbliższe 7 dni: ") + soon.size() + tr(" operations", " operacji") + (hide || out == 0 ? "" : ", " + money.format(out / 100.0))
                    : tr("Complex schedules — open the preview", "Złożone zlecenia — otwórz podgląd");
            }
        } catch (RuntimeException error) { line1 = tr("Open the app to refresh", "Otwórz aplikację, aby odświeżyć"); }
        finally { if (data != null) data.close(); }
        v.setTextViewText(id(c, "id", "cb_widget_title"), title);
        v.setTextViewText(id(c, "id", "cb_widget_line1"), line1);
        v.setTextViewText(id(c, "id", "cb_widget_line2"), line2);
        v.setTextViewText(id(c, "id", "cb_widget_line3"), line3);
        return v;
    }
}

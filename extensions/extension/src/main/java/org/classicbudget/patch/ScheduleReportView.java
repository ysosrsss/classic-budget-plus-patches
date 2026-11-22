package org.classicbudget.patch;

import android.widget.*;
import java.util.*;

/** Shares transaction cards, search, sorting and filters; no nested scrolling list. */
final class ScheduleReportView extends LinearLayout {
    ScheduleReportView(ReportsActivity activity) {
        super(activity);
        setOrientation(VERTICAL);
        setPadding(activity.px(8), 0, activity.px(8), 0);
        boolean history = activity.prefs.getBoolean("upcoming.history", false);
        int days = activity.prefs.getInt("upcoming.days", 30);
        LinearLayout modes = new LinearLayout(activity);
        String[] names = {ReportsActivity.tr("Next ", "Najbliższe ") + days + ReportsActivity.tr(" days", " dni"),
            ReportsActivity.tr("Last 30 days", "Ostatnie 30 dni")};
        for (int i = 0; i < names.length; i++) {
            final boolean past = i == 1;
            Button button = activity.chip(names[i], past ? "schedule-history" : "schedule-future", view -> {
                activity.prefs.edit().putBoolean("upcoming.history", past).apply();
                activity.render();
            });
            button.setSelected(history == past);
            if (history == past) button.setTypeface(null, android.graphics.Typeface.BOLD);
            modes.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        addView(modes);
        ReportData.Filter filter = activity.filter();
        String today = ReportData.iso(Calendar.getInstance());
        List<ScheduleReportModel.Entry> entries = history
            ? ScheduleReportModel.history(activity.data.db, filter.excludedAccounts, today, 30)
            : ScheduleReportModel.future(activity.data.db, filter.excludedAccounts, today, days);
        if (!history) {
            String label;
            try {
                long current = activity.data.balance(filter, true);
                label = ReportsActivity.tr("Budget accounts: ", "Konta budżetowe: ") + activity.cash(current)
                    + " → " + activity.cash(current + ScheduleReportModel.forecast(entries));
            } catch (IllegalStateException complex) {
                label = ReportsActivity.tr("Forecast unavailable: linked transfers in splits.",
                    "Prognoza niedostępna: powiązane przelewy w podziałach.");
            }
            TextView forecast = activity.text(label, 13, ReportsActivity.TEXT);
            forecast.setContentDescription("upcoming-balance");
            addView(forecast);
        }
        TextView note = activity.text(history
            ? ReportsActivity.tr("Entered from schedules. Bank clearance is shown separately.",
                "Wpisane ze zleceń. Status rozliczenia jest pokazany osobno.")
            : ReportsActivity.tr("List filters do not change the forecast. Internal transfers have zero net effect.",
                "Filtry listy nie zmieniają prognozy. Przelewy wewnętrzne mają wpływ netto 0."), 11, ReportsActivity.MUTED);
        addView(note);
        List<ReportTable.Detail> details = new ArrayList<ReportTable.Detail>();
        for (ScheduleReportModel.Entry entry : entries) {
            ReportTable.Detail detail = new ReportTable.Detail();
            detail.guid = entry.key;
            detail.date = entry.date;
            detail.accountGuid = entry.accountGuid;
            detail.account = entry.account + (entry.transfer ? " → " + entry.target : "");
            detail.payee = entry.payee == null || entry.payee.isEmpty()
                ? ReportsActivity.tr("No payee", "Bez płatnika") : entry.payee;
            detail.payeeGuid = entry.payeeGuid;
            detail.memo = entry.memo;
            detail.amount = entry.amount;
            detail.category = entry.transfer ? ReportsActivity.tr("Transfer", "Przelew") : ReportsActivity.tr("Scheduled", "Ze zlecenia");
            if (history) {
                detail.category += ReportsActivity.tr(" · entered ", " · wpisano ") + entry.entered.substring(0, 10)
                    + (entry.cleared < 0 ? ReportsActivity.tr(" · clearance unknown", " · status rozliczenia nieustalony")
                        : entry.cleared == 0 ? ReportsActivity.tr(" · uncleared", " · nierozliczona")
                        : entry.cleared == 2 ? ReportsActivity.tr(" · reconciled", " · uzgodniona")
                        : ReportsActivity.tr(" · cleared", " · rozliczona"));
            }
            details.add(detail);
        }
        ReportTransactions list = new ReportTransactions(activity, details);
        list.order = history ? 0 : 1;
        list.sign = 1;
        addView(list.content(), new LinearLayout.LayoutParams(-1, 0, 1));
    }
}

package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.*;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.text.NumberFormat;
import java.util.*;

/**
 * Module "Reports" ("Raporty"): the YNAB4 desktop reports on the phone, read only.
 * Spending by category (totals / trends), spending by payee, income vs expense,
 * net worth; filters for dates, categories, accounts and payees, remembered per report.
 */
public final class ReportsActivity extends Activity {
    /** Language of our screens: English unless Polish is chosen in Plus settings (PlusLang). */
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    static final int SPEND = Color.rgb(205, 92, 72), INCOME = Color.rgb(76, 150, 96), LINE = Color.rgb(44, 110, 160), TEXT = Color.rgb(50, 50, 50), MUTED = Color.rgb(130, 130, 130), ACCENT = Color.rgb(20, 66, 82);
    static final String[] MONTHS_PL = {"sty", "lut", "mar", "kwi", "maj", "cze", "lip", "sie", "wrz", "paź", "lis", "gru"};
    static final String[] MONTHS_EN = {"Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"};
    static String[] months() { return PlusLang.polish() ? MONTHS_PL : MONTHS_EN; }
    static final String[] REPORTS = {"category", "payee", "trends", "income", "networth", "person", "upcoming", "analysis", "comparison"};
    static final String[] DATES = {"this_month", "last3", "ytd", "last_year", "last12", "all", "custom"};

    ReportData data; NumberFormat money; SharedPreferences prefs; float dp; LinearLayout root;
    String pendingExport; android.webkit.WebView printView;
    String report;
    boolean hide, landscape;
    LinearLayout navigation, screenFrame;
    View schedulePanel;
    boolean schedulePreview;
    ReportScroll reportScroll;
    View stickyHeader, stickyContainer;
    ReportTable incomeModel;
    int incomeScrollX;
    /** Desktop chart: the category group opened by a tap (desktop drill-down); NO_DRILL = all groups. */
    static final long NO_DRILL = Long.MIN_VALUE;
    long drillGroup = NO_DRILL;
    List<ReportData.Item> categories, accounts, payees;

    protected void onCreate(Bundle state) {
        super.onCreate(state); PlusEdition.mark(this); pendingExport=state==null?null:state.getString("pending-export"); setTitle(tr("Reports", "Raporty")); dp = getResources().getDisplayMetrics().density;
        data = ReportData.open(this);
        if (data == null) { TextView t = new TextView(this); t.setText(tr("No budget on this phone yet.", "Brak budżetu na tym telefonie.")); t.setPadding(px(20), px(20), px(20), px(20)); setContentView(t); return; }
        money = data.money(); prefs = ReportData.prefs(this);
        migrateTrends();
        // Each new visit starts in portrait. Keep the chosen mode only across this Activity's rotation.
        landscape = state != null && state.getBoolean("report-landscape", false);
        setRequestedOrientation(landscape ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        categories = data.categories(); accounts = data.accounts(); payees = data.payees();
        schedulePreview = getIntent().getBooleanExtra("schedule-preview", false);
        report = schedulePreview ? "upcoming" : prefs.getString("report", "category"); if (!Arrays.asList(REPORTS).contains(report)) report = "category";
        hide = prefs.getBoolean("hide_amounts", false);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(px(16), px(8), px(16), px(24));
        LinearLayout frame = new LinearLayout(this); frame.setOrientation(LinearLayout.VERTICAL);
        screenFrame = frame;
        navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER_VERTICAL);
        navigation.setPadding(px(8), 0, px(8), 0);
        frame.addView(navigation, new LinearLayout.LayoutParams(-1, px(48)));
        reportScroll = new ReportScroll();
        reportScroll.addView(root);
        frame.addView(reportScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(frame);
        incomeScrollX = state == null ? 0 : state.getInt("report-income-scroll", 0);
        // Reports use one compact toolbar; the Activity title would duplicate it.
        if (getActionBar() != null) getActionBar().hide();
        String activityMonth = getIntent().getStringExtra("budget-activity-month");
        String activityCategory = getIntent().getStringExtra("budget-activity-category");
        if (activityMonth != null && activityMonth.matches("[0-9]{4}-[0-9]{2}") && activityCategory != null) {
            String title = getIntent().getStringExtra("budget-activity-title");
            reportScroll.post(() -> new ReportTransactions(this, ReportTable.budgetActivity(data, activityMonth, activityCategory))
                .open(title, this::finish));
            return;
        }
        render();
        // 090: "Search transactions" from the Plus sheet opens straight into the search list.
        if (state == null && getIntent().getBooleanExtra("search-all", false)) reportScroll.post(this::searchAll);
    }

    protected void onSaveInstanceState(Bundle state) {
        state.putString("pending-export", pendingExport);
        state.putBoolean("report-landscape", landscape);
        state.putInt("report-income-scroll", incomeScrollX);
        super.onSaveInstanceState(state);
    }

    void rotateReport() {
        landscape = !landscape;
        setRequestedOrientation(landscape ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        render();
    }

    final class ReportScroll extends ScrollView {
        ReportScroll() { super(ReportsActivity.this); }
        protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            updateStickyHeader();
        }
    }

    void updateStickyHeader() {
        if (stickyHeader == null || stickyContainer == null) return;
        int offset = Math.max(0, reportScroll.getScrollY() - stickyContainer.getTop());
        offset = Math.min(offset, Math.max(0, stickyContainer.getHeight() - stickyHeader.getHeight()));
        stickyHeader.setTranslationY(offset);
    }

    void pinHeader(View container, View header) {
        stickyContainer = container;
        stickyHeader = header;
        header.setBackgroundColor(Color.rgb(243, 247, 249));
        if (android.os.Build.VERSION.SDK_INT >= 21) header.setElevation(px(8));
        container.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> updateStickyHeader());
        header.post(() -> updateStickyHeader());
    }

    protected void onDestroy() { if(printView!=null)printView.destroy(); if (data != null) data.close(); super.onDestroy(); }

    int px(float v) { return (int) (v * dp); }
    static String label(String m) { return months()[Integer.parseInt(m.substring(5, 7)) - 1] + " " + m.substring(0, 4); }
    static String shortLabel(String m) { return months()[Integer.parseInt(m.substring(5, 7)) - 1]; }
    String amount(long hundredths, long total) {
        if (hide) return total == 0 ? "" : Math.round(100.0 * hundredths / total) + "%";
        return money.format(hundredths / 100.0);
    }
    String cash(long hundredths) { return hide ? "•••" : money.format(hundredths / 100.0); }

    // --- filters ----------------------------------------------------------------------------------

    private void migrateTrends() {
        if (!prefs.contains("category.trends")) return;
        SharedPreferences.Editor edit = prefs.edit().remove("category.trends");
        if (prefs.getBoolean("category.trends", false)) {
            for (String name : new String[]{"dates", "from", "to", "who"}) {
                String old = prefs.getString("category." + name, null);
                if (old != null && !prefs.contains("trends." + name)) edit.putString("trends." + name, old);
            }
            for (String name : new String[]{"xcat", "xpay", "xacc"}) {
                Set<String> old = prefs.getStringSet("category." + name, null);
                if (old != null && !prefs.contains("trends." + name)) edit.putStringSet("trends." + name, old);
            }
            if ("category".equals(prefs.getString("report", "category"))) edit.putString("report", "trends");
        }
        edit.apply();
    }

    String key(String name) { return report + "." + name; }
    String dates() { return prefs.getString(key("dates"), report.equals("category") || report.equals("payee") ? "this_month" : "last12"); }

    ReportData.Filter filter() {
        ReportData.Filter f = new ReportData.Filter(); String now = ReportData.thisMonth(); String[] span = data.span();
        String d = dates();
        if (d.equals("this_month")) { f.first = now; f.last = now; }
        else if (d.equals("last3")) { f.first = ReportData.shift(now, -2); f.last = now; }
        else if (d.equals("ytd")) { f.first = now.substring(0, 4) + "-01"; f.last = now; }
        else if (d.equals("last_year")) { String y = String.valueOf(Integer.parseInt(now.substring(0, 4)) - 1); f.first = y + "-01"; f.last = y + "-12"; }
        else if (d.equals("all")) { f.first = span[0]; f.last = span[1].compareTo(now) > 0 ? span[1] : now; }
        else if (d.equals("custom")) { f.first = prefs.getString(key("from"), span[0]); f.last = prefs.getString(key("to"), now); if (f.first.compareTo(f.last) > 0) { String t = f.first; f.first = f.last; f.last = t; } }
        else { f.first = ReportData.shift(now, -11); f.last = now; }
        for (String s : prefs.getStringSet(key("xcat"), Collections.<String>emptySet())) try { f.excludedCategories.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
        for (String s : prefs.getStringSet(key("xpay"), Collections.<String>emptySet())) try { f.excludedPayees.add(Long.parseLong(s)); } catch (NumberFormatException ignored) { }
        for (String s : prefs.getStringSet(key("xacc"), Collections.<String>emptySet())) if (s.matches("[A-Za-z0-9-]{1,64}")) f.excludedAccounts.add(s);
        f.enteredBy = report.equals("networth") || report.equals("person") || report.equals("upcoming") ? "all" : prefs.getString(key("who"), "all"); f.mine.addAll(data.mineDevices());
        return f;
    }

    String datesLabel(String d) {
        if (d.equals("this_month")) return tr("This month", "Ten miesiąc");
        if (d.equals("last3")) return tr("Latest 3 months", "Ostatnie 3 miesiące");
        if (d.equals("ytd")) return tr("Year to date", "Od początku roku");
        if (d.equals("last_year")) return tr("Last year", "Poprzedni rok");
        if (d.equals("all")) return tr("All dates", "Wszystkie daty");
        if (d.equals("custom")) return tr("Custom range…", "Własny zakres…");
        return tr("Latest 12 months", "Ostatnie 12 miesięcy");
    }

    /** Saved exclusions of choices still offered; payees no longer listed (transfers between budget
     * accounts, §152) add nothing to spending, so they must not lower the "n/total" count. */
    static Set<String> visibleExclusions(Set<String> excluded, List<ReportData.Item> items) {
        Set<String> out = new HashSet<String>();
        for (ReportData.Item item : items) if (excluded.contains(String.valueOf(item.id))) out.add(String.valueOf(item.id));
        return out;
    }

    String count(Set<String> excluded, int total) { int n = total - excluded.size(); return n == total ? tr("All", "Wszystkie") : n + "/" + total; }

    int activeFilters() {
        int count = 0;
        String defaultDates = report.equals("category") || report.equals("payee") ? "this_month" : "last12";
        if (report.equals("upcoming")) {
            if (prefs.getInt("upcoming.days", 30) != 30) count++;
        } else if (!dates().equals(defaultDates)) count++;
        for (String name : new String[]{"xcat", "xpay", "xacc"}) {
            if (!prefs.getStringSet(key(name), Collections.<String>emptySet()).isEmpty()) count++;
        }
        if (!prefs.getString(key("who"), "all").equals("all")) count++;
        return count;
    }

    void resetFilters() {
        SharedPreferences.Editor edit = prefs.edit();
        for (String name : new String[]{"dates", "from", "to", "xcat", "xpay", "xacc", "who"}) edit.remove(key(name));
        if (report.equals("upcoming")) edit.remove("upcoming.days");
        if (report.equals("income")) edit.remove("income.month_page");
        if (report.equals("income")) incomeScrollX = 0;
        edit.apply();
        drillGroup = NO_DRILL;
        trendGroup = null;
        trendPick = null;
        render();
    }

    void filterDatesAndReset() {
        List<View> row = new ArrayList<View>();
        if (report.equals("upcoming")) {
            final int days = prefs.getInt("upcoming.days", 30);
            row.add(chip(tr("Next ", "Najbliższe ") + days + tr(" days", " dni"), "filter-days", v -> {
                prefs.edit().putInt("upcoming.days", days == 30 ? 60 : days == 60 ? 90 : 30).apply();
                render();
            }));
        } else {
            row.add(chip(datesLabel(dates()), "filter-dates", v -> chooseDates()));
        }
        row.add(chip(tr("Reset filters", "Resetuj filtry"), "report-reset-filters", v -> resetFilters()));
        grid(row);
    }

    void filtersRow() {
        List<View> row = new ArrayList<View>();
        if (!report.equals("networth") && !report.equals("upcoming")) {
            row.add(chip(tr("Categories: ", "Kategorie: ") + count(prefs.getStringSet(key("xcat"), Collections.<String>emptySet()), categories.size()),
                "filter-categories", v -> chooseCategories()));
        }
        row.add(chip(tr("Accounts: ", "Konta: ") + count(prefs.getStringSet(key("xacc"), Collections.<String>emptySet()), visibleAccounts().size()),
            "filter-accounts", v -> chooseAccounts()));
        if (!report.equals("networth") && !report.equals("person") && !report.equals("upcoming")) {
            row.add(chip(tr("Last edited on: ", "Ostatnia edycja: ") + whoLabel(prefs.getString(key("who"), "all")),
                "filter-who", v -> chooseWho()));
        }
        if (hasSpendingFilters()) {
            row.add(chip(tr("Payees: ", "Płatnicy: ") + count(visibleExclusions(prefs.getStringSet(key("xpay"), Collections.<String>emptySet()), payees), payees.size()),
                "filter-payees", v -> choosePayees()));
        }
        grid(row);
    }

    String[] reportNames() {
        return new String[]{tr("Categories", "Kategorie"), tr("Payees", "Płatnicy"),
            tr("Spending Trends", "Trendy wydatków"),
            tr("Income / expense", "Przychody / wydatki"), tr("Net worth", "Majątek"),
            tr("Devices", "Urządzenia"), tr("Upcoming", "Nadchodzące"), tr("Budget analysis", "Analiza budżetu"), tr("Comparison", "Porównanie")};
    }

    private void selectReport(String kind) {
        report = kind;
        prefs.edit().putString("report", kind).apply();
        drillGroup = NO_DRILL;
        trendGroup = null;
        trendPick = null;
        render();
        root.post(() -> reportScroll.scrollTo(0, 0));
    }

    private boolean hasSpendingFilters() {
        return "payee".equals(report) || "category".equals(report) || "trends".equals(report)
            || "income".equals(report) || "comparison".equals(report);
    }

    void navigationBar() {
        navigation.removeAllViews();
        if (schedulePreview) {
            navigation.addView(chip(tr("‹ Scheduled transactions", "‹ Zlecenia stałe"), "schedule-preview-back", view -> finish()),
                new LinearLayout.LayoutParams(0, px(48), 1));
            navigation.addView(actionButton(tr("More", "Opcje"), "report-view-menu", view -> reportOptions()),
                new LinearLayout.LayoutParams(px(48), px(48)));
            return;
        }
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setContentDescription("report-tabs");
        LinearLayout tabs = new LinearLayout(this);
        scroller.addView(tabs);
        navigation.addView(scroller, new LinearLayout.LayoutParams(0, px(48), 1));
        String[] names = reportNames();
        Button selected = null;
        for (int i = 0; i < REPORTS.length; i++) {
            final String kind = REPORTS[i];
            if (!visible(kind)) continue;
            Button button = chip(names[i], "report-" + kind, v -> selectReport(kind));
            // 075: light text tabs sized to their label; the active one is underlined (was a filled button).
            button.setMaxLines(1);
            button.setTextSize(14);
            button.setBackground(tabBackground(report.equals(kind)));
            // After setBackground: the underline's InsetDrawable would otherwise become the padding.
            button.setPadding(px(12), 0, px(12), 0);
            if (report.equals(kind)) {
                selected = button;
                button.setTypeface(null, android.graphics.Typeface.BOLD);
                button.setSelected(true);
            } else button.setTextColor(MUTED);
            tabs.addView(button, new LinearLayout.LayoutParams(-2, px(48)));
        }
        final Button active = selected;
        scroller.post(() -> {
            // Keep the active tab in view, centred when possible.
            if (active != null) scroller.scrollTo(Math.max(0, (active.getLeft() + active.getRight() - scroller.getWidth()) / 2), 0);
        });
        // 089: search across all transactions, independent of the report and its filters.
        Button search = actionButton(tr("Search", "Szukaj"), "report-search-all", v -> searchAll());
        search.setCompoundDrawablesWithIntrinsicBounds(null, actionIcon("search"), null, null);
        navigation.addView(search, new LinearLayout.LayoutParams(px(48), px(48)));
        Button options = actionButton(tr("More", "Opcje"), "report-view-menu", v -> reportOptions());
        options.setCompoundDrawablesWithIntrinsicBounds(null, actionIcon("more"), null, null);
        navigation.addView(options, new LinearLayout.LayoutParams(px(48), px(48)));
    }

    /** All transactions of the budget in the report detail list (search, sort, account/payee/type). */
    void searchAll() {
        ReportTransactions list = new ReportTransactions(this, ReportTable.allTransactions(data));
        list.enableSearch(new java.io.File(data.db.getPath()).getName());
        list.open(tr("All transactions", "Wszystkie transakcje"));
    }

    /**
     * True when the original app has a budget open: BudgetSwitcher (core.backend.c, an enum whose
     * constant is "a") returns its current backend from a(). Read by reflection, no stub needed;
     * any failure counts as "not open", so the editor is never started blind.
     */
    static boolean originalBudgetOpen() {
        try {
            Class<?> switcher = Class.forName("com.youneedabudget.ynab.core.backend.c");
            Object instance = switcher.getField("a").get(null);
            return switcher.getMethod("a").invoke(instance) != null;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** List waiting for the original editor's result (090). */
    ReportTransactions editing;
    static final int EDIT_REQUEST = 721;

    /** The original editor of an existing transaction, opened as its register does (TRANSACTION_ID). */
    void editTransaction(ReportTransactions list, long txnId) {
        // The original editor needs the budget the original app has open; without it (e.g. Reports
        // restored after the process was killed) TxnEntryActivity crashes (090 test, §155).
        if (!originalBudgetOpen()) {
            Toast.makeText(this, tr("Open the budget in the app first, then search again to edit.",
                "Najpierw otwórz budżet w aplikacji, potem wyszukaj ponownie, aby edytować."), Toast.LENGTH_LONG).show();
            return;
        }
        android.content.Intent intent = new android.content.Intent().setClassName(getPackageName(), "com.youneedabudget.ynab.app.edit.TxnEntryActivity");
        intent.putExtra("com.youneedabudget.ynab.intent.extra.TRANSACTION_ID", txnId);
        editing = list;
        startActivityForResult(intent, EDIT_REQUEST);
    }

    android.graphics.drawable.Drawable tabBackground(boolean active) {
        android.graphics.drawable.StateListDrawable states = new android.graphics.drawable.StateListDrawable();
        android.graphics.drawable.GradientDrawable pressed = new android.graphics.drawable.GradientDrawable();
        pressed.setColor(Color.rgb(232, 240, 243));
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        if (active) {
            android.graphics.drawable.GradientDrawable bar = new android.graphics.drawable.GradientDrawable();
            // The dark accent disappears on the night background; use a light teal there.
            bar.setColor(ReportColors.night(this) ? Color.rgb(110, 190, 215) : ACCENT);
            states.addState(new int[]{}, new android.graphics.drawable.InsetDrawable(bar, px(8), px(45), px(8), 0));
        } else states.addState(new int[]{}, new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        return states;
    }

    /** Anything Reset would change for this report (dates, filters, page). */
    boolean resettable() {
        for (String name : new String[]{"dates", "from", "to", "xcat", "xpay", "xacc", "who"}) if (prefs.contains(key(name))) return true;
        return report.equals("upcoming") && prefs.contains("upcoming.days") || report.equals("income") && prefs.contains("income.month_page");
    }

    Button actionButton(String title, String description, View.OnClickListener click) {
        Button button = chip(title, description, click);
        button.setTextSize(9);
        button.setPadding(px(1), px(2), px(1), px(2));
        button.setMaxLines(1);
        button.setOnLongClickListener(v -> {
            Toast.makeText(this, title, Toast.LENGTH_SHORT).show();
            return true;
        });
        return button;
    }

    android.graphics.drawable.Drawable actionIcon(String kind) {
        return new android.graphics.drawable.Drawable() {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            public int getIntrinsicWidth() { return px(19); }
            public int getIntrinsicHeight() { return px(19); }
            public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                canvas.save();
                canvas.translate(bounds.left, bounds.top);
                float scale = bounds.width() / 24f;
                canvas.scale(scale, scale);
                paint.setColor(ACCENT);
                paint.setStrokeWidth(1.8f);
                paint.setStyle(Paint.Style.STROKE);
                if (kind.equals("more")) {
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = 0; i < 3; i++) canvas.drawCircle(12, 5 + 7 * i, 1.6f, paint);
                } else if (kind.equals("search")) {
                    canvas.drawCircle(10, 10, 6, paint);
                    canvas.drawLine(14.5f, 14.5f, 20, 20, paint);
                } else if (kind.equals("rotate")) {
                    canvas.drawRect(4, 8, 20, 18, paint);
                    canvas.drawLine(5, 4, 18, 4, paint);
                    canvas.drawLine(18, 4, 15, 1, paint);
                    canvas.drawLine(18, 4, 15, 7, paint);
                }
                canvas.restore();
            }
            public void setAlpha(int alpha) { paint.setAlpha(alpha); }
            public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
            public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }

    void reportOptions() {
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        labels.add(landscape ? tr("Portrait", "Pionowo") : tr("Landscape", "Poziomo"));
        actions.add(() -> rotateReport());
        labels.add(tr("Report visibility…", "Widoczność raportów…"));
        actions.add(() -> startActivity(new android.content.Intent(this, PlusSettingsActivity.class).putExtra("section", "reports")));
        labels.add(tr("Filters…", "Filtry…"));
        actions.add(() -> {
            if (report.equals("analysis")) new AlertDialog.Builder(this).setMessage(tr("This report shows the full monthly budget; change the month above the cards.", "Ten raport pokazuje pełny budżet miesięczny; miesiąc zmienisz nad kartami.")).setPositiveButton("OK", null).show();
            else filterMenu();
        });
        if (report.equals("income")) {
            labels.add(tr("Table…", "Tabela…"));
            actions.add(() -> incomeOptions());
        }
        if (!Arrays.asList("upcoming", "analysis", "comparison").contains(report) && !hide) {
            labels.add(tr("Export…", "Eksport…"));
            actions.add(() -> exportMenu());
        }
        labels.add(hide ? tr("Show amounts", "Pokaż kwoty") : tr("Hide amounts", "Ukryj kwoty"));
        actions.add(() -> {
            hide = !hide;
            prefs.edit().putBoolean("hide_amounts", hide).apply();
            render();
        });
        new AlertDialog.Builder(this).setTitle(tr("Report options", "Opcje raportu"))
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).show();
    }

    void exportMenu() {
        if (hide) return;
        new AlertDialog.Builder(this).setTitle(tr("Export", "Eksport"))
            .setItems(new String[]{tr("Export CSV", "Eksport CSV"), tr("Print / PDF", "Drukuj / PDF")}, (dialog, which) -> {
                if (which == 0) exportCsv();
                else printReport();
            }).show();
    }

    void filterMenu() {
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        if (!report.equals("upcoming")) {
            labels.add(tr("Dates", "Daty"));
            actions.add(() -> chooseDates());
        }
        if (!report.equals("networth") && !report.equals("upcoming")) {
            labels.add(tr("Categories", "Kategorie"));
            actions.add(() -> chooseCategories());
        }
        if (report.equals("upcoming")) {
            labels.add(tr("Upcoming period", "Okres nadchodzących"));
            actions.add(() -> ReportChoiceDialog.show(this, tr("Upcoming period", "Okres nadchodzących"),
                new String[]{tr("30 days", "30 dni"), tr("60 days", "60 dni"), tr("90 days", "90 dni")},
                prefs.getInt("upcoming.days", 30) / 30 - 1, index -> {
                    prefs.edit().putInt("upcoming.days", (index + 1) * 30).apply();
                    render();
                }));
        }
        labels.add(tr("Accounts", "Konta"));
        actions.add(() -> chooseAccounts());
        if (hasSpendingFilters()) {
            labels.add(tr("Payees", "Płatnicy"));
            actions.add(() -> choosePayees());
            labels.add(tr("Last edited on", "Ostatnia edycja"));
            actions.add(() -> chooseWho());
        }
        labels.add(tr("Reset filters", "Resetuj filtry"));
        actions.add(() -> resetFilters());
        new AlertDialog.Builder(this).setTitle(tr("Filters", "Filtry"))
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).show();
    }

    void incomeOptions() {
        if (incomeModel == null) return;
        boolean summary = prefs.getBoolean("income.summary", false);
        List<String> labels = new ArrayList<String>();
        List<Runnable> actions = new ArrayList<Runnable>();
        labels.add(summary ? tr("Monthly columns", "Kolumny miesięcy") : tr("Summary: average and total", "Podsumowanie: średnio i razem"));
        actions.add(() -> {
            prefs.edit().putBoolean("income.summary", !summary).apply();
            render();
        });
        boolean alphabetical = prefs.getBoolean("income.sources_az", false);
        labels.add(alphabetical ? tr("Income sources: largest first", "Źródła przychodu: od największych")
            : tr("Income sources: A–Z", "Źródła przychodu: A–Z"));
        actions.add(() -> {
            prefs.edit().putBoolean("income.sources_az", !alphabetical).apply();
            render();
        });
        for (boolean expanded : new boolean[]{true, false}) {
            labels.add(expanded ? tr("Expand all groups", "Rozwiń wszystkie grupy") : tr("Collapse all groups", "Zwiń wszystkie grupy"));
            actions.add(() -> {
                SharedPreferences.Editor edit = prefs.edit();
                edit.putBoolean("income.expanded." + incomeModel.income.key, expanded);
                for (ReportTable.Row row : incomeModel.groups) edit.putBoolean("income.expanded." + row.key, expanded);
                edit.apply();
                render();
            });
        }
        if (!summary && incomeModel.months.size() > 12) {
            int first = Math.min(Math.max(0, prefs.getInt("income.month_page", 0)), Math.max(0, incomeModel.months.size() - 12));
            if (first > 0) {
                labels.add(tr("Earlier months", "Wcześniejsze miesiące"));
                actions.add(() -> {
                    prefs.edit().putInt("income.month_page", Math.max(0, first - 12)).apply();
                    incomeScrollX = 0;
                    render();
                });
            }
            if (first + 12 < incomeModel.months.size()) {
                labels.add(tr("Later months", "Późniejsze miesiące"));
                actions.add(() -> {
                    prefs.edit().putInt("income.month_page", Math.min(incomeModel.months.size() - 12, first + 12)).apply();
                    incomeScrollX = 0;
                    render();
                });
            }
        }
        new AlertDialog.Builder(this).setTitle(tr("Table", "Tabela"))
            .setItems(labels.toArray(new String[0]), (dialog, which) -> actions.get(which).run()).show();
    }

    void reportControls() {
        List<View> row = new ArrayList<View>();
        if (report.equals("upcoming")) {
            int days = prefs.getInt("upcoming.days", 30);
            row.add(chip(tr("Next ", "Najbliższe ") + days + tr(" days", " dni"), "filter-days", v -> {
                prefs.edit().putInt("upcoming.days", days == 30 ? 60 : days == 60 ? 90 : 30).apply();
                render();
            }));
        } else row.add(chip(datesLabel(dates()), "filter-dates", v -> chooseDates()));
        int active = activeFilters();
        row.add(chip(tr("Filters", "Filtry") + (active == 0 ? "" : " · " + active) + " ▾", "report-filters-toggle", v -> {
            prefs.edit().putBoolean("controls.filters_open", !prefs.getBoolean("controls.filters_open", false)).apply();
            render();
        }));
        LinearLayout line = new LinearLayout(this);
        for (View view : row) line.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
        // 075: Reset only when there is something to reset, as a small text action.
        if (resettable()) {
            Button reset = chip(tr("Reset", "Resetuj"), "report-reset-filters", v -> resetFilters());
            reset.setBackground(tabBackground(false));
            reset.setPadding(px(12), 0, px(12), 0);
            line.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        }
        root.addView(line);
        if (prefs.getBoolean("controls.filters_open", false)) filtersRow();
    }

    /** Two buttons per row: everything stays visible on a narrow phone. */
    void grid(List<View> views) {
        for (int i = 0; i < views.size(); i += 2) {
            LinearLayout line = new LinearLayout(this);
            for (int k = i; k < Math.min(i + 2, views.size()); k++) { View v = views.get(k); v.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); line.addView(v); }
            if (views.size() - i == 1) { View pad = new View(this); pad.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1)); line.addView(pad); }
            root.addView(line);
        }
    }

    /** Spending and income use budget accounts (like the desktop); net worth lists every account. */
    List<ReportData.Item> visibleAccounts() {
        if (report.equals("networth") || report.equals("upcoming")) return accounts;
        List<ReportData.Item> out = new ArrayList<ReportData.Item>(); for (ReportData.Item a : accounts) if (a.onBudget) out.add(a); return out;
    }

    void chooseDates() {
        String[] labels = new String[DATES.length];
        int checked = 0;
        for (int i = 0; i < DATES.length; i++) {
            labels[i] = datesLabel(DATES[i]);
            if (DATES[i].equals(dates())) checked = i;
        }
        ReportChoiceDialog.show(this, tr("Dates", "Daty"), labels, checked, which -> {
            prefs.edit().putString(key("dates"), DATES[which]).apply();
            incomeScrollX = 0;
            if (DATES[which].equals("custom")) chooseMonth(true);
            else render();
        });
    }

    void chooseMonth(final boolean from) {
        String[] span = data.span(); String now = ReportData.thisMonth();
        final List<String> months = ReportData.months(span[0], span[1].compareTo(now) > 0 ? span[1] : now); Collections.reverse(months);
        String[] labels = new String[months.size()]; for (int i = 0; i < labels.length; i++) labels[i] = label(months.get(i));
        new AlertDialog.Builder(this).setTitle(from ? tr("From month", "Od miesiąca") : tr("To month", "Do miesiąca")).setItems(labels, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                prefs.edit().putString(key(from ? "from" : "to"), months.get(which)).apply();
                incomeScrollX = 0;
                if (from) chooseMonth(false); else render();
            }
        }).show();
    }

    /** Checkbox list like the desktop selectors: OK, Select all, Select none (or "My accounts"). */
    void chooseSet(String title, final String prefKey, final List<ReportData.Item> items, final boolean useKey, final Set<String> myExcluded) {
        ReportFilterDialog.show(this, title, items, useKey, "xcat".equals(prefKey),
            prefs.getStringSet(key(prefKey), Collections.<String>emptySet()), myExcluded,
            new ReportFilterDialog.Saved() { public void apply(Set<String> excluded) { save(prefKey, excluded); } });
    }

    static String id(ReportData.Item it, boolean useKey) { return useKey ? it.key : String.valueOf(it.id); }
    void save(String prefKey, Set<String> excluded) { prefs.edit().putStringSet(key(prefKey), excluded).apply(); render(); }

    void chooseCategories() { chooseSet(tr("Categories", "Kategorie"), "xcat", categories, false, null); }
    void choosePayees() { chooseSet(tr("Payees", "Płatnicy"), "xpay", payees, false, null); }
    void chooseAccounts() {
        // Optional dependency on the accounts module: its "My accounts" list as a one-tap preset.
        Set<String> mine = getSharedPreferences("cb-plus-accounts", 0).getStringSet("excluded", Collections.<String>emptySet());
        chooseSet(tr("Accounts", "Konta"), "xacc", visibleAccounts(), true, mine);
    }

    // --- screen -----------------------------------------------------------------------------------

    boolean visible(String kind) {
        return !Arrays.asList("person", "upcoming", "analysis", "comparison").contains(kind) || prefs.getBoolean("visible." + kind, false);
    }

    void render() {
        if (schedulePanel != null) {
            screenFrame.removeView(schedulePanel);
            schedulePanel = null;
        }
        reportScroll.setVisibility(View.VISIBLE);
        if (!schedulePreview && !visible(report)) {
            report = "category";
            drillGroup = NO_DRILL;
            prefs.edit().putString("report", report).apply();
        }
        stickyHeader = null;
        stickyContainer = null;
        incomeModel = null;
        root.removeAllViews();
        root.setPadding(px(8), 0, px(8), px(8));
        navigationBar();
        if (report.equals("upcoming")) {
            reportScroll.setVisibility(View.GONE);
            schedulePanel = new ScheduleReportView(this);
            screenFrame.addView(schedulePanel, new LinearLayout.LayoutParams(-1, 0, 1));
            return;
        }
        root.addView(text(data.budgetName(), 12, MUTED));
        if (report.equals("analysis")) {
            new ReportExtensionViews(this).budget();
            return;
        }
        reportControls();
        ReportData.Filter f = filter();
        TextView range = text(tr("Showing ", "Zakres: ") + label(f.first) + (f.first.equals(f.last) ? "" : " – " + label(f.last)), 13, MUTED); range.setContentDescription("report-range"); if (!report.equals("upcoming")) root.addView(range);
        if (report.equals("category")) category(f);
        else if (report.equals("trends")) trends(f);
        else if (report.equals("payee")) payee(f);
        else if (report.equals("income")) income(f);
        else if (report.equals("person")) person(f);
        else if (report.equals("comparison")) new ReportExtensionViews(this).comparison(f);
        else netWorth(f);
        root.addView(text(tr("\nSpending uses budget-account transactions, net of refunds. Transfers to off-budget accounts count as spending.",
            "\nWydatki obejmują transakcje na kontach budżetowych po odjęciu zwrotów, w tym przelewy na konta poza budżetem."), 12, MUTED));
    }

    void category(ReportData.Filter f) {
        LinearLayout mode = new LinearLayout(this);
        mode.addView(chip(tr("Spending Trends ›", "Trendy wydatków ›"), "category-trends", view -> selectReport("trends")));
        root.addView(mode);
        List<ReportData.Group> groups = data.byCategory(f);
        long total = 0; for (ReportData.Group g : groups) total += g.amount;
        if (groups.isEmpty()) { root.addView(text(tr("No spending in this range.", "Brak wydatków w tym zakresie."), 14, MUTED)); return; }
        TextView t = text(tr("Total: ", "Razem: ") + (hide ? "100%" : money.format(total / 100.0)), 16, TEXT); t.setContentDescription("report-total"); root.addView(t);
        root.addView(text(tr("Tap a group to see its categories", "Dotknij grupy, aby zobaczyć kategorie"), 12, MUTED));
        new DesktopReports(this).ranking(f,groups,true);
    }

    /** Trends drill-down (076): the group whose categories the chart shows, null = all groups. */
    String trendGroup;
    /** Series picked by a tap on the chart (highlighted, named above the chart); null = none. */
    String trendPick;

    void trends(ReportData.Filter f) {
        ReportTable model = new ReportTable(data, f);
        ReportTable.Row opened = null;
        if (trendGroup != null) for (ReportTable.Row g : model.groups) if (g.name.equals(ReportTable.localized(trendGroup))) opened = g;
        if (opened == null) trendGroup = null;
        final ReportTable.Row group = opened;
        List<ReportData.Month> months;
        if (group == null) months = data.monthly(f);
        else {
            // Same columns for the categories of one group (spending positive, like the group chart).
            months = new ArrayList<ReportData.Month>();
            for (int i = 0; i < model.months.size(); i++) {
                ReportData.Month m = new ReportData.Month();
                m.month = model.months.get(i);
                m.spending = -group.amounts[i];
                for (ReportTable.Row child : group.children) if (child.amounts[i] != 0) m.groups.put(child.name, -child.amounts[i]);
                months.add(m);
            }
        }
        Map<String, Long> totals = new HashMap<String, Long>();
        for (ReportData.Month m : months) for (Map.Entry<String, Long> e : m.groups.entrySet()) { Long o = totals.get(e.getKey()); totals.put(e.getKey(), (o == null ? 0 : o) + e.getValue()); }
        List<String> top = new ArrayList<String>(totals.keySet());
        final Map<String, Long> tt = totals; Collections.sort(top, new Comparator<String>() { public int compare(String a, String b) { return tt.get(b).compareTo(tt.get(a)); } });
        // Seven series plus "Other": one distinct palette hue each (075).
        int shown = ReportColors.LIGHT.length - 1;
        boolean other = top.size() > shown;
        if (other) top = new ArrayList<String>(top.subList(0, shown));
        if (trendPick != null && !top.contains(trendPick)) trendPick = null;
        final Map<String, Integer> colours = group == null ? trendColors(top, f) : categoryColors(top, group);
        final List<String> series = top;

        if (group != null) {
            // Path back, as in the desktop chart after opening a group.
            LinearLayout path = new LinearLayout(this);
            path.setGravity(Gravity.CENTER_VERTICAL);
            path.setMinimumHeight(px(40));
            TextView back = text("‹ " + tr("All groups", "Wszystkie grupy"), 14, LINE);
            back.setContentDescription("trend-back");
            back.setPadding(0, px(8), px(10), px(8));
            back.setOnClickListener(v -> { trendGroup = null; trendPick = null; render(); });
            path.addView(back);
            path.addView(text("›  " + group.name, 14, TEXT));
            root.addView(path);
        }
        // Legend above the chart (076): swatch and name, wrapped; a group chip opens its categories.
        Flow legend = new Flow(this);
        for (String name : series) {
            View chip = legendChip(ReportTable.localized(name), colours.get(name), "trend-group:" + name);
            chip.setOnClickListener(v -> {
                if (group == null) { trendGroup = name; trendPick = null; render(); }
                else { trendPick = name.equals(trendPick) ? null : name; render(); }
            });
            legend.addView(chip);
        }
        if (other) legend.addView(legendChip(tr("Other", "Pozostałe"), ReportColors.other(this), "trend-other"));
        root.addView(legend);

        final Stacked chart = new Stacked(this, months, series, colours);
        root.addView(chart);
        final LinearLayout comparison = new LinearLayout(this);
        root.addView(comparison, new LinearLayout.LayoutParams(-1, -2));
        // What the tap picked, with the next step: open the group's categories or the transactions.
        final LinearLayout pick = new LinearLayout(this);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pick.setContentDescription("trend-pick");
        root.addView(pick, new LinearLayout.LayoutParams(-1, -2));
        chart.onMonth = () -> showTrendMonths(comparison, chart);
        chart.onPick = () -> {
            chart.onMonth.run();
            showPick(pick, chart, f, model, group);
        };
        chart.onPick.run();

        long sum = 0; for (ReportData.Month m : months) sum += m.spending;
        TextView avg = text(tr("Average per month: ", "Średnio miesięcznie: ") + cash(ReportTable.average(sum, months.size())), 13, MUTED); avg.setContentDescription("report-average"); root.addView(avg);
        TextView total = text(tr("Total: ", "Razem: ") + cash(sum), 16, TEXT); total.setContentDescription("report-total"); root.addView(total);
        TableLayout history = new TableLayout(this); history.setStretchAllColumns(true);
        for (ReportData.Month m : months) history.addView(line(new String[]{label(m.month), cash(m.spending)}, false, "trend-month:" + m.month));
        root.addView(history);
    }

    /** Show nearby months for the picked series without crowding the chart. */
    void showTrendMonths(LinearLayout comparison, Stacked chart) {
        comparison.removeAllViews();
        int count = chart.months.size();
        if (trendPick == null || count < 2) return;
        int visible = Math.min(count, 3);
        int first = Math.max(0, Math.min(chart.selected - 1, count - visible));
        for (int i = first; i < first + visible; i++) {
            ReportData.Month month = chart.months.get(i);
            Long amount = month.groups.get(trendPick);
            TextView value = text(label(month.month) + "\n" + cash(amount == null ? 0 : amount), 13, TEXT);
            value.setGravity(Gravity.CENTER);
            value.setPadding(px(2), px(6), px(2), px(6));
            if (i == chart.selected) value.setTypeface(Typeface.DEFAULT_BOLD);
            comparison.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
        }
    }

    /** Links to the selected series or the transactions for the highlighted month. */
    void showPick(LinearLayout pick, Stacked chart, ReportData.Filter f, ReportTable model, ReportTable.Row group) {
        pick.removeAllViews();
        final String name = trendPick;
        if (name != null && group == null) {
            pick.addView(link(tr("Categories of the group ›", "Kategorie grupy ›"), "trend-drill", v -> { trendGroup = name; trendPick = null; render(); }));
        }
        pick.addView(link(tr("Transactions ›", "Transakcje ›"), "trend-transactions", v -> {
            ReportTable.Row scope = group == null ? model.expenses : group;
            if (name != null) {
                List<ReportTable.Row> rows = group == null ? model.groups : group.children;
                for (ReportTable.Row r : rows) if (r.name.equals(ReportTable.localized(name))) scope = r;
            }
            new DesktopReports(this).details(f, scope, chart.months.get(chart.selected).month);
        }));
    }

    TextView link(String label, String desc, View.OnClickListener click) {
        TextView t = text(label, 14, LINE);
        t.setContentDescription(desc);
        t.setPadding(0, px(12), px(18), px(12));
        t.setOnClickListener(click);
        return t;
    }

    View legendChip(String name, int colour, String desc) {
        LinearLayout chip = new LinearLayout(this);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setContentDescription(desc);
        chip.setMinimumHeight(px(36));
        chip.setPadding(0, 0, px(14), 0);
        View swatch = new View(this);
        ReportColors.ownRendering(swatch);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(px(2));
        box.setColor(colour);
        swatch.setBackground(box);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(px(10), px(10));
        sp.setMargins(0, 0, px(6), 0);
        chip.addView(swatch, sp);
        TextView label = text(name, 13, TEXT);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setMaxWidth(px(170));
        chip.addView(label);
        return chip;
    }

    /** Wraps children into lines, left to right (legend chips). */
    static final class Flow extends ViewGroup {
        Flow(Context context) { super(context); }
        protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line; line = 0; }
                x += child.getMeasuredWidth();
                line = Math.max(line, child.getMeasuredHeight());
            }
            setMeasuredDimension(width, y + line);
        }
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int width = r - l, x = 0, y = 0, line = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (x > 0 && x + child.getMeasuredWidth() > width) { x = 0; y += line; line = 0; }
                child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                x += child.getMeasuredWidth();
                line = Math.max(line, child.getMeasuredHeight());
            }
        }
    }

    String whoLabel(String w) { return "me".equals(w) ? tr("My devices", "Moje urządzenia") : "others".equals(w) ? tr("Other devices", "Inne urządzenia") : tr("All", "Wszyscy"); }

    void chooseWho() {
        final String[] values = {"all", "me", "others"}; String[] labels = new String[3]; int checked = 0;
        for (int i = 0; i < 3; i++) { labels[i] = whoLabel(values[i]); if (values[i].equals(prefs.getString(key("who"), "all"))) checked = i; }
        new AlertDialog.Builder(this).setTitle(tr("Last edited on", "Ostatnia edycja")).setSingleChoiceItems(labels, checked, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int which) { d.dismiss(); prefs.edit().putString(key("who"), values[which]).apply(); render(); } }).show();
    }

    Map<String, String> deviceNames() { return DeviceNames.all(this); }
    protected void onResume(){super.onResume();if(root!=null&&data!=null)render();}

    void person(ReportData.Filter f) {
        Set<String> mine = data.mineDevices(); Map<String, String> names = deviceNames();
        List<ReportData.Group> rows = data.byDevice(f); long total = 0;
        for (ReportData.Group g : rows) {
            total += g.amount; String letter = g.name;
            g.name = (names.containsKey(letter) ? names.get(letter) : mine.contains(letter) ? tr("Me", "Ja") : tr("Device ", "Urządzenie ") + letter) + " (" + letter + ")";
        }
        Button rename = chip(tr("Manage devices…", "Zarządzaj urządzeniami…"), "name-devices", new View.OnClickListener() { public void onClick(View v) {
            android.content.Intent intent=new android.content.Intent(ReportsActivity.this,PlusSettingsActivity.class);intent.putExtra("section","devices");startActivity(intent);
        }});
        root.addView(rename);
        if (rows.isEmpty()) { root.addView(text(tr("No spending in this range.", "Brak wydatków w tym zakresie."), 14, MUTED)); return; }
        TextView t = text(tr("Total: ", "Razem: ") + (hide ? "100%" : money.format(total / 100.0)), 16, TEXT); t.setContentDescription("report-total"); root.addView(t);
        root.addView(new Bars(this, rows, total, false));
        root.addView(text(tr("Grouped by the device that last changed each transaction (an edit on the computer moves it there). Your devices: ", "Według urządzenia, które ostatnio zmieniło transakcję (edycja na komputerze ją tam przenosi). Twoje urządzenia: ") + mine
            + tr(" (manage them in Plus settings, Devices).", " (zarządzanie w Ustawieniach Plus → Urządzenia)."), 12, MUTED));
    }

    void payee(ReportData.Filter f) {
        List<ReportData.Group> rows = data.byPayee(f);
        long total = 0; for (ReportData.Group g : rows) total += g.amount;
        if (rows.isEmpty()) { root.addView(text(tr("No spending in this range.", "Brak wydatków w tym zakresie."), 14, MUTED)); return; }
        TextView t = text(tr("Total: ", "Razem: ") + (hide ? "100%" : money.format(total / 100.0)), 16, TEXT); t.setContentDescription("report-total"); root.addView(t);
        new DesktopReports(this).ranking(f,rows,false);
    }

    void income(ReportData.Filter f) {
        LinearLayout modes = new LinearLayout(this);
        boolean chart = prefs.getBoolean("income.chart", false);
        for (boolean choice : new boolean[]{false, true}) {
            Button button = chip(choice ? tr("Chart", "Wykres") : tr("Table", "Tabela"),
                choice ? "income-chart" : "income-table", view -> {
                    prefs.edit().putBoolean("income.chart", choice).apply();
                    render();
                });
            button.setSelected(chart == choice);
            modes.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        root.addView(modes);
        if (!chart) {
            new DesktopReports(this).income(f);
            return;
        }
        incomeModel = new ReportTable(data, f);
        List<ReportData.Month> months = new ArrayList<ReportData.Month>();
        for (int i = 0; i < incomeModel.months.size(); i++) {
            ReportData.Month month = new ReportData.Month();
            month.month = incomeModel.months.get(i);
            month.income = incomeModel.income.amounts[i];
            month.spending = -incomeModel.expenses.amounts[i];
            months.add(month);
        }
        root.addView(legend(tr("■ income   ", "■ przychody   "), INCOME, tr("■ spending", "■ wydatki"), SPEND));
        root.addView(new CashFlow(this, months));
        root.addView(text(tr("Tap a month for transactions. Negative values stay below zero.", "Dotknij miesiąca, aby zobaczyć transakcje. Wartości ujemne są pod zerem."), 12, MUTED));
        TableLayout table = new TableLayout(this);
        table.setStretchAllColumns(true);
        table.addView(line(new String[]{tr("Month", "Miesiąc"), tr("Income", "Przychody"), tr("Spending", "Wydatki"), tr("Net", "Netto")}, true, null));
        table.addView(line(new String[]{tr("Total", "Razem"), cash(incomeModel.income.total()), cash(-incomeModel.expenses.total()), cash(incomeModel.net.total())}, true, "cashflow-total"));
        for (ReportData.Month month : months) table.addView(line(new String[]{label(month.month), cash(month.income), cash(month.spending), cash(month.income - month.spending)}, false, "cashflow-month:" + month.month));
        root.addView(table);
    }

    private List<List<String>> exportRows() {
        boolean spendingTrends = "trends".equals(report);
        return ReportExport.rows(data, filter(), spendingTrends ? "category" : report, spendingTrends);
    }

    void exportCsv(){
        pendingExport=ReportExport.csv(exportRows());
        android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);intent.setType("text/csv");intent.putExtra(android.content.Intent.EXTRA_TITLE,"YNAB-"+report+"-"+filter().first+"-"+filter().last+".csv");
        // Start in Download, not the last folder used: that is often the synced budget folder (Syncthing/Folder backend).
        if(android.os.Build.VERSION.SDK_INT>=26)intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
            android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Download"));
        try{startActivityForResult(intent,720);}catch(android.content.ActivityNotFoundException unavailable){pendingExport=null;Toast.makeText(this,tr("No document provider available","Brak aplikacji do zapisu dokumentu"),Toast.LENGTH_LONG).show();}
    }
    protected void onActivityResult(int request,int result,android.content.Intent intent){super.onActivityResult(request,result,intent);
        if(request==EDIT_REQUEST){
            // Saved or cancelled: re-read either way; the editor may also have deleted the transaction.
            if(editing!=null)editing.reload(ReportTable.allTransactions(data));
            render();
            return;
        }
        if(request!=720)return;
        if(result==RESULT_OK&&intent!=null&&intent.getData()!=null&&pendingExport!=null){
            try(java.io.OutputStream out=getContentResolver().openOutputStream(intent.getData(),"w")){
                if(out==null)throw new java.io.IOException("No output stream");out.write(pendingExport.getBytes("UTF-8"));
                Toast.makeText(this,tr("Report saved","Raport zapisany"),Toast.LENGTH_SHORT).show();
            }catch(Exception e){Toast.makeText(this,tr("Could not save the report","Nie udało się zapisać raportu"),Toast.LENGTH_LONG).show();}
        }pendingExport=null;
    }
    void printReport(){
        if(printView!=null)printView.destroy();printView=new android.webkit.WebView(this);printView.getSettings().setJavaScriptEnabled(false);
        printView.setWebViewClient(new android.webkit.WebViewClient(){public void onPageFinished(android.webkit.WebView view,String url){
            android.print.PrintManager manager=(android.print.PrintManager)getSystemService(PRINT_SERVICE);
            if(manager!=null)manager.print("YNAB "+report,view.createPrintDocumentAdapter("YNAB "+report),new android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4.asLandscape()).build());
        }});printView.loadDataWithBaseURL(null,ReportExport.html(exportRows()),"text/html","UTF-8",null);
    }

    /** One table row; with `desc`, cells get "<desc>-<column>" for tests and accessibility. */
    TableRow line(String[] cells, boolean bold, String desc) {
        TableRow r = new TableRow(this); String[] cols = {"name", "income", "spending", "net"};
        for (int i = 0; i < cells.length; i++) {
            TextView t = cell(cells[i], bold); if (i == 0) t.setGravity(Gravity.START);
            if (i == 1 && cells.length == 4) t.setTextColor(INCOME); if (i == 2 && cells.length == 4) t.setTextColor(SPEND);
            if (desc != null) t.setContentDescription(desc + "-" + cols[Math.min(i, 3)]); r.addView(t);
        }
        return r;
    }

    TextView cell(String s, boolean bold) { TextView t = text(s, 12, TEXT); t.setPadding(px(6), px(3), px(6), px(3)); t.setGravity(Gravity.END); if (bold) t.setTypeface(Typeface.DEFAULT_BOLD); return t; }

    void netWorth(ReportData.Filter f) {
        List<ReportData.Month> months = data.monthly(f);
        root.addView(legend(tr("■ assets   ", "■ aktywa   "), INCOME, tr("■ debts   ", "■ długi   "), SPEND));
        root.addView(text(tr("— net worth", "— majątek netto"), 13, LINE));
        root.addView(new NetWorthChart(this, months));
        ReportData.Month first = months.get(0), last = months.get(months.size() - 1);
        TextView t = text(label(last.month) + ": " + cash(last.net()) + tr("  (assets ", "  (aktywa ") + cash(last.assets) + tr(", debts ", ", długi ") + cash(-last.debts) + ")", 14, TEXT);
        t.setContentDescription("networth-value"); root.addView(t);
        new DesktopReports(this).months(months,true);
        if (!hide) root.addView(text(tr("Change since ", "Zmiana od ") + label(first.month) + ": " + money.format((last.net() - first.net()) / 100.0), 13, MUTED));
    }

    TextView text(String s, int size, int color) { TextView t = new TextView(this); t.setText(s); t.setTextSize(size); t.setTextColor(color); return t; }
    Button chip(String s, String desc, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(s);
        button.setTextSize(12);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(px(48));
        button.setMinimumHeight(px(48));
        button.setPadding(px(8), px(5), px(8), px(5));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_pressed}, controlBackground(Color.rgb(218, 231, 237)));
        background.addState(new int[]{}, controlBackground(Color.rgb(239, 245, 247)));
        button.setBackground(background);
        button.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
            new int[]{MUTED, ACCENT}));
        button.setContentDescription(desc);
        button.setOnClickListener(listener);
        return button;
    }

    android.graphics.drawable.GradientDrawable controlBackground(int color) {
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(px(9));
        background.setStroke(px(1), Color.rgb(217, 229, 234));
        return background;
    }
    View legend(String a, int ca, String b, int cb) { LinearLayout l = new LinearLayout(this); l.addView(text(a, 13, ca)); l.addView(text(b, 13, cb)); return l; }

    // --- charts -----------------------------------------------------------------------------------

    /** Horizontal bars; with groups, a tap expands the group's categories. */
    final class Bars extends LinearLayout {
        Bars(Context c, List<ReportData.Group> groups, final long total, boolean expandable) {
            super(c); setOrientation(VERTICAL);
            long max = 1; for (ReportData.Group g : groups) max = Math.max(max, g.amount);
            for (final ReportData.Group g : groups) {
                final Bar bar = new Bar(c, g.name, g.amount, max, total, SPEND); bar.setContentDescription("bar-" + g.name); addView(bar);
                if (!expandable) continue;
                final LinearLayout kids = new LinearLayout(c); kids.setOrientation(VERTICAL); kids.setVisibility(GONE); kids.setPadding(px(16), 0, 0, 0);
                for (ReportData.Group k : g.children) kids.addView(new Bar(c, k.name, k.amount, max, total, Color.argb(160, 205, 92, 72)));
                addView(kids);
                bar.setOnClickListener(new OnClickListener() { public void onClick(View v) { kids.setVisibility(kids.getVisibility() == GONE ? VISIBLE : GONE); } });
            }
        }
    }

    final class Bar extends View {
        final String name; final long value, max, total; final int color; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Bar(Context c, String name, long value, long max, long total, int color) { super(c); this.name = name; this.value = value; this.max = max; this.total = total; this.color = color; }
        protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), px(40)); }
        protected void onDraw(Canvas c) {
            float w = getWidth(), barH = px(10), top = px(24);
            String v = amount(value, total); paint.setTextSize(px(13)); float vw = paint.measureText(v);
            paint.setColor(TEXT); String n = name; while (n.length() > 3 && paint.measureText(n) > w - vw - px(12)) n = n.substring(0, n.length() - 2) + "…";
            c.drawText(n, 0, px(16), paint); paint.setColor(MUTED); c.drawText(v, w - vw, px(16), paint);
            paint.setColor(Color.argb(45, 128, 128, 128)); c.drawRoundRect(new RectF(0, top, w, top + barH), px(3), px(3), paint);   // neutral track, light and dark theme
            paint.setColor(color); float len = max <= 0 ? 0 : Math.max(px(2), w * Math.max(0, value) / (float) max);
            c.drawRoundRect(new RectF(0, top, len, top + barH), px(3), px(3), paint);
        }
    }

    /** Base for monthly column charts: tap selects a month and shows its numbers on top. */
    abstract class MonthChart extends View {
        final List<ReportData.Month> months; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); int selected; final String desc;
        MonthChart(Context c, List<ReportData.Month> months, String desc) { super(c); this.months = months; this.desc = desc; selected = months.size() - 1; describe(); }
        /** "chart-x 2026-09": the selected month, for TalkBack and the UI tests (the caption is drawn). */
        void describe() { setContentDescription(desc + (selected >= 0 && selected < months.size() ? " " + months.get(selected).month : "")); }
        protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), px(220)); }
        float downX, downY; boolean dragging, vertical;
        int monthAt(float x) { return Math.min(months.size() - 1, Math.max(0, (int) (x / (getWidth() / (float) months.size())))); }
        void select(float x) {
            int m = monthAt(x);
            if (m != selected) { selected = m; describe(); invalidate(); if (dragging) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); }
        }
        /**
         * Tap selects a month; a sideways drag scrubs across months and the numbers on top follow
         * the finger. A mostly vertical move is left to the page scroll.
         */
        public boolean onTouchEvent(MotionEvent e) {
            int slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: downX = e.getX(); downY = e.getY(); dragging = false; vertical = false; return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging) {
                        float dx = Math.abs(e.getX() - downX), dy = Math.abs(e.getY() - downY);
                        if (vertical || (dy > slop && dy > dx)) { vertical = true; return false; }   // vertical: page scroll takes over, no selection
                        if (dx > slop) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); }
                    }
                    if (dragging) select(e.getX());
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!vertical) { select(e.getX()); if (!dragging) performClick(); }
                    dragging = false; getParent().requestDisallowInterceptTouchEvent(false); return true;
                case MotionEvent.ACTION_CANCEL: dragging = false; return true;
            }
            return true;
        }
        public boolean performClick() { return super.performClick(); }
        void axis(Canvas c, float base) {
            float slot = getWidth() / (float) months.size(); paint.setTextSize(px(10)); paint.setColor(ReportColors.muted(ReportsActivity.this));
            int step = months.size() <= 12 ? 1 : months.size() <= 24 ? 2 : (months.size() + 11) / 12;
            for (int i = 0; i < months.size(); i += step) { String l = shortLabel(months.get(i).month); c.drawText(l, i * slot + (slot - paint.measureText(l)) / 2, base + px(14), paint); }
            if (selected >= 0) { paint.setColor(Color.argb(30, 0, 0, 0)); c.drawRect(selected * slot, px(22), (selected + 1) * slot, base, paint); }
        }
        void caption(Canvas c, String s) { paint.setColor(ReportColors.text(ReportsActivity.this)); paint.setTextSize(px(12)); c.drawText(s, 0, px(14), paint); }
    }

    final class CashFlow extends MonthChart {
        CashFlow(Context c, List<ReportData.Month> months) {
            super(c, months, "chart-cashflow");
            setClickable(true);
            ReportColors.ownRendering(this);
        }
        public boolean performClick() {
            super.performClick();
            String month = months.get(selected).month;
            ReportTable model = incomeModel;
            ReportChoiceDialog.show(ReportsActivity.this, label(month),
                new String[]{tr("Income", "Przychody"), tr("Spending", "Wydatki"), tr("All transactions", "Wszystkie transakcje")}, -1,
                index -> new DesktopReports(ReportsActivity.this).details(filter(), index == 0 ? model.income : index == 1 ? model.expenses : model.net, month));
            return true;
        }
        protected void onDraw(Canvas c) {
            float w = getWidth(), top = px(30), height = px(150), slot = w / months.size();
            long positive = 1, negative = 0;
            for (ReportData.Month month : months) {
                positive = Math.max(positive, Math.max(month.income, month.spending));
                negative = Math.min(negative, Math.min(month.income, month.spending));
            }
            float scale = height / (float) (positive - negative);
            float zero = top + positive * scale;
            axis(c, top + height);
            paint.setColor(ReportColors.muted(ReportsActivity.this));
            c.drawLine(0, zero, w, zero, paint);
            for (int i = 0; i < months.size(); i++) {
                ReportData.Month month = months.get(i);
                long[] values = {month.income, month.spending};
                for (int j = 0; j < 2; j++) {
                    float left = (i + .12f + j * .4f) * slot;
                    float value = zero - values[j] * scale;
                    paint.setColor(j == 0 ? INCOME : SPEND);
                    c.drawRect(left, Math.min(zero, value), left + slot * .34f, Math.max(zero, value), paint);
                }
            }
            ReportData.Month month = months.get(selected);
            caption(c, label(month.month) + ": " + cash(month.income) + " / " + cash(month.spending));
        }
    }

    /** Spending trends: monthly columns stacked by category group (top groups + other). */
    /** Colours of the shown trend groups: entity colour, but never the same hue twice on one chart. */
    Map<String, Integer> trendColors(List<String> top, ReportData.Filter filter) {
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (String name : top) out.put(name, ReportColors.unused(this, trendColor(name, filter), out.values()));
        return out;
    }

    int trendColor(String name, ReportData.Filter filter) {
        for (ReportData.Group group : data.byCategory(filter)) {
            if (group.name.equals(name)) return ReportColors.color(this, group);
        }
        return ReportColors.other(this);
    }

    /** Categories of one group in the trends chart: own palette hues, never repeated on the chart. */
    Map<String, Integer> categoryColors(List<String> top, ReportTable.Row group) {
        Map<Long, String> guids = new HashMap<Long, String>();
        try (android.database.Cursor c = data.db.rawQuery("SELECT _id, guid FROM categoryTable", null)) {
            while (c.moveToNext()) guids.put(c.getLong(0), c.getString(1));
        }
        Map<String, Integer> out = new HashMap<String, Integer>();
        for (String name : top) {
            int preferred = ReportColors.other(this);
            for (ReportTable.Row child : group.children)
                if (child.name.equals(name) && guids.containsKey(child.category)) preferred = ReportColors.color(this, "category:" + guids.get(child.category));
            out.put(name, ReportColors.unused(this, preferred, out.values()));
        }
        return out;
    }

    final class Stacked extends MonthChart {
        final List<String> top;
        final Map<String, Integer> colors = new HashMap<String, Integer>();
        final List<android.util.Pair<RectF, String>> segments = new ArrayList<android.util.Pair<RectF, String>>();
        Runnable onPick, onMonth;
        Stacked(Context context, List<ReportData.Month> months, List<String> top, Map<String, Integer> colours) {
            super(context, months, "chart-trends");
            this.top = top;
            colors.putAll(colours);
            ReportColors.ownRendering(this);
        }
        @Override void select(float x) {
            int previous = selected;
            super.select(x);
            if (selected != previous && onMonth != null) onMonth.run();
        }
        /** A tap picks the segment under the finger (named on top, others dimmed); a tap on empty space clears it. */
        public boolean performClick() {
            super.performClick();
            String name = null;
            for (android.util.Pair<RectF, String> segment : segments) {
                if (segment.first.contains(downX, downY)) { name = segment.second; break; }
            }
            trendPick = name;
            describe();
            invalidate();
            if (onPick != null) onPick.run();
            return true;
        }
        void describe() {
            super.describe();
            if (top != null && trendPick != null) setContentDescription(getContentDescription() + " pick:" + trendPick);
        }
        protected void onDraw(Canvas c) {
            segments.clear();
            float w=getWidth(),topY=px(24),bottom=px(190),slot=w/months.size(),gap=px(1);
            long hi=1,lo=0;
            for(ReportData.Month m:months){long pos=0,neg=0;for(long value:m.groups.values()){pos+=Math.max(0,value);neg+=Math.min(0,value);}hi=Math.max(hi,pos);lo=Math.min(lo,neg);}
            float height=bottom-topY,zero=topY+height*hi/(float)(hi-lo);axis(c,bottom);
            paint.setColor(Color.GRAY);c.drawLine(0,zero,w,zero,paint);
            for(int i=0;i<months.size();i++){
                ReportData.Month m=months.get(i);float up=zero,down=zero,x=i*slot+slot*.18f,bw=slot*.64f;long rest=0;
                for(Map.Entry<String,Long> e:m.groups.entrySet())if(!top.contains(e.getKey()))rest+=e.getValue();
                for(int k=0;k<=top.size();k++){
                    String name = k < top.size() ? top.get(k) : null;
                    long value=name!=null?(m.groups.containsKey(name)?m.groups.get(name):0):rest;
                    float bar=height*Math.abs(value)/(float)(hi-lo);
                    paint.setColor(name != null ? colors.get(name) : ReportColors.other(ReportsActivity.this));
                    // The picked series stays strong; the rest steps back.
                    if (trendPick != null && !trendPick.equals(name)) paint.setAlpha(70);
                    RectF area = value >= 0 ? new RectF(x, up - bar, x + bw, up) : new RectF(x, down, x + bw, down + bar);
                    // 2px surface gap between stacked segments (thin ones stay whole).
                    RectF drawn = new RectF(area);
                    if (bar > 3 * gap) { if (value >= 0) drawn.top += gap; else drawn.bottom -= gap; }
                    c.drawRect(drawn, paint);
                    paint.setAlpha(255);
                    // Thin segments get a taller hit area so a finger can still pick them.
                    RectF hit = new RectF(area);
                    if (hit.height() < px(12)) { float mid = hit.centerY(); hit.top = mid - px(6); hit.bottom = mid + px(6); }
                    if (bar > 0) segments.add(new android.util.Pair<RectF, String>(hit, name));
                    if (value >= 0) up -= bar;
                    else down += bar;
                }
            }
            ReportData.Month m = months.get(selected);
            String caption = label(m.month) + ": ";
            if (trendPick != null) {
                Long value = m.groups.get(trendPick);
                caption += ReportTable.localized(trendPick) + " " + (hide ? "" : money.format((value == null ? 0 : value) / 100.0));
            } else caption += hide ? "" : money.format(m.spending / 100.0);
            caption(c, caption);
        }
    }

    /** Net worth: assets up (green), debts down (red), net worth line. */
    final class NetWorthChart extends MonthChart {
        NetWorthChart(Context c, List<ReportData.Month> months) { super(c, months, "chart-networth"); }
        protected void onDraw(Canvas c) {
            float w = getWidth(), top = px(24), bottom = px(190), slot = w / months.size();
            long hi = 1, lo = 0; for (ReportData.Month m : months) { hi = Math.max(hi, Math.max(m.assets, m.net())); lo = Math.min(lo, Math.min(m.debts, m.net())); }
            float zero = top + (bottom - top) * hi / (float) (hi - lo);
            axis(c, bottom);
            paint.setColor(Color.rgb(200, 200, 200)); c.drawLine(0, zero, w, zero, paint);
            Path path = new Path();
            for (int i = 0; i < months.size(); i++) {
                ReportData.Month m = months.get(i); float x = i * slot + slot * 0.2f, bw = slot * 0.6f;
                paint.setColor(INCOME); c.drawRect(x, zero - (zero - top) * m.assets / (float) hi, x + bw, zero, paint);
                if (lo < 0) { paint.setColor(SPEND); c.drawRect(x, zero, x + bw, zero + (bottom - zero) * m.debts / (float) lo, paint); }
                float cx = i * slot + slot / 2, cy = m.net() >= 0 ? zero - (zero - top) * m.net() / (float) hi : zero + (bottom - zero) * m.net() / (float) lo;
                if (i == 0) path.moveTo(cx, cy); else path.lineTo(cx, cy);
            }
            paint.setColor(LINE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(px(2.5f)); c.drawPath(path, paint); paint.setStyle(Paint.Style.FILL);
            ReportData.Month m = months.get(selected);
            caption(c, label(m.month) + ": " + cash(m.net()));
        }
    }
}

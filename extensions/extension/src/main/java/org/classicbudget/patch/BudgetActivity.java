package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** Experimental: set budgeted amounts of a month from the phone (TEST/COPY budgets only). */
public final class BudgetActivity extends Activity {
    BudgetStore store; String month; LinearLayout root; List<BudgetStore.Row> rows = new ArrayList<BudgetStore.Row>();
    private BudgetGoals goals;
    private BudgetCellActions cellActions;
    static final int TEXT = Color.rgb(33, 33, 33), MUTED = Color.rgb(110, 110, 110), SPEND = Color.rgb(196, 64, 48);

    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    int px(int dp) { return Math.round(dp * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        PlusEdition.mark(this);
        setTitle(tr("Budget (Plus)", "Budżet (Plus)"));
        Calendar now = Calendar.getInstance();
        month = state != null && state.getString("month") != null ? state.getString("month") : BudgetStore.monthKey(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(16), px(8), px(16), px(24));
        scroll.addView(root);
        scroll.setBackgroundColor(Color.WHITE);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        if (SyncStatusActivity.available(this)) page.addView(new SyncNoticeView(this));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        try { store = BudgetStore.open(this); } catch (Exception e) { message(e.getMessage()); return; }
        goals = new BudgetGoals(this);
        cellActions = new BudgetCellActions(this, goals);
        render();
    }

    /** Index reading runs in BudgetIndexService; this screen only shows its progress. */
    final android.os.Handler poll = new android.os.Handler();
    ProgressDialog indexDialog;
    TextView indexStatus;
    boolean waitingForManualResult;

    final Runnable watch = new Runnable() {
        public void run() {
            showIndexState();
            showSyncState();
            // Not more often: constant redraws also keep accessibility tools from ever seeing an idle screen.
            poll.postDelayed(this, 1500);
        }
    };

    @Override protected void onResume() {
        super.onResume();
        if (store == null) return;
        // First visit of a budget whose index was never completed: read it in the background.
        if (!BudgetIndexService.running && !BudgetIndexService.complete(this, store.budgetFolder, store.dataFolder))
            BudgetIndexService.start(this, store.budgetFolder, store.dataFolder, true);
        poll.post(watch);
    }

    @Override protected void onPause() {
        super.onPause();
        poll.removeCallbacks(watch);
        if (indexDialog != null) {
            indexDialog.dismiss();
            indexDialog = null;
        }
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void showIndexState() {
        boolean running = BudgetIndexService.running;
        String progress = BudgetIndexService.total == 0 ? "" : " " + BudgetIndexService.done + "/" + BudgetIndexService.total;
        if (indexStatus != null) {
            String text;
            if (running) text = tr("Budget notes: reading in the background", "Notatki budżetu: czytam w tle") + progress;
            else if (store != null && !BudgetIndexService.complete(this, store.budgetFolder, store.dataFolder))
                text = tr("Budget notes: not finished", "Notatki budżetu: niedokończone");
            else text = tr("Budget notes: ready", "Notatki budżetu: gotowe");
            if (!text.contentEquals(indexStatus.getText())) indexStatus.setText(text);
        }
        if (running && waitingForManualResult) {
            if (indexDialog == null) {
                indexDialog = new ProgressDialog(this);
                indexDialog.setCancelable(false);
                indexDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
                indexDialog.setButton(ProgressDialog.BUTTON_NEGATIVE, tr("Stop", "Przerwij"), (d, w) -> BudgetIndexService.stop(this));
                // Hide: reading goes on in the background (notification and the status line show it).
                indexDialog.setButton(ProgressDialog.BUTTON_POSITIVE, tr("Hide", "Ukryj"), (d, w) -> {
                    waitingForManualResult = false;
                    indexDialog = null;
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                });
                indexDialog.show();
                indexDialog.getButton(ProgressDialog.BUTTON_POSITIVE).setContentDescription("budget-index-hide");
                indexDialog.getButton(ProgressDialog.BUTTON_NEGATIVE).setContentDescription("budget-index-stop");
                // A long first read: keep the screen on while this dialog is visible.
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            indexDialog.setMessage(BudgetIndexService.stage == 1
                    ? tr("Retrying files that did not load…", "Ponawiam pliki, których nie udało się odczytać…")
                    : tr("Reading budget files… (continues if you leave this screen)", "Czytam pliki budżetu… (działa dalej, gdy wyjdziesz z ekranu)"));
            indexDialog.setMax(Math.max(1, BudgetIndexService.total));
            indexDialog.setProgress(BudgetIndexService.done);
        }
        if (!running && waitingForManualResult && BudgetIndexService.result != null) {
            waitingForManualResult = false;
            if (indexDialog != null) {
                indexDialog.dismiss();
                indexDialog = null;
            }
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            render();
            new AlertDialog.Builder(this).setMessage(BudgetIndexService.result).setPositiveButton("OK", null).show();
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("month", month); }

    void message(String text) {
        root.removeAllViews();
        root.addView(label(text, 15, TEXT, false));
    }

    TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, px(4), 0, px(4));
        return t;
    }

    Button button(String text, String desc, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text); b.setContentDescription(desc); b.setAllCaps(false); b.setOnClickListener(click);
        return b;
    }

    TextView syncStatus;

    void showSyncState() {
        if (store == null || syncStatus == null) return;
        if (SyncStatusActivity.available(this)) {
            syncStatus.setVisibility(View.GONE);
            return;
        }
        int unsent;
        try {
            unsent = store.unsent();
        } catch (IllegalStateException closed) {
            // The original adapter may close while the user switches or resets the budget.
            return;
        }
        String text = unsent == 0 ? tr("✓ All changes sent", "✓ Wszystkie zmiany wysłane")
            : tr("↑ Waiting to be sent: ", "↑ Czeka na wysłanie: ") + unsent;
        // Update only this label when it changes; do not redraw the table or interrupt an open dialog.
        if (!text.contentEquals(syncStatus.getText())) {
            syncStatus.setText(text);
            syncStatus.setTextColor(unsent == 0 ? MUTED : PENDING);
            syncStatus.setBackground(new android.graphics.drawable.InsetDrawable(
                shape(unsent == 0 ? Color.TRANSPARENT : PENDING_BG, 0, 12), 0, px(4), 0, px(4)));
            // After setBackground: an InsetDrawable replaces the view padding with its insets.
            syncStatus.setPadding(px(12), px(8), px(12), px(8));
            syncStatus.setContentDescription("budget-unsent:" + unsent);
        }
    }

    /**
     * 092 "Hide amounts": the same local switch as in Reports (cb-plus-reports / hide_amounts), so one
     * tap hides money on both Plus screens. Only the screen changes; the edit dialog of a category still
     * shows real amounts, because there the user types a number on purpose.
     */
    static final String HIDE_PREFS = "cb-plus-reports", HIDE_KEY = "hide_amounts";
    boolean hideAmounts;

    boolean amountsHidden() { return getSharedPreferences(HIDE_PREFS, 0).getBoolean(HIDE_KEY, false); }

    /** Amount as shown on the budget screen: "•••" while hidden. */
    String shown(long cents) { return hideAmounts ? "•••" : cash(cents); }

    static String cash(long cents) {
        NumberFormat f = NumberFormat.getNumberInstance(Locale.getDefault());
        f.setMinimumFractionDigits(2); f.setMaximumFractionDigits(2);
        return f.format(BigDecimal.valueOf(cents, 2));
    }

    String monthLabel(String key) {
        Calendar c = Calendar.getInstance();
        c.set(Integer.parseInt(key.substring(0, 4)), Integer.parseInt(key.substring(5, 7)) - 1, 1);
        return new java.text.SimpleDateFormat("LLLL yyyy", Locale.getDefault()).format(c.getTime());
    }

    static final int LINE = Color.rgb(224, 228, 231), GROUP_BG = Color.rgb(242, 245, 247), CELL_BG = Color.rgb(238, 243, 246);
    static final int POSITIVE = Color.rgb(46, 125, 50), DANGER_BG = Color.rgb(252, 232, 230);
    /** Waiting to be sent: amber, not red (nothing is wrong yet). */
    static final int PENDING = Color.rgb(150, 82, 0), PENDING_BG = Color.rgb(255, 238, 209);

    /** Rounded outline or fill used by the compact controls and the negative-balance pill. */
    android.graphics.drawable.GradientDrawable shape(int fill, int stroke, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(px(radiusDp));
        if (stroke != 0) d.setStroke(px(1), stroke);
        return d;
    }

    /** Small outlined action; keeps a 48dp touch height while drawing a smaller pill. */
    TextView chip(String text, String desc, View.OnClickListener click) {
        TextView c = label(text, 13, Color.rgb(20, 66, 79), false);
        c.setContentDescription(desc);
        c.setOnClickListener(click);
        c.setSingleLine(true);
        c.setGravity(Gravity.CENTER);
        c.setBackground(new android.graphics.drawable.InsetDrawable(shape(Color.WHITE, Color.rgb(160, 176, 184), 16), 0, px(6), 0, px(6)));
        // After setBackground: an InsetDrawable replaces the view padding with its insets.
        c.setPadding(px(14), px(6), px(14), px(6));
        c.setMinHeight(px(48));
        return c;
    }

    /** Borderless arrow for month navigation (no grey legacy button). */
    TextView arrow(String text, String desc, View.OnClickListener click) {
        TextView a = label(text, 22, Color.rgb(20, 66, 79), false);
        a.setContentDescription(desc);
        a.setOnClickListener(click);
        a.setGravity(Gravity.CENTER);
        a.setMinWidth(px(48));
        a.setMinHeight(px(48));
        return a;
    }

    View divider() {
        View d = new View(this);
        d.setBackgroundColor(LINE);
        d.setLayoutParams(new LinearLayout.LayoutParams(-1, Math.max(1, px(1) / 2)));
        return d;
    }

    /** One line of the desktop-style month header: label left, amount right. */
    LinearLayout headerLine(String name, long shown, boolean plus, String desc, long raw, boolean total) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        int size = total ? 16 : 13;
        TextView n = label(name, size, total ? TEXT : MUTED, total);
        if (!total) n.setPadding(0, px(1), 0, px(1));
        String value = hideAmounts ? "•••" : (plus && shown > 0 ? "+" : "") + cash(shown);
        int color = total ? (shown < 0 ? SPEND : POSITIVE) : MUTED;
        TextView v = label(value, size, color, total);
        v.setGravity(Gravity.END);
        v.setSingleLine(true);
        if (!total) v.setPadding(0, px(1), 0, px(1));
        // Raw value (as computed, sign included) for tests; the screen shows desktop signs.
        v.setContentDescription(desc + ":" + raw);
        l.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(v, new LinearLayout.LayoutParams(-2, -2));
        return l;
    }

    String monthShort(String key) {
        Calendar c = Calendar.getInstance();
        c.set(Integer.parseInt(key.substring(0, 4)), Integer.parseInt(key.substring(5, 7)) - 1, 1);
        return new java.text.SimpleDateFormat("LLL", Locale.getDefault()).format(c.getTime());
    }

    void render() {
        hideAmounts = amountsHidden();
        root.removeAllViews();
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.addView(arrow("‹", "budget-prev", v -> { month = BudgetStore.shift(month, -1); render(); }));
        TextView title = label(monthLabel(month), 18, TEXT, true);
        title.setGravity(Gravity.CENTER); title.setContentDescription("budget-month:" + month);
        nav.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(arrow("›", "budget-next", v -> { month = BudgetStore.shift(month, 1); render(); }));
        root.addView(nav);
        // Sync state right under the month (076): changes leave only with a sync, so it must be visible.
        syncStatus = label("", 12, MUTED, false);
        syncStatus.setGravity(Gravity.CENTER);
        syncStatus.setMinHeight(px(40));
        syncStatus.setOnClickListener(v -> syncInfo());
        LinearLayout.LayoutParams centred = new LinearLayout.LayoutParams(-2, -2);
        centred.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(syncStatus, centred);
        showSyncState();

        rows = store.rows(month);
        BudgetMath.Result math = store.lastMath;
        String previous = monthShort(BudgetStore.shift(month, -1)), current = monthShort(month);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(12), px(6), px(12), px(8));
        card.setBackground(shape(GROUP_BG, 0, 10));
        card.addView(headerLine((math.previousAvailable < 0 ? tr("Overbudgeted in ", "Przebudżetowano w ") : tr("Not budgeted in ", "Nie zabudżetowano w ")) + previous,
            math.previousAvailable, false, "budget-header-previous", math.previousAvailable, false));
        card.addView(headerLine(tr("Overspent in ", "Przekroczenia w ") + previous, math.carriedFromPrevious, false, "budget-header-overspent", math.carriedFromPrevious, false));
        card.addView(headerLine(tr("Income for ", "Przychody w ") + current, math.incomeForMonth(), true, "budget-header-income", math.incomeForMonth(), false));
        card.addView(headerLine(tr("Budgeted in ", "Zabudżetowano w ") + current, -math.budgeted, false, "budget-header-budgeted", math.budgeted, false));
        card.addView(divider());
        LinearLayout total = headerLine(math.available < 0 ? tr("Overbudgeted", "Przebudżetowano") : tr("To be budgeted", "Do zabudżetowania"),
            math.available, false, "budget-available", math.available, true);
        total.setPadding(0, px(4), 0, 0);
        card.addView(total);
        root.addView(card);

        LinearLayout actions = new LinearLayout(this);
        actions.setPadding(0, px(4), 0, 0);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.setMarginEnd(px(8));
        actions.addView(chip(tr("Quick budget", "Szybki budżet"), "budget-quick", v -> quick()), gap);
        actions.addView(chip(tr("Zero month", "Wyzeruj"), "budget-zero", v -> confirm(BudgetStore.ZERO)), gap);
        actions.addView(chip(tr("Available → 0", "Dostępne → 0"), "budget-balance-zero", v -> confirm(BudgetStore.BALANCE_ZERO)), gap);
        actions.addView(chip(tr("Goals", "Cele"), "budget-goals", v -> goals.show()), gap);
        actions.addView(chip(hideAmounts ? tr("Show amounts", "Pokaż kwoty") : tr("Hide amounts", "Ukryj kwoty"), "budget-hide-amounts", v -> {
            getSharedPreferences(HIDE_PREFS, 0).edit().putBoolean(HIDE_KEY, !hideAmounts).apply();
            render();
        }), gap);
        android.widget.HorizontalScrollView actionScroll = new android.widget.HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.addView(actions);
        root.addView(actionScroll);

        String warning = rows.isEmpty() ? null : store.blocked(month, rows.get(0));
        if (warning != null && !BudgetNotes.monthKnown(this, store.dataFolder, month)) root.addView(label(warning, 13, SPEND, false));

        LinearLayout header = line(tr("Category", "Kategoria"), tr("Budgeted", "Budżet"), tr("Activity", "Wydatki"), tr("Available", "Dostępne"), true);
        for (int i = 0; i < header.getChildCount(); i++) ((TextView) header.getChildAt(i)).setTextColor(MUTED);
        header.setMinimumHeight(px(32));
        root.addView(header);
        java.util.Map<String, long[]> groups = new java.util.LinkedHashMap<String, long[]>();
        for (BudgetStore.Row r : rows) {
            long[] sum = groups.get(r.group);
            if (sum == null) groups.put(r.group, sum = new long[3]);
            sum[0] += r.budgeted; sum[1] += r.activity; sum[2] += r.balance;
        }
        long budgeted = 0, activity = 0, available = 0; String group = null;
        for (final BudgetStore.Row r : rows) {
            if (!r.group.equals(group)) {
                group = r.group;
                long[] sum = groups.get(group);
                LinearLayout g = line(group, shown(sum[0]), shown(sum[1]), shown(sum[2]), true);
                g.setBackgroundColor(GROUP_BG);
                g.setMinimumHeight(px(36));
                g.setContentDescription("budget-group:" + group);
                ((TextView) g.getChildAt(3)).setTextColor(sum[2] < 0 ? SPEND : TEXT);
                LinearLayout.LayoutParams top = new LinearLayout.LayoutParams(-1, -2);
                top.topMargin = px(8);
                root.addView(g, top);
            }
            LinearLayout line = line(r.name, shown(r.budgeted), shown(r.activity), shown(r.balance), false);
            TextView balance = (TextView) line.getChildAt(3);
            if (r.balance < 0) {
                pill(balance);
                if (r.confined) balance.setText(shown(r.balance) + " →");
                balance.setOnClickListener(v -> cellActions.overspending(r));
            }
            balance.setContentDescription("budget-balance:" + r.categoryGuid + ":" + r.balance);
            line.setContentDescription("budget-row:" + r.categoryGuid);
            line.getChildAt(1).setContentDescription("budget-cell:" + r.categoryGuid);
            line.getChildAt(1).setOnClickListener(v -> edit(r));
            line.getChildAt(0).setContentDescription("budget-category-options:" + r.categoryGuid);
            line.getChildAt(0).setOnClickListener(v -> cellActions.show(r));
            line.getChildAt(2).setContentDescription("budget-activity:" + r.categoryGuid);
            line.getChildAt(2).setOnClickListener(v -> cellActions.activity(r));
            root.addView(line);
            root.addView(divider());
            budgeted += r.budgeted; activity += r.activity; available += r.balance;
        }
        long uncategorized = BudgetMath.get(math.balance, BudgetMath.UNCATEGORIZED);
        if (uncategorized != 0) root.addView(line(tr("Uncategorized transactions", "Transakcje bez kategorii"), "", "", shown(uncategorized), false));
        root.addView(line(tr("Total", "Razem"), shown(budgeted), shown(activity), shown(available), true));

        // The note index is a rare, technical matter: one small line with an explanation, at the bottom.
        LinearLayout index = new LinearLayout(this);
        index.setGravity(Gravity.CENTER_VERTICAL);
        index.setMinimumHeight(px(48));
        index.setContentDescription("budget-index-info");
        index.setOnClickListener(v -> indexInfo());
        indexStatus = label("", 12, MUTED, false);
        indexStatus.setContentDescription("budget-index-status");
        index.addView(indexStatus, new LinearLayout.LayoutParams(0, -2, 1));
        index.addView(label("ⓘ", 16, MUTED, false));
        root.addView(index);
        showIndexState();
        if (PlusEdition.TEST_ONLY) root.addView(label(tr("Experimental, TEST and COPY budgets only. Changes go to the desktop like its own budget edits; amounts follow YNAB 4 desktop rules.",
            "Eksperymentalne, tylko budżety TEST i COPY. Zmiany trafiają do komputera jak jego własne; kwoty liczone regułami YNAB 4."), 11, MUTED, false));
    }

    /** Negative Available as a red pill, like the original budget screen. */
    void pill(TextView t) {
        t.setTextColor(SPEND);
        t.setBackground(new android.graphics.drawable.InsetDrawable(shape(DANGER_BG, 0, 12), px(4), px(8), 0, px(8)));
        t.setPadding(px(8), px(10), px(6), px(10));
    }

    LinearLayout line(String name, String budgeted, String activity, String balance, boolean bold) {
        LinearLayout l = new LinearLayout(this);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setMinimumHeight(px(44));
        TextView n = label(name, 14, TEXT, bold);
        n.setSingleLine(true);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        n.setPadding(px(bold ? 4 : 8), px(4), px(4), px(4));
        TextView b = label(budgeted, 14, TEXT, bold);
        b.setGravity(Gravity.END);
        if (!bold) b.setBackground(new android.graphics.drawable.InsetDrawable(shape(CELL_BG, 0, 6), 0, px(4), 0, px(4)));
        // After setBackground: an InsetDrawable replaces the view padding with its insets.
        b.setPadding(px(6), px(10), px(8), px(10));
        TextView a = label(activity, 14, MUTED, bold);
        a.setGravity(Gravity.END);
        TextView v = label(balance, 14, TEXT, bold);
        v.setGravity(Gravity.END);
        for (TextView t : new TextView[]{b, a, v}) { t.setTextSize(13); t.setSingleLine(true); }
        a.setPadding(px(4), px(4), 0, px(4));
        v.setPadding(px(4), px(4), px(4), px(4));
        l.addView(n, new LinearLayout.LayoutParams(0, -2, 4));
        l.addView(b, new LinearLayout.LayoutParams(0, -2, 3));
        l.addView(a, new LinearLayout.LayoutParams(0, -2, 3));
        l.addView(v, new LinearLayout.LayoutParams(0, -2, 3));
        return l;
    }

    static long parse(String text) throws NumberFormatException {
        String clean = text.trim().replace(" ", "").replace(" ", "").replace(",", ".");
        if (clean.isEmpty()) return 0;
        return new BigDecimal(clean).setScale(2, java.math.RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    void edit(final BudgetStore.Row r) {
        edit(r, r.budgeted);
    }

    void edit(final BudgetStore.Row r, long amount) {
        String why = store.blocked(month, r);
        if (why != null) { new AlertDialog.Builder(this).setTitle(r.name).setMessage(why).setPositiveButton("OK", null).show(); return; }
        EditText input = BudgetCellActions.amount(this, "budget-input", amount);
        new AlertDialog.Builder(this).setTitle(r.name + " · " + monthLabel(month))
            .setMessage(tr("Activity this month: ", "Wydatki w tym miesiącu: ") + cash(r.activity))
            .setView(input)
            .setPositiveButton(tr("Save", "Zapisz"), (d, w) -> {
                try {
                    List<BudgetStore.Change> one = new ArrayList<BudgetStore.Change>();
                    one.add(new BudgetStore.Change(r, parse(input.getText().toString())));
                    save(one);
                } catch (NumberFormatException | ArithmeticException bad) {
                    Toast.makeText(this, tr("Not an amount", "To nie jest kwota"), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    void quick() {
        String[] names = {tr("Budgeted last month", "Budżet z poprzedniego miesiąca"), tr("Spent last month", "Wydatki z poprzedniego miesiąca"),
            tr("Average budgeted (3 months)", "Średni budżet (3 miesiące)"), tr("Average spent (3 months)", "Średnie wydatki (3 miesiące)")};
        new AlertDialog.Builder(this).setTitle(tr("Fill empty budget cells", "Uzupełnij puste kwoty budżetu"))
            .setItems(names, (d, which) -> confirm(which)).show();
    }

    String exclusionKey(int mode) {
        String prefix = mode == BudgetStore.BALANCE_ZERO ? "balance-excluded:"
            : mode == BudgetStore.ZERO ? "zero-excluded:" : "quick-excluded:";
        return prefix + store.budgetFolder + "/" + store.dataFolder;
    }

    Set<String> excluded(int mode) {
        return new HashSet<String>(getSharedPreferences("cb-budget-choices", MODE_PRIVATE)
            .getStringSet(exclusionKey(mode), java.util.Collections.<String>emptySet()));
    }

    void chooseExceptions(final int mode) {
        final Set<String> excluded = excluded(mode);
        String[] labels = new String[rows.size()];
        boolean[] checked = new boolean[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            BudgetStore.Row row = rows.get(i);
            labels[i] = row.group + " · " + row.name;
            checked[i] = excluded.contains(row.categoryGuid);
        }
        String title = mode == BudgetStore.BALANCE_ZERO
            ? tr("Keep these category balances", "Pozostaw salda tych kategorii")
            : mode == BudgetStore.ZERO ? tr("Keep these categories when zeroing", "Pomijaj te kategorie przy zerowaniu")
            : tr("Skip these categories in Quick budget", "Pomijaj te kategorie w Szybkim budżecie");
        new AlertDialog.Builder(this).setTitle(title)
            .setMultiChoiceItems(labels, checked, (dialog, which, selected) -> {
                String guid = rows.get(which).categoryGuid;
                if (selected) excluded.add(guid);
                else excluded.remove(guid);
            })
            .setPositiveButton(tr("Remember", "Zapamiętaj"), (dialog, which) -> {
                // Local preference only: do not add phone-specific choices to the synchronized budget.
                getSharedPreferences("cb-budget-choices", MODE_PRIVATE).edit().putStringSet(exclusionKey(mode), excluded).apply();
                confirm(mode);
            })
            .setNegativeButton(tr("Cancel", "Anuluj"), (dialog, which) -> confirm(mode)).show();
    }

    void confirm(int mode) {
        Set<String> excluded = excluded(mode);
        final List<BudgetStore.Change> changes = store.quick(month, rows, mode, excluded);
        int count = 0;
        int kept = 0;
        // Include hidden categories in the total, although bulk edits affect visible categories only.
        long total = BudgetMath.compute(store.db, month).budgeted;
        for (BudgetStore.Row row : rows) {
            if (excluded.contains(row.categoryGuid)) kept++;
        }
        for (BudgetStore.Change change : changes) {
            if (change.amount != change.row.budgeted) count++;
            total += change.amount - change.row.budgeted;
        }
        String text = monthLabel(month) + "\n" + tr("Change ", "Zmienić ") + count
            + tr(" categories. New budget total: ", " kategorii. Nowa suma budżetu: ") + cash(total);
        if (mode == BudgetStore.BALANCE_ZERO) {
            text += "\n" + tr("Categories kept: ", "Pomijane kategorie: ") + kept
                + "\n" + tr("Adjusts Budgeted up or down so Available becomes zero, including carried balances and net refunds. Negative budgeted amounts are possible.",
                    "Podnosi lub obniża Budżet, aby Dostępne wyniosło zero, uwzględniając przeniesione salda i zwroty netto. Może ustawić ujemne kwoty Budżet.");
        } else if (mode == BudgetStore.ZERO) {
            text += "\n" + tr("Categories kept: ", "Pomijane kategorie: ") + kept
                + "\n" + tr("Sets Budgeted to zero; carried balances in Available remain.",
                    "Zeruje kwoty Budżet; przeniesione salda Dostępne pozostają.");
        } else {
            text += "\n" + tr("Categories kept: ", "Pomijane kategorie: ") + kept
                + "\n" + tr("Only zero-valued budget cells will be filled. Existing positive and negative amounts stay.",
                "Uzupełni tylko zerowe kwoty Budżet. Wpisane kwoty dodatnie i ujemne pozostaną.");
        }
        text += "\n\n" + tr("Changes are written in one step and sent to the desktop.", "Zmiany zostaną zapisane naraz i wysłane do komputera.");
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setMessage(text)
            .setPositiveButton(tr("Apply", "Zastosuj"), (d, w) -> save(changes))
            .setNegativeButton(tr("Cancel", "Anuluj"), null);
        builder.setNeutralButton(tr("Keep categories…", "Pomijaj kategorie…"), (d, w) -> chooseExceptions(mode));
        AlertDialog dialog = builder.show();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(count != 0);
    }

    void save(List<BudgetStore.Change> changes) {
        try {
            int n = store.apply(month, changes);
            // Send right away, as the original does after a pull-down on the accounts screen.
            boolean syncing = n > 0 && store.syncNow();
            Toast.makeText(this, tr("Saved: ", "Zapisano: ") + n + (n == 0 ? "" : syncing
                ? tr(". Sending now.", ". Wysyłam.") : tr(". Sent with the next sync.", ". Wyślę przy synchronizacji.")), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            new AlertDialog.Builder(this).setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
        render();
    }

    /** Tap on the sync line: explain, and offer to sync now while something waits. */
    void syncInfo() {
        int unsent;
        try { unsent = store.unsent(); } catch (IllegalStateException closed) { return; }
        AlertDialog.Builder b = new AlertDialog.Builder(this).setMessage(unsent == 0
            ? tr("All budget changes made on this phone have been sent. The desktop applies them when it syncs.",
                "Wszystkie zmiany budżetu z tego telefonu są wysłane. Komputer przyjmie je przy swojej synchronizacji.")
            : tr("Budget changes leave the phone with a sync, like transactions. Sending starts by itself after saving; without internet they wait and go with the next sync.",
                "Zmiany budżetu wychodzą z telefonu przy synchronizacji, tak jak transakcje. Wysyłanie zaczyna się samo po zapisie; bez internetu czekają i pójdą przy następnej synchronizacji."))
            .setPositiveButton("OK", null);
        if (unsent > 0) b.setNeutralButton(tr("Sync now", "Synchronizuj teraz"), (d, w) ->
            Toast.makeText(this, store.syncNow() ? tr("Syncing…", "Synchronizuję…")
                : tr("Sync could not start; pull down on the accounts screen.", "Nie udało się uruchomić; pociągnij w dół na ekranie kont."), Toast.LENGTH_SHORT).show());
        AlertDialog d = b.show();
        Button now = d.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (now != null) now.setContentDescription("budget-sync-now");
    }

    /** What the note index is for, and the rarely needed manual refresh. */
    void indexInfo() {
        AlertDialog d = new AlertDialog.Builder(this).setTitle(tr("Budget notes", "Notatki budżetu"))
            .setMessage(tr("A budget cell can carry a note. When the phone sends a new amount, the desktop replaces the whole cell, so the phone must know the note or the desktop would erase it. The original app does not keep notes, so Plus reads them from the budget files: everything once, afterwards only new files, by itself with each sync.\n\nRefresh by hand only when the app says a note is unknown. Files already read are skipped.",
                "Komórka budżetu może mieć notatkę. Gdy telefon wysyła nową kwotę, komputer zastępuje całą komórkę, więc telefon musi znać notatkę, inaczej komputer by ją skasował. Oryginalna aplikacja notatek nie przechowuje, dlatego Plus czyta je z plików budżetu: raz wszystko, potem tylko nowe pliki, samoczynnie przy każdej synchronizacji.\n\nOdświeżaj ręcznie tylko wtedy, gdy aplikacja powie, że nie zna notatki. Pliki już przeczytane są pomijane."))
            .setPositiveButton("OK", null)
            .setNeutralButton(tr("Refresh now", "Odśwież teraz"), (x, w) -> refresh()).show();
        d.getButton(DialogInterface.BUTTON_NEUTRAL).setContentDescription("budget-refresh");
    }

    void refresh() {
        waitingForManualResult = true;
        BudgetIndexService.start(this, store.budgetFolder, store.dataFolder, false);
        poll.removeCallbacks(watch);
        poll.postDelayed(watch, 300);
    }
}

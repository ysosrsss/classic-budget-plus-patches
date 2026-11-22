package org.classicbudget.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Plus destinations. 077 (user's choice after drafts on the real-budget copy): a dark "Plus" button in
 * thumb reach at the bottom, left of the amount column, hidden while the list scrolls, opening a sheet
 * with Budgeting, Reports, Scheduled transactions and Payee rules. It can be switched off in Plus
 * settings; the four entries then sit in the overflow menu. The 076 drawer row is kept but disabled.
 */
public final class PlusMenu {
    private static final String[] CLASSES = {"BudgetActivity", "ReportsActivity", "ScheduledActivity"};

    /** 077: the Plus button (or, when switched off, the overflow menu) is the entry; drawer tiles stay off. */
    static final boolean DRAWER_TILES = false;

    /** Local preference (Plus settings → Display): the Plus button on the budget screen, on or off. */
    public static final String BUTTON = "plus_button", ON = "on", OFF = "off";
    private static final int FIRST = 0x43425010;
    /** 090: Reports opened straight into the all-transactions search (no hooks in the original screens). */
    static final String SEARCH = "ReportsActivity#search";
    private static final String[] ITEMS = {"BudgetActivity", "ReportsActivity", "ScheduledActivity", "PayeeRulesActivity", SEARCH};

    static String[] labels() {
        return new String[]{PlusLang.tr("Budgeting", "Budżetowanie"), PlusLang.tr("Reports", "Raporty"),
            PlusLang.tr("Scheduled transactions", "Zlecenia stałe"), PlusLang.tr("Payee rules", "Reguły płatników"),
            PlusLang.tr("Search transactions", "Szukaj transakcji")};
    }

    static boolean buttonOn(Context context) {
        // On by default (079, user's choice); off puts the entries in the overflow menu.
        return !OFF.equals(context.getSharedPreferences(Display.PREFS, 0).getString(BUTTON, ON));
    }

    /**
     * Called from OverviewActivity.onCreateOptionsMenu (existing hook). With the Plus button on (default),
     * a dark floating "Plus" button is added once to the screen, bottom right but left of the amount
     * column, above the original Add Transaction bar; it only lies over the list. With the button off,
     * the four destinations are in the overflow menu instead, so there is always exactly one way in.
     */
    public static void menu(final Activity activity, Menu menu) {
        try {
            follow(activity);
            PlusEdition.mark(activity);
            PlusEdition.checkBudget(activity);
            if (!buttonOn(activity)) {
                android.view.ViewGroup content = activity.findViewById(android.R.id.content);
                View old = content == null ? null : content.findViewWithTag("cb-plus-fab");
                if (old != null) content.removeView(old);
                String[] labels = labels();
                for (int i = 0; i < ITEMS.length; i++) {
                    Intent intent = intent(activity, ITEMS[i]);
                    if (intent.resolveActivity(activity.getPackageManager()) == null || menu.findItem(FIRST + i) != null) continue;
                    menu.add(0, FIRST + i, 100 + i, labels[i]).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
                }
                return;
            }
            android.view.ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag("cb-plus-fab") != null) return;
            if (!(content instanceof android.widget.FrameLayout)) return;
            TextView fab = new TextView(activity);
            fab.setTag("cb-plus-fab");
            fab.setText("Plus");
            fab.setTextColor(Color.WHITE);
            fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            fab.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            fab.setGravity(Gravity.CENTER);
            fab.setContentDescription("plus-fab");
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, roundedSolid(activity, 0xff065f75, 16));
            states.addState(new int[]{}, roundedSolid(activity, 0xff0782a0, 16));
            fab.setBackground(states);
            fab.setElevation(dp(activity, 6));
            fab.setOnClickListener(v -> sheet(activity));
            int size = dp(activity, 56);
            android.widget.FrameLayout.LayoutParams lp = new android.widget.FrameLayout.LayoutParams(size, size, Gravity.BOTTOM | Gravity.END);
            // Bottom right, the usual place for such a button, above the original Add Transaction bar
            // (54dp high, 8dp margin). At rest it may cover one amount; it hides while the list scrolls.
            lp.setMargins(0, 0, dp(activity, 16), dp(activity, 76));
            content.addView(fab, lp);
            hideWhileScrolling(content, fab);
        } catch (RuntimeException unexpected) {
            // Never break the original screen.
        }
    }

    /** Kept strongly: SharedPreferences holds listeners weakly. */
    private static android.content.SharedPreferences.OnSharedPreferenceChangeListener listener;
    private static java.lang.ref.WeakReference<Activity> screen;

    /** A change in Plus settings applies when the budget screen is shown again, without a restart. */
    static void follow(Activity activity) {
        screen = new java.lang.ref.WeakReference<Activity>(activity);
        if (listener != null) return;
        listener = (prefs, key) -> {
            if (!BUTTON.equals(key)) return;
            Activity shown = screen == null ? null : screen.get();
            // Rebuilding the options menu calls menu() again: it adds or removes the button or the items.
            if (shown != null && !shown.isFinishing()) shown.runOnUiThread(shown::invalidateOptionsMenu);
        };
        activity.getApplicationContext().getSharedPreferences(Display.PREFS, 0).registerOnSharedPreferenceChangeListener(listener);
    }

    static Intent intent(Context context, String activity) {
        if (SEARCH.equals(activity)) return intent(context, "ReportsActivity").putExtra("search-all", true);
        return new Intent().setClassName(context.getPackageName(), "org.classicbudget.patch." + activity);
    }

    public static boolean selected(Activity activity, MenuItem item) {
        int index = item.getItemId() - FIRST;
        if (index < 0 || index >= ITEMS.length) return false;
        activity.startActivity(intent(activity, ITEMS[index]));
        return true;
    }

    /**
     * The thumb that scrolls the categories runs along the right edge, right over the button. So the
     * button fades in place and ignores taps while scrolling. It comes back after 700 ms of rest.
     */
    static void hideWhileScrolling(final android.view.ViewGroup content, final TextView fab) {
        final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
        final boolean animate = Display.animations(fab.getContext());
        final android.view.ViewTreeObserver observer = content.getViewTreeObserver();
        final Runnable show = () -> {
            fab.animate().cancel();
            fab.setVisibility(View.VISIBLE);
            fab.setEnabled(true);
            if (animate) fab.animate().alpha(1f).setDuration(140).start();
            else fab.setAlpha(1f);
        };
        final android.view.ViewTreeObserver.OnScrollChangedListener scroll = () -> {
            if (fab.isEnabled()) {
                fab.setEnabled(false);
                fab.animate().cancel();
                if (animate) {
                    fab.animate().alpha(0f).setDuration(110)
                        .withEndAction(() -> fab.setVisibility(View.INVISIBLE)).start();
                } else {
                    fab.setVisibility(View.INVISIBLE);
                }
            }
            handler.removeCallbacks(show);
            handler.postDelayed(show, 700);
        };
        observer.addOnScrollChangedListener(scroll);
        fab.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View v) {}
            public void onViewDetachedFromWindow(View v) {
                handler.removeCallbacks(show);
                fab.animate().cancel();
                if (observer.isAlive()) observer.removeOnScrollChangedListener(scroll);
            }
        });
    }

    /** Bottom sheet with the four Plus destinations as large tiles. */
    static void sheet(final Activity activity) {
        final android.app.Dialog dialog = new android.app.Dialog(activity);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 16), dp(activity, 20));
        GradientDrawable top = new GradientDrawable();
        top.setColor(0xffffffff);
        float r = dp(activity, 18);
        top.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(top);
        View grab = new View(activity);
        grab.setBackground(roundedSolid(activity, 0xffcccccc, 2));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(activity, 36), dp(activity, 4));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.setMargins(0, 0, 0, dp(activity, 12));
        box.addView(grab, gp);
        TextView title = new TextView(activity);
        title.setText("Plus");
        title.setTextColor(0xff6e6e6e);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setPadding(dp(activity, 2), 0, 0, dp(activity, 8));
        box.addView(title);
        String[] labels = labels();
        String[][] items = new String[ITEMS.length][];
        for (int i = 0; i < ITEMS.length; i++) items[i] = new String[]{ITEMS[i], labels[i]};
        LinearLayout line = null;
        int shown = 0;
        for (String[] item : items) {
            final Intent intent = intent(activity, item[0]);
            if (intent.resolveActivity(activity.getPackageManager()) == null) continue;
            if (shown % 2 == 0) {
                line = new LinearLayout(activity);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
                if (shown > 0) ll.topMargin = dp(activity, 8);
                box.addView(line, ll);
            }
            TextView tile = new TextView(activity);
            tile.setText(item[1]);
            tile.setTextColor(0xff14424f);
            tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tile.setGravity(Gravity.CENTER);
            tile.setMinHeight(dp(activity, 64));
            tile.setPadding(dp(activity, 6), dp(activity, 8), dp(activity, 6), dp(activity, 8));
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[]{android.R.attr.state_pressed}, roundedSolid(activity, 0xffd5e6ec, 12));
            states.addState(new int[]{}, roundedSolid(activity, 0xffeef4f6, 12));
            tile.setBackground(states);
            tile.setContentDescription("plus-sheet:" + item[0].replace("#", "-"));
            tile.setOnClickListener(v -> { dialog.dismiss(); activity.startActivity(intent); });
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            if (shown % 2 == 1) tp.setMarginStart(dp(activity, 8));
            line.addView(tile, tp);
            shown++;
        }
        dialog.setContentView(box);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            window.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM);
            window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
        dialog.show();
    }

    private static GradientDrawable roundedSolid(Context context, int colour, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(context, radiusDp));
        return shape;
    }

    /** Wraps the drawer's "Budget" heading with one row of Plus tiles below it. */
    public static View drawer(View budgetHeading) {
        if (!DRAWER_TILES) return budgetHeading;
        try {
            final Context context = budgetHeading.getContext();
            String[] labels = {PlusLang.tr("Budgeting", "Budżetowanie"), PlusLang.tr("Reports", "Raporty"),
                PlusLang.tr("Scheduled", "Zlecenia")};
            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int side = dp(context, 10), gap = dp(context, 6);
            row.setPadding(side, dp(context, 2), side, dp(context, 8));
            int count = 0;
            for (int i = 0; i < CLASSES.length; i++) {
                final Intent intent = new Intent().setClassName(context.getPackageName(), "org.classicbudget.patch." + CLASSES[i]);
                if (intent.resolveActivity(context.getPackageManager()) == null) continue;
                TextView tile = new TextView(context);
                tile.setText(labels[i]);
                tile.setTextColor(Color.WHITE);
                tile.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                tile.setGravity(Gravity.CENTER);
                tile.setMaxLines(2);
                tile.setMinHeight(dp(context, 44));
                tile.setPadding(dp(context, 4), dp(context, 6), dp(context, 4), dp(context, 6));
                tile.setBackground(tileBackground(context));
                tile.setContentDescription("plus-drawer:" + CLASSES[i]);
                tile.setOnClickListener(v -> context.startActivity(intent));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                if (count > 0) lp.setMarginStart(gap);
                row.addView(tile, lp);
                count++;
            }
            if (count == 0) return budgetHeading;
            LinearLayout box = new LinearLayout(context);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(budgetHeading, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            box.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            return box;
        } catch (RuntimeException unexpected) {
            // Never break the original drawer.
            return budgetHeading;
        }
    }

    /** Translucent white on the drawer's teal gradient; a little stronger while pressed. */
    private static StateListDrawable tileBackground(Context context) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, rounded(context, 0x55ffffff));
        states.addState(new int[]{}, rounded(context, 0x26ffffff));
        return states;
    }

    private static GradientDrawable rounded(Context context, int colour) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(context, 8));
        return shape;
    }

    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

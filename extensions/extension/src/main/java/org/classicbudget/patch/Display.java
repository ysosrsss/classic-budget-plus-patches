package org.classicbudget.patch;

import android.app.Activity;
import android.app.Application;
import android.app.UiModeManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

/**
 * Module "Display": dark theme and screen transitions, set in Plus settings.
 *
 * Dark theme uses Android's own force-dark renderer (Android 10+), the same
 * mechanism tools like Darq switch on from outside; the app theme opts in with
 * android:forceDarkAllowed. "Always dark" / "Off" use the per-app night mode
 * (Android 12+); on Android 10-11 the theme follows the system dark mode.
 *
 * Transitions: YNAB forces slide animations in code (overridePendingTransition),
 * which the earlier theme-only "NoActivityAnimation" patch could not stop. Every
 * such call is routed through {@link #transition}; original animations are the default.
 */
public final class Display {
    public static final String PREFS = "cb-plus-ui", DARK = "dark", MOTION = "transitions";
    public static final String SYSTEM = "system", ON = "on", OFF = "off";
    public static final String BALANCE_TOAST = "balance_toast", ABOVE = "above", ORIGINAL = "original";
    static volatile Context app;

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, 0); }
    public static String dark(Context c) { String v = prefs(c).getString(DARK, SYSTEM); return ON.equals(v) || OFF.equals(v) ? v : SYSTEM; }
    public static boolean animations(Context c) { return !OFF.equals(prefs(c).getString(MOTION, ON)); }
    public static String balanceToast(Context c) { String v = prefs(c).getString(BALANCE_TOAST, ABOVE); return ORIGINAL.equals(v) || OFF.equals(v) ? v : ABOVE; }
    static String balanceMode() { try { return app == null ? ABOVE : balanceToast(app); } catch (RuntimeException e) { return ABOVE; } }

    /*
     * Category balance bars after saving a transaction (original CategoryBalanceToaster):
     * the original hides the "Add transaction" button (GONE) for about 3 s and slides the
     * bars into its place at the bottom, so a quick second tap lands on the list below.
     * "above" (default): the button stays, the bars stack above it; "original"; "off": none.
     */
    /** Called first in the toaster; true = show nothing. */
    public static boolean balanceToastOff() { return OFF.equals(balanceMode()); }

    /** Replaces addTransactionButton.setVisibility(GONE) before the bars appear. */
    public static void balanceButton(View button, int visibility) { if (!ABOVE.equals(balanceMode())) button.setVisibility(visibility); }

    /** Bottom-anchored bar layout: in "above" mode lifted over the visible button. */
    public static void balanceToastParams(android.widget.FrameLayout.LayoutParams p) {
        if (!ABOVE.equals(balanceMode())) return;
        float dp = android.content.res.Resources.getSystem().getDisplayMetrics().density;
        p.bottomMargin += (int) (70 * dp);   // = original overlay_button_gap (54dp button + 2 x 8dp margin)
    }

    /** Called from the Application's onCreate (one hook). */
    public static void install(Application app) {
        Display.app = app.getApplicationContext();
        try { applyNightMode(app); } catch (RuntimeException ignored) { }
        // Optional modules that need app-wide callbacks (present only when patched in).
        try { Class.forName("org.classicbudget.patch.Alerts").getMethod("install", Application.class).invoke(null, app); } catch (Exception absent) { }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity a, Bundle b) {
                CompactEntry.install(a);
                // Android 10-11 have no per-app night mode: "Off" excludes the window from force dark.
                if (Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 31 && OFF.equals(dark(a)))
                    try { View.class.getMethod("setForceDarkAllowed", boolean.class).invoke(a.getWindow().getDecorView(), false); } catch (Exception ignored) { }
            }
            public void onActivityStarted(Activity a) { }
            public void onActivityResumed(Activity a) { }
            public void onActivityPaused(Activity a) { }
            public void onActivityStopped(Activity a) { }
            public void onActivitySaveInstanceState(Activity a, Bundle b) { }
            public void onActivityDestroyed(Activity a) { }
        });
    }

    /** Per-app night mode (Android 12+): follow system, always dark or always light. */
    public static void applyNightMode(Context c) {
        if (Build.VERSION.SDK_INT < 31) return;
        UiModeManager ui = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (ui == null) return;
        String d = dark(c);
        // API 31 method, called by reflection (the patch compiles against API 24).
        int mode = ON.equals(d) ? UiModeManager.MODE_NIGHT_YES : OFF.equals(d) ? UiModeManager.MODE_NIGHT_NO : UiModeManager.MODE_NIGHT_AUTO;
        try { UiModeManager.class.getMethod("setApplicationNightMode", int.class).invoke(ui, mode); } catch (Exception ignored) { }
    }

    public static void set(Context c, String key, String value) { prefs(c).edit().putString(key, value).commit(); if (DARK.equals(key)) applyNightMode(c); }

    /** Replaces every Activity.overridePendingTransition(enter, exit) call in the original code. */
    public static void transition(Activity a, int enter, int exit) {
        boolean on;
        try { on = animations(a); } catch (RuntimeException e) { on = true; }
        if (on) a.overridePendingTransition(enter, exit); else a.overridePendingTransition(0, 0);
    }
}

package org.classicbudget.patch;

import android.content.Context;

/**
 * Language of our added screens. Default "system": Polish on a Polish phone,
 * English otherwise. Can be fixed to English or Polish in "Plus settings".
 */
public final class PlusLang {
    public static final String PREFS = "cb-plus-ui", KEY = "lang", SYSTEM = "system", EN = "en", PL = "pl";

    public static String choice() {
        try {
            Context c = com.youneedabudget.ynab.core.app.d.i();
            String v = c == null ? SYSTEM : c.getSharedPreferences(PREFS, 0).getString(KEY, SYSTEM);
            return EN.equals(v) || PL.equals(v) ? v : SYSTEM;
        } catch (RuntimeException error) { return SYSTEM; }
    }

    public static boolean polish() {
        String c = choice();
        return PL.equals(c) || (SYSTEM.equals(c) && "pl".equals(phoneLanguage()));
    }

    /** The phone's language. Not Locale.getDefault(): the original app sets that to the budget's locale. */
    static String phoneLanguage() {
        try { return android.content.res.Resources.getSystem().getConfiguration().locale.getLanguage(); } catch (RuntimeException error) { return "en"; }
    }

    public static String tr(String en, String pl) { return polish() ? pl : en; }

    public static void set(Context c, String lang) { c.getSharedPreferences(PREFS, 0).edit().putString(KEY, EN.equals(lang) || PL.equals(lang) ? lang : SYSTEM).commit(); }
}

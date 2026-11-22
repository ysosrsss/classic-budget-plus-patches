package org.classicbudget.patch;

/**
 * Device letters of a budget (§157). YNAB 4 for Windows reads only one-letter devices
 * (DropboxBudgetMetaData.getDevices: deviceId.length <= 1), so a phone registered as "AA" would sync
 * changes the computer never sees. The original Android goes on from Z to AA (core.cloud.r.a(String)),
 * always continuing after the highest existing letter, never filling gaps. Hooked at that point:
 * a registration beyond Z stops with a clear message instead of creating an invisible device.
 */
public final class DeviceLetters {
    private DeviceLetters() {}

    /** Thrown instead of registering a device the Windows app would ignore. */
    public static final class Exhausted extends IllegalStateException {
        Exhausted(String message) { super(message); }
    }

    /**
     * Called with the letter the original engine is about to try next. One letter passes unchanged.
     * Longer ids stop the registration (no .ydevice is written).
     */
    /** Ids of the device files read in each budget folder (the original reads all of them before choosing). */
    private static final java.util.Map<String, java.util.Set<String>> SEEN = new java.util.HashMap<String, java.util.Set<String>>();
    private static String lastFolder;

    /** Called for every device file the original reads (DeviceCatalog.remember). */
    static synchronized void seen(java.io.File dataFolder, String id) {
        if (dataFolder == null || id == null) return;
        String key = String.valueOf(dataFolder.getParent());
        java.util.Set<String> ids = SEEN.get(key);
        if (ids == null) SEEN.put(key, ids = new java.util.HashSet<String>());
        ids.add(id);
        lastFolder = key;
    }

    /**
     * First one-letter id no device file of the folder uses. The original only continues after the highest
     * id, so an "AA" added by the original YNAB (sorted last) made it try "AB" although Z was free (098 test).
     * A letter never registered is safe: no device has knowledge of it, its counter starts at 0.
     */
    static synchronized String firstFree() {
        java.util.Set<String> ids = lastFolder == null ? null : SEEN.get(lastFolder);
        if (ids == null || ids.isEmpty()) return null;
        for (char c = 'A'; c <= 'Z'; c++) if (!ids.contains(String.valueOf(c))) return String.valueOf(c);
        return null;
    }

    public static String next(String id) {
        return next(null, null, null, null, id);
    }

    /**
     * Registration hook with the original accessor and folders (§160): when no letter is free, Plus offers
     * the place of a retired device (DeviceTakeover) instead of only explaining the stop.
     */
    public static String next(android.content.Context context, Object accessor, java.io.File data,
                              java.io.File devices, String id) {
        if (id != null && id.length() > 1) {
            String free = firstFree();
            if (free != null) {
                android.util.Log.w("ClassicBudgetPlus", "DEVICE_LETTER_FREE_USED " + free + " instead of " + id);
                return beforeNewRegistration(context, accessor, data, devices, free);
            }
            String message = PlusLang.tr(
                "All 26 device letters (A–Z) of this budget are used. YNAB 4 for Windows cannot see a 27th device, "
                    + "so this phone was not registered. See Plus settings → Devices.",
                "Wszystkie 26 liter urządzeń (A–Z) tego budżetu są zajęte. YNAB 4 na Windows nie widzi 27. urządzenia, "
                    + "więc ten telefon nie został zarejestrowany. Zobacz Ustawienia Plus → Urządzenia.");
            boolean offered = context != null && DeviceTakeover.offerForNewDevice(context, accessor, data, devices);
            // Android 12+ cuts toasts to two lines: the toast is short, Plus settings has the full text.
            if (!offered) tell(PlusLang.tr("No free device letter (A–Z): this phone was not registered. See Plus settings → Devices.",
                "Brak wolnej litery urządzenia (A–Z): telefon nie został zarejestrowany. Zobacz Ustawienia Plus → Urządzenia."));
            if (offered) remember();
            throw new Exhausted(message);
        }
        return beforeNewRegistration(context, accessor, data, devices, id);
    }

    private static String beforeNewRegistration(android.content.Context context, Object accessor,
                                                 java.io.File data, java.io.File devices, String id) {
        if (context != null && id != null && id.matches("[A-Z]")
            && DeviceTakeover.earlyRecoveryEnabled()
            && DeviceTakeover.offerEarlyRecovery(context, accessor, data, devices, id)) {
            // This hook runs in the engine's new-letter branch. Stop before it writes the registration.
            throw new Exhausted("DEVICE_REGISTRATION_CHOICE_REQUIRED");
        }
        return id;
    }

    /** Letters the budget knows (versionVector of the open budget), one-letter ids only. */
    public static java.util.TreeSet<String> used(android.database.sqlite.SQLiteDatabase db) {
        java.util.TreeSet<String> out = new java.util.TreeSet<String>();
        try (android.database.Cursor c = db.rawQuery("SELECT name FROM versionVector", null)) {
            while (c.moveToNext()) {
                String name = c.getString(0);
                if (name != null && name.matches("[A-Z]")) out.add(name);
            }
        } catch (RuntimeException missing) {
            // No knowledge table yet: nothing to count.
        }
        return out;
    }

    /**
     * The letter a new device gets with Plus: the original takes the one after the highest; when that would
     * be longer than one letter, Plus takes the first free one (099). So "next" is the first free letter
     * after the highest, else the first gap.
     */
    public static String nextNew(java.util.TreeSet<String> used) {
        if (used.isEmpty()) return "A";
        char last = used.last().charAt(0);
        if (last < 'Z') return String.valueOf((char) (last + 1));
        for (char c = 'A'; c <= 'Z'; c++) if (!used.contains(String.valueOf(c))) return String.valueOf(c);
        return null;
    }

    /** One-letter devices registered in the budget: device files the original read (device catalog). */
    public static java.util.TreeSet<String> registered(android.content.Context context, String budget) {
        java.util.TreeSet<String> out = new java.util.TreeSet<String>();
        for (String key : context.getSharedPreferences("cb-device-catalog", 0).getAll().keySet()) {
            if (!key.startsWith(budget + "|")) continue;
            String id = key.substring(budget.length() + 1);
            if (id.matches("[A-Z]")) out.add(id);
        }
        return out;
    }

    /** Preference set when a registration was stopped; Plus settings shows it. */
    public static final String PREFS = "cb-plus-devices", EXHAUSTED_AT = "letters_exhausted_at";

    /**
     * The original then only shows its generic "Woops! Something went wrong when loading your budget"
     * (094 emulator test), so the reason is shown as a long toast, logged and remembered.
     */
    /** Stop recorded for Plus settings when the choice screen replaced the toast. */
    static void remember() {
        android.util.Log.w("ClassicBudgetPlus", "DEVICE_LETTERS_EXHAUSTED takeover offered");
        try {
            android.content.Context context = com.youneedabudget.ynab.core.app.d.i();
            if (context != null) context.getSharedPreferences(PREFS, 0).edit().putLong(EXHAUSTED_AT, System.currentTimeMillis()).apply();
        } catch (Throwable unavailable) {
            // Only the note in Plus settings is missing.
        }
    }

    static void tell(final String message) {
        android.util.Log.w("ClassicBudgetPlus", "DEVICE_LETTERS_EXHAUSTED " + message);
        try {
            // The original app keeps its Application in core.app.d.a (used the same way by the write tests).
            java.lang.reflect.Field field = Class.forName("com.youneedabudget.ynab.core.app.d").getDeclaredField("a");
            field.setAccessible(true);
            final android.content.Context context = (android.content.Context) field.get(null);
            if (context == null) return;
            context.getSharedPreferences(PREFS, 0).edit().putLong(EXHAUSTED_AT, System.currentTimeMillis()).apply();
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show());
        } catch (Throwable unavailable) {
            // The registration is still stopped; only the explanation is missing.
        }
    }
}

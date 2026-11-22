package org.classicbudget.patch;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.concurrent.TimeUnit;

/** Opt-in breadcrumbs. The enum is the entire event payload, so no budget data is retained. */
final class PlusDiagnostics {
    enum Event {
        START, STOP, WIDGET_LAUNCH, WIDGET_FORM_READY, WIDGET_ADD_REQUESTED,
        TRANSACTION_SAVED, TRANSACTION_SAVE_FAILED, SYNC_EXPORT_STARTED, SYNC_EXPORT_OK, SYNC_EXPORT_FAILED,
        SYNC_OK, SYNC_AUTH, SYNC_LIMIT, SYNC_FILE_CONFLICT, SYNC_SEND_FAILED, SYNC_RECEIVE_FAILED,
        CONFLICT_CAPTURED, CONFLICT_REPAIR_OK, CONFLICT_REPAIR_FAILED
    }

    private static final long WINDOW = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_EVENTS = 256;

    private PlusDiagnostics() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("cb-plus-diagnostics", Context.MODE_PRIVATE);
    }

    private static boolean active(SharedPreferences state, long now) {
        long start = state.getLong("start", 0);
        return start > 0 && now >= start && now - start < WINDOW && state.getInt("count", 0) < MAX_EVENTS;
    }

    static boolean active(Context context) {
        return active(prefs(context), System.currentTimeMillis());
    }

    static synchronized void begin(Context context) {
        long now = System.currentTimeMillis();
        prefs(context).edit().putLong("start", now).putInt("count", 1).putString("events", "0 START\n").apply();
    }

    static synchronized void stop(Context context) {
        record(context, Event.STOP);
        prefs(context).edit().remove("start").apply();
    }

    static synchronized void record(Context context, Event event) {
        try {
            long now = System.currentTimeMillis();
            SharedPreferences state = prefs(context);
            if (!active(state, now)) return;
            String line = (now - state.getLong("start", now)) + " " + event.name() + "\n";
            state.edit().putString("events", state.getString("events", "") + line)
                .putInt("count", state.getInt("count", 0) + 1).apply();
        } catch (RuntimeException ignored) {
            // Diagnostics must never affect the operation being observed.
        }
    }

    static String contents(Context context) {
        String events = prefs(context).getString("events", "");
        if (events.isEmpty()) return "";
        return "YNAB Plus diagnostic events v1\nAndroid API " + Build.VERSION.SDK_INT
            + "\nElapsed milliseconds and event\n" + events;
    }
}

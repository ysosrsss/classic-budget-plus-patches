package org.classicbudget.patch;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.File;

/** Old-profile alarms must not recreate a database after a sync-method reset. */
public final class SyncLifecycle {
    private SyncLifecycle() { }

    static void cancelOldSync(Context context) throws Exception {
        // Ask the original helper for its exact intent, including Alongside's action rewrite.
        Class<?> helper = Class.forName("com.youneedabudget.ynab.core.cloud.o");
        java.lang.reflect.Method intentMethod = helper.getDeclaredMethod("b", Context.class);
        intentMethod.setAccessible(true);
        Intent intent = (Intent) intentMethod.invoke(null, context);
        PendingIntent pending = PendingIntent.getService(context, 0, intent, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            ((AlarmManager) context.getSystemService(Context.ALARM_SERVICE)).cancel(pending);
            pending.cancel();
        }
        context.stopService(intent);
    }

    /** Only SYNC needs an existing database; discovery and IMPORT_FULL still create it normally. */
    public static boolean allowIntent(Context context, Intent intent) {
        if (intent == null) return false;
        String action = intent.getAction();
        if (action == null || !(action.equals("com.youneedabudget.ynab.SYNC") ||
                action.equals(context.getPackageName() + ".isolated.com.youneedabudget.ynab.SYNC"))) return true;
        String id = intent.getStringExtra("com.youneedabudget.ynab.intent.extra.BUDGET_ID");
        return id != null && id.indexOf('/') < 0 && id.indexOf('\\') < 0 &&
            new File(context.getApplicationInfo().dataDir, "databases/" + id).isFile();
    }
}

package org.classicbudget.patch;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.PowerManager;

/**
 * Reads the budget index (BudgetNotes.rebuild) outside the screen: with the screen off or another
 * app in front the plain thread of the budget screen was paused (phone, 14.11). A started service
 * with an ongoing notification keeps the process running; a partial wake lock (with a time limit)
 * keeps the CPU awake. Started from the budget screen, by hand or automatically while the index of
 * a budget has never been completed. One run at a time; progress and result are kept here for the
 * screen to show.
 */
public final class BudgetIndexService extends Service {
    static final String ACTION_STOP = "org.classicbudget.patch.INDEX_STOP";
    static final String EXTRA_FOLDER = "budgetFolder";
    static final String EXTRA_DATA = "dataFolder";
    static final String EXTRA_AUTO = "auto";
    static final int NOTIFICATION = 7301;
    static final long WAKE_LIMIT_MS = 30 * 60 * 1000L;

    /** State read by the budget screen. */
    static volatile boolean running, cancel, auto;
    static volatile int done, total, stage;
    static volatile String result;

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("cb-budget-index", 0);
    }

    static String budgetKey(String budgetFolder, String dataFolder) {
        return "complete:" + budgetFolder + "/" + dataFolder;
    }

    /** True once every file of the budget was read; later files are indexed by the normal sync. */
    static boolean complete(Context context, String budgetFolder, String dataFolder) {
        return prefs(context).getBoolean(budgetKey(budgetFolder, dataFolder), false);
    }

    static void start(Context context, String budgetFolder, String dataFolder, boolean automatic) {
        if (running) return;
        result = null;  // the screen must not show the result of an earlier run as this one's
        Intent intent = new Intent(context, BudgetIndexService.class);
        intent.putExtra(EXTRA_FOLDER, budgetFolder);
        intent.putExtra(EXTRA_DATA, dataFolder);
        intent.putExtra(EXTRA_AUTO, automatic);
        context.startService(intent);
    }

    static void stop(Context context) {
        cancel = true;
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cancel = true;
            return START_NOT_STICKY;
        }
        if (running || intent == null) return START_NOT_STICKY;
        final String budgetFolder = intent.getStringExtra(EXTRA_FOLDER);
        final String dataFolder = intent.getStringExtra(EXTRA_DATA);
        if (budgetFolder == null || dataFolder == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        cancel = false;
        auto = intent.getBooleanExtra(EXTRA_AUTO, false);
        done = 0;
        total = 0;
        stage = 0;
        result = null;
        startForeground(NOTIFICATION, notification(PlusLang.tr("Reading budget files…", "Czytam pliki budżetu…"), 0, 0, true));
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        final PowerManager.WakeLock wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClassicBudgetPlus:index");
        wake.acquire(WAKE_LIMIT_MS);
        new Thread(new Runnable() {
            public void run() {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                String text;
                boolean finished = false;
                try {
                    BudgetNotes.Result r = BudgetNotes.rebuild(BudgetIndexService.this, budgetFolder, dataFolder, new BudgetNotes.Progress() {
                        long shown;
                        public void step(int d, int t, int s) {
                            done = d;
                            total = t;
                            stage = s;
                            long now = System.currentTimeMillis();
                            if (now - shown < 1000) return;  // one notification update per second
                            shown = now;
                            String label = s == 1 ? PlusLang.tr("Retrying files…", "Ponawiam pliki…") : PlusLang.tr("Reading budget files…", "Czytam pliki budżetu…");
                            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(label + " " + d + "/" + t, d, t, true));
                        }
                        public boolean cancelled() {
                            return cancel;
                        }
                    });
                    finished = r.remaining == 0 && !cancel;
                    if (finished) prefs(BudgetIndexService.this).edit().putBoolean(budgetKey(budgetFolder, dataFolder), true).apply();
                    text = PlusLang.tr("Files read now: ", "Odczytane teraz pliki: ") + r.read
                            + PlusLang.tr(", already known: ", ", znane wcześniej: ") + r.known
                            + PlusLang.tr(", budget cells: ", ", komórki budżetu: ") + r.cells;
                    if (r.remaining > 0)
                        text += "\n\n" + PlusLang.tr("Not read yet: ", "Jeszcze nieodczytane: ") + r.remaining
                                + PlusLang.tr(". Tap Refresh index again to continue from here.", ". Naciśnij ponownie „Odśwież indeks”, aby dokończyć.")
                                + (r.cause == null ? "" : "\n(" + r.cause + ")");
                } catch (Throwable e) {
                    DavProvider.diagnostic(e);
                    android.util.Log.w("ClassicBudgetPlus", "BudgetIndexService", e);
                    text = PlusLang.tr("Could not read the budget files: ", "Nie udało się odczytać plików budżetu: ") + e.getClass().getSimpleName() + " " + e.getMessage();
                }
                result = text;
                running = false;
                if (wake.isHeld()) wake.release();
                stopForeground(true);
                String summary = finished ? PlusLang.tr("Budget index ready", "Indeks budżetu gotowy")
                        : PlusLang.tr("Budget index: not finished, open the budget screen", "Indeks budżetu: niedokończony, otwórz ekran budżetu");
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION, notification(summary, 0, 0, false));
                stopSelf();
            }
        }, "cb-budget-index").start();
        return START_NOT_STICKY;
    }

    Notification notification(String text, int value, int max, boolean ongoing) {
        Intent open = new Intent(this, BudgetActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = new Notification.Builder(this)
                .setSmallIcon(getApplicationInfo().icon)
                .setContentTitle(PlusLang.tr("Budget index", "Indeks budżetu"))
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setOnlyAlertOnce(true);
        if (ongoing) {
            builder.setProgress(max, value, max == 0);
            Intent stop = new Intent(this, BudgetIndexService.class).setAction(ACTION_STOP);
            PendingIntent stopIntent = PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            builder.addAction(0, PlusLang.tr("Stop", "Przerwij"), stopIntent);
        }
        return builder.build();
    }
}

package org.classicbudget.patch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.UriPermission;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;

/**
 * Runs in its own process (":cbreset", manifest) after the main process ended, so no
 * in-memory state of the old profile can be written back. Removes everything the app
 * keeps locally except Plus settings (cb-plus-*), releases granted folder permissions
 * and starts the app again. Equivalent to "Clear data" minus Plus settings.
 */
public final class SyncResetActivity extends Activity {
    static final String[] KEEP_PREFS = {"cb-plus-accounts.xml", "cb-plus-reports.xml", "cb-plus-ui.xml"};
    static final String PROCESS_SUFFIX = ":cbreset";

    /**
     * Called first in the Application's onCreate (hook): true in the reset process, which then
     * skips the original start-up entirely (no database, no sync, no preferences opened there).
     */
    public static boolean resetProcess(android.app.Application app) {
        String name = null;
        try { if (android.os.Build.VERSION.SDK_INT >= 28) name = (String) android.app.Application.class.getMethod("getProcessName").invoke(null); } catch (Exception ignored) { }
        if (name == null) try {
            byte[] b = new byte[256]; int n; try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) { n = in.read(b); }
            int end = 0; while (end < Math.max(n, 0) && b[end] != 0) end++; name = new String(b, 0, end, "UTF-8");
        } catch (Exception ignored) { }
        return name != null && name.endsWith(PROCESS_SUFFIX);
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView t = new TextView(this); t.setText(PlusLang.tr("Changing sync method…", "Zmieniam synchronizację…")); t.setPadding(48, 48, 48, 48); setContentView(t);
        final int old = getIntent().getIntExtra("pid", -1);
        new Thread(new Runnable() { public void run() {
            waitFor(old);
            try { SyncLifecycle.cancelOldSync(SyncResetActivity.this); }
            catch (Exception error) {
                // Fail closed: leave the old local copy intact if cancellation cannot be established.
                runOnUiThread(new Runnable() { public void run() {
                    t.setText(PlusLang.tr("Could not stop the previous sync. Close and reopen the app before trying again.",
                        "Nie udało się zatrzymać poprzedniej synchronizacji. Zamknij i otwórz aplikację przed kolejną próbą."));
                } });
                return;
            }
            reset();
            runOnUiThread(new Runnable() { public void run() {
                Intent start = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (start != null) { start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); startActivity(start); }
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            } });
        } }).start();
    }

    /** The main process must be gone before its files are removed. */
    void waitFor(int pid) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (int i = 0; i < 50; i++) {
            boolean alive = false;
            java.util.List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) for (ActivityManager.RunningAppProcessInfo p : list) if (p.pid == pid || getPackageName().equals(p.processName)) alive = true;
            if (!alive) return;
            if (i == 20 && pid > 0) android.os.Process.killProcess(pid);   // same app, same uid
            try { Thread.sleep(100); } catch (InterruptedException e) { return; }
        }
    }

    void reset() {
        try { for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            getContentResolver().releasePersistableUriPermission(p.getUri(), (p.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) | (p.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        } catch (RuntimeException ignored) { }
        File data = new File(getApplicationInfo().dataDir);
        File[] children = data.listFiles(); if (children == null) return;
        for (File child : children) {
            String n = child.getName();
            if (n.equals("lib") || n.equals("code_cache")) continue;   // lib is a link to the APK's libraries
            if (n.equals("shared_prefs")) {
                File[] prefs = child.listFiles();
                if (prefs != null) for (File p : prefs) if (!java.util.Arrays.asList(KEEP_PREFS).contains(p.getName())) delete(p);
            } else delete(child);
        }
    }

    static void delete(File f) {
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}

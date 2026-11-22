package org.classicbudget.patch;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.UriPermission;
import android.os.Bundle;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;

/**
 * Runs in its own process (":cbreset", manifest) after the main process ended, so no
 * in-memory state of the old profile can be written back. Removes everything the app
 * keeps locally except Plus settings (cb-plus-*), releases granted folder permissions
 * and starts the app again. Equivalent to "Clear data" minus Plus settings.
 */
public final class SyncResetActivity extends Activity {
    static final String[] KEEP_PREFS = {"cb-plus-accounts.xml", "cb-plus-reports.xml", "cb-plus-ui.xml",
        "cb-plus-budget-goals.xml", "cb-plus-transaction-filters.xml"};
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
        boolean changingFolder = getIntent().getBooleanExtra(SyncSwitchActivity.DROPBOX_FOLDER, false);
        new Thread(new Runnable() { public void run() {
            synchronized (SyncResetActivity.class) {
                String folder = DropboxProfile.pendingFolder(SyncResetActivity.this);
                if (changingFolder != (folder != null)) return;
                try {
                    if (!waitFor(old)) throw new IOException("PREVIOUS_PROCESS_STILL_RUNNING");
                    SyncLifecycle.cancelOldSync(SyncResetActivity.this);
                    if (!SyncSwitchActivity.pending(SyncResetActivity.this).isEmpty()) {
                        if (changingFolder && !DropboxProfile.cancelFolder(SyncResetActivity.this))
                            throw new IOException("CANNOT_CANCEL_FOLDER_CHANGE");
                        show(t, "Changes are waiting to be sent. Reopen the app and sync them first.",
                            "Zmiany czekają na wysłanie. Otwórz ponownie aplikację i najpierw je zsynchronizuj.");
                        return;
                    }
                    reset(changingFolder);
                    // The selected root changes only after every old-profile file was removed successfully.
                    if (changingFolder && !DropboxProfile.finishFolder(SyncResetActivity.this, folder))
                        throw new IOException("CANNOT_COMMIT_FOLDER_CHANGE");
                } catch (Exception error) {
                    show(t, "Could not finish changing the connection. Close and reopen the app to retry.",
                        "Nie udało się dokończyć zmiany połączenia. Zamknij i otwórz aplikację, aby ponowić.");
                    return;
                }
            }
            runOnUiThread(new Runnable() { public void run() {
                Intent start = getPackageManager().getLaunchIntentForPackage(getPackageName());
                if (start != null) { start.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK); startActivity(start); }
                finish();
                android.os.Process.killProcess(android.os.Process.myPid());
            } });
        } }).start();
    }

    private void show(TextView view, String en, String pl) {
        runOnUiThread(() -> view.setText(PlusLang.tr(en, pl)));
    }

    /** The main process must be gone before its files are removed. */
    boolean waitFor(int pid) {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        for (int i = 0; i < 50; i++) {
            boolean alive = false;
            java.util.List<ActivityManager.RunningAppProcessInfo> list = am.getRunningAppProcesses();
            if (list != null) for (ActivityManager.RunningAppProcessInfo p : list) if (p.pid == pid || getPackageName().equals(p.processName)) alive = true;
            if (!alive) return list != null;
            if (i == 20 && pid > 0) android.os.Process.killProcess(pid);   // same app, same uid
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }

    void reset(boolean keepDropbox) throws IOException {
        try { for (UriPermission p : getContentResolver().getPersistedUriPermissions())
            getContentResolver().releasePersistableUriPermission(p.getUri(), (p.isReadPermission() ? Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) | (p.isWritePermission() ? Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0));
        } catch (RuntimeException ignored) { }
        File data = new File(getApplicationInfo().dataDir).getCanonicalFile();
        File[] children = data.listFiles();
        if (children == null) throw new IOException("CANNOT_LIST_LOCAL_DATA");
        for (File child : children) {
            String n = child.getName();
            if (n.equals("lib") || n.equals("code_cache")) continue;   // lib is a link to the APK's libraries
            if (n.equals("shared_prefs")) {
                File[] prefs = child.listFiles();
                if (prefs == null) throw new IOException("CANNOT_LIST_PREFERENCES");
                for (File p : prefs) {
                    String name = p.getName();
                    if (name.endsWith(".bak")) name = name.substring(0, name.length() - 4);
                    if (java.util.Arrays.asList(KEEP_PREFS).contains(name)) continue;
                    if (keepDropbox && ("dropboxSettings.xml".equals(name) || (BackendSelector.PREFS + ".xml").equals(name))) continue;
                    delete(p);
                }
            } else delete(child);
        }
    }

    static void delete(File f) throws IOException {
        if (f.getAbsoluteFile().equals(f.getCanonicalFile()) && f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids == null) throw new IOException("CANNOT_LIST_PROFILE_DIRECTORY");
            for (File child : kids) delete(child);
        }
        if (!f.delete() && f.exists()) throw new IOException("CANNOT_REMOVE_PROFILE_FILE");
    }
}

package org.classicbudget.patch;

import android.content.*;
import android.net.Uri;
import java.io.*;
import org.json.*;
import com.youneedabudget.ynab.core.cloud.c;
import com.youneedabudget.ynab.core.cloud.z;

/** "Folder" backend (Syncthing-Fork etc.): one persisted SAF tree with read+write grant. */
public final class FolderProvider implements c {
    static final String CONFIG = "folder-sync.json";
    private z accessor; private String active;

    static Context context() { return com.youneedabudget.ynab.core.app.d.i(); }
    static File configFile() { return new File(context().getFilesDir(), CONFIG); }

    static Uri tree() throws Exception {
        File file = configFile(); if (!file.isFile() || file.length() > 4096) throw new FileNotFoundException("FOLDER_NOT_LINKED");
        JSONObject config = new JSONObject(WriteJournal.read(file));
        if (config.getInt("version") != 1 || !config.getBoolean("synthetic_only")) throw new IOException("FOLDER_CONFIG_REJECTED");
        return SafReadAccessor.canonical(Uri.parse(config.getString("root")));
    }

    static void save(Uri tree) throws Exception {
        String root = tree.toString(), anchor = context().getSharedPreferences("folder-profile", 0).getString("root", root);
        if (!root.equals(anchor)) throw new IOException("FOLDER_PROFILE_IDENTITY_CHANGED");
        WriteJournal.atomic(configFile(), new JSONObject().put("version", 1).put("root", root).put("synthetic_only", true).toString());
    }

    public void a() { }
    public void a(Context ctx) {
        Intent intent = new Intent(ctx, FolderSetupActivity.class);
        if (!(ctx instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }
    public void b(Context ctx) { accessor = null; configFile().delete(); }
    public boolean b() {
        // No SafWriteAccessor here: its journal registers itself as the active one.
        try { Uri tree = tree(); new SafReadAccessor(context(), tree); return SafWriteAccessor.writeGranted(context(), tree); }
        catch (Exception error) { return false; }
    }
    public synchronized z c() {
        try {
            Uri tree = tree(); String root = tree.toString();
            SharedPreferences profile = context().getSharedPreferences("folder-profile", 0);
            if (!root.equals(profile.getString("root", root)) || (accessor != null && !root.equals(active))) throw new IOException("FOLDER_PROFILE_IDENTITY_CHANGED");
            if (accessor == null) {
                SafWriteAccessor next = new SafWriteAccessor(context(), tree);
                if (!profile.edit().putString("root", root).commit()) throw new IOException("FOLDER_PROFILE_PERSIST_FAILED");
                accessor = next; active = root;
            }
            return accessor;
        } catch (Exception error) { DavProvider.diagnostic(error); throw new IllegalStateException("Folder TEST unavailable", error); }
    }
}

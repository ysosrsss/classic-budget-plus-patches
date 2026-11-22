package org.classicbudget.patch;

import android.content.*;
import com.youneedabudget.ynab.core.cloud.c;
import com.youneedabudget.ynab.core.cloud.z;

/**
 * One APK, several sync backends. The choice belongs to this installation's
 * local profile: once a backend has been linked the profile is locked to it,
 * because the local database identity (device ID, knowledge, budget folder)
 * belongs to that backend. Switching requires a fresh app profile.
 */
public final class BackendSelector implements c {
    public static final String DROPBOX = "dropbox", WEBDAV = "webdav", FOLDER = "folder";
    static final String PREFS = "cb-backend-profile";
    private c delegate;
    private String kind;

    static Context context() { return com.youneedabudget.ynab.core.app.d.i(); }
    static SharedPreferences prefs() { return context().getSharedPreferences(PREFS, 0); }
    public static String chosen() { return prefs().getString("kind", null); }
    /**
     * Locked once a local budget exists: its device ID and knowledge belong to
     * the chosen backend. Linking alone does not lock (a rejected or expired
     * login must still allow choosing another backend).
     */
    public static boolean locked() {
        if (chosen() == null) return false;
        for (String name : context().databaseList()) if (name.contains("#") && !name.endsWith("-journal")) return true;
        return false;
    }

    /** Persist the user's choice; refuses a different backend for a locked profile. */
    public static void choose(String value) {
        if (!DROPBOX.equals(value) && !WEBDAV.equals(value) && !FOLDER.equals(value)) throw new IllegalArgumentException("UNKNOWN_BACKEND");
        String old = chosen();
        if (old != null && !old.equals(value) && locked()) throw new IllegalStateException("PROFILE_LOCKED");
        if (!prefs().edit().putString("kind", value).commit()) throw new IllegalStateException("PROFILE_PERSIST_FAILED");
    }

    /**
     * Upgrade from the original app: a Dropbox link made before this module existed
     * keeps working (the profile becomes "Dropbox" instead of asking to choose again).
     */
    private String adoptLinkedDropbox() {
        try {
            c dropbox = create(DROPBOX);
            if (!dropbox.b()) return null;
            prefs().edit().putString("kind", DROPBOX).commit();
            delegate = dropbox; kind = DROPBOX;
            return DROPBOX;
        } catch (Throwable error) {
            return null;
        }
    }

    static c create(String value) {
        if (DROPBOX.equals(value)) return new DropboxProfile();
        if (WEBDAV.equals(value)) return new DavProvider();
        if (FOLDER.equals(value)) return new FolderProvider();
        throw new IllegalStateException("UNKNOWN_BACKEND");
    }

    synchronized c current() {
        String value = chosen();
        if (value == null) value = adoptLinkedDropbox();
        if (value == null) return null;
        if (delegate == null || !value.equals(kind)) { delegate = create(value); kind = value; }
        return delegate;
    }

    /** Called by the original setup screen on resume (finishes Dropbox OAuth). */
    public void a() { c d = current(); if (d != null) d.a(); }

    /** "Link" button: let the user pick the backend first. */
    public void a(Context ctx) {
        Intent intent = new Intent(ctx, BackendChooserActivity.class);
        if (!(ctx instanceof android.app.Activity)) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(intent);
    }

    /** Disconnect keeps the profile lock: the local budget still belongs to this backend. */
    public void b(Context ctx) { c d = current(); if (d != null) d.b(ctx); }

    public boolean b() {
        c d = current();
        return d != null && d.b();
    }

    public z c() {
        c d = current();
        if (d == null) throw new IllegalStateException("No sync backend selected");
        return d.c();
    }
}

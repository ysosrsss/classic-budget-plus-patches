package org.classicbudget.patch;

import android.content.Context;
import java.io.*;
import java.util.*;
import com.youneedabudget.ynab.core.backend.i;
import com.youneedabudget.ynab.core.cloud.c;
import com.youneedabudget.ynab.core.cloud.z;

/**
 * The original Dropbox provider (OAuth, SDK, adapter) unchanged. In a TEST
 * build every path is confined below TEST_ROOT in the user's Dropbox, so the
 * real budget folder can never be listed, read or written. A release build
 * sets TEST_ROOT to null and uses the original accessor directly.
 */
public final class DropboxProfile implements c {
    /** Replaced by the build script; null means no confinement (release). */
    static final String TEST_ROOT = null;   // Morphe: see "Dropbox budget folder"
    private final c original;
    private z guarded;

    public DropboxProfile() {
        try { original = (c) Class.forName("com.youneedabudget.ynab.core.cloud.a.a").getConstructor().newInstance(); }
        catch (Exception error) { throw new IllegalStateException("Original Dropbox provider unavailable", error); }
    }

    /** User-chosen Dropbox folder holding .ynabSettings.yroot. TEST builds: only inside TEST_ROOT. */
    public static boolean folderAllowed(String folder) {
        if (folder == null || !folder.startsWith("/") || folder.length() > 300 || folder.indexOf(92) >= 0 /* backslash */ ||folder.indexOf('%') >= 0 || folder.indexOf(':') >= 0) return false;
        for (char ch : folder.toCharArray()) if (ch < 32) return false;
        String[] parts = folder.substring(1).split("/", -1);
        for (String part : parts) if (part.length() == 0 && folder.length() > 1 || part.equals(".") || part.equals("..")) return false;
        if (TEST_ROOT == null) return true;
        return folder.equals(TEST_ROOT) || folder.startsWith(TEST_ROOT + "/");
    }
    public static String folder() { String f = BackendSelector.prefs().getString("dropbox_folder", null); return folderAllowed(f) ? f : null; }
    public static void chooseFolder(String folder) {
        if (!folderAllowed(folder)) throw new IllegalArgumentException("DROPBOX_FOLDER_NOT_ALLOWED");
        String old = folder();
        if (old != null && !old.equals(folder) && BackendSelector.locked()) throw new IllegalStateException("PROFILE_LOCKED");
        if (!BackendSelector.prefs().edit().putString("dropbox_folder", folder).commit()) throw new IllegalStateException("PROFILE_PERSIST_FAILED");
    }

    static String root() {
        String chosen = folder();
        if (TEST_ROOT == null) return chosen == null || chosen.equals("/") ? null : chosen;
        if (chosen != null) return chosen;
        // Lab harness may move the TEST namespace to another synthetic folder; never outside a TEST prefix.
        String override = BackendSelector.prefs().getString("dropbox_test_root", null);
        if (override != null && override.matches("/(YNAB4_PLUS_TEST(/[A-Za-z0-9_-]{1,64})?|classic-budget-fulltest-[a-f0-9]{32})")) return override;
        return TEST_ROOT;
    }

    public void a() { original.a(); guarded = null; }
    public void a(Context ctx) { original.a(ctx); }
    public void b(Context ctx) { original.b(ctx); guarded = null; }
    public boolean b() { return original.b(); }

    public synchronized z c() {
        z accessor = original.c();
        if (accessor == null) throw new IllegalStateException("Dropbox is not linked");
        String root = root();
        if (root == null) return accessor;
        if (guarded == null || ((RootGuard) guarded).delegate != accessor) guarded = new RootGuard(accessor, root);
        return guarded;
    }

    /** Maps the app's logical "/" onto a fixed TEST folder and rejects everything else. */
    static final class RootGuard implements z {
        final z delegate; final String root;
        RootGuard(z delegate, String root) { this.delegate = delegate; this.root = root; }

        static String clean(File file) throws IOException {
            String path = file.getPath().replace('\\', '/');
            if (!path.startsWith("/")) path = "/" + path;
            if (path.indexOf('%') >= 0 || path.matches("(?s).*[\\x00-\\x1f].*")) throw new IOException("TEST_PATH_REJECTED");
            for (String part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new IOException("TEST_PATH_REJECTED");
            while (path.contains("//")) path = path.replace("//", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return path;
        }
        File remote(File file) throws IOException { String p = clean(file); return new File(p.equals("/") ? root : root + p); }
        File local(File file) throws IOException {
            String p = clean(file), lower = p.toLowerCase(Locale.ROOT), prefix = root.toLowerCase(Locale.ROOT);
            if (lower.equals(prefix)) return new File("/");
            if (!lower.startsWith(prefix + "/")) throw new IOException("TEST_RESULT_OUTSIDE_ROOT");
            return new File(p.substring(root.length()));
        }
        i local(i value) throws IOException { return value == null ? null : new i(local(value.c()), value.b(), value.e()); }

        public File a(File f, String text) throws IOException { return local(delegate.a(remote(f), text)); }
        public String a(File f) throws IOException { return delegate.a(remote(f)); }
        public InputStream b(File f) throws IOException { return delegate.b(remote(f)); }
        public void b(File f, String text) throws IOException { delegate.b(remote(f), text); }
        public i c(File f) throws IOException { return local(delegate.c(remote(f))); }
        public List<File> c(File f, String suffix) throws IOException {
            List<File> out = new ArrayList<File>(); for (File e : delegate.c(remote(f), suffix)) out.add(local(e)); return out;
        }
        public List<i> d(File f, String suffix) throws IOException {
            List<i> out = new ArrayList<i>(); for (i e : delegate.d(remote(f), suffix)) out.add(local(e)); return out;
        }
        public void d(File f) throws IOException { clean(f); throw new IOException("TEST_DELETE_DISABLED"); }
    }
}

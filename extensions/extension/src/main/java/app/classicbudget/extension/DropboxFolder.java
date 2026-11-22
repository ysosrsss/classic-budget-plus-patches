package app.classicbudget.extension;

import com.youneedabudget.ynab.core.backend.i;
import com.youneedabudget.ynab.core.cloud.z;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runtime part of "Dropbox budget folder".
 *
 * The original app looks for budgets only at the top of the Dropbox ("/" and "/YNAB"). This
 * wraps the app's Dropbox file accessor so that the app's "/" is the chosen folder: every path
 * the app reads or writes is moved below it, and paths coming back are moved up again. Nothing
 * outside the folder is listed, read or written. The folder is fixed when patching (option).
 *
 * A plain implementation of the app's accessor interface (compiled against stubs): exceptions of
 * the Dropbox SDK pass through unchanged, exactly as the app expects them.
 */
@SuppressWarnings("unused")
public final class DropboxFolder {
    /** Replaced by the patch with the chosen folder. "" or "/" = the original behaviour. */
    static String folder() {
        return "";
    }

    /** Wraps the original Dropbox accessor; returns it unchanged for the original behaviour or an invalid folder. */
    public static Object wrap(Object accessor) {
        String root = normalized(folder());
        if (!(accessor instanceof z) || accessor instanceof Root || root == null || root.equals("/")) return accessor;
        return new Root((z) accessor, root);
    }

    /** "/a/b" form ("YNAB4_PLUS_TEST", "/YNAB4_PLUS_TEST/" and "\\YNAB4_PLUS_TEST" are the same); null when unsafe. */
    static String normalized(String folder) {
        if (folder == null) return null;
        String f = folder.trim().replace('\\', '/');
        if (!f.startsWith("/")) f = "/" + f;
        while (f.length() > 1 && f.endsWith("/")) f = f.substring(0, f.length() - 1);
        if (f.length() > 300 || f.indexOf('%') >= 0 || f.indexOf(':') >= 0) return null;
        for (char ch : f.toCharArray()) if (ch < 32) return null;
        for (String part : f.substring(1).split("/", -1)) {
            if ((part.length() == 0 && f.length() > 1) || part.equals(".") || part.equals("..")) return null;
        }
        return f;
    }

    static final class Root implements z {
        final z delegate;
        final String root;

        Root(z delegate, String root) { this.delegate = delegate; this.root = root; }

        static String clean(File file) throws IOException {
            String path = file.getPath().replace('\\', '/');
            if (!path.startsWith("/")) path = "/" + path;
            for (String part : path.split("/", -1)) if (part.equals(".") || part.equals("..")) throw new IOException("PATH_REJECTED");
            while (path.contains("//")) path = path.replace("//", "/");
            if (path.length() > 1 && path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return path;
        }

        File remote(File file) throws IOException {
            String p = clean(file);
            return new File(p.equals("/") ? root : root + p);
        }

        File local(File file) throws IOException {
            if (file == null) return null;
            String p = clean(file), lower = p.toLowerCase(Locale.ROOT), prefix = root.toLowerCase(Locale.ROOT);
            if (lower.equals(prefix)) return new File("/");
            if (!lower.startsWith(prefix + "/")) throw new IOException("RESULT_OUTSIDE_FOLDER");
            return new File(p.substring(root.length()));
        }

        i local(i entry) throws IOException {
            return entry == null ? null : new i(local(entry.c()), entry.b(), entry.e());
        }

        public File a(File f, String text) throws IOException { return local(delegate.a(remote(f), text)); }
        public String a(File f) throws IOException { return delegate.a(remote(f)); }
        public InputStream b(File f) throws IOException { return delegate.b(remote(f)); }
        public void b(File f, String text) throws IOException { delegate.b(remote(f), text); }
        public i c(File f) throws IOException { return local(delegate.c(remote(f))); }

        public List<File> c(File f, String suffix) throws IOException {
            List<File> in = delegate.c(remote(f), suffix);
            if (in == null) return null;
            List<File> out = new ArrayList<File>();
            for (File e : in) out.add(local(e));
            return out;
        }

        public List<i> d(File f, String suffix) throws IOException {
            List<i> in = delegate.d(remote(f), suffix);
            if (in == null) return null;
            List<i> out = new ArrayList<i>();
            for (i e : in) out.add(local(e));
            return out;
        }

        public void d(File f) throws IOException { delegate.d(remote(f)); }
    }
}

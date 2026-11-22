package org.classicbudget.patch;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/**
 * DRAFT (2026-11-09, compile-checked only, no runtime test): publication
 * protocol for a SAF folder synchronized by Syncthing. See
 * docs/PROJEKT_ZAPISU_SAF.md. Never trusts the requested name: every create
 * and rename is followed by reading the provider's actual display name.
 */
public final class SafTransport {
    public static final class Collision extends IOException { Collision(String m) { super(m); } }
    public static final class Uncertain extends IOException { Uncertain(String m, Throwable c) { super(m, c); } }

    static final String STAGING_PREFIX = ".cbtmp-", OLD_PREFIX = ".cbold-";
    static final int MAX_BYTES = 4 * 1024 * 1024;          // writes (diffs/devices are small)
    static final int MAX_READ_BYTES = 64 * 1024 * 1024;    // reads: as WebDAV GET; real Budget.yfull > 4 MB
    final ContentResolver resolver; final Uri tree;

    public SafTransport(ContentResolver resolver, Uri tree) { this.resolver = resolver; this.tree = tree; }

    static String sha(byte[] data) throws IOException {
        try { StringBuilder s = new StringBuilder(); for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b)); return s.toString(); }
        catch (Exception e) { throw new IOException("SHA_UNAVAILABLE", e); }
    }

    /** Children of a directory document: display name -> document URI. */
    Map<String, Uri> children(Uri dir) throws IOException {
        Map<String, Uri> out = new HashMap<String, Uri>();
        Uri query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(dir));
        Cursor c = resolver.query(query, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (c == null) throw new IOException("LIST_NULL");
        try { while (c.moveToNext()) { if (out.size() > 20000) throw new IOException("LIST_LIMIT"); out.put(c.getString(1), DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))); } }
        finally { c.close(); }
        return out;
    }

    String name(Uri doc) throws IOException {
        Cursor c = resolver.query(doc, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
        if (c == null) throw new FileNotFoundException("METADATA_NULL");
        try { if (!c.moveToFirst()) throw new FileNotFoundException("METADATA_EMPTY"); return c.getString(0); } finally { c.close(); }
    }

    byte[] read(Uri doc) throws IOException {
        InputStream in = resolver.openInputStream(doc); if (in == null) throw new IOException("OPEN_NULL");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) { if (out.size() + n > MAX_READ_BYTES) throw new IOException("READ_LIMIT"); out.write(b, 0, n); } }
        finally { in.close(); }
        return out.toByteArray();
    }

    /** Steps 1-3: own staging file with verified exact name and bytes. */
    Uri stage(Uri dir, byte[] data, String prefix) throws IOException {
        if (data.length > MAX_BYTES) throw new IOException("WRITE_LIMIT");
        String requested = prefix + UUID.randomUUID().toString().replace("-", "") + ".tmp";
        Uri doc = DocumentsContract.createDocument(resolver, dir, "application/octet-stream", requested);
        if (doc == null) throw new IOException("CREATE_NULL");
        if (!requested.equals(name(doc))) { DocumentsContract.deleteDocument(resolver, doc); throw new IOException("STAGING_NAME_CHANGED_BY_PROVIDER"); }
        ParcelFileDescriptor fd = resolver.openFileDescriptor(doc, "w"); if (fd == null) throw new IOException("FD_NULL");
        FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
        try { out.write(data); out.flush(); fd.getFileDescriptor().sync(); } finally { out.close(); fd.close(); }
        if (!sha(read(doc)).equals(sha(data))) throw new IOException("STAGING_BYTES_CHANGED");
        return doc;
    }

    /** Steps 5-6: rename, then insist on the exact final name. Returns the final URI. */
    Uri rename(Uri doc, String finalName, boolean ownStaging) throws IOException {
        String before = name(doc);
        Uri result;
        try { result = DocumentsContract.renameDocument(resolver, doc, finalName); }
        catch (Exception error) { throw new Uncertain("RENAME_FAILED", error); }
        if (result == null) throw new Collision("RENAME_RETURNED_NULL");          // observed on API23 for an occupied name
        String actual = name(result);
        if (!finalName.equals(actual)) {                                          // observed on API34: "name (2).ext"
            if (ownStaging) DocumentsContract.deleteDocument(resolver, result);   // only our fresh staging may be deleted
            else {
                // Never delete a real registration: put its previous name back.
                Uri back;
                try { back = DocumentsContract.renameDocument(resolver, result, before); }
                catch (Exception error) { throw new Uncertain("RENAME_SUFFIXED_RESTORE_FAILED", error); }
                if (back == null || !before.equals(name(back))) throw new Uncertain("RENAME_SUFFIXED_RESTORE_FAILED", null);
            }
            throw new Collision("RENAME_SUFFIXED");
        }
        return result;
    }

    /** Publish an immutable file (.ydiff or a new .ydevice). Idempotent for identical bytes. */
    public Uri publishNew(Uri dir, String finalName, byte[] data) throws IOException {
        Map<String, Uri> before = children(dir);
        if (before.containsKey(finalName)) {
            if (sha(read(before.get(finalName))).equals(sha(data))) return before.get(finalName);
            throw new Collision("IMMUTABLE_TARGET_EXISTS");
        }
        Uri staging = stage(dir, data, STAGING_PREFIX);
        Uri done;
        try { done = rename(staging, finalName, true); }
        catch (Collision collision) { discard(staging); throw collision; }
        if (!sha(read(done)).equals(sha(data))) throw new Uncertain("PUBLISHED_BYTES_DIFFER", null);
        return done;
    }

    /** Replace this device's own registration without truncating the visible file. */
    public Uri replaceOwn(Uri dir, String finalName, byte[] data) throws IOException {
        Uri current = children(dir).get(finalName);
        if (current == null) throw new FileNotFoundException("OWN_REGISTRATION_MISSING");
        Uri staging = stage(dir, data, STAGING_PREFIX);
        String oldName = OLD_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".tmp";
        Uri old = rename(current, oldName, false);
        Uri done;
        try { done = rename(staging, finalName, true); }
        catch (IOException error) {
            if (error instanceof Collision) discard(staging);
            // Put the previous registration back; the journal records the uncertain state.
            try { rename(old, finalName, false); } catch (IOException restore) { throw new Uncertain("REPLACE_AND_RESTORE_FAILED", restore); }
            throw error;
        }
        if (!sha(read(done)).equals(sha(data))) throw new Uncertain("PUBLISHED_BYTES_DIFFER", null);
        DocumentsContract.deleteDocument(resolver, old);
        return done;
    }

    /** Delete our own staging if it still exists under its exact staging name (API23 null-rename case). */
    void discard(Uri staging) {
        try { String n = name(staging); if (n.startsWith(STAGING_PREFIX) && n.endsWith(".tmp")) DocumentsContract.deleteDocument(resolver, staging); }
        catch (Exception ignored) { /* already renamed or deleted */ }
    }

    /** Syncthing conflict copies must stop export and be shown to the user, never deleted. */
    public List<String> syncConflicts(Uri dir) throws IOException {
        List<String> out = new ArrayList<String>();
        for (String n : children(dir).keySet()) if (n.contains(".sync-conflict-")) out.add(n);
        Collections.sort(out); return out;
    }

    // Lab contract hooks (used only by the standalone contract app).
    public byte[] readForLab(Uri doc) throws IOException { return read(doc); }
    public Map<String, Uri> childrenForLab(Uri dir) throws IOException { return children(dir); }
    /** Forces the provider collision path: stage, then rename onto an occupied name. */
    public void renameStagingOntoForLab(Uri dir, String occupied, byte[] data) throws IOException {
        Uri staging = stage(dir, data, STAGING_PREFIX);
        try { rename(staging, occupied, true); } catch (Collision c) { discard(staging); throw c; }
        throw new IOException("LAB_EXPECTED_COLLISION");
    }
}

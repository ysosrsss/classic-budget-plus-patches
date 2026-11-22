package org.classicbudget.patch;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import org.json.*;

/**
 * Journaled writer on a SAF folder (synchronized by Syncthing or similar).
 * Same contract as the WebDAV WriteTransport, with the provider behaviour
 * measured on API23/API34: staging lives next to the target under the name
 * the journal pinned, every create/rename is followed by reading the actual
 * name, and a taken immutable name is a collision, never an overwrite.
 */
final class SafPublishTransport implements PublishTransport {
    static final String STAGING_NAME = "\\.cbtmp-[0-9a-f]{32}\\.tmp", OLD_NAME = "\\.cbold-[0-9a-f]{32}\\.tmp";
    final SafTransport saf; final Uri root; final android.content.Context context;

    SafPublishTransport(android.content.Context context, ContentResolver resolver, Uri tree) {
        this.context = context; saf = new SafTransport(resolver, tree);
        root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
    }

    static String parent(String path) { int i = path.lastIndexOf('/'); return i <= 0 ? "/" : path.substring(0, i); }
    static String leaf(String path) { return path.substring(path.lastIndexOf('/') + 1); }

    /** Exact name, else the single case-insensitive match (Dropbox/Windows semantics: an existing "devices" serves "Devices"
     *  and mkdir does not create a second folder). Two case-variants of one name are refused. */
    static Uri child(java.util.Map<String, Uri> children, String name) throws IOException {
        Uri exact = children.get(name); if (exact != null) return exact;
        Uri folded = null; for (java.util.Map.Entry<String, Uri> e : children.entrySet()) if (e.getKey().equalsIgnoreCase(name)) { if (folded != null) throw new IOException("SAF_AMBIGUOUS_CASE"); folded = e.getValue(); }
        return folded;
    }
    /** Name walk from the tree root (see child); null when any part is missing. */
    Uri locate(String path) throws IOException {
        String logical = WritePolicy.logical(new File(path)); WritePolicy.readable(logical);
        Uri current = root;
        if (logical.equals("/")) return current;
        for (String part : logical.substring(1).split("/")) { current = child(saf.children(current), part); if (current == null) return null; }
        return current;
    }

    public byte[] optional(String path) throws IOException { Uri doc = locate(path); return doc == null ? null : saf.read(doc); }

    long modified(String path) throws IOException {
        Uri document = locate(path);
        if (document == null) return 0;
        String column = DocumentsContract.Document.COLUMN_LAST_MODIFIED;
        try (android.database.Cursor cursor = saf.resolver.query(document, new String[]{column}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    /** The private transfer record pins both temporary names before either rename. */
    void transferRegistration(String target, JSONObject transfer) throws Exception {
        if (!WritePolicy.devicePath(target)) throw new IOException("TAKEOVER_INVALID_TARGET");
        String token = transfer.getString("token");
        if (!token.matches("[0-9a-f]{32}")) throw new IOException("TAKEOVER_INVALID_TOKEN");
        String folder = parent(target);
        String stagePath = folder + "/.cbtmp-" + token + ".tmp";
        String backupPath = folder + "/.cbold-" + token + ".tmp";
        byte[] payload = transfer.getString("payload").getBytes(WritePolicy.UTF8);
        byte[] expected = transfer.getString("previous").getBytes(WritePolicy.UTF8);
        byte[] current = optional(target);
        if (!same(current, payload)) {
            Uri backup = locate(backupPath);
            if (!same(current, expected) && backup == null) {
                removeTransferFile(stagePath, payload);
                throw new TakeoverStorage.Changed();
            }
            if (backup != null && (current != null || !same(saf.read(backup), expected))) {
                throw new IOException("TAKEOVER_FOLDER_RECOVERY_REQUIRED");
            }
            Uri staged = locate(stagePath);
            if (staged == null) staged = stage(locate(folder), leaf(stagePath), payload);
            if (!same(saf.read(staged), payload)) throw new IOException("TAKEOVER_STAGING_CHANGED");
            WriteFault.hit("after_staging");
            if (backup == null) {
                Uri source = locate(target);
                if (source == null || !same(saf.read(source), expected)) {
                    removeTransferFile(stagePath, payload);
                    throw new TakeoverStorage.Changed();
                }
                backup = saf.rename(source, leaf(backupPath), false);
                if (!same(saf.read(backup), expected)) {
                    saf.rename(backup, leaf(target), false);
                    removeTransferFile(stagePath, payload);
                    throw new TakeoverStorage.Changed();
                }
            }
            // A crash while the visible file is absent resumes from the verified backup.
            WriteFault.hit("takeover_after_backup");
            saf.rename(staged, leaf(target), false);
        }
        WriteFault.hit("after_move");
        if (!same(optional(target), payload)) throw new IOException("TAKEOVER_REPLACE_NOT_CONFIRMED");
        removeTransferFile(backupPath, expected);
        removeTransferFile(stagePath, payload);
    }

    private void removeTransferFile(String path, byte[] expected) throws IOException {
        Uri file = locate(path);
        if (file == null) return;
        if (!same(saf.read(file), expected)) throw new IOException("TAKEOVER_TEMP_CHANGED");
        if (!DocumentsContract.deleteDocument(saf.resolver, file)) throw new IOException("TAKEOVER_TEMP_DELETE_FAILED");
    }

    public String stagingFor(String target) { return parent(target) + "/.cbtmp-" + UUID.randomUUID().toString().replace("-", "") + ".tmp"; }

    void stagingPath(String target, String stage) throws IOException {
        if (!parent(stage).equals(parent(target)) || !leaf(stage).matches(STAGING_NAME)) throw new IOException("INVALID_STAGING_PATH");
    }

    String ownGuid() throws IOException { return WritePolicy.ownGuid(context); }

    /** Only this device's own diff directory may be created. */
    public void directory(String path) throws IOException {
        if (!path.equals(WritePolicy.DATA + "/" + ownGuid())) throw new IOException("MKDIR_NOT_ALLOWED");
        if (locate(path) != null) return;
        Uri parent = locate(parent(path)); if (parent == null) throw new FileNotFoundException("SAF_PARENT_MISSING");
        Uri made = DocumentsContract.createDocument(saf.resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, leaf(path));
        if (made != null && !leaf(path).equals(saf.name(made))) {
            // A concurrent creator won (" (1)" on this provider): our fresh empty copy goes.
            if (saf.children(made).isEmpty()) DocumentsContract.deleteDocument(saf.resolver, made);
        }
        if (locate(path) == null) throw new IOException("MKDIR_NOT_CONFIRMED");
    }

    static boolean same(byte[] a, byte[] b) { return a != null && Arrays.equals(a, b); }

    Uri stage(Uri dir, String name, byte[] payload) throws IOException {
        Uri doc = DocumentsContract.createDocument(saf.resolver, dir, "application/octet-stream", name);
        if (doc == null) throw new IOException("STAGING_CREATE_NULL");
        if (!name.equals(saf.name(doc))) { DocumentsContract.deleteDocument(saf.resolver, doc); throw new IOException("STAGING_NAME_CHANGED_BY_PROVIDER"); }
        ParcelFileDescriptor fd = saf.resolver.openFileDescriptor(doc, "w"); if (fd == null) throw new IOException("FD_NULL");
        FileOutputStream out = new FileOutputStream(fd.getFileDescriptor());
        try { out.write(payload); out.flush(); fd.getFileDescriptor().sync(); } finally { out.close(); fd.close(); }
        return doc;
    }

    public boolean publish(JSONObject entry, boolean replace) throws IOException {
        try {
            String target = entry.getString("path"), stage = entry.getString("staging");
            byte[] payload = entry.getString("payload").getBytes(WritePolicy.UTF8);
            if (payload.length > WritePolicy.MAX_BYTES) throw new IOException("WRITE_PAYLOAD_SIZE_LIMIT");
            stagingPath(target, stage);
            Uri dir = locate(parent(target)); if (dir == null) throw new FileNotFoundException("SAF_PARENT_MISSING");
            byte[] existing = optional(target);
            if (same(existing, payload)) {
                try { removeStaging(entry); } catch (IOException keep) { /* partial staging stays as evidence */ }
                return true;
            }
            Uri staged = locate(stage);
            if (staged == null) staged = stage(dir, leaf(stage), payload);
            byte[] verified = saf.read(staged);
            if (!same(verified, payload)) throw new StagingMismatch(verified);
            WriteFault.hit("after_staging");
            if (existing != null && !replace) return false;
            if (existing == null) {
                try { saf.rename(staged, leaf(target), true); }
                catch (SafTransport.Collision collision) {
                    // Someone else's file took the name between our check and rename.
                    saf.discard(staged);
                    if (same(optional(target), payload)) return true;
                    if (!replace) return false;
                    throw new IOException("SAF_TARGET_APPEARED", collision);
                } catch (SafTransport.Uncertain uncertain) {
                    if (same(optional(target), payload) && locate(stage) == null) return true;
                    throw uncertain;
                }
            } else {
                // Own registration: swap through .cbold, never truncate the visible file.
                Uri current = locate(target);
                Uri old = saf.rename(current, ".cbold-" + UUID.randomUUID().toString().replace("-", "") + ".tmp", false);
                try { saf.rename(staged, leaf(target), true); }
                catch (IOException error) {
                    if (error instanceof SafTransport.Collision) saf.discard(staged);
                    try { saf.rename(old, leaf(target), false); } catch (IOException restore) { throw new SafTransport.Uncertain("REPLACE_AND_RESTORE_FAILED", restore); }
                    throw error;
                }
            }
            WriteFault.hit("after_move");
            if (!same(optional(target), payload) || locate(stage) != null) throw new IOException("SAF_PUBLISH_NOT_CONFIRMED");
            if (WritePolicy.devicePath(target)) removeOwnOldRegistrations(dir, target);
            return true;
        } catch (JSONException error) { throw new IOException("INVALID_OUTBOX_ENTRY", error); }
    }

    /** A crash between the two renames of a swap leaves .cbold; delete it only if it is this device's own registration. */
    void removeOwnOldRegistrations(Uri dir, String target) throws IOException {
        String guid = ownGuid();
        for (Map.Entry<String, Uri> child : saf.children(dir).entrySet()) {
            if (!child.getKey().matches(OLD_NAME)) continue;
            try { WritePolicy.device(target, new String(saf.read(child.getValue()), WritePolicy.UTF8), guid); }
            catch (IOException foreign) { continue; }
            DocumentsContract.deleteDocument(saf.resolver, child.getValue());
        }
    }

    public void removeStaging(JSONObject entry) throws IOException {
        try {
            String stage = entry.getString("staging"); stagingPath(entry.getString("path"), stage);
            Uri doc = locate(stage); if (doc == null) return;
            if (!WritePolicy.sha(saf.read(doc)).equals(entry.getString("sha256"))) throw new IOException("STAGING_DELETE_HASH_MISMATCH");
            if (!DocumentsContract.deleteDocument(saf.resolver, doc)) throw new IOException("STAGING_DELETE_FAILED");
        } catch (JSONException error) { throw new IOException(error); }
    }
}

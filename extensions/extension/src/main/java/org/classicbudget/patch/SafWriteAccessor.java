package org.classicbudget.patch;

import android.content.*;
import android.net.Uri;
import java.io.*;
import java.util.*;
import org.json.*;
import com.youneedabudget.ynab.core.backend.i;
import com.youneedabudget.ynab.core.cloud.z;

/**
 * Folder backend for the original engine: reads through the narrow SAF reader,
 * writes only this device's registration and diffs through the shared journal
 * (same rules and recovery as the WebDAV writer, see WriteAccessor).
 */
public final class SafWriteAccessor implements z, TakeoverStorage {
    final Context context; final SafReadAccessor reader; final SafPublishTransport writer; final WriteJournal journal;

    public SafWriteAccessor(Context context, Uri tree) throws IOException {
        this.context = context;
        reader = new SafReadAccessor(context, tree);
        if (!writeGranted(context, tree)) throw new IOException("SAF_WRITE_GRANT_REQUIRED");
        journal = new WriteJournal(context, "saf:" + tree);
        writer = new SafPublishTransport(context, context.getContentResolver(), tree);
        journal.transport = writer;
        // Budgets of this folder (yroot) become readable; the write scope follows the path's budget.
        scope = new BudgetScope(new BudgetScope.Source() { public byte[] read(String p) throws IOException {
            synchronized (WritePolicy.KNOWN) { LocalReadAccessor.KNOWN.addAll(WritePolicy.KNOWN); }   // the reader's own path guard
            return reader.bytes(new File(p)); } });
    }
    final BudgetScope scope;

    static boolean writeGranted(Context context, Uri tree) {
        for (UriPermission p : context.getContentResolver().getPersistedUriPermissions()) if (tree.equals(p.getUri()) && p.isWritePermission()) return true;
        return false;
    }

    String path(File file) throws IOException { String p = scope.path(WritePolicy.logical(file)); knownToReader(); return p; }
    /** yroot budgets loaded by the scope must also pass the reader's path guard. Without this, after a process
     *  restart a folder with 2+ budgets rejected all but the synthetic one (LOCAL_SYNTHETIC_ONLY): the reader set
     *  was copied before the scope had read the yroot. */
    void load() throws IOException { scope.load(); knownToReader(); }
    static void knownToReader() { synchronized (WritePolicy.KNOWN) { LocalReadAccessor.KNOWN.addAll(WritePolicy.KNOWN); } }
    String guid() throws IOException { return WritePolicy.ownGuid(context); }

    public synchronized String takeoverProfile(File device) throws Exception {
        path(device);
        return journal.idleProfile();
    }

    public synchronized long registrationModified(File device) throws IOException {
        return writer.modified(path(device));
    }

    public synchronized byte[] registrationBytes(File device) throws IOException {
        return writer.optional(path(device));
    }

    public synchronized void replaceRegistration(File device, JSONObject transfer) throws Exception {
        journal.idleProfile();
        writer.transferRegistration(path(device), transfer);
    }
    String shortId() throws IOException {
        String value = context.getSharedPreferences("write-device", 0).getString(WritePolicy.shortIdKey(), null);
        if (!WritePolicy.shortId(value)) { value = WritePolicy.recoverShortId(writer, guid()); if (value == null) throw new IOException("OWN_DEVICE_NOT_REGISTERED"); remember(value); }
        return value;
    }
    void remember(String value) throws IOException { if (!context.getSharedPreferences("write-device", 0).edit().putString(WritePolicy.shortIdKey(), value).commit()) throw new IOException("DEVICE_PREFS_COMMIT_FAILED"); }
    boolean publish(JSONObject entry, boolean replace) throws IOException {
        while (true) try { return writer.publish(entry, replace); }
        catch (PublishTransport.StagingMismatch mismatch) { journal.rotateStaging(entry, mismatch.observedHash); }
    }

    /** New registration (core/cloud/r): false result hands the collision back to the original ID choice. */
    public synchronized File a(File file, String text) throws IOException {
        journal.healthy();
        String path = path(file), shortId = WritePolicy.device(path, text, guid());
        WriteFault.awaitRegistration(path);
        JSONObject entry = journal.begin(path, text, "device");
        if (publish(entry, false)) { journal.published(entry); remember(shortId); journal.forget(path); return new File(path); }
        try { return new File(entry.getString("staging")); } catch (JSONException error) { throw new IOException(error); }
    }

    public synchronized void b(File file, String text) throws IOException {
        journal.healthy();
        String path = path(file), mode;
        if (WritePolicy.devicePath(path)) {
            String id = WritePolicy.device(path, text, guid()); if (!shortId().equals(id)) throw new IOException("FOREIGN_SHORT_DEVICE_ID");
            byte[] old = writer.optional(path); if (old == null) throw new IOException("REGISTERED_DEVICE_DISAPPEARED");
            String oldText = new String(old, WritePolicy.UTF8); WritePolicy.device(path, oldText, guid());
            try { if (!WritePolicy.dominates(WritePolicy.knowledge(new JSONObject(text).getString("knowledge")), WritePolicy.knowledge(new JSONObject(oldText).getString("knowledge")))) throw new IOException("DEVICE_KNOWLEDGE_REGRESSION"); }
            catch (JSONException error) { throw new IOException(error); }
            JSONObject prior = journal.entry(path);
            if (prior != null && prior.optBoolean("published") && !WritePolicy.equivalent(prior.optString("payload"), text, false)) journal.forget(path);
            mode = "device";
        } else { WritePolicy.diff(path, text, guid(), shortId()); writer.directory(WritePolicy.DATA + "/" + guid()); mode = "diff"; }
        JSONObject entry = journal.begin(path, text, mode);
        if (!publish(entry, mode.equals("device"))) throw new IOException("IMMUTABLE_DIFF_COLLISION");
        journal.published(entry);
        if (mode.equals("device") && journal.singlePendingDiff() == null) journal.forget(path);
    }

    byte[] read(File file) throws IOException {
        String path = path(file); byte[] data = reader.bytes(file);
        return WriteRecovery.readOwnMetadata(context, journal, writer, path, data);
    }
    public String a(File file) throws IOException { return new String(read(file), WritePolicy.UTF8); }
    public InputStream b(File file) throws IOException { return new ByteArrayInputStream(read(file)); }
    public i c(File file) throws IOException { load(); return reader.c(file); }
    public List<File> c(File file, String suffix) throws IOException { load(); return reader.c(file, suffix); }
    public List<i> d(File file, String suffix) throws IOException { load(); return reader.d(file, suffix); }

    /** Only our own unpublished collision staging (returned by a(File,String)) may be deleted. */
    public synchronized void d(File file) throws IOException {
        String path = path(file); JSONObject entry = journal.byStaging(path);
        if (entry == null || entry.optBoolean("published")) throw new IOException("DELETE_NOT_OWN_COLLISION_STAGING");
        writer.removeStaging(entry); journal.forget(entry.optString("path"));
    }
}

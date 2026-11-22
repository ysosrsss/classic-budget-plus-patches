package org.classicbudget.patch;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Lists conflict files for the selected SAF budget. A byte-identical duplicate can be kept as a backup. */
final class FolderConflictReview {
    private static final String MARK = ".sync-conflict-";
    private static final int MAX_DIRS = 256;
    private static final long MAX_COMPARE = 8L * 1024 * 1024;

    static final class Entry {
        final String folder, name, original;
        final boolean duplicate;

        Entry(String folder, String name, String original, boolean duplicate) {
            this.folder = folder;
            this.name = name;
            this.original = original;
            this.duplicate = duplicate;
        }
    }

    private final Context context;
    private final Uri tree, data;
    private final SafTransport saf;

    private FolderConflictReview(Context context, String id) throws Exception {
        this.context = context;
        if (!BackendSelector.FOLDER.equals(BackendSelector.chosen())) throw new IOException("NOT_FOLDER");
        tree = FolderProvider.tree();
        saf = new SafTransport(context.getContentResolver(), tree);
        String budget;
        String directory;
        try (SQLiteDatabase db = ConflictResolution.read(context, id)) {
            budget = value(db, "budgetFolder");
            directory = value(db, "dataFolder");
        }
        if (!WritePolicy.budgetName(budget) || !WritePolicy.dataName(directory)) throw new IOException("FOLDER_BUDGET");
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri budgetDir = unique(saf.children(root), budget.substring(1));
        data = budgetDir == null ? null : unique(saf.children(budgetDir), directory);
        if (data == null) throw new IOException("FOLDER_DATA_MISSING");
    }

    private static String value(SQLiteDatabase db, String key) {
        try (Cursor cursor = db.rawQuery("SELECT value FROM keyValue WHERE name=?", new String[]{key})) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    static List<Entry> scan(Context context, String id) throws Exception {
        FolderConflictReview review = new FolderConflictReview(context, id);
        List<Entry> result = new ArrayList<>();
        Map<String, Uri> directories = review.saf.children(review.data);
        review.scanDirectory("", directories, result);
        int count = 0;
        for (Map.Entry<String, Uri> child : directories.entrySet()) {
            String name = child.getKey();
            if (!"Devices".equalsIgnoreCase(name) && !name.matches("[a-zA-Z0-9-]{8,80}")) continue;
            if (++count > MAX_DIRS || result.size() >= 100) throw new IOException("FOLDER_REVIEW_LIMIT");
            review.scanDirectory(name, review.saf.children(child.getValue()), result);
        }
        result.sort(java.util.Comparator.comparing(entry -> entry.folder + "/" + entry.name));
        return result;
    }

    private void scanDirectory(String folder, Map<String, Uri> files, List<Entry> result) throws Exception {
        for (String name : files.keySet()) {
            String original = originalName(name);
            if (original == null) continue;
            Uri previous = unique(files, original);
            boolean canCompare = previous != null && !original.toLowerCase(Locale.ROOT).endsWith(".yfull");
            boolean identical = canCompare && sameFiles(files.get(name), previous);
            result.add(new Entry(folder, name, original, identical));
            if (result.size() >= 100) throw new IOException("FOLDER_REVIEW_LIMIT");
        }
    }

    private static Uri unique(Map<String, Uri> files, String name) throws IOException {
        Uri match = null;
        for (Map.Entry<String, Uri> entry : files.entrySet()) {
            if (!name.equalsIgnoreCase(entry.getKey())) continue;
            if (match != null) throw new IOException("FOLDER_AMBIGUOUS_NAME");
            match = entry.getValue();
        }
        return match;
    }

    static String originalName(String name) {
        String folded = name.toLowerCase(Locale.ROOT);
        int marker = folded.indexOf(MARK);
        int extension = folded.lastIndexOf('.');
        if (marker <= 0 || extension <= marker + MARK.length()) return null;
        String type = folded.substring(extension);
        if (!type.equals(".ydiff") && !type.equals(".ydevice") && !type.equals(".yfull")) return null;
        return name.substring(0, marker) + name.substring(extension);
    }

    /** The original stays in place. Revalidate both bytes immediately before moving the copy. */
    static void archiveDuplicate(Context context, String id, Entry shown) throws Exception {
        if (!shown.duplicate || !id.equals(SyncHealth.selected(context))) throw new IOException("FOLDER_REVIEW_CHANGED");
        FolderConflictReview review = new FolderConflictReview(context, id);
        Uri dir = shown.folder.isEmpty() ? review.data : unique(review.saf.children(review.data), shown.folder);
        if (dir == null) throw new IOException("FOLDER_REVIEW_CHANGED");
        Map<String, Uri> files = review.saf.children(dir);
        Uri conflict = unique(files, shown.name);
        Uri original = unique(files, shown.original);
        if (conflict == null || original == null || !review.sameFiles(conflict, original))
            throw new IOException("FOLDER_REVIEW_CHANGED");
        String backup = "cb-kept-conflict-" + UUID.randomUUID().toString() + ".backup";
        Uri moved = review.saf.rename(conflict, backup, false);
        if (!review.sameFiles(moved, original)) {
            // Keep any concurrently changed bytes. Return the copy to active review whenever possible.
            if (unique(review.saf.children(dir), shown.name) == null)
                review.saf.rename(moved, shown.name, false);
            throw new IOException("FOLDER_REVIEW_CHANGED");
        }
    }

    private boolean sameFiles(Uri first, Uri second) {
        try {
            return MessageDigest.isEqual(digest(first), digest(second));
        } catch (Exception unreadableOrTooLarge) {
            return false;
        }
    }

    private byte[] digest(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("FOLDER_REVIEW_READ");
            byte[] buffer = new byte[8192];
            long length = 0;
            for (int read; (read = in.read(buffer)) != -1;) {
                length += read;
                if (length > MAX_COMPARE) throw new IOException("FOLDER_REVIEW_SIZE");
                digest.update(buffer, 0, read);
            }
        }
        return digest.digest();
    }
}

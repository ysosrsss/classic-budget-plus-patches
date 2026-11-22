package org.classicbudget.patch;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Budget-cell notes and month entities seen in the budget files.
 *
 * The original Android app drops the "note" of monthlyCategoryBudget items, but YNAB 4 desktop
 * overwrites the note with whatever an incoming item carries (null clears it). Before the phone may
 * change a budgeted amount it must therefore know the note of exactly the version it is replacing.
 * Every file the original importer reads passes through tee(); a full rebuild reads all files once.
 * The index keeps every (entityId, version) -> note pair, so the order of reading does not matter.
 * Month entities (MB/yyyy-MM) are recorded too: desktop requires the parent month to exist.
 */
public final class BudgetNotes {
    static final String NAME = "cb-budget-notes.db";
    static SQLiteDatabase open;

    static synchronized SQLiteDatabase db(Context context) {
        if (open == null || !open.isOpen()) {
            File path = context.getDatabasePath(NAME);
            path.getParentFile().mkdirs();
            open = SQLiteDatabase.openOrCreateDatabase(path, null);
            open.execSQL("CREATE TABLE IF NOT EXISTS notes(entity TEXT NOT NULL, version TEXT NOT NULL, note TEXT, PRIMARY KEY(entity, version))");
            // Months per budget data folder: a month existing in one budget says nothing about another.
            open.execSQL("CREATE TABLE IF NOT EXISTS budgetMonths(budget TEXT NOT NULL, entity TEXT NOT NULL, PRIMARY KEY(budget, entity))");
            // .ydiff files never change once published: a file read once is not read again.
            open.execSQL("CREATE TABLE IF NOT EXISTS scannedFiles(budget TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(budget, name))");
        }
        return open;
    }

    static Context app() {
        try { return com.youneedabudget.ynab.core.app.d.i(); } catch (Throwable error) { return null; }
    }

    /** Hook at the start of the original importer (full file and diffs). Never changes what it reads. */
    public static Reader tee(Reader in) throws IOException {
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[16384];
        int n;
        while ((n = in.read(buffer)) != -1) text.append(buffer, 0, n);
        in.close();
        String content = text.toString();
        try {
            Context context = app();
            if (context != null) scan(db(context), content, null);
        } catch (Throwable error) {
            // The index is an extra; it must never break the original import.
            android.util.Log.w("ClassicBudgetPlus", "BudgetNotes.scan", error);
        }
        return new StringReader(content);
    }

    /** Records notes and months from one .ydiff or Budget.yfull text. Returns the number of budget cells seen. */
    /**
     * budget: data folder of the file when the caller knows it (index rebuild). Diffs name their own
     * budgetDataGUID; a Budget.yfull read by the original importer does not, so its months are only
     * recorded by the rebuild.
     */
    static int scan(SQLiteDatabase db, String content, String budget) throws Exception {
        if (!content.contains("monthlyCategoryBudget") && !content.contains("monthlyBudget")) return 0;
        JSONObject root = new JSONObject(content);
        if (root.has("budgetDataGUID")) budget = root.optString("budgetDataGUID", budget);
        int cells = 0;
        db.beginTransaction();
        try {
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    String type = item.optString("entityType");
                    if ("monthlyCategoryBudget".equals(type)) { cell(db, item, budget); cells++; }
                    if ("monthlyBudget".equals(type)) month(db, budget, item.optString("entityId"));
                }
            }
            JSONArray months = root.optJSONArray("monthlyBudgets");
            if (months != null) {
                for (int i = 0; i < months.length(); i++) {
                    JSONObject month = months.getJSONObject(i);
                    month(db, budget, month.optString("entityId"));
                    JSONArray subs = month.optJSONArray("monthlySubCategoryBudgets");
                    if (subs == null) continue;
                    for (int j = 0; j < subs.length(); j++) { cell(db, subs.getJSONObject(j), budget); cells++; }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return cells;
    }

    static void cell(SQLiteDatabase db, JSONObject item, String budget) {
        String entity = item.optString("entityId", null), version = item.optString("entityVersion", null);
        if (entity == null || version == null) return;
        ContentValues values = new ContentValues();
        values.put("entity", entity);
        values.put("version", version);
        if (item.isNull("note")) values.putNull("note"); else values.put("note", item.optString("note"));
        db.insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        String parent = item.optString("parentMonthlyBudgetId", null);
        if (parent != null) month(db, budget, parent);
    }

    static void month(SQLiteDatabase db, String budget, String entity) {
        if (budget == null || entity == null || !entity.matches("MB/[0-9]{4}-[0-9]{2}")) return;
        ContentValues values = new ContentValues();
        values.put("budget", budget);
        values.put("entity", entity);
        db.insertWithOnConflict("budgetMonths", null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    /** Result of a lookup: known=false means the note of that exact version was never seen. */
    public static final class Note { public boolean known; public String text; }

    public static Note lookup(Context context, String entity, String version) {
        Note result = new Note();
        try (Cursor c = db(context).rawQuery("SELECT note FROM notes WHERE entity=? AND version=?", new String[]{entity, version})) {
            if (c.moveToFirst()) { result.known = true; result.text = c.isNull(0) ? null : c.getString(0); }
        }
        return result;
    }

    public static boolean monthKnown(Context context, String budget, String month) {
        try (Cursor c = db(context).rawQuery("SELECT 1 FROM budgetMonths WHERE budget=? AND entity=?", new String[]{budget, "MB/" + month})) { return c.moveToFirst(); }
    }

    /** Our own edit: the note we send for the new version is the note we kept. */
    public static void record(Context context, String entity, String version, String note) {
        ContentValues values = new ContentValues();
        values.put("entity", entity);
        values.put("version", version);
        if (note == null) values.putNull("note"); else values.put("note", note);
        db(context).insertWithOnConflict("notes", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public interface Progress {
        /** stage: 0 = reading, 1 = retrying failed files. */
        void step(int done, int total, int stage);
        boolean cancelled();
    }

    public static final class Result {
        public int cells, read, known, remaining;
        public String cause;
    }

    /**
     * Reads the open budget's Budget.yfull files and the .ydiff files not read before, through the
     * original cloud accessor. On a network backend (Koofr/WebDAV, Dropbox) ~2000 reads in one go
     * could stall for hours on timeouts (phone, 13.11: stuck at 100% while retrying). Now: a file
     * read once is remembered, failed files get one short retry, and what is still missing is
     * reported so that the next refresh continues from there.
     */
    public static Result rebuild(Context context, String budgetFolder, String dataFolder, Progress progress) throws Exception {
        Object cloud = Class.forName("com.youneedabudget.ynab.core.app.d").getMethod("m").invoke(null);
        com.youneedabudget.ynab.core.cloud.z accessor = (com.youneedabudget.ynab.core.cloud.z) cloud.getClass().getMethod("a").invoke(cloud);
        File data = new File(budgetFolder.startsWith("/") ? budgetFolder : "/" + budgetFolder, dataFolder);
        List<com.youneedabudget.ynab.core.backend.i> folders = accessor.d(data, null);
        SQLiteDatabase db = db(context);
        String budget = budgetFolder + "/" + dataFolder;
        java.util.HashSet<String> scanned = new java.util.HashSet<String>();
        android.database.Cursor c = db.rawQuery("SELECT name FROM scannedFiles WHERE budget=?", new String[]{budget});
        try {
            while (c.moveToNext()) scanned.add(c.getString(0));
        } finally {
            c.close();
        }
        Result result = new Result();
        java.util.ArrayList<File> files = new java.util.ArrayList<File>();
        for (com.youneedabudget.ynab.core.backend.i folder : folders) {
            File dir = folder.c();
            if (dir == null || dir.getName().equalsIgnoreCase("devices")) continue;
            for (File f : accessor.c(dir, null)) {
                if (f.getName().equals("Budget.yfull")) files.add(f);  // rewritten in place: always read
                else if (f.getName().endsWith(".ydiff")) {
                    if (scanned.contains(dir.getName() + "/" + f.getName())) result.known++;
                    else files.add(f);
                }
            }
        }
        java.util.ArrayList<File> pending = files;
        Exception firstError = null;
        // Koofr/WebDAV: a short pause between reads, so a first full read (~2000 files) does not hit the
        // server's request limit, which would also stop the normal sync (DavProvider blocks after 429).
        boolean network = false;
        try {
            network = BackendSelector.WEBDAV.equals(BackendSelector.chosen());
        } catch (Throwable notInstalled) {
            network = false;
        }
        for (int round = 0; round < 2 && !pending.isEmpty(); round++) {
            if (round > 0) Thread.sleep(3000L);
            java.util.ArrayList<File> failedNow = new java.util.ArrayList<File>();
            for (int n = 0; n < pending.size(); n++) {
                if (progress != null && progress.cancelled()) {
                    failedNow.addAll(pending.subList(n, pending.size()));
                    break;
                }
                File f = pending.get(n);
                if (network && DavProvider.authenticationBlocked()) {
                    failedNow.addAll(pending.subList(n, pending.size()));
                    if (firstError == null) firstError = new IOException("WEBDAV_LOGIN_BLOCKED");
                    break;
                }
                if (network && n > 0) Thread.sleep(40);
                try {
                    result.cells += scan(db, accessor.a(f), dataFolder);
                    result.read++;
                    if (f.getName().endsWith(".ydiff")) {
                        android.content.ContentValues values = new android.content.ContentValues();
                        values.put("budget", budget);
                        values.put("name", f.getParentFile().getName() + "/" + f.getName());
                        db.insertWithOnConflict("scannedFiles", null, values, SQLiteDatabase.CONFLICT_IGNORE);
                    }
                } catch (Exception error) {
                    failedNow.add(f);
                    if (firstError == null) firstError = error;
                    android.util.Log.w("ClassicBudgetPlus", "BudgetNotes.rebuild round " + round + " " + f.getName(), error);
                }
                if (progress != null) progress.step(n + 1, pending.size(), round);
            }
            pending = failedNow;
            if (progress != null && progress.cancelled()) break;
        }
        result.remaining = pending.size();
        if (firstError != null && result.remaining > 0)
            result.cause = firstError.getClass().getSimpleName() + (firstError.getMessage() == null ? "" : ": " + firstError.getMessage());
        android.util.Log.i("ClassicBudgetPlus", "BudgetNotes.rebuild read=" + result.read + " known=" + result.known + " remaining=" + result.remaining + " cells=" + result.cells);
        return result;
    }
}

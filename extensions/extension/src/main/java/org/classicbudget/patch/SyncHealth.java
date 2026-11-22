package org.classicbudget.patch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.preference.PreferenceManager;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Budget-scoped observations from the original diff exporter and sync result. */
public final class SyncHealth {
    static final String PREFS = "cb-sync-health";
    static final ExecutorService READER = Executors.newSingleThreadExecutor();
    static final long PENDING_GRACE = TimeUnit.MINUTES.toMillis(5);
    private static final String RESULT = "com.youneedabudget.ynab.intent.extra.BACKGROUND_OP_RESULT";
    private static final String EXCEPTION = "com.youneedabudget.ynab.intent.extra.EXCEPTION";
    private static final String DROPBOX_RECONNECT = "dropboxReconnect";

    private SyncHealth() { }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String selected(Context context) {
        String id = PreferenceManager.getDefaultSharedPreferences(context).getString("lastBudget", null);
        return database(context, id) != null ? id : null;
    }

    private static File database(Context context, String id) {
        if (id == null || !id.endsWith("#dropbox") || id.indexOf('/') >= 0 || id.indexOf('\\') >= 0) return null;
        File file = context.getDatabasePath(id);
        return file.isFile() ? file : null;
    }

    private static String key(String backend, String id, String field) {
        return backend + ":" + id + ":" + field;
    }

    public static void beforeExport(Context context, String id) {
        try {
            if (database(context, id) == null) return;
            PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_EXPORT_STARTED);
            String backend = backend();
            Counts counts = counts(context, id);
            prefs(context).edit().putInt(key(backend, id, "before"), counts == null ? -1 : counts.total())
                .putLong(key(backend, id, "attempt"), System.currentTimeMillis()).apply();
        } catch (RuntimeException ignored) {
            // Diagnostics cannot interrupt the original exporter.
        }
    }

    public static void afterExport(Context context, String id, Throwable failure) {
        try {
            if (database(context, id) == null) return;
            PlusDiagnostics.record(context, failure == null ? PlusDiagnostics.Event.SYNC_EXPORT_OK
                : PlusDiagnostics.Event.SYNC_EXPORT_FAILED);
            String backend = backend();
            SharedPreferences state = prefs(context);
            SharedPreferences.Editor update = state.edit().putBoolean(key(backend, id, "sendFailed"), failure != null);
            if (failure == null && state.getInt(key(backend, id, "before"), -1) > 0) {
                Counts remaining = counts(context, id);
                if (remaining != null && remaining.total() == 0)
                    update.putLong(key(backend, id, "sendOk"), System.currentTimeMillis());
            }
            update.remove(key(backend, id, "before")).apply();
        } catch (RuntimeException ignored) {
            // Preserve the native outcome if local status storage fails.
        }
    }

    public static void finished(Context context, String id, Intent result) {
        try {
            if (database(context, id) == null || result == null) return;
            String backend = backend();
            SharedPreferences state = prefs(context);
            SharedPreferences.Editor update = state.edit();
            if (result.getBooleanExtra(RESULT, false)) {
                PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_OK);
                update.putLong(key(backend, id, "syncOk"), System.currentTimeMillis()).remove(key(backend, id, "problem"));
                if ("dropbox".equals(backend)) update.remove(DROPBOX_RECONNECT);
            } else {
                Object value = result.getSerializableExtra(EXCEPTION);
                String kind = problem(value instanceof Throwable ? (Throwable) value : null);
                if (kind == null) kind = state.getBoolean(key(backend, id, "sendFailed"), false) ? "send" : "receive";
                update.putString(key(backend, id, "problem"), kind);
                switch (kind) {
                    case "auth": PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_AUTH); break;
                    case "limit": PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_LIMIT); break;
                    case "files": PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_FILE_CONFLICT); break;
                    case "send": PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_SEND_FAILED); break;
                    default: PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_RECEIVE_FAILED); break;
                }
            }
            update.apply();
        } catch (RuntimeException ignored) {
            // The result still goes to the original app.
        }
    }

    /** Called immediately before the native service unlinks a rejected Dropbox token. */
    public static void dropboxRejected(Context context) {
        try {
            if ("dropbox".equals(backend())) {
                PlusDiagnostics.record(context, PlusDiagnostics.Event.SYNC_AUTH);
                prefs(context).edit().putBoolean(DROPBOX_RECONNECT, true).apply();
            }
        } catch (RuntimeException unavailable) {
            // Leave the original unlink and recovery flow intact.
        }
    }

    static boolean needsDropboxReconnect(Context context) {
        return "dropbox".equals(backend()) && prefs(context).getBoolean(DROPBOX_RECONNECT, false);
    }

    static String problem(Throwable error) {
        for (int depth = 0; error != null && depth < 8; depth++, error = error.getCause()) {
            if (error.getClass().getName().equals("com.dropbox.core.r")) return "auth";
            String message = error.getMessage();
            if (message == null) continue;
            if (message.contains("LOCAL_SYNC_CONFLICT_REQUIRES_REVIEW") || message.contains("SAF_SYNC_CONFLICT")) return "files";
            if (message.contains("AUTHENTICATION_STOPPED_HTTP_401")) return "auth";
            if (message.contains("AUTHENTICATION_STOPPED_HTTP_429")) return "limit";
            if ("AUTHENTICATION_STOPPED".equals(message)) {
                return DavProvider.authenticationStatus() == 429 ? "limit" : "auth";
            }
        }
        return null;
    }

    static Snapshot snapshot(Context context) {
        String id = selected(context);
        if (id == null) return null;
        String backend = backend();
        SharedPreferences state = prefs(context);
        Counts counts = counts(context, id);
        long now = System.currentTimeMillis();
        long since = state.getLong(key(backend, id, "pendingSince"), 0);
        if (counts != null) {
            if (counts.total() == 0 && since != 0) {
                state.edit().remove(key(backend, id, "pendingSince")).apply();
                since = 0;
            } else if (counts.total() > 0 && (since == 0 || since > now)) {
                since = now;
                state.edit().putLong(key(backend, id, "pendingSince"), since).apply();
            }
        }
        String problem = state.getString(key(backend, id, "problem"), null);
        try {
            if (problem == null && ConflictResolution.unresolved(context, id, ConflictStore.active(context, id)) > 0)
                problem = "conflict";
        } catch (Exception unreadable) {
            if (problem == null) problem = "conflictStore";
        }
        if (problem == null && counts != null && counts.total() > 0 && now - since >= PENDING_GRACE)
            problem = "waiting";
        return new Snapshot(id, backend, counts, problem, state.getLong(key(backend, id, "syncOk"), 0),
            state.getLong(key(backend, id, "sendOk"), 0), state.getLong(key(backend, id, "attempt"), 0));
    }

    private static String backend() {
        String backend = BackendSelector.chosen();
        if (BackendSelector.FOLDER.equals(backend)) return "folder";
        if (BackendSelector.WEBDAV.equals(backend)) return "webdav";
        return "dropbox";
    }

    static String backendName(String backend) {
        if ("folder".equals(backend)) return PlusLang.tr("Folder (Syncthing)", "Folder (Syncthing)");
        if ("webdav".equals(backend)) return "Koofr / WebDAV";
        return "Dropbox";
    }

    static String message(Snapshot status) {
        if (status == null || status.issue == null) return "";
        switch (status.issue) {
            case "files": return PlusLang.tr("Conflicting files in the sync folder. Review copies.",
                "Konflikt plików w folderze synchronizacji. Sprawdź kopie.");
            case "conflict": return PlusLang.tr("Different versions of a transaction. Review the conflict.",
                "Różne wersje transakcji. Sprawdź konflikt.");
            case "conflictStore": return PlusLang.tr("Saved conflict information needs review.",
                "Zapisane informacje o konfliktach wymagają sprawdzenia.");
            case "auth": return PlusLang.tr("The sync service needs a new sign-in.", "Usługa synchronizacji wymaga ponownego logowania.");
            case "limit": return PlusLang.tr("The sync service limited requests. Try again later.", "Usługa ograniczyła liczbę żądań. Spróbuj później.");
            case "send": return PlusLang.tr("Changes could not be sent. Check synchronization.", "Nie udało się wysłać zmian. Sprawdź synchronizację.");
            case "receive": return PlusLang.tr("Could not check for incoming changes.", "Nie udało się sprawdzić zmian z innych urządzeń.");
            default: return PlusLang.tr("Local changes still wait to sync.", "Zmiany na telefonie nadal czekają na synchronizację.");
        }
    }

    private static Counts counts(Context context, String id) {
        File file = database(context, id);
        if (file == null) return null;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            return new Counts(count(db, "txnTable"), count(db, "scheduledTxnTable"), count(db, "cbBudgetOutbox"),
                count(db, "payeeTable") + count(db, "payeeLocationsTable"));
        } catch (RuntimeException unavailable) {
            return null;
        }
    }

    private static int count(SQLiteDatabase db, String table) {
        try (Cursor schema = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?",
            new String[]{table})) {
            if (!schema.moveToFirst()) return 0;
        }
        try (Cursor rows = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE synced=0", null)) {
            rows.moveToFirst();
            return rows.getInt(0);
        }
    }

    /** Reuses the same native operation as pulling down on the overview. */
    static boolean retry(Context context, String id) {
        if (id == null || !id.equals(selected(context))) return false;
        try {
            Class<?> budgetType = Class.forName("com.youneedabudget.ynab.core.cloud.g");
            Object budget = budgetType.getMethod("d", String.class).invoke(null, id);
            Class<?> operation = Class.forName("com.youneedabudget.ynab.core.cloud.o$a");
            java.lang.reflect.Method sync = Class.forName("com.youneedabudget.ynab.core.cloud.o")
                .getDeclaredMethod("a", Context.class, budgetType, operation);
            sync.setAccessible(true);
            sync.invoke(null, context.getApplicationContext(), budget, operation.getField("a").get(null));
            return true;
        } catch (Exception unavailable) {
            return false;
        }
    }

    static final class Counts {
        final int transactions, schedules, budgeting, other;

        Counts(int transactions, int schedules, int budgeting, int other) {
            this.transactions = transactions;
            this.schedules = schedules;
            this.budgeting = budgeting;
            this.other = other;
        }

        int total() { return transactions + schedules + budgeting + other; }
    }

    static final class Snapshot {
        final String id, backend, issue;
        final Counts counts;
        final long synced, sent, attempted;

        Snapshot(String id, String backend, Counts counts, String issue, long synced, long sent, long attempted) {
            this.id = id;
            this.backend = backend;
            this.counts = counts;
            this.issue = issue;
            this.synced = synced;
            this.sent = sent;
            this.attempted = attempted;
        }
    }
}

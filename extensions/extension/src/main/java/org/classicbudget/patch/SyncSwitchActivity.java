package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.*;
import org.json.*;

/**
 * "Change sync method" without clearing the app data by hand.
 *
 * The profile lock (BackendSelector) exists because the local database (device ID,
 * knowledge, budget) belongs to one connection. Changing the method therefore means
 * the same as Android's "Clear data", except that Plus settings are kept:
 * - refused while anything is not sent yet (rows with synced=0, unpublished write
 *   journal entries, captured conflicts): that work would be lost;
 * - after confirmation SyncResetActivity (own process) removes the local budget,
 *   connection and original app state, keeps cb-plus-* settings, releases the folder
 *   permission and starts the app again, ready to choose a method.
 * The budget on the server/folder is not touched; the old connection keeps this
 * phone's registration file, as after a reinstall of the original app.
 */
public final class SyncSwitchActivity extends Activity {
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    /** Not sent yet: one line per reason; empty = safe to switch. */
    static java.util.List<String> pending(android.content.Context c) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        android.content.SharedPreferences transfer = c.getSharedPreferences(WritePolicy.DEVICE_TAKEOVER_PREFS, 0);
        if (transfer.contains("pending") || transfer.contains("pendingNew")) {
            out.add(tr("A device transfer is in progress.", "Trwa przejmowanie miejsca urządzenia."));
        }
        for (String name : c.databaseList()) {
            if (!name.contains("#") || name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")) continue;
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(c.getDatabasePath(name).getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try {
                    int n = 0;
                    for (String table : new String[]{"txnTable", "scheduledTxnTable", "payeeTable", "payeeLocationsTable", "cbBudgetOutbox"}) {
                        try { Cursor cur = db.rawQuery("SELECT COUNT(*) FROM " + table + " WHERE synced = 0", null); try { if (cur.moveToFirst()) n += cur.getInt(0); } finally { cur.close(); } }
                        catch (RuntimeException noColumn) { }
                    }
                    if (n > 0) out.add(tr("Changes not synced yet: ", "Niezsynchronizowane zmiany: ") + n);
                    try {
                        int unresolved = ConflictResolution.unresolved(c, name, ConflictStore.active(c, name));
                        if (unresolved > 0) out.add(tr("Unresolved conflicts: ", "Nierozwiązane konflikty: ") + unresolved);
                    } catch (Exception unreadable) {
                        out.add(tr("Saved conflicts could not be checked.", "Nie udało się sprawdzić zapisanych konfliktów."));
                    }
                } finally { db.close(); }
            } catch (RuntimeException unreadable) { out.add(tr("The local budget could not be checked.", "Nie udało się sprawdzić lokalnego budżetu.")); }
        }
        File ledger = new File(c.getFilesDir(), "write-ledger.json");
        if (ledger.exists()) try {
            JSONObject entries = new JSONObject(read(ledger)).optJSONObject("entries"); int n = 0;
            if (entries != null) for (java.util.Iterator<String> k = entries.keys(); k.hasNext();) { JSONObject e = entries.optJSONObject(k.next()); if (e != null && !e.optBoolean("published")) n++; }
            if (n > 0) out.add(tr("Files waiting to be sent: ", "Pliki czekające na wysłanie: ") + n);
        } catch (Exception broken) { out.add(tr("The send queue could not be read.", "Nie udało się odczytać kolejki wysyłania.")); }
        File conflicts = new File(c.getFilesDir(), "conflicts.json");
        if (conflicts.exists() && conflicts.length() > 2) out.add(tr("Unresolved sync conflicts are recorded.", "Są zapisane nierozwiązane konflikty synchronizacji."));
        return out;
    }

    static String read(File f) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        try (InputStream in = new FileInputStream(f)) { while ((n = in.read(b)) != -1) o.write(b, 0, n); }
        return new String(o.toByteArray(), "UTF-8");
    }

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Change sync method", "Zmień synchronizację"));
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); l.setPadding(pad, pad, pad, pad);
        String chosen = BackendSelector.chosen();
        TextView now = new TextView(this); now.setTextSize(17); now.setContentDescription("switch-current");
        now.setText(tr("Current: ", "Obecnie: ") + (chosen == null ? tr("not connected", "brak połączenia") : BackendChooserActivity.label(chosen)));
        l.addView(now);
        TextView info = new TextView(this);
        info.setText(tr("\nThis phone forgets its local copy of the budget and the current connection, then the app starts again and you choose a new sync method. "
            + "The budget itself on the server or in the folder is not changed. Plus settings (language, display, rules, reports) are kept.\n\n"
            + "Allowed only when everything has been sent.",
            "\nTelefon zapomni lokalną kopię budżetu i obecne połączenie, potem aplikacja uruchomi się od nowa i wybierzesz nowy sposób synchronizacji. "
            + "Sam budżet na serwerze ani w folderze się nie zmienia. Ustawienia Plus (język, wygląd, reguły, raporty) zostają.\n\n"
            + "Możliwe tylko wtedy, gdy wszystko zostało wysłane."));
        l.addView(info);
        final java.util.List<String> pending = pending(this);
        TextView status = new TextView(this); status.setTextSize(15); status.setContentDescription("switch-status");
        StringBuilder s = new StringBuilder("\n");
        if (pending.isEmpty()) s.append(tr("✓ Nothing is waiting to be sent.", "✓ Nic nie czeka na wysłanie."));
        else { s.append(tr("Not possible yet — first sync (open the budget with a connection):", "Jeszcze niemożliwe — najpierw zsynchronizuj (otwórz budżet przy połączeniu):")); for (String p : pending) s.append("\n• ").append(p); }
        status.setText(s); l.addView(status);
        Button go = new Button(this); go.setText(tr("Change sync method", "Zmień synchronizację")); go.setContentDescription("switch-go"); go.setEnabled(pending.isEmpty());
        go.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirm(); } });
        l.addView(go);
        ScrollView scroll = new ScrollView(this); scroll.addView(l); setContentView(scroll);
    }

    void confirm() {
        new AlertDialog.Builder(this).setTitle(tr("Change sync method?", "Zmienić synchronizację?"))
            .setMessage(tr("The local copy of the budget on this phone will be removed and the app restarts. Continue?", "Lokalna kopia budżetu na tym telefonie zostanie usunięta, a aplikacja uruchomi się ponownie. Kontynuować?"))
            .setPositiveButton(tr("Change", "Zmień"), new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                if (!pending(SyncSwitchActivity.this).isEmpty()) { recreate(); return; }   // re-check right before
                Intent reset = new Intent(SyncSwitchActivity.this, SyncResetActivity.class).putExtra("pid", android.os.Process.myPid());
                reset.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(reset);
                finishAffinity();
                android.os.Process.killProcess(android.os.Process.myPid());   // nothing of the old profile may write after this point
            } })
            .setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }
}

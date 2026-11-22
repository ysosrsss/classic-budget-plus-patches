package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.widget.*;
import java.io.*;
import org.json.*;

/** Pick the synchronized folder (read + write). TEST: only ClassicBudgetLab_TEST/<32 hex> with the synthetic budget. */
public final class FolderSetupActivity extends Activity {
    /** Language of our screens: English unless Polish is chosen in Plus settings (PlusLang). */
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    static final int PICK = 43; static final int FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    TextView status; Button choose;

    protected void onCreate(Bundle state) {
        super.onCreate(state); PlusEdition.mark(this); setTitle(PlusEdition.TEST_ONLY ? "Folder (Syncthing) TEST" : "Folder (Syncthing)");
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); content.setPadding(pad, pad, pad, pad);
        TextView intro = new TextView(this);
        intro.setText(tr("A folder synced e.g. by Syncthing-Fork. The app writes only this phone's registration and its own changes.\n\nTEST build: choose the long-named subfolder in ClassicBudgetLab_TEST with the synthetic budget. Do not choose a real budget.\n\nIn Syncthing add to ignore patterns: (?d).cbtmp-* and (?d).cbold-*", "Folder synchronizowany np. przez Syncthing-Fork. Aplikacja zapisuje tylko rejestrację tego telefonu i jego zmiany.\n\nWersja TEST: wybierz podfolder o długiej nazwie w ClassicBudgetLab_TEST ze sztucznym budżetem. Nie wybieraj prawdziwego budżetu.\n\nW Syncthingu dodaj do ignorowanych: (?d).cbtmp-* oraz (?d).cbold-*"));
        if (!PlusEdition.TEST_ONLY) intro.setText(tr("A folder synced e.g. by Syncthing-Fork, with the budget (.ynabSettings.yroot and the .ynab4 folder). The app writes only this phone's registration and its own changes.\n\nIn Syncthing add to ignore patterns: (?d).cbtmp-* and (?d).cbold-*",
            "Folder synchronizowany np. przez Syncthing-Fork, z budżetem (.ynabSettings.yroot i folder .ynab4). Aplikacja zapisuje tylko rejestrację tego telefonu i jego zmiany.\n\nW Syncthingu dodaj do ignorowanych: (?d).cbtmp-* oraz (?d).cbold-*"));
        content.addView(intro);
        choose = new Button(this); choose.setText(tr("Choose folder", "Wybierz folder")); choose.setContentDescription("folder-choose"); content.addView(choose);
        status = new TextView(this); status.setTextIsSelectable(true); content.addView(status);
        ScrollView scroll = new ScrollView(this); scroll.addView(content); setContentView(scroll);
        choose.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(FLAGS | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            if (PlusEdition.TEST_ONLY) intent.putExtra("android.provider.extra.INITIAL_URI", DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:ClassicBudgetLab_TEST"));
            startActivityForResult(intent, PICK);
        });
    }

    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); if (request != PICK) return;
        if (result != RESULT_OK || data == null || data.getData() == null) { status.setText(tr("No folder chosen. Nothing changed.", "Nie wybrano folderu. Niczego nie zmieniono.")); return; }
        final Uri selected = data.getData(); choose.setEnabled(false); status.setText(tr("Checking the folder…", "Sprawdzam folder…"));
        new Thread(() -> {
            String error = null; Uri tree = null; boolean taken = false;
            try {
                tree = SafReadAccessor.canonical(selected);
                getContentResolver().takePersistableUriPermission(tree, FLAGS); taken = true;
                SafReadAccessor reader = new SafReadAccessor(this, tree);
                String yroot;
                try { yroot = reader.a(new File("/.ynabSettings.yroot")); }
                catch (FileNotFoundException missing) { throw new IOException(tr("This folder has no .ynabSettings.yroot.", "W tym folderze brakuje .ynabSettings.yroot.")); }
                JSONArray budgets = new JSONObject(yroot).getJSONArray("relativeKnownBudgets");
                if (budgets.length() < 1) throw new IOException(tr("No budget is listed in this folder.", "W tym folderze nie ma żadnego budżetu."));
                for (int i = 0; i < budgets.length(); i++) {
                    String b = budgets.getString(i); if (!b.startsWith("/")) b = "/" + b;
                    if (!WritePolicy.budgetName(b)) throw new IOException(tr("Unexpected budget name in .ynabSettings.yroot.", "Nieoczekiwana nazwa budżetu w .ynabSettings.yroot."));
                    LocalReadAccessor.KNOWN.add(b);
                    JSONObject meta;
                    try { meta = new JSONObject(reader.a(new File(b + "/Budget.ymeta"))); }
                    catch (FileNotFoundException missing) { throw new IOException(tr("A listed budget has no Budget.ymeta (not synced yet?).", "Budżet z listy nie ma Budget.ymeta (jeszcze się nie zsynchronizował?).")); }
                    if (!WritePolicy.dataName(meta.getString("relativeDataFolderName"))) throw new IOException(tr("Invalid budget files.", "Niepoprawne pliki budżetu."));
                }
                if (!SafWriteAccessor.writeGranted(this, tree)) throw new IOException("SAF_WRITE_GRANT_REQUIRED");
                FolderProvider.save(tree);
            } catch (Exception failure) {
                DavProvider.diagnostic(failure); String detail = failure.getMessage();
                if ("SAF_FOLDER_NOT_ALLOWED".equals(detail)) error = tr("Choose a folder on the phone's storage or in Downloads.", "Wybierz folder w pamięci telefonu albo w Pobranych.");
                else if ("SAF_TEST_TREE_ONLY".equals(detail)) error = tr("Choose the long-named subfolder in ClassicBudgetLab_TEST.", "Wybierz podfolder o długiej nazwie w ClassicBudgetLab_TEST.");
                else if ("LOCAL_SYNC_CONFLICT_REQUIRES_REVIEW".equals(detail)) error = tr("The folder has Syncthing conflict copies (.sync-conflict). Keep them for review.", "W folderze są kopie konfliktowe Syncthinga (.sync-conflict). Zostaw je do sprawdzenia.");
                else if ("SAF_WRITE_GRANT_REQUIRED".equals(detail)) error = tr("The system did not grant lasting write access to the folder. Choose it again.", "System nie dał trwałego zapisu do folderu. Wybierz go ponownie.");
                else if (failure instanceof IOException && detail != null && !detail.matches("[A-Z0-9_]+")) error = detail;
                else error = tr("Could not link the folder (", "Nie udało się połączyć folderu (") + failure.getClass().getSimpleName() + ").";
                if (taken) try { getContentResolver().releasePersistableUriPermission(tree, FLAGS); } catch (Exception ignored) { }
                FolderProvider.configFile().delete();
            }
            final String message = error;
            runOnUiThread(() -> {
                choose.setEnabled(true);
                if (message == null) { setResult(RESULT_OK); finish(); }
                else { status.setText(message); new AlertDialog.Builder(this).setTitle(tr("Folder not linked", "Folder nie został połączony")).setMessage(message).setPositiveButton("OK", null).show(); }
            });
        }).start();
    }
}

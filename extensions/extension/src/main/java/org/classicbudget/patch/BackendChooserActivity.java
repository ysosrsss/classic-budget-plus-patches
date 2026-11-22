package org.classicbudget.patch;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

/** First step of linking: choose where this phone's budget is synchronized. */
public final class BackendChooserActivity extends Activity {
    /** Language of our screens: English unless Polish is chosen in Plus settings (PlusLang). */
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean alternate = BackendSelector.alternateBackends(this);
        setTitle(alternate ? tr("Sync", "Synchronizacja") : tr("Dropbox folder", "Folder Dropbox"));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); layout.setPadding(pad, pad, pad, pad);
        TextView intro = new TextView(this);
        String chosen = BackendSelector.chosen();
        if (DropboxProfile.pendingFolder(this) != null) {
            startActivity(SyncSwitchActivity.dropboxFolderIntent(this));
            finish();
            return;
        }
        boolean locked = BackendSelector.locked();
        intro.setText(locked
            ? tr("This phone already has a sync profile.", "Ten telefon ma już profil synchronizacji.")
            : tr("Choose where YNAB Plus should look for budgets.", "Wybierz, gdzie YNAB Plus ma szukać budżetów."));
        intro.setTextSize(17);
        layout.addView(intro);
        TextView registration = new TextView(this); registration.setContentDescription("sync-registration-notice");
        registration.setText(PlusEdition.TEST_ONLY
            ? tr("Connecting a budget can register this phone and upload device metadata before you edit a transaction. For this TEST, use a separate copy of the budget.", "Połączenie budżetu może zarejestrować ten telefon i wysłać metadane urządzenia, zanim zmienisz transakcję. W tym TEST użyj osobnej kopii budżetu.")
            : tr("Connecting a budget registers this phone in it (like the original app does with Dropbox).", "Połączenie budżetu rejestruje w nim ten telefon (tak jak oryginalna aplikacja przy Dropboxie)."));
        registration.setPadding(0, pad / 2, 0, pad / 2);
        if (PlusEdition.TEST_ONLY || alternate) layout.addView(registration);
        String current = DropboxProfile.folder();
        if (PlusEdition.TEST_ONLY) {
            TextView label = new TextView(this);
            label.setText(tr("Dropbox folder with the budget (.ynabSettings.yroot)", "Folder Dropboxa z budżetem (.ynabSettings.yroot)"));
            layout.addView(label);
            folder = new EditText(this);
            folder.setSingleLine(true);
            folder.setContentDescription("dropbox-folder");
            folder.setText(current != null ? current : DropboxProfile.TEST_ROOT != null ? DropboxProfile.TEST_ROOT : "/");
            folder.setEnabled(!locked);
            layout.addView(folder);
        } else {
            folderChoice = new DropboxFolderChoice(this, current, !locked, "dropbox-folder", state);
            layout.addView(folderChoice);
        }
        String signIn = alternate ? "Dropbox" : tr("Continue with Dropbox", "Przejdź do Dropboxa");
        add(layout, BackendSelector.DROPBOX, signIn, !locked || chosen == null || BackendSelector.DROPBOX.equals(chosen), "backend-dropbox", false);
        if (alternate) {
            add(layout, BackendSelector.WEBDAV, "Koofr", !locked || BackendSelector.WEBDAV.equals(chosen), "backend-koofr", true);
            add(layout, BackendSelector.WEBDAV, tr("Other WebDAV (Nextcloud, NAS…)", "Inny WebDAV (Nextcloud, NAS…)"), !locked || BackendSelector.WEBDAV.equals(chosen), "backend-webdav", false);
            add(layout, BackendSelector.FOLDER, "Folder (Syncthing)", !locked || BackendSelector.FOLDER.equals(chosen), "backend-folder", false);
        }
        if (locked) {
            Button change = new Button(this);
            change.setText(alternate ? tr("Change sync method…", "Zmień synchronizację…") : tr("Change Dropbox folder…", "Zmień folder Dropbox…"));
            change.setContentDescription("chooser-switch-sync");
            change.setOnClickListener(v -> startActivity(alternate ? new Intent(this, SyncSwitchActivity.class) : SyncSwitchActivity.dropboxFolderIntent(this)));
            layout.addView(change);
        }
        TextView note = new TextView(this);
        note.setText(tr("\nSign in as usual. If Android asks which app to open, choose YNAB Plus.",
            "\nZaloguj się jak zwykle. Jeśli Android zapyta, którą aplikację otworzyć, wybierz YNAB Plus."));
        layout.addView(note);
        // Open without focus in the folder field: otherwise the keyboard covers the backend buttons.
        layout.setFocusableInTouchMode(true); layout.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS); layout.requestFocus();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
    }

    static String label(String kind) { return BackendSelector.DROPBOX.equals(kind) ? "Dropbox" : BackendSelector.FOLDER.equals(kind) ? "Folder (Syncthing)" : "WebDAV / Koofr"; }

    private void add(LinearLayout layout, final String kind, String text, boolean enabled, String desc, final boolean koofr) {
        Button button = new Button(this); button.setText(text); button.setEnabled(enabled);
        button.setContentDescription(desc);
        button.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pick(kind, koofr); } });
        layout.addView(button);
    }

    private boolean launched;
    private EditText folder;
    private DropboxFolderChoice folderChoice;

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (folderChoice != null) folderChoice.save(state);
    }

    protected void onResume() {
        super.onResume();
        // Back from the Dropbox login (or its browser): the setup screen continues.
        if (launched) { setResult(RESULT_OK); finish(); }
    }

    private void pick(String kind, boolean koofr) {
        try {
            if (BackendSelector.DROPBOX.equals(kind)) {
                String f = folderChoice == null ? DropboxProfile.normalize(folder.getText().toString()) : folderChoice.selectedFolder();
                if (f == null) {
                    String error = tr("Enter a valid Dropbox folder path.", "Wpisz poprawną ścieżkę folderu Dropbox.");
                    if (folderChoice == null) folder.setError(error);
                    else folderChoice.showError(error);
                    return;
                }
                if (!lockedFolderIsDefault(f)) DropboxProfile.chooseFolder(f);
            }
            BackendSelector.choose(kind);
            // Delegates to the backend's own link flow (Dropbox OAuth or WebDAV form).
            // Stay alive for Dropbox: the SDK may need this window for its security dialog.
            if (BackendSelector.WEBDAV.equals(kind)) startActivity(new Intent(this, WebDavSetupActivity.class).putExtra("koofr", koofr));   // Koofr form or any server
            else BackendSelector.create(kind).a(this);
            if (BackendSelector.DROPBOX.equals(kind)) { launched = true; return; }
            setResult(RESULT_OK); finish();
        } catch (Exception error) {
            Toast.makeText(this, tr("This profile's sync method cannot be changed", "Nie można zmienić sposobu synchronizacji tego profilu"), Toast.LENGTH_LONG).show();
        }
    }

    private boolean lockedFolderIsDefault(String folder) {
        // Legacy LAB profiles can have a compiled root and no stored folder.
        if (!BackendSelector.locked() || DropboxProfile.folder() != null) return false;
        return folder.equals(DropboxProfile.TEST_ROOT == null ? "/" : DropboxProfile.TEST_ROOT);
    }
}

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
        setTitle(tr("Sync", "Synchronizacja"));
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); layout.setPadding(pad, pad, pad, pad);
        TextView intro = new TextView(this);
        String chosen = BackendSelector.chosen();
        boolean locked = BackendSelector.locked() && chosen != null;
        intro.setText(locked
            ? tr("This app profile is already linked to: ", "Ten profil aplikacji jest już powiązany z: ") + label(chosen) + tr(".\n\nTo use another sync method, tap \"Change sync method\" below (it checks that everything was sent and keeps Plus settings).", ".\n\nAby użyć innego sposobu, stuknij niżej „Zmień synchronizację” (sprawdzi, że wszystko wysłano, i zachowa ustawienia Plus).")
            : tr("Choose where this phone syncs the budget. The choice applies to this phone only; once linked it belongs to the local budget.", "Wybierz, gdzie ten telefon synchronizuje budżet. Wybór dotyczy tylko tego telefonu; po połączeniu zostaje przypisany do lokalnego budżetu."));
        layout.addView(intro);
        TextView registration = new TextView(this); registration.setContentDescription("sync-registration-notice");
        registration.setText(PlusEdition.TEST_ONLY
            ? tr("Connecting a budget can register this phone and upload device metadata before you edit a transaction. For this TEST, use a separate copy of the budget.", "Połączenie budżetu może zarejestrować ten telefon i wysłać metadane urządzenia, zanim zmienisz transakcję. W tym TEST użyj osobnej kopii budżetu.")
            : tr("Connecting a budget registers this phone in it (like the original app does with Dropbox).", "Połączenie budżetu rejestruje w nim ten telefon (tak jak oryginalna aplikacja przy Dropboxie)."));
        registration.setPadding(0, pad / 2, 0, pad / 2); layout.addView(registration);
        TextView fl = new TextView(this); fl.setText(tr("Dropbox folder with the budget (.ynabSettings.yroot)", "Folder Dropboxa z budżetem (.ynabSettings.yroot)")); layout.addView(fl);
        folder = new EditText(this); folder.setSingleLine(true); folder.setContentDescription("dropbox-folder");
        String current = DropboxProfile.folder(); folder.setText(current != null ? current : DropboxProfile.TEST_ROOT != null ? DropboxProfile.TEST_ROOT : "/");
        folder.setEnabled(!locked); layout.addView(folder);
        add(layout, BackendSelector.DROPBOX, "Dropbox", !locked || BackendSelector.DROPBOX.equals(chosen), "backend-dropbox", false);
        add(layout, BackendSelector.WEBDAV, "Koofr", !locked || BackendSelector.WEBDAV.equals(chosen), "backend-koofr", true);
        add(layout, BackendSelector.WEBDAV, tr("Other WebDAV (Nextcloud, NAS…)", "Inny WebDAV (Nextcloud, NAS…)"), !locked || BackendSelector.WEBDAV.equals(chosen), "backend-webdav", false);
        add(layout, BackendSelector.FOLDER, "Folder (Syncthing)", !locked || BackendSelector.FOLDER.equals(chosen), "backend-folder", false);
        if (locked) {
            Button change = new Button(this); change.setText(tr("Change sync method…", "Zmień synchronizację…")); change.setContentDescription("chooser-switch-sync");
            change.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { startActivity(new Intent(BackendChooserActivity.this, SyncSwitchActivity.class)); } });
            layout.addView(change);
        }
        TextView note = new TextView(this);
        note.setText(tr("\nDropbox uses the app's original sign-in. If the regular YNAB is also installed, Android asks after sign-in which app to return to: choose this one.\n\nFolder: a folder synced e.g. by Syncthing-Fork, no cloud account.", "\nDropbox korzysta z oryginalnego logowania aplikacji. Jeśli na telefonie jest też zwykły YNAB, po zalogowaniu Android zapyta, którą aplikacją wrócić: wybierz tę.\n\nFolder: katalog synchronizowany np. przez Syncthing-Fork, bez konta w chmurze."));
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

    protected void onResume() {
        super.onResume();
        // Back from the Dropbox login (or its browser): the setup screen continues.
        if (launched) { setResult(RESULT_OK); finish(); }
    }

    private void pick(String kind, boolean koofr) {
        try {
            if (BackendSelector.DROPBOX.equals(kind)) {
                String f = folder.getText().toString().trim(); if (f.length() > 1 && f.endsWith("/")) f = f.substring(0, f.length() - 1);
                if (!DropboxProfile.folderAllowed(f)) { Toast.makeText(this, tr("This folder is not allowed in the TEST build (only inside /YNAB4_PLUS_TEST).", "Ten folder jest niedozwolony w wersji TEST (tylko wewnątrz /YNAB4_PLUS_TEST)."), Toast.LENGTH_LONG).show(); return; }
                DropboxProfile.chooseFolder(f);
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
}

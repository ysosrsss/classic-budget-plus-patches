package org.classicbudget.patch;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.io.*;
import org.json.*;

/**
 * WebDAV connection: any https server (Koofr, Nextcloud, ownCloud, a NAS…), a folder,
 * user and app password. TEST builds accept only folders inside YNAB4_PLUS_TEST.
 * The folder must already hold the budget (.ynabSettings.yroot), e.g. synced from the computer.
 */
public final class WebDavSetupActivity extends Activity {
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    static final String KOOFR = "https://app.koofr.net/dav/Koofr/";
    EditText server, folder, user, password; TextView status, serverLabel; Button connect; boolean koofr;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean reauthorize = getIntent().getBooleanExtra("reauthorize", false);
        JSONObject savedProfile = null;
        try { savedProfile = DavProvider.config(); } catch (Exception missing) { }
        if (reauthorize && (savedProfile == null || !savedProfile.has("server") || !savedProfile.has("folder"))) {
            Toast.makeText(this, tr("Could not open the current WebDAV profile.",
                "Nie udało się otworzyć bieżącego profilu WebDAV."), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        koofr = getIntent().getBooleanExtra("koofr", false);
        if (reauthorize) koofr = savedProfile.optString("server").startsWith(KOOFR);
        setTitle(koofr ? "Koofr" : "WebDAV");
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density); l.setPadding(pad, pad, pad, pad);
        TextView intro = new TextView(this);
        if (koofr) intro.setText(tr("Sync through Koofr. Enter your Koofr e-mail and an app password: Koofr → Settings → Passwords → Generate new password (works for this app only; you can revoke it any time).\n\nThe folder must contain the budget (.ynabSettings.yroot and the .ynab4 folder), e.g. synced there by the Koofr desktop app. This TEST build only accepts folders inside YNAB4_PLUS_TEST.",
            "Synchronizacja przez Koofr. Podaj e-mail do Koofra i hasło aplikacji: Koofr → Ustawienia → Hasła → Wygeneruj nowe hasło (działa tylko dla tej aplikacji, możesz je odwołać w każdej chwili).\n\nFolder musi zawierać budżet (.ynabSettings.yroot i folder .ynab4), np. zsynchronizowany programem Koofr na komputerze. Ta wersja TEST przyjmuje tylko foldery wewnątrz YNAB4_PLUS_TEST."));
        else intro.setText(tr("Sync through any WebDAV server: Koofr, Nextcloud, ownCloud, a NAS.\n\nThe folder must contain the budget (.ynabSettings.yroot and the .ynab4 folder), e.g. synced there from the computer. This TEST build only accepts folders inside YNAB4_PLUS_TEST.\n\nUse an app password, not your main password.",
            "Synchronizacja przez dowolny serwer WebDAV: Koofr, Nextcloud, ownCloud, NAS.\n\nFolder musi zawierać budżet (.ynabSettings.yroot i folder .ynab4), np. zsynchronizowany z komputera. Ta wersja TEST przyjmuje tylko foldery wewnątrz YNAB4_PLUS_TEST.\n\nUżyj hasła aplikacji, nie głównego hasła."));
        if (!PlusEdition.TEST_ONLY) intro.setText(intro.getText().toString()
            .replace(" This TEST build only accepts folders inside YNAB4_PLUS_TEST.", "")
            .replace(" Ta wersja TEST przyjmuje tylko foldery wewnątrz YNAB4_PLUS_TEST.", ""));
        l.addView(intro);
        server = field(l, tr("Server URL (https)", "Adres serwera (https)"), "https://cloud.example.com/remote.php/dav/files/me/", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, "webdav-server");
        serverLabel = lastLabel;
        user = field(l, koofr ? tr("Koofr e-mail", "E-mail do Koofra") : tr("User name / e-mail", "Użytkownik / e-mail"), "name@example.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, "webdav-user");
        password = field(l, tr("App password", "Hasło aplikacji"), tr("generated app password", "wygenerowane hasło aplikacji"), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, "webdav-password");
        folder = field(l, tr("Folder with the budget", "Folder z budżetem"), "YNAB4_PLUS_TEST/…", InputType.TYPE_CLASS_TEXT, "webdav-folder");
        TextView folderNote = new TextView(this); folderNote.setTextSize(13);
        folderNote.setText(tr("The folder that holds .ynabSettings.yroot, relative to the top of your " + (koofr ? "Koofr" : "server") + ". Prefilled because this TEST build only accepts YNAB4_PLUS_TEST or a folder inside it.",
            "Folder z plikiem .ynabSettings.yroot, licząc od głównego katalogu " + (koofr ? "Koofra" : "serwera") + ". Wpisany z góry, bo wersja TEST przyjmuje tylko YNAB4_PLUS_TEST albo folder w nim."));
        if (!PlusEdition.TEST_ONLY) {
            folderNote.setText(tr("The folder that holds .ynabSettings.yroot, relative to the top of your " + (koofr ? "Koofr" : "server") + ", e.g. YNAB.",
                "Folder z plikiem .ynabSettings.yroot, licząc od głównego katalogu " + (koofr ? "Koofra" : "serwera") + ", np. YNAB."));
            folder.setHint("YNAB");
        }
        l.addView(folderNote);
        server.setText(koofr ? KOOFR : ""); folder.setText(PlusEdition.TEST_ONLY ? "YNAB4_PLUS_TEST" : "");
        if (koofr) { server.setVisibility(View.GONE); serverLabel.setVisibility(View.GONE); }   // Koofr: address is fixed
        if (savedProfile != null) {
            String savedServer = savedProfile.optString("server");
            if (!savedServer.isEmpty() && koofr == savedServer.startsWith(KOOFR)) {
                server.setText(savedServer);
                folder.setText(savedProfile.optString("folder"));
            }
            user.setText(savedProfile.optString("username"));
        }
        if (reauthorize) {
            server.setEnabled(false);
            folder.setEnabled(false);
            user.setEnabled(false);
        }
        connect = new Button(this); connect.setText(tr("Connect", "Połącz")); connect.setContentDescription("webdav-connect"); l.addView(connect);
        status = new TextView(this); status.setTextIsSelectable(true); l.addView(status);
        ScrollView s = new ScrollView(this); s.addView(l); setContentView(s);
        connect.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { connect(); } });
    }

    TextView lastLabel;
    EditText field(LinearLayout l, String title, String hint, int type, String desc) {
        float dp = getResources().getDisplayMetrics().density;
        TextView label = new TextView(this); label.setText(title); label.setPadding(0, (int) (14 * dp), 0, (int) (4 * dp)); l.addView(label); lastLabel = label;
        // setSingleLine first: it resets the transformation, which would unmask a password field.
        EditText e = new EditText(this); e.setSingleLine(true); e.setInputType(type); e.setContentDescription(desc); e.setHint(hint);
        // A visible box: in the dark theme the default underline is hidden until the field has focus.
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(6 * dp); box.setStroke((int) Math.max(1, dp), 0xFF7F8C99); box.setColor(0x00000000);
        e.setBackground(box); int p = (int) (10 * dp); e.setPadding(p, p, p, p);
        if ((type & InputType.TYPE_TEXT_VARIATION_PASSWORD) == InputType.TYPE_TEXT_VARIATION_PASSWORD) e.setTransformationMethod(android.text.method.PasswordTransformationMethod.getInstance());
        l.addView(e); return e;
    }

    void connect() {
        final String srv = server.getText().toString().trim(), dir = folder.getText().toString().trim(), name = user.getText().toString().trim(), secret = password.getText().toString();
        connect.setEnabled(false); status.setText(tr("Checking the folder…", "Sprawdzam folder…"));
        new Thread(new Runnable() { public void run() {
            String failure = null; int budgets = 0;
            try {
                if (name.length() == 0 || secret.length() == 0) throw new IOException(tr("Enter the user name and app password.", "Wpisz użytkownika i hasło aplikacji."));
                String root = DavClient.join(srv, dir);
                if (!DavClient.testRoot(root)) throw new IOException(tr("This TEST build only accepts a folder inside YNAB4_PLUS_TEST.", "Ta wersja TEST przyjmuje tylko folder wewnątrz YNAB4_PLUS_TEST."));
                String anchor = getSharedPreferences("dav-test-profile", 0).getString("root", root);
                if (!anchor.equals(root)) throw new IOException(tr("This app profile is bound to another WebDAV folder. Use a fresh profile.", "Ten profil aplikacji jest powiązany z innym folderem WebDAV. Użyj nowego profilu."));
                DavProvider.clearAuthenticationBlock();
                DavClient.configure(root);
                DavClient client = new DavClient(name, secret, null);
                byte[] yroot;
                try { yroot = client.read("/.ynabSettings.yroot"); }
                catch (FileNotFoundException missing) { throw new IOException(tr("Connected, but the folder has no .ynabSettings.yroot (no budget there yet).", "Połączono, ale w folderze nie ma .ynabSettings.yroot (nie ma jeszcze budżetu).")); }
                budgets = new JSONObject(new String(yroot, "UTF-8")).getJSONArray("relativeKnownBudgets").length();
                if (budgets < 1) throw new IOException(tr("No budget is listed in this folder.", "W tym folderze nie ma żadnego budżetu."));
                JSONObject config = new JSONObject().put("server", srv).put("folder", dir).put("username", name).put("app_password", secret);
                FileOutputStream out = openFileOutput("webdav-test.json", 0);
                try { out.write(config.toString().getBytes("UTF-8")); } finally { out.close(); }
                getSharedPreferences("dav-test-profile", 0).edit().putString("root", root).putString("username", name).commit();
            } catch (Throwable error) {
                DavProvider.diagnostic(error);
                if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException || error instanceof java.net.SocketTimeoutException)
                    failure = tr("Could not reach the server. Check the address and the connection.", "Nie udało się połączyć z serwerem. Sprawdź adres i połączenie.");
                else if (error.getMessage() != null && error.getMessage().contains("401"))
                    failure = tr("The server rejected the user name or app password.", "Serwer odrzucił użytkownika lub hasło aplikacji.");
                else failure = error instanceof IOException && error.getMessage() != null && !error.getMessage().matches("[A-Z0-9_ ]+") ? error.getMessage()
                    : tr("Could not set up WebDAV (", "Nie udało się skonfigurować WebDAV (") + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
            }
            final String message = failure;
            runOnUiThread(new Runnable() { public void run() {
                connect.setEnabled(true);
                if (message == null) { password.setText(""); setResult(RESULT_OK); finish(); } else status.setText(message);
            } });
        } }).start();
    }
}

package org.classicbudget.patch;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.DocumentsContract;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

public final class DiagnosticsActivity extends Activity {
    private static final int SAVE_REPORT = 1;
    private static final int SAVE_FULL_LOG = 2;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable update = this::refresh;
    private TextView status;
    private Button recording;
    private Button export;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Diagnostics", "Diagnostyka"));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int inset = Math.round(20 * getResources().getDisplayMetrics().density);
        page.setPadding(inset, inset, inset, inset);
        TextView help = new TextView(this);
        help.setText(tr("Records app events for up to 15 minutes. The report contains event codes and no budget or location data.",
            "Nagrywa zdarzenia aplikacji przez maksymalnie 15 minut. Raport zawiera kody zdarzeń bez danych budżetu i lokalizacji."));
        page.addView(help);
        status = new TextView(this);
        page.addView(status);
        recording = new Button(this);
        recording.setContentDescription("diagnostics-recording");
        recording.setOnClickListener(view -> {
            if (PlusDiagnostics.active(this)) PlusDiagnostics.stop(this);
            else PlusDiagnostics.begin(this);
            refresh();
        });
        page.addView(recording);
        export = new Button(this);
        export.setText(tr("Export short recording…", "Eksportuj krótkie nagranie…"));
        export.setContentDescription("diagnostics-export");
        export.setOnClickListener(view -> destination(SAVE_REPORT, "YNAB-Plus-diagnostics.txt"));
        page.addView(export);
        TextView fullHelp = new TextView(this);
        fullHelp.setText(tr("The full app log works independently of recording and contains the available entries from the current app process. It may include budget or location data.",
            "Pełny log działa niezależnie od nagrania i zawiera dostępne wpisy bieżącego procesu aplikacji. Może zawierać dane budżetu lub lokalizacji."));
        page.addView(fullHelp);
        Button full = new Button(this);
        full.setText(tr("Export full app log…", "Eksportuj pełny log aplikacji…"));
        full.setContentDescription("diagnostics-full-export");
        full.setOnClickListener(view -> destination(SAVE_FULL_LOG, "YNAB-Plus-full-log.txt"));
        page.addView(full);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(update);
        super.onPause();
    }

    private void refresh() {
        handler.removeCallbacks(update);
        boolean active = PlusDiagnostics.active(this);
        boolean hasReport = !PlusDiagnostics.contents(this).isEmpty();
        status.setText(active ? tr("Recording", "Nagrywanie trwa") : tr("Recording stopped", "Nagrywanie zatrzymane"));
        recording.setText(active ? tr("Stop recording", "Zatrzymaj nagrywanie") : hasReport
            ? tr("Start a new recording", "Rozpocznij nowe nagranie") : tr("Start recording", "Rozpocznij nagrywanie"));
        export.setEnabled(!active && hasReport);
        if (active) handler.postDelayed(update, 1000);
    }

    private void destination(int request, String filename) {
        Intent destination = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/plain").putExtra(Intent.EXTRA_TITLE, filename);
        if (Build.VERSION.SDK_INT >= 26) destination.putExtra(DocumentsContract.EXTRA_INITIAL_URI,
            DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download"));
        try {
            startActivityForResult(destination, request);
        } catch (RuntimeException unavailable) {
            feedback(false);
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if ((request != SAVE_REPORT && request != SAVE_FULL_LOG) || result != RESULT_OK || data == null
            || data.getData() == null) return;
        SyncHealth.READER.execute(() -> {
            boolean success = export(request, data);
            runOnUiThread(() -> feedback(success));
        });
    }

    private boolean export(int request, Intent data) {
        try {
            String report = request == SAVE_FULL_LOG ? fullLog() : PlusDiagnostics.contents(this);
            try (OutputStream stream = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (stream == null) return false;
                stream.write(report.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static String fullLog() throws IOException {
        String pid = Integer.toString(Process.myPid());
        Pattern ownLine = Pattern.compile("^\\d{2}-\\d{2}\\s+\\S+\\s+" + pid + "\\s+\\d+\\s+.*$");
        java.lang.Process logcat = new ProcessBuilder("logcat", "-t", "600", "-v", "threadtime")
            .redirectErrorStream(true).start();
        String header = "YNAB Plus full app log · current process\n";
        StringBuilder result = new StringBuilder(header);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(logcat.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && result.length() < 256 * 1024) {
                if (ownLine.matcher(line).matches()) result.append(line).append('\n');
            }
        } finally {
            logcat.destroy();
        }
        if (result.length() == header.length()) result.append("No log entries available for this process.\n");
        return result.toString();
    }

    private void feedback(boolean success) {
        Toast.makeText(this, success ? tr("Report saved", "Raport zapisany")
            : tr("Could not save the report", "Nie udało się zapisać raportu"), Toast.LENGTH_SHORT).show();
    }

    private static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
}

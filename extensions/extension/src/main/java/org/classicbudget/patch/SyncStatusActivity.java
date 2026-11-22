package org.classicbudget.patch;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.text.DateFormat;
import java.util.Date;

/** One place for recent sync results, unsent data, and the conflict review. */
public final class SyncStatusActivity extends Activity {
    private LinearLayout content;
    private boolean reading;
    private boolean active;
    private final Runnable periodic = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener changes = (prefs, key) -> refresh();

    static boolean available(Context context) {
        return new Intent(context, SyncStatusActivity.class).resolveActivity(context.getPackageManager()) != null;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Sync status", "Stan synchronizacji"));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int space = Math.round(getResources().getDisplayMetrics().density * 18);
        content.setPadding(space, space, space, space);
        scroll.addView(content);
        setContentView(scroll);
    }

    @Override protected void onStart() {
        super.onStart();
        active = true;
        SyncHealth.prefs(this).registerOnSharedPreferenceChangeListener(changes);
        refresh();
    }

    @Override protected void onStop() {
        active = false;
        content.removeCallbacks(periodic);
        SyncHealth.prefs(this).unregisterOnSharedPreferenceChangeListener(changes);
        super.onStop();
    }

    private void refresh() {
        if (!active || reading) return;
        reading = true;
        Context app = getApplicationContext();
        SyncHealth.READER.execute(() -> {
            SyncHealth.Snapshot status = SyncHealth.snapshot(app);
            runOnUiThread(() -> {
                reading = false;
                if (!active) return;
                render(status);
                content.removeCallbacks(periodic);
                content.postDelayed(periodic, 30000);
            });
        });
    }

    private void render(SyncHealth.Snapshot status) {
        content.removeAllViews();
        if (status == null) {
            text(tr("Open a synced budget to see its status.", "Otwórz synchronizowany budżet, aby sprawdzić jego stan."), 16);
            return;
        }
        text(SyncHealth.backendName(status.backend), 20);
        String issue = SyncHealth.message(status);
        if (!issue.isEmpty()) text(issue, 16);
        if (status.counts == null) {
            text(tr("The local send queue could not be checked.", "Nie udało się sprawdzić kolejki na telefonie."), 16);
        } else {
            text(tr("Items waiting on this phone", "Wpisy oczekujące na telefonie"), 17);
            text(tr("Transactions · ", "Transakcje · ") + status.counts.transactions + "\n"
                + tr("Budgeted amounts · ", "Budżetowanie · ") + status.counts.budgeting + "\n"
                + tr("Scheduled transactions · ", "Zlecenia stałe · ") + status.counts.schedules + "\n"
                + tr("Other · ", "Pozostałe · ") + status.counts.other, 15);
        }
        boolean folder = "folder".equals(status.backend);
        text(tr("Last attempt · ", "Ostatnia próba · ") + time(status.attempted) + "\n"
            + (folder ? tr("Last successful folder sync · ", "Ostatnia udana synchronizacja folderu · ")
                : tr("Last successful sync · ", "Ostatnia udana synchronizacja · ")) + time(status.synced) + "\n"
            + (folder ? tr("Last write to shared folder · ", "Ostatni zapis do udostępnionego folderu · ")
                : tr("Last confirmed send · ", "Ostatnie potwierdzone wysłanie · ")) + time(status.sent), 15);
        if (folder) text(tr("Syncthing transfers files from this folder to other devices.",
            "Syncthing odpowiada za przesłanie plików z tego folderu na inne urządzenia."), 14);
        boolean webdav = "webdav".equals(status.backend);
        boolean limited = webdav && "limit".equals(status.issue);
        Button retry = button(limited ? tr("Retry after the pause", "Ponów po przerwie")
            : tr("Sync now", "Synchronizuj teraz"), "sync-health-retry");
        retry.setOnClickListener(v -> {
            if (limited && status.id.equals(SyncHealth.selected(this)) && "webdav".equals(BackendSelector.chosen())
                && DavProvider.authenticationStatus() == 429)
                DavProvider.clearAuthenticationBlock();
            if (!SyncHealth.retry(this, status.id))
                android.widget.Toast.makeText(this, tr("Could not start synchronization.", "Nie udało się uruchomić synchronizacji."),
                    android.widget.Toast.LENGTH_LONG).show();
        });
        if ("auth".equals(status.issue) && "dropbox".equals(status.backend)) {
            Button link = button(tr("Reconnect Dropbox", "Połącz Dropbox ponownie"), "sync-health-reconnect");
            link.setOnClickListener(v -> new DropboxProfile().a(this));
        } else if ("auth".equals(status.issue) && webdav) {
            Intent link = new Intent(this, WebDavSetupActivity.class).putExtra("reauthorize", true);
            if (link.resolveActivity(getPackageManager()) != null) {
                Button change = button(tr("Update the app password", "Zaktualizuj hasło aplikacji"), "sync-health-reconnect");
                change.setOnClickListener(v -> startActivity(link));
            }
        }
        Intent conflicts = new Intent(this, ConflictReviewActivity.class);
        if (conflicts.resolveActivity(getPackageManager()) != null) {
            Button review = button(tr("Review conflicts", "Sprawdź konflikty"), "sync-health-conflicts");
            review.setOnClickListener(v -> startActivity(conflicts));
        }
    }

    private String time(long millis) {
        return millis == 0 ? tr("No result recorded yet", "Brak zapisanego wyniku")
            : DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(millis));
    }

    private void text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        int space = Math.round(getResources().getDisplayMetrics().density * 8);
        view.setPadding(0, space, 0, space);
        content.addView(view);
    }

    private Button button(String label, String description) {
        Button view = new Button(this);
        view.setAllCaps(false);
        view.setText(label);
        view.setContentDescription(description);
        content.addView(view);
        return view;
    }

    private static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
}

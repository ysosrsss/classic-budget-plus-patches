package org.classicbudget.patch;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.UUID;

public final class PayeePlaceEditorActivity extends Activity {
    static final String SHARE = "shared-map-point";
    private static final String DRAFT = "gps-place-draft";
    private String token;
    private String budget;
    private PayeePlaces.Place place;
    private EditText coordinates;
    private TextView notice;
    private Button save;
    private Button map;
    private boolean saving;
    private boolean reading;
    private int requestId;

    static void open(Activity activity, String budget, PayeePlaces.Place place) {
        draft(activity).edit().putString("token", UUID.randomUUID().toString())
            .putString("budget", budget).putString("guid", place.guid).putString("version", place.version).apply();
        activity.startActivity(new Intent(activity, PayeePlaceEditorActivity.class));
    }

    static boolean hasDraft(Context context) {
        return draft(context).contains("token");
    }

    private static SharedPreferences draft(Context context) {
        return context.getSharedPreferences(DRAFT, MODE_PRIVATE);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Move place", "Przenieś miejsce"));
        SharedPreferences draft = draft(this);
        token = draft.getString("token", null);
        budget = draft.getString("budget", null);
        String guid = draft.getString("guid", null);
        String version = draft.getString("version", null);
        if (token == null || budget == null || guid == null || version == null) {
            finish();
            return;
        }
        LinearLayout page = new LinearLayout(this);
        int inset = Math.round(16 * getResources().getDisplayMetrics().density);
        page.setPadding(inset, inset, inset, inset);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setFocusableInTouchMode(true);
        TextView current = new TextView(this);
        page.addView(current);
        map = button(page, tr("Open Google Maps", "Otwórz Mapy Google"), this::openMaps);
        map.setEnabled(false);
        TextView help = new TextView(this);
        help.setText(tr("Press and hold the new spot in Maps. Share the pin to YNAB Plus or copy its coordinates and paste them below.",
            "Przytrzymaj nowe miejsce w Mapach. Udostępnij pinezkę do YNAB Plus albo skopiuj jej współrzędne i wklej je poniżej."));
        page.addView(help);
        coordinates = new EditText(this);
        coordinates.setSingleLine(true);
        coordinates.setHint(tr("Latitude, longitude", "Szerokość, długość"));
        coordinates.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        if (state != null && token.equals(state.getString("token"))) {
            coordinates.setText(state.getCharSequence("coordinates"));
        }
        page.addView(coordinates);
        notice = new TextView(this);
        notice.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(notice);
        save = button(page, tr("Save new location", "Zapisz nowe położenie"), this::save);
        save.setEnabled(false);
        button(page, getString(android.R.string.cancel), this::onBackPressed);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(page);
        setContentView(scroll);
        page.requestFocus();

        Context app = getApplicationContext();
        SyncHealth.READER.execute(() -> {
            PayeePlaces.Place loaded = null;
            try {
                loaded = PayeePlaces.find(app, budget, guid, version);
            } catch (RuntimeException unavailable) {
                // Changing budgets or deleting the selected place invalidates the draft.
            }
            PayeePlaces.Place result = loaded;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null || !hasCurrentDraft()) {
                    changed();
                    finish();
                    return;
                }
                place = result;
                current.setText(result.name + "\n" + tr("Current pin", "Obecna pinezka") + " · " + result.lat + ", " + result.lng);
                map.setEnabled(true);
                save.setEnabled(!reading);
            });
        });
        readShare(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readShare(intent);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("token", token);
        if (coordinates != null) out.putCharSequence("coordinates", coordinates.getText().toString());
    }

    @Override public void onBackPressed() {
        if (saving) return;
        clearDraft();
        super.onBackPressed();
    }

    private void readShare(Intent intent) {
        String shared = intent.getStringExtra(SHARE);
        if (shared == null || !hasCurrentDraft() || saving) return;
        final int request = ++requestId;
        reading = true;
        save.setEnabled(false);
        coordinates.setEnabled(false);
        notice.setText(tr("Reading map pin…", "Odczytuję pinezkę…"));
        new Thread(() -> {
            MapPoint point = MapPoint.read(shared);
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || request != requestId) return;
                reading = false;
                getIntent().removeExtra(SHARE);
                coordinates.setEnabled(true);
                if (point == null) {
                    coordinates.setText("");
                    notice.setText(tr("Coordinates were unavailable. Copy them from Maps and paste them above.",
                        "Brak współrzędnych w udostępnionej pinezce. Skopiuj je z Map i wklej powyżej."));
                } else {
                    coordinates.setText(point.coordinates());
                    notice.setText(tr("Check the proposed coordinates and save to move this pin.",
                        "Sprawdź podane współrzędne i zapisz, aby przenieść pinezkę."));
                }
                save.setEnabled(place != null);
            });
        }, "payee-map-pin").start();
    }

    private void openMaps() {
        String point = place.lat + "," + place.lng;
        Intent maps = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + point))
            .setPackage("com.google.android.apps.maps");
        try {
            startActivity(maps);
        } catch (ActivityNotFoundException missingApp) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://www.google.com/maps/search/?api=1&query=" + Uri.encode(point))));
            } catch (ActivityNotFoundException missingBrowser) {
                notice.setText(tr("Google Maps is unavailable. You can paste coordinates above.",
                    "Mapy Google są niedostępne. Możesz wkleić współrzędne powyżej."));
            }
        }
    }

    private void save() {
        if (place == null || saving) return;
        if (!hasCurrentDraft()) {
            changed();
            finish();
            return;
        }
        MapPoint point = MapPoint.fromCoordinates(coordinates.getText().toString());
        if (point == null) {
            coordinates.setError(tr("Enter coordinates like 52.12345, 21.12345", "Wpisz współrzędne, np. 52.12345, 21.12345"));
            return;
        }
        if (point.matches(place.lat, place.lng)) {
            coordinates.setError(tr("Enter a different point.", "Wskaż inne miejsce."));
            return;
        }
        Context app = getApplicationContext();
        saving = true;
        save.setEnabled(false);
        map.setEnabled(false);
        coordinates.setEnabled(false);
        SyncHealth.READER.execute(() -> {
            boolean written;
            try {
                PayeePlaces.move(app, budget, place, point);
                written = true;
            } catch (Exception failure) {
                written = false;
            }
            boolean success = written;
            runOnUiThread(() -> {
                clearDraft();
                if (isFinishing() || isDestroyed()) return;
                if (success) {
                    Toast.makeText(this, tr("Place moved.", "Miejsce przeniesione."), Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, PayeePlacesActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                } else {
                    changed();
                }
                finish();
            });
        });
    }

    private void changed() {
        clearDraft();
        Toast.makeText(this, tr("Could not update the place. Open it again from the current list.",
            "Nie udało się zmienić miejsca. Otwórz je ponownie z aktualnej listy."), Toast.LENGTH_LONG).show();
    }

    private boolean hasCurrentDraft() {
        return token != null && token.equals(draft(this).getString("token", null));
    }

    private void clearDraft() {
        if (hasCurrentDraft()) draft(this).edit().clear().apply();
    }

    private Button button(LinearLayout page, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(view -> action.run());
        page.addView(button);
        return button;
    }

    private static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
}

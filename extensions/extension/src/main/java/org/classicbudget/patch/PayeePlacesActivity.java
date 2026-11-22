package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** A local view of the current budget's native payee locations. */
public final class PayeePlacesActivity extends Activity {
    private List<PayeePlaces.Place> all = Collections.emptyList();
    private final List<PayeePlaces.Place> filtered = new ArrayList<>();
    private EditText search;
    private TextView empty;
    private PlaceAdapter listAdapter;
    private String budget;
    private boolean available;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle(tr("Payee places", "Miejsca płatników"));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), 0);
        page.setFocusableInTouchMode(true);
        search = new EditText(this);
        search.setId(View.generateViewId());
        search.setHint(tr("Find a payee", "Szukaj płatnika"));
        search.setSingleLine(true);
        if (state != null) search.setText(state.getCharSequence("query"));
        page.addView(search);

        empty = new TextView(this);
        empty.setPadding(0, dp(12), 0, 0);
        page.addView(empty);
        ListView list = new ListView(this);
        listAdapter = new PlaceAdapter();
        list.setAdapter(listAdapter);
        list.setEmptyView(empty);
        list.setOnItemClickListener((parent, view, position, itemId) -> showPlace(listAdapter.getItem(position)));
        page.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        page.requestFocus();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable editable) { }
        });
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putCharSequence("query", search.getText());
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        String selected = SyncHealth.selected(this);
        budget = selected;
        available = false;
        all = Collections.emptyList();
        filter();
        if (selected == null) {
            empty.setText(tr("Open a synced budget first.", "Najpierw otwórz synchronizowany budżet."));
            return;
        }
        empty.setText(tr("Loading saved places…", "Wczytuję zapisane miejsca…"));
        Context app = getApplicationContext();
        SyncHealth.READER.execute(() -> {
            List<PayeePlaces.Place> places = null;
            try {
                places = PayeePlaces.read(app, selected);
            } catch (RuntimeException unavailable) {
                // Budget selection can change while this screen is opening.
            }
            List<PayeePlaces.Place> result = places;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || !selected.equals(budget)) return;
                if (result == null) {
                    empty.setText(tr("Could not read saved places. Reopen the budget and try again.",
                        "Nie udało się odczytać miejsc. Otwórz budżet ponownie i spróbuj jeszcze raz."));
                    return;
                }
                all = result;
                available = true;
                filter();
            });
        });
    }

    private void filter() {
        filtered.clear();
        String query = search.getText().toString().trim();
        for (PayeePlaces.Place place : all) {
            if (SearchText.matches(place.name, query)) filtered.add(place);
        }
        if (available) empty.setText(all.isEmpty() ? tr("No saved payee places in this budget.",
            "W tym budżecie nie ma zapisanych miejsc płatników.") : tr("No places match that payee.",
            "Brak miejsc pasujących do wyszukiwania."));
        listAdapter.notifyDataSetChanged();
    }

    private void showPlace(PayeePlaces.Place place) {
        String selected = budget;
        new AlertDialog.Builder(this).setTitle(place.name).setMessage(coordinates(place))
            .setNegativeButton(tr("Remove…", "Usuń…"), (dialog, which) -> confirm(selected, place))
            .setNeutralButton(tr("Open map", "Pokaż na mapie"), (dialog, which) -> map(place))
            .setPositiveButton(tr("Move…", "Przenieś…"),
                (dialog, which) -> PayeePlaceEditorActivity.open(this, selected, place)).show();
    }

    private void confirm(String selected, PayeePlaces.Place place) {
        String remove = tr("Remove place", "Usuń miejsce");
        new AlertDialog.Builder(this).setTitle(remove)
            .setMessage(place.name + "\n" + coordinates(place) + "\n\n"
                + tr("Removal syncs to other devices using this budget. YNAB can learn the place again when you save a transaction there.",
                    "Usunięcie zsynchronizuje się z innymi urządzeniami tego budżetu. YNAB może zapamiętać miejsce ponownie po zapisaniu tam transakcji."))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(remove, (dialog, which) -> remove(selected, place)).show();
    }

    private void remove(String selected, PayeePlaces.Place place) {
        Context app = getApplicationContext();
        SyncHealth.READER.execute(() -> {
            String message;
            try {
                PayeePlaces.remove(app, selected, place);
                message = tr("Place removed.", "Miejsce usunięte.");
            } catch (Exception unavailable) {
                message = tr("Could not remove the place. Check the latest list and try again.",
                    "Nie udało się usunąć miejsca. Sprawdź aktualną listę i spróbuj ponownie.");
            }
            String result = message;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                Toast.makeText(this, result, Toast.LENGTH_LONG).show();
                load();
            });
        });
    }

    private void map(PayeePlaces.Place place) {
        String point = place.lat + "," + place.lng;
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + point));
        try {
            startActivity(view);
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(this, tr("No map app is available.", "Brak dostępnej aplikacji map."), Toast.LENGTH_SHORT).show();
        }
    }

    private static String coordinates(PayeePlaces.Place place) {
        return String.format(Locale.ROOT, "%.5f, %.5f", place.lat, place.lng);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static String tr(String en, String pl) { return PlusLang.tr(en, pl); }

    private final class PlaceAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public PayeePlaces.Place getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return getItem(position).id; }
        @Override public boolean hasStableIds() { return true; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(PayeePlacesActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setMinimumHeight(dp(56));
                row.setPadding(dp(4), dp(9), dp(4), dp(9));
                TextView name = new TextView(PayeePlacesActivity.this);
                name.setTextSize(17);
                row.addView(name);
                row.addView(new TextView(PayeePlacesActivity.this));
            }
            PayeePlaces.Place place = getItem(position);
            ((TextView) row.getChildAt(0)).setText(place.name);
            ((TextView) row.getChildAt(1)).setText(coordinates(place));
            return row;
        }
    }
}

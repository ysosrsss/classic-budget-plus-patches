package org.classicbudget.patch;

import android.app.Activity;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.*;

/** "Plus settings" ("Ustawienia Plus"): local settings of the optional modules. Today: account suggestions. */
public class PlusSettingsActivity extends Activity {
    /** Language of our screens: English unless Polish is chosen in Plus settings (PlusLang). */
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    SharedPreferences prefs;

    protected boolean rulesOnly() { return false; }

    protected void onCreate(Bundle state) {
        super.onCreate(state); PlusEdition.mark(this); setTitle(tr("Plus settings", "Ustawienia Plus"));
        // §160: opened by a registration stopped at A–Z; only the choice dialog, no budget page (none is open yet).
        if ("new".equals(getIntent().getStringExtra("takeover"))) {
            setContentView(new LinearLayout(this));
            DeviceTakeover.chooseForNewDevice(this);
            return;
        }
        prefs = getSharedPreferences(AccountSuggest.PREFS, 0);
        Bundle draft = getIntent().getBundleExtra(TransactionPayeeRule.DRAFT);
        if (rulesOnly() && draft != null) {
            if (TransactionPayeeRule.openBudget(this).equals(getIntent().getStringExtra(TransactionPayeeRule.BUDGET))) {
                int existing = exactRule(draft.getString("text"));
                editRule(existing, draft);
            } else finish();
            return;
        }
        final LinearLayout page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density); page.setPadding(pad, pad, pad, pad);
        if (rulesOnly() || "rules".equals(getIntent().getStringExtra("section"))) {
            setTitle(tr("Payee rules", "Reguły płatników"));
            rulePage(page);
            ScrollView rulesScroll = new ScrollView(this);
            rulesScroll.addView(page);
            setContentView(rulesScroll);
            return;
        }
        note(page, tr("Changes are saved at once; there is nothing to confirm. \"Restore defaults\" is at the bottom.", "Zmiany zapisują się od razu, nie trzeba niczego zatwierdzać. „Przywróć domyślne” jest na dole."));
        LinearLayout layout = section(page,"language","Language / Język",tr("Language of Plus screens","Język ekranów Plus"));
        RadioGroup lang = new RadioGroup(this);
        String[][] langs = {{PlusLang.SYSTEM, tr("Phone language", "Język telefonu")}, {PlusLang.EN, "English"}, {PlusLang.PL, "Polski"}};
        for (final String[] l : langs) {
            RadioButton b = new RadioButton(this); b.setId(View.generateViewId()); b.setText(l[1]); b.setContentDescription("lang-" + l[0]);
            lang.addView(b); if (l[0].equals(PlusLang.choice())) b.setChecked(true);
            b.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton v, boolean on) { if (on && !l[0].equals(PlusLang.choice())) { PlusLang.set(PlusSettingsActivity.this, l[0]); recreate(); } }
            });
        }
        layout.addView(lang);
        note(layout, tr("Applies to the screens added by Plus (sync choice, Plus settings, Reports). The original app, including its first screen and Settings, stays in English.", "Dotyczy ekranów dodanych przez Plus (wybór synchronizacji, Ustawienia Plus, Raporty). Oryginalna aplikacja, łącznie z pierwszym ekranem i Ustawieniami, zostaje po angielsku."));
        layout=section(page,"budget",tr("Budget and sync","Budżet i synchronizacja"),tr("Connection and conflicts","Połączenie i konflikty"));
        if (SyncStatusActivity.available(this)) {
            shortcut(layout,tr("Sync status","Stan synchronizacji"),"plus-sync-status","org.classicbudget.patch.SyncStatusActivity");
        } else {
            shortcut(layout,tr("Sync conflicts","Konflikty synchronizacji"),"plus-conflicts","org.classicbudget.patch.ConflictReviewActivity");
        }
        android.content.Intent sw = new android.content.Intent().setClassName(getPackageName(), "org.classicbudget.patch.SyncSwitchActivity");
        if (BackendSelector.hasActivity(this, SyncSwitchActivity.class)) {
            boolean alternate = BackendSelector.alternateBackends(this);
            String backend = BackendSelector.chosen();
            note(layout, tr("Opening a linked budget can register this phone and send its device metadata.",
                "Otwarcie powiązanego budżetu może zarejestrować ten telefon i wysłać jego metadane."));
            if (!PlusEdition.TEST_ONLY && (backend == null || BackendSelector.DROPBOX.equals(backend))) {
                Button folder = new Button(this);
                folder.setText(tr("Dropbox folder…", "Folder Dropbox…"));
                folder.setContentDescription("plus-dropbox-folder");
                folder.setOnClickListener(v -> startActivity(SyncSwitchActivity.dropboxFolderIntent(this)));
                layout.addView(folder);
            }
            if (alternate) {
                Button change = new Button(this);
                change.setText(tr("Change sync method…", "Zmień synchronizację…"));
                change.setContentDescription("plus-switch-sync");
                change.setOnClickListener(v -> startActivity(sw));
                layout.addView(change);
            }
        }
        shortcut(layout, tr("Diagnostics…", "Diagnostyka…"), "plus-diagnostics", "org.classicbudget.patch.DiagnosticsActivity");
        String places = tr("Payee places (GPS)…", "Miejsca płatników (GPS)…");
        shortcut(layout, places, places, "org.classicbudget.patch.PayeePlacesActivity");
        layout = section(page, "reports", tr("Reports", "Raporty"), tr("Optional reports in the report tabs", "Dodatkowe raporty na pasku"));
        reportVisibility(layout, "upcoming", tr("Show Upcoming", "Pokazuj Nadchodzące"));
        reportVisibility(layout, "person", tr("Show By device", "Pokazuj Według urządzenia"));
        reportVisibility(layout, "analysis", tr("Show Budget analysis", "Pokazuj Analizę budżetu"));
        reportVisibility(layout, "comparison", tr("Show Period comparison", "Pokazuj Porównanie okresów"));
        note(layout, tr("By device identifies the last editor of a transaction, not who spent the money.",
            "Według urządzenia pokazuje ostatniego edytora transakcji, nie osobę wydającą pieniądze."));
        layout=section(page,"display",tr("Display","Wygląd"),tr("Theme, animations and balance messages","Motyw, animacje i komunikaty salda"));
        choice(layout, Display.DARK, tr("Dark theme", "Ciemny motyw"), Display.dark(this), new String[][]{
            {Display.SYSTEM, tr("Follow the phone", "Jak w telefonie")}, {Display.ON, tr("Always dark", "Zawsze ciemny")}, {Display.OFF, tr("Off (light)", "Wyłączony (jasny)")}});
        note(layout, tr("Uses Android's built-in dark rendering (Android 10+, like Darq). \"Always dark\" and \"Off\" need Android 12+.", "Wbudowane ciemne renderowanie Androida (Android 10+, jak Darq). „Zawsze ciemny” i „Wyłączony” wymagają Androida 12+."));
        choice(layout, Display.MOTION, tr("Screen transitions", "Przejścia ekranów"), Display.animations(this) ? Display.ON : Display.OFF, new String[][]{
            {Display.OFF, tr("Off (no jump)", "Wyłączone (bez skoku)")}, {Display.ON, tr("Original animations", "Oryginalne animacje")}});
        choice(layout, Display.BALANCE_TOAST, tr("Category balance after saving", "Saldo kategorii po zapisie"), Display.balanceToast(this), new String[][]{
            {Display.ABOVE, tr("Above the Add button (button stays usable)", "Nad przyciskiem dodawania (przycisk działa)")},
            {Display.ORIGINAL, tr("Original (replaces the button for ~3 s)", "Oryginalnie (zastępuje przycisk na ~3 s)")}, {Display.OFF, tr("Off", "Wyłączone")}});
        choice(layout, PlusMenu.BUTTON, tr("Plus button on the budget screen", "Przycisk Plus na ekranie budżetu"),
            PlusMenu.buttonOn(this) ? PlusMenu.ON : PlusMenu.OFF, new String[][]{
            {PlusMenu.ON, tr("On: button bottom right, hides while scrolling", "Włączony: przycisk w prawym dolnym rogu, chowa się przy przewijaniu")},
            {PlusMenu.OFF, tr("Off: Budgeting, Reports, Scheduled and Payee rules in the ⋮ menu", "Wyłączony: Budżetowanie, Raporty, Zlecenia i Reguły w menu ⋮")}});
        choice(layout,"entry_layout",tr("Transaction form","Formularz transakcji"),getSharedPreferences(Display.PREFS,0).getString("entry_layout","original"),new String[][]{{"original",tr("Original","Oryginalny")},{"compact",tr("Compact amount · show payee, category and account","Kompaktowa kwota · pokaż płatnika, kategorię i konto")}});
        note(layout,tr("Compact layout uses the original keypad and save action. Small windows keep the native layout.","Układ kompaktowy zachowuje oryginalną klawiaturę i zapis. W małych oknach pozostaje układ natywny."));
        layout=section(page,"alerts",tr("Alerts","Alerty"),tr("Budget opening alerts","Alerty przy otwarciu budżetu"));
        final SharedPreferences rp = getSharedPreferences("cb-plus-reports", 0);
        CheckBox alerts = new CheckBox(this); alerts.setText(tr("Show overspent categories when the budget opens (once a day)", "Pokazuj przekroczone kategorie przy otwarciu budżetu (raz dziennie)"));
        alerts.setContentDescription("alerts-toggle"); alerts.setChecked(Alerts.enabled(this));
        alerts.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() { public void onCheckedChanged(CompoundButton b, boolean on) { rp.edit().putString("alerts", on ? "on" : "off").apply(); } });
        layout.addView(alerts);
        layout=section(page,"suggestions",tr("Account suggestions","Podpowiadanie kont"),tr("Default account and payee suggestions","Konto domyślne i podpowiedzi płatników"));
        note(layout, tr("These settings apply to this phone only. They do not change the budget or other devices.", "Ustawienia dotyczą tylko tego telefonu. Nie zmieniają budżetu ani innych urządzeń."));
        modes(layout, "payee_mode", tr("Account after choosing a payee", "Konto po wybraniu płatnika"), new String[]{
            tr("Original: last account used with this payee, by anyone", "Oryginalnie: ostatnie konto z tym płatnikiem, ktokolwiek je wpisał"),
            tr("Prefer my entries: last account I used with this payee", "Preferuj moje wpisy: ostatnie konto, którego ja użyłem z tym płatnikiem"),
            tr("My entries and my accounts only: as above, and accounts outside “My accounts” are never suggested", "Moje wpisy i tylko moje konta: jak wyżej, a konta spoza listy „Moje konta” nigdy nie są podpowiadane")});
        modes(layout, "default_mode", tr("Default account of a new transaction", "Konto domyślne nowej transakcji"), new String[]{
            tr("Original: account of the last transaction in the budget", "Oryginalnie: konto ostatniej transakcji w budżecie"),
            tr("Last account I used", "Ostatnie konto, którego ja użyłem"),
            tr("My last account, skipping accounts outside “My accounts”", "Ostatnie moje konto, z pominięciem kont spoza listy „Moje konta”")});
        // Payee rules left the overflow menu in 076 (destinations moved to the drawer); this is their entry now.
        Button rules = new Button(this); rules.setAllCaps(false);
        rules.setText(tr("Payee rules…", "Reguły płatników…")); rules.setContentDescription("settings-payee-rules");
        rules.setOnClickListener(v -> startActivity(new android.content.Intent(this, PayeeRulesActivity.class)));
        layout.addView(rules);
        layout=section(page,"accounts",tr("My accounts","Moje konta"),tr("Accounts this phone may suggest","Konta podpowiadane na tym telefonie"));
        note(layout, tr("Untick accounts this phone should not suggest (e.g. your partner's). You can still pick them by hand.", "Odznacz konta, których ten telefon ma nie podpowiadać (np. konta partnera). Nadal można je wybrać ręcznie."));
        accounts(layout);
        layout=section(page,"devices",tr("Devices","Urządzenia"),tr("Names, my devices and account counts","Nazwy, moje urządzenia i liczniki kont"));
        final LinearLayout deviceSection=layout;
        devices(layout);
        layout=section(page,"reset",tr("Restore defaults","Przywróć domyślne"),tr("Reset local Plus preferences","Przywrócenie lokalnych ustawień Plus"));
        note(layout, tr("Language, display, alerts, account suggestions, my accounts and my devices go back to the original behaviour. Payee rules are kept.", "Język, wygląd, alerty, podpowiadanie kont, moje konta i moje urządzenia wracają do zachowania oryginału. Reguły płatników zostają."));
        Button reset = new Button(this); reset.setText(tr("Restore defaults…", "Przywróć domyślne…")); reset.setContentDescription("plus-reset");
        reset.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { confirmReset(); } }); layout.addView(reset);
        // Keep the screen at the top: otherwise the text field takes focus and scrolls to the bottom.
        page.setFocusableInTouchMode(true); page.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS); page.requestFocus();
        final ScrollView scroll = new ScrollView(this); scroll.addView(page); setContentView(scroll);
        if("devices".equals(getIntent().getStringExtra("section"))){
            deviceSection.setVisibility(View.VISIBLE);
            scroll.post(new Runnable(){public void run(){scroll.smoothScrollTo(0,Math.max(0,deviceSection.getTop()-80));}});
        }
    }

    void reportVisibility(LinearLayout layout, String kind, String label) {
        SharedPreferences reports = getSharedPreferences("cb-plus-reports", 0);
        CheckBox box = new CheckBox(this);
        box.setText(label);
        box.setMinHeight((int) (48 * getResources().getDisplayMetrics().density));
        box.setContentDescription("report-visible:" + kind);
        box.setChecked(reports.getBoolean("visible." + kind, false));
        box.setOnCheckedChangeListener((button, checked) -> reports.edit().putBoolean("visible." + kind, checked).apply());
        layout.addView(box);
    }

    void rulePage(LinearLayout layout) {
        note(layout, tr("When you choose a matching payee, the editor sets this account and/or category (first matching rule wins). Local to this phone.",
            "Po wybraniu pasującego płatnika edytor ustawia konto i/lub kategorię (wygrywa pierwsza pasująca reguła). Tylko na tym telefonie."));
        rulesBox = new LinearLayout(this);
        rulesBox.setOrientation(LinearLayout.VERTICAL);
        layout.addView(rulesBox);
        showRules();
        Button add = new Button(this);
        add.setText(tr("Add rule", "Dodaj regułę"));
        add.setContentDescription("rule-add");
        add.setOnClickListener(view -> addRule());
        layout.addView(add);
    }

    /** Radio list for one Display setting; the screen is recreated so the change is visible at once. */
    void choice(LinearLayout layout, final String key, String title, String current, String[][] options) {
        TextView t = new TextView(this); t.setText(title); t.setTextSize(16); t.setPadding(0, 16, 0, 0); layout.addView(t);
        RadioGroup group = new RadioGroup(this);
        for (final String[] o : options) {
            RadioButton b = new RadioButton(this); b.setId(View.generateViewId()); b.setText(o[1]); b.setContentDescription(key + "-" + o[0]);
            group.addView(b); if (o[0].equals(current)) b.setChecked(true);
            final String was = current;
            b.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton v, boolean on) { if (on && !o[0].equals(was)) { Display.set(PlusSettingsActivity.this, key, o[0]); recreate(); } }
            });
        }
        layout.addView(group);
    }

    void header(LinearLayout layout, String text) { TextView t = new TextView(this); t.setText(text); t.setTextSize(19); t.setPadding(0, 28, 0, 6); layout.addView(t); }
    LinearLayout section(LinearLayout page,final String key,final String title,final String summary) {
        final SharedPreferences ui=getSharedPreferences("cb-plus-ui",0);
        if(key.equals(getIntent().getStringExtra("section")))ui.edit().putBoolean("section_collapsed_"+key,false).apply();
        final LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);
        final Button heading=new Button(this);heading.setAllCaps(false);heading.setGravity(android.view.Gravity.LEFT|android.view.Gravity.CENTER_VERTICAL);
        heading.setContentDescription("settings-section:"+key);heading.setTextSize(17);
        int pad=(int)(12*getResources().getDisplayMetrics().density);heading.setPadding(pad,pad,pad,pad);
        final Runnable refresh=new Runnable(){public void run(){
            boolean closed=ui.getBoolean("section_collapsed_"+key,false);
            String label=(closed?"▸ ":"▾ ")+title+"\n"+summary;
            android.text.SpannableString text=new android.text.SpannableString(label);
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),0,label.indexOf('\n'),0);
            text.setSpan(new android.text.style.RelativeSizeSpan(.78f),label.indexOf('\n')+1,label.length(),0);
            heading.setText(text);body.setVisibility(closed?View.GONE:View.VISIBLE);
        }};
        heading.setOnClickListener(new View.OnClickListener(){public void onClick(View v){ui.edit().putBoolean("section_collapsed_"+key,!ui.getBoolean("section_collapsed_"+key,false)).apply();refresh.run();}});
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.setMarginStart(-Math.round(10 * getResources().getDisplayMetrics().density));
        page.addView(heading, headingParams);
        page.addView(body);
        refresh.run();
        if (key.equals(getIntent().getStringExtra("section"))) body.post(() -> {
            if (page.getParent() instanceof ScrollView) ((ScrollView) page.getParent()).smoothScrollTo(0, Math.max(0, heading.getTop()));
        });
        return body;
    }
    void shortcut(LinearLayout section,String title,String desc,final String className) {
        final android.content.Intent intent=new android.content.Intent().setClassName(getPackageName(),className);
        try{getPackageManager().getActivityInfo(intent.getComponent(),0);}catch(android.content.pm.PackageManager.NameNotFoundException absent){return;}
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setContentDescription(desc);
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){startActivity(intent);}});section.addView(b);
    }
    void note(LinearLayout layout, String text) { TextView t = new TextView(this); t.setText(text); layout.addView(t); }

    void modes(LinearLayout layout, final String key, String title, String[] labels) {
        TextView t = new TextView(this); t.setText(title); t.setTextSize(16); t.setPadding(0, 16, 0, 0); layout.addView(t);
        final String[] values = {AccountSuggest.ORIGINAL, AccountSuggest.MINE, AccountSuggest.MY_ACCOUNTS};
        String current = AccountSuggest.mode(key);
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < values.length; i++) {
            final String value = values[i];
            RadioButton b = new RadioButton(this); b.setId(View.generateViewId()); b.setText(labels[i]); b.setContentDescription(key + "-" + value);
            group.addView(b); if (value.equals(current)) b.setChecked(true);
            b.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton v, boolean on) { if (on) prefs.edit().putString(key, value).apply(); }
            });
        }
        layout.addView(group);
    }

    /** Accounts of the local budgets on this phone (read only). */
    LinearLayout rulesBox;

    /** Database of the budget the original app has open ("lastBudget"); newest file only as fallback. */
    File currentDatabase() {
        String open = getSharedPreferences(getPackageName() + "_preferences", 0).getString("lastBudget", null);
        if (open != null && open.contains("#") && Arrays.asList(databaseList()).contains(open)) return getDatabasePath(open);
        File best = null;
        for (String name : databaseList()) {
            if (!name.contains("#") || name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")) continue;
            File f = getDatabasePath(name);
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    /** Pairs (guid, label) of open accounts or categories in the current local budget. */
    List<String[]> options(String sql) {
        List<String[]> out = new ArrayList<String[]>();
        File best = currentDatabase();
        if (best == null) return out;
        try {
            SQLiteDatabase db = SQLiteDatabase.openDatabase(best.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try { Cursor c = db.rawQuery(sql, null); try { while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)}); } finally { c.close(); } }
            finally { db.close(); }
        } catch (RuntimeException ignored) { }
        return out;
    }
    static final String ACCOUNTS_SQL = "SELECT guid, name FROM accountTable WHERE deleted = 0 AND hidden = 0 ORDER BY name";
    // Visible categories only (hidden ones live under the "__Hidden__" master category, as the original editor filters them),
    // plus the two income categories, which have no master category.
    static final String CATEGORIES_SQL = "SELECT guid, label FROM ("
        + " SELECT c.guid, m.name || ': ' || c.name AS label, 0 AS g, m.sequence AS s1, c.sequence AS s2 FROM categoryTable c JOIN masterCategoryTable m ON m._id = c.masterCategoryId"
        + "  WHERE c.deleted = 0 AND m.deleted = 0 AND m.name != '__Hidden__' AND (m.guid IS NULL OR m.guid != 'MasterCategory/__Hidden__')"
        + " UNION ALL SELECT c.guid, c.guid, 1, 0, c._id FROM categoryTable c WHERE c.deleted = 0 AND c.masterCategoryId IS NULL AND c.guid IN ('Category/__ImmediateIncome__', 'Category/__DeferredIncome__')"
        + ") ORDER BY g, s1, s2";

    /** Readable names for the income categories (their stored name is internal). */
    List<String[]> categories() {
        List<String[]> out = options(CATEGORIES_SQL);
        for (String[] o : out) {
            if ("Category/__ImmediateIncome__".equals(o[1])) o[1] = tr("Income for this month", "Dochód na ten miesiąc");
            else if ("Category/__DeferredIncome__".equals(o[1])) o[1] = tr("Income for next month", "Dochód na następny miesiąc");
        }
        return out;
    }

    String labelOf(List<String[]> list, String guid) { for (String[] o : list) if (o[0].equals(guid)) return o[1]; return "?"; }

    void showRules() {
        rulesBox.removeAllViews();
        final org.json.JSONArray rules = AccountSuggest.rules();
        List<String[]> accs = options(ACCOUNTS_SQL), cats = categories();
        if (rules.length() == 0) { note(rulesBox, tr("No rules yet.", "Brak reguł.")); return; }
        for (int i = 0; i < rules.length(); i++) {
            final int index = i; org.json.JSONObject r = rules.optJSONObject(i); if (r == null) continue;
            String what = ("contains".equals(r.optString("match")) ? tr("contains ", "zawiera ") : tr("is ", "to ")) + tr("“", "„") + r.optString("text") + "”";
            String to = (r.optString("account").length() > 0 ? tr(" → account ", " → konto ") + labelOf(accs, r.optString("account")) : "")
                + (r.optString("category").length() > 0 ? tr(" → category ", " → kategoria ") + labelOf(cats, r.optString("category")) : "");
            // Compact two-line row (076): what matches, then where it leads; the whole text opens the editor.
            float d = getResources().getDisplayMetrics().density;
            LinearLayout line = new LinearLayout(this); line.setGravity(android.view.Gravity.CENTER_VERTICAL);
            line.setMinimumHeight((int) (56 * d));
            TextView tv = new TextView(this);
            android.text.SpannableStringBuilder text = new android.text.SpannableStringBuilder(tr("Payee ", "Płatnik ") + what);
            text.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, text.length(), 0);
            text.append("\n").append(to.replaceFirst("^ → ", "→ ").replace(" → ", " · "));
            tv.setText(text); tv.setTextSize(15); tv.setTextColor(0xFF212121); tv.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
            tv.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1)); tv.setContentDescription("rule-" + i);
            tv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { editRule(index); } });   // tap = edit
            Button del = new Button(this, null, android.R.attr.borderlessButtonStyle); del.setText("✕"); del.setTextColor(0xFF6E6E6E);
            del.setMinWidth((int) (48 * d)); del.setMinimumWidth((int) (48 * d)); del.setContentDescription("rule-delete-" + i);
            del.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                org.json.JSONArray next = new org.json.JSONArray(); for (int k = 0; k < rules.length(); k++) if (k != index) next.put(rules.opt(k));
                prefs.edit().putString("rules", next.toString()).apply(); showRules(); } });
            line.addView(tv); line.addView(del); rulesBox.addView(line);
            View divider = new View(this); divider.setBackgroundColor(0xFFE0E4E7);
            rulesBox.addView(divider, new LinearLayout.LayoutParams(-1, Math.max(1, (int) (d / 2))));
        }
    }

    void addRule() { editRule(-1); }

    static int position(List<String[]> list, String guid) { for (int i = 0; i < list.size(); i++) if (list.get(i)[0].equals(guid)) return i + 1; return 0; }

    /** Dialog for a new rule (index -1) or for editing rule `index` in place. */
    void editRule(final int index) { editRule(index, null); }

    private static int exactRule(String name) {
        org.json.JSONArray rules = AccountSuggest.rules();
        for (int i = 0; i < rules.length(); i++) {
            org.json.JSONObject rule = rules.optJSONObject(i);
            if (rule != null && "is".equals(rule.optString("match")) && rule.optString("text").equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private void editRule(final int index, Bundle draft) {
        final List<String[]> accs = options(ACCOUNTS_SQL), cats = categories();
        final org.json.JSONObject old = index < 0 ? null : AccountSuggest.rules().optJSONObject(index);
        if (old != null) {   // a target that is now hidden or deleted stays selectable, so editing never drops it silently
            String a = old.optString("account"), c = old.optString("category");
            if (a.length() > 0 && position(accs, a) == 0) accs.add(new String[]{a, tr("(hidden or deleted account)", "(ukryte lub usunięte konto)")});
            if (c.length() > 0 && position(cats, c) == 0) cats.add(new String[]{c, tr("(hidden or deleted category)", "(ukryta lub usunięta kategoria)")});
        }
        LinearLayout f = new LinearLayout(this); f.setOrientation(LinearLayout.VERTICAL); int pad = (int) (16 * getResources().getDisplayMetrics().density); f.setPadding(pad, 0, pad, 0);
        final EditText text = new EditText(this); text.setSingleLine(true); text.setHint(tr("Payee name or part of it", "Nazwa płatnika lub jej fragment")); text.setContentDescription("rule-text"); f.addView(text);
        final RadioGroup match = new RadioGroup(this); match.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton is = new RadioButton(this); is.setId(View.generateViewId()); is.setText(tr("is", "to")); is.setContentDescription("rule-is"); match.addView(is);
        RadioButton contains = new RadioButton(this); contains.setId(View.generateViewId()); contains.setText(tr("contains", "zawiera")); contains.setContentDescription("rule-contains"); match.addView(contains);
        if (old != null && "is".equals(old.optString("match"))) is.setChecked(true); else contains.setChecked(true);
        f.addView(match);
        final Spinner acc = spinner(f, tr("Account", "Konto"), accs, "rule-account");
        final Spinner cat = spinner(f, tr("Category", "Kategoria"), cats, "rule-category");
        if (old != null) {
            text.setText(old.optString("text")); text.setSelection(text.getText().length());
            acc.setSelection(position(accs, old.optString("account"))); cat.setSelection(position(cats, old.optString("category")));
        }
        if (draft != null) {
            text.setText(draft.getString("text"));
            text.setEnabled(false);
            match.setVisibility(View.GONE);
            is.setChecked(true);
            acc.setSelection(position(accs, draft.getString("account")));
            cat.setSelection(position(cats, draft.getString("category")));
        }
        final RadioButton isButton = is;
        String title = old == null ? tr("New payee rule", "Nowa reguła płatnika") : tr("Edit payee rule", "Edytuj regułę płatnika");
        String confirm = draft != null && old != null ? tr("Replace rule", "Zastąp regułę") : "OK";
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle(title).setView(f)
            .setPositiveButton(confirm, null).setNegativeButton(tr("Cancel", "Anuluj"), null).create();
        if (draft != null) dialog.setOnDismissListener(d -> finish());
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                String s = text.getText().toString().trim(); int a = acc.getSelectedItemPosition(), c = cat.getSelectedItemPosition();
                if (s.length() == 0 || (a == 0 && c == 0)) { Toast.makeText(PlusSettingsActivity.this, tr("Enter a payee and choose an account or a category.", "Wpisz płatnika i wybierz konto lub kategorię."), Toast.LENGTH_LONG).show(); return; }
                try {
                    org.json.JSONObject r = new org.json.JSONObject().put("match", isButton.isChecked() ? "is" : "contains").put("text", s)
                        .put("account", a == 0 ? "" : accs.get(a - 1)[0]).put("category", c == 0 ? "" : cats.get(c - 1)[0]);
                    org.json.JSONArray rules = AccountSuggest.rules();
                    if (index < 0 && rules.length() >= 200) {
                        Toast.makeText(this, tr("The payee rule limit is 200.", "Limit reguł płatników to 200."), Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (draft != null) {
                        // The explicit rule for this exact payee takes precedence over broader rules.
                        org.json.JSONArray next = new org.json.JSONArray().put(r);
                        for (int i = 0; i < rules.length(); i++) {
                            if (i != index) next.put(rules.opt(i));
                        }
                        rules = next;
                    } else if (index >= 0 && index < rules.length()) rules.put(index, r);
                    else rules.put(r);
                    prefs.edit().putString("rules", rules.toString()).apply();
                } catch (org.json.JSONException ignored) { return; }
                if (rulesBox != null) showRules();
                dialog.dismiss();
            }));
        dialog.show();
    }

    Spinner spinner(LinearLayout f, String title, List<String[]> items, String desc) {
        TextView t = new TextView(this); t.setText(title); f.addView(t);
        List<String> labels = new ArrayList<String>(); labels.add(tr("(no change)", "(bez zmian)")); for (String[] o : items) labels.add(o[1]);
        Spinner s = new Spinner(this); s.setContentDescription(desc);
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels); a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a); f.addView(s); return s;
    }

    void confirmReset() {
        new android.app.AlertDialog.Builder(this).setTitle(tr("Restore defaults?", "Przywrócić domyślne?"))
            .setMessage(tr("Payee rules are kept.", "Reguły płatników zostają."))
            .setPositiveButton(tr("Restore", "Przywróć"), new android.content.DialogInterface.OnClickListener() {
                public void onClick(android.content.DialogInterface d, int w) {
                    getSharedPreferences(Display.PREFS, 0).edit().clear().commit();   // language + display
                    getSharedPreferences(ReportData.PREFS, 0).edit().remove("alerts").remove("visible.upcoming").remove("visible.person").remove("visible.analysis").remove("visible.comparison").commit();
                    String rules = prefs.getString("rules", null);
                    SharedPreferences.Editor e = prefs.edit().clear(); if (rules != null) e.putString("rules", rules); e.commit();
                    Display.applyNightMode(PlusSettingsActivity.this);
                    recreate();
                }
            })
            .setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    /**
     * Devices that changed transactions in this budget (letter = YNAB4 short device ID), with a hint
     * to recognise them. Ticked ones count as "mine" besides this phone. Stored as "extra_devices".
     */
    /**
     * §157: how many device letters the budget uses. YNAB 4 for Windows sees only A–Z and the original
     * gives every new device the letter after the highest one, so the count of free letters matters.
     */
    void letters(LinearLayout layout, java.util.TreeSet<String> used) {
        int taken = used.size();
        String next = DeviceLetters.nextNew(used);
        // Plus fills gaps too (099), so every unused letter of A–Z is a place for a new device.
        int left = 26 - taken;
        String text = tr("Device letters: ", "Litery urządzeń: ") + taken + tr(" used", " zajętych")
            + tr(", new devices left: ", ", zostało dla nowych urządzeń: ") + left
            + (next == null ? "" : tr(" (next: ", " (następna: ") + next + ")") + ". "
            + tr("YNAB 4 for Windows sees only letters A–Z; a reinstall of the same app usually keeps its letter.",
                 "YNAB 4 na Windows widzi tylko litery A–Z; ponowna instalacja tej samej aplikacji zwykle zachowuje swoją literę.");
        TextView summary = new TextView(this);
        summary.setText(text);
        summary.setContentDescription("devices-letters:" + taken + ":" + left);
        summary.setPadding(0, 8, 0, 8);
        if (left <= 3) summary.setTextColor(0xffb3261e);
        layout.addView(summary);
        long stopped = getSharedPreferences(DeviceLetters.PREFS, 0).getLong(DeviceLetters.EXHAUSTED_AT, 0);
        if (stopped > 0) {
            TextView warning = new TextView(this);
            warning.setText(tr("A registration was stopped on ", "Rejestrację zatrzymano ")
                + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new java.util.Date(stopped))
                + tr(": all 26 letters were used. A 27th device would sync changes the Windows app never sees.",
                     ": wszystkie 26 liter były zajęte. 27. urządzenie wysyłałoby zmiany, których YNAB na Windows nigdy nie zobaczy."));
            warning.setTextColor(0xffb3261e);
            warning.setContentDescription("devices-letters-exhausted");
            layout.addView(warning);
        }
    }

    /**
     * §158: devices the Windows app cannot see (ids longer than one letter, e.g. AA added by the original
     * YNAB, which has no Plus guard). Taken from the device metadata the original sync already read.
     */
    void invisibleDevices(LinearLayout layout, String budget) {
        for (Map.Entry<String, ?> entry : getSharedPreferences("cb-device-catalog", 0).getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(budget + "|")) continue;
            String id = key.substring(budget.length() + 1);
            if (id.length() <= 1) continue;
            org.json.JSONObject metadata = DeviceCatalog.get(this, budget, id);
            TextView warning = new TextView(this);
            warning.setText(tr("Device ", "Urządzenie ") + id + " (" + metadata.optString("friendlyName", "?") + ", "
                + metadata.optString("deviceType", "?") + tr(") is not visible to YNAB 4 for Windows: its changes never reach the computer. "
                    + "It was probably added by the original YNAB app, which has no Plus limit.",
                ") nie jest widoczne dla YNAB 4 na Windows: jego zmiany nigdy nie trafią na komputer. "
                    + "Prawdopodobnie dodał je oryginalny YNAB, który nie ma ograniczenia Plus."));
            warning.setTextColor(0xffb3261e);
            warning.setContentDescription("devices-invisible:" + id);
            warning.setPadding(0, 8, 0, 8);
            layout.addView(warning);
        }
    }

    /**
     * Devices without recent visible transactions, oldest first. The takeover checks the live
     * registration timestamp and version history separately during sync.
     */
    void unusedDevices(LinearLayout layout, SQLiteDatabase db, String budget, java.util.TreeSet<String> used, String self) {
        java.util.Calendar limit = java.util.Calendar.getInstance();
        limit.add(java.util.Calendar.DAY_OF_YEAR, -60);
        String before = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(limit.getTime());
        Map<String, String> last = new HashMap<String, String>();
        try (Cursor c = db.rawQuery("SELECT substr(version, 1, instr(version, '-') - 1), MAX(date) FROM txnTable"
            + " WHERE deleted = 0 AND instr(version, '-') > 1 GROUP BY 1", null)) {
            while (c.moveToNext()) last.put(c.getString(0), c.getString(1));
        }
        List<String[]> rows = new ArrayList<String[]>();
        for (String id : used) {
            if (id.equals(self)) continue;
            String date = last.get(id);
            if (date != null && date.compareTo(before) >= 0) continue;
            rows.add(new String[]{date == null ? "" : date, id});
        }
        if (rows.isEmpty()) return;
        Collections.sort(rows, (a, b) -> a[0].compareTo(b[0]));
        StringBuilder text = new StringBuilder(tr("Not used for 60+ days (could free a place later):",
            "Nieużywane od ponad 60 dni (w przyszłości można zwolnić ich miejsce):"));
        StringBuilder ids = new StringBuilder();
        for (String[] row : rows) {
            org.json.JSONObject metadata = DeviceCatalog.get(this, budget, row[1]);
            String name = metadata.optString("friendlyName", "");
            text.append("\n  ").append(row[1]).append(name.isEmpty() ? "" : " · " + name).append(" · ")
                .append(row[0].isEmpty() ? tr("no own changes left", "brak własnych zmian") : tr("last ", "ostatnio ") + row[0].substring(0, Math.min(10, row[0].length())));
            ids.append(row[1]);
        }
        TextView view = new TextView(this);
        view.setText(text.toString());
        view.setContentDescription("devices-unused:" + ids);
        view.setPadding(0, 8, 0, 8);
        layout.addView(view);
        if (self != null && DeviceTakeover.available(budget)) {
            Set<String> candidates = new LinkedHashSet<>();
            for (String[] row : rows) candidates.add(row[1]);
            Button takeOver = new Button(this);
            takeOver.setAllCaps(false);
            takeOver.setText(tr("Take over a device's place…", "Przejmij miejsce urządzenia…"));
            takeOver.setContentDescription("device-takeover");
            takeOver.setOnClickListener(v -> DeviceTakeover.choose(this, budget, self, candidates));
            layout.addView(takeOver);
        }
    }

    void devices(LinearLayout layout) {
        final Set<String> chosen = new TreeSet<String>();
        for (String d : prefs.getString("extra_devices", "").toUpperCase(Locale.ROOT).split("[,; ]+")) if (d.matches("[A-Z]{1,3}")) chosen.add(d);
        String self = null; final Map<String, String> found = new TreeMap<String, String>();
        final Map<String,String> kinds=new HashMap<String,String>(), labels=new HashMap<String,String>();
        // Devices of the open budget only: the same phone has another letter in every budget.
        File openBudget = currentDatabase();
        List<String> budgets = openBudget == null ? Collections.<String>emptyList() : Collections.singletonList(openBudget.getName());
        for (String name : budgets) {
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(getDatabasePath(name).getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try {
                    String budget="";
                    try(Cursor path=db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'",null)){if(path.moveToFirst())budget=path.getString(0);}
                    Cursor c = db.rawQuery("SELECT value FROM keyValue WHERE name = 'deviceId'", null);
                    try { if (c.moveToFirst()) self = c.getString(0); } finally { c.close(); }
                    // This phone's own letter is registered before it appears in the knowledge table.
                    // Letters from the knowledge table and from the registration files the original read:
                    // a device that registered but never synced has no knowledge entry (099 test).
                    java.util.TreeSet<String> used = DeviceLetters.used(db);
                    used.addAll(DeviceLetters.registered(this, budget));
                    if (self != null && self.matches("[A-Z]")) used.add(self);
                    letters(layout, used);
                    invisibleDevices(layout, budget);
                    unusedDevices(layout, db, budget, used, self);
                    try (Cursor data = db.rawQuery("SELECT value FROM keyValue WHERE name='dataFolder'", null)) {
                        if (data.moveToFirst()) {
                            String history = DeviceTakeover.history(this, new File(budget, data.getString(0)).getPath());
                            if (!history.isEmpty()) {
                                header(layout, tr("Device transfer history", "Historia przejmowania urządzeń"));
                                note(layout, history);
                            }
                        }
                    }
                    c = db.rawQuery("SELECT substr(version, 1, instr(version, '-') - 1) AS d, COUNT(*), MAX(date) FROM txnTable WHERE deleted = 0 AND parentId IS NULL AND instr(version, '-') > 1 GROUP BY d ORDER BY d", null);
                    try {
                        while (c.moveToNext()) {
                            String d = c.getString(0); if (d == null || !d.matches("[A-Z]{1,3}")) continue;
                            String hint = c.getInt(1) + tr(" transactions, last ", " transakcji, ostatnia ") + c.getString(2);
                            Cursor p = db.rawQuery("SELECT p.name FROM txnTable t JOIN payeeTable p ON p._id = t.payeeId WHERE t.deleted = 0 AND substr(t.version, 1, instr(t.version, '-') - 1) = ? AND p.name NOT LIKE '%:%' GROUP BY p.name ORDER BY COUNT(*) DESC LIMIT 3", new String[]{d});
                            try { StringBuilder s = new StringBuilder(); while (p.moveToNext()) s.append(s.length() == 0 ? "" : ", ").append(p.getString(0)); if (s.length() > 0) hint += tr(" — e.g. ", " — np. ") + s; } finally { p.close(); }
                            Cursor accountCounts = db.rawQuery("SELECT a.name,COUNT(*) FROM txnTable t JOIN accountTable a ON a._id=t.accountId WHERE t.deleted=0 AND t.parentId IS NULL AND substr(t.version,1,instr(t.version,'-')-1)=? GROUP BY a._id,a.name ORDER BY COUNT(*) DESC,a.name COLLATE NOCASE",new String[]{d});
                            try {
                                StringBuilder usage = new StringBuilder();
                                while(accountCounts.moveToNext()) usage.append("\n  ").append(accountCounts.getString(0)).append(" · ").append(accountCounts.getInt(1));
                                if(usage.length()>0) hint += "\n" + tr("Accounts (transactions):", "Konta (liczba transakcji):") + usage;
                            } finally { accountCounts.close(); }
                            found.put(d, hint);
                            org.json.JSONObject metadata=DeviceCatalog.get(this,budget,d);
                            kinds.put(d,DeviceCatalog.kind(metadata.optString("deviceType")));
                            labels.put(d,metadata.optString("friendlyName"));
                        }
                    } finally { c.close(); }
                } finally { db.close(); }
            } catch (RuntimeException ignored) { }
        }
        note(layout, tr("Entries from this phone are always \"mine\"", "Wpisy z tego telefonu są zawsze „moje”") + (self == null ? "" : tr(" (this phone: ", " (ten telefon: ") + self + ")")
            + tr(". Select your other devices using the account counts below.",
                 ". Rozpoznaj i zaznacz pozostałe swoje urządzenia po kontach poniżej."));
        note(layout,tr("Counts describe current, non-deleted transactions last changed by each device, not a full activity history. Splits count once.",
            "Liczby dotyczą obecnych, nieusuniętych transakcji ostatnio zmienionych przez urządzenie, a nie pełnej historii działań. Podział liczy się raz."));
        note(layout,tr("These choices affect suggestions and report filters only on this phone. They do not identify who made a purchase or change other clients.",
            "Te wybory wpływają na podpowiedzi i filtry raportów tylko na tym telefonie. Nie określają osoby kupującej i nie zmieniają innych klientów."));
        note(layout,tr("Devices include desktop apps as well as phones. Types come from the last sync metadata; missing metadata is shown as unknown.",
            "Urządzenia to także aplikacje komputerowe. Typ pochodzi z ostatnio odczytanych metadanych synchronizacji; bez nich pozostaje nierozpoznany."));
        if(self!=null && found.containsKey(self)) {
            header(layout,DeviceCatalog.label("android"));
            TextView current = new TextView(this); current.setText(tr("This phone", "Ten telefon")+" ("+self+")\n"+found.get(self));
            deviceName(layout,self);
            current.setContentDescription("device-self-usage"); current.setPadding(0,12,0,20); layout.addView(current);
        }
        for (String d : chosen) if (!found.containsKey(d) && !d.equals(self)) found.put(d, tr("no transactions in this budget", "brak transakcji w tym budżecie"));
        int shown = 0;
        for(String kind:new String[]{"desktop","android","ios","unknown"}) {
        boolean heading=false;
        for (final Map.Entry<String, String> e : found.entrySet()) {
            if (e.getKey().equals(self)) continue;
            if(!kind.equals(kinds.containsKey(e.getKey())?kinds.get(e.getKey()):"unknown"))continue;
            if(!heading){header(layout,DeviceCatalog.label(kind));heading=true;}
            LinearLayout deviceCard = new LinearLayout(this); deviceCard.setOrientation(LinearLayout.VERTICAL); deviceCard.setPadding(0,8,0,16);
            CheckBox box = new CheckBox(this); box.setText(tr("Device ","Urządzenie ")+e.getKey()); box.setContentDescription("device-" + e.getKey());
            box.setChecked(chosen.contains(e.getKey())); shown++;
            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton v, boolean mine) {
                    if (mine) chosen.add(e.getKey()); else chosen.remove(e.getKey());
                    StringBuilder s = new StringBuilder(); for (String d : chosen) s.append(s.length() == 0 ? "" : ",").append(d);
                    prefs.edit().putString("extra_devices", s.toString()).apply();
                }
            });
            deviceCard.addView(box);
            deviceName(deviceCard,e.getKey());
            String label=labels.get(e.getKey());if(label!=null&&!label.isEmpty())note(deviceCard,label);
            TextView details=new TextView(this); details.setText(e.getValue());details.setTextSize(14);details.setContentDescription("device-usage:"+e.getKey());deviceCard.addView(details);
            layout.addView(deviceCard);
        }
        }
        if (shown == 0) note(layout, self == null ? tr("No budget on this phone yet.", "Brak otwartego budżetu na tym telefonie.") : tr("No other devices have entered transactions yet.", "Żadne inne urządzenie nie wpisało jeszcze transakcji."));
    }

    void deviceName(LinearLayout layout,final String device){
        final Button name=new Button(this);name.setAllCaps(false);final String label=DeviceNames.all(this).get(device);
        name.setText((label==null?tr("Name device","Nazwij urządzenie"):label)+" ("+device+")");name.setContentDescription("device-name:"+device);layout.addView(name);
        name.setOnClickListener(new View.OnClickListener(){public void onClick(View v){
            final EditText input=new EditText(PlusSettingsActivity.this);input.setSingleLine(true);input.setHint(tr("Device name","Nazwa urządzenia"));input.setText(DeviceNames.all(PlusSettingsActivity.this).get(device));input.setContentDescription("device-name-input");
            new android.app.AlertDialog.Builder(PlusSettingsActivity.this).setTitle(tr("Device ","Urządzenie ")+device).setView(input)
                .setPositiveButton(tr("Save","Zapisz"),new android.content.DialogInterface.OnClickListener(){public void onClick(android.content.DialogInterface d,int which){DeviceNames.set(PlusSettingsActivity.this,device,input.getText().toString());String label=DeviceNames.all(PlusSettingsActivity.this).get(device);name.setText((label==null?tr("Name device","Nazwij urządzenie"):label)+" ("+device+")");}})
                .setNegativeButton(tr("Cancel","Anuluj"),null).show();
        }});
    }

    void accounts(LinearLayout layout) {
        final Set<String> excluded = new TreeSet<String>(prefs.getStringSet("excluded", Collections.<String>emptySet()));
        int shown = 0;
        for (String name : databaseList()) {
            if (!name.contains("#") || name.endsWith("-journal") || name.endsWith("-wal") || name.endsWith("-shm")) continue;
            File file = getDatabasePath(name);
            try {
                SQLiteDatabase db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                try {
                    Cursor c = db.rawQuery("SELECT guid, name FROM accountTable WHERE deleted = 0 AND hidden = 0 ORDER BY name", null);
                    try {
                        while (c.moveToNext()) {
                            final String guid = c.getString(0);
                            CheckBox box = new CheckBox(this); box.setText(c.getString(1)); box.setContentDescription("account-" + c.getString(1));
                            box.setChecked(!excluded.contains(guid)); shown++;
                            box.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                                public void onCheckedChanged(CompoundButton v, boolean mine) {
                                    if (mine) excluded.remove(guid); else excluded.add(guid);
                                    prefs.edit().putStringSet("excluded", new TreeSet<String>(excluded)).apply();
                                }
                            });
                            layout.addView(box);
                        }
                    } finally { c.close(); }
                } finally { db.close(); }
            } catch (RuntimeException error) { note(layout, tr("Could not read the budget accounts.", "Nie udało się odczytać kont budżetu.")); }
        }
        if (shown == 0) note(layout, tr("No budget on this phone yet.", "Brak otwartego budżetu na tym telefonie."));
    }
}

package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import com.youneedabudget.ynab.core.cloud.z;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reuses a device registration during the original serialized budget sync. */
public final class DeviceTakeover {
    private static final String PENDING = "pending";
    private static final long MIN_AGE = TimeUnit.DAYS.toMillis(60);
    private static final Pattern GUID = Pattern.compile("[A-Za-z0-9-]{8,80}");
    private static final Pattern DIFF_END = Pattern.compile(".*_([A-Z]{1,3})-([0-9]+)\\.ydiff");

    private DeviceTakeover() { }

    static boolean available(String budget) {
        if (!PlusEdition.TEST_ONLY || budget == null || !budget.toUpperCase(Locale.ROOT).matches(".*(?:TEST|COPY).*")) {
            return false;
        }
        String backend = BackendSelector.chosen();
        return BackendSelector.FOLDER.equals(backend) || BackendSelector.WEBDAV.equals(backend)
            || (BackendSelector.DROPBOX.equals(backend) && DropboxTakeoverStorage.configured());
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(WritePolicy.DEVICE_TAKEOVER_PREFS, Context.MODE_PRIVATE);
    }

    static String history(Context context, String data) {
        try {
            JSONArray history = new JSONArray(prefs(context).getString("history:" + data, "[]"));
            StringBuilder text = new StringBuilder();
            java.text.DateFormat date = java.text.DateFormat.getDateInstance();
            for (int index = history.length() - 1; index >= 0; index--) {
                JSONObject transfer = history.getJSONObject(index);
                if (text.length() > 0) text.append('\n');
                text.append(date.format(new java.util.Date(transfer.getLong("at")))).append(" · ")
                    .append(transfer.getString("id")).append(" · ")
                    .append(transfer.getString("previousName")).append(" → ").append(transfer.getString("newName"));
            }
            return text.toString();
        } catch (Exception missing) {
            return "";
        }
    }

    /** The base installation identity remains valid in every other budget. */
    public static String registrationGuid(Context context, File data, String original) {
        return WritePolicy.registrationGuid(context, data, original);
    }

    // ---------------------------------------------------------------- §160: no free letter for a new device

    private static final String OFFER = "offerNew", PENDING_NEW = "pendingNew";
    private static final String AUTOMATIC = "automaticNew";

    // Flipped only by the optional Morphe patch.
    public static boolean earlyRecoveryEnabled() {
        return false;
    }

    private static boolean earlyRecoveryTransportAvailable(File data) {
        if (PlusEdition.TEST_ONLY) return available(data.getParent());
        // When the optional backend selector is absent the original app uses Dropbox directly.
        String backend = BackendSelector.chosen();
        return backend == null || BackendSelector.DROPBOX.equals(backend);
    }

    private static boolean earlyRecoveryAvailable(Context context, File data) {
        if (!earlyRecoveryEnabled() || !earlyRecoveryTransportAvailable(data)) return false;
        for (String other : new String[]{"com.youneedabudget.ynab.app.market", "com.youneedabudget.ynab.app.market.plus",
            "com.youneedabudget.ynab.app.market.plus.test"}) {
            if (other.equals(context.getPackageName())) continue;
            try {
                context.getPackageManager().getPackageInfo(other, 0);
                return false;
            } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
                // Only offer recovery when this is the sole YNAB installation.
            }
        }
        return true;
    }

    private static boolean sameAndroidModel(JSONObject peer) {
        return "Android".equalsIgnoreCase(peer.optString("deviceType"))
            && android.os.Build.MODEL.equalsIgnoreCase(peer.optString("friendlyName"));
    }

    static boolean offerEarlyRecovery(Context context, Object accessor, File data, File devices, String next) {
        try {
            if (!earlyRecoveryAvailable(context, data)) return false;
            return offerForNewDevice(context, (z) accessor, data, devices, next);
        } catch (Exception unavailable) {
            Log.w("ClassicBudgetPlus", "DEVICE_RECOVERY_OFFER_FAILED " + diagnostic(unavailable));
            return false;
        }
    }

    /**
     * Registration of a new device (core.cloud.r, before it reads the device files). A transfer chosen on
     * the "no free place" screen replaces the retired X.ydevice with a new GUID; the original then finds
     * that GUID and goes on as "Previously registered as device X", the same path a reinstalled phone takes.
     */
    public static String registrationGuid(Context context, Object accessor, File data, File devices, String original)
        throws IOException {
        String scoped = WritePolicy.registrationGuid(context, data, original);
        SharedPreferences state = prefs(context);
        String pending = state.getString(PENDING_NEW, null);
        if (pending == null) return scoped;
        boolean pinned = false;
        boolean recovery = false;
        try {
            JSONObject transfer = new JSONObject(pending);
            if (!data.getPath().equals(transfer.getString("data"))) return scoped;
            pinned = transfer.has("payload");
            recovery = transfer.has("recoveryProfile");
            if (recovery) {
                // Once the replacement has started, finish it on the same profile even if the offer is now disabled.
                require(pinned ? earlyRecoveryTransportAvailable(data) : earlyRecoveryAvailable(context, data),
                    "TAKEOVER_UNAVAILABLE");
            } else {
                require(available(data.getParent()), "TAKEOVER_UNAVAILABLE");
            }
            z backend = (z) accessor;
            TakeoverStorage storage = storage(context, backend, recovery);
            String id = transfer.getString("id");
            require(id.matches("[A-Z]"), "TAKEOVER_BAD_ID");
            File target = new File(devices, id + ".ydevice");
            String profile = storage.takeoverProfile(target);
            if (!pinned) {
                prepareNew(context, backend, storage, data, devices, target, transfer, profile, scoped);
                save(state.edit().putString(PENDING_NEW, transfer.toString()));
                pinned = true;
            }
            require(profile.equals(transfer.getString("profile")), "TAKEOVER_PROFILE_CHANGED");
            String guid = transfer.getString("guid");
            // The Folder transport must also finish cleanup if the replacement is already visible.
            storage.replaceRegistration(target, transfer);
            require(guid.equals(parse(storage.registrationBytes(target)).optString("deviceGUID")),
                "TAKEOVER_REPLACE_NOT_CONFIRMED");
            save(state.edit().putString("guid:" + data.getPath(), guid));
            JSONArray history = new JSONArray(state.getString("history:" + data.getPath(), "[]"));
            history.put(new JSONObject().put("id", id).put("at", transfer.getLong("at"))
                .put("previousName", transfer.getString("previousName")).put("newName", transfer.getString("newName")));
            save(state.edit().putString("history:" + data.getPath(), history.toString()).remove(PENDING_NEW).remove(OFFER));
            Log.i("ClassicBudgetPlus", "DEVICE_TAKEOVER_NEW_CONFIRMED " + id);
            explain(context, PlusLang.tr("Device place transferred.", "Miejsce urządzenia zostało przejęte."));
            return guid;
        } catch (Exception failure) {
            Log.w("ClassicBudgetPlus", "DEVICE_TAKEOVER_NEW_STOPPED " + failure.getClass().getSimpleName()
                + " " + diagnostic(failure));
            if (pinned && !(failure instanceof TakeoverStorage.Changed)) {
                // The replacement may be half done: keep the record, opening the budget again finishes it.
                explain(context, PlusLang.tr("Transfer interrupted. Open the budget again to finish it.",
                    "Przejęcie przerwane. Otwórz budżet ponownie, aby je dokończyć."));
                throw new IOException("TAKEOVER_RECOVERY_REQUIRED");
            }
            save(state.edit().remove(PENDING_NEW));
            explain(context, refusal(failure));
            if (recovery) {
                // A rejected recovery must return to the chooser, without registering a different letter.
                throw new IOException("DEVICE_RECOVERY_REFUSED");
            }
            return scoped;
        }
    }

    private static void prepareNew(Context context, z accessor, TakeoverStorage storage, File data, File devices,
                                   File target, JSONObject transfer, String profile, String currentGuid) throws Exception {
        String id = transfer.getString("id");
        byte[] previous = storage.registrationBytes(target);
        JSONObject peer = parse(previous);
        String oldGuid = transfer.getString("oldGuid");
        require(GUID.matcher(oldGuid).matches() && oldGuid.equals(peer.getString("deviceGUID"))
            && id.equals(peer.getString("shortDeviceId")), "TAKEOVER_REGISTRATION_CHANGED");
        long now = System.currentTimeMillis();
        long modified = storage.registrationModified(target);
        if (transfer.has("recoveryProfile")) {
            require(profile.equals(transfer.getString("recoveryProfile")), "TAKEOVER_PROFILE_CHANGED");
            require(sameAndroidModel(peer) && WritePolicy.sha(previous).equals(transfer.getString("sourceHash")),
                "TAKEOVER_REGISTRATION_CHANGED");
            for (File file : accessor.c(devices, ".ydevice")) {
                JSONObject registered = parse(storage.registrationBytes(new File(devices, file.getName())));
                require(!currentGuid.equalsIgnoreCase(registered.optString("deviceGUID")), "TAKEOVER_ALREADY_REGISTERED");
            }
        } else {
            require(modified > 0 && modified <= now - MIN_AGE, "TAKEOVER_ACTIVE_DEVICE");
        }
        require(!holdsFull(peer), "TAKEOVER_DEVICE_CONTAINS_FULL");
        int version = versionOf(WritePolicy.knowledge(peer.optString("knowledge", "")), id);
        verifyDiffs(accessor, new File(data, oldGuid), id, version);
        verifyPeers(accessor, storage, devices, target, id, version, now);
        verifyFullFiles(accessor, storage, data, devices, id, version);
        String guid = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        // Same fields as a new Android registration of the original (core.cloud.r$a); the knowledge stays
        // the retired device's own: this phone claims nothing it has not imported yet.
        String name = android.os.Build.MODEL;
        String payload = new JSONObject(peer.toString()).put("deviceGUID", guid).put("shortDeviceId", id)
            .put("deviceType", "Android").put("friendlyName", name)
            .put("YNABVersion", "Android build " + appVersion(context)).put("formatVersion", "1.2")
            .put("hasFullKnowledge", false).put("knowledgeInFullBudgetFile", JSONObject.NULL).toString();
        transfer.put("profile", profile).put("previous", new String(previous, WritePolicy.UTF8)).put("payload", payload)
            .put("guid", guid).put("version", version).put("token", guid.replace("-", "").toLowerCase(Locale.ROOT))
            .put("at", now).put("previousName", peer.optString("friendlyName")).put("newName", name);
    }

    /** Every device file that points to a full budget must already contain the retired device's history. */
    private static void verifyFullFiles(z accessor, TakeoverStorage storage, File data, File devices,
                                        String id, int version) throws Exception {
        int checked = 0;
        for (File file : accessor.c(devices, ".ydevice")) {
            JSONObject peer = parse(storage.registrationBytes(new File(devices, file.getName())));
            if (!holdsFull(peer)) continue;
            String declared = peer.isNull("knowledgeInFullBudgetFile")
                ? peer.optString("knowledge", "") : peer.optString("knowledgeInFullBudgetFile", "");
            require(versionOf(WritePolicy.knowledge(declared), id) >= version, "TAKEOVER_FULL_BEHIND");
            File full = new File(new File(data, peer.getString("deviceGUID")), "Budget.yfull");
            try (InputStream stream = accessor.b(full)) {
                require(versionOf(fullKnowledge(stream), id) >= version, "TAKEOVER_FULL_BEHIND");
            }
            checked++;
        }
        require(checked > 0, "TAKEOVER_FULL_MISSING");
    }

    private static boolean holdsFull(JSONObject device) {
        return device.optBoolean("hasFullKnowledge") || !device.isNull("knowledgeInFullBudgetFile");
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception unknown) {
            return "unknown";
        }
    }

    private static TakeoverStorage storage(Context context, z accessor) throws Exception {
        return storage(context, accessor, false);
    }

    private static TakeoverStorage storage(Context context, z accessor, boolean recovery) throws Exception {
        if (recovery && !PlusEdition.TEST_ONLY) return new DropboxTakeoverStorage(context, accessor, true);
        return accessor instanceof TakeoverStorage ? (TakeoverStorage) accessor
            : new DropboxTakeoverStorage(context, accessor, false);
    }

    /**
     * Called by DeviceLetters when all of A–Z are registered. Lists the registrations Plus could take over
     * (inactive at least 60 days, without the full budget) and opens the choice screen. Returns false when
     * there is nothing to offer; the original "Woops" then stays the only answer.
     */
    static boolean offerForNewDevice(Context context, Object accessor, File data, File devices) {
        try {
            if (!available(data.getParent())) return false;
            return offerForNewDevice(context, (z) accessor, data, devices, null);
        } catch (Exception unavailable) {
            Log.w("ClassicBudgetPlus", "DEVICE_TAKEOVER_NEW_OFFER_FAILED " + diagnostic(unavailable));
            return false;
        }
    }

    private static synchronized boolean offerForNewDevice(Context context, z accessor, File data, File devices,
                                                           String next) throws Exception {
        boolean recovery = next != null;
        TakeoverStorage storage = storage(context, accessor, recovery);
        SharedPreferences state = prefs(context);
        // A registration path supplies the root/profile identity even before its file exists.
        String profile = recovery ? storage.takeoverProfile(new File(devices, next + ".ydevice")) : null;
        String automatic = AUTOMATIC + ":" + profile + ":" + data.getPath();
        if (recovery && state.contains(automatic)) {
            save(state.edit().remove(automatic));
            return false;
        }
        long now = System.currentTimeMillis();
        JSONArray candidates = new JSONArray();
        for (File file : accessor.c(devices, ".ydevice")) {
            File device = new File(devices, file.getName());
            byte[] source = storage.registrationBytes(device);
            JSONObject peer = parse(source);
            String id = peer.optString("shortDeviceId");
            String guid = peer.optString("deviceGUID");
            if (!id.matches("[A-Z]") || !GUID.matcher(guid).matches() || holdsFull(peer)) continue;
            if (recovery && (!sameAndroidModel(peer) || !file.getName().equalsIgnoreCase(id + ".ydevice"))) continue;
            long modified = storage.registrationModified(device);
            if (!recovery && (modified <= 0 || modified > now - MIN_AGE)) continue;
            candidates.put(new JSONObject().put("id", id).put("guid", guid).put("modified", modified)
                .put("name", peer.optString("friendlyName")).put("sourceHash", WritePolicy.sha(source)));
        }
        if (candidates.length() == 0) return false;
        String token = UUID.randomUUID().toString();
        JSONObject offer = new JSONObject().put("data", data.getPath()).put("candidates", candidates).put("token", token);
        if (recovery) offer.put("recoveryProfile", profile).put("next", next).put("automatic", automatic);
        save(state.edit().putString(OFFER, offer.toString()));
        android.content.Intent intent = new android.content.Intent(context, PlusSettingsActivity.class)
            .putExtra("takeover", "new").putExtra("takeoverOffer", token)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        Log.i("ClassicBudgetPlus", (recovery ? "DEVICE_RECOVERY_OFFERED " : "DEVICE_TAKEOVER_NEW_OFFERED ") + candidates.length());
        return true;
    }

    /** "No free place" screen: one retired device to take over; the original "Try Again" returns to the budget list. */
    static void chooseForNewDevice(Activity activity) {
        try {
            JSONObject offer = new JSONObject(prefs(activity).getString(OFFER, "{}"));
            require(offer.getString("token").equals(activity.getIntent().getStringExtra("takeoverOffer")),
                "TAKEOVER_OFFER_CHANGED");
            boolean recovery = offer.has("recoveryProfile");
            String next = recovery ? offer.getString("next") : null;
            JSONArray candidates = offer.getJSONArray("candidates");
            String[] labels = new String[candidates.length()];
            java.text.DateFormat date = java.text.DateFormat.getDateInstance();
            for (int index = 0; index < candidates.length(); index++) {
                JSONObject candidate = candidates.getJSONObject(index);
                String name = candidate.optString("name");
                String device = name.isEmpty() ? candidate.getString("id") : name + " (" + candidate.getString("id") + ")";
                long modified = candidate.getLong("modified");
                labels[index] = device + "\n" + (modified > 0
                    ? PlusLang.tr("last change: ", "ostatnia zmiana: ") + date.format(new java.util.Date(modified))
                    : PlusLang.tr("Last change unknown", "Brak daty ostatniej zmiany"));
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(recovery
                    ? PlusLang.tr("Register this phone as " + next + "?", "Zarejestrować telefon jako " + next + "?")
                    : PlusLang.tr("All 26 device letters are taken", "Wszystkie 26 liter urządzeń jest zajętych"))
                .setItems(labels, (dialog, position) -> {
                    try {
                        // The introduction is a non-selectable header in the original dialog list.
                        int index = position - ((AlertDialog) dialog).getListView().getHeaderViewsCount();
                        confirmNew(activity, offer, candidates.getJSONObject(index), labels[index]);
                    } catch (Exception broken) {
                        activity.finish();
                    }
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> activity.finish())
                .setOnCancelListener(dialog -> activity.finish());
            if (recovery) builder.setPositiveButton(
                PlusLang.tr("Register as " + next, "Zarejestruj jako " + next),
                (choice, which) -> {
                    try {
                        SharedPreferences state = prefs(activity);
                        require(offer.toString().equals(state.getString(OFFER, "")), "TAKEOVER_OFFER_CHANGED");
                        save(state.edit().putBoolean(offer.getString("automatic"), true).remove(OFFER));
                        explain(activity, PlusLang.tr("Open the budget again.", "Otwórz budżet ponownie."));
                    } catch (Exception unavailable) {
                        explain(activity, PlusLang.tr("The choice could not be saved.", "Nie udało się zapisać wyboru."));
                    }
                    activity.finish();
                });
            AlertDialog dialog = builder.create();
            TextView introduction = new TextView(activity);
            introduction.setTextAppearance(activity, android.R.style.TextAppearance_Medium);
            float density = activity.getResources().getDisplayMetrics().density;
            int horizontal = Math.round(16 * density);
            int vertical = Math.round(12 * density);
            introduction.setPadding(horizontal, vertical, horizontal, vertical);
            introduction.setText(recovery ? PlusLang.tr(
                "Plus did not find a registration for this installation. The list shows phones of the same model. "
                    + "That alone cannot identify your phone. If unsure, choose the new letter.",
                "Plus nie znalazł rejestracji tej instalacji. Na liście są telefony o tym samym modelu. "
                    + "Sam model nie pozwala rozpoznać Twojego telefonu. Jeśli nie masz pewności, wybierz nową literę.")
                : PlusLang.tr(
                "Choose a device that will never sync this budget again. If no device has been retired permanently, tap Cancel.",
                "Wybierz urządzenie, które już nigdy nie zsynchronizuje tego budżetu. "
                    + "Jeśli żadne urządzenie nie zostało wycofane na stałe, wybierz „Anuluj”."));
            dialog.getListView().addHeaderView(introduction, null, false);
            dialog.show();
        } catch (Exception missing) {
            explain(activity, PlusLang.tr("Open the budget again.", "Otwórz budżet ponownie."));
            activity.finish();
        }
    }

    private static void confirmNew(Activity activity, JSONObject offer, JSONObject candidate, String label) {
        String device = label.split("\n")[0];
        String letter = candidate.optString("id");
        boolean recovery = offer.has("recoveryProfile");
        new AlertDialog.Builder(activity)
            .setTitle(recovery
                ? PlusLang.tr("Recover letter " + letter + "?", "Odzyskać literę " + letter + "?")
                : PlusLang.tr("Take over letter " + letter + "?", "Przejąć literę " + letter + "?"))
            .setMessage(recovery ? PlusLang.tr(
                "Selected registration: " + device + ".\n\n"
                    + "Recover this letter only if it belonged to your previous installation and that installation "
                    + "will never sync this budget again. When you reopen the budget, Plus will check synchronization first. "
                    + "If the check fails, no letter is assigned.",
                "Wybrana rejestracja: " + device + ".\n\n"
                    + "Odzyskaj tę literę tylko wtedy, gdy należała do Twojej poprzedniej instalacji i ta instalacja "
                    + "już nigdy nie zsynchronizuje tego budżetu. Po ponownym otwarciu budżetu Plus sprawdzi synchronizację. "
                    + "Jeśli kontrola się nie powiedzie, żadna litera nie zostanie przypisana.")
                : PlusLang.tr(
                "Selected device: " + device + ".\n\n"
                    + "Take over this letter only if the selected device will never sync this budget again. "
                    + "When you reopen the budget, Plus will check synchronization before transferring the letter.",
                "Wybrane urządzenie: " + device + ".\n\n"
                    + "Przejmij tę literę tylko wtedy, gdy wybrane urządzenie już nigdy nie zsynchronizuje tego budżetu. "
                    + "Po ponownym otwarciu budżetu Plus sprawdzi synchronizację przed przejęciem litery."))
            .setNegativeButton(PlusLang.tr("Back", "Wróć"), (dialog, which) -> chooseForNewDevice(activity))
            .setOnCancelListener(dialog -> chooseForNewDevice(activity))
            .setPositiveButton(recovery ? PlusLang.tr("Recover " + letter, "Odzyskaj " + letter)
                : PlusLang.tr("Take over " + letter, "Przejmij " + letter), (dialog, which) -> {
                try {
                    SharedPreferences state = prefs(activity);
                    require(offer.toString().equals(state.getString(OFFER, "")) && !state.contains(PENDING_NEW),
                        "TAKEOVER_OFFER_CHANGED");
                    JSONObject request = new JSONObject().put("data", offer.getString("data")).put("id", candidate.getString("id"))
                        .put("oldGuid", candidate.getString("guid"));
                    if (recovery) request.put("recoveryProfile", offer.getString("recoveryProfile"))
                        .put("sourceHash", candidate.getString("sourceHash"));
                    save(state.edit().putString(PENDING_NEW, request.toString()).remove(OFFER));
                    explain(activity, PlusLang.tr("Open the budget again.", "Otwórz budżet ponownie."));
                } catch (Exception unavailable) {
                    explain(activity, PlusLang.tr("The choice could not be saved.", "Nie udało się zapisać wyboru."));
                }
                activity.finish();
            }).show();
    }

    static void choose(Activity activity, String budget, String self, Set<String> ids) {
        try {
            BudgetStore store = BudgetStore.open(activity);
            require(budget.equals(store.budgetFolder) && self.equals(store.value("deviceId")), "TAKEOVER_BUDGET_CHANGED");
            if (prefs(activity).contains(PENDING)) {
                if (store.syncNow()) explain(activity, checking());
                return;
            }
            java.util.List<String> letters = new java.util.ArrayList<>();
            java.util.List<String> labels = new java.util.ArrayList<>();
            for (String id : ids) {
                JSONObject peer = DeviceCatalog.get(activity, budget, id);
                if (!id.matches("[A-Z]") || id.equals(self) || !GUID.matcher(peer.optString("deviceGUID")).matches()) continue;
                String name = peer.optString("friendlyName");
                String label = name.isEmpty() ? id : name + " (" + id + ")";
                letters.add(id);
                labels.add(label);
            }
            if (letters.isEmpty()) {
                explain(activity, PlusLang.tr("No unused device is listed.", "Lista nie zawiera nieużywanego urządzenia."));
                return;
            }
            new AlertDialog.Builder(activity)
                .setTitle(PlusLang.tr("Take over a device's place", "Przejmij miejsce urządzenia"))
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    String id = letters.get(index);
                    JSONObject peer = DeviceCatalog.get(activity, budget, id);
                    confirm(activity, store, self, id, labels.get(index), peer.optString("deviceGUID"));
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        } catch (Exception unavailable) {
            explain(activity, PlusLang.tr("Open the budget and sync it first.", "Najpierw otwórz i zsynchronizuj budżet."));
        }
    }

    private static void confirm(Activity activity, BudgetStore store, String self, String id, String label, String oldGuid) {
        new AlertDialog.Builder(activity)
            .setTitle(PlusLang.tr("Take over " + label + "?", "Przejąć miejsce " + label + "?"))
            .setMessage(PlusLang.tr(
                "Confirm that you will no longer open this budget on the retired device and that it is closed in the other YNAB apps on this phone. Plus will check that all changes are synced.",
                "Potwierdź, że nie otworzysz już tego budżetu na wycofanym urządzeniu i że jest on zamknięty w pozostałych aplikacjach YNAB na tym telefonie. Plus sprawdzi, czy wszystkie zmiany zostały zsynchronizowane."))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(PlusLang.tr("Take over", "Przejmij"), (dialog, which) -> {
                try {
                    String data = new File(store.budgetFolder, store.dataFolder).getPath();
                    JSONObject request = new JSONObject()
                        .put("data", data).put("self", self).put("id", id).put("oldGuid", oldGuid);
                    require(!prefs(activity).contains(PENDING), "TAKEOVER_ALREADY_PENDING");
                    save(prefs(activity).edit().putString(PENDING, request.toString()));
                    if (!store.syncNow()) {
                        save(prefs(activity).edit().remove(PENDING));
                        throw new IOException("TAKEOVER_SYNC_UNAVAILABLE");
                    }
                    explain(activity, checking());
                } catch (Exception unavailable) {
                    explain(activity, PlusLang.tr("Sync could not be started.", "Nie udało się uruchomić synchronizacji."));
                }
            }).show();
    }

    private static String checking() {
        return PlusLang.tr("Checking the device during sync…", "Sprawdzam urządzenie podczas synchronizacji…");
    }

    private static void explain(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }

    /** Runs before the original constructor reads its own .ydevice and verifies the counters. */
    public static String beforeSync(Context context, Object cloud, Object local, String self) throws Exception {
        SharedPreferences state = prefs(context);
        String pending = state.getString(PENDING, null);
        if (pending == null) return self;
        JSONObject transfer = new JSONObject(pending);
        File data = (File) DeviceCatalog.field(cloud, "c");
        if (!data.getPath().equals(transfer.getString("data"))) return self;
        if (!available(data.getParent())) return self;
        boolean pinned = transfer.has("payload");
        try {
            z accessor = (z) DeviceCatalog.field(cloud, "a");
            TakeoverStorage storage = storage(context, accessor);
            String id = transfer.getString("id");
            String source = transfer.getString("self");
            require(id.matches("[A-Z]") && !id.equals(source) && WritePolicy.shortId(source), "TAKEOVER_BAD_ID");
            File devices = new File(data, "Devices");
            File target = new File(devices, id + ".ydevice");
            String profile = storage.takeoverProfile(target);
            SQLiteDatabase db = (SQLiteDatabase) BudgetStore.no(BudgetStore.no(local, "i"), "c");
            if (!pinned) {
                require(self.equals(source), "TAKEOVER_LOCAL_DEVICE_CHANGED");
                prepare(context, cloud, accessor, storage, db, devices, target, transfer, profile, source, id);
            }
            db.beginTransaction();
            try {
                if (!pinned) {
                    checkNoLocalEdits(db, source);
                    require(transfer.getString("knowledge").equals(encode(knowledge(db))), "TAKEOVER_LOCAL_KNOWLEDGE_CHANGED");
                    save(state.edit().putString(PENDING, transfer.toString()));
                    pinned = true;
                }
                require(profile.equals(transfer.getString("profile")), "TAKEOVER_PROFILE_CHANGED");
                int version = transfer.getInt("version");
                if (self.equals(source)) {
                    checkNoLocalEdits(db, source);
                    Map<String, Integer> localKnowledge = knowledge(db);
                    require(version == versionOf(localKnowledge, id), "TAKEOVER_LOCAL_KNOWLEDGE_CHANGED");
                    storage.replaceRegistration(target, transfer);
                    migrateCounters(local, db, id, version);
                } else {
                    // Local commit succeeded before the pending marker could be cleared.
                    require(self.equals(id), "TAKEOVER_LOCAL_DEVICE_CHANGED");
                    JSONObject current = parse(storage.registrationBytes(target));
                    require(transfer.getString("guid").equals(current.getString("deviceGUID")), "TAKEOVER_OWNER_CHANGED");
                }
                String guid = transfer.getString("guid");
                save(state.edit().putString("guid:" + data.getPath(), guid));
                save(context.getSharedPreferences("write-device", 0).edit().putString(WritePolicy.shortIdKey(), id));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            recordHistory(state, data, transfer);
            Log.i("ClassicBudgetPlus", "DEVICE_TAKEOVER_CONFIRMED " + source + " -> " + id);
            explain(context, PlusLang.tr("Device place transferred.", "Miejsce urządzenia zostało przejęte."));
            return id;
        } catch (Exception failure) {
            Log.w("ClassicBudgetPlus", "DEVICE_TAKEOVER_STOPPED " + failure.getClass().getSimpleName()
                + " " + diagnostic(failure));
            if (pinned && !(failure instanceof TakeoverStorage.Changed)) {
                explain(context, PlusLang.tr("Transfer interrupted. Sync again to finish it.", "Przejęcie przerwane. Zsynchronizuj ponownie, aby je dokończyć."));
                throw new IOException("TAKEOVER_RECOVERY_REQUIRED");
            }
            save(state.edit().remove(PENDING));
            explain(context, refusal(failure));
            return self;
        }
    }

    /** The original existing-budget constructor looks up a letter without checking its GUID. */
    public static void verifyOwnRegistration(Context context, File data, String found) throws IOException {
        if (!available(data.getParent())) return;
        String base = context.getSharedPreferences("cloudPrefs", 0).getString("guid", null);
        String expected = registrationGuid(context, data, base);
        require(expected == null || expected.equals(found), "TAKEOVER_OWNER_CHANGED");
    }

    private static void prepare(Context context, Object cloud, z accessor, TakeoverStorage storage,
                                SQLiteDatabase db, File devices, File target, JSONObject transfer,
                                String profile, String source, String id) throws Exception {
        checkNoLocalEdits(db, source);
        byte[] previous = storage.registrationBytes(target);
        JSONObject peer = parse(previous);
        String oldGuid = transfer.getString("oldGuid");
        require(GUID.matcher(oldGuid).matches() && oldGuid.equals(peer.getString("deviceGUID"))
            && id.equals(peer.getString("shortDeviceId")), "TAKEOVER_REGISTRATION_CHANGED");
        long now = System.currentTimeMillis();
        long modified = storage.registrationModified(target);
        require(modified > 0 && modified <= now - MIN_AGE, "TAKEOVER_ACTIVE_DEVICE");
        // This Android/Windows file may still be the only entry point to a full budget.
        require(!peer.optBoolean("hasFullKnowledge") && peer.isNull("knowledgeInFullBudgetFile"),
            "TAKEOVER_DEVICE_CONTAINS_FULL");
        JSONObject own = parse(storage.registrationBytes(new File(devices, source + ".ydevice")));
        String ownGuid = registrationGuid(context, devices.getParentFile(),
            context.getSharedPreferences("cloudPrefs", 0).getString("guid", ""));
        require(!oldGuid.equals(ownGuid) && ownGuid.equals(own.getString("deviceGUID"))
            && source.equals(own.getString("shortDeviceId")), "TAKEOVER_OWNER_CHANGED");
        Map<String, Integer> local = knowledge(db);
        require(versionOf(local, source) == versionOf(WritePolicy.knowledge(own.optString("knowledge", "")), source),
            "TAKEOVER_PENDING_EXPORT");
        int version = versionOf(WritePolicy.knowledge(peer.optString("knowledge", "")), id);
        require(version == versionOf(local, id), "TAKEOVER_LOCAL_KNOWLEDGE_CHANGED");
        verifyDiffs(accessor, new File(devices.getParentFile(), oldGuid), id, version);
        verifyPeers(accessor, storage, devices, target, id, version, now);
        try (InputStream full = (InputStream) BudgetStore.no(cloud, "a")) {
            require(versionOf(fullKnowledge(full), id) >= version, "TAKEOVER_FULL_BEHIND");
        }
        String guid = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
        String vector = encode(local);
        String payload = own.put("deviceGUID", guid).put("shortDeviceId", id)
            .put("knowledge", vector).put("hasFullKnowledge", false)
            .put("knowledgeInFullBudgetFile", JSONObject.NULL).toString();
        transfer.put("profile", profile).put("previous", new String(previous, WritePolicy.UTF8)).put("payload", payload)
            .put("guid", guid).put("version", version).put("token", guid.replace("-", "").toLowerCase(Locale.ROOT))
            .put("knowledge", vector).put("at", now).put("previousName", peer.optString("friendlyName"))
            .put("newName", own.optString("friendlyName"));
    }

    private static void verifyDiffs(z accessor, File folder, String id, int version) throws IOException {
        for (com.youneedabudget.ynab.core.backend.i entry : accessor.d(folder.getParentFile(), null)) {
            if (!folder.getName().equalsIgnoreCase(entry.c().getName())) continue;
            for (File diff : accessor.c(entry.c(), ".ydiff")) {
                Matcher end = DIFF_END.matcher(diff.getName());
                require(end.matches() && id.equals(end.group(1)) && Integer.parseInt(end.group(2)) <= version,
                    "TAKEOVER_UNPUBLISHED_PEER_DIFF");
            }
            return;
        }
    }

    private static void verifyPeers(z accessor, TakeoverStorage storage, File devices, File target,
                                    String id, int version, long now) throws Exception {
        if (version == 0) return;
        for (File file : accessor.c(devices, ".ydevice")) {
            if (file.getName().equalsIgnoreCase(target.getName())) continue;
            JSONObject peer = parse(storage.registrationBytes(new File(devices, file.getName())));
            if (versionOf(WritePolicy.knowledge(peer.optString("knowledge", "")), id) >= version) continue;
            long modified = storage.registrationModified(new File(devices, file.getName()));
            require(modified > 0 && modified <= now - MIN_AGE, "TAKEOVER_PEER_NEEDS_SYNC");
        }
    }

    private static Map<String, Integer> fullKnowledge(InputStream full) throws Exception {
        require(full != null, "TAKEOVER_FULL_MISSING");
        try (JsonReader json = new JsonReader(new InputStreamReader(full, WritePolicy.UTF8))) {
            json.beginObject();
            while (json.hasNext()) {
                if (!"fileMetaData".equals(json.nextName())) {
                    json.skipValue();
                    continue;
                }
                json.beginObject();
                while (json.hasNext()) {
                    if ("currentKnowledge".equals(json.nextName())) return WritePolicy.knowledge(json.nextString());
                    json.skipValue();
                }
                break;
            }
        }
        throw new IOException("TAKEOVER_FULL_MISSING_KNOWLEDGE");
    }

    private static void checkNoLocalEdits(SQLiteDatabase db, String id) throws IOException {
        Map<String, String> counters = new TreeMap<>();
        try (Cursor rows = db.rawQuery("SELECT name,value FROM counters", null)) {
            while (rows.moveToNext()) counters.put(rows.getString(0), rows.getString(1));
        }
        require(id.equals(counters.get("deviceId")) && counters.containsKey("version"), "TAKEOVER_LOCAL_DEVICE_CHANGED");
        String version = counters.get("version");
        String synced = counters.get("lastSyncedVersion");
        String published = counters.get("lastPublishedKnowledge");
        require(version.equals(synced) && (published == null || version.equals(published)), "TAKEOVER_PENDING_EXPORT");
        for (String table : new String[]{"txnTable", "scheduledTxnTable", "payeeTable", "payeeLocationsTable", "cbBudgetOutbox"}) {
            try (Cursor exists = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
                if (!exists.moveToFirst()) continue;
            }
            try (Cursor rows = db.rawQuery("SELECT 1 FROM " + table + " WHERE synced=0 LIMIT 1", null)) {
                require(!rows.moveToFirst(), "TAKEOVER_PENDING_EXPORT");
            }
        }
    }

    private static void migrateCounters(Object local, SQLiteDatabase db, String id, int version) throws Exception {
        ContentValues value = new ContentValues();
        value.put("value", id);
        require(db.update("keyValue", value, "name=?", new String[]{"deviceId"}) == 1, "TAKEOVER_LOCAL_DEVICE_MISSING");
        Object counters = BudgetStore.no(local, "e");
        BudgetStore.call(counters, "a", new Class<?>[]{String.class, int.class}, id, version);
    }

    private static Map<String, Integer> knowledge(SQLiteDatabase db) {
        Map<String, Integer> versions = new TreeMap<>();
        try (Cursor rows = db.rawQuery("SELECT name,version FROM versionVector", null)) {
            while (rows.moveToNext()) versions.put(rows.getString(0), rows.getInt(1));
        }
        return versions;
    }

    private static String encode(Map<String, Integer> versions) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : versions.entrySet()) {
            if (out.length() > 0) out.append(',');
            out.append(entry.getKey()).append('-').append(entry.getValue());
        }
        return out.toString();
    }

    private static int versionOf(Map<String, Integer> versions, String device) {
        Integer version = versions.get(device);
        return version == null ? 0 : version;
    }

    private static void recordHistory(SharedPreferences state, File data, JSONObject transfer) throws Exception {
        String key = "history:" + data.getPath();
        JSONArray history = new JSONArray(state.getString(key, "[]"));
        history.put(new JSONObject().put("id", transfer.getString("id")).put("at", transfer.getLong("at"))
            .put("previousName", transfer.getString("previousName")).put("newName", transfer.getString("newName")));
        save(state.edit().putString(key, history.toString()).remove(PENDING));
    }

    private static JSONObject parse(byte[] raw) throws Exception {
        require(raw != null, "TAKEOVER_REGISTRATION_MISSING");
        return new JSONObject(new String(raw, WritePolicy.UTF8));
    }

    private static void require(boolean condition, String code) throws IOException {
        if (!condition) throw new IOException(code);
    }

    private static void save(SharedPreferences.Editor edit) throws IOException {
        require(edit.commit(), "TAKEOVER_PERSIST_FAILED");
    }

    private static String diagnostic(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,90}") ? message : "TAKEOVER_CHECK_FAILED";
    }

    private static String refusal(Exception failure) {
        switch (diagnostic(failure)) {
            case "TAKEOVER_ACTIVE_DEVICE":
                return PlusLang.tr("The device was active in the last 60 days.", "Urządzenie było aktywne w ostatnich 60 dniach.");
            case "TAKEOVER_PENDING_EXPORT":
                return PlusLang.tr("Send the pending budget changes first.", "Najpierw wyślij oczekujące zmiany budżetu.");
            case "TAKEOVER_FULL_BEHIND":
            case "TAKEOVER_DEVICE_CONTAINS_FULL":
                return PlusLang.tr("The full budget does not safely cover this device's history.",
                    "Pełny budżet nie zabezpiecza historii tego urządzenia.");
            case "TAKEOVER_PEER_NEEDS_SYNC":
                return PlusLang.tr("Sync the other active devices first.", "Najpierw zsynchronizuj pozostałe aktywne urządzenia.");
            default:
                return PlusLang.tr("The device place could not be transferred.", "Nie udało się przejąć miejsca urządzenia.");
        }
    }
}

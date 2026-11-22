package org.classicbudget.patch;

import android.content.Context;
import com.youneedabudget.ynab.core.cloud.z;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Uses the original Dropbox session and its guarded path mapping. */
final class DropboxTakeoverStorage implements TakeoverStorage {
    private static final String SDK_ACCESSOR = "com.youneedabudget.ynab.core.cloud.a.b";
    private static final String PATCH_ROOT = "app.classicbudget.extension.DropboxFolder$Root";
    private static final String TEST_ROOT = "/YNAB4_PLUS_TEST";
    private final z accessor;
    private final String root;
    private final boolean productionRecovery;
    private final Object sdk;
    private final String token;

    DropboxTakeoverStorage(Context context, z accessor, boolean productionRecovery) throws Exception {
        this.accessor = accessor;
        this.productionRecovery = productionRecovery;
        Object delegate = accessor;
        String prefix = "";
        while (!SDK_ACCESSOR.equals(delegate.getClass().getName())) {
            String type = delegate.getClass().getName();
            if (!type.equals(PATCH_ROOT) && !type.equals(DropboxProfile.RootGuard.class.getName())) {
                throw new IOException("TAKEOVER_DROPBOX_ROOT_REQUIRED");
            }
            prefix = DeviceCatalog.field(delegate, "root") + prefix;
            delegate = DeviceCatalog.field(delegate, "delegate");
        }
        // Production uses the native accessor. It passes the absolute Dropbox path unchanged.
        verifyRoot(prefix, productionRecovery);
        root = prefix;
        Method client = delegate.getClass().getDeclaredMethod("a");
        client.setAccessible(true);
        sdk = client.invoke(delegate);
        token = context.getSharedPreferences("dropboxSettings", Context.MODE_PRIVATE)
            .getString("dropboxAccessSecret", null);
        if (token == null || token.isEmpty()) throw new IOException("TAKEOVER_DROPBOX_NOT_LINKED");
    }

    static boolean configured() {
        if (testRoot(DropboxProfile.root())) return true;
        try {
            Method folder = Class.forName("app.classicbudget.extension.DropboxFolder").getDeclaredMethod("folder");
            folder.setAccessible(true);
            String configured = (String) folder.invoke(null);
            return testRoot("/" + configured);
        } catch (Exception unavailable) {
            return false;
        }
    }

    private static boolean testRoot(String root) {
        return root != null && DropboxProfile.folderAllowed(root)
            && (root.equals(TEST_ROOT) || root.startsWith(TEST_ROOT + "/"));
    }

    private static void verifyRoot(String root, boolean productionRecovery) throws IOException {
        if (productionRecovery ? !root.isEmpty() : !testRoot(root)) {
            throw new IOException("TAKEOVER_DROPBOX_ROOT_REQUIRED");
        }
    }

    private String remote(File device) throws IOException {
        return registrationPath(device, root, productionRecovery);
    }

    static String registrationPath(File device, String root, boolean productionRecovery) throws IOException {
        verifyRoot(root, productionRecovery);
        String path = WritePolicy.logical(device);
        String[] parts = path.substring(1).split("/");
        // The original provider looks for budgets directly at / and in /YNAB.
        int budget = productionRecovery && parts.length == 5 && "YNAB".equalsIgnoreCase(parts[0]) ? 1 : 0;
        if (parts.length != budget + 4
            || !WritePolicy.budgetName("/" + parts[budget].toLowerCase(Locale.ROOT))
            || !WritePolicy.dataName(parts[budget + 1].toLowerCase(Locale.ROOT))
            || !"Devices".equalsIgnoreCase(parts[budget + 2])
            || !parts[budget + 3].matches("(?i)[A-Z]{1,3}\\.ydevice")) {
            throw new IOException("TAKEOVER_INVALID_TARGET");
        }
        return root + path;
    }

    private Object metadata(File device) throws Exception {
        return BudgetStore.call(sdk, "b", new Class<?>[]{String.class}, remote(device));
    }

    public String takeoverProfile(File device) throws Exception {
        remote(device);
        return WritePolicy.sha((root + "\n" + token).getBytes(WritePolicy.UTF8));
    }

    public long registrationModified(File device) throws IOException {
        try {
            return ((Date) DeviceCatalog.field(metadata(device), "c")).getTime();
        } catch (Exception error) {
            throw new IOException("TAKEOVER_DROPBOX_METADATA");
        }
    }

    public byte[] registrationBytes(File device) throws IOException {
        remote(device);
        return accessor.a(device).getBytes(WritePolicy.UTF8);
    }

    public void replaceRegistration(File device, JSONObject transfer) throws Exception {
        String revision = (String) BudgetStore.no(metadata(device), "c");
        byte[] current = registrationBytes(device);
        byte[] payload = transfer.getString("payload").getBytes(WritePolicy.UTF8);
        if (Arrays.equals(current, payload)) return;
        if (!Arrays.equals(current, transfer.getString("previous").getBytes(WritePolicy.UTF8))) throw new Changed();
        JSONObject mode = new JSONObject().put(".tag", "update").put("update", revision);
        JSONObject args = new JSONObject().put("path", remote(device)).put("mode", mode)
            .put("autorename", false).put("strict_conflict", true);
        // The bundled Dropbox SDK has revision updates but predates strict_conflict,
        // which also rejects the update if the existing registration was deleted.
        HttpsURLConnection upload = (HttpsURLConnection) new URL("https://content.dropboxapi.com/2/files/upload").openConnection();
        int status;
        try {
            upload.setInstanceFollowRedirects(false);
            upload.setConnectTimeout(15000);
            upload.setReadTimeout(20000);
            upload.setRequestMethod("POST");
            upload.setRequestProperty("Authorization", "Bearer " + token);
            upload.setRequestProperty("Content-Type", "application/octet-stream");
            upload.setRequestProperty("Dropbox-API-Arg", asciiJson(args.toString()));
            upload.setDoOutput(true);
            upload.setFixedLengthStreamingMode(payload.length);
            try (OutputStream body = upload.getOutputStream()) {
                body.write(payload);
            }
            status = upload.getResponseCode();
        } finally {
            upload.disconnect();
        }
        if (status == 409) throw new Changed();
        if (status != 200 || !Arrays.equals(registrationBytes(device), payload)) {
            throw new IOException("TAKEOVER_DROPBOX_REPLACE_NOT_CONFIRMED");
        }
    }

    private static String asciiJson(String json) {
        StringBuilder header = new StringBuilder();
        for (int index = 0; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (ch < 128) header.append(ch);
            else header.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) ch));
        }
        return header.toString();
    }
}

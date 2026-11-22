package app.classicbudget.extension;

import android.content.Context;
import android.content.pm.PackageManager;
import android.telephony.TelephonyManager;

/**
 * Runtime part of "Install on Android 14 and newer". With target SDK 24 Android enforces runtime
 * permissions, which the 2016 app never requests: reading the phone ID and the location then
 * throw SecurityException. These helpers return "not available" instead.
 */
@SuppressWarnings("unused")
public final class Compat {
    /** TelephonyManager.getDeviceId() or null when the app may not read it (the app then uses its own ID). */
    public static String deviceId(TelephonyManager manager) {
        try {
            return manager == null ? null : manager.getDeviceId();
        } catch (SecurityException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** True when the app holds a location permission (payee locations are then used as before). */
    public static boolean locationAllowed(Context context) {
        if (context == null) return false;
        return context.checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || context.checkCallingOrSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
}

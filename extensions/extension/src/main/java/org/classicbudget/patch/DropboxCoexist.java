package org.classicbudget.patch;

import android.content.Context;
import android.content.pm.ResolveInfo;
import java.util.*;

/**
 * Lets this app link Dropbox while the original YNAB (same Dropbox app key)
 * is installed. The SDK refuses login when two apps handle db-<key>://;
 * we drop from that list only the original YNAB's own Dropbox AuthActivity.
 * Any other foreign handler still triggers the SDK's refusal. The OAuth
 * result stays protected by the SDK's random state nonce: an app that did
 * not start the login rejects the reply.
 */
public final class DropboxCoexist {
    static final Set<String> ORIGINAL = new HashSet<String>(Arrays.asList(
        "com.youneedabudget.ynab.app.market", "com.youneedabudget.ynab.app"));
    static final String AUTH = "com.dropbox.core.android.AuthActivity";

    public static List<ResolveInfo> filter(Context context, List<ResolveInfo> handlers) {
        if (handlers == null) return null;
        String own = context.getPackageName();
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : handlers) {
            boolean original = r != null && r.activityInfo != null && !own.equals(r.activityInfo.packageName)
                && ORIGINAL.contains(r.activityInfo.packageName) && AUTH.equals(r.activityInfo.name);
            if (!original) out.add(r);
        }
        return out;
    }
}

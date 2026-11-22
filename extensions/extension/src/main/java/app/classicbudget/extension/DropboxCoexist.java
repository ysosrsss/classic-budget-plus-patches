package app.classicbudget.extension;

import android.content.Context;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime part of "Install alongside the original": lets this copy link Dropbox while the
 * original YNAB Classic (same Dropbox app key) is installed. The Dropbox SDK refuses to log in
 * when two apps handle db-&lt;key&gt;:// ("Security alert"); only the Dropbox AuthActivity of the
 * original app and of other copies built from it (com.youneedabudget.ynab.app.market.*) is dropped. Any other foreign handler still triggers the refusal,
 * and the OAuth reply stays protected by the SDK's random state value.
 */
@SuppressWarnings("unused")
public final class DropboxCoexist {
    private static final Set<String> ORIGINAL = new HashSet<String>(Arrays.asList(
        "com.youneedabudget.ynab.app.market", "com.youneedabudget.ynab.app"));
    private static final String AUTH = "com.dropbox.core.android.AuthActivity";
    /** Other copies of YNAB Classic built from it (e.g. ".plus", test builds) installed on the same phone. */
    private static final String VARIANTS = "com.youneedabudget.ynab.app.market.";

    public static List<ResolveInfo> filter(Context context, List<ResolveInfo> handlers) {
        if (handlers == null) return null;
        String own = context.getPackageName();
        List<ResolveInfo> out = new ArrayList<ResolveInfo>();
        for (ResolveInfo r : handlers) {
            String pkg = r == null || r.activityInfo == null ? null : r.activityInfo.packageName;
            boolean original = pkg != null && !own.equals(pkg) && AUTH.equals(r.activityInfo.name)
                && (ORIGINAL.contains(pkg) || pkg.startsWith(VARIANTS));
            if (!original) out.add(r);
        }
        return out;
    }
}

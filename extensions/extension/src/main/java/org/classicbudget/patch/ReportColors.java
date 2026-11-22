package org.classicbudget.patch;

import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Color;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity colours are independent of rank, period, and chart style (075).
 *
 * Groups take the categorical palette in the budget's own group order, so two groups only share a hue
 * when the budget has more than eight groups. Inside a group, a category's position picks either a
 * distinct palette hue (shown on its own, e.g. the ring after opening a group) or a clearly different
 * lightness of the group hue (shown under its group in the list tree). Payees have no budget order and
 * fall back to a hash; ClassicDonut additionally avoids repeating a hue among the visible slices.
 *
 * Both palettes passed the dataviz validator (lightness band, chroma floor, adjacent CVD and
 * normal-vision separation); the light one needs visible labels for three hues, which every chart has.
 */
final class ReportColors {
    static final int[] LIGHT = {0xff2a78d6, 0xffeb6834, 0xff1baf7a, 0xffeda100, 0xffe87ba4, 0xff008300, 0xff6250d6, 0xffe34948};
    static final int[] DARK = {0xff3987e5, 0xffd95926, 0xff199e70, 0xffc98500, 0xffd55181, 0xff0ca30c, 0xff9085e9, 0xffe66767};
    /** Lightness steps for categories under their group: darker / base / lighter, alternating. */
    private static final float[] SHADES = {-.30f, 0f, .38f, -.52f, .58f, -.15f, .22f};

    /** Position of each group key ("group:<guid>") and of each category inside its group. */
    private static final Map<String, Integer> ORDER = new HashMap<String, Integer>();

    static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
            == Configuration.UI_MODE_NIGHT_YES;
    }

    static int surface(Context context) { return night(context) ? 0xff1b1b1b : Color.WHITE; }
    static int text(Context context) { return night(context) ? 0xffeeeeee : 0xff323232; }
    static int muted(Context context) { return night(context) ? 0xffb8bec5 : 0xff626b74; }
    static int other(Context context) { return night(context) ? 0xffa0a5ad : 0xff777d85; }

    static int[] palette(Context context) { return night(context) ? DARK : LIGHT; }

    /** Reads the budget's group and category order; Hidden Categories and internal groups go last. */
    static void load(SQLiteDatabase db) {
        Map<String, Integer> order = new HashMap<String, Integer>();
        String sql = "SELECT m.guid, c.guid FROM masterCategoryTable m"
            + " LEFT JOIN categoryTable c ON c.masterCategoryId = m._id AND c.deleted = 0"
            + " WHERE m.deleted = 0 AND m.guid != 'MasterCategory/__Internal__'"
            + " ORDER BY CASE WHEN m.guid = 'MasterCategory/__Hidden__' THEN 1 ELSE 0 END, m.sequence, m._id, c.sequence, c._id";
        String lastGroup = null;
        int groupIndex = -1;
        int childIndex = 0;
        try (Cursor c = db.rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String group = "group:" + c.getString(0);
                if (!group.equals(lastGroup)) {
                    lastGroup = group;
                    groupIndex++;
                    childIndex = 0;
                    order.put(group, groupIndex);
                }
                if (!c.isNull(1)) {
                    order.put("category:" + c.getString(1), childIndex);
                    childIndex++;
                }
            }
        } catch (RuntimeException schema) {
            // Without the order (unexpected schema) colours fall back to the stable hash below.
            return;
        }
        synchronized (ORDER) {
            ORDER.clear();
            ORDER.putAll(order);
        }
    }

    static int index(String key) {
        synchronized (ORDER) {
            Integer known = ORDER.get(key);
            if (known != null) return known;
        }
        return key.hashCode() & 0x7fffffff;
    }

    static int color(Context context, String key) {
        int[] palette = palette(context);
        return palette[index(key) % palette.length];
    }

    static String key(ReportData.Group group) {
        if (group.key != null) return group.key;
        return "group:" + group.groupId + ":" + group.id;
    }

    /** Colour of a group or payee; a category shown under its group gets a lightness step of the group hue. */
    static int color(Context context, ReportData.Group group) {
        if (group.parentKey == null) return color(context, key(group));
        int base = color(context, group.parentKey);
        float step = SHADES[index(key(group)) % SHADES.length];
        return step < 0 ? mix(base, 0xff000000, -step) : mix(base, 0xffffffff, step);
    }

    /** Colour of an item shown without its parent: a category gets its own distinct palette hue. */
    static int alone(Context context, ReportData.Group group) {
        if (group.parentKey == null) return color(context, group);
        return color(context, key(group));
    }

    static int mix(int from, int to, float amount) {
        return Color.rgb(Math.round(Color.red(from) * (1 - amount) + Color.red(to) * amount),
            Math.round(Color.green(from) * (1 - amount) + Color.green(to) * amount),
            Math.round(Color.blue(from) * (1 - amount) + Color.blue(to) * amount));
    }

    /** Next palette hue not yet used, starting at the preferred one; used so visible slices never repeat. */
    static int unused(Context context, int preferred, java.util.Collection<Integer> used) {
        if (!used.contains(preferred)) return preferred;
        int[] palette = palette(context);
        int start = 0;
        for (int i = 0; i < palette.length; i++) if (palette[i] == preferred) start = i;
        for (int i = 1; i <= palette.length; i++) {
            int candidate = palette[(start + i) % palette.length];
            if (!used.contains(candidate)) return candidate;
        }
        return preferred;
    }

    /** Largest visible items that get their own hue; the rest share the grey of "Other". */
    static final int DISTINCT = 7;

    /**
     * Colours for one view (076). Budgets often have more groups than hues (15 in the real copy), so
     * entity colours alone repeat on screen. The largest DISTINCT positive items, in the order given,
     * keep their entity hue when free and otherwise take the next unused one; everything else is grey.
     * ClassicDonut uses the same rule, so ring, list and strip agree for the same data.
     */
    static Map<ReportData.Group, Integer> distinct(Context context, java.util.List<ReportData.Group> largestFirst) {
        Map<ReportData.Group, Integer> out = new java.util.IdentityHashMap<ReportData.Group, Integer>();
        java.util.List<Integer> used = new java.util.ArrayList<Integer>();
        int positive = 0;
        for (ReportData.Group g : largestFirst) if (g.amount > 0) positive++;
        // Like the ring: eight items fit as eight slices; with more, seven plus "Other".
        int limit = positive <= DISTINCT + 1 ? positive : DISTINCT;
        for (ReportData.Group g : largestFirst) {
            if (g.amount > 0 && used.size() < limit) {
                int colour = unused(context, alone(context, g), used);
                used.add(colour);
                out.put(g, colour);
            } else out.put(g, other(context));
        }
        return out;
    }

    /** A category under its group: a lightness step of the colour the group got in this view. */
    static int shade(int base, ReportData.Group child) {
        float step = SHADES[index(key(child)) % SHADES.length];
        return step < 0 ? mix(base, 0xff000000, -step) : mix(base, 0xffffffff, step);
    }

    static void ownRendering(View view) {
        // Force Dark cannot infer the semantic palette or a Canvas ring's hole.
        if (android.os.Build.VERSION.SDK_INT >= 29) view.setForceDarkAllowed(false);
    }
}

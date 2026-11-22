package org.classicbudget.patch;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Optional desktop-style ring, with native text labels and collision-free columns. */
final class ClassicDonut extends ViewGroup {
    private static final int[] COLORS = {
        0xfff04c32, 0xffbdc834, 0xff8dac49, 0xff7eb1b3, 0xffd8df88,
        0xfff5b12f, 0xffb8c9b5, 0xffe8d18b, 0xff9bb5bf, 0xff666a6e
    };
    // Eight distinct palette hues: seven slices plus "All others" (075; was ten with near-identical hues).
    private static final int MAX_SLICES = 8;

    /** Tap on a slice or its label; "All others" is not tappable, as in the desktop app. */
    interface Listener {
        void onSlice(ReportData.Group group);
    }

    private static final class Slice {
        ReportData.Group group;
        String name;
        long amount;
        int color;
        float start, sweep, desiredY, labelTop;
        boolean left;
        LinearLayout label;
        TextView title;
        TextView value;
    }

    private final ReportsActivity activity;
    private final List<Slice> slices = new ArrayList<Slice>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final long netTotal, positiveTotal;
    private final String centerTitle;
    private final Listener listener;
    private float radius, centerX, centerY;
    private int labelWidth;

    ClassicDonut(ReportsActivity activity, List<ReportData.Group> groups, String centerTitle, Listener listener) {
        super(activity);
        this.activity = activity;
        this.centerTitle = centerTitle;
        this.listener = listener;
        setWillNotDraw(false);
        ReportColors.ownRendering(this);
        setContentDescription(ReportsActivity.tr("Desktop spending chart",
                "Komputerowy wykres wydatków"));
        long net = 0;
        long positive = 0;
        List<ReportData.Group> included = new ArrayList<ReportData.Group>();
        for (ReportData.Group group : groups) {
            net += group.amount;
            if (group.amount > 0) {
                positive += group.amount;
                included.add(group);
            }
        }
        netTotal = net;
        positiveTotal = positive;
        Collections.sort(included, new Comparator<ReportData.Group>() {
            public int compare(ReportData.Group a, ReportData.Group b) {
                return a.amount == b.amount ? 0 : a.amount > b.amount ? -1 : 1;
            }
        });
        int individual = included.size() > MAX_SLICES ? MAX_SLICES - 1 : included.size();
        for (int i = 0; i < individual; i++) {
            addSlice(ReportTable.localized(included.get(i).name), included.get(i).amount, included.get(i));
        }
        long remainder = 0;
        for (int i = individual; i < included.size(); i++) {
            remainder += included.get(i).amount;
        }
        if (remainder > 0) {
            addSlice(ReportsActivity.tr("All others", "Pozostałe"), remainder, null);
        }
        float start = -180;
        for (Slice slice : slices) {
            slice.start = start;
            slice.sweep = 360f * slice.amount / positiveTotal;
            slice.left = Math.cos(Math.toRadians(start + slice.sweep / 2)) < 0;
            start += slice.sweep;
        }
    }

    /** Colour this ring gave a group, so the legend below matches it; null when not a slice. */
    Integer colorOf(ReportData.Group group) {
        for (Slice slice : slices) if (slice.group == group) return slice.color;
        return null;
    }

    private void addSlice(String name, long amount, final ReportData.Group group) {
        Slice slice = new Slice();
        slice.group = group;
        slice.name = name;
        slice.amount = amount;
        if (group == null) slice.color = ReportColors.other(activity);
        else {
            // A category shown without its group gets its own hue; visible slices never repeat a hue.
            java.util.List<Integer> used = new java.util.ArrayList<Integer>();
            for (Slice other : slices) used.add(other.color);
            slice.color = ReportColors.unused(activity, ReportColors.alone(activity, group), used);
        }
        slice.label = new LinearLayout(activity);
        slice.label.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(activity);
        title.setText(name);
        title.setTextSize(11);
        title.setTextColor(ReportColors.text(activity));
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        slice.title = title;
        slice.value = new TextView(activity);
        slice.value.setTextSize(10);
        slice.value.setTextColor(ReportColors.text(activity));
        slice.label.addView(title);
        slice.label.addView(slice.value);
        if (group != null && listener != null) {
            slice.label.setContentDescription("report-slice:" + name);
            slice.label.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    listener.onSlice(group);
                }
            });
        }
        addView(slice.label);
        slices.add(slice);
    }

    /** Slice under a tap inside the ring (angle measured like the drawing, from -180 degrees). */
    public boolean onTouchEvent(MotionEvent event) {
        if (listener == null) return super.onTouchEvent(event);
        if (event.getAction() == MotionEvent.ACTION_DOWN) return true;
        if (event.getAction() != MotionEvent.ACTION_UP) return super.onTouchEvent(event);
        float dx = event.getX() - centerX;
        float dy = event.getY() - centerY;
        if (Math.hypot(dx, dy) > radius) return false;
        float angle = (float) Math.toDegrees(Math.atan2(dy, dx));
        for (Slice slice : slices) {
            float offset = angle - slice.start;
            while (offset < 0) offset += 360;
            while (offset >= 360) offset -= 360;
            if (offset < slice.sweep) {
                if (slice.group != null) {
                    playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    listener.onSlice(slice.group);
                }
                return true;
            }
        }
        return false;
    }

    public CharSequence getAccessibilityClassName() {
        return ClassicDonut.class.getName();
    }

    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int padding = activity.px(8);
        // Size the ring from the shorter screen edge, not only the view width:
        // in landscape the width alone gave a ring taller than the visible area.
        android.util.DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        int shortEdge = Math.min(width, Math.min(screen.widthPixels, screen.heightPixels));
        radius = Math.min(activity.px(150), shortEdge * 0.22f);
        labelWidth = Math.max(1, (int) ((width - radius * 2) / 2) - activity.px(20));
        int leftHeight = 0;
        int rightHeight = 0;
        boolean wide = width >= activity.px(520);
        for (Slice slice : slices) {
            String percent = Math.round(100.0 * slice.amount / positiveTotal) + "%";
            String amount = activity.hide ? percent : activity.cash(slice.amount) + " (" + percent + ")";
            if (wide) {
                // Landscape has little height: one line per label, so ten labels
                // (nine slices and "All others") fit beside the ring.
                slice.title.setText(slice.name + "  " + amount);
                slice.value.setVisibility(GONE);
            } else {
                slice.title.setText(slice.name);
                slice.value.setText(activity.hide ? percent : activity.cash(slice.amount) + "\n" + percent);
                slice.value.setVisibility(VISIBLE);
            }
            int gravity = slice.left ? android.view.Gravity.END : android.view.Gravity.START;
            for (int i = 0; i < slice.label.getChildCount(); i++) {
                ((TextView) slice.label.getChildAt(i)).setGravity(gravity);
            }
            slice.label.measure(MeasureSpec.makeMeasureSpec(labelWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int required = slice.label.getMeasuredHeight() + activity.px(8);
            if (slice.left) {
                leftHeight += required;
            } else {
                rightHeight += required;
            }
        }
        int height = (int) (radius * 2) + activity.px(40);
        height = Math.max(height, Math.max(leftHeight, rightHeight) + padding * 2);
        setMeasuredDimension(width, height);
    }

    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        centerX = getWidth() / 2f;
        centerY = getHeight() / 2f;
        List<Slice> leftLabels = new ArrayList<Slice>();
        List<Slice> rightLabels = new ArrayList<Slice>();
        for (Slice slice : slices) {
            double angle = Math.toRadians(slice.start + slice.sweep / 2);
            slice.desiredY = centerY + (float) Math.sin(angle) * radius;
            (slice.left ? leftLabels : rightLabels).add(slice);
        }
        placeColumn(leftLabels);
        placeColumn(rightLabels);
    }

    private void placeColumn(List<Slice> labels) {
        Collections.sort(labels, new Comparator<Slice>() {
            public int compare(Slice a, Slice b) {
                return Float.compare(a.desiredY, b.desiredY);
            }
        });
        int padding = activity.px(8);
        int gap = activity.px(8);
        float cursor = padding;
        for (Slice slice : labels) {
            slice.labelTop = Math.max(cursor, slice.desiredY - slice.label.getMeasuredHeight() / 2f);
            cursor = slice.labelTop + slice.label.getMeasuredHeight() + gap;
        }
        // Move the column upward from its bottom edge when labels cluster below
        // the ring. onMeasure reserves enough room for every measured label.
        cursor = getHeight() - padding;
        for (int i = labels.size() - 1; i >= 0; i--) {
            Slice slice = labels.get(i);
            int height = slice.label.getMeasuredHeight();
            slice.labelTop = Math.min(slice.labelTop, cursor - height);
            cursor = slice.labelTop - gap;
            int x = slice.left ? padding : getWidth() - padding - labelWidth;
            int y = Math.round(slice.labelTop);
            slice.label.layout(x, y, x + labelWidth, y + height);
        }
    }

    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        RectF oval = new RectF(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);
        paint.setStyle(Paint.Style.FILL);
        if (positiveTotal == 0) {
            paint.setColor(Color.LTGRAY);
            canvas.drawOval(oval, paint);
        }
        for (int i = 0; i < slices.size(); i++) {
            Slice slice = slices.get(i);
            paint.setColor(slice.color);
            canvas.drawArc(oval, slice.start, slice.sweep, true, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(activity.px(1));
            paint.setColor(ReportColors.surface(activity));
            canvas.drawArc(oval, slice.start, slice.sweep, true, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        paint.setColor(ReportColors.surface(activity));
        canvas.drawCircle(centerX, centerY, radius * 0.43f, paint);
        paint.setColor(ReportColors.text(activity));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(activity.px(11));
        String title = TextUtils.ellipsize(centerTitle, new android.text.TextPaint(paint), radius * 0.8f, TextUtils.TruncateAt.END).toString();
        canvas.drawText(title, centerX, centerY - activity.px(3), paint);
        String total = activity.cash(netTotal);
        float size = activity.px(12);
        paint.setTextSize(size);
        float available = radius * 0.78f;
        if (paint.measureText(total) > available) {
            paint.setTextSize(size * available / paint.measureText(total));
        }
        paint.setFakeBoldText(true);
        canvas.drawText(total, centerX, centerY + activity.px(13), paint);
        paint.setFakeBoldText(false);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, activity.px(1)));
        paint.setColor(ReportColors.muted(activity));
        for (Slice slice : slices) {
            double angle = Math.toRadians(slice.start + slice.sweep / 2);
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            float endX = slice.left ? slice.label.getRight() + activity.px(3)
                    : slice.label.getLeft() - activity.px(3);
            float endY = slice.label.getTop() + slice.label.getHeight() / 2f;
            Path line = new Path();
            line.moveTo(centerX + cos * radius * 0.96f, centerY + sin * radius * 0.96f);
            line.lineTo(centerX + cos * (radius + activity.px(8)),
                    centerY + sin * (radius + activity.px(8)));
            line.lineTo(endX, endY);
            canvas.drawPath(line, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
}

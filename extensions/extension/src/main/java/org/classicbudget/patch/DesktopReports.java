package org.classicbudget.patch;

import android.app.AlertDialog;
import android.graphics.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Phone presentation of desktop report rows; all selection is local and read-only. */
final class DesktopReports {
    final ReportsActivity a;
    int firstMonth,lastMonth;
    boolean summary;
    int[] columnWidths;
    HorizontalScrollView matrixScroll;
    final List<TextView> pinnedLabels=new ArrayList<TextView>();
    final class PinnedScroll extends HorizontalScrollView {
        // OVER_SCROLL_NEVER: the Android 12+ stretch edge effect transforms the whole content, pinned
        // column included, and after a fling into the edge it can stay applied (first letters clipped).
        PinnedScroll() {
            super(a);
            setHorizontalFadingEdgeEnabled(false);
            setFadingEdgeLength(0);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
        }
        protected void onScrollChanged(int x, int y, int oldX, int oldY) {
            super.onScrollChanged(x, y, oldX, oldY);
            if (!summary && getWindowToken() != null) a.incomeScrollX = x;
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                for (TextView label : pinnedLabels) label.setTranslationX(x);
            }
        }
    }
    DesktopReports(ReportsActivity activity){a=activity;}
    String tr(String en,String pl){return ReportsActivity.tr(en,pl);}
    void income(final ReportData.Filter filter) {
        final ReportTable model = new ReportTable(a.data, filter);
        a.incomeModel = model;
        model.sortIncome(a.prefs.getBoolean("income.sources_az", false));
        summary = a.prefs.getBoolean("income.summary", false);
        firstMonth = Math.min(Math.max(0, a.prefs.getInt("income.month_page", 0)), Math.max(0, model.months.size() - 12));
        lastMonth = Math.min(model.months.size(), firstMonth + 12);
        if (!summary && model.months.size() > 12) {
            LinearLayout pages = new LinearLayout(a);
            Button previous = a.chip(tr("Earlier months", "Wcześniejsze miesiące"), "income-earlier", v -> {
                a.prefs.edit().putInt("income.month_page", Math.max(0, firstMonth - 12)).apply();
                a.incomeScrollX = 0;
                a.render();
            });
            previous.setEnabled(firstMonth > 0);
            pages.addView(previous);
            Button next = a.chip(tr("Later months", "Późniejsze miesiące"), "income-later", v -> {
                a.prefs.edit().putInt("income.month_page", Math.min(model.months.size() - 12, firstMonth + 12)).apply();
                a.incomeScrollX = 0;
                a.render();
            });
            next.setEnabled(lastMonth < model.months.size());
            pages.addView(next);
            a.root.addView(pages);
        }
        prepareWidths(model);
        final HorizontalScrollView scroller = new PinnedScroll();
        matrixScroll = scroller;
        final int rememberedScroll = a.incomeScrollX;
        scroller.setContentDescription("income-matrix-scroll");
        final LinearLayout table = new LinearLayout(a);
        table.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(table);
        a.root.addView(scroller);
        String[] headers = new String[summary ? 3 : lastMonth - firstMonth + 3];
        headers[0] = tr("Category / source", "Kategoria / źródło");
        if (summary) headers[0] += "\n" + ReportsActivity.label(filter.first) + " – " + ReportsActivity.label(filter.last);
        else for (int i = firstMonth; i < lastMonth; i++) headers[i - firstMonth + 1] = ReportsActivity.label(model.months.get(i));
        headers[headers.length - 2] = tr("Average", "Średnio");
        headers[headers.length - 1] = tr("Total", "Razem");
        LinearLayout heading = matrixLine(headers, true, null, null, null, 0);
        heading.setContentDescription("income-header");
        table.addView(heading);
        a.pinHeader(scroller, heading);
        if (!summary) scroller.post(() -> scroller.scrollTo(rememberedScroll, 0));
        addExpandable(table, model.income, model, filter, 0);
        table.addView(matrixLine(values(model.expenses, model), true, model.expenses, model, filter, 0));
        for (ReportTable.Row group : model.groups) addExpandable(table, group, model, filter, 0);
        table.addView(matrixLine(values(model.net, model), true, model.net, model, filter, 0));
    }

    String[] values(ReportTable.Row row, ReportTable model) {
        String[] out = new String[summary ? 3 : lastMonth - firstMonth + 3];
        out[0] = row.name;
        if (!summary) for (int i = firstMonth; i < lastMonth; i++) out[i - firstMonth + 1] = a.cash(row.amounts[i]);
        out[out.length - 2] = a.cash(row.average());
        out[out.length - 1] = a.cash(row.total());
        return out;
    }

    void collectRows(ReportTable.Row row, List<ReportTable.Row> rows) {
        rows.add(row);
        for (ReportTable.Row child : row.children) collectRows(child, rows);
    }

    void prepareWidths(ReportTable model) {
        columnWidths = new int[summary ? 3 : lastMonth - firstMonth + 3];
        Arrays.fill(columnWidths, a.px(88));
        boolean wide = a.getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        columnWidths[0] = a.px(wide ? 168 : 140);
        List<ReportTable.Row> rows = new ArrayList<ReportTable.Row>();
        collectRows(model.income, rows);
        collectRows(model.expenses, rows);
        collectRows(model.net, rows);
        for (ReportTable.Row group : model.groups) collectRows(group, rows);
        Paint measure = a.text("", 12, ReportsActivity.TEXT).getPaint();
        measure.setTypeface(Typeface.DEFAULT_BOLD);
        for (ReportTable.Row row : rows) {
            String[] cells = values(row, model);
            for (int i = 1; i < cells.length; i++) {
                // Measure full currency strings, including large/negative values; never crop cents to fit more columns.
                columnWidths[i] = Math.max(columnWidths[i], (int) Math.ceil(measure.measureText(cells[i])) + a.px(18));
            }
        }
        if (summary) {
            int available = a.getResources().getDisplayMetrics().widthPixels - a.root.getPaddingLeft() - a.root.getPaddingRight();
            columnWidths[0] = Math.max(columnWidths[0], available * 44 / 100);
            int amountWidth = (available - columnWidths[0]) / 2;
            columnWidths[1] = Math.max(columnWidths[1], amountWidth);
            columnWidths[2] = Math.max(columnWidths[2], amountWidth);
        }
    }
    void addExpandable(LinearLayout table,final ReportTable.Row r,final ReportTable model,final ReportData.Filter f,final int indent){
        final LinearLayout children=new LinearLayout(a);children.setOrientation(LinearLayout.VERTICAL);
        final boolean expandable=!r.children.isEmpty();
        final android.content.SharedPreferences pref=a.prefs;
        boolean expanded=pref.getBoolean("income.expanded."+r.key,false);
        String[] cells=values(r,model);cells[0]=(expandable?(expanded?"▾ ":"▸ "):"")+cells[0];
        final LinearLayout line=matrixLine(cells,true,r,model,f,indent);table.addView(line);
        if(expandable){
            final boolean[] populated={false};
            final Runnable populate=new Runnable(){public void run(){if(populated[0])return;for(ReportTable.Row child:r.children)children.addView(matrixLine(values(child,model),false,child,model,f,indent+1));populated[0]=true;}};
            if(expanded)populate.run();
            final TextView title=(TextView)line.getChildAt(0);title.setContentDescription("income-expand:"+r.key);
            children.setVisibility(expanded?View.VISIBLE:View.GONE);
            title.setOnClickListener(new View.OnClickListener(){public void onClick(View v){boolean on=children.getVisibility()!=View.VISIBLE;if(on)populate.run();children.setVisibility(on?View.VISIBLE:View.GONE);title.setText((on?"▾ ":"▸ ")+r.name);pref.edit().putBoolean("income.expanded."+r.key,on).apply();}});
            table.addView(children);
        }
    }
    LinearLayout matrixLine(String[] cells, boolean bold, final ReportTable.Row row, final ReportTable model,
            final ReportData.Filter filter, int indent) {
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < cells.length; i++) {
            final TextView cell = a.cell(cells[i], bold);
            cell.setMinHeight(a.px(48));
            cell.setGravity((i == 0 ? Gravity.START : Gravity.END) | Gravity.CENTER_VERTICAL);
            cell.setPadding(a.px(i == 0 ? 8 + indent * 14 : 8), a.px(6), a.px(8), a.px(6));
            if (i == 0) {
                cell.setMaxLines(2);
                cell.setEllipsize(TextUtils.TruncateAt.END);
            } else cell.setSingleLine(true);
            line.addView(cell, new LinearLayout.LayoutParams(columnWidths[i], -2));
            if (i == 0 && android.os.Build.VERSION.SDK_INT >= 21) {
                cell.setBackgroundColor(bold ? Color.rgb(244, 246, 247) : Color.WHITE);
                cell.setElevation(a.px(2));
                cell.setTranslationX(matrixScroll == null ? 0 : matrixScroll.getScrollX());
                pinnedLabels.add(cell);
            }
            if (row != null) {
                final String month = !summary && i > 0 && i <= lastMonth - firstMonth ? model.months.get(firstMonth + i - 1) : null;
                final boolean average = i == cells.length - 2;
                String key = i == 0 ? "name" : month != null ? month : average ? "average" : "total";
                cell.setContentDescription("income-cell:" + row.key + ":" + key);
                if (i > 0 && !average) cell.setOnClickListener(v -> details(filter, row, month));
                if (i > 0) cell.setTextColor("income".equals(row.kind) ? ReportsActivity.INCOME
                    : "expense".equals(row.kind) ? ReportsActivity.SPEND : ReportsActivity.TEXT);
            }
        }
        line.setBackgroundColor(bold ? Color.argb(15, 80, 100, 110) : Color.TRANSPARENT);
        return line;
    }
    void details(ReportData.Filter filter,ReportTable.Row row,String month){
        if(a.hide){new AlertDialog.Builder(a).setMessage(tr("Show amounts to open transaction details.","Włącz widoczność kwot, aby otworzyć szczegóły transakcji.")).setPositiveButton("OK",null).show();return;}
        final List<ReportTable.Detail> all = ReportTable.details(a.data, filter, row, month);
        String title = row == null ? tr("Transactions", "Transakcje") : row.name;
        if (month != null) title += " · " + ReportsActivity.label(month);
        ReportTransactions.show(a, all, title);
    }

    /** Colours of the phone style (bars). The desktop ring keeps the desktop app's colours. */
    static final int[] PHONE_COLORS = {
        Color.rgb(216, 90, 48), Color.rgb(239, 159, 39), Color.rgb(151, 196, 89), Color.rgb(93, 202, 165),
        Color.rgb(55, 138, 221), Color.rgb(127, 119, 221), Color.rgb(212, 83, 126), Color.rgb(180, 178, 169)
    };

    /** Spending by category / payee. Two styles: "classic" = the desktop app's ring with its drill-down,
     *  "current" = phone list with bars. Both open the same transaction list as the desktop app. */
    void ranking(final ReportData.Filter f, final List<ReportData.Group> groups, final boolean categories) {
        final boolean classic = "classic".equals(a.prefs.getString("donut.style", "current"));
        a.root.addView(styleToggle(classic));
        if (classic) desktopRanking(f, groups, categories);
        else phoneRanking(f, groups, categories);
    }

    /** Pill switch Desktop | Phone instead of a dialog. */
    View styleToggle(boolean classic) {
        LinearLayout pill = new LinearLayout(a);
        pill.setContentDescription("donut-style");
        pill.setPadding(a.px(3), a.px(3), a.px(3), a.px(3));
        android.graphics.drawable.GradientDrawable track = new android.graphics.drawable.GradientDrawable();
        track.setCornerRadius(a.px(20));
        track.setColor(Color.rgb(236, 238, 239));
        pill.setBackground(track);
        pill.addView(pillButton(tr("Ring", "Koło"), "donut-style-desktop", classic, "classic"), new LinearLayout.LayoutParams(0, a.px(36), 1));
        pill.addView(pillButton(tr("List", "Lista"), "donut-style-phone", !classic, "current"), new LinearLayout.LayoutParams(0, a.px(36), 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, a.px(8), 0, a.px(10));
        pill.setLayoutParams(lp);
        return pill;
    }

    TextView pillButton(String label, String desc, boolean selected, final String value) {
        TextView t = a.text(label, 14, selected ? ReportsActivity.TEXT : ReportsActivity.MUTED);
        t.setGravity(Gravity.CENTER);
        StyleIcon icon = new StyleIcon("classic".equals(value), selected ? ReportsActivity.TEXT : ReportsActivity.MUTED);
        icon.setBounds(0, 0, a.px(18), a.px(18));
        t.setCompoundDrawables(icon, null, null, null);
        t.setCompoundDrawablePadding(a.px(6));
        t.setPadding(a.px(12), 0, a.px(8), 0);
        t.setContentDescription(desc);
        if (selected) {
            android.graphics.drawable.GradientDrawable knob = new android.graphics.drawable.GradientDrawable();
            knob.setCornerRadius(a.px(18));
            knob.setColor(Color.WHITE);
            t.setBackground(knob);
            t.setTypeface(null, Typeface.BOLD);
        }
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.prefs.edit().putString("donut.style", value).apply();
                a.drillGroup = ReportsActivity.NO_DRILL;
                a.render();
            }
        });
        return t;
    }

    static final class StyleIcon extends android.graphics.drawable.Drawable {
        final boolean ring;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        StyleIcon(boolean ring, int color) {
            this.ring = ring;
            paint.setColor(color);
        }
        public void draw(Canvas canvas) {
            android.graphics.Rect bounds = getBounds();
            float width = bounds.width();
            float height = bounds.height();
            canvas.save();
            canvas.translate(bounds.left, bounds.top);
            if (ring) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(width / 5);
                canvas.drawCircle(width / 2, height / 2, width * 0.34f, paint);
            } else {
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < 3; i++) {
                    float top = height * (0.12f + i * 0.28f);
                    canvas.drawRect(0, top, width * (1 - i * 0.2f), top + height * 0.16f, paint);
                }
            }
            canvas.restore();
        }
        public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Groups the desktop app opens straight as a transaction list: Uncategorized and Hidden Categories. */
    static boolean opensTransactions(ReportData.Group g) {
        return g.groupId == -1 || "__Hidden__".equals(g.name) || g.children.isEmpty();
    }

    ReportTable.Row scope(ReportData.Group g, boolean categories) {
        ReportTable.Row row = ReportTable.row("", ReportTable.localized(g.name), "expense", 0);
        if (categories) row.group = g.groupId;
        else row.payee = g.id;
        return row;
    }

    ReportTable.Row leaf(ReportData.Group child) {
        ReportTable.Row row = ReportTable.row("", ReportTable.localized(child.name), "expense", 0);
        row.category = child.id;
        return row;
    }

    /** Desktop style: ring with labels; a group opens its categories (path back above), a category or
     *  payee opens its transactions. As in the desktop app, "All others" is not tappable. */
    void desktopRanking(final ReportData.Filter f, final List<ReportData.Group> groups, final boolean categories) {
        ReportData.Group opened = null;
        if (categories && a.drillGroup != ReportsActivity.NO_DRILL)
            for (ReportData.Group g : groups) if (g.groupId == a.drillGroup) opened = g;
        if (opened == null) a.drillGroup = ReportsActivity.NO_DRILL;
        final ReportData.Group group = opened;
        // As in the desktop app, the path appears only inside a group (the top level keeps the height for the ring).
        if (categories && group != null) a.root.addView(breadcrumb(group), new LinearLayout.LayoutParams(-1, -2));
        List<ReportData.Group> shown = group == null ? groups : group.children;
        String center = group == null ? tr("Total spending", "Wydatki razem") : ReportTable.localized(group.name);
        ClassicDonut.Listener listener = new ClassicDonut.Listener() {
            public void onSlice(ReportData.Group g) { desktopTap(f, g, group, categories); }
        };
        final ClassicDonut donut = new ClassicDonut(a, shown, center, listener);
        a.root.addView(donut);
        refundNote(shown);
        List<ReportData.Group> sorted = new ArrayList<ReportData.Group>(shown);
        Collections.sort(sorted, new Comparator<ReportData.Group>() {
            public int compare(ReportData.Group x, ReportData.Group y) { return Long.compare(y.amount, x.amount); }
        });
        int colour = 0;
        for (final ReportData.Group g : sorted) {
            Integer sliceColor = donut.colorOf(g);
            // Same colour as the ring; groups folded into "All others" share its grey.
            int swatch = g.amount > 0 && sliceColor != null ? sliceColor : ReportColors.other(a);
            if (g.amount > 0) colour++;
            View row = legendRow(ReportTable.localized(g.name), a.cash(g.amount), swatch, "report-legend:" + (group == null && categories ? g.groupId : g.id));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { desktopTap(f, g, group, categories); }
            });
            a.root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        if (group != null) {
            TextView all = link(tr("All transactions of this group", "Wszystkie transakcje grupy"), "report-group-detail:" + group.groupId);
            all.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { details(f, scope(group, true), null); }
            });
            a.root.addView(all);
        }
    }

    static final int[] DESKTOP_COLORS = {
        0xfff04c32, 0xffbdc834, 0xff8dac49, 0xff7eb1b3, 0xffd8df88,
        0xfff5b12f, 0xffb8c9b5, 0xffe8d18b, 0xff9bb5bf, 0xff666a6e
    };

    void desktopTap(ReportData.Filter f, ReportData.Group g, ReportData.Group opened, boolean categories) {
        if (!categories) { details(f, scope(g, false), null); return; }
        if (opened != null) { details(f, leaf(g), null); return; }
        if (opensTransactions(g)) { details(f, scope(g, true), null); return; }
        a.drillGroup = g.groupId;
        a.render();
    }

    View breadcrumb(ReportData.Group opened) {
        LinearLayout line = new LinearLayout(a);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(a.px(36));
        TextView back = link(tr("Category groups", "Grupy kategorii"), "report-breadcrumb-back");
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                a.drillGroup = ReportsActivity.NO_DRILL;
                a.render();
            }
        });
        line.addView(back);
        line.addView(a.text("  ›  " + ReportTable.localized(opened.name), 14, ReportsActivity.TEXT));
        return line;
    }

    TextView link(String label, String desc) {
        TextView t = a.text(label, 14, ReportsActivity.LINE);
        t.setContentDescription(desc);
        t.setPadding(0, a.px(10), a.px(8), a.px(10));
        return t;
    }

    View legendRow(String name, String amount, int colour, String desc) {
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(a.px(44));
        row.setContentDescription(desc);
        View swatch = new View(a);
        ReportColors.ownRendering(swatch);
        android.graphics.drawable.GradientDrawable box = new android.graphics.drawable.GradientDrawable();
        box.setCornerRadius(a.px(2));
        box.setColor(colour);
        swatch.setBackground(box);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(a.px(12), a.px(12));
        sp.setMargins(a.px(2), 0, a.px(10), 0);
        row.addView(swatch, sp);
        TextView label = a.text(name, 15, ReportsActivity.TEXT);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(amountText(amount, 15));
        row.setBackground(divider());
        return row;
    }

    TextView amountText(String amount, int size) {
        TextView t = a.text(amount, size, ReportsActivity.TEXT);
        t.setPadding(a.px(4), 0, a.px(4), 0);
        return t;
    }

    android.graphics.drawable.Drawable divider() {
        android.graphics.drawable.GradientDrawable line = new android.graphics.drawable.GradientDrawable();
        line.setColor(Color.TRANSPARENT);
        line.setStroke(Math.max(1, a.px(0.5f)), Color.rgb(225, 228, 230));
        android.graphics.drawable.InsetDrawable onlyTop = new android.graphics.drawable.InsetDrawable(line, -a.px(2), 0, -a.px(2), -a.px(2));
        return onlyTop;
    }

    void refundNote(List<ReportData.Group> shown) {
        boolean refunds = false;
        for (ReportData.Group g : shown) refunds |= g.amount < 0;
        if (refunds) a.root.addView(a.text(tr("The ring shows positive spending; refund surpluses are listed below and included in the net total.",
                "Pierścień pokazuje dodatnie wydatki; nadwyżki zwrotów są na liście i w sumie netto."), 12, ReportsActivity.MUTED));
    }

    /** Phone style: total, one proportion strip, ranked rows with bars; a group expands in place,
     *  a category or payee opens its transactions. */
    void phoneRanking(final ReportData.Filter f, final List<ReportData.Group> groups, final boolean categories) {
        List<ReportData.Group> sorted = new ArrayList<ReportData.Group>(groups);
        Collections.sort(sorted, new Comparator<ReportData.Group>() {
            public int compare(ReportData.Group x, ReportData.Group y) { return Long.compare(y.amount, x.amount); }
        });
        long net = 0;
        long positive = 0;
        long largest = 1;
        for (ReportData.Group g : sorted) {
            net += g.amount;
            positive += Math.max(0, g.amount);
            largest = Math.max(largest, g.amount);
        }
        TextView total = a.text(a.cash(net), 26, ReportsActivity.TEXT);
        total.setTypeface(null, Typeface.BOLD);
        total.setContentDescription("report-phone-total");
        a.root.addView(total);
        a.root.addView(a.text(sorted.size() + (categories ? tr(" groups", " grup") : tr(" payees", " płatników")), 13, ReportsActivity.MUTED));
        final java.util.Map<ReportData.Group, Integer> colours = ReportColors.distinct(a, sorted);
        if (positive > 0) a.root.addView(strip(sorted, positive, colours));
        refundNote(sorted);
        for (int i = 0; i < sorted.size(); i++) {
            final ReportData.Group g = sorted.get(i);
            final int colour = colours.get(g);
            final boolean expandable = categories && !opensTransactions(g);
            final String key = "bars.expanded." + g.groupId;
            final boolean expanded = expandable && a.prefs.getBoolean(key, false);
            String percent = net == 0 ? "" : "  " + Math.round(100.0 * g.amount / net) + "%";
            String name = (expandable ? (expanded ? "▾ " : "▸ ") : "") + ReportTable.localized(g.name);
            View row = barRow(name, a.cash(g.amount) + percent, g.amount, largest, colour, 15, "report-group:" + (categories ? g.groupId : g.id));
            row.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (expandable) {
                        a.prefs.edit().putBoolean(key, !expanded).apply();
                        a.render();
                    } else details(f, scope(g, categories), null);
                }
            });
            a.root.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (!expanded) continue;
            List<ReportData.Group> children = new ArrayList<ReportData.Group>(g.children);
            Collections.sort(children, new Comparator<ReportData.Group>() {
                public int compare(ReportData.Group x, ReportData.Group y) { return Long.compare(y.amount, x.amount); }
            });
            for (final ReportData.Group child : children) {
                View sub = barRow(ReportTable.localized(child.name), a.cash(child.amount), child.amount, Math.max(1, g.amount), ReportColors.shade(colour, child), 14, "report-category-detail:" + child.id);
                sub.setPadding(a.px(22), sub.getPaddingTop(), sub.getPaddingRight(), sub.getPaddingBottom());
                sub.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { details(f, leaf(child), null); }
                });
                a.root.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            }
            TextView all = link(tr("All transactions of this group", "Wszystkie transakcje grupy"), "report-group-detail:" + g.groupId);
            all.setPadding(a.px(22), a.px(6), 0, a.px(10));
            all.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) { details(f, scope(g, true), null); }
            });
            a.root.addView(all);
        }
    }

    static int lighter(int colour) {
        return Color.rgb((Color.red(colour) + 255) / 2, (Color.green(colour) + 255) / 2, (Color.blue(colour) + 255) / 2);
    }

    View strip(List<ReportData.Group> sorted, long positive, java.util.Map<ReportData.Group, Integer> colours) {
        LinearLayout strip = new LinearLayout(a);
        strip.setContentDescription("report-phone-strip");
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setCornerRadius(a.px(5));
        shape.setColor(Color.rgb(236, 238, 239));
        strip.setBackground(shape);
        if (android.os.Build.VERSION.SDK_INT >= 21) strip.setClipToOutline(true);
        for (int i = 0; i < sorted.size(); i++) {
            long amount = sorted.get(i).amount;
            if (amount <= 0) continue;
            View part = new View(a);
            part.setBackgroundColor(colours.get(sorted.get(i)));
            ReportColors.ownRendering(part);
            strip.addView(part, new LinearLayout.LayoutParams(0, -1, (float) amount / positive));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, a.px(10));
        lp.setMargins(0, a.px(10), 0, a.px(8));
        strip.setLayoutParams(lp);
        return strip;
    }

    /** Name and amount on one line, a proportional bar below; whole row is one tap target. */
    View barRow(String name, String amount, long value, long largest, int colour, int textSize, String desc) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, a.px(9), 0, a.px(9));
        row.setContentDescription(desc);
        row.setBackground(divider());
        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = a.text(name, textSize, ReportsActivity.TEXT);
        label.setMaxLines(2);
        label.setEllipsize(TextUtils.TruncateAt.END);
        // A little room on both edges: the expand arrow and the last glyph (ł, %) were cut off.
        label.setPadding(a.px(2), 0, a.px(6), 0);
        top.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(amountText(amount, textSize));
        row.addView(top, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout track = new LinearLayout(a);
        android.graphics.drawable.GradientDrawable trackShape = new android.graphics.drawable.GradientDrawable();
        trackShape.setCornerRadius(a.px(3));
        trackShape.setColor(Color.rgb(240, 242, 243));
        track.setBackground(trackShape);
        float share = Math.max(0, Math.min(1, (float) value / largest));
        View bar = new View(a);
        ReportColors.ownRendering(bar);
        android.graphics.drawable.GradientDrawable barShape = new android.graphics.drawable.GradientDrawable();
        barShape.setCornerRadius(a.px(3));
        barShape.setColor(colour);
        bar.setBackground(barShape);
        track.addView(bar, new LinearLayout.LayoutParams(0, -1, share));
        track.addView(new View(a), new LinearLayout.LayoutParams(0, -1, 1 - share));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, a.px(6));
        tp.setMargins(0, a.px(6), 0, 0);
        row.addView(track, tp);
        return row;
    }
    void months(List<ReportData.Month> months,boolean worth){
        TableLayout table=new TableLayout(a);table.setStretchAllColumns(true);
        table.addView(a.line(new String[]{tr("Month","Miesiąc"),worth?tr("Assets","Aktywa"):tr("Income","Przychody"),worth?tr("Debts","Długi"):tr("Spending","Wydatki"),worth?tr("Net worth","Majątek netto"):tr("Net","Saldo")},true,null));
        for(ReportData.Month m:months)table.addView(a.line(new String[]{ReportsActivity.label(m.month),a.cash(worth?m.assets:m.income),a.cash(worth?-m.debts:m.spending),a.cash(worth?m.net():m.income-m.spending)},false,"month:"+m.month));
        a.root.addView(table);
    }
}

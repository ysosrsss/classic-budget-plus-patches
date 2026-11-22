package org.classicbudget.patch;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.*;

/** Searchable virtualized filter lists; category groups expand without changing selection. */
final class ReportFilterDialog {
    interface Saved { void apply(Set<String> excluded); }
    static String tr(String en, String pl) { return PlusLang.tr(en, pl); }
    static void show(final Activity activity, String title, final List<ReportData.Item> items,
                     final boolean useKey, boolean grouped, Set<String> initial, final Set<String> mine, final Saved saved) {
        final Set<String> excluded = new HashSet<String>(initial);
        final LinkedHashMap<String, List<ReportData.Item>> all = new LinkedHashMap<String, List<ReportData.Item>>();
        for (ReportData.Item item : items) {
            String group = grouped && item.group != null ? item.group : "";
            if (!all.containsKey(group)) all.put(group, new ArrayList<ReportData.Item>());
            all.get(group).add(item);
        }
        final ArrayList<String> groups = new ArrayList<String>();
        final ArrayList<List<ReportData.Item>> visible = new ArrayList<List<ReportData.Item>>();
        LinearLayout box = new LinearLayout(activity); box.setOrientation(LinearLayout.VERTICAL);
        final EditText search = new EditText(activity); search.setSingleLine(true);
        search.setHint(tr("Search any part of a name…", "Szukaj fragmentu nazwy…")); search.setContentDescription("report-filter-search"); box.addView(search);
        final ExpandableListView list = new ExpandableListView(activity); list.setGroupIndicator(null);
        final float density = activity.getResources().getDisplayMetrics().density;
        list.setStackFromBottom(false);
        list.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_DISABLED);
        list.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            public boolean onGroupClick(ExpandableListView parent, View view, int group, long id) {
                if (parent.isGroupExpanded(group)) parent.collapseGroup(group);
                else parent.expandGroup(group, false); // Keep the heading in place; children appear below it.
                return true;
            }
        });
        int height = (int) (activity.getResources().getDisplayMetrics().heightPixels * .48f);
        box.addView(list, new LinearLayout.LayoutParams(-1, height));
        final BaseExpandableListAdapter adapter = new BaseExpandableListAdapter() {
            public int getGroupCount() { return groups.size(); }
            public int getChildrenCount(int g) { return visible.get(g).size(); }
            public Object getGroup(int g) { return groups.get(g); }
            public Object getChild(int g, int c) { return visible.get(g).get(c); }
            public long getGroupId(int g) { return g; }
            public long getChildId(int g, int c) { return visible.get(g).get(c).id; }
            public boolean hasStableIds() { return false; }
            public boolean isChildSelectable(int g, int c) { return false; }
            public View getGroupView(final int g, boolean expanded, View old, ViewGroup parent) {
                final String name = groups.get(g);
                if (name.isEmpty()) { TextView empty = new TextView(activity); empty.setHeight(0); return empty; }
                LinearLayout row = new LinearLayout(activity); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding((int)(8*density), 0, (int)(8*density), 0);
                row.setMinimumHeight((int)(52*density));
                CheckBox check = new CheckBox(activity); check.setFocusable(false);
                int selected = 0; final List<ReportData.Item> members = all.get(name);
                for (ReportData.Item item : members) if (!excluded.contains(ReportsActivity.id(item, useKey))) selected++;
                check.setChecked(selected == members.size()); check.setContentDescription("filter-group-select:" + name);
                check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton button, boolean checked) {
                        for (ReportData.Item item : members) { String id = ReportsActivity.id(item, useKey); if (checked) excluded.remove(id); else excluded.add(id); }
                        notifyDataSetChanged();
                    }
                });
                row.addView(check);
                TextView label = new TextView(activity); label.setText((expanded ? "▾ " : "▸ ") + name + " (" + selected + "/" + members.size() + ")");
                label.setTextSize(16); label.setTypeface(null, android.graphics.Typeface.BOLD);
                label.setPadding((int)(8*density), (int)(12*density), (int)(4*density), (int)(12*density)); label.setContentDescription("filter-group:" + name);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); return row;
            }
            public View getChildView(int g, int c, boolean last, View old, ViewGroup parent) {
                final ReportData.Item item = visible.get(g).get(c); final String id = ReportsActivity.id(item, useKey);
                LinearLayout childRow = new LinearLayout(activity);
                childRow.setPadding((int)((groups.get(g).isEmpty()?8:48)*density), 0, (int)(8*density), 0);
                CheckBox check = new CheckBox(activity);
                check.setPadding(0, (int)(4*density), 0, (int)(4*density));
                check.setMinHeight((int)(48*density)); check.setTextSize(15);
                check.setText(item.name + (item.closed ? tr(" (closed)", " (zamknięte)") : "") + (!item.onBudget ? tr(" · off-budget", " · poza budżetem") : ""));
                check.setChecked(!excluded.contains(id)); check.setContentDescription("filter-item:" + id);
                check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                    public void onCheckedChanged(CompoundButton button, boolean checked) { if (checked) excluded.remove(id); else excluded.add(id); notifyDataSetChanged(); }
                }); childRow.addView(check, new LinearLayout.LayoutParams(-1,-2)); return childRow;
            }
        };
        list.setAdapter(adapter);
        Runnable filter = new Runnable() { public void run() {
            String query = search.getText().toString(); groups.clear(); visible.clear();
            for (Map.Entry<String, List<ReportData.Item>> entry : all.entrySet()) {
                List<ReportData.Item> matches = new ArrayList<ReportData.Item>();
                for (ReportData.Item item : entry.getValue()) if (SearchText.matches(entry.getKey() + " " + item.name, query)) matches.add(item);
                if (!matches.isEmpty()) { groups.add(entry.getKey()); visible.add(matches); }
            }
            adapter.notifyDataSetChanged();
            for (int g = 0; g < groups.size(); g++) {
                if (!query.trim().isEmpty() || groups.get(g).isEmpty()) list.expandGroup(g); else list.collapseGroup(g);
            }
        }};
        filter.run(); final Runnable refresh = filter;
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            public void afterTextChanged(Editable s) {}
        });
        final AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(box)
            .setPositiveButton("OK", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) { saved.apply(new TreeSet<String>(excluded)); } })
            .setNeutralButton(tr("Select all", "Zaznacz wszystkie"), null)
            .setNegativeButton(mine != null && !mine.isEmpty() ? tr("My accounts", "Moje konta") : tr("Select none", "Odznacz wszystkie"), null).create();
        dialog.setOnShowListener(new DialogInterface.OnShowListener() { public void onShow(DialogInterface d) {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { excluded.clear(); adapter.notifyDataSetChanged(); } });
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                excluded.clear(); if (mine != null && !mine.isEmpty()) excluded.addAll(mine);
                else for (ReportData.Item item : items) excluded.add(ReportsActivity.id(item, useKey));
                adapter.notifyDataSetChanged();
            }});
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }});
        dialog.show();
    }
}

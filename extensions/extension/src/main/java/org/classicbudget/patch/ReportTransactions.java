package org.classicbudget.patch;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Reuses the transaction row UI for report details and the all-accounts search. */
final class ReportTransactions {
    private static final ExecutorService SEARCH_WORKER = Executors.newSingleThreadExecutor();
    final ReportsActivity a;
    List<ReportTable.Detail> source;
    TransactionFilterControls filters;
    List<ReportTable.Detail> shown = new ArrayList<ReportTable.Detail>();
    final Map<String, String> accounts = new LinkedHashMap<String, String>();
    final Map<String, String> payees = new LinkedHashMap<String, String>();
    final Set<String> expanded = new HashSet<String>();
    String account, payee;
    int sign, order;
    EditText search;
    TextView total;
    Button sortButton, accountButton, payeeButton, typeButton;
    LinearLayout selectionControls;
    Button selectAll;
    BulkCategoryControls bulkCategory;
    boolean selecting;
    private boolean filtering;
    private Runnable pendingSearch;
    private Future<?> runningSearch;
    private int searchRevision;
    final Map<String, Long> selected = new LinkedHashMap<>();
    final Rows adapter = new Rows();

    ReportTransactions(ReportsActivity a, List<ReportTable.Detail> source) {
        this.a = a;
        this.source = source;
        index();
    }

    void enableSearch(String budget) {
        filters = new TransactionFilterControls(this, budget);
        if (BulkCategory.enabled(a.data.value("budgetFolder"))) bulkCategory = new BulkCategoryControls(this, budget);
    }

    void applyFilter(TransactionFilter filter) {
        if (filters != null) filters.current = filter;
        account = filter.account;
        payee = filter.payee;
        sign = filter.sign;
        order = filter.order;
        expanded.clear();
        if (TextUtils.equals(search.getText(), filter.query)) refresh();
        else search.setText(filter.query);
    }

    /** Account and payee choices of the current source. */
    void index() {
        accounts.clear();
        payees.clear();
        for (ReportTable.Detail detail : source) {
            accounts.put(detail.accountGuid, detail.account);
            String key = ReportDetailModel.payeeKey(detail);
            payees.put(key, ReportDetailModel.NO_PAYEE.equals(key) ? tr("No payee", "Bez płatnika") : detail.payee);
        }
    }

    String tr(String en, String pl) { return ReportsActivity.tr(en, pl); }
    String[] sortNames() { return new String[]{tr("Newest first", "Od najnowszych"), tr("Oldest first", "Od najstarszych"),
        tr("Largest amount", "Największa kwota"), tr("Smallest amount", "Najmniejsza kwota")}; }
    String[] typeNames() { return new String[]{tr("All amounts", "Wszystkie kwoty"), tr("Outflows (−)", "Wychodzące (−)"),
        tr("Inflows / refunds (+)", "Wpływy / zwroty (+)")}; }

    static void show(ReportsActivity a, List<ReportTable.Detail> details, String title) {
        new ReportTransactions(a, details).open(title);
    }

    /** After the original editor closed: the same search, sort and filters over fresh rows. */
    void reload(List<ReportTable.Detail> fresh) {
        source = fresh;
        index();
        refresh();
    }

    LinearLayout content() {
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(a.px(12), a.px(6), a.px(12), a.px(4));
        LinearLayout searchRow = new LinearLayout(a);
        search = new EditText(a);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setHint(tr("Search transactions", "Szukaj transakcji"));
        search.setContentDescription("report-detail-search");
        searchRow.addView(search, new LinearLayout.LayoutParams(0, a.px(44), 1));
        searchRow.addView(a.chip(tr("Reset", "Resetuj"), "report-detail-reset", v -> applyFilter(new TransactionFilter())));
        layout.addView(searchRow);
        LinearLayout controls = new LinearLayout(a);
        sortButton = a.chip("", "report-detail-sort", v -> new AlertDialog.Builder(a)
            .setTitle(tr("Sort by", "Sortowanie"))
            .setSingleChoiceItems(sortNames(), order, (dialog, which) -> {
                order = which;
                dialog.dismiss();
                refresh();
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show());
        accountButton = a.chip("", "report-detail-account", v -> chooseAccount());
        payeeButton = a.chip("", "report-detail-payee", v -> choosePayee());
        typeButton = a.chip("", "report-detail-type", v -> new AlertDialog.Builder(a)
            .setTitle(tr("Amount type", "Rodzaj kwoty"))
            .setSingleChoiceItems(typeNames(), sign, (dialog, which) -> {
                sign = which;
                dialog.dismiss();
                refresh();
            }).setNegativeButton(tr("Cancel", "Anuluj"), null).show());
        for (Button button : new Button[]{sortButton, accountButton, payeeButton, typeButton}) {
            button.setTextSize(11);
            button.setMaxLines(2);
            button.setEllipsize(TextUtils.TruncateAt.END);
            controls.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        }
        layout.addView(controls);
        if (filters != null) layout.addView(filters.controls());
        total = a.text("", 13, ReportsActivity.TEXT);
        total.setContentDescription("report-detail-total");
        layout.addView(total);
        if (filters != null) {
            selectionControls = new LinearLayout(a);
            selectAll = a.chip("", "transaction-select-results", v -> selectResults());
            selectionControls.addView(selectAll, new LinearLayout.LayoutParams(0, -2, 1));
            if (bulkCategory != null) {
                selectionControls.addView(bulkCategory.button(), new LinearLayout.LayoutParams(0, -2, 1));
            }
            selectionControls.addView(a.chip(tr("Done", "Gotowe"), "transaction-selection-done", v -> toggleSelection()));
            layout.addView(selectionControls);
        }
        ListView list = new ListView(a);
        list.setContentDescription("report-detail-list");
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (selecting) {
                selectRow(shown.get(position));
                return;
            }
            String guid = shown.get(position).guid;
            if (!expanded.add(guid)) expanded.remove(guid);
            adapter.notifyDataSetChanged();
        });
        if (filters != null) list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (!selecting) toggleSelection();
            selectRow(shown.get(position));
            return true;
        });
        layout.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
            public void afterTextChanged(Editable value) { }
        });
        refresh();
        return layout;
    }

    void open(String title) {
        open(title, null);
    }

    void open(String title, Runnable afterClose) {
        LinearLayout layout = content();
        // Leave room for the dialog title/buttons in landscape as well as portrait.
        int height = Math.min(a.px(600), Math.max(a.px(180), a.getResources().getDisplayMetrics().heightPixels - a.px(200)));
        FrameLayout frame = new FrameLayout(a);
        frame.addView(layout, new FrameLayout.LayoutParams(-1, height));
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(title).setView(frame)
            .setPositiveButton(tr("Close", "Zamknij"), null).create();
        dialog.setOnShowListener(d -> {
            search.clearFocus();
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        });
        dialog.setOnDismissListener(d -> {
            cancelSearch();
            if (afterClose != null) afterClose.run();
        });
        dialog.show();
    }

    void chooseAccount() {
        final List<String> keys = new ArrayList<String>(accounts.keySet());
        String[] names = new String[keys.size() + 1];
        names[0] = tr("All accounts", "Wszystkie konta");
        int selected = 0;
        for (int i = 0; i < keys.size(); i++) {
            names[i + 1] = accounts.get(keys.get(i));
            if (keys.get(i) != null && keys.get(i).equals(account)) selected = i + 1;
        }
        new AlertDialog.Builder(a).setTitle(tr("Account", "Konto")).setSingleChoiceItems(names, selected, (dialog, which) -> {
            account = which == 0 ? null : keys.get(which - 1);
            dialog.dismiss();
            refresh();
        }).setNegativeButton(tr("Cancel", "Anuluj"), null).show();
    }

    void refresh() {
        if (search == null) return;
        cancelSearch();
        TransactionFilter filter = filters == null ? null : filters.current;
        String query = search.getText().toString();
        selected.clear();
        sortButton.setText(new String[]{tr("Date ↓", "Data ↓"), tr("Date ↑", "Data ↑"), tr("Amount ↓", "Kwota ↓"), tr("Amount ↑", "Kwota ↑")}[order]);
        accountButton.setText(tr("Account", "Konto") + (account == null ? "" : " •"));
        payeeButton.setText(tr("Payee", "Płatnik") + (payee == null ? "" : " •"));
        typeButton.setText(sign == 0 ? tr("Type", "Typ") : sign == 1 ? tr("Outflows", "Wychodzące") : tr("Inflows", "Wpływy"));
        if (filters != null) filters.refresh();
        filtering = filter != null && !query.trim().isEmpty();
        if (filtering) {
            shown = Collections.emptyList();
            scheduleSearch(query, filter);
        } else {
            shown = ReportDetailModel.select(source, query, account, payee, sign, order, filter);
        }
        updateTotal();
    }

    private void scheduleSearch(String query, TransactionFilter filter) {
        List<ReportTable.Detail> rows = source;
        String accountKey = account;
        String payeeKey = payee;
        int amountSign = sign;
        int sort = order;
        int revision = searchRevision;
        pendingSearch = () -> runningSearch = SEARCH_WORKER.submit(() -> {
            List<ReportTable.Detail> result = ReportDetailModel.select(rows, query, accountKey, payeeKey, amountSign, sort, filter);
            a.runOnUiThread(() -> {
                if (searchRevision != revision || a.isDestroyed()) return;
                filtering = false;
                shown = result;
                updateTotal();
            });
        });
        search.postDelayed(pendingSearch, 140);
    }

    private void cancelSearch() {
        searchRevision++;
        if (pendingSearch != null) search.removeCallbacks(pendingSearch);
        if (runningSearch != null) runningSearch.cancel(true);
        pendingSearch = null;
        runningSearch = null;
    }

    void toggleSelection() {
        selecting = !selecting;
        selected.clear();
        expanded.clear();
        updateTotal();
    }

    private String selectionKey(ReportTable.Detail row) {
        return row.transactionGuid == null ? row.guid : row.transactionGuid;
    }

    Map<String, String> selectionRevisions() {
        Map<String, String> result = new LinkedHashMap<>();
        for (ReportTable.Detail row : shown) {
            String guid = selectionKey(row);
            if (selected.containsKey(guid)) result.put(guid, row.transactionVersion);
        }
        return result;
    }

    private void selectRow(ReportTable.Detail row) {
        String key = selectionKey(row);
        if (selected.containsKey(key)) selected.remove(key);
        else selected.put(key, row.transactionAmount);
        updateTotal();
    }

    private void selectResults() {
        boolean all = allSelected();
        selected.clear();
        if (!all) {
            for (ReportTable.Detail row : shown) selected.put(selectionKey(row), row.transactionAmount);
        }
        updateTotal();
    }

    private boolean allSelected() {
        if (selected.isEmpty()) return false;
        for (ReportTable.Detail row : shown) {
            if (!selected.containsKey(selectionKey(row))) return false;
        }
        return true;
    }

    private void updateTotal() {
        long sum = 0;
        if (filtering) {
            total.setText(tr("Searching…", "Wyszukiwanie…"));
        } else if (selecting) {
            for (long amount : selected.values()) sum += amount;
            total.setText(tr("Selected: ", "Zaznaczone: ") + selected.size() + tr(" · total ", " · suma ") + a.cash(sum));
        } else {
            for (ReportTable.Detail detail : shown) sum += detail.amount;
            total.setText(shown.size() + " / " + source.size() + tr(" transactions · total ", " transakcji · suma ") + a.cash(sum));
        }
        if (selectionControls != null) {
            selectionControls.setVisibility(selecting ? View.VISIBLE : View.GONE);
            selectAll.setEnabled(!shown.isEmpty());
            selectAll.setText(allSelected() ? tr("Clear selection", "Odznacz wszystko")
                : tr("Select all results", "Zaznacz wyniki"));
        }
        if (bulkCategory != null) bulkCategory.update();
        adapter.notifyDataSetChanged();
    }

    void choosePayee() {
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(a.px(12), 0, a.px(12), 0);
        layout.setFocusableInTouchMode(true);
        EditText query = new EditText(a);
        query.setSingleLine(true);
        query.setHint(tr("Find a payee", "Znajdź płatnika"));
        query.setContentDescription("report-detail-payee-search");
        layout.addView(query);
        final List<String> keys = new ArrayList<String>();
        final List<String> sorted = new ArrayList<String>(payees.keySet());
        Collections.sort(sorted, (left, right) -> payees.get(left).compareToIgnoreCase(payees.get(right)));
        ArrayAdapter<String> choices = new ArrayAdapter<String>(a, android.R.layout.simple_list_item_single_choice);
        ListView list = new ListView(a);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setContentDescription("report-detail-payee-options");
        list.setAdapter(choices);
        int height = Math.min(a.px(320), Math.max(a.px(120), a.getResources().getDisplayMetrics().heightPixels - a.px(240)));
        layout.addView(list, new LinearLayout.LayoutParams(-1, height));
        Runnable update = () -> {
            choices.clear();
            keys.clear();
            keys.add(null);
            choices.add(tr("All payees", "Wszyscy płatnicy"));
            for (String key : sorted) {
                if (SearchText.matches(payees.get(key), query.getText().toString())) {
                    keys.add(key);
                    choices.add(payees.get(key));
                }
            }
            choices.notifyDataSetChanged();
            list.clearChoices();
            int selected = keys.indexOf(payee);
            if (selected >= 0) list.setItemChecked(selected, true);
        };
        query.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            public void onTextChanged(CharSequence value, int start, int before, int count) { update.run(); }
            public void afterTextChanged(Editable value) { }
        });
        update.run();
        AlertDialog dialog = new AlertDialog.Builder(a).setTitle(tr("Payee", "Płatnik")).setView(layout)
            .setNegativeButton(tr("Cancel", "Anuluj"), null).create();
        list.setOnItemClickListener((parent, view, position, id) -> {
            payee = keys.get(position);
            dialog.dismiss();
            refresh();
        });
        dialog.show();
        layout.requestFocus();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    final class Rows extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int position) { return shown.get(position); }
        public long getItemId(int position) { return position; }
        public View getView(int position, View reuse, ViewGroup parent) {
            LinearLayout card;
            if (reuse == null) {
                card = new LinearLayout(a);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(a.px(10), a.px(7), a.px(10), a.px(7));
                LinearLayout first = new LinearLayout(a);
                first.setGravity(Gravity.CENTER_VERTICAL);
                TextView payee = a.text("", 14, ReportsActivity.TEXT);
                payee.setTypeface(null, Typeface.BOLD);
                payee.setSingleLine(true);
                payee.setEllipsize(TextUtils.TruncateAt.END);
                first.addView(payee, new LinearLayout.LayoutParams(0, -2, 1));
                TextView amount = a.text("", 14, ReportsActivity.TEXT);
                amount.setTypeface(null, Typeface.BOLD);
                amount.setSingleLine(true);
                amount.setPadding(a.px(8), 0, 0, 0);
                first.addView(amount);
                TextView disclosure = a.text("", 13, ReportsActivity.MUTED);
                disclosure.setPadding(a.px(5), 0, 0, 0);
                first.addView(disclosure);
                card.addView(first);
                card.addView(a.text("", 11, ReportsActivity.MUTED));
                TextView note = a.text("", 11, ReportsActivity.MUTED);
                note.setMaxLines(2);
                note.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(note);
                Button edit = a.chip(tr("Edit", "Edytuj"), "report-detail-edit", null);
                edit.setTextSize(13);
                LinearLayout.LayoutParams editLayout = new LinearLayout.LayoutParams(-2, a.px(40));
                editLayout.topMargin = a.px(4);
                card.addView(edit, editLayout);
            } else card = (LinearLayout) reuse;
            ReportTable.Detail detail = shown.get(position);
            boolean open = expanded.contains(detail.guid);
            boolean checked = selecting && selected.containsKey(selectionKey(detail));
            String description = selecting ? checked ? "report-detail-selected:" : "report-detail-selectable:"
                : open ? "report-detail-row-expanded:" : "report-detail-row:";
            card.setContentDescription(description + detail.guid);
            LinearLayout first = (LinearLayout) card.getChildAt(0);
            TextView payee = (TextView) first.getChildAt(0);
            payee.setSingleLine(!open);
            payee.setMaxLines(open ? Integer.MAX_VALUE : 1);
            payee.setEllipsize(open ? null : TextUtils.TruncateAt.END);
            payee.setText(detail.payee);
            TextView amount = (TextView) first.getChildAt(1);
            amount.setText(a.cash(detail.amount));
            amount.setTextColor(detail.amount < 0 ? ReportsActivity.SPEND : detail.amount > 0 ? ReportsActivity.INCOME : ReportsActivity.MUTED);
            amount.setContentDescription("report-detail-amount:" + (a.hide ? "hidden" : detail.amount));
            ((TextView) first.getChildAt(2)).setText(selecting ? checked ? "☑" : "☐" : open ? "▴" : "▾");
            ((TextView) card.getChildAt(1)).setText((open ? detail.date : detail.date.substring(0, Math.min(10, detail.date.length()))) + " · " + detail.account);
            TextView memo = (TextView) card.getChildAt(2);
            memo.setMaxLines(open ? Integer.MAX_VALUE : 2);
            memo.setEllipsize(open ? null : TextUtils.TruncateAt.END);
            memo.setText(detail.category + (detail.memo == null || detail.memo.isEmpty() ? "" : " · " + detail.memo));
            Button edit = (Button) card.getChildAt(3);
            boolean canEdit = filters != null && !selecting && open && detail.id > 0;
            edit.setVisibility(canEdit ? View.VISIBLE : View.GONE);
            edit.setContentDescription("report-detail-edit:" + detail.guid);
            // A split part opens its whole split transaction, as in the original register.
            final long target = detail.parentId > 0 ? detail.parentId : detail.id;
            edit.setOnClickListener(canEdit ? v -> a.editTransaction(ReportTransactions.this, target) : null);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(a.px(8));
            background.setColor(checked ? Color.rgb(215, 238, 247) : position % 2 == 0 ? Color.rgb(244, 248, 250) : Color.WHITE);
            card.setBackground(background);
            return card;
        }
    }
}

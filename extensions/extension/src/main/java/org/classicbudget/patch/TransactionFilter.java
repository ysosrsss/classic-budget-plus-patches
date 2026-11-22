package org.classicbudget.patch;

import org.json.JSONException;
import org.json.JSONObject;

/** A named view captures the full local search, including the existing sort and selectors. */
final class TransactionFilter {
    private static final String[] STATUS_EN = {"Any status", "Uncleared", "Cleared", "Reconciled", "Unreconciled"};
    private static final String[] STATUS_PL = {"Dowolny status", "Nierozliczone", "Rozliczone", "Uzgodnione", "Nieuzgodnione"};
    private static final String[] FIELD_EN = {"All fields", "Payee", "Account", "Category", "Memo", "Amount", "Check number"};
    private static final String[] FIELD_PL = {"Wszystkie pola", "Płatnik", "Konto", "Kategoria", "Notatka", "Kwota", "Numer czeku"};
    // The flag order uses the stored values from the native editor's flag mapping.
    private static final String[] FLAG_EN = {"Any flag", "No flag", "Red", "Blue", "Yellow", "Green", "Purple", "Orange"};
    private static final String[] FLAG_PL = {"Dowolna flaga", "Bez flagi", "Czerwona", "Niebieska", "Żółta", "Zielona", "Fioletowa", "Pomarańczowa"};
    private static final String[] APPROVAL_EN = {"Any approval", "Approved", "Unapproved"};
    private static final String[] APPROVAL_PL = {"Dowolne zatwierdzenie", "Zatwierdzone", "Niezatwierdzone"};
    private static final String[] AMOUNT_EN = {"Any amount", "Equal to", "Greater than", "Less than"};
    private static final String[] AMOUNT_PL = {"Dowolna kwota", "Równa", "Większa niż", "Mniejsza niż"};

    static final int UNRECONCILED = 4;
    String first = "";
    String last = "";
    int status;
    int field;
    int flag;
    int approval;
    int comparison;
    long threshold;
    boolean needsCategory;
    String query = "";
    String account;
    String payee;
    int sign;
    int order;

    static String[] statuses() {
        return ReportsActivity.tr("en", "pl").equals("pl") ? STATUS_PL : STATUS_EN;
    }

    static String[] fields() {
        return ReportsActivity.tr("en", "pl").equals("pl") ? FIELD_PL : FIELD_EN;
    }

    static String[] flags() {
        return ReportsActivity.tr("en", "pl").equals("pl") ? FLAG_PL : FLAG_EN;
    }

    static String[] approvals() {
        return ReportsActivity.tr("en", "pl").equals("pl") ? APPROVAL_PL : APPROVAL_EN;
    }

    static String[] comparisons() {
        return ReportsActivity.tr("en", "pl").equals("pl") ? AMOUNT_PL : AMOUNT_EN;
    }

    boolean accepts(ReportTable.Detail detail) {
        String date = detail.date == null ? "" : detail.date.substring(0, Math.min(10, detail.date.length()));
        if ((!first.isEmpty() || !last.isEmpty()) && date.isEmpty()) return false;
        if (!first.isEmpty() && date.compareTo(first) < 0) return false;
        if (!last.isEmpty() && date.compareTo(last) > 0) return false;
        if (needsCategory && !detail.needsCategory) return false;
        if (flag != 0 && flag - 1 != detail.flag) return false;
        if (approval != 0 && (approval == 1) != detail.accepted) return false;
        if (comparison != 0) {
            int compared = ReportDetailModel.magnitude(detail.amount, threshold);
            if (comparison == 1 && compared != 0) return false;
            if (comparison == 2 && compared <= 0) return false;
            if (comparison == 3 && compared >= 0) return false;
        }
        if (status == UNRECONCILED) return detail.clearance == 0 || detail.clearance == 1;
        return status == 0 || detail.clearance == status - 1;
    }

    String searchField(ReportTable.Detail detail, String amount, String allFields) {
        switch (field) {
            case 1: return detail.payee;
            case 2: return detail.account;
            case 3: return detail.category;
            case 4: return detail.memo;
            case 5: return amount;
            case 6: return detail.checkNumber;
            default: return allFields;
        }
    }

    JSONObject encode() throws JSONException {
        return new JSONObject().put("first", first).put("last", last).put("status", status)
            .put("field", field).put("needsCategory", needsCategory).put("query", query)
            .put("account", account).put("payee", payee).put("sign", sign).put("order", order)
            .put("flag", flag).put("approval", approval).put("comparison", comparison).put("threshold", threshold);
    }

    static TransactionFilter decode(JSONObject value) {
        TransactionFilter filter = new TransactionFilter();
        filter.first = date(value.optString("first"));
        filter.last = date(value.optString("last"));
        filter.status = option(value, "status", STATUS_EN.length);
        filter.field = option(value, "field", FIELD_EN.length);
        filter.needsCategory = value.optBoolean("needsCategory");
        filter.query = value.optString("query");
        filter.account = value.isNull("account") ? null : value.optString("account");
        filter.payee = value.isNull("payee") ? null : value.optString("payee");
        filter.sign = option(value, "sign", 3);
        filter.order = option(value, "order", 4);
        filter.flag = option(value, "flag", FLAG_EN.length);
        filter.approval = option(value, "approval", APPROVAL_EN.length);
        filter.comparison = option(value, "comparison", AMOUNT_EN.length);
        filter.threshold = Math.max(0, value.optLong("threshold"));
        return filter;
    }

    private static String date(String value) {
        return value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? value : "";
    }

    private static int option(JSONObject value, String key, int count) {
        int index = value.optInt(key);
        return index >= 0 && index < count ? index : 0;
    }
}

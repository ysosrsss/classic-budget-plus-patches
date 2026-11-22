package org.classicbudget.patch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.math.BigDecimal;

/** Local presentation of already scoped report details; never queries or changes the budget. */
public final class ReportDetailModel {
    public static final String NO_PAYEE = "\u0000";
    private static String text(String value) { return value == null ? "" : value; }

    public static String payeeKey(ReportTable.Detail detail) {
        return detail.payeeGuid == null || detail.payeeGuid.isEmpty() ? NO_PAYEE : detail.payeeGuid;
    }

    static int magnitude(long a, long b) {
        if (a == Long.MIN_VALUE) return b == Long.MIN_VALUE ? 0 : 1;
        if (b == Long.MIN_VALUE) return -1;
        long x = Math.abs(a);
        long y = Math.abs(b);
        return x < y ? -1 : x == y ? 0 : 1;
    }

    /** sign: 0 all, 1 negative, 2 positive; order: newest, oldest, largest magnitude, smallest magnitude. */
    public static List<ReportTable.Detail> select(List<ReportTable.Detail> source, String query,
            String accountGuid, int sign, final int order) {
        return select(source, query, accountGuid, null, sign, order);
    }

    public static List<ReportTable.Detail> select(List<ReportTable.Detail> source, String query,
            String accountGuid, String payeeGuid, int sign, final int order) {
        return select(source, query, accountGuid, payeeGuid, sign, order, null);
    }

    static List<ReportTable.Detail> select(List<ReportTable.Detail> source, String query,
            String accountGuid, String payeeGuid, int sign, final int order, TransactionFilter filter) {
        List<ReportTable.Detail> result = new ArrayList<ReportTable.Detail>();
        SearchText.Query match = new SearchText.Query(query);
        for (ReportTable.Detail detail : source) {
            if (Thread.currentThread().isInterrupted()) return Collections.emptyList();
            if (accountGuid != null && !accountGuid.equals(detail.accountGuid)) continue;
            if (payeeGuid != null && !payeeGuid.equals(payeeKey(detail))) continue;
            if (sign == 1 && detail.amount >= 0 || sign == 2 && detail.amount <= 0) continue;
            if (filter != null && !filter.accepts(detail)) continue;
            if (match.isEmpty()) {
                result.add(detail);
                continue;
            }
            String amount = BigDecimal.valueOf(detail.amount, 2).toPlainString();
            String amounts = amount + " " + amount.replace('.', ',');
            String searchable = text(detail.date) + " " + text(detail.payee) + " " + text(detail.account)
                + " " + text(detail.category) + " " + text(detail.memo) + " " + amounts;
            if (filter != null) searchable = filter.searchField(detail, amounts, searchable + " " + text(detail.checkNumber));
            if (match.matches(searchable)) result.add(detail);
        }
        Collections.sort(result, new Comparator<ReportTable.Detail>() {
            public int compare(ReportTable.Detail a, ReportTable.Detail b) {
                int value;
                if (order == 2 || order == 3) {
                    value = magnitude(a.amount, b.amount);
                    if (order == 2) value = -value;
                } else {
                    value = text(a.date).compareTo(text(b.date));
                    if (order == 0) value = -value;
                }
                if (value != 0) return value;
                return text(a.guid).compareTo(text(b.guid));
            }
        });
        return result;
    }
}

package org.classicbudget.patch;

import android.database.Cursor;
import java.util.*;

/** Desktop-shaped read-only data: identity is a database ID, never a translated label. */
public final class ReportTable {
    public static final class Row {
        public String key, name, kind;
        public long category=Long.MIN_VALUE, group=Long.MIN_VALUE, payee=Long.MIN_VALUE;
        public long[] amounts;
        Long averageOverride;
        boolean aggregateForNetAverage;
        public final List<Row> children=new ArrayList<Row>();
        public long total(){long n=0;for(long x:amounts)n+=x;return n;}
        public long average() {
            return averageOverride == null ? ReportTable.average(total(), amounts.length) : averageOverride;
        }
    }
    public final List<String> months;
    public final Row income, expenses, net;
    public final List<Row> groups=new ArrayList<Row>();
    static Row row(String key,String name,String kind,int n){Row r=new Row();r.key=key;r.name=name;r.kind=kind;r.amounts=new long[n];return r;}
    public ReportTable(ReportData data,ReportData.Filter filter){
        months=ReportData.months(filter.first,filter.last);
        income=row("income",PlusLang.tr("Income","Przychody"),"income",months.size());
        expenses=row("expenses",PlusLang.tr("Expenses","Wydatki"),"expense",months.size());
        net=row("net",PlusLang.tr("Net income","Dochód netto"),"all",months.size());
        Map<Long,Row> byGroup=new LinkedHashMap<Long,Row>(), byCategory=new HashMap<Long,Row>();
        ReportData.Where w=data.where(filter,true,true);
        String sql="SELECT substr(t.date,1,7),COALESCE(m._id,-1),COALESCE(m.name,'Uncategorized'),COALESCE(c._id,-1),COALESCE(c.name,'Uncategorized'),SUM(t.amount),m.guid"
            +ReportData.JOINS+w.sql+" GROUP BY 1,2,4 ORDER BY CASE WHEN m.guid='MasterCategory/__Hidden__' OR m.name='__Hidden__' THEN 1 ELSE 0 END,m.sequence,c.sequence";
        try(Cursor c=data.db.rawQuery(sql,w.args.toArray(new String[0]))){while(c.moveToNext()){
            int index=months.indexOf(c.getString(0));if(index<0)continue;
            long gid=c.getLong(1),cid=c.getLong(3);
            Row g=byGroup.get(gid);if(g==null){g=row("group:"+gid,localized(c.getString(2)),"expense",months.size());g.group=gid;byGroup.put(gid,g);groups.add(g);}
            g.aggregateForNetAverage = gid == -1 || "MasterCategory/__Hidden__".equals(c.getString(6))
                || "__Hidden__".equals(c.getString(2));
            Row r=byCategory.get(cid);if(r==null){r=row("category:"+cid,localized(c.getString(4)),"expense",months.size());r.category=cid;r.group=gid;g.children.add(r);byCategory.put(cid,r);}
            long amount=c.getLong(5);r.amounts[index]+=amount;g.amounts[index]+=amount;expenses.amounts[index]+=amount;
        }}
        ReportData.Where wi=data.where(filter,true,true);
        String incomeWhere=wi.sql.toString().replace(ReportData.EXPENSE,"");
        Map<Long,Row> payees=new LinkedHashMap<Long,Row>();
        sql="SELECT substr(t.date,1,7),COALESCE(p._id,-1),COALESCE(p.name,'—'),SUM(t.amount)"+ReportData.JOINS
            +" LEFT JOIN payeeTable p ON p._id=COALESCE(t.payeeId,pt.payeeId)"+incomeWhere
            +" AND c.guid IN ('Category/__ImmediateIncome__','Category/__DeferredIncome__')"+ReportData.LEAF+" GROUP BY 1,2 ORDER BY p.name COLLATE NOCASE";
        try(Cursor c=data.db.rawQuery(sql,wi.args.toArray(new String[0]))){while(c.moveToNext()){
            int index=months.indexOf(c.getString(0));if(index<0)continue;
            long id=c.getLong(1);Row r=payees.get(id);
            if(r==null){r=row("source:"+id,c.getString(2),"income",months.size());r.payee=id;payees.put(id,r);income.children.add(r);}
            r.amounts[index]+=c.getLong(3);income.amounts[index]+=c.getLong(3);
        }}
        sortIncome(false);
        for(int i=0;i<months.size();i++)net.amounts[i]=income.amounts[i]+expenses.amounts[i];
        // Windows ProfitAndLossDataProvider sums already-rounded leaf averages
        // for Net Income. Hidden and Uncategorized count as one aggregate each.
        // Averaging the combined net total instead can differ by a few cents.
        long netAverage = 0;
        for (Row source : income.children) {
            netAverage += source.average();
        }
        for (Row group : groups) {
            if (group.aggregateForNetAverage) {
                netAverage += group.average();
            } else {
                for (Row category : group.children) {
                    netAverage += category.average();
                }
            }
        }
        net.averageOverride = netAverage;
    }
    static long average(long cents,int count){return java.math.BigDecimal.valueOf(cents).divide(java.math.BigDecimal.valueOf(Math.max(1,count)),0,java.math.RoundingMode.HALF_UP).longValue();}
    static String localized(String name){return "Uncategorized".equals(name)?PlusLang.tr("Uncategorized","Bez kategorii"):"__Hidden__".equals(name)?PlusLang.tr("Hidden Categories","Ukryte kategorie"):name;}

    public static final class Detail {public String date,account,accountGuid,payee,payeeGuid,category,memo,guid;public long amount;
        /** txnTable ids, set only by allTransactions (090): the row to open in the original editor. */
        public long id,parentId;
        public int clearance = -1;
        public boolean needsCategory;
        public String transactionGuid, checkNumber;
        public String transactionVersion;
        public long transactionAmount;
        public int flag;
        public boolean accepted;
    }
    public static List<Detail> details(ReportData data,ReportData.Filter filter,Row scope,String month){
        ReportData.Where w=data.where(filter,true,true);
        if(scope!=null&&"income".equals(scope.kind))w.sql.replace(0,w.sql.length(),w.sql.toString().replace(ReportData.EXPENSE,"")
            +" AND c.guid IN ('Category/__ImmediateIncome__','Category/__DeferredIncome__')"+ReportData.LEAF);
        else if(scope!=null&&"all".equals(scope.kind))w.sql.replace(0,w.sql.length(),w.sql.toString().replace(ReportData.EXPENSE,"")
            +" AND ((c.masterCategoryId IS NOT NULL OR t.categoryId IS NULL) OR c.guid IN ('Category/__ImmediateIncome__','Category/__DeferredIncome__'))"+ReportData.LEAF+" AND COALESCE(ta.onBudget,0)=0");
        if(month!=null){w.sql.append(" AND substr(t.date,1,7)=?");w.args.add(month);}
        if(scope!=null){
            if(scope.category!=Long.MIN_VALUE){w.sql.append(" AND COALESCE(c._id,-1)=CAST(? AS INTEGER)");w.args.add(String.valueOf(scope.category));}
            else if(scope.group!=Long.MIN_VALUE){w.sql.append(" AND COALESCE(m._id,-1)=CAST(? AS INTEGER)");w.args.add(String.valueOf(scope.group));}
            if(scope.payee!=Long.MIN_VALUE){w.sql.append(" AND COALESCE(t.payeeId,pt.payeeId,-1)=CAST(? AS INTEGER)");w.args.add(String.valueOf(scope.payee));}
        }
        String sql="SELECT t.guid,t.date,a.name,COALESCE(p.name,'—'),COALESCE(c.name,'Uncategorized'),COALESCE(t.memo,''),t.amount,a.guid,p.guid"+ReportData.JOINS
            +" LEFT JOIN payeeTable p ON p._id=COALESCE(t.payeeId,pt.payeeId)"+w.sql+" ORDER BY t.date DESC,t._id DESC";
        List<Detail> out=new ArrayList<Detail>();
        try(Cursor c=data.db.rawQuery(sql,w.args.toArray(new String[0]))){while(c.moveToNext()){
            Detail d=new Detail();d.guid=c.getString(0);d.date=c.getString(1);d.account=c.getString(2);d.payee=c.getString(3);d.category=localized(c.getString(4));d.memo=c.getString(5);d.amount=c.getLong(6);d.accountGuid=c.getString(7);d.payeeGuid=c.getString(8);out.add(d);
        }}return out;
    }

    /**
     * Every transaction of the budget for "Search transactions" (089): all non-deleted accounts,
     * budget and off-budget, all dates, transfers and income included, split parts instead of the
     * split parent (LEAF). Read only. Both sides of a transfer are listed, each on its own account,
     * like in the account registers. Report filters do not apply here.
     */
    public static List<Detail> allTransactions(ReportData data){
        String sql="SELECT t.guid,t.date,a.name,COALESCE(p.name,'—'),c.name,c.guid,ta.name,COALESCE(t.memo,''),t.amount,a.guid,p.guid,t._id,COALESCE(t.parentId,0)"
            +",CASE WHEN pt._id IS NULL THEN t.cleared ELSE pt.cleared END"
            +",CASE WHEN a.onBudget=1 AND t.amount<>0 AND c._id IS NULL"
            +" AND (t.targAccountId IS NULL OR ta.onBudget=0) THEN 1 ELSE 0 END"
            +",COALESCE(pt.guid,t.guid),COALESCE(pt.amount,t.amount),COALESCE(pt.flag,t.flag,0),COALESCE(pt.accepted,t.accepted),COALESCE(pt.checkNo,t.checkNo)"
            +",CASE WHEN pt._id IS NULL THEN t.version ELSE pt.version END"
            +" FROM txnTable t JOIN accountTable a ON a._id=t.accountId"
            +" LEFT JOIN categoryTable c ON c._id=t.categoryId"
            +" LEFT JOIN txnTable pt ON pt._id=t.parentId"
            +" LEFT JOIN accountTable ta ON ta._id=t.targAccountId"
            +" LEFT JOIN payeeTable p ON p._id=COALESCE(t.payeeId,pt.payeeId)"
            +" WHERE t.deleted=0 AND a.deleted=0"+ReportData.LEAF
            +" ORDER BY t.date DESC,t._id DESC";
        List<Detail> out=new ArrayList<Detail>();
        try(Cursor c=data.db.rawQuery(sql,null)){
            while(c.moveToNext()){
                Detail d=new Detail();
                d.guid=c.getString(0);
                d.date=c.getString(1);
                d.account=c.getString(2);
                d.payee=c.getString(3);
                d.category=searchCategory(c.getString(4),c.getString(5),c.getString(6));
                d.memo=c.getString(7);
                d.amount=c.getLong(8);
                d.accountGuid=c.getString(9);
                d.payeeGuid=c.getString(10);
                d.id=c.getLong(11);
                d.parentId=c.getLong(12);
                d.clearance = c.isNull(13) ? -1 : c.getInt(13);
                d.needsCategory = c.getInt(14) != 0;
                d.transactionGuid = c.getString(15);
                d.transactionAmount = c.getLong(16);
                d.flag = c.getInt(17);
                d.accepted = c.getInt(18) != 0;
                d.checkNumber = c.getString(19);
                d.transactionVersion = c.getString(20);
                out.add(d);
            }
        }
        return out;
    }

    /** Same ledger rows used for the Activity cell in the budget table, including refunds. */
    static List<Detail> budgetActivity(ReportData data, String month, String categoryGuid) {
        String sql = "SELECT t.guid,t.date,a.name,COALESCE(p.name,'—'),c.name,COALESCE(t.memo,''),t.amount,a.guid,p.guid"
            + " FROM txnTable t JOIN accountTable a ON a._id=t.accountId JOIN categoryTable c ON c._id=t.categoryId"
            + " LEFT JOIN txnTable pt ON pt._id=t.parentId LEFT JOIN payeeTable p ON p._id=COALESCE(t.payeeId,pt.payeeId)"
            + " WHERE t.deleted=0 AND a.deleted=0 AND a.onBudget=1 AND c.guid=? AND substr(t.date,1,7)=?"
            + ReportData.LEAF + " ORDER BY t.date DESC,t._id DESC";
        List<Detail> details = new ArrayList<>();
        try (Cursor cursor = data.db.rawQuery(sql, new String[]{categoryGuid, month})) {
            while (cursor.moveToNext()) {
                Detail row = new Detail();
                row.guid = cursor.getString(0);
                row.date = cursor.getString(1);
                row.account = cursor.getString(2);
                row.payee = cursor.getString(3);
                row.category = cursor.getString(4);
                row.memo = cursor.getString(5);
                row.amount = cursor.getLong(6);
                row.accountGuid = cursor.getString(7);
                row.payeeGuid = cursor.getString(8);
                details.add(row);
            }
        }
        return details;
    }

    /** Category text of a search row: transfer target, income, or the category name. */
    static String searchCategory(String name,String guid,String targetAccount){
        if(targetAccount!=null)return PlusLang.tr("Transfer: ","Przelew: ")+targetAccount;
        if("Category/__ImmediateIncome__".equals(guid)||"Category/__DeferredIncome__".equals(guid))return PlusLang.tr("Income","Przychód");
        return localized(name==null?"Uncategorized":name);
    }

    /** Income sources: by period total, largest first (default, 071), or alphabetically (076). */
    public void sortIncome(boolean alphabetical) {
        Collections.sort(income.children, (left, right) -> {
            int value = alphabetical ? left.name.compareToIgnoreCase(right.name) : Long.compare(right.total(), left.total());
            if (value == 0) value = alphabetical ? Long.compare(right.total(), left.total()) : left.name.compareToIgnoreCase(right.name);
            return value == 0 ? left.key.compareTo(right.key) : value;
        });
    }
}

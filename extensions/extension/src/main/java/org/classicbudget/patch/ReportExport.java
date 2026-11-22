package org.classicbudget.patch;

import java.util.*;
import java.math.BigDecimal;

/** One numeric report snapshot for CSV and printable HTML. No networking or automatic exports. */
final class ReportExport {
    static String amount(long cents){return BigDecimal.valueOf(cents,2).toPlainString();}
    static List<String> cells(String... values){return Arrays.asList(values);}
    static List<List<String>> rows(ReportData data,ReportData.Filter f,String report,boolean trends){
        List<List<String>> out=new ArrayList<List<String>>();
        out.add(cells("Budget",data.budgetName()));out.add(cells("Period",f.first,f.last));
        if("income".equals(report)){
            ReportTable model=new ReportTable(data,f);List<String> header=new ArrayList<String>();header.add("Category / source");header.addAll(model.months);header.add("Average");header.add("Total");out.add(header);
            // Same row order as the on-screen table: Income, sources, Expenses, groups with categories, Net.
            add(out,model.income);for(ReportTable.Row source:model.income.children)add(out,source);
            add(out,model.expenses);
            for(ReportTable.Row g:model.groups){add(out,g);for(ReportTable.Row sub:g.children)add(out,sub);}
            add(out,model.net);
        }else if("networth".equals(report)||("category".equals(report)&&trends)){
            boolean worth="networth".equals(report);List<ReportData.Month> months=data.monthly(f);
            if(worth){out.add(cells("Month","Assets","Debts","Net worth"));for(ReportData.Month m:months)out.add(cells(m.month,amount(m.assets),amount(-m.debts),amount(m.net())));}
            else{
                Set<String> groups=new LinkedHashSet<String>();for(ReportData.Month m:months)groups.addAll(m.groups.keySet());
                List<String> header=new ArrayList<String>();header.add("Month");header.addAll(groups);header.add("Total");out.add(header);
                for(ReportData.Month m:months){List<String> row=new ArrayList<String>();row.add(m.month);for(String g:groups)row.add(amount(m.groups.containsKey(g)?m.groups.get(g):0));row.add(amount(m.spending));out.add(row);}
            }
        }else{
            out.add(cells("Group","Category / payee / device","Net spending"));
            List<ReportData.Group> groups="payee".equals(report)?data.byPayee(f):"person".equals(report)?data.byDevice(f):data.byCategory(f);
            for(ReportData.Group g:groups){out.add(cells(g.name,"",amount(g.amount)));for(ReportData.Group child:g.children)out.add(cells(g.name,child.name,amount(child.amount)));}
        }
        return out;
    }
    static void add(List<List<String>> out,ReportTable.Row r){List<String> row=new ArrayList<String>();row.add(r.name);for(long n:r.amounts)row.add(amount(n));row.add(amount(r.average()));row.add(amount(r.total()));out.add(row);}
    /** CSV cell; text a spreadsheet could run as a formula (=,+,@,-,TAB,CR first) gets a leading apostrophe. Plain numbers stay numbers. */
    static String quote(String value){
        String v=value==null?"":value;
        boolean number=v.matches("-?[0-9]+(\\.[0-9]+)?");
        if(!number&&!v.isEmpty()&&"=+-@\t\r".indexOf(v.charAt(0))>=0)v="'"+v;
        return "\""+v.replace("\"","\"\"")+"\"";
    }
    static String csv(List<List<String>> rows){StringBuilder b=new StringBuilder("\ufeff");for(List<String> row:rows){for(int i=0;i<row.size();i++){if(i>0)b.append(',');b.append(quote(row.get(i)));}b.append("\r\n");}return b.toString();}
    static String escape(String v){return v.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String html(List<List<String>> rows){StringBuilder b=new StringBuilder("<html><head><meta charset='utf-8'><style>body{font:12px sans-serif}table{border-collapse:collapse}td{padding:6px;border:1px solid #ccc;text-align:right}td:first-child{text-align:left}tr:nth-child(odd){background:#f2f5f6}@page{size:landscape}</style></head><body><h1>YNAB Classic Plus</h1><table>");for(List<String> row:rows){b.append("<tr>");for(String cell:row)b.append("<td>").append(escape(cell)).append("</td>");b.append("</tr>");}return b.append("</table></body></html>").toString();}
}

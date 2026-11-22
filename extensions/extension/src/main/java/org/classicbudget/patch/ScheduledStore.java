package org.classicbudget.patch;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.lang.reflect.*;
import java.util.*;

/** TEST-only definitions editor. Uses the live original adapter, builder, version counter and notifier.
 * Does not enter occurrences, edit txnTable, write SQL, or implement an alternate sync engine.
 */
public final class ScheduledStore {
    static final String C = "com.youneedabudget.ynab.core.";
    static final String[] FREQUENCIES = {"Once", "Daily", "Weekly", "EveryOtherWeek", "TwiceAMonth", "Every4Weeks", "Monthly", "EveryOtherMonth", "Every3Months", "Every4Months", "TwiceAYear", "Yearly", "EveryOtherYear"};
    public static final class Entry {
        public long id, account, payee, category, amount, target; public int twiceDay;
        public String guid, version, date, frequency, memo, accountName, payeeName, targetAccountName; public boolean complex, transfer; public int splitParts;
    }
    final Object adapter, budget; final SQLiteDatabase db; final android.content.Context context;
    static Object call(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        return target.getClass().getMethod(name, types).invoke(target, args);
    }
    static Object no(Object target, String name) throws Exception { return call(target, name, new Class<?>[0]); }
    public static ScheduledStore open(android.content.Context context) throws Exception {
        Object app = Class.forName(C+"app.d").getMethod("j").invoke(null);
        Field field=Class.forName(C+"app.d").getDeclaredField("h"); field.setAccessible(true);
        Map<?,?> map=(Map<?,?>)field.get(app); Object current=null;
        java.io.File selected=ReportData.database(context); if(selected==null)throw new Exception("Open the TEST budget first / Otwórz najpierw budżet TEST");
        synchronized(map) { for(Object b:map.values()) if(selected.getName().equals(no(b,"j"))) { if(current!=null)throw new Exception("Ambiguous budget"); current=b; } }
        if(current==null)throw new Exception("Open the budget home screen first / Najpierw otwórz ekran budżetu");
        return new ScheduledStore(context, current);
    }
    ScheduledStore(android.content.Context context, Object current) throws Exception {
        this.context=context.getApplicationContext();
        budget=current; adapter=no(current,"i"); db=(SQLiteDatabase)no(adapter,"c");
        String folder="";
        try(Cursor c=db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'",null)) { if(c.moveToFirst())folder=c.getString(0); }
        // Same rule as BudgetStore: TEST budgets and local COPY budgets only, never the family budget itself.
        String upper=folder==null?"":folder.toUpperCase(Locale.ROOT);
        if(PlusEdition.TEST_ONLY && !upper.contains("TEST") && !upper.contains("COPY"))throw new Exception(PlusLang.tr("Scheduled editor is limited to TEST and COPY budgets for now.",
            "Edytor zleceń działa na razie tylko w budżetach TEST i COPY."));
    }
    public List<Entry> entries() {
        List<Entry> out=new ArrayList<Entry>();
        String sql="SELECT s._id,s.guid,s.version,s.date,s.frequency,s.twiceAMonthStartDay,s.accountId,s.payeeId,s.categoryId,s.amount,s.memo,a.name,p.name,"
            +"s.targAccountId,s.transferGuid,(SELECT COUNT(*) FROM scheduledTxnTable ch WHERE ch.parentId=s._id AND ch.deleted=0),"
            +"(SELECT ta.name FROM accountTable ta WHERE ta._id=s.targAccountId)"
            +" FROM scheduledTxnTable s JOIN accountTable a ON a._id=s.accountId LEFT JOIN payeeTable p ON p._id=s.payeeId WHERE s.deleted=0 AND s.parentId IS NULL ORDER BY s.date,s._id";
        try(Cursor c=db.rawQuery(sql,null)) { while(c.moveToNext()) { Entry e=new Entry();e.id=c.getLong(0);e.guid=c.getString(1);e.version=c.getString(2);e.date=c.getString(3);e.frequency=c.getString(4);e.twiceDay=c.getInt(5);e.account=c.getLong(6);e.payee=c.isNull(7)?0:c.getLong(7);e.category=c.isNull(8)?0:c.getLong(8);e.amount=c.getLong(9);e.memo=c.getString(10);e.accountName=c.getString(11);e.payeeName=c.getString(12);e.splitParts=c.getInt(15);e.targetAccountName=c.getString(16);e.target=c.isNull(13)?0:c.getLong(13);e.complex=complex(e.target,c.getString(14),e.splitParts>0);e.transfer=singleTransfer(e.target,c.getString(14),e.splitParts>0);out.add(e); } }
        return out;
    }
    /** Read-only structures: a definition linked to another entry (transferGuid) or one with split parts.
     * A scheduled transfer made by the desktop app is ONE entity with targAccountId and an empty
     * transferGuid (checked on the real-budget copy, 13.11): it has no second half to keep in step. */
    static boolean complex(long targetAccount,String transferGuid,boolean children) {
        return (transferGuid!=null && !transferGuid.trim().isEmpty()) || children;
    }
    /** Single-entity scheduled transfer: date, frequency, amount and memo may change; account,
     * target account, transfer payee and category stay as they are. */
    static boolean singleTransfer(long targetAccount,String transferGuid,boolean children) {
        return targetAccount>0 && !complex(targetAccount,transferGuid,children);
    }
    /** Category rule for a NEW scheduled transfer, as in the desktop app: the source must be on budget;
     * a transfer to an on-budget account has no category, one to an off-budget account needs one. */
    static final String TRANSFER_UNSUPPORTED="unsupported", TRANSFER_NO_CATEGORY="no-category", TRANSFER_CATEGORY="category";
    static String transferRule(boolean sourceOnBudget, boolean targetOnBudget) {
        if(!sourceOnBudget)return TRANSFER_UNSUPPORTED;
        if(targetOnBudget)return TRANSFER_NO_CATEGORY;
        return TRANSFER_CATEGORY;
    }
    boolean onBudget(long account) throws Exception {
        try(Cursor c=db.rawQuery("SELECT onBudget FROM accountTable WHERE _id=? AND deleted=0",new String[]{Long.toString(account)})) {
            if(!c.moveToFirst())throw new Exception("Account no longer exists");
            return c.getInt(0)!=0;
        }
    }
    /** The desktop app's transfer payee of an account: guid "Payee/Transfer:" + account guid. */
    long transferPayee(long account) throws Exception {
        String sql="SELECT p._id FROM payeeTable p JOIN accountTable a ON p.guid='Payee/Transfer:'||a.guid WHERE a._id=? AND p.deleted=0";
        try(Cursor c=db.rawQuery(sql,new String[]{Long.toString(account)})) {
            if(!c.moveToFirst())throw new Exception(PlusLang.tr("This account has no transfer payee yet. Create one transfer in the desktop app first.",
                "To konto nie ma jeszcze płatnika przelewu. Utwórz najpierw jeden przelew w aplikacji komputerowej."));
            return c.getLong(0);
        }
    }
    void checkExisting(Entry e) throws Exception {
        if(e.id==0)return;
        Entry found=null;for(Entry row:entries())if(row.id==e.id)found=row;
        if(found==null || !Objects.equals(found.guid,e.guid) || !Objects.equals(found.version,e.version))throw new Exception("Schedule changed during editing. Reopen it. / Zlecenie zmieniło się podczas edycji. Otwórz ponownie.");
        if(found.complex)throw new Exception("Linked transfers and splits are read-only in this TEST editor");
        if(found.transfer!=e.transfer || found.target!=e.target || found.account!=e.account)throw new Exception("Schedule changed during editing. Reopen it. / Zlecenie zmieniło się podczas edycji. Otwórz ponownie.");
    }
    static void set(Object b,String method,Class<?> type,Object value)throws Exception { call(b,method,new Class<?>[]{type},value); }
    /** Save or tombstone a definition atomically; version is allocated by the original Knowledge class. */
    public void save(final Entry e, final boolean remove) throws Exception {
        if(!Arrays.asList(FREQUENCIES).contains(e.frequency))throw new Exception("Invalid frequency");
        java.text.SimpleDateFormat format=new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.ROOT);format.setLenient(false);
        if(e.date==null || !e.date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))throw new Exception("Use YYYY-MM-DD");format.parse(e.date);
        if(e.complex)throw new Exception("Complex schedules are read-only");
        if(remove && e.id==0)throw new Exception("Nothing to remove");
        final Exception[] error=new Exception[1];
        Runnable operation=new Runnable(){ public void run(){try {
            checkExisting(e);
            try(Cursor c=db.rawQuery("SELECT _id FROM accountTable WHERE _id=? AND deleted=0",new String[]{Long.toString(e.account)})) { if(!c.moveToFirst())throw new Exception("Account no longer exists"); }
            final boolean newTransfer=!remove && e.transfer && e.id==0;
            if(newTransfer){
                if(e.target==e.account)throw new Exception(PlusLang.tr("Choose a different target account.","Wybierz inne konto docelowe."));
                String rule=transferRule(onBudget(e.account),onBudget(e.target));
                if(TRANSFER_UNSUPPORTED.equals(rule))throw new Exception(PlusLang.tr("A scheduled transfer must start in an on-budget account.",
                    "Zaplanowany przelew musi wychodzić z konta budżetowego."));
                if(TRANSFER_NO_CATEGORY.equals(rule))e.category=0;
                if(TRANSFER_CATEGORY.equals(rule)){
                    try(Cursor c=db.rawQuery("SELECT _id FROM categoryTable WHERE _id=? AND deleted=0",new String[]{Long.toString(e.category)})){
                        if(!c.moveToFirst())throw new Exception(PlusLang.tr("Choose a category for a transfer to an off-budget account.",
                            "Wybierz kategorię dla przelewu na konto poza budżetem."));
                    }
                }
                e.payee=transferPayee(e.target);
            }
            if(!remove && !e.transfer){
                try(Cursor c=db.rawQuery("SELECT _id FROM payeeTable WHERE _id=? AND deleted=0",new String[]{Long.toString(e.payee)})){if(!c.moveToFirst())throw new Exception("Select an existing payee");}
                try(Cursor c=db.rawQuery("SELECT _id FROM categoryTable WHERE _id=? AND deleted=0",new String[]{Long.toString(e.category)})){if(!c.moveToFirst())throw new Exception("Select an existing category");}
            }
            Object builder=Class.forName(C+"c.ai").newInstance();
            Object knowledge=no(budget,"e");
            set(builder,"f",String.class,no(knowledge,"f")); // original counter increments within this DB transaction
            set(builder,"d",boolean.class,false); // unsent: original DiffExporter owns serialization and upload
            if(remove)set(builder,"c",boolean.class,true);
            else {
                if(!e.transfer || newTransfer){
                    set(builder,"a",long.class,e.account);set(builder,"c",long.class,e.payee);
                    // category 0 is stored as NULL by the original builder (transfer between on-budget accounts)
                    call(builder,"b",new Class<?>[]{Class.forName(C+"c.f"),long.class},adapter,e.category);
                }
                if(newTransfer)call(builder,"c",new Class<?>[]{Class.forName(C+"c.f"),long.class},adapter,e.target);
                // A transfer keeps its account, target, transfer payee and category: the original
                // builder updates only the columns it was given (ContentValues + SQLiteDatabase.update).
                set(builder,"c",String.class,e.date);set(builder,"d",long.class,e.amount);set(builder,"d",String.class,e.memo==null?"":e.memo);
                set(builder,"a",String.class,e.frequency);
                int day=Integer.parseInt(e.date.substring(8));
                int anchor="TwiceAMonth".equals(e.frequency)?(day<=15?day:day-15):0;
                // Preserve the existing base after February's clamped second occurrence.
                if("TwiceAMonth".equals(e.frequency) && e.twiceDay>=1 && e.twiceDay<=15 && (day==e.twiceDay || day==Math.min(e.twiceDay+15,ReportData.day(e.date).getActualMaximum(Calendar.DAY_OF_MONTH))))anchor=e.twiceDay;
                set(builder,"a",int.class,anchor);
                if(e.id==0){set(builder,"e",String.class,no(knowledge,"e"));set(builder,"b",Boolean.class,Boolean.TRUE);set(builder,"c",int.class,0);}
            }
            if(e.id==0)call(builder,"a",new Class<?>[]{Class.forName(C+"c.f")},adapter);
            else call(builder,"a",new Class<?>[]{Class.forName(C+"c.f"),long.class},adapter,e.id);
        } catch(Exception problem){error[0]=problem;throw new RuntimeException(problem);} }};
        try { call(adapter,"a",new Class<?>[]{Runnable.class},operation); }
        catch(Exception failed){throw error[0]!=null?error[0]:failed;}
        no(adapter,"d"); // same notifier used by original TxnEntryTask, after commit
        // This screen is not the original OverviewActivity: its observer can be paused.
        // Queue the original sync explicitly, so staying on this screen does not leave edits unsent.
        try {
            Class<?> reason=Class.forName(C+"cloud.o$a");
            Class.forName(C+"cloud.o").getMethod("a",android.content.Context.class,Class.forName(C+"cloud.g"),reason)
                .invoke(null,context,budget,reason.getField("a").get(null));
        } catch(Exception syncError) {
            // The save already committed. Do not report a failed save or invite duplicate creation.
            DavProvider.diagnostic(syncError);
            android.widget.Toast.makeText(context,PlusLang.tr("Saved locally. Open the budget home screen to retry sync.",
                "Zapisano lokalnie. Otwórz ekran budżetu, aby ponowić synchronizację."),android.widget.Toast.LENGTH_LONG).show();
        }
    }
}

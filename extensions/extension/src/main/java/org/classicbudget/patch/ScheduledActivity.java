package org.classicbudget.patch;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.Bundle;
import android.text.*;
import android.view.View;
import android.widget.*;
import java.math.BigDecimal;
import java.util.*;

/** First TEST editor of schedule definitions. Posting occurrences stays with the original sync gate. */
public final class ScheduledActivity extends Activity {
    ScheduledStore store; LinearLayout body; TextView status; EditText search; boolean editing;
    static String tr(String en,String pl){return PlusLang.tr(en,pl);}
    protected void onCreate(Bundle state){super.onCreate(state);PlusEdition.mark(this);setTitle(tr("Scheduled transactions","Zlecenia stałe"));list();}
    LinearLayout screen() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = px(16);
        content.setPadding(padding, padding, padding, padding);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        if (SyncStatusActivity.available(this)) page.addView(new SyncNoticeView(this));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);
        return content;
    }
    void note(LinearLayout l,String text){TextView t=new TextView(this);t.setText(text);t.setPadding(0,12,0,12);l.addView(t);}
    Button button(LinearLayout l,String text,String desc,View.OnClickListener click){Button b=new Button(this);b.setAllCaps(false);b.setText(text);b.setTextSize(15);b.setMinHeight(px(48));b.setContentDescription(desc);b.setOnClickListener(click);l.addView(b);return b;}
    int px(int value){return (int)(value*getResources().getDisplayMetrics().density);}
    String frequencyName(String raw){String[] en={"Once","Daily","Weekly","Every 2 weeks","Twice a month","Every 4 weeks","Monthly","Every 2 months","Every 3 months","Every 4 months","Every 6 months","Yearly","Every 2 years"};String[] pl={"Jednorazowo","Codziennie","Co tydzień","Co 2 tygodnie","Dwa razy w miesiącu","Co 4 tygodnie","Co miesiąc","Co 2 miesiące","Co 3 miesiące","Co 4 miesiące","Co 6 miesięcy","Co rok","Co 2 lata"};int at=Arrays.asList(ScheduledStore.FREQUENCIES).indexOf(raw);return at<0?raw:tr(en[at],pl[at]);}
    public void onBackPressed(){if(editing)list();else super.onBackPressed();}
    EditText field(LinearLayout l,String label,String value,String desc){note(l,label);EditText e=new EditText(this);e.setSingleLine(true);e.setText(value);e.setContentDescription(desc);l.addView(e);return e;}
    void problem(Throwable e){while(e.getCause()!=null)e=e.getCause();new AlertDialog.Builder(this).setMessage(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()).setPositiveButton("OK",null).show();}
    static final int TEXT = 0xFF212121, MUTED = 0xFF6E6E6E, OUT = 0xFFC44030, IN = 0xFF2E7D32, LINE = 0xFFE0E4E7, ACCENT = 0xFF14424F;

    /** Small outlined action like Budgeting (076): 48dp touch height, smaller pill drawn. */
    TextView chip(LinearLayout l, String text, String desc, View.OnClickListener click) {
        TextView c = new TextView(this);
        c.setText(text); c.setTextSize(13); c.setTextColor(ACCENT); c.setSingleLine(true);
        c.setGravity(android.view.Gravity.CENTER); c.setContentDescription(desc); c.setOnClickListener(click);
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(0xFFFFFFFF); pill.setStroke(px(1), 0xFFA0B0B8); pill.setCornerRadius(px(16));
        c.setBackground(new android.graphics.drawable.InsetDrawable(pill, 0, px(6), 0, px(6)));
        // After setBackground: an InsetDrawable replaces the view padding with its insets.
        c.setPadding(px(14), px(6), px(14), px(6));
        c.setMinHeight(px(48));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.setMarginEnd(px(8));
        l.addView(c, lp);
        return c;
    }

    void list(){
        editing=false;setTitle(tr("Scheduled transactions","Zlecenia stałe"));
        LinearLayout l=screen();
        LinearLayout actions=new LinearLayout(this);
        HorizontalScrollView actionScroll=new HorizontalScrollView(this);actionScroll.setHorizontalScrollBarEnabled(false);actionScroll.addView(actions);l.addView(actionScroll);
        chip(actions, tr("Upcoming", "Nadchodzące"), "schedule-preview", view -> {
            startActivity(new android.content.Intent(this, ReportsActivity.class).putExtra("schedule-preview", true));
        });
        try{store=ScheduledStore.open(this);}catch(Exception e){note(l,e.getMessage());button(l,tr("Open budget","Otwórz budżet"),"schedule-open-budget",new View.OnClickListener(){public void onClick(View v){startActivity(new Intent().setClassName(getPackageName(),"com.youneedabudget.ynab.app.OverviewActivity"));finish();}});return;}
        chip(actions,tr("+ Schedule","+ Zlecenie"),"schedule-add",new View.OnClickListener(){public void onClick(View v){edit(null,false);}});
        chip(actions,tr("+ Transfer","+ Przelew"),"schedule-add-transfer",new View.OnClickListener(){public void onClick(View v){edit(null,true);}});
        search=new EditText(this);search.setSingleLine(true);search.setHint(tr("Search payee, account, memo","Szukaj płatnika, konta, notatki"));search.setContentDescription("schedule-search");search.setTextSize(15);l.addView(search);
        body=new LinearLayout(this);body.setOrientation(1);l.addView(body);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){} public void onTextChanged(CharSequence s,int a,int b,int c){rows(s.toString());}public void afterTextChanged(Editable e){}});
        rows("");
        TextView about=new TextView(this);about.setTextSize(11);about.setTextColor(MUTED);about.setPadding(0,px(16),0,0);
        about.setText((PlusEdition.TEST_ONLY?tr("TEST and COPY budgets only. ","Tylko budżety TEST i COPY. "):"")
            +tr("Edits sync the schedule definition; they do not post a payment. With WebDAV/folder, the desktop app enters due occurrences. No end date yet. Single transfers can be edited; linked transfers and splits are read-only.","Edycja synchronizuje definicję zlecenia, nie księguje płatności. Przy WebDAV/folderze terminowe wystąpienia księguje aplikacja komputerowa. Na razie bez daty końcowej. Pojedyncze przelewy można edytować; powiązane przelewy i podziały są tylko do odczytu."));
        l.addView(about);
        l.setFocusableInTouchMode(true);l.requestFocus();
    }

    String money(long cents){java.text.NumberFormat f=java.text.NumberFormat.getNumberInstance(Locale.getDefault());f.setMinimumFractionDigits(2);f.setMaximumFractionDigits(2);return f.format(BigDecimal.valueOf(cents,2));}

    String day(String iso){try{return new java.text.SimpleDateFormat("d MMM yyyy",Locale.getDefault()).format(ReportData.day(iso).getTime());}catch(Exception bad){return iso;}}

    /** One compact row per schedule (076): payee and amount, then date, repeat and account; the whole row opens it. */
    void rows(String query){body.removeAllViews();try{int count=0;for(final ScheduledStore.Entry e:store.entries()){
        String label=e.date+" · "+frequencyName(e.frequency)+"\n"+e.accountName+" · "+(e.payeeName==null?"":e.payeeName)+"\n"+BigDecimal.valueOf(e.amount,2).toPlainString()+" · "+(e.memo==null?"":e.memo);
        if(!SearchText.matches(label,query))continue;count++;
        LinearLayout row=new LinearLayout(this);row.setOrientation(1);row.setPadding(0,px(10),0,px(10));row.setMinimumHeight(px(56));
        row.setContentDescription("schedule-row:"+e.guid);
        row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(e.complex)details(e);else edit(e,false);}});
        android.util.TypedValue ripple=new android.util.TypedValue();
        if(android.os.Build.VERSION.SDK_INT>=23&&getTheme().resolveAttribute(android.R.attr.selectableItemBackground,ripple,true))row.setForeground(getDrawable(ripple.resourceId));
        LinearLayout top=new LinearLayout(this);top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title=new TextView(this);title.setText(e.transfer&&e.targetAccountName!=null?tr("Transfer → ","Przelew → ")+e.targetAccountName:e.payeeName==null||e.payeeName.isEmpty()?tr("No payee","Bez płatnika"):e.payeeName);title.setTextSize(16);title.setTextColor(TEXT);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView amount=new TextView(this);amount.setText((e.amount>0?"+":"")+money(e.amount));amount.setTextSize(16);amount.setTextColor(e.amount<0?OUT:IN);amount.setPadding(px(8),0,0,0);
        top.addView(amount);row.addView(top);
        String second=day(e.date)+"  ·  "+frequencyName(e.frequency)+"  ·  "+e.accountName;
        // A simple transfer names its target in the title; only splits and linked transfers need this line.
        if(e.complex||(e.transfer&&e.targetAccountName==null))second+="\n"+structure(e)+(e.complex?tr(" · read-only"," · tylko odczyt"):"");
        if(e.memo!=null&&!e.memo.isEmpty())second+="\n"+e.memo;
        TextView meta=new TextView(this);meta.setText(second);meta.setTextSize(13);meta.setTextColor(MUTED);meta.setPadding(0,px(2),0,0);row.addView(meta);
        body.addView(row,new LinearLayout.LayoutParams(-1,-2));
        View divider=new View(this);divider.setBackgroundColor(LINE);body.addView(divider,new LinearLayout.LayoutParams(-1,Math.max(1,px(1)/2)));
    }if(count==0)note(body,tr("No schedules match.","Brak pasujących zleceń."));}catch(Exception e){problem(e);}}
    String structure(ScheduledStore.Entry e){
        String text=e.targetAccountName==null?"":tr("Transfer to ","Przelew na ")+e.targetAccountName;
        if(e.splitParts>0)text+=(text.isEmpty()?"":"\n")+tr("Category split: ","Podział kategorii: ")+e.splitParts+tr(" parts"," części");
        return text.isEmpty()?tr("Linked transfer","Powiązany przelew"):text;
    }
    void details(ScheduledStore.Entry e){new AlertDialog.Builder(this).setTitle(tr("Schedule details","Szczegóły zlecenia"))
        .setMessage(e.date+" · "+frequencyName(e.frequency)+"\n"+e.accountName+"\n"+BigDecimal.valueOf(e.amount,2).toPlainString()+"\n\n"+structure(e)+"\n\n"+tr("Editing this structure is not supported yet. Its linked entries must be changed together. Edit it in the desktop app for now.","Edycja tej struktury nie jest jeszcze obsługiwana. Powiązane wpisy muszą zmieniać się razem. Na razie edytuj ją w aplikacji komputerowej."))
        .setPositiveButton(tr("Close","Zamknij"),null).show();}
    final class Pick {long id;String name;Button button;}
    Pick choice(LinearLayout l,String label,String desc,final List<String[]> choices,long selected){
        final Pick p=new Pick();for(String[] c:choices)if(Long.parseLong(c[0])==selected){p.id=selected;p.name=c[1];}
        if(p.name==null && !choices.isEmpty()){p.id=Long.parseLong(choices.get(0)[0]);p.name=choices.get(0)[1];}
        note(l,label);p.button=button(l,(p.name==null?tr("Choose…","Wybierz…"):p.name)+"  ▾",desc,new View.OnClickListener(){public void onClick(View v){
            LinearLayout box=new LinearLayout(ScheduledActivity.this);box.setOrientation(1);final EditText filter=new EditText(ScheduledActivity.this);filter.setSingleLine();box.addView(filter);final ListView list=new ListView(ScheduledActivity.this);box.addView(list);
            final List<String[]> shown=new ArrayList<String[]>();final ArrayAdapter<String> adapter=new ArrayAdapter<String>(ScheduledActivity.this,android.R.layout.simple_list_item_1);list.setAdapter(adapter);
            final Runnable refresh=new Runnable(){public void run(){shown.clear();adapter.clear();for(String[] c:choices)if(SearchText.matches(c[1],filter.getText().toString())){shown.add(c);adapter.add(c[1]);}}};refresh.run();
            filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){refresh.run();}public void afterTextChanged(Editable e){}});
            final AlertDialog dialog=new AlertDialog.Builder(ScheduledActivity.this).setTitle(label).setView(box).setNegativeButton(tr("Cancel","Anuluj"),null).create();list.setOnItemClickListener(new AdapterView.OnItemClickListener(){public void onItemClick(AdapterView<?> a,View v,int at,long id){p.id=Long.parseLong(shown.get(at)[0]);p.name=shown.get(at)[1];p.button.setText(p.name+"  ▾");dialog.dismiss();}});dialog.show();
        }});return p;
    }
    List<String[]> options(String sql){List<String[]> out=new ArrayList<String[]>();try(Cursor c=store.db.rawQuery(sql,null)){while(c.moveToNext())out.add(new String[]{c.getString(0),c.getString(1)});}return out;}
    void edit(final ScheduledStore.Entry old,final boolean newTransfer){
        editing=true;setTitle(newTransfer?tr("New scheduled transfer","Nowy zaplanowany przelew"):old==null?tr("New schedule","Nowe zlecenie"):tr("Edit schedule","Edytuj zlecenie"));
        LinearLayout l=screen();final ScheduledStore.Entry e=old==null?new ScheduledStore.Entry():old;
        if(newTransfer)e.transfer=true;
        if(old==null){Calendar c=Calendar.getInstance();c.add(Calendar.DAY_OF_MONTH,1);e.date=ReportData.iso(c);e.frequency="Monthly";}
        note(l,tr("Next date","Następna data"));LinearLayout dateRow=new LinearLayout(this);l.addView(dateRow);
        final EditText date=new EditText(this);date.setSingleLine(true);date.setText(e.date);date.setHint("YYYY-MM-DD");date.setContentDescription("schedule-date");dateRow.addView(date,new LinearLayout.LayoutParams(0,-2,1));
        button(dateRow,tr("Calendar","Kalendarz"),"schedule-calendar",new View.OnClickListener(){public void onClick(View v){Calendar c;try{c=ReportData.day(date.getText().toString());}catch(Exception invalid){c=Calendar.getInstance();}new DatePickerDialog(ScheduledActivity.this,new DatePickerDialog.OnDateSetListener(){public void onDateSet(DatePicker picker,int year,int month,int day){date.setText(String.format(Locale.ROOT,"%04d-%02d-%02d",year,month+1,day));}},c.get(Calendar.YEAR),c.get(Calendar.MONTH),c.get(Calendar.DAY_OF_MONTH)).show();}});
        note(l,tr("Repeat","Częstotliwość"));final Spinner frequency=new Spinner(this);frequency.setContentDescription("schedule-frequency");String[] frequencyLabels=new String[ScheduledStore.FREQUENCIES.length];for(int i=0;i<frequencyLabels.length;i++)frequencyLabels[i]=frequencyName(ScheduledStore.FREQUENCIES[i]);frequency.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,frequencyLabels));frequency.setSelection(Math.max(0,Arrays.asList(ScheduledStore.FREQUENCIES).indexOf(e.frequency)));l.addView(frequency);
        final Pick account;
        final Pick payee;
        final Pick category;
        final Pick target;
        final String fixedStructure;
        if(newTransfer){
            // New transfer: source on budget; category only for an off-budget target (checked on save).
            fixedStructure=null;
            payee=null;
            account=choice(l,tr("From account (on budget)","Z konta (budżetowego)"),"schedule-account",options("SELECT _id,name FROM accountTable WHERE deleted=0 AND onBudget=1 ORDER BY hidden,sequence"),e.account);
            target=choice(l,tr("To account","Na konto"),"schedule-target",options("SELECT _id,name FROM accountTable WHERE deleted=0 ORDER BY onBudget DESC,hidden,sequence"),e.target);
            List<String[]> categories=new ArrayList<String[]>();
            categories.add(new String[]{"0",tr("No category (target on budget)","Bez kategorii (cel w budżecie)")});
            categories.addAll(options("SELECT c._id,COALESCE(m.name||': ','')||c.name FROM categoryTable c LEFT JOIN masterCategoryTable m ON m._id=c.masterCategoryId WHERE c.deleted=0 AND (m.deleted=0 OR m._id IS NULL) AND c.guid!='Category/__Split__' ORDER BY m.sequence,c.sequence"));
            category=choice(l,tr("Category (only for an off-budget target)","Kategoria (tylko dla celu poza budżetem)"),"schedule-category",categories,0);
        } else if(e.transfer){
            target=null;
            // Scheduled transfer: only date, frequency, amount and memo are editable here.
            account=null;
            payee=null;
            category=null;
            fixedStructure=e.accountName+" → "+e.targetAccountName;
            note(l,tr("Transfer (account and target stay as they are)","Przelew (konto i cel pozostają bez zmian)"));
            TextView fixed=new TextView(this);
            fixed.setText(fixedStructure);
            fixed.setTextSize(16);
            fixed.setContentDescription("schedule-transfer-fixed");
            l.addView(fixed);
        } else {
            fixedStructure=null;
            target=null;
            account=choice(l,tr("Account","Konto"),"schedule-account",options("SELECT _id,name FROM accountTable WHERE deleted=0 ORDER BY hidden,sequence"),e.account);
            payee=choice(l,tr("Payee","Płatnik"),"schedule-payee",options("SELECT _id,name FROM payeeTable WHERE deleted=0 AND name NOT LIKE 'Transfer : %' ORDER BY name COLLATE NOCASE"),e.payee);
            category=choice(l,tr("Category","Kategoria"),"schedule-category",options("SELECT c._id,COALESCE(m.name||': ','')||c.name FROM categoryTable c LEFT JOIN masterCategoryTable m ON m._id=c.masterCategoryId WHERE c.deleted=0 AND (m.deleted=0 OR m._id IS NULL) AND c.guid!='Category/__Split__' ORDER BY m.sequence,c.sequence"),e.category);
        }
        final EditText amount=field(l,tr("Amount (negative = outflow)","Kwota (ujemna = wydatek)"),BigDecimal.valueOf(e.amount,2).toPlainString(),"schedule-amount");amount.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
        final EditText memo=field(l,tr("Memo","Opis"),e.memo==null?"":e.memo,"schedule-memo");
        button(l,tr("Review and save","Sprawdź i zapisz"),"schedule-save",new View.OnClickListener(){public void onClick(View v){try{
            e.date=date.getText().toString().trim();e.frequency=ScheduledStore.FREQUENCIES[frequency.getSelectedItemPosition()];e.memo=memo.getText().toString();e.amount=new BigDecimal(amount.getText().toString().trim().replace(',','.')).movePointRight(2).longValueExact();
            String structure;
            if(newTransfer){
                e.account=account.id;
                e.target=target.id;
                e.category=category.id;
                structure=account.name+" → "+target.name+"\n"+category.name;
            } else if(e.transfer){
                structure=fixedStructure;
            } else {
                e.account=account.id;
                e.payee=payee.id;
                e.category=category.id;
                structure=account.name+" · "+payee.name+"\n"+category.name;
            }
            new AlertDialog.Builder(ScheduledActivity.this).setTitle(tr("Save definition?","Zapisać definicję?"))
                .setMessage(e.date+" · "+frequencyName(e.frequency)+"\n"+structure+"\n"+BigDecimal.valueOf(e.amount,2).toPlainString()+"\n"+e.memo)
                .setNegativeButton(tr("Cancel","Anuluj"),null).setPositiveButton(tr("Save","Zapisz"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){try{store.save(e,false);list();}catch(Exception error){problem(error);}}}).show();
        }catch(Exception error){problem(error);}}});
        if(old!=null)button(l,tr("Remove schedule…","Usuń zlecenie…"),"schedule-remove",new View.OnClickListener(){public void onClick(View v){new AlertDialog.Builder(ScheduledActivity.this).setTitle(tr("Remove this definition?","Usunąć tę definicję?"))
            .setMessage(tr("Existing posted transactions stay in the budget.","Już zaksięgowane transakcje pozostaną w budżecie."))
            .setNegativeButton(tr("Cancel","Anuluj"),null).setPositiveButton(tr("Remove","Usuń"),new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){try{store.save(e,true);list();}catch(Exception error){problem(error);}}}).show();}});
        button(l,tr("Cancel","Anuluj"),"schedule-cancel",new View.OnClickListener(){public void onClick(View v){list();}});l.setFocusableInTouchMode(true);l.requestFocus();
    }
}

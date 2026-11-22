package org.classicbudget.patch;
import android.app.Activity;
import android.view.*;
import android.widget.*;

/** Optional layout only. Keeps the original form, model, amount keypad and save handlers. */
public final class CompactEntry {
    static int id(Activity a,String name){return a.getResources().getIdentifier(name,"id",a.getPackageName());}
    static void fitKeys(View view,float density){
        if(view instanceof TableRow){
            ViewGroup.LayoutParams params=view.getLayoutParams();
            if(params instanceof TableLayout.LayoutParams){TableLayout.LayoutParams row=(TableLayout.LayoutParams)params;
                if(row.height!=0||row.weight!=1){row.height=0;row.weight=1;view.setLayoutParams(row);}
            }
        }
        if(view instanceof TextView){TextView key=(TextView)view;key.setTextSize(30);key.setPadding(key.getPaddingLeft(),0,key.getPaddingRight(),0);key.setIncludeFontPadding(false);}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)fitKeys(group.getChildAt(i),density);}
    }
    public static void install(final Activity a){
        if(!a.getClass().getName().equals("com.youneedabudget.ynab.app.edit.TxnEntryActivity"))return;
        if(!"compact".equals(a.getSharedPreferences(Display.PREFS,0).getString("entry_layout","original")))return;
        final View decor=a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener(){
            public void onGlobalLayout(){
                View amount=a.findViewById(id(a,"amount")),pad=a.findViewById(id(a,"container_keypad"));
                if(!(amount instanceof TextView))return;
                float density=a.getResources().getDisplayMetrics().density;
                View parent=a.findViewById(id(a,"parent_container"));
                // Tiny windows retain native scrolling; no control is shrunk below its normal touch target.
                if(parent==null||parent.getHeight()/density<540)return;
                ViewGroup.LayoutParams lp=amount.getLayoutParams();int height=(int)(96*density);
                if(lp.height!=height){lp.height=height;amount.setLayoutParams(lp);((TextView)amount).setTextSize(42);}
                if(pad!=null&&pad.getParent() instanceof View){
                    ViewGroup.LayoutParams keypad=pad.getLayoutParams();int keypadHeight=(int)(240*density);
                    if(keypad.height!=keypadHeight){keypad.height=keypadHeight;pad.setLayoutParams(keypad);}
                    // Tall-screen resources give the fragment's root 320dp independently of its container.
                    // Fit that root too, otherwise the last row remains outside the 240dp panel.
                    if(pad instanceof ViewGroup){ViewGroup container=(ViewGroup)pad;for(int i=0;i<container.getChildCount();i++){
                        View child=container.getChildAt(i);ViewGroup.LayoutParams cp=child.getLayoutParams();
                        if(cp.height!=ViewGroup.LayoutParams.MATCH_PARENT){cp.height=ViewGroup.LayoutParams.MATCH_PARENT;child.setLayoutParams(cp);}
                    }}
                    fitKeys(pad,density);
                    View panel=(View)pad.getParent();ViewGroup.LayoutParams pp=panel.getLayoutParams();
                    if(pp instanceof FrameLayout.LayoutParams){FrameLayout.LayoutParams frame=(FrameLayout.LayoutParams)pp;
                        int h=(int)(304*density);if(frame.height!=h||frame.gravity!=Gravity.BOTTOM){frame.height=h;frame.gravity=Gravity.BOTTOM;panel.setLayoutParams(frame);}
                    }
                }
            }
        });
    }
}

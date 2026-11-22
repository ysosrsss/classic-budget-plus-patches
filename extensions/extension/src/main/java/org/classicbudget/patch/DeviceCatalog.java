package org.classicbudget.patch;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;
import java.util.Locale;
import org.json.JSONObject;

/** Local cache of metadata already read by the original sync engine. No network calls. */
public final class DeviceCatalog {
    static Object field(Object object,String name)throws Exception {Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    public static void remember(File dataFolder,Object originalDevice) {
        try {
            Context c=com.youneedabudget.ynab.core.app.d.i();if(c==null)return;
            Object dto=field(originalDevice,"d");String id=String.valueOf(field(dto,"shortDeviceId"));
            DeviceLetters.seen(dataFolder,id); // §158: every registration in the folder, before any filter
            if(!id.matches("[A-Z]{1,3}") || dataFolder.getParentFile()==null)return;
            JSONObject item=new JSONObject();
            for(String name:new String[]{"deviceType","friendlyName","YNABVersion","deviceGUID"}) {
                Object value=field(dto,name);String text=value==null?"":value.toString();
                item.put(name,text.substring(0,Math.min(240,text.length())));
            }
            c.getSharedPreferences("cb-device-catalog",0).edit().putString(dataFolder.getParent()+"|"+id,item.toString()).apply();
        }catch(Exception ignored){ } // Metadata labels must never interrupt the original sync.
    }
    static JSONObject get(Context c,String budget,String id) {
        try{return new JSONObject(c.getSharedPreferences("cb-device-catalog",0).getString(budget+"|"+id,"{}"));}
        catch(Exception ignored){return new JSONObject();}
    }
    static String kind(String type) {
        String t=type==null?"":type.toLowerCase(Locale.ROOT);
        if(t.contains("android"))return "android";
        if(t.contains("iphone")||t.contains("ipad")||t.contains("ios"))return "ios";
        if(t.contains("desktop")||t.contains("windows")||t.contains("mac")||t.contains("linux"))return "desktop";
        return "unknown";
    }
    static String label(String kind) {
        if("android".equals(kind))return PlusLang.tr("Android phones and tablets","Telefony i tablety Android");
        if("ios".equals(kind))return "iPhone / iPad";
        if("desktop".equals(kind))return PlusLang.tr("Computers (YNAB Desktop)","Komputery (YNAB Desktop)");
        return PlusLang.tr("Type not yet known","Typ jeszcze nierozpoznany");
    }
}

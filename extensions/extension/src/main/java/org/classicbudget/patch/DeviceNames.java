package org.classicbudget.patch;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.json.JSONObject;

/** Local labels shared by report and suggestion settings, preserving legacy report labels. */
public final class DeviceNames {
    public static Map<String,String> all(Context context){
        SharedPreferences p=context.getSharedPreferences("cb-plus-reports",0);Map<String,String> result=new TreeMap<String,String>();
        for(String part:p.getString("device_names","").split(",")){String[] pair=part.split("=",2);if(pair.length==2&&pair[0].trim().matches("[A-Z]{1,3}"))result.put(pair[0].trim(),pair[1].trim());}
        try{JSONObject json=new JSONObject(p.getString("device_labels_json","{}"));Iterator<String> it=json.keys();while(it.hasNext()){String key=it.next();if(key.matches("[A-Z]{1,3}")){String name=json.optString(key,"");if(name.isEmpty())result.remove(key);else result.put(key,name);}}}catch(Exception ignored){}
        return result;
    }
    public static void set(Context context,String device,String name){
        if(!device.matches("[A-Z]{1,3}"))return;
        Map<String,String> labels=all(context);labels.put(device,name.trim());JSONObject json=new JSONObject(labels);
        context.getSharedPreferences("cb-plus-reports",0).edit().putString("device_labels_json",json.toString()).apply();
    }
}

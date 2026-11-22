package org.classicbudget.patch;

import android.content.*;
import java.io.*;
import org.json.*;
import com.youneedabudget.ynab.core.cloud.c;
import com.youneedabudget.ynab.core.cloud.z;

public final class DavProvider implements c {
    private z accessor;
    public static Context context() { return com.youneedabudget.ynab.core.app.d.i(); }
    public static File configFile() { return new File(context().getFilesDir(),"webdav-test.json"); }
    public static boolean authenticationBlocked() {
        Context ctx=context();return ctx!=null && ctx.getSharedPreferences("dav-auth-stop",0).getBoolean("blocked",false);
    }
    static int authenticationStatus() {
        Context ctx = context();
        return ctx == null ? 0 : ctx.getSharedPreferences("dav-auth-stop", 0).getInt("status", 0);
    }
    public static void clearAuthenticationBlock() {
        context().getSharedPreferences("dav-auth-stop",0).edit().clear().commit();
    }
    public static void blockAuthentication(final int status) {
        final Context ctx=context();if(ctx==null)return;
        ctx.getSharedPreferences("dav-auth-stop",0).edit().putBoolean("blocked",true).putInt("status",status).commit();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable(){public void run(){
            android.widget.Toast.makeText(ctx,status==401?"Koofr TEST: sprawdź login i hasło aplikacji. Próby zatrzymane.":"Koofr TEST: ograniczenie logowania. Próby zatrzymane.",android.widget.Toast.LENGTH_LONG).show();
        }});
    }
    public static void diagnostic(Throwable error) {
        try {
            JSONObject result=new JSONObject();JSONArray causes=new JSONArray();
            for(Throwable e=error;e!=null;e=e.getCause()) {
                JSONObject cause=new JSONObject();cause.put("type",e.getClass().getName());
                if(e instanceof LinkageError)cause.put("linkage",e.getMessage());
                JSONArray frames=new JSONArray();for(StackTraceElement f:e.getStackTrace())frames.put(f.getClassName()+"."+f.getMethodName()+":"+f.getLineNumber());
                cause.put("frames",frames);causes.put(cause);
            }
            result.put("causes",causes);FileOutputStream out=context().openFileOutput("dav-diagnostic.json",0);
            try{out.write(result.toString().getBytes("UTF-8"));}finally{out.close();}
        }catch(Throwable ignored){}
    }
    public static JSONObject config() throws Exception {
        FileInputStream in=new FileInputStream(configFile()); ByteArrayOutputStream out=new ByteArrayOutputStream();
        try { byte[] b=new byte[4096]; int n; while((n=in.read(b))!=-1) { if(out.size()+n>16384)throw new IOException("Configuration too large");out.write(b,0,n); } }
        finally { in.close(); }
        JSONObject config=new JSONObject(new String(out.toByteArray(),"UTF-8"));
        if(config.has("server")) {
            // Any https WebDAV server; TEST builds keep the folder inside YNAB4_PLUS_TEST.
            DavClient.configure(DavClient.join(config.getString("server"),config.getString("folder")));
        } else if(!"https://app.koofr.net/dav/Koofr/".equals(config.getString("base_url")) || !"YNAB4_PLUS_TEST/__write_budget_lab__".equals(config.getString("test_root")))
            throw new IOException("Only TEST configuration is permitted");
        if(config.getString("username").length()==0 || config.getString("app_password").length()==0)throw new IOException("Missing configuration");
        return config;
    }
    public void a() { accessor=null; }
    public void a(Context context) { context.startActivity(new Intent(context,DavSetupActivity.class)); }
    public void b(Context context) {
        configFile().delete(); accessor=null;
        android.support.v4.b.f.a(context).a(new Intent("com.youneedabudget.ynab.intent.action.DROPBOX_UNLINKED"));
    }
    public boolean b() { try { config();return true; } catch(Exception e) { return false; } }
    public synchronized z c() {
        if(accessor==null) try { accessor=new WriteAccessor(context(),config()); }
        catch(Throwable e) { diagnostic(e);throw new IllegalStateException("WebDAV TEST is not configured"); }
        return accessor;
    }
}

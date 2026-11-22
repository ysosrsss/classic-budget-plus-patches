package org.classicbudget.patch;

import android.content.*;
import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;
import com.youneedabudget.ynab.core.backend.i;
import com.youneedabudget.ynab.core.cloud.z;

/** Only new, private .ydevice registrations may be written, never to WebDAV. */
public final class DavAccessor implements z {
    private final DavClient client;
    private final File local;
    private final JSONObject overlay;
    private static final Charset UTF8=Charset.forName("UTF-8");
    public DavAccessor(final Context context,JSONObject config) throws Exception {
        String username=config.getString("username");
        SharedPreferences profile=context.getSharedPreferences("dav-test-profile",0);
        String anchor=profile.getString("username",username);
        if(!anchor.equals(username))throw new IOException("Changing TEST account requires fresh TEST app data");
        profile.edit().putString("username",username).commit();
        byte[] hash=MessageDigest.getInstance("SHA-256").digest((username+DavClient.ROOT).getBytes(UTF8));
        StringBuilder key=new StringBuilder();for(byte b:hash)key.append(String.format(java.util.Locale.US,"%02x",b&255));
        local=new File(context.getFilesDir(),"readonly-device-"+key+".json");
        overlay=local.exists()?new JSONObject(readLocal(local)):new JSONObject();
        client=new DavClient(username,config.getString("app_password"),new DavClient.Audit(){
            public void record(String method,String path,int status){
                try { FileOutputStream out=context.openFileOutput("dav-audit.jsonl",Context.MODE_APPEND);
                    try { JSONObject event=new JSONObject();event.put("method",method);event.put("path",path);event.put("status",status);
                        out.write((event.toString()+"\n").getBytes(UTF8)); } finally { out.close(); }
                } catch(Exception ignored) { }
            }
        });
    }
    private static String readLocal(File file) throws IOException {
        FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream();
        try { byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n); } finally {in.close();}
        return new String(out.toByteArray(),UTF8);
    }
    private String path(File file)throws IOException{return DavClient.logical(file.getPath());}
    private void persist()throws IOException {
        File temp=new File(local.getPath()+".tmp");FileOutputStream out=new FileOutputStream(temp);
        try {out.write(overlay.toString().getBytes(UTF8));out.getFD().sync();}finally{out.close();}
        if(!temp.renameTo(local))throw new IOException("Unable to save private device registration");
    }
    private static i budget(String path){return new i(new File(path),i.a.a,0L);}
    public synchronized File a(File file,String text)throws IOException {
        String path=path(file);
        if(!path.endsWith(".ydevice") || !new File(path).getParentFile().getName().equalsIgnoreCase("devices"))throw new IOException("Read-only TEST registration only");
        boolean collision=overlay.has(path);
        for(DavClient.Entry e:client.list(new File(path).getParent()))if(e.path.equals(path))collision=true;
        String chosen=collision?path+"-"+UUID.randomUUID()+".ydevice":path;
        try {overlay.put(chosen,text);}catch(JSONException e){throw new IOException(e);}
        persist();return new File(chosen);
    }
    public synchronized String a(File file)throws IOException {
        String path=path(file);if(overlay.has(path))return overlay.optString(path);
        return new String(client.read(path),UTF8);
    }
    public InputStream b(File file)throws IOException{return new ByteArrayInputStream(aBytes(file));}
    private synchronized byte[] aBytes(File file)throws IOException {
        String path=path(file);return overlay.has(path)?overlay.optString(path).getBytes(UTF8):client.read(path);
    }
    public synchronized void b(File file,String text)throws IOException {
        String path=path(file);if(!path.endsWith(".ydevice") || !overlay.has(path))throw new IOException("Remote writes disabled in TEST");
        try {overlay.put(path,text);}catch(JSONException e){throw new IOException(e);}persist();
    }
    public i c(File file)throws IOException {
        try {client.list(path(file));return budget(path(file));}catch(FileNotFoundException missing){return null;}
    }
    public synchronized List<File> c(File directory,String suffix)throws IOException {
        String parent=path(directory);List<File> out=new ArrayList<File>();Set<String> remote=new HashSet<String>();
        for(DavClient.Entry e:client.list(parent))if(!e.directory&&!e.path.equals(parent)){
            remote.add(e.path);if(suffix==null||e.path.endsWith(suffix))out.add(new File(e.path));
        }
        for(Iterator<String> keys=overlay.keys();keys.hasNext();){String path=keys.next();
            if(new File(path).getParent().equals(parent)){
                if(remote.contains(path))throw new IOException("TEST device registration changed remotely; reimport required");
                if(suffix==null||path.endsWith(suffix))out.add(new File(path));
            }
        }return out;
    }
    public List<i> d(File directory,String suffix)throws IOException {
        String parent=path(directory);List<i> out=new ArrayList<i>();
        for(DavClient.Entry e:client.list(parent))if(e.directory&&!e.path.equals(parent)&&(suffix==null||e.path.endsWith(suffix)))out.add(budget(e.path));
        return out;
    }
    public synchronized void d(File file)throws IOException {
        String path=path(file);if(!overlay.has(path))throw new IOException("Remote deletes disabled in TEST");
        overlay.remove(path);persist();
    }
}

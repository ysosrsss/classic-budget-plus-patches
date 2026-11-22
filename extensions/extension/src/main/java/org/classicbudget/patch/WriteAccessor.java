package org.classicbudget.patch;

import android.content.*;
import java.io.*;
import java.util.*;
import org.json.*;
import com.youneedabudget.ynab.core.backend.i;
import com.youneedabudget.ynab.core.cloud.z;

/** Experimental adapter: only one synthetic budget, own device and own diffs. */
public final class WriteAccessor implements z, TakeoverStorage {
    final Context context;final DavClient client;final PublishTransport writer;final WriteJournal journal;
    /** Only for a configured server/folder ("server" in config); the lab default keeps its fixed scope. */
    BudgetScope scope;
    public WriteAccessor(final Context context,JSONObject config)throws Exception {
        this.context=context;
        DavClient.Audit audit=new DavClient.Audit(){public void record(String method,String path,int status){
            try{JSONObject event=new JSONObject();event.put("method",method);event.put("path",path);event.put("status",status);
                try(FileOutputStream out=context.openFileOutput("dav-audit.jsonl",Context.MODE_APPEND)){out.write((event.toString()+"\n").getBytes(WritePolicy.UTF8));}
            }catch(Exception ignored){}
        }};
        // Configured server/folder: the root DavProvider validated becomes the write root (journal profile, transport check).
        if(config.has("server"))WritePolicy.ROOT=DavClient.ROOT;
        String username=config.getString("username"),password=config.getString("app_password");
        journal=new WriteJournal(context,username);client=new DavClient(username,password,audit);
        // Koofr server: writes through the Koofr API (server-side conditions), reads stay on WebDAV.
        // config "transport":"webdav" keeps plain WebDAV writes; the lab default (no "server") stays WebDAV.
        boolean koofr=config.has("server")&&KoofrTransport.koofrRoot(DavClient.ROOT)&&!"webdav".equals(config.optString("transport"));
        writer=koofr?new KoofrTransport(username,password,client,audit):new WriteTransport(username,password,client,audit);journal.transport=writer;
        if(config.has("server")){final DavClient c=client;scope=new BudgetScope(new BudgetScope.Source(){public byte[] read(String p)throws IOException{return c.read(p);}});}
    }
    String path(File file)throws IOException {String path=WritePolicy.logical(file);if(scope!=null)return scope.path(path);WritePolicy.readable(path);return path;}
    String guid()throws IOException{return WritePolicy.ownGuid(context);}

    public synchronized String takeoverProfile(File device) throws Exception {
        path(device);
        if (!(writer instanceof KoofrTransport)) throw new IOException("TAKEOVER_BACKEND_UNSUPPORTED");
        return journal.idleProfile();
    }

    public synchronized long registrationModified(File device) throws IOException {
        String target = path(device);
        for (DavClient.Entry entry : client.list(new File(target).getParent())) {
            if (!entry.directory && entry.path.equals(target)) return entry.modified;
        }
        return 0;
    }

    public synchronized byte[] registrationBytes(File device) throws IOException {
        return writer.optional(path(device));
    }

    public synchronized void replaceRegistration(File device, JSONObject transfer) throws Exception {
        journal.idleProfile();
        ((KoofrTransport) writer).transferRegistration(path(device), transfer);
    }
    String shortId()throws IOException {
        String value=context.getSharedPreferences("write-device",0).getString(WritePolicy.shortIdKey(),null);
        if(!WritePolicy.shortId(value)){value=WritePolicy.recoverShortId(writer,guid());if(value==null)throw new IOException("OWN_DEVICE_NOT_REGISTERED");remember(value);}
        return value;
    }
    void remember(String value)throws IOException {if(!context.getSharedPreferences("write-device",0).edit().putString(WritePolicy.shortIdKey(),value).commit())throw new IOException("DEVICE_PREFS_COMMIT_FAILED");}
    boolean publish(JSONObject entry,boolean replace)throws IOException {
        while(true)try{return writer.publish(entry,replace);}
        catch(PublishTransport.StagingMismatch mismatch){journal.rotateStaging(entry,mismatch.observedHash);}
    }
    public synchronized File a(File file,String text)throws IOException {
        journal.healthy();
        String path=path(file),shortId=WritePolicy.device(path,text,guid());
        WriteFault.awaitRegistration(path);
        JSONObject entry=journal.begin(path,text,"device");
        if(publish(entry,false)){journal.published(entry);remember(shortId);journal.forget(path);return new File(path);}
        try{return new File(entry.getString("staging"));}catch(JSONException error){throw new IOException(error);}
    }
    public synchronized void b(File file,String text)throws IOException {
        journal.healthy();
        String path=path(file),mode;
        if(WritePolicy.devicePath(path)) {
            String id=WritePolicy.device(path,text,guid());if(!shortId().equals(id))throw new IOException("FOREIGN_SHORT_DEVICE_ID");
            byte[] old=writer.optional(path);if(old==null)throw new IOException("REGISTERED_DEVICE_DISAPPEARED");
            String oldText=new String(old,WritePolicy.UTF8);WritePolicy.device(path,oldText,guid());
            try{if(!WritePolicy.dominates(WritePolicy.knowledge(new JSONObject(text).getString("knowledge")),WritePolicy.knowledge(new JSONObject(oldText).getString("knowledge"))))throw new IOException("DEVICE_KNOWLEDGE_REGRESSION");}
            catch(JSONException error){throw new IOException(error);}
            JSONObject prior=journal.entry(path);
            if(prior!=null&&prior.optBoolean("published")&&!WritePolicy.equivalent(prior.optString("payload"),text,false))journal.forget(path);
            mode="device";
        }else {WritePolicy.diff(path,text,guid(),shortId());writer.directory(WritePolicy.DATA+"/"+guid());mode="diff";}
        JSONObject entry=journal.begin(path,text,mode);
        if(!publish(entry,mode.equals("device")))throw new IOException("IMMUTABLE_DIFF_COLLISION");
        journal.published(entry);
        if(mode.equals("device")&&journal.singlePendingDiff()==null)journal.forget(path);
    }
    private byte[] read(File file)throws IOException {
        String path=path(file);byte[] data=client.read(path);
        return WriteRecovery.readOwnMetadata(context,journal,writer,path,data);
    }
    public String a(File file)throws IOException{return new String(read(file),WritePolicy.UTF8);}
    public InputStream b(File file)throws IOException{return new ByteArrayInputStream(read(file));}
    private static i budget(String path){return new i(new File(path),i.a.a,0L);}
    public i c(File file)throws IOException{String path=path(file);try{client.list(path);return budget(path);}catch(FileNotFoundException e){return null;}}
    public List<File> c(File file,String suffix)throws IOException {
        String path=path(file);List<File> out=new ArrayList<File>();for(DavClient.Entry e:client.list(path))if(!e.directory&&(suffix==null||e.path.endsWith(suffix)))out.add(new File(e.path));return out;
    }
    public List<i> d(File file,String suffix)throws IOException {
        String path=path(file);List<i> out=new ArrayList<i>();for(DavClient.Entry e:client.list(path))if(e.directory&&!e.path.equals(path)&&(suffix==null||e.path.endsWith(suffix)))out.add(budget(e.path));return out;
    }
    public synchronized void d(File file)throws IOException {
        String path=path(file);WritePolicy.staging(path);JSONObject entry=journal.byStaging(path);
        if(entry==null||entry.optBoolean("published"))throw new IOException("DELETE_NOT_OWN_COLLISION_STAGING");
        writer.removeStaging(entry);journal.forget(entry.optString("path"));
    }
}

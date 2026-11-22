package org.classicbudget.patch;

import android.content.Context;
import java.io.*;
import java.util.*;
import org.json.*;

/** Private, fsynced payloads survive uncertain remote acknowledgements. */
public final class WriteJournal {
    private static WriteJournal active;
    private boolean failed;
    final Context context;final File file;final JSONObject state;
    /** Names staging files; null keeps the original WebDAV naming (also used by fixtures). */
    PublishTransport transport;
    String newStaging(String path){return transport!=null?transport.stagingFor(path):WritePolicy.STAGING+"/"+UUID.randomUUID().toString().replace("-","")+".tmp";}
    public WriteJournal(Context context,String username)throws IOException {
        this.context=context;file=new File(context.getFilesDir(),"write-ledger.json");
        try {
            String profile=WritePolicy.sha((username+WritePolicy.ROOT).getBytes(WritePolicy.UTF8));
            state=file.exists()?new JSONObject(read(file)):new JSONObject();
            if(state.has("profile")&&!profile.equals(state.getString("profile")))throw new IOException("OUTBOX_PROFILE_CHANGED");
            state.put("profile",profile);if(!state.has("entries"))state.put("entries",new JSONObject());if(!state.has("quarantined"))state.put("quarantined",new JSONArray());persist();active=this;
        }catch(JSONException error){throw new IOException("OUTBOX_CORRUPT",error);}
    }
    static String read(File file)throws IOException {
        try(FileInputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("OUTBOX_SIZE_LIMIT");out.write(b,0,n);}return new String(out.toByteArray(),WritePolicy.UTF8);
        }
    }
    static void atomic(File file,String text)throws IOException {
        File temporary=new File(file.getPath()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(text.getBytes(WritePolicy.UTF8));out.getFD().sync();}
        if(!temporary.renameTo(file))throw new IOException("PRIVATE_STATE_RENAME_FAILED");
    }
    synchronized void healthy()throws IOException {if(failed)throw new IOException("OUTBOX_PERSISTENCE_FAILED_RESTART_REQUIRED");}
    synchronized String idleProfile() throws IOException, JSONException {
        healthy();
        if (state.getJSONObject("entries").length() != 0) throw new IOException("TAKEOVER_PENDING_EXPORT");
        return state.getString("profile");
    }
    synchronized void persist()throws IOException {
        healthy();
        try{String data=state.toString();if(data.getBytes(WritePolicy.UTF8).length>8*1024*1024)throw new IOException("OUTBOX_SIZE_LIMIT");atomic(file,data);}
        catch(IOException error){failed=true;throw error;}
    }
    synchronized JSONObject entry(String path)throws IOException {healthy();try{return state.getJSONObject("entries").optJSONObject(path);}catch(JSONException e){throw new IOException(e);}}
    synchronized JSONObject begin(String path,String text,String mode)throws IOException {
        healthy();
        if(text.getBytes(WritePolicy.UTF8).length>WritePolicy.MAX_BYTES)throw new IOException("WRITE_PAYLOAD_SIZE_LIMIT");
        try {
            JSONObject entries=state.getJSONObject("entries"),old=entries.optJSONObject(path);
            if(old!=null){if(!mode.equals(old.getString("mode"))||!WritePolicy.equivalent(old.getString("payload"),text,mode.equals("diff")))throw new IOException("PENDING_EXPORT_CHANGED");return old;}
            if(mode.equals("diff"))supersede(entries,text);
            if(entries.length()>=32)throw new IOException("OUTBOX_ENTRY_LIMIT");
            JSONObject entry=new JSONObject();entry.put("path",path);entry.put("mode",mode);entry.put("payload",text);
            entry.put("sha256",WritePolicy.sha(text.getBytes(WritePolicy.UTF8)));entry.put("staging",newStaging(path));entry.put("published",false);
            entries.put(path,entry);persist();return entry;
        }catch(JSONException error){throw new IOException("OUTBOX_FAILURE",error);}
    }
    /**
     * A failed export leaves its diff pinned unpublished. When the user keeps editing, the
     * engine's next export is a new diff from the same start knowledge that contains every
     * change of the old one. If the old diff provably never reached storage (its final path
     * is absent), the new diff replaces it: own staging removed, entry recorded as superseded.
     * Otherwise (target present, or no transport to check) the old stop rule stays.
     */
    private void supersede(JSONObject entries,String text)throws IOException,JSONException {
        if(transport==null)return;
        JSONObject fresh=new JSONObject(text);
        String start=fresh.getString("startVersion");Map<String,Integer> end=WritePolicy.knowledge(fresh.getString("endVersion"));
        List<String> replaced=new ArrayList<String>();
        for(Iterator<String> i=entries.keys();i.hasNext();){
            String key=i.next();JSONObject e=entries.getJSONObject(key);
            if(!"diff".equals(e.optString("mode"))||e.optBoolean("published"))continue;
            JSONObject payload=new JSONObject(e.getString("payload"));
            if(!start.equals(payload.getString("startVersion"))||!WritePolicy.dominates(end,WritePolicy.knowledge(payload.getString("endVersion"))))continue;
            if(transport.optional(e.getString("path"))!=null)continue;
            replaced.add(key);
        }
        if(replaced.isEmpty())return;
        if(!state.has("superseded"))state.put("superseded",new JSONArray());
        JSONArray log=state.getJSONArray("superseded");
        for(String key:replaced){
            JSONObject e=entries.getJSONObject(key);
            try{transport.removeStaging(e);}catch(IOException keep){/* changed staging stays as evidence */}
            if(log.length()<96)log.put(new JSONObject().put("path",key).put("sha256",e.getString("sha256")));
            entries.remove(key);
        }
        persist();
    }
    synchronized void published(JSONObject entry)throws IOException {try{entry.put("published",true);persist();}catch(JSONException error){throw new IOException(error);}}
    synchronized void forget(String path)throws IOException {try{state.getJSONObject("entries").remove(path);persist();}catch(JSONException error){throw new IOException(error);}}
    synchronized JSONObject byStaging(String path)throws IOException {
        healthy();
        try{JSONObject entries=state.getJSONObject("entries");for(Iterator<String> i=entries.keys();i.hasNext();){JSONObject e=entries.getJSONObject(i.next());if(path.equals(e.getString("staging")))return e;}return null;}
        catch(JSONException error){throw new IOException(error);}
    }
    synchronized JSONObject singlePendingDiff()throws IOException {
        healthy();
        try{JSONObject entries=state.getJSONObject("entries"),found=null;
            for(Iterator<String> i=entries.keys();i.hasNext();){JSONObject entry=entries.getJSONObject(i.next());if("diff".equals(entry.optString("mode"))){if(found!=null)throw new IOException("MULTIPLE_PENDING_DIFFS_REQUIRE_REVIEW");found=entry;}}
            return found;
        }catch(JSONException error){throw new IOException(error);}
    }
    synchronized void rotateStaging(JSONObject entry,String observedHash)throws IOException {
        healthy();
        try {
            int count=entry.optInt("stagingRotations",0);if(count>=3)throw new IOException("STAGING_RECOVERY_LIMIT");
            JSONArray quarantined=state.getJSONArray("quarantined");if(quarantined.length()>=96)throw new IOException("STAGING_QUARANTINE_LIMIT");
            JSONObject record=new JSONObject();record.put("path",entry.getString("staging"));record.put("expectedSha256",entry.getString("sha256"));record.put("observedSha256",observedHash);quarantined.put(record);
            entry.put("stagingRotations",count+1);entry.put("staging",newStaging(entry.getString("path")));persist();
        }catch(JSONException error){throw new IOException("STAGING_ROTATION_STATE_INVALID",error);}
    }
    synchronized void confirmedCommitted(String diffPath,String devicePath)throws IOException {
        try{JSONObject entries=state.getJSONObject("entries");entries.remove(diffPath);entries.remove(devicePath);persist();}
        catch(JSONException error){throw new IOException(error);}
    }
    public static void onExportCommitted(Context context) {
        WriteJournal current=active;
        if(current!=null){try{current.removePublished();}catch(Exception error){DavProvider.diagnostic(error);}return;}
        try {
            File file=new File(context.getFilesDir(),"write-ledger.json");if(!file.exists())return;
            JSONObject state=new JSONObject(read(file)),entries=state.getJSONObject("entries");List<String> done=new ArrayList<String>();
            for(Iterator<String> i=entries.keys();i.hasNext();){String key=i.next();if(entries.getJSONObject(key).optBoolean("published"))done.add(key);}
            for(String key:done)entries.remove(key);atomic(file,state.toString());
        }catch(Exception error){DavProvider.diagnostic(error);}
    }
    private synchronized void removePublished()throws IOException {
        try{JSONObject entries=state.getJSONObject("entries");List<String> done=new ArrayList<String>();
            for(Iterator<String> i=entries.keys();i.hasNext();){String key=i.next();if(entries.getJSONObject(key).optBoolean("published"))done.add(key);}
            for(String key:done)entries.remove(key);persist();
        }catch(JSONException error){throw new IOException(error);}
    }
}

package org.classicbudget.patch;

import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import okhttp3.*;
import org.json.*;

/**
 * Writer transport through the Koofr REST API (same app password as WebDAV, HTTP Basic).
 * Reads stay on WebDAV (DavClient); only mutations go through the API, because it has
 * server-side conditions WebDAV lacks (verified live, koofr-api-probe-001):
 * - upload with overwrite=false onto an existing file: 409 AlreadyExists, file untouched;
 * - move onto an existing file: 409 AlreadyExists (atomic create-if-absent);
 * - upload with overwriteIfHash (MD5): 409 Conflict when the file changed meanwhile.
 * Same contract as WriteTransport: staging upload, verify, then publish. A new file
 * (diff, device registration) is published by an atomic move; our own .ydevice is
 * replaced only if it still has the MD5 we read (compare-and-swap), so a concurrent
 * change is never silently overwritten. Same path policy as the WebDAV writer.
 */
public final class KoofrTransport implements PublishTransport {
    static final String API="https://app.koofr.net";
    private final OkHttpClient http=DavClient.newHttpClient(false);
    private final String authorization;
    private final DavClient reader;
    private final DavClient.Audit audit;
    private final String base;          // folder path inside the mount, e.g. /YNAB4_PLUS_TEST/Budget
    private final String mountName;     // WebDAV mount segment, e.g. "Koofr"
    private String mountId;

    /** True when a WebDAV root points at Koofr's WebDAV ("https://app.koofr.net/dav/<mount>/..."). */
    public static boolean koofrRoot(String root) {
        try{URI u=new URI(root);return "https".equals(u.getScheme())&&"app.koofr.net".equalsIgnoreCase(u.getHost())&&(u.getPort()==-1||u.getPort()==443)
            &&u.getPath()!=null&&u.getPath().startsWith("/dav/")&&u.getPath().split("/").length>=3;}catch(URISyntaxException e){return false;}
    }

    KoofrTransport(String username,String password,DavClient reader,DavClient.Audit audit)throws IOException {
        if(!WritePolicy.ROOT.equals(DavClient.ROOT))throw new IOException("WRITE_ROOT_BUILD_MISMATCH");
        if(!koofrRoot(DavClient.ROOT))throw new IOException("KOOFR_ROOT_REQUIRED");
        try {
            String path=new URI(DavClient.ROOT).getPath();               // decoded: /dav/<mount>/<folder...>/
            String rest=path.substring("/dav/".length());int slash=rest.indexOf('/');
            mountName=slash<0?rest:rest.substring(0,slash);
            String folder=slash<0?"":rest.substring(slash);
            while(folder.endsWith("/"))folder=folder.substring(0,folder.length()-1);
            base=folder;
        }catch(URISyntaxException error){throw new IOException("KOOFR_ROOT_INVALID",error);}
        authorization=Credentials.basic(username,password,WritePolicy.UTF8);this.reader=reader;this.audit=audit;
    }

    private String remote(String logical)throws IOException {String p=DavClient.logical(logical);return p.equals("/")?(base.length()==0?"/":base):base+p;}

    private static final class Reply {final int status;final String code;final JSONObject body;Reply(int s,String c,JSONObject b){status=s;code=c;body=b;}}

    private Reply call(String method,String apiPath,Map<String,String> query,String logical,byte[] raw,JSONObject json)throws IOException {
        if(DavProvider.authenticationBlocked())throw new IOException("AUTHENTICATION_STOPPED");
        HttpUrl.Builder url=HttpUrl.get(API+apiPath).newBuilder();
        if(query!=null)for(Map.Entry<String,String> e:query.entrySet())url.addQueryParameter(e.getKey(),e.getValue());
        Request.Builder builder=new Request.Builder().url(url.build()).header("Authorization",authorization)
            .header("User-Agent","ClassicBudget-KoofrApiTEST/1.0").header("Connection","close");
        RequestBody body=raw!=null?RequestBody.create(raw,MediaType.get("application/octet-stream"))
            :json!=null?RequestBody.create(json.toString().getBytes(WritePolicy.UTF8),MediaType.get("application/json; charset=utf-8")):null;
        if(body==null&&(method.equals("POST")||method.equals("PUT")))body=RequestBody.create(new byte[0],null);
        builder.method(method,body);
        String label="API-"+method;
        if(audit!=null)audit.record(label,logical,0);
        try(Response response=http.newCall(builder.build()).execute()) {
            int status=response.code();if(audit!=null)audit.record(label,logical,status);
            if(status==401||status==429){DavProvider.blockAuthentication(status);throw new IOException("AUTHENTICATION_STOPPED_HTTP_"+status);}
            String text=response.body()==null?"":response.body().string();
            JSONObject parsed=null;String code="";
            try{if(text.startsWith("{")){parsed=new JSONObject(text);JSONObject err=parsed.optJSONObject("error");if(err!=null)code=err.optString("code");}}catch(JSONException ignored){}
            return new Reply(status,code,parsed);
        }
    }

    private synchronized String mount()throws IOException {
        if(mountId!=null)return mountId;
        Reply r=call("GET","/api/v2.1/mounts",null,"/",null,null);
        if(r.status!=200||r.body==null)throw new IOException("KOOFR_MOUNTS_HTTP_"+r.status);
        JSONArray mounts=r.body.optJSONArray("mounts");String found=null;
        for(int i=0;mounts!=null&&i<mounts.length();i++){JSONObject m=mounts.optJSONObject(i);if(m!=null&&mountName.equals(m.optString("name"))){if(found!=null)throw new IOException("KOOFR_MOUNT_AMBIGUOUS");found=m.optString("id");}}
        if(found==null||!found.matches("[A-Za-z0-9-]{8,80}"))throw new IOException("KOOFR_MOUNT_NOT_FOUND");
        return mountId=found;
    }

    static String md5(byte[] data)throws IOException {
        try{byte[] d=MessageDigest.getInstance("MD5").digest(data);StringBuilder s=new StringBuilder();for(byte b:d)s.append(String.format(Locale.ROOT,"%02x",b));return s.toString();}
        catch(java.security.NoSuchAlgorithmException e){throw new IOException(e);}
    }

    /** Same mutation policy as the WebDAV writer: staging files, our diff folder, our device file. */
    private static void allowedTarget(String path,String guid)throws IOException {
        boolean diff=path.startsWith(WritePolicy.DATA+"/"+guid+"/")&&path.endsWith(".ydiff")&&!path.substring((WritePolicy.DATA+"/"+guid+"/").length()).contains("/");
        if(!WritePolicy.devicePath(path)&&!diff)throw new IOException("MOVE_TARGET_NOT_ALLOWED");
    }

    private Reply upload(String logical,byte[] data,Map<String,String> conditions)throws IOException {
        if(data.length>WritePolicy.MAX_BYTES)throw new IOException("WRITE_PAYLOAD_SIZE_LIMIT");
        String r=remote(logical);int cut=r.lastIndexOf('/');
        Map<String,String> q=new LinkedHashMap<String,String>();q.put("path",cut==0?"/":r.substring(0,cut));q.put("filename",r.substring(cut+1));q.put("autorename","false");q.putAll(conditions);
        return call("POST","/content/api/v2.1/mounts/"+mount()+"/files/put",q,logical,data,null);
    }

    public String stagingFor(String target){return WritePolicy.STAGING+"/"+UUID.randomUUID().toString().replace("-","")+".tmp";}
    public byte[] optional(String path)throws IOException {try{return reader.read(path);}catch(FileNotFoundException e){return null;}}
    private static boolean same(byte[] a,byte[] b){return a!=null&&Arrays.equals(a,b);}

    void transferRegistration(String target, JSONObject transfer) throws Exception {
        if (!WritePolicy.devicePath(target)) throw new IOException("TAKEOVER_INVALID_TARGET");
        byte[] expected = transfer.getString("previous").getBytes(WritePolicy.UTF8);
        byte[] payload = transfer.getString("payload").getBytes(WritePolicy.UTF8);
        byte[] current = optional(target);
        if (same(current, payload)) return; // A prior response may have been lost.
        if (!same(current, expected)) throw new TakeoverStorage.Changed();
        Map<String, String> conditions = Collections.singletonMap("overwriteIfHash", md5(expected));
        Reply response = upload(target, payload, conditions);
        WriteFault.hit("after_move");
        if (same(optional(target), payload)) return;
        if (response.status == 409) throw new TakeoverStorage.Changed();
        throw new IOException("TAKEOVER_REPLACE_NOT_CONFIRMED");
    }

    public void directory(String path)throws IOException {
        path=DavClient.logical(path);
        String guid=WritePolicy.ownGuid(DavProvider.context());
        if(!path.equals(WritePolicy.STAGING)&&!path.equals(WritePolicy.DATA+"/"+guid))throw new IOException("MKCOL_NOT_ALLOWED");
        try{reader.list(path);return;}catch(FileNotFoundException missing){}
        String r=remote(path);int cut=r.lastIndexOf('/');
        Map<String,String> q=new HashMap<String,String>();q.put("path",cut==0?"/":r.substring(0,cut));
        IOException uncertain=null;int status=-1;
        try{status=call("POST","/api/v2.1/mounts/"+mount()+"/files/folder",q,path,null,new JSONObject().put("name",r.substring(cut+1))).status;}
        catch(JSONException e){throw new IOException(e);}catch(IOException e){if(DavProvider.authenticationBlocked())throw e;uncertain=e;}
        try{reader.list(path);return;}catch(FileNotFoundException missing){}   // confirm by reading, never by status alone
        throw new IOException("FOLDER_NOT_CONFIRMED_HTTP_"+status,uncertain);
    }

    public boolean publish(JSONObject entry,boolean replace)throws IOException {
        try {
            String target=DavClient.logical(entry.getString("path")),stage=DavClient.logical(entry.getString("staging"));
            WritePolicy.staging(stage);allowedTarget(target,WritePolicy.ownGuid(DavProvider.context()));
            byte[] payload=entry.getString("payload").getBytes(WritePolicy.UTF8);
            byte[] existing=optional(target);
            if(same(existing,payload)){removeLeftover(stage,payload);return true;}
            directory(WritePolicy.STAGING);
            byte[] staged=optional(stage);
            if(staged==null){
                Map<String,String> q=new HashMap<String,String>();q.put("overwrite","false");
                Reply r=upload(stage,payload,q);
                if(r.status!=200&&!(r.status==409&&"AlreadyExists".equals(r.code)))throw new IOException("STAGING_PUT_HTTP_"+r.status);
            }
            byte[] verified=optional(stage);
            if(!same(verified,payload))throw new StagingMismatch(verified);
            WriteFault.hit("after_staging");
            if(existing!=null&&!replace)return false;
            for(int attempt=0;attempt<4;attempt++) {
                Reply r=null;IOException uncertain=null;
                try {
                    if(existing==null) {
                        // New file: atomic move, fails with AlreadyExists if anyone created it meanwhile.
                        Map<String,String> q=new HashMap<String,String>();q.put("path",remote(stage));
                        r=call("PUT","/api/v2.1/mounts/"+mount()+"/files/move",q,stage,null,
                            new JSONObject().put("toMountId",mount()).put("toPath",remote(target)).put("ifHash",md5(payload)));
                    }else {
                        // Our own device file: replace only if it is still exactly what we read.
                        Map<String,String> q=new HashMap<String,String>();q.put("overwriteIfHash",md5(existing));
                        r=upload(target,payload,q);
                    }
                }catch(IOException error){uncertain=error;if(DavProvider.authenticationBlocked())throw error;}
                WriteFault.hit("after_move");
                byte[] finalBytes=optional(target);
                if(same(finalBytes,payload)){removeLeftover(stage,payload);return true;}
                if(finalBytes!=null&&!replace)return false;                         // immutable target taken by someone else
                if(r!=null&&r.status==409) {
                    if(!replace)return false;
                    existing=finalBytes;                                             // device changed meanwhile: re-read, retry once more
                    if(existing==null)throw new IOException("REGISTERED_DEVICE_DISAPPEARED");
                    WritePolicy.device(target,new String(existing,WritePolicy.UTF8),WritePolicy.ownGuid(DavProvider.context()));
                }else if(uncertain==null&&r!=null&&r.status!=200)throw new IOException("PUBLISH_NOT_CONFIRMED_HTTP_"+r.status);
                if(attempt==3)throw new IOException("PUBLISH_RETRY_LIMIT",uncertain);
                try{Thread.sleep(250L*(attempt+1));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("PUBLISH_INTERRUPTED",error);}
            }
            throw new IOException("PUBLISH_NOT_CONFIRMED");
        }catch(JSONException error){throw new IOException("INVALID_OUTBOX_ENTRY",error);}
    }

    /** After a replace (or a lost move response) the staging copy may remain: remove it if it is ours. */
    private void removeLeftover(String stage,byte[] payload)throws IOException {
        byte[] left=optional(stage);if(left==null||!same(left,payload))return;
        delete(stage);
    }

    private void delete(String stage)throws IOException {
        WritePolicy.staging(stage);
        Map<String,String> q=new HashMap<String,String>();q.put("path",remote(stage));
        Reply r=call("DELETE","/api/v2.1/mounts/"+mount()+"/files/remove",q,stage,null,null);
        if(r.status!=200&&r.status!=404)throw new IOException("STAGING_DELETE_HTTP_"+r.status);
    }

    public void removeStaging(JSONObject entry)throws IOException {
        try{String stage=DavClient.logical(entry.getString("staging"));byte[] bytes=optional(stage);if(bytes==null)return;
            if(!WritePolicy.sha(bytes).equals(entry.getString("sha256")))throw new IOException("STAGING_DELETE_HASH_MISMATCH");
            delete(stage);
        }catch(JSONException error){throw new IOException(error);}
    }
}

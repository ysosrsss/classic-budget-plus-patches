package org.classicbudget.patch;

import java.io.*;
import java.util.*;
import okhttp3.*;
import org.json.*;

/** Mutations have a fixed synthetic namespace and explicit, bounded recovery. */
public final class WriteTransport implements PublishTransport {
    private final OkHttpClient http=DavClient.newHttpClient(false);
    private final String authorization;
    private final DavClient reader;
    private final DavClient.Audit audit;
    WriteTransport(String username,String password,DavClient reader,DavClient.Audit audit)throws IOException {
        if(!WritePolicy.ROOT.equals(DavClient.ROOT))throw new IOException("WRITE_ROOT_BUILD_MISMATCH");
        authorization=Credentials.basic(username,password,WritePolicy.UTF8);this.reader=reader;this.audit=audit;
    }
    private int request(String method,String path,byte[] data,String destination,boolean overwrite)throws IOException {
        path=DavClient.logical(path);
        if(method.equals("PUT")||method.equals("DELETE")||method.equals("MOVE"))WritePolicy.staging(path);
        else if(method.equals("MKCOL")) {
            String guid=WritePolicy.ownGuid(DavProvider.context());
            if(!path.equals(WritePolicy.STAGING)&&!path.equals(WritePolicy.DATA+"/"+guid))throw new IOException("MKCOL_NOT_ALLOWED");
        }else throw new IOException("METHOD_NOT_ALLOWED");
        if(destination!=null) {
            String guid=WritePolicy.ownGuid(DavProvider.context());
            if(!WritePolicy.devicePath(destination)&&!(destination.startsWith(WritePolicy.DATA+"/"+guid+"/")&&destination.endsWith(".ydiff")&&!destination.substring((WritePolicy.DATA+"/"+guid+"/").length()).contains("/")))throw new IOException("MOVE_TARGET_NOT_ALLOWED");
        }
        if(data!=null&&data.length>WritePolicy.MAX_BYTES)throw new IOException("WRITE_PAYLOAD_SIZE_LIMIT");
        if(DavProvider.authenticationBlocked())throw new IOException("AUTHENTICATION_STOPPED");
        Request.Builder builder=new Request.Builder().url(DavClient.url(path,method.equals("MKCOL")))
            .header("Authorization",authorization).header("User-Agent","ClassicBudget-SyntheticWriteTEST/1.0").header("Connection","close");
        if(destination!=null)builder.header("Destination",DavClient.url(destination,false)).header("Overwrite",overwrite?"T":"F");
        builder.method(method,data==null?null:RequestBody.create(data,MediaType.get("application/octet-stream")));
        if(audit!=null)audit.record(method,path,0);
        try(Response response=http.newCall(builder.build()).execute()) {
            int status=response.code();if(audit!=null)audit.record(method,path,status);
            if(status==401||status==429){DavProvider.blockAuthentication(status);throw new IOException("AUTHENTICATION_STOPPED_HTTP_"+status);}
            return status;
        }
    }
    public String stagingFor(String target){return WritePolicy.STAGING+"/"+UUID.randomUUID().toString().replace("-","")+".tmp";}
    public byte[] optional(String path)throws IOException {try{return reader.read(path);}catch(FileNotFoundException e){return null;}}
    private static boolean same(byte[] a,byte[] b){return a!=null&&Arrays.equals(a,b);}
    public void directory(String path)throws IOException {
        try{reader.list(path);return;}catch(FileNotFoundException missing){}
        for(int attempt=0;attempt<4;attempt++) {
            int status=-1;IOException uncertain=null;
            try{status=request("MKCOL",path,null,null,false);}
            catch(IOException error){if(DavProvider.authenticationBlocked())throw error;uncertain=error;}
            // A competing creator or a lost MKCOL response may already have
            // produced the collection. Never infer existence from status alone.
            try{reader.list(path);return;}catch(FileNotFoundException missing){}
            if(status!=423&&uncertain==null)throw new IOException("MKCOL_NOT_CONFIRMED_HTTP_"+status);
            if(attempt==3)throw new IOException("MKCOL_RETRY_LIMIT",uncertain);
            try{Thread.sleep(250L*(attempt+1));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("MKCOL_INTERRUPTED",error);}
        }
    }
    public boolean publish(JSONObject entry,boolean replace)throws IOException {
        try {
            String target=entry.getString("path"),stage=entry.getString("staging");
            byte[] payload=entry.getString("payload").getBytes(WritePolicy.UTF8);
            byte[] existing=optional(target);
            if(same(existing,payload))return true;
            directory(WritePolicy.STAGING);
            byte[] staged=optional(stage);
            if(staged==null){int status=request("PUT",stage,payload,null,false);if(status!=201&&status!=204)throw new IOException("STAGING_PUT_HTTP_"+status);}
            byte[] verified=optional(stage);
            if(!same(verified,payload))throw new StagingMismatch(verified);
            WriteFault.hit("after_staging");
            if(existing!=null&&!replace)return false;
            for(int attempt=0;attempt<4;attempt++) {
                int status=-1;IOException uncertain=null;
                try{status=request("MOVE",stage,null,target,replace);}catch(IOException error){uncertain=error;if(DavProvider.authenticationBlocked())throw error;}
                WriteFault.hit("after_move");
                byte[] finalBytes=optional(target),sourceBytes=optional(stage);
                if(same(finalBytes,payload)&&sourceBytes==null)return true;
                if(status==412&&!replace&&sourceBytes!=null)return false;
                if(status!=423&&uncertain==null)throw new IOException("MOVE_NOT_CONFIRMED_HTTP_"+status);
                if(!same(sourceBytes,payload))throw new IOException("UNCERTAIN_MOVE_STAGING_CHANGED",uncertain);
                if(finalBytes!=null&&!replace)return false;
                if(attempt==3)throw new IOException("MOVE_RETRY_LIMIT",uncertain);
                try{Thread.sleep(250L*(attempt+1));}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException("MOVE_INTERRUPTED",error);}
            }
            throw new IOException("MOVE_NOT_CONFIRMED");
        }catch(JSONException error){throw new IOException("INVALID_OUTBOX_ENTRY",error);}
    }
    public void removeStaging(JSONObject entry)throws IOException {
        try{String stage=entry.getString("staging");byte[] bytes=optional(stage);if(bytes==null)return;
            if(!WritePolicy.sha(bytes).equals(entry.getString("sha256")))throw new IOException("STAGING_DELETE_HASH_MISMATCH");
            int status=request("DELETE",stage,null,null,false);if(status!=204&&status!=200&&status!=404)throw new IOException("STAGING_DELETE_HTTP_"+status);
        }catch(JSONException error){throw new IOException(error);}
    }
}

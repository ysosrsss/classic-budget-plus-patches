package org.classicbudget.patch;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;
import org.json.*;

/** Recover interrupted export commits through a narrow read view, or replay a
 * pinned import acknowledgement proven against committed SQLite. Never edit SQLite.
 */
public final class WriteRecovery {
    static byte[] readOwnMetadata(Context context,WriteJournal journal,PublishTransport writer,String path,byte[] remote)throws IOException {
        if(!WritePolicy.devicePath(path))return remote;
        JSONObject metadataEntry=journal.entry(path),diffEntry=journal.singlePendingDiff();
        if(metadataEntry==null)return remote;
        if(diffEntry==null)return resumeImportedMetadata(context,journal,writer,path,remote,metadataEntry);
        try {
            String guid=WritePolicy.ownGuid(context),shortId=WritePolicy.device(path,new String(remote,WritePolicy.UTF8),guid);
            JSONObject diff=new JSONObject(diffEntry.getString("payload")),device=new JSONObject(new String(remote,WritePolicy.UTF8));
            Map<String,Integer> actual=WritePolicy.knowledge(device.getString("knowledge")),startVector=WritePolicy.knowledge(diff.getString("startVersion"));
            // A stale completed registration/import entry may coexist with a diff
            // that has not advanced remote metadata. Its true view needs no repair.
            if(WritePolicy.dominates(actual,startVector)&&WritePolicy.dominates(startVector,actual))return remote;
            if(!"device".equals(metadataEntry.getString("mode")))throw new IOException("RECOVERY_METADATA_MODE");
            // Only a known pending publication, confirmed byte for byte, can be viewed.
            if(!Arrays.equals(remote,metadataEntry.getString("payload").getBytes(WritePolicy.UTF8)))throw new IOException("RECOVERY_REMOTE_METADATA_CHANGED");
            String payload=diffEntry.getString("payload"),diffPath=diffEntry.getString("path");WritePolicy.diff(diffPath,payload,guid,shortId);
            byte[] published=writer.optional(diffPath);
            if(published==null||!WritePolicy.sha(published).equals(diffEntry.getString("sha256")))throw new IOException("RECOVERY_DIFF_NOT_CONFIRMED");
            String start=diff.getString("startVersion"),end=diff.getString("endVersion");
            if(!WritePolicy.knowledge(device.getString("knowledge")).equals(WritePolicy.knowledge(end)))throw new IOException("RECOVERY_KNOWLEDGE_CHANGED");
            Map<String,Integer> starts=WritePolicy.knowledge(start),ends=WritePolicy.knowledge(end);
            int from=starts.containsKey(shortId)?starts.get(shortId):0,to=ends.get(shortId);
            File database=WritePolicy.database(context);
            if(!database.isFile())throw new IOException("RECOVERY_DATABASE_MISSING");
            int local;String localId;
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(database.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                try(Cursor c=db.rawQuery("SELECT value FROM counters WHERE name='lastPublishedKnowledge'",null)){if(!c.moveToFirst())throw new IOException("RECOVERY_COUNTER_MISSING");local=c.getInt(0);}
                try(Cursor c=db.rawQuery("SELECT value FROM counters WHERE name='deviceId'",null)){if(!c.moveToFirst())throw new IOException("RECOVERY_DEVICE_MISSING");localId=c.getString(0);}
            }
            if(!shortId.equals(localId))throw new IOException("RECOVERY_LOCAL_DEVICE_CHANGED");
            if(local==to){journal.confirmedCommitted(diffPath,path);return remote;}
            if(local!=from)throw new IOException("RECOVERY_LOCAL_COUNTER_CHANGED");
            // The constructor compares remote published knowledge with local committed
            // knowledge. Give only that constructor's reader the pinned predecessor.
            // Writes still validate/read the real server metadata through WriteTransport.
            device.put("knowledge",start);return device.toString().getBytes(WritePolicy.UTF8);
        }catch(JSONException error){throw new IOException("RECOVERY_INVALID_JOURNAL",error);}
    }

    /** Import commits SQLite before acknowledging knowledge remotely. Resume only
     * a pinned acknowledgement proven to describe this exact committed database. */
    static byte[] resumeImportedMetadata(Context context,WriteJournal journal,PublishTransport writer,String path,byte[] remote,JSONObject entry)throws IOException {
        try {
            String payload=entry.getString("payload"),guid=WritePolicy.ownGuid(context);
            String id=WritePolicy.device(path,payload,guid);
            WritePolicy.device(path,new String(remote,WritePolicy.UTF8),guid);
            if(!"device".equals(entry.getString("mode")))throw new IOException("IMPORT_RECOVERY_METADATA_MODE");
            Map<String,Integer> expected=WritePolicy.knowledge(new JSONObject(payload).getString("knowledge"));
            Map<String,Integer> actual=WritePolicy.knowledge(new JSONObject(new String(remote,WritePolicy.UTF8)).getString("knowledge"));
            if(!WritePolicy.dominates(expected,actual))throw new IOException("IMPORT_RECOVERY_REMOTE_AHEAD");
            File file=WritePolicy.database(context);
            if(!file.isFile())throw new IOException("IMPORT_RECOVERY_DATABASE_MISSING");
            Map<String,Integer> local=new TreeMap<String,Integer>();
            try(SQLiteDatabase db=SQLiteDatabase.openDatabase(file.getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
                try(Cursor c=db.rawQuery("SELECT name,version FROM versionVector",null)){while(c.moveToNext())local.put(c.getString(0),c.getInt(1));}
                if(!WritePolicy.dominates(local,expected)||!WritePolicy.dominates(expected,local))throw new IOException("IMPORT_RECOVERY_VECTOR_MISMATCH");
                try(Cursor c=db.rawQuery("SELECT value FROM counters WHERE name='deviceId'",null)){if(!c.moveToFirst()||!id.equals(c.getString(0)))throw new IOException("IMPORT_RECOVERY_DEVICE_MISMATCH");}
                try(Cursor c=db.rawQuery("SELECT value FROM counters WHERE name='lastPublishedKnowledge'",null)){if(!c.moveToFirst()||c.getInt(0)!=(expected.containsKey(id)?expected.get(id):0))throw new IOException("IMPORT_RECOVERY_COUNTER_MISMATCH");}
                for(String table:new String[]{"txnTable","payeeTable"})try(Cursor c=db.rawQuery("SELECT COUNT(*) FROM "+table+" WHERE synced=0",null)){if(!c.moveToFirst()||c.getInt(0)!=0)throw new IOException("IMPORT_RECOVERY_UNPUBLISHED_EDITS");}
            }
            // Preserve pinned bytes and normal staging/MOVE, including bounded
            // partial-staging recovery. Never patch SQLite or clear an unproven entry.
            while(true)try {
                if(!writer.publish(entry,true))throw new IOException("IMPORT_RECOVERY_PUBLISH_REJECTED");
                break;
            }catch(PublishTransport.StagingMismatch mismatch){journal.rotateStaging(entry,mismatch.observedHash);}
            journal.published(entry);journal.forget(path);return payload.getBytes(WritePolicy.UTF8);
        }catch(JSONException error){throw new IOException("IMPORT_RECOVERY_INVALID_JOURNAL",error);}
    }
}

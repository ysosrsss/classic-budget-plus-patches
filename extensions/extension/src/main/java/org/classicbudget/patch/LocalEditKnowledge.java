package org.classicbudget.patch;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;

/** Snapshot knowledge inside the original local-edit transaction, before new versions. */
public final class LocalEditKnowledge {
 public static String capture(SQLiteDatabase db) {
  // Engine-semantics fix, valid for every budget: an identity guard here made every local edit
  // of a non-synthetic budget fail (found 2026-11-09 night, MULTI with a budget copy on Dropbox).
  if(db==null)throw new IllegalStateException("LOCAL_KNOWLEDGE_NO_DATABASE");
  if(!db.inTransaction())throw new IllegalStateException("LOCAL_KNOWLEDGE_REQUIRES_TRANSACTION");
  Map<String,Integer> known=new TreeMap<String,Integer>();
  try(Cursor c=db.rawQuery("SELECT name,version FROM versionVector",null)){
   while(c.moveToNext()){
    String name=c.getString(0);int version=c.getInt(1);
    if(!WritePolicy.shortId(name)||version<0||known.containsKey(name))throw new IllegalStateException("LOCAL_KNOWLEDGE_INVALID_VECTOR");
    known.put(name,version);
   }
  }
  String id=null;Integer assigned=null;
  try(Cursor c=db.rawQuery("SELECT name,value FROM counters WHERE name IN ('deviceId','version')",null)){
   while(c.moveToNext())if(c.getString(0).equals("deviceId"))id=c.getString(1);else assigned=c.getInt(1);
  }
  if(!WritePolicy.shortId(id)||assigned==null||assigned<0)throw new IllegalStateException("LOCAL_KNOWLEDGE_COUNTER_MISSING");
  int previous=known.containsKey(id)?known.get(id):0;
  if(assigned<previous)throw new IllegalStateException("LOCAL_KNOWLEDGE_COUNTER_REGRESSION");
  known.put(id,assigned);
  StringBuilder result=new StringBuilder();
  for(Map.Entry<String,Integer> e:known.entrySet()){if(result.length()>0)result.append(',');result.append(e.getKey()).append('-').append(e.getValue());}
  return result.toString();
 }
}

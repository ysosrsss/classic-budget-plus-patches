package org.classicbudget.patch;
import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.view.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** Preserves both transaction versions before the native importer skips a conflict. */
public final class ConflictStore {
 static final int MAX_RECORDS=32,MAX_RECORD_BYTES=128*1024,MAX_BYTES=2*1024*1024,MENU=0x43424c;
 static WeakReference<Activity> foreground=new WeakReference<Activity>(null);
 static final WeakHashMap<Activity,String> shown=new WeakHashMap<Activity,String>();
 static File file(Context c){return new File(c.getFilesDir(),"conflicts.json");}
 static File file(Context c,String budgetId)throws IOException {
  return new File(c.getFilesDir(),"conflicts-"+WritePolicy.sha(budgetId.getBytes(WritePolicy.UTF8))+".json");
 }
 static Object value(Object object,int depth)throws Exception {
  if(object==null)return JSONObject.NULL;
  if(object instanceof String||object instanceof Number||object instanceof Boolean)return object;
  if(depth>4)throw new IOException("CONFLICT_DTO_DEPTH");
  if(object instanceof Object[]){JSONArray array=new JSONArray();for(Object item:(Object[])object)array.put(value(item,depth+1));return array;}
  if(!object.getClass().getName().equals("com.youneedabudget.ynab.core.backend.h$e"))throw new IOException("CONFLICT_DTO_TYPE");
  JSONObject result=new JSONObject();
  for(Field f:object.getClass().getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())&&!f.isSynthetic()){f.setAccessible(true);result.put(f.getName(),value(f.get(object),depth+1));}
  return result;
 }
 static JSONObject row(Cursor cursor)throws Exception {
  JSONObject result=new JSONObject();
  for(int i=0;i<cursor.getColumnCount();i++){
   Object v;switch(cursor.getType(i)){case Cursor.FIELD_TYPE_NULL:v=JSONObject.NULL;break;case Cursor.FIELD_TYPE_INTEGER:v=cursor.getLong(i);break;case Cursor.FIELD_TYPE_FLOAT:v=cursor.getDouble(i);break;case Cursor.FIELD_TYPE_STRING:v=cursor.getString(i);break;default:throw new IOException("CONFLICT_ROW_TYPE");}
   result.put(cursor.getColumnName(i),v);
  }return result;
 }
 static String name(SQLiteDatabase db,String table,String column,String id){
  if(id==null||id.equals("null"))return "(brak danych)";
  try(Cursor c=db.query(table,new String[]{"name"},column+"=?",new String[]{id},null,null,null)){return c.moveToFirst()&&!c.isNull(0)?c.getString(0):"(brak danych)";}
 }
 static JSONObject display(SQLiteDatabase db,JSONObject local,JSONObject incoming)throws Exception {
  JSONObject result=new JSONObject();
  result.put("localAccount",name(db,"accountTable","_id",local.isNull("accountId")?null:local.optString("accountId")));
  result.put("incomingAccount",name(db,"accountTable","guid",incoming.isNull("accountId")?null:incoming.optString("accountId")));
  result.put("localPayee",name(db,"payeeTable","_id",local.isNull("payeeId")?null:local.optString("payeeId")));
  result.put("incomingPayee",name(db,"payeeTable","guid",incoming.isNull("payeeId")?null:incoming.optString("payeeId")));
  result.put("localCategory",name(db,"categoryTable","_id",local.isNull("categoryId")?null:local.optString("categoryId")));
  result.put("incomingCategory",name(db,"categoryTable","guid",incoming.isNull("categoryId")?null:incoming.optString("categoryId")));
  return result;
 }
 static String key(String guid,String first,String second)throws IOException {
  return WritePolicy.sha(("txnTable\n"+guid+"\n"+(first.compareTo(second)<0?first+"\n"+second:second+"\n"+first)).getBytes(WritePolicy.UTF8));
 }
 static synchronized JSONObject load(Context context)throws IOException {
  return load(file(context),WritePolicy.BUDGET);
 }
 private static JSONObject load(File file,String identity)throws IOException {
  try{
   if(!file.exists()){JSONObject root=new JSONObject();root.put("schema",1);root.put("budget",identity);root.put("records",new JSONObject());return root;}
   if(file.length()>MAX_BYTES)throw new IOException("CONFLICT_STORE_SIZE");
   JSONObject root=new JSONObject(WriteJournal.read(file));
   if(root.getInt("schema")!=1||!root.getString("budget").equals(identity))throw new IOException("CONFLICT_STORE_IDENTITY");
   JSONObject records=root.getJSONObject("records");if(records.length()>MAX_RECORDS)throw new IOException("CONFLICT_STORE_COUNT");
   for(Iterator<String> keys=records.keys();keys.hasNext();){String id=keys.next();JSONObject record=records.getJSONObject(id);String payload=record.getString("payload");
    if(payload.getBytes(WritePolicy.UTF8).length>MAX_RECORD_BYTES||!WritePolicy.sha(payload.getBytes(WritePolicy.UTF8)).equals(record.getString("sha256")))throw new IOException("CONFLICT_RECORD_HASH");
    JSONObject data=new JSONObject(payload),local=data.getJSONObject("local").getJSONObject("row"),incoming=data.getJSONObject("incoming");
    if(!local.getString("guid").equals(incoming.getString("entityId"))||!id.equals(key(local.getString("guid"),local.getString("version"),incoming.getString("entityVersion"))))throw new IOException("CONFLICT_RECORD_IDENTITY");
   }return root;
  }catch(JSONException e){throw new IOException("CONFLICT_STORE_INVALID",e);}
 }
 static synchronized JSONObject active(Context context,String id)throws Exception {
  JSONObject records=load(file(context,id),id).getJSONObject("records");
  File legacy=file(context);
  if(!legacy.isFile())return records;
  String folder=null;
  try(SQLiteDatabase db=SQLiteDatabase.openDatabase(context.getDatabasePath(id).getPath(),null,SQLiteDatabase.OPEN_READONLY);
      Cursor cursor=db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'",null)){
   if(cursor.moveToFirst())folder=cursor.getString(0);
  }
  if(folder==null || legacy.length()>MAX_BYTES)return records;
  JSONObject old=new JSONObject(WriteJournal.read(legacy));
  if(!folder.equals(old.optString("budget")))return records;
  JSONObject candidates=load(legacy,folder).getJSONObject("records");
  for(Iterator<String> keys=candidates.keys();keys.hasNext();){
   String key=keys.next();
   if(!records.has(key))records.put(key,candidates.getJSONObject(key));
  }
  return records;
 }
 public static synchronized void capture(Object dto,SQLiteDatabase db,String table)throws IOException {
  if(!"txnTable".equals(table))return;
  if(db==null)throw new IOException("CONFLICT_NO_DATABASE");
  // Evidence store is per scoped budget; any other budget keeps the original behaviour (no capture, no failure).
  if(!WritePolicy.scoped(db))return;
  Context context=DavProvider.context();
  capture(dto,db,context,file(context),WritePolicy.BUDGET);
 }
 /** Morphe captures ordinary transaction conflicts for the actual local budget, regardless of transport. */
 public static synchronized void captureBudget(Object dto,SQLiteDatabase db,String table)throws IOException {
  if(!"txnTable".equals(table))return;
  if(db==null)throw new IOException("CONFLICT_NO_DATABASE");
  Context context=DavProvider.context();
  File database=new File(db.getPath());
  String id=database.getName();
  if(!id.endsWith("#dropbox")||!database.equals(context.getDatabasePath(id)))return;
  capture(dto,db,context,file(context,id),id);
  SyncHealth.prefs(context).edit().putLong(id+":conflictChanged",System.currentTimeMillis()).apply();
 }
 private static void capture(Object dto,SQLiteDatabase db,Context context,File file,String identity)throws IOException {
  if(!db.inTransaction())throw new IOException("CONFLICT_REQUIRES_IMPORT_TRANSACTION");
  try{
   JSONObject incoming=(JSONObject)value(dto,0);String guid=incoming.getString("entityId"),remoteVersion=incoming.getString("entityVersion");
   if(guid.isEmpty()||guid.length()>120||!remoteVersion.matches(WritePolicy.SHORT_ID+"-[0-9]+"))throw new IOException("CONFLICT_ENTITY_IDENTITY");
   JSONObject local;
   try(Cursor c=db.query("txnTable",null,"guid=?",new String[]{guid},null,null,null)){if(!c.moveToFirst())throw new IOException("CONFLICT_LOCAL_MISSING");local=row(c);if(c.moveToNext())throw new IOException("CONFLICT_LOCAL_DUPLICATE");}
   String localVersion=local.getString("version");if(!localVersion.matches(WritePolicy.SHORT_ID+"-[0-9]+")||localVersion.equals(remoteVersion))throw new IOException("CONFLICT_VERSION_IDENTITY");
   JSONObject root=load(file,identity),records=root.getJSONObject("records");String key=key(guid,localVersion,remoteVersion);
   if(records.has(key))return;
   if(records.length()>=MAX_RECORDS)throw new IOException("CONFLICT_STORE_COUNT");
   JSONObject localData=new JSONObject();localData.put("row",local);long parent=local.optLong("parentId",0),family=parent>0?parent:local.getLong("_id");
   if(parent>0)try(Cursor c=db.query("txnTable",null,"_id=?",new String[]{Long.toString(parent)},null,null,null)){if(c.moveToFirst())localData.put("parent",row(c));}
   JSONArray children=new JSONArray();try(Cursor c=db.query("txnTable",null,"parentId=?",new String[]{Long.toString(family)},null,null,"guid")){while(c.moveToNext())children.put(row(c));}localData.put("subTransactions",children);
   JSONObject payload=new JSONObject();payload.put("local",localData);payload.put("incoming",incoming);payload.put("display",display(db,local,incoming));
   String body=payload.toString();if(body.getBytes(WritePolicy.UTF8).length>MAX_RECORD_BYTES)throw new IOException("CONFLICT_RECORD_SIZE");
   JSONObject record=new JSONObject();record.put("payload",body);record.put("sha256",WritePolicy.sha(body.getBytes(WritePolicy.UTF8)));record.put("capturedAt",System.currentTimeMillis());records.put(key,record);
   String text=root.toString();if(text.getBytes(WritePolicy.UTF8).length>MAX_BYTES)throw new IOException("CONFLICT_STORE_SIZE");
   WriteJournal.atomic(file,text);
   PlusDiagnostics.record(context, PlusDiagnostics.Event.CONFLICT_CAPTURED);
   Activity activity=foreground.get();if(activity!=null)prompt(activity);
  }catch(IOException e){throw e;}catch(Exception e){throw new IOException("CONFLICT_CAPTURE_FAILED",e);}
 }
 public static void resume(Activity activity){foreground=new WeakReference<Activity>(activity);prompt(activity);}
 public static void pause(Activity activity){if(foreground.get()==activity)foreground.clear();}
 static void prompt(final Activity activity){
  activity.runOnUiThread(new Runnable(){public void run(){
   if(foreground.get()!=activity||activity.isFinishing()||activity.isDestroyed())return;
   if(SyncStatusActivity.available(activity))return;
   try{
    String id=SyncHealth.selected(activity);
    JSONObject records=id==null?load(activity).getJSONObject("records"):active(activity,id);if(records.length()==0)return;
    ArrayList<String> keys=new ArrayList<String>();for(Iterator<String> it=records.keys();it.hasNext();)keys.add(it.next());Collections.sort(keys);String digest=keys.toString();
    if(digest.equals(shown.get(activity)))return;shown.put(activity,digest);
    new AlertDialog.Builder(activity).setTitle("Konflikt synchronizacji").setMessage("Zachowano różne wersje transakcji. Otwórz podgląd i sprawdź różnice.")
     .setPositiveButton("Zobacz wersje",new DialogInterface.OnClickListener(){public void onClick(DialogInterface dialog,int which){activity.startActivity(new Intent(activity,ConflictReviewActivity.class));}})
     .setNegativeButton("Później",null).show();
   }catch(Exception e){try{new AlertDialog.Builder(activity).setTitle("Zapis konfliktów wymaga sprawdzenia").setMessage("Nie można odczytać zachowanych wersji. Dane pozostawiono do sprawdzenia.").setPositiveButton("Zamknij",null).show();}catch(RuntimeException gone){/* Activity can disappear between lifecycle check and show. */}}
  }});
 }
 public static void menu(Activity activity,Menu menu){if(menu.findItem(MENU)==null)menu.add(0,MENU,1000,"Konflikty").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);}
 public static boolean selected(Activity activity,MenuItem item){if(item.getItemId()!=MENU)return false;activity.startActivity(new Intent(activity,ConflictReviewActivity.class));return true;}
}

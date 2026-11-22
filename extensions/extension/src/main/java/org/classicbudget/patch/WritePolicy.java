package org.classicbudget.patch;

import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/**
 * Write scope of the journaled writer. Defaults to the synthetic lab budget; an
 * accessor may widen it to the budgets listed in its storage's .ynabSettings.yroot
 * (scope/known) — the storage root itself is still confined by each backend's TEST guard.
 * Only this device's registration and its own diffs in the scoped budget are writable.
 */
public final class WritePolicy {
    public static volatile String ROOT="https://app.koofr.net/dav/Koofr/YNAB4_PLUS_TEST/__write_budget_lab__/";
    public static final String SYNTHETIC_BUDGET="/Synthetic Budget TEST~C0DEC0DE.ynab4", SYNTHETIC_DATA="data3-C0DEC0DE";
    public static volatile String BUDGET=SYNTHETIC_BUDGET;
    public static volatile String DATA_NAME=SYNTHETIC_DATA;
    public static volatile String DATA=BUDGET+"/"+DATA_NAME;
    public static volatile String DEVICES=DATA+"/Devices";
    /** Budgets that may be read (the yroot's relativeKnownBudgets). */
    public static final Set<String> KNOWN=Collections.synchronizedSet(new LinkedHashSet<String>(Collections.singleton(SYNTHETIC_BUDGET)));
    public static boolean budgetName(String budget){return budget!=null&&budget.startsWith("/")&&budget.endsWith(".ynab4")&&budget.length()<=210&&budget.indexOf('/',1)<0&&budget.indexOf('\\')<0&&budget.indexOf(':')<0&&!budget.contains("..");}
    public static boolean dataName(String data){return data!=null&&data.matches("data[0-9]{1,3}[-~][0-9A-Za-z]{1,40}");}
    /** Budgets listed in the storage's yroot become readable. */
    public static synchronized void known(Collection<String> budgets)throws IOException {
        for(String b:budgets)if(!budgetName(b))throw new IOException("INVALID_KNOWN_BUDGET");
        KNOWN.addAll(budgets);
    }
    /** Selects the budget whose own registration and diffs may be written. */
    public static synchronized void scope(String budget,String data)throws IOException {
        if(!budgetName(budget)||!dataName(data)||!KNOWN.contains(budget))throw new IOException("INVALID_WRITE_SCOPE");
        BUDGET=budget;DATA_NAME=data;DATA=budget+"/"+data;DEVICES=DATA+"/Devices";
    }
    /** Preference key of this phone's short device id in the scoped budget. The same phone has a
     * different letter in every budget (e.g. B in Synthetic, P in a copy); one shared key made the
     * second budget publish diffs under the wrong letter (INVALID_DIFF_KNOWLEDGE, 13.11). */
    public static synchronized String shortIdKey(){return "shortId:"+DATA;}
    /** The known budget that contains a path, or null. */
    public static String budgetOf(String path){synchronized(KNOWN){for(String b:KNOWN)if(path.equals(b)||path.startsWith(b+"/"))return b;}return null;}
    /** True when this open database (same connection, safe inside a transaction) belongs to the scoped budget. */
    public static boolean scoped(android.database.sqlite.SQLiteDatabase db) {
        // Synthetic lab budget: exactly the qualified rule (database file name).
        if(SYNTHETIC_BUDGET.equals(BUDGET))return new File(db.getPath()).getName().equals("U3ludGhldGljIEJ1ZGdldCBURVNU#dropbox");
        try(android.database.Cursor cur=db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'",null)){return cur.moveToFirst()&&BUDGET.equals(cur.getString(0));}
        catch(RuntimeException error){return false;}
    }
    /** Local database of the scoped budget (keyValue.budgetFolder), synthetic name as fallback. */
    public static File database(android.content.Context c) {
        if(SYNTHETIC_BUDGET.equals(BUDGET))return c.getDatabasePath("U3ludGhldGljIEJ1ZGdldCBURVNU#dropbox");
        for(String name:c.databaseList()) {
            if(!name.contains("#")||name.endsWith("-journal")||name.endsWith("-wal")||name.endsWith("-shm"))continue;
            File f=c.getDatabasePath(name);
            try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(f.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                android.database.Cursor cur=db.rawQuery("SELECT value FROM keyValue WHERE name='budgetFolder'",null)){
                if(cur.moveToFirst()&&BUDGET.equals(cur.getString(0)))return f;
            }catch(RuntimeException ignored){}
        }
        return c.getDatabasePath("U3ludGhldGljIEJ1ZGdldCBURVNU#dropbox");
    }
    /**
     * Short device id. The original Android allocates A..Z and then continues AA..AZ, AAA..
     * (core.cloud.r.a(String) appends "AA" after a final Z); Windows 4.3.857 allocates only A..Z
     * but parses any name before '-' (VersionVectorElement.parse). A single-letter rule here made
     * one 27th registration block every Plus Folder/WebDAV writer of the budget (20.11).
     * The length cap only bounds malformed input.
     */
    public static final String SHORT_ID="[A-Z]{1,8}";
    public static boolean shortId(String id){return id!=null&&id.matches(SHORT_ID);}
    /** The id the original Android tries after the given one: A..Z, AA..AZ, AAA.. */
    static String nextShortId(String id) {
        if(id==null||id.isEmpty())return "A";
        String head=id.substring(0,id.length()-1);
        char last=id.charAt(id.length()-1);
        if(last=='Z')return head+"AA";
        return head+(char)(last+1);
    }
    public static final String STAGING="/__staging__";
    public static final int MAX_BYTES=2*1024*1024;
    public static final Charset UTF8=Charset.forName("UTF-8");
    public static String logical(File file)throws IOException{return DavClient.logical(file==null?"/":file.getPath());}
    public static void readable(String path)throws IOException {
        if(!path.equals("/")&&!path.equals("/.ynabSettings.yroot")&&!path.equals("/YNAB")&&!path.equals(STAGING)&&!path.startsWith(STAGING+"/")&&budgetOf(path)==null)
            throw new IOException("WRITE_TEST_ROOT_ONLY");
    }
    public static void staging(String path)throws IOException {
        if(!path.matches("/__staging__/[0-9a-f]{32}\\.tmp"))throw new IOException("INVALID_STAGING_PATH");
    }
    public static String ownGuid(android.content.Context context)throws IOException {
        // core.cloud.aa.a initializes this before invoking the adapter's writes.
        String guid=context.getSharedPreferences("cloudPrefs",0).getString("guid",null);
        guid=registrationGuid(context,new File(DATA),guid);
        if(guid==null||!guid.matches("[A-Za-z0-9-]{8,80}"))throw new IOException("ENGINE_GUID_NOT_INITIALIZED");
        return guid;
    }
    static final String DEVICE_TAKEOVER_PREFS = "cb-plus-device-takeover";
    static String registrationGuid(android.content.Context context, File data, String baseGuid) {
        return context.getSharedPreferences(DEVICE_TAKEOVER_PREFS, 0).getString("guid:" + data.getPath(), baseGuid);
    }
    /**
     * Our own registration when the local note of it is gone (app data cleared, "Change sync method",
     * reinstall): the original engine re-identifies the phone by its stable device GUID and reuses the
     * same letter ("Previously registered as device B"). Finds the .ydevice carrying our deviceGUID.
     * Returns null when none does (a new registration then goes through the normal path).
     * The transport cannot list a folder, so ids are probed in the original allocation order:
     * all of A..Z (holes are possible there), then AA, AB.. until the first missing file, since
     * the original engine fills that tail contiguously.
     */
    static String recoverShortId(PublishTransport transport,String guid)throws IOException {
        String found=null;
        String id="A";
        while(true) {
            byte[] data=transport.optional(DEVICES+"/"+id+".ydevice");
            boolean pastSingleLetters=id.length()>1;
            if(data==null&&pastSingleLetters)break;
            if(data!=null)found=ownRegistration(data,id,guid,found);
            id=nextShortId(id);
            if(id.length()>8)break;
        }
        return found;
    }
    /** Returns id when the .ydevice bytes are this device's registration under that id. */
    private static String ownRegistration(byte[] data,String id,String guid,String foundSoFar)throws IOException {
        try {
            JSONObject o=new JSONObject(new String(data,UTF8));
            boolean ours=guid.equals(o.optString("deviceGUID"))&&id.equals(o.optString("shortDeviceId"));
            if(!ours)return foundSoFar;
            if(foundSoFar!=null)throw new IOException("OWN_DEVICE_AMBIGUOUS");
            return id;
        }catch(JSONException ignored){return foundSoFar;}
    }
    public static boolean devicePath(String path){return path.matches(java.util.regex.Pattern.quote(DEVICES)+"/"+SHORT_ID+"\\.ydevice");}
    public static String device(String path,String text,String guid)throws IOException {
        if(!devicePath(path))throw new IOException("DEVICE_PATH_NOT_ALLOWED");
        try {
            JSONObject object=new JSONObject(text);
            String shortId=path.substring(path.lastIndexOf('/')+1,path.length()-8);
            if(!guid.equals(object.getString("deviceGUID"))||!shortId.equals(object.getString("shortDeviceId")))throw new IOException("FOREIGN_DEVICE_METADATA");
            knowledge(object.optString("knowledge",""));return shortId;
        }catch(JSONException error){throw new IOException("INVALID_DEVICE_JSON",error);}
    }
    public static void diff(String path,String text,String guid,String shortId)throws IOException {
        String parent=DATA+"/"+guid+"/";
        if(!path.startsWith(parent)||path.substring(parent.length()).contains("/")||!path.endsWith(".ydiff"))throw new IOException("FOREIGN_DIFF_PATH");
        try {
            JSONObject object=new JSONObject(text);Map<String,Integer> start=knowledge(object.getString("startVersion")),end=knowledge(object.getString("endVersion"));
            if(!guid.equals(object.getString("deviceGUID"))||!DATA_NAME.equals(object.getString("budgetDataGUID")))throw new IOException("FOREIGN_DIFF_IDENTITY");
            int from=start.containsKey(shortId)?start.get(shortId):0,to=end.containsKey(shortId)?end.get(shortId):0;
            if(to<=from||!dominates(end,start))throw new IOException("INVALID_DIFF_KNOWLEDGE");
            if(!path.equals(parent+object.getString("startVersion")+"_"+shortId+"-"+to+".ydiff"))throw new IOException("DIFF_NAME_KNOWLEDGE_MISMATCH");
            JSONArray items=object.getJSONArray("items");if(items.length()==0||items.length()>5000)throw new IOException("INVALID_DIFF_ITEM_COUNT");
            for(int i=0;i<items.length();i++) {
                JSONObject item=items.getJSONObject(i);String version=item.getString("entityVersion");
                if(!version.startsWith(shortId+"-"))throw new IOException("FOREIGN_ENTITY_VERSION");
                int n=Integer.parseInt(version.substring(shortId.length()+1));
                if(n<=from||n>to||item.getString("entityId").length()==0)throw new IOException("ENTITY_VERSION_OUTSIDE_DIFF");
            }
        }catch(JSONException error){throw new IOException("INVALID_DIFF_JSON",error);}
         catch(NumberFormatException error){throw new IOException("INVALID_DIFF_VERSION",error);}
    }
    public static Map<String,Integer> knowledge(String value)throws IOException {
        Map<String,Integer> result=new TreeMap<String,Integer>();if(value.length()==0)return result;
        for(String pair:value.split(",")) {
            if(!pair.matches(SHORT_ID+"-[0-9]+"))throw new IOException("INVALID_KNOWLEDGE");
            int dash=pair.indexOf('-');
            String key=pair.substring(0,dash);
            int n;
            try{n=Integer.parseInt(pair.substring(dash+1));}catch(NumberFormatException e){throw new IOException("INVALID_KNOWLEDGE",e);}
            if(result.put(key,n)!=null)throw new IOException("DUPLICATE_KNOWLEDGE_DEVICE");
        }return result;
    }
    public static boolean dominates(Map<String,Integer> newer,Map<String,Integer> older) {
        for(Map.Entry<String,Integer> e:older.entrySet())if((newer.containsKey(e.getKey())?newer.get(e.getKey()):0)<e.getValue())return false;
        return true;
    }
    public static String sha(byte[] data)throws IOException {
        try{StringBuilder out=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(data))out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}
        catch(Exception error){throw new IOException("HASH_FAILURE",error);}
    }
    static String canonical(Object value)throws JSONException {
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject) {
            JSONObject object=(JSONObject)value;List<String> names=new ArrayList<String>();for(Iterator<String> i=object.keys();i.hasNext();)names.add(i.next());Collections.sort(names);
            StringBuilder out=new StringBuilder("{");for(String name:names)out.append(JSONObject.quote(name)).append(':').append(canonical(object.get(name))).append(',');return out.append('}').toString();
        }
        if(value instanceof JSONArray) {StringBuilder out=new StringBuilder("[");JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++)out.append(canonical(array.get(i))).append(',');return out.append(']').toString();}
        return value instanceof String?JSONObject.quote((String)value):value.toString();
    }
    public static boolean equivalent(String first,String second,boolean diff)throws IOException {
        try {JSONObject a=new JSONObject(first),b=new JSONObject(second);if(diff){a.remove("publishTime");b.remove("publishTime");}return canonical(a).equals(canonical(b));}
        catch(JSONException error){throw new IOException("INVALID_OUTBOX_JSON",error);}
    }
}

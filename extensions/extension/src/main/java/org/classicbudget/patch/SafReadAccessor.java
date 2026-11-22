package org.classicbudget.patch;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;
import com.youneedabudget.ynab.core.cloud.z;
import com.youneedabudget.ynab.core.backend.i;

/** Narrow persisted tree grant; no legacy storage permission and no writes. */
public final class SafReadAccessor implements z {
 static final String AUTHORITY="com.android.externalstorage.documents";
 static final String DOWNLOADS="com.android.providers.downloads.documents";
 // Same read ceiling as WebDAV GET: real Budget.yfull files already exceed 4 MB (5.4 MB seen 2026-11-12).
 static final int LIMIT=64*1024*1024;
 final ContentResolver resolver;final Uri tree;final String rootId;
 static final class Node {String id,name;boolean directory;long modified,size;}
 public static Uri canonical(Uri uri)throws IOException {
  try {
   if(uri==null||!"content".equals(uri.getScheme())||uri.getQuery()!=null||uri.getFragment()!=null)throw new IOException("SAF_INVALID_TREE");
   if(!AUTHORITY.equals(uri.getAuthority())&&!DOWNLOADS.equals(uri.getAuthority()))throw new IOException("SAF_UNSUPPORTED_PROVIDER");
   String id=DocumentsContract.getTreeDocumentId(uri);
   for(String part:id.split("/",-1))if(part.equals(".")||part.equals("..")||part.isEmpty())throw new IOException("SAF_TEST_TREE_ONLY");
   if(id.indexOf('\\')>=0||id.matches("(?s).*[\\x00-\\x1f].*"))throw new IOException("SAF_TEST_TREE_ONLY");
   if(!PlusEdition.TEST_ONLY){
    // Production (080): any folder below the storage root or in Downloads (fallback when Dropbox is gone).
    if(AUTHORITY.equals(uri.getAuthority())?!id.matches("[a-z0-9A-Z-]+:[^/].*"):!id.matches("msd:[0-9]+")&&!id.matches("raw:/storage/emulated/0/Download/.+"))throw new IOException("SAF_FOLDER_NOT_ALLOWED");
   }else if(AUTHORITY.equals(uri.getAuthority())){
    if(!id.matches("primary:(?:[^/]+/)*ClassicBudgetLab_TEST/[a-f0-9]{32}"))throw new IOException("SAF_TEST_TREE_ONLY");
   }else if(!id.matches("msd:[0-9]+")&&!id.matches("raw:/storage/emulated/0/Download/(?:[^/]+/)*ClassicBudgetLab_TEST/[a-f0-9]{32}"))throw new IOException("SAF_TEST_TREE_ONLY");
   List<String> parts=uri.getPathSegments();
   if(!(parts.size()==2&&parts.get(0).equals("tree"))&&!(parts.size()==4&&parts.get(0).equals("tree")&&parts.get(2).equals("document")&&parts.get(3).equals(id)))throw new IOException("SAF_INVALID_TREE");
   // Keep the exact URI offered by the picker for the OS grant. Encoded colon
   // spelling or an equivalent tree/document form is not a different folder.
   return uri;
  }catch(IllegalArgumentException error){throw new IOException("SAF_INVALID_TREE",error);}
 }
 public SafReadAccessor(Context context,Uri uri)throws IOException {
  tree=canonical(uri);rootId=DocumentsContract.getTreeDocumentId(tree);resolver=context.getContentResolver();
  boolean allowed=false;for(UriPermission permission:resolver.getPersistedUriPermissions())if(tree.equals(permission.getUri())&&permission.isReadPermission())allowed=true;
  if(!allowed)throw new IOException("SAF_READ_GRANT_REQUIRED");
  try(Cursor cursor=resolver.query(document(rootId),new String[]{"_display_name","mime_type"},null,null,null)){
   if(cursor==null||!cursor.moveToFirst()||PlusEdition.TEST_ONLY&&!cursor.getString(0).matches("[a-f0-9]{32}")||!DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))throw new IOException(PlusEdition.TEST_ONLY?"SAF_TEST_TREE_ONLY":"SAF_FOLDER_NOT_ALLOWED");
  }catch(SecurityException error){throw new IOException("SAF_READ_GRANT_LOST",error);}
 }
 Uri document(String id)throws IOException {
  if(AUTHORITY.equals(tree.getAuthority())&&!id.equals(rootId)&&!id.startsWith(rootId+"/"))throw new IOException("SAF_DOCUMENT_OUTSIDE_TREE");
  // Downloads document IDs are opaque. Only IDs returned while walking the
  // selected tree are used; the system provider enforces its tree URI grant.
  return DocumentsContract.buildDocumentUriUsingTree(tree,id);
 }
 List<Node> children(Node parent)throws IOException {
  if(!parent.directory)throw new FileNotFoundException("SAF_NOT_DIRECTORY");
  Uri uri=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent.id);List<Node> result=new ArrayList<Node>();Set<String> names=new HashSet<String>();
  String[] columns={"document_id","_display_name","mime_type","last_modified","_size"};
  try(Cursor cursor=resolver.query(uri,columns,null,null,null)){
   if(cursor==null)throw new IOException("SAF_LIST_UNAVAILABLE");
   while(cursor.moveToNext()){
    if(result.size()>=4096)throw new IOException("SAF_LIST_LIMIT");Node node=new Node();node.id=cursor.getString(0);node.name=cursor.getString(1);node.directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));node.modified=cursor.isNull(3)?0:cursor.getLong(3);node.size=cursor.isNull(4)?0:cursor.getLong(4);
    document(node.id);if(node.name==null||node.name.contains("/")||node.name.contains("\\")||node.name.equals(".")||node.name.equals("..")||!names.add(node.name.toLowerCase(Locale.ROOT))||node.id.equals(parent.id)||(AUTHORITY.equals(tree.getAuthority())&&!node.id.equals(parent.id+"/"+node.name)))throw new IOException("SAF_AMBIGUOUS_ENTRY");
    if(node.name.toLowerCase(Locale.ROOT).contains(".sync-conflict-"))throw new IOException("LOCAL_SYNC_CONFLICT_REQUIRES_REVIEW");result.add(node);
   }
  }catch(SecurityException error){throw new IOException("SAF_READ_GRANT_LOST",error);}
  return result;
 }
 Node locate(File file)throws IOException {
  String path=LocalReadAccessor.logical(file);if(path.length()>2048)throw new IOException("SAF_PATH_LIMIT");Node current=new Node();current.id=rootId;current.directory=true;
  if(path.equals("/"))return current;
  // Exact name first, then case-insensitive like Dropbox/Windows (real budgets may have "devices" while the engine asks for "Devices").
  // children() already rejects names that differ only in case, so the fallback is unambiguous.
  for(String part:path.substring(1).split("/")){Node found=null,folded=null;for(Node child:children(current)){if(child.name.equals(part))found=child;else if(child.name.equalsIgnoreCase(part))folded=child;}if(found==null)found=folded;if(found==null)return null;current=found;}
  return current;
 }
 byte[] bytes(File file)throws IOException {
  Node node=locate(file);if(node==null||node.directory)throw new FileNotFoundException("SAF_FILE_MISSING");if(node.size>LIMIT)throw new IOException("SAF_FILE_LIMIT");
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=resolver.openInputStream(document(node.id))){if(in==null)throw new IOException("SAF_STREAM_UNAVAILABLE");byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(out.size()+n>LIMIT)throw new IOException("SAF_FILE_LIMIT");out.write(buffer,0,n);}}
  catch(SecurityException error){throw new IOException("SAF_READ_GRANT_LOST",error);}return out.toByteArray();
 }
 public String a(File file)throws IOException{return new String(bytes(file),java.nio.charset.Charset.forName("UTF-8"));}
 public InputStream b(File file)throws IOException{return new ByteArrayInputStream(bytes(file));}
 public i c(File file)throws IOException{Node node=locate(file);return node==null?null:new i(new File(LocalReadAccessor.logical(file)),i.a.a,node.modified);}
 public List<File> c(File file,String suffix)throws IOException{return list(file,suffix,false);}
 /** Path for diagnostics without the budget name: /<budget>/data…/<rest>. */
 static String redacted(String path){String[] parts=path.split("/",-1);if(parts.length>1&&parts[1].endsWith(".ynab4"))parts[1]="<budget>";StringBuilder b=new StringBuilder();for(int i=1;i<parts.length;i++)b.append("/").append(parts[i]);return b.length()==0?"/":b.toString();}
 List<File> list(File file,String suffix,boolean directories)throws IOException {
  String parent=LocalReadAccessor.logical(file);Node node=locate(file);if(node==null){FileNotFoundException missing=new FileNotFoundException("SAF_DIRECTORY_MISSING");android.util.Log.w("ClassicBudgetPlus","SAF_DIRECTORY_MISSING "+redacted(parent),missing);throw missing;}List<File> result=new ArrayList<File>();
  for(Node child:children(node)){
   if(child.directory!=directories||(suffix!=null&&!child.name.endsWith(suffix)))continue;
   if(parent.equals("/")&&!LocalReadAccessor.KNOWN.contains("/"+child.name)&&!child.name.equals(".ynabSettings.yroot"))continue;
   String path=(parent.equals("/")?"":parent)+"/"+child.name;LocalReadAccessor.logical(new File(path));result.add(new File(path));
  }Collections.sort(result,LocalReadAccessor.ORDER);return result;
 }
 public List<i> d(File file,String suffix)throws IOException{List<i> result=new ArrayList<i>();for(File child:list(file,suffix,true))result.add(c(child));return result;}
 public File a(File file,String text)throws IOException{LocalReadAccessor.logical(file);throw new IOException("SAF_READ_ONLY");}
 public void b(File file,String text)throws IOException{LocalReadAccessor.logical(file);throw new IOException("SAF_READ_ONLY");}
 public void d(File file)throws IOException{LocalReadAccessor.logical(file);throw new IOException("SAF_READ_ONLY");}
}

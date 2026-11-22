package org.classicbudget.patch;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import com.youneedabudget.ynab.core.cloud.z;
import com.youneedabudget.ynab.core.backend.i;

/** Experimental shared-folder reader. No shared writes or registration yet. */
public final class LocalReadAccessor implements z {
 public static final String BUDGET="/Synthetic Budget TEST~C0DEC0DE.ynab4";
 /** Readable budgets; the synthetic one by default, a writer may add the budgets of its folder's yroot. */
 public static final Set<String> KNOWN=Collections.synchronizedSet(new LinkedHashSet<String>(Collections.singleton(BUDGET)));
 static boolean known(String p){synchronized(KNOWN){for(String b:KNOWN)if(p.equals(b)||p.startsWith(b+"/"))return true;}return false;}
 private final File root;
 private static final int LIMIT=64*1024*1024; // as WebDAV GET; real Budget.yfull > 4 MB
 static final Comparator<File> ORDER=new Comparator<File>(){public int compare(File a,File b){return a.getPath().compareTo(b.getPath());}};
 public LocalReadAccessor(File directory)throws IOException {
  String path=directory.getPath();
  if(!path.matches("/storage/emulated/0/ClassicBudgetLab_TEST/[a-f0-9]{32}"))throw new IOException("LOCAL_ROOT_REJECTED");
  root=directory.getCanonicalFile();
  if(!root.getPath().equals(path)||!root.isDirectory())throw new IOException("LOCAL_ROOT_UNAVAILABLE");
 }
 static String logical(File f)throws IOException {
  String p=f.getPath();if(!p.startsWith("/"))p="/"+p;
  if(p.indexOf('\\')>=0||p.indexOf('%')>=0||p.matches("(?s).*[\\x00-\\x1f].*"))throw new IOException("LOCAL_PATH_REJECTED");
  for(String part:p.split("/",-1))if(part.equals(".")||part.equals(".."))throw new IOException("LOCAL_PATH_REJECTED");
  if(!(p.equals("/")||p.equals("/.ynabSettings.yroot")||known(p)))throw new IOException("LOCAL_SYNTHETIC_ONLY");
  return p;
 }
 File physical(File f)throws IOException {
  String p=logical(f);File raw=new File(root,p.equals("/")?"":p.substring(1));File resolved=raw.getCanonicalFile();
  if(!(resolved.equals(root)||resolved.getPath().startsWith(root.getPath()+"/")))throw new IOException("LOCAL_ESCAPE_REJECTED");
  if(!raw.getAbsolutePath().equals(resolved.getPath()))throw new IOException("LOCAL_ALIAS_REJECTED");
  return resolved;
 }
 byte[] bytes(File f)throws IOException {
  File p=physical(f);if(!p.isFile())throw new FileNotFoundException("LOCAL_FILE_MISSING");
  if(p.length()>LIMIT)throw new IOException("LOCAL_FILE_TOO_LARGE");
  ByteArrayOutputStream out=new ByteArrayOutputStream();try(InputStream in=new FileInputStream(p)){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>LIMIT)throw new IOException("LOCAL_FILE_TOO_LARGE");out.write(b,0,n);}}return out.toByteArray();
 }
 public String a(File f)throws IOException{return new String(bytes(f),Charset.forName("UTF-8"));}
 public InputStream b(File f)throws IOException{return new ByteArrayInputStream(bytes(f));}
 // REMOTE denotes the synchronization source, not whether the entry is a directory.
 public i c(File f)throws IOException{File p=physical(f);return p.exists()?new i(new File(logical(f)),i.a.a,p.lastModified()):null;}
 List<File> list(File f,String suffix,boolean directories)throws IOException {
  File parent=physical(f);if(!parent.isDirectory())throw new FileNotFoundException("LOCAL_DIRECTORY_MISSING");
  File[] children=parent.listFiles();if(children==null)throw new IOException("LOCAL_LIST_DENIED");
  if(children.length>4096)throw new IOException("LOCAL_LIST_TOO_LARGE");
  List<File> result=new ArrayList<File>();String prefix=logical(f);if(prefix.equals("/"))prefix="";
  Set<String> names=new HashSet<String>();
  for(File child:children){
   String name=child.getName();if(name.toLowerCase(Locale.ROOT).contains(".sync-conflict-"))throw new IOException("LOCAL_SYNC_CONFLICT_REQUIRES_REVIEW");
   if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("LOCAL_CASE_COLLISION");
   if(suffix!=null&&!name.endsWith(suffix))continue;
   if(prefix.equals("")&&!name.equals(BUDGET.substring(1))&&!name.equals(".ynabSettings.yroot"))continue;
   File logical=new File(prefix+"/"+name);File checked=physical(logical);
   if(checked.isDirectory()==directories&&(directories||checked.isFile()))result.add(logical);
  }Collections.sort(result,ORDER);return result;
 }
 public List<File> c(File f,String suffix)throws IOException{return list(f,suffix,false);}
 public List<i> d(File f,String suffix)throws IOException{List<i> result=new ArrayList<i>();for(File child:list(f,suffix,true))result.add(c(child));return result;}
 public File a(File f,String text)throws IOException{logical(f);throw new IOException("LOCAL_READ_ONLY");}
 public void b(File f,String text)throws IOException{logical(f);throw new IOException("LOCAL_READ_ONLY");}
 public void d(File f)throws IOException{logical(f);throw new IOException("LOCAL_READ_ONLY");}
}

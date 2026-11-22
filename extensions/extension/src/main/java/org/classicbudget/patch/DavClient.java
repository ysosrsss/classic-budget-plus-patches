package org.classicbudget.patch;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.xmlpull.v1.*;

/** Koofr TEST only. The transport deliberately exposes no remote mutation. */
public final class DavClient {
    public static volatile String ROOT = "https://app.koofr.net/dav/Koofr/YNAB4_PLUS_TEST/";
    /** Root = server URL + folder (segments URL-encoded), always ending in "/". */
    public static String join(String server, String folder) throws IOException {
        String s = server.trim(); if (!s.startsWith("https://") || s.contains("?") || s.contains("#") || s.contains("..")) throw new IOException("WEBDAV_HTTPS_URL_REQUIRED");
        if (!s.endsWith("/")) s += "/";
        StringBuilder r = new StringBuilder(s);
        for (String part : folder.trim().split("/")) {
            if (part.length() == 0) continue;
            if (part.equals(".") || part.equals("..") || part.indexOf('\\') >= 0) throw new IOException("WEBDAV_FOLDER_INVALID");
            r.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20").replace("%7E", "~")).append('/');
        }
        return r.toString();
    }
    /**
     * TEST edition: the folder must be inside a YNAB4_PLUS_TEST folder on an https server. Production
     * edition (080): any https folder — Koofr/WebDAV is the fallback when Dropbox is unavailable.
     */
    public static boolean testRoot(String root) {
        if (!root.startsWith("https://") || !root.endsWith("/") || root.contains("..")) return false;
        if (!PlusEdition.TEST_ONLY) return true;
        // Built from parts: the write-test builder rewrites the plain quoted folder name elsewhere.
        String test = "YNAB4_PLUS_" + "TEST";
        for (String part : root.substring(8).split("/")) if (part.equals(test)) return true;
        return false;
    }
    public static synchronized void configure(String root) throws IOException { if (!testRoot(root)) throw new IOException("Only TEST configuration is permitted"); ROOT = root; }
    private static final Charset UTF8 = Charset.forName("UTF-8");
    public interface Audit { void record(String method, String path, int status); }
    public static final class Entry {
        public final String path; public final boolean directory;
        public final long modified;
        Entry(String path, boolean directory, long modified) {
            this.path = path;
            this.directory = directory;
            this.modified = modified;
        }
    }
    private final OkHttpClient http;
    private final String authorization;
    private final Audit audit;
    public DavClient(String username, String password, Audit audit) {
        if (username.length()==0 || password.length()==0) throw new IllegalArgumentException("Missing credentials");
        authorization=Credentials.basic(username,password,UTF8);
        this.audit=audit;
        http=newHttpClient(true);
    }
    static OkHttpClient newHttpClient(boolean retry) {
        return new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .dns(new Dns(){public List<InetAddress> lookup(String hostname)throws UnknownHostException {
                return orderedAddresses(Dns.SYSTEM.lookup(hostname));
            }})
            .retryOnConnectionFailure(retry).authenticator(okhttp3.Authenticator.NONE)
            .callTimeout(60,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS)
            .readTimeout(45,TimeUnit.SECONDS).protocols(Collections.singletonList(Protocol.HTTP_1_1)).build();
    }
    /** Preserve every system DNS result, preferring IPv4 on broken dual-stack links. */
    public static List<InetAddress> orderedAddresses(List<InetAddress> addresses) {
        List<InetAddress> ordered=new ArrayList<InetAddress>();
        for(InetAddress address:addresses)if(address instanceof Inet4Address)ordered.add(address);
        for(InetAddress address:addresses)if(!(address instanceof Inet4Address))ordered.add(address);
        return ordered;
    }
    public static String logical(String path) throws IOException {
        if (path.indexOf('\\')>=0 || path.indexOf(':')>=0) throw new IOException("Invalid logical path");
        for(char c:path.toCharArray()) if(c<32 || c==127) throw new IOException("Control character in path");
        StringBuilder out=new StringBuilder();
        for(String part:path.split("/")) {
            if(part.equals(".") || part.equals("..")) throw new IOException("Path escaped TEST");
            if(part.length()>0) out.append('/').append(part);
        }
        return out.length()==0 ? "/" : out.toString();
    }
    static String url(String path, boolean directory) throws IOException {
        String normalized=logical(path); StringBuilder result=new StringBuilder(ROOT);
        if(!normalized.equals("/")) for(String part:normalized.substring(1).split("/"))
            result.append(URLEncoder.encode(part,"UTF-8").replace("+","%20").replace("%7E","~")).append('/');
        if(!directory && !normalized.equals("/")) result.setLength(result.length()-1);
        return result.toString();
    }
    private byte[] request(String method, String path, boolean directory) throws IOException {
        if(!method.equals("GET") && !method.equals("PROPFIND")) throw new IOException("Read-only transport");
        if(DavProvider.authenticationBlocked()) throw new IOException("Logowanie zatrzymane. Sprawdź hasło aplikacji w ustawieniach Koofr TEST.");
        Request.Builder builder=new Request.Builder().url(url(path,directory))
            .header("Authorization",authorization).header("User-Agent","ClassicBudgetTEST-ReadOnly/1.0")
            .header("Connection","close");
        if(method.equals("PROPFIND")) builder.header("Depth","1").method("PROPFIND",RequestBody.create(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getlastmodified/></d:prop></d:propfind>".getBytes(UTF8), MediaType.get("application/xml; charset=utf-8")));
        else builder.get();
        if(audit!=null)audit.record(method,logical(path),0);
        try(Response response=http.newCall(builder.build()).execute()) {
            if(audit!=null) audit.record(method,logical(path),response.code());
            if(response.code()==401 || response.code()==429) {
                DavProvider.blockAuthentication(response.code());
                throw new IOException(response.code()==401 ? "Nieprawidłowy login lub hasło aplikacji. Automatyczne próby zatrzymane." : "Koofr ograniczył logowanie. Próby zatrzymane; odczekaj przed ponownym połączeniem.");
            }
            if(response.code()==404) throw new FileNotFoundException("Resource not found in TEST");
            if(response.code()!=(method.equals("GET")?200:207)) throw new IOException("WebDAV HTTP "+response.code());
            int limit=method.equals("GET")?64*1024*1024:2*1024*1024;
            ByteArrayOutputStream data=new ByteArrayOutputStream(); byte[] buffer=new byte[32768]; int count;
            InputStream in=response.body().byteStream();
            while((count=in.read(buffer))!=-1) {
                if(data.size()+count>limit) throw new IOException("Response exceeds TEST limit");
                data.write(buffer,0,count);
            }
            return data.toByteArray();
        }catch(IOException error){DavProvider.diagnostic(error);if(audit!=null)audit.record(method,logical(path),-1);throw error;}
    }
    public byte[] read(String path) throws IOException { return request("GET",path,false); }
    public List<Entry> list(String path) throws IOException {
        String parent=logical(path);
        byte[] data=request("PROPFIND",parent,true);
        try {
            XmlPullParserFactory factory=XmlPullParserFactory.newInstance(); factory.setNamespaceAware(true);
            XmlPullParser parser=factory.newPullParser(); parser.setInput(new ByteArrayInputStream(data),null);
            List<Entry> entries=new ArrayList<Entry>();
            String href=null,status=null; boolean directory=false, good=false, rootSeen=false;
            long modified = 0;
            java.text.SimpleDateFormat httpDate = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
            httpDate.setLenient(false);
            for(int event=parser.nextToken();event!=XmlPullParser.END_DOCUMENT;event=parser.nextToken()) {
                if(event==XmlPullParser.DOCDECL) throw new IOException("DTD is not permitted");
                if(event==XmlPullParser.START_TAG && "DAV:".equals(parser.getNamespace())) {
                    String name=parser.getName();
                    if(name.equals("multistatus")) rootSeen=true;
                    if(name.equals("response")) { href=null;directory=false;good=false;modified=0; }
                    if(name.equals("href")) href=parser.nextText();
                    if(name.equals("getlastmodified")) {
                        try { modified = httpDate.parse(parser.nextText()).getTime(); }
                        catch (java.text.ParseException unavailable) { modified = 0; }
                    }
                    if(name.equals("status")) { status=parser.nextText(); if(status.matches("HTTP/[^ ]+ 200 .*"))good=true; }
                    if(name.equals("collection")) directory=true;
                }
                if(event==XmlPullParser.END_TAG && "response".equals(parser.getName()) && "DAV:".equals(parser.getNamespace())) {
                    if(!good || href==null) throw new IOException("DAV resource properties unavailable");
                    URI resolved=new URI(url(parent,true)).resolve(href);
                    // Same origin as the configured root (any https WebDAV server, not only Koofr).
                    URI origin=new URI(ROOT);
                    int port=resolved.getPort()==-1?443:resolved.getPort(), rootPort=origin.getPort()==-1?443:origin.getPort();
                    if(!"https".equals(resolved.getScheme()) || resolved.getHost()==null || !resolved.getHost().equalsIgnoreCase(origin.getHost()) ||
                       port!=rootPort || resolved.getUserInfo()!=null || resolved.getQuery()!=null || resolved.getFragment()!=null)
                        throw new IOException("DAV resource changed origin");
                    String absolute=resolved.getPath(), root=new URI(ROOT).getPath();
                    // Koofr may omit the final slash on the collection's own href.
                    boolean rootSelf=absolute.equals(root.substring(0,root.length()-1));
                    if(!rootSelf && !absolute.startsWith(root)) throw new IOException("DAV resource escaped TEST");
                    String item=rootSelf ? "/" : logical(absolute.substring(root.length()));
                    String prefix=parent.equals("/")?"/":parent+"/";
                    if(!item.equals(parent) && (!item.startsWith(prefix) || item.substring(prefix.length()).contains("/")))
                        throw new IOException("Unexpected DAV Depth 1 resource");
                    entries.add(new Entry(item,directory,modified));
                }
            }
            boolean self=false; for(Entry e:entries) if(e.path.equals(parent)&&e.directory)self=true;
            if(!rootSeen || !self) throw new IOException("DAV directory was not confirmed");
            return entries;
        } catch(XmlPullParserException e) { throw new IOException("Invalid DAV XML",e); }
          catch(URISyntaxException e) { throw new IOException("Invalid DAV resource URI",e); }
    }
}

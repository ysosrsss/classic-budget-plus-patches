package app.classicbudget.extension;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime part of "Dropbox: prefer IPv4".
 *
 * The app's HTTP stack connects to the first address the system resolves (often IPv6) and, on a
 * connect timeout, does not try the next one. Where IPv6 is broken (some VPN/ad-block apps, some
 * networks) every Dropbox request then hangs and fails. This class runs a tiny local CONNECT tunnel
 * on 127.0.0.1 and hands its address to the Dropbox SDK as an HTTP proxy. The tunnel connects to
 * the Dropbox host trying IPv4 addresses first, then IPv6, each with a short timeout.
 * TLS stays end-to-end between the app and Dropbox (the tunnel only copies encrypted bytes), so
 * certificate and host name checks are unchanged. Only CONNECT to port 443 is accepted.
 */
@SuppressWarnings("unused")
public final class Ipv4First {
    private static final int CONNECT_TIMEOUT_MS = 6000;
    private static volatile Proxy proxy;

    /** Replaces the SDK proxy: a direct connection becomes the local tunnel; a configured proxy is kept. */
    public static Proxy proxy(Proxy configured) {
        if (configured != null && configured.type() != Proxy.Type.DIRECT) return configured;
        try {
            return tunnel();
        } catch (IOException e) {
            return configured != null ? configured : Proxy.NO_PROXY;
        }
    }

    private static synchronized Proxy tunnel() throws IOException {
        if (proxy != null) return proxy;
        final ServerSocket server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        Thread accept = new Thread(new Runnable() {
            public void run() {
                while (true) {
                    try {
                        final Socket client = server.accept();
                        start(new Runnable() { public void run() { serve(client); } });
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        }, "ipv4-first-tunnel");
        accept.setDaemon(true);
        accept.start();
        proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        return proxy;
    }

    private static void start(Runnable r) {
        Thread t = new Thread(r, "ipv4-first-conn");
        t.setDaemon(true);
        t.start();
    }

    private static void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setSoTimeout(30000);
            String request = readHeader(client.getInputStream());
            String[] parts = request.split(" ");
            if (parts.length < 2 || !"CONNECT".equals(parts[0])) throw new IOException("only CONNECT");
            int colon = parts[1].lastIndexOf(':');
            String host = parts[1].substring(0, colon);
            int port = Integer.parseInt(parts[1].substring(colon + 1));
            if (port != 443) throw new IOException("only port 443");
            upstream = connect(host, port);
            client.setSoTimeout(0);
            OutputStream out = client.getOutputStream();
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes("ISO-8859-1"));
            out.flush();
            final Socket up = upstream, down = client;
            start(new Runnable() { public void run() { pipe(down, up); } });
            pipe(up, down);
        } catch (Exception e) {
            try {
                client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes("ISO-8859-1"));
            } catch (Exception ignored) { }
            close(client);
            close(upstream);
        }
    }

    /** IPv4 addresses first, then IPv6; the first one that connects wins. */
    static Socket connect(String host, int port) throws IOException {
        InetAddress[] all = InetAddress.getAllByName(host);
        List<InetAddress> ordered = new ArrayList<InetAddress>();
        for (InetAddress a : all) if (a instanceof Inet4Address) ordered.add(a);
        for (InetAddress a : all) if (!(a instanceof Inet4Address)) ordered.add(a);
        IOException last = new IOException("no address for " + host);
        for (InetAddress a : ordered) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(a, port), CONNECT_TIMEOUT_MS);
                return s;
            } catch (IOException e) {
                last = e;
                close(s);
            }
        }
        throw last;
    }

    private static String readHeader(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        String first = null;
        int c, total = 0;
        while ((c = in.read()) != -1) {
            if (++total > 8192) throw new IOException("header too long");
            if (c == '\n') {
                String l = line.toString().trim();
                line.setLength(0);
                if (first == null) first = l;
                else if (l.length() == 0) return first;
            } else {
                line.append((char) c);
            }
        }
        throw new IOException("closed");
    }

    private static void pipe(Socket from, Socket to) {
        byte[] buffer = new byte[16384];
        int n;
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(Socket s) {
        if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}

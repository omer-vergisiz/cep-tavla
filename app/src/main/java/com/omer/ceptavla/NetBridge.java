package com.omer.ceptavla;

import android.app.Activity;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** İki telefonlu oyun için yerel ağ köprüsü: WebSocket aktarıcı sunucu + UDP ile oyun bulma. */
public class NetBridge {
    static final int WS_PORT = 8765;
    static final int UDP_PORT = 8766;
    static final int HTTP_PORT = 8080;
    static final String BEACON = "CEPTAVLA|" + WS_PORT;

    private final Activity act;
    private final WebView web;
    private Relay relay;
    private ServerSocket http;
    private byte[] page;
    private volatile boolean beaconOn = false;
    private volatile boolean discOn = false;
    private DatagramSocket discSock;
    private WifiManager.MulticastLock lock;

    NetBridge(Activity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ---------- kurucu ----------
    @JavascriptInterface
    public synchronized String startHost() {
        stopHost();
        try {
            relay = new Relay(WS_PORT);
            relay.setReuseAddr(true);
            relay.start();
            startHttp();
            beaconOn = true;
            Thread t = new Thread(this::beaconLoop, "tavla-beacon");
            t.setDaemon(true);
            t.start();
            return "{\"ok\":true,\"ips\":" + ipsJson() + "}";
        } catch (Exception e) {
            return "{\"ok\":false,\"err\":\"" + esc(String.valueOf(e.getMessage())) + "\"}";
        }
    }

    @JavascriptInterface
    public synchronized void stopHost() {
        beaconOn = false;
        if (http != null) { try { http.close(); } catch (Exception ignored) {} http = null; }
        if (relay != null) {
            try { relay.stop(300); } catch (Exception ignored) {}
            relay = null;
        }
    }

    @JavascriptInterface
    public String getIps() { return ipsJson(); }

    private void beaconLoop() {
        DatagramSocket s = null;
        try {
            s = new DatagramSocket();
            s.setBroadcast(true);
            byte[] data = BEACON.getBytes(StandardCharsets.UTF_8);
            while (beaconOn) {
                for (InetAddress a : broadcastTargets()) {
                    try { s.send(new DatagramPacket(data, data.length, a, UDP_PORT)); } catch (Exception ignored) {}
                }
                Thread.sleep(1000);
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
    }

    private List<InetAddress> broadcastTargets() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) out.add(ia.getBroadcast());
                }
            }
            out.add(InetAddress.getByName("255.255.255.255"));
        } catch (Exception ignored) {}
        return out;
    }

    // ---------- iPhone ve tarayıcılar için oyun sayfası ----------
    private void startHttp() throws Exception {
        if (page == null) {
            InputStream in = act.getAssets().open("index.html");
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[8192]; int n;
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
            page = bo.toByteArray();
        }
        ServerSocket ss = new ServerSocket();
        ss.setReuseAddress(true);
        ss.bind(new InetSocketAddress(HTTP_PORT));
        http = ss;
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket c = ss.accept();
                    Thread h = new Thread(() -> serve(c));
                    h.setDaemon(true);
                    h.start();
                } catch (Exception e) { if (ss.isClosed()) break; }
            }
        }, "tavla-http");
        t.setDaemon(true);
        t.start();
    }

    private void serve(Socket c) {
        try {
            c.setSoTimeout(5000);
            InputStream in = c.getInputStream();
            StringBuilder req = new StringBuilder();
            int ch, last4 = 0;
            while ((ch = in.read()) != -1 && req.length() < 8192) {
                req.append((char) ch);
                last4 = (last4 << 8 | ch) & 0xFFFFFFFF;
                if (last4 == 0x0D0A0D0A) break;
            }
            String line = req.toString().split("\r\n", 2)[0];
            String path = line.split(" ").length > 1 ? line.split(" ")[1] : "/";
            OutputStream out = c.getOutputStream();
            if (path.equals("/") || path.startsWith("/index.html") || path.startsWith("/?")) {
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: " + page.length
                        + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
                out.write(head.getBytes(StandardCharsets.US_ASCII));
                out.write(page);
            } else {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    // ---------- katılan ----------
    @JavascriptInterface
    public synchronized void startDiscovery() {
        stopDiscovery();
        try {
            WifiManager wm = (WifiManager) act.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                lock = wm.createMulticastLock("tavla");
                lock.setReferenceCounted(false);
                lock.acquire();
            }
        } catch (Exception ignored) {}
        discOn = true;
        Thread t = new Thread(this::discoveryLoop, "tavla-discovery");
        t.setDaemon(true);
        t.start();
    }

    @JavascriptInterface
    public synchronized void stopDiscovery() {
        discOn = false;
        if (discSock != null) { discSock.close(); discSock = null; }
        if (lock != null) { try { lock.release(); } catch (Exception ignored) {} lock = null; }
    }

    private void discoveryLoop() {
        try {
            DatagramSocket s = new DatagramSocket(null);
            s.setReuseAddress(true);
            s.setBroadcast(true);
            s.bind(new InetSocketAddress(UDP_PORT));
            s.setSoTimeout(1000);
            discSock = s;
            byte[] buf = new byte[256];
            while (discOn) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    s.receive(p);
                    String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                    if (msg.startsWith("CEPTAVLA") && p.getAddress() instanceof Inet4Address) {
                        final String ip = p.getAddress().getHostAddress();
                        act.runOnUiThread(() -> web.evaluateJavascript(
                                "window.onTavlaFound&&window.onTavlaFound('" + ip + "')", null));
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    // ---------- yardımcılar ----------
    private static String ipsJson() {
        StringBuilder sb = new StringBuilder("[");
        try {
            List<String> ips = new ArrayList<>();
            List<Integer> pr = new ArrayList<>();
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName();
                if (name.startsWith("rmnet") || name.startsWith("dummy") || name.startsWith("tun") || name.startsWith("ccmni")) continue;
                int p = (name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("softap")) ? 0 : name.startsWith("wlan") ? 1 : 2;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        int q = ip.startsWith("192.168.43.") ? 0 : p;
                        int at = 0;
                        while (at < pr.size() && pr.get(at) <= q) at++;
                        ips.add(at, ip); pr.add(at, q);
                    }
                }
            }
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(ips.get(i)).append('"');
            }
        } catch (Exception ignored) {}
        return sb.append(']').toString();
    }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "'"); }

    void shutdown() {
        stopHost();
        stopDiscovery();
    }

    /** İki bağlantı arasında mesajları aktaran basit sunucu. */
    static class Relay extends WebSocketServer {
        Relay(int port) { super(new InetSocketAddress(port)); }

        private int openCount() {
            int n = 0;
            for (WebSocket c : getConnections()) if (c.isOpen()) n++;
            return n;
        }

        private void sendPeers() {
            broadcast("{\"t\":\"peers\",\"n\":" + openCount() + "}");
        }

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            if (openCount() > 2) { conn.close(1000, "full"); return; }
            sendPeers();
        }

        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) { sendPeers(); }

        @Override public void onMessage(WebSocket conn, String message) {
            for (WebSocket c : getConnections()) if (c != conn && c.isOpen()) c.send(message);
        }

        @Override public void onError(WebSocket conn, Exception ex) {}

        @Override public void onStart() { setConnectionLostTimeout(8); }
    }
}

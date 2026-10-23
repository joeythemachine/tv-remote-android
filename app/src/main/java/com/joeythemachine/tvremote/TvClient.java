package com.joeythemachine.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Talks to a Samsung Tizen TV (2016+) over Wi-Fi: finds it on the network,
 * keeps a remote-control connection open, sends keys, and wakes it up.
 */
public class TvClient {

    public interface Listener {
        void onStatus(String text);
    }

    private static final int PORT = 8001;
    private static final String APP_NAME = "Galaxy Tab Remote";   // name shown on the TV
    private static final String DEFAULT_IP = "192.168.86.35";
    private static final String PLEX_APP_ID = "3201512006963";   // Plex on Samsung Tizen TVs

    private final SharedPreferences prefs;
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService prober;

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)   // no pings: 2016 Samsung TVs don't answer them
            .build();
    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    private volatile String tvIp;
    private volatile WebSocket ws;
    private volatile boolean wsReady;
    private long lastScan;
    private volatile String probeSaid;   // "on" / "off" / null
    private volatile String lastProblem = "TV is off - press Power";   // why the last connection failed

    public TvClient(Context context, Listener listener) {
        this.prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE);
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.listener = listener;
        this.tvIp = prefs.getString("ip", DEFAULT_IP);
    }

    // ---------------------------------------------------------------- public API

    public void start() {
        running = true;
        worker.execute(() -> {
            status("Connecting...");
            if (ensureConnected(true)) {
                probeSaid = "on";
                status("Connected to TV");
            } else {
                probeSaid = "off";
                status(lastProblem);
            }
        });
        prober = Executors.newSingleThreadScheduledExecutor();
        prober.scheduleWithFixedDelay(this::probe, 6, 6, TimeUnit.SECONDS);
    }

    /** The address the app is using for the TV. */
    public String getTvIp() { return tvIp; }

    /** This tablet's own address(es) on the network, for troubleshooting. */
    public String getTabletIp() {
        List<String> mine = new ArrayList<>();
        try {
            Network net = connectivity.getActiveNetwork();
            LinkProperties props = net == null ? null : connectivity.getLinkProperties(net);
            if (props != null) {
                for (LinkAddress la : props.getLinkAddresses()) {
                    if (la.getAddress() instanceof Inet4Address) mine.add(la.getAddress().getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return mine.isEmpty() ? "not on Wi-Fi" : String.join(", ", mine);
    }

    /** Use an address typed in by the person, then connect to it. */
    public void setTvIp(String ip) {
        worker.execute(() -> {
            disconnect();
            tvIp = ip;
            prefs.edit().putString("ip", ip).apply();
            status("Connecting to " + ip + "...");
            if (ensureConnected(true)) { probeSaid = "on"; status("Connected to TV"); }
            else { probeSaid = "off"; status(lastProblem); }
        });
    }

    public void stop() {
        running = false;
        reconnectWanted = false;
        if (prober != null) prober.shutdownNow();
        prober = null;
        worker.execute(this::disconnect);
    }

    public void sendKey(String key) {
        worker.execute(() -> {
            boolean sent = ensureConnected(false) && send(key);
            if (!sent && wsReady) {           // connection had died quietly; try once more
                disconnect();
                sent = ensureConnected(false) && send(key);
            }
            status(sent ? "Connected to TV" : lastProblem);
        });
    }

    /** Opens the Plex app on the TV. */
    public void launchPlex() {
        worker.execute(() -> {
            status("Opening Plex...");
            if (!ensureConnected(false)) { status(lastProblem); return; }
            WebSocket s = ws;
            boolean sent = s != null && s.send("{\"method\":\"ms.channel.emit\",\"params\":{\"event\":\"ed.apps.launch\",\"to\":\"host\","
                    + "\"data\":{\"appId\":\"" + PLEX_APP_ID + "\",\"action_type\":\"DEEP_LINK\"}}}");
            // Some TVs prefer this direct request instead; sending both is harmless.
            try {
                Request req = new Request.Builder()
                        .url("http://" + tvIp + ":" + PORT + "/api/v2/applications/" + PLEX_APP_ID)
                        .post(okhttp3.RequestBody.create(new byte[0], (okhttp3.MediaType) null))
                        .build();
                try (Response r = httpClient.newCall(req).execute()) { if (r.isSuccessful()) sent = true; }
            } catch (Exception ignored) { }
            status(sent ? "Opening Plex" : "Couldn't open Plex - try again");
        });
    }

    /**
     * Types into Plex's on-screen letter grid by steering the highlight with the
     * arrow keys and pressing OK on each character. Plex's grid is:
     *
     *   A B C D E F      row 0
     *   G H I J K L      row 1
     *   M N O P Q R      row 2
     *   S T U V W X      row 3
     *   Y Z 1 2 3 4      row 4
     *   5 6 7 8 9 0      row 5
     *   Delete Space Clear   row 6 (each two columns wide)
     *
     * The highlight must start on A, which is where Plex puts it when search opens.
     */
    public void sendText(String text) {
        worker.execute(() -> {
            if (!ensureConnected(false)) { status(lastProblem); return; }
            status("Typing...");
            int row = 0, col = 0;
            String digits = "1234567890";
            for (char ch : text.toUpperCase().toCharArray()) {
                int r, c;
                if (ch >= 'A' && ch <= 'Z') {
                    r = (ch - 'A') / 6; c = (ch - 'A') % 6;
                } else if (Character.isDigit(ch)) {
                    int d = digits.indexOf(ch) + 26;   // digits continue after Z
                    r = d / 6; c = d % 6;
                } else if (ch == ' ') {
                    // go to the digit row above Space, step down onto Space, press it
                    int target = col <= 2 ? 2 : 3;
                    if (!move(row, col, 5, target)) break;
                    if (!key("KEY_DOWN") || !key("KEY_ENTER")) break;
                    // climb back to the top row and slide to the left edge, so the
                    // position is known again (A)
                    boolean ok = true;
                    for (int k = 0; k < 6 && ok; k++) ok = key("KEY_UP");
                    for (int k = 0; k < 4 && ok; k++) ok = key("KEY_LEFT");
                    if (!ok) break;
                    row = 0; col = 0;
                    continue;
                } else {
                    continue;   // the grid has no punctuation; skip it
                }
                if (!move(row, col, r, c) || !key("KEY_ENTER")) break;
                row = r; col = c;
            }
            status("Typed \"" + text + "\"");
        });
    }

    private boolean move(int fromR, int fromC, int toR, int toC) {
        for (int k = fromC; k < toC; k++) if (!key("KEY_RIGHT")) return false;
        for (int k = fromC; k > toC; k--) if (!key("KEY_LEFT")) return false;
        for (int k = fromR; k < toR; k++) if (!key("KEY_DOWN")) return false;
        for (int k = fromR; k > toR; k--) if (!key("KEY_UP")) return false;
        return true;
    }

    private boolean key(String k) {
        boolean ok = send(k);
        sleep(170);   // let Plex keep up
        return ok;
    }

    public void power() {
        worker.execute(() -> {
            status("Checking TV...");
            boolean reachable = wsReady || resolve(1500, true);
            if (reachable) {
                if (ensureConnected(false) && send("KEY_POWER")) {
                    sleep(500);
                    disconnect();
                    probeSaid = "off";
                    status("TV turned off");
                } else {
                    status(lastProblem);
                }
            } else {
                String mac = prefs.getString("mac", null);
                if (mac != null) {
                    sendWakeOnLan(mac);
                    probeSaid = null;
                    status("Wake signal sent");
                } else {
                    status("TV is off - use the TV's button");
                }
            }
        });
    }

    // ---------------------------------------------------------------- connection

    private boolean ensureConnected(boolean startup) {
        if (wsReady && ws != null) return true;
        disconnect();
        if (!tvAnswers(tvIp)) {
            status("Looking for the TV...");
            if (!resolve(1500, startup)) {
                lastProblem = BUSY;
                reconnectWanted = true;   // keep trying quietly in the background
                return false;
            }
        }
        status("Connecting...");
        String name = Base64.encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String url = "ws://" + tvIp + ":" + PORT + "/api/v2/channels/samsung.remote.control?name=" + name;
        CountDownLatch done = new CountDownLatch(1);
        final String[] outcome = {"noanswer"};
        WebSocket socket = wsClient.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.contains("ms.channel.connect")) { wsReady = true; outcome[0] = "ok"; done.countDown(); }
                else if (text.contains("ms.channel.unauthorized")) { outcome[0] = "denied"; done.countDown(); }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (outcome[0].equals("noanswer")) outcome[0] = "closed";
                lost(webSocket); done.countDown();
            }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) {
                if (outcome[0].equals("noanswer")) outcome[0] = "closed";
                lost(webSocket); done.countDown();
            }
        });
        ws = socket;
        status("Connecting... (accept the prompt on the TV if one appears)");
        try { done.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (!wsReady) {
            disconnect();
            switch (outcome[0]) {
                case "denied":
                    lastProblem = "TV refused this tablet - allow it in the TV's Mobile Device List"; break;
                case "closed":
                    lastProblem = "TV turned the remote away - check its Mobile Device List"; break;
                default:
                    lastProblem = "TV didn't answer the prompt - accept it on the TV, then tap a button";
            }
            return false;
        }
        sleep(150);   // give the TV a moment before the first key
        if (prefs.getString("mac", null) == null) {
            String mac = samsungMac(tvIp);
            if (mac != null && !mac.equals("?")) prefs.edit().putString("mac", mac).apply();
        }
        return true;
    }

    private void lost(WebSocket socket) {
        if (socket == ws) {
            boolean wasUp = wsReady;
            wsReady = false; ws = null;
            if (wasUp && running) {   // the TV dropped us (it does this during video)
                reconnectWanted = true;
                status(BUSY);
            }
        }
    }

    private void disconnect() {
        WebSocket s = ws;
        ws = null;
        wsReady = false;
        if (s != null) s.close(1000, null);
    }

    private boolean send(String key) {
        WebSocket s = ws;
        if (s == null) return false;
        String json = "{\"method\":\"ms.remote.control\",\"params\":{\"Cmd\":\"Click\",\"DataOfCmd\":\""
                + key + "\",\"Option\":\"false\",\"TypeOfRemote\":\"SendRemoteKey\"}}";
        return s.send(json);
    }

    private static final String BUSY = "Can't reach the TV (off, or playing video) - retrying";
    private volatile boolean reconnectWanted;
    private volatile boolean running;
    private long lastRetry;

    // Every few seconds. It never pokes the TV while connected. The connection
    // stays open the whole time the app is on screen (an open connection may
    // survive video playback, when the TV refuses new ones). If the TV has
    // dropped us, it retries politely every 15 seconds until the TV answers.
    private void probe() {
        if (!running || !reconnectWanted || wsReady) return;
        long now = System.currentTimeMillis();
        if (now - lastRetry < 15_000) return;
        lastRetry = now;
        worker.execute(() -> {
            if (wsReady || !reconnectWanted) return;
            if (tvAnswers(tvIp) && ensureConnected(false)) {
                reconnectWanted = false;
                status("Connected to TV");
            } else {
                status(BUSY);
            }
        });
    }

    // ---------------------------------------------------------------- finding the TV

    /** Makes sure tvIp points at the TV; searches the network if its address changed. */
    private boolean resolve(int quickTimeoutMs, boolean force) {
        if (tvAnswers(tvIp)) return true;
        long now = System.currentTimeMillis();
        if (!force && now - lastScan < 20_000) return false;   // don't rescan on every tap
        lastScan = now;
        String found = findTv();
        if (found == null) return false;
        tvIp = found;
        prefs.edit().putString("ip", found).apply();
        return true;
    }

    private String findTv() {
        String savedMac = prefs.getString("mac", null);
        String fallback = null;

        // 1) Ask the network: Samsung TVs answer this "who's there?" broadcast.
        for (String ip : ssdpSearch()) {
            String mac = samsungMac(ip);
            if (mac == null) continue;
            if (savedMac == null || mac.equalsIgnoreCase(savedMac)) return ip;
            if (fallback == null) fallback = ip;
        }

        // 2) Check every address on the local network.
        for (String subnet : localSubnets()) {
            List<String> open = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(128);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + "." + i;
                results.add(pool.submit(() -> tvAnswers(ip) ? ip : null));   // polite request, not a bare connection
            }
            for (Future<String> f : results) {
                try { String ip = f.get(); if (ip != null) open.add(ip); } catch (Exception ignored) { }
            }
            pool.shutdownNow();
            for (String ip : open) {
                String mac = samsungMac(ip);
                if (mac == null) continue;
                if (savedMac == null || mac.equalsIgnoreCase(savedMac)) return ip;   // our TV
                if (fallback == null) fallback = ip;                                // some Samsung TV
            }
        }
        return fallback;
    }

    /** SSDP discovery: returns addresses of devices that say they're a Samsung TV. */
    private List<String> ssdpSearch() {
        Set<String> found = new LinkedHashSet<>();
        WifiManager.MulticastLock lock = null;
        try {
            if (wifi != null) { lock = wifi.createMulticastLock("tvremote"); lock.acquire(); }
            String[] targets = {"urn:samsung.com:device:RemoteControlReceiver:1", "urn:dial-multiscreen-org:service:dial:1"};
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setSoTimeout(400);
                InetAddress group = InetAddress.getByName("239.255.255.250");
                for (int round = 0; round < 2; round++) {
                    for (String st : targets) {
                        String msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: " + st + "\r\n\r\n";
                        byte[] b = msg.getBytes(StandardCharsets.US_ASCII);
                        socket.send(new DatagramPacket(b, b.length, group, 1900));
                    }
                }
                long until = System.currentTimeMillis() + 2500;
                byte[] buf = new byte[2048];
                while (System.currentTimeMillis() < until) {
                    DatagramPacket reply = new DatagramPacket(buf, buf.length);
                    try { socket.receive(reply); } catch (SocketTimeoutException e) { continue; }
                    String body = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.US_ASCII);
                    if (body.toLowerCase().contains("samsung") || body.contains("RemoteControlReceiver") || body.contains("dial-multiscreen")) {
                        found.add(reply.getAddress().getHostAddress());
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
        return new ArrayList<>(found);
    }

    private Set<String> localSubnets() {
        Set<String> subnets = new LinkedHashSet<>();
        try {
            Network net = connectivity.getActiveNetwork();
            LinkProperties props = net == null ? null : connectivity.getLinkProperties(net);
            if (props != null) {
                for (LinkAddress la : props.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        subnets.add(ip.substring(0, ip.lastIndexOf('.')));
                    }
                }
            }
        } catch (Exception ignored) { }
        subnets.add(tvIp.substring(0, tvIp.lastIndexOf('.')));
        return subnets;
    }

    /** Returns the TV's MAC ("?" if it didn't report one) if ip is a Samsung TV, else null. */
    private String samsungMac(String ip) {
        Request req = new Request.Builder().url("http://" + ip + ":" + PORT + "/api/v2/").build();
        try (Response r = httpClient.newCall(req).execute()) {
            String body = r.body() == null ? "" : r.body().string();
            if (!body.contains("Samsung")) return null;
            Matcher m = Pattern.compile("\"wifiMac\"\\s*:\\s*\"([0-9A-Fa-f:\\-]{17})\"").matcher(body);
            return m.find() ? m.group(1).replace('-', ':').toUpperCase() : "?";
        } catch (Exception e) {
            return null;
        }
    }

    /** Polite check: asks the TV for its info page, a normal request it handles and closes. */
    private boolean tvAnswers(String ip) {
        return samsungMac(ip) != null;
    }

    private static boolean portOpen(String ip, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, PORT), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- wake-on-LAN

    private void sendWakeOnLan(String mac) {
        try {
            String[] parts = mac.split("[:\\-]");
            byte[] macBytes = new byte[6];
            for (int i = 0; i < 6; i++) macBytes[i] = (byte) Integer.parseInt(parts[i], 16);
            byte[] packet = new byte[6 + 16 * 6];
            for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
            for (int i = 6; i < packet.length; i += 6) System.arraycopy(macBytes, 0, packet, i, 6);

            String subnetBroadcast = tvIp.substring(0, tvIp.lastIndexOf('.')) + ".255";
            try (DatagramSocket socket = new DatagramSocket()) {
                socket.setBroadcast(true);
                for (String target : new String[]{"255.255.255.255", subnetBroadcast}) {
                    InetAddress addr = InetAddress.getByName(target);
                    for (int port : new int[]{9, 7}) {
                        socket.send(new DatagramPacket(packet, packet.length, addr, port));
                    }
                }
            }
        } catch (Exception ignored) { }
    }

    // ---------------------------------------------------------------- helpers

    private void status(String text) {
        main.post(() -> listener.onStatus(text));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }
}

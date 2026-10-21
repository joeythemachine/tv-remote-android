package com.joeythemachine.tvremote;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
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

    private final SharedPreferences prefs;
    private final ConnectivityManager connectivity;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService prober;

    private final OkHttpClient wsClient = new OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)   // notices a dead connection
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

    public TvClient(Context context, Listener listener) {
        this.prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE);
        this.connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.listener = listener;
        this.tvIp = prefs.getString("ip", DEFAULT_IP);
    }

    // ---------------------------------------------------------------- public API

    public void start() {
        worker.execute(() -> {
            status("Connecting...");
            if (ensureConnected(true)) {
                probeSaid = "on";
                status("Connected to TV");
            } else {
                probeSaid = "off";
                status("TV is off - press Power");
            }
        });
        prober = Executors.newSingleThreadScheduledExecutor();
        prober.scheduleWithFixedDelay(this::probe, 3, 3, TimeUnit.SECONDS);
    }

    public void stop() {
        if (prober != null) prober.shutdownNow();
        prober = null;
        worker.execute(this::disconnect);
    }

    public void sendKey(String key) {
        worker.execute(() -> {
            if (ensureConnected(false) && send(key)) {
                status("Connected to TV");
            } else {
                status("TV is off - press Power");
            }
        });
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
                    status("Couldn't send Power - try again");
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
        if (!portOpen(tvIp, startup ? 1200 : 700)) {
            status("Looking for the TV...");
            if (!resolve(300, false)) return false;
        }
        status("Connecting...");
        String name = Base64.encodeToString(APP_NAME.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String url = "ws://" + tvIp + ":" + PORT + "/api/v2/channels/samsung.remote.control?name=" + name;
        CountDownLatch done = new CountDownLatch(1);
        WebSocket socket = wsClient.newWebSocket(new Request.Builder().url(url).build(), new WebSocketListener() {
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (text.contains("ms.channel.connect")) { wsReady = true; done.countDown(); }
                else if (text.contains("ms.channel.unauthorized")) {
                    status("TV denied access - allow this tablet on the TV");
                    done.countDown();
                }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) { lost(webSocket); done.countDown(); }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response r) { lost(webSocket); done.countDown(); }
        });
        ws = socket;
        status("Connecting... (accept the prompt on the TV if one appears)");
        try { done.await(30, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        if (!wsReady) { disconnect(); return false; }
        sleep(150);   // give the TV a moment before the first key
        if (prefs.getString("mac", null) == null) {
            String mac = samsungMac(tvIp);
            if (mac != null && !mac.equals("?")) prefs.edit().putString("mac", mac).apply();
        }
        return true;
    }

    private void lost(WebSocket socket) {
        if (socket == ws) { wsReady = false; ws = null; }
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

    // Background check so the status stays accurate and dead connections are dropped.
    private void probe() {
        boolean on = portOpen(tvIp, 800);
        String state = on ? "on" : "off";
        if (!on && ws != null) worker.execute(this::disconnect);
        if (!state.equals(probeSaid)) {
            String before = probeSaid;
            probeSaid = state;
            if (on) status("TV is on");
            else if (before != null) status("TV is off - press Power");
        }
    }

    // ---------------------------------------------------------------- finding the TV

    /** Makes sure tvIp points at the TV; searches the network if its address changed. */
    private boolean resolve(int quickTimeoutMs, boolean force) {
        if (portOpen(tvIp, quickTimeoutMs)) return true;
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
        for (String subnet : localSubnets()) {
            List<String> open = new ArrayList<>();
            ExecutorService pool = Executors.newFixedThreadPool(64);
            List<Future<String>> results = new ArrayList<>();
            for (int i = 1; i <= 254; i++) {
                final String ip = subnet + "." + i;
                results.add(pool.submit(() -> portOpen(ip, 500) ? ip : null));
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

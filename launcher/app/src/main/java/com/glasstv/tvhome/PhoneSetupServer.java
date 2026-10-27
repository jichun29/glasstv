package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 手机扫码改设置（2026-10-26，参考 aerial-tv 的 PhoneServer 思路重写）。
 *
 * 电视上显示一个二维码，手机扫码进一个局域网页面，改完点保存，电视上的桌面立刻生效。
 * 解决的是遥控器填字难：天气城市、壁纸挑选这些用电视遥控器做很痛苦。
 *
 * 安全与边界（跟 aerial-tv 有一处刻意的差别）：
 * <ul>
 *   <li>URL 里带 8 位随机 token，token 存在 SharedPreferences 里跨次保留 —— 局域网里别人
 *       扫不到码就猜不到地址。每次进页面都会校验，不带 token 一律 403。</li>
 *   <li><b>不常驻</b>：只有用户主动从「桌面设置 → 用手机改设置」进来才启动，退出页面或
 *       闲置 10 分钟自动停。电视常年停在 Home，常驻等于长期开着一个 HTTP 端口。</li>
 *   <li>只绑定局域网 IPv4，且<b>跳过 VPN 网段</b>。K 的电视走国内直连/国际代理的分流，
 *       此时默认网络可能是指向代理隧道的 198.18.x.x（Clash/Surge 的 fake-ip 段），
 *       手机连不上那个地址 —— 这条是从 aerial-tv 的 issue #667（小米 S55）学来的坑。</li>
 * </ul>
 *
 * 之所以手写 ServerSocket 而不用 NanoHTTPD：只用得上 GET/POST 各一个路径，为了它多背
 * 51KB 依赖不值得，而且手写在 4.4 上更好确认行为（lite 包也要能跑）。
 */
final class PhoneSetupServer {

    private static final String TAG = "PhoneSetup";
    private static final int PORT = 7921;
    /** 闲置多久自动关。手机连上了就不会闲，关掉页面 10 分钟后服务自己退。 */
    private static final long IDLE_MS = 10 * 60 * 1000L;

    private static final String PREF = "phone_setup";
    private static final String TOKEN_KEY = "token";

    private static PhoneSetupServer running;

    private final Context context;
    private final String token;
    private final Callback callback;
    private volatile ServerSocket socket;
    private volatile long lastSeen = System.currentTimeMillis();
    private volatile boolean stopping;

    interface Callback {
        /** 手机上改了设置：主线程回调，重读配置（天气要顺带刷新）。 */
        void onSettingsChanged();
    }

    private PhoneSetupServer(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        this.token = token(context);
    }

    /** token 跨次保留：手机存的收藏夹/历史记录下次还能用，不用重扫。 */
    private static String token(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String t = p.getString(TOKEN_KEY, null);
        if (t != null && t.length() >= 8) return t;
        // 只用小写字母和数字：手机输入法不会把 URL 自动大写，也没有易混字符。
        String alphabet = "abcdefghijkmnpqrstuvwxyz23456789";
        Random random = new Random();
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        t = sb.toString();
        p.edit().putString(TOKEN_KEY, t).commit();
        return t;
    }

    /**
     * 启动（重复调用无副作用）。返回可扫的 URL，没有可用局域网地址时返回 null。
     */
    static String start(Context context, Callback callback) {
        stop();
        String ip = lanAddress();
        if (ip == null) return null;
        PhoneSetupServer server = new PhoneSetupServer(context, callback);
        try {
            ServerSocket ss = new ServerSocket(PORT);
            ss.setReuseAddress(true);
            server.socket = ss;
            running = server;
            server.thread.start();
            return server.url(ip);
        } catch (IOException e) {
            Log.w(TAG, "cannot bind port " + PORT, e);
            return null;
        }
    }

    static void stop() {
        PhoneSetupServer server = running;
        running = null;
        if (server != null) server.shutdown();
    }

    static boolean isRunning() { return running != null; }

    // ---- 地址 -----------------------------------------------------------------

    /**
     * 取一个手机能连上的局域网 IPv4。
     *
     * 顺序：Wi-Fi / 以太网 的站点本地地址优先，其次其它私有网段。VPN、移动数据、回环、
     * 以及 198.18.0.0/15（Clash 的 fake-ip）一律跳过 —— 扫到那些地址手机上打不开。
     */
    static String lanAddress() {
        List<String> preferred = new ArrayList<>();
        List<String> fallback = new ArrayList<>();
        try {
            for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
                 e != null && e.hasMoreElements(); ) {
                NetworkInterface ni = e.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.startsWith("tun") || name.startsWith("tap") || name.startsWith("ppp")
                        || name.startsWith("rmnet") || name.startsWith("clat")
                        || name.contains("vpn") || name.startsWith("dummy")) continue;
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress address = a.nextElement();
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    String ip = address.getHostAddress();
                    if (ip == null || ip.contains(":")) continue;
                    String[] parts = ip.split("\\.");
                    if (parts.length != 4) continue;
                    int second = Integer.parseInt(parts[1]);
                    int first = Integer.parseInt(parts[0]);
                    // 198.18.0.0/15 —— Clash/Surge 的 fake-ip，代理隧道专用，手机连不上。
                    if (first == 198 && (second == 18 || second == 19)) continue;
                    if (!address.isSiteLocalAddress()) continue;   // 只要 10/172.16/192.168
                    boolean wired = name.startsWith("wlan") || name.startsWith("eth")
                            || name.startsWith("ap") || name.startsWith("en");
                    (wired ? preferred : fallback).add(ip);
                }
            }
        } catch (Exception ignored) {
            // 某些定制固件会在这里抛 SecurityException：退回下面用不着地址
        }
        if (!preferred.isEmpty()) return preferred.get(0);
        if (!fallback.isEmpty()) return fallback.get(0);
        return null;
    }

    private String url(String ip) {
        return "http://" + ip + ":" + PORT + "/" + token + "/";
    }

    static String currentUrl() {
        PhoneSetupServer server = running;
        if (server == null) return null;
        String ip = lanAddress();
        return ip == null ? null : server.url(ip);
    }

    // ---- 主循环 ---------------------------------------------------------------

    private final Thread thread = new Thread(this::serve, "phone-setup");

    private void serve() {
        while (!stopping) {
            if (System.currentTimeMillis() - lastSeen > IDLE_MS) break;
            try (Socket client = socket.accept()) {
                client.setSoTimeout(8000);
                handle(client);
            } catch (IOException e) {
                if (stopping) break;
                // accept 超时/单连接异常都不该杀掉服务；下一轮继续
            }
        }
        shutdown();
    }

    private void shutdown() {
        stopping = true;
        ServerSocket ss = socket;
        socket = null;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) { }
        }
        if (running != null && running.socket == null) running = null;
    }

    private void handle(Socket client) {
        lastSeen = System.currentTimeMillis();
        try {
            InputStream in = new BufferedInputStream(client.getInputStream());
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] first = requestLine.split(" ");
            if (first.length < 2) return;
            String method = first[0];
            String rawPath = first[1];

            Map<String, String> headers = new LinkedHashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT),
                        line.substring(colon + 1).trim());
            }
            int length = 0;
            String cl = headers.get("content-length");
            if (cl != null) {
                try { length = Integer.parseInt(cl); } catch (NumberFormatException ignored) { }
            }
            byte[] body = new byte[0];
            if (length > 0 && length < 512 * 1024) {
                body = new byte[length];
                int read = 0;
                while (read < length) {
                    int n = in.read(body, read, length - read);
                    if (n < 0) break;
                    read += n;
                }
            }
            String path = rawPath;
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (!authorized(path)) {
                send(client.getOutputStream(), 403, "text/plain; charset=utf-8", "403".getBytes("UTF-8"));
                return;
            }
            String rest = path.substring(token.length() + 2);   // 去掉 "//<token>/"
            OutputStream out = client.getOutputStream();
            if ("POST".equalsIgnoreCase(method) && rest.startsWith("api/")) {
                String json = Api.apply(context, rest.substring(4),
                        new String(body, "UTF-8"), () -> callback.onSettingsChanged());
                send(out, 200, "application/json; charset=utf-8", json.getBytes("UTF-8"));
            } else if (rest.isEmpty() || rest.equals("index.html")) {
                send(out, 200, "text/html; charset=utf-8", asset("phone/index.html"));
            } else if (rest.startsWith("api/state")) {
                send(out, 200, "application/json; charset=utf-8",
                        Api.state(context).getBytes("UTF-8"));
            } else if (rest.startsWith("thumb/")) {
                int n;
                try { n = Integer.parseInt(rest.substring(6)); } catch (NumberFormatException e) { n = -1; }
                android.graphics.Bitmap bmp = WallpaperController.thumbFor(context, n);
                if (bmp == null) {
                    send(out, 404, "text/plain", "404".getBytes("UTF-8"));
                } else {
                    ByteArrayOutputStream buf = new ByteArrayOutputStream();
                    bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 72, buf);
                    bmp.recycle();
                    send(out, 200, "image/jpeg", buf.toByteArray());
                }
            } else {
                send(out, 404, "text/plain; charset=utf-8", "404".getBytes("UTF-8"));
            }
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    /**
     * 路径必须形如 /&lt;token&gt;/...，token 用等长比较避免逐字符计时差。
     * 不带 token（比如有人直接访问 http://ip:7921/）一律 403。
     */
    private boolean authorized(String path) {
        if (!path.startsWith("/" + token + "/")) return false;
        int diff = 0;
        for (int i = 0; i < token.length(); i++) {
            diff |= path.charAt(i + 1) ^ token.charAt(i);
        }
        return diff == 0;
    }

    private byte[] asset(String name) {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            return "页面资源缺失".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') out.write(c);
            if (out.size() > 8192) break;
        }
        if (c == -1 && out.size() == 0) return null;
        return new String(out.toByteArray(), "UTF-8");
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(code).append(' ').append(code == 200 ? "OK" : "ERR").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: no-store\r\n");
        head.append("Connection: close\r\n\r\n");
        out.write(head.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }

    static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}

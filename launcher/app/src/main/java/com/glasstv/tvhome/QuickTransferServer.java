package com.glasstv.tvhome;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;

/**
 * 快传：手机/电脑浏览器打开电视的局域网地址，上传 APK 推送到电视安装（2026-10-23 K）。
 *
 * 方法一：页面顶部大字显示「http://电视IP:8899」，手机连同一 Wi-Fi 浏览器直接输。
 * 方法二：页面带二维码，手机相机扫码即开。
 *
 * 实现要点（踩过的坑，别改回去）：
 *  - 解析 HTTP 头**不能**用 BufferedReader：它会预读 8KB，把上传文件的开头吞进自己的缓冲，
 *    后面再从 InputStream 读 body 就会丢数据 → APK 装不上。这里用 readLine() 逐字节读到 \n。
 *  - multipart 结尾不能靠「保留 N 字节然后丢掉」：保留量比真实边界长时会吃掉文件末尾几个字节，
 *    APK 直接损坏。正确做法是保留一段窗口，结束时在窗口里**定位**边界，把边界之前的全部写盘。
 *  - 每轮只把超出窗口的部分写盘，绝不逐字节 toByteArray()（1GB 内存的电视会卡死）。
 *  - 必须校验结尾边界，否则传一半断了也会当成功。
 *
 * 算法已用 scripts/QuickTransferParseTest.java 拿真实 multipart 数据（1KB / 1MB / 64MB）验证 md5 一致。
 */
public class QuickTransferServer {

    private static final String TAG = "TvHome.QuickTransfer";
    public static final int PORT = 8899;
    private static final long MAX_SIZE = 512L * 1024 * 1024;

    public interface Listener {
        /** 收到完整 APK（主线程回调）。 */
        void onApk(File apk);
        /** 出错（主线程）。 */
        void onError(String msg);
    }

    private final Context ctx;
    private final Listener listener;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public QuickTransferServer(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        running = true;
        acceptThread = new Thread(() -> {
            try {
                server = new ServerSocket(PORT);
                while (running) {
                    try {
                        final Socket s = server.accept();
                        Thread t = new Thread(() -> handle(s));
                        t.setDaemon(true);
                        t.start();
                    } catch (Exception e) {
                        if (running) Log.w(TAG, "accept", e);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "listen", e);
                notifyError("端口 " + PORT + " 被占用，换个时间再试");
            }
        }, "quick-transfer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (Exception ignored) {}
        server = null;
    }

    public boolean isRunning() { return running && server != null && !server.isClosed(); }

    /** 本机局域网 IPv4，取不到返回 null。 */
    public static String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "localIp", e);
        }
        return null;
    }

    // ---- HTTP ----------------------------------------------------------------

    private void handle(Socket s) {
        try {
            s.setSoTimeout(60000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in, 8192);
            if (requestLine == null) { s.close(); return; }
            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "";
            String path = parts.length > 1 ? parts[1] : "/";

            if ("GET".equals(method) || "HEAD".equals(method)) {
                byte[] body = uploadPage().getBytes("UTF-8");
                writeHeader(out, 200, body.length);
                out.flush();
                if (!"HEAD".equals(method)) out.write(body);
                out.flush();
                s.shutdownOutput();
                s.close();
                return;
            }

            if ("POST".equals(method) && path.startsWith("/upload")) {
                long contentLength = -1;
                String boundary = null;
                String line;
                while ((line = readLine(in, 16384)) != null && !line.isEmpty()) {
                    String l = line.toLowerCase(java.util.Locale.ROOT);
                    if (l.startsWith("content-length:")) {
                        try { contentLength = Long.parseLong(line.substring(15).trim()); }
                        catch (Exception ignored) {}
                    } else if (l.startsWith("content-type:") && l.contains("boundary=")) {
                        int bi = line.indexOf("boundary=");
                        boundary = line.substring(bi + 9).trim().replace("\"", "");
                    }
                }
                if (contentLength <= 0 || contentLength > MAX_SIZE || boundary == null) {
                    writeJson(out, 400, "{\"ok\":false,\"msg\":\"请求无效或文件过大\"}");
                    out.flush();
                    s.close();
                    return;
                }
                File dir = new File(ctx.getCacheDir(), "quicktransfer");
                File apk = readMultipart(in, contentLength, boundary, dir);
                if (apk != null) {
                    writeJson(out, 200, "{\"ok\":true}");
                    out.flush();
                    s.close();
                    notifyApk(apk);
                } else {
                    writeJson(out, 400, "{\"ok\":false,\"msg\":\"只接受 .apk 安装包，或上传中断了\"}");
                    out.flush();
                    s.close();
                }
                return;
            }

            writeJson(out, 404, "{\"ok\":false,\"msg\":\"not found\"}");
            out.flush();
            s.close();
        } catch (Exception e) {
            Log.w(TAG, "handle", e);
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 逐字节读到 \n（跳过 \r）。不能用 BufferedReader：它会预读，把 body 开头吞掉。
     * 返回 null = 流结束且没读到东西。超过 max 视为畸形。
     */
    static String readLine(InputStream in, int max) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > max) return null;
        }
        if (b == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), "UTF-8");
    }

    /**
     * multipart 解析：只认一个文件字段，流式落盘，结尾定位边界并校验。
     * 目录用参数传（不碰 Context），便于用普通 JVM 跑单元测试。
     */
    static File readMultipart(InputStream in, long contentLength, String boundary, File dir) {
        FileOutputStream fos = null;
        File outFile = null;
        long remaining = contentLength;
        try {
            // --- 部分头：找 filename ---
            String filename = null;
            String line;
            while ((line = readLine(in, 16384)) != null) {
                remaining -= line.getBytes("UTF-8").length + 2L;
                if (line.isEmpty()) break;               // 头结束，后面是文件字节
                if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-disposition:")) {
                    int fi = line.indexOf("filename=\"");
                    if (fi >= 0) {
                        int end = line.indexOf('"', fi + 10);
                        if (end > fi) filename = line.substring(fi + 10, end);
                    }
                }
            }
            if (filename == null || !filename.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                skip(in, remaining);
                return null;
            }
            if (remaining <= 0) return null;

            if (dir != null) dir.mkdirs();
            outFile = new File(dir, System.currentTimeMillis() + ".apk");
            fos = new FileOutputStream(outFile);

            // --- 文件内容：窗口滑动，超出窗口的部分写盘 ---
            byte[] tail = ("\r\n--" + boundary).getBytes("ISO-8859-1");
            int hold = tail.length + 8;          // 窗口：足够放下尾部边界 + 后续 "--\r\n"
            byte[] buf = new byte[64 * 1024];
            ByteArrayOutputStream carry = new ByteArrayOutputStream(hold + buf.length);
            while (remaining > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) break;
                remaining -= n;
                carry.write(buf, 0, n);
                int size = carry.size();
                int safe = size - hold;
                if (safe > 0) {
                    byte[] all = carry.toByteArray();
                    fos.write(all, 0, safe);
                    carry.reset();
                    carry.write(all, safe, size - safe);
                }
            }

            // --- 在窗口里定位边界：边界之前的字节才是文件内容 ---
            byte[] win = carry.toByteArray();
            int cut = lastIndexOf(win, tail);
            if (cut < 0) {                        // 没找到边界 = 上传中断
                Log.w(TAG, "no boundary in tail, discard");
                fos.close(); fos = null;
                outFile.delete();
                return null;
            }
            fos.write(win, 0, cut);               // 补写干净的文件尾巴
            fos.flush();
            long len = outFile.length();
            fos.close();
            fos = null;
            if (len < 512) {                      // 太小肯定不是 APK
                outFile.delete();
                return null;
            }
            Log.i(TAG, "got apk " + len + " bytes");
            return outFile;
        } catch (Exception e) {
            Log.w(TAG, "multipart", e);
            try { if (fos != null) fos.close(); } catch (Exception ignored) {}
            if (outFile != null) outFile.delete();
            return null;
        }
    }

    /** 在 hay 里找 needle 最后一次出现的下标，没有返回 -1。 */
    static int lastIndexOf(byte[] hay, byte[] needle) {
        if (hay == null || needle == null || needle.length == 0 || hay.length < needle.length) return -1;
        for (int i = hay.length - needle.length; i >= 0; i--) {
            boolean hit = true;
            for (int j = 0; j < needle.length; j++) {
                if (hay[i + j] != needle[j]) { hit = false; break; }
            }
            if (hit) return i;
        }
        return -1;
    }

    private static void skip(InputStream in, long n) {
        byte[] b = new byte[8192];
        while (n > 0) {
            try {
                int r = in.read(b, 0, (int) Math.min(b.length, n));
                if (r < 0) return;
                n -= r;
            } catch (Exception e) { return; }
        }
    }

    private void writeHeader(OutputStream out, int code, long len) throws Exception {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: text/html; charset=utf-8\r\n"
                + "Content-Length: " + len + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
    }

    private void writeJson(OutputStream out, int code, String json) throws Exception {
        byte[] body = json.getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : "ERR") + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
    }

    private void notifyApk(File f) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> { if (listener != null) listener.onApk(f); });
    }

    private void notifyError(String msg) {
        new android.os.Handler(android.os.Looper.getMainLooper())
                .post(() -> { if (listener != null) listener.onError(msg); });
    }

    /** 上传页：手机浏览器打开。大字、点选/拖放、进度条。 */
    private String uploadPage() {
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>快传到电视</title><style>"
                + "body{margin:0;background:#000;color:#fff;font-family:-apple-system,system-ui,sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;"
                + "min-height:100vh;padding:24px;box-sizing:border-box}"
                + "h1{font-size:24px;font-weight:600;margin:0 0 8px}"
                + "p.sub{color:#888;font-size:14px;margin:0 0 32px;text-align:center}"
                + ".zone{width:100%;max-width:400px;border:2px dashed #444;border-radius:20px;padding:48px 24px;"
                + "text-align:center;font-size:16px;color:#aaa;cursor:pointer;transition:.2s;box-sizing:border-box}"
                + ".zone.over{border-color:#0af;color:#0af;background:rgba(0,170,255,.06)}"
                + "#bar{width:100%;max-width:400px;height:6px;background:#222;border-radius:3px;margin-top:24px;display:none;overflow:hidden}"
                + "#fill{height:100%;width:0;background:#0af;transition:width .2s}"
                + "#msg{margin-top:16px;font-size:14px;min-height:20px;text-align:center}"
                + ".ok{color:#4ade80}.err{color:#f87171}"
                + "</style></head><body><h1>快传到电视</h1>"
                + "<p class=\"sub\">选一个 APK 安装包，电视上会自动弹出安装框</p>"
                + "<div class=\"zone\" id=\"zone\">点这里选择 APK<br>"
                + "<span style=\"font-size:12px;color:#666\">或把文件拖到这里</span></div>"
                + "<input type=\"file\" id=\"file\" accept=\".apk\" style=\"display:none\">"
                + "<div id=\"bar\"><div id=\"fill\"></div></div><div id=\"msg\"></div><script>"
                + "var zone=document.getElementById('zone'),file=document.getElementById('file'),"
                + "bar=document.getElementById('bar'),fill=document.getElementById('fill'),msg=document.getElementById('msg');"
                + "zone.onclick=function(){file.click()};"
                + "zone.ondragover=function(e){e.preventDefault();zone.classList.add('over')};"
                + "zone.ondragleave=function(){zone.classList.remove('over')};"
                + "zone.ondrop=function(e){e.preventDefault();zone.classList.remove('over');"
                + "if(e.dataTransfer.files.length)upload(e.dataTransfer.files[0])};"
                + "file.onchange=function(){if(file.files.length)upload(file.files[0])};"
                + "function upload(f){"
                + "if(!/\\.apk$/i.test(f.name)){msg.className='err';msg.textContent='只支持 .apk 文件';return}"
                + "msg.className='';msg.textContent=f.name+'（'+(f.size/1048576).toFixed(1)+' MB）';"
                + "bar.style.display='block';fill.style.width='0';"
                + "var fd=new FormData();fd.append('file',f);"
                + "var xhr=new XMLHttpRequest();xhr.open('POST','/upload');"
                + "xhr.upload.onprogress=function(e){if(e.lengthComputable)fill.style.width=(e.loaded/e.total*100)+'%'};"
                + "xhr.onload=function(){try{var r=JSON.parse(xhr.responseText);"
                + "if(r.ok){msg.className='ok';msg.textContent='已传到电视，看电视上弹出的安装框'}"
                + "else{msg.className='err';msg.textContent='失败：'+(r.msg||'未知原因')}}"
                + "catch(e){msg.className='err';msg.textContent='失败：电视没响应'}};"
                + "xhr.onerror=function(){msg.className='err';msg.textContent='连接断了，重试一次'};"
                + "xhr.send(fd)}"
                + "</script></body></html>";
    }
}

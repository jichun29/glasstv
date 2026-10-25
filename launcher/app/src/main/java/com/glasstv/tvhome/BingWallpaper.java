package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 必应每日壁纸（移植自 mclhm8182/tvhome-zh 的 Wallpaper.java，MIT，感谢）。
 *
 * 开关打开后，每次桌面启动时若今天还没拉过，就下载必应今日图（1080P 中国区），原子替换
 * sdcard/Android/data/com.glasstv.tvhome/files/bing-wallpaper.jpg——空间恒定一张，不会越攒越多。
 * 下载/校验全部在后台线程；失败当天留着重试（只有成功落盘才记日期）。
 * 电视无外网或必应挂了就静默跳过，绝不影响桌面启动。
 */
final class BingWallpaper {
    private static final String TAG = "TvHome.Bing";
    private static final String PREFS = "bing_wallpaper";
    private static final String KEY_ON = "enabled";
    private static final String KEY_DAY = "last_day";
    static final String FILE_NAME = "bing-wallpaper.jpg";
    private static final String API = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";
    private static final int TIMEOUT = 15000;
    private static final long MAX_BYTES = 4L * 1024 * 1024;

    private final Context ctx;
    private final SharedPreferences prefs;

    BingWallpaper(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return prefs.getBoolean(KEY_ON, false);
    }

    void setEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_ON, on).apply();
    }

    /** 今天的必应图（已下载在本地），没有则 null。 */
    File file() {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return null;
        File f = new File(dir, FILE_NAME);
        return f.isFile() && f.length() > 0 ? f : null;
    }

    /** 删掉本地必应图。 */
    boolean clear() {
        File f = file();
        return f == null || f.delete();
    }

    /**
     * 今天还没拉过就下载。阻塞——必须在后台线程调用。
     * 返回 true = 壁纸文件本次有变化（调用方应刷新显示）。
     */
    boolean refreshIfDue() {
        if (!enabled()) return false;
        String today = today();
        if (today.equals(prefs.getString(KEY_DAY, "")) && file() != null) return false;
        String url = imageUrl();
        if (url == null) return false;
        byte[] bytes = download(url);
        if (bytes == null) return false;
        // 先落盘再记日期：失败当天可重试
        if (writeAtomically(bytes)) {
            prefs.edit().putString(KEY_DAY, today).apply();
            return true;
        }
        return false;
    }

    private String imageUrl() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(API).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            try (InputStream in = c.getInputStream()) {
                String json = readAll(in);
                int at = json.indexOf("\"url\"");
                if (at < 0) return null;
                int from = json.indexOf(':', at) + 1;
                int q1 = json.indexOf('"', from);
                int q2 = json.indexOf('"', q1 + 1);
                if (q1 < 0 || q2 < 0) return null;
                String path = json.substring(q1 + 1, q2);
                return path.startsWith("http") ? path : "https://www.bing.com" + path;
            }
        } catch (Exception e) {
            Log.w(TAG, "lookup failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private byte[] download(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;
            // Content-Length 的 long 版要 API 24；必应图只有几百 KB，int 版足够判断。
            long declared = android.os.Build.VERSION.SDK_INT >= 24 ? c.getContentLengthLong() : c.getContentLength();
            if (declared > MAX_BYTES) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) return null;
                    bos.write(buf, 0, n);
                }
                return bos.toByteArray();
            }
        } catch (Exception e) {
            Log.w(TAG, "download failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** .part 临时文件 + BitmapFactory 校验 + rename 原子替换，半截文件永远不会成为壁纸。 */
    private boolean writeAtomically(byte[] bytes) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) return false;
        File f = new File(dir, FILE_NAME);
        File tmp = new File(dir, FILE_NAME + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(tmp.getPath(), o);
        if (o.outWidth <= 0) {
            tmp.delete();
            return false;
        }
        if (f.exists() && !f.delete()) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f);
    }

    private static String today() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return c.get(java.util.Calendar.YEAR) + "-" + (c.get(java.util.Calendar.MONTH) + 1)
                + "-" + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    private static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toString(StandardCharsets.UTF_8.name());
    }
}

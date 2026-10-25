package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 壁纸屏保（2026-10-23 K）：必应每日图。
 *
 * 必应一天只给一张（idx=0），所以要「多久换一次」短于一天，就得一次拿一批：
 * 用 HPImageArchive 的 n=8 取最近 8 天的图，全部下到本地 pool，然后按间隔在池子里轮换。
 *
 * 池目录：externalFilesDir/bing-shots/{0..7}.jpg
 * 设置（prefs = launcher_settings）：
 *   screensaver_interval_min  换图间隔分钟：0=每次进屏保都换，30/60/1440（1天）
 *   screensaver_pool_day      池子是哪天抓的（跨天就重抓）
 *   screensaver_index         当前用第几张
 *   screensaver_last_change   上次换图时间戳
 */
final class BingScreensaver {

    private static final String TAG = "TvHome.BingShots";
    private static final String API =
            "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=zh-CN";
    private static final int TIMEOUT = 15000;
    private static final int MAX_IMAGES = 8;

    private BingScreensaver() {}

    static File dir(Context ctx) {
        File base = ctx.getExternalFilesDir(null);
        return base == null ? new File(ctx.getFilesDir(), "bing-shots") : new File(base, "bing-shots");
    }

    /** 本地已有的池子，按文件里的序号数字排序（0.jpg,1.jpg,...,10.jpg；字符串排序会把 10 排到 2 前面）。 */
    static List<File> pool(Context ctx) {
        List<File> out = new ArrayList<>();
        File[] files = dir(ctx).listFiles();
        if (files == null) return out;
        for (File f : files) {
            if (f.isFile() && f.length() > 0 && f.getName().endsWith(".jpg")) out.add(f);
        }
        out.sort((a, b) -> Integer.compare(indexOf(a), indexOf(b)));
        return out;
    }

    /** "3.jpg" → 3，解析不了给个大数排最后。 */
    private static int indexOf(File f) {
        try {
            return Integer.parseInt(f.getName().replace(".jpg", ""));
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    /** 池子是不是今天的（跨天要重抓）。 */
    private static boolean poolFresh(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return today().equals(p.getString("screensaver_pool_day", ""))
                && pool(ctx).size() >= 2;
    }

    /**
     * 池子是「哪一批必应图」的标记。必应一天一张，跨天要重抓最新的 8 张，
     * 否则第二天起的图永远是昨天那批。
     */
    private static String today() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /**
     * 确保池子可用。阻塞，必须在后台线程调用。
     * 返回 true = 这次真的下载了。
     */
    static boolean ensurePool(Context ctx) {
        if (poolFresh(ctx)) return false;
        List<String> urls = urls();
        if (urls.isEmpty()) return false;
        File d = dir(ctx);
        if (!d.mkdirs() && !d.isDirectory()) return false;
        int got = 0;
        for (int i = 0; i < urls.size() && i < MAX_IMAGES; i++) {
            File out = new File(d, i + ".jpg");
            if (out.isFile() && out.length() > 1024) { got++; continue; }
            byte[] bytes = download(urls.get(i));
            if (bytes == null || bytes.length < 1024) continue;
            if (write(out, bytes)) got++;
        }
        if (got < 2) return false;
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putString("screensaver_pool_day", today()).apply();
        Log.i(TAG, "pool ready, " + got + " images");
        return true;
    }

    /** 进屏保时挑一张。按间隔决定换不换；池子空返回 null。 */
    static File pickForNow(Context ctx) {
        List<File> list = pool(ctx);
        if (list.isEmpty()) return null;
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        int interval = p.getInt("screensaver_interval_min", 60);
        long last = p.getLong("screensaver_last_change", 0L);
        int index = p.getInt("screensaver_index", 0);
        long now = System.currentTimeMillis();
        boolean advance;
        if (interval <= 0) {
            advance = true;                                  // 每次进屏保都换
        } else {
            advance = last == 0L || now < last || now - last >= interval * 60_000L;
        }
        if (advance) {
            index = (index + 1) % list.size();
            p.edit().putInt("screensaver_index", index)
                    .putLong("screensaver_last_change", now).apply();
        }
        return list.get(Math.abs(index) % list.size());
    }

    /** 解码成屏幕能用的位图（按 4 倍下采样兜底 4K，避免 1GB 内存的电视 OOM）。 */
    static Bitmap decode(File f, int targetW, int targetH) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) {
                sample *= 2;
            }
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        } catch (Throwable t) {
            Log.w(TAG, "decode", t);
            return null;
        }
    }

    // ---- 网络 ----------------------------------------------------------------

    /** 从必应接口拿 8 个图片地址（先 http 再 https，小米老固件对 https 证书挑）。
     *  同时改写成 UHD（3840×2160）：1080p 拉伸到 4K 会糊，UHD 单张约 3.5MB 可接受。 */
    private static List<String> urls() {
        List<String> out = new ArrayList<>();
        String json = null;
        for (String api : new String[]{API, API.replace("https://", "http://")}) {
            json = get(api);
            if (json != null) break;
        }
        if (json == null) return out;
        int at = 0;
        while (true) {
            int u = json.indexOf("\"url\":\"", at);
            if (u < 0) break;
            int from = u + 7;
            int to = json.indexOf('"', from);
            if (to < 0) break;
            String path = json.substring(from, to);
            path = path.replace("_1920x1080.jpg", "_UHD.jpg");
            if (!path.startsWith("http")) path = "https://www.bing.com" + path;
            out.add(path);
            at = to + 1;
        }
        return out;
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(TIMEOUT);
            c.setReadTimeout(TIMEOUT);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                return new String(b.toByteArray(), "UTF-8");
            }
        } catch (Exception e) {
            Log.w(TAG, "get failed: " + e.getMessage());
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] download(String url) {
        for (String u : new String[]{url, url.replace("https://", "http://")}) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(TIMEOUT);
                c.setReadTimeout(20000);
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV)");
                if (c.getResponseCode() != 200) continue;
                try (InputStream in = c.getInputStream()) {
                    java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        b.write(buf, 0, n);
                        if (b.size() > 12 * 1024 * 1024) break;   // 单张上限 12MB（UHD 约 3.5MB）
                    }
                    return b.toByteArray();
                }
            } catch (Exception e) {
                Log.w(TAG, "download failed: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    private static boolean write(File f, byte[] bytes) {
        File tmp = new File(f.getAbsolutePath() + ".part");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(f) || (f.delete() && tmp.renameTo(f));
    }

    static void clear(Context ctx) {
        for (File f : pool(ctx)) f.delete();
        ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .remove("screensaver_pool_day").apply();
    }
}

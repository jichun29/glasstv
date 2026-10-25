package com.glasstv.tvhome;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * 自动清理内存（2026-10-23 K）：按间隔清后台，白名单里的 App 不动。
 *
 * 触发时机（K 要求「时间等相关设置优化好」）：
 *  1. 回到桌面时检查——距上次清理超过间隔就清（桌面进程被杀重启也不会丢计时，last 存在 prefs 里）
 *  2. 桌面在前台时，到点用 Handler 清一次
 *  3. 电视待机（SCREEN_OFF）时清一次——这时最安全，不会打断正在看的东西
 * 清理时跳过：桌面自己、当前前台 App、白名单 App。
 *
 * 设置项（prefs = launcher_settings）：
 *  auto_clean_enabled       开关，默认关
 *  auto_clean_interval_min  间隔，30/60/180，默认 60
 *  auto_clean_whitelist     白名单，逗号分隔包名
 *  auto_clean_last          上次清理时间戳（毫秒）
 */
public class MemoryCleaner {

    private static final String TAG = "TvHome.Cleaner";
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static Context appCtx;

    /** MainActivity onCreate 里调一次，供定时器取 context。 */
    public static void init(Context ctx) { appCtx = ctx.getApplicationContext(); }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            Context ctx = appCtx;
            if (ctx == null) return;
            maybeClean(ctx, "定时");
            H.postDelayed(this, 60_000L);   // 每分钟检查一次，到点才真清
        }
    };

    /** 设置变化后调用：开=开始检查，关=停止。 */
    public static void reschedule(Context ctx) {
        H.removeCallbacks(TICK);
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (p.getBoolean("auto_clean_enabled", false)) {
            H.postDelayed(TICK, 60_000L);
            Log.i(TAG, "auto clean scheduled");
        }
    }

    /** 到点才清。返回是否真清了。 */
    public static boolean maybeClean(Context ctx, String reason) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        if (!p.getBoolean("auto_clean_enabled", false)) return false;
        long interval = p.getInt("auto_clean_interval_min", 60) * 60_000L;
        long last = p.getLong("auto_clean_last", 0L);
        long now = System.currentTimeMillis();
        // 待机清理不看间隔（睡前清一次最合适），其余按间隔
        if (!"待机".equals(reason) && now - last < interval) return false;
        int n = cleanNow(ctx);
        p.edit().putLong("auto_clean_last", now).apply();
        Log.i(TAG, "cleaned(" + reason + ") " + n);
        return true;
    }

    /** 立即清一次。返回杀掉的包数（粗略）。 */
    public static int cleanNow(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return 0;
            Set<String> keep = whitelist(ctx);
            keep.add(ctx.getPackageName());          // 桌面自己不杀
            String front = null;
            try {
                android.app.ActivityManager.RunningTaskInfo t = null;
                java.util.List<ActivityManager.RunningTaskInfo> tasks = am.getRunningTasks(1);
                if (tasks != null && !tasks.isEmpty()) t = tasks.get(0);
                if (t != null && t.topActivity != null) front = t.topActivity.getPackageName();
            } catch (Exception ignored) {}
            if (front != null) keep.add(front);      // 正在用的 App 不杀

            int n = 0;
            for (ActivityManager.RunningAppProcessInfo pi : am.getRunningAppProcesses()) {
                if (pi.pkgList == null) continue;
                for (String pkg : pi.pkgList) {
                    if (keep.contains(pkg)) continue;
                    try { am.killBackgroundProcesses(pkg); n++; } catch (Exception ignored) {}
                }
            }
            return n;
        } catch (Exception e) {
            Log.w(TAG, "clean", e);
            return 0;
        }
    }

    public static Set<String> whitelist(Context ctx) {
        String raw = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .getString("auto_clean_whitelist", "");
        Set<String> out = new HashSet<>();
        if (raw != null) for (String s : raw.split(",")) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public static boolean inWhitelist(Context ctx, String pkg) {
        return whitelist(ctx).contains(pkg);
    }

    public static void toggleWhitelist(Context ctx, String pkg) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        Set<String> set = whitelist(ctx);
        if (!set.remove(pkg)) set.add(pkg);
        StringBuilder sb = new StringBuilder();
        for (String s : set) { if (sb.length() > 0) sb.append(','); sb.append(s); }
        p.edit().putString("auto_clean_whitelist", sb.toString()).apply();
    }
}

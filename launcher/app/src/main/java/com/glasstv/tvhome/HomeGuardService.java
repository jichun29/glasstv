package com.glasstv.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 桌面守护（移植自 pengchujin/aerial-tv 的 HomeGuardService，Java 重写）。
 *
 * 小米待机/后台清理会把第三方桌面杀掉，原生桌面（PatchWall）就露出来。这个无障碍服务盯着
 * 窗口切换：一旦原生桌面跑到前台、且不是用户故意打开的，就把 GlassTV 拉回前台。
 *
 * 只做一件事：把桌面拉回来。不读屏幕内容，不拦按键（Home 键交给系统，本来默认桌面就是我们）。
 *
 * 启用（adb，一次性）：
 *   settings put secure enabled_accessibility_services com.glasstv.tvhome/.HomeGuardService
 *   settings put secure accessibility_enabled 1
 *   appops set com.glasstv.tvhome SYSTEM_ALERT_WINDOW allow   (Android 10+ 后台拉起前台需要)
 */
public class HomeGuardService extends AccessibilityService {

    private static final String TAG = "HomeGuard";
    /** 开机后这段时间内，守护积极接管。 */
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;
    /** 窗口事件后稍等再动手：开机时 HDMI-CEC 信号源可能正在切换，急拉会顶掉。 */
    private static final long SETTLE_MS = 1500L;

    /** 原生桌面包名 → 它的 home activity 类名集合。每次绑定时刷新。 */
    private Map<String, Set<String>> stockHomes = new HashMap<>();
    /** 用户从网格里故意点开原生桌面后，这段时间内不拉回。 */
    private long allowStockUntil = 0L;
    /** 最近一个跑到前台的窗口（包名, 类名）。 */
    private String[] lastWindow; // [pkg, cls]
    private long screenOnAt = Long.MIN_VALUE / 2;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private String takeOverReason = "";
    private boolean takeOverOnWake = false;
    private final Runnable takeOver = () -> takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake);

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            // 待机时固件把原生桌面放回前台，亮屏时没有窗口事件。亮屏后稳一下看一眼。
            screenOnAt = SystemClock.elapsedRealtime();
            takeOverOnceSettled("screen on", true);
        }
    };

    @Override
    protected void onServiceConnected() {
        instance = this;
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        refreshStockHomes();
        PowerManager pm = getSystemService(PowerManager.class);
        boolean interactive = pm == null || pm.isInteractive();
        // 刚绑定（开机后被杀又拉活）：如果原生桌面在前台，接管。升级覆盖安装杀了我们时不抢——
        // 用户可能正在看别的 App。
        boolean justUpdated = justUpdated();
        boolean diedInFront = wasHomeInFront()
                && SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000L;
        if (interactive && !justUpdated
                && (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || diedInFront)) {
            takeOverOnceSettled(diedInFront ? "died in front" : "bound after boot", !diedInFront);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgCs = event.getPackageName();
        CharSequence clsCs = event.getClassName();
        if (pkgCs == null || clsCs == null) return;
        String pkg = pkgCs.toString();
        String cls = clsCs.toString();
        // 只有真正的 activity 才算「前台」：弹窗/浮层也会发这个事件（TCL 语音助手开机就弹）。
        if (!isActivity(pkg, cls)) return;
        String before = lastWindow != null ? lastWindow[0] : null;
        lastWindow = new String[]{pkg, cls};

        if (stockHomes.containsKey(pkg)) {
            if (SystemClock.uptimeMillis() < allowStockUntil) return;
            // 只认原生桌面的 home 主界面，它的详情页/弹窗不抢。
            Set<String> homes = stockHomes.get(pkg);
            if (homes != null && !homes.contains(cls)) return;
            Log.i(TAG, "stock home " + pkg + " in front; bringing GlassTV back");
            goHome(SystemClock.elapsedRealtime() - screenOnAt < 15_000L);
            return;
        }
        // 离开原生桌面去别的 App = 用户主动操作，结束放行。
        if (SystemClock.uptimeMillis() < allowStockUntil && !pkg.equals(getPackageName()) && isApp(pkg)) {
            allowStockUntil = 0L;
        }
    }

    private void takeOverOnceSettled(String reason, boolean woke) {
        takeOverReason = reason;
        takeOverOnWake = woke;
        handler.removeCallbacks(takeOver);
        handler.postDelayed(takeOver, SETTLE_MS);
    }

    private void takeOverIfStockHomeInFront(String reason, boolean woke) {
        String[] last = lastWindow;
        boolean stockInFront = last == null || stockHomes.containsKey(last[0]);
        if (!stockInFront) {
            Log.i(TAG, reason + " with " + last[0] + " in front; leaving it");
            return;
        }
        if (SystemClock.uptimeMillis() < allowStockUntil) return;
        Log.i(TAG, reason + " with stock launcher in front; taking Home back");
        goHome(woke);
    }

    private void goHome(boolean woke) {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check");
            return;
        }
        // 不带 CATEGORY_HOME：固件不认我们当 Home 时，HOME 类别会开第二个任务，丢焦点。
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "could not bring GlassTV to front", e);
        }
    }

    private final Map<String, Boolean> activityCache = new HashMap<>();

    private boolean isActivity(String pkg, String cls) {
        String key = pkg + "/" + cls;
        Boolean cached = activityCache.get(key);
        if (cached != null) return cached;
        boolean ok;
        try {
            getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            ok = true;
        } catch (Exception e) {
            ok = false;
        }
        activityCache.put(key, ok);
        return ok;
    }

    /** 有 launcher 入口的包 = 真 App，不是系统浮层。 */
    private boolean isApp(String pkg) {
        return getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null
                || getPackageManager().getLaunchIntentForPackage(pkg) != null;
    }

    private void refreshStockHomes() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> all = getPackageManager().queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY);
        Map<String, Set<String>> homes = new HashMap<>();
        for (ResolveInfo ri : all) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            homes.computeIfAbsent(pkg, k -> new HashSet<>()).add(ri.activityInfo.name);
        }
        stockHomes = homes;
    }

    private boolean justUpdated() {
        try {
            return System.currentTimeMillis()
                    - getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime < 2 * 60_000L;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean wasHomeInFront() {
        return getSharedPreferences("guard", MODE_PRIVATE).getBoolean("home_in_front", false);
    }

    /** MainActivity onResume/onPause 时记：我们的桌面是否在前台。死了又活时能分清是被杀还是用户切走的。 */
    public static void setHomeInFront(Context ctx, boolean inFront) {
        ctx.getSharedPreferences("guard", MODE_PRIVATE).edit().putBoolean("home_in_front", inFront).apply();
    }

    /** 用户从网格故意打开原生桌面时调用：一小时内不拉回。 */
    public static void allowStockLauncher() {
        if (instance != null) instance.allowStockUntil = SystemClock.uptimeMillis() + 60 * 60 * 1000L;
    }

    @Override
    public void onDestroy() {
        try { unregisterReceiver(screenOn); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onInterrupt() {}

    private static volatile HomeGuardService instance;

    public static boolean isRunning() {
        return instance != null;
    }

    /** 开机完成后 BootReceiver 再确认一次；守护没在跑返回 false。 */
    public static boolean onBootCompleted(Context ctx) {
        HomeGuardService s = instance;
        if (s == null) return false;
        s.handler.post(() -> {
            if (s.lastWindow != null && s.lastWindow[0].equals(s.getPackageName())) return;
            PowerManager pm = s.getSystemService(PowerManager.class);
            if (pm == null || pm.isInteractive()) s.takeOverOnceSettled("boot completed", true);
        });
        return true;
    }
}

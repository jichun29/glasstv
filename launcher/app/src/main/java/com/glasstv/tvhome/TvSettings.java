package com.glasstv.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 打开电视自己的设置（2026-10-23 K，2026-10-26 改为运行期自找）。
 *
 * 国产固件普遍不注册 AOSP 的入口点，已知两例：
 * <ul>
 *   <li>小米 MiTV：{@code android.settings.SETTINGS} / {@code WIFI_SETTINGS} 解析不到，
 *       平台 {@code com.android.tv.settings} 被禁用。</li>
 *   <li>雷鸟 / TCL（K 的朋友 2026-10-26 反馈）：三个已知入口全不存在，设置是
 *       {@code com.tcl.setting} 那套，不在 AOSP 体系里 —— 点「系统设置」直接弹
 *       「此电视不支持该功能」。</li>
 * </ul>
 *
 * 所以改成三段式，不再只靠写死包名：
 * <ol>
 *   <li>先试已知厂商组件（下面那张表，命中就直接进）；</li>
 *   <li>运行期自找：用 PackageManager 把能响应 {@code android.settings.SETTINGS} 的
 *       Activity 全列出来 —— 很多国产固件换了包名但仍然注册了这个 action；</li>
 *   <li>还找不到，就在厂商包（tcl / ffalcon / hisense …）里搜类名带 setting 的 Activity，
 *       这些固件通常只给自己开 entry，不注册标准 action。</li>
 * </ol>
 * 上一次找到的入口记在 SharedPreferences 里，下次直接用，省一次全量扫描。
 */
final class TvSettings {

    private static final String TAG = "TvSettings";
    private static final String PREF = "tv_settings";
    private static final String KEY_SETTINGS = "settings_component";
    private static final String KEY_WIFI = "wifi_component";
    private static final String KEY_SOURCE = "source_component";

    private static final String XIAOMI = "com.xiaomi.mitv.settings";
    private static final String AOSP_TV = "com.android.tv.settings";

    /** 已知厂商入口，按命中概率排序；不存在的会静默跳过。 */
    private static final ComponentName[] KNOWN_SETTINGS = {
            new ComponentName(XIAOMI, XIAOMI + ".entry.MainActivity"),
            new ComponentName(AOSP_TV, AOSP_TV + ".MainSettings"),
            // 雷鸟 / TCL（Android TV 与国内 T-UI 两种固件都见过这几个）
            new ComponentName("com.tcl.setting", "com.tcl.setting.MainActivity"),
            new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"),
            new ComponentName("com.tcl.settings", "com.tcl.settings.SettingsActivity"),
            new ComponentName("com.ffalcon.settings", "com.ffalcon.settings.MainActivity"),
            // 海信 / 创维 / 长虹
            new ComponentName("com.hisense.settings", "com.hisense.settings.SettingsActivity"),
            new ComponentName("com.skyworth.settings", "com.skyworth.settings.MainActivity"),
            new ComponentName("com.changhong.settings", "com.changhong.settings.MainActivity"),
    };

    private TvSettings() {}

    static boolean openSettings(Context ctx) {
        return open(ctx, KEY_SETTINGS, "setting");
    }

    static boolean openWifi(Context ctx) {
        return open(ctx, KEY_WIFI, "wifi");
    }

    static boolean openAbout(Context ctx) {
        return start(ctx,
                new ComponentName(XIAOMI, XIAOMI + ".entry.AboutActivity"),
                new ComponentName(AOSP_TV, AOSP_TV + ".about.AboutActivity"),
                new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
    }

    static boolean openAppInfo(Context ctx, String pkg) {
        return start(ctx,
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + pkg)));
    }

    /**
     * 选源面板（2026-10-26）：TvInputManager 一个源都不报时的兜底。
     *
     * 雷鸟/TCL 这类固件把 HDMI 切换做在自己那套里，既不注册 TvInputManager 的 input，
     * 也没配 source_panel_service —— 原来的行为就是弹「不支持」，用户完全没法切。
     *
     * 这里按「已知厂商组件 → 厂商包内类名搜索」找一次，找到就直达；找不到返回 false
     * 由调用方提示。结果同样记下来，下次直接进。
     */
    static boolean openSourcePanel(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String remembered = prefs.getString(KEY_SOURCE, null);
        if (remembered != null) {
            ComponentName c = ComponentName.unflattenFromString(remembered);
            if (c != null && launch(ctx, new Intent().setComponent(c))) return true;
        }
        ComponentName[] known = {
                new ComponentName("com.tcl.tv", "com.tcl.tv.settings.SourceActivity"),
                new ComponentName("com.tcl.tvinput", "com.tcl.tvinput.SourceSelectActivity"),
                new ComponentName("com.ffalcon.tvinput", "com.ffalcon.tvinput.SourceActivity"),
                new ComponentName("com.android.tv.settings", "com.android.tv.settings.inputs.InputsActivity"),
        };
        for (ComponentName c : known) {
            if (launch(ctx, new Intent().setComponent(c))) {
                prefs.edit().putString(KEY_SOURCE, c.flattenToString()).apply();
                return true;
            }
        }
        ComponentName found = findInVendorPackages(ctx, "source");
        if (found != null && launch(ctx, new Intent().setComponent(found))) {
            prefs.edit().putString(KEY_SOURCE, found.flattenToString()).apply();
            return true;
        }
        return false;
    }

    /**
     * @param hint 在厂商包里搜类名用的关键字（setting / wifi / source）
     */
    private static boolean open(Context ctx, String prefKey, String hint) {
        SharedPreferences prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String action = KEY_SETTINGS.equals(prefKey)
                ? Settings.ACTION_SETTINGS : Settings.ACTION_WIFI_SETTINGS;

        // 0. 上次成功过的组件：先按它试一次（固件升级后可能失效，失效就往下走）
        String remembered = prefs.getString(prefKey, null);
        if (remembered != null) {
            ComponentName c = ComponentName.unflattenFromString(remembered);
            if (c != null && launch(ctx, new Intent().setComponent(c))) return true;
        }

        // 1. 已知厂商组件 + 标准 action
        List<Object> candidates = new ArrayList<>();
        if (KEY_SETTINGS.equals(prefKey)) {
            for (ComponentName c : KNOWN_SETTINGS) candidates.add(c);
        }
        candidates.add(new Intent(action));
        for (Object candidate : candidates) {
            Intent intent = candidate instanceof Intent
                    ? new Intent((Intent) candidate)
                    : new Intent().setComponent((ComponentName) candidate);
            if (launch(ctx, intent)) {
                remember(ctx, prefs, prefKey, intent);
                return true;
            }
        }

        // 2. 运行期自找：任何响应标准 action 的 Activity
        ResolveInfo resolved = null;
        try {
            resolved = ctx.getPackageManager()
                    .resolveActivity(new Intent(action), PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException ignored) { }
        if (resolved != null && resolved.activityInfo != null) {
            ComponentName c = new ComponentName(resolved.activityInfo.packageName, resolved.activityInfo.name);
            if (launch(ctx, new Intent().setComponent(c))) {
                prefs.edit().putString(prefKey, c.flattenToString()).apply();
                return true;
            }
        }

        // 3. 最后一招：在厂商包里按类名关键字搜
        ComponentName found = findInVendorPackages(ctx, hint);
        if (found != null && launch(ctx, new Intent().setComponent(found))) {
            prefs.edit().putString(prefKey, found.flattenToString()).apply();
            return true;
        }

        Log.i(TAG, "no settings activity found for " + hint);
        return false;
    }

    /**
     * 在厂商自己的包里找类名含关键字的 Activity。
     *
     * 只扫包名带厂商关键字的包，不全量遍历 —— 电视上装了几百个包，全扫会卡住主线程。
     */
    private static ComponentName findInVendorPackages(Context ctx, String hint) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> vendors = new HashSet<>();
        List<ApplicationInfo> apps;
        try {
            apps = pm.getInstalledApplications(0);
        } catch (RuntimeException e) {
            return null;
        }
        for (ApplicationInfo app : apps) {
            String pkg = app.packageName == null ? "" : app.packageName.toLowerCase(Locale.ROOT);
            if (pkg.contains("tcl") || pkg.contains("ffalcon") || pkg.contains("hisense")
                    || pkg.contains("skyworth") || pkg.contains("changhong") || pkg.contains("konka")
                    || pkg.contains("haier") || pkg.contains("mitv") || pkg.contains("coocaa")) {
                vendors.add(app.packageName);
            }
        }
        for (String pkg : vendors) {
            List<ResolveInfo> activities;
            try {
                activities = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).setPackage(pkg), 0);
            } catch (RuntimeException e) {
                continue;
            }
            for (ResolveInfo ri : activities) {
                if (ri.activityInfo == null) continue;
                String name = ri.activityInfo.name == null ? "" : ri.activityInfo.name;
                String lower = name.toLowerCase(Locale.ROOT);
                if (!lower.contains(hint)) continue;
                // 排除音量条/语言选择这类同名字噪音（选源搜索时 "input" 是关键，不能排除）
                if (lower.contains("volume") || lower.contains("language")
                        || lower.contains("about")) continue;
                if (lower.contains("input") && !"source".equals(hint)) continue;
                return new ComponentName(ri.activityInfo.packageName, name);
            }
        }
        return null;
    }

    private static boolean launch(Context ctx, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /** 记下实际启动的组件，下次第一步就用它。 */
    private static void remember(Context ctx, SharedPreferences prefs, String key, Intent intent) {
        ComponentName c = intent.getComponent();
        if (c == null) {
            ResolveInfo ri = ctx.getPackageManager()
                    .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (ri == null || ri.activityInfo == null) return;
            c = new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
        }
        prefs.edit().putString(key, c.flattenToString()).apply();
    }

    /** 逐个试，第一个能起来的赢。 */
    private static boolean start(Context ctx, Object... candidates) {
        for (Object candidate : candidates) {
            Intent intent = candidate instanceof Intent
                    ? new Intent((Intent) candidate)
                    : new Intent().setComponent((ComponentName) candidate);
            if (launch(ctx, intent)) return true;
        }
        return false;
    }
}

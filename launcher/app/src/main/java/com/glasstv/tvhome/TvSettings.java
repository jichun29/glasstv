package com.glasstv.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * Opening the TV's own settings (2026-10-23 K).
 *
 * China-market Xiaomi firmware does not register the AOSP entry points: on MiTV_ASTP0
 * `android.settings.SETTINGS` and `android.settings.WIFI_SETTINGS` resolve to nothing and the
 * platform `com.android.tv.settings` package is disabled. Sending the standard intents was what
 * produced the "this TV does not support it" toast.
 *
 * So: try the vendor components explicitly first, then the AOSP ones, then the standard intents.
 */
final class TvSettings {

    private static final String XIAOMI = "com.xiaomi.mitv.settings";
    private static final String AOSP_TV = "com.android.tv.settings";

    private TvSettings() {}

    static boolean openSettings(Context ctx) {
        return start(ctx,
                new ComponentName(XIAOMI, XIAOMI + ".entry.MainActivity"),
                new ComponentName(AOSP_TV, AOSP_TV + ".MainSettings"),
                new Intent(Settings.ACTION_SETTINGS));
    }

    static boolean openWifi(Context ctx) {
        return start(ctx,
                new ComponentName(XIAOMI, XIAOMI + ".wifi.MitvWifiActivity"),
                new ComponentName(AOSP_TV, AOSP_TV + ".connectivity.NetworkActivity"),
                new Intent(Settings.ACTION_WIFI_SETTINGS));
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
                        android.net.Uri.parse("package:" + pkg)));
    }

    /** Each candidate in order; the first one that launches wins. */
    private static boolean start(Context ctx, Object... candidates) {
        for (Object candidate : candidates) {
            Intent intent = candidate instanceof Intent
                    ? new Intent((Intent) candidate)
                    : new Intent().setComponent((ComponentName) candidate);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                ctx.startActivity(intent);
                return true;
            } catch (ActivityNotFoundException | SecurityException | IllegalArgumentException ignored) {
                // try the next one
            }
        }
        return false;
    }
}

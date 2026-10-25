package com.glasstv.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机后：守护已在跑就让它再看一眼前台；没在跑且原生桌面占着，就直接拉起 GlassTV。
 * 从 Receiver 拉 activity 在 Android 10+ 需要 SYSTEM_ALERT_WINDOW（装的时候 appops 给过）。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)) return;
        if (HomeGuardService.onBootCompleted(context)) return;
        try {
            context.startActivity(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ignored) {}
    }
}

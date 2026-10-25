package com.glasstv.tvhome;

import android.content.Context;
import android.graphics.Typeface;

/**
 * 字体取用的统一入口。Resources.getFont() 要 API 26，老系统没有；
 * 这里按版本分流：新系统用 getFont，老系统退回 Typeface.createFromAsset 读同一个字体文件，
 * 拿不到就返回 null（用系统默认字体，界面照常可用）。
 */
final class Fonts {
    private static Typeface medium;

    private Fonts() {
    }

    static Typeface medium(Context context) {
        if (medium != null) return medium;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                medium = context.getResources().getFont(R.font.outfit_medium);
            } else {
                // 字体是可变字体，放在 res/font/outfit.ttf；老系统直接从 assets 路径读同一个文件
                medium = Typeface.createFromAsset(context.getAssets(), "font/outfit.ttf");
            }
        } catch (Exception | LinkageError ignored) {
            medium = null;   // 兜底：系统默认字体
        }
        return medium;
    }
}

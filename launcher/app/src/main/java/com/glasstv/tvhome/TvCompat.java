package com.glasstv.tvhome;

import android.content.Context;
import android.media.tv.TvInputInfo;

/**
 * TvInputInfo 的新方法兼容层（Android 5.0/5.1 没有）。
 * - isHidden(Context)：API 24+
 * - loadCustomLabel(Context)：API 24+（电视设置里给输入源起的自定义名字）
 * 老系统上认为端口都没被隐藏、也没有自定义名——菜单照常列出来，只是少了这两项细化。
 */
final class TvCompat {
    private TvCompat() {
    }

    static boolean isHidden(Context context, TvInputInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                return info.isHidden(context);
            } catch (Exception | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    static CharSequence customLabel(Context context, TvInputInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 24) {
            try {
                return info.loadCustomLabel(context);
            } catch (Exception | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }
}

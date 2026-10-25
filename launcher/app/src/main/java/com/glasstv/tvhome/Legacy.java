package com.glasstv.tvhome;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewPropertyAnimator;

/**
 * Android 5.0 以下（API 19，给 4.4 盒子用的精简版）的降级实现集中在这里。
 *
 * 4.4 上没有 Material 主题、没有 ViewOutlineProvider、没有 TvInputManager、没有 NetworkCallback、
 * 没有 Drawable#setTint、没有 Canvas#saveLayerAlpha(alpha)。精简版在这些机器上：
 * - 圆角用 XML 的 drawable 背景实现（不做 outline 裁剪）
 * - 没有输入源菜单（TvInputManager 是 API 21 才有的整套框架）
 * - 网络状态条不显示（只保留 Wi-Fi 名称为空的状态）
 * - 图标着色改为给 Drawable 套一层 ColorFilter
 * 高负载功能（必应每日壁纸、时钟屏保、宇称卡片）在 4.4 版里整体不编进去。
 */
final class Legacy {
    static final boolean HAS_MATERIAL = android.os.Build.VERSION.SDK_INT >= 21;

    private Legacy() {
    }

    /** Context#getDrawable(int) 要 API 21。 */
    @SuppressWarnings("deprecation")
    static Drawable drawable(Context context, int id) {
        if (android.os.Build.VERSION.SDK_INT >= 21) return context.getDrawable(id);
        return context.getResources().getDrawable(id);
    }

    /** Resources#getDrawableForDensity 要 API 21（4.4 上退回普通 getDrawable）。 */
    @SuppressWarnings("deprecation")
    static Drawable drawableForDensity(Resources res, int id, int density, Resources.Theme theme) throws Resources.NotFoundException {
        if (android.os.Build.VERSION.SDK_INT >= 21) return res.getDrawableForDensity(id, density, theme);
        return res.getDrawable(id);
    }

    /** PackageItemInfo#banner / loadBanner 要 API 20；4.4 上永远没有 banner，返回 0。 */
    @SuppressWarnings("deprecation")
    static int bannerId(PackageItemInfo info) {
        if (android.os.Build.VERSION.SDK_INT >= 20) return info.banner;
        return 0;
    }

    @SuppressWarnings("deprecation")
    static Drawable loadBanner(PackageItemInfo info, PackageManager pm) {
        if (android.os.Build.VERSION.SDK_INT >= 20) return info.loadBanner(pm);
        return null;
    }

    /** ViewOutlineProvider / setClipToOutline 要 API 21：4.4 上不裁剪，靠 XML 背景的圆角。 */
    static void roundCorner(View view) {
        if (android.os.Build.VERSION.SDK_INT < 21) return;
        Outline21.apply(view);
    }

    /** 单独一个内部类装 API 21 的类型：外面的方法在 4.4 上根本走不进来，类也不会被加载。 */
    @TargetApi(21)
    private static final class Outline21 {
        static void apply(View view) {
            final float density = view.getResources().getDisplayMetrics().density;
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(),
                            AppAdapter.cornerRadius(v.getWidth(), density));
                }
            });
            view.setClipToOutline(true);
        }
    }

    /** setTranslationZ 要 API 21：4.4 上卡片不做 Z 抬升（本来也没有阴影）。 */
    static void translationZ(View view, float z) {
        if (android.os.Build.VERSION.SDK_INT >= 21) view.setTranslationZ(z);
    }

    /** ViewPropertyAnimator#translationZ 要 API 21：4.4 上退化成无 Z 的动画（其余属性照常）。 */
    static ViewPropertyAnimator translationZ(ViewPropertyAnimator animator, float z) {
        if (animator == null) return null;
        if (android.os.Build.VERSION.SDK_INT >= 21) return animator.translationZ(z);
        return animator;
    }

    /** Drawable#setTint 要 API 21：4.4 上改写 ColorFilter，观感一致。 */
    @SuppressWarnings("deprecation")
    static void tint(Drawable drawable, int color) {
        if (drawable == null) return;
        if (android.os.Build.VERSION.SDK_INT >= 21) drawable.setTint(color);
        else drawable.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
    }

    /** TextView#setLetterSpacing 要 API 21：4.4 上直接跳过（间距差一点点，不影响布局）。 */
    static void letterSpacing(Object unused) {
        // 只做占位说明，实际调用点用 SDK_INT 判断；见调用处。
    }

    /** PackageManager#getLeanbackLaunchIntentForPackage 要 API 21：4.4 上整包没有 leanback，返回 null。 */
    static android.content.Intent leanbackIntent(PackageManager pm, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        if (android.os.Build.VERSION.SDK_INT >= 21) return pm.getLeanbackLaunchIntentForPackage(pkg);
        return pm.getLaunchIntentForPackage(pkg);
    }

    /** 4.4 上没有 Material 主题时用的屏幕密度兜底，避免 DENSITY_XHIGH 常量差异。 */
    static int highDensity(Context context) {
        DisplayMetrics m = context.getResources().getDisplayMetrics();
        return Math.max(DisplayMetrics.DENSITY_HIGH, m.densityDpi);
    }
}

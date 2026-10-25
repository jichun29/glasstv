package com.glasstv.tvhome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bundled offline wallpapers, ported from BeatsAD/quiet-tv (MIT, thanks WenBin AI LAB).
 *
 * 2026-10-25 K：壁纸页重做成「缩略图网格」，素材扩到 11 张（并入二开版那套风景 / 跑车 / 卡通），
 * 按分区排格子；遥控器上下左右走格子、OK 直接套用、返回退出。原来是一排卡片挤在弹窗里，
 * 960dp 屏上最多摆 4 张，素材一多就放不下。
 *
 * The picture layer sits directly above the sky view and under the ambient glow: it is two ImageViews
 * (current + incoming for the cross-fade) plus a readability shade, inserted into the root FrameLayout
 * at index 1 by MainActivity. All decoding happens on one background executor, downsampled to the screen,
 * so a 1 GB TV never holds more than roughly one screen-sized bitmap per picture.
 */
final class WallpaperController {
    /** 内置壁纸（2026-10-25 合并二开版素材）：随 APK 打包，不联网。 */
    private static final String[] FILES = {
            "coast", "forest", "waterfall", "alpine",
            "car_coast", "car_desert",
            "cartoon_coast", "cartoon_village",
            "big-sur-coastline", "big-sur-mountains", "big-sur-road"};
    private static final String[] ZH = {
            "海岸暮色", "森林晨光", "金色瀑布", "雪山湖泊",
            "海岸跑车", "峡谷跑车",
            "卡通海岸", "童话小镇",
            "大苏尔海岸", "大苏尔群山", "大苏尔山路"};
    /** 壁纸页的分区：标题 + 该区在 FILES 里的起止下标。 */
    private static final String[] GROUPS = {"自然风景", "跑车", "卡通场景", "清简风景"};
    private static final int[] GROUP_FROM = {0, 4, 6, 8};
    private static final int[] GROUP_TO = {4, 6, 8, FILES.length};
    private static final long ROTATION_MS = 5 * 60 * 1000L;

    /** Caller reacts to picture vs. default backdrop (hide ambient glow & sky over a photo). */
    interface VisibilityListener { void onPictureVisibilityChanged(boolean pictureVisible); }

    private final Activity activity;
    private final FrameLayout background;   // layer inserted into root by MainActivity
    private final SharedPreferences preferences;
    private final VisibilityListener visibilityListener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();
    private final ImageView current, incoming;
    private final View shade;
    private final Bitmap[] thumbnails = new Bitmap[FILES.length];
    private volatile int request;
    private int index, preview, shown = -2;
    private boolean rotating, active, useDefault;
    /** 必应每日壁纸模式（tvhome-zh 移植）：开启后图片层显示电视本地 bing-wallpaper.jpg。 */
    private boolean bingMode;
    private BingWallpaper bing;
    private Dialog picker;
    private ValueAnimator transition;

    private final Runnable rotate = () -> {
        index = (index + 1) % FILES.length;
        save();
        display(index, true);
        schedule();
    };

    /** {@code layerIndex} = position inside root where the background FrameLayout goes. */
    WallpaperController(Activity activity, FrameLayout root, int layerIndex, VisibilityListener listener) {
        this.activity = activity;
        this.visibilityListener = listener;
        preferences = activity.getSharedPreferences("wallpapers", 0);
        index = preferences.getInt("index", 0);
        rotating = preferences.getBoolean("rotating", true);
        useDefault = preferences.getBoolean("default", !preferences.contains("index"));
        if (useDefault) rotating = false;
        // 素材表改过长度以后旧下标可能越界，兜底回第一张。
        if (index < 0 || index >= FILES.length) index = 0;
        bing = new BingWallpaper(activity);
        bingMode = preferences.getBoolean("bing", false) && bing.enabled() && bing.file() != null;

        background = new FrameLayout(activity);
        root.addView(background, layerIndex,
                new FrameLayout.LayoutParams(-1, -1));
        current = image(); incoming = image(); incoming.setAlpha(0);
        background.addView(current, new FrameLayout.LayoutParams(-1, -1));
        background.addView(incoming, new FrameLayout.LayoutParams(-1, -1));
        // Readability shade: keeps clock/dock text legible over bright scenery.
        // K 2026-10-22：按上游 Aerial 的做法改成「左侧 + 底部」压暗——时钟和 App 名字都在左边和下方，
        // 只压这两处，中间的天空/山景保持干净。原来是从上到下整体压暗，画面发灰。
        shade = new View(activity);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x4D000000, 0x00000000}));
        shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        background.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        // 底部再压一层，Dock 那一排的名字永远看得清
        View bottomShade = new View(activity);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x80000000, 0x00000000}));
        bottomShade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        background.addView(bottomShade, new FrameLayout.LayoutParams(-1, -1));
        display(useDefault ? -1 : (bingMode ? -2 : index), false);
        // 精简版不在启动时联网拉必应图（老盒子开机本来就慢，别再加一次网络+解码）。
        if (!BuildConfig.LITE) refreshBing();
    }

    /** 启动时后台拉一次必应今日图（开了开关且今天没拉过才真的下载），拉到就切换显示。 */
    private void refreshBing() {
        if (!bing.enabled()) return;
        decoder.execute(() -> {
            boolean changed = bing.refreshIfDue();
            if (changed) main.post(() -> {
                if (activity.isDestroyed()) return;
                if (preferences.getBoolean("bing", false)) {
                    bingMode = true;
                    useDefault = false;
                    rotating = false;
                    display(-2, true);
                }
            });
        });
    }

    private ImageView image() {
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return image;
    }

    private Bitmap decode(int n, int sample) {
        try (InputStream input = activity.getAssets().open("wallpapers/" + FILES[n] + ".jpg")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeStream(input, null, options);
        } catch (IOException e) {
            throw new IllegalStateException("Missing bundled wallpaper", e);
        }
    }

    private synchronized Bitmap thumbnail(int n) {
        if (thumbnails[n] == null) thumbnails[n] = decode(n, 4);
        return thumbnails[n];
    }

    private Bitmap fullImage(int n) {
        android.util.DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = activity.getAssets().open("wallpapers/" + FILES[n] + ".jpg")) {
            BitmapFactory.decodeStream(input, null, bounds);
        } catch (IOException e) {
            throw new IllegalStateException("Missing bundled wallpaper", e);
        }
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= screen.widthPixels
                && bounds.outHeight / (sample * 2) >= screen.heightPixels) sample *= 2;
        return decode(n, sample);
    }

    /** n &lt; 0 shows the default backdrop (no picture layer visible); n == -2 shows the Bing daily picture. */
    private void display(int n, boolean animate) {
        final int version = ++request;
        if (transition != null) { transition.end(); transition = null; }
        if (n < 0 && n != -2) {
            current.setImageDrawable(null);
            incoming.setImageDrawable(null);
            background.setVisibility(View.INVISIBLE);
            if (visibilityListener != null) visibilityListener.onPictureVisibilityChanged(false);
            shown = -1;
            Log.i("GlassTV", "Wallpaper display=default");
            return;
        }
        if (shown == n && background.getVisibility() == View.VISIBLE) return;
        decoder.execute(() -> {
            if (version != request) return;
            Bitmap bitmap = n == -2 ? bingImage() : fullImage(n);
            if (bitmap == null) return;   // 必应图解码失败保持现状
            main.post(() -> {
                if (version != request || activity.isDestroyed()) return;
                background.setVisibility(View.VISIBLE);
                if (visibilityListener != null) visibilityListener.onPictureVisibilityChanged(true);
                if (!animate || shown < 0) {
                    current.setImageBitmap(bitmap);
                    shown = n;
                } else {
                    incoming.setImageBitmap(bitmap);
                    incoming.setAlpha(0);
                    transition = ValueAnimator.ofFloat(0, 1);
                    transition.setDuration(900);
                    transition.addUpdateListener(a -> {
                        float fraction = (float) a.getAnimatedValue();
                        incoming.setAlpha(fraction);
                        if (fraction == 1f) {
                            current.setImageBitmap(bitmap);
                            incoming.setImageDrawable(null);
                            incoming.setAlpha(0);
                            shown = n;
                        }
                    });
                    transition.start();
                }
                Log.i("GlassTV", "Wallpaper display=" + (n == -2 ? "bing" : FILES[n]) + " rotating=" + rotating);
            });
        });
    }

    /** 解码本地必应图，按屏幕尺寸降采样。 */
    private Bitmap bingImage() {
        java.io.File f = bing.file();
        if (f == null) return null;
        android.util.DisplayMetrics screen = activity.getResources().getDisplayMetrics();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), bounds);
        if (bounds.outWidth <= 0) return null;
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= screen.widthPixels
                && bounds.outHeight / (sample * 2) >= screen.heightPixels) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private void save() {
        preferences.edit().putInt("index", index).putBoolean("rotating", rotating)
                .putBoolean("default", useDefault).putBoolean("bing", bingMode).apply();
    }

    private void schedule() {
        main.removeCallbacks(rotate);
        if (active && rotating && !useDefault && picker == null) main.postDelayed(rotate, ROTATION_MS);
    }

    void resume() { active = true; schedule(); }

    /** 内存紧张时放开壁纸位图（满屏 1080p RGB_565 约 4MB）；从后台回来时 restore() 重新解码。 */
    void releaseBitmaps() {
        if (transition != null) {
            transition.end();
            transition = null;
        }
        current.setImageDrawable(null);
        incoming.setImageDrawable(null);
        incoming.setAlpha(0);
        shown = -2;   // 下次 display() 必定重新解码
    }

    /** 释放过位图后重新显示当前壁纸。 */
    void restore() {
        if (shown == -2) display(useDefault ? -1 : (bingMode ? -2 : index), false);
    }

    void pause() {
        active = false;
        main.removeCallbacks(rotate);
        if (picker != null) picker.dismiss();
        if (transition != null) transition.end();
    }

    void destroy() {
        ++request;
        decoder.shutdownNow();
        main.removeCallbacksAndMessages(null);
        if (transition != null) {
            transition.cancel();
            transition.removeAllUpdateListeners();
            transition = null;
        }
        current.setImageDrawable(null);
        incoming.setImageDrawable(null);
    }

    private String name(int n) { return n < 0 ? "默认壁纸（纯黑＋光晕）" : ZH[n]; }

    // ---- picker: 整屏缩略图网格（2026-10-25 K）-------------------------------------------------

    private float dp(float value) {
        return value * activity.getResources().getDisplayMetrics().density;
    }

    /** 壁纸页卡片/胶囊背景：默认深灰圆角，焦点白框提亮（和桌面设置页同一套语言）。 */
    private GradientDrawable cardBg(boolean focus) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focus ? 0x3DFFFFFF : 0xFF1C2128);
        bg.setCornerRadius(dp(14));
        bg.setStroke(Math.round((focus ? 2 : 1) * dp(1f)), focus ? 0xFFFFFFFF : 0x1FFFFFFF);
        return bg;
    }

    /**
     * 整屏壁纸页：标题 / 当前状态 / 轮播 + 必应两个开关 / 分区缩略图网格。
     * 焦点走到哪张就先预览哪张；OK 套用并关页；返回 = 不改动。
     */
    void showPicker() {
        if (picker != null) return;
        main.removeCallbacks(rotate);
        preview = useDefault ? -1 : (bingMode ? -2 : index);
        picker = new Dialog(activity);

        // Only the content layers above the wallpaper are hidden during preview; the
        // ambient/sky layers follow the wallpaper state, not this hide/show cycle.
        FrameLayout root = (FrameLayout) background.getParent();
        ArrayList<View> hidden = new ArrayList<>();
        for (int n = 0; n < root.getChildCount(); n++) {
            View child = root.getChildAt(n);
            if (child != background && child.getVisibility() == View.VISIBLE
                    && child.getId() != R.id.ambient && child.getId() != R.id.sky) {
                child.setVisibility(View.INVISIBLE);
                hidden.add(child);
            }
        }

        FrameLayout page = new FrameLayout(activity);
        page.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xF20B1119, 0xF2000000}));

        TextView title = label("壁纸", 30);
        place(page, title, 300, 42, 60, 24);

        TextView mode = label(currentLabel(), 13);
        mode.setTextColor(0x99FFFFFF);
        place(page, mode, 460, 24, 62, 74);

        // 右上两个开关：轮播 / 必应每日壁纸
        TextView auto = pill(rotating ? "壁纸轮播：开" : "壁纸轮播：关");
        place(page, auto, 172, 42, 552, 40);
        final boolean hasBing = !BuildConfig.LITE;
        TextView bingBtn = pill(bingMode || bing.enabled() ? "必应每日：开" : "必应每日：关");
        if (hasBing) place(page, bingBtn, 172, 42, 736, 40);

        TextView status = label("按方向键挑选，OK 应用", 13);
        status.setTextColor(0x99FFFFFF);
        place(page, status, 840, 24, 60, 508);

        // ---- 缩略图网格（4 列，垂直滚动）----
        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(0, 0, 0, Math.round(dp(40)));
        scroll.addView(column, new LinearLayout.LayoutParams(-1, -2));
        place(page, scroll, 840, 352, 60, 112);

        final int TILE_W = 192, TILE_H = 112;   // 4 列 × 192 + 3×12 = 804 ≤ 840

        final ArrayList<View> tiles = new ArrayList<>();
        final ArrayList<Integer> tileChoice = new ArrayList<>();
        final ArrayList<int[]> tileCell = new ArrayList<>();   // {row, col}，默认卡独占第 0 行

        // 默认壁纸单独一张卡排最前（网格第 0 位）
        addTile(column, tiles, tileChoice, tileCell, -1, 0, 0, TILE_W, TILE_H,
                defaultThumbnail(), "默认", useDefault);

        for (int g = 0; g < GROUPS.length; g++) {
            TextView head = label(GROUPS[g], 12);
            head.setTextColor(0x66FFFFFF);
            // setLetterSpacing 要 API 21：4.4 上跳过，间距差一点点不影响排版
            if (android.os.Build.VERSION.SDK_INT >= 21) head.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = Math.round(dp(g == 0 ? 16 : 20));
            hp.bottomMargin = Math.round(dp(6));
            head.setLayoutParams(hp);
            column.addView(head);

            LinearLayout row = null;
            for (int n = GROUP_FROM[g]; n < GROUP_TO[g]; n++) {
                int slot = (n - GROUP_FROM[g]) % 4;
                if (slot == 0) {
                    row = new LinearLayout(activity);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                    column.addView(row);
                }
                addTile(row, tiles, tileChoice, tileCell, n, g + 1, slot, TILE_W, TILE_H,
                        null, ZH[n], false);
            }
        }

        // 缩略图后台解码后填进去（先占位，主线程不碰 11 张图）
        for (int i = 0; i < tiles.size(); i++) {
            final int choice = tileChoice.get(i);
            if (choice < 0) continue;
            final ImageView img = tiles.get(i).findViewWithTag("thumb");
            decoder.execute(() -> {
                Bitmap bmp = thumbnail(choice);
                main.post(() -> { if (img != null && bmp != null) img.setImageBitmap(bmp); });
            });
        }

        // 上下左右走格子：4 列，UP/DOWN 跳一行，到头就吃掉按键（不穿到顶部开关）
        for (int i = 0; i < tiles.size(); i++) {
            final int at = i;
            View tile = tiles.get(i);
            tile.setOnFocusChangeListener((v, focused) -> {
                v.setBackground(cardBg(focused));
                if (!focused) return;
                int c = tileChoice.get(at);
                preview = c;
                display(c, true);
                status.setText(name(c));
            });
            tile.setOnClickListener(v -> {
                int c = tileChoice.get(at);
                commit(c < 0 ? index : c, false, c < 0, false);
            });
            tile.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int[] cell = tileCell.get(at);
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 同一行内左右走；走到头就吃掉按键，别跳进别的分区
                    int target = at + (key == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                    if (target < 0 || target >= tiles.size()) return true;
                    if (tileCell.get(target)[0] != cell[0]) return true;
                    tiles.get(target).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    // 上下跨行按「视觉行列」找最近的：默认卡独占第 0 行，各分区从第 1 行起，
                    // 用固定 ±4 会因为「跑车/卡通只有 2 张」而错位（实测会卡在边界出不去）。
                    int wantRow = cell[0] + (key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
                    int best = -1, bestDist = Integer.MAX_VALUE;
                    for (int j = 0; j < tiles.size(); j++) {
                        int[] c = tileCell.get(j);
                        if (c[0] != wantRow) continue;
                        int d = Math.abs(c[1] - cell[1]);
                        if (d < bestDist) { bestDist = d; best = j; }
                    }
                    if (best >= 0) tiles.get(best).requestFocus();
                    return true;   // 没有下一行就吃掉，别穿到顶部开关
                }
                return false;
            });
        }

        auto.setOnFocusChangeListener((v, focus) -> {
            auto.setBackground(cardBg(focus));
            if (focus) status.setText(rotating
                    ? "按确定关闭轮播，固定当前壁纸" : "按确定开启轮播：每 5 分钟换一张");
        });
        auto.setOnClickListener(v -> commit(preview < 0 ? index : preview, !rotating, false, false));
        auto.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { tiles.get(0).requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && hasBing) { bingBtn.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_LEFT;
        });

        if (hasBing) {
            bingBtn.setOnFocusChangeListener((v, focus) -> {
                bingBtn.setBackground(cardBg(focus));
                if (focus) status.setText(bingMode || bing.enabled()
                        ? "按确定关闭必应每日壁纸（当前壁纸保留）" : "按确定开启：每天自动换一张必应今日图");
            });
            bingBtn.setOnClickListener(v -> {
                boolean on = !(bingMode || bing.enabled());
                bing.setEnabled(on);
                if (on) {
                    // 开启：立即后台拉一张，拉到就切到必应模式
                    status.setText("正在下载今日必应壁纸…");
                    decoder.execute(() -> {
                        bing.refreshIfDue();
                        main.post(() -> {
                            if (picker == null || activity.isDestroyed()) return;
                            if (bing.file() != null) commit(0, false, false, true);
                            else status.setText("下载失败（电视可能没联网），明天开机自动重试");
                        });
                    });
                } else {
                    // 关闭：保留当前图当固定壁纸（不删文件，明天不再换）
                    commit(index, false, false, false);
                    Toast.makeText(activity, "已关闭必应每日壁纸", Toast.LENGTH_SHORT).show();
                }
            });
            bingBtn.setOnKeyListener((v, key, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN && key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    auto.requestFocus();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN;
            });
        }

        picker.setContentView(page);
        picker.setOnDismissListener(dialog -> {
            picker = null;
            for (View v : hidden) v.setVisibility(View.VISIBLE);
            display(useDefault ? -1 : (bingMode ? -2 : index), true);
            schedule();
        });
        picker.setOnKeyListener((dialog, keyCode, event) -> {
            // 返回 = 不改动，直接退出（Dialog 默认只关窗，这里显式处理避免焦点残留）
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                dialog.dismiss();
                return true;
            }
            return false;
        });
        Window window = picker.getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.10f);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        picker.show();
        window.setLayout(-1, -1);
        // 开场焦点落在当前生效的那张（默认卡占网格第 0 位）
        tiles.get(useDefault || bingMode ? 0 : Math.min(index + 1, tiles.size() - 1)).requestFocus();
    }

    private String currentLabel() {
        if (bingMode) return "当前：必应每日壁纸 · 每天自动换一张";
        if (useDefault) return "当前：默认壁纸 · 纯黑背景＋应用主题色光晕";
        return "当前：" + name(index) + (rotating ? " · 轮流播放" : "");
    }

    /**
     * 往网格里加一张壁纸卡：缩略图 + 名字。{@code bitmap} 为空时先留空，后台解码后再塞（tag "thumb"）。
     */
    private void addTile(ViewGroup parent, List<View> tiles, List<Integer> choices, List<int[]> cells,
                         int choice, int row, int col, int wDp, int hDp,
                         Bitmap bitmap, String caption, boolean checked) {
        FrameLayout card = new FrameLayout(activity);
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackground(cardBg(false));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(dp(wDp)), Math.round(dp(hDp)));
        if (parent instanceof LinearLayout
                && ((LinearLayout) parent).getOrientation() == LinearLayout.HORIZONTAL) {
            lp.rightMargin = Math.round(dp(12));
        }
        card.setLayoutParams(lp);
        card.setContentDescription(caption);

        ImageView img = new ImageView(activity);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setTag("thumb");
        if (bitmap != null) img.setImageBitmap(bitmap);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
        ip.setMargins(Math.round(dp(4)), Math.round(dp(4)), Math.round(dp(4)), Math.round(dp(4)));
        card.addView(img, ip);

        // 底部压暗 + 名字，浅色图上白字也看得清
        View scrim = new View(activity);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xB3000000, 0x00000000}));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, Math.round(dp(44)), Gravity.BOTTOM);
        sp.setMargins(Math.round(dp(4)), 0, Math.round(dp(4)), Math.round(dp(4)));
        card.addView(scrim, sp);

        TextView label = label(caption + (checked ? "  ✓" : ""), 13);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(Math.round(dp(10)), 0, Math.round(dp(10)), Math.round(dp(10)));
        card.addView(label, np);

        parent.addView(card);
        tiles.add(card);
        choices.add(choice);
        cells.add(new int[]{row, col});
    }

    private TextView pill(String value) {
        TextView text = label(value, 14);
        text.setGravity(Gravity.CENTER);
        text.setFocusable(true);
        text.setClickable(true);
        text.setBackground(cardBg(false));
        return text;
    }

    private TextView label(String value, float sizeSp) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        text.setIncludeFontPadding(false);
        return text;
    }

    private void place(FrameLayout parent, View view, int wDp, int hDp, int leftDp, int topDp) {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Math.round(dp(wDp)), Math.round(dp(hDp)));
        params.leftMargin = Math.round(dp(leftDp));
        params.topMargin = Math.round(dp(topDp));
        parent.addView(view, params);
    }

    private void commit(int choice, boolean slideshow, boolean defaultWallpaper, boolean bingOn) {
        index = choice;
        rotating = slideshow;
        useDefault = defaultWallpaper;
        bingMode = bingOn;
        if (bingOn) { useDefault = false; rotating = false; }
        save();
        picker.dismiss();
        Toast.makeText(activity,
                bingOn ? "已开启必应每日壁纸"
                        : defaultWallpaper ? "已恢复默认壁纸"
                        : slideshow ? "已开启壁纸轮播" : "已应用：" + name(choice),
                Toast.LENGTH_SHORT).show();
    }

    private Bitmap defaultThumbnail() {
        Bitmap bitmap = Bitmap.createBitmap(360, 203, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new RadialGradient(116, 59, 232,
                new int[]{0xac557eac, 0x40557eac, Color.TRANSPARENT},
                new float[]{0, .5f, 1}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 360, 203, paint);
        return bitmap;
    }
}

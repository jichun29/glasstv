package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 桌面设置右侧的「首页实时预览」（2026-10-26 K，参考 QuietTV 的 HomePreviewView 重写）。
 *
 * 为什么要有它：以前改一项设置得退回桌面才知道效果，来回试很烦。这里按首页的真实排版
 * 画一块 960×540 的等比缩略场景——时间块位置、首页信息开关、字号、12/24 小时、列数、
 * 保留行数、图标名称、背景色、壁纸，全都读同一份 SharedPreferences，改一项立刻重画。
 *
 * 只画，不启动任何应用、不请求定位、不联网；壁纸优先用壁纸页当前选中的那张
 * （必应模式下读本地已下载的图），没有就用纯背景色。
 */
public final class HomePreviewView extends FrameLayout {
    private static final int SCENE_W = 960;
    private static final int SCENE_H = 540;
    /** 与 activity_main.xml 的左右/顶部内边距一致。 */
    private static final int PAD_SIDE = 56;
    private static final int PAD_TOP = 26;

    private final FrameLayout scene;
    private final ImageView wallpaper;
    private final View shade, bottomShade;
    private final LinearLayout header;
    private final LinearLayout dock;
    private final TextView hint;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private int generation;
    private String loadedWallpaper = "";

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClockText();
            main.postDelayed(this, 20_000L);
        }
    };

    public HomePreviewView(Context context) { this(context, null); }

    public HomePreviewView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(true);
        setFocusable(false);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        scene = new FrameLayout(context);
        scene.setPivotX(0);
        scene.setPivotY(0);
        scene.setBackgroundColor(Color.BLACK);
        addView(scene, new LayoutParams(SCENE_W, SCENE_H));

        wallpaper = new ImageView(context);
        wallpaper.setScaleType(ImageView.ScaleType.CENTER_CROP);
        scene.addView(wallpaper, new LayoutParams(SCENE_W, SCENE_H));

        shade = new View(context);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xB3000000, 0x4D000000, 0x00000000}));
        scene.addView(shade, new LayoutParams(SCENE_W, SCENE_H));

        bottomShade = new View(context);
        bottomShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x80000000, 0x00000000}));
        scene.addView(bottomShade, new LayoutParams(SCENE_W, SCENE_H));

        header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        scene.addView(header, new LayoutParams(SCENE_W - PAD_SIDE * 2, LayoutParams.WRAP_CONTENT));

        dock = new LinearLayout(context);
        dock.setOrientation(LinearLayout.VERTICAL);
        scene.addView(dock, new LayoutParams(SCENE_W - PAD_SIDE * 2, LayoutParams.WRAP_CONTENT));

        hint = new TextView(context);
        hint.setTextColor(0x66FFFFFF);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_PX, 22);
        hint.setVisibility(GONE);
        scene.addView(hint, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        if (w <= 0) w = MeasureSpec.getSize(heightSpec) * SCENE_W / SCENE_H;
        setMeasuredDimension(w, Math.round(w * (float) SCENE_H / SCENE_W));
        scene.measure(MeasureSpec.makeMeasureSpec(SCENE_W, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(SCENE_H, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float scale = w / (float) SCENE_W;
        scene.setScaleX(scale);
        scene.setScaleY(scale);
        if (w > 0) refresh();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        tick.run();
    }

    @Override
    protected void onDetachedFromWindow() {
        main.removeCallbacks(tick);
        worker.shutdownNow();
        super.onDetachedFromWindow();
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** 重画整块预览：所有设置都从 SharedPreferences 现读，不做缓存。 */
    public void refresh() {
        if (getWidth() == 0) return;
        Context ctx = getContext();
        Config cfg = Config.load(ctx);
        SharedPreferences prefs = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);

        boolean showGreeting = Config.showGreeting(ctx, cfg.greetingEnabled);
        boolean showDate = Config.showDate(ctx, cfg.dateEnabled);
        boolean showWeather = Config.showWeather(ctx, cfg.weatherEnabled);
        String align = Config.headerAlign(ctx);
        int size = Config.headerSize(ctx);
        boolean hour12 = Config.hour12(ctx);
        int columns = cfg.columns;
        int rowsBelow = cfg.rowsBelowDock;
        boolean dockOnly = Config.dockOnly(ctx);
        boolean names = Config.showAppNames(ctx, cfg.alwaysShowNames);

        // 背景色
        scene.setBackgroundColor(cfg.backgroundColor);

        // ---- 时间块 ----
        header.removeAllViews();
        header.setGravity("center".equals(align) ? Gravity.CENTER_HORIZONTAL
                : "right".equals(align) ? Gravity.END : Gravity.START);

        int clockSp = new int[]{52, 62, 72}[Math.max(0, Math.min(2, size))];
        int dateSp = new int[]{14, 17, 20}[Math.max(0, Math.min(2, size))];
        int subSp = new int[]{12, 14, 16}[Math.max(0, Math.min(2, size))];
        int textGravity = "center".equals(align) ? Gravity.CENTER_HORIZONTAL
                : "right".equals(align) ? Gravity.END : Gravity.START;

        if (showGreeting) {
            TextView g = label(greetingText(), subSp, 0x8CFFFFFF, "@font/outfit_medium");
            if (android.os.Build.VERSION.SDK_INT >= 21) g.setLetterSpacing(0.22f);
            g.setGravity(textGravity);
            header.addView(g, lpMatchWrap());
        }

        // 左对齐：时钟 | 竖线 | 日期（与首页一致）；居中/靠右：时钟 / 日期 竖排
        boolean inline = "left".equals(align);
        LinearLayout clockRow = new LinearLayout(ctx);
        clockRow.setOrientation(inline ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        clockRow.setGravity(inline ? Gravity.BOTTOM : Gravity.CENTER_HORIZONTAL);
        clockRow.setBaselineAligned(true);

        TextClock clock = new TextClock(ctx);
        String fmt = hour12 ? "h:mm a" : "HH:mm";
        clock.setFormat12Hour(fmt);
        clock.setFormat24Hour(fmt);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, clockSp * getResources().getDisplayMetrics().scaledDensity);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        if (inline) {
            View divider = new View(ctx);
            divider.setBackgroundColor(0x33FFFFFF);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(1), dp(44));
            dlp.leftMargin = dp(16);
            dlp.rightMargin = dp(16);
            dlp.bottomMargin = dp(8);
            clockRow.addView(divider, dlp);
        }

        TextView date = label(dateText(), dateSp, 0xA6FFFFFF, "@font/outfit_regular");
        if (android.os.Build.VERSION.SDK_INT >= 21) date.setLetterSpacing(0.14f);
        date.setGravity(textGravity);
        LinearLayout.LayoutParams dateLp = new LinearLayout.LayoutParams(
                inline ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (inline) dateLp.bottomMargin = dp(9);
        clockRow.addView(date, dateLp);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        header.addView(clockRow, rowLp);

        if (showWeather) {
            TextView w = label(weatherText(), subSp, 0xB3FFFFFF, "@font/outfit_regular");
            if (android.os.Build.VERSION.SDK_INT >= 21) w.setLetterSpacing(0.06f);
            w.setGravity(textGravity);
            LinearLayout.LayoutParams wlp = lpMatchWrap();
            wlp.topMargin = dp(2);
            header.addView(w, wlp);
        }

        header.measure(MeasureSpec.makeMeasureSpec(SCENE_W - PAD_SIDE * 2, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams hlp = (FrameLayout.LayoutParams) header.getLayoutParams();
        hlp.leftMargin = PAD_SIDE;
        hlp.topMargin = PAD_TOP;
        hlp.width = SCENE_W - PAD_SIDE * 2;
        header.setLayoutParams(hlp);

        // ---- 应用行 ----
        dock.removeAllViews();
        List<String> apps = new ArrayList<>(cfg.dockApps);
        int rows = dockOnly ? 1 : (1 + Math.max(0, Math.min(3, rowsBelow)));
        int maxCards = rows * columns;
        if (apps.size() > maxCards) apps = apps.subList(0, maxCards);

        int inner = SCENE_W - PAD_SIDE * 2;
        int gap = dp(14);
        int cardW = (inner - gap * (columns - 1)) / columns;
        int cardH = Math.round(cardW * 9f / 16f);

        for (int r = 0; r < rows; r++) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < columns; c++) {
                int i = r * columns + c;
                View card = i < apps.size() ? appCard(ctx, apps.get(i), names, cardW, cardH)
                        : glassCard(ctx, cardW, cardH);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(cardW, cardH);
                if (c > 0) p.leftMargin = gap;
                row.addView(card, p);
            }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, cardH);
            if (r > 0) p.topMargin = gap;
            dock.addView(row, p);
        }

        dock.measure(MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams dlp2 = (FrameLayout.LayoutParams) dock.getLayoutParams();
        dlp2.leftMargin = PAD_SIDE;
        dlp2.width = inner;
        int bottomRoom = dp(34);
        int top = SCENE_H - bottomRoom - dock.getMeasuredHeight();
        // 不压到时间块：至少留出时间块下方 24dp
        int minTop = PAD_TOP + header.getMeasuredHeight() + dp(24);
        dlp2.topMargin = Math.max(top, minTop);
        dock.setLayoutParams(dlp2);

        loadWallpaper(ctx);

        hint.setVisibility(GONE);
    }

    private LinearLayout.LayoutParams lpMatchWrap() {
        return new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
    }

    private TextView label(String text, int sp, int color, String font) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * getResources().getDisplayMetrics().scaledDensity);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        if (android.os.Build.VERSION.SDK_INT >= 21) t.setLetterSpacing(0.02f);
        return t;
    }

    private void updateClockText() {
        // TextClock 自己走时间；这里只兜底刷新问候语（跨时段要变）
        if (header.getChildCount() > 0 && header.getChildAt(0) instanceof TextView) {
            TextView g = (TextView) header.getChildAt(0);
            if (Config.showGreeting(getContext(), Config.load(getContext()).greetingEnabled)) {
                g.setText(greetingText());
            }
        }
    }

    private String greetingText() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 5) return "夜深了";
        if (hour < 12) return "早上好";
        if (hour < 17) return "下午好";
        if (hour < 22) return "晚上好";
        return "夜深了";
    }

    private String dateText() {
        String format = getResources().getString(R.string.date_format_home).replace("\\n", " ");
        try {
            return new SimpleDateFormat(format, Locale.CHINA).format(new Date());
        } catch (Exception e) {
            return new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date());
        }
    }

    private String weatherText() {
        Weather.Now now = Weather.cached();
        if (now != null) return now.label();
        String place = Weather.place();
        return place == null || place.isEmpty() ? "宜昌 20° 晴 15/24°" : place + " 20° 晴 15/24°";
    }

    /** 玻璃卡片：没有图标时的占位（空槽位）。 */
    private View glassCard(Context ctx, int w, int h) {
        View v = new View(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x14FFFFFF);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0x22FFFFFF);
        v.setBackground(d);
        return v;
    }

    /** 一张应用卡：图标 + 名称（名称按「图标下常显名称」开关决定显隐）。 */
    private View appCard(Context ctx, String pkg, boolean showNames, int w, int h) {
        FrameLayout card = new FrameLayout(ctx);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0x1FFFFFFF);
        d.setCornerRadius(dp(12));
        d.setStroke(dp(1), 0x2EFFFFFF);
        card.setBackground(d);

        Drawable icon = null;
        String name = pkg;
        try {
            PackageManager pm = ctx.getPackageManager();
            icon = pm.getApplicationIcon(pkg);
            name = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Exception ignored) {
        }

        ImageView image = new ImageView(ctx);
        if (icon != null) image.setImageDrawable(icon);
        int iconSize = Math.round(h * (showNames ? 0.46f : 0.56f));
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(iconSize, iconSize);
        ilp.gravity = Gravity.CENTER_HORIZONTAL | (showNames ? Gravity.TOP : Gravity.CENTER_VERTICAL);
        if (showNames) ilp.topMargin = Math.round(h * 0.14f);
        card.addView(image, ilp);

        if (showNames) {
            TextView t = label(name, 12, 0xE6FFFFFF, "@font/outfit_regular");
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setGravity(Gravity.CENTER_HORIZONTAL);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(w - dp(16), LayoutParams.WRAP_CONTENT);
            tlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            tlp.bottomMargin = Math.round(h * 0.10f);
            card.addView(t, tlp);
        }
        return card;
    }

    /** 壁纸：跟首页读同一份选择（默认壁纸 / 内置某张 / 必应今日图）。 */
    private void loadWallpaper(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        boolean useDefault = prefs.getBoolean("default", !prefs.contains("index"));
        boolean bing = prefs.getBoolean("bing", false);
        int index = prefs.getInt("index", 0);
        final String key = useDefault ? "default" : bing ? "bing" : "img" + index;
        if (key.equals(loadedWallpaper)) return;
        loadedWallpaper = key;

        if ("default".equals(key)) {
            wallpaper.setImageDrawable(null);
            shade.setVisibility(GONE);
            bottomShade.setVisibility(GONE);
            return;
        }
        final int request = ++generation;
        worker.execute(() -> {
            Bitmap bmp = null;
            try {
                if ("bing".equals(key)) {
                    File dir = ctx.getExternalFilesDir(null);
                    File f = dir == null ? null : new File(dir, BingWallpaper.FILE_NAME);
                    if (f != null && f.isFile()) bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                } else {
                    bmp = WallpaperController.previewImage(ctx, index);
                }
            } catch (Exception | OutOfMemoryError ignored) {
            }
            final Bitmap image = bmp;
            main.post(() -> {
                if (request != generation || getWidth() == 0) return;
                wallpaper.setImageBitmap(image);
                int vis = image == null ? GONE : VISIBLE;
                shade.setVisibility(vis);
                bottomShade.setVisibility(vis);
            });
        });
    }
}

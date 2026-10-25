package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 壁纸与屏保（2026-10-25 K 定稿，v4.0.2）。
 *
 * 顶部标签页「壁纸 | 屏保」，默认停在壁纸页，两页各自占满整屏（不是左右分栏）：
 *   壁纸页：轮播 / 必应每日两个开关 + 4 列缩略图网格，方向键挑选、确定应用
 *   屏保页：时间 / 必应壁纸 两张卡（选哪张就是哪套）+ 屏保开关 + 换图间隔 + 预览
 *
 * 焦点规则：焦点在标签行时左右键切页；进了内容区左右键归内容自己用（网格选图、
 * 卡片/分段之间移动）。上键一律回标签行，所以遥控器永远走得出去。
 * 为什么自己算焦点：电视上 ScrollView + 网格的默认焦点搜索会乱跑（左右窜出滚动区、
 * 上下穿到别的控件），这里按「视觉行列」找目标格，到边界就吃掉按键。
 * 设置写共享 prefs（wallpapers / launcher_settings），桌面回前台自己重读，不用回传结果。
 */
public final class PersonalizeActivity extends Activity {

    private static final int[] INTERVAL_MINS = {0, 60, 1440};
    /** 4 列 × 200dp + 3×12dp = 836 ≤ 864 的可用宽度。 */
    private static final int TILE_W = 200, TILE_H = 112, TILE_GAP = 12;
    private static final int COLUMNS = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences choices;      // launcher_settings：屏保
    private SharedPreferences wall;         // wallpapers：壁纸

    private View tabWallpaper, tabSaver;
    private TextView tabWallpaperText, tabSaverText;
    private View tabWallpaperLine, tabSaverLine;
    private View pageWallpaper, pageSaver;

    private TextView wallpaperMode;
    private TextView rotatePill;
    private TextView bingPill;
    private TextView idlePill;
    private TextView status;          // 壁纸页底部状态行
    private TextView saverStatus;     // 屏保页底部状态行

    private FrameLayout saverClock;
    private FrameLayout saverFlip;
    private FrameLayout saverBing;
    private TextView clockCaption;
    private TextView flipCaption;
    private TextView bingCaption;
    private ImageView bingThumb;
    private TextView bingPlaceholder;
    private View intervalLabel;
    private View previewButton;
    private View[] segments;
    /** 翻页时钟的卡片大小：标签 + 小 / 中 / 大三个分段。 */
    private View flipSizeLabel;
    private View[] flipSizeSegments;
    private View nextImage;

    private ScrollView scroll;
    private LinearLayout column;

    /** 网格格子：视觉行列 + 对应的壁纸下标（-1 = 默认壁纸）。 */
    private final List<View> tiles = new ArrayList<>();
    private final List<int[]> cells = new ArrayList<>();
    private final List<Integer> tileChoice = new ArrayList<>();

    private boolean rotating, bingDaily, useDefault;
    private int index;
    private float density;

    /** 当前在壁纸页（true）还是屏保页（false）。默认壁纸页。 */
    private boolean wallpaperPage = true;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_personalize);
        density = getResources().getDisplayMetrics().density;
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        wall = getSharedPreferences("wallpapers", MODE_PRIVATE);

        tabWallpaper = findViewById(R.id.pzTabWallpaper);
        tabSaver = findViewById(R.id.pzTabSaver);
        tabWallpaperText = findViewById(R.id.pzTabWallpaperText);
        tabSaverText = findViewById(R.id.pzTabSaverText);
        tabWallpaperLine = findViewById(R.id.pzTabWallpaperLine);
        tabSaverLine = findViewById(R.id.pzTabSaverLine);
        pageWallpaper = findViewById(R.id.pzPageWallpaper);
        pageSaver = findViewById(R.id.pzPageSaver);

        wallpaperMode = findViewById(R.id.pzWallpaperMode);
        rotatePill = findViewById(R.id.pzRotate);
        bingPill = findViewById(R.id.pzBingDaily);
        idlePill = findViewById(R.id.pzIdleSwitch);
        status = findViewById(R.id.pzStatus);
        saverStatus = findViewById(R.id.pzSaverStatus);
        saverClock = findViewById(R.id.pzSaverClock);
        saverFlip = findViewById(R.id.pzSaverFlip);
        saverBing = findViewById(R.id.pzSaverBing);
        clockCaption = findViewById(R.id.pzClockCaption);
        flipCaption = findViewById(R.id.pzFlipCaption);
        bingCaption = findViewById(R.id.pzBingCaption);
        buildFlipPreview();
        bingThumb = findViewById(R.id.pzBingThumb);
        bingPlaceholder = findViewById(R.id.pzBingPlaceholder);
        intervalLabel = findViewById(R.id.pzIntervalLabel);
        previewButton = findViewById(R.id.pzPreviewSaver);
        nextImage = findViewById(R.id.pzNextImage);
        scroll = findViewById(R.id.pzWallpaperScroll);
        column = findViewById(R.id.pzWallpaperColumn);
        segments = new View[]{findViewById(R.id.pzEveryTime),
                findViewById(R.id.pzEvery1h), findViewById(R.id.pzEvery1d)};
        flipSizeLabel = findViewById(R.id.pzFlipSizeLabel);
        flipSizeSegments = new View[]{findViewById(R.id.pzFlipSizeSmall),
                findViewById(R.id.pzFlipSizeMedium), findViewById(R.id.pzFlipSizeLarge)};

        readWallpaperState();
        buildWallpaperGrid();
        wireTabs();
        wireWallpaperPage();
        wireSaverPage();
        refreshScreensaver();
        loadBingThumb();

        // 默认停在壁纸页，焦点落在「壁纸」标签上：第一下左右就能切到屏保页
        showPage(true, false);
        tabWallpaper.requestFocus();
    }

    // ---- 标签页 ------------------------------------------------------------------

    private void wireTabs() {
        tabWallpaper.setOnClickListener(v -> showPage(true, true));
        tabSaver.setOnClickListener(v -> showPage(false, true));

        for (final boolean isWallpaper : new boolean[]{true, false}) {
            final View tab = isWallpaper ? tabWallpaper : tabSaver;
            tab.setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // 只有两个标签：左右都切到另一边，切完焦点跟过去
                    boolean goWallpaper = !wallpaperPage;
                    showPage(goWallpaper, false);
                    (goWallpaper ? tabWallpaper : tabSaver).requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (wallpaperPage) rotatePill.requestFocus();
                    else idlePill.requestFocus();
                    return true;
                }
                return key == KeyEvent.KEYCODE_DPAD_UP;
            });
        }
    }

    /** 切页：只改可见性 + 标签的下划线与字色，焦点交给调用方决定。 */
    private void showPage(boolean showWallpaper, boolean focusContent) {
        wallpaperPage = showWallpaper;
        pageWallpaper.setVisibility(showWallpaper ? View.VISIBLE : View.GONE);
        pageSaver.setVisibility(showWallpaper ? View.GONE : View.VISIBLE);

        tabWallpaperText.setTextColor(showWallpaper ? 0xFFFFFFFF : 0x80FFFFFF);
        tabSaverText.setTextColor(showWallpaper ? 0x80FFFFFF : 0xFFFFFFFF);
        tabWallpaperLine.setVisibility(showWallpaper ? View.VISIBLE : View.INVISIBLE);
        tabSaverLine.setVisibility(showWallpaper ? View.INVISIBLE : View.VISIBLE);

        if (showWallpaper) {
            status.setText("方向键选壁纸，确定应用");
            if (focusContent) focusFirstGridTile();
        } else {
            saverStatus.setText("方向键挑选屏保样式，确定生效");
            if (focusContent) saverClock.requestFocus();
        }
    }

    // ---- 壁纸：状态 + 网格 -------------------------------------------------------

    private void readWallpaperState() {
        rotating = wall.getBoolean("rotating", true);
        useDefault = wall.getBoolean("default", !wall.contains("index"));
        index = wall.getInt("index", 0);
        if (index < 0 || index >= WallpaperController.count()) index = 0;
        // 必应每日壁纸的开关在 BingWallpaper 自己的 prefs 里，和这里显示的是同一个开关
        bingDaily = new BingWallpaper(this).enabled() && !BuildConfig.LITE;
        if (useDefault) rotating = false;
        refreshWallpaperMode();
    }

    private void refreshWallpaperMode() {
        String label;
        if (bingDaily) label = "当前：必应每日壁纸 · 每天自动换一张";
        else if (useDefault) label = "当前：默认壁纸 · 纯黑背景＋应用主题色光晕";
        else label = "当前：" + WallpaperController.nameOf(index) + (rotating ? " · 轮流播放" : "");
        wallpaperMode.setText(label);
        rotatePill.setText(rotating ? "壁纸轮播：开" : "壁纸轮播：关");
        rotatePill.setSelected(rotating);
        bingPill.setText(bingDaily ? "必应每日：开" : "必应每日：关");
        bingPill.setSelected(bingDaily);
    }

    private int firstFocusTile() {
        if (bingDaily) return Math.min(1, tiles.size() - 1);
        if (useDefault) return 0;
        for (int i = 0; i < tileChoice.size(); i++) if (tileChoice.get(i) == index) return i;
        return 0;
    }

    private void buildWallpaperGrid() {
        addWallpaperCard(column, -1, 0, 0, "默认");
        int row = 1;
        for (int g = 0; g < WallpaperController.groupCount(); g++) {
            TextView head = new TextView(this);
            head.setText(WallpaperController.groupName(g));
            head.setTextColor(0x66FFFFFF);
            head.setTextSize(12f);
            if (android.os.Build.VERSION.SDK_INT >= 21) head.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = Math.round(dp(g == 0 ? 12 : 18));
            hp.bottomMargin = Math.round(dp(6));
            column.addView(head, hp);

            LinearLayout line = null;
            int slot = 0;
            for (int n = WallpaperController.groupStart(g); n < WallpaperController.groupEnd(g); n++) {
                if (slot == 0) {
                    line = new LinearLayout(this);
                    line.setOrientation(LinearLayout.HORIZONTAL);
                    line.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
                    column.addView(line);
                }
                addWallpaperCard(line, n, row, slot, WallpaperController.nameOf(n));
                slot++;
                if (slot == COLUMNS) { slot = 0; row++; }
            }
            if (slot != 0) row++;
        }
        paintCards();
    }

    private void addWallpaperCard(ViewGroup parent, int choice, int cellRow, int cellCol, String caption) {
        FrameLayout card = new FrameLayout(this);
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(caption);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                Math.round(dp(TILE_W)), Math.round(dp(TILE_H)));
        lp.rightMargin = Math.round(dp(TILE_GAP));
        card.setLayoutParams(lp);

        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        img.setTag("thumb");
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(-1, -1);
        int m = Math.round(dp(3));
        ip.setMargins(m, m, m, m);
        card.addView(img, ip);

        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xCC000000, 0x00000000}));
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, Math.round(dp(36)), Gravity.BOTTOM);
        sp.setMargins(m, 0, m, m);
        card.addView(scrim, sp);

        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(12f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(Math.round(dp(9)), 0, Math.round(dp(9)), Math.round(dp(9)));
        card.addView(label, np);

        parent.addView(card);
        final int at = tiles.size();
        tiles.add(card);
        cells.add(new int[]{cellRow, cellCol});
        tileChoice.add(choice);

        if (choice < 0) {
            img.setImageBitmap(defaultThumb());
        } else {
            final ImageView target = img;
            final int n = choice;
            io.execute(() -> {
                Bitmap bmp = WallpaperController.thumbFor(this, n);
                if (bmp == null) return;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    target.setImageBitmap(bmp);
                });
            });
        }

        card.setOnFocusChangeListener((v, focused) -> {
            paintCards();
            if (!focused) return;
            status.setText(choice < 0
                    ? "默认壁纸：纯黑背景 + 应用主题色光晕　确定应用"
                    : WallpaperController.nameOf(choice) + "　确定应用");
        });
        card.setOnClickListener(v -> applyWallpaper(choice));
        card.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            return moveInsideGrid(at, key);
        });
    }

    /** 网格内部导航：左右只在本行，上下按视觉行列找最近的；出边界就吃掉按键。 */
    private boolean moveInsideGrid(int at, int key) {
        int[] cell = cells.get(at);
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (cell[1] == 0) return true;                       // 行首：吃掉
            tiles.get(at - 1).requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            int target = at + 1;
            if (target < tiles.size() && cells.get(target)[0] == cell[0]) {
                tiles.get(target).requestFocus();
            }
            return true;   // 本行到头就吃掉，不窜到别处
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (cell[0] == 0) { tabWallpaper.requestFocus(); return true; }   // 第一行往上回标签
            focusRow(cell[0] - 1, cell[1]);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            focusRow(cell[0] + 1, cell[1]);   // 没有下一行就留在原地
            return true;
        }
        return false;
    }

    /** 跳到指定视觉行里、列最接近 fromCol 的格子；没有该行返回 false。 */
    private boolean focusRow(int wantRow, int fromCol) {
        int best = -1, bestDist = Integer.MAX_VALUE;
        for (int j = 0; j < cells.size(); j++) {
            int[] c = cells.get(j);
            if (c[0] != wantRow) continue;
            int d = Math.abs(c[1] - fromCol);
            if (d < bestDist) { bestDist = d; best = j; }
        }
        if (best < 0) return false;
        final View target = tiles.get(best);
        target.requestFocus();
        scroll.post(() -> scroll.smoothScrollTo(0,
                Math.max(0, target.getTop() - Math.round(dp(16)))));
        return true;
    }

    private boolean isChecked(int choice) {
        if (bingDaily) return false;
        return choice < 0 ? useDefault : (!useDefault && choice == index);
    }

    private void paintCards() {
        for (int i = 0; i < tiles.size(); i++) {
            View card = tiles.get(i);
            card.setBackground(bg(card.isFocused(), isChecked(tileChoice.get(i))));
        }
    }

    /** 卡片底：焦点=白框提亮，选中=亮一档白描边，其余=深灰。 */
    private GradientDrawable bg(boolean focused, boolean checked) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(focused ? 0x33FFFFFF : 0xFF14181E);
        bg.setCornerRadius(dp(10));
        if (focused) bg.setStroke(Math.round(2 * density), 0xFFFFFFFF);
        else if (checked) bg.setStroke(Math.round(2 * density), 0xCCFFFFFF);
        else bg.setStroke(Math.round(1 * density), 0x1FFFFFFF);
        return bg;
    }

    private void applyWallpaper(int choice) {
        boolean toDefault = choice < 0;
        wall.edit()
                .putBoolean("default", toDefault)
                .putBoolean("rotating", false)
                .putBoolean("bing", false)
                .apply();
        if (!toDefault) wall.edit().putInt("index", choice).apply();
        new BingWallpaper(this).setEnabled(false);
        useDefault = toDefault;
        rotating = false;
        bingDaily = false;
        if (!toDefault) index = choice;
        refreshWallpaperMode();
        paintCards();
        Toast.makeText(this, toDefault ? "已恢复默认壁纸"
                : "已应用：" + WallpaperController.nameOf(choice), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
    }

    // ---- 壁纸页：轮播 / 必应每日两个开关 ------------------------------------------

    private void wireWallpaperPage() {
        rotatePill.setOnClickListener(v -> {
            if (useDefault) {
                Toast.makeText(this, "先挑一张壁纸，再开轮播", Toast.LENGTH_SHORT).show();
                return;
            }
            rotating = !rotating;
            if (rotating) bingDaily = false;
            wall.edit().putBoolean("rotating", rotating).putBoolean("bing", false).apply();
            refreshWallpaperMode();
            status.setText(rotating ? "已开轮播：每 5 分钟换一张" : "已关轮播：固定当前这张");
            setResult(RESULT_OK);
        });
        rotatePill.setOnFocusChangeListener((v, f) -> {
            // 焦点态是白底，白字会看不见：焦点时文字转深色（实测踩到）
            rotatePill.setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) status.setText(rotating ? "关掉轮播就固定当前壁纸" : "开启后每 5 分钟换一张");
        });
        rotatePill.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstGridTile(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) { tabWallpaper.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && bingPill.getVisibility() == View.VISIBLE) {
                bingPill.requestFocus();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_LEFT;   // 左边界：吃掉
        });

        bingPill.setOnClickListener(v -> toggleBingDaily());
        bingPill.setOnFocusChangeListener((v, f) -> {
            bingPill.setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) status.setText(bingDaily ? "关掉后当前图保留，明天不再换"
                    : "开启后每天自动换一张必应今日图（需联网）");
        });
        bingPill.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstGridTile(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) { tabWallpaper.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { rotatePill.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });

        if (BuildConfig.LITE) bingPill.setVisibility(View.GONE);
        status.setText("方向键选壁纸，确定应用");
        saverStatus.setText("方向键挑选屏保样式，确定生效");
    }

    private void focusFirstGridTile() {
        if (!tiles.isEmpty()) tiles.get(firstFocusTile()).requestFocus();
    }

    private void toggleBingDaily() {
        if (bingDaily) {
            new BingWallpaper(this).setEnabled(false);
            bingDaily = false;
            wall.edit().putBoolean("bing", false).apply();
            refreshWallpaperMode();
            status.setText("已关闭必应每日壁纸");
            Toast.makeText(this, "已关闭必应每日壁纸", Toast.LENGTH_SHORT).show();
            return;
        }
        if (BuildConfig.LITE) {
            Toast.makeText(this, "4.4 精简版不支持必应每日壁纸", Toast.LENGTH_SHORT).show();
            return;
        }
        status.setText("正在下载今日必应图…");
        io.execute(() -> {
            BingWallpaper bing = new BingWallpaper(this);
            bing.setEnabled(true);
            bing.refreshIfDue();
            boolean ok = bing.file() != null;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (!ok) {
                    bing.setEnabled(false);   // 没下到就别留个空开关，明天开机不会再自动试
                    status.setText("下载失败（电视可能没联网），明天开机自动重试");
                    refreshWallpaperMode();
                    return;
                }
                bingDaily = true;
                rotating = false;
                useDefault = false;
                wall.edit().putBoolean("bing", true).putBoolean("default", false)
                        .putBoolean("rotating", false).apply();
                refreshWallpaperMode();
                paintCards();
                status.setText("必应每日壁纸已开启");
                setResult(RESULT_OK);
            });
        });
    }

    // ---- 屏保页 ------------------------------------------------------------------

    private void wireSaverPage() {
        saverClock.setOnClickListener(v -> setSaverStyle("clock"));
        saverFlip.setOnClickListener(v -> setSaverStyle("flip"));
        saverBing.setOnClickListener(v -> setSaverStyle("bing"));

        idlePill.setOnClickListener(v -> {
            choices.edit().putBoolean("idle_clock_enabled",
                    !choices.getBoolean("idle_clock_enabled", true)).apply();
            refreshScreensaver();
            setResult(RESULT_OK);
        });
        idlePill.setOnFocusChangeListener((v, f) -> {
            idlePill.setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) saverStatus.setText("关掉后待机再久也不进屏保");
        });
        idlePill.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { saverClock.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) { tabSaver.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });

        saverClock.setOnFocusChangeListener((v, f) -> {
            if (f) saverStatus.setText("时间屏保：大号时钟，不联网也一直有");
        });
        saverFlip.setOnFocusChangeListener((v, f) -> {
            if (f) saverStatus.setText("翻页时钟：两块大翻页牌 + 天气地区 + 年月日周几");
        });
        saverBing.setOnFocusChangeListener((v, f) -> {
            if (f) saverStatus.setText("必应壁纸屏保：每天一张新风景，需要联网");
        });
        saverClock.setOnKeyListener((v, key, event) -> saverKeys(key, event, 0));
        saverFlip.setOnKeyListener((v, key, event) -> saverKeys(key, event, 1));
        saverBing.setOnKeyListener((v, key, event) -> saverKeys(key, event, 2));

        for (int i = 0; i < segments.length; i++) {
            final int minutes = INTERVAL_MINS[i];
            final int at = i;
            segments[i].setOnClickListener(v -> {
                choices.edit().putInt("screensaver_interval_min", minutes).apply();
                refreshScreensaver();
                setResult(RESULT_OK);
            });
            segments[i].setOnFocusChangeListener((v, f) -> {
                // 选中态是白底深字，焦点态是半透明底：焦点时用白字，否则白底上看不清
                ((TextView) v).setTextColor(f ? 0xFFFFFFFF : (v.isSelected() ? 0xFF14171C : 0xFFFFFFFF));
            });
            segments[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { saverClock.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { nextSaverRow(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (at == 0) saverClock.requestFocus();
                    else segments[at - 1].requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (at < segments.length - 1) segments[at + 1].requestFocus();
                    else previewButton.requestFocus();
                    return true;
                }
                return false;
            });
        }

        nextImage.setOnClickListener(v -> {
            choices.edit().putLong("screensaver_last_change", 0L).apply();
            Toast.makeText(this, "下次进屏保换新一张", Toast.LENGTH_SHORT).show();
        });
        nextImage.setOnKeyListener((v, key, event) -> bottomKeys(key, event, false));

        // 卡片大小：小 / 中 / 大。跟「换图间隔」同一行位置，两排互斥显示不会有视觉冲突。
        for (int i = 0; i < flipSizeSegments.length; i++) {
            final int size = i;
            flipSizeSegments[i].setOnClickListener(v -> {
                choices.edit().putInt(Config.FLIP_CARD_SIZE_KEY, size).apply();
                android.util.Log.i("FlipScene", "click size=" + size
                        + " readBack=" + Config.flipCardSize(this));
                refreshScreensaver();
                setResult(RESULT_OK);
            });
            flipSizeSegments[i].setOnFocusChangeListener((v, f) ->
                    ((TextView) v).setTextColor(f ? 0xFFFFFFFF
                            : (v.isSelected() ? 0xFF14171C : 0xFFFFFFFF)));
            flipSizeSegments[i].setOnKeyListener((v, key, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (key == KeyEvent.KEYCODE_DPAD_UP) { saverFlip.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { previewButton.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (size == 0) saverFlip.requestFocus();
                    else flipSizeSegments[size - 1].requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (size < flipSizeSegments.length - 1) flipSizeSegments[size + 1].requestFocus();
                    else previewButton.requestFocus();
                    return true;
                }
                return false;
            });
        }

        previewButton.setOnClickListener(v -> startActivity(ClockActivity.launch(this)));
        previewButton.setOnFocusChangeListener((v, f) -> {
            ((TextView) previewButton).setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) saverStatus.setText("按确定立即预览，任意键返回");
        });
        previewButton.setOnKeyListener((v, key, event) -> bottomKeys(key, event, true));
    }

    /** at: 0=时间 1=翻页 2=必应；三张卡横排，左右只在卡之间走，到边界吃掉。 */
    private boolean saverKeys(int key, KeyEvent event, int at) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) { tabSaver.requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            // 从卡片往下进「卡片大小」（翻页时钟时）或「换图间隔」，落在第一个分段上
            if (flipSizeSegments[0].getVisibility() == View.VISIBLE) flipSizeSegments[0].requestFocus();
            else if (segments[0].getVisibility() == View.VISIBLE) segments[0].requestFocus();
            else previewButton.requestFocus();
            return true;
        }
        FrameLayout[] cards = {saverClock, saverFlip, saverBing};
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (at < cards.length - 1) cards[at + 1].requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (at > 0) cards[at - 1].requestFocus();
            return true;   // 最左：吃掉，不跨回壁纸页
        }
        return false;
    }

    private boolean bottomKeys(int key, KeyEvent event, boolean first) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (segments[0].getVisibility() == View.VISIBLE) segments[1].requestFocus();
            else if (flipSizeSegments[1].getVisibility() == View.VISIBLE) flipSizeSegments[1].requestFocus();
            else saverBing.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!first && nextImage.getVisibility() == View.VISIBLE) previewButton.requestFocus();
            else if (segments[0].getVisibility() == View.VISIBLE) segments[segments.length - 1].requestFocus();
            else if (flipSizeSegments[0].getVisibility() == View.VISIBLE) {
                flipSizeSegments[flipSizeSegments.length - 1].requestFocus();
            } else saverClock.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (first && nextImage.getVisibility() == View.VISIBLE) nextImage.requestFocus();
            return true;
        }
        return false;
    }

    private void nextSaverRow() {
        if (nextImage.getVisibility() == View.VISIBLE) nextImage.requestFocus();
        else previewButton.requestFocus();
    }

    private void setSaverStyle(String style) {
        if ("flip".equals(style)) {
            choices.edit().putString("screensaver_style", "flip").apply();
            refreshScreensaver();
            setResult(RESULT_OK);
            return;
        }
        if ("bing".equals(style)) {
            if (BuildConfig.LITE) {
                Toast.makeText(this, "4.4 精简版不支持壁纸屏保", Toast.LENGTH_SHORT).show();
                return;
            }
            choices.edit().putString("screensaver_style", "bing").apply();
            io.execute(() -> BingScreensaver.ensurePool(this));
        } else {
            choices.edit().putString("screensaver_style", "clock").apply();
        }
        refreshScreensaver();
        setResult(RESULT_OK);
    }

    private void refreshScreensaver() {
        boolean enabled = choices.getBoolean("idle_clock_enabled", true);
        String style = choices.getString("screensaver_style", "clock");
        boolean bing = "bing".equals(style) && !BuildConfig.LITE;
        boolean flip = "flip".equals(style);

        idlePill.setText(enabled ? "屏保：开" : "屏保：关");
        idlePill.setSelected(enabled);
        clockCaption.setText(flip || bing ? "时间" : "时间  ✓");
        flipCaption.setText(flip ? "翻页时钟  ✓" : "翻页时钟");
        bingCaption.setText(bing ? "必应每日  ✓" : "必应每日");
        TextView mode = findViewById(R.id.pzSaverMode);
        if (mode != null) mode.setText(bing ? "当前：必应壁纸屏保"
                : flip ? "当前：翻页时钟屏保" : "当前：时间屏保");

        int visibility = (bing && enabled) ? View.VISIBLE : View.GONE;
        intervalLabel.setVisibility(visibility);
        for (View segment : segments) segment.setVisibility(visibility);
        nextImage.setVisibility(visibility);

        // 卡片大小只在「翻页时钟 + 屏保开着」时出现：别的样式没有翻页牌，摆着是噪声。
        int flipSizeVisibility = (flip && enabled) ? View.VISIBLE : View.GONE;
        if (flipSizeLabel != null) {
            flipSizeLabel.setVisibility(flipSizeVisibility);
            for (View segment : flipSizeSegments) segment.setVisibility(flipSizeVisibility);
            int size = Config.flipCardSize(this);
            for (int i = 0; i < flipSizeSegments.length; i++) {
                boolean selected = i == size;
                flipSizeSegments[i].setSelected(selected);
                ((TextView) flipSizeSegments[i]).setTextColor(selected ? 0xFF14171C : 0xFFFFFFFF);
            }
        }

        int current = choices.getInt("screensaver_interval_min", 60);
        for (int i = 0; i < segments.length; i++) {
            boolean selected = current == INTERVAL_MINS[i];
            segments[i].setSelected(selected);
            ((TextView) segments[i]).setTextColor(selected ? 0xFF14171C : 0xFFFFFFFF);
        }
    }

    /** 屏保页「翻页时钟」卡里的静态预览：两块小牌子 09 / 41，跟实物同一套画法。 */
    private void buildFlipPreview() {
        LinearLayout row = findViewById(R.id.pzFlipPreview);
        if (row == null || row.getChildCount() > 0) return;
        int w = Math.round(dp(80)), h = Math.round(dp(68));
        int text = Math.round(dp(57));
        String[] values = {"09", "41"};
        for (int i = 0; i < values.length; i++) {
            FlipCard card = new FlipCard(this, w, h, text);
            card.setValue(values[i], false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
            if (i == 0) lp.rightMargin = Math.round(dp(7));
            row.addView(card, lp);
        }
    }

    /** 本地已有的必应图当场显示；没有就留占位文案。 */
    private void loadBingThumb() {
        if (BuildConfig.LITE) {
            saverBing.setVisibility(View.GONE);
            return;
        }
        io.execute(() -> {
            File file = BingScreensaver.pickForNow(this);
            if (file == null || !file.isFile()) return;
            Bitmap bitmap = BingScreensaver.decode(file, 560, 300);
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bingThumb.setImageBitmap(bitmap);
                bingPlaceholder.setVisibility(View.GONE);
            });
        });
    }

    // ---- 通用 --------------------------------------------------------------------

    private float dp(float value) {
        return value * density;
    }

    private Bitmap defaultThumb() {
        int w = Math.round(dp(TILE_W)) * 2, h = Math.round(dp(TILE_H)) * 2;
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        paint.setShader(new RadialGradient(w * 0.32f, h * 0.3f, h * 1.1f,
                new int[]{0xAC557EAC, 0x40557EAC, 0x00000000}, new float[]{0f, .5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, w, h, paint);
        return bitmap;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
    }
}

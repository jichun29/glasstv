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
 * 壁纸与屏保（2026-10-25 K）。
 *
 * 控制中心从 9 个入口收到 6 个，原来分开的「壁纸」「屏保」两页合成这一页：
 *   左半屏 壁纸：分区缩略图网格，方向键挑选、确定应用（轮播 / 必应每日在上一行开关里）
 *   右半屏 屏保：时间 / 必应壁纸 两张卡（选哪张就是哪套）+ 屏保开关 + 换图间隔 + 预览
 *
 * 为什么自己写焦点导航：电视上 ScrollView + 网格的默认焦点搜索会乱跑（左右窜到右半屏、
 * 上下穿出滚动区）。这里按「视觉行列」算目标格，走到边界就吃掉按键。
 * 两把设置写共享 prefs（wallpapers / launcher_settings），桌面回前台自己重读，不用回传结果。
 */
public final class PersonalizeActivity extends Activity {

    private static final int[] INTERVAL_MINS = {0, 60, 1440};
    private static final int TILE_W = 132, TILE_H = 82, TILE_GAP = 8;
    private static final int COLUMNS = 4;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SharedPreferences choices;      // launcher_settings：屏保
    private SharedPreferences wall;         // wallpapers：壁纸

    private TextView wallpaperMode;
    private TextView rotatePill;
    private TextView bingPill;
    private TextView idlePill;
    private TextView status;

    private FrameLayout saverClock;
    private FrameLayout saverBing;
    private TextView clockCaption;
    private TextView bingCaption;
    private ImageView bingThumb;
    private TextView bingPlaceholder;
    private View intervalLabel;
    private View previewButton;
    private View[] segments;
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

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_personalize);
        density = getResources().getDisplayMetrics().density;
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        wall = getSharedPreferences("wallpapers", MODE_PRIVATE);

        wallpaperMode = findViewById(R.id.pzWallpaperMode);
        rotatePill = findViewById(R.id.pzRotate);
        bingPill = findViewById(R.id.pzBingDaily);
        idlePill = findViewById(R.id.pzIdleSwitch);
        status = findViewById(R.id.pzStatus);
        saverClock = findViewById(R.id.pzSaverClock);
        saverBing = findViewById(R.id.pzSaverBing);
        clockCaption = findViewById(R.id.pzClockCaption);
        bingCaption = findViewById(R.id.pzBingCaption);
        bingThumb = findViewById(R.id.pzBingThumb);
        bingPlaceholder = findViewById(R.id.pzBingPlaceholder);
        intervalLabel = findViewById(R.id.pzIntervalLabel);
        previewButton = findViewById(R.id.pzPreviewSaver);
        nextImage = findViewById(R.id.pzNextImage);
        scroll = findViewById(R.id.pzWallpaperScroll);
        column = findViewById(R.id.pzWallpaperColumn);
        segments = new View[]{findViewById(R.id.pzEveryTime),
                findViewById(R.id.pzEvery1h), findViewById(R.id.pzEvery1d)};

        readWallpaperState();
        buildWallpaperGrid();
        wireTopRow();
        wireSaver();
        refreshScreensaver();
        loadBingThumb();

        if (!tiles.isEmpty()) tiles.get(firstFocusTile()).requestFocus();
        status.setText("方向键选壁纸，确定应用　·　右侧是屏保");
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
            head.setTextSize(11f);
            if (android.os.Build.VERSION.SDK_INT >= 21) head.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
            hp.topMargin = Math.round(dp(g == 0 ? 12 : 16));
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
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-1, Math.round(dp(30)), Gravity.BOTTOM);
        sp.setMargins(m, 0, m, m);
        card.addView(scrim, sp);

        TextView label = new TextView(this);
        label.setText(caption);
        label.setTextColor(0xFFFFFFFF);
        label.setTextSize(11f);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        np.setMargins(Math.round(dp(7)), 0, Math.round(dp(7)), Math.round(dp(7)));
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
            card.setBackground(bg(true, isChecked(choice)));
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
                return true;
            }
            // 本行到头 → 进右半屏的屏保卡片（两栏布局的自然走法）
            saverClock.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (cell[0] == 0) { rotatePill.requestFocus(); return true; }
            focusRow(cell[0] - 1, cell[1]);
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!focusRow(cell[0] + 1, cell[1])) saverClock.requestFocus();
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

    // ---- 上一行：壁纸轮播 / 必应每日 / 屏保开关 -------------------------------------

    private void wireTopRow() {
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
            // 焦点态是白底，白字会看不见：焦点时文字转深色（2026-10-25 实测踩到）
            rotatePill.setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) status.setText(rotating ? "关掉轮播就固定当前壁纸" : "开启后每 5 分钟换一张");
        });
        rotatePill.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { focusFirstGridTile(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT && bingPill.getVisibility() == View.VISIBLE) {
                bingPill.requestFocus();
                return true;
            }
            return key == KeyEvent.KEYCODE_DPAD_LEFT;
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
            if (key == KeyEvent.KEYCODE_DPAD_UP) return true;
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { rotatePill.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });

        idlePill.setOnClickListener(v -> {
            choices.edit().putBoolean("idle_clock_enabled",
                    !choices.getBoolean("idle_clock_enabled", true)).apply();
            refreshScreensaver();
            setResult(RESULT_OK);
        });
        idlePill.setOnFocusChangeListener((v, f) -> {
            idlePill.setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) status.setText("关掉后待机再久也不进屏保");
        });
        idlePill.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_DOWN) { saverClock.requestFocus(); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { saverBing.requestFocus(); return true; }
            return key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_RIGHT;
        });

        if (BuildConfig.LITE) bingPill.setVisibility(View.GONE);
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

    // ---- 右半屏：屏保 -------------------------------------------------------------

    private void wireSaver() {
        saverClock.setOnClickListener(v -> setSaverStyle("clock"));
        saverBing.setOnClickListener(v -> setSaverStyle("bing"));

        saverClock.setOnFocusChangeListener((v, f) -> {
            if (f) status.setText("时间屏保：大号时钟，不联网也一直有");
        });
        saverBing.setOnFocusChangeListener((v, f) -> {
            if (f) status.setText("必应壁纸屏保：每天一张新风景，需要联网");
        });
        saverClock.setOnKeyListener((v, key, event) -> saverKeys(key, event, true));
        saverBing.setOnKeyListener((v, key, event) -> saverKeys(key, event, false));

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
                if (key == KeyEvent.KEYCODE_DPAD_UP) { saverBing.requestFocus(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_DOWN) { nextSaverRow(); return true; }
                if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (at == 0) saverBing.requestFocus();
                    else segments[at - 1].requestFocus();
                    return true;
                }
                if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (at < segments.length - 1) segments[at + 1].requestFocus();
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

        previewButton.setOnClickListener(v -> startActivity(new Intent(this, ClockActivity.class)));
        previewButton.setOnFocusChangeListener((v, f) -> {
            ((TextView) previewButton).setTextColor(f ? 0xFF14171C : 0xFFFFFFFF);
            if (f) status.setText("按确定立即预览，任意键返回");
        });
        previewButton.setOnKeyListener((v, key, event) -> bottomKeys(key, event, true));
    }

    private boolean saverKeys(int key, KeyEvent event, boolean first) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) { idlePill.requestFocus(); return true; }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (segments[0].getVisibility() == View.VISIBLE) segments[1].requestFocus();
            else previewButton.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (first) saverBing.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!first) { saverClock.requestFocus(); return true; }
            // 左边界 → 回网格里同一水平带最近的一格
            View tile = nearestTileTo(saverClock.getTop());
            if (tile != null) { tile.requestFocus(); return true; }
            return true;
        }
        return false;
    }

    private boolean bottomKeys(int key, KeyEvent event, boolean first) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        if (key == KeyEvent.KEYCODE_DPAD_UP) {
            if (segments[0].getVisibility() == View.VISIBLE) segments[1].requestFocus();
            else saverBing.requestFocus();
            return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_DOWN) return true;
        if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (!first && nextImage.getVisibility() == View.VISIBLE) previewButton.requestFocus();
            else saverBing.requestFocus();
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

    /** 屏保卡片顶部高度附近、网格里最近的一格（从左往右的返回路径）。 */
    private View nearestTileTo(int topPx) {
        if (tiles.isEmpty()) return null;
        View best = null;
        int bestDist = Integer.MAX_VALUE;
        for (View tile : tiles) {
            int d = Math.abs(tile.getTop() - topPx);
            if (d < bestDist) { bestDist = d; best = tile; }
        }
        return best;
    }

    private void setSaverStyle(String style) {
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
        boolean bing = "bing".equals(choices.getString("screensaver_style", "clock")) && !BuildConfig.LITE;

        idlePill.setText(enabled ? "屏保：开" : "屏保：关");
        idlePill.setSelected(enabled);
        clockCaption.setText(bing ? "时间" : "时间  ✓");
        bingCaption.setText(bing ? "必应每日  ✓" : "必应每日");

        int visibility = (bing && enabled) ? View.VISIBLE : View.GONE;
        intervalLabel.setVisibility(visibility);
        for (View segment : segments) segment.setVisibility(visibility);
        nextImage.setVisibility(visibility);

        int current = choices.getInt("screensaver_interval_min", 60);
        for (int i = 0; i < segments.length; i++) {
            boolean selected = current == INTERVAL_MINS[i];
            segments[i].setSelected(selected);
            ((TextView) segments[i]).setTextColor(selected ? 0xFF14171C : 0xFFFFFFFF);
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
            Bitmap bitmap = BingScreensaver.decode(file, 420, 240);
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

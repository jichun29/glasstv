package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 桌面设置（2026-10-23 K）。
 *
 * 壁纸 / 屏保 / 内存清理 都拆成了独立页面，这里只保留入口，避免同一个功能在两处设置。
 *   布局：每行应用数量、应用栏下方保留行数
 *   外观：壁纸 → 桌面整屏选择器、屏保 → ScreensaverSettingsActivity
 *   动效：动态效果、减弱动画、高级动态效果、恢复主题默认
 *   软件更新：更新方式、检查更新
 *   存储与清理：内存清理 → MemoryCleanSettingsActivity、快传
 *   系统：默认桌面、系统设置（小米电视走 TvSettings 显式组件）
 */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private final TextView[] rowChoices = new TextView[4];
    private final TextView[] headerSizeChoices = new TextView[3];
    private int headerSize;
    private View animationsChoice;
    private TextView animationsValue;
    private View motionChoice;
    private TextView motionValue;
    private View advancedChoice;
    private TextView advancedValue;
    private TextView advancedOffHint;
    private View followThemeChoice;
    private TextView wallpaperEntryValue;
    private TextView screensaverEntryValue;
    private TextView memoryEntryValue;
    private TextView updateManualChoice;
    private TextView updateAutoChoice;
    private int columns;
    private int rowsBelowDock;
    private boolean animations;
    private boolean advancedEffects;
    private boolean reducedMotion;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        rowsBelowDock = config.rowsBelowDock;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;
        reducedMotion = config.reducedMotion;

        // ===== 分段：每行应用数量 =====
        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }

        // ===== 分段：应用栏下方保留行数 =====
        int[] rowIds = {R.id.rows0, R.id.rows1, R.id.rows2, R.id.rows3};
        for (int i = 0; i < rowIds.length; i++) {
            final int value = i;
            rowChoices[i] = findViewById(rowIds[i]);
            rowChoices[i].setOnClickListener(v -> {
                if (rowsBelowDock == value) return;
                rowsBelowDock = value;
                choices.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // RTL 下保持数值递增的阅读顺序
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            reverseRow(columnChoices);
            reverseRow(rowChoices);
        }

        // ===== 分段：时间与天气字号（2026-10-25 合并二开版） =====
        headerSize = Config.headerSize(this);
        int[] sizeIds = {R.id.headerSize0, R.id.headerSize1, R.id.headerSize2};
        for (int i = 0; i < sizeIds.length; i++) {
            final int value = i;
            headerSizeChoices[i] = findViewById(sizeIds[i]);
            headerSizeChoices[i].setOnClickListener(v -> {
                if (headerSize == value) return;
                headerSize = value;
                choices.edit().putInt(Config.HEADER_SIZE_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }

        // ===== 外观：壁纸 / 屏保（各自独立页） =====
        wallpaperEntryValue = findViewById(R.id.wallpaperEntryValue);
        screensaverEntryValue = findViewById(R.id.screensaverEntryValue);
        findViewById(R.id.wallpaperEntry).setOnClickListener(v -> {
            // 壁纸用桌面自带的整屏选择器（缩略图 + 轮播 + 必应每日壁纸）
            setResult(RESULT_OK);
            finish();
            startActivity(new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_SHOW_WALLPAPER)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP));
        });
        findViewById(R.id.screensaverEntry).setOnClickListener(v ->
                startActivity(new Intent(this, ScreensaverSettingsActivity.class)));

        // ===== 开关：动态效果 =====
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsValue = findViewById(R.id.animationsValue);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 开关：减弱动画 =====
        motionChoice = findViewById(R.id.motionChoice);
        motionValue = findViewById(R.id.motionValue);
        motionChoice.setOnClickListener(v -> {
            reducedMotion = !reducedMotion;
            choices.edit().putBoolean("reduced_motion", reducedMotion).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 开关：高级动态效果 =====
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedValue = findViewById(R.id.advancedValue);
        advancedOffHint = findViewById(R.id.advancedOffHint);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });

        // ===== 入口：恢复主题默认 =====
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove(Config.ROWS_BELOW_DOCK_KEY).remove("animations")
                    .remove("advanced_effects").remove("reduced_motion")
                    .remove(Config.HEADER_SIZE_KEY)
                    .remove(Config.WALLPAPER_CHOICE_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            reducedMotion = current.reducedMotion;
            headerSize = Config.headerSize(this);
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 软件更新：分段 + 入口 =====
        updateManualChoice = findViewById(R.id.updateManual);
        updateAutoChoice = findViewById(R.id.updateAuto);
        updateManualChoice.setOnClickListener(v -> setUpdateAuto(false));
        updateAutoChoice.setOnClickListener(v -> setUpdateAuto(true));
        findViewById(R.id.checkUpdateChoice).setOnClickListener(v ->
                startActivity(new Intent(this, UpdateActivity.class)));

        // ===== 存储与清理：内存清理独立页 + 快传 =====
        memoryEntryValue = findViewById(R.id.memoryEntryValue);
        findViewById(R.id.memoryEntry).setOnClickListener(v ->
                startActivity(new Intent(this, MemoryCleanSettingsActivity.class)));
        findViewById(R.id.quickTransferChoice).setOnClickListener(v ->
                startActivity(new Intent(this, QuickTransferActivity.class)));

        // ===== 系统：默认桌面、系统设置 =====
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.settings_home_hint, Toast.LENGTH_LONG).show();
            TvSettings.openSettings(this);
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v -> {
            if (!TvSettings.openSettings(this)) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        });

        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
    }

    private void reverseRow(TextView[] views) {
        android.widget.LinearLayout row = (android.widget.LinearLayout) views[0].getParent();
        row.removeAllViews();
        for (int i = views.length - 1; i >= 0; i--) row.addView(views[i]);
    }

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    /** 软件更新方式：手动（默认）/ 自动。 */
    private void setUpdateAuto(boolean auto) {
        if (UpdateChecker.isAutoEnabled(this) == auto) return;
        UpdateChecker.setAutoEnabled(this, auto);
        refresh();
    }

    private void refresh() {
        // 分段：数值
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        for (int i = 0; i < rowChoices.length; i++) {
            rowChoices[i].setSelected(rowsBelowDock == i);
        }
        for (int i = 0; i < headerSizeChoices.length; i++) {
            if (headerSizeChoices[i] != null) headerSizeChoices[i].setSelected(headerSize == i);
        }
        // 开关：只更新右侧的「开/关」值，行标签留在 XML 里
        animationsValue.setText(animations ? R.string.on : R.string.off);
        animationsValue.setTextColor(animations ? 0xFF4ADE80 : 0x66FFFFFF);
        motionValue.setText(reducedMotion ? R.string.on : R.string.off);
        motionValue.setTextColor(reducedMotion ? 0xFF4ADE80 : 0x66FFFFFF);
        advancedValue.setText(advancedEffects ? R.string.on : R.string.off);
        advancedValue.setTextColor(advancedEffects ? 0xFF4ADE80 : 0x66FFFFFF);

        // 入口当前值：壁纸 / 屏保 / 内存清理
        String wallpaperCurrent = choices.getString(Config.WALLPAPER_CHOICE_KEY, null);
        wallpaperEntryValue.setText("none".equals(wallpaperCurrent) ? getString(R.string.wallpaper_none)
                : wallpaperCurrent != null ? wallpaperCurrent
                : getString(R.string.wallpaper_theme_default));

        boolean idleOn = choices.getBoolean("idle_clock_enabled", true);
        boolean bing = "bing".equals(choices.getString("screensaver_style", "clock")) && !BuildConfig.LITE;
        screensaverEntryValue.setText((bing ? "壁纸" : "时间") + " · " + getString(idleOn ? R.string.on : R.string.off));

        boolean cleanOn = choices.getBoolean("auto_clean_enabled", false);
        memoryEntryValue.setText(cleanOn ? "自动 · 开" : "自动 · 关");
        memoryEntryValue.setTextColor(cleanOn ? 0xFF4ADE80 : 0x66FFFFFF);

        // 高级效果依赖动态效果：关掉动态效果时灰掉
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.45f);
        advancedOffHint.setVisibility(animations ? View.GONE : View.VISIBLE);
        // 恢复主题默认：没有任何自定义时灰掉
        followThemeChoice.setAlpha(hasOnTvChoices() ? 1f : 0.45f);
        // 软件更新分段
        boolean autoUpdate = UpdateChecker.isAutoEnabled(this);
        updateManualChoice.setSelected(!autoUpdate);
        updateAutoChoice.setSelected(autoUpdate);
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains(Config.ROWS_BELOW_DOCK_KEY)
                || choices.contains("animations") || choices.contains("advanced_effects")
                || choices.contains("reduced_motion")
                || choices.contains(Config.HEADER_SIZE_KEY)
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }
}

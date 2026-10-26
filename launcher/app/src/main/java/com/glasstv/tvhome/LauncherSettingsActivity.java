package com.glasstv.tvhome;

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
 * 壁纸与屏保已从本页撤走（2026-10-25 K）：控制中心有独立的「壁纸与屏保」入口，
 * 同一功能不再在两处重复出现。本页只留布局/动效/更新/清理/系统五组。
 *   布局：每行应用数量、应用栏下方保留行数
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
    /** 时间块位置：左/中/右（K 2026-10-26）。 */
    private TextView alignLeft, alignCenter, alignRight;
    private String headerAlign;
    /** 首页信息开关：问候语 / 日期 / 天气 / 图标常显名称。 */
    private TextView showGreetingChoice, showDateChoice, showWeatherChoice;
    /** 时间格式：24 / 12 小时制（桌面 + 屏保共用）。 */
    private TextView timeFormat24, timeFormat12;
    private boolean hour12;
    /** 首页只显示常用应用那一排。 */
    private View dockOnlyChoice;
    private TextView dockOnlyValue;
    private boolean dockOnly;
    private View showAppNamesChoice;
    private TextView showAppNamesValue;
    private boolean showGreeting, showDate, showWeather, showAppNames;
    private final TextView[] bgColorChoices = new TextView[4];
    private int headerSize;
    /** 背景色四选一，顺序与布局里的 bgColor0..3 一致（默认值来自主题，见 Config）。 */
    private static final int[] BG_COLORS = {0xFF000000, 0xFF101B2E, 0xFF2B0D17, 0xFF283039};
    private String weatherCity;
    private TextView weatherCityValue;
    private int backgroundColor;
    private View animationsChoice;
    private TextView animationsValue;
    private View motionChoice;
    private TextView motionValue;
    private View advancedChoice;
    private TextView advancedValue;
    private TextView advancedOffHint;
    private View followThemeChoice;
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

        // ===== 时间位置（K 2026-10-26）：左 / 中 / 右。整块一起挪，单选项，选中的高亮 =====
        headerAlign = Config.headerAlign(this);
        alignLeft = findViewById(R.id.headerAlignLeft);
        alignCenter = findViewById(R.id.headerAlignCenter);
        alignRight = findViewById(R.id.headerAlignRight);
        TextView[] aligns = {alignLeft, alignCenter, alignRight};
        String[] alignValues = {"left", "center", "right"};
        for (int i = 0; i < aligns.length; i++) {
            final String value = alignValues[i];
            aligns[i].setOnClickListener(v -> {
                if (headerAlign.equals(value)) return;
                headerAlign = value;
                choices.edit().putString(Config.HEADER_ALIGN_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }

        // ===== 首页信息开关（K 2026-10-26）：问候语 / 日期 / 天气 各自可关 =====
        showGreeting = Config.showGreeting(this, config.greetingEnabled);
        showDate = Config.showDate(this, config.dateEnabled);
        showWeather = Config.showWeather(this, config.weatherEnabled);
        showGreetingChoice = findViewById(R.id.showGreeting);
        showDateChoice = findViewById(R.id.showDate);
        showWeatherChoice = findViewById(R.id.showWeather);
        showGreetingChoice.setOnClickListener(v -> {
            showGreeting = !showGreeting;
            choices.edit().putBoolean(Config.SHOW_GREETING_KEY, showGreeting).apply();
            setResult(RESULT_OK);
            refresh();
        });
        showDateChoice.setOnClickListener(v -> {
            showDate = !showDate;
            choices.edit().putBoolean(Config.SHOW_DATE_KEY, showDate).apply();
            setResult(RESULT_OK);
            refresh();
        });
        showWeatherChoice.setOnClickListener(v -> {
            showWeather = !showWeather;
            choices.edit().putBoolean(Config.SHOW_WEATHER_KEY, showWeather).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 图标下常显名称：关掉只显示焦点项的名字，格子更干净 =====
        showAppNames = Config.showAppNames(this, config.alwaysShowNames);
        showAppNamesChoice = findViewById(R.id.showAppNamesChoice);
        showAppNamesValue = findViewById(R.id.showAppNamesValue);
        showAppNamesChoice.setOnClickListener(v -> {
            showAppNames = !showAppNames;
            choices.edit().putBoolean(Config.SHOW_APP_NAMES_KEY, showAppNames).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 时间格式（K 2026-10-26）：24 / 12 小时制，屏保也跟着变 =====
        hour12 = Config.hour12(this);
        timeFormat24 = findViewById(R.id.timeFormat24);
        timeFormat12 = findViewById(R.id.timeFormat12);
        timeFormat24.setOnClickListener(v -> setHour12(false));
        timeFormat12.setOnClickListener(v -> setHour12(true));

        // ===== 首页只显示常用应用（K 2026-10-26）：开了其余应用进应用库，桌面中间留白 =====
        dockOnly = Config.dockOnly(this);
        dockOnlyChoice = findViewById(R.id.dockOnlyChoice);
        dockOnlyValue = findViewById(R.id.dockOnlyValue);
        dockOnlyChoice.setOnClickListener(v -> {
            dockOnly = !dockOnly;
            choices.edit().putBoolean(Config.DOCK_ONLY_KEY, dockOnly).apply();
            setResult(RESULT_OK);
            refresh();
        });

        // ===== 天气城市：只读展示自动定位结果（K 2026-10-26：不想手打城市名，定位出来就行）=====
        weatherCityValue = findViewById(R.id.weatherCityValue);
        String tvCity = Config.weatherCityOverride(this);
        weatherCity = tvCity != null ? tvCity : config.weatherCity;

        // ===== 背景颜色（壁纸关掉/缺图时才看得见） =====
        backgroundColor = Config.load(this).backgroundColor;
        int[] bgIds = {R.id.bgColor0, R.id.bgColor1, R.id.bgColor2, R.id.bgColor3};
        for (int i = 0; i < bgIds.length; i++) {
            final int value = BG_COLORS[i];
            bgColorChoices[i] = findViewById(bgIds[i]);
            bgColorChoices[i].setOnClickListener(v -> {
                if (backgroundColor == value) return;
                backgroundColor = value;
                choices.edit().putInt(Config.BACKGROUND_COLOR_KEY, value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }

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
                    .remove(Config.WEATHER_CITY_KEY)
                    .remove(Config.BACKGROUND_COLOR_KEY)
                    .remove(Config.WALLPAPER_CHOICE_KEY)
                    .remove(Config.HEADER_ALIGN_KEY).remove(Config.SHOW_GREETING_KEY)
                    .remove(Config.SHOW_DATE_KEY).remove(Config.SHOW_WEATHER_KEY)
                    .remove(Config.SHOW_APP_NAMES_KEY)
                    .remove(Config.TIME_FORMAT_KEY).remove(Config.DOCK_ONLY_KEY).commit();
            Config current = Config.load(this);
            columns = current.columns;
            rowsBelowDock = current.rowsBelowDock;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            reducedMotion = current.reducedMotion;
            headerSize = Config.headerSize(this);
            headerAlign = Config.headerAlign(this);
            showGreeting = Config.showGreeting(this, current.greetingEnabled);
            showDate = Config.showDate(this, current.dateEnabled);
            showWeather = Config.showWeather(this, current.weatherEnabled);
            showAppNames = Config.showAppNames(this, current.alwaysShowNames);
            hour12 = Config.hour12(this);
            dockOnly = Config.dockOnly(this);
            String backCity = Config.weatherCityOverride(this);
            weatherCity = backCity != null ? backCity : current.weatherCity;
            backgroundColor = current.backgroundColor;
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

        // ===== 内存清理 / 快传 入口已撤（K 2026-10-26）：内存清理在控制中心，快传整个不要了 =====

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

    /** 12 / 24 小时制：只写一个键，桌面和屏保都读它。 */
    private void setHour12(boolean enabled) {
        if (hour12 == enabled) return;
        hour12 = enabled;
        choices.edit().putString(Config.TIME_FORMAT_KEY, enabled ? "12" : "24").apply();
        setResult(RESULT_OK);
        refresh();
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
        for (int i = 0; i < bgColorChoices.length; i++) {
            if (bgColorChoices[i] != null) bgColorChoices[i].setSelected(backgroundColor == BG_COLORS[i]);
        }
        // 时间位置 + 首页信息开关（K 2026-10-26）
        if (alignLeft != null) {
            alignLeft.setSelected("left".equals(headerAlign));
            alignCenter.setSelected("center".equals(headerAlign));
            alignRight.setSelected("right".equals(headerAlign));
        }
        if (showGreetingChoice != null) {
            showGreetingChoice.setSelected(showGreeting);
            showDateChoice.setSelected(showDate);
            showWeatherChoice.setSelected(showWeather);
            showAppNamesValue.setText(showAppNames ? R.string.on : R.string.off);
            showAppNamesValue.setTextColor(showAppNames ? 0xFF4ADE80 : 0x66FFFFFF);
        }
        if (timeFormat24 != null) {
            timeFormat24.setSelected(!hour12);
            timeFormat12.setSelected(hour12);
            dockOnlyValue.setText(dockOnly ? R.string.on : R.string.off);
            dockOnlyValue.setTextColor(dockOnly ? 0xFF4ADE80 : 0x66FFFFFF);
        }
        if (weatherCityValue != null) {
            // 只读：显示自动定位到的城市名（定位失败时退回「自动定位」）
            String auto = Weather.place();
            weatherCityValue.setText(auto != null && auto.length() > 0 ? auto : "自动定位");
        }
        // 开关：只更新右侧的「开/关」值，行标签留在 XML 里
        animationsValue.setText(animations ? R.string.on : R.string.off);
        animationsValue.setTextColor(animations ? 0xFF4ADE80 : 0x66FFFFFF);
        motionValue.setText(reducedMotion ? R.string.on : R.string.off);
        motionValue.setTextColor(reducedMotion ? 0xFF4ADE80 : 0x66FFFFFF);
        advancedValue.setText(advancedEffects ? R.string.on : R.string.off);
        advancedValue.setTextColor(advancedEffects ? 0xFF4ADE80 : 0x66FFFFFF);

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
                || choices.contains(Config.WEATHER_CITY_KEY)
                || choices.contains(Config.BACKGROUND_COLOR_KEY)
                || choices.contains(Config.HEADER_ALIGN_KEY) || choices.contains(Config.SHOW_GREETING_KEY)
                || choices.contains(Config.SHOW_DATE_KEY) || choices.contains(Config.SHOW_WEATHER_KEY)
                || choices.contains(Config.SHOW_APP_NAMES_KEY)
                || choices.contains(Config.TIME_FORMAT_KEY) || choices.contains(Config.DOCK_ONLY_KEY)
                || choices.contains(Config.WALLPAPER_CHOICE_KEY);
    }
}

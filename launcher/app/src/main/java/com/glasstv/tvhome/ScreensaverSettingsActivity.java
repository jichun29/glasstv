package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

/**
 * 屏保设置（2026-10-24 K 重做，照壁纸设置页的版式）。
 *
 * 深色整屏、右上胶囊开关、两张大幅预览卡片（时间 / 必应壁纸）、间隔分段胶囊、底部预览按钮。
 * 选中哪张卡片就是哪套屏保，卡片带 ✓ 和白边。
 * 壁纸本身仍在桌面自带的整屏壁纸选择器（WallpaperController.showPicker）。
 */
public final class ScreensaverSettingsActivity extends Activity {

    private static final int[] INTERVAL_MINS = {0, 60, 1440};

    private SharedPreferences choices;
    private View styleClock;
    private View styleBing;
    private TextView clockCaption;
    private TextView bingCaption;
    private TextView modeLabel;
    private TextView idlePill;
    private TextView statusLabel;
    private ImageView bingThumb;
    private TextView bingPlaceholder;
    private View intervalLabel;
    private View[] segments;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_screensaver_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);

        styleClock = findViewById(R.id.styleClock);
        styleBing = findViewById(R.id.styleBing);
        clockCaption = findViewById(R.id.clockCaption);
        bingCaption = findViewById(R.id.bingCaption);
        modeLabel = findViewById(R.id.saverMode);
        idlePill = findViewById(R.id.idleClockChoice);
        statusLabel = findViewById(R.id.saverStatus);
        bingThumb = findViewById(R.id.bingThumb);
        bingPlaceholder = findViewById(R.id.bingPlaceholder);
        intervalLabel = findViewById(R.id.intervalLabel);
        segments = new View[]{
                findViewById(R.id.wpEveryTime), findViewById(R.id.wp1h), findViewById(R.id.wp1d)};

        styleClock.setOnClickListener(v -> setStyle("clock"));
        styleBing.setOnClickListener(v -> setStyle("bing"));

        idlePill.setOnClickListener(v -> {
            boolean now = choices.getBoolean("idle_clock_enabled", true);
            choices.edit().putBoolean("idle_clock_enabled", !now).apply();
            setResult(RESULT_OK);
            refresh();
        });

        for (int i = 0; i < segments.length; i++) {
            final int minutes = INTERVAL_MINS[i];
            segments[i].setOnClickListener(v -> {
                choices.edit().putInt("screensaver_interval_min", minutes).apply();
                refresh();
            });
        }

        findViewById(R.id.wpNextChoice).setOnClickListener(v -> {
            // 手动换一张：把上次换图时间清零，下次进屏保就会进一位
            choices.edit().putLong("screensaver_last_change", 0L).apply();
            Toast.makeText(this, "下次进屏保换新一张", Toast.LENGTH_SHORT).show();
            refresh();
        });

        findViewById(R.id.previewScreensaverChoice).setOnClickListener(v ->
                startActivity(new Intent(this, ClockActivity.class)));

        // 焦点描边交给 saver_card / saver_pill 的 selector，这里只更新底部提示
        styleClock.setOnFocusChangeListener((v, focused) -> {
            if (focused) statusLabel.setText("时间屏保：大号时钟，不联网也一直有");
        });
        styleBing.setOnFocusChangeListener((v, focused) -> {
            if (focused) statusLabel.setText("必应壁纸屏保：每天一张新风景，需要联网");
        });
        idlePill.setOnFocusChangeListener((v, focused) -> {
            if (focused) statusLabel.setText("关闭后待机再久也不进屏保");
        });
        findViewById(R.id.previewScreensaverChoice).setOnFocusChangeListener((v, focused) -> {
            if (focused) statusLabel.setText("按确定立即预览，任意键返回");
        });

        refresh();
        styleClock.requestFocus();
        loadBingThumb();
    }

    private void setStyle(String style) {
        if ("bing".equals(style) && BuildConfig.LITE) return;
        choices.edit().putString("screensaver_style", style).apply();
        setResult(RESULT_OK);
        if ("bing".equals(style)) {
            Toast.makeText(this, "壁纸屏保已开，电视空闲时会显示必应每日图", Toast.LENGTH_SHORT).show();
            // 后台先把图池备好，第一次进屏保就有图（不阻塞界面）
            new Thread(() -> BingScreensaver.ensurePool(this)).start();
        }
        refresh();
    }

    /** 本地已有的必应图当场显示；没有就留占位文案。 */
    private void loadBingThumb() {
        if (BuildConfig.LITE) {
            styleBing.setVisibility(View.GONE);
            return;
        }
        new Thread(() -> {
            File file = BingScreensaver.pickForNow(this);
            if (file == null || !file.isFile()) return;
            Bitmap bitmap = BingScreensaver.decode(file, 900, 460);
            if (bitmap == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                bingThumb.setImageBitmap(bitmap);
                bingPlaceholder.setVisibility(View.GONE);
            });
        }).start();
    }

    private void refresh() {
        boolean enabled = choices.getBoolean("idle_clock_enabled", true);
        boolean bing = "bing".equals(choices.getString("screensaver_style", "clock")) && !BuildConfig.LITE;

        modeLabel.setText(bing ? "当前：必应壁纸屏保" : "当前：时间屏保");
        idlePill.setText(enabled ? "屏保：开" : "屏保：关");
        idlePill.setSelected(enabled);

        clockCaption.setText(bing ? "时间" : "时间  ✓");
        bingCaption.setText(bing ? "必应每日壁纸  ✓" : "必应每日壁纸");

        // 只有壁纸屏保才需要换图间隔
        int visibility = (bing && enabled) ? View.VISIBLE : View.GONE;
        intervalLabel.setVisibility(visibility);
        for (View segment : segments) segment.setVisibility(visibility);
        findViewById(R.id.wpNextChoice).setVisibility(visibility);

        int current = choices.getInt("screensaver_interval_min", 60);
        for (int i = 0; i < segments.length; i++) {
            boolean selected = current == INTERVAL_MINS[i];
            segments[i].setSelected(selected);
            ((TextView) segments[i]).setTextColor(selected ? 0xFF14171C : 0xFFFFFFFF);
        }
    }
}

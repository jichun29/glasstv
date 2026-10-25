package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 内存清理（2026-10-23 K）：从「桌面设置」里拆出来的独立页。
 * 立即清理 / 自动清理开关 / 间隔 / 白名单。控制中心「内存清理」也进这一页。
 */
public final class MemoryCleanSettingsActivity extends Activity {

    private SharedPreferences choices;
    private TextView autoCleanValue;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_memory_clean_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        autoCleanValue = findViewById(R.id.autoCleanValue);

        findViewById(R.id.cleanNowChoice).setOnClickListener(v -> {
            int n = MemoryCleaner.cleanNow(this);
            Toast.makeText(this, n > 0 ? "已清理（" + n + " 个后台进程）" : "后台本来就很干净",
                    Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.autoCleanChoice).setOnClickListener(v -> {
            boolean now = choices.getBoolean("auto_clean_enabled", false);
            choices.edit().putBoolean("auto_clean_enabled", !now).apply();
            MemoryCleaner.reschedule(this);
            refresh();
        });

        int[] mins = {30, 60, 180};
        int[] ids = {R.id.interval30, R.id.interval60, R.id.interval180};
        for (int i = 0; i < 3; i++) {
            final int minutes = mins[i];
            findViewById(ids[i]).setOnClickListener(v -> {
                choices.edit().putInt("auto_clean_interval_min", minutes).apply();
                MemoryCleaner.reschedule(this);
                refresh();
            });
        }

        findViewById(R.id.cleanWhitelistChoice).setOnClickListener(v ->
                startActivity(new Intent(this, WhitelistActivity.class)));

        refresh();
        findViewById(R.id.cleanNowChoice).requestFocus();
    }

    private void refresh() {
        boolean on = choices.getBoolean("auto_clean_enabled", false);
        autoCleanValue.setText(on ? R.string.on : R.string.off);
        autoCleanValue.setTextColor(on ? 0xFF4ADE80 : 0x66FFFFFF);
        findViewById(R.id.autoCleanIntervalRow).setVisibility(on ? View.VISIBLE : View.GONE);

        int current = choices.getInt("auto_clean_interval_min", 60);
        int[] mins = {30, 60, 180};
        int[] ids = {R.id.interval30, R.id.interval60, R.id.interval180};
        for (int i = 0; i < 3; i++) findViewById(ids[i]).setSelected(current == mins[i]);
    }
}

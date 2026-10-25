package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 控制中心（2026-10-23 K：照上游 aerial-tv 改版）。
 *
 * 右上角弹出的毛玻璃面板，内容分三块：
 *  1. 大号时间日期 + 网络状态（右上）
 *  2. 三排 3×3 入口：信号源 / 网络 / 系统设置；桌面设置 / 屏保 / 壁纸；内存清理 / 检查更新 / 关于
 *  3. 底部守护状态 + 版本号
 *
 * 方向键自己接管：面板是透明 Activity，盖在桌面之上，如果交给系统做焦点搜索，
 * 焦点会跑到背后的桌面网格里（实测按右键会直接启动后面的 App）。所以这里用
 * 自己的九宫格导航，焦点永远出不去。不放音量亮度（遥控器自带，K 定）。
 */
public final class QuickSettingsActivity extends Activity {

    static final String EXTRA_SOURCE = "cc_source";
    static final String SOURCE_MENU_KEY = "menu_key";
    static final String SOURCE_SETTINGS_KEY = "settings_key";

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 三排磁贴，按行存放；方向键导航只认这张表。 */
    private final List<List<View>> grid = new ArrayList<>();
    private int focusRow;
    private int focusCol;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());

        // ---- 顶部：时间日期 + 网络 ----
        TextView date = findViewById(R.id.ccDate);
        date.setText(new SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(new Date()));
        refreshNetwork();

        // ---- 磁贴：3 × 3 ----
        LinearLayout row1 = findViewById(R.id.tiles1);
        LinearLayout row2 = findViewById(R.id.tiles2);
        LinearLayout row3 = findViewById(R.id.tiles3);
        grid.add(new ArrayList<>());
        grid.add(new ArrayList<>());
        grid.add(new ArrayList<>());

        addTile(0, row1, R.drawable.ic_input, R.string.cc_tile_inputs, TileKind.INPUTS);
        addTile(0, row1, R.drawable.ic_wifi, R.string.cc_tile_network, TileKind.NETWORK);
        addTile(0, row1, R.drawable.ic_settings, R.string.cc_tile_system_settings, TileKind.SYSTEM_SETTINGS);

        addTile(1, row2, R.drawable.ic_home_settings, R.string.cc_tile_settings, TileKind.LAUNCHER_SETTINGS);
        addTile(1, row2, R.drawable.ic_screensaver, R.string.cc_tile_screensaver, TileKind.SCREENSAVER);
        addTile(1, row2, R.drawable.ic_wallpaper, R.string.cc_tile_wallpaper, TileKind.WALLPAPER);

        addTile(2, row3, R.drawable.ic_clean, R.string.cc_tile_clean, TileKind.CLEAN);
        addTile(2, row3, R.drawable.ic_refresh, R.string.cc_tile_update, TileKind.CHECK_UPDATE);
        addTile(2, row3, R.drawable.ic_info, R.string.cc_tile_about, TileKind.ABOUT);

        // 第一枚先拿焦点
        if (!grid.get(0).isEmpty()) grid.get(0).get(0).requestFocus();

        // ---- 底部：守护状态 + 版本号 ----
        refreshStatus();
    }

    // ---- 磁贴 ---------------------------------------------------------------------------------

    private enum TileKind { INPUTS, NETWORK, SYSTEM_SETTINGS, LAUNCHER_SETTINGS, SCREENSAVER, CLEAN, CHECK_UPDATE, ABOUT, WALLPAPER }

    private void addTile(int rowIndex, LinearLayout row, int icon, int label, TileKind kind) {
        View tile = getLayoutInflater().inflate(R.layout.item_cc_tile, row, false);
        ImageView ic = tile.findViewById(R.id.tileIcon);
        TextView name = tile.findViewById(R.id.tileLabel);
        ic.setImageResource(icon);
        ic.setColorFilter(0xFFD8D8DC);
        name.setText(label);
        name.setTextColor(0xB3FFFFFF);
        tile.setAlpha(0.92f);
        name.setAlpha(0.9f);

        tile.setOnFocusChangeListener((v, focused) -> {
            ic.setColorFilter(focused ? 0xFF1A1A1C : 0xFFD8D8DC);
            name.setTextColor(focused ? 0xFFFFFFFF : 0xB3FFFFFF);
            name.setAlpha(focused ? 1f : 0.9f);
            v.animate().scaleX(focused ? 1.045f : 1f).scaleY(focused ? 1.045f : 1f)
                    .alpha(focused ? 1f : 0.92f)
                    .setDuration(170)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            if (focused) {
                for (int r = 0; r < grid.size(); r++) {
                    int c = grid.get(r).indexOf(v);
                    if (c >= 0) { focusRow = r; focusCol = c; break; }
                }
            }
        });

        tile.setOnClickListener(v -> activate(kind));
        row.addView(tile);
        grid.get(rowIndex).add(tile);
    }

    private void activate(TileKind kind) {
        switch (kind) {
            case INPUTS:
                finish();
                startActivity(new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_SHOW_INPUTS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            case NETWORK:
                finish();
                if (!TvSettings.openWifi(this)) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                break;
            case SYSTEM_SETTINGS:
                finish();
                if (!TvSettings.openSettings(this)) {
                    Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
                }
                break;
            case LAUNCHER_SETTINGS:
                finish();
                startActivity(new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_LAUNCHER_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            case SCREENSAVER:
                finish();
                startActivity(new Intent(this, ScreensaverSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case CLEAN:
                finish();
                startActivity(new Intent(this, MemoryCleanSettingsActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case WALLPAPER:
                finish();
                // 回到桌面的全屏壁纸选择器（WallpaperController.showPicker）：
                // 缩略图预览、轮播、必应每日壁纸都在那一页，不要另做简易列表页。
                startActivity(new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_SHOW_WALLPAPER)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            case CHECK_UPDATE:
                finish();
                startActivity(new Intent(this, UpdateActivity.class));
                break;
            case ABOUT:
                finish();
                // 用桌面自己的「关于」玻璃弹窗（MainActivity.showAbout），不跳系统关于页
                startActivity(new Intent(this, MainActivity.class)
                        .setAction(MainActivity.ACTION_SHOW_ABOUT)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
                break;
            default:
                break;
        }
    }

    // ---- 焦点导航：九宫格自己走，焦点出不去面板 ------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveFocus(0, -1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveFocus(0, 1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveFocus(-1, 0);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveFocus(1, 0);
                    return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void moveFocus(int rowDelta, int colDelta) {
        int row = Math.max(0, Math.min(focusRow, grid.size() - 1));
        List<View> current = grid.get(row);
        if (current.isEmpty()) return;
        int col = Math.max(0, Math.min(focusCol, current.size() - 1));

        int targetRow = rowDelta == 0 ? row : Math.max(0, Math.min(row + rowDelta, grid.size() - 1));
        List<View> target = grid.get(targetRow);
        if (target.isEmpty()) return;

        int targetCol;
        if (rowDelta != 0) {
            // 换排时按比例落到最近的一列，短排不会越界
            targetCol = Math.max(0, Math.min(col, target.size() - 1));
        } else {
            targetCol = Math.max(0, Math.min(col + colDelta, target.size() - 1));
        }

        focusRow = targetRow;
        focusCol = targetCol;
        target.get(targetCol).requestFocus();
    }

    // ---- 顶部网络状态 -------------------------------------------------------------------------

    private void refreshNetwork() {
        ImageView icon = findViewById(R.id.ccNetIcon);
        TextView name = findViewById(R.id.ccNetName);
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            if (ni == null || !ni.isConnected()) {
                icon.setImageResource(R.drawable.ic_ethernet);
                icon.setColorFilter(0x66FFFFFF);
                name.setText("未连接");
                name.setTextColor(0x66FFFFFF);
                return;
            }
            if (ni.getType() == ConnectivityManager.TYPE_WIFI) {
                icon.setImageResource(R.drawable.ic_wifi);
                icon.setColorFilter(0xCCFFFFFF);
                String ssid = null;
                try {
                    WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wm != null && wm.getConnectionInfo() != null) {
                        ssid = wm.getConnectionInfo().getSSID();
                        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                    }
                } catch (Exception ignored) {}
                name.setText(ssid != null && !ssid.isEmpty() ? ssid : "WiFi");
                name.setTextColor(0xCCFFFFFF);
            } else if (ni.getType() == ConnectivityManager.TYPE_ETHERNET) {
                icon.setImageResource(R.drawable.ic_ethernet);
                icon.setColorFilter(0xCCFFFFFF);
                name.setText("有线");
                name.setTextColor(0xCCFFFFFF);
            } else {
                icon.setImageResource(R.drawable.ic_wifi);
                icon.setColorFilter(0xCCFFFFFF);
                name.setText("已连接");
                name.setTextColor(0xCCFFFFFF);
            }
        } catch (Exception e) {
            icon.setVisibility(View.GONE);
            name.setText("");
        }
    }

    // ---- 底部状态 -------------------------------------------------------------------------------

    private void refreshStatus() {
        View dot = findViewById(R.id.ccGuardDot);
        TextView text = findViewById(R.id.ccGuardText);
        boolean guarded = HomeGuardService.isRunning();
        if (dot != null) {
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(guarded ? 0xFF4ADE80 : 0xFFFB923C);
            dot.setBackground(d);
        }
        if (text != null) {
            text.setText(guarded ? R.string.cc_guard_on : R.string.cc_guard_off);
            text.setTextColor(guarded ? 0x99FFFFFF : 0xFFFB923C);
        }
        TextView version = findViewById(R.id.ccVersion);
        if (version != null) {
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                version.setText("GlassTV " + (pi.versionName != null ? pi.versionName : ""));
            } catch (Exception ignored) {
                version.setText("GlassTV");
            }
        }
    }

    // ---- 生命周期 -------------------------------------------------------------------------------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) { finish(); return true; }
        if (keyCode == KeyEvent.KEYCODE_MENU) { finish(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isFinishing()) finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
    }
}

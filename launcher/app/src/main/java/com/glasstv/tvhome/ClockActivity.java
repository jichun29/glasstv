package com.glasstv.tvhome;

import android.app.Activity;
import android.graphics.Point;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;

/**
 * The clock screensaver shown by Home itself after home_idle_clock_minutes without input. Some TVs' own
 * screens (CVTE's live TV) do not keep the screen on, so the system screensaver would cut into programmes;
 * this one only ever starts from Home. Any key returns to Home.
 *
 * 2026-10-23 K：屏保样式在桌面设置里选「时间屏保 / 壁纸屏保」。
 * 壁纸 = 必应每日图（本地池按设定间隔轮换）。图没下完先显示纯黑时钟，下完自动换，不闪屏。
 */
public class ClockActivity extends Activity {

    private ClockScene scene;
    private boolean alive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_clock);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);
        scene = new ClockScene(getWindow().getDecorView());

        String style = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString("screensaver_style", "clock");
        if (!"bing".equals(style) || BuildConfig.LITE) return;

        final Point size = screenSize();
        // 先用本地已有的图（秒开），再后台补池子，下好了再换。
        File cached = BingScreensaver.pickForNow(this);
        if (cached != null) scene.applyPhoto(cached, size.x, size.y);

        new Thread(() -> {
            boolean downloaded = BingScreensaver.ensurePool(this);
            if (!alive) return;
            // 无论这次有没有新下载，都重新挑一张（跨天/池子刚建好都要刷新）
            File f = BingScreensaver.pickForNow(this);
            if (f == null || f.equals(cached)) return;
            runOnUiThread(() -> {
                if (alive) scene.applyPhoto(f, size.x, size.y);
            });
        }, "screensaver-bing").start();
    }

    private Point screenSize() {
        DisplayMetrics dm = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(dm);
        return new Point(dm.widthPixels, dm.heightPixels);
    }

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
        finish();   // never left behind in the back stack
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // The key that wakes the clock should not also act on Home, so it is consumed here.
        if (event.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }
}

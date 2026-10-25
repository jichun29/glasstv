package com.glasstv.tvhome;

import android.service.dreams.DreamService;

/**
 * Screensaver: the launcher's thin clock and date on black, over a slow ambient glow (ClockScene).
 * No network, no images: cheap enough for a 1 GB TV.
 */
public class ClockDream extends DreamService {

    private ClockScene scene;
    private FlipScene flip;
    /** 存成字段才能 detach，见 Weather.detach 的说明。 */
    private Weather.Listener weatherListener;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);   // any key wakes the TV back to where it was
        setFullscreen(true);
        setScreenBright(false);

        // 翻页时钟（2026-10-25 K）：两块翻页牌 + 天气地区 + 月日，和 Home 进来的那个同一套画面。
        String style = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getString("screensaver_style", "clock");
        if ("flip".equals(style)) {
            setContentView(R.layout.dream_flip);
            flip = new FlipScene(this, getWindow().getDecorView());
            weatherListener = now -> {
                if (flip != null) flip.setWeather(now);
            };
            Weather.init(this, weatherListener);
            return;
        }
        setContentView(R.layout.dream_clock);
        scene = new ClockScene(getWindow().getDecorView());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        if (flip != null) flip.start();
        else if (scene != null) scene.start();
    }

    @Override
    public void onDreamingStopped() {
        if (flip != null) flip.stop();
        else if (scene != null) scene.stop();
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        if (scene != null) scene.stop();
        if (flip != null) { flip.stop(); flip = null; }
        Weather.detach(weatherListener);
        weatherListener = null;
        super.onDetachedFromWindow();
    }
}

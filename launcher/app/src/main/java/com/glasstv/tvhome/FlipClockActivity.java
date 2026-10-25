package com.glasstv.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

/**
 * 翻页时钟屏保（2026-10-25 K）：两块大牌显示 HH、两块显示 mm，下面天气地区 + 年月日周几。
 *
 * 画面本体在 FlipScene（和系统屏保 ClockDream 共用），这里只管生命周期与「任意键退出」。
 * 为什么要单独一个 Activity：翻页牌要整屏自己排版（牌子尺寸按屏宽算、信息行位置也不同），
 * 跟细字时钟放一个布局在 720p/1080p 上都算不准。
 */
public class FlipClockActivity extends Activity {

    private FlipScene scene;
    /** 存成字段才能 detach：每次 init 传新 lambda 是另一个实例，退不掉旧的。 */
    private Weather.Listener weatherListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dream_flip);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE);

        scene = new FlipScene(this, getWindow().getDecorView());
        weatherListener = now -> {
            if (scene != null) scene.setWeather(now);
        };
        Weather.init(this, weatherListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (scene != null) scene.start();
    }

    @Override
    protected void onPause() {
        if (scene != null) scene.stop();
        super.onPause();
        finish();
    }

    @Override
    protected void onDestroy() {
        // 屏保销毁后把静态天气回调解掉：否则下一次请求回来会往已销毁的 View 写文本，
        // 并且新起的屏保会因为「回调被旧实例占着」而在第一屏拿不到天气。
        Weather.detach(weatherListener);
        weatherListener = null;
        scene = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // 只在按下时退一次：ACTION_UP 也退会让长按/连发多次走 finish()，
        // 虽然 Activity 已经 finishing 不会再崩，但会跟淡出动画抢一次转场。
        if (event.getAction() == KeyEvent.ACTION_DOWN) finish();
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

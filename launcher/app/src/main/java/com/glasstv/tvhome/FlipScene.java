package com.glasstv.tvhome;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;

/**
 * 翻页时钟的画面本体（layout dream_flip），照爆款 Fliqlo 的排面：
 * 两块大牌子（小时 / 分钟）并排居中，中间不留冒号，下面挂一行天气地区、一行年月日周几。
 *
 * 抽出来给两个入口共用：FlipClockActivity（Home 闲置 / 返回键进来的屏保）和 ClockDream
 * （系统屏保服务）。两边只负责生命周期，画面和每秒走字都在这里。
 * 第一次摆牌子不做动画，之后分钟/小时变化才翻，避免刚进来两块一起翻一下。
 */
final class FlipScene {

    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final TextView weatherView;
    private final TextView dateView;

    private FlipCard hours, minutes;
    private String lastHh = "", lastMm = "";
    private boolean started;
    private boolean alive = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            render();
            handler.postDelayed(this, 1000);
        }
    };

    FlipScene(Context context, View root) {
        weatherView = root.findViewById(R.id.flipWeather);
        dateView = root.findViewById(R.id.flipDate);

        // 牌子尺寸照 Fliqlo 官网原图的比例：单块约为屏宽的 21%，高 ≈ 宽 × 1.06（略高一点的方形），
        // 两块之间的缝很窄（约屏宽 1%），不加冒号。
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        int cardW = Math.round(screenW * 0.21f);
        int cardH = Math.round(cardW * 1.02f);
        int textPx = Math.round(cardH * 0.75f);
        int gap = Math.round(screenW * 0.010f);

        LinearLayout row = root.findViewById(R.id.flipRow);
        hours = new FlipCard(context, cardW, cardH, textPx);
        minutes = new FlipCard(context, cardW, cardH, textPx);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cardW, cardH);
        lp.rightMargin = gap;
        row.addView(hours, lp);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(cardW, cardH);
        row.addView(minutes, rp);

        row.setGravity(Gravity.CENTER_HORIZONTAL);
    }

    void start() {
        alive = true;
        render();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    void stop() {
        alive = false;
        handler.removeCallbacks(tick);
    }

    /** 天气单独喂进来：数据是异步的，不应该卡住走字。 */
    void setWeather(Weather.Now now) {
        if (weatherView == null) return;
        if (now == null) {
            weatherView.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (now.place != null && !now.place.isEmpty()) sb.append(now.place).append(' ');
        sb.append(now.celsius).append("°");
        if (now.kind != null && !now.kind.isEmpty()) sb.append(' ').append(now.kind);
        weatherView.setText(sb.toString());
    }

    private void render() {
        Date now = new Date();
        String hh = new SimpleDateFormat("HH", Locale.CHINA).format(now);
        String mm = new SimpleDateFormat("mm", Locale.CHINA).format(now);

        boolean animate = started;    // 第一次进来直接摆好，不翻
        started = true;
        if (!hh.equals(lastHh)) {
            hours.setValue(hh, animate);
            lastHh = hh;
        }
        if (!mm.equals(lastMm)) {
            minutes.setValue(mm, animate);
            lastMm = mm;
        }
        if (dateView != null) {
            int dow = new GregorianCalendar().get(Calendar.DAY_OF_WEEK) - 1;
            dateView.setText(new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(now)
                    + "  " + WEEK[Math.max(0, Math.min(6, dow))]);
        }
    }
}

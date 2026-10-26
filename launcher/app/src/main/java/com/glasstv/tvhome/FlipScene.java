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
 * 两块大牌子（小时 / 分钟）并排居中，中间不留冒号，下面挂一行脚注（天气 + 月日周几）。
 *
 * 抽出来给两个入口共用：FlipClockActivity（Home 闲置 / 返回键进来的屏保）和 ClockDream
 * （系统屏保服务）。两边只负责生命周期，画面和每秒走字都在这里。
 * 第一次摆牌子不做动画，之后分钟/小时变化才翻，避免刚进来两块一起翻一下。
 *
 * 脚注为什么是一行而不是两行（K 2026-10-26 定）：两行文字会在钟下面形成「第二段内容」，
 * 跟大牌子抢层次；合成一行、字号统一、颜色压暗后，读起来就是时间下面的一行小字。
 * 为了长度稳定，**去掉年份**——看屏保的人不需要被提醒今年是哪年，而「2026年」四个字
 * 会让整行宽度随年份/城市名忽长忽短，居中的那一行会跟着左右跳。
 */
final class FlipScene {

    private static final String[] WEEK = {"周日", "周一", "周二", "周三", "周四", "周五", "周六"};
    /** 天气与日期之间的分隔：用中圆点不用竖线，竖线读起来像分栏、太硬。 */
    private static final String DOT = "  ·  ";

    /**
     * 卡片档位：小 / 中 / 大，值是「单块卡宽占屏幕宽度的比例」。
     * 默认「大」= 0.30（两块合计 60% 屏宽）；中 0.25；小 0.20。
     * 之前用的 0.34 是「大」以上，实测在 55 寸上偏挤、边距只剩 300px，所以把大定在 0.30。
     */
    static final float[] SIZE_RATIO = {0.20f, 0.25f, 0.30f};
    /** 卡片高宽比：Fliqlo 实测略竖，1.12 比方形更像翻页牌。 */
    private static final float CARD_ASPECT = 1.12f;
    /** 两块牌之间的缝 = 卡宽 × 0.085（Fliqlo 实测 8~9%）。 */
    private static final float CARD_GAP = 0.085f;

    /**
     * 防烧屏的位移网格：3 列 × 2 行共 6 个停靠点，每格间距 20dp。
     * 一天下来每个点都会轮到，任何一块白色数字都不会常年压在同一片像素上。
     * 为什么用网格而不是随机：随机有概率连续两次落在同一个角落，网格保证全覆盖。
     */
    private static final int GRID_X = 3, GRID_Y = 2;
    private static final float STEP_DP = 20f;
    /** 长时间挂机后自动降亮：每 10 分钟降一档，降到 0.86 为止（约 70 分钟后稳住）。 */
    private static final long DIM_MS = 10 * 60 * 1000L;
    private static final float DIM_STEP = 0.02f;
    private static final float DIM_FLOOR = 0.86f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final View content;
    private final TextView footnote;
    /** 防烧屏网格当前停靠点，每分钟前进一格。 */
    private int driftIndex;
    /** 进屏保的时刻与当前亮度：挂得越久越暗，降低静态高亮的累积伤害。 */
    private long startedAt;
    private float dimAlpha = 1f;

    private FlipCard hours, minutes;
    private String lastHh = "", lastMm = "";
    /** 天气是异步喂进来的，日期是每秒算的，两边都变了要重新拼这一行。 */
    private String weatherText = "";
    /** 日期串只有「日」变了才重算，避免每秒 new 格式器。 */
    private String dateText;
    private int lastDay = -1;
    private SimpleDateFormat dateFormat;
    private boolean started;
    private boolean alive = true;
    /** 12 小时制时脚注里要带「上午/下午」，跟桌面时钟同一个设置（K 2026-10-26）。 */
    private boolean hour12;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!alive) return;
            render();
            handler.postDelayed(this, 1000);
        }
    };

    FlipScene(Context context, View root) {
        content = root.findViewById(R.id.flipContent);
        footnote = root.findViewById(R.id.flipFootnote);
        hour12 = Config.hour12(context);

        // 牌子尺寸照 Fliqlo 官方截图量出来的比例（2026-10-26 重做）：
        //   · 单块略竖：高 = 宽 × 1.12（之前 1.02 太方，两块拼起来像个方盒子）
        //   · 两块之间的缝 = 卡宽 × 0.085（之前只有 1.4%，缝细到看不出，两条卡连成一块面板，
        //     再被中缝一横切，就成了「四宫格」）
        //   · 字号取牌高的 0.84——condensed 粗体的数字实际墨迹高约 0.734em，
        //     落下来正好占牌高的 61%，接近 Fliqlo 的饱满度。
        // 尺寸由 size 决定：0 小 / 1 中 / 2 大，用户在「壁纸与屏保 → 屏保」里选。
        int screenW = context.getResources().getDisplayMetrics().widthPixels;
        float sizeRatio = SIZE_RATIO[Config.flipCardSize(context)];
        android.util.Log.i("FlipScene", "card size=" + Config.flipCardSize(context)
                + " ratio=" + sizeRatio);

        int cardW = Math.round(screenW * sizeRatio);
        int cardH = Math.round(cardW * CARD_ASPECT);
        // 宽高都取偶数：FlipCard 内部按 half = H/2 切上下两片、按 cardWidth 算数字居中，
        // 奇数会让上下两片差 1px（中缝偏一格）且牌的实测高度比布局高度少 1px（底边露一条黑线）。
        cardW -= cardW & 1;
        cardH -= cardH & 1;
        int textPx = Math.round(cardH * 0.84f);
        // 缝按卡宽算，不按屏宽算：换尺寸时缝也跟着缩，观感才一致（Fliqlo 也是这个比例）。
        int gap = Math.max(8, Math.round(cardW * CARD_GAP));

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
        // 每次进屏保都从头开始：亮度回到 100%、停靠点从中心再走一遍，
        // 不继承上一次（比如昨晚挂了一夜）的暗度，否则白天进屏保会发灰。
        // 计时从「进屏保」这一刻开始算，不能等第一次分钟跳变（那样最多白亮 60 秒）。
        startedAt = System.currentTimeMillis();
        dimAlpha = 1f;
        driftIndex = 0;
        started = false;   // 重新进屏保时数字直接摆好，不翻一下再滑一下
        if (content != null) {
            content.animate().cancel();   // 上一次挂机残留的位移/降亮动画先掐掉，否则会跟复位打架
            content.setAlpha(1f);
            content.setTranslationX(0f);
            content.setTranslationY(0f);
        }
        render();
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, 1000);
    }

    void stop() {
        alive = false;
        handler.removeCallbacks(tick);
    }

    /** 天气单独喂进来：数据是异步的，不应该卡住走字。取不到就留空，不占位符。 */
    void setWeather(Weather.Now now) {
        if (now == null) {
            weatherText = "";
        } else {
            StringBuilder sb = new StringBuilder();
            if (now.place != null && !now.place.isEmpty()) sb.append(now.place).append(' ');
            sb.append(now.celsius).append("°");
            if (now.kind != null && !now.kind.isEmpty()) sb.append(' ').append(now.kind);
            weatherText = sb.toString();
        }
        updateFootnote();
    }

    private void render() {
        Date now = new Date();
        // 时间格式（K 2026-10-26）：桌面设置里选 12 小时制就让翻页牌走 h，脚注里补上午/下午。
        String hh = new SimpleDateFormat(hour12 ? "h" : "HH", Locale.CHINA).format(now);
        String mm = new SimpleDateFormat("mm", Locale.CHINA).format(now);

        boolean animate = started;    // 第一次进来直接摆好，不翻
        started = true;
        // 防烧屏：大块白色数字长时间钉在同一点会留印（屏保本来就常年挂着）。
        // 每分钟换一个停靠点并缓慢降亮。首次进来不做，避免入画就滑一下。
        if (!mm.equals(lastMm) && animate) moveAndDim();

        if (!hh.equals(lastHh)) {
            hours.setValue(hh, animate);
            lastHh = hh;
        }
        if (!mm.equals(lastMm)) {
            minutes.setValue(mm, animate);
            lastMm = mm;
        }
        updateFootnote();
    }

    /**
     * 拼脚注：天气 + 月日周几。
     * 天气为空时整行只剩日期，**不能留下孤零零的分隔点**（离线/定位失败时的常态）。
     *
     * 每秒都调，所以日期串只在「日」变化时重算：new SimpleDateFormat + GregorianCalendar
     * 每秒各建一次在 1GB 内存的电视盒子上是白白的 GC 压力（屏保要挂一整夜）。
     */
    private void updateFootnote() {
        if (footnote == null) return;
        int day = new GregorianCalendar().get(Calendar.DAY_OF_MONTH);
        if (day != lastDay || dateText == null) {
            lastDay = day;
            int dow = new GregorianCalendar().get(Calendar.DAY_OF_WEEK) - 1;
            if (dateFormat == null) dateFormat = new SimpleDateFormat("M月d日", Locale.CHINA);
            dateText = dateFormat.format(new Date())
                    + " " + WEEK[Math.max(0, Math.min(6, dow))];
        }
        String line = weatherText.isEmpty() ? dateText : weatherText + DOT + dateText;
        if (hour12) line = new SimpleDateFormat("a", Locale.CHINA).format(new Date()) + "  " + line;
        if (!line.equals(footnote.getText().toString())) footnote.setText(line);
    }

    /**
     * 防烧屏位移：整组内容（牌子 + 脚注）按 3×2 网格轮流停靠，每格 20dp，一分钟换一次。
     *
     * 为什么不是小随机抖动（4.0.4 初版是 ±3dp 随机）：屏幕中间那块白色数字笔画常年重叠，
     * ±3dp 只挪了 6 个像素，OLED 上照样会积累；而且随机有概率连着几次落回同一角。
     * 换成网格后一天之内 6 个点全部走到，数字边缘覆盖的像素范围是 40dp × 20dp，
     * 屏幕上完全看不出来（牌子两边各有 190dp 余量），但足以让像素换位。
     *
     * 位移和降亮**必须写在同一个动画里**：ViewPropertyAnimator 是每个 View 一个实例，
     * 先 animate().translationX().start() 再 animate().alpha().setDuration(4000).start()，
     * 后一次会把前一次还没跑完的位移参数连同 duration 一起改写
     * （实测位移变成 4 秒内只走了 40px 的 1/4，停在半路）。所以两条属性合并成一次提交。
     */
    private void moveAndDim() {
        if (content == null) return;

        int idx = (driftIndex++) % (GRID_X * GRID_Y);
        float d = content.getResources().getDisplayMetrics().density;
        float tx = (idx % GRID_X - (GRID_X - 1) / 2f) * STEP_DP * d;
        float ty = (idx / GRID_X - (GRID_Y - 1) / 2f) * STEP_DP * d;

        // 长时间挂机逐渐降亮：每 10 分钟降 2%，最低 86%。
        // 电视屏保经常挂一整夜，白字面积大会累积烧屏；18% 的渐变人眼几乎无感，但伤害显著下降。
        // 有下限是防止降到让人以为电视坏了。
        long minutes = (System.currentTimeMillis() - startedAt) / DIM_MS;
        float target = Math.max(DIM_FLOOR, 1f - DIM_STEP * minutes);

        content.animate()
                .translationX(tx)
                .translationY(ty)
                .alpha(target)
                .setDuration(1200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator())
                .start();
        dimAlpha = target;
    }
}

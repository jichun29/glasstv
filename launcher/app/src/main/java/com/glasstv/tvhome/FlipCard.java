package com.glasstv.tvhome;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Fliqlo 风翻页牌的一张（2026-10-25 K）：一块牌子装两位数字（小时「23」或分钟「41」）。
 *
 * 拟物要点（照爆款那个的光影逻辑，不是扁平卡片）：
 *   · 卡片是「上亮 → 中暗 → 下微亮」的竖直渐变，再叠顶部细高光和一圈暗边，做出塑料片的厚度
 *   · 中缝三层：上半底部的下压暗影 → 纯黑缝 → 下半顶部的极淡亮边，读起来像两片叠在一起
 *   · 下半数字比上半暗一档（0.93），模拟下片被上片遮住的环境光
 *
 * 怎么做翻动：上下两半分开画——一个满高的 TextView 塞进只有一半高的容器里 clipChildren，
 * 露出来的就是上半/下半。换数字时旧的上半张绕底边向下翻 90°，翻到底换字，下片再从 90° 翻回。
 */
final class FlipCard extends FrameLayout {

    private final TextView topStatic;
    private final TextView bottomStatic;
    private final TextView topFlip;
    private final TextView bottomFlip;
    private final FrameLayout topFlipBox;
    private final FrameLayout bottomFlipBox;
    private final int half;
    private final int height;

    private String value = "";

    FlipCard(Context context, int width, int cardHeight, int textSizePx) {
        super(context);
        height = cardHeight;
        half = cardHeight / 2;
        float radius = Math.min(width, height) * 0.045f;
        float d = getResources().getDisplayMetrics().density;

        // 卡片底：上亮 → 中暗 → 下稍亮，像一块受顶光照着的塑料翻页片
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF20242B, 0xFF14171C, 0xFF191C22});
        face.setCornerRadius(radius);

        // 顶部 1px 高光 + 一圈 1px 暗边：这两笔是「厚度」的来源
        GradientDrawable topLight = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x2EFFFFFF, 0x00FFFFFF});
        topLight.setCornerRadius(radius);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(0x00000000);
        edge.setCornerRadius(radius);
        edge.setStroke(Math.max(1, Math.round(d)), 0x33000000);

        setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{edge, face, topLight}));
        setClipChildren(true);

        topStatic = digits(context, textSizePx);
        bottomStatic = digits(context, textSizePx);
        bottomStatic.setAlpha(0.93f);   // 下片受上片遮挡，略暗一档
        topFlip = digits(context, textSizePx);
        bottomFlip = digits(context, textSizePx);
        bottomFlip.setAlpha(0.93f);

        FrameLayout topBox = new FrameLayout(context);
        topBox.setClipChildren(true);
        topBox.addView(topStatic, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        addView(topBox, boxParams(half, Gravity.TOP));

        FrameLayout bottomBox = new FrameLayout(context);
        bottomBox.setClipChildren(true);
        bottomStatic.setTranslationY(-half);
        bottomBox.addView(bottomStatic, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        addView(bottomBox, boxParams(half, Gravity.BOTTOM));

        topFlipBox = new FrameLayout(context);
        topFlipBox.setClipChildren(true);
        topFlipBox.addView(topFlip, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        topFlipBox.setVisibility(View.INVISIBLE);
        addView(topFlipBox, boxParams(half, Gravity.TOP));

        bottomFlipBox = new FrameLayout(context);
        bottomFlipBox.setClipChildren(true);
        bottomFlip.setTranslationY(-half);
        bottomFlipBox.addView(bottomFlip, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        bottomFlipBox.setVisibility(View.INVISIBLE);
        addView(bottomFlipBox, boxParams(half, Gravity.BOTTOM));

        buildSeam(context, width);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setCameraDistance(height * 5f);
    }

    /**
     * 中缝：三层叠出来的「缝」，不是一根硬线。
     * 上部薄雾（越靠缝越黑）→ 一道约短边 1.2% 的纯黑缝 → 下部一条极淡亮边（下片受光的边沿）。
     */
    private void buildSeam(Context context, int width) {
        int seamH = Math.max(3, Math.round(Math.min(width, height) * 0.012f));
        int shadowH = Math.round(seamH * 2.6f);

        View upperShadow = gradient(context, 0x00000000, 0x9E000000, shadowH);
        LayoutParams up = new LayoutParams(LayoutParams.MATCH_PARENT, shadowH);
        up.gravity = Gravity.CENTER_VERTICAL;
        up.topMargin = -seamH / 2 - shadowH;
        addView(upperShadow, up);

        View seam = new View(context);
        seam.setBackgroundColor(0xFF030406);
        LayoutParams sp = new LayoutParams(LayoutParams.MATCH_PARENT, seamH);
        sp.gravity = Gravity.CENTER_VERTICAL;
        addView(seam, sp);

        View lowerLight = gradient(context, 0x3DFFFFFF, 0x00FFFFFF, Math.max(2, seamH));
        LayoutParams lo = new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(2, seamH));
        lo.gravity = Gravity.CENTER_VERTICAL;
        lo.topMargin = seamH / 2 + 1;
        addView(lowerLight, lo);
    }

    /** 竖直渐变的小蒙层。 */
    private View gradient(Context context, int from, int to, int h) {
        View v = new View(context);
        v.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{from, to}));
        return v;
    }

    private FrameLayout.LayoutParams boxParams(int h, int gravity) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, h);
        p.gravity = gravity;
        return p;
    }

    /** 白字、粗、压缩：Fliqlo 那种窄长的数字，比常规字体更显大。 */
    private TextView digits(Context context, int textSizePx) {
        TextView t = new TextView(context);
        t.setGravity(Gravity.CENTER);
        t.setTextColor(0xFFF7F8FA);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setLetterSpacing(-0.02f);
        // Fliqlo 是窄体粗字：sans-serif-condensed + BOLD 最接近（Android 自带，不用塞字体文件）
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setShadowLayer(Math.max(1f, textSizePx * 0.028f), 0f, textSizePx * 0.014f, 0xB3000000);
        return t;
    }

    /** 换数字；animate=false 直接跳到目标（首次显示、预览缩略图）。 */
    void setValue(String nextValue, boolean animate) {
        String next = nextValue == null ? "00" : nextValue;
        if (next.equals(value)) return;
        final String previous = value;
        value = next;

        if (!animate || previous.isEmpty()) {
            topStatic.setText(next);
            bottomStatic.setText(next);
            return;
        }

        // 下半先换成新数字（被翻进来的那块盖住），上半还是旧的
        bottomStatic.setText(next);
        topFlip.setText(previous);
        topFlipBox.setVisibility(View.VISIBLE);
        topFlipBox.setPivotY(half);
        topFlipBox.setRotationX(0f);
        topFlipBox.setCameraDistance(height * 5f);

        topFlipBox.animate()
                .rotationX(-90f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    topFlipBox.setVisibility(View.INVISIBLE);
                    topFlipBox.setRotationX(0f);
                    topStatic.setText(value);

                    bottomFlip.setText(value);
                    bottomFlipBox.setVisibility(View.VISIBLE);
                    bottomFlipBox.setPivotY(0f);
                    bottomFlipBox.setCameraDistance(height * 5f);
                    bottomFlipBox.setRotationX(90f);
                    bottomFlipBox.animate()
                            .rotationX(0f)
                            .setDuration(220)
                            .setInterpolator(new AccelerateDecelerateInterpolator())
                            .withEndAction(() -> bottomFlipBox.setVisibility(View.INVISIBLE));
                })
                .start();
    }
}

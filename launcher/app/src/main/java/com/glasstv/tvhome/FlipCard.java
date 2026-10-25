package com.glasstv.tvhome;

import android.content.Context;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
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

    /** 整次翻页的总时长：上片下落 + 下片落下各占一半。220ms 两段其实是 440ms，偏肉。 */
    private static final int FLIP_MS = 340;

    private final TextView topStatic;
    private final TextView bottomStatic;
    private final TextView topFlip;
    private final TextView bottomFlip;
    private final FrameLayout topFlipBox;
    private final FrameLayout bottomFlipBox;
    /** 上半片高度（= 中缝位置）；下半片高度 = height - half，奇数高度时不会漏出 1px 黑边。 */
    private final int half;
    private final int height;
    private final int cardWidth;

    private String value = "";

    FlipCard(Context context, int width, int cardHeight, int textSizePx) {
        super(context);
        height = cardHeight;
        cardWidth = width;
        half = height / 2;
        float radius = Math.min(width, height) * 0.045f;
        float d = getResources().getDisplayMetrics().density;

        // 卡片底：上亮 → 中暗 → 下稍亮，像一块受顶光照着的塑料翻页片
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF212630, 0xFF14171C, 0xFF191D24});
        face.setCornerRadius(radius);

        // 顶部 1px 高光 + 一圈暗边 + 内侧一圈极淡亮边：这三笔是「厚度」的来源。
        // 只有暗边时，卡片在纯黑背景上会像一块贴纸（暗边跟背景糊在一起），
        // 内侧亮边把塑料片的受光棱线勾出来才立得住。
        GradientDrawable topLight = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x38FFFFFF, 0x00FFFFFF});
        topLight.setCornerRadius(radius);
        GradientDrawable innerLight = new GradientDrawable();
        innerLight.setColor(0x00000000);
        innerLight.setCornerRadius(radius);
        innerLight.setStroke(Math.max(1, Math.round(d)), 0x14FFFFFF);
        GradientDrawable edge = new GradientDrawable();
        edge.setColor(0x00000000);
        edge.setCornerRadius(radius);
        edge.setStroke(Math.max(1, Math.round(d)), 0x33000000);

        setBackground(new LayerDrawable(new android.graphics.drawable.Drawable[]{
                edge, face, innerLight, topLight}));
        setClipChildren(true);

        // 翻页的两片自己也要有牌面底色：否则翻转时看到的是「一个透明层上的数字在转」，
        // 底下新数字会透出来，读成双层数字。上半片只圆上面的两个角、下半片只圆下面两个角，
        // 拼在缝口才是平的。
        GradientDrawable flipTopFace = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF212630, 0xFF14171C});
        flipTopFace.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        GradientDrawable flipBottomFace = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xFF14171C, 0xFF191D24});
        flipBottomFace.setCornerRadii(new float[]{0, 0, 0, 0, radius, radius, radius, radius});

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
        addView(bottomBox, boxParams(height - half, Gravity.BOTTOM));

        topFlipBox = new FrameLayout(context);
        topFlipBox.setClipChildren(true);
        topFlipBox.setBackground(flipTopFace);
        topFlipBox.addView(topFlip, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        topFlipBox.setVisibility(View.INVISIBLE);
        addView(topFlipBox, boxParams(half, Gravity.TOP));

        bottomFlipBox = new FrameLayout(context);
        bottomFlipBox.setClipChildren(true);
        bottomFlipBox.setBackground(flipBottomFace);
        bottomFlip.setTranslationY(-half);
        bottomFlipBox.addView(bottomFlip, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, height));
        bottomFlipBox.setVisibility(View.INVISIBLE);
        addView(bottomFlipBox, boxParams(height - half, Gravity.BOTTOM));

        buildSeam(context, width);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setCameraDistance(height * 5f);
    }

    /**
     * 中缝：三层叠出来的「缝」，不是一根硬线。
     * 上部薄雾（越靠缝越黑）→ 一道约短边 1.2% 的纯黑缝 → 下部一条极淡亮边（下片受光的边沿）。
     *
     * 三块都用 TOP 定位 + 精确 topMargin，不用 CENTER_VERTICAL：
     * 卡片高度是奇数时 CENTER_VERTICAL 会把缝放在 half 与 half+1 之间，
     * 跟两片真实的交界（half）差 1px，放大看能看到数字被切偏。
     */
    private void buildSeam(Context context, int width) {
        int seamH = Math.max(3, Math.round(Math.min(width, height) * 0.012f));
        int shadowH = Math.round(seamH * 2.6f);

        // 渐变方向：从透明到黑，所以它必须整块贴在缝的上方（底部最黑、紧接缝）
        View upperShadow = gradient(context, 0x00000000, 0x9E000000, shadowH);
        LayoutParams up = new LayoutParams(LayoutParams.MATCH_PARENT, shadowH);
        up.gravity = Gravity.TOP;
        up.topMargin = half - seamH / 2 - shadowH;
        addView(upperShadow, up);

        View seam = new View(context);
        seam.setBackgroundColor(0xFF030406);
        LayoutParams sp = new LayoutParams(LayoutParams.MATCH_PARENT, seamH);
        sp.gravity = Gravity.TOP;
        sp.topMargin = half - seamH / 2;
        addView(seam, sp);

        // 下半片的受光亮边：白到透明，紧贴在缝的下沿
        View lowerLight = gradient(context, 0x3DFFFFFF, 0x00FFFFFF, Math.max(2, seamH));
        LayoutParams lo = new LayoutParams(LayoutParams.MATCH_PARENT, Math.max(2, seamH));
        lo.gravity = Gravity.TOP;
        lo.topMargin = half + seamH / 2 + 1;
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
        t.setTextColor(0xFFEDEFF2);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSizePx);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setLetterSpacing(-0.02f);
        // Fliqlo 是窄体粗字：sans-serif-condensed + BOLD 最接近（Android 自带，不用塞字体文件）
        t.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        t.setShadowLayer(Math.max(1f, textSizePx * 0.028f), 0f, textSizePx * 0.014f, 0xB3000000);
        return t;
    }

    /**
     * 换数字；animate=false 直接跳到目标（首次显示、预览缩略图）。
     *
     * 翻页顺序必须是「上半先翻、下半后落」，而且任何时刻露在外面的一半不能是新一半：
     *   ① 旧上半片往下翻 90° —— 它后面藏着新的上半，下半仍是旧的
     *   ② 翻片落地后，新的下半片从缝口落下来盖住旧下半
     *   ③ 落地那一刻才把下半的静态字换成新值，再藏起翻片
     * 之前是「一开始就把下半换成新值」，于是分钟跳变时下半先变、上半后翻，
     * 肉眼能看到数字先跳一下再翻，是明显的破功。
     */
    void setValue(String nextValue, boolean animate) {
        final String next = nextValue == null ? "00" : nextValue;
        if (next.equals(value)) return;
        final String previous = value;
        value = next;

        if (!animate || previous.isEmpty()) {
            setDigits(topStatic, next);
            setDigits(bottomStatic, next);
            return;
        }

        // 第①步：新的上半先放到底下（被翻片挡住），旧上半做翻片，下半维持旧值
        setDigits(topStatic, next);
        setDigits(topFlip, previous);
        topFlipBox.setVisibility(View.VISIBLE);
        topFlipBox.setPivotY(half);
        topFlipBox.setRotationX(0f);
        topFlipBox.setCameraDistance(height * 5f);

        topFlipBox.animate()
                .rotationX(-90f)
                .setDuration(FLIP_MS / 2)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    topFlipBox.setVisibility(View.INVISIBLE);
                    topFlipBox.setRotationX(0f);

                    // 第②步：新的下半片从缝口落下来，盖住旧下半
                    setDigits(bottomFlip, next);
                    bottomFlipBox.setVisibility(View.VISIBLE);
                    bottomFlipBox.setPivotY(0f);
                    bottomFlipBox.setCameraDistance(height * 5f);
                    bottomFlipBox.setRotationX(90f);
                    bottomFlipBox.animate()
                            .rotationX(0f)
                            .setDuration(FLIP_MS / 2)
                            .setInterpolator(new DecelerateInterpolator())
                            .withEndAction(() -> {
                                // 第③步：落地才换静态字，避免翻片还没落到底就露新值
                                setDigits(bottomStatic, value);
                                bottomFlipBox.setVisibility(View.INVISIBLE);
                                bottomFlipBox.setRotationX(0f);
                            })
                            .start();
                })
                .start();
    }

    /**
     * 写数字并做光学居中。
     *
     * TextView 的 gravity=center 居中的是「字宽盒」（含字形左右留白），不是墨迹：
     * 「1」的字宽和「0」一样但墨迹窄、且偏在盒子左侧，于是「01」整组会往左歪一截，
     * 右边空出的黑边比左边多，两块牌一松一满（实测量过：牌心 682.5px，墨迹心 651px）。
     * 这里按 Paint 量出墨迹框，用 translationX 把墨迹中心对到牌心，
     * 每个数字都独立对中，翻分钟时也不会横向跳。
     */
    private void setDigits(TextView view, String text) {
        view.setText(text);
        Paint paint = view.getPaint();
        Rect ink = new Rect();
        paint.getTextBounds(text, 0, text.length(), ink);
        float advance = paint.measureText(text);
        float inkCenter = (cardWidth - advance) / 2f + (ink.left + ink.right) / 2f;
        view.setTranslationX(cardWidth / 2f - inkCenter);
    }
}

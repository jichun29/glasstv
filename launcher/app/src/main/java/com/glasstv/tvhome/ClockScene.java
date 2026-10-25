package com.glasstv.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;

import java.io.File;
import java.util.Random;

/**
 * The clock screensaver's scene (layout dream_clock): the thin clock and date on black over a slow ambient
 * glow. Every minute the clock drifts to a new spot and the glow shifts colour, so nothing burns in.
 * Shared by ClockDream (the system screensaver) and ClockActivity (Home left idle).
 */
final class ClockScene {

    private static final long MOVE_MS = 60_000L;
    /** Deep, low-brightness glow colours; the glow is drawn at partial alpha on black. */
    private static final int[] PALETTE = {0xFF2B5CFF, 0xFF00A39A, 0xFF8A3FFC, 0xFFE0661B, 0xFFD6336C};

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final View content;
    private final GradientDrawable glow;
    private int colorIndex;
    private int glowColor;
    private boolean firstPlace = true;
    /** 有壁纸背景时停掉漂移，时钟固定在中间。 */
    private boolean photoMode;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!photoMode) {
                moveClock();
                shiftGlow();
            }
            handler.postDelayed(this, MOVE_MS);
        }
    };

    /** root is the inflated dream_clock layout. */
    ClockScene(View root) {
        content = root.findViewById(R.id.dreamContent);
        View ambient = root.findViewById(R.id.dreamAmbient);
        glow = new GradientDrawable();
        glow.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        ambient.setBackground(glow);
        ambient.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                glow.setGradientRadius((r - l) * 0.55f));
        colorIndex = random.nextInt(PALETTE.length);
        setGlow(PALETTE[colorIndex]);
    }

    void start() {
        content.setAlpha(0f);
        content.post(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        });
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, MOVE_MS);
    }

    /**
     * 壁纸屏保（2026-10-23 K）：换成必应图当背景，时钟不再每分钟漂移（漂移是为了防黑色 AMOLED 烧屏，
     * 有图时反而晃眼）。photo 为空 = 退回纯黑时钟屏保。
     */
    void applyPhoto(File photo, int targetW, int targetH) {
        View root = (View) content.getParent();
        ImageView img = root.findViewById(R.id.dreamPhoto);
        View scrim = root.findViewById(R.id.dreamScrim);
        if (img == null) return;
        if (photo == null) return;    // 没图：保持纯黑时钟
        Bitmap bmp = BingScreensaver.decode(photo, targetW, targetH);
        if (bmp == null) return;
        img.setImageBitmap(bmp);
        img.setVisibility(View.VISIBLE);
        if (scrim != null) scrim.setVisibility(View.VISIBLE);
        // 有背景图时时钟停在中上偏下更耐看，且关掉漂移动画
        photoMode = true;
        content.setTranslationX(0f);
        content.setTranslationY(0f);
    }

    void stop() {
        handler.removeCallbacks(tick);
    }

    private void moveClock() {
        content.animate().alpha(0f).setDuration(900).withEndAction(() -> {
            placeClock();
            content.animate().alpha(1f).setDuration(1200).start();
        }).start();
    }

    /** 首次居中；之后每分钟小幅漂移（±10%），防烧屏但不跑偏。 */
    private void placeClock() {
        View parent = (View) content.getParent();
        int maxX = Math.max(0, parent.getWidth() - content.getWidth());
        int maxY = Math.max(0, parent.getHeight() - content.getHeight());
        float x, y;
        if (firstPlace) {
            // 首次：靠 layout_gravity=center 居中，不用算坐标
            firstPlace = false;
            return; // 不 setX/setY，保持 layout 居中
        } else {
            // 之后：以中心为基准 ±10% 漂移
            float cx = (parent.getWidth() - content.getWidth()) / 2f;
            float cy = (parent.getHeight() - content.getHeight()) / 2f;
            float rangeX = maxX * 0.2f;
            float rangeY = maxY * 0.2f;
            x = cx + (random.nextFloat() - 0.5f) * rangeX;
            y = cy + (random.nextFloat() - 0.5f) * rangeY;
        }
        content.setX(x);
        content.setY(y);
        if (parent.getWidth() > 0 && parent.getHeight() > 0) {
            glow.setGradientCenter((x + content.getWidth() / 2f) / parent.getWidth(),
                    (y + content.getHeight() / 2f) / parent.getHeight());
        }
    }

    private void shiftGlow() {
        int from = glowColor;
        colorIndex = (colorIndex + 1) % PALETTE.length;
        ValueAnimator anim = ValueAnimator.ofObject(new ArgbEvaluator(), from, PALETTE[colorIndex]);
        anim.setDuration(8000);
        anim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        anim.start();
    }

    private void setGlow(int color) {
        glowColor = color;
        int c = color & 0x00FFFFFF;
        glow.setColors(new int[]{0x40000000 | c, 0x14000000 | c, 0x00000000 | c});
    }
}

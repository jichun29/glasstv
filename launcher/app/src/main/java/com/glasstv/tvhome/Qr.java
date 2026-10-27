package com.glasstv.tvhome;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 二维码（2026-10-26）：手机扫码改设置页用。
 *
 * 用 zxing 在本地画，不调任何在线接口 —— 电视可能没网，或者走的是代理，二维码也不该依赖网络。
 * 纠错级别用 M：容错 15%，电视屏大、手机能凑近扫，不必上 H（H 的码点更密，小屏上反而更难扫）。
 */
final class Qr {

    private Qr() {}

    static Bitmap bitmap(String text, int sizePx) {
        if (text == null || text.isEmpty() || sizePx <= 0) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            // 留 2 格静区：电视背景偏亮，静区太窄手机取景框容易切到码边。
            hints.put(EncodeHintType.MARGIN, 2);
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            int w = matrix.getWidth(), h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                int offset = y * w;
                for (int x = 0; x < w; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Exception e) {
            return null;
        }
    }
}

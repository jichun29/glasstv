package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;

/** 局域网快传：访问电视 IP:8899，选择 APK 上传后弹出系统安装器。 */
public class QuickTransferActivity extends Activity {
    private QuickTransferServer server;
    private TextView status;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_transfer);
        TextView address = findViewById(R.id.address);
        status = findViewById(R.id.status);
        ImageView qr = findViewById(R.id.qr);

        String ip = QuickTransferServer.localIp();
        if (ip == null || ip.isEmpty()) {
            address.setText("未联网");
            status.setText("电视连上 Wi-Fi 或网线后再试");
            qr.setVisibility(android.view.View.GONE);
            return;
        }
        String url = "http://" + ip + ":" + QuickTransferServer.PORT;
        address.setText(url);
        status.setText("请在同一局域网的手机或电脑浏览器打开上方地址");
        showQr(qr, url);

        server = new QuickTransferServer(this, new QuickTransferServer.Listener() {
            @Override public void onApk(File apk) {
                status.setText("收到 APK，正在打开安装器…");
                install(apk);
            }
            @Override public void onError(String msg) {
                status.setText(msg);
            }
        });
        server.start();
    }

    /** 本地生成二维码；生成失败只是不显示，不影响手输地址。 */
    private void showQr(ImageView view, String content) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            int size = (int) (180 * getResources().getDisplayMetrics().density);
            com.google.zxing.common.BitMatrix matrix =
                    new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int w = matrix.getWidth();
            int h = matrix.getHeight();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            // 电视上是深色面板：白底二维码最稳，不加滤镜
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            view.setImageBitmap(bitmap);
            view.setVisibility(android.view.View.VISIBLE);
        } catch (Exception e) {
            view.setVisibility(android.view.View.GONE);
        }
    }

    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(this, "请先允许 GlassTV 安装应用", Toast.LENGTH_LONG).show();
            Toast.makeText(this, "设置 → 安全 → 未知来源应用 → 允许", Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) {
                // 小米电视可能没有这个页面；上面的提示已经够用户手动找了
            }
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".updateapk", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "打不开安装器：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onDestroy() {
        if (server != null) server.stop();
        super.onDestroy();
    }
}

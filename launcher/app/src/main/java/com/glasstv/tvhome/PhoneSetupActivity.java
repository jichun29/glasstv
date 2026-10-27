package com.glasstv.tvhome;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 用手机改设置（2026-10-26，参考 aerial-tv 的扫码页思路）。
 *
 * 电视上出二维码，手机扫进来改天气城市 / 壁纸 / 布局 / 屏保，改一条电视立刻生效。
 * 页面退出就停服务（{@link PhoneSetupServer#stop()}），不常驻。
 */
public final class PhoneSetupActivity extends Activity {

    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean changed;

    /** 服务端回调在后台线程，切回主线程再碰 SharedPreferences 之外的 UI/配置。 */
    private final PhoneSetupServer.Callback callback = () -> main.post(() -> {
        changed = true;
        if (status != null) status.setText(R.string.phone_setup_applied);
    });

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_phone_setup);

        TextView url = findViewById(R.id.phoneUrl);
        status = findViewById(R.id.phoneStatus);
        ImageView qr = findViewById(R.id.phoneQr);
        TextView fallback = findViewById(R.id.phoneQrFallback);
        View close = findViewById(R.id.phoneClose);

        String link = PhoneSetupServer.start(this, callback);
        if (link == null) {
            // 电视没接网线/Wi-Fi（或者只有代理隧道网卡）：二维码没法扫，给一句人话解释。
            qr.setVisibility(View.GONE);
            fallback.setVisibility(View.VISIBLE);
            fallback.setText(R.string.phone_setup_no_network);
            url.setText("");
            status.setText("");
        } else {
            android.graphics.Bitmap bitmap = Qr.bitmap(link, 320 * 3);
            if (bitmap != null) qr.setImageBitmap(bitmap);
            else fallback.setVisibility(View.VISIBLE);
            url.setText(link);
        }

        // 长按/点击地址条把 URL 复制到电视侧剪贴板（有人更愿意用输入法打字而不是扫码）
        url.setOnClickListener(v -> copy(link));
        close.setOnClickListener(v -> finish());
        close.setOnFocusChangeListener((v, focused) -> v.setAlpha(focused ? 1f : 0.7f));
        close.requestFocus();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void copy(String link) {
        if (link == null) return;
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("phone-setup", link));
            Toast.makeText(this, link, Toast.LENGTH_LONG).show();
        } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从后台回来时服务可能已被其它页面停掉：重启一次（幂等）。
        if (!PhoneSetupServer.isRunning()) {
            String link = PhoneSetupServer.currentUrl();
            if (link == null) PhoneSetupServer.start(this, callback);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PhoneSetupServer.stop();
        if (changed) setResult(RESULT_OK);
    }
}

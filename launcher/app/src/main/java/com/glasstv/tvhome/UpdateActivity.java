package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 检查更新页 v4（K 2026-10-22）。
 *
 * 布局：单列卡片——标题 / 状态行（状态 + 版本胶囊）/ 更新日志 / 右对齐按钮行。
 * 之前做过左右双栏，但左边只有三行字、右边一个大空框，反而失衡；单列收紧更适合电视。
 *
 * 关键行为：无论有没有新版，都显示「当前版本」的更新日志，让用户知道自己在用什么。
 */
public final class UpdateActivity extends Activity {

    private TextView statusText;
    private TextView versionText;
    private TextView notesText;
    private TextView logTitle;
    private Button downloadButton;
    private Button skipButton;
    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_update);

        statusText = findViewById(R.id.updateStatus);
        versionText = findViewById(R.id.updateVersions);
        notesText = findViewById(R.id.updateNotes);
        logTitle = findViewById(R.id.updateLogTitle);
        downloadButton = findViewById(R.id.updateDownload);
        skipButton = findViewById(R.id.updateSkip);
        progress = findViewById(R.id.updateProgress);

        findViewById(R.id.updateClose).setOnClickListener(v -> finish());
        downloadButton.setVisibility(View.GONE);
        skipButton.setVisibility(View.GONE);

        check();
    }

    private void check() {
        statusText.setText("正在检查更新…");
        statusText.setTextColor(0xBFFFFFFF);
        progress.setVisibility(View.VISIBLE);
        downloadButton.setVisibility(View.GONE);
        skipButton.setVisibility(View.GONE);
        notesText.setText("正在读取更新日志…");

        UpdateChecker.checkNow(this, r -> {
            progress.setVisibility(View.GONE);
            if (r.error != null) {
                statusText.setText("检查失败");
                statusText.setTextColor(0xFFFF6B6B);
                versionText.setText("v" + r.currentVersion);
                showCurrentVersionLog(r.currentVersion);
                return;
            }
            if (!r.hasUpdate) {
                statusText.setText("已是最新版本");
                statusText.setTextColor(0xFF4ADE80);
                versionText.setText("v" + r.currentVersion);
                showCurrentVersionLog(r.currentVersion);
                return;
            }
            // 有新版本
            statusText.setText("发现新版本 " + r.latestTag);
            statusText.setTextColor(0xFFFFB347);
            versionText.setText("v" + r.currentVersion + " → " + r.latestTag);
            logTitle.setText("新版本更新内容");
            notesText.setText(UpdateChecker.shortenNotes(r.body));
            downloadButton.setVisibility(View.VISIBLE);
            skipButton.setVisibility(View.VISIBLE);
            downloadButton.setOnClickListener(v -> startDownload(r.apkUrl));
            skipButton.setOnClickListener(v -> {
                UpdateChecker.skipTag(this, r.latestTag);
                finish();
            });
        });
    }

    /** 显示当前版本的更新日志（从 GitHub 取当前 tag 的 release body）。 */
    private void showCurrentVersionLog(String currentVersion) {
        String tag = "v" + currentVersion.replaceAll("-.*$", "");
        new Thread(() -> {
            String body = UpdateChecker.fetchTagBody(tag);
            runOnUiThread(() -> {
                if (body != null && !body.isEmpty()) {
                    logTitle.setText(tag + " 更新内容");
                    notesText.setText(UpdateChecker.shortenNotes(body));
                } else {
                    // 空状态：一句话说清，不重复上面的"已是最新版本"
                    logTitle.setText("更新日志");
                    notesText.setText("这个版本还没写更新日志。\n有新版时，这里会显示改了什么。");
                }
            });
        }, "fetch-current-log").start();
    }

    /** 下载：应用内下完 APK 交系统安装器（一键 OTA，2026-10-23）。 */
    private void startDownload(String apkUrl) {
        if (apkUrl == null || downloading) return;
        // Android 8+ 要先有「安装未知应用」权限，没有就跳设置页让用户开。
        if (android.os.Build.VERSION.SDK_INT >= 26
                && !getPackageManager().canRequestPackageInstalls()) {
            statusText.setText(R.string.update_install_need_permission);
            statusText.setTextColor(0xFFFFB347);
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
            }
            return;
        }
        downloading = true;
        downloadButton.setEnabled(false);
        skipButton.setVisibility(View.GONE);
        statusText.setTextColor(0xBFFFFFFF);
        UpdateChecker.downloadApk(this, apkUrl, new UpdateChecker.DownloadCallback() {
            @Override public void onProgress(int percent) {
                statusText.setText(percent >= 0
                        ? getString(R.string.update_downloading, percent)
                        : "正在下载…");
            }
            @Override public void onDone(java.io.File apk) {
                downloading = false;
                install(apk);
            }
            @Override public void onFailed() {
                downloading = false;
                statusText.setText(R.string.update_download_failed);
                statusText.setTextColor(0xFFFF6B6B);
                downloadButton.setEnabled(true);
                skipButton.setVisibility(View.VISIBLE);
            }
        });
    }

    private boolean downloading;

    /** 下完了：FileProvider 拿 content:// Uri，交系统安装器。装完系统会自己重启桌面。 */
    private void install(java.io.File apk) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".updateapk", apk);
            Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setData(uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            // 老系统没有 ACTION_INSTALL_PACKAGE，退 VIEW。
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".updateapk", apk);
                Intent i = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(i);
            } catch (Exception e2) {
                statusText.setText("无法打开安装器");
                statusText.setTextColor(0xFFFF6B6B);
                downloadButton.setEnabled(true);
            }
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) {
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
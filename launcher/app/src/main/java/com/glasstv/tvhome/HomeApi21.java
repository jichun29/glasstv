package com.glasstv.tvhome;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;

import androidx.annotation.NonNull;

/**
 * Android 5.0+ 才有的框架回调，全部集中在这里，**只有 API 21 以上才会被实例化**。
 *
 * 这样做的原因：ConnectivityManager.NetworkCallback 和 TvInputManager.TvInputCallback 是 API 21 才加的类，
 * 如果直接写成 MainActivity 的字段初始化器，4.4 上加载 MainActivity 时就会 NoClassDefFoundError 直接崩。
 * 放进这个类里懒加载，4.4 上永远不会碰它，桌面照常起来，只是少了「网络变化即时刷新」和「HDMI 输入源菜单」。
 */
@TargetApi(21)
final class HomeApi21 {
    private final ConnectivityManager cm;
    private final TvInputManager tim;
    private final ConnectivityManager.NetworkCallback network, vpn;
    private final TvInputManager.TvInputCallback input;

    private HomeApi21(Context ctx, Handler handler, Runnable onNetworkChanged,
                      Runnable onInputsChanged, boolean watchVpn) {
        cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        network = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network n) {
                handler.post(onNetworkChanged);
            }

            @Override
            public void onLost(@NonNull Network n) {
                handler.post(onNetworkChanged);
            }
        };
        vpn = watchVpn ? new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network n) {
                handler.post(onNetworkChanged);
            }

            @Override
            public void onLost(@NonNull Network n) {
                handler.post(onNetworkChanged);
            }
        } : null;
        input = new TvInputManager.TvInputCallback() {
            @Override
            public void onInputAdded(String id) {
                handler.post(onInputsChanged);
            }

            @Override
            public void onInputRemoved(String id) {
                handler.post(onInputsChanged);
            }
        };
    }

    /** 只在 API 21+ 调用；4.4 上返回 null。 */
    @SuppressLint("NewApi")
    static HomeApi21 attach(Context ctx, Handler handler, Runnable onNetworkChanged,
                            Runnable onInputsChanged, boolean watchVpn) {
        if (Build.VERSION.SDK_INT < 21) return null;
        HomeApi21 api = new HomeApi21(ctx, handler, onNetworkChanged, onInputsChanged, watchVpn);
        if (api.cm != null) {
            // API 21-23 没有 registerDefaultNetworkCallback：用等价的 NetworkRequest 顶替。
            if (Build.VERSION.SDK_INT >= 24) {
                api.cm.registerDefaultNetworkCallback(api.network);
            } else {
                api.cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(), api.network);
            }
            if (api.vpn != null) {
                api.cm.registerNetworkCallback(new NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .build(), api.vpn);
            }
        }
        if (api.tim != null) api.tim.registerCallback(api.input, handler);
        return api;
    }

    @SuppressLint("NewApi")
    void detach() {
        if (cm != null) {
            try {
                cm.unregisterNetworkCallback(network);
                if (vpn != null) cm.unregisterNetworkCallback(vpn);
            } catch (Exception ignored) {
            }
        }
        if (tim != null) {
            try {
                tim.unregisterCallback(input);
            } catch (Exception ignored) {
            }
        }
    }
}

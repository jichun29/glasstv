package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/**
 * 手机页的读写口（2026-10-26）。
 *
 * 手机页只改「遥控器改起来最难受」的那些设置，一律写进桌面设置自己的 SharedPreferences，
 * 也就是电视端设置页用的同一批键 —— 不另开一套配置，否则两端会不同步。
 *
 * 白名单式：只接受下面列出名字的键，别的一律 400。这样即便 token 泄漏，也改不了别的东西。
 */
final class Api {

    interface Changed { void now(); }

    private Api() {}

    /** 手机页打开时拉一次当前值，把所有控件初始化到电视现在的状态。 */
    static String state(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences wall = ctx.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        try {
            // 天气
            String manual = Config.weatherCityOverride(ctx);
            String detected = Weather.place();
            o.put("city", manual == null ? "" : manual);
            o.put("autoCity", detected == null ? "" : detected);

            // 壁纸：index = 选中的内置壁纸下标，-1 = 默认/纯色，-2 = 必应每日
            boolean useDefault = wall.getBoolean("default", false);
            boolean bing = wall.getBoolean("bing", false);
            o.put("wallpaperDefault", useDefault);
            o.put("wallpaperBing", bing);
            o.put("wallpaperIndex", useDefault ? -1 : wall.getInt("index", -1));
            o.put("wallpaperRotating", wall.getBoolean("rotating", false));

            JSONArray list = new JSONArray();
            for (int i = 0; i < WallpaperController.count(); i++) {
                JSONObject w = new JSONObject();
                w.put("i", i);
                w.put("name", WallpaperController.nameOf(i));
                w.put("group", WallpaperController.groupName(wallpaperGroupOf(i)));
                list.put(w);
            }
            o.put("wallpapers", list);

            // 布局
            Config c = Config.load(ctx);
            o.put("columns", c.columns);
            o.put("rowsBelowDock", c.rowsBelowDock);
            o.put("headerSize", Config.headerSize(ctx));
            o.put("headerAlign", Config.headerAlign(ctx));
            o.put("showWeather", Config.showWeather(ctx, c.weatherEnabled));

            // 屏保
            o.put("screensaverOn", p.getBoolean("idle_clock_enabled", true));
            o.put("screensaverStyle", p.getString("screensaver_style", "clock"));
            o.put("screensaverInterval", p.getInt("screensaver_interval_min", 60));
            o.put("flipCardSize", Config.flipCardSize(ctx));

            // 关服务用（页面右上角「关闭」）：手机改完可以让电视把端口关掉
            o.put("port", 7921);
        } catch (JSONException ignored) {
            // JSONObject 不会往字符串里塞非法值，这里不会发生
        }
        return o.toString();
    }

    private static int wallpaperGroupOf(int index) {
        for (int g = 0; g < WallpaperController.groupCount(); g++) {
            if (index >= WallpaperController.groupStart(g) && index < WallpaperController.groupEnd(g)) return g;
        }
        return 0;
    }

    /**
     * 应用一次修改。action 是白名单里的键名，value 由手机页按类型给。
     * 返回 {"ok":true} 或 {"ok":false,"error":"..."}，手机页直接显示。
     */
    static String apply(Context ctx, String action, String body, Changed changed) {
        SharedPreferences p = ctx.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        SharedPreferences wall = ctx.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        JSONObject in;
        try {
            in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
        } catch (JSONException e) {
            return error("请求格式不对");
        }
        boolean touchedWeather = false;
        try {
            switch (action) {
                case "city": {
                    String city = in.optString("value", "").trim();
                    if (city.length() > 20) return error("城市名太长");
                    // 空 = 回到自动定位：必须真正删掉键，否则 Config 会继续拿到手填值
                    if (city.isEmpty()) p.edit().remove(Config.WEATHER_CITY_KEY).apply();
                    else p.edit().putString(Config.WEATHER_CITY_KEY, city).apply();
                    touchedWeather = true;
                    break;
                }
                case "wallpaper": {
                    // value: "default" | "bing" | 数字下标 | "rotate"
                    String v = in.optString("value", "");
                    if ("rotate".equals(v)) {
                        wall.edit().putBoolean("rotating", in.optBoolean("on", true)).apply();
                        break;
                    }
                    if ("default".equals(v)) {
                        wall.edit().putBoolean("default", true).putBoolean("rotating", false)
                                .putBoolean("bing", false).apply();
                        new BingWallpaper(ctx).setEnabled(false);
                        break;
                    }
                    if ("bing".equals(v)) {
                        if (BuildConfigShim.lite()) return error("精简版没有必应每日壁纸");
                        wall.edit().putBoolean("default", false).putBoolean("rotating", false)
                                .putBoolean("bing", true).putInt("index", -2).apply();
                        break;
                    }
                    int index;
                    try {
                        index = Integer.parseInt(v);
                    } catch (NumberFormatException e) {
                        return error("壁纸编号不对");
                    }
                    if (index < 0 || index >= WallpaperController.count()) return error("壁纸编号超出范围");
                    wall.edit().putBoolean("default", false).putBoolean("bing", false)
                            .putInt("index", index).apply();
                    new BingWallpaper(ctx).setEnabled(false);
                    break;
                }
                case "columns": {
                    int v = in.optInt("value", 0);
                    if (v < 4 || v > 7) return error("每行数量只能是 4-7");
                    p.edit().putInt("columns", v).apply();
                    break;
                }
                case "rows": {
                    int v = in.optInt("value", -1);
                    if (v < 0 || v > 3) return error("保留行数只能是 0-3");
                    p.edit().putInt(Config.ROWS_BELOW_DOCK_KEY, v).apply();
                    break;
                }
                case "headerSize": {
                    int v = in.optInt("value", -1);
                    if (v < 0 || v > 2) return error("字号档位不对");
                    p.edit().putInt(Config.HEADER_SIZE_KEY, v).apply();
                    break;
                }
                case "headerAlign": {
                    String v = in.optString("value", "");
                    if (!"left".equals(v) && !"center".equals(v) && !"right".equals(v)) return error("位置不对");
                    p.edit().putString(Config.HEADER_ALIGN_KEY, v).apply();
                    break;
                }
                case "showWeather": {
                    p.edit().putBoolean(Config.SHOW_WEATHER_KEY, in.optBoolean("value", true)).apply();
                    touchedWeather = true;
                    break;
                }
                case "screensaver": {
                    p.edit().putBoolean("idle_clock_enabled", in.optBoolean("on", true)).apply();
                    break;
                }
                case "screensaverStyle": {
                    String v = in.optString("value", "");
                    if (!"clock".equals(v) && !"flip".equals(v) && !"bing".equals(v)) return error("屏保样式不对");
                    if ("bing".equals(v) && BuildConfigShim.lite()) return error("精简版没有必应壁纸屏保");
                    p.edit().putString("screensaver_style", v).apply();
                    break;
                }
                case "screensaverInterval": {
                    int v = in.optInt("value", -1);
                    if (v != 0 && v != 60 && v != 1440) return error("间隔只能是 0/60/1440");
                    p.edit().putInt("screensaver_interval_min", v).apply();
                    break;
                }
                case "flipCardSize": {
                    int v = in.optInt("value", -1);
                    if (v < 0 || v > 2) return error("卡片大小不对");
                    p.edit().putInt(Config.FLIP_CARD_SIZE_KEY, v).apply();
                    break;
                }
                default:
                    return error("不支持的项目：" + action);
            }
        } catch (RuntimeException e) {
            return error("保存失败：" + e.getClass().getSimpleName());
        }
        if (touchedWeather) Weather.forceRefresh(ctx);
        if (changed != null) changed.now();
        JSONObject ok = new JSONObject();
        try {
            ok.put("ok", true);
        } catch (JSONException ignored) { }
        return ok.toString();
    }

    private static String error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("error", message);
        } catch (JSONException ignored) { }
        return o.toString();
    }

    /** 单独拿出来只为让上面的白名单读起来不掺 BuildConfig 判断。 */
    private static final class BuildConfigShim {
        static boolean lite() { return BuildConfig.LITE; }
    }
}

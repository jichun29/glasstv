package com.glasstv.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import javax.net.ssl.SSLException;

/**
 * 桌面左上角时钟旁的天气（移植自 aerial-tv 的 Weather.kt，Java 重写）。
 *
 * 数据源 Open-Meteo：免 key、国内直连。城市默认从 ipinfo.io 按公网 IP 猜一次，
 * config.xml 里 weather_city 写死中文城市名（如 "宜昌"）就不猜了。
 *
 * 只读天气，不上报任何设备信息。一小时最多取一次；失败十分钟重试；断网静默。
 */
public final class Weather {

    private static final String TAG = "Weather";
    private static final String PREFS = "weather";
    private static final long REFRESH_MS = 60 * 60 * 1000L;
    private static final long RETRY_MS = 10 * 60 * 1000L;
    /** 超过三小时的读数不显示。 */
    private static final long STALE_MS = 3 * 60 * 60 * 1000L;

    /** 当前读数；null = 还没有。 */
    public static final class Now {
        public final int celsius;
        public final String kind;      // 晴 / 多云 / 阴 / 雾 / 雨 / 雪 / 雷
        public final int high;         // Integer.MIN_VALUE = 无
        public final int low;
        public final String place;

        Now(int celsius, String kind, int high, int low, String place) {
            this.celsius = celsius;
            this.kind = kind;
            this.high = high;
            this.low = low;
            this.place = place;
        }

        /** 电视上显示的一行：「宜昌 18° 晴 12/22°」。 */
        public String label() {
            StringBuilder sb = new StringBuilder();
            if (place != null && !place.isEmpty()) sb.append(place).append(' ');
            sb.append(celsius).append('°');
            if (kind != null) sb.append(' ').append(kind);
            if (high != Integer.MIN_VALUE && low != Integer.MIN_VALUE) {
                sb.append("  ").append(low).append('/').append(high).append('°');
            }
            return sb.toString();
        }
    }

    public interface Listener {
        void onWeather(Now now); // null = 无有效读数
    }

    private static SharedPreferences prefs;
    private static Context app;
    private static Listener listener;
    private static Now current;
    private static long lastAttempt;
    private static boolean failed;
    private static boolean fetching;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Weather() {}

    public static void init(Context ctx, Listener l) {
        if (app == null) {
            app = ctx.getApplicationContext();
            prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            // config 里的 weather_city 和上次定位的城市不一致 = 换城市了，旧坐标作废，重新定位。
            String cfgCity = Config.load(app).weatherCity;
            if (cfgCity != null && !cfgCity.isEmpty()
                    && !cfgCity.equals(prefs.getString("place", null))) {
                prefs.edit().remove("lat").remove("lon").remove("place")
                        .remove("fetched_at").apply();
                current = null;
            }
            // 上次存的读数没过时就先用着，不等网络。
            if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
                String kind = prefs.getString("kind", null);
                if (kind != null) {
                    current = new Now(
                            prefs.getInt("celsius", 0), kind,
                            prefs.getInt("high", Integer.MIN_VALUE),
                            prefs.getInt("low", Integer.MIN_VALUE),
                            prefs.getString("place", null));
                }
            }
        }
        listener = l;
        if (listener != null) listener.onWeather(current);
        refreshIfStale();
    }

    /**
     * 换城市后强制重取：清掉旧坐标与旧读数，并绕过「刚取过就别再取」的节流。
     * 只清 lat/lon/place 不够——init() 里 3 小时内的旧读数还会被当成有效值直接显示。
     */
    public static void forceRefresh(Context ctx) {
        if (app == null) app = ctx.getApplicationContext();
        if (prefs == null) prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().remove("lat").remove("lon").remove("place")
                .remove("fetched_at").remove("kind").apply();
        current = null;
        lastAttempt = 0;
        failed = false;
        refresh();
    }

    public static void refreshIfStale() {
        if (app == null) return;
        long since = System.currentTimeMillis() - lastAttempt;
        if (since >= REFRESH_MS || (failed && since >= RETRY_MS)) refresh();
    }

    private static void refresh() {
        if (fetching) return;
        fetching = true;
        lastAttempt = System.currentTimeMillis();
        new Thread(() -> {
            Now reading = null;
            try {
                reading = fetch();
            } catch (Exception e) {
                Log.w(TAG, "weather unavailable", e);
            }
            failed = reading == null;
            fetching = false;
            if (reading != null) {
                current = reading;
                prefs.edit()
                        .putLong("fetched_at", System.currentTimeMillis())
                        .putInt("celsius", reading.celsius)
                        .putString("kind", reading.kind)
                        .putInt("high", reading.high)
                        .putInt("low", reading.low)
                        .putString("place", reading.place)
                        .apply();
            }
            final Now toShow = current;
            main.post(() -> { if (listener != null) listener.onWeather(toShow); });
        }, "weather").start();
    }

    /** 网络请求，必须在子线程。 */
    private static Now fetch() throws Exception {
        float lat = prefs.getFloat("lat", Float.NaN);
        float lon = prefs.getFloat("lon", Float.NaN);
        String place = prefs.getString("place", null);

        // config.xml 的 weather_city 优先，且和上次存的不一样就重新定位（换了城市不能还用旧坐标）。
        String cfgCity = Config.load(app).weatherCity;
        if (cfgCity != null && !cfgCity.isEmpty() && !cfgCity.equals(place)) {
            double[] ll = geocode(cfgCity);
            if (ll != null) {
                lat = (float) ll[0];
                lon = (float) ll[1];
                place = cfgCity;
                prefs.edit().putFloat("lat", lat).putFloat("lon", lon).putString("place", place).apply();
            }
        }

        if (lat != lat) { // NaN
            // 没写死城市：优先用系统定位（GPS/网络，装时弹过一次授权），没有才按公网 IP 猜。
            android.location.Location loc = lastKnownLocation();
            if (loc != null) {
                lat = (float) loc.getLatitude();
                lon = (float) loc.getLongitude();
                // 反查城市名（Open-Meteo 地理编码不支持反查，用 BigDataCloud 免 key 反向接口，国内可直连）
                place = reverseGeocode(lat, lon);
            } else {
                JSONObject ip = new JSONObject(get("https://ipinfo.io/json"));
                String[] loc2 = ip.getString("loc").split(",");
                lat = Float.parseFloat(loc2[0]);
                lon = Float.parseFloat(loc2[1]);
                String c = ip.optString("city");
                place = c.isEmpty() ? null : c;
            }
            prefs.edit().putFloat("lat", lat).putFloat("lon", lon).apply();
        }

        JSONObject j = new JSONObject(get(
                "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                        + "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min"
                        + "&forecast_days=1&timezone=auto"));
        JSONObject cur = j.getJSONObject("current");
        JSONObject daily = j.optJSONObject("daily");
        int high = Integer.MIN_VALUE, low = Integer.MIN_VALUE;
        if (daily != null) {
            if (!daily.isNull("temperature_2m_max")) {
                double v = daily.getJSONArray("temperature_2m_max").optDouble(0, Double.NaN);
                if (v == v) high = (int) Math.round(v);
            }
            if (!daily.isNull("temperature_2m_min")) {
                double v = daily.getJSONArray("temperature_2m_min").optDouble(0, Double.NaN);
                if (v == v) low = (int) Math.round(v);
            }
        }
        return new Now(
                (int) Math.round(cur.getDouble("temperature_2m")),
                kindOf(cur.getInt("weather_code")),
                high, low, place);
    }

    /** 系统最后一次已知定位（不给权限/没开过定位返回 null）。电视基本没 GPS，靠网络定位。 */
    private static android.location.Location lastKnownLocation() {
        try {
            android.location.LocationManager lm = (android.location.LocationManager)
                    app.getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return null;
            android.location.Location best = null;
            for (String provider : new String[]{"gps", "network"}) {
                try {
                    android.location.Location l = lm.getLastKnownLocation(provider);
                    if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
                } catch (SecurityException ignored) {}
            }
            // 超过 24 小时的定位不新鲜了，不拿它定天气城市
            if (best != null && System.currentTimeMillis() - best.getTime() > 24 * 3600_000L) return null;
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    /** 经纬度反查中文城市名（BigDataCloud 免 key，国内可直连）。失败返回 null。 */
    private static String reverseGeocode(double lat, double lon) {
        try {
            JSONObject j = new JSONObject(get(
                    "https://api.bigdatacloud.net/data/reverse-geocode-client?latitude=" + lat
                            + "&longitude=" + lon + "&localityLanguage=zh"));
            String city = j.optString("city");
            if (city.isEmpty()) city = j.optString("locality");
            return city.isEmpty() ? null : city;
        } catch (Exception e) {
            Log.w(TAG, "reverse geocode failed", e);
            return null;
        }
    }

    /** Open-Meteo 的地理编码，中文城市名直查（language=zh）。找不到返回 null。 */
    private static double[] geocode(String name) {
        try {
            String q = java.net.URLEncoder.encode(name.trim(), "UTF-8");
            JSONObject j = new JSONObject(get(
                    "https://geocoding-api.open-meteo.com/v1/search?name=" + q
                            + "&count=1&language=zh&format=json"));
            org.json.JSONArray results = j.optJSONArray("results");
            if (results == null || results.length() == 0) return null;
            JSONObject first = results.getJSONObject(0);
            return new double[]{first.getDouble("latitude"), first.getDouble("longitude")};
        } catch (Exception e) {
            Log.w(TAG, "geocode " + name + " failed", e);
            return null;
        }
    }

    /** WMO 天气码 → 中文。 */
    private static String kindOf(int code) {
        if (code == 0) return "晴";
        if (code <= 2) return "多云";
        if (code == 3) return "阴";
        if (code == 45 || code == 48) return "雾";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "雪";
        if (code >= 95) return "雷";
        return "雨";
    }

    /** 老 Android（<7.1）不认 Let's Encrypt；这几个站同样走 HTTP（network_security_config 已放行）。 */
    private static String get(String url) throws Exception {
        try {
            return read(url);
        } catch (SSLException e) {
            return read(url.replaceFirst("https://", "http://"));
        }
    }

    private static String read(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}

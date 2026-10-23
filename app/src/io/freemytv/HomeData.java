package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Optional crypto prices (Binance, every 5 min) and weather/sunrise/sunset (Open-Meteo, every 30 min), cached
 * so the screen opens with numbers. Only fetched while the home screen or the screensaver is visible (the screen asks).
 * Neither source needs a key. Location and currency come from config.properties.
 */
final class HomeData {
    static final String[] COINS = {"BTC", "ETH", "SOL", "USDT"};
    static final String[] COIN_NAMES = {"Bitcoin", "Ethereum", "Solana", "Dollar"};
    private static final String CURRENCY = Config.CURRENCY.trim().toUpperCase(Locale.ROOT);
    /** Binance quotes dollars as USDT. */
    private static final String QUOTE = CURRENCY.equals("USD") ? "USDT" : CURRENCY;
    /** api.open-meteo.com is not reachable from this network; MET Norway (api.met.no) is, and needs no key. */
    private static final String USER_AGENT = "FreeMyTV/1.0 github.com/simbasjungles/free-my-tv";

    private static String weatherPlace() {
        return Prefs.latitude() + "," + Prefs.longitude();
    }

    private static String weatherUrl() {
        String lat = Prefs.latitude(), lon = Prefs.longitude();
        try {
            lat = String.format(Locale.US, "%.4f", Double.parseDouble(lat));
            lon = String.format(Locale.US, "%.4f", Double.parseDouble(lon));
        } catch (NumberFormatException ignored) {}
        return "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=" + lat + "&lon=" + lon;
    }
    private static final long PRICES_EVERY = 5 * 60_000L, WEATHER_EVERY = 30 * 60_000L;
    interface Listener { void onData(); }

    /** BTC, ETH and SOL; in Brazilian reais also the dollar (USDT/BRL), which Binance doesn't list for most currencies. */
    static int coinCount() {
        return Prefs.quotes() ? active().length : 0;
    }

    private static volatile int[] activeCoins;
    private static volatile int activeVersion = -1;

    /** Indexes in COINS of the coins the user has on (and that Binance lists in this currency). */
    static int[] active() {
        int[] a = activeCoins;
        if (a != null && activeVersion == Prefs.version) return a;
        int n = 0;
        int[] tmp = new int[COINS.length];
        for (int i = 0; i < COINS.length; i++) if (Prefs.coin(i) && (i < 3 || CURRENCY.equals("BRL"))) tmp[n++] = i;
        a = java.util.Arrays.copyOf(tmp, n);
        activeCoins = a;
        activeVersion = Prefs.version;
        return a;
    }

    /** Settings changed (city, coins): fetch again now. */
    void settingsChanged() {
        pricesAt = 0;
        weatherAt = 0;
        pricesFailedAt = 0;
        weatherFailedAt = 0;
    }

    static String coinName(int i) {
        return i == 3 ? Ui.tr("Dólar", "Dollar") : COIN_NAMES[i];
    }

    private static String pricesUrl() {
        StringBuilder symbols = new StringBuilder();
        int[] coins = active();
        for (int i = 0; i < coins.length; i++) symbols.append(i == 0 ? "" : ",").append("%22").append(COINS[coins[i]]).append(QUOTE).append("%22");
        return "https://api.binance.com/api/v3/ticker/24hr?type=MINI&symbols=%5B" + symbols + "%5D";
    }

    static final class Weather {
        double temp = Double.NaN, max = Double.NaN, min = Double.NaN;
        int code = -1;
        boolean isDay = true;
        long sunrise, sunset;   // today's, in epoch ms
        // Next hours (starting at the next full hour): time, temperature, code and day/night.
        final List<Long> hours = new ArrayList<>();
        final List<Double> hourTemp = new ArrayList<>();
        final List<Integer> hourCode = new ArrayList<>();
        final List<Boolean> hourDay = new ArrayList<>();
        // The next two days (tomorrow, the day after): midnight (epoch ms), highest and lowest temperature, code.
        final long[] dayAt = new long[2];
        final double[] dayMax = {Double.NaN, Double.NaN}, dayMin = {Double.NaN, Double.NaN};
        final int[] dayCode = {-1, -1};
        // Today and the next five days, for the drawer: midnight, highest and lowest temperature, and code.
        // From Yandex: the daytime temperature of today, wind (m/s, direction) and pressure (mm Hg).
        String nowcast = "";   // "in the next 2 hours ..." when Yandex expects precipitation, else empty
        double dayTemp = Double.NaN, windSpeed = Double.NaN;
        String windDir = "";
        int pressure = -1;
        final long[] fcAt = new long[6];
        final double[] fcMax = new double[6], fcMin = new double[6];
        final int[] fcCode = new int[6];
        { java.util.Arrays.fill(fcMax, Double.NaN); java.util.Arrays.fill(fcMin, Double.NaN); java.util.Arrays.fill(fcCode, -1); }
    }

    private static HomeData instance;

    static synchronized HomeData get(Context c) {
        if (instance == null) instance = new HomeData(c.getApplicationContext());
        return instance;
    }

    final double[] prices = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    /** 24 h change (%), in the same order. */
    final double[] changes = {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    volatile long pricesAt, weatherAt;
    volatile Weather weather = new Weather();

    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private volatile boolean fetchingPrices, fetchingWeather;
    private volatile long pricesFailedAt, weatherFailedAt;

    private static Context appCtx;

    private HomeData(Context c) {
        appCtx = c.getApplicationContext();
        cache = c.getSharedPreferences("homedata", Context.MODE_PRIVATE);
        try {
            if (Prefs.quotes()) {
                String p = cache.getString("prices", null);
                if (p != null) parsePrices(p);
                pricesAt = cache.getLong("pricesAt", 0);
            }
            String w = cache.getString("weather6", null);
            // The cached forecast is for the city it was fetched for; another city means fetch again.
            if (w != null && weatherPlace().equals(cache.getString("weatherPlace", ""))) {
                weather = parseWeather(w);
                weatherAt = cache.getLong("weatherAt", 0);
                if (System.currentTimeMillis() - cache.getLong("yaAt", 0) < 3 * 3_600_000L && cache.contains("yaCode")) {
                    weather.temp = cache.getFloat("yaTemp", (float) weather.temp);
                    weather.code = cache.getInt("yaCode", weather.code);
                    weather.isDay = cache.getBoolean("yaDay", weather.isDay);
                    try { applyCachedDays(weather); } catch (RuntimeException ignored) {}
                    weather.dayTemp = cache.getFloat("yaDayTemp", Float.NaN);
                    weather.windSpeed = cache.getFloat("yaWind", Float.NaN);
                    weather.windDir = cache.getString("yaWindDir", "");
                    weather.pressure = cache.getInt("yaPressure", -1);
                    weather.nowcast = cache.getString("yaNow", "");
                }
            }
        } catch (Exception ignored) {}
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    /** Fetches whatever is stale. The screen calls this when it appears and every minute while it's visible. */
    void refreshIfStale() {
        long now = System.currentTimeMillis();
        // A negative age (the TV's clock went back) counts as stale: otherwise the data would freeze.
        if (!fetchingPrices && coinCount() > 0 && (now - pricesAt > PRICES_EVERY || now < pricesAt) && !failedRecently(pricesFailedAt, now)) {
            fetchingPrices = true;
            io.execute(() -> {
                try {
                    String body = get(pricesUrl());
                    parsePrices(body);
                    pricesAt = System.currentTimeMillis();
                    cache.edit().putString("prices", body).putLong("pricesAt", pricesAt).apply();
                    notifyListeners();
                } catch (Exception e) {
                    pricesFailedAt = System.currentTimeMillis();
                    AppLog.w("prices: " + e.getMessage());
                } finally {
                    fetchingPrices = false;
                }
            });
        }
        // A new day also makes the forecast stale: its first day would still be yesterday.
        if (!fetchingWeather && (now - weatherAt > WEATHER_EVERY || now < weatherAt || weather.fcAt[0] != todayStart()) && !failedRecently(weatherFailedAt, now)) {
            fetchingWeather = true;
            io.execute(() -> {
                try {
                    String body = get(weatherUrl());
                    Weather fresh = parseWeather(body);
                    if (Prefs.yandexWeather()) try {
                        applyYandex(fresh, getPage(yandexUrl()));   // what is outside now, as Yandex Weather reports it
                    } catch (Exception e) {
                        AppLog.w("yandex weather: " + e.getMessage());   // keep the other source's numbers
                    }
                    weather = fresh;
                    weatherAt = System.currentTimeMillis();
                    cache.edit().putString("weather6", body).putLong("weatherAt", weatherAt).putString("weatherPlace", weatherPlace()).apply();
                    notifyListeners();
                } catch (Exception e) {
                    weatherFailedAt = System.currentTimeMillis();
                    AppLog.w("weather: " + e.getMessage());
                } finally {
                    fetchingWeather = false;
                }
            });
        }
    }

    private static boolean failedRecently(long at, long now) {
        return at != 0 && now >= at && now - at < 2 * 60_000L;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onData(); });
    }

    private void parsePrices(String body) throws Exception {
        JSONArray list = new JSONArray(body);
        String quote = QUOTE;
        int found = 0;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.getJSONObject(i);
            String symbol = o.getString("symbol");
            for (int k = 0; k < COINS.length; k++) {
                if (!symbol.equals(COINS[k] + quote)) continue;
                found++;
                double last = Double.parseDouble(o.optString("lastPrice", o.optString("price", "NaN")));
                double open = Double.parseDouble(o.optString("openPrice", "NaN"));
                prices[k] = last;
                changes[k] = open > 0 ? (last - open) / open * 100 : Double.NaN;
            }
        }
        if (found == 0) pricesAt = 0;   // cached in another currency (config changed): fetch again
    }

    private static Weather parseWeather(String body) throws Exception {
        JSONArray series = new JSONObject(body).getJSONObject("properties").getJSONArray("timeseries");
        TimeZone zone = TimeZone.getDefault();
        SimpleDateFormat utc = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        utc.setTimeZone(TimeZone.getTimeZone("UTC"));
        long now = System.currentTimeMillis();
        Weather w = new Weather();
        long[] sun = sunTimes(Double.parseDouble(Prefs.latitude()), Double.parseDouble(Prefs.longitude()), now, zone);
        w.sunrise = sun[0];
        w.sunset = sun[1];
        long today = midnight(now, zone);
        double[] max = new double[6], min = new double[6];
        java.util.Arrays.fill(max, Double.NaN);
        java.util.Arrays.fill(min, Double.NaN);
        long[] noonGap = new long[6];
        java.util.Arrays.fill(noonGap, Long.MAX_VALUE);
        int[] noonCode = new int[6];
        java.util.Arrays.fill(noonCode, -1);
        long curAt = Long.MIN_VALUE;
        for (int i = 0; i < series.length(); i++) {
            JSONObject e = series.getJSONObject(i);
            long at = utc.parse(e.getString("time")).getTime();
            JSONObject data = e.getJSONObject("data");
            double temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            String symbol = symbol(data, "next_1_hours", "next_6_hours", "next_12_hours");
            int code = symbol == null ? -1 : wmo(symbol);
            boolean day = symbol == null ? isDayAt(at, w, zone) : symbol.endsWith("_night") ? false : symbol.endsWith("_day") ? true : isDayAt(at, w, zone);
            if (at <= now && at > curAt) {
                curAt = at;
                w.temp = temp;
                w.code = code;
                w.isDay = day;
            }
            if (at > now && w.hours.size() < 6) {
                w.hours.add(at);
                w.hourTemp.add(temp);
                w.hourCode.add(code);
                w.hourDay.add(day);
            }
            int d = (int) Math.round((midnight(at, zone) - today) / 86_400_000.0);
            if (d < 0 || d > 5) continue;
            if (Double.isNaN(max[d]) || temp > max[d]) max[d] = temp;
            if (Double.isNaN(min[d]) || temp < min[d]) min[d] = temp;
            long gap = Math.abs(at - (midnight(at, zone) + 13 * 3_600_000L));
            String block = symbol(data, "next_6_hours", "next_12_hours", "next_1_hours");
            if (block != null && gap < noonGap[d]) {
                noonGap[d] = gap;
                noonCode[d] = wmo(block);
            }
        }
        if (Double.isNaN(w.temp) && series.length() > 0) {   // every entry is in the future: take the first
            JSONObject data = series.getJSONObject(0).getJSONObject("data");
            w.temp = data.getJSONObject("instant").getJSONObject("details").getDouble("air_temperature");
            String symbol = symbol(data, "next_1_hours", "next_6_hours", "next_12_hours");
            w.code = symbol == null ? -1 : wmo(symbol);
        }
        w.max = max[0];
        w.min = min[0];
        for (int d = 0; d < 6; d++) {
            w.fcAt[d] = today + d * 86_400_000L;
            w.fcMax[d] = max[d];
            w.fcMin[d] = min[d];
            w.fcCode[d] = noonCode[d];
        }
        for (int d = 1; d <= 2; d++) {
            w.dayAt[d - 1] = today + d * 86_400_000L;
            w.dayMax[d - 1] = max[d];
            w.dayMin[d - 1] = min[d];
            w.dayCode[d - 1] = noonCode[d];
        }
        return w;
    }

    private static String symbol(JSONObject data, String... periods) {
        for (String p : periods) {
            JSONObject o = data.optJSONObject(p);
            if (o == null) continue;
            JSONObject s = o.optJSONObject("summary");
            if (s != null && s.has("symbol_code")) return s.optString("symbol_code");
        }
        return null;
    }

    /** MET Norway's symbol_code ("lightrain_day", "partlycloudy_night", "heavysnowshowersandthunder") as a WMO code. */
    private static int wmo(String symbol) {
        int u = symbol.indexOf('_');
        String base = u < 0 ? symbol : symbol.substring(0, u);
        if (base.contains("thunder")) return 95;
        boolean showers = base.contains("showers");
        switch (base.replace("showers", "")) {
            case "clearsky": return 0;
            case "fair": return 1;
            case "partlycloudy": return 2;
            case "cloudy": return 3;
            case "fog": return 45;
            case "lightrain": return showers ? 80 : 61;
            case "rain": return showers ? 81 : 63;
            case "heavyrain": return showers ? 82 : 65;
            case "lightsleet": case "sleet": case "heavysleet": return 71;
            case "lightsnow": return showers ? 85 : 71;
            case "snow": return showers ? 85 : 73;
            case "heavysnow": return showers ? 86 : 75;
            default: return 3;
        }
    }

    static long todayStart() { return midnight(System.currentTimeMillis(), TimeZone.getDefault()); }

    private static long midnight(long t, TimeZone zone) {
        java.util.Calendar c = java.util.Calendar.getInstance(zone);
        c.setTimeInMillis(t);
        c.set(java.util.Calendar.HOUR_OF_DAY, 0);
        c.set(java.util.Calendar.MINUTE, 0);
        c.set(java.util.Calendar.SECOND, 0);
        c.set(java.util.Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Is it daylight at `at`, by today's sunrise and sunset (time of day only)? */
    private static boolean isDayAt(long at, Weather w, TimeZone zone) {
        if (w.sunrise == 0 || w.sunset == 0) return true;
        long t = at - midnight(at, zone);
        return t >= w.sunrise - midnight(w.sunrise, zone) && t < w.sunset - midnight(w.sunset, zone);
    }

    /** Sunrise and sunset (epoch ms) for the local day of `now`, by the sunrise equation; 0 when the sun doesn't rise or set. */
    private static long[] sunTimes(double lat, double lon, long now, TimeZone zone) {
        java.util.Calendar c = java.util.Calendar.getInstance(zone);
        c.setTimeInMillis(now);
        int n = c.get(java.util.Calendar.DAY_OF_YEAR);
        java.util.Calendar u = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH), c.get(java.util.Calendar.DAY_OF_MONTH));
        long base = u.getTimeInMillis();
        return new long[] {sunEvent(lat, lon, n, true, base), sunEvent(lat, lon, n, false, base)};
    }

    private static double mod(double v, double m) {
        return ((v % m) + m) % m;
    }

    private static long sunEvent(double lat, double lon, int n, boolean rise, long base) {
        double lngHour = lon / 15, t = n + ((rise ? 6 : 18) - lngHour) / 24;
        double m = 0.9856 * t - 3.289;
        double l = mod(m + 1.916 * Math.sin(Math.toRadians(m)) + 0.020 * Math.sin(Math.toRadians(2 * m)) + 282.634, 360);
        double ra = mod(Math.toDegrees(Math.atan(0.91764 * Math.tan(Math.toRadians(l)))), 360);
        ra += Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90;
        ra /= 15;
        double sinDec = 0.39782 * Math.sin(Math.toRadians(l)), cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (Math.cos(Math.toRadians(90.833)) - sinDec * Math.sin(Math.toRadians(lat))) / (cosDec * Math.cos(Math.toRadians(lat)));
        if (cosH > 1 || cosH < -1) return 0;
        double h = Math.toDegrees(Math.acos(cosH));
        h = (rise ? 360 - h : h) / 15;
        double ut = mod(h + ra - 0.06571 * t - 6.622 - lngHour, 24);
        return base + Math.round(ut * 3_600_000L);
    }

    private static long localTime(String iso, TimeZone zone) throws ParseException {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US);
        f.setTimeZone(zone);
        return f.parse(iso).getTime();
    }

    static List<String[]> findCity(String name) throws Exception {
        JSONObject o = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=10&format=json&language=" + Ui.language
                + "&name=" + URLEncoder.encode(name.trim(), "UTF-8")));
        JSONArray results = o.optJSONArray("results");
        List<String[]> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();   // places with the same name, state and country read the same
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String city = r.optString("name", "");
            if (city.isEmpty() || !r.has("latitude") || !r.has("longitude")) continue;
            StringBuilder label = new StringBuilder(city);
            for (String part : new String[] {r.optString("admin1", ""), r.optString("country", "")}) {
                if (!part.isEmpty() && !part.equals(city)) label.append(", ").append(part);
            }
            if (!seen.add(label.toString())) continue;
            out.add(new String[] {label.toString(), city,
                    String.format(Locale.US, "%.4f", r.getDouble("latitude")), String.format(Locale.US, "%.4f", r.getDouble("longitude"))});
        }
        return out;
    }

    // MARK: - Yandex Weather's public page (current conditions only; the forecast stays with the first source)

    private static final String BROWSER_UA = "Mozilla/5.0 (Linux; Android 10; Mediaroom) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static String yandexUrl() {
        String lat = Prefs.latitude(), lon = Prefs.longitude();
        try {
            lat = String.format(Locale.US, "%.4f", Double.parseDouble(lat));
            lon = String.format(Locale.US, "%.4f", Double.parseDouble(lon));
        } catch (NumberFormatException ignored) {}
        return "https://yandex.ru/pogoda/?lat=" + lat + "&lon=" + lon;
    }

    /** The page as a browser would get it (about 1.3 MB, compressed in transit). */
    private static String getPage(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return getPage(url, attempt);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static String getPage(String url, int attempt) throws IOException {
        HttpURLConnection c = open(url, attempt);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("User-Agent", BROWSER_UA);
        c.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9");
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 21);
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > 6 << 20) throw new IOException("page too large");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /** Takes the temperature, sky and day/night from the "fact" block of the page. Throws if the page has changed shape. */
    private void applyYandex(Weather w, String page) throws IOException {
        int at = page.indexOf("fact\\\":{\\\"temperature\\\"");
        if (at < 0) throw new IOException(page.contains("captcha") ? "captcha" : "no fact block on the page");
        String f = page.substring(at, Math.min(page.length(), at + 2500)).replace("\\\"", "\"");
        java.util.regex.Matcher t = java.util.regex.Pattern.compile("\"temperatureInCelsius\":(-?\\d+)").matcher(f);
        java.util.regex.Matcher cond = java.util.regex.Pattern.compile("\"condition\":\"([A-Z_]+)\"").matcher(f);
        if (!t.find() || !cond.find()) throw new IOException("the fact block is incomplete");
        java.util.regex.Matcher icon = java.util.regex.Pattern.compile("\"icon\":\"([a-z0-9_+]+)\"").matcher(f);
        java.util.regex.Matcher phen = java.util.regex.Pattern.compile("\"phenomCondition\":\"([A-Z_]+)\"").matcher(f);
        String iconName = icon.find() ? icon.group(1) : "";
        int code = yandexCode(cond.group(1), phen.find() ? phen.group(1) : "");
        boolean day = iconName.endsWith("_n") ? false : iconName.endsWith("_d") ? true : isDayAt(System.currentTimeMillis(), w, TimeZone.getDefault());
        w.temp = Integer.parseInt(t.group(1));
        w.code = code;
        w.isDay = day;
        java.util.regex.Matcher ws = java.util.regex.Pattern.compile("\"windSpeedInMetersPerSecond\":([0-9.]+)").matcher(f);
        java.util.regex.Matcher wd = java.util.regex.Pattern.compile("\"windDirection\":\"([A-Z_]+)\"").matcher(f);
        java.util.regex.Matcher pr = java.util.regex.Pattern.compile("\"pressure\":(\\d+)").matcher(f);
        if (ws.find()) w.windSpeed = Double.parseDouble(ws.group(1));
        if (wd.find()) w.windDir = wd.group(1);
        if (pr.find()) w.pressure = Integer.parseInt(pr.group(1));
        // The sentence about the next two hours, from the page's text ("В ближайшие 2 часа осадков не ожидается."): kept only
        // when it says something is expected.
        java.util.regex.Matcher nc = java.util.regex.Pattern.compile("В ближайш[^.<]{0,140}\\.").matcher(page);
        w.nowcast = "";
        if (nc.find()) {
            String sentence = nc.group().replace("⁠", "").replace("‌", "").trim();
            if (!sentence.contains("не ожидается") && !sentence.contains("без осадков")) w.nowcast = sentence;
        }
        cache.edit().putString("yaNow", w.nowcast).putFloat("yaTemp", (float) w.temp).putInt("yaCode", code).putBoolean("yaDay", day)
                .putFloat("yaWind", (float) w.windSpeed).putString("yaWindDir", w.windDir).putInt("yaPressure", w.pressure)
                .putLong("yaAt", System.currentTimeMillis()).apply();
        try {
            yandexDays(w, page);
        } catch (RuntimeException e) {
            AppLog.w("yandex forecast: " + e);   // the days stay as the first source has them
        }
    }

    /**
     * Today and the next five days from the page's short forecast strip (highest and lowest temperature per day) and the
     * hidden text of the day cards (the sky at midday). Days that can't be read keep the first source's numbers.
     */
    private void yandexDays(Weather w, String page) {
        int strip = page.indexOf("AppShortForecastContainer_days");
        if (strip < 0) return;
        String s = page.substring(strip, Math.min(page.length(), strip + 40_000));
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("AppShortForecastDay_temperature[^>]*>([+\\-−]?)(\\d+)°").matcher(s);
        double[] temps = new double[12];
        int n = 0;
        while (n < 12 && m.find()) temps[n++] = (m.group(1).equals("-") || m.group(1).equals("−") ? -1 : 1) * Integer.parseInt(m.group(2));
        // Day cards: the sky at midday ("днём").
        int[] codes = new int[6];
        java.util.Arrays.fill(codes, -1);
        java.util.regex.Pattern midday = java.util.regex.Pattern.compile("дн[ёе]м\\s*([+\\-−]?)(\\d+)°,\\s*ощущается как\\s*[+\\-−]?\\d+°,\\s*([^,<]+)");
        int from = 0;
        for (int d = 0; d < 6; d++) {
            int at = page.indexOf("AppForecastDay_dayCard", from);
            if (at < 0) break;
            int next = page.indexOf("AppForecastDay_dayCard", at + 20);
            java.util.regex.Matcher c = midday.matcher(page.substring(at, next < 0 ? Math.min(page.length(), at + 4000) : Math.min(next, at + 4000)));
            if (c.find()) {
                codes[d] = russianSky(c.group(3).trim().toLowerCase(java.util.Locale.ROOT));
                if (d == 0) w.dayTemp = (c.group(1).equals("-") || c.group(1).equals("−") ? -1 : 1) * Integer.parseInt(c.group(2));
            }
            from = at + 20;
        }
        for (int d = 0; d < 6 && 2 * d + 1 < n; d++) {
            w.fcMax[d] = temps[2 * d];
            w.fcMin[d] = temps[2 * d + 1];
            if (codes[d] >= 0) w.fcCode[d] = codes[d];
        }
        if (n >= 2) {
            w.max = temps[0];
            w.min = temps[1];
        }
        for (int d = 1; d <= 2; d++) {
            if (2 * d + 1 < n) {
                w.dayMax[d - 1] = temps[2 * d];
                w.dayMin[d - 1] = temps[2 * d + 1];
                if (codes[d] >= 0) w.dayCode[d - 1] = codes[d];
            }
        }
        cache.edit().putFloat("yaDayTemp", (float) w.dayTemp).apply();
        StringBuilder saved = new StringBuilder();   // kept for the next start, until the page is read again
        for (int d = 0; d < 6; d++) saved.append(w.fcMax[d]).append(',').append(w.fcMin[d]).append(',').append(w.fcCode[d]).append(';');
        cache.edit().putString("yaDays", saved.toString()).apply();
    }

    /** The days saved by yandexDays, for the first screen after a start. */
    private void applyCachedDays(Weather w) {
        String saved = cache.getString("yaDays", "");
        String[] days = saved.split(";");
        if (days.length < 6) return;
        for (int d = 0; d < 6; d++) {
            String[] v = days[d].split(",");
            if (v.length < 3) return;
            w.fcMax[d] = Double.parseDouble(v[0]);
            w.fcMin[d] = Double.parseDouble(v[1]);
            w.fcCode[d] = Integer.parseInt(v[2]);
        }
        w.max = w.fcMax[0];
        w.min = w.fcMin[0];
        for (int d = 1; d <= 2; d++) {
            w.dayMax[d - 1] = w.fcMax[d];
            w.dayMin[d - 1] = w.fcMin[d];
            w.dayCode[d - 1] = w.fcCode[d];
        }
    }

    /** Yandex's words for the sky ("пасмурно", "небольшой дождь", ...) as a WMO code; -1 when not recognised. */
    private static int russianSky(String p) {
        if (p.contains("гроз")) return 95;                                   // гроз
        if (p.contains("град")) return 99;                                   // град
        if (p.contains("туман")) return 45;                             // туман
        if (p.contains("дождь со снег")) return 71;   // дождь со снегом
        if (p.contains("снег")) return p.contains("сильн") ? 75 : p.contains("небольш") ? 71 : 73;   // снег
        if (p.contains("ливен")) return p.contains("сильн") ? 82 : 81;   // ливень
        if (p.contains("морос")) return 51;                             // морось
        if (p.contains("дожд")) return p.contains("сильн") ? 65 : p.contains("небольш") ? 61 : 63;   // дождь
        if (p.contains("пасмур")) return 3;                        // пасмурно
        if (p.contains("проясн")) return 3;                        // облачно с прояснениями
        if (p.contains("малооблач")) return 2;      // малооблачно
        if (p.contains("облач")) return 3;                              // облачно
        if (p.contains("ясно")) return 0;                                    // ясно
        return -1;
    }

    /** "1,7 м/с" / "1.7 m/s"; empty when there is no wind data. */
    static String windSpeedText(Weather w) {
        if (Double.isNaN(w.windSpeed)) return "";
        return String.format(Ui.ru ? new Locale("ru") : Locale.US, "%.1f", w.windSpeed) + (Ui.ru ? " м/с" : " m/s");
    }

    /** The compass bearing the wind blows towards (0 = north, 90 = east), or NaN without a direction. */
    static float windToBearing(Weather w) {
        String[] names = {"NORTH", "NORTH_EAST", "EAST", "SOUTH_EAST", "SOUTH", "SOUTH_WEST", "WEST", "NORTH_WEST"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(w.windDir)) return (i * 45 + 180) % 360;   // it blows from there
        return Float.NaN;
    }

    /** "762 мм" / "762 mmHg"; empty when there is no data. */
    static String pressureValue(Weather w) {
        if (w.pressure < 0) return "";
        return w.pressure + (Ui.ru ? " мм" : " mmHg");
    }

    /** Yandex's `condition` ("CLOUDY", "LIGHT_RAIN", ...) as a WMO code, which the rest of the app speaks. */
    private static int yandexCode(String c, String phenomenon) {
        if (phenomenon.contains("FOG")) return 45;
        if (c.contains("THUNDER")) return 95;
        switch (c) {
            case "CLEAR": return 0;
            case "PARTLY_CLOUDY": return 2;
            case "CLOUDY": case "OVERCAST": return 3;
            case "DRIZZLE": return 51;
            case "LIGHT_RAIN": return 61;
            case "RAIN": case "MODERATE_RAIN": return 63;
            case "HEAVY_RAIN": return 65;
            case "SHOWERS": return 81;
            case "WET_SNOW": case "LIGHT_SNOW": return 71;
            case "SNOW": return 73;
            case "SNOW_SHOWERS": return 85;
            case "HAIL": return 99;
            default: return c.contains("SNOW") ? 73 : c.contains("RAIN") ? 63 : 3;
        }
    }

    /**
     * Weather and the rest go over the real network (Wi-Fi or cable), never through the VPN, even though Free My TV is
     * among the apps the VPN carries (that is what lets the VPN indicator test the real path).
     */
    private static HttpURLConnection open(String url, int attempt) throws IOException {
        URL u = new URL(url);
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) appCtx.getSystemService(Context.CONNECTIVITY_SERVICE);
            int seen = 0;
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                // Only a network that works: after a Wi-Fi roam an old one can linger, and binding to it fails with EPERM.
                if (nc != null && !nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)
                        && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        && seen++ == attempt) {
                    return (HttpURLConnection) n.openConnection(u);
                }
            }
        } catch (RuntimeException ignored) {}
        return (HttpURLConnection) u.openConnection();   // the default network, when no other is left to try
    }

    private static String get(String url) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return get(url, attempt);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static String get(String url, int attempt) throws IOException {
        HttpURLConnection c = open(url, attempt);
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestProperty("User-Agent", USER_AGENT);
        try {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toString(StandardCharsets.UTF_8.name());
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Night at your location: from sunset to sunrise (like Apple's "Automatic" appearance: the light design darkens).
     * Uses the last weather fetched (it moves ~1 min a day); without data, 6:00 and 18:00.
     */
    boolean isNight(long now) {
        String theme = Prefs.theme();
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        long t = Shaders.timeOfDay(now, 0);
        long rise = Shaders.timeOfDay(weather.sunrise, 6 * 60 * 60_000L);
        long set = Shaders.timeOfDay(weather.sunset, 18 * 60 * 60_000L);
        return t < rise || t >= set;
    }

    // MARK: - Formatting

    private static Locale locale() {
        return Ui.locale();
    }

    /** 580.4k · 21.0k · 1.04k · 5.61 (as short as possible; the dollar always with cents). */
    static String price(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        return String.format(locale(), "%.2f", v);
    }

    /** 416.3k · 12.2k · 1.04k · 531 · 5.05: abbreviated, for the price row on the home screen. */
    static String compact(double v) {
        if (Double.isNaN(v)) return "—";
        if (v >= 10_000) return String.format(locale(), "%.1fk", v / 1000);
        if (v >= 1000) return String.format(locale(), "%.2fk", v / 1000);
        if (v >= 100) return String.format(locale(), "%.0f", v);
        return String.format(locale(), "%.2f", v);
    }

    /** $108,234 · $185.20 · R$ 581.234 · R$ 5,61 (cents only below a thousand), in the configured currency. */
    static String brl(double v) {
        String symbol = currencySymbol();
        if (Double.isNaN(v)) return symbol + "—";
        return Ui.once("brl", v, () -> v >= 1000 ? String.format(locale(), symbol + "%,.0f", v)
                : String.format(locale(), symbol + "%.2f", v));
    }

    /** The weather in words, from Open-Meteo's WMO code. */
    static String condition(int code, boolean day) {
        if (code < 0) return "";
        if (Ui.en) {
            if (code == 0) return "Clear";
            if (code == 1) return day ? "Mostly Sunny" : "Mostly Clear";
            if (code == 2) return "Partly Cloudy";
            if (code == 3) return "Cloudy";
            if (code == 45 || code == 48) return "Fog";
            if (code >= 51 && code <= 57) return "Drizzle";
            if (code == 65 || code == 67 || code == 82) return "Heavy Rain";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Snow";
            if (code >= 95) return "Thunderstorms";
            return "Rain";
        }
        if (Ui.ru) {
            if (code == 0) return "Ясно";
            if (code == 1) return "Малооблачно";
            if (code == 2) return "Переменная облачность";
            if (code == 3) return "Облачно";
            if (code == 45 || code == 48) return "Туман";
            if (code >= 51 && code <= 57) return "Морось";
            if (code == 65 || code == 67 || code == 82) return "Сильный дождь";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Снег";
            if (code >= 95) return "Гроза";
            return "Дождь";
        }
        if (Ui.es) {
            if (code == 0) return day ? "Despejado" : "Noche despejada";
            if (code == 1) return "Mayormente despejado";
            if (code == 2) return "Parcialmente nublado";
            if (code == 3) return "Nublado";
            if (code == 45 || code == 48) return "Niebla";
            if (code >= 51 && code <= 57) return "Llovizna";
            if (code == 65 || code == 67 || code == 82) return "Lluvia fuerte";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Nieve";
            if (code >= 95) return "Tormenta";
            return "Lluvia";
        }
        if (Ui.de) {
            if (code == 0) return day ? "Sonnig" : "Klar";
            if (code == 1) return day ? "Überwiegend sonnig" : "Überwiegend klar";
            if (code == 2) return "Teilweise bewölkt";
            if (code == 3) return "Bewölkt";
            if (code == 45 || code == 48) return "Nebel";
            if (code >= 51 && code <= 57) return "Nieselregen";
            if (code == 65 || code == 67 || code == 82) return "Starker Regen";
            if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Schnee";
            if (code >= 95) return "Gewitter";
            return "Regen";
        }
        if (code == 0) return day ? "Céu limpo" : "Noite limpa";
        if (code == 1) return "Poucas nuvens";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code == 65 || code == 67 || code == 82) return "Chuva forte";
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "Neve";
        if (code >= 95) return "Tempestade";
        return "Chuva";
    }

    private static String currencySymbol() {
        switch (CURRENCY) {
            case "USD": return "$";
            case "BRL": return "R$ ";
            case "EUR": return "€";
            case "GBP": return "£";
            case "JPY": return "¥";
            default: return CURRENCY + " ";
        }
    }

    /** Celsius or Fahrenheit (config.properties "temperature": C, F, or auto = Fahrenheit in the US). */
    static String degrees(double v) {
        if (Double.isNaN(v)) return "—";
        return Math.round(fahrenheit() ? v * 9 / 5 + 32 : v) + "°";
    }

    private static boolean fahrenheit() {
        String unit = Prefs.temperature();
        if (unit.equals("F")) return true;
        if (unit.equals("C")) return false;
        return "US".equals(Locale.getDefault().getCountry());
    }

    /** "+1.2%" / "−0.8%" (a real minus sign). */
    static String change(double v) {
        if (Double.isNaN(v)) return "";
        return Ui.once("change", v, () -> (v >= 0 ? "+" : "−") + String.format(locale(), "%.1f%%", Math.abs(v)));
    }

    static String hour(long at) {
        return Ui.once("hour", at, () -> Ui.en ? new SimpleDateFormat("h a", Locale.US).format(new java.util.Date(at))
                : new SimpleDateFormat(Ui.es || Ui.ru ? "HH:mm" : Ui.de ? "HH 'Uhr'" : "HH'h'", Ui.locale()).format(new java.util.Date(at)));
    }

    /** How long ago the data was fetched, if it's stale (no internet): "12 min ago". */
    static String staleness(long at, long every) {
        if (at == 0) return "";
        long age = System.currentTimeMillis() - at;
        if (age < every * 2) return "";
        long minutes = age / 60_000;
        if (Ui.en) return minutes < 60 ? minutes + " min ago" : (minutes / 60) + " h ago";
        if (Ui.ru) return minutes < 60 ? minutes + " мин назад" : (minutes / 60) + " ч назад";
        if (Ui.es) return minutes < 60 ? "hace " + minutes + " min" : "hace " + (minutes / 60) + " h";
        if (Ui.de) return minutes < 60 ? "vor " + minutes + " Min." : "vor " + (minutes / 60) + " Std.";
        return minutes < 60 ? "há " + minutes + " min" : "há " + (minutes / 60) + " h";
    }
}

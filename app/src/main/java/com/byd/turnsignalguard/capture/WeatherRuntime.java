package com.byd.turnsignalguard.capture;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Small, isolated local-weather adapter for the stock BYD WeatherData provider. */
public final class WeatherRuntime {
    public static final String PREF_ENABLED = "weather_enabled";
    public static final String PREF_INTERVAL_MINUTES = "weather_interval_minutes";
    public static final String PREF_LAST_SUCCESS_MS = "weather_last_success_ms";
    public static final int DEFAULT_INTERVAL_MINUTES = 15;
    public static final int MIN_INTERVAL_MINUTES = 5;
    public static final int MAX_INTERVAL_MINUTES = 180;
    public static final int RETRY_INTERVAL_MINUTES = 5;
    public static final String THIRD_REFRESH_ACTION = "com.byd.weatherdata.action.THIRD_REFRESH";
    public static final String WEATHER_URI =
            "content://com.byd.weatherdata.utils.WeatherContentProvider/weather";
    private static final long LOCATION_WAIT_MS = 15_000L;
    private static final long MAX_LAST_LOCATION_AGE_MS = 30 * 60 * 1000L;
    private static final long CONNECT_TIMEOUT_MS = 8_000L;
    private static final long READ_TIMEOUT_MS = 12_000L;

    public interface ResultCallback {
        void onResult(boolean success, String error);
    }

    private final Context context;
    private final SharedPreferences preferences;
    private final BiConsumer<String, Object[]> eventSink;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "weather-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final Object stateLock = new Object();
    private volatile boolean started;
    private volatile boolean enabled;
    private volatile boolean inFlight;
    private volatile long generation;
    private volatile long lastSuccessMs;
    private volatile ScheduledFuture<?> scheduled;
    private volatile Future<?> activeFuture;

    public WeatherRuntime(Context context, SharedPreferences preferences) {
        this(context, preferences, null);
    }

    public WeatherRuntime(Context context, SharedPreferences preferences,
            BiConsumer<String, Object[]> eventSink) {
        if (context == null || preferences == null) throw new IllegalArgumentException("null runtime argument");
        Context application = context.getApplicationContext();
        this.context = application == null ? context : application;
        this.preferences = preferences;
        this.eventSink = eventSink;
        this.lastSuccessMs = preferences.getLong(PREF_LAST_SUCCESS_MS, 0L);
    }

    public void start() {
        synchronized (stateLock) {
            if (started) return;
            started = true;
            enabled = preferences.getBoolean(PREF_ENABLED, false);
            if (!enabled) return;
            long dueAt = lastSuccessMs <= 0L ? 0L : lastSuccessMs
                    + TimeUnit.MINUTES.toMillis(intervalMinutes());
            scheduleLocked(Math.max(0L, dueAt - System.currentTimeMillis()));
        }
    }

    /** Re-read the user switch and interval. Disabling invalidates in-flight work immediately. */
    public void settingsChanged() {
        boolean nowEnabled = preferences.getBoolean(PREF_ENABLED, false);
        synchronized (stateLock) {
            int interval = intervalMinutes();
            boolean changed = enabled != nowEnabled;
            enabled = nowEnabled;
            cancelScheduledLocked();
            if (!nowEnabled) {
                generation++;
                cancelActiveLocked();
                emit("weather_disabled", "reason", "settings");
            } else if (started || changed) {
                long dueAt = lastSuccessMs <= 0L ? 0L : lastSuccessMs
                        + TimeUnit.MINUTES.toMillis(interval);
                scheduleLocked(Math.max(0L, dueAt - System.currentTimeMillis()));
                emit("weather_enabled", "interval_minutes", interval);
            }
        }
    }

    public boolean requestNow(String reason) {
        return requestNow(reason, null);
    }

    /** Queues one request. A second request while one is active is deliberately ignored. */
    public boolean requestNow(String reason, ResultCallback callback) {
        synchronized (stateLock) {
            if (!started || !enabled || inFlight) return false;
            cancelScheduledLocked();
            long requestGeneration = generation;
            inFlight = true;
            activeFuture = executor.submit(() -> runRequest(requestGeneration,
                    reason == null ? "manual" : reason, callback));
            return true;
        }
    }

    public void shutdown() {
        synchronized (stateLock) {
            if (!started && executor.isShutdown()) return;
            started = false;
            enabled = false;
            generation++;
            cancelScheduledLocked();
            cancelActiveLocked();
        }
        executor.shutdownNow();
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequestInFlight() {
        return inFlight;
    }

    public long lastSuccessMs() {
        return lastSuccessMs;
    }

    public int intervalMinutes() {
        return WeatherMapping.clampIntervalMinutes(preferences.getInt(
                PREF_INTERVAL_MINUTES, DEFAULT_INTERVAL_MINUTES));
    }

    static long delayAfterResultMs(boolean success, int intervalMinutes) {
        return TimeUnit.MINUTES.toMillis(success
                ? WeatherMapping.clampIntervalMinutes(intervalMinutes) : RETRY_INTERVAL_MINUTES);
    }

    private void runRequest(long requestGeneration, String reason, ResultCallback callback) {
        boolean success = false;
        String error = "";
        try {
            emit("weather_request", "reason", reason);
            Location location = locate();
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather disabled");
            City city = geocode(location);
            String query = coordinateQuery(location);
            JSONObject forecast = getJson("https://api.open-meteo.com/v1/forecast?" + query
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_direction_10m_dominant"
                    + "&timezone=auto&forecast_days=7&forecast_hours=8");
            JSONObject aqi = null;
            try {
                aqi = getJson("https://air-quality-api.open-meteo.com/v1/air-quality?" + query
                        + "&current=european_aqi,pm10,pm2_5&timezone=auto");
            } catch (Exception ignored) {
                emit("weather_aqi_unavailable");
            }
            String payload = WeatherMapping.toBydJson(forecast, aqi, city.localName,
                    city.englishName, System.currentTimeMillis());
            if (!WeatherMapping.isComplete(payload)) throw new IllegalStateException("incomplete BYD payload");
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather disabled");
            if (!writeProvider(payload, requestGeneration)) throw new IllegalStateException("provider readback failed");
            synchronized (stateLock) {
                success = true;
                lastSuccessMs = System.currentTimeMillis();
                preferences.edit().putLong(PREF_LAST_SUCCESS_MS, lastSuccessMs).apply();
            }
            emit("weather_success", "last_success_ms", lastSuccessMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "cancelled";
        } catch (Throwable failure) {
            error = summary(failure);
            emit("weather_failure", "error", error);
        } finally {
            boolean deliverCallback;
            synchronized (stateLock) {
                deliverCallback = ownsGeneration(requestGeneration, generation);
                if (deliverCallback) {
                    inFlight = false;
                    activeFuture = null;
                    if (started && enabled && !executor.isShutdown()) {
                        scheduleLocked(delayAfterResultMs(success, intervalMinutes()));
                    }
                }
            }
            try {
                if (deliverCallback && callback != null) callback.onResult(success, error);
            } catch (Throwable callbackFailure) {
                emit("weather_callback_failure", "error", summary(callbackFailure));
            }
        }
    }

    private boolean writeProvider(String payload, long requestGeneration) {
        if (!isCurrent(requestGeneration)) return false;
        ContentResolver resolver = context.getContentResolver();
        android.net.Uri uri = android.net.Uri.parse(WEATHER_URI);
        ProviderRow old = null;
        ProviderRow written = null;
        ContentValues values = new ContentValues();
        values.put("name", payload);
        boolean inserted = false;
        try {
            old = readProvider(resolver, uri, null, null);
            if (old != null) {
                String oldRowSelection = old.name == null
                        ? "_id=? AND name IS NULL" : "_id=? AND name=?";
                String[] oldRowArgs = old.name == null
                        ? new String[]{String.valueOf(old.id)}
                        : new String[]{String.valueOf(old.id), old.name};
                int updated = resolver.update(uri, values, oldRowSelection, oldRowArgs);
                if (updated != 1) return false;
                written = new ProviderRow(old.id, payload);
            } else {
                android.net.Uri result = resolver.insert(uri, values);
                if (result == null) return false;
                inserted = true;
                written = readProvider(resolver, uri, "name=?", new String[]{payload});
                if (written == null || !payload.equals(written.name)) {
                    restoreProvider(resolver, uri, old, written, inserted, payload);
                    return false;
                }
            }
            if (!isCurrent(requestGeneration)) {
                restoreProvider(resolver, uri, old, written, inserted, payload);
                return false;
            }
            ProviderRow readback = readProvider(resolver, uri, "_id=?",
                    new String[]{String.valueOf(written.id)});
            if (readback == null || !payload.equals(readback.name)) {
                restoreProvider(resolver, uri, old, written, inserted, payload);
                return false;
            }
            synchronized (stateLock) {
                if (!isCurrent(requestGeneration)) {
                    restoreProvider(resolver, uri, old, written, inserted, payload);
                    return false;
                }
                context.sendBroadcast(new android.content.Intent(THIRD_REFRESH_ACTION));
            }
            return true;
        } catch (Throwable failure) {
            restoreProvider(resolver, uri, old, written, inserted, payload);
            return false;
        }
    }

    private ProviderRow readProvider(ContentResolver resolver, android.net.Uri uri,
            String selection, String[] selectionArgs) {
        try (Cursor cursor = resolver.query(uri, new String[]{"_id", "name"},
                selection, selectionArgs, "_id DESC")) {
            if (cursor == null) throw new IllegalStateException("weather provider query failed");
            if (!cursor.moveToFirst()) return null;
            return new ProviderRow(cursor.getLong(0), cursor.getString(1));
        }
    }

    private void restoreProvider(ContentResolver resolver, android.net.Uri uri,
            ProviderRow old, ProviderRow written, boolean inserted, String payload) {
        try {
            if (old != null) {
                ContentValues restore = new ContentValues();
                restore.put("name", old.name);
                resolver.update(uri, restore, "_id=? AND name=?",
                        new String[]{String.valueOf(old.id), payload});
            }
            if (inserted) {
                if (written == null) {
                    written = readProvider(resolver, uri, "name=?", new String[]{payload});
                }
                if (written != null && payload.equals(written.name)) {
                    resolver.delete(uri, "_id=? AND name=?",
                            new String[]{String.valueOf(written.id), payload});
                }
            }
        } catch (Throwable ignored) {
            // A provider failure is still a failure; never send THIRD_REFRESH for it.
        }
    }

    private Location locate() throws Exception {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("location permission denied");
        }
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) throw new IllegalStateException("location unavailable");
        Location best = null;
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null) {
                    long age = Math.max(0L, System.currentTimeMillis() - candidate.getTime());
                    if (age <= MAX_LAST_LOCATION_AGE_MS
                            && (best == null || candidate.getTime() > best.getTime())) best = candidate;
                }
            } catch (SecurityException ignored) {
                // The permission check above is authoritative; OEM providers can still reject one source.
            }
        }
        CountDownLatch latch = new CountDownLatch(1);
        Location[] result = new Location[1];
        LocationListener listener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location != null && (location.getTime() <= 0L
                        || System.currentTimeMillis() - location.getTime() <= MAX_LAST_LOCATION_AGE_MS)) {
                    result[0] = location;
                    latch.countDown();
                }
            }
            @Override public void onProviderEnabled(String provider) { }
            @Override public void onProviderDisabled(String provider) { }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
        };
        try {
            for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    if (manager.isProviderEnabled(provider)) {
                        manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper());
                    }
                } catch (SecurityException ignored) {
                }
            }
            latch.await(LOCATION_WAIT_MS, TimeUnit.MILLISECONDS);
        } finally {
            try { manager.removeUpdates(listener); } catch (SecurityException ignored) { }
        }
        if (result[0] != null) return result[0];
        if (best != null) return best;
        throw new IllegalStateException("location unavailable");
    }

    private City geocode(Location location) {
        String fallback = String.format(Locale.US, "%.4f, %.4f", location.getLatitude(), location.getLongitude());
        try {
            if (!Geocoder.isPresent()) return new City(fallback, fallback);
        } catch (Throwable ignored) {
            return new City(fallback, fallback);
        }
        String local = fallback;
        String english = fallback;
        try {
            List<Address> addresses = new Geocoder(context, Locale.getDefault()).getFromLocation(
                    location.getLatitude(), location.getLongitude(), 1);
            if (addresses != null && !addresses.isEmpty()) {
                Address address = addresses.get(0);
                local = first(address.getLocality(), address.getSubAdminArea(), address.getAdminArea(), fallback);
            }
        } catch (Throwable ignored) {
            emit("weather_geocoder_unavailable");
        }
        try {
            List<Address> englishAddresses = new Geocoder(context, Locale.ENGLISH).getFromLocation(
                    location.getLatitude(), location.getLongitude(), 1);
            if (englishAddresses != null && !englishAddresses.isEmpty()) {
                Address englishAddress = englishAddresses.get(0);
                english = first(englishAddress.getLocality(), englishAddress.getSubAdminArea(),
                        englishAddress.getAdminArea(), local);
            }
        } catch (Throwable ignored) {
            // The localized result remains a valid non-empty city name.
        }
        return new City(local, english);
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout((int) CONNECT_TIMEOUT_MS);
        connection.setReadTimeout((int) READ_TIMEOUT_MS);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new IllegalStateException("HTTP " + status);
            try (InputStream stream = connection.getInputStream();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) body.append(line);
                if (body.length() > 2 * 1024 * 1024) throw new IllegalStateException("response too large");
                return new JSONObject(body.toString());
            }
        } finally {
            connection.disconnect();
        }
    }

    private String coordinateQuery(Location location) throws Exception {
        return "latitude=" + URLEncoder.encode(String.format(Locale.US, "%.6f", location.getLatitude()), "UTF-8")
                + "&longitude=" + URLEncoder.encode(String.format(Locale.US, "%.6f", location.getLongitude()), "UTF-8");
    }

    private boolean isCurrent(long requestGeneration) {
        return started && enabled && generation == requestGeneration && !Thread.currentThread().isInterrupted();
    }

    private void scheduleLocked(long delayMs) {
        if (!started || !enabled || inFlight || executor.isShutdown()) return;
        cancelScheduledLocked();
        scheduled = executor.schedule(() -> requestNow("scheduled"), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    private void cancelScheduledLocked() {
        if (scheduled != null) scheduled.cancel(false);
        scheduled = null;
    }

    private void cancelActiveLocked() {
        Future<?> future = activeFuture;
        activeFuture = null;
        inFlight = false;
        if (future != null) future.cancel(true);
    }

    static boolean ownsGeneration(long requestGeneration, long currentGeneration) {
        return requestGeneration == currentGeneration;
    }

    private void emit(String name, Object... fields) {
        if (eventSink != null) {
            try {
                eventSink.accept(name, fields);
            } catch (Throwable ignored) {
                // Diagnostics must not turn a weather success into a failed write.
            }
        }
    }

    private static String first(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return "Location";
    }

    private static String summary(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    private static final class ProviderRow {
        final long id;
        final String name;
        ProviderRow(long id, String name) { this.id = id; this.name = name; }
    }

    private static final class City {
        final String localName;
        final String englishName;
        City(String localName, String englishName) {
            this.localName = localName;
            this.englishName = englishName;
        }
    }
}

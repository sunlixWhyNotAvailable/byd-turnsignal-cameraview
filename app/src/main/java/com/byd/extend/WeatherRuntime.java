package com.byd.extend;

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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
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
    static final String PREF_LANGUAGE = "ui_language";
    private static final long MAX_LAST_LOCATION_AGE_MS = 30 * 60 * 1000L;
    private static final long CONNECT_TIMEOUT_MS = 8_000L;
    private static final long READ_TIMEOUT_MS = 12_000L;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    enum PrerequisiteState {
        READY,
        WAIT_LOCATION,
        WAIT_NETWORK,
        WAIT_BOTH,
        LOCATION_PERMISSION_DENIED
    }

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
    private boolean requestPending;
    private boolean prerequisiteCheckQueued;
    private boolean prerequisiteRecheckRequested;
    private long pendingGeneration;
    private String pendingReason;
    private ResultCallback pendingCallback;
    private ResultCallback activeCallback;
    private PrerequisiteState waitState = PrerequisiteState.READY;
    private Location callbackLocation;
    private LocationManager waitingLocationManager;
    private ConnectivityManager waitingConnectivityManager;
    private boolean locationCallbackRegistered;
    private boolean networkCallbackRegistered;
    private final LocationListener locationWaitListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            prerequisiteChanged("location", location);
        }

        @Override public void onProviderEnabled(String provider) {
            prerequisiteChanged("location", null);
        }
        @Override public void onProviderDisabled(String provider) {
            prerequisiteChanged("location", null);
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };
    private final ConnectivityManager.NetworkCallback networkWaitCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    prerequisiteChanged("network", null);
                }
                @Override public void onLost(Network network) {
                    prerequisiteChanged("network", null);
                }
                @Override public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    prerequisiteChanged("network", null);
                }
                @Override public void onUnavailable() {
                    prerequisiteChanged("network", null);
                }
            };

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
        boolean unregister = false;
        ResultCallback cancelledCallback = null;
        synchronized (stateLock) {
            int interval = intervalMinutes();
            boolean changed = enabled != nowEnabled;
            enabled = nowEnabled;
            cancelScheduledLocked();
            if (!nowEnabled) {
                cancelledCallback = requestPending ? pendingCallback : activeCallback;
                activeCallback = null;
                generation++;
                clearPendingLocked();
                cancelActiveLocked();
                unregister = true;
                emit("weather_disabled", "reason", "settings");
            } else if (requestPending) {
                schedulePrerequisiteRecheckLocked();
            } else if (started || changed) {
                long dueAt = lastSuccessMs <= 0L ? 0L : lastSuccessMs
                        + TimeUnit.MINUTES.toMillis(interval);
                scheduleLocked(Math.max(0L, dueAt - System.currentTimeMillis()));
                emit("weather_enabled", "interval_minutes", interval);
            }
        }
        if (unregister) unregisterPrerequisiteCallbacks();
        deliver(cancelledCallback, false, "weather disabled");
    }

    public boolean requestNow(String reason) {
        return requestNow(reason, null);
    }

    /** Queues one request. A second request while one is active is deliberately ignored. */
    public boolean requestNow(String reason, ResultCallback callback) {
        synchronized (stateLock) {
            if (!started || !enabled || inFlight || requestPending) return false;
            cancelScheduledLocked();
            requestPending = true;
            pendingGeneration = generation;
            pendingReason = reason == null ? "manual" : reason;
            pendingCallback = callback;
            queuePrerequisiteCheckLocked("request");
            return true;
        }
    }

    public void shutdown() {
        ResultCallback cancelledCallback;
        synchronized (stateLock) {
            if (!started && executor.isShutdown()) return;
            cancelledCallback = requestPending ? pendingCallback : activeCallback;
            activeCallback = null;
            started = false;
            enabled = false;
            generation++;
            cancelScheduledLocked();
            clearPendingLocked();
            cancelActiveLocked();
        }
        unregisterPrerequisiteCallbacks();
        executor.shutdownNow();
        deliver(cancelledCallback, false, "weather shutdown");
    }

    public boolean isStarted() {
        return started;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isRequestInFlight() {
        return inFlight || requestPending;
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

    private void runRequest(
            long requestGeneration, String reason, ResultCallback callback, Location location) {
        boolean success = false;
        String error = "";
        try {
            emit("weather_request", "reason", reason);
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather disabled");
            City city = geocode(location);
            String query = coordinateQuery(location);
            JSONObject forecast = getJson("https://api.open-meteo.com/v1/forecast?" + query
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_direction_10m_dominant"
                    + "&timezone=auto&past_days=1&forecast_days=15&forecast_hours=8");
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
            boolean networkValidated = hasValidatedDefaultNetwork();
            boolean deliverCallback;
            synchronized (stateLock) {
                deliverCallback = ownsGeneration(requestGeneration, generation);
                if (activeCallback == callback) activeCallback = null;
                if (deliverCallback) {
                    inFlight = false;
                    activeFuture = null;
                    if (started && enabled && !executor.isShutdown()) {
                        if (success) {
                            scheduleLocked(delayAfterResultMs(true, intervalMinutes()));
                        } else if (shouldUseFailureTimer(false, networkValidated)) {
                            scheduleLocked(delayAfterResultMs(false, intervalMinutes()));
                        } else {
                            requestPending = true;
                            pendingGeneration = generation;
                            pendingReason = "network_recovery";
                            pendingCallback = null;
                            queuePrerequisiteCheckLocked("fetch_failure");
                        }
                    }
                }
            }
            if (deliverCallback) deliver(callback, success, error);
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
                try {
                    resolver.notifyChange(Settings.System.getUriFor("time_12_24"), null);
                } catch (Throwable failure) {
                    emit("weather_widget_refresh_failure", "error", summary(failure));
                }
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

    static PrerequisiteState prerequisiteState(
            boolean locationAuthorized, boolean locationReady, boolean networkReady) {
        if (!locationAuthorized) return PrerequisiteState.LOCATION_PERMISSION_DENIED;
        if (locationReady && networkReady) return PrerequisiteState.READY;
        if (!locationReady && !networkReady) return PrerequisiteState.WAIT_BOTH;
        return locationReady
                ? PrerequisiteState.WAIT_NETWORK : PrerequisiteState.WAIT_LOCATION;
    }

    static boolean shouldUseFailureTimer(boolean success, boolean networkValidated) {
        return !success && networkValidated;
    }

    private void prerequisiteChanged(String trigger, Location location) {
        synchronized (stateLock) {
            if (!started || !enabled || !requestPending || executor.isShutdown()) return;
            if (location != null) callbackLocation = location;
            if (prerequisiteCheckQueued) {
                prerequisiteRecheckRequested = true;
                return;
            }
            queuePrerequisiteCheckLocked(trigger);
        }
    }

    private void queuePrerequisiteCheckLocked(String trigger) {
        if (!started || !enabled || !requestPending || prerequisiteCheckQueued
                || executor.isShutdown()) return;
        prerequisiteCheckQueued = true;
        FutureTask<Void> task = new FutureTask<>(() -> {
            runPrerequisiteCheck(trigger);
            return null;
        });
        activeFuture = task;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException rejected) {
            prerequisiteCheckQueued = false;
            activeFuture = null;
        }
    }

    private void runPrerequisiteCheck(String trigger) {
        long requestGeneration;
        synchronized (stateLock) {
            if (!started || !enabled || !requestPending) {
                prerequisiteCheckQueued = false;
                activeFuture = null;
                return;
            }
            requestGeneration = pendingGeneration;
        }

        LocationSnapshot location = currentLocation();
        boolean networkReady = hasValidatedDefaultNetwork();
        PrerequisiteState state = prerequisiteState(
                location.authorized, location.value != null, networkReady);
        String reason = null;
        ResultCallback callback = null;
        boolean startRequest = false;
        boolean authorizationDenied = false;
        boolean readinessRetry = false;
        synchronized (stateLock) {
            if (!started || !enabled || !requestPending
                    || pendingGeneration != requestGeneration) {
                prerequisiteCheckQueued = false;
                activeFuture = null;
                return;
            }
            PrerequisiteState previous = waitState;
            if (state == PrerequisiteState.LOCATION_PERMISSION_DENIED) {
                callback = pendingCallback;
                authorizationDenied = true;
                cancelScheduledLocked();
                clearPendingLocked();
                activeFuture = null;
                unregisterPrerequisiteCallbacksLocked();
            } else if (state != PrerequisiteState.READY) {
                boolean transition = state != previous;
                waitState = state;
                prerequisiteCheckQueued = false;
                activeFuture = null;
                updatePrerequisiteCallbacksLocked(state);
                schedulePrerequisiteRecheckLocked();
                if (transition) {
                    emit("weather_prerequisite_wait", "missing", waitLabel(state));
                }
                if (prerequisiteRecheckRequested) {
                    prerequisiteRecheckRequested = false;
                    queuePrerequisiteCheckLocked("callback");
                }
                return;
            } else {
                reason = pendingReason;
                callback = pendingCallback;
                readinessRetry = previous != PrerequisiteState.READY;
                requestPending = false;
                pendingReason = null;
                pendingCallback = null;
                pendingGeneration = 0L;
                prerequisiteCheckQueued = false;
                prerequisiteRecheckRequested = false;
                waitState = PrerequisiteState.READY;
                inFlight = true;
                activeCallback = callback;
                cancelScheduledLocked();
                unregisterPrerequisiteCallbacksLocked();
                startRequest = true;
            }
        }
        if (authorizationDenied) {
            emit("weather_authorization_required", "permission", "location");
            deliver(callback, false, "SecurityException: location permission denied");
        } else if (startRequest) {
            if (readinessRetry) {
                emit("weather_prerequisite_ready", "trigger", trigger);
            }
            runRequest(requestGeneration, reason, callback, location.value);
        }
    }

    private LocationSnapshot currentLocation() {
        boolean authorized = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (!authorized) return new LocationSnapshot(false, null);
        Location best;
        synchronized (stateLock) {
            best = fresh(callbackLocation) ? callbackLocation : null;
        }
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager != null) {
            for (String provider : new String[]{
                    LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
                try {
                    Location candidate = manager.getLastKnownLocation(provider);
                    if (fresh(candidate) && (best == null
                            || candidate.getTime() > best.getTime())) best = candidate;
                } catch (SecurityException ignored) {
                    // One OEM provider can reject access while the granted provider remains usable.
                }
            }
        }
        return new LocationSnapshot(true, best);
    }

    private boolean hasValidatedDefaultNetwork() {
        try {
            ConnectivityManager manager =
                    (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            return capabilities != null
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static boolean fresh(Location location) {
        if (location == null) return false;
        if (location.getTime() <= 0L) return true;
        return Math.max(0L, System.currentTimeMillis() - location.getTime())
                <= MAX_LAST_LOCATION_AGE_MS;
    }

    private void updatePrerequisiteCallbacksLocked(PrerequisiteState state) {
        boolean needLocation = state == PrerequisiteState.WAIT_LOCATION
                || state == PrerequisiteState.WAIT_BOTH;
        boolean needNetwork = state == PrerequisiteState.WAIT_NETWORK
                || state == PrerequisiteState.WAIT_BOTH;
        if (needLocation) registerLocationCallbackLocked();
        else unregisterLocationCallbackLocked();
        if (needNetwork) registerNetworkCallbackLocked();
        else unregisterNetworkCallbackLocked();
    }

    private void registerLocationCallbackLocked() {
        if (locationCallbackRegistered) return;
        LocationManager manager =
                (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) return;
        boolean registered = false;
        for (String provider : new String[]{
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                manager.requestLocationUpdates(
                        provider, 0L, 0f, locationWaitListener, Looper.getMainLooper());
                registered = true;
            } catch (SecurityException | IllegalArgumentException ignored) {
            }
        }
        if (registered) {
            waitingLocationManager = manager;
            locationCallbackRegistered = true;
        }
    }

    private void registerNetworkCallbackLocked() {
        if (networkCallbackRegistered) return;
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return;
        try {
            manager.registerDefaultNetworkCallback(networkWaitCallback);
            waitingConnectivityManager = manager;
            networkCallbackRegistered = true;
        } catch (RuntimeException ignored) {
        }
    }

    private void unregisterPrerequisiteCallbacks() {
        synchronized (stateLock) {
            unregisterPrerequisiteCallbacksLocked();
        }
    }

    private void unregisterPrerequisiteCallbacksLocked() {
        unregisterLocationCallbackLocked();
        unregisterNetworkCallbackLocked();
    }

    private void unregisterLocationCallbackLocked() {
        if (!locationCallbackRegistered) return;
        try {
            waitingLocationManager.removeUpdates(locationWaitListener);
        } catch (SecurityException ignored) {
        }
        waitingLocationManager = null;
        locationCallbackRegistered = false;
    }

    private void unregisterNetworkCallbackLocked() {
        if (!networkCallbackRegistered) return;
        try {
            waitingConnectivityManager.unregisterNetworkCallback(networkWaitCallback);
        } catch (IllegalArgumentException ignored) {
        }
        waitingConnectivityManager = null;
        networkCallbackRegistered = false;
    }

    private void schedulePrerequisiteRecheckLocked() {
        cancelScheduledLocked();
        try {
            scheduled = executor.schedule(() -> {
                synchronized (stateLock) {
                    scheduled = null;
                    if (requestPending) queuePrerequisiteCheckLocked("safety_recheck");
                }
            }, TimeUnit.MINUTES.toMillis(RETRY_INTERVAL_MINUTES), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            scheduled = null;
        }
    }

    private static String waitLabel(PrerequisiteState state) {
        if (state == PrerequisiteState.WAIT_LOCATION) return "location";
        if (state == PrerequisiteState.WAIT_NETWORK) return "network";
        return "both";
    }

    private void deliver(ResultCallback callback, boolean success, String error) {
        if (callback == null) return;
        try {
            callback.onResult(success, error);
        } catch (Throwable callbackFailure) {
            emit("weather_callback_failure", "error", summary(callbackFailure));
        }
    }

    private City geocode(Location location) {
        String language = preferences.getString(PREF_LANGUAGE, "uk");
        String fallback = friendlyLocationFallback(language);
        double latitude = location.getLatitude();
        double longitude = location.getLongitude();
        String failureReason = null;
        try {
            if (!Geocoder.isPresent()) {
                failureReason = "provider_unavailable";
            }
        } catch (Throwable failure) {
            failureReason = "availability_check_failed:" + summary(failure);
        }
        if (failureReason == null) {
            try {
                List<Address> addresses = new Geocoder(context).getFromLocation(
                        latitude, longitude, 1);
                if (addresses != null && !addresses.isEmpty()) {
                    Address address = addresses.get(0);
                    String city = firstNonEmpty(address.getLocality(), address.getSubAdminArea(),
                            address.getAdminArea());
                    if (city != null) return new City(city, city);
                    failureReason = "no_usable_name";
                } else {
                    failureReason = "no_result";
                }
            } catch (Throwable failure) {
                failureReason = summary(failure);
            }
        }
        emit("weather_geocoder_fallback", "ui_language", normalizedLanguage(language),
                "latitude", latitude, "longitude", longitude, "reason", failureReason);
        return new City(fallback, fallback);
    }

    static String normalizedLanguage(String language) {
        return "en".equalsIgnoreCase(language) ? "en" : "uk";
    }

    static String friendlyLocationFallback(String language) {
        return "en".equalsIgnoreCase(language) ? "Current location" : "Поточне місце";
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return null;
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
            try (InputStream stream = connection.getInputStream()) {
                return readJson(stream);
            }
        } finally {
            connection.disconnect();
        }
    }

    static JSONObject readJson(InputStream stream) throws Exception {
        byte[] buffer = new byte[8192];
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int size = 0;
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (count > MAX_RESPONSE_BYTES - size) throw new IllegalStateException("response too large");
            body.write(buffer, 0, count);
            size += count;
        }
        return new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
    }

    private String coordinateQuery(Location location) throws Exception {
        return "latitude=" + URLEncoder.encode(String.format(Locale.US, "%.6f", location.getLatitude()), "UTF-8")
                + "&longitude=" + URLEncoder.encode(String.format(Locale.US, "%.6f", location.getLongitude()), "UTF-8");
    }

    private boolean isCurrent(long requestGeneration) {
        return started && enabled && generation == requestGeneration && !Thread.currentThread().isInterrupted();
    }

    private void scheduleLocked(long delayMs) {
        if (!canScheduleNormal(started, enabled, inFlight, requestPending,
                executor.isShutdown())) return;
        cancelScheduledLocked();
        scheduled = executor.schedule(() -> requestNow("scheduled"), Math.max(0L, delayMs), TimeUnit.MILLISECONDS);
    }

    static boolean canScheduleNormal(boolean started, boolean enabled, boolean inFlight,
            boolean requestPending, boolean executorShutdown) {
        return started && enabled && !inFlight && !requestPending && !executorShutdown;
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

    private void clearPendingLocked() {
        requestPending = false;
        prerequisiteCheckQueued = false;
        prerequisiteRecheckRequested = false;
        pendingGeneration = 0L;
        pendingReason = null;
        pendingCallback = null;
        waitState = PrerequisiteState.READY;
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

    private static final class LocationSnapshot {
        final boolean authorized;
        final Location value;

        LocationSnapshot(boolean authorized, Location value) {
            this.authorized = authorized;
            this.value = value;
        }
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

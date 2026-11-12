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

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import android.os.Build;

import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

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
    // Android 12 Geocoder Binder calls may outlive interruption; a superseding request needs a worker.
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "weather-runtime");
        thread.setDaemon(true);
        return thread;
    });
    private final Object stateLock = new Object();
    private final Object providerWriteLock = new Object();
    private final WeatherPowerState vehiclePower = new WeatherPowerState();
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
    private final WeatherLocationMonitor locationMonitor;
    private String selectedSource = "none";
    private volatile Call activeCall;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS).build();

    private ConnectivityManager waitingConnectivityManager;

    private boolean networkCallbackRegistered;
    private final WeatherLocationAttempt locationAttempt = new WeatherLocationAttempt();

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
        this.locationMonitor = new WeatherLocationMonitor(this.context, this::locationSelectionChanged, this::emit);
        this.lastSuccessMs = preferences.getLong(PREF_LAST_SUCCESS_MS, 0L);
    }

    public void start() {
        synchronized (stateLock) {
            if (started) return;
            started = true;
            enabled = preferences.getBoolean(PREF_ENABLED, false);
            locationMonitor.setActive(enabled && vehiclePower.allowsRequests());
            if (enabled) scheduleLocked(0L);
        }
    }

    public void vehiclePowerChanged(boolean known, boolean off) {
        ResultCallback cancelled = null;
        synchronized (stateLock) {
            if (!vehiclePower.update(known, off)) return;
            emit("weather_vehicle_power", "known", known, "off", off,
                    "suspended", !vehiclePower.allowsRequests());
            locationMonitor.setActive(started && enabled && vehiclePower.allowsRequests());
            if (!vehiclePower.allowsRequests()) selectedSource = "none";
            if (vehiclePower.allowsRequests()) {
                if (started && enabled) scheduleLocked(0L);
            } else {
                cancelled = requestPending ? pendingCallback : activeCallback;
                activeCallback = null;
                generation++;
                cancelScheduledLocked();
                clearPendingLocked();
                cancelActiveLocked();
                unregisterPrerequisiteCallbacksLocked();
            }
        }
        deliver(cancelled, false, "vehicle_power_suspended");
    }

    public boolean isPowerSuspended() { return !vehiclePower.allowsRequests(); }

    /** Re-read the user switch and interval. Disabling invalidates in-flight work immediately. */
    public void settingsChanged() {
        boolean nowEnabled = preferences.getBoolean(PREF_ENABLED, false);
        boolean unregister = false;
        ResultCallback cancelledCallback = null;
        synchronized (stateLock) {
            int interval = intervalMinutes();
            boolean changed = enabled != nowEnabled;
            enabled = nowEnabled;
            locationMonitor.setActive(started && enabled && vehiclePower.allowsRequests());
            if (!enabled) selectedSource = "none";
            cancelScheduledLocked();
            if (!nowEnabled) {
                cancelledCallback = requestPending ? pendingCallback : activeCallback;
                activeCallback = null;
                generation++;
                clearPendingLocked();
                cancelActiveLocked();
                unregister = true;
                emit("weather_disabled", "reason", "settings");
            } else if (!vehiclePower.allowsRequests()) {
                emit("weather_suspended", "reason", "vehicle_power");
            } else if (requestPending) {
                queuePrerequisiteCheckLocked("settings");
            } else if (started || changed) {
                long dueAt = changed || lastSuccessMs <= 0L ? 0L : lastSuccessMs
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
            if (!started || !enabled || !vehiclePower.allowsRequests() || inFlight || requestPending) return false;
            cancelScheduledLocked();
            requestPending = true;
            locationAttempt.clear();

            pendingGeneration = generation;
            pendingReason = reason == null ? "manual" : reason;
            pendingCallback = callback;
            locationMonitor.refresh();
            emitLocationAccess();
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
        locationMonitor.close();
        executor.shutdownNow();
        deliver(cancelledCallback, false, "weather shutdown");
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

    static int errorMessageResource(String error) {
        if ("vehicle_power_suspended".equals(error)) return R.string.runtime_weather_power_suspended;
        if ("location_permission_denied".equals(error)) {
            return R.string.runtime_weather_location_permission_needed;
        }
        if ("location_access_denied".equals(error)) return R.string.runtime_weather_location_denied;
        if ("location_provider_unavailable".equals(error)) return R.string.runtime_weather_location_unavailable;
        if ("location_timeout".equals(error)) return R.string.runtime_weather_location_timeout;
        if ("provider_write_failed".equals(error)) return R.string.runtime_weather_provider_failed;
        return R.string.runtime_weather_failed;
    }

    private void runRequest(
            long requestGeneration, String reason, ResultCallback callback,
            LocationSnapshot location) {
        boolean success = false;
        boolean retryPrerequisites = false;
        String error = "";
        try {
            emit("weather_request", "reason", reason,
                    "candidate_origin", location.origin,
                    "candidate_provider", location.provider,
                    "candidate_mock", location.mock,
                    "candidate_age_ms", location.ageMs);
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather disabled");
            emitLocationDetail(location);
            City city = geocode(location.value);
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather cancelled");
            if (!hasValidatedDefaultNetwork() || WeatherLocationSelection.rejectionReason(
                    locationElapsedNanos(location.value), SystemClock.elapsedRealtimeNanos()) != null) {
                retryPrerequisites = true;
                return;
            }
            String query = coordinateQuery(location.value);
            JSONObject forecast = getJson("https://api.open-meteo.com/v1/forecast?" + query
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,wind_speed_10m,wind_direction_10m,wind_gusts_10m,visibility,uv_index"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability,wind_speed_10m,wind_direction_10m,is_day"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset,uv_index_max,wind_speed_10m_max,wind_direction_10m_dominant"
                    + "&timezone=auto&past_days=1&forecast_days=15&forecast_hours=8", requestGeneration);
            JSONObject aqi = null;
            try {
                aqi = getJson("https://air-quality-api.open-meteo.com/v1/air-quality?" + query
                        + "&current=european_aqi,pm10,pm2_5&timezone=auto", requestGeneration);
            } catch (Exception failure) {
                if (!isCurrent(requestGeneration)) throw new InterruptedException("weather cancelled");
                emit("weather_aqi_unavailable", "stack", android.util.Log.getStackTraceString(failure));
            }
            String payload = WeatherMapping.toBydJson(forecast, aqi, city.localName,
                    city.englishName, System.currentTimeMillis());
            if (!WeatherMapping.isComplete(payload)) throw new IllegalStateException("incomplete BYD payload");
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather disabled");
            if (!writeProvider(payload, requestGeneration)) {
                error = "provider_write_failed";
                throw new IllegalStateException("provider readback failed");
            }
            emit("weather_provider_written");
            synchronized (stateLock) {
                if (!isCurrent(requestGeneration)) throw new InterruptedException("weather cancelled");
                success = true;
                lastSuccessMs = System.currentTimeMillis();
                preferences.edit().putLong(PREF_LAST_SUCCESS_MS, lastSuccessMs).apply();
            }
            emit("weather_success", "last_success_ms", lastSuccessMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            error = "cancelled";
        } catch (Throwable failure) {
            if (!isCurrent(requestGeneration)) {
                error = "cancelled";
                emit("weather_superseded_request_error", "generation", requestGeneration,
                        "stack", android.util.Log.getStackTraceString(failure));
            }
            else {
                if (error.isEmpty()) error = summary(failure);
                emit("weather_failure", "error", error, "cause", android.util.Log.getStackTraceString(failure));
            }
        } finally {
            boolean networkValidated = hasValidatedDefaultNetwork();
            boolean deliverCallback;
            synchronized (stateLock) {
                deliverCallback = ownsGeneration(requestGeneration, generation);
                if (deliverCallback && activeCallback == callback) activeCallback = null;
                if (deliverCallback) {
                    inFlight = false;
                    activeFuture = null;
                    if (started && enabled && vehiclePower.allowsRequests() && !executor.isShutdown()) {
                        if (retryPrerequisites) {
                            requestPending = true;
                            pendingGeneration = generation;
                            pendingReason = reason;
                            pendingCallback = callback;
                            locationAttempt.clear();
                            queuePrerequisiteCheckLocked("prerequisites_changed");
                            deliverCallback = false;
                        } else if (success) {
                            scheduleLocked(delayAfterResultMs(true, intervalMinutes()));
                        } else if (shouldUseFailureTimer(false, networkValidated)) {
                            scheduleLocked(delayAfterResultMs(false, intervalMinutes()));
                        } else {
                            requestPending = true;
                            pendingGeneration = generation;
                            pendingReason = "network_recovery";
                            pendingCallback = null;
                            locationAttempt.clear();

                            queuePrerequisiteCheckLocked("fetch_failure");
                        }
                    }
                }
            }
            if (deliverCallback) deliver(callback, success, error);
        }
    }

    private boolean writeProvider(String payload, long requestGeneration) {
        synchronized (providerWriteLock) {
            return writeProviderLocked(payload, requestGeneration);
        }
    }

    private boolean writeProviderLocked(String payload, long requestGeneration) {
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
            if (!isCurrent(requestGeneration)) return false;
            if (old != null) {
                String oldRowSelection = old.name == null
                        ? "_id=? AND name IS NULL" : "_id=? AND name=?";
                String[] oldRowArgs = old.name == null
                        ? new String[]{String.valueOf(old.id)}
                        : new String[]{String.valueOf(old.id), old.name};
                int updated = resolver.update(uri, values, oldRowSelection, oldRowArgs);
                if (updated != 1) throw new IllegalStateException("weather update affected " + updated + " rows; expected 1");
                written = new ProviderRow(old.id, payload);
            } else {
                android.net.Uri result = resolver.insert(uri, values);
                if (result == null) throw new IllegalStateException("weather insert returned null URI");
                inserted = true;
                written = readProvider(resolver, uri, "name=?", new String[]{payload});
                if (written == null || !payload.equals(written.name)) {
                    throw new IllegalStateException("weather insert readback missing or mismatched");
                }
            }
            if (!isCurrent(requestGeneration)) {
                restoreProvider(resolver, uri, old, written, inserted, payload);
                return false;
            }
            ProviderRow readback = readProvider(resolver, uri, "_id=?",
                    new String[]{String.valueOf(written.id)});
            if (readback == null || !payload.equals(readback.name)) {
                throw new IllegalStateException("weather update readback missing or mismatched");
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
                    emit("weather_widget_refresh_failure", "error", summary(failure),
                            "stack", android.util.Log.getStackTraceString(failure));
                }
            }
            return true;
        } catch (Throwable failure) {
            emit("weather_provider_write_failed", "error", summary(failure),
                    "stack", android.util.Log.getStackTraceString(failure));
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
        } catch (Throwable failure) {
            emit("weather_provider_restore_failed", "stack", android.util.Log.getStackTraceString(failure));
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

    private void locationSelectionChanged(WeatherLocationSources.Selection selection) {
        synchronized (stateLock) {
            if (!started || !enabled || !vehiclePower.allowsRequests() || executor.isShutdown()) return;
            if (!selectedSource.equals(selection.source)) {
                selectedSource = selection.source;
                ResultCallback callback = requestPending ? pendingCallback : activeCallback;
                activeCallback = null;
                generation++;
                cancelScheduledLocked();
                cancelActiveLocked();
                clearPendingLocked();
                requestPending = true;
                pendingGeneration = generation;
                pendingReason = "location_source_changed";
                pendingCallback = callback;
            }
            if (requestPending) {
                if (prerequisiteCheckQueued) prerequisiteRecheckRequested = true;
                else queuePrerequisiteCheckLocked("location");
            }
        }
    }

    private void prerequisiteChanged(String trigger, Location unused) {
        synchronized (stateLock) {
            if (!started || !enabled || !vehiclePower.allowsRequests() || !requestPending || executor.isShutdown()) return;
            if (prerequisiteCheckQueued) prerequisiteRecheckRequested = true;
            else queuePrerequisiteCheckLocked(trigger);
        }
    }
    private void queuePrerequisiteCheckLocked(String trigger) {
        if (!started || !enabled || !vehiclePower.allowsRequests() || !requestPending || prerequisiteCheckQueued
                || executor.isShutdown()) return;
        prerequisiteCheckQueued = true;
        final long requestGeneration = pendingGeneration;
        FutureTask<Void> task = new FutureTask<>(() -> {
            runPrerequisiteCheck(trigger, requestGeneration);
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

    private void runPrerequisiteCheck(String trigger, long requestGeneration) {
        synchronized (stateLock) {
            if (!isCurrent(requestGeneration) || !requestPending || pendingGeneration != requestGeneration) return;
        }

        boolean networkReady = hasValidatedDefaultNetwork();
        // Do not spend the location acquisition window waiting for internet after power-on.
        LocationSnapshot location = networkReady ? currentLocation()
                : new LocationSnapshot(true, null, null, 0L, 0L, false);
        PrerequisiteState state = networkReady ? prerequisiteState(
                location.authorized, location.value != null, true) : PrerequisiteState.WAIT_NETWORK;
        String reason = null;
        ResultCallback callback = null;
        boolean startRequest = false;
        String prerequisiteError = null;
        boolean readinessRetry = false;
        synchronized (stateLock) {
            if (!isCurrent(requestGeneration) || !requestPending
                    || pendingGeneration != requestGeneration) {
                return;
            }
            PrerequisiteState previous = waitState;
            if (state == PrerequisiteState.LOCATION_PERMISSION_DENIED) {
                callback = pendingCallback;
                prerequisiteError = "location_permission_denied";
                failPrerequisiteLocked(prerequisiteError);
            } else if (state != PrerequisiteState.READY) {
                boolean transition = state != previous;
                waitState = state;
                prerequisiteCheckQueued = false;
                activeFuture = null;
                boolean needLocation = networkReady && location.value == null;
                if (needLocation) locationAttempt.start(SystemClock.elapsedRealtime());
                else locationAttempt.clear();
                if (needLocation && locationAttempt.remaining(SystemClock.elapsedRealtime()) == 0L) {
                    callback = pendingCallback;
                    prerequisiteError = "location_timeout";
                    failPrerequisiteLocked(prerequisiteError);
                } else {
                    updatePrerequisiteCallbacksLocked(state);
                    schedulePrerequisiteRecheckLocked();
                }
                if (transition && prerequisiteError == null) {
                    if (needLocation) {
                        emit("weather_prerequisite_wait", "missing", waitLabel(state),
                                "location_rejections", locationWaitRejections(location));
                    } else {
                        emit("weather_prerequisite_wait", "missing", waitLabel(state));
                    }
                    emitLocationDetail(location);
                }
                if (requestPending && prerequisiteRecheckRequested) {
                    prerequisiteRecheckRequested = false;
                    queuePrerequisiteCheckLocked("callback");
                }
                if (prerequisiteError == null) return;
            } else {
                emit("weather_location_ready", "provider", location.provider,
                        "age_ms", location.ageMs, "mock", location.mock,
                        "wait_ms", locationAttempt.duration(SystemClock.elapsedRealtime()));
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
                locationAttempt.clear();
                startRequest = true;
            }
        }
        if (prerequisiteError != null) {
            deliver(callback, false, prerequisiteError);
        } else if (startRequest) {
            if (readinessRetry) {
                emit("weather_prerequisite_ready", "trigger", trigger);
            }
            runRequest(requestGeneration, reason, callback, location);
        }
    }

    private void failPrerequisiteLocked(String reason) {
        long waited = locationAttempt.duration(SystemClock.elapsedRealtime());
        cancelScheduledLocked();
        unregisterPrerequisiteCallbacksLocked();
        clearPendingLocked();
        activeFuture = null;
        long retryMs = TimeUnit.MINUTES.toMillis(RETRY_INTERVAL_MINUTES);
        emit("weather_prerequisite_failed", "reason", reason, "wait_ms", waited,
                "retry_in_ms", retryMs, "retry_elapsed_ms", SystemClock.elapsedRealtime() + retryMs);
        scheduleLocked(retryMs);
    }

    private void emitLocationAccess() {
        emit("weather_location_access",
                "fine", context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED,
                "coarse", context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED,
                "background", Build.VERSION.SDK_INT < 29
                        || context.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    private LocationSnapshot currentLocation() {
        boolean authorized = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
                || context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        WeatherLocationSources.Selection selected = locationMonitor.snapshot();
        WeatherLocationSources.Fix fix = selected.fix;
        long now = SystemClock.elapsedRealtime();
        if (!authorized || fix == null || !fix.usable(now, fix.mock
                ? WeatherLocationSources.MOCK_AGE_MS : WeatherLocationSources.CACHE_AGE_MS)) {
            return new LocationSnapshot(authorized, null, selected.reason, 0, 0, false);
        }
        Location value = new Location(fix.provider == null ? fix.channel : fix.provider);
        value.setLatitude(fix.latitude);
        value.setLongitude(fix.longitude);
        value.setTime(fix.utcMs);
        value.setElapsedRealtimeNanos(fix.elapsedMs * 1_000_000L);
        if (Float.isFinite(fix.accuracy)) value.setAccuracy(fix.accuracy);
        return new LocationSnapshot(true, value, fix.channel, now * 1_000_000L,
                fix.elapsedMs * 1_000_000L, fix.mock);
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

    private void emitLocationDetail(LocationSnapshot snapshot) {
        if (!DiagnosticLogPolicy.extended() || snapshot.value == null) return;
        emit("weather_location_detail", "selected_origin", snapshot.origin,
                "selected_provider", snapshot.provider, "selected_mock", snapshot.mock,
                "selected_age_ms", snapshot.ageMs, "selected_latitude", snapshot.value.getLatitude(),
                "selected_longitude", snapshot.value.getLongitude(), "selected_wall_ms", snapshot.value.getTime());
    }

    private static long locationElapsedNanos(Location location) {
        return location == null ? 0L : location.getElapsedRealtimeNanos();
    }

    private String locationWaitRejections(LocationSnapshot snapshot) {
        return locationMonitor.snapshot().reason;
    }

    private void updatePrerequisiteCallbacksLocked(PrerequisiteState state) {
        if (state != PrerequisiteState.READY) registerNetworkCallbackLocked();
        else unregisterNetworkCallbackLocked();
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
        } catch (RuntimeException failure) {
            emit("weather_network_subscribe_failed", "error", android.util.Log.getStackTraceString(failure));
        }
    }

    private void unregisterPrerequisiteCallbacks() {
        synchronized (stateLock) {
            unregisterPrerequisiteCallbacksLocked();
        }
    }

    private void unregisterPrerequisiteCallbacksLocked() {
        unregisterNetworkCallbackLocked();
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
        boolean needsLocation = waitState == PrerequisiteState.WAIT_LOCATION
                || waitState == PrerequisiteState.WAIT_BOTH;
        long delayMs = needsLocation ? locationAttempt.remaining(SystemClock.elapsedRealtime())
                : TimeUnit.MINUTES.toMillis(RETRY_INTERVAL_MINUTES);
        try {
            scheduled = executor.schedule(() -> {
                synchronized (stateLock) {
                    scheduled = null;
                    if (requestPending) queuePrerequisiteCheckLocked("safety_recheck");
                }
            }, delayMs, TimeUnit.MILLISECONDS);
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
        String language = AppLanguage.read(preferences);
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
                "reason", failureReason);
        return new City(fallback, fallback);
    }

    static String normalizedLanguage(String language) {
        return AppLanguage.normalize(language);
    }

    private String friendlyLocationFallback(String language) {
        return AppLanguage.localizedContext(context, language).getString(R.string.weather_current_location);
    }

    static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value;
        return null;
    }

    private JSONObject getJson(String url, long requestGeneration) throws Exception {
        Call call = http.newCall(new Request.Builder().url(url).header("Accept", "application/json").build());
        synchronized (stateLock) {
            if (!isCurrent(requestGeneration)) throw new InterruptedException("weather cancelled");
            activeCall = call;
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code());
            if (response.body() == null) throw new IllegalStateException("HTTP response has no body");
            return readJson(response.body().byteStream());
        } finally {
            synchronized (stateLock) { if (activeCall == call) activeCall = null; }
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
        return started && enabled && vehiclePower.allowsRequests()
                && generation == requestGeneration && !Thread.currentThread().isInterrupted();
    }

    private void scheduleLocked(long delayMs) {
        if (!canScheduleNormal(started, enabled && vehiclePower.allowsRequests(), inFlight, requestPending,
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
        Call call = activeCall;
        if (call != null) call.cancel();
        Future<?> future = activeFuture;
        activeFuture = null;
        inFlight = false;
        if (future != null) future.cancel(true);
    }

    private void clearPendingLocked() {
        locationAttempt.clear();
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
        final String origin;
        final String provider;
        final boolean mock;
        final long ageMs;
        final long nowNanos;

        LocationSnapshot(boolean authorized, Location value, String origin, long nowNanos,
                long selectedNanos, boolean mock) {
            this.authorized = authorized;
            this.value = value;
            this.origin = origin;
            this.provider = value == null ? null : value.getProvider();
            this.mock = mock;
            this.ageMs = value == null ? -1L : (nowNanos - selectedNanos) / 1_000_000L;
            this.nowNanos = nowNanos;

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

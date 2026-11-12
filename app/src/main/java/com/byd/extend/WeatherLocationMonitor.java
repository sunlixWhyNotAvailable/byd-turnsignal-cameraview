package com.byd.extend;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.OnNmeaMessageListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/** Continuous, latest-value observation, independent of the weather HTTP executor. */
final class WeatherLocationMonitor {
    interface Listener { void changed(WeatherLocationSources.Selection selection); }
    private final Context context;
    private final Listener listener;
    private final BiConsumer<String, Object[]> events;
    private final HandlerThread thread = new HandlerThread("weather-location");
    private final Handler handler;
    private final WeatherLocationSources sources = new WeatherLocationSources();
    private final Set<String> subscribed = new HashSet<>(), errors = new HashSet<>();
    private final Map<String, WeatherLocationSources.Fix> incoming = new HashMap<>();
    private boolean drainQueued, running, nmeaSubscribed;
    private long incomingEpoch;
    private volatile boolean wanted, closed;
    private volatile long epoch;
    private LocationManager manager;
    private WeatherFlpSource flp;
    private Long networkUtc;
    private long networkClockReadMs;
    private long flpStartedMs;
    private String flpDataState = "inactive";
    private volatile WeatherLocationSources.Selection selection =
            new WeatherLocationSources.Selection(null, "inactive", 0, Long.MAX_VALUE);

    private final LocationListener locations = new LocationListener() {
        @Override public void onLocationChanged(Location value) { offer(value.getProvider(), value, epoch); }
        @Override public void onProviderEnabled(String provider) { refresh(); }
        @Override public void onProviderDisabled(String provider) {
            handler.post(() -> {
                if (!running) return;
                sources.remove(provider);
                evaluate();
            });
        }
        @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    };
    private final OnNmeaMessageListener nmea = (sentence, timestamp) -> {
        if (!running || !wanted) return;
        WeatherNmeaFix value = WeatherNmeaFix.parse(sentence, SystemClock.elapsedRealtime());
        if (value == null) return;
        sources.acceptNmea(value);
        evaluate();
    };
    private final Runnable expiry = this::evaluate;

    WeatherLocationMonitor(Context context, Listener listener, BiConsumer<String, Object[]> events) {
        this.context = context;
        this.listener = listener;
        this.events = events;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    WeatherLocationSources.Selection snapshot() { return selection; }

    void setActive(boolean active) {
        if (closed) return;
        if (wanted == active) { if (active) refresh(); return; }
        wanted = active;
        long owner = ++epoch;
        if (!active) selection = new WeatherLocationSources.Selection(null, "inactive", selection.revision + 1, Long.MAX_VALUE);
        handler.post(() -> {
            if (!active) stop();
            else if (owner == epoch && wanted) startOrRefresh();
        });
    }

    void refresh() {
        if (!closed) handler.post(() -> { if (wanted) startOrRefresh(); });
    }

    void close() {
        closed = true;
        wanted = false;
        epoch++;
        handler.post(() -> { stop(); thread.quitSafely(); });
    }

    private void startOrRefresh() {
        running = true;
        if (manager == null) manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            failure("location_service", new IllegalStateException("LocationManager is null"));
            evaluate();
            return;
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER}) {
            try {
                if (manager.getProvider(provider) == null) continue;
                if (!subscribed.contains(provider)) {
                    manager.requestLocationUpdates(provider, 0L, 0f, locations, thread.getLooper());
                    subscribed.add(provider);
                    event("weather_location_subscribed", "provider", provider);
                }
                Location cached = manager.getLastKnownLocation(provider);
                if (cached != null) accept(cached.getProvider(), cached);
            } catch (SecurityException error) { failure("subscribe_or_cache:" + provider, error); }
            catch (RuntimeException error) { failure("subscribe_or_cache:" + provider, error); }
        }
        boolean fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (fine && !nmeaSubscribed) {
            try {
                nmeaSubscribed = manager.addNmeaListener(nmea, handler);
                if (!nmeaSubscribed) failure("nmea_subscribe", new IllegalStateException("addNmeaListener returned false"));
            } catch (RuntimeException error) { failure("nmea_subscribe", error); }
        }
        if (fine && flp == null) startFlp();
        evaluate();
    }

    private void startFlp() {
        long owner = epoch;
        flpStartedMs = SystemClock.elapsedRealtime();
        flpDataState = "awaiting_data";
        flp = new WeatherFlpSource(new WeatherFlpSource.Listener() {
            @Override public void locations(Location[] values) {
                Location newest = null;
                for (Location value : values) {
                    if (value != null && (newest == null || value.getTime() > newest.getTime())) newest = value;
                }
                if (newest != null) offer("flp", newest, owner);
            }
            @Override public void started() {
                handler.post(() -> { if (owner == epoch && wanted) event("weather_flp_started", "interval_ms", 1000); });
            }
            @Override public void failed(String operation, Throwable error) {
                failure("flp_" + operation, error);
                handler.post(() -> {
                    if (owner != epoch) return;
                    flp = null;
                    sources.remove("flp");
                    evaluate();
                });
            }
            @Override public void cleanupFailed(String operation, Throwable error) {
                failure("flp_cleanup_" + operation, error);
            }
        });
        flp.start(context);
    }

    private void offer(String channel, Location value, long owner) {
        synchronized (incoming) {
            if (!wanted || closed || owner != epoch) return;
            if (incomingEpoch != owner) { incoming.clear(); incomingEpoch = owner; }
            incoming.put(channel + ':' + value.isFromMockProvider(), toFix(channel, value));
            if (drainQueued) return;
            drainQueued = true;
        }
        handler.post(() -> {
            Map<String, WeatherLocationSources.Fix> latest;
            long batchEpoch;
            synchronized (incoming) {
                batchEpoch = incomingEpoch;
                latest = new HashMap<>(incoming);
                incoming.clear();
                drainQueued = false;
            }
            if (!wanted || !running || batchEpoch != epoch) return;
            for (WeatherLocationSources.Fix valueEntry : latest.values()) sources.accept(valueEntry, SystemClock.elapsedRealtime());
            evaluate();
        });
    }

    private void accept(String channel, Location location) {
        sources.accept(toFix(channel, location), SystemClock.elapsedRealtime());
    }

    private static WeatherLocationSources.Fix toFix(String channel, Location location) {
        return new WeatherLocationSources.Fix(channel, location.getProvider(),
                location.isFromMockProvider(), "flp".equals(channel) || LocationManager.GPS_PROVIDER.equals(location.getProvider()),
                location.getLatitude(), location.getLongitude(), location.hasAccuracy() ? location.getAccuracy() : Float.NaN,
                location.getElapsedRealtimeNanos() / 1_000_000L, location.getTime());
    }

    private void evaluate() {
        if (!running || !wanted) return;
        long now = SystemClock.elapsedRealtime();
        if (networkClockReadMs == 0 || now - networkClockReadMs >= 1000) {
            networkClockReadMs = now;
            try {
                networkUtc = Build.VERSION.SDK_INT >= 33 ? SystemClock.currentNetworkTimeClock().millis()
                        : (Long) SystemClock.class.getMethod("currentNetworkTimeMillis").invoke(null);
            } catch (Exception | LinkageError error) {
                networkUtc = null;
                failure("network_clock", WeatherFlpSource.unwrap(error));
            }
        }
        Long utcNow = networkUtc == null ? null : networkUtc + now - networkClockReadMs;
        WeatherLocationSources.Selection next = sources.select(now, utcNow);
        WeatherLocationSources.Selection before = selection;
        selection = next;
        listener.changed(next);
        if (!before.source.equals(next.source)) event("weather_location_source", "from", before.source,
                "to", next.source, "reason", next.reason, "revision", next.revision);
        WeatherLocationSources.Fix[] candidates = sources.fixes();
        boolean freshFlp = false;
        for (WeatherLocationSources.Fix fix : candidates) {
            if ("flp".equals(fix.channel) && fix.usable(now, WeatherLocationSources.GPS_AGE_MS)) freshFlp = true;
        }
        String dataState = freshFlp ? "receiving" : flp == null ? "unavailable"
                : now - flpStartedMs <= WeatherLocationSources.GPS_AGE_MS ? "awaiting_data" : "no_fresh_data";
        if (!dataState.equals(flpDataState)) {
            flpDataState = dataState;
            event("weather_flp_data", "state", dataState);
        }
        if (DiagnosticLogPolicy.extended()) {
            for (WeatherLocationSources.Fix fix : candidates) {
                event("weather_location_candidate", "channel", fix.channel, "provider", fix.provider,
                        "mock", fix.mock, "latitude", fix.latitude, "longitude", fix.longitude,
                        "accuracy_m", fix.accuracy, "elapsed_ms", fix.elapsedMs, "utc_ms", fix.utcMs,
                        "age_ms", now - fix.elapsedMs, "network_time_known", utcNow != null,
                        "gps_rejection", fix.gps && !fix.mock ? sources.gpsRejection(fix, now, utcNow) : null,
                        "selected", next.fix == fix);
            }
        }
        handler.removeCallbacks(expiry);
        long nextCheck = next.nextCheckMs;
        if ("awaiting_data".equals(dataState)) nextCheck = Math.min(nextCheck, flpStartedMs + WeatherLocationSources.GPS_AGE_MS + 1);
        if (nextCheck != Long.MAX_VALUE) handler.postDelayed(expiry, Math.max(1, nextCheck - now));
    }

    private void stop() {
        running = false;
        handler.removeCallbacks(expiry);
        synchronized (incoming) { incoming.clear(); }
        sources.clear();
        selection = new WeatherLocationSources.Selection(null, "inactive", selection.revision + 1, Long.MAX_VALUE);
        if (manager != null) {
            try { manager.removeUpdates(locations); }
            catch (RuntimeException error) { failure("unsubscribe_locations", error); }
            try { if (nmeaSubscribed) manager.removeNmeaListener(nmea); }
            catch (RuntimeException error) { failure("unsubscribe_nmea", error); }
        }
        subscribed.clear();
        nmeaSubscribed = false;
        if (flp != null) { flp.stop(); flp = null; }
    }

    private void failure(String operation, Throwable error) {
        String key = operation + ':' + error.getClass().getName() + ':' + error.getMessage();
        boolean first;
        synchronized (errors) { first = errors.add(key); }
        if (first) event("weather_location_failed", "operation", operation,
                "stack", android.util.Log.getStackTraceString(error));
    }

    private void event(String name, Object... fields) { events.accept(name, fields); }
}

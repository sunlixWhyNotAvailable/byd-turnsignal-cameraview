package com.byd.extend;

import java.util.LinkedHashMap;
import java.util.Map;

/** Location arbitration only; no Android, network or OEM operations. Times are milliseconds. */
final class WeatherLocationSources {
    static final long GPS_AGE_MS = 10_000, MOCK_AGE_MS = 30_000, CACHE_AGE_MS = 120_000;
    static final long UTC_TOLERANCE_MS = 60_000, NMEA_AGE_MS = 3_000;
    static final float GPS_ACCURACY_METERS = 300;

    static final class Fix {
        final String channel, provider;
        final boolean mock, gps;
        final double latitude, longitude;
        final float accuracy;
        final long elapsedMs, utcMs;

        Fix(String channel, String provider, boolean mock, boolean gps, double latitude,
                double longitude, float accuracy, long elapsedMs, long utcMs) {
            this.channel = channel;
            this.provider = provider;
            this.mock = mock;
            this.gps = gps;
            this.latitude = latitude;
            this.longitude = longitude;
            this.accuracy = accuracy;
            this.elapsedMs = elapsedMs;
            this.utcMs = utcMs;
        }

        Fix withElapsed(long elapsed) {
            return new Fix(channel, provider, mock, gps, latitude, longitude, accuracy, elapsed, utcMs);
        }

        boolean usable(long now, long age) {
            return Double.isFinite(latitude) && Math.abs(latitude) <= 90
                    && Double.isFinite(longitude) && Math.abs(longitude) <= 180
                    && elapsedMs > 0 && elapsedMs <= now && now - elapsedMs <= age;
        }
    }

    static final class Selection {
        final Fix fix;
        final String source, reason;
        final long revision, nextCheckMs;

        Selection(Fix fix, String reason, long revision, long nextCheckMs) {
            this.fix = fix;
            this.source = sourceOf(fix);
            this.reason = reason;
            this.revision = revision;
            this.nextCheckMs = nextCheckMs;
        }
    }

    private final Map<String, Fix> fixes = new LinkedHashMap<>();
    private final Map<Long, Long> flpEpochs = new LinkedHashMap<>();
    private final Map<String, WeatherNmeaFix> nmea = new LinkedHashMap<>();
    private String selectedSource = "none";
    private long revision;

    void accept(Fix incoming, long now) {
        String key = key(incoming.channel, incoming.mock);
        Fix old = fixes.get(key);
        Fix real = fixes.get(key(incoming.channel, false));
        if (incoming.mock && real != null && real.usable(now, CACHE_AGE_MS)
                && incoming.elapsedMs <= real.elapsedMs) return;
        // Qualcomm stamps elapsed time at delivery. Re-delivery of one GNSS epoch is not a new fix.
        if ("flp".equals(incoming.channel)) {
            if (old != null && old.utcMs == incoming.utcMs) {
                incoming = incoming.withElapsed(Math.min(old.elapsedMs, incoming.elapsedMs));
            }
            flpEpochs.values().removeIf(elapsed -> now - elapsed > CACHE_AGE_MS);
            Long firstElapsed = flpEpochs.get(incoming.utcMs);
            if (firstElapsed != null) incoming = incoming.withElapsed(Math.min(firstElapsed, incoming.elapsedMs));
            else flpEpochs.put(incoming.utcMs, incoming.elapsedMs);
        }
        if (old != null && incoming.elapsedMs < old.elapsedMs && old.usable(now, CACHE_AGE_MS)) return;
        fixes.put(key, incoming);
        // A newer real fix from the SAME Android provider proves that its override has ended.
        Fix mock = fixes.get(key(incoming.channel, true));
        if (!incoming.mock && mock != null && incoming.elapsedMs <= now
                && incoming.elapsedMs >= mock.elapsedMs) fixes.remove(key(incoming.channel, true));
    }

    private static String key(String channel, boolean mock) { return channel + (mock ? ":mock" : ":real"); }

    void remove(String channel) { fixes.remove(key(channel, false)); fixes.remove(key(channel, true)); }

    void acceptNmea(WeatherNmeaFix value) {
        if (value != null) nmea.put(value.type, value);
    }

    void clear() {
        fixes.clear();
        flpEpochs.clear();
        nmea.clear();
        selectedSource = "none";
        revision++;
    }

    Fix[] fixes() { return fixes.values().toArray(new Fix[0]); }

    Selection select(long now, Long networkUtc) {
        Fix mock = null, validGps = null, gps = null, network = null;
        long next = Long.MAX_VALUE;
        for (Fix fix : fixes.values()) {
            if (fix.mock) {
                if (fix.usable(now, MOCK_AGE_MS)) mock = newer(mock, fix);
                next = deadline(next, fix.elapsedMs, MOCK_AGE_MS, now);
            } else if (fix.gps) {
                if (fix.usable(now, CACHE_AGE_MS)) gps = newer(gps, fix);
                if (gpsRejection(fix, now, networkUtc) == null) validGps = newer(validGps, fix);
                next = deadline(next, fix.elapsedMs, GPS_AGE_MS, now);
                next = deadline(next, fix.elapsedMs, CACHE_AGE_MS, now);
            } else {
                if (fix.usable(now, CACHE_AGE_MS)) network = newer(network, fix);
                next = deadline(next, fix.elapsedMs, CACHE_AGE_MS, now);
            }
        }
        for (WeatherNmeaFix value : nmea.values()) {
            next = deadline(next, value.receivedMs, NMEA_AGE_MS, now);
        }
        Fix chosen;
        String reason;
        if (validGps != null) { chosen = validGps; reason = "gps_valid"; }
        else if (mock != null) { chosen = mock; reason = gps == null ? "mock_no_gps" : "mock_" + gpsRejection(gps, now, networkUtc); }
        else if (gps != null) { chosen = gps; reason = "gps_without_mock"; }
        else if (network != null) { chosen = network; reason = "network_fallback"; }
        else { chosen = null; reason = "no_fresh_position"; }
        String source = sourceOf(chosen);
        if (!source.equals(selectedSource)) { selectedSource = source; revision++; }
        return new Selection(chosen, reason, revision, next);
    }

    String gpsRejection(Fix fix, long now, Long networkUtc) {
        if (!fix.usable(now, GPS_AGE_MS)) return "gps_age_or_coordinates";
        if (!Float.isFinite(fix.accuracy) || fix.accuracy <= 0
                || fix.accuracy > GPS_ACCURACY_METERS) return "gps_accuracy";
        if (networkUtc != null && (fix.utcMs <= 0 || Math.abs((double) fix.utcMs
                + (now - fix.elapsedMs) - networkUtc) > UTC_TOLERANCE_MS)) return "gps_utc";
        for (WeatherNmeaFix value : nmea.values()) {
            if (!value.valid && value.matches(fix.utcMs, now)) return "nmea_no_fix";
        }
        return null;
    }

    private static String sourceOf(Fix fix) {
        return fix == null ? "none" : fix.mock ? "mock" : fix.gps ? "gps" : "network";
    }

    private static Fix newer(Fix old, Fix candidate) {
        return old == null || candidate.elapsedMs > old.elapsedMs ? candidate : old;
    }

    private static long deadline(long next, long elapsed, long ttl, long now) {
        if (elapsed > 0 && elapsed <= now && now - elapsed <= ttl) {
            return Math.min(next, elapsed + ttl + 1);
        }
        return next;
    }
}

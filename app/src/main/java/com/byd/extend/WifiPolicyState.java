package com.byd.extend;

import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Pure policy journal transitions plus its small SharedPreferences representation. */
final class WifiPolicyState {
    static final Object LOCK = new Object();
    static final String IDLE = "idle";
    static final String APPLY = "apply";
    static final String RESTORE = "restore";
    static final long RETRY_INTERVAL_MS = 60_000L;

    private static final String GENERATION_KEY = "wifi_policy_generation";
    private static final String OPERATION_KEY = "wifi_policy_operation";
    private static final String CAPTURED_KEY = "wifi_policy_snapshot_captured";
    private static final String SNAPSHOT_KEY = "wifi_policy_snapshot";
    private static final String LAST_ATTEMPT_KEY = "wifi_policy_last_attempt_elapsed";

    private WifiPolicyState() { }

    static void write(SharedPreferences.Editor editor, Journal journal) {
        editor.putBoolean(WifiRetentionSettings.KEY_ENABLED, journal.enabled)
                .putLong(GENERATION_KEY, journal.generation)
                .putString(OPERATION_KEY, journal.operation)
                .putBoolean(CAPTURED_KEY, journal.captured)
                .putLong(LAST_ATTEMPT_KEY, journal.lastAttemptElapsed);
        if (journal.snapshot == null) editor.remove(SNAPSHOT_KEY);
        else editor.putString(SNAPSHOT_KEY, journal.snapshot);
    }

    static void commit(SharedPreferences preferences, Journal next, Journal previous,
            String operation) {
        RuntimeException failure;
        try {
            SharedPreferences.Editor editor = preferences.edit();
            write(editor, next);
            if (editor.commit()) return;
            failure = new IllegalStateException(operation + " commit returned false");
        } catch (RuntimeException error) {
            failure = error;
        }
        try {
            SharedPreferences.Editor rollback = preferences.edit();
            write(rollback, previous);
            if (!rollback.commit()) {
                failure.addSuppressed(new IllegalStateException(operation + " rollback commit returned false"));
            }
        } catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        throw failure;
    }

    static final class Journal {
        final boolean enabled;
        final long generation;
        final String operation;
        final boolean captured;
        final String snapshot;
        final long lastAttemptElapsed;

        Journal(boolean enabled, long generation, String operation, boolean captured,
                String snapshot, long lastAttemptElapsed) {
            this.enabled = enabled;
            this.generation = generation;
            this.operation = operation;
            this.captured = captured;
            this.snapshot = snapshot;
            this.lastAttemptElapsed = lastAttemptElapsed;
        }

        static Journal initial() {
            return new Journal(false, 0L, IDLE, false, null, -1L);
        }

        static Journal read(SharedPreferences preferences) {
            String operation = preferences.getString(OPERATION_KEY, IDLE);
            if (!IDLE.equals(operation) && !APPLY.equals(operation) && !RESTORE.equals(operation)) {
                throw new IllegalStateException("Invalid Wi-Fi policy operation: " + operation);
            }
            long generation = preferences.getLong(GENERATION_KEY, 0L);
            if (generation < 0L || generation == 0L && !IDLE.equals(operation)) {
                throw new IllegalStateException("Invalid Wi-Fi policy generation: " + generation);
            }
            boolean captured = preferences.getBoolean(CAPTURED_KEY, false);
            String snapshot = preferences.getString(SNAPSHOT_KEY, null);
            if (!captured && snapshot != null) {
                throw new IllegalStateException("Wi-Fi policy snapshot exists without captured marker");
            }
            long lastAttemptElapsed = preferences.getLong(LAST_ATTEMPT_KEY, -1L);
            if (lastAttemptElapsed < -1L) {
                throw new IllegalStateException("Invalid Wi-Fi policy retry timestamp: " + lastAttemptElapsed);
            }
            Journal journal = new Journal(preferences.getBoolean(WifiRetentionSettings.KEY_ENABLED, false),
                    generation, operation, captured, snapshot, lastAttemptElapsed);
            if (!journal.intentConsistent()) {
                throw new IllegalStateException("Wi-Fi retention preference does not match its policy operation"
                        + ": enabled=" + journal.enabled + " operation=" + journal.operation);
            }
            return journal;
        }

        Journal toggle(boolean enabled) {
            if (this.enabled == enabled) return this;
            return new Journal(enabled, Math.incrementExact(generation),
                    enabled ? APPLY : RESTORE, captured, snapshot, -1L);
        }

        boolean pending() {
            return APPLY.equals(operation) || RESTORE.equals(operation);
        }

        boolean intentConsistent() {
            return APPLY.equals(operation) == enabled;
        }

        boolean mayAttempt(long nowElapsed) {
            return pending() && intentConsistent()
                    && (lastAttemptElapsed < 0L || nowElapsed < lastAttemptElapsed
                    || nowElapsed - lastAttemptElapsed >= RETRY_INTERVAL_MS);
        }

        Journal attempted(long nowElapsed) {
            if (nowElapsed < 0L) throw new IllegalArgumentException("Negative elapsed time");
            return new Journal(enabled, generation, operation, captured, snapshot, nowElapsed);
        }

        Journal capture(Request request, Snapshot original) {
            if (!isCurrent(request) || !APPLY.equals(operation) || captured) return null;
            return new Journal(enabled, generation, operation, true, original.encode(),
                    lastAttemptElapsed);
        }

        Journal finish(Request request) {
            if (!isCurrent(request)) return null;
            boolean keepOriginal = APPLY.equals(operation);
            return new Journal(enabled, generation, IDLE, keepOriginal && captured,
                    keepOriginal && captured ? snapshot : null, lastAttemptElapsed);
        }

        boolean isCurrent(Request request) {
            return request != null && generation == request.generation
                    && operation.equals(request.operation)
                    && (APPLY.equals(operation) == enabled);
        }

        Request request() {
            return new Request(generation, operation, captured, snapshot);
        }
    }

    static final class Request {
        final long generation;
        final String operation;
        final boolean captured;
        final String snapshot;

        Request(long generation, String operation, boolean captured, String snapshot) {
            this.generation = generation;
            this.operation = operation;
            this.captured = captured;
            this.snapshot = snapshot;
        }
    }

    static final class Snapshot {
        final String sleepPolicy;
        final String offSwitch;

        Snapshot(String sleepPolicy, String offSwitch) {
            requirePolicyValue("wifi_sleep_policy", sleepPolicy);
            requirePolicyValue("byd_off_wifi_switch", offSwitch);
            this.sleepPolicy = sleepPolicy;
            this.offSwitch = offSwitch;
        }

        String encode() {
            try {
                return new JSONObject()
                        .put("sleep", sleepPolicy == null ? JSONObject.NULL : sleepPolicy)
                        .put("off", offSwitch == null ? JSONObject.NULL : offSwitch)
                        .toString();
            } catch (JSONException error) {
                throw new IllegalStateException("Could not encode original Wi-Fi policies", error);
            }
        }

        static Snapshot decode(String encoded) {
            if (encoded == null) {
                throw new IllegalArgumentException("Saved Wi-Fi policy snapshot is missing");
            }
            try {
                JSONObject json = new JSONObject(encoded);
                return new Snapshot(readValue(json, "sleep"), readValue(json, "off"));
            } catch (JSONException error) {
                throw new IllegalArgumentException("Saved Wi-Fi policy snapshot is corrupt", error);
            }
        }

        private static String readValue(JSONObject json, String key) throws JSONException {
            if (!json.has(key)) {
                throw new IllegalArgumentException("Saved Wi-Fi policy snapshot is missing " + key);
            }
            if (json.isNull(key)) return null;
            Object value = json.get(key);
            if (!(value instanceof String)) {
                throw new IllegalArgumentException("Saved Wi-Fi policy " + key + " is not a string");
            }
            return (String) value;
        }

        private static void requirePolicyValue(String key, String value) {
            if (value == null) return;
            try {
                Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("Invalid saved Wi-Fi policy " + key + ": " + value,
                        error);
            }
        }
    }
}

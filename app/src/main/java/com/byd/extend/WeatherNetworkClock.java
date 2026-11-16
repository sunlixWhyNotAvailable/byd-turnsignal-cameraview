package com.byd.extend;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;

/** Optional network UTC: cache API discovery, but not a transient absence of a time sample. */
final class WeatherNetworkClock {
    private final Class<?> platform;
    private final boolean clockApi;
    private Method method;
    private Clock clock;
    private boolean discovered;
    private Throwable permanentFailure, failure;
    private Long sampledUtc;
    private long sampledAt = -1;

    WeatherNetworkClock(Class<?> platform, boolean clockApi) {
        this.platform = platform;
        this.clockApi = clockApi;
    }

    Long read(long now) {
        if (sampledAt < 0 || now - sampledAt >= 1_000) {
            sampledAt = now;
            if (!discovered) {
                discovered = true;
                try { method = platform.getMethod(clockApi ? "currentNetworkTimeClock" : "currentNetworkTimeMillis"); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError error) { permanentFailure = error; }
            }
            if (permanentFailure != null) { failure = permanentFailure; sampledUtc = null; }
            else try {
                if (clockApi) {
                    if (clock == null) clock = (Clock) method.invoke(null);
                    sampledUtc = clock.millis();
                } else sampledUtc = (Long) method.invoke(null);
                failure = null;
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                failure = unwrap(error);
                sampledUtc = null;
                if (failure instanceof SecurityException || failure instanceof IllegalAccessException
                        || failure instanceof LinkageError || failure instanceof ClassCastException)
                    permanentFailure = failure;
            }
        }
        return sampledUtc == null ? null : sampledUtc + now - sampledAt;
    }
    Throwable failure() { return failure; }
    void resetSample() { sampledUtc = null; sampledAt = -1; failure = null; }

    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException && error.getCause() != null) error = error.getCause();
        return error;
    }
}

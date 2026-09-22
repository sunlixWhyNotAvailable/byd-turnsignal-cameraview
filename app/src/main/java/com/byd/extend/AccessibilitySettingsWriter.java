package com.byd.extend;

/** Serializes this process's Accessibility service-list updates. */
final class AccessibilitySettingsWriter {
    private static final Object LOCK = new Object();

    interface Access {
        String readServices();
        boolean writeServices(String value);
        boolean enableAccessibility();
    }

    interface Cancellation { boolean cancelled(); }
    interface Pause { boolean run(); }
    interface Events { void log(String event); }

    static final class Result {
        final boolean ok;
        final boolean listed;
        final boolean cancelled;

        Result(boolean ok, boolean listed, boolean cancelled) {
            this.ok = ok;
            this.listed = listed;
            this.cancelled = cancelled;
        }
    }

    private AccessibilitySettingsWriter() {}

    static Result ensureEnabled(Access access, boolean forceRebind,
            Cancellation cancellation, Pause pause, Events events) {
        synchronized (LOCK) {
            String current = normalize(access.readServices());
            if (cancelled(cancellation)) return new Result(false,
                    WeatherAccessibilitySettings.hasOwnService(current), true);

            boolean installed = WeatherAccessibilitySettings.hasOwnService(current);
            boolean legacy = WeatherAccessibilitySettings.hasLegacyService(current);
            boolean removedForRebind = false;
            try {
                if (forceRebind && installed) {
                    if (!access.writeServices(WeatherAccessibilitySettings
                            .transformEnabledAccessibilityServices(current, false))) {
                        return new Result(false, false, false);
                    }
                    removedForRebind = true;
                    if (!pause.run() || cancelled(cancellation)) {
                        boolean restored = restoreOwnService(access, events);
                        return new Result(false, restored, true);
                    }
                    // Another process may have changed the setting during the rebind pause.
                    current = normalize(access.readServices());
                    legacy = WeatherAccessibilitySettings.hasLegacyService(current);
                }

                if (!WeatherAccessibilitySettings.hasOwnService(current) || legacy) {
                    if (cancelled(cancellation)) {
                        boolean restored = removedForRebind
                                && restoreOwnService(access, events);
                        return new Result(false, restored, true);
                    }
                    if (!access.writeServices(WeatherAccessibilitySettings
                            .transformEnabledAccessibilityServices(current, true))) {
                        boolean restored = removedForRebind
                                && restoreOwnService(access, events);
                        return new Result(false, restored, false);
                    }
                    removedForRebind = false;
                    if (!pause.run()) return new Result(false, true, false);
                }

                if (cancelled(cancellation)) return new Result(false, true, true);
                boolean enabled = access.enableAccessibility();
                String readback = normalize(access.readServices());
                return new Result(enabled && WeatherAccessibilitySettings.hasOwnService(readback),
                        WeatherAccessibilitySettings.hasOwnService(readback), false);
            } catch (RuntimeException | Error failure) {
                if (removedForRebind) restoreOwnService(access, events);
                throw failure;
            }
        }
    }

    private static boolean restoreOwnService(Access access, Events events) {
        try {
            String latest = normalize(access.readServices());
            if (WeatherAccessibilitySettings.hasOwnService(latest)) return true;
            if (access.writeServices(WeatherAccessibilitySettings
                    .transformEnabledAccessibilityServices(latest, true))
                    && WeatherAccessibilitySettings.hasOwnService(
                    normalize(access.readServices()))) return true;
        } catch (RuntimeException ignored) {
            // Report below while retaining the caller's original failure.
        }
        try { events.log("weather_accessibility_restore_failed"); }
        catch (RuntimeException ignored) { /* Recovery reporting is best-effort. */ }
        return false;
    }

    private static boolean cancelled(Cancellation cancellation) {
        return cancellation != null && cancellation.cancelled();
    }

    private static String normalize(String value) {
        if (value == null) return "";
        value = value.trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }
}

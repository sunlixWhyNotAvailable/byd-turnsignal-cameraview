package com.byd.extend;

/** Serializes this process's Accessibility service-list updates. */
final class AccessibilitySettingsWriter {
    private static final Object LOCK = new Object();

    interface Access {
        String readServices();
        boolean writeServices(String value);
        boolean enableAccessibility();
        default void stage(String value) {}
        default Failure failure() { return null; }
    }

    interface Cancellation { boolean cancelled(); }
    interface Pause { boolean run(); }
    interface Events {
        void log(String event);
        default void log(String event, Failure failure) { log(event); }
    }

    static final class Failure {
        final String stage;
        final String status;
        final String errorClass;
        final String error;
        final Throwable diagnosticCause;
        final Failure secondary;

        Failure(String stage, String status, String errorClass, String error) {
            this(stage, status, errorClass, error, null);
        }

        private Failure(String stage, String status, String errorClass, String error,
                Throwable diagnosticCause) {
            this(stage, status, errorClass, error, diagnosticCause, null);
        }

        private Failure(String stage, String status, String errorClass, String error,
                Throwable diagnosticCause, Failure secondary) {
            this.stage = stage;
            this.status = status;
            this.errorClass = errorClass;
            this.error = error;
            this.diagnosticCause = diagnosticCause;
            this.secondary = secondary;
        }

        Failure withCause(Throwable cause) {
            return new Failure(stage, status, errorClass, error, cause, secondary);
        }

        Failure withSecondary(Failure failure) {
            return new Failure(stage, status, errorClass, error, diagnosticCause, failure);
        }
    }

    static final class OperationFailure extends RuntimeException {
        final Failure failure;

        OperationFailure(Failure failure, Throwable cause) {
            super(failure == null ? "Accessibility settings operation failed" : failure.error, cause);
            this.failure = failure;
        }
    }

    static final class Result {
        final boolean ok;
        final boolean listed;
        final boolean cancelled;
        final Failure failure;

        Result(boolean ok, boolean listed, boolean cancelled) {
            this(ok, listed, cancelled, null);
        }

        Result(boolean ok, boolean listed, boolean cancelled, Failure failure) {
            this.ok = ok;
            this.listed = listed;
            this.cancelled = cancelled;
            this.failure = failure;
        }
    }

    private AccessibilitySettingsWriter() {}

    static Result ensureEnabled(Access access, boolean forceRebind,
            Cancellation cancellation, Pause pause, Events events) {
        synchronized (LOCK) {
            String current = "";
            boolean removedForRebind = false;
            try {
                access.stage("read_services");
                current = normalize(access.readServices());
                if (cancelled(cancellation)) return failed(access,
                        WeatherAccessibilitySettings.hasOwnService(current), true,
                        "recovery", "superseded");

                boolean installed = WeatherAccessibilitySettings.hasOwnService(current);
                boolean legacy = WeatherAccessibilitySettings.hasLegacyService(current);
                if (forceRebind && installed) {
                    access.stage("write_services");
                    if (!access.writeServices(WeatherAccessibilitySettings
                            .transformEnabledAccessibilityServices(current, false))) {
                        return failed(access, false, false, "write_services", "command_failure");
                    }
                    removedForRebind = true;
                    boolean paused = pause.run();
                    boolean superseded = cancelled(cancellation);
                    if (!paused || superseded) {
                        boolean restored = restoreOwnService(access, events, null);
                        return new Result(false, restored, true, new Failure("rebind_pause",
                                superseded ? "superseded" : "interrupted", "",
                                superseded ? "Recovery was superseded" : "Rebind pause was interrupted"));
                    }
                    // Another process may have changed the setting during the rebind pause.
                    access.stage("read_services_after_rebind");
                    current = normalize(access.readServices());
                    legacy = WeatherAccessibilitySettings.hasLegacyService(current);
                }

                if (!WeatherAccessibilitySettings.hasOwnService(current) || legacy) {
                    if (cancelled(cancellation)) {
                        boolean restored = removedForRebind
                                && restoreOwnService(access, events, null);
                        return failed(access, restored, true, "recovery", "superseded");
                    }
                    access.stage("write_services");
                    if (!access.writeServices(WeatherAccessibilitySettings
                            .transformEnabledAccessibilityServices(current, true))) {
                        Failure primaryFailure = access.failure();
                        boolean restored = removedForRebind
                                && restoreOwnService(access, events, null);
                        if (primaryFailure == null) primaryFailure = new Failure(
                                "write_services", "command_failure", "", "Service list write failed");
                        return new Result(false, restored, false, primaryFailure);
                    }
                    removedForRebind = false;
                    if (!pause.run()) return new Result(false, true, false,
                            new Failure("registration_pause", "interrupted", "",
                                    "Accessibility registration pause was interrupted"));
                }

                if (cancelled(cancellation)) return failed(access, true, true,
                        "recovery", "superseded");
                boolean enabled = access.enableAccessibility();
                Failure enableFailure = access.failure();
                access.stage("readback_services");
                String readback;
                try {
                    readback = normalize(access.readServices());
                } catch (OperationFailure readbackFailure) {
                    if (!enabled && enableFailure != null) {
                        OperationFailure primaryFailure = new OperationFailure(
                                enableFailure, readbackFailure);
                        boolean restored = removedForRebind
                                && restoreOwnService(access, events, primaryFailure);
                        return new Result(false, restored, false,
                                enableFailure.withSecondary(readbackFailure.failure)
                                        .withCause(primaryFailure));
                    }
                    throw readbackFailure;
                }
                boolean listed = WeatherAccessibilitySettings.hasOwnService(readback);
                if (enabled && listed) return new Result(true, true, false);
                Failure failure = !enabled ? enableFailure : access.failure();
                if (failure == null) failure = new Failure(enabled
                        ? "readback_services" : "enable_accessibility", "not_enabled", "",
                        enabled ? "Service was absent from readback" : "Accessibility setting was not enabled");
                if (!enabled && !listed) failure = failure.withSecondary(new Failure(
                        "readback_services", "service_absent", "",
                        "Service was absent from readback"));
                return new Result(false, listed, false, failure);
            } catch (OperationFailure failure) {
                Failure primaryFailure = failure.failure == null
                        ? access.failure() : failure.failure;
                boolean listed = removedForRebind
                        && restoreOwnService(access, events, failure);
                return new Result(false, listed, false,
                        failureWithCause(primaryFailure, failure));
            } catch (RuntimeException | Error failure) {
                if (removedForRebind) restoreOwnService(access, events, failure);
                throw failure;
            }
        }
    }

    private static Result failed(Access access, boolean listed, boolean cancelled,
            String stage, String status) {
        Failure failure = cancelled
                ? new Failure(stage, "superseded", "", "Accessibility recovery was superseded")
                : access.failure();
        if (failure == null) failure = new Failure(stage, status, "", status);
        return new Result(false, listed, cancelled, failure);
    }

    private static Failure failureWithCause(Failure failure, Throwable cause) {
        return (failure == null
                ? new Failure("accessibility_operation", "command_failure", "", cause.toString())
                : failure).withCause(cause);
    }

    private static boolean restoreOwnService(Access access, Events events, Throwable primary) {
        try {
            access.stage("restore_read_services");
            String latest = normalize(access.readServices());
            if (WeatherAccessibilitySettings.hasOwnService(latest)) return true;
            access.stage("restore_write_services");
            if (!access.writeServices(WeatherAccessibilitySettings
                    .transformEnabledAccessibilityServices(latest, true))) return reportRestoreFailure(
                    access, events, new Failure("restore_write_services", "command_failure", "",
                            "Service list restoration failed"));
            access.stage("restore_readback_services");
            if (WeatherAccessibilitySettings.hasOwnService(normalize(access.readServices()))) return true;
            return reportRestoreFailure(access, events, new Failure("restore_readback_services",
                    "service_absent", "", "Service was absent from restoration readback"));
        } catch (RuntimeException | Error recoveryFailure) {
            if (primary != null && recoveryFailure != primary) primary.addSuppressed(recoveryFailure);
            Failure detail = access.failure();
            if (detail == null) detail = new Failure("restore_own_service", "exception",
                    recoveryFailure.getClass().getName(), recoveryFailure.getMessage());
            else detail = detail.withCause(recoveryFailure);
            return reportRestoreFailure(access, events, detail);
        }
    }

    private static boolean reportRestoreFailure(Access access, Events events, Failure failure) {
        Failure detail = access.failure();
        if (detail == null) detail = failure;
        else if (failure.diagnosticCause != null && detail.diagnosticCause == null)
            detail = detail.withCause(failure.diagnosticCause);
        try { events.log("weather_accessibility_restore_failed", detail); }
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

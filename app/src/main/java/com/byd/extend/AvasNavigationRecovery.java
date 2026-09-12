package com.byd.extend;

/** Persist the distinction between a rejected NAV command and an acquired/uncertain route. */
final class AvasNavigationRecovery {
    interface Marker {
        void write(int value) throws Exception;
    }

    interface Command {
        int run() throws Exception;
    }

    private AvasNavigationRecovery() {}

    static void prepare(Marker marker, Command command) throws Exception {
        // Persist before Binder: helper death or a missing reply must still require teardown.
        marker.write(AvasShellSettings.NAVIGATION_PENDING);
        int status = command.run();
        if (status < 0) {
            // No route was acquired. Volume/focus still need independent restoration.
            marker.write(AvasShellSettings.NAVIGATION_REJECTED);
            throw new IllegalStateException("Navigation route prepare failed (status=" + status + ")");
        }
        marker.write(AvasShellSettings.NAVIGATION_ACTIVE);
    }

    static boolean requiresRelease(int marker) {
        // Legacy marker 2 is ambiguous and must first get a real teardown attempt.
        return marker == AvasShellSettings.NAVIGATION_DIRTY
                || marker == AvasShellSettings.NAVIGATION_PENDING
                || marker == AvasShellSettings.NAVIGATION_ACTIVE;
    }

    static boolean release(int dirty, Marker marker, Command command) throws Exception {
        int status = command.run();
        if (dirty == AvasShellSettings.NAVIGATION_DIRTY && status == -10011) {
            // Owner-approved one-time legacy escape, NOT confirmation of a closed route.
            // Persist before restoring local state so a retry cannot repeat the escape.
            marker.write(AvasShellSettings.NAVIGATION_REJECTED);
            return true;
        }
        if (status < 0) {
            throw new IllegalStateException("Navigation route release failed (status=" + status + ")");
        }
        return false;
    }
}

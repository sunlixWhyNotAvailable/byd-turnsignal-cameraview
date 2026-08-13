package com.byd.turnsignalguard.capture;

final class CameraTransition {
    private static final String PREFIX = "activity_transition:";

    private int sequence;
    private String pendingToken;

    String begin(String reason) {
        pendingToken = PREFIX + (++sequence) + ":"
                + (reason == null ? "unknown" : reason);
        return pendingToken;
    }

    boolean pending() {
        return pendingToken != null;
    }

    boolean matches(String token) {
        return pendingToken != null && pendingToken.equals(token);
    }

    String pendingToken() {
        return pendingToken;
    }

    boolean complete(String token) {
        if (pendingToken == null || !pendingToken.equals(token)) return false;
        pendingToken = null;
        return true;
    }

    void cancel() {
        pendingToken = null;
    }

    static boolean owns(String reason) {
        return reason != null && reason.startsWith(PREFIX);
    }

    static boolean reasonEquals(String token, String reason) {
        return owns(token) && reason != null && token.endsWith(":" + reason);
    }
}

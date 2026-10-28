package com.byd.extend.remote;

import org.junit.Test;
import com.byd.extend.DiagnosticLogPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public class CameraViewingDiagnosticsTest {
    @Test public void verboseDetailDoesNotFormatWhenExtendedLoggingIsOff() {
        boolean previous = DiagnosticLogPolicy.extended();
        AtomicBoolean formatted = new AtomicBoolean();
        try {
            DiagnosticLogPolicy.configure(false);
            CameraViewingDiagnostics.verbose("test_progress", "vdo", () -> {
                formatted.set(true);
                return "frames=1";
            });
            assertFalse(formatted.get());
        } finally {
            DiagnosticLogPolicy.configure(previous);
        }
    }

    @Test public void keepsCauseAndCleanupStacksWithoutCredentials() {
        IOException cause = new IOException("HTTP 403: token=opaque-secret https://login.example/a?key=private");
        IllegalStateException failure = new IllegalStateException("authorize: password=\"1\"", cause);
        failure.addSuppressed(new IOException("cleanup tskey-auth-sensitive rejected"));
        String result = CameraViewingDiagnostics.describe(failure, "opaque-secret", "1");
        assertTrue(result.contains("java.lang.IllegalStateException"));
        assertTrue(result.contains("Caused by: java.io.IOException: HTTP 403"));
        assertTrue(result.contains("Suppressed: java.io.IOException"));
        assertTrue(result.contains("CameraViewingDiagnosticsTest"));
        for (String secret : new String[]{"opaque-secret", "login.example", "tskey-auth-sensitive", "password=\"1\""})
            assertFalse(result.contains(secret));
    }

    @Test public void redactsQuotedSecretsAndUrlsWhileRetainingFailureCategory() {
        String result = CameraViewingDiagnostics.safeText(
                "{\"credential\":\"a b c\",\"error\":\"not allowed\"} wss://relay.example/secret?token=foo");
        assertTrue(result.contains("not allowed"));
        assertFalse(result.contains("a b c"));
        assertFalse(result.contains("relay.example"));
        assertFalse(CameraViewingDiagnostics.safeText("Authorization: Bearer private-data").contains("private-data"));
    }

    @Test public void longUnicodeCauseIsRedactedBeforeLosslessLogcatChunking() {
        String safe = CameraViewingDiagnostics.safeText("HTTP 403 token=hidden-secret "
                + "\uD83D\uDE97".repeat(2500) + " final native cause");
        List<String> parts = CameraViewingDiagnostics.chunks(safe);
        assertTrue(parts.size() > 1);
        assertEquals(safe, String.join("", parts));
        assertFalse(String.join("", parts).contains("hidden-secret"));
        for (String part : parts) {
            assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 3072);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
}

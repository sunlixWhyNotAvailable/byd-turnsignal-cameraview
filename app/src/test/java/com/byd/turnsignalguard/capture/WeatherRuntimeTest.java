package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WeatherRuntimeTest {
    @Test
    public void successfulRequestsUseConfiguredInterval() {
        assertEquals(15, WeatherRuntime.DEFAULT_INTERVAL_MINUTES);
        assertEquals(5, WeatherRuntime.MIN_INTERVAL_MINUTES);
        assertEquals(180, WeatherRuntime.MAX_INTERVAL_MINUTES);
        assertEquals(TimeUnit.MINUTES.toMillis(15),
                WeatherRuntime.delayAfterResultMs(true, 15));
        assertEquals(TimeUnit.MINUTES.toMillis(180),
                WeatherRuntime.delayAfterResultMs(true, 999));
    }

    @Test
    public void failuresUseFiveMinuteRetryRegardlessOfConfiguredInterval() {
        assertEquals(TimeUnit.MINUTES.toMillis(5),
                WeatherRuntime.delayAfterResultMs(false, 180));
    }

    @Test
    public void staleGenerationCannotFinalizeNewRequest() {
        assertTrue(WeatherRuntime.ownsGeneration(4, 4));
        assertFalse(WeatherRuntime.ownsGeneration(4, 5));
    }

    @Test
    public void providerMutationsRemainScopedToRowIdentity() throws Exception {
        Path source = Path.of(
                "app/src/main/java/com/byd/turnsignalguard/capture/WeatherRuntime.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/turnsignalguard/capture/WeatherRuntime.java");
        }
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        assertFalse(text.contains("resolver.update(uri, values, null, null)"));
        assertFalse(text.contains("resolver.update(uri, values, \"_id=?\""));
        assertFalse(text.contains("resolver.delete(uri, \"name=?\""));
        assertTrue(text.contains("\"_id=? AND name=?\""));
        assertTrue(text.contains("\"_id=? AND name IS NULL\""));
    }
}

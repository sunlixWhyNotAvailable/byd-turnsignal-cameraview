package com.byd.turnsignalguard.capture;

import org.junit.Test;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
    public void sourceContractGuardsProviderAndWidgetRefreshMutations() throws Exception {
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
        assertTrue(text.contains("notifyChange(Settings.System.getUriFor(\"time_12_24\"), null)"));
        assertTrue(text.contains("&timezone=auto&past_days=1&forecast_days=15&forecast_hours=8"));
        assertFalse(text.contains("forecast_days=7"));
        assertTrue(text.contains("%.6f"));
        assertFalse(text.contains("Settings.System.put"));
    }

    @Test
    public void responseReaderAcceptsUnderAndAtByteLimit() throws Exception {
        JSONObject under = WeatherRuntime.readJson(new ByteArrayInputStream(
                "{\"text\":\"snow ☃\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("snow ☃", under.getString("text"));

        byte[] exact = new byte[2 * 1024 * 1024];
        Arrays.fill(exact, (byte) ' ');
        byte[] prefix = "{\"x\":\"".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, exact, 0, prefix.length);
        exact[exact.length - 2] = '"';
        exact[exact.length - 1] = '}';
        assertEquals(2 * 1024 * 1024, exact.length);
        assertEquals(2 * 1024 * 1024, WeatherRuntime.readJson(
                new ByteArrayInputStream(exact)).getString("x").length() + prefix.length + 2);
    }

    @Test
    public void responseReaderRejectsOverByteLimitAndMalformedJson() throws Exception {
        byte[] over = new byte[2 * 1024 * 1024 + 1];
        try {
            WeatherRuntime.readJson(new ByteArrayInputStream(over));
            throw new AssertionError("oversized response accepted");
        } catch (IllegalStateException expected) {
            assertEquals("response too large", expected.getMessage());
        }

        try {
            WeatherRuntime.readJson(new ByteArrayInputStream("{malformed".getBytes(StandardCharsets.UTF_8)));
            throw new AssertionError("malformed JSON accepted");
        } catch (org.json.JSONException expected) {
            // Expected.
        }
    }
}

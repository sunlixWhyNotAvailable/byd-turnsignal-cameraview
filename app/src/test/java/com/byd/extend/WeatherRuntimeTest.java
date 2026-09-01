package com.byd.extend;

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
        assertTrue(WeatherRuntime.shouldUseFailureTimer(false, true));
        assertFalse(WeatherRuntime.shouldUseFailureTimer(false, false));
        assertFalse(WeatherRuntime.shouldUseFailureTimer(true, true));
    }

    @Test
    public void fallbackFollowsAppLanguage() {
        assertEquals("Current location", WeatherRuntime.friendlyLocationFallback("en"));
        assertEquals("Поточне місце", WeatherRuntime.friendlyLocationFallback("uk"));
        assertEquals("Поточне місце", WeatherRuntime.friendlyLocationFallback("unknown"));
    }

    @Test
    public void cityNameUsesAddressLocalityThenAdministrativeParts() {
        assertEquals("Berlin", WeatherRuntime.firstNonEmpty("Berlin", "Brandenburg", "Germany"));
        assertEquals(" Brandenburg ", WeatherRuntime.firstNonEmpty("", " Brandenburg ", "Germany"));
        assertEquals("Germany", WeatherRuntime.firstNonEmpty(null, "", "Germany"));
        assertEquals(null, WeatherRuntime.firstNonEmpty(null, " ", ""));
    }

    @Test
    public void prerequisiteMatrixKeepsLocationAndNetworkIndependent() {
        assertEquals(WeatherRuntime.PrerequisiteState.LOCATION_PERMISSION_DENIED,
                WeatherRuntime.prerequisiteState(false, false, false));
        assertEquals(WeatherRuntime.PrerequisiteState.WAIT_BOTH,
                WeatherRuntime.prerequisiteState(true, false, false));
        assertEquals(WeatherRuntime.PrerequisiteState.WAIT_LOCATION,
                WeatherRuntime.prerequisiteState(true, false, true));
        assertEquals(WeatherRuntime.PrerequisiteState.WAIT_NETWORK,
                WeatherRuntime.prerequisiteState(true, true, false));
        assertEquals(WeatherRuntime.PrerequisiteState.READY,
                WeatherRuntime.prerequisiteState(true, true, true));
    }

    @Test
    public void normalScheduleNeverReplacesPendingPrerequisiteSafetyCheck() {
        assertTrue(WeatherRuntime.canScheduleNormal(true, true, false, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, true, false, true, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, true, true, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(false, true, false, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, false, false, false, false));
        assertFalse(WeatherRuntime.canScheduleNormal(true, true, false, false, true));
    }

    @Test
    public void staleGenerationCannotFinalizeNewRequest() {
        assertTrue(WeatherRuntime.ownsGeneration(4, 4));
        assertFalse(WeatherRuntime.ownsGeneration(4, 5));
    }

    @Test
    public void sourceContractGuardsProviderAndWidgetRefreshMutations() throws Exception {
        Path source = Path.of(
                "app/src/main/java/com/byd/extend/WeatherRuntime.java");
        if (!Files.exists(source)) {
            source = Path.of("src/main/java/com/byd/extend/WeatherRuntime.java");
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
        assertTrue(text.contains("registerDefaultNetworkCallback(networkWaitCallback)"));
        assertTrue(text.contains("NET_CAPABILITY_VALIDATED"));
        assertTrue(text.contains("removeUpdates(locationWaitListener)"));
        assertTrue(text.contains("unregisterNetworkCallback(networkWaitCallback)"));
        assertTrue(text.contains("queuePrerequisiteCheckLocked(\"safety_recheck\")"));
        assertTrue(text.contains("cancelledCallback = requestPending ? pendingCallback : activeCallback"));
        assertTrue(text.contains("deliver(cancelledCallback, false, \"weather disabled\")"));
        assertTrue(text.contains("deliver(cancelledCallback, false, \"weather shutdown\")"));
        assertFalse(text.contains("latch.await("));
        assertFalse(text.contains("LOCATION_WAIT_MS"));
        assertEquals(1, occurrences(text, "getFromLocation("));
        assertTrue(text.contains("new Geocoder(context).getFromLocation("));
        assertFalse(text.contains("new Geocoder(context,"));
        assertFalse(text.contains("geocoderLocale("));
        assertTrue(text.contains("new City(city, city)"));
        assertTrue(text.contains("friendlyLocationFallback(language)"));
        assertTrue(text.contains("weather_geocoder_fallback"));
        assertTrue(text.contains("\"latitude\", latitude"));
        assertTrue(text.contains("\"longitude\", longitude"));
        assertFalse(text.contains("String.format(Locale.US, \"%.4f, %.4f\""));
        assertFalse(text.contains("Locale.ENGLISH).getFromLocation"));

        Path manifest = Path.of("app/src/main/AndroidManifest.xml");
        if (!Files.exists(manifest)) manifest = Path.of("src/main/AndroidManifest.xml");
        String manifestText = new String(
                Files.readAllBytes(manifest), StandardCharsets.UTF_8);
        assertTrue(manifestText.contains("android.permission.ACCESS_NETWORK_STATE"));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) count++;
        return count;
    }

    @Test
    public void legacyHandoverHasNoWeatherOnlyServiceBypass() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/CameraHelperService.java");
        if (!Files.exists(source)) source = Path.of("src/main/java/com/byd/extend/CameraHelperService.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertFalse(text.contains("handleBlockedWeatherAction"));
        String create = text.substring(text.indexOf("public void onCreate()"),
                text.indexOf("public int onStartCommand"));
        String blockedCreate = create.substring(create.indexOf("} else if"));
        assertFalse(blockedCreate.contains("weatherRuntime.start()"));
        assertFalse(blockedCreate.contains("startForegroundRuntime()"));
        String entry = text.substring(
                text.indexOf("public int onStartCommand"),
                text.indexOf("private void handleStartCommand"));
        assertTrue(entry.contains("blocked || !willRecover"));
        assertTrue(entry.contains("START_NOT_STICKY"));
        String start = text.substring(text.indexOf("private void handleStartCommand"));
        int blockedStart = start.indexOf("if (LegacySettingsImporter.blocksRuntime(this))");
        String blocked = start.substring(blockedStart,
                start.indexOf("syncWeatherAccessibility(\n                shouldRecover",
                        blockedStart));
        assertTrue(blocked.contains("stopRuntime(true)"));
        assertTrue(blocked.contains("runtimeNeedsReinit = true"));
        assertTrue(blocked.contains("syncWeatherAccessibility(false)"));
        assertTrue(blocked.contains("WEATHER_RESULT_FAILED"));
        assertTrue(blocked.contains("stopServiceFromRuntime(command.startId)"));
        assertTrue(blocked.contains("return;"));
        assertFalse(blocked.contains("pauseActiveRuntime()"));
        assertFalse(blocked.contains("weatherRuntime.start()"));
        String activity = new String(Files.readAllBytes(
                source.resolveSibling("CameraProbeActivity.java")), StandardCharsets.UTF_8);
        int controlsStart = activity.indexOf("private void updateControls()");
        String weatherControls = activity.substring(controlsStart,
                activity.indexOf("if (settingsPanel != null)", controlsStart));
        assertTrue(weatherControls.contains("!legacyRuntimeBlocked"));
        String repair = activity.substring(
                activity.indexOf("private void finishLegacyAccessRestore("),
                activity.indexOf("private void readSettingsTransfer("));
        assertTrue(repair.indexOf("legacyRuntimeBlocked =")
                < repair.indexOf("finishSettingsTransfer()"));
        assertTrue(repair.indexOf("recreate()")
                > repair.indexOf("CameraHelperService.settingsReloaded("));
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

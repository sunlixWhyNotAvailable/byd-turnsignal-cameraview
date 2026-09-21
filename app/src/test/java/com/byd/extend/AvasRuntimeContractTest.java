package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AvasRuntimeContractTest {
    @Test
    public void runtimeKeepsFixedCacheSignalsAndTypedSurface() throws Exception {
        Path source = Path.of("app/src/main/java/com/byd/extend/AvasRuntime.java");
        if (!Files.exists(source)) source = Path.of("src/main/java/com/byd/extend/AvasRuntime.java");
        String text = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);

        assertTrue(text.contains("/data/local/tmp/bydextend_avas"));
        Path transport = Path.of("app/src/main/java/com/byd/extend/AvasVehicleTelemetryTransport.java");
        if (!Files.exists(transport)) transport = Path.of(
                "src/main/java/com/byd/extend/AvasVehicleTelemetryTransport.java");
        String transportText = new String(Files.readAllBytes(transport), StandardCharsets.UTF_8);
        Path controller = Path.of("app/src/main/java/com/byd/extend/AvasTelemetryController.java");
        if (!Files.exists(controller)) controller = Path.of(
                "src/main/java/com/byd/extend/AvasTelemetryController.java");
        String controllerText = new String(Files.readAllBytes(controller), StandardCharsets.UTF_8);
        assertTrue(controllerText.contains("POWER_DEVICE = 1001"));
        assertTrue(controllerText.contains("POWER_FID = 315621418"));
        assertTrue(controllerText.contains("LOCK_DEVICE = 1032"));
        assertTrue(controllerText.contains("LOCK_FID = 1081081864"));
        assertTrue(text.contains("void installAsset(String assetId, ParcelFileDescriptor descriptor)"));
        assertTrue(text.contains("void startManual(String profileId)"));
        assertTrue(text.contains("void stopManual(String profileId)"));
        assertTrue(text.contains("void startAudition(String profileId, String assetId, String sessionId)"));
        assertTrue(text.contains("void stopAudition(String sessionId)"));
        assertTrue(text.contains("void stopAllAuditions()"));
        assertTrue(text.contains("private volatile String auditionSessionId"));
        assertTrue(text.contains("String auditionSessionId()"));
        assertTrue(text.contains("sessionId.matches(\"[0-9a-f]{32}\")"));
        assertTrue(text.contains(".put(\"audition\", audition)"));
        assertTrue(text.contains("\"stage\", \"audition\""));
        assertTrue(text.contains("AvasBuiltinSounds.isBuiltinAsset(asset.id)"));
        assertTrue(text.contains("queue.removeAuditionsForAssets(deleted)"));
        assertTrue(text.contains("assetId.equals(activeAssetId)"));
        assertTrue(text.contains("new AvasVehicleTelemetryTransport(context)"));
        assertTrue(text.contains("telemetryController.activate()"));
        assertTrue(text.contains("telemetryController.deactivate()"));
        assertTrue(text.indexOf("telemetryController.close()")
                < text.indexOf("telemetry.shutdownNow()"));
        assertTrue(controllerText.contains("RECONCILE_MS = 60_000"));
        assertTrue(controllerText.contains("FALLBACK_MS = 250"));
        assertTrue(transportText.contains("context.getSystemService(\"auto\")"));
        assertTrue(transportText.contains("registerListener"));
        assertTrue(transportText.contains("enableDevice"));
        assertTrue(transportText.contains("autoservice.transact(5"));
        assertFalse(transportText.contains("BYDAutoBodyworkDevice"));
        assertTrue(text.contains("skipEligible(config, \"power_on\")"));
        assertTrue(text.contains("skipEligible(config, \"power_off\")"));
        assertTrue(text.contains("telemetryController.eligibilityChanged(skipEligible(next, \"power_on\")"));
        assertTrue(text.contains("\"power_profile\", powerProfile"));
        assertTrue(text.contains("\"observed_delta_ms\", deltaMs"));
        assertTrue(text.contains("\"reason\", \"power_profile_concurrent_lock_unlock\""));
        assertTrue(text.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(text.contains("Os.chmod(CACHE.getAbsolutePath(), 0700)"));
        assertTrue(text.contains("new ParcelFileDescriptor.AutoCloseInputStream(descriptor)"));
        assertFalse(text.contains("Settings.Global"));
        assertFalse(text.contains("MediaPlayer"));
        assertFalse(text.contains("Runtime.getRuntime"));
    }
}

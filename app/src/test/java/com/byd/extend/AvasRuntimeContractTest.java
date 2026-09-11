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
        assertTrue(text.contains("POWER_DEVICE = 1001"));
        assertTrue(text.contains("POWER_FID = 315621418"));
        assertTrue(text.contains("LOCK_DEVICE = 1032"));
        assertTrue(text.contains("LOCK_FID = 1081081864"));
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
        assertTrue(text.contains("scheduleWithFixedDelay(this::poll, 0, POLL_MS"));
        assertTrue(text.contains("StandardCopyOption.ATOMIC_MOVE"));
        assertTrue(text.contains("Os.chmod(CACHE.getAbsolutePath(), 0700)"));
        assertTrue(text.contains("new ParcelFileDescriptor.AutoCloseInputStream(descriptor)"));
        assertFalse(text.contains("Settings.Global"));
        assertFalse(text.contains("MediaPlayer"));
        assertFalse(text.contains("Runtime.getRuntime"));
    }
}

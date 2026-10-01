package com.byd.extend;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class AvasEngineOutputTest {
    @Test public void callbackFailureCancelsDelayedReconciliationAndRetriesNow() {
        FutureTask<Void> delayed = new FutureTask<>(() -> null);
        AtomicInteger attempts = new AtomicInteger();
        AvasEngineTelemetry.retryNow(true, delayed, () -> {
            assertTrue(delayed.isCancelled());
            attempts.incrementAndGet();
        });
        assertEquals(1, attempts.get());
    }

    @Test public void oldQueuedErrorCannotInvalidateOrCancelAReactivatedSession() {
        AtomicInteger generation = new AtomicInteger(1);
        AtomicInteger currentValue = new AtomicInteger(42);
        FutureTask<Void> newSessionTick = new FutureTask<>(() -> null);
        Runnable oldError = () -> AvasEngineTelemetry.retryNow(generation.get() == 1,
                newSessionTick, () -> currentValue.set(-1));
        generation.set(2); // Disable/reactivate before the old callback task executes.
        oldError.run();
        assertFalse(newSessionTick.isCancelled());
        assertEquals(42, currentValue.get());
    }

    @Test public void engineDrainHandlesUnsignedPlaybackCounterWrap() {
        assertFalse(AvasAudioPlayer.engineFramesPending(0, 0));
        assertTrue(AvasAudioPlayer.engineFramesPending(0xffff_ffffL, -2));
        assertFalse(AvasAudioPlayer.engineFramesPending(0xffff_ffffL, -1));
        assertTrue(AvasAudioPlayer.engineFramesPending(0x1_0000_0000L, -1));
        assertFalse(AvasAudioPlayer.engineFramesPending(0x1_0000_0000L, 0));
        assertTrue(AvasAudioPlayer.engineFramesPending(0x1_0000_0010L, 15));
        assertFalse(AvasAudioPlayer.engineFramesPending(0x1_0000_0010L, 16));
    }

    @Test public void independentVolumesClampWithoutWrappingOrPropagatingInvalidSamples() {
        float[] input = {1, -1, 2, Float.NaN, 0.5f};
        short[] exterior = new short[5];
        short[] interior = new short[5];
        AvasEngineRuntime.pcm(input, exterior, 5, 100);
        AvasEngineRuntime.pcm(input, interior, 5, 50);
        assertArrayEquals(new short[]{32767, -32767, 32767, 0, 16384}, exterior);
        assertArrayEquals(new short[]{16384, -16383, 32767, 0, 8192}, interior);
        assertEquals(1f, input[0], 0);
    }

    @Test public void invalidPedalsNeverBecomeThrottle() {
        assertTrue(AvasEngineTelemetry.percentValid(100, 1));
        assertFalse(AvasEngineTelemetry.percentValid(51, 0));
        assertFalse(AvasEngineTelemetry.percentValid(-10011, 1));
        assertFalse(AvasEngineTelemetry.percentValid(101, 1));
    }

    @Test public void exteriorEngineUsesTaggedTrackGainOnceAndKeepsCabinVolumeIndependent()
            throws Exception {
        String player = source("AvasAudioPlayer.java");
        String engine = player.substring(player.indexOf("EngineOutput openEngineOutput("),
                player.indexOf("interface ExteriorSessionCleanup"));
        String runtime = source("AvasEngineRuntime.java");

        assertTrue(engine.contains("MusicPlaybackSource.ENGINE"));
        assertTrue(engine.contains("new ExteriorGain(track, currentVolume"));
        assertTrue(engine.contains("if (gain != null) gain.update()"));
        assertTrue(engine.contains("\"submitted_frames\""));
        assertTrue(engine.contains("\"drained_frames\""));
        assertTrue(player.contains("MusicPlaybackSource.EVENT"));
        assertTrue(player.contains("MusicPlaybackSource.MICROPHONE"));
        assertTrue(runtime.contains("() -> config.exteriorVolume"));
        assertTrue(runtime.contains("pcm(block, pcm, frames, 100)"));
        assertTrue(runtime.contains("pcm(block, pcm, frames, settings.interiorVolume)"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("src/main/java/com/byd/extend", name);
        if (!Files.isRegularFile(path)) path = Paths.get("app/src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}

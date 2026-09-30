package com.byd.extend;

import org.junit.Test;
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
}

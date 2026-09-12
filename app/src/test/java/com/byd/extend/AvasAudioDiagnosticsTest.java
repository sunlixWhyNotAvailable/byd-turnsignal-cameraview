package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public final class AvasAudioDiagnosticsTest {
    @Test public void pcmLevelsCountOnlyAcceptedRangesWithoutChangingTheirSamples() {
        byte[] pcm = {99, 98, 0, (byte) 0x80, 0, 0x40, 0, 0, 97, 96};
        byte[] original = pcm.clone();
        AvasAudioDiagnostics.PcmLevels levels = new AvasAudioDiagnostics.PcmLevels();
        levels.record(pcm, 2, 2);
        levels.record(pcm, 4, 4);
        assertEquals(3, levels.samples);
        assertEquals(32768, levels.peak);
        assertEquals(Math.sqrt((32768d * 32768 + 16384d * 16384) / 3), levels.rms(), 0d);
        org.junit.Assert.assertArrayEquals(original, pcm);
        assertThrows(IllegalArgumentException.class, () -> levels.record(pcm, 2, 3));
        assertThrows(IllegalArgumentException.class, () -> levels.record(pcm, 2, pcm.length));
        assertEquals(3, levels.samples);
    }

    @Test public void pcmLevelsDistinguishAnEmptyOrSilentBuffer() {
        AvasAudioDiagnostics.PcmLevels levels = new AvasAudioDiagnostics.PcmLevels();
        assertEquals(0d, levels.rms(), 0d);
        levels.record(new byte[8], 0, 8);
        assertEquals(4, levels.samples);
        assertEquals(0, levels.peak);
        assertEquals(0d, levels.rms(), 0d);
    }

    @Test public void initialSamplingIsRateAndWindowBoundedAndStopsAtProgress() {
        AvasAudioDiagnostics.SampleGate gate = new AvasAudioDiagnostics.SampleGate(1_000);
        assertTrue(gate.initial(1_000));
        assertFalse(gate.initial(1_099));
        assertTrue(gate.initial(1_100));
        assertTrue(gate.initial(3_000));
        assertFalse(gate.initial(3_001));

        AvasAudioDiagnostics.SampleGate progressed = new AvasAudioDiagnostics.SampleGate(5_000);
        assertTrue(progressed.initial(5_000));
        progressed.progress();
        assertTrue(progressed.hasProgress());
        assertFalse(progressed.initial(5_100));
    }

    @Test public void unavailableAndProbeErrorsAreDataNotFailures() {
        assertFalse(AvasAudioDiagnostics.safeProbe(null).available);
        AvasAudioDiagnostics.Snapshot unavailable =
                AvasAudioDiagnostics.safeProbe(AvasAudioDiagnostics.Snapshot::unavailable);
        assertFalse(unavailable.available);
        AvasAudioDiagnostics.Snapshot failed = AvasAudioDiagnostics.safeProbe(() -> {
            throw new IllegalStateException("diagnostic only");
        });
        assertFalse(failed.available);
        assertTrue(failed.error.contains("diagnostic only"));
        assertEquals(7, AvasAudioDiagnostics.safeProbe(
                () -> AvasAudioDiagnostics.Snapshot.ready(7)).playbackHead);
    }

    @Test public void delayedCallbacksRetainTheRequestTheyCaptured() {
        AvasAudioDiagnostics.Context first =
                new AvasAudioDiagnostics.Context(1, "power_on", "automatic", 41, 100, 101);
        AvasAudioDiagnostics.Context second =
                new AvasAudioDiagnostics.Context(2, "lock", "manual", 41, 200, 201);
        List<Long> callbacks = new ArrayList<>();
        IntConsumer delayedFirst = AvasAudioDiagnostics.bind(first,
                (captured, ignored) -> callbacks.add(captured.requestId));
        IntConsumer currentSecond = AvasAudioDiagnostics.bind(second,
                (captured, ignored) -> callbacks.add(captured.requestId));
        currentSecond.accept(1);
        delayedFirst.accept(-1);
        assertEquals(List.of(2L, 1L), callbacks);
        assertEquals("power_on", first.profile);
        assertEquals("manual", second.source);
        assertEquals(101, first.enqueuedMs);
    }
}

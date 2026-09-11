package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

public final class AvasAudioDiagnosticsTest {
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

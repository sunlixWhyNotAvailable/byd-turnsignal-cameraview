package com.byd.extend;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class AvasPcmTransferTest {
    @Test public void partialAndZeroWritesDeliverEveryByteOnceBeforeDrain() throws Exception {
        Clock clock = new Clock();
        Output output = new Output(4, 0, 8, 4);
        int written = AvasPcmTransfer.write(16, 4, () -> false, output, clock);
        assertEquals(16, written);
        assertArrayEquals(output.pcm, output.accepted.toByteArray());
        assertEquals(List.of("0:16", "4:12", "4:12", "12:4"), output.calls);
        assertEquals(4, output.frames);
        assertEquals(10, clock.now);

        long[] head = {0};
        AvasPcmTransfer.drain(written / 4, 3000, () -> false,
                () -> head[0], () -> head[0]++, clock);
        assertEquals(4, head[0]);
        assertEquals(50, clock.now);
    }

    @Test public void cancellationReturnsOnlySubmittedFramesAndSkipsDrain() throws Exception {
        Clock clock = new Clock();
        Output output = new Output(4, 12);
        int written = AvasPcmTransfer.write(16, 4,
                () -> output.frames == 1, output, clock);
        assertEquals(4, written);
        assertArrayEquals(new byte[]{0, 1, 2, 3}, output.accepted.toByteArray());
        AvasPcmTransfer.drain(4, 3000, () -> true,
                () -> { fail("cancelled drain must not read head"); return 0; },
                () -> fail("cancelled drain must not poll"), clock);
        assertEquals(0, clock.now);
    }

    @Test public void rejectedOrSplitFrameWritesFailWithoutPublishingProgress() {
        for (int result : new int[]{-6, 3}) {
            Output output = new Output(result);
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> AvasPcmTransfer.write(16, 4, () -> false, output, new Clock()));
            assertTrue(failure.getMessage().contains(result < 0 ? "write=-6" : "split"));
            assertEquals(0, output.frames);
        }
    }

    @Test public void cancellationDuringARejectedWriteIsNotReportedAsAudioFailure() throws Exception {
        boolean[] cancelled = {false};
        Output output = new Output(-6) {
            @Override public int write(int offset, int length) {
                cancelled[0] = true;
                return super.write(offset, length);
            }
        };
        assertEquals(0, AvasPcmTransfer.write(16, 4, () -> cancelled[0], output, new Clock()));
        assertEquals(0, output.frames);
    }

    @Test public void stalledWriterTimesOutButPositiveProgressRestartsItsDeadline() throws Exception {
        Clock stalled = new Clock();
        assertThrows(IllegalStateException.class,
                () -> AvasPcmTransfer.write(16, 4, () -> false, new Output(), stalled));
        assertEquals(3010, stalled.now);

        Clock progressing = new Clock();
        Output output = new Output() {
            @Override public int write(int offset, int length) {
                // Four successful frames, each preceded by 2 seconds of backpressure.
                return progressing.now >= (frames + 1) * 2000L ? 4 : 0;
            }
        };
        assertEquals(16, AvasPcmTransfer.write(16, 4, () -> false, output, progressing));
        assertEquals(8000, progressing.now);
        assertEquals(4, output.frames);
    }

    @Test public void drainChecksPlaybackHeadAndUsesItsOwnAbsoluteDeadline() throws Exception {
        Clock clock = new Clock();
        assertThrows(IllegalStateException.class,
                () -> AvasPcmTransfer.drain(4, 3000, () -> false, () -> 3, () -> {}, clock));
        assertEquals(3000, clock.now);
        AvasPcmTransfer.drain(4, 0, () -> false, () -> 4,
                () -> fail("already drained"), clock);
        assertEquals(3000, clock.now);
    }

    private static final class Clock implements AvasPcmTransfer.Clock {
        long now;
        @Override public long now() { return now; }
        @Override public void sleep(long millis) { now += millis; }
    }

    private static class Output implements AvasPcmTransfer.Output {
        final byte[] pcm = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
        final ArrayDeque<Integer> results = new ArrayDeque<>();
        final List<String> calls = new ArrayList<>();
        final ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        int frames;
        Output(int... results) { for (int result : results) this.results.add(result); }
        @Override public int write(int offset, int length) {
            calls.add(offset + ":" + length);
            int result = results.isEmpty() ? 0 : results.remove();
            if (result > 0) accepted.write(pcm, offset, result);
            return result;
        }
        @Override public void written(int offset, int count) { frames += count / 4; }
        @Override public void idle() { }
    }
}

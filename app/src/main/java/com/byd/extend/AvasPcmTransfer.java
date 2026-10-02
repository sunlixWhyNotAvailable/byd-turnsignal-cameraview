package com.byd.extend;

import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** PCM transfer loops shared by the Android output adapter and deterministic JVM checks. */
final class AvasPcmTransfer {
    interface Clock {
        long now();
        void sleep(long millis) throws InterruptedException;
    }
    interface Output {
        default void awaitReady() throws Exception { }
        int write(int offset, int length) throws Exception;
        void written(int offset, int count);
        void idle();
    }

    private AvasPcmTransfer() { }

    /** AudioTrack head wraps at 32 bits and resets on flush; discarded PCM is not played PCM. */
    static final class PlaybackHead {
        long submitted;
        volatile long drained;
        volatile long discarded;
        private int previous;

        void observe(int head) {
            long advanced = (Integer.toUnsignedLong(head) - Integer.toUnsignedLong(previous))
                    & 0xffff_ffffL;
            drained += Math.min(advanced, Math.max(0, submitted - drained - discarded));
            previous = head;
        }

        void flushed(int headBeforeFlush) {
            observe(headBeforeFlush);
            discarded += Math.max(0, submitted - drained - discarded);
            previous = 0;
        }

        boolean completed(long frame) { return drained + discarded >= frame; }
    }

    static int write(int length, int frameSize, BooleanSupplier cancelled,
            Output output, Clock clock) throws Exception {
        int offset = 0;
        long lastProgress = clock.now();
        while (offset < length && !cancelled.getAsBoolean()) {
            long beforeWait = clock.now();
            output.awaitReady();
            lastProgress += clock.now() - beforeWait;
            if (cancelled.getAsBoolean()) return offset;
            int count = output.write(offset, length - offset);
            if (count < 0) {
                if (cancelled.getAsBoolean()) return offset;
                throw new IllegalStateException("AudioTrack.write=" + count);
            }
            if (count % frameSize != 0) throw new IllegalStateException("AudioTrack split a PCM frame");
            if (count > 0) {
                output.written(offset, count);
                offset += count;
                lastProgress = clock.now();
            } else {
                if (cancelled.getAsBoolean()) return offset;
                output.idle();
                if (clock.now() - lastProgress > 3000) {
                    throw new IllegalStateException("AudioTrack write stalled");
                }
                clock.sleep(10);
            }
        }
        return offset;
    }

    static void drain(long framesWritten, long timeoutMillis, BooleanSupplier cancelled,
            LongSupplier playedFrames, Runnable sample, Clock clock) throws Exception {
        drain(framesWritten, timeoutMillis, cancelled, playedFrames, sample, clock, () -> { });
    }

    interface Waiter { void awaitReady() throws Exception; }

    static void drain(long framesWritten, long timeoutMillis, BooleanSupplier cancelled,
            LongSupplier playedFrames, Runnable sample, Clock clock, Waiter waiter) throws Exception {
        long deadline = clock.now() + timeoutMillis;
        while (!cancelled.getAsBoolean() && playedFrames.getAsLong() < framesWritten) {
            long beforeWait = clock.now();
            waiter.awaitReady();
            deadline += clock.now() - beforeWait;
            if (cancelled.getAsBoolean()) return;
            sample.run();
            if (clock.now() >= deadline) {
                throw new IllegalStateException("AudioTrack drain timeout");
            }
            clock.sleep(10);
        }
    }
}

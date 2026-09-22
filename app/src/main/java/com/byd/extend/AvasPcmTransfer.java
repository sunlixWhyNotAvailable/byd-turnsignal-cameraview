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
        int write(int offset, int length) throws Exception;
        void written(int offset, int count);
        void idle();
    }

    private AvasPcmTransfer() { }

    static int write(int length, int frameSize, BooleanSupplier cancelled,
            Output output, Clock clock) throws Exception {
        int offset = 0;
        long lastProgress = clock.now();
        while (offset < length && !cancelled.getAsBoolean()) {
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
        long deadline = clock.now() + timeoutMillis;
        while (!cancelled.getAsBoolean() && playedFrames.getAsLong() < framesWritten) {
            sample.run();
            if (clock.now() >= deadline) {
                throw new IllegalStateException("AudioTrack drain timeout");
            }
            clock.sleep(10);
        }
    }
}

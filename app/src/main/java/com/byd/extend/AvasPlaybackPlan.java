package com.byd.extend;

import java.util.function.BooleanSupplier;

/** Pure per-request PCM timing rules shared by playback and deterministic JVM tests. */
final class AvasPlaybackPlan {
    interface FrameWriter { long write(long frames) throws Exception; }
    static final int AUTOMATIC_POWER_ON_SILENCE_MILLIS = 500;
    static final int NAVIGATION_SILENCE_MILLIS = 420;

    private AvasPlaybackPlan() {}

    static int silenceMillis(AvasPlaybackQueue.Kind kind, String profile) {
        if (kind == AvasPlaybackQueue.Kind.AUDITION_NAV) return NAVIGATION_SILENCE_MILLIS;
        return kind == AvasPlaybackQueue.Kind.AUTOMATIC_EXTERIOR && "power_on".equals(profile)
                ? AUTOMATIC_POWER_ON_SILENCE_MILLIS : 0;
    }

    static long silenceFrames(int sampleRate, int millis) {
        if (sampleRate <= 0 || millis < 0) throw new IllegalArgumentException("invalid PCM timing");
        return (long) sampleRate * millis / 1000;
    }

    static long silenceBytes(int sampleRate, int frameSize, int millis) {
        if (frameSize <= 0) throw new IllegalArgumentException("invalid PCM frame size");
        return silenceFrames(sampleRate, millis) * frameSize;
    }

    static boolean maySubmitFile(long plannedSilenceFrames, long writtenSilenceFrames,
            boolean cancelled) {
        return !cancelled && writtenSilenceFrames == plannedSilenceFrames;
    }

    static byte[] zeroPcm(int alignedBytes) {
        if (alignedBytes < 0) throw new IllegalArgumentException("invalid PCM byte count");
        return new byte[alignedBytes];
    }

    static long writeSilence(long plannedFrames, int maximumChunkFrames,
            BooleanSupplier cancelled, FrameWriter writer) throws Exception {
        if (plannedFrames < 0 || maximumChunkFrames <= 0) {
            throw new IllegalArgumentException("invalid silence write plan");
        }
        long written = 0;
        while (written < plannedFrames && !cancelled.getAsBoolean()) {
            long wanted = Math.min(maximumChunkFrames, plannedFrames - written);
            long count = writer.write(wanted);
            if (count < 0 || count > wanted) throw new IllegalStateException("invalid frame write");
            written += count;
            if (count < wanted) break;
        }
        return written;
    }
}

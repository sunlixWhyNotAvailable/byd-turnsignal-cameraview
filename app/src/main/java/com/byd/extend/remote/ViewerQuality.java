package com.byd.extend.remote;

/** Compression quality for the single, fixed-size live encoder. Inputs use monotonic milliseconds. */
public final class ViewerQuality {
    public static final String PREFERENCE_KEY = "cameras_viewing_quality";
    public static final int AUTO = 0, LOW = 1, MEDIUM = 2, HIGH = 3;
    private static final int[] BITRATES = {500_000, 500_000, 1_000_000, 2_000_000};
    private int mode, level;
    private long previousSample = -1, previousReceived = -1, healthySince = -1;
    private long minimumClockOffset = Long.MAX_VALUE;
    private int pressureSamples;

    public ViewerQuality(int mode) { reset(mode); }
    public static void validate(int mode) {
        if (mode < AUTO || mode > HIGH) throw new IllegalArgumentException("invalid_viewer_quality");
    }
    public static int bitrate(int mode) { validate(mode); return BITRATES[mode]; }
    public int mode() { return mode; }
    public int target() { return BITRATES[level]; }
    public void reset(int value) {
        validate(value); mode = value; level = value == AUTO ? LOW : value;
        previousSample = previousReceived = healthySince = -1; pressureSamples = 0;
        minimumClockOffset = Long.MAX_VALUE;
    }

    /** Return a target only from fresh, advancing feedback; unknown throughput is -1. */
    public int sample(long receivedMs, long sampleMs, boolean stalled, boolean advancing,
            long bufferMs, long lagMs, boolean downloadPressure, long throughputBps) {
        if (mode != AUTO || sampleMs <= previousSample || receivedMs < previousReceived) return target();
        long clockOffset = receivedMs - sampleMs;
        minimumClockOffset = Math.min(minimumClockOffset, clockOffset);
        if (clockOffset - minimumClockOffset > 5_000) return target();
        if (previousReceived >= 0 && receivedMs - previousReceived < 1_500) return target();
        if (previousReceived < 0 || receivedMs - previousReceived > 5_000
                || previousSample >= 0 && sampleMs - previousSample > 5_000) {
            healthySince = -1; pressureSamples = 0;
        }
        previousSample = sampleMs; previousReceived = receivedMs;
        boolean pressure = downloadPressure || lagMs > 4_000 || !advancing || bufferMs < 250;
        pressureSamples = pressure ? pressureSamples + 1 : 0;
        if (stalled || pressureSamples >= 2) {
            level = Math.max(LOW, level - 1); healthySince = -1; pressureSamples = 0;
        } else if (!pressure && bufferMs >= 500 && lagMs >= 0 && lagMs <= 3_000) {
            if (healthySince < 0) healthySince = receivedMs;
            if (level < HIGH && receivedMs - healthySince >= 10_000
                    && (throughputBps < 0 || throughputBps >= BITRATES[level + 1] * 1.5)) {
                level++; healthySince = receivedMs;
            }
        } else healthySince = -1;
        return target();
    }
}

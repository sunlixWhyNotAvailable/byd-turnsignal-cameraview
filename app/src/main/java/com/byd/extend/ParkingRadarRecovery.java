package com.byd.extend;

/** Retry only a previously functioning family; registration alone is not recovery. */
final class ParkingRadarRecovery {
    private static final long[] DELAYS_MS = {1_000, 2_000, 5_000};
    private boolean active, receivedData;
    private long generation, dueAt = -1;
    private int attempts;

    void start() { active = true; receivedData = false; attempts = 0; dueAt = -1; }
    void registering(long generation) { this.generation = generation; dueAt = -1; }
    boolean data(long generation, int fid, int value) {
        if (!active || this.generation != generation || value < 0
                || value > (ParkingRadarState.family(fid) == 1 ? 251 : 150)) return false;
        boolean recovered = attempts != 0;
        receivedData = true;
        attempts = 0;
        dueAt = -1;
        return recovered;
    }
    long failed(long generation, boolean permanent, long now) {
        if (!active || this.generation != generation || !receivedData || permanent
                || attempts == DELAYS_MS.length) return -1;
        long delay = DELAYS_MS[attempts++];
        dueAt = now + delay;
        return delay;
    }
    boolean take(long generation, long now) {
        if (!active || this.generation != generation || dueAt < 0 || now < dueAt) return false;
        dueAt = -1;
        return true;
    }
    int attempts() { return attempts; }
    void stop() { active = false; dueAt = -1; }

    static boolean permanent(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SecurityException || cause instanceof LinkageError
                    || cause instanceof ClassNotFoundException || cause instanceof NoSuchMethodException
                    || cause instanceof NoSuchFieldException || cause instanceof IllegalAccessException
                    || cause instanceof IllegalArgumentException || cause instanceof ClassCastException)
                return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }
}

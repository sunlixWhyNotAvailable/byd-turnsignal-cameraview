package com.byd.extend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONException;
import org.json.JSONObject;

/** Pure shared charge decision and durable low-cause latch used by the shell worker. */
public final class CameraChargePolicy {
    public static final int CAUSE_SOC = 1;
    public static final int CAUSE_VOLTAGE = 1 << 1;
    public static final int MISSING_SOC = 1;
    public static final int MISSING_VOLTAGE = 1 << 1;

    private static final String LATCH_FILE_NAME = "camera-charge.json";
    private static final int BOTH_CAUSES = CAUSE_SOC | CAUSE_VOLTAGE;

    private final File root;
    private final File latchFile;
    private int socThresholdPercent;
    private int voltageThresholdTenths;
    private Integer socPercent;
    private Float voltageVolts;
    private String socOrigin = "unknown";
    private String voltageOrigin = "unknown";
    private long socSampleElapsedMs = -1L;
    private long voltageSampleElapsedMs = -1L;
    private int lowCauses;
    private boolean monitoring;
    private boolean latchPersistenceHealthy = true;

    public CameraChargePolicy(File root, int socThresholdPercent, int voltageThresholdTenths)
            throws IOException {
        if (root == null || !CameraChargeSettings.isValidSocThreshold(socThresholdPercent)
                || voltageThresholdTenths < CameraChargeSettings.MIN_VOLTAGE_THRESHOLD_TENTHS
                || voltageThresholdTenths > CameraChargeSettings.MAX_VOLTAGE_THRESHOLD_TENTHS)
            throw new IllegalArgumentException("invalid camera charge policy configuration");
        this.root = root;
        this.latchFile = new File(root, LATCH_FILE_NAME);
        this.socThresholdPercent = socThresholdPercent;
        this.voltageThresholdTenths = voltageThresholdTenths;
        try { lowCauses = readLatch(latchFile); }
        catch (IOException damaged) {
            lowCauses = BOTH_CAUSES;
            latchPersistenceHealthy = false;
        }
    }

    public synchronized void setThresholds(int socThresholdPercent, int voltageThresholdTenths) {
        if (!CameraChargeSettings.isValidSocThreshold(socThresholdPercent)
                || voltageThresholdTenths < CameraChargeSettings.MIN_VOLTAGE_THRESHOLD_TENTHS
                || voltageThresholdTenths > CameraChargeSettings.MAX_VOLTAGE_THRESHOLD_TENTHS)
            throw new IllegalArgumentException("invalid camera charge thresholds");
        this.socThresholdPercent = socThresholdPercent;
        this.voltageThresholdTenths = voltageThresholdTenths;
    }

    public synchronized void setMonitoring(boolean enabled) {
        monitoring = enabled;
        if (!enabled) {
            socPercent = null;
            voltageVolts = null;
        }
    }

    /** A null, out-of-range, or stale value marks this metric unavailable without clearing its latch. */
    public synchronized State updateSoc(Integer value) {
        return updateSoc(value, "unknown", -1L);
    }

    synchronized State updateSoc(Integer value, String origin, long sampleElapsedMs) {
        socOrigin = validOrigin(origin);
        socSampleElapsedMs = sampleElapsedMs >= 0L ? sampleElapsedMs : -1L;
        if (value == null || value < 0 || value > 100) {
            socPercent = null;
            return state();
        }
        socPercent = value;
        if (value <= socThresholdPercent) setCause(CAUSE_SOC, true);
        else setCause(CAUSE_SOC, false);
        return state();
    }

    /** Zero, non-finite and implausibly high voltages are unavailable, not low readings. */
    public synchronized State updateVoltage(Float value) {
        return updateVoltage(value, "unknown", -1L);
    }

    synchronized State updateVoltage(Float value, String origin, long sampleElapsedMs) {
        voltageOrigin = validOrigin(origin);
        voltageSampleElapsedMs = sampleElapsedMs >= 0L ? sampleElapsedMs : -1L;
        if (value == null || !Float.isFinite(value) || value <= 0.0f || value > 20.0f) {
            voltageVolts = null;
            return state();
        }
        voltageVolts = value;
        if (value * 10.0f <= voltageThresholdTenths) setCause(CAUSE_VOLTAGE, true);
        else setCause(CAUSE_VOLTAGE, false);
        return state();
    }

    public synchronized State markUnavailable(int missingMask) {
        if ((missingMask & MISSING_SOC) != 0) socPercent = null;
        if ((missingMask & MISSING_VOLTAGE) != 0) voltageVolts = null;
        return state();
    }

    public synchronized State snapshot() { return state(); }

    private State state() {
        int missing = (socPercent == null ? MISSING_SOC : 0)
                | (voltageVolts == null ? MISSING_VOLTAGE : 0);
        return new State(socPercent, voltageVolts, missing, lowCauses, monitoring,
                latchPersistenceHealthy, socThresholdPercent, voltageThresholdTenths,
                socOrigin, voltageOrigin, socSampleElapsedMs, voltageSampleElapsedMs);
    }

    private static String validOrigin(String origin) {
        return "callback".equals(origin) || "get".equals(origin) ? origin : "unknown";
    }

    private void setCause(int cause, boolean low) {
        int next = low ? lowCauses | cause : lowCauses & ~cause;
        if (next == lowCauses) return;
        lowCauses = next;
        try {
            writeLatch(latchFile, lowCauses);
            latchPersistenceHealthy = true;
        } catch (IOException failure) {
            latchPersistenceHealthy = false;
            // Keep a confirmed low latched in memory; never roll it back to an allow state.
            System.err.println("camera_charge_latch_write_failed " + failure.getMessage());
        }
    }

    private static int readLatch(File file) throws IOException {
        if (!file.exists()) return 0;
        if (file.length() < 1 || file.length() > 128)
            throw new IOException("camera charge latch size is invalid");
        try {
            JSONObject envelope = new JSONObject(new String(
                    Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            int causes = envelope.getInt("low_causes");
            if (envelope.getInt("version") != 1 || (causes & ~BOTH_CAUSES) != 0)
                throw new IOException("camera charge latch fields are invalid");
            return causes;
        } catch (JSONException | RuntimeException malformed) {
            throw new IOException("camera charge latch is malformed", malformed);
        }
    }

    private void writeLatch(File file, int causes) throws IOException {
        if (!root.isDirectory() && !root.mkdirs() && !root.isDirectory())
            throw new IOException("camera charge state directory unavailable");
        File temp = new File(root, LATCH_FILE_NAME + ".tmp");
        final byte[] bytes;
        try {
            bytes = new JSONObject().put("version", 1).put("low_causes", causes)
                    .toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException impossible) {
            throw new IOException("could not serialize camera charge latch", impossible);
        }
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException failure) {
            temp.delete();
            throw new IOException("could not atomically save camera charge latch", failure);
        }
    }

    public static final class State {
        private final Integer socPercent;
        private final Float voltageVolts;
        private final int missingMetrics;
        private final int lowCauses;
        private final boolean monitoring;
        private final boolean latchPersistenceHealthy;
        private final int socThresholdPercent;
        private final int voltageThresholdTenths;
        private final String socOrigin;
        private final String voltageOrigin;
        private final long socSampleElapsedMs;
        private final long voltageSampleElapsedMs;

        State(Integer socPercent, Float voltageVolts, int missingMetrics,
                int lowCauses, boolean monitoring, boolean latchPersistenceHealthy) {
            this(socPercent, voltageVolts, missingMetrics, lowCauses, monitoring,
                    latchPersistenceHealthy, -1, -1, "unknown", "unknown", -1L, -1L);
        }

        State(Integer socPercent, Float voltageVolts, int missingMetrics,
                int lowCauses, boolean monitoring, boolean latchPersistenceHealthy,
                int socThresholdPercent, int voltageThresholdTenths, String socOrigin,
                String voltageOrigin, long socSampleElapsedMs, long voltageSampleElapsedMs) {
            this.socPercent = socPercent;
            this.voltageVolts = voltageVolts;
            this.missingMetrics = missingMetrics;
            this.lowCauses = lowCauses;
            this.monitoring = monitoring;
            this.latchPersistenceHealthy = latchPersistenceHealthy;
            this.socThresholdPercent = socThresholdPercent;
            this.voltageThresholdTenths = voltageThresholdTenths;
            this.socOrigin = validOrigin(socOrigin);
            this.voltageOrigin = validOrigin(voltageOrigin);
            this.socSampleElapsedMs = socSampleElapsedMs >= 0L ? socSampleElapsedMs : -1L;
            this.voltageSampleElapsedMs = voltageSampleElapsedMs >= 0L ? voltageSampleElapsedMs : -1L;
        }

        public Integer socPercent() { return socPercent; }
        public Float voltageVolts() { return voltageVolts; }
        public int missingMetrics() { return missingMetrics; }
        public boolean isSocUnavailable() { return (missingMetrics & MISSING_SOC) != 0; }
        public boolean isVoltageUnavailable() { return (missingMetrics & MISSING_VOLTAGE) != 0; }
        public int lowCauses() { return lowCauses; }
        public int socThresholdPercent() { return socThresholdPercent; }
        public int voltageThresholdTenths() { return voltageThresholdTenths; }
        public String socOrigin() { return socOrigin; }
        public String voltageOrigin() { return voltageOrigin; }
        public long socSampleElapsedMs() { return socSampleElapsedMs; }
        public long voltageSampleElapsedMs() { return voltageSampleElapsedMs; }
        public boolean isBlocked() { return lowCauses != 0; }
        public boolean isMonitoring() { return monitoring; }
        public boolean isLatchPersistenceHealthy() { return latchPersistenceHealthy; }
        public boolean shouldWarnUnavailable() { return monitoring && !isBlocked() && missingMetrics != 0; }

        @Override public boolean equals(Object other) {
            if (!(other instanceof State)) return false;
            State state = (State) other;
            return equal(socPercent, state.socPercent) && equal(voltageVolts, state.voltageVolts)
                    && missingMetrics == state.missingMetrics && lowCauses == state.lowCauses
                    && monitoring == state.monitoring
                    && latchPersistenceHealthy == state.latchPersistenceHealthy;
        }

        @Override public int hashCode() {
            int result = socPercent == null ? 0 : socPercent.hashCode();
            result = 31 * result + (voltageVolts == null ? 0 : Float.floatToIntBits(voltageVolts));
            result = 31 * result + missingMetrics;
            result = 31 * result + lowCauses;
            result = 31 * result + (monitoring ? 1 : 0);
            return 31 * result + (latchPersistenceHealthy ? 1 : 0);
        }

        private static boolean equal(Object first, Object second) {
            return first == null ? second == null : first.equals(second);
        }
    }
}

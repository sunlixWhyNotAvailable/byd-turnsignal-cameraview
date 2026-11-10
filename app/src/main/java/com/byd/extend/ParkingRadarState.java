package com.byd.extend;

import java.util.Arrays;

/** Latest OEM state per FID. Callback silence is not a new measurement or a failure. */
final class ParkingRadarState {
    static final int[] FIDS = ParkingCameraProfile.allRadarFids();
    private final int[] raw = new int[FIDS.length];
    private final boolean[] present = new boolean[FIDS.length];
    private final long[] received = new long[FIDS.length], revisions = new long[FIDS.length];
    private final long[] generations = new long[2];
    private final boolean[] accepting = new boolean[2], healthy = new boolean[2];
    private final String[] status = {"inactive", "inactive"};

    synchronized void begin(int family, long generation) {
        invalidate(family, generation, "initializing");
        accepting[family] = true;
    }

    synchronized void registered(int family, long generation) {
        if (generations[family] == generation && accepting[family]) {
            healthy[family] = true;
            status[family] = "ready";
        }
    }

    synchronized void invalidate(int family, long generation, String reason) {
        generations[family] = generation;
        accepting[family] = healthy[family] = false;
        status[family] = reason;
        for (int i = 0; i < FIDS.length; i++) if (family(FIDS[i]) == family) {
            raw[i] = -1; present[i] = false; received[i] = 0; revisions[i]++;
        }
    }

    synchronized long revision(int fid) { return revisions[index(fid)]; }
    synchronized boolean fail(int family, long generation, String reason) {
        if (generations[family] != generation || !accepting[family]) return false;
        invalidate(family, generation, reason);
        return true;
    }

    synchronized boolean callback(int fid, int value, long at, long generation) {
        return put(fid, value, at, generation, -1);
    }

    synchronized boolean initialRead(int fid, int value, long at, long generation, long beforeRead) {
        return put(fid, value, at, generation, beforeRead);
    }

    private boolean put(int fid, int value, long at, long generation, long beforeRead) {
        int i = index(fid), family = family(fid);
        if (!accepting[family] || generations[family] != generation ||
                beforeRead >= 0 && revisions[i] != beforeRead) return false;
        raw[i] = value; present[i] = true; received[i] = at; revisions[i]++;
        return true;
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(raw, present, received, generations, healthy, status);
    }

    static int family(int fid) { return ParkingCameraProfile.isSideRadarFid(fid) ? 1 : 0; }
    private static int index(int fid) {
        for (int i = 0; i < FIDS.length; i++) if (FIDS[i] == fid) return i;
        throw new IllegalArgumentException("Unknown parking FID: " + fid);
    }

    static boolean measurement(int fid, int value) {
        return value >= 0 && value < (family(fid) == 1 ? 251 : 150);
    }

    static final class Snapshot {
        private final int[] raw;
        private final boolean[] present, healthy;
        private final long[] received, generations;
        private final String[] status;
        private Snapshot(int[] raw, boolean[] present, long[] received, long[] generations,
                boolean[] healthy, String[] status) {
            this.raw = raw.clone(); this.present = present.clone(); this.received = received.clone();
            this.generations = generations.clone(); this.healthy = healthy.clone(); this.status = status.clone();
        }
        int[] raw() { return raw.clone(); }
        boolean[] present() { return present.clone(); }
        long[] received() { return received.clone(); }
        long generation(int family) { return generations[family]; }
        String status(int family) { return status[family]; }
        boolean available() {
            for (int i = 0; i < FIDS.length; i++) if (healthy[family(FIDS[i])] && present[i] &&
                    raw[i] >= 0 && raw[i] <= (family(FIDS[i]) == 1 ? 251 : 150)) return true;
            return false;
        }
        double[] distances() {
            double[] result = new double[8]; Arrays.fill(result, Double.NaN);
            for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
                // Parking's legacy order differs from the clockwise ADAS order.
                int sector = switch (profile.id) {
                    case ParkingCameraProfile.FL -> 7;
                    case ParkingCameraProfile.FRONT -> 0;
                    case ParkingCameraProfile.FR -> 1;
                    case ParkingCameraProfile.RR -> 3;
                    case ParkingCameraProfile.REAR -> 4;
                    case ParkingCameraProfile.RL -> 5;
                    case ParkingCameraProfile.LEFT -> 6;
                    case ParkingCameraProfile.RIGHT -> 2;
                    default -> throw new IllegalArgumentException("Unknown parking sector");
                };
                for (int fid : profile.radarFids()) {
                    int i = index(fid);
                    if (healthy[family(fid)] && present[i] && measurement(fid, raw[i]) &&
                            (Double.isNaN(result[sector]) || raw[i] < result[sector])) result[sector] = raw[i];
                }
            }
            return result;
        }
    }
}

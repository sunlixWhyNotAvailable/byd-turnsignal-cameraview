package com.byd.turnsignalguard.capture;

import java.util.Arrays;

/**
 * The six parking-camera views.  The order and the hardware mappings are part
 * of the shell/UI contract and must not be changed independently.
 */
public final class ParkingCameraProfile {
    public static final int FL = 0;
    public static final int FRONT = 1;
    public static final int FR = 2;
    public static final int RR = 3;
    public static final int REAR = 4;
    public static final int RL = 5;
    public static final int COUNT = 6;

    public static final int RADAR_FID_FL = -1728053151;
    public static final int RADAR_FID_FRONT_LEFT = -1728053150;
    public static final int RADAR_FID_FRONT_RIGHT = -1728053149;
    public static final int RADAR_FID_FR = -1728053148;
    public static final int RADAR_FID_RR = -1728053144;
    public static final int RADAR_FID_REAR_LEFT = -1728053146;
    public static final int RADAR_FID_REAR_RIGHT = -1728053145;
    public static final int RADAR_FID_RL = -1728053147;

    public static final int RADAR_RAW_MIN = 0;
    public static final int RADAR_RAW_MAX = 155;

    private static final ParkingCameraProfile[] VALUES = {
            new ParkingCameraProfile(FL, "FL", new int[]{RADAR_FID_FL},
                    2, "left", FRONT),
            new ParkingCameraProfile(FRONT, "Front",
                    new int[]{RADAR_FID_FRONT_LEFT, RADAR_FID_FRONT_RIGHT},
                    4, "front", -1),
            new ParkingCameraProfile(FR, "FR", new int[]{RADAR_FID_FR},
                    3, "right", FRONT),
            new ParkingCameraProfile(RR, "RR", new int[]{RADAR_FID_RR},
                    3, "right", REAR),
            new ParkingCameraProfile(REAR, "Rear",
                    new int[]{RADAR_FID_REAR_LEFT, RADAR_FID_REAR_RIGHT},
                    1, "rear", -1),
            new ParkingCameraProfile(RL, "RL", new int[]{RADAR_FID_RL},
                    2, "left", REAR)
    };

    public final int id;
    public final String wireName;
    public final int physicalCameraIndex;
    public final String lens;

    private final int[] radarFids;
    private final int additiveCentralId;

    private ParkingCameraProfile(
            int id, String wireName, int[] radarFids,
            int physicalCameraIndex, String lens, int additiveCentralId) {
        this.id = id;
        this.wireName = wireName;
        this.radarFids = radarFids;
        this.physicalCameraIndex = physicalCameraIndex;
        this.lens = lens;
        this.additiveCentralId = additiveCentralId;
    }

    public static ParkingCameraProfile of(int id) {
        if (!isValid(id)) throw new IllegalArgumentException("invalid parking camera id: " + id);
        return VALUES[id];
    }

    public static boolean isValid(int id) {
        return id >= 0 && id < COUNT;
    }

    public static ParkingCameraProfile[] values() {
        return VALUES.clone();
    }

    public boolean central() {
        return id == FRONT || id == REAR;
    }

    public boolean corner() {
        return !central();
    }

    public boolean rear() {
        return id == RR || id == REAR || id == RL;
    }

    public int bit() {
        return 1 << id;
    }

    /** Returns the radar feature IDs owned by this view. */
    public int[] radarFids() {
        return radarFids.clone();
    }

    /** The central view optionally added by this corner, or {@code -1}. */
    public int additiveCentralId() {
        return additiveCentralId;
    }

    public static int[] allRadarFids() {
        int[] result = new int[8];
        int offset = 0;
        for (ParkingCameraProfile profile : VALUES) {
            for (int fid : profile.radarFids) result[offset++] = fid;
        }
        return result;
    }

    public static boolean isValidRadarRaw(int raw) {
        return raw >= RADAR_RAW_MIN && raw <= RADAR_RAW_MAX;
    }

    @Override
    public String toString() {
        return "ParkingCameraProfile{" + wireName + ", radar="
                + Arrays.toString(radarFids) + '}';
    }
}

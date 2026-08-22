package com.byd.turnsignalguard.capture;

import java.util.Arrays;

/**
 * The eight parking-camera views.  The order and the hardware mappings are part
 * of the shell/UI contract and must not be changed independently.
 */
public final class ParkingCameraProfile {
    public static final int FL = 0;
    public static final int FRONT = 1;
    public static final int FR = 2;
    public static final int RR = 3;
    public static final int REAR = 4;
    public static final int RL = 5;
    public static final int LEFT = 6;
    public static final int RIGHT = 7;
    public static final int COUNT = 8;

    public static final int RADAR_FID_FL = -1728053151;
    public static final int RADAR_FID_FRONT_LEFT = -1728053150;
    public static final int RADAR_FID_FRONT_RIGHT = -1728053149;
    public static final int RADAR_FID_FR = -1728053148;
    public static final int RADAR_FID_RR = -1728053144;
    public static final int RADAR_FID_REAR_LEFT = -1728053146;
    public static final int RADAR_FID_REAR_RIGHT = -1728053145;
    public static final int RADAR_FID_RL = -1728053147;

    public static final int RADAR_FID_LEFT_FRONT = 0x36500008;
    public static final int RADAR_FID_LEFT_MIDDLE = 0x36500018;
    public static final int RADAR_FID_LEFT_REAR = 0x36500028;
    public static final int RADAR_FID_LEFT_OUTER = 0x36500038;
    public static final int RADAR_FID_RIGHT_FRONT = 0x36500010;
    public static final int RADAR_FID_RIGHT_MIDDLE = 0x36500020;
    public static final int RADAR_FID_RIGHT_REAR = 0x36500030;
    public static final int RADAR_FID_RIGHT_OUTER = 0x36500040;

    public static final int RADAR_RAW_MIN = 0;
    public static final int RADAR_RAW_MAX = 155;
    public static final int SIDE_RADAR_RAW_MAX = 255;

    private static final int[] CORE_RADAR_FIDS = {
            RADAR_FID_FL, RADAR_FID_FRONT_LEFT, RADAR_FID_FRONT_RIGHT, RADAR_FID_FR,
            RADAR_FID_RR, RADAR_FID_REAR_LEFT, RADAR_FID_REAR_RIGHT, RADAR_FID_RL
    };
    private static final int[] SIDE_RADAR_FIDS = {
            RADAR_FID_LEFT_FRONT, RADAR_FID_LEFT_MIDDLE,
            RADAR_FID_LEFT_REAR, RADAR_FID_LEFT_OUTER,
            RADAR_FID_RIGHT_FRONT, RADAR_FID_RIGHT_MIDDLE,
            RADAR_FID_RIGHT_REAR, RADAR_FID_RIGHT_OUTER
    };

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
                    2, "left", REAR),
            new ParkingCameraProfile(LEFT, "Left", new int[]{
                    RADAR_FID_LEFT_FRONT, RADAR_FID_LEFT_MIDDLE,
                    RADAR_FID_LEFT_REAR, RADAR_FID_LEFT_OUTER},
                    2, "left", -1),
            new ParkingCameraProfile(RIGHT, "Right", new int[]{
                    RADAR_FID_RIGHT_FRONT, RADAR_FID_RIGHT_MIDDLE,
                    RADAR_FID_RIGHT_REAR, RADAR_FID_RIGHT_OUTER},
                    3, "right", -1)
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
        return id == FL || id == FR || id == RR || id == RL;
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
        int[] result = new int[CORE_RADAR_FIDS.length + SIDE_RADAR_FIDS.length];
        System.arraycopy(CORE_RADAR_FIDS, 0, result, 0, CORE_RADAR_FIDS.length);
        System.arraycopy(SIDE_RADAR_FIDS, 0, result, CORE_RADAR_FIDS.length,
                SIDE_RADAR_FIDS.length);
        return result;
    }

    /** Core 0x99000061..68 radar FIDs, in the stable legacy order. */
    public static int[] coreRadarFids() {
        return CORE_RADAR_FIDS.clone();
    }

    /** Side 0x365 FIDs, in left/right transport order. */
    public static int[] sideRadarFids() {
        return SIDE_RADAR_FIDS.clone();
    }

    /** Returns whether a raw value is transport-valid for the FID's source. */
    public static boolean isValidRadarRaw(int fid, int raw) {
        if (isSideRadarFid(fid)) {
            return raw >= RADAR_RAW_MIN && raw <= SIDE_RADAR_RAW_MAX;
        }
        if (!isCoreRadarFid(fid)) return false;
        int maximum = RADAR_RAW_MAX;
        return raw >= RADAR_RAW_MIN && raw <= maximum;
    }

    public static boolean isSideRadarFid(int fid) {
        for (int sideFid : SIDE_RADAR_FIDS) if (sideFid == fid) return true;
        return false;
    }

    public static boolean isCoreRadarFid(int fid) {
        for (int coreFid : CORE_RADAR_FIDS) if (coreFid == fid) return true;
        return false;
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

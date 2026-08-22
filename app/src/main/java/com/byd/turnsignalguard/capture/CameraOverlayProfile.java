package com.byd.turnsignalguard.capture;

/**
 * Wire identity for every camera overlay pane.
 *
 * <p>The legacy blind-spot identities remain 0..3.  Parking panes deliberately
 * live in a separate range (4..9) so a malformed or stale parking request can
 * never alias a blind-spot pane.</p>
 */
final class CameraOverlayProfile {
    static final int BLIND_COUNT = CameraProfile.COUNT;
    static final int PARKING_COUNT = ParkingCameraProfile.COUNT;
    static final int PARKING_OFFSET = BLIND_COUNT;
    static final int COUNT = BLIND_COUNT + PARKING_COUNT;

    private static final CameraOverlayProfile[] VALUES = createValues();

    final int id;
    final String wireName;
    final int lens;

    private CameraOverlayProfile(int id, String wireName, int lens) {
        this.id = id;
        this.wireName = wireName;
        this.lens = lens;
    }

    static CameraOverlayProfile of(int id) {
        if (!isValid(id)) throw new IllegalArgumentException("invalid overlay camera id: " + id);
        return VALUES[id];
    }

    static boolean isValid(int id) {
        return id >= 0 && id < COUNT;
    }

    static CameraOverlayProfile[] values() {
        return VALUES.clone();
    }

    static boolean isParking(int id) {
        return id >= PARKING_OFFSET && id < COUNT;
    }

    static int parkingIdFor(int id) {
        if (!isParking(id)) throw new IllegalArgumentException("not a parking overlay id");
        return id - PARKING_OFFSET;
    }

    static int overlayIdForParking(int parkingId) {
        if (parkingId < 0 || parkingId >= COUNT - PARKING_OFFSET) {
            throw new IllegalArgumentException("invalid parking camera id: " + parkingId);
        }
        return PARKING_OFFSET + parkingId;
    }

    boolean parking() {
        return isParking(id);
    }

    private static CameraOverlayProfile[] createValues() {
        CameraOverlayProfile[] values = new CameraOverlayProfile[COUNT];
        for (CameraProfile profile : CameraProfile.values()) {
            values[profile.id] = new CameraOverlayProfile(
                    profile.id, profile.wireName, CameraDewarpConfig.lensFor(profile));
        }
        // Parking order is part of the protocol: FL, Front, FR, RR, Rear, RL, Left, Right.
        for (int i = 0; i < PARKING_COUNT; i++) {
            int id = PARKING_OFFSET + i;
            ParkingCameraProfile parking = ParkingCameraProfile.of(i);
            values[id] = new CameraOverlayProfile(
                    id, parking.wireName, CameraDewarpConfig.lensFor(parking));
        }
        return values;
    }
}

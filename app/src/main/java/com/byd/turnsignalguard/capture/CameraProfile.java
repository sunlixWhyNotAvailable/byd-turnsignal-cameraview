package com.byd.turnsignalguard.capture;

final class CameraProfile {
    static final int REAR_LEFT = 0;
    static final int REAR_RIGHT = 1;
    static final int FRONT_LEFT = 2;
    static final int FRONT_RIGHT = 3;
    static final int COUNT = 4;

    static final int GROUP_REAR = 0;
    static final int GROUP_FRONT = 1;

    static final int SIDE_LEFT = 0;
    static final int SIDE_RIGHT = 1;

    private static final CameraProfile[] VALUES = {
            new CameraProfile(REAR_LEFT, "rear_left", GROUP_REAR, SIDE_LEFT, 2),
            new CameraProfile(REAR_RIGHT, "rear_right", GROUP_REAR, SIDE_RIGHT, 3),
            new CameraProfile(FRONT_LEFT, "front_left", GROUP_FRONT, SIDE_LEFT, 2),
            new CameraProfile(FRONT_RIGHT, "front_right", GROUP_FRONT, SIDE_RIGHT, 3)
    };

    final int id;
    final String wireName;
    final int group;
    final int side;
    final int previewIndex;

    private CameraProfile(int id, String wireName, int group, int side, int previewIndex) {
        this.id = id;
        this.wireName = wireName;
        this.group = group;
        this.side = side;
        this.previewIndex = previewIndex;
    }

    static CameraProfile of(int id) {
        if (!isValid(id)) throw new IllegalArgumentException("invalid camera id: " + id);
        return VALUES[id];
    }

    static boolean isValid(int id) {
        return id >= 0 && id < COUNT;
    }

    static CameraProfile[] values() {
        return VALUES.clone();
    }

    boolean rear() {
        return group == GROUP_REAR;
    }

    boolean front() {
        return group == GROUP_FRONT;
    }

    boolean right() {
        return side == SIDE_RIGHT;
    }

    int bit() {
        return 1 << id;
    }

    static int desiredMask(
            boolean valid, int blink, float speedKph, float steeringAngle,
            boolean rearEnabled, int rearMinSpeed, int rearMaxSpeed,
            boolean frontEnabled, int frontMinSpeed, int frontMaxSpeed,
            boolean frontTurnRequired, float frontMinAngle) {
        if (!valid || !Float.isFinite(speedKph)) return 0;
        int mask = 0;
        if (rearEnabled && speedAllowed(speedKph, rearMinSpeed, rearMaxSpeed)) {
            if (blink == 2) mask |= of(REAR_LEFT).bit();
            else if (blink == 4) mask |= of(REAR_RIGHT).bit();
        }
        if (!frontEnabled || !speedAllowed(speedKph, frontMinSpeed, frontMaxSpeed)
                || !Float.isFinite(steeringAngle)
                || !Float.isFinite(frontMinAngle)
                || frontMinAngle < 0.0f || frontMinAngle > 780.0f) return mask;
        boolean leftAngle = steeringAngle > 0.0f && steeringAngle >= frontMinAngle;
        boolean rightAngle = steeringAngle < 0.0f && steeringAngle <= -frontMinAngle;
        if (frontTurnRequired) {
            if (blink == 2 && leftAngle) mask |= of(FRONT_LEFT).bit();
            else if (blink == 4 && rightAngle) mask |= of(FRONT_RIGHT).bit();
        } else {
            if (leftAngle) mask |= of(FRONT_LEFT).bit();
            else if (rightAngle) mask |= of(FRONT_RIGHT).bit();
        }
        return mask;
    }

    private static boolean speedAllowed(float speedKph, int minimum, int maximum) {
        return minimum >= 0 && minimum <= maximum && maximum <= 300
                && speedKph >= minimum && speedKph <= maximum;
    }
}

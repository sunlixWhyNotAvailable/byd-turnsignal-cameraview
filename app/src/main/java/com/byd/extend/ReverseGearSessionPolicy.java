package com.byd.extend;

/**
 * Pure state machine for the optional Reverse gear-session behavior.
 *
 * <p>The gearbox listener is deliberately kept separate from the camera runtime.  This class
 * only interprets the four observed raw values and combines that state with the OEM pano
 * visibility edge.  It performs no vehicle I/O and does not infer a direction from a stale
 * callback.</p>
 */
final class ReverseGearSessionPolicy {
    static final int MODE_REAR = 0;
    static final int MODE_FRONT = 1;

    static final int RAW_PARK = 1;
    static final int RAW_REVERSE = 2;
    static final int RAW_NEUTRAL = 3;
    static final int RAW_DRIVE = 4;

    enum Gear {
        UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE
    }

    static final class State {
        boolean eligible;
        boolean panoVisible;
        boolean gearValid;
        int raw = -1;
        Gear gear = Gear.UNKNOWN;
    }

    static final class Decision {
        final boolean eligible;
        final boolean started;
        final boolean stopped;
        /** MODE_* when a direction edge should be applied; -1 otherwise. */
        final int targetMode;
        final Gear gear;
        final boolean gearEdge;

        Decision(
                boolean eligible, boolean started, boolean stopped,
                int targetMode, Gear gear, boolean gearEdge) {
            this.eligible = eligible;
            this.started = started;
            this.stopped = stopped;
            this.targetMode = targetMode;
            this.gear = gear;
            this.gearEdge = gearEdge;
        }

        boolean hasTarget() {
            return targetMode == MODE_REAR || targetMode == MODE_FRONT;
        }
    }

    private ReverseGearSessionPolicy() {}

    static Gear gearForRaw(int raw) {
        switch (raw) {
            case RAW_PARK:
                return Gear.PARK;
            case RAW_REVERSE:
                return Gear.REVERSE;
            case RAW_NEUTRAL:
                return Gear.NEUTRAL;
            case RAW_DRIVE:
                return Gear.DRIVE;
            default:
                return Gear.UNKNOWN;
        }
    }

    static boolean isValidRaw(int raw) {
        return gearForRaw(raw) != Gear.UNKNOWN;
    }

    static boolean isReverseRaw(int raw) {
        return raw == RAW_REVERSE;
    }

    static boolean sessionEligible(
            boolean switchByGear, boolean gearValid, int raw, boolean panoVisible) {
        return switchByGear
                ? panoVisible || (gearValid && isReverseRaw(raw))
                : gearValid && isReverseRaw(raw);
    }

    static int coldMode(Gear gear) {
        if (gear == Gear.REVERSE) return MODE_REAR;
        if (gear == Gear.PARK || gear == Gear.NEUTRAL || gear == Gear.DRIVE) {
            return MODE_FRONT;
        }
        return -1;
    }

    static int modeForGearEdge(Gear gear) {
        if (gear == Gear.REVERSE) return MODE_REAR;
        if (gear == Gear.DRIVE) return MODE_FRONT;
        return -1;
    }

    /**
     * Advances the state from one complete source snapshot.  D/R changes are edge-triggered;
     * repeated callbacks and pano visibility changes never select a camera in an open session.
     */
    static Decision update(
            State state, boolean switchByGear,
            boolean gearValid, int raw, boolean panoVisible) {
        if (state == null) throw new IllegalArgumentException("state is required");
        boolean previousEligible = state.eligible;
        Gear previousGear = state.gear;
        Gear nextGear = gearValid ? gearForRaw(raw) : Gear.UNKNOWN;
        boolean nextEligible = sessionEligible(switchByGear, gearValid, raw, panoVisible);
        boolean gearEdge = previousGear != nextGear
                && (nextGear == Gear.DRIVE || nextGear == Gear.REVERSE);

        state.eligible = nextEligible;
        state.panoVisible = panoVisible;
        state.gearValid = gearValid && nextGear != Gear.UNKNOWN;
        state.raw = state.gearValid ? raw : -1;
        state.gear = nextGear;

        boolean started = !previousEligible && nextEligible;
        boolean stopped = previousEligible && !nextEligible;
        int target = -1;
        if (started && switchByGear) {
            target = coldMode(nextGear);
        } else if (nextEligible && switchByGear && gearEdge) {
            target = modeForGearEdge(nextGear);
        }
        return new Decision(nextEligible, started, stopped, target, nextGear, gearEdge);
    }
}

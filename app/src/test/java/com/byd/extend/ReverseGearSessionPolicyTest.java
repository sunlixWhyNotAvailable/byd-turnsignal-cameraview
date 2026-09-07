package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ReverseGearSessionPolicyTest {
    @Test
    public void observedRawGearMappingIsBounded() {
        assertEquals(ReverseGearSessionPolicy.Gear.PARK,
                ReverseGearSessionPolicy.gearForRaw(1));
        assertEquals(ReverseGearSessionPolicy.Gear.REVERSE,
                ReverseGearSessionPolicy.gearForRaw(2));
        assertEquals(ReverseGearSessionPolicy.Gear.NEUTRAL,
                ReverseGearSessionPolicy.gearForRaw(3));
        assertEquals(ReverseGearSessionPolicy.Gear.DRIVE,
                ReverseGearSessionPolicy.gearForRaw(4));
        assertEquals(ReverseGearSessionPolicy.Gear.UNKNOWN,
                ReverseGearSessionPolicy.gearForRaw(5));
        assertFalse(ReverseGearSessionPolicy.isValidRaw(0));
        assertFalse(ReverseGearSessionPolicy.isValidRaw(5));
        assertFalse(ReverseGearSessionPolicy.isReverseRaw(4));
        assertTrue(ReverseGearSessionPolicy.isReverseRaw(2));
    }

    @Test
    public void offKeepsExistingReverseOnlyEligibility() {
        assertTrue(ReverseGearSessionPolicy.sessionEligible(false, true, 2, false));
        assertFalse(ReverseGearSessionPolicy.sessionEligible(false, true, 4, true));
        assertFalse(ReverseGearSessionPolicy.sessionEligible(false, false, 2, true));
        ReverseGearSessionPolicy.Decision decision = ReverseGearSessionPolicy.update(
                new ReverseGearSessionPolicy.State(), false, true, 2, false);
        assertFalse(decision.hasTarget());
    }

    @Test
    public void panoOpensSessionAndColdDirectionUsesCurrentGear() {
        ReverseGearSessionPolicy.State state = new ReverseGearSessionPolicy.State();
        ReverseGearSessionPolicy.Decision d = ReverseGearSessionPolicy.update(
                state, true, true, 4, true);
        assertTrue(d.started);
        assertEquals(ReverseGearSessionPolicy.MODE_FRONT, d.targetMode);

        state = new ReverseGearSessionPolicy.State();
        d = ReverseGearSessionPolicy.update(state, true, true, 2, true);
        assertTrue(d.started);
        assertEquals(ReverseGearSessionPolicy.MODE_REAR, d.targetMode);

        state = new ReverseGearSessionPolicy.State();
        d = ReverseGearSessionPolicy.update(state, true, false, -1, true);
        assertTrue(d.started);
        assertFalse(d.hasTarget());
    }

    @Test
    public void onlyActualDriveAndReverseEdgesSelectInsideSession() {
        ReverseGearSessionPolicy.State state = new ReverseGearSessionPolicy.State();
        ReverseGearSessionPolicy.Decision d = ReverseGearSessionPolicy.update(
                state, true, true, 3, true);
        assertTrue(d.started);
        assertEquals(ReverseGearSessionPolicy.MODE_FRONT, d.targetMode);

        d = ReverseGearSessionPolicy.update(state, true, true, 3, true);
        assertFalse(d.gearEdge);
        assertFalse(d.hasTarget());

        d = ReverseGearSessionPolicy.update(state, true, true, 2, true);
        assertTrue(d.gearEdge);
        assertEquals(ReverseGearSessionPolicy.MODE_REAR, d.targetMode);

        d = ReverseGearSessionPolicy.update(state, true, true, 2, true);
        assertFalse(d.gearEdge);
        assertFalse(d.hasTarget());

        d = ReverseGearSessionPolicy.update(state, true, true, 1, true);
        assertFalse(d.gearEdge);
        assertFalse(d.hasTarget());

        state = new ReverseGearSessionPolicy.State();
        d = ReverseGearSessionPolicy.update(state, true, true, 1, true);
        assertTrue(d.started);
        assertEquals(ReverseGearSessionPolicy.MODE_FRONT, d.targetMode);
    }

    @Test
    public void panoCloseWaitsForReverseSourceWhenGearSwitchEnabled() {
        ReverseGearSessionPolicy.State state = new ReverseGearSessionPolicy.State();
        ReverseGearSessionPolicy.Decision d = ReverseGearSessionPolicy.update(
                state, true, true, 4, true);
        assertTrue(d.eligible);
        d = ReverseGearSessionPolicy.update(state, true, true, 4, false);
        assertTrue(d.stopped);
        assertFalse(d.eligible);
    }

    @Test
    public void duplicatePanoEdgesNeverUndoManualModeSelection() {
        ReverseGearSessionPolicy.State state = new ReverseGearSessionPolicy.State();
        ReverseGearSessionPolicy.Decision d = ReverseGearSessionPolicy.update(
                state, true, true, 4, true);
        assertEquals(ReverseGearSessionPolicy.MODE_FRONT, d.targetMode);
        // The policy does not own the manually selected mode; repeated pano edges carry no
        // direction target and therefore cannot undo it.
        d = ReverseGearSessionPolicy.update(state, true, true, 4, true);
        assertFalse(d.hasTarget());
        d = ReverseGearSessionPolicy.update(state, true, true, 4, false);
        assertTrue(d.stopped);
    }
}

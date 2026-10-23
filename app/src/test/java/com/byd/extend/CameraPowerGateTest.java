package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraPowerGateTest {
    @Test public void startupUnknownAllowsAndConfirmedOffSurvivesFourFailedGets() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        assertEquals(0, gate.snapshot().revision);
        assertFalse(gate.observe(-1));
        assertEquals(0, gate.snapshot().revision);
        assertTrue(gate.ordinaryAllowed());
        assertTrue(gate.observe(0));
        assertEquals(1, gate.snapshot().revision);
        assertFalse(gate.ordinaryAllowed());
        assertFalse(gate.observe(0));
        assertEquals(1, gate.snapshot().revision);

        for (int failures = 1; failures <= 4; failures++) {
            assertTrue(gate.failedRead());
            assertFalse(gate.ordinaryAllowed());
            assertEquals(failures, gate.snapshot().consecutiveFailedReads);
            assertEquals(failures + 1, gate.snapshot().revision);
            assertTrue(gate.snapshot().known);
            assertTrue(gate.snapshot().off);
        }
    }

    @Test public void fifthFailedGetReleasesOffAndValidPowerResetsCount() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        gate.observe(0);
        for (int i = 0; i < 4; i++) gate.failedRead();
        assertTrue(gate.failedRead());
        assertTrue(gate.ordinaryAllowed());
        assertEquals(6, gate.snapshot().revision);
        assertFalse(gate.snapshot().known);
        assertFalse(gate.snapshot().off);
        assertEquals(5, gate.snapshot().consecutiveFailedReads);

        assertTrue(gate.observeValid(0));
        assertFalse(gate.ordinaryAllowed());
        assertEquals(7, gate.snapshot().revision);
        assertEquals(0, gate.snapshot().consecutiveFailedReads);
        for (int i = 0; i < 4; i++) gate.failedRead();
        assertTrue(gate.observeValid(2));
        assertTrue(gate.ordinaryAllowed());
        assertEquals(12, gate.snapshot().revision);
        assertEquals(0, gate.snapshot().consecutiveFailedReads);
    }

    @Test public void invalidReadDoesNotResetAndEveryValidPoweredStateAllows() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        gate.observe(0);
        gate.failedRead();
        assertFalse(gate.observe(99));
        assertEquals(1, gate.snapshot().consecutiveFailedReads);
        assertFalse(gate.ordinaryAllowed());
        CameraSourceDemand.PowerGate powered = new CameraSourceDemand.PowerGate();
        for (int raw = 1; raw <= 4; raw++) {
            assertTrue(powered.observe(0));
            assertTrue(powered.observe(raw));
            assertTrue(powered.ordinaryAllowed());
            assertEquals(0, powered.snapshot().consecutiveFailedReads);
        }
    }

    @Test public void hostSnapshotUpdatesKeepPolicyAndRejectInvalidValues() {
        CameraSourceDemand.PowerGate gate = new CameraSourceDemand.PowerGate();
        assertTrue(gate.update(new CameraSourceDemand.PowerGate.Snapshot(true, true, 3, 7)));
        assertFalse(gate.ordinaryAllowed());
        assertFalse(gate.update(new CameraSourceDemand.PowerGate.Snapshot(true, true, 3, 7)));
        try {
            gate.update(new CameraSourceDemand.PowerGate.Snapshot(false, true, 5));
            fail("unknown OFF snapshot must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid camera power state"));
        }
        try {
            gate.update(new CameraSourceDemand.PowerGate.Snapshot(true, true, 5));
            fail("fifth failed GET must release confirmed OFF");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("invalid camera power state"));
        }
    }
}

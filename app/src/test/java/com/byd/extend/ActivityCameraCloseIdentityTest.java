package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityCameraCloseIdentityTest {
    @Test public void unopenedCloseRequiresExactOperationAndKnownCameraOwner() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "activity_transition:1:camera_tab_changed";
        identity.capture(1, reason, 4);
        assertTrue(identity.matchesUnopenedTerminal(1, reason, 4));
        assertFalse(identity.matchesTerminal(1, reason, 4, 0, true));
        assertFalse(identity.matchesUnopenedTerminal(2, reason, 4));
        assertFalse(identity.matchesUnopenedTerminal(1, "replacement", 4));
        assertFalse(identity.matchesUnopenedTerminal(1, reason, 5));
        assertFalse(identity.matchesUnopenedTerminal(1, reason, 0));
        assertTrue(identity.bindStockCloseOwner(1, reason, 4, 6));
        assertFalse(identity.matchesUnopenedTerminal(1, reason, 4));
        assertTrue(identity.matchesTerminal(1, reason, 4, 6, true));
        identity.capture(2, reason, 4);
        assertFalse(identity.matchesUnopenedTerminal(1, reason, 4));
        assertTrue(identity.matchesUnopenedTerminal(2, reason, 4));
        identity.clear();
        assertFalse(identity.matchesUnopenedTerminal(2, reason, 4));
    }

    @Test public void terminalUsesCapturedNormalCloseIdentityAfterSurfaceLoss() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "reverse_surface_destroyed";
        identity.capture(2, reason, 1);
        assertEquals(0L, identity.avmShellEpoch());
        assertTrue(identity.bindStockCloseOwner(2, reason, 1, 1));

        assertTrue(identity.matchesTerminal(2, reason, 1, 1, true));
        assertFalse(identity.matchesTerminal(2, reason, 2, 2, true));
        assertFalse(identity.matchesTerminal(2, reason, 0, 1, true));
        assertFalse(identity.matchesTerminal(2, reason, 1, 0, true));
        assertFalse(identity.matchesTerminal(3, reason, 1, 1, true));
        assertFalse(identity.matchesTerminal(2, "activity_stopped", 1, 1, true));

        identity.capture(2, reason, 0);
        assertFalse(identity.matchesTerminal(2, reason, 1, 1, true));
        assertTrue(identity.bindStockCloseOwner(2, reason, 1, 1));
        assertEquals(1L, identity.cameraShellEpoch());
        assertEquals(1L, identity.avmShellEpoch());
        assertTrue(identity.matchesTerminal(2, reason, 1, 1, true));
    }

    @Test public void matchingCloseFailureAndClearRejectLaterDuplicate() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String token = "activity_transition:4:camera_tab_changed";
        identity.capture(4, token, 7);
        assertTrue(identity.bindStockCloseOwner(4, token, 7, 11));

        assertTrue(identity.matchesCloseFailure(4, token, 7, 11, true));
        assertFalse(identity.matchesCloseFailure(5, token, 7, 11, true));
        assertFalse(identity.matchesCloseFailure(4, "", 7, 11, true));
        assertFalse(identity.matchesCloseFailure(4, "other_close", 7, 11, true));
        assertFalse(identity.matchesCloseFailure(4, token, 6, 11, true));
        assertFalse(identity.matchesCloseFailure(4, token, 7, 0, true));
        assertFalse(identity.matchesCloseFailure(4, token, 7, 12, true));
        identity.updateRequestId(9);
        assertFalse(identity.matchesTerminal(4, token, 7, 11, true));
        assertTrue(identity.matchesTerminal(9, token, 7, 11, true));
        identity.clear();
        assertFalse(identity.matchesTerminal(9, token, 7, 11, true));
        assertFalse(identity.matchesCloseFailure(9, token, 7, 11, true));
    }

    @Test public void oldGlobalEpochIsNotAdoptedAndCloseOwnerBindsExactEpoch() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "activity_transition:4:camera_tab_changed";

        // A prior global epoch may belong to an earlier request. Capture retains no AVM owner.
        identity.capture(4, reason, 7);
        assertEquals(0L, identity.avmShellEpoch());
        assertFalse(identity.matchesTerminal(4, reason, 7, 11, true));
        assertFalse(identity.matchesShellDeath(4, 7, 11));

        // Only the exact request-scoped close operation can pin the target shell epoch.
        assertFalse(identity.bindStockCloseOwner(3, reason, 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, "replacement", 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 8, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 0));
        assertEquals(0L, identity.avmShellEpoch());

        assertTrue(identity.bindStockCloseOwner(4, reason, 7, 12));
        assertEquals(12L, identity.avmShellEpoch());
        assertTrue(identity.matchesTerminal(4, reason, 7, 12, true));
        assertFalse(identity.matchesTerminal(4, reason, 7, 11, true));
        assertTrue(identity.matchesShellDeath(4, 7, 12));
        assertFalse(identity.matchesShellDeath(4, 7, 0));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 13));
        assertEquals(12L, identity.avmShellEpoch());
    }

    @Test public void closeOwnerBindingRequiresExactRequestReasonCameraAndPositiveAvmEpoch() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "reverse_surface_destroyed";
        identity.capture(4, reason, 7);

        assertFalse(identity.bindStockCloseOwner(5, reason, 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, "other_close", 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 8, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 0, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 0));
        assertEquals(0L, identity.avmShellEpoch());

        assertTrue(identity.bindStockCloseOwner(4, reason, 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 13));
        assertTrue(identity.matchesTerminal(4, reason, 7, 12, true));
        assertFalse(identity.matchesTerminal(4, reason, 7, 13, true));
    }

    @Test public void closeOwnerCanInitializeUnknownCameraEpochWithoutAdoptingSuccessor() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "reverse_surface_destroyed";
        identity.capture(4, reason, 0);

        assertFalse(identity.bindStockCloseOwner(5, reason, 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, "replacement", 7, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 0, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 0));
        assertEquals(0L, identity.cameraShellEpoch());
        assertEquals(0L, identity.avmShellEpoch());
        assertFalse(identity.matchesTerminal(4, reason, 7, 12, true));
        assertFalse(identity.matchesShellDeath(4, 7, 12));

        assertTrue(identity.bindStockCloseOwner(4, reason, 7, 12));
        assertEquals(7L, identity.cameraShellEpoch());
        assertEquals(12L, identity.avmShellEpoch());
        assertTrue(identity.matchesTerminal(4, reason, 7, 12, true));
        assertFalse(identity.bindStockCloseOwner(4, reason, 8, 12));
        assertFalse(identity.bindStockCloseOwner(4, reason, 7, 13));
        assertTrue(identity.matchesTerminal(4, reason, 7, 12, true));
    }

    @Test public void unknownOwnerCanMatchOnlyExactExplicitFailureAndNotSuccess() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "activity_transition:4:camera_tab_changed";
        identity.capture(4, reason, 7);

        assertTrue(identity.matchesCloseFailure(4, reason, 7, 0, true));
        assertFalse(identity.matchesTerminal(4, reason, 7, 0, true));
        assertFalse(identity.matchesCloseFailure(4, "", 7, 11, true));
        assertFalse(identity.matchesCloseFailure(4, "replacement", 7, 11, true));
        assertFalse(identity.matchesCloseFailure(4, reason, 8, 0, true));
        assertFalse(identity.matchesCloseFailure(5, reason, 7, 0, true));
    }

    @Test public void explicitFailureCanResolveBeforeEitherEpochIsObserved() {
        ActivityCameraCloseIdentity identity = new ActivityCameraCloseIdentity();
        String reason = "activity_transition:4:camera_tab_changed";
        identity.capture(4, reason, 0);

        assertTrue(identity.matchesCloseFailure(4, reason, 7, 0, true));
        assertFalse(identity.matchesCloseFailure(4, "", 7, 0, true));
        assertFalse(identity.matchesCloseFailure(4, "replacement", 7, 0, true));
        assertFalse(identity.matchesCloseFailure(5, reason, 7, 0, true));
        assertTrue(identity.matchesCloseFailure(4, reason, 0, 0, true));
        assertFalse(identity.matchesCloseFailure(4, "", 0, 0, true));
        assertFalse(identity.matchesCloseFailure(5, reason, 0, 0, true));
        assertFalse(identity.matchesCloseFailure(4, reason, -1, 0, true));
        assertFalse(identity.matchesCloseFailure(4, reason, 0, -1, true));
        assertFalse(identity.matchesTerminal(4, reason, 0, 0, true));
        assertFalse(identity.matchesShellDeath(4, 0, 0));
        assertFalse(identity.matchesTerminal(4, reason, 7, 0, true));
        assertFalse(identity.matchesShellDeath(4, 7, 0));
    }

    @Test public void intermediateStockClosePendingIsNotTerminal() {
        assertTrue(CameraProbeActivity.shouldWaitForStockShellClose(
                "reverse_preview_with_stock_base", "helper", true));
        assertFalse(CameraProbeActivity.shouldWaitForStockShellClose(
                "reverse_preview_with_stock_base", "stock_avm_shell", true));
        assertFalse(CameraProbeActivity.shouldWaitForStockShellClose(
                "reverse_preview_with_stock_base", "helper", false));
    }
}

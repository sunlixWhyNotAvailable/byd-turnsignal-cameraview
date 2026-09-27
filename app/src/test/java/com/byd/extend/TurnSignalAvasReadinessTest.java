package com.byd.extend;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TurnSignalAvasReadinessTest {
    @Test
    public void pendingConfigurationAndStatusCoalesceAndFlushAfterAttachSync() {
        TurnSignalController.PendingAvasOperations pending =
                new TurnSignalController.PendingAvasOperations();

        assertFalse(pending.deferConfigurationIf(false));
        assertFalse(pending.deferStatusIf(false));
        assertTrue(pending.deferConfigurationIf(true));
        assertTrue(pending.deferConfigurationIf(true));
        assertTrue(pending.deferStatusIf(true));
        assertTrue(pending.deferStatusIf(true));

        assertTrue(pending.completeAttachSync());
        assertFalse(pending.completeAttachSync());
        assertArrayEquals(new String[0], pending.takeFailureStages());
    }

    @Test
    public void terminalReadinessFailureReportsEachPendingOperationOnce() {
        TurnSignalController.PendingAvasOperations pending =
                new TurnSignalController.PendingAvasOperations();
        pending.deferConfigurationIf(true);
        pending.deferStatusIf(true);

        assertArrayEquals(new String[] {"config", "status"}, pending.takeFailureStages());
        assertArrayEquals(new String[0], pending.takeFailureStages());
    }

    @Test
    public void stoppedSessionClearsDeferredOperations() {
        TurnSignalController.PendingAvasOperations pending =
                new TurnSignalController.PendingAvasOperations();
        pending.deferConfigurationIf(true);
        pending.deferStatusIf(true);

        pending.clear();

        assertArrayEquals(new String[0], pending.takeFailureStages());
        assertFalse(pending.completeAttachSync());
    }
}

package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class TurnSignalReconciliationTest {
    @Test public void onlyMatchingControlCallbacksSettlePendingEdges() {
        TurnSignalReconciliation pending = new TurnSignalReconciliation();
        pending.expect(5, 2, 2, 1000, 2250);
        pending.callback(2, 2); // Steering is not a stalk/blink edge.
        pending.callback(1, 1);
        assertEquals(5, pending.mask());
        pending.callback(1, 2);
        assertTrue(pending.pending());
        pending.callback(4, 2);
        assertFalse(pending.pending());
    }

    @Test public void repeatedGetCannotExtendDeadlineAndStaleInputExpiresImmediately() {
        TurnSignalReconciliation pending = new TurnSignalReconciliation();
        pending.expect(1, 2, 1, 1000, 2250);
        pending.expect(4, 2, 2, 2000, 3250);
        assertEquals(2250, pending.deadline());
        assertFalse(pending.expired(2249, true));
        assertTrue(pending.expired(2250, true));
        assertTrue(pending.expired(2001, false));
        pending.clear();
        assertFalse(pending.pending());
        pending.expect(1, 1, 1, 3000, 3200);
        assertEquals(3200, pending.deadline());
        assertTrue(pending.expired(3200, true));
    }
}

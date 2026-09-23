package com.byd.extend;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class UpdateHintLifetimeTest {
    @Test
    public void wakeReconcilesTheOriginalElapsedDeadline() {
        long deadline = UpdateHintLifetime.deadlineAfter(1_000_000L, 10_000L);

        assertEquals(10_000L, UpdateHintLifetime.remainingMs(deadline, 1_000_000L));
        assertEquals(3_500L, UpdateHintLifetime.remainingMs(deadline, 1_006_500L));
        assertEquals(0L, UpdateHintLifetime.remainingMs(deadline, deadline));
        assertEquals(0L, UpdateHintLifetime.remainingMs(deadline, 1_013_000L));
    }
}

package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class MotionConfigRetryTest {
    @Test public void retriesAreBoundedAndDuplicateRefreshDoesNotRefillBudget() {
        MotionConfigRetry retry = new MotionConfigRetry();
        assertTrue(retry.select("off"));
        assertEquals(1_000L, retry.afterFailure("off"));
        assertFalse(retry.select("off"));
        assertEquals(3_000L, retry.afterFailure("off"));
        assertEquals(-1L, retry.afterFailure("off"));
        assertFalse(retry.select("off"));
        assertEquals(-1L, retry.afterFailure("off"));
        retry.recovered();
        assertEquals(1_000L, retry.afterFailure("off"));
    }

    @Test public void replacementRejectsObsoleteFailuresAndStartsAFreshBudget() {
        MotionConfigRetry retry = new MotionConfigRetry();
        retry.select("recording");
        assertEquals(1_000L, retry.afterFailure("recording"));
        assertTrue(retry.select("off"));
        assertEquals(-1L, retry.afterFailure("recording"));
        assertEquals(1_000L, retry.afterFailure("off"));
    }
}

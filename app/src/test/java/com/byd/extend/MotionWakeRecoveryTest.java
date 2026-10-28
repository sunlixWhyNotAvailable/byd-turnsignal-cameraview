package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class MotionWakeRecoveryTest {
    @Test public void repeatedWakeDoesNotReplaceOrExtendThePendingTail() {
        MotionWakeRecovery recovery = new MotionWakeRecovery();
        long request = recovery.begin("session", 7);
        assertTrue(recovery.retain(request, "session", 7, 10_500L, 3));
        assertEquals(0L, recovery.begin("session", 7));
        assertFalse(recovery.retain(request, "session", 7, 20_500L, 3));
        assertEquals(10_500L, recovery.deadline());
        assertEquals(3, recovery.recordingGeneration());
        assertEquals(7, recovery.takeAfterDetach("session", 7));
        assertEquals(-1, recovery.takeAfterDetach("session", 7));
    }

    @Test public void replacementRejectsOldDecisionAndDetach() {
        MotionWakeRecovery recovery = new MotionWakeRecovery();
        long old = recovery.begin("old", 7);
        long current = recovery.begin("new", 8);
        assertFalse(recovery.retain(old, "old", 7, 10_500L, 3));
        assertEquals(-1, recovery.takeAfterDetach("old", 7));
        recovery.sourceClosed(7);
        assertTrue(recovery.matches(current, "new", 8));
        recovery.sourceClosed(8);
        assertFalse(recovery.matches(current, "new", 8));
    }

    @Test public void completedDetachBeforeDecisionCannotRetainTheRetiredSource() {
        MotionWakeRecovery recovery = new MotionWakeRecovery();
        long request = recovery.begin("session", 7);
        assertEquals(-1, recovery.takeAfterDetach("session", 8));
        assertEquals(7, recovery.takeAfterDetach("session", 7));
        assertFalse(recovery.retain(request, "session", 7, 10_500L, 3));
    }
}

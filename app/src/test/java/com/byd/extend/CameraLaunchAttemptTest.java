package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CameraLaunchAttemptTest {
    @Test public void joiningDoesNotExtendTheOriginalTwentySecondDeadline() {
        CameraLaunchAttempt attempt = new CameraLaunchAttempt(1, 1_000, false);
        assertTrue(attempt.canJoin(9_000, false));
        assertTrue(attempt.canJoin(20_999, false));
        assertEquals(21_000, attempt.deadlineMs);
        assertFalse(attempt.canJoin(21_000, false));
        assertFalse(attempt.canJoin(2_000, true));
        assertTrue(new CameraLaunchAttempt(2, 2_000, true).canJoin(2_001, true));
    }

    @Test public void onlyCompleteProcessIdentityCanBeRetainedForReplacement() {
        CameraLaunchAttempt attempt = new CameraLaunchAttempt(1, 0, false);
        attempt.recordIdentity("camera_launch_timeout\ncamera_process_identity=13059:61309779\n");
        assertEquals(13059, attempt.pid);
        assertEquals(61309779, attempt.startTicks);
        for (String invalid : new String[]{"camera_process_identity=0:1", "camera_process_identity=1:0",
                "camera_process_identity=999999999999:2", "camera_process_identity=12:bad"}) {
            CameraLaunchAttempt empty = new CameraLaunchAttempt(2, 0, false);
            empty.recordIdentity(invalid);
            assertEquals(0, empty.pid);
            assertEquals(0, empty.startTicks);
        }
    }
}

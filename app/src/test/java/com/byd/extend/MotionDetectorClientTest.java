package com.byd.extend;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import org.junit.Test;

public class MotionDetectorClientTest {
    @Test public void disabledCompletionRequiresEmptySessionAndCannotRetainViewing() throws Exception {
        String disabled = MotionConfig.parse("{}").toJson();
        assertTrue(MotionDetectorClient.validConfiguredSession("", disabled, 0));
        assertFalse(MotionDetectorClient.validConfiguredSession("", disabled, 1));
        assertFalse(MotionDetectorClient.validConfiguredSession("session", disabled, 0));
        assertFalse(MotionDetectorClient.validConfiguredSession(null, disabled, 0));
        assertFalse(MotionDetectorClient.validConfiguredSession("", "{", 0));
        assertFalse(MotionDetectorClient.validConfiguredSession("", disabled, 2));
    }

    @Test public void enabledCompletionStillRequiresAnAuthenticatedSession() throws Exception {
        String enabled = MotionConfig.parse("{\"warning\":true}").toJson();
        String session = java.util.UUID.randomUUID().toString().replace("-", "");
        assertFalse(MotionDetectorClient.validConfiguredSession("", enabled, 0));
        assertFalse(MotionDetectorClient.validConfiguredSession("invalid/session", enabled, 0));
        assertTrue(MotionDetectorClient.validConfiguredSession(session, enabled, 0));
        assertTrue(MotionDetectorClient.validConfiguredSession(session, enabled, 1));
    }
    @Test public void replacementRejectsProcessGroupPids() {
        assertThrows(IllegalArgumentException.class,
                () -> MotionDetectorClient.stopObsoleteWorkerCommand(0));
        assertThrows(IllegalArgumentException.class,
                () -> MotionDetectorClient.stopObsoleteWorkerCommand(-1));
    }

    @Test public void nativeEnvironmentBelongsToTheSpawnedRuntimeAndPathsAreQuoted() {
        String command = MotionDetectorClient.launchCommand("/data/app/Extend's base.apk", 10123,
                "apk:123", "/system/app/Sentry's libraries/arm64", true);
        assertTrue(command.contains("LD_LIBRARY_PATH='/system/app/Sentry'\\''s libraries/arm64:/system/lib64:/vendor/lib64' CLASSPATH='/data/app/Extend'\\''s base.apk' setsid app_process"));
        assertTrue(command.contains(" 10123 'apk:123' </dev/null"));
    }

    @Test public void mediaOnlyLaunchWithoutSentryKeepsAbiAppropriateSystemPaths() {
        String command = MotionDetectorClient.launchCommand("/data/app/base.apk", 10123,
                "apk:123", null, false);
        assertTrue(command.contains("LD_LIBRARY_PATH='/system/lib:/vendor/lib'"));
        assertFalse(command.contains("lib64"));
        assertFalse(command.contains("null:"));
    }
}

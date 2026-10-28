package com.byd.extend;

import org.junit.Test;
import org.json.JSONObject;
import static org.junit.Assert.*;

public class MotionConfigTest {
    @Test public void absentOemLimitUsesExactCapacityAndPreservesSavedLimits() throws Exception {
        MotionConfig defaults = MotionConfig.parse("{\"oemCleanup\":true}");
        assertFalse(defaults.anyEnabled());
        assertEquals(0, defaults.oemLimitGb);
        assertFalse(new JSONObject(defaults.toJson()).has("oemQuota"));
        long capacity = 63_734_567_891L;
        assertEquals(capacity, MotionOemRetentionState.from(defaults).limitBytes(capacity));

        MotionConfig saved = MotionConfig.parse("{\"oemCleanup\":false,\"oemQuota\":10}");
        MotionConfig reopened = MotionConfig.parse(saved.toJson());
        assertEquals(10, reopened.oemLimitGb);
        assertEquals(10 * MotionStoragePolicy.GB, MotionOemRetentionState.from(reopened).limitBytes(capacity));
        assertFalse(MotionOemRetentionState.from(reopened).active());
        assertFalse(reopened.cameraConfig().oemCleanup);
        assertEquals(0, reopened.cameraConfig().oemLimitGb);
        assertEquals(10, reopened.oemLimitGb);
    }
    @Test public void defaultsAreOptInAndKeepIndependentBeforeAfter() throws Exception {
        MotionConfig defaults=MotionConfig.parse("{}");
        assertFalse(defaults.anyEnabled());
        assertFalse(defaults.audioEnabled);
        assertFalse(defaults.fallbackEnabled);
        assertEquals(MotionConfig.DEFAULT_RECORDING_SENSITIVITY, defaults.recordingSensitivity);
        assertEquals(1, defaults.lockCondition);
        assertEquals(1, new JSONObject(defaults.toJson()).getInt("lock"));
        assertEquals(1920,defaults.width());
        assertEquals(1080,defaults.height());
        MotionConfig selected=MotionConfig.parse("{\"warning\":true,\"enabled\":true,\"before\":2,\"after\":28,\"order\":\"3,2,1,0\"}");
        MotionConfig reopened=MotionConfig.parse(selected.toJson());
        assertTrue(reopened.warningEnabled);
        assertTrue(reopened.recordingEnabled);
        assertEquals(2,reopened.beforeSeconds);
        assertEquals(28,reopened.afterSeconds);
        assertArrayEquals(new int[]{3,2,1,0},reopened.cameraOrder);
    }

    @Test public void recordingSensitivityMapsAndRoundTripsWithLegacyDefault() throws Exception {
        assertEquals("recording_sensitivity", MotionConfig.KEY_RECORDING_SENSITIVITY);
        assertEquals(3, MotionConfig.nativeSensitivityFor(0));
        assertEquals(2, MotionConfig.nativeSensitivityFor(1));
        assertEquals(1, MotionConfig.nativeSensitivityFor(2));
        assertEquals(6_000, MotionConfig.rThresholdMsFor(0));
        assertEquals(3_000, MotionConfig.rThresholdMsFor(1));
        assertEquals(0, MotionConfig.rThresholdMsFor(2));

        MotionConfig legacy = MotionConfig.parse("{\"enabled\":true}");
        assertEquals(1, legacy.recordingSensitivity);
        MotionConfig low = MotionConfig.parse("{\"enabled\":true,\"recordingSensitivity\":0}");
        MotionConfig reopened = MotionConfig.parse(low.toJson());
        assertEquals(0, reopened.recordingSensitivity);
        assertTrue(reopened.onlyInPlaceMotionChangesDifferFrom(legacy));
        assertTrue(reopened.onlyRecordingSensitivityDiffersFrom(legacy));
        MotionConfig combined = MotionConfig.parse(
                "{\"warning\":true,\"enabled\":true,\"recordingSensitivity\":0}");
        assertTrue(combined.onlyInPlaceMotionChangesDifferFrom(legacy));
        assertFalse(combined.onlyRecordingSensitivityDiffersFrom(legacy));
        assertFalse(MotionConfig.parse("{\"enabled\":true,\"quality\":2}")
                .onlyInPlaceMotionChangesDifferFrom(legacy));
        assertEquals(2, MotionConfig.parse("{\"recordingSensitivity\":99}").recordingSensitivity);
    }

    @Test public void activationModeMigrationPreservesOffModesAndSerializesLegacyValue() throws Exception {
        assertEquals(0, MotionConfig.normalizeLockMode(0));
        assertEquals(1, MotionConfig.normalizeLockMode(1));
        assertEquals(1, MotionConfig.normalizeLockMode(2));
        MotionConfig off = MotionConfig.parse("{\"lock\":0}");
        MotionConfig offAndLocked = MotionConfig.parse("{\"lock\":1}");
        assertEquals(0, off.lockCondition);
        assertEquals(0, MotionConfig.parse(off.toJson()).lockCondition);
        assertEquals(1, offAndLocked.lockCondition);
        assertEquals(1, MotionConfig.parse(offAndLocked.toJson()).lockCondition);
        MotionConfig migrated = MotionConfig.parse("{\"lock\":2}");
        assertEquals(1, migrated.lockCondition);
        assertEquals(1, new JSONObject(migrated.toJson()).getInt("lock"));
        assertEquals(1, MotionConfig.parse(migrated.toJson()).lockCondition);
    }

    @Test public void malformedOrderAndOutOfRangeValuesCannotReachEncoder() throws Exception {
        MotionConfig config=MotionConfig.parse("{\"before\":0,\"after\":99,\"quality\":9,\"internalLimit\":-2,\"order\":\"0,1,1,3\"}");
        assertEquals(1,config.beforeSeconds);
        assertEquals(30,config.afterSeconds);
        assertEquals(2560,config.width());
        assertEquals(1,config.internalLimitGb);
        assertArrayEquals(new int[]{0,1,2,3},config.cameraOrder);
    }

    @Test public void powerAndLockFiltersRequireActualEligibleValues() throws Exception {
        MotionConfig any=MotionConfig.parse("{\"enabled\":true,\"lock\":0}");
        MotionConfig locked=MotionConfig.parse("{\"enabled\":true,\"lock\":1}");
        assertTrue(any.eligible(0,-1));
        assertFalse(any.eligible(-1,2));
        assertFalse(any.eligible(1,2));
        assertTrue(locked.eligible(0,2));
        assertFalse(locked.eligible(0,1));
        assertFalse(locked.eligible(0,-1));
        assertTrue(MotionConfig.parse("{\"enabled\":true}").eligible(0,2));
    }

    @Test public void remoteViewingKeepsWorkerEnabledWithoutEnablingMotion() throws Exception {
        MotionConfig remoteOnly = MotionConfig.parse(
                "{\"remoteViewingEnabled\":true,\"chargeSocThreshold\":35,\"chargeVoltageTenths\":127}");
        assertTrue(remoteOnly.anyEnabled());
        assertFalse(remoteOnly.motionEnabled());
        assertFalse(remoteOnly.eligible(0, 2));
        MotionConfig reopened = MotionConfig.parse(remoteOnly.toJson());
        assertTrue(reopened.remoteViewingEnabled);
        assertEquals(35, reopened.chargeSocThresholdPercent);
        assertEquals(127, reopened.chargeVoltageThresholdTenths);
    }
}

package com.byd.extend.remote;

import com.byd.extend.CameraChargePolicy;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ViewerBatteryStatusTest {
    private CameraChargePolicy policy() throws Exception {
        CameraChargePolicy policy = new CameraChargePolicy(
                Files.createTempDirectory("viewer-battery-").toFile(), 20, 124);
        policy.setMonitoring(true);
        return policy;
    }

    @Test public void serializesTypedReadingsAndRemainingFreshness() throws Exception {
        CameraChargePolicy policy = policy();
        policy.updateSoc(76);
        policy.updateVoltage(12.7f);
        JSONObject output = new JSONObject(new ViewerBatteryStatus(policy.snapshot(), 1_000L)
                .toJson(6_000L).toString());
        assertEquals(76, output.getInt("socPercent"));
        assertEquals(12.7, output.getDouble("voltageVolts"), .0001);
        assertEquals(0, output.getInt("missingMetrics"));
        assertEquals(10_000L, output.getLong("validForMs"));
    }

    @Test public void missingSignalsRemainIndependentAndZeroSocIsReal() throws Exception {
        CameraChargePolicy policy = policy();
        policy.updateSoc(0);
        policy.updateVoltage(Float.NaN);
        JSONObject output = new ViewerBatteryStatus(policy.snapshot(), 0L).toJson(1L);
        assertEquals(0, output.getInt("socPercent"));
        assertTrue(output.isNull("voltageVolts"));
        assertEquals(2, output.getInt("missingMetrics"));
        policy.updateSoc(null);
        policy.updateVoltage(13.2f);
        output = new ViewerBatteryStatus(policy.snapshot(), 2L).toJson(3L);
        assertTrue(output.isNull("socPercent"));
        assertEquals(13.2, output.getDouble("voltageVolts"), .0001);
        assertEquals(1, output.getInt("missingMetrics"));
    }

    @Test public void expirationAndClockRegressionDoNotChangeTheLowChargeLatch() throws Exception {
        CameraChargePolicy policy = policy();
        policy.updateSoc(10);
        policy.updateVoltage(12.0f);
        ViewerBatteryStatus sample = new ViewerBatteryStatus(policy.snapshot(), 1_000L);
        assertEquals(1L, sample.toJson(15_999L).getLong("validForMs"));
        for (long now : new long[] {16_000L, 100_000L, 999L}) {
            JSONObject output = sample.toJson(now);
            assertTrue(output.isNull("socPercent"));
            assertTrue(output.isNull("voltageVolts"));
            assertEquals(3, output.getInt("missingMetrics"));
            assertEquals(0L, output.getLong("validForMs"));
        }
        assertTrue(policy.snapshot().isBlocked());
        assertEquals(Integer.valueOf(10), policy.snapshot().socPercent());
    }

    @Test public void stoppedAndAbsentOwnersProduceNullRatherThanZero() throws Exception {
        CameraChargePolicy policy = policy();
        policy.updateSoc(76);
        policy.updateVoltage(12.7f);
        policy.setMonitoring(false);
        for (CameraChargePolicy.State state : new CameraChargePolicy.State[] {null, policy.snapshot()}) {
            JSONObject output = new ViewerBatteryStatus(state, 0L).toJson(1L);
            assertTrue(output.isNull("socPercent"));
            assertTrue(output.isNull("voltageVolts"));
            assertEquals(0L, output.getLong("validForMs"));
        }
    }
}

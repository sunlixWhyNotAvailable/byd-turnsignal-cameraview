package com.byd.extend.remote;

import com.byd.extend.CameraChargePolicy;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only presentation snapshot; expiry never changes the shared protection policy. */
public final class ViewerBatteryStatus {
    // The shared owner refreshes every five seconds; don't retain a stopped publisher indefinitely.
    public static final long MAX_AGE_MS = 15_000L;
    private final CameraChargePolicy.State state;
    private final long receivedMs;

    public ViewerBatteryStatus(CameraChargePolicy.State state, long receivedMs) {
        this.state = state;
        this.receivedMs = receivedMs;
    }

    public JSONObject toJson(long nowMs) throws JSONException {
        long age = nowMs - receivedMs;
        boolean fresh = state != null && state.isMonitoring() && age >= 0 && age < MAX_AGE_MS;
        Integer soc = fresh && !state.isSocUnavailable() ? state.socPercent() : null;
        Float voltage = fresh && !state.isVoltageUnavailable() ? state.voltageVolts() : null;
        int missing = (soc == null ? CameraChargePolicy.MISSING_SOC : 0)
                | (voltage == null ? CameraChargePolicy.MISSING_VOLTAGE : 0);
        return new JSONObject().put("socPercent", soc == null ? JSONObject.NULL : soc)
                .put("voltageVolts", voltage == null ? JSONObject.NULL : voltage)
                .put("missingMetrics", missing)
                .put("validForMs", fresh ? MAX_AGE_MS - age : 0L);
    }
}

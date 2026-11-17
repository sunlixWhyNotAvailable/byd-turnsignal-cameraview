package com.byd.extend;

import java.util.ArrayList;
import java.util.List;

/** Geometry and the latest decoded sample; no subscription or device ownership. */
final class DistanceAdasMeasurements {
    private AdasDistanceModel.Calibration calibration = AdasDistanceModel.ZERO;
    private final DistanceObjectContinuity continuity = new DistanceObjectContinuity();
    private List<AdasDistanceModel.Obstacle> latest;
    private long receivedAt;

    boolean calibrate(int front, int rear, int left, int right) {
        if (calibration.matches(front, rear, left, right)) return false;
        calibration = new AdasDistanceModel.Calibration(front, rear, left, right);
        continuity.clear();
        return true;
    }

    DistanceFrame accept(List<AdasDistanceModel.Obstacle> objects, long generation, long received) {
        DistanceFrame frame = measure(objects, generation, received);
        latest = new ArrayList<>(objects);
        receivedAt = received;
        return frame;
    }

    DistanceFrame recalculate(long generation, long now) {
        if (latest == null || now < receivedAt || now - receivedAt >= DistanceFrame.STALE_MS) {
            clear();
            return DistanceFrame.unavailable(generation, now, "calibration_waiting_frame");
        }
        return measure(latest, generation, receivedAt);
    }

    void clear() { latest = null; continuity.clear(); }

    private DistanceFrame measure(List<AdasDistanceModel.Obstacle> objects, long generation, long received) {
        AdasDistanceModel.Result result = AdasDistanceModel.evaluate(objects, calibration);
        return new DistanceFrame(generation, received, true, "ready", result.distances,
                continuity.accept(result.objects));
    }
}

package com.byd.extend;

import java.util.Arrays;

/** Distance-only changes are sampled; source/availability/radar transitions are never delayed. */
final class DistanceDiagnosticSampler {
    private long revision = -1, emittedAt;
    private Object[] last;

    long delay(long now, DistanceFrame frame, DistanceFrame adas, ParkingRadarState.Snapshot radar) {
        if (revision != DiagnosticLogPolicy.revision()) { last = null; revision = DiagnosticLogPolicy.revision(); }
        if (!DiagnosticLogPolicy.extended()) { last = null; return -1; }
        Object[] state = {frame.radarMask, frame.status, frame.generation, adas.status,
                radar.raw(), radar.present(), radar.status(0), radar.status(1), radar.generation(0), radar.generation(1),
                sourceMask(frame), frame.distances(), adas.distances()};
        boolean urgent = last == null;
        if (last != null) for (int i = 0; i < 11; i++) if (!java.util.Objects.deepEquals(last[i], state[i])) urgent = true;
        if (Arrays.deepEquals(last, state)) return -1;
        if (!urgent && now - emittedAt < 500) return 500 - (now - emittedAt);
        last = state; emittedAt = now; return 0;
    }

    private static int sourceMask(DistanceFrame frame) {
        int mask = 0; double[] values = frame.distances();
        for (int i = 0; i < values.length; i++) if (Double.isFinite(values[i])) mask |= 1 << i;
        return mask;
    }
}

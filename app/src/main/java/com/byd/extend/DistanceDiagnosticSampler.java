package com.byd.extend;

import java.util.Arrays;

/** Geometry changes are sampled; source/radar and identified-owner transitions are immediate. */
final class DistanceDiagnosticSampler {
    private long revision = -1, emittedAt;
    private Object[] last;

    long delay(long now, DistanceFrame frame, DistanceFrame adas, ParkingRadarState.Snapshot radar) {
        if (revision != DiagnosticLogPolicy.revision()) { last = null; revision = DiagnosticLogPolicy.revision(); }
        if (!DiagnosticLogPolicy.extended()) { last = null; return -1; }
        Object[] state = {frame.radarMask, frame.status, frame.generation, adas.status,
                radar.raw(), radar.present(), radar.status(0), radar.status(1), radar.generation(0), radar.generation(1),
                sourceMask(frame), owners(adas), frame.distances(), adas.distances(), adas.objects};
        boolean urgent = last == null;
        if (last != null) for (int i = 0; i < 12; i++) if (!java.util.Objects.deepEquals(last[i], state[i])) urgent = true;
        if (Arrays.deepEquals(last, state)) return -1;
        if (!urgent && now - emittedAt < 500) return 500 - (now - emittedAt);
        last = state; emittedAt = now; return 0;
    }

    private static Object[] owners(DistanceFrame adas) {
        Object[] result=new Object[8]; double[] distances=adas.distances();
        for (int sector=0;sector<8;sector++) {
            java.util.Set<java.util.List<Object>> keys=new java.util.HashSet<>();
            for (DistanceObject value : adas.objects) if (value.distance(sector)==distances[sector])
                keys.add(Arrays.asList(value.id,value.type,value.continuity));
            result[sector]=keys;
        }
        return result;
    }

    private static int sourceMask(DistanceFrame frame) {
        int mask = 0; double[] values = frame.distances();
        for (int i = 0; i < values.length; i++) if (Double.isFinite(values[i])) mask |= 1 << i;
        return mask;
    }
}

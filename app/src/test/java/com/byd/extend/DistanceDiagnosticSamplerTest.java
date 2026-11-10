package com.byd.extend;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class DistanceDiagnosticSamplerTest {
    @After public void reset() { DiagnosticLogPolicy.configure(false); }
    private DistanceFrame adas(long time, double distance) {
        double[] values = new double[8]; java.util.Arrays.fill(values, distance);
        return new DistanceFrame(1, time, true, "ready", values);
    }
    @Test public void changedGeometryWaitsButRadarAndAvailabilityDoNot() {
        DiagnosticLogPolicy.configure(true);
        DistanceDiagnosticSampler sampler = new DistanceDiagnosticSampler();
        DistanceSources sources = new DistanceSources();
        sources.adas(adas(100, 80));
        assertEquals(0, sampler.delay(100, sources.frame(100), sources.adas(), sources.radar()));
        sources.adas(adas(110, 79));
        assertEquals(490, sampler.delay(110, sources.frame(110), sources.adas(), sources.radar()));
        assertEquals(0, sampler.delay(600, sources.frame(600), sources.adas(), sources.radar()));
        assertEquals(-1, sampler.delay(601, sources.frame(601), sources.adas(), sources.radar()));
        ParkingRadarState radar = new ParkingRadarState(); radar.begin(0, 1); radar.registered(0, 1);
        radar.callback(0x99000062, 65, 602, 1); sources.radar(radar.snapshot());
        assertEquals(0, sampler.delay(602, sources.frame(602), sources.adas(), sources.radar()));
        radar.callback(0x99000062, 64, 603, 1); sources.radar(radar.snapshot());
        assertEquals(0, sampler.delay(603, sources.frame(603), sources.adas(), sources.radar()));
        assertEquals(0, sampler.delay(611, sources.frame(611), sources.adas(), sources.radar()));
        assertFalse(sources.frame(611).adasFresh(611));
        assertEquals(64, sources.frame(611).distances()[0], 0);
    }
    @Test public void toggleDropsPreviousSamplingWindow() {
        DistanceDiagnosticSampler sampler = new DistanceDiagnosticSampler(); DistanceSources sources = new DistanceSources();
        sources.adas(adas(0, 60));
        DiagnosticLogPolicy.configure(true);
        assertEquals(0, sampler.delay(0, sources.frame(0), sources.adas(), sources.radar()));
        DiagnosticLogPolicy.configure(false);
        assertEquals(-1, sampler.delay(10, sources.frame(10), sources.adas(), sources.radar()));
        DiagnosticLogPolicy.configure(true);
        assertEquals(0, sampler.delay(20, sources.frame(20), sources.adas(), sources.radar()));
    }
}

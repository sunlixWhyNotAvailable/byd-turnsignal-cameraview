package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProcessCpuWindowTest {
    @Test public void actualIntervalIncludesSleepAndCountsAllCoresWithoutCrossingPidOrToggle() {
        ProcessCpuWindow window = new ProcessCpuWindow();
        assertNull(window.sample(123, 1_000, 1_000, 100));
        ProcessCpuWindow.Sample busy = window.sample(123, 31_000, 31_000, 82_900);
        assertEquals(276.0, busy.percent(), .001);
        ProcessCpuWindow.Sample slept = window.sample(123, 131_000, 61_000, 85_900);
        assertEquals(100_000, slept.elapsedMs);
        assertEquals(30_000, slept.uptimeMs);
        assertEquals(3.0, slept.percent(), .001);
        assertNull(window.sample(456, 161_000, 91_000, 100));
        window.reset();
        assertFalse(window.started());
        assertNull(window.sample(456, 191_000, 121_000, 500));
    }
}

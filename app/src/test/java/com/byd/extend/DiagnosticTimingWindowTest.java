package com.byd.extend;

import org.junit.After;
import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class DiagnosticTimingWindowTest {
    @After public void restoreLogging() { DiagnosticLogPolicy.configure(false); }

    @Test public void aggregatesInputsAndKeepsCpuPairedWithSlowestElapsedSample() {
        DiagnosticLogPolicy.configure(true);
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us", "cpu_us");
        assertNull(timing.record(0, 100, 20));
        assertNull(timing.record(4000, 400, 30));
        Map<String, Object> result = fields(timing.record(5000, 200, 100));
        assertEquals(3L, result.get("samples"));
        assertEquals(5000L, result.get("window_ms"));
        assertEquals(700d / 3, (double) result.get("work_us_avg"), .0001);
        assertEquals(400L, result.get("work_us_max"));
        assertEquals(100L, result.get("cpu_us_max"));
        assertEquals(30L, result.get("cpu_us_at_slowest"));
        assertNull(timing.record(5001, 1, 1));
    }

    @Test public void loggingOffProducesNothingAndToggleDiscardsPreviousWindow() {
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us");
        DiagnosticLogPolicy.configure(false);
        assertFalse(timing.enabled());
        assertNull(timing.record(0, 900));
        DiagnosticLogPolicy.configure(true);
        assertNull(timing.record(100, 100));
        DiagnosticLogPolicy.configure(false);
        DiagnosticLogPolicy.configure(true);
        assertNull(timing.record(6000, 2));
        Map<String, Object> result = fields(timing.record(11000, 4));
        assertEquals(2L, result.get("samples"));
        assertEquals(3d, (double) result.get("work_us_avg"), 0);
        for (String kind : new String[]{"distance_source_metrics", "distance_render_metrics",
                "distance_visibility_detail", "distance_settings_detail", "distance_stale_detail", "reverse_phase_detail"}) {
            assertTrue(DiagnosticLogPolicy.shouldPersist(kind));
            DiagnosticLogPolicy.configure(false);
            assertFalse(DiagnosticLogPolicy.shouldPersist(kind));
            assertFalse(DiagnosticLogPolicy.shouldProduce(kind));
            assertTrue(DiagnosticLogPolicy.shouldPersist("distance_source_error"));
            DiagnosticLogPolicy.configure(true);
        }
    }

    @Test public void sourceOrHostResetExcludesPreviousSession() {
        DiagnosticLogPolicy.configure(true);
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us");
        assertNull(timing.record(0, 900));
        timing.clear();
        assertNull(timing.record(8000, 2));
        Map<String, Object> result = fields(timing.record(13000, 4));
        assertEquals(2L, result.get("samples"));
        assertEquals(3d, (double) result.get("work_us_avg"), 0);
        assertEquals(5000L, result.get("window_ms"));
    }

    @Test public void contextBelongsToSlowestSampleInsteadOfReportTime() {
        DiagnosticLogPolicy.configure(true);
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us", "cpu_us");
        timing.recordContext(0, new Object[]{"fid", 10, "sequence", 1}, 500, 3);
        Map<String, Object> report = fields(timing.recordContext(5000, new Object[]{"fid", 11, "sequence", 2}, 20, 15));
        assertEquals(10, report.get("fid_at_slowest"));
        assertEquals(1, report.get("sequence_at_slowest"));
        assertEquals(3L, report.get("cpu_us_at_slowest"));
    }

    @Test public void flushRetainsShortWindowOnceAndStartsIndependentWindow() {
        DiagnosticLogPolicy.configure(true);
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us", "cpu_us");
        timing.recordContext(100, new Object[]{"sequence", 1}, 400, 20);
        timing.recordContext(120, new Object[]{"sequence", 2}, 200, 50);
        Map<String, Object> report = fields(timing.flush(185));
        assertEquals(2L, report.get("samples"));
        assertEquals(85L, report.get("window_ms"));
        assertEquals(300d, (double) report.get("work_us_avg"), 0);
        assertEquals(400L, report.get("work_us_max"));
        assertEquals(20L, report.get("cpu_us_at_slowest"));
        assertEquals(1, report.get("sequence_at_slowest"));
        assertNull(timing.flush(190));
        timing.record(200, 10, 5);
        report = fields(timing.flush(205));
        assertEquals(1L, report.get("samples"));
        assertEquals(5L, report.get("window_ms"));
        assertEquals(10d, (double) report.get("work_us_avg"), 0);
        assertFalse(report.containsKey("sequence_at_slowest"));
    }

    @Test public void flushDoesNotReviveSamplesAcrossLoggingPolicyChanges() {
        DiagnosticLogPolicy.configure(true);
        DiagnosticTimingWindow timing = new DiagnosticTimingWindow("work_us");
        timing.record(100, 900);
        DiagnosticLogPolicy.configure(false);
        assertNull(timing.flush(200));
        DiagnosticLogPolicy.configure(true);
        timing.record(300, 500);
        DiagnosticLogPolicy.configure(false);
        DiagnosticLogPolicy.configure(true);
        assertNull(timing.flush(400));
    }

    private static Map<String, Object> fields(Object[] values) {
        assertNotNull(values);
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
}

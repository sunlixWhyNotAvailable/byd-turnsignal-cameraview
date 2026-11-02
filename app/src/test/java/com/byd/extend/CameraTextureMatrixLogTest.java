package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public class CameraTextureMatrixLogTest {
    @Test public void reportsInitialAndChangesOnlyInTheCurrentExtendedLoggingPeriod() {
        boolean previous = DiagnosticLogPolicy.extended();
        try {
            CameraFramePump.MatrixLog log = new CameraFramePump.MatrixLog();
            float[] matrix = {1, 0, 0, 0, 0, -1, 0, 0, 0, 0, 1, 0, 0, 1, 0, 1};
            DiagnosticLogPolicy.configure(false);
            long disabledRevision = DiagnosticLogPolicy.revision();
            assertFalse(log.shouldReport(matrix, disabledRevision));
            DiagnosticLogPolicy.configure(true);
            long enabledRevision = DiagnosticLogPolicy.revision();
            assertFalse(log.shouldReport(matrix, disabledRevision));
            assertTrue(log.shouldReport(matrix, enabledRevision));
            for (int frame = 0; frame < 1000; frame++)
                assertFalse(log.shouldReport(matrix, enabledRevision));
            // SurfaceTexture reuses this array; the saved baseline must not alias it.
            matrix[0] = -1;
            matrix[12] = 1;
            assertTrue(log.shouldReport(matrix, enabledRevision));
            assertFalse(log.shouldReport(matrix, enabledRevision));
            matrix[0] = 1;
            matrix[12] = 0;
            assertTrue(log.shouldReport(matrix, enabledRevision));
            assertTrue(new CameraFramePump.MatrixLog().shouldReport(matrix, enabledRevision));
            DiagnosticLogPolicy.configure(false);
            assertFalse(log.shouldReport(matrix, DiagnosticLogPolicy.revision()));
            DiagnosticLogPolicy.configure(true);
            assertFalse(log.shouldReport(matrix, enabledRevision));
            assertTrue(log.shouldReport(matrix, DiagnosticLogPolicy.revision()));
            assertFalse(log.shouldReport(matrix, DiagnosticLogPolicy.revision()));
        } finally {
            DiagnosticLogPolicy.configure(previous);
        }
    }
}

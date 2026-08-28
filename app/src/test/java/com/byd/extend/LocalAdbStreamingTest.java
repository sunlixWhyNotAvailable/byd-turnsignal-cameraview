package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.Socket;

/** Focused seams for export-only ADB timeout and operation-local cancellation. */
public final class LocalAdbStreamingTest {
    @Test
    public void exportReadTimeoutIsLongerThanNormalFiveSecondReads() {
        assertEquals(30_000, LocalAdbClient.EXPORT_READ_TIMEOUT_MS);
        assertTrue(LocalAdbClient.EXPORT_READ_TIMEOUT_MS > 5_000);
    }

    @Test
    public void exportCancellationClosesOnlyItsRegisteredSocket() throws Exception {
        CompatibilityBundleExporter.ExportControl control =
                new CompatibilityBundleExporter.ExportControl();
        final boolean[] closed = {false};
        Socket socket = new Socket() {
            @Override public synchronized void close() throws IOException {
                closed[0] = true;
            }
        };
        long generation = LocalAdbClient.cancellationToken();
        control.registerActiveSocket(socket);
        control.cancel();
        assertTrue(closed[0]);
        assertTrue(LocalAdbClient.isCancellationTokenCurrent(generation));
    }
}

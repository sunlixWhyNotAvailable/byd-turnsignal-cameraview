package com.byd.extend;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.net.Socket;

/** Operation-local cancellation must not cancel unrelated ADB requests. */
public final class LocalAdbStreamingTest {
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

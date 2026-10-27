package com.byd.extend;

import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.io.IOException;

public class StockAvmRequestTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void cancellationBeforeDispatchPreventsRemoteOpen() {
        TurnSignalController.StockAvmOpenState request =
                new TurnSignalController.StockAvmOpenState(1, true);
        request.cancel();
        assertFalse(request.beginRemoteOpen());
        assertFalse(request.remoteOpenAttempted());
        TurnSignalController.StockAvmOpenState successor =
                new TurnSignalController.StockAvmOpenState(2, true);
        assertTrue(successor.beginRemoteOpen());
        assertTrue(successor.remoteOpenAttempted());
    }

    @Test public void cancellationAfterDispatchStillRequiresRemoteCleanup() {
        TurnSignalController.StockAvmOpenState request =
                new TurnSignalController.StockAvmOpenState(1, true);
        assertTrue(request.beginRemoteOpen());
        request.cancel();
        assertTrue(request.remoteOpenAttempted());
        assertFalse(request.beginRemoteOpen());
        assertTrue(request.remoteOpenAttempted());
    }

    @Test public void diagnosticPreservesActualFileFailureAndStage() throws Exception {
        try {
            Files.readAllBytes(temporary.getRoot().toPath().resolve("missing-startup.log"));
            fail("reading a missing file must fail");
        } catch (IOException failure) {
            StockAvmPreview.StageException stage =
                    new StockAvmPreview.StageException("read_startup_log", failure);
            String diagnostic = StockAvmShellProtocol.diagnostic(stage);
            assertSame(failure, stage.getCause());
            assertEquals("read_startup_log", stage.stage);
            assertTrue(diagnostic.contains(failure.getClass().getName()));
            assertTrue(diagnostic.contains("missing-startup.log"));
            assertTrue(diagnostic.contains("Caused by:"));
            assertTrue(diagnostic.contains("diagnosticPreservesActualFileFailureAndStage"));
        }
    }
}

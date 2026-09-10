package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class StockAvmShellProtocolTest {
    @Test
    public void endpointIsolatedFromCameraOverlayShell() {
        assertEquals(2, StockAvmShellProtocol.VERSION);
        assertFalse(StockAvmShellProtocol.SERVICE_NAME.equals(CameraShellProtocol.SERVICE_NAME));
        assertFalse(StockAvmShellProtocol.PROCESS_NAME.equals(CameraShellProtocol.PROCESS_NAME));
        assertTrue(StockAvmShellProtocol.HELPER_CLASS.contains("StockAvmShellMain"));
        assertTrue(TurnSignalController.avmLaunchCommand("/data/app/base.apk", 10058, 99)
                .contains("pidof " + StockAvmShellProtocol.PROCESS_NAME));
    }

    @Test
    public void retryIsExactlyOneBoundedResetBeforeFailure() {
        assertTrue(TurnSignalController.shouldRetryStockAvm(false, false, false));
        assertFalse(TurnSignalController.shouldRetryStockAvm(false, true, false));
        assertFalse(TurnSignalController.shouldRetryStockAvm(true, false, false));
        assertFalse(TurnSignalController.shouldRetryStockAvm(false, false, true));
        assertFalse(TurnSignalController.shouldRetryStockAvm(false, false, false, true));
        assertTrue(TurnSignalController.shouldRetryStockAvm(false, false, false, false));
    }

    @Test
    public void canceledBlockingOpenCannotPublishAndReleasesReturnedCopy() throws Exception {
        Object requestLock = new Object();
        AtomicBoolean canceled = new AtomicBoolean();
        AtomicInteger released = new AtomicInteger();
        AtomicInteger published = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        Thread blockedOpen = new Thread(() -> {
            entered.countDown();
            try {
                unblock.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
            TurnSignalController.publishStockAvmInputGate(
                    requestLock, canceled, false,
                    released::incrementAndGet, published::incrementAndGet);
        });
        blockedOpen.start();
        assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));
        canceled.set(true);
        unblock.countDown();
        blockedOpen.join(1_000);
        assertFalse(TurnSignalController.stockAvmCanContinue(!canceled.get(), false));
        assertEquals(0, published.get());
        assertEquals(1, released.get());

        boolean accepted = TurnSignalController.publishStockAvmInputGate(
                requestLock, canceled, false,
                released::incrementAndGet, published::incrementAndGet);
        assertFalse(accepted);
        assertEquals(0, published.get());
        assertEquals(2, released.get());
    }

    @Test
    public void firstAttemptCallbackBeforeReplyIsSuppressedButTerminalAttemptIsNot() {
        assertTrue(TurnSignalController.shouldSuppressStockAvmError(
                true, 17, 1, 17, 1));
        // A late first-attempt callback can arrive after reset starts; request/attempt
        // identity keeps it from clearing the still-pending request.
        assertTrue(TurnSignalController.shouldSuppressStockAvmError(
                true, 17, 2, 17, 1));
        assertFalse(TurnSignalController.shouldSuppressStockAvmError(
                true, 17, 2, 17, 2));
        assertFalse(TurnSignalController.shouldSuppressStockAvmError(
                true, 17, 2, 18, 1));
    }

    @Test
    public void binderPingDoesNotDefineDisplayReadiness() {
        assertFalse(StockAvmPreview.isDisplayReadyStatus("Initialized"));
        assertTrue(StockAvmPreview.isDisplayReadyStatus("Configured"));
        assertTrue(StockAvmPreview.isDisplayReadyStatus("Started"));
    }

    @Test
    public void stockAvmGroupPredicateDoesNotMatchDirectOrOverlayGroups() {
        CameraHelperMain.HelperBinder.ConsumerGroup stock =
                new CameraHelperMain.HelperBinder.ConsumerGroup(CameraHelperMain.CAMERA_OWNER_ACTIVITY);
        stock.set(new android.view.Surface[]{null}, new int[]{0}, 1, "stock_avm_input",
                false, true, true, false);
        CameraHelperMain.HelperBinder.ConsumerGroup direct =
                new CameraHelperMain.HelperBinder.ConsumerGroup(CameraHelperMain.CAMERA_OWNER_ACTIVITY);
        direct.set(new android.view.Surface[]{null}, new int[]{0}, 2, "direct_pano_h_index_0",
                false, false, true, true);
        assertTrue(CameraHelperMain.HelperBinder.PersistentSession.isStockAvmGroup(stock));
        assertFalse(CameraHelperMain.HelperBinder.PersistentSession.isStockAvmGroup(direct));
    }
}

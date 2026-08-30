package com.byd.extend;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class StockAvmShellProtocolTest {
    @Test
    public void endpointIsolatedFromCameraOverlayShell() {
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

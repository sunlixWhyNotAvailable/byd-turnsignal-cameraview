package com.byd.extend;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class StockAvmRequestLifecycleTest {
    @Test
    public void deliveredInputRetainsIdentityForCapturedRequest63Close() {
        StockAvmRequestState state = new StockAvmRequestState();
        state.begin(63);
        assertTrue(state.takeInput(63));
        assertEquals(0, state.pendingId());
        assertTrue(state.isActive());
        assertTrue(state.matches(63));
        assertEquals(63, state.id());
        state.clear();
        assertFalse(state.isActive());
        assertFalse(state.matches(63));
    }

    @Test
    public void canceledAndDuplicateInputsCannotReacquireOwnership() {
        StockAvmRequestState state = new StockAvmRequestState();
        state.begin(7);
        assertTrue(state.takeInput(7));
        assertFalse(state.takeInput(7));
        state.clear();
        assertFalse(state.takeInput(7));
        state.begin(8);
        assertFalse(state.takeInput(7));
        assertFalse(state.matches(7));
        assertEquals(8, state.pendingId());
        assertTrue(state.takeInput(8));
    }

    @Test
    public void closeIdentityAlsoExistsBeforeInputAndAfterReplacement() {
        StockAvmRequestState state = new StockAvmRequestState();
        state.begin(11);
        assertTrue(state.matches(11));
        state.begin(12);
        assertFalse(state.matches(11));
        assertTrue(state.matches(12));
        assertFalse(state.matches(0));
        assertFalse(state.matches(-1));
    }

    @Test
    public void otherCameraTerminalWithSameNumberCannotRetireStockSession() throws Exception {
        JSONObject event = new JSONObject().put("kind", "camera_closed")
                .put("source", "camera_shell_helper").put("request_id", 63)
                .put("camera_owner", "reverse");
        assertFalse(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 63));
        event.put("renderer", "stock_avm_shell").put("source", "stock_avm_shell");
        assertTrue(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 63));
        assertFalse(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 64));
        assertFalse(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 0));
        event.put("kind", "camera_error");
        assertTrue(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 63));
        event.put("kind", "camera_opened");
        assertFalse(CameraHelperMain.HelperBinder.isCurrentStockTerminal(event, 63));
    }
}

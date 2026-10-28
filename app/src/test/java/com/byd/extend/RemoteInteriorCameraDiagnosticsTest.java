package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteInteriorCameraDiagnosticsTest {
    @Test public void knownNativeFailureEventRemainsOrdinary() {
        assertTrue(RemoteInteriorCameraSources.isNativeFailureEvent("onEvent", new Object[]{null, 1008}));
        assertFalse(RemoteInteriorCameraSources.isNativeFailureEvent("onEvent", new Object[]{null, 1007}));
        assertFalse(RemoteInteriorCameraSources.isNativeFailureEvent("onPreview", new Object[]{null, 1008}));
    }
}

package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

public class CameraViewingShellProtocolTest {
    @Test public void diagnosticsSettingIsAllowedOnRequestContract() {
        assertTrue(CameraViewingShellProtocol.isOperationAllowed("diagnostics"));
    }
}

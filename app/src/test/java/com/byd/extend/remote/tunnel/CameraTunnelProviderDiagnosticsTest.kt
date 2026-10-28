package com.byd.extend.remote.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTunnelProviderDiagnosticsTest {
    @Test fun successfulPeriodicBodiesRequireExtendedLogging() {
        val result = CameraTunnelResult("starting", diagnostic = "native detail")
        assertFalse(shouldLogNativeDiagnosticBody("status", result, false))
        assertFalse(shouldLogNativeDiagnosticBody("serve", result, false))
        assertTrue(shouldLogNativeDiagnosticBody("status", result, true))
        assertTrue(shouldLogNativeDiagnosticBody("authorize", result, false))
    }

    @Test fun everyNativeFailureKeepsItsDiagnosticBody() {
        val failures = listOf(
            CameraTunnelResult("error", diagnostic = "state failure"),
            CameraTunnelResult("starting", errorCode = "native_error", diagnostic = "code failure"),
            CameraTunnelResult("starting", errorDetail = "native detail failure", diagnostic = "detail failure"),
            CameraTunnelResult("starting", networkFailure = true, diagnostic = "network failure"),
        )
        for (failure in failures) assertTrue(shouldLogNativeDiagnosticBody("status", failure, false))
    }
}

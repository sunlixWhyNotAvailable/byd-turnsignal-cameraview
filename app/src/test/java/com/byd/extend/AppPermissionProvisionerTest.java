package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class AppPermissionProvisionerTest {
    @Test public void accessibilityAppendPreservesOtherComponentsAndAvoidsDuplicates() {
        String other = "com.example/.Listener";
        assertEquals(other + ":" + WeatherAccessibilitySettings.SERVICE_COMPONENT,
                AppPermissionProvisioner.appendAccessibility(other));
        String present = other + ":" + WeatherAccessibilitySettings.SERVICE_COMPONENT;
        assertEquals(present, AppPermissionProvisioner.appendAccessibility(present));
        assertEquals(WeatherAccessibilitySettings.SERVICE_COMPONENT,
                AppPermissionProvisioner.appendAccessibility(null));
    }

    @Test public void ensureGrantsOwnUserWssThenUsesNativeReadbackBeforeOtherAccess()
            throws Exception {
        String source = source("AppPermissionProvisioner.java");
        String ensure = source.substring(source.indexOf("static synchronized void ensure("),
                source.indexOf("    private static void ensureAccessibility("));
        assertOrdered(ensure,
                "userIdForUid(Process.myUid())",
                "if (!hasWriteSecureSettings(app))",
                "pm grant --user \" + user + \" \" + app.getPackageName()",
                "android.permission.WRITE_SECURE_SETTINGS",
                "\"app_permission_readback\"",
                "hasWriteSecureSettings(app)",
                "AvasNotificationAccess.ensureGranted(app",
                "ensureAccessibility(app, user");
        assertTrue(source.contains("context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)"));
    }

    @Test public void provisioningHasNoFeaturePreferenceGate() throws Exception {
        String source = source("AppPermissionProvisioner.java");
        String ensure = source.substring(source.indexOf("static synchronized void ensure("),
                source.indexOf("    private static void ensureAccessibility("));
        assertTrue(!ensure.contains("SharedPreferences"));
        assertTrue(!ensure.contains("adb_recovery_enabled"));
        assertTrue(!ensure.contains("hasEnabledProfiles"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/byd/extend", name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/byd/extend", name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void assertOrdered(String text, String... values) {
        int index = -1;
        for (String value : values) {
            int next = text.indexOf(value, index + 1);
            assertTrue("Missing/out-of-order: " + value, next >= 0);
            index = next;
        }
    }
}

package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    @Test public void ensureUsesActualOwnUserPermissionChecksAndUserScopedRepairs()
            throws Exception {
        String source = source("AppPermissionProvisioner.java");
        String ensure = source.substring(source.indexOf("static synchronized void ensure("),
                source.indexOf("    static boolean runProvisioning("));
        assertOrdered(ensure,
                "userIdForUid(Process.myUid())",
                "runProvisioning(",
                "hasWriteSecureSettings(app)",
                "applyAdbConnectionTimePolicy(app",
                "pm grant --user \" + user + \" \" + app.getPackageName()",
                "android.permission.WRITE_SECURE_SETTINGS",
                "AvasNotificationAccess.ensureGranted(app",
                "ensureAccessibility(app, user",
                "\"app_permission_readback\"");
        assertTrue(source.contains("context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)"));
        assertTrue(source.contains("Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1"));
        assertTrue(source.contains("put secure accessibility_enabled 1"));
    }

    @Test public void provisioningHasNoFeaturePreferenceGate() throws Exception {
        String source = source("AppPermissionProvisioner.java");
        String ensure = source.substring(source.indexOf("static synchronized void ensure("),
                source.indexOf("    private static void ensureAccessibility("));
        assertTrue(!ensure.contains("SharedPreferences"));
        assertTrue(!ensure.contains("adb_recovery_enabled"));
        assertTrue(!ensure.contains("hasEnabledProfiles"));
    }

    @Test public void directPolicyRunsBeforeUnavailableAdbRepairsAndFailuresAreIndependent() {
        List<String> calls = new ArrayList<>();
        boolean granted = AppPermissionProvisioner.runProvisioning(
                () -> true,
                () -> calls.add("global"),
                () -> calls.add("wss-repair"),
                () -> {
                    calls.add("notification");
                    throw new IllegalStateException("adb unavailable");
                },
                () -> calls.add("accessibility"));

        assertTrue(granted);
        assertEquals(List.of("global", "notification", "accessibility"), calls);
        assertFalse(calls.contains("wss-repair"));
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

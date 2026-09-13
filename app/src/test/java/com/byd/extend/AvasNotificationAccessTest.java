package com.byd.extend;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class AvasNotificationAccessTest {
    private static final String FULL =
            "com.byd.extend/com.byd.extend.RecoveryNotificationListener";

    @Test public void requirementNeedsAutoStartAndAnEnabledAutomaticProfile() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        assertFalse(AvasNotificationAccess.required(prefs));
        assertFalse(prefs.contains(AvasAudioLibrary.PREF_CONFIG));

        prefs.edit().putString(AvasAudioLibrary.PREF_CONFIG, config(false).toJson()).apply();
        assertFalse(AvasNotificationAccess.required(prefs));
        prefs.edit().putString(AvasAudioLibrary.PREF_CONFIG, config(true).toJson()).apply();
        assertTrue(AvasNotificationAccess.required(prefs));
        prefs.edit().putBoolean(GuardRecovery.KEY_AUTO_START, false).apply();
        assertFalse(AvasNotificationAccess.required(prefs));
    }

    @Test public void malformedStoredConfigDoesNotMaterializeOrEnableRequirement() {
        TestSharedPreferences prefs = new TestSharedPreferences();
        prefs.edit().putString(AvasAudioLibrary.PREF_CONFIG, "bad").apply();
        assertFalse(AvasNotificationAccess.hasEnabledProfiles(prefs));
        assertEquals("bad", prefs.getString(AvasAudioLibrary.PREF_CONFIG, null));
    }

    @Test public void secureFallbackMatchesOnlyExactFullOrShortOwnComponent() {
        assertTrue(AvasNotificationAccess.exactComponentEnabled(FULL, FULL));
        assertTrue(AvasNotificationAccess.exactComponentEnabled(
                "foreign.pkg/.Listener:com.byd.extend/.RecoveryNotificationListener", FULL));
        assertFalse(AvasNotificationAccess.exactComponentEnabled(
                "com.byd.extend/.RecoveryNotificationListenerExtra", FULL));
        assertFalse(AvasNotificationAccess.exactComponentEnabled(
                "foreign.pkg/.RecoveryNotificationListener", FULL));
    }

    @Test public void grantCommandTargetsExactComponentAndCurrentUserArgument() {
        assertEquals(10, AvasNotificationAccess.userIdForUid(1_012_345));
        assertEquals(0, AvasNotificationAccess.userIdForUid(12_345));
        assertEquals("cmd notification allow_listener " + FULL + " 10",
                AvasNotificationAccess.grantCommand(10, FULL));
        assertFalse(AvasNotificationAccess.grantCommand(10, FULL).endsWith(" 0"));
    }

    @Test public void api27UsesFrameworkReadbackAndApi26UsesExactSecureFallback() {
        assertTrue(AvasNotificationAccess.grantedForApi(27, true, null, FULL));
        assertFalse(AvasNotificationAccess.grantedForApi(27, false, FULL, FULL));
        assertTrue(AvasNotificationAccess.grantedForApi(
                26, false, "com.byd.extend/.RecoveryNotificationListener", FULL));
        assertFalse(AvasNotificationAccess.grantedForApi(
                26, true, "foreign.pkg/.Listener", FULL));
    }

    @Test public void alreadyGrantedAndNotRequiredNeverUseAdb() {
        AtomicInteger commands = new AtomicInteger();
        assertTrue(AvasNotificationAccess.ensureGranted(true, 0, FULL, "test", null,
                () -> true, command -> {
                    commands.incrementAndGet();
                    return LocalAdbClient.Result.failed("unexpected", "", 1, "");
                }));
        assertTrue(AvasNotificationAccess.ensureGranted(false, 0, FULL, "test", null,
                () -> false, command -> {
                    commands.incrementAndGet();
                    return LocalAdbClient.Result.failed("unexpected", "", 1, "");
                }));
        assertEquals(0, commands.get());
    }

    @Test public void successfulShellExitWithoutPositiveReadbackIsNotGranted() {
        AtomicInteger reads = new AtomicInteger();
        List<String> events = new ArrayList<>();
        boolean granted = AvasNotificationAccess.ensureGranted(true, 0, FULL, "test",
                (name, fields) -> events.add(name),
                () -> {
                    reads.incrementAndGet();
                    return false;
                },
                command -> LocalAdbClient.Result.ok("", 0, "fingerprint", false));
        assertFalse(granted);
        assertEquals(2, reads.get());
        assertTrue(events.contains("avas_notification_access_requested"));
        assertTrue(events.contains("avas_notification_access_readback"));
    }

    @Test public void commandFailureStillAcceptsAuthoritativeConcurrentReadback() {
        AtomicInteger reads = new AtomicInteger();
        boolean granted = AvasNotificationAccess.ensureGranted(true, 3, FULL, "test", null,
                () -> reads.getAndIncrement() > 0,
                command -> LocalAdbClient.Result.failed("rejected", "", 1, ""));
        assertTrue(granted);
        assertEquals(2, reads.get());
    }

    @Test public void unavailableReadbackNeverClaimsGrant() {
        AtomicInteger commands = new AtomicInteger();
        boolean granted = AvasNotificationAccess.ensureGranted(true, 0, FULL, "test", null,
                () -> { throw new IllegalStateException("unavailable"); },
                command -> {
                    commands.incrementAndGet();
                    return LocalAdbClient.Result.ok("", 0, "fingerprint", false);
                });
        assertFalse(granted);
        assertEquals(1, commands.get());
    }

    @Test public void commandExceptionCannotAbortAndStillRequiresReadback() {
        AtomicInteger reads = new AtomicInteger();
        boolean granted = AvasNotificationAccess.ensureGranted(true, 0, FULL, "test", null,
                () -> reads.getAndIncrement() > 0,
                command -> { throw new IllegalStateException("ADB unavailable"); });
        assertTrue(granted);
        assertEquals(2, reads.get());
    }

    private static AvasConfig config(boolean enabled) {
        AvasConfig config = AvasConfig.empty();
        AvasConfig.Profile lock = config.profile("lock");
        return config.withProfile(lock.withSettings(enabled, false, 15, ""));
    }
}

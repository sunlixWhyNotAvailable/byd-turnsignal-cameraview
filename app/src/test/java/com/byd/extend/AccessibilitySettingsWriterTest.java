package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public final class AccessibilitySettingsWriterTest {
    private static final String OTHER = "com.example/.Other";
    private static final String LATE = "com.late/.Service";

    @Test public void ordinaryProvisioningDoesNotRewriteExistingEntry() {
        FakeAccess access = new FakeAccess(OTHER + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT);

        AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                access, false, () -> false, () -> true, event -> {});

        assertTrue(result.ok);
        assertEquals(0, access.writes.size());
        assertEquals(1, access.enableCalls);
    }

    @Test public void forcedRebindReadsFreshListAfterPauseAndPreservesLateEntry() {
        FakeAccess access = new FakeAccess(OTHER + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT);

        AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                access, true, () -> false, () -> {
                    if (access.writes.size() == 1) access.services = OTHER + ":" + LATE;
                    return true;
                }, event -> {});

        assertTrue(result.ok);
        assertEquals(2, access.writes.size());
        assertEquals(OTHER, access.writes.get(0));
        assertEquals(OTHER + ":" + LATE + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT, access.writes.get(1));
    }

    @Test public void cancellationAfterRemovalRestoresOwnEntryFromFreshList() {
        FakeAccess access = new FakeAccess(OTHER + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT);
        AtomicBoolean cancelled = new AtomicBoolean();

        AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                access, true, cancelled::get, () -> {
                    access.services = OTHER + ":" + LATE;
                    cancelled.set(true);
                    return true;
                }, event -> {});

        assertTrue(result.cancelled);
        assertFalse(result.ok);
        assertEquals(OTHER + ":" + LATE + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT, access.services);
    }

    @Test public void sharedLockPreventsOrdinaryProvisioningDuringTemporaryRemoval()
            throws Exception {
        FakeAccess access = new FakeAccess(OTHER + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT);
        CountDownLatch removed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread rebind = new Thread(() -> AccessibilitySettingsWriter.ensureEnabled(
                access, true, () -> false, () -> {
                    if (access.writes.size() == 1) {
                        removed.countDown();
                        await(release);
                    }
                    return true;
                }, event -> {}));
        Thread ordinary = new Thread(() -> AccessibilitySettingsWriter.ensureEnabled(
                access, false, () -> false, () -> true, event -> {}));

        rebind.start();
        assertTrue(removed.await(1, TimeUnit.SECONDS));
        ordinary.start();
        Thread.sleep(30);
        assertEquals(1, access.writes.size());
        release.countDown();
        rebind.join(1_000);
        ordinary.join(1_000);

        assertFalse(rebind.isAlive());
        assertFalse(ordinary.isAlive());
        assertEquals(2, access.writes.size());
        assertTrue(WeatherAccessibilitySettings.hasOwnService(access.services));
        assertTrue(access.services.contains(OTHER));
    }

    @Test public void failedCancellationRestoreIsReportedOnce() {
        FakeAccess access = new FakeAccess(WeatherAccessibilitySettings.SERVICE_COMPONENT);
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> events = new ArrayList<>();
        access.failWriteNumber = 2;

        AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                access, true, cancelled::get, () -> {
            cancelled.set(true);
            return true;
        }, events::add);

        assertEquals(List.of("weather_accessibility_restore_failed"), events);
        assertEquals(2, access.writeAttempts);
        assertFalse(result.listed);
    }

    @Test public void readFailureAfterRemovalRestoresOwnEntryAndRethrowsOriginal() {
        FakeAccess access = new FakeAccess(OTHER + ":"
                + WeatherAccessibilitySettings.SERVICE_COMPONENT);
        access.throwReadNumber = 2;

        try {
            AccessibilitySettingsWriter.ensureEnabled(access, true, () -> false,
                    () -> true, event -> {});
        } catch (IllegalStateException expected) {
            assertEquals("read-2", expected.getMessage());
            assertTrue(WeatherAccessibilitySettings.hasOwnService(access.services));
            assertTrue(access.services.contains(OTHER));
            return;
        }
        throw new AssertionError("Expected original read failure");
    }

    @Test public void successfulRestoreCommandWithoutReadbackIsReportedFailed() {
        FakeAccess access = new FakeAccess(WeatherAccessibilitySettings.SERVICE_COMPONENT);
        AtomicBoolean cancelled = new AtomicBoolean();
        List<String> events = new ArrayList<>();
        access.writeWithoutEffectNumber = 2;

        AccessibilitySettingsWriter.Result result = AccessibilitySettingsWriter.ensureEnabled(
                access, true, cancelled::get, () -> {
                    cancelled.set(true);
                    return true;
                }, events::add);

        assertFalse(result.listed);
        assertEquals(List.of("weather_accessibility_restore_failed"), events);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeAccess implements AccessibilitySettingsWriter.Access {
        volatile String services;
        final List<String> writes = new CopyOnWriteArrayList<>();
        int enableCalls;
        int writeAttempts;
        int readAttempts;
        int failWriteNumber = -1;
        int writeWithoutEffectNumber = -1;
        int throwReadNumber = -1;

        FakeAccess(String services) { this.services = services; }

        @Override public String readServices() {
            readAttempts++;
            if (readAttempts == throwReadNumber) {
                throw new IllegalStateException("read-" + readAttempts);
            }
            return services;
        }

        @Override public boolean writeServices(String value) {
            writeAttempts++;
            if (writeAttempts == failWriteNumber) return false;
            if (writeAttempts == writeWithoutEffectNumber) return true;
            services = value;
            writes.add(value);
            return true;
        }

        @Override public boolean enableAccessibility() {
            enableCalls++;
            return true;
        }
    }
}

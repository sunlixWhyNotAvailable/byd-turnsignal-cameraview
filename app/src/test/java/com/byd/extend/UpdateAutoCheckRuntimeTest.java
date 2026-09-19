package com.byd.extend;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class UpdateAutoCheckRuntimeTest {
    @Test public void backgroundProcessStartsExactlyOneCheckAtThirtySeconds() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(29_999);
        assertEquals(0, f.attempts);
        f.advance(30_000);
        assertEquals(1, f.attempts);
        assertNull(f.queued);
        f.advance(90_000);
        f.runtime.refresh();
        assertNull(f.queued);
        assertEquals(1, f.attempts);
    }

    @Test public void foregroundEntriesDoNotMoveOrDuplicateTheDeadline() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(10_000);
        f.runtime.resume();
        f.runtime.resume();
        assertEquals(30_000, f.deadline);
        // Pausing/stopping/destroying an Activity has no timer operation (wiring test below).
        f.advance(30_000);
        assertEquals(1, f.attempts);
        f.runtime.resume();
        assertNull(f.queued);
    }

    @Test public void disablingCancelsAndReenablingUsesOnlyRemainingTime() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(10_000);
        f.enable(false);
        assertNull(f.queued);
        f.advance(20_000);
        f.enable(true);
        assertEquals(30_000, f.deadline);
        f.advance(30_000);
        assertEquals(1, f.attempts);
    }

    @Test public void enablingAfterDeadlineRunsImmediatelyButOnlyOnce() {
        Fixture f = new Fixture();
        f.enable(false);
        f.advance(45_000);
        assertEquals(0, f.attempts);
        f.enable(true);
        assertEquals(45_000, f.deadline);
        f.advance(45_000);
        f.enable(false);
        f.enable(true);
        assertEquals(1, f.attempts);
        assertNull(f.queued);
    }

    @Test public void disabledValuePreventsAnAlreadyDequeuedCallbackFromRunning() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.enabled = false; // SharedPreferences changed before its posted listener runs.
        f.advance(30_000);
        assertEquals(0, f.attempts);
        f.enable(true);
        f.advance(30_000);
        assertEquals(1, f.attempts);
    }

    @Test public void shutdownCancelsAndResumePreservesUnusedDeadline() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(10_000);
        f.runtime.shutdown();
        assertNull(f.queued);
        f.enable(false);
        f.enable(true); // Settings changes cannot undo explicit shutdown.
        assertNull(f.queued);
        f.advance(20_000);
        f.runtime.resume();
        assertEquals(30_000, f.deadline);
        f.advance(30_000);
        assertEquals(1, f.attempts);
        f.runtime.shutdown();
        f.runtime.resume();
        assertNull(f.queued);
    }

    @Test public void reopeningAfterShutdownPastDeadlineRunsImmediately() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(5_000);
        f.runtime.shutdown();
        f.advance(50_000);
        f.runtime.run(); // A stale callback is harmless while shut down.
        assertEquals(0, f.attempts);
        f.runtime.resume();
        f.advance(50_000);
        assertEquals(1, f.attempts);
    }

    @Test public void busyOrThrottledAttemptIsNotRescheduled() {
        Fixture f = new Fixture();
        // Runtime's check may decline a busy/download request or return a throttled result.
        // The callback is still consumed; there is no second automatic attempt.
        f.runtime.refresh();
        f.advance(30_000);
        f.runtime.refresh();
        f.runtime.resume();
        f.runtime.run();
        assertEquals(1, f.attempts);
        assertNull(f.queued);
    }

    @Test public void earlyCallbackRequeuesOnlyTheRemainingTime() {
        Fixture f = new Fixture();
        f.runtime.refresh();
        f.advance(1_000);
        f.runtime.run();
        assertEquals(30_000, f.deadline);
        assertEquals(0, f.attempts);
        f.advance(30_000);
        assertEquals(1, f.attempts);
    }

    @Test public void productionWiresQueueToProcessNotActivityAndPreservesBusyAndShutdownGates()
            throws Exception {
        String application = source("TurnSignalGuardApplication.java");
        String runtime = source("UpdateHintRuntime.java");
        String activity = source("CameraProbeActivity.java");
        assertTrue(application.indexOf("UpdateAutoCheckRuntime.onProcessStarted()")
                < application.indexOf("UpdateHintRuntime.get(this)"));
        assertTrue(runtime.contains("new UpdateAutoCheckRuntime(main::postDelayed, main::removeCallbacks"));
        assertTrue(runtime.contains("() -> preferences.getBoolean(PREF_AUTO_CHECK, true), () -> check(false)"));
        assertTrue(runtime.contains("if (PREF_AUTO_CHECK.equals(key)) autoCheck.refresh()"));
        assertTrue(runtime.contains("autoCheck.refresh();\n    }")); // Constructor arms without Activity.
        assertFalse(activity.contains("runStartupUpdateCheck"));
        assertFalse(activity.contains("scheduleStartupUpdateCheck"));
        assertFalse(activity.contains("UpdateAutoCheckRuntime"));
        assertTrue(runtime.contains("if (checking || downloading || shutdown) return false"));
        assertTrue(runtime.contains("autoCheck.shutdown()"));
        assertTrue(runtime.contains("autoCheck.resume()"));
        assertTrue(runtime.contains("if (ticket != generation || shutdown) {"));
        assertTrue(runtime.contains("startedObserver.onCheckStarted(force)"));
        assertTrue(activity.contains("onUpdateCheckStarted(force)"));
        assertTrue(activity.contains("if (updateCheckInFlight) showUpdateCheckProgress()"));
        assertTrue(activity.contains("setDownloadInFlight(true)"));
        assertTrue(activity.contains("setDownloadInFlight(false)"));
        assertEquals(2, activity.split("dismissUpdateProgress\\(\\);", -1).length - 1);
        String visibilityCallbacks = runtime.substring(runtime.indexOf("@Override public void onActivityStopped"));
        assertFalse(visibilityCallbacks.contains("autoCheck"));
    }

    @Test public void staleCompletionReleasesReopenedUiWithoutPublishingItsResult() throws Exception {
        String runtime = source("UpdateHintRuntime.java");
        String completion = runtime.substring(runtime.indexOf("checking = false;"),
                runtime.indexOf("if (error == null && completed != null)"));
        assertTrue(completion.contains("if (ticket != generation || shutdown) {"));
        assertTrue(completion.contains("observer.onCheckDiscarded();\n                    return;"));
        assertFalse(completion.contains("onCheckFinished("));
        String activity = source("CameraProbeActivity.java");
        String discarded = activity.substring(activity.indexOf("@Override public void onCheckDiscarded()"),
                activity.indexOf("@Override public void onCheckFinished("));
        assertTrue(discarded.contains("updateCheckInFlight = false;"));
        assertTrue(discarded.contains("SettingsOperation.Update, \"\", StatusTone.Neutral, false"));
        assertTrue(discarded.contains("restoreUpdateButton()"));
        assertFalse(discarded.contains("showCachedUpdateIfAvailable"));
    }

    private static String source(String file) throws Exception {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/byd/extend", file)),
                StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
    }

    /** A single controllable Handler slot, running the same Runnable as Production. */
    private static final class Fixture {
        long now;
        long deadline;
        boolean enabled = true;
        int attempts;
        Runnable queued;
        final UpdateAutoCheckRuntime runtime;

        Fixture() {
            UpdateAutoCheckRuntime.Scheduler scheduler =
                    new UpdateAutoCheckRuntime.Scheduler(30_000);
            scheduler.start(now);
            runtime = new UpdateAutoCheckRuntime(scheduler, () -> now,
                    (callback, delay) -> {
                        assertNull("must cancel the existing callback before posting", queued);
                        queued = callback;
                        deadline = now + delay;
                    }, callback -> { if (queued == callback) queued = null; },
                    () -> enabled, () -> attempts++);
        }

        void enable(boolean value) {
            enabled = value;
            runtime.refresh();
        }

        void advance(long time) {
            now = time;
            if (queued != null && deadline <= now) {
                Runnable callback = queued;
                queued = null;
                callback.run();
            }
        }
    }
}

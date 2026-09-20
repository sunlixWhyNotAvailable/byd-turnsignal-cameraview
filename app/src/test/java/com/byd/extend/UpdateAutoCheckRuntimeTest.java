package com.byd.extend;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class UpdateAutoCheckRuntimeTest {
    private static final String QUICK = "android.intent.action.QUICKBOOT_POWERON";

    @Test public void startupAgesWithoutActivityAndSuccessEndsCycle() {
        Fixture f = new Fixture(10_000);
        assertEquals(30_000, f.deadline);
        f.runtime.entry(true);
        f.advance(29_999);
        assertEquals(0, f.requests.size());
        f.advance(30_000);
        assertEquals(1, f.requests.size());
        assertTrue(f.done(true));
        f.advance(900_000);
        f.runtime.entry(true);
        f.runtime.refresh();
        assertNull(f.queued);
        assertEquals(1, f.requests.size());
    }

    @Test public void failedChecksRetryFromCompletionTimeAndCapAtFiveMinutes() {
        Fixture f = new Fixture();
        f.advance(30_000);
        for (long delay : new long[]{30_000, 60_000, 120_000, 300_000, 300_000}) {
            int count = f.requests.size();
            f.advance(f.now + 25_000); // Slow HTTP must not consume any of the retry interval.
            assertTrue(f.done(false));
            assertEquals(f.now + delay, f.deadline);
            f.runtime.entry(true);
            f.advance(f.now + delay - 1);
            assertEquals(count, f.requests.size());
            f.advance(f.now + 1);
            assertEquals(count + 1, f.requests.size());
        }
        assertTrue(f.done(true)); // Both Available and UpToDate are successful at the boundary.
        assertNull(f.queued);
        assertTrue(f.events.stream().anyMatch(s -> s.contains("delay_ms=300000")));
    }

    @Test public void disablingCancelsAndReenablingStartsNewThirtySecondCycle() {
        Fixture f = new Fixture();
        f.advance(10_000);
        f.enable(false);
        assertNull(f.queued);
        f.advance(90_000);
        f.enable(true);
        assertEquals(120_000, f.deadline);
        f.advance(120_000);
        f.done(true);
        f.enable(false);
        f.enable(true);
        assertEquals(150_000, f.deadline);
    }

    @Test public void preferenceOffFencesDequeuedCallbacksAndPendingResults() {
        Fixture f = new Fixture();
        Runnable queued = f.queued;
        f.enabled = false; // Listener has not run yet.
        f.now = 30_000;
        queued.run();
        assertEquals(0, f.requests.size());
        f.enable(true);
        f.advance(60_000);
        f.enabled = false;
        assertFalse(f.done(true));
        assertNull(f.queued);
    }

    @Test public void staleFailureCannotReplaceReenabledCyclesDeadline() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.enable(false);
        f.advance(40_000);
        f.enable(true);
        assertFalse(f.done(false));
        assertEquals(70_000, f.deadline);
        f.advance(70_000);
        assertEquals(2, f.requests.size());
    }

    @Test public void immediateOffOnPairInvalidatesOldSuccessAndKeepsNewCycle() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.enable(false);
        f.enable(true);
        assertFalse(f.done(true));
        assertEquals(60_000, f.deadline);
        f.advance(60_000);
        assertEquals(2, f.requests.size());
    }

    @Test public void manualBeforeTimerStartsImmediatelyAndCancelsAutomaticWait() {
        Fixture f = new Fixture();
        f.advance(5_000);
        assertTrue(f.runtime.requestManual());
        assertTrue(f.running.manual);
        assertNull(f.queued);
        f.done(true);
        f.advance(30_000);
        assertEquals(1, f.requests.size());
    }

    @Test public void manualAdoptsActiveAutomaticRequestWithoutSecondFetch() {
        Fixture f = new Fixture();
        f.advance(30_000);
        UpdateAutoCheckRuntime.Request first = f.running;
        assertTrue(f.runtime.requestManual());
        assertSame(first, f.running);
        assertTrue(first.manual);
        assertEquals(1, f.requests.size());
        f.advance(50_000);
        f.done(false);
        assertEquals(80_000, f.deadline);
    }

    @Test public void manualDuringRetryCancelsTimerAndSuccessStopsRetries() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.done(false);
        f.advance(35_000);
        assertTrue(f.runtime.requestManual());
        assertEquals(2, f.requests.size());
        f.done(true);
        f.advance(600_000);
        assertEquals(2, f.requests.size());
    }

    @Test public void disabledAutomaticStillAllowsManualButNeverRetriesItsFailure() {
        Fixture f = new Fixture();
        f.enable(false);
        assertTrue(f.runtime.requestManual());
        f.done(false);
        assertNull(f.queued);
    }

    @Test public void sleepCancelsAutomaticWorkAndWakeResetsBackoff() {
        Fixture f = new Fixture();
        f.advance(30_000);
        for (int i = 0; i < 4; i++) {
            f.done(false);
            f.advance(f.deadline);
        }
        f.runtime.sleep();
        assertFalse(f.done(false));
        assertNull(f.queued);
        f.advance(f.now + 600_000);
        f.runtime.wake("screen_on");
        assertEquals(f.now + 30_000, f.deadline);
        f.advance(f.deadline);
        f.done(false);
        assertEquals(f.now + 30_000, f.deadline);
    }

    @Test public void wakeAfterSuccessChecksAgainAndIgnoresDuplicateWakeBurst() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.done(true);
        f.runtime.sleep();
        f.advance(100_000);
        f.runtime.wake("screen_on");
        f.advance(105_000);
        f.runtime.wake("user_present");
        f.runtime.wake(QUICK);
        f.runtime.entry(true);
        assertEquals(130_000, f.deadline);
        f.advance(130_000);
        f.done(true);
        assertEquals(2, f.requests.size());
    }

    @Test public void quickbootWithoutScreenOffStartsNewCycleAfterCoalescingWindow() {
        Fixture f = new Fixture();
        f.advance(20_000);
        f.runtime.wake(QUICK);
        assertEquals(30_000, f.deadline);
        f.advance(30_000);
        f.done(true);
        f.advance(100_000);
        f.runtime.wake(QUICK);
        assertEquals(130_000, f.deadline);
    }

    @Test public void oldAutomaticReplyCannotPublishOrOverlapNextWakeRequest() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.runtime.sleep();
        f.advance(40_000);
        f.runtime.wake("screen_on");
        f.advance(90_000); // New deadline elapsed but old HTTP still owns the executor slot.
        assertEquals(1, f.requests.size());
        assertFalse(f.done(true));
        assertEquals(90_000, f.deadline);
        f.advance(90_000);
        assertEquals(2, f.requests.size());
        f.done(true);
    }

    @Test public void activeManualRequestSuppliesWakeResultAndFailureStartsFreshBackoff() {
        Fixture f = new Fixture();
        f.runtime.requestManual();
        f.runtime.sleep();
        f.advance(100_000);
        f.runtime.wake("screen_on");
        assertNull(f.queued);
        assertTrue(f.done(false));
        assertEquals(130_000, f.deadline);
        assertEquals(1, f.requests.size());
    }

    @Test public void manualWhileOldInvalidHttpDrainsQueuesOnlyOneNewRequest() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.runtime.sleep();
        f.runtime.wake("screen_on");
        assertTrue(f.runtime.requestManual());
        assertTrue(f.runtime.requestManual());
        assertEquals(1, f.requests.size());
        assertFalse(f.done(false));
        f.advance(f.now);
        assertEquals(2, f.requests.size());
        assertTrue(f.running.manual);
        f.done(true);
        assertNull(f.queued);
    }

    @Test public void downloadPreservesDueAttemptWithoutPollingOrCountingFailure() {
        Fixture f = new Fixture();
        f.runtime.setDownloading(true);
        assertNull(f.queued);
        f.advance(100_000);
        assertFalse(f.runtime.requestManual());
        assertEquals(0, f.requests.size());
        f.runtime.setDownloading(false);
        assertEquals(100_000, f.deadline);
        f.advance(100_000);
        f.done(false);
        assertEquals(130_000, f.deadline);
    }

    @Test public void downloadCompletionBeforeDeadlineKeepsRemainingWait() {
        Fixture f = new Fixture();
        f.runtime.setDownloading(true);
        f.advance(10_000);
        f.runtime.setDownloading(false);
        assertEquals(30_000, f.deadline);
    }

    @Test public void sleepAndDisableCannotBeUndoneByDownloadCompletion() {
        Fixture f = new Fixture();
        f.runtime.setDownloading(true);
        f.runtime.sleep();
        f.runtime.setDownloading(false);
        assertNull(f.queued);
        f.enable(false);
        f.runtime.wake("screen_on");
        assertNull(f.queued);
    }

    @Test public void shutdownFencesManualResultAndWakeCannotReviveIt() {
        Fixture f = new Fixture();
        f.runtime.requestManual();
        f.runtime.shutdown();
        f.advance(100_000);
        f.runtime.wake(QUICK);
        assertFalse(f.done(true));
        assertFalse(f.runtime.requestManual());
        assertNull(f.queued);
        f.runtime.entry(true);
        assertEquals(130_000, f.deadline);
    }

    @Test public void reopeningWhileOldShutdownRequestDrainsKeepsNewCycle() {
        Fixture f = new Fixture();
        f.advance(30_000);
        f.runtime.shutdown();
        f.advance(40_000);
        f.runtime.entry(true);
        assertFalse(f.done(false));
        assertEquals(70_000, f.deadline);
    }

    @Test public void asleepStartupAndNoninteractiveEntryWaitForRealWake() {
        Fixture f = new Fixture();
        f.runtime.sleep(); // Runtime samples PowerManager before arming initial work.
        f.runtime.entry(false);
        f.advance(200_000);
        assertEquals(0, f.requests.size());
        f.runtime.entry(true);
        assertEquals(230_000, f.deadline);
    }

    @Test public void duplicateCompletionCannotReleaseNewerRequest() {
        Fixture f = new Fixture();
        f.runtime.requestManual();
        UpdateAutoCheckRuntime.Request old = f.running;
        f.done(true);
        f.runtime.requestManual();
        assertFalse(f.runtime.complete(old, false));
        assertTrue(f.runtime.isChecking());
        assertNull(f.queued);
        f.done(true);
    }

    @Test public void productionWiresProcessWakeResultAndDownloadBoundaries() throws Exception {
        String app = source("TurnSignalGuardApplication.java");
        String runtime = source("UpdateHintRuntime.java");
        String receiver = source("GuardRecoveryReceiver.java");
        String activity = source("CameraProbeActivity.java");
        String manager = source("AppUpdateManager.java");
        assertTrue(app.indexOf("UpdateAutoCheckRuntime.onProcessStarted()")
                < app.indexOf("UpdateHintRuntime.get(this)"));
        assertTrue(runtime.contains("this::startCheck"));
        assertTrue(runtime.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(runtime.contains("Intent.ACTION_SCREEN_ON"));
        assertTrue(runtime.contains("autoCheck.sleep()"));
        assertTrue(runtime.contains("autoCheck.wake(action)"));
        assertTrue(runtime.contains("autoCheck.entry(interactive())"));
        assertTrue(runtime.contains("autoCheck.setDownloading(value)"));
        assertTrue(runtime.contains("autoCheck.shutdown()"));
        String preference = section(runtime, "settingsListener =", "preferences.register");
        assertTrue(preference.indexOf("autoCheck.refresh()") < preference.indexOf("main.post"));
        assertTrue(receiver.contains("UpdateHintRuntime.onRuntimeWake(context, action)"));
        assertTrue(receiver.indexOf("UpdateHintRuntime.onRuntimeWake")
                < receiver.indexOf("GuardRecovery.shouldRecover"));
        String wake = section(runtime, "private void runtimeWake(", "private boolean interactive()");
        assertTrue(wake.contains("GuardRecovery.isUserShutdownActive"));
        assertFalse(wake.contains("isAutoStartEnabled"));
        assertTrue(runtime.contains("manager.checkForUpdate()"));
        String completion = section(runtime, "main.post(() -> {\n                CheckListener observer",
                "AppUpdateManager.UpdateInfo pendingOffer()");
        assertTrue(completion.indexOf("autoCheck.complete(request")
                < completion.indexOf("presentation.accept("));
        assertTrue(completion.contains("observer.onCheckDiscarded();\n                    return;"));
        assertTrue(completion.contains("error, request.manual"));
        assertFalse(manager.contains("last_check_ms"));
        assertFalse(manager.contains("CHECK_THROTTLE_MS"));
        assertFalse(manager.contains("cachedAvailable"));
        assertFalse(section(manager, "CheckResult checkForUpdate()", "File downloadAndVerify(")
                .contains("System.currentTimeMillis()"));
        assertFalse(activity.contains("UpdateAutoCheckRuntime"));
        assertFalse(section(activity, "private void runUpdateCheck(", "private void onUpdateCheckStarted(")
                .contains("updateCheckInFlight ||"));
        assertTrue(activity.contains("setDownloadInFlight(false)"));
        assertFalse(runtime.substring(runtime.indexOf("@Override public void onActivityStopped"))
                .contains("autoCheck"));
    }

    private static String source(String name) throws Exception {
        return new String(Files.readAllBytes(Path.of("src/main/java/com/byd/extend", name)),
                StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start), to = source.indexOf(end, from);
        assertTrue(start, from >= 0);
        assertTrue(end, to > from);
        return source.substring(from, to);
    }

    private static final class Fixture {
        long now;
        long deadline;
        boolean enabled = true;
        Runnable queued;
        UpdateAutoCheckRuntime.Request running;
        final List<UpdateAutoCheckRuntime.Request> requests = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final UpdateAutoCheckRuntime runtime;

        Fixture() { this(0); }
        Fixture(long initialNow) {
            now = initialNow;
            runtime = new UpdateAutoCheckRuntime(() -> now, 0,
                    (callback, delay) -> {
                        assertNull("one callback only", queued);
                        queued = callback;
                        deadline = now + delay;
                    }, callback -> { if (queued == callback) queued = null; },
                    () -> enabled, request -> {
                        assertNull("HTTP requests must not overlap", running);
                        running = request;
                        requests.add(request);
                    }, events::add);
            runtime.refresh();
        }

        boolean done(boolean success) {
            assertNotNull(running);
            UpdateAutoCheckRuntime.Request finished = running;
            running = null;
            return runtime.complete(finished, success);
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

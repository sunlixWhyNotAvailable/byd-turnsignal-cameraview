package com.byd.turnsignalguard.capture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.view.Surface;

import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PersistentCameraSessionTest {
    @Test
    public void producerBootstrapsKnownGoodSidePairBeforeAnyLogicalConsumer()
            throws Exception {
        Trace calibrationTrace = new Trace();
        CameraHelperMain.HelperBinder.PersistentSession calibration = session();
        calibration.startProducer(new FakeCameraPort(calibrationTrace),
                new FakeFanout(calibrationTrace), calibration.activityGroup,
                surfaces(1), new int[]{2}, 1, "calibration", false, false);

        assertTrue(calibrationTrace.values.indexOf("add:2")
                < calibrationTrace.values.indexOf("add:3"));
        assertTrue(calibrationTrace.values.indexOf("add:3")
                < calibrationTrace.values.indexOf("start"));
        assertTrue(calibrationTrace.values.indexOf("start")
                < calibrationTrace.values.indexOf("target-add:2"));

        Trace reverseTrace = new Trace();
        CameraHelperMain.HelperBinder.PersistentSession reverse = session();
        reverse.startProducer(new FakeCameraPort(reverseTrace),
                new FakeFanout(reverseTrace), reverse.activityGroup,
                surfaces(4), new int[]{0, 1, 2, 3}, 2,
                "reverse_preview_with_stock_base", false, true, true);

        assertTrue(reverseTrace.values.indexOf("add:3")
                < reverseTrace.values.indexOf("start"));
        assertTrue(reverseTrace.values.indexOf("start")
                < reverseTrace.values.indexOf("add:0"));
        assertTrue(reverseTrace.values.indexOf("start")
                < reverseTrace.values.indexOf("add:1"));
    }

    @Test
    public void startupAttachFailureStopsProducerBeforeFanoutRelease() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        camera.removeResult = false;
        FakeFanout fanout = new FakeFanout(trace);
        fanout.failAttachCall = 1;
        CameraHelperMain.HelperBinder.PersistentSession session = session();

        try {
            session.startProducer(camera, fanout, session.activityGroup,
                    surfaces(1), new int[]{2}, 3,
                    "calibration", false, false);
            fail("logical attach should fail");
        } catch (IllegalStateException expected) {
            assertEquals("attach failed", expected.getMessage());
        }
        CameraHelperMain.HelperBinder.closeFailedPersistentProducer(camera, fanout);

        assertTrue(trace.values.indexOf("start")
                < trace.values.indexOf("stop"));
        assertTrue(trace.values.indexOf("stop")
                < trace.values.indexOf("close"));
        assertTrue(trace.values.indexOf("close")
                < trace.values.indexOf("fanout-close"));
    }

    @Test
    public void lifecycleCloseAndReopenNeverReattachesVendorSource() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);

        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(1), new int[]{2}, 11, "camera", false, false);
        session.close(camera, session.activityGroup, "activity_stopped", 11,
                events, new FakeShellClose(trace), 7, 1);

        assertTrue(session.producerOpen);
        assertFalse(session.activityGroup.has());
        assertEquals(0, fanout.activeTargets);
        assertEquals(1, count(trace.values, "add:2"));
        assertEquals(0, count(trace.values, "remove:2"));

        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{2}, 12, "camera", false, false,
                events, new FakeShellClose(trace), 7, 1);

        assertEquals(1, count(trace.values, "source:2"));
        assertEquals(1, count(trace.values, "add:2"));
        assertEquals(0, count(trace.values, "remove:2"));
        assertEquals(2, count(trace.values, "target-add:2"));
        assertEquals(1, fanout.activeTargets);
    }

    @Test
    public void stockAutomaticRouteCoexistsAndSharesOneVendorIngress() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();

        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 21, "rear_left", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{2}, 22, "stock_camera",
                CameraHelperMain.HelperBinder.persistentActivityExclusive(true), true,
                new FakeEventSink(trace, session), new FakeShellClose(trace), 7, 2);

        assertTrue(session.overlayGroup.attached);
        assertTrue(session.activityGroup.attached);
        assertEquals(2, fanout.activeTargets);
        assertEquals(1, count(trace.values, "source:2"));
        assertEquals(1, count(trace.values, "add:2"));
        assertEquals(2, count(trace.values, "target-add:2"));
        assertFalse(session.activityGroup.exclusive);
        assertTrue(CameraHelperMain.HelperBinder.persistentActivityExclusive(false));
    }

    @Test
    public void tabReverseAndManualTransitionsOnlyChangeDownstreamTargets() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        FakeShellClose shell = new FakeShellClose(trace);

        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(1), new int[]{2}, 31, "camera", false, false);
        session.attach(camera, session.reverseGroup,
                surfaces(3), new int[]{1, 2, 3}, 32, "reverse", true, false,
                events, shell, 7, 3);
        session.close(camera, session.reverseGroup, "not_reverse", 32,
                events, shell, 7, 3);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{2}, 33, "calibration", false, false,
                events, shell, 7, 3);
        session.close(camera, session.activityGroup, "user_close", 33,
                events, shell, 7, 3);

        assertEquals(1, count(trace.values, "add:1"));
        assertEquals(1, count(trace.values, "add:2"));
        assertEquals(1, count(trace.values, "add:3"));
        assertEquals(0, countPrefix(trace.values, "remove:"));
        assertEquals(1, count(trace.values, "start"));
        assertEquals(0, count(trace.values, "stop"));
        assertEquals(0, fanout.activeTargets);
        assertTrue(session.producerOpen);
    }

    @Test
    public void reversePreemptionSignalsBeforeActivityClear() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        FakeShellClose shell = new FakeShellClose(trace);

        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(4), new int[]{0, 1, 2, 3}, 41,
                "reverse_preview_with_stock_base", false, true, true);
        session.attach(camera, session.reverseGroup,
                surfaces(3), new int[]{1, 2, 3}, 42,
                "reverse_overlay", true, false,
                events, shell, 7, 4);

        assertEquals(list(true, true), events.activityPresentDuringEvents);
        assertFalse(session.activityGroup.has());
        assertTrue(session.reverseGroup.has());
        assertTrue(session.producerOpen);
        Event terminal = events.byKind("camera_closed");
        assertEquals(41, terminal.field("request_id"));
        assertEquals("replace_with_multi_preview", terminal.field("reason"));
        assertEquals(4, terminal.field("producer_epoch"));
        assertEquals(1, count(trace.values, "add:0"));
        assertEquals(1, count(trace.values, "remove:0"));
        assertEquals(0, count(trace.values, "source:0"));
        assertEquals(0, count(trace.values, "target-add:0"));
        assertTrue(trace.values.contains("shell:replace_with_multi_preview:41"));
    }

    @Test
    public void directInputIsNotRemovedTwiceAfterPartialDetachFailure() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        FakeShellClose shell = new FakeShellClose(trace);

        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(4), new int[]{0, 1, 2, 3}, 43,
                "reverse_preview_with_stock_base", false, true, true);
        fanout.failDetachCall = 1;

        CameraHelperMain.HelperBinder.PersistentSessionFailure failure;
        try {
            session.close(camera, session.activityGroup, "camera_shell_died", 43,
                    events, shell, 7, 4);
            fail("partial detach should fail");
            return;
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            failure = expected;
        }
        assertTrue(failure.fatal);
        session.tearDown(camera, failure.reason, failure.getCause(),
                true, 43, false, shell, events, 4);

        assertEquals(1, count(trace.values, "remove:0"));
    }

    @Test
    public void staleCloseLeavesTargetAndSourceAttached() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(1), new int[]{2}, 52, "camera", false, false);

        CameraHelperMain.HelperBinder.CloseOutcome outcome = session.close(
                camera, session.activityGroup, "user_close", 51,
                new FakeEventSink(trace, session), new FakeShellClose(trace), 7, 5);

        assertEquals(CameraHelperMain.HelperBinder.PersistentCloseDecision.STALE,
                outcome.decision);
        assertTrue(session.activityGroup.attached);
        assertEquals(1, fanout.activeTargets);
        assertEquals(0, countPrefix(trace.values, "remove:"));
    }

    @Test
    public void downstreamAttachFailureDoesNotTearDownIngress() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 61, "overlay", false, false);
        fanout.failAttachCall = 2;

        try {
            session.attach(camera, session.activityGroup,
                    surfaces(1), new int[]{3}, 62, "calibration", true, false,
                    new FakeEventSink(trace, session), new FakeShellClose(trace), 7, 6);
            fail("attach should fail");
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            assertFalse(expected.fatal);
            assertEquals("consumer_attach_failed", expected.reason);
        }

        assertTrue(session.producerOpen);
        assertTrue(session.overlayGroup.attached);
        assertFalse(session.activityGroup.has());
        assertEquals(1, count(trace.values, "add:2"));
        assertEquals(1, count(trace.values, "add:3"));
        assertEquals(0, countPrefix(trace.values, "remove:"));
        assertEquals(0, count(trace.values, "stop"));
        assertEquals(0, count(trace.values, "close"));
    }

    @Test
    public void newIngressFailureLeavesExistingSourceRunning() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 71, "overlay", false, false);
        camera.failAddCall = 3;

        try {
            session.attach(camera, session.activityGroup,
                    surfaces(2), new int[]{1, 3}, 72, "camera", false, false,
                    new FakeEventSink(trace, session), new FakeShellClose(trace), 7, 7);
            fail("source attach should fail");
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            assertTrue(expected.fatal);
            assertEquals("raw_source_attach_failed", expected.reason);
        }

        assertTrue(session.producerOpen);
        assertTrue(session.overlayGroup.attached);
        assertEquals(1, count(trace.values, "add:1"));
        assertEquals(0, count(trace.values, "remove:1"));
        assertEquals(0, count(trace.values, "remove:2"));
        assertEquals(0, count(trace.values, "stop"));

        session.tearDown(camera, "raw_source_attach_failed",
                new IllegalStateException("ownership ambiguous"), false, 0, false,
                new FakeShellClose(trace), new FakeEventSink(trace, session), 7);
        assertEquals(1, count(trace.values, "remove:1"));
        assertEquals(1, count(trace.values, "remove:2"));
        assertEquals(1, count(trace.values, "remove:3"));
        assertEquals(1, count(trace.values, "stop"));
        assertEquals(1, count(trace.values, "close"));
    }

    @Test
    public void downstreamDrawFailureClearsOnlyOwningConsumer() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 76, "rear_left", false, false);

        assertTrue(session.failConsumer(camera, null, 2,
                new IllegalStateException("swap failed"), events,
                new FakeShellClose(trace), 7, 7));

        assertFalse(session.overlayGroup.has());
        assertTrue(session.producerOpen);
        assertEquals(0, fanout.activeTargets);
        assertEquals(0, countPrefix(trace.values, "remove:"));
        assertEquals(0, count(trace.values, "stop"));
        Event error = events.byKind("camera_error");
        assertEquals("raw_fanout_consumer", error.field("stage"));
        assertEquals(CameraHelperMain.CAMERA_OWNER_OVERLAY,
                error.field("camera_owner"));
        assertEquals(76, error.field("request_id"));
        assertEquals("rear_left", error.field("view"));
        assertEquals("consumer_render_failed",
                events.byKind("camera_closed").field("reason"));
    }

    @Test
    public void shellOwnedLogicalCloseQueuesShellButKeepsVendorSource() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        session.startProducer(camera, new FakeFanout(trace), session.activityGroup,
                surfaces(1), new int[]{0}, 81, "stock_avm_input", true, true);

        CameraHelperMain.HelperBinder.CloseOutcome outcome = session.close(
                camera, session.activityGroup, "manual_stop", 81,
                new FakeEventSink(trace, session), new FakeShellClose(trace), 7, 8);

        assertTrue(outcome.shellCloseQueued);
        assertTrue(session.producerOpen);
        assertEquals(0, count(trace.values, "remove:0"));
        assertTrue(trace.values.contains("shell:manual_stop:81"));
    }

    @Test
    public void finalTeardownRemovesEachIngressExactlyOnce() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(2), new int[]{2, 2}, 91, "left_pair", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{3}, 92, "right", false, false,
                events, new FakeShellClose(trace), 7, 9);
        IllegalStateException failure = new IllegalStateException("fatal source");

        CameraHelperMain.HelperBinder.TeardownOutcome outcome = session.tearDown(
                camera, "raw_source_failed", failure, false, 0, false,
                new FakeShellClose(trace), events, 9);

        assertSame(failure, outcome.failure);
        assertEquals(1, count(trace.values, "remove:2"));
        assertEquals(1, count(trace.values, "remove:3"));
        assertEquals(1, count(trace.values, "stop"));
        assertEquals(1, count(trace.values, "close"));
        assertEquals(1, count(trace.values, "fanout-close"));
        assertFalse(session.producerOpen);
        assertFalse(session.activityGroup.has());
        assertFalse(session.overlayGroup.has());
        assertEquals(1, events.count("camera_error", 91));
        assertEquals(1, events.count("camera_error", 92));
    }

    @Test
    public void fatalTeardownReportsDetachedOverlayAndActiveReverseOnce() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 101, "overlay", false, false);
        session.attach(camera, session.reverseGroup,
                surfaces(3), new int[]{1, 2, 3}, 102, "reverse", true, false,
                events, new FakeShellClose(trace), 7, 10);

        session.tearDown(camera, "raw_source_failed",
                new IllegalStateException("source failed"), false, 0, false,
                new FakeShellClose(trace), events, 10);

        assertEquals(1, events.count("camera_error", 101));
        assertEquals(1, events.count("camera_error", 102));
        assertEquals("raw_source_failed",
                events.byKindAndRequest("camera_error", 101).field("stage"));
        assertEquals("raw_source_failed",
                events.byKindAndRequest("camera_error", 102).field("stage"));
    }

    @Test
    public void fatalShellOwnerTerminalPrecedesQueuedStockClose() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.activityGroup,
                surfaces(1), new int[]{0}, 111, "stock", false, true);

        session.tearDown(camera, "raw_source_failed",
                new IllegalStateException("source failed"), true, 111, false,
                new FakeShellClose(trace), events, 11);

        assertEquals(1, events.count("camera_error", 111));
        assertTrue(trace.values.indexOf("event:camera_error")
                < trace.values.indexOf("shell:raw_source_failed:111"));
        assertTrue(trace.values.indexOf("shell:raw_source_failed:111")
                < trace.values.indexOf("event:camera_producer_closed"));
    }

    @Test
    public void failedRequestedAttachAndFailedRestoreTerminallyClearOldGroup()
            throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 121, "overlay", false, false);
        fanout.failAttachCall = 2;
        fanout.failAttachCall2 = 3;

        try {
            session.attach(camera, session.activityGroup,
                    surfaces(1), new int[]{3}, 122, "activity", true, false,
                    events, new FakeShellClose(trace), 7, 12);
            fail("attach should fail");
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            assertEquals("consumer_attach_failed", expected.reason);
            assertFalse(expected.fatal);
        }

        assertFalse(session.overlayGroup.has());
        assertFalse(session.activityGroup.has());
        assertEquals(0, fanout.activeTargets);
        assertEquals(1, events.count("camera_error", 121));
        assertEquals(1, events.count("camera_closed", 121));
    }

    @Test
    public void restoreFailureAfterSuccessfulAttachRollsBackRequestedTarget()
            throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 131, "overlay", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{3}, 132, "old_activity", false, false,
                events, new FakeShellClose(trace), 7, 13);
        fanout.detach(session.overlayGroup.surfaces);
        session.overlayGroup.attached = false;
        fanout.failAttachCall = 4;

        try {
            session.attach(camera, session.activityGroup,
                    surfaces(1), new int[]{3}, 133, "new_activity", false, false,
                    events, new FakeShellClose(trace), 7, 13);
            fail("restore should fail");
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            assertEquals("consumer_restore_failed", expected.reason);
            assertTrue(expected.requestedOwnedBySession);
            assertFalse(expected.fatal);
        }

        assertFalse(session.overlayGroup.has());
        assertTrue(session.activityGroup.attached);
        assertEquals(132, session.activityGroup.requestId);
        assertEquals("old_activity", session.activityGroup.view);
        assertEquals(1, fanout.activeTargets);
        assertEquals(1, events.count("camera_error", 131));
        assertEquals(1, events.count("camera_closed", 131));
    }

    @Test
    public void consumerFailureAndFailedCompatibleRestoreLeaveNoPhantomGroup()
            throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 141, "overlay", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{3}, 142, "activity", false, false,
                events, new FakeShellClose(trace), 7, 14);
        fanout.detach(session.overlayGroup.surfaces);
        session.overlayGroup.attached = false;
        fanout.failAttachCall = 3;

        assertTrue(session.failConsumer(camera, null, 3,
                new IllegalStateException("swap failed"), events,
                new FakeShellClose(trace), 7, 14));

        assertFalse(session.activityGroup.has());
        assertFalse(session.overlayGroup.has());
        assertEquals(0, fanout.activeTargets);
        assertEquals(1, events.count("camera_closed", 142));
        assertEquals(1, events.count("camera_closed", 141));
        assertTrue(session.producerOpen);
    }

    @Test
    public void queuedLateHubCallCannotMutateAfterCancellation() throws Exception {
        DirectCameraSourceHub.SerializedCall<Integer> call =
                new DirectCameraSourceHub.SerializedCall<>();
        AtomicInteger mutations = new AtomicInteger();

        assertFalse(call.await(1));
        assertTrue(call.cancelIfQueued());
        call.run(() -> mutations.incrementAndGet());

        assertEquals(0, mutations.get());
        try {
            call.result();
            fail("cancelled call should fail");
        } catch (java.util.concurrent.TimeoutException expected) {
            // Expected: no queued mutation can run after the failed return.
        }
    }

    @Test
    public void inFlightHubCallCompletesBeforeCancellationCanReturn() throws Exception {
        DirectCameraSourceHub.SerializedCall<Integer> call =
                new DirectCameraSourceHub.SerializedCall<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        Thread operation = new Thread(() -> call.run(() -> {
            started.countDown();
            release.await();
            return 7;
        }));
        operation.start();
        assertTrue(started.await(1, TimeUnit.SECONDS));
        Thread canceller = new Thread(() -> cancelled.set(call.cancelIfQueued()));
        canceller.start();
        release.countDown();
        operation.join(1000);
        canceller.join(1000);

        assertFalse(cancelled.get());
        assertEquals(7, call.result().intValue());
    }

    @Test
    public void falseOneShotRemoveLatchesAndBlocksEveryPersistentOpenAttempt() {
        AtomicInteger persistentOpens = new AtomicInteger();
        AtomicInteger released = new AtomicInteger();
        Trace trace = new Trace();
        FakeEventSink events = new FakeEventSink(trace, null);
        FakeCameraPort oneShot = new FakeCameraPort(trace);
        oneShot.removeResult = false;
        Throwable closeFailure = CameraHelperMain.HelperBinder.closeOneShotPort(
                oneShot, surfaces(1), new int[]{2}, new boolean[]{true});
        assertTrue(closeFailure != null);
        assertTrue(closeFailure.getMessage().contains("rmPreviewSurface returned false"));
        assertEquals(1, count(trace.values, "remove:2"));
        assertEquals(1, count(trace.values, "stop"));
        assertEquals(1, count(trace.values, "close"));

        boolean latched = closeFailure != null;
        for (int requestId = 201; requestId <= 202; requestId++) {
            boolean rejected = CameraHelperMain.HelperBinder.rejectUnconfirmedOwner(
                    latched, released::incrementAndGet, events,
                    CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                    requestId, new int[]{2});
            if (!rejected) persistentOpens.incrementAndGet();
        }
        assertEquals(0, persistentOpens.get());
        assertEquals(2, released.get());
        assertEquals(1, events.count("camera_error", 201));
        assertEquals(1, events.count("camera_error", 202));
    }

    @Test
    public void secondDetachFailureRequiresFatalScopedTeardown() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        FakeShellClose shell = new FakeShellClose(trace);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 211, "overlay", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{3}, 212, "activity", false, false,
                events, shell, 7, 21);
        fanout.failDetachCall = 2;

        CameraHelperMain.HelperBinder.PersistentSessionFailure failure;
        try {
            session.attach(camera, session.reverseGroup,
                    surfaces(3), new int[]{1, 2, 3}, 213, "reverse", true, false,
                    events, shell, 7, 21);
            fail("second detach should fail");
            return;
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            failure = expected;
        }
        assertTrue(failure.fatal);
        assertEquals("consumer_detach_failed", failure.reason);
        session.tearDown(camera, failure.reason, failure.getCause(),
                false, 0, false, shell, events, 21);

        assertFalse(session.overlayGroup.has());
        assertFalse(session.activityGroup.has());
        assertFalse(session.reverseGroup.has());
        assertEquals(1, events.count("camera_error", 211));
        assertEquals(1, events.count("camera_error", 212));
        assertEquals(0, events.count("camera_error", 213));
        assertEquals(0, fanout.activeTargets);
    }

    @Test
    public void fatalOwnedRollbackLetsTeardownEmitRequestedTerminalOnce() throws Exception {
        Trace trace = new Trace();
        FakeCameraPort camera = new FakeCameraPort(trace);
        FakeFanout fanout = new FakeFanout(trace);
        CameraHelperMain.HelperBinder.PersistentSession session = session();
        FakeEventSink events = new FakeEventSink(trace, session);
        FakeShellClose shell = new FakeShellClose(trace);
        session.startProducer(camera, fanout, session.overlayGroup,
                surfaces(1), new int[]{2}, 221, "overlay", false, false);
        session.attach(camera, session.activityGroup,
                surfaces(1), new int[]{3}, 222, "old_stock", false, true,
                events, shell, 7, 22);
        fanout.detach(session.overlayGroup.surfaces);
        session.overlayGroup.attached = false;
        fanout.failAttachCall = 4;
        fanout.failDetachCall = 3;

        CameraHelperMain.HelperBinder.PersistentSessionFailure failure;
        try {
            session.attach(camera, session.activityGroup,
                    surfaces(1), new int[]{3}, 223, "new_stock", false, true,
                    events, shell, 7, 22);
            fail("rollback detach should fail");
            return;
        } catch (CameraHelperMain.HelperBinder.PersistentSessionFailure expected) {
            failure = expected;
        }
        assertTrue(failure.fatal);
        assertTrue(failure.requestedOwnedBySession);
        if (CameraHelperMain.HelperBinder.shouldPreEmitFatalRequest(
                failure.requestedOwnedBySession)) {
            events.emit("camera_error", "request_id", 223);
        }
        session.tearDown(camera, failure.reason, failure.getCause(),
                true, 223, failure.shellCloseQueued, shell, events, 22);

        assertEquals(1, events.count("camera_error", 223));
        int terminal = trace.values.indexOf("event:camera_error");
        int stockClose = trace.values.indexOf("shell:consumer_restore_rollback_failed:223");
        int producerClosed = trace.values.indexOf("event:camera_producer_closed");
        assertTrue(terminal >= 0 && terminal < stockClose);
        assertTrue(stockClose < producerClosed);
    }

    @Test
    public void finalizerWaitsBehindBlockedOperationAndRunsExactlyOnce() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch blockerStarted = new CountDownLatch(1);
        CountDownLatch releaseBlocker = new CountDownLatch(1);
        CountDownLatch cleanupReturned = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        executor.execute(() -> {
            blockerStarted.countDown();
            try {
                releaseBlocker.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));
        DirectCameraSourceHub.SerializedCall<Void> finalizer =
                new DirectCameraSourceHub.SerializedCall<>();
        executor.execute(() -> finalizer.run(() -> {
            cleanupCalls.incrementAndGet();
            return null;
        }));
        Thread closeWaiter = new Thread(() -> {
            try {
                DirectCameraSourceHub.awaitFinalizer(finalizer);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                cleanupReturned.countDown();
            }
        });
        closeWaiter.start();
        assertFalse(cleanupReturned.await(20, TimeUnit.MILLISECONDS));
        releaseBlocker.countDown();
        assertTrue(cleanupReturned.await(1, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));

        assertEquals(null, failure.get());
        assertEquals(1, cleanupCalls.get());
    }

    @Test
    public void busyOpenStillEmitsRequestScopedTerminalError() {
        Trace trace = new Trace();
        FakeEventSink events = new FakeEventSink(trace, null);
        CameraHelperMain.HelperBinder.emitPersistentBusy(
                events, CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                111, "direct_open_owner_busy", 11);

        Event error = events.byKind("camera_error");
        assertEquals("direct_open_owner_busy", error.field("stage"));
        assertEquals(CameraHelperMain.CAMERA_OWNER_ACTIVITY, error.field("camera_owner"));
        assertEquals(111, error.field("request_id"));
        assertEquals(11, error.field("producer_epoch"));
    }

    private static CameraHelperMain.HelperBinder.PersistentSession session() {
        return new CameraHelperMain.HelperBinder.PersistentSession();
    }

    private static Surface[] surfaces(int count) {
        return new Surface[count];
    }

    private static List<Boolean> list(Boolean... values) {
        List<Boolean> result = new ArrayList<>();
        for (Boolean value : values) result.add(value);
        return result;
    }

    private static int count(List<String> values, String expected) {
        int count = 0;
        for (String value : values) if (expected.equals(value)) count++;
        return count;
    }

    private static int countPrefix(List<String> values, String prefix) {
        int count = 0;
        for (String value : values) if (value.startsWith(prefix)) count++;
        return count;
    }

    private static final class Trace {
        final List<String> values = new ArrayList<>();
    }

    private static final class FakeCameraPort
            implements CameraHelperMain.HelperBinder.PersistentCameraPort {
        final Trace trace;
        int addCalls;
        int failAddCall;
        boolean removeResult = true;

        FakeCameraPort(Trace trace) {
            this.trace = trace;
        }

        @Override
        public boolean add(Surface value, int index) {
            trace.values.add("add:" + index);
            addCalls++;
            if (addCalls == failAddCall) throw new IllegalStateException("attach failed");
            return true;
        }

        @Override
        public boolean remove(Surface value, int index) {
            trace.values.add("remove:" + index);
            return removeResult;
        }

        @Override
        public boolean start() {
            trace.values.add("start");
            return true;
        }

        @Override
        public void stop() {
            trace.values.add("stop");
        }

        @Override
        public void close() {
            trace.values.add("close");
        }
    }

    private static final class FakeFanout
            implements CameraHelperMain.HelperBinder.PersistentSurfaceFanout {
        final Trace trace;
        int attachCalls;
        int failAttachCall;
        int failAttachCall2;
        int activeTargets;
        int detachCalls;
        int failDetachCall;

        FakeFanout(Trace trace) {
            this.trace = trace;
        }

        @Override
        public Surface source(int index) {
            trace.values.add("source:" + index);
            return null;
        }

        @Override
        public void attach(Surface[] values, int[] indexes) {
            attachCalls++;
            if (attachCalls == failAttachCall || attachCalls == failAttachCall2) {
                throw new IllegalStateException("attach failed");
            }
            activeTargets += values.length;
            for (int index : indexes) trace.values.add("target-add:" + index);
        }

        @Override
        public void detach(Surface[] values) {
            detachCalls++;
            if (detachCalls == failDetachCall) {
                throw new IllegalStateException("detach failed");
            }
            activeTargets -= values.length;
            trace.values.add("target-remove:" + values.length);
        }

        @Override
        public void close() {
            activeTargets = 0;
            trace.values.add("fanout-close");
        }
    }

    private static final class FakeShellClose
            implements CameraHelperMain.HelperBinder.PersistentShellCloseSink {
        final Trace trace;

        FakeShellClose(Trace trace) {
            this.trace = trace;
        }

        @Override
        public boolean close(String reason, int requestId) {
            trace.values.add("shell:" + reason + ":" + requestId);
            return true;
        }
    }

    private static final class FakeEventSink
            implements CameraHelperMain.HelperBinder.PersistentEventSink {
        final Trace trace;
        final CameraHelperMain.HelperBinder.PersistentSession session;
        final List<Event> values = new ArrayList<>();
        final List<Boolean> activityPresentDuringEvents = new ArrayList<>();

        FakeEventSink(Trace trace, CameraHelperMain.HelperBinder.PersistentSession session) {
            this.trace = trace;
            this.session = session;
        }

        @Override
        public void emit(String kind, Object... fields) {
            trace.values.add("event:" + kind);
            values.add(new Event(kind, fields));
            if (session != null && ("camera_consumer_detached".equals(kind)
                    || "camera_closed".equals(kind))) {
                activityPresentDuringEvents.add(session.activityGroup.has());
            }
        }

        Event byKind(String kind) {
            for (Event value : values) if (kind.equals(value.kind)) return value;
            throw new AssertionError("Missing event " + kind);
        }

        Event byKindAndRequest(String kind, int requestId) {
            for (Event value : values) {
                if (kind.equals(value.kind)
                        && Integer.valueOf(requestId).equals(value.field("request_id"))) {
                    return value;
                }
            }
            throw new AssertionError("Missing event " + kind + " for " + requestId);
        }

        int count(String kind, int requestId) {
            int count = 0;
            for (Event value : values) {
                if (kind.equals(value.kind)
                        && Integer.valueOf(requestId).equals(value.field("request_id"))) {
                    count++;
                }
            }
            return count;
        }
    }

    private static final class Event {
        final String kind;
        final Map<String, Object> fields = new LinkedHashMap<>();

        Event(String kind, Object... values) {
            this.kind = kind;
            for (int i = 0; i + 1 < values.length; i += 2) {
                fields.put(String.valueOf(values[i]), values[i + 1]);
            }
        }

        Object field(String name) {
            return fields.get(name);
        }
    }
}

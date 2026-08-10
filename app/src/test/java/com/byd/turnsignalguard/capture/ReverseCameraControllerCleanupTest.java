package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ReverseCameraControllerCleanupTest {
    @Test
    public void asyncFailureRetriesSameRequestAndFinishesExactlyOnce() {
        Harness harness = new Harness(false, true);

        harness.coordinator.startCameraThenWindow();
        assertEquals(Arrays.asList(47), harness.closeRequestIds);
        Consumer<Boolean> oldWindowCallback = harness.windowCallbacks.get(0);

        oldWindowCallback.accept(false);
        assertEquals(1, harness.retryEvents);
        Runnable scheduledRetry = harness.retry;
        assertTrue(scheduledRetry != null);

        scheduledRetry.run();
        harness.windowCallbacks.get(1).accept(true);

        assertEquals(Arrays.asList(47, 47), harness.closeRequestIds);
        assertEquals(2, harness.coordinator.closeAttempts());
        assertTrue(harness.coordinator.cameraClosed());
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertNull(harness.active[0]);

        oldWindowCallback.accept(true);
        scheduledRetry.run();
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertEquals(Arrays.asList(47, 47), harness.closeRequestIds);
    }

    @Test
    public void thrownCloseAndWindowQueueUseTheSameBoundedRetryPath() {
        Harness harness = new Harness(new IllegalStateException("close"), true);
        harness.throwFirstWindowClose = true;

        harness.coordinator.startCameraThenWindow();

        assertEquals(1, harness.cameraErrors);
        assertEquals(1, harness.windowErrors);
        assertEquals(1, harness.retryEvents);
        assertEquals(Arrays.asList(47), harness.closeRequestIds);

        Runnable scheduledRetry = harness.retry;
        assertTrue(scheduledRetry != null);
        scheduledRetry.run();
        harness.windowCallbacks.get(0).accept(true);

        assertEquals(Arrays.asList(47, 47), harness.closeRequestIds);
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertFalse(harness.throwFirstWindowClose);
    }

    @Test
    public void windowFirstProductionPathClosesCameraAfterAsyncWindowSuccess() {
        Harness harness = new Harness(true);

        harness.coordinator.startWindowThenCamera();
        assertTrue(harness.closeRequestIds.isEmpty());
        Consumer<Boolean> callback = harness.windowCallbacks.get(0);
        callback.accept(true);

        assertEquals(Arrays.asList(47), harness.closeRequestIds);
        assertEquals(1, harness.coordinator.closeAttempts());
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
        assertNull(harness.active[0]);

        callback.accept(true);
        assertEquals(Arrays.asList(47), harness.closeRequestIds);
        assertEquals(1, harness.priorityReleases);
        assertEquals(1, harness.stoppedEvents);
    }

    @Test
    public void failClosedInvalidationMakesQueuedCleanupRetriesNoOps() {
        Harness harness = new Harness(false);

        harness.coordinator.startCameraThenWindow();
        harness.windowCallbacks.get(0).accept(false);
        Runnable staleRetry = harness.retry;
        assertTrue(staleRetry != null);

        harness.active[0] = null;
        harness.retry = null;
        for (int i = 0; i < 6; i++) staleRetry.run();

        assertEquals(Arrays.asList(47), harness.closeRequestIds);
        assertEquals(1, harness.windowCallbacks.size());
        assertEquals(1, harness.coordinator.closeAttempts());
        assertEquals(0, harness.priorityReleases);
        assertEquals(0, harness.stoppedEvents);
    }

    private static final class Harness {
        final ReverseCameraController.CleanupCloseCoordinator[] active =
                new ReverseCameraController.CleanupCloseCoordinator[1];
        final List<Integer> closeRequestIds = new ArrayList<>();
        final List<Consumer<Boolean>> windowCallbacks = new ArrayList<>();
        final List<Object> cameraResults;
        final ReverseCameraController.CleanupCloseCoordinator coordinator;
        Runnable retry;
        boolean throwFirstWindowClose;
        int retryEvents;
        int cameraErrors;
        int windowErrors;
        int priorityReleases;
        int stoppedEvents;

        Harness(Object... cameraResults) {
            this.cameraResults = new ArrayList<>(Arrays.asList(cameraResults));
            coordinator = new ReverseCameraController.CleanupCloseCoordinator(
                    47,
                    candidate -> active[0] == candidate,
                    requestId -> {
                        closeRequestIds.add(requestId);
                        Object result = this.cameraResults.remove(0);
                        if (result instanceof Throwable) throw (Throwable) result;
                        return (Boolean) result;
                    },
                    callback -> {
                        if (throwFirstWindowClose) {
                            throwFirstWindowClose = false;
                            throw new IllegalStateException("window");
                        }
                        windowCallbacks.add(callback);
                    },
                    task -> retry = task,
                    () -> retry = null,
                    ignored -> retryEvents++,
                    ignored -> cameraErrors++,
                    ignored -> windowErrors++,
                    state -> {
                        assertSame(active[0], state);
                        active[0] = null;
                        priorityReleases++;
                        stoppedEvents++;
                    });
            active[0] = coordinator;
        }
    }
}

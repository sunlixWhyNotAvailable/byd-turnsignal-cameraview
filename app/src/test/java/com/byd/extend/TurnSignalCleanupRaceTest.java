package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class TurnSignalCleanupRaceTest {
    @Test
    public void closeCleanupIsHarmlessWhenAlreadyStopped() {
        AtomicInteger submissions = new AtomicInteger();
        TurnSignalController.executeCleanup(
                command -> submissions.incrementAndGet(), () -> true, () -> fail("ran"));
        assertEquals(0, submissions.get());
    }

    @Test
    public void closeCleanupIgnoresOnlyShutdownRaceRejection() {
        AtomicInteger checks = new AtomicInteger();
        TurnSignalController.executeCleanup(
                command -> { throw new RejectedExecutionException("executor shutdown"); },
                () -> checks.getAndIncrement() > 0,
                () -> fail("ran"));
        assertEquals(2, checks.get());
    }

    @Test
    public void queuedCloseCleanupDoesNotRunAfterShutdown() {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicBoolean stopped = new AtomicBoolean();
        AtomicBoolean ran = new AtomicBoolean();
        TurnSignalController.executeCleanup(queued::set, stopped::get,
                () -> ran.set(true));

        stopped.set(true);
        queued.get().run();

        assertEquals(false, ran.get());
    }

    @Test
    public void closeCleanupPreservesUnexpectedLiveSubmissionFailure() {
        try {
            TurnSignalController.executeCleanup(
                    command -> { throw new RejectedExecutionException("live failure"); },
                    () -> false,
                    () -> fail("ran"));
            fail("live rejection swallowed");
        } catch (RejectedExecutionException expected) {
            assertEquals("live failure", expected.getMessage());
        }
    }

    @Test
    public void acceptedCloseCleanupStillRuns() {
        AtomicBoolean ran = new AtomicBoolean();
        TurnSignalController.executeCleanup(Runnable::run, () -> false,
                () -> ran.set(true));
        assertEquals(true, ran.get());
    }
}

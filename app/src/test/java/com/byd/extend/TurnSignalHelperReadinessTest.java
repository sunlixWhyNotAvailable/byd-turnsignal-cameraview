package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class TurnSignalHelperReadinessTest {
    @Test
    public void onlyAuthorizedCommandReadTimeoutContinuesToBinderReadiness() throws Exception {
        LocalAdbClient.Result handshakeTimeout = LocalAdbClient.Result.failed(
                "SocketTimeoutException: Read timed out", "", -1, "key-a");
        LocalAdbClient.Result authorizationTimeout = LocalAdbClient.Result.authorizationRequired(
                "authorization_prompt_timeout", true, "key-a");
        LocalAdbClient.Result commandTimeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        AtomicInteger probes = new AtomicInteger();

        assertFalse(handshakeTimeout.commandReadTimeout);
        assertFalse(authorizationTimeout.commandReadTimeout);
        assertTrue(commandTimeout.commandReadTimeout);
        assertFalse(TurnSignalController.shouldAwaitHelperReadiness(handshakeTimeout));
        assertFalse(TurnSignalController.shouldAwaitHelperReadiness(authorizationTimeout));
        assertTrue(TurnSignalController.shouldAwaitHelperReadiness(commandTimeout));
        assertNull(TurnSignalController.awaitHelperReadiness(
                handshakeTimeout, -1, () -> { probes.incrementAndGet(); return null; },
                () -> 0L, millis -> { }, () -> true));
        assertEquals(0, probes.get());
    }

    @Test
    public void commandTimeoutWaitsImmediatelyRejectsReplacedPidThenAcceptsCurrentBinder()
            throws Exception {
        LocalAdbClient.Result commandTimeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        IBinder replacedBinder = fakeBinder();
        IBinder currentBinder = fakeBinder();
        long[] now = {100};
        List<Long> probeTimes = new ArrayList<>();
        AtomicInteger probes = new AtomicInteger();

        TurnSignalController.Ping ready = TurnSignalController.awaitHelperReadiness(
                commandTimeout, 41, () -> {
                    probeTimes.add(now[0]);
                    return probes.getAndIncrement() == 0
                            ? new TurnSignalController.Ping(replacedBinder, 11,
                                    BuildConfig.VERSION_CODE, 41, "")
                            : new TurnSignalController.Ping(currentBinder, 11,
                                    BuildConfig.VERSION_CODE, 42, "");
                },
                () -> now[0], millis -> now[0] += millis, () -> true);

        assertSame(currentBinder, ready.binder);
        assertEquals(2, probes.get());
        assertEquals(List.of(100L, 350L), probeTimes);
        assertEquals(350L, now[0]);
    }

    @Test
    public void readinessExpiresWithinThreeSecondsAndCancellationBeforeAttachIsRejected()
            throws Exception {
        LocalAdbClient.Result commandTimeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        long[] now = {0};
        AtomicInteger probes = new AtomicInteger();

        TurnSignalController.Ping expired = TurnSignalController.awaitHelperReadiness(
                commandTimeout, -1, () -> {
                    probes.incrementAndGet();
                    return TurnSignalController.Ping.failed("protocol_mismatch");
                },
                () -> now[0], millis -> now[0] += millis, () -> true);

        assertNull(expired);
        assertEquals(3_000L, now[0]);
        assertEquals(12, probes.get());

        AtomicBoolean mayContinue = new AtomicBoolean(true);
        IBinder currentBinder = fakeBinder();
        TurnSignalController.Ping cancelled = TurnSignalController.awaitHelperReadiness(
                commandTimeout, -1, () -> {
                    mayContinue.set(false);
                    return new TurnSignalController.Ping(currentBinder, 11,
                            BuildConfig.VERSION_CODE, 42, "");
                },
                () -> 0L, millis -> { }, mayContinue::get);

        assertNull(cancelled);
    }

    private static IBinder fakeBinder() {
        return (IBinder) Proxy.newProxyInstance(
                IBinder.class.getClassLoader(), new Class<?>[] {IBinder.class},
                (proxy, method, args) -> null);
    }
}

package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.os.IBinder;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvasRecoveryDaemonReadinessTest {
    @Test
    public void typedCommandReadTimeoutAcceptsDelayedCompatibleBinder() throws Exception {
        LocalAdbClient.Result timeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        IBinder binder = fakeBinder();
        AvasRecoveryDaemonController.DaemonPing ready =
                new AvasRecoveryDaemonController.DaemonPing(binder, 42, "apk-current", "");
        long[] now = {100L};
        AtomicInteger probes = new AtomicInteger();

        AvasRecoveryDaemonController.DaemonPing result =
                AvasRecoveryDaemonController.awaitReadiness(timeout, "apk-current", () ->
                        probes.getAndIncrement() == 0
                                ? new AvasRecoveryDaemonController.DaemonPing(
                                        binder, 41, "apk-old", "")
                                : ready,
                        () -> now[0], millis -> now[0] += millis, () -> true);

        assertSame(ready, result);
        assertEquals(2, probes.get());
        assertEquals(200L, now[0]);
    }

    @Test
    public void incompatibleAndAbsentBindersExpireAtExistingReadinessDeadline() throws Exception {
        LocalAdbClient.Result timeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        IBinder binder = fakeBinder();
        AvasRecoveryDaemonController.DaemonPing[] unusable = {
                new AvasRecoveryDaemonController.DaemonPing(
                        binder, 42, "apk-current", "protocol_mismatch"),
                new AvasRecoveryDaemonController.DaemonPing(binder, 42, "apk-old", ""),
                AvasRecoveryDaemonController.DaemonPing.failed("unavailable")
        };
        long[] now = {500L};
        AtomicInteger probes = new AtomicInteger();

        AvasRecoveryDaemonController.DaemonPing result =
                AvasRecoveryDaemonController.awaitReadiness(timeout, "apk-current", () ->
                        unusable[probes.getAndIncrement() % unusable.length],
                        () -> now[0], millis -> now[0] += millis, () -> true);

        assertNull(result);
        assertEquals(30, probes.get());
        assertEquals(3_500L, now[0]);
    }

    @Test
    public void authenticationTransportAndExitFailuresNeverStartReadiness() throws Exception {
        List<LocalAdbClient.Result> failures = List.of(
                LocalAdbClient.Result.authorizationRequired("authorization_prompt_timeout",
                        true, "key-a"),
                LocalAdbClient.Result.failed(
                        "SocketTimeoutException: Read timed out", "", -1, "key-a"),
                LocalAdbClient.Result.failed("command_failed", "", 1, "key-a"));
        AtomicInteger probes = new AtomicInteger();

        for (LocalAdbClient.Result failure : failures) {
            assertFalse(AvasRecoveryDaemonController.shouldAwaitReadiness(failure));
            assertNull(AvasRecoveryDaemonController.awaitReadiness(failure, "apk-current",
                    () -> { probes.incrementAndGet(); return null; },
                    () -> 0L, millis -> { }, () -> true));
        }

        assertEquals(0, probes.get());
    }

    @Test
    public void cancellationAfterProbeRejectsOtherwiseHealthyBinder() throws Exception {
        LocalAdbClient.Result timeout = LocalAdbClient.Result.commandReadTimeout(
                "SocketTimeoutException: Read timed out", "key-a");
        AtomicBoolean mayContinue = new AtomicBoolean(true);
        AtomicInteger probes = new AtomicInteger();

        AvasRecoveryDaemonController.DaemonPing result =
                AvasRecoveryDaemonController.awaitReadiness(timeout, "apk-current", () -> {
                    probes.incrementAndGet();
                    mayContinue.set(false);
                    return new AvasRecoveryDaemonController.DaemonPing(
                            fakeBinder(), 42, "apk-current", "");
                }, () -> 0L, millis -> { }, mayContinue::get);

        assertNull(result);
        assertEquals(1, probes.get());
        assertFalse(mayContinue.get());
    }

    private static IBinder fakeBinder() {
        return (IBinder) Proxy.newProxyInstance(
                IBinder.class.getClassLoader(), new Class<?>[] {IBinder.class},
                (proxy, method, args) -> null);
    }
}

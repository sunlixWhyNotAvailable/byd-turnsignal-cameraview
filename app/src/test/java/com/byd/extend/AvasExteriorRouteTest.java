package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class AvasExteriorRouteTest {
    @Test public void acceptedPrimaryIsMarkedBeforeItsCommand() throws Exception {
        List<String> order = new ArrayList<>();
        assertTrue(AvasExteriorRoute.acquirePrimary(value -> order.add("marker:" + value), () -> {
            order.add("command");
            return 1;
        }));
        assertEquals(List.of("marker:9", "command"), order);
    }

    @Test public void rejectedPrimaryUsesOneAttemptAndLeavesOnlySharedOwnership() throws Exception {
        List<Integer> markers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AvasExteriorRoute.acquirePrimary(markers::add, () -> {
            calls.incrementAndGet();
            return -10011;
        }));
        assertEquals(1, calls.get());
        assertEquals(List.of(9, 8), markers);
        assertEquals(-1, AvasExteriorRoute.releasePrimaryDevice(markers.get(1)));
    }

    @Test public void missingReplyKeepsTeardownOwnershipWithoutBlockingSharedSetup() throws Exception {
        List<Integer> markers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AvasExteriorRoute.acquirePrimary(markers::add, () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("missing Binder reply");
                }));
        assertEquals(1, calls.get());
        assertEquals(List.of(9), markers);
        assertEquals(3, AvasExteriorRoute.releasePrimaryDevice(markers.get(0)));
    }

    @Test public void rejectedActivationIsNotRetried() throws Exception {
        List<Integer> markers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AvasExteriorRoute.acquirePrimary(markers::add,
                () -> calls.getAndIncrement() == 0 ? -10011 : 1));
        assertEquals(1, calls.get());
        assertEquals(List.of(9, 8), markers);
    }

    @Test public void negativeReplyCannotEraseAnEarlierUnknownOutcome() throws Exception {
        List<Integer> markers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertFalse(AvasExteriorRoute.acquirePrimary(markers::add,
                () -> calls.getAndIncrement() == 0 ? Integer.MIN_VALUE : -10011));
        assertEquals(List.of(9), markers);
    }

    @Test public void cancellationDoesNotRetryOrReleaseUnknownOwnership() {
        List<Integer> markers = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> AvasExteriorRoute.acquirePrimary(markers::add,
                () -> {
                    calls.incrementAndGet();
                    throw new InterruptedException();
                }));
        assertEquals(1, calls.get());
        assertEquals(List.of(9), markers);
    }

    @Test public void primaryAdmissionIsMandatoryRegardlessOfSdkOrFocus() {
        for (int bits = 0; bits < 8; bits++) {
            assertEquals((bits & 1) != 0, AvasExteriorRoute.routeAccepted(
                    (bits & 1) != 0, (bits & 2) != 0, (bits & 4) != 0));
        }
    }

    @Test public void teardownCanSucceedOnItsSingleRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(0, AvasExteriorRoute.writePrimary(
                () -> calls.getAndIncrement() == 0 ? -10011 : 0));
        assertEquals(2, calls.get());
    }

    @Test public void releaseMarkerSelectsOnlyItsFixedPrimaryRoute() {
        assertEquals(3, AvasExteriorRoute.EXTERIOR_DEVICE);
        assertEquals(6, AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY);
        assertEquals(7, AvasShellSettings.EXTERIOR_UNACQUIRED);
        assertEquals(8, AvasShellSettings.EXTERIOR_SHARED);
        assertEquals(9, AvasShellSettings.EXTERIOR_DEVICE3_DIRTY);
        assertEquals(3, AvasExteriorRoute.releasePrimaryDevice(AvasShellSettings.EXTERIOR_DIRTY));
        assertEquals(3, AvasExteriorRoute.releasePrimaryDevice(AvasShellSettings.EXTERIOR_DEVICE3_DIRTY));
        assertEquals(1000, AvasExteriorRoute.releasePrimaryDevice(
                AvasShellSettings.EXTERIOR_CHANNEL0_DIRTY));
        assertEquals(-1, AvasExteriorRoute.releasePrimaryDevice(
                AvasShellSettings.EXTERIOR_UNACQUIRED));
        assertEquals(-1, AvasExteriorRoute.releasePrimaryDevice(
                AvasShellSettings.EXTERIOR_SHARED));
        assertThrows(IllegalArgumentException.class,
                () -> AvasExteriorRoute.releasePrimaryDevice(AvasShellSettings.CLEAN));
    }
}

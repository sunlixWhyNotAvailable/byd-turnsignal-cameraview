package com.byd.extend;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AvasNavigationRecoveryTest {
    @Test public void explicitRejectionsDoNotRequireOemReleaseEvenAfterRestart() throws Exception {
        for (int status : new int[]{-10011, -1}) {
            List<Integer> persisted = new ArrayList<>();
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> AvasNavigationRecovery.prepare(persisted::add, () -> status));
            assertTrue(failure.getMessage().contains("status=" + status));
            assertEquals(Arrays.asList(AvasShellSettings.NAVIGATION_PENDING,
                    AvasShellSettings.NAVIGATION_REJECTED), persisted);
            assertFalse(AvasNavigationRecovery.requiresRelease(persisted.get(1)));
        }
    }

    @Test public void acceptedPrepareRequiresReleaseEvenAfterRestart() throws Exception {
        for (int status : new int[]{0, 1}) {
            List<Integer> persisted = new ArrayList<>();
            AvasNavigationRecovery.prepare(persisted::add, () -> status);
            assertEquals(Arrays.asList(AvasShellSettings.NAVIGATION_PENDING,
                    AvasShellSettings.NAVIGATION_ACTIVE), persisted);
            assertTrue(AvasNavigationRecovery.requiresRelease(persisted.get(1)));
        }
    }

    @Test public void missingBinderReplyRemainsUncertainAndRequiresRelease() {
        List<Integer> persisted = new ArrayList<>();
        IOException transport = new IOException("Binder reply lost");
        assertSame(transport, assertThrows(IOException.class,
                () -> AvasNavigationRecovery.prepare(persisted::add, () -> { throw transport; })));
        assertEquals(Arrays.asList(AvasShellSettings.NAVIGATION_PENDING), persisted);
        assertTrue(AvasNavigationRecovery.requiresRelease(persisted.get(0)));
    }

    @Test public void failureToPersistBeforePrepareNeverIssuesCommand() {
        assertThrows(IOException.class, () -> AvasNavigationRecovery.prepare(
                marker -> { throw new IOException("Settings unavailable"); },
                () -> { fail("Must not open an untracked route"); return 1; }));
    }

    @Test public void failureToPersistReplyKeepsUncertainMarker() {
        for (int status : new int[]{1, -10011}) {
            List<Integer> persisted = new ArrayList<>();
            assertThrows(IOException.class, () -> AvasNavigationRecovery.prepare(marker -> {
                if (marker != AvasShellSettings.NAVIGATION_PENDING) {
                    throw new IOException("Settings write failed after command");
                }
                persisted.add(marker);
            }, () -> status));
            assertTrue(AvasNavigationRecovery.requiresRelease(persisted.get(0)));
        }
    }

    @Test public void legacyMarkersAreNotMistakenForRejectedPreparation() {
        assertTrue(AvasNavigationRecovery.requiresRelease(AvasShellSettings.NAVIGATION_DIRTY));
        assertFalse(AvasNavigationRecovery.requiresRelease(AvasShellSettings.CLEAN));
        assertFalse(AvasNavigationRecovery.requiresRelease(AvasShellSettings.EXTERIOR_DIRTY));
    }

    @Test public void onlyLegacyUnavailableReleaseUsesApprovedOneTimeEscape() throws Exception {
        List<Integer> persisted = new ArrayList<>();
        assertTrue(AvasNavigationRecovery.release(AvasShellSettings.NAVIGATION_DIRTY,
                persisted::add, () -> -10011));
        assertEquals(Arrays.asList(AvasShellSettings.NAVIGATION_REJECTED), persisted);
        // A new player skips route teardown for this persisted marker and only restores locals.
        assertFalse(AvasNavigationRecovery.requiresRelease(persisted.get(0)));
    }

    @Test public void activeAndUncertainRoutesNeverDiscardUnavailableRelease() {
        for (int dirty : new int[]{AvasShellSettings.NAVIGATION_ACTIVE,
                AvasShellSettings.NAVIGATION_PENDING}) {
            List<Integer> persisted = new ArrayList<>();
            assertThrows(IllegalStateException.class, () -> AvasNavigationRecovery.release(
                    dirty, persisted::add, () -> -10011));
            assertTrue(persisted.isEmpty());
            assertTrue(AvasNavigationRecovery.requiresRelease(dirty));
        }
    }

    @Test public void otherLegacyFailuresAreNotSilentlyDiscarded() {
        List<Integer> persisted = new ArrayList<>();
        assertThrows(IllegalStateException.class, () -> AvasNavigationRecovery.release(
                AvasShellSettings.NAVIGATION_DIRTY, persisted::add, () -> -1));
        assertThrows(IOException.class, () -> AvasNavigationRecovery.release(
                AvasShellSettings.NAVIGATION_DIRTY, persisted::add,
                () -> { throw new IOException("Lost release reply"); }));
        assertTrue(persisted.isEmpty());
    }

    @Test public void successfulTeardownDoesNotInvokeLegacyEscape() throws Exception {
        for (int dirty : new int[]{AvasShellSettings.NAVIGATION_DIRTY,
                AvasShellSettings.NAVIGATION_PENDING, AvasShellSettings.NAVIGATION_ACTIVE}) {
            List<Integer> persisted = new ArrayList<>();
            assertFalse(AvasNavigationRecovery.release(dirty, persisted::add, () -> 1));
            assertTrue(persisted.isEmpty());
        }
    }

    @Test public void failedLegacyMarkerWriteRemainsAnError() {
        assertThrows(IOException.class, () -> AvasNavigationRecovery.release(
                AvasShellSettings.NAVIGATION_DIRTY,
                marker -> { throw new IOException("Cannot persist legacy recovery"); },
                () -> -10011));
    }
}

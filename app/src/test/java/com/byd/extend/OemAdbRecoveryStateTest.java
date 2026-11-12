package com.byd.extend;

import android.content.SharedPreferences;
import java.io.File;
import java.lang.reflect.Constructor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.rules.TemporaryFolder;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Actual preference XML and admission transitions; no OEM/ADB responses or worker outcomes. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 32, manifest = Config.NONE)
public final class OemAdbRecoveryStateTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private OemAdbRecoveryState open(File file) throws Exception {
        Constructor<?> constructor = Class.forName("android.app.SharedPreferencesImpl")
                .getDeclaredConstructor(File.class, int.class);
        constructor.setAccessible(true);
        return new OemAdbRecoveryState((SharedPreferences) constructor.newInstance(file, 0));
    }

    @Test public void mutationSurvivesReopenAndOnlyMatchingCompletionOrNewBootReleasesIt() throws Exception {
        File file = new File(temporary.getRoot(), "oem.xml");
        OemAdbRecoveryState state = open(file);
        assertTrue(state.eligible(7, false, "boot-a", 12));
        state.attempted(7);
        state.dispatch("first", "ivi_write", "boot-a", 12);
        state = open(file);
        assertFalse(state.eligible(7, true, "boot-a", 12));
        assertFalse(state.eligible(8, true, "", -1));
        state.completed("obsolete");
        assertFalse(state.eligible(8, true, "boot-a", 12));
        state.restored();
        assertFalse(state.eligible(8, true, "boot-a", 12));
        state.completed("first");
        state = open(file);
        assertTrue(state.eligible(8, false, "boot-a", 12));
        state.dispatch("second", "host_write", "boot-a", 12);
        assertTrue(open(file).eligible(8, false, "boot-b", 13));
        assertEquals("none", open(file).pendingStage());
    }

    @Test public void completedAttemptStaysConsumedAcrossReopenUntilExplicitRetryOrRecovery() throws Exception {
        File file = new File(temporary.getRoot(), "oem-cycle.xml");
        OemAdbRecoveryState state = open(file);
        state.attempted(41);
        state = open(file);
        assertFalse(state.eligible(41, false, "boot-a", 1));
        assertTrue(state.eligible(41, true, "boot-a", 1));
        state.restored();
        assertTrue(open(file).eligible(41, false, "boot-a", 1));
    }

    @Test public void oneElapsedBudgetCannotBeExtendedByAnotherStage() {
        OemAdbRecoveryState.Ticket ticket = new OemAdbRecoveryState.Ticket(1000);
        assertTrue(ticket.valid(1000));
        assertEquals(5000, ticket.remaining(11000));
        assertTrue(ticket.valid(15999));
        assertFalse(ticket.valid(16000));
        assertEquals(0, ticket.remaining(17000));
        assertEquals("deadline", ticket.invalidation());
        assertFalse(ticket.valid(1001));
    }

    @Test public void foregroundRsaInvalidatesTheActualRecoveryAdmissionWithoutRearmingItsTicket() throws Exception {
        AdbAuthorizationSession session = new AdbAuthorizationSession();
        OemAdbRecoveryState.Ticket ticket = new OemAdbRecoveryState.Ticket(0);
        try (AdbAuthorizationSession.RecoveryOperation operation = session.beginRecovery()) {
            assertNotNull(operation);
            operation.register(() -> ticket.invalidate("foreground_rsa"));
            try (AdbAuthorizationSession.Attempt foreground = session.begin()) {
                assertTrue(operation.cancelled());
                assertFalse(ticket.valid(1));
                assertNull(session.beginRecovery());
            }
            try (AdbAuthorizationSession.RecoveryOperation next = session.beginRecovery()) {
                assertNotNull(next);
                assertFalse(next.cancelled());
                assertFalse(ticket.valid(2));
            }
        }
    }
}

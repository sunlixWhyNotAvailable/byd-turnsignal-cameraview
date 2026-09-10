package com.byd.extend;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class AvasEventPolicyTest {
    @Test public void initialRepeatedAndUnknownAreSilent() {
        AvasEventPolicy policy = new AvasEventPolicy();
        assertTrue(policy.sample(0, 1, 2, true).isEmpty());
        assertTrue(policy.sample(250, 4, 2, true).isEmpty());
        assertTrue(policy.sample(500, -1, -1, true).isEmpty());
        assertTrue(policy.sample(750, 0, 1, true).isEmpty());
    }

    @Test public void powerFirstSuppressesAssociatedUnlockWithoutDelayingOff() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertEquals(Collections.singletonList("power_off"), policy.sample(250, 0, 1, true));
        assertTrue(policy.wasUnlockSuppressed());
        assertEquals(Collections.singletonList("lock"), policy.sample(500, 0, 2, true));
        assertEquals(Collections.singletonList("unlock"), policy.sample(750, 0, 1, true));
        assertEquals(Collections.singletonList("power_on"), policy.sample(1000, 2, 1, true));
    }

    @Test public void matchesObserved258msUnlockButNotDelayedIndependentUnlock() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertEquals(Collections.singletonList("power_off"), policy.sample(100, 0, 2, true));
        assertTrue(policy.sample(358, 0, 1, true).isEmpty());
        assertTrue(policy.wasUnlockSuppressed());
        policy.reset();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        assertEquals(Collections.singletonList("unlock"), policy.sample(1101, 0, 1, true));
    }

    @Test public void offNotReadyAndPowerOnDoNotConsumeUnlock() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, false);
        assertEquals(Arrays.asList("power_off", "unlock"), policy.sample(100, 0, 1, false));
        policy.reset();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        assertEquals(Arrays.asList("power_on", "unlock"), policy.sample(300, 1, 1, true));
    }

    @Test public void resetSeedsCurrentStateAndDoesNotReplay() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        policy.sample(100, 0, 2, true);
        policy.reset();
        assertTrue(policy.sample(500, 0, 1, true).isEmpty());
        assertEquals(Collections.singletonList("lock"), policy.sample(600, 0, 2, true));
    }

    @Test public void oneMissingSignalMakesWholeSampleSilentAndReseedsBoth() {
        AvasEventPolicy policy = new AvasEventPolicy();
        policy.sample(0, 1, 2, true);
        assertTrue(policy.sample(250, 0, -1, true).isEmpty());
        assertTrue(policy.sample(500, 0, 1, true).isEmpty());
        assertEquals(Collections.singletonList("lock"), policy.sample(750, 0, 2, true));
    }
}

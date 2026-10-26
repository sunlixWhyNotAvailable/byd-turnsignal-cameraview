package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HazardThanksSequenceTest {
    @Test public void requiresFreshIdleBaselineAndIgnoresActiveRetriggers() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertEquals("mode_not_idle", sequence.start(2, 1, 3, 2, 100));
        assertEquals("stalk_not_neutral", sequence.start(1, 2, 3, 2, 100));
        assertEquals("sound_not_idle", sequence.start(1, 1, 2, 2, 100));
        assertEquals("sound_not_idle", sequence.start(1, 1, 1, 2, 100));
        assertEquals("sound_unavailable", sequence.start(1, 1, null, 2, 100));
        assertEquals("invalid_flash_count", sequence.start(1, 1, 3, 4, 100));

        assertNull(sequence.start(1, 1, 3, 2, 100));
        long generation = sequence.generation();
        assertEquals("active_retrigger_ignored", sequence.start(1, 1, 3, 1, 150));
        assertEquals(generation, sequence.generation());
        assertEquals(2, sequence.target());
        assertEquals(0, sequence.completed());
    }

    @Test public void countsOnlyFreshOneToTwoCyclesForTargetsOneTwoAndThree() {
        for (int target = 1; target <= 3; target++) {
            HazardThanksSequence sequence = new HazardThanksSequence();
            assertNull(sequence.start(1, 1, 3, target, 1_000));
            for (int count = 1; count <= target; count++) {
                assertEquals(HazardThanksSequence.SOUND_EDGE,
                        sequence.observeSound(1, 1_100L + count));
                assertEquals(HazardThanksSequence.SOUND_UNCHANGED,
                        sequence.observeSound(1, 1_110L + count));
                assertEquals(count == target ? HazardThanksSequence.SOUND_TARGET_REACHED
                                : HazardThanksSequence.SOUND_CYCLE,
                        sequence.observeSound(2, 1_200L + count));
                assertEquals(count, sequence.completed());
                assertEquals(HazardThanksSequence.SOUND_UNCHANGED,
                        sequence.observeSound(2, 1_210L + count));
            }
        }
    }

    @Test public void rejectsInvalidSoundAndNeverCompletesPartialOrOldCycles() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertEquals("sound_invalid", sequence.start(1, 1, 65_535, 1, 0));
        assertNull(sequence.start(1, 1, 3, 1, 100));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(1, 200));
        assertEquals(HazardThanksSequence.SOUND_INVALID, sequence.observeSound(null, 250));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(2, 275));
        assertEquals(0, sequence.completed());
        sequence.cancel();

        assertNull(sequence.start(1, 1, 3, 1, 300));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(1, 400));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(3, 450));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(2, 500));
        assertEquals(0, sequence.completed());
    }

    @Test public void timesOutFromActivationAndEachCompletedCycleAndDiscardsStaleGeneration() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertNull(sequence.start(1, 1, 3, 3, 1_000));
        long firstGeneration = sequence.generation();
        assertFalse(sequence.timeoutDue(firstGeneration, 2_999));
        assertTrue(sequence.timeoutDue(firstGeneration, 3_000));
        sequence.cancel();

        assertNull(sequence.start(1, 1, 3, 3, 4_000));
        long secondGeneration = sequence.generation();
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(1, 4_100));
        assertEquals(HazardThanksSequence.SOUND_CYCLE, sequence.observeSound(2, 4_500));
        assertFalse(sequence.timeoutDue(secondGeneration, 6_499));
        assertTrue(sequence.timeoutDue(secondGeneration, 6_500));

        sequence.cancel();
        assertNull(sequence.start(1, 1, 3, 3, 7_000));
        assertNotEquals(secondGeneration, sequence.generation());
        assertFalse(sequence.timeoutDue(secondGeneration, 10_000));

        assertTrue(HazardThanksSequence.inputOccurredDuring(4_000, 4_000, 4_500));
        assertTrue(HazardThanksSequence.inputOccurredDuring(4_500, 4_000, 4_500));
        assertFalse(HazardThanksSequence.inputOccurredDuring(4_501, 4_000, 4_500));
        assertFalse(HazardThanksSequence.inputOccurredDuring(3_999, 4_000, 4_500));
    }

    @Test public void onlyManualObservationsAfterActivationCanAffectTheSequence() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertNull(sequence.start(1, 1, 3, 2, 1_000));
        assertFalse(sequence.observationIsAfterActivation(999));
        assertFalse(sequence.observationIsAfterActivation(1_000));
        assertTrue(sequence.observationIsAfterActivation(1_001));
    }

    @Test public void stalkAndObservedHazardDepartureCancelButInitialOffDoesNot() {
        for (int stalk = 2; stalk <= 5; stalk++) {
            HazardThanksSequence sequence = new HazardThanksSequence();
            assertNull(sequence.start(1, 1, 3, 3, 100));
            assertEquals("manual_stalk_" + stalk, sequence.observeStalk(stalk));
        }

        HazardThanksSequence sequence = new HazardThanksSequence();
        assertNull(sequence.start(1, 1, 3, 3, 100));
        assertNull(sequence.observeMode(1));
        assertNull(sequence.observeMode(6));
        assertEquals("mode_left_hazards_1", sequence.observeMode(1));
        sequence.cancel();
        assertFalse(sequence.active());

        for (int mode : new int[]{2, 3, 4, 5, 7, 8, 9}) {
            sequence = new HazardThanksSequence();
            assertNull(sequence.start(1, 1, 3, 3, 100));
            assertEquals("unexpected_mode_before_hazards_" + mode,
                    sequence.observeMode(mode));
        }
    }
}

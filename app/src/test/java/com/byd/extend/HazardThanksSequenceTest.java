package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class HazardThanksSequenceTest {
    @Test public void olderAndOverlappingCallbacksCannotUndoNewerGet() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertNull(sequence.start(1, 1, 3, 2, 61308497));
        sequence.activate(61308497);
        assertNull(sequence.modeObservationRejection(61309770, 61309779));
        assertNull(sequence.observeMode(6));
        assertNull(sequence.modeObservationRejection(61310000, 61310018));
        assertNull(sequence.observeMode(6));
        assertEquals("before_newer_get", sequence.modeObservationRejection(-1, 61309982));
        assertEquals("overlaps_get", sequence.modeObservationRejection(-1, 61310007));
        assertEquals("overlaps_get", sequence.modeObservationRejection(-1, 61310018));
        assertEquals("manual_stalk_2", sequence.observeStalk(2));
        assertNull(sequence.modeObservationRejection(-1, 61310019));
        assertEquals("mode_left_hazards_1", sequence.observeMode(1));
        sequence.cancel();
        assertNull(sequence.start(1, 1, 3, 2, 61311000));
        assertNull(sequence.modeObservationRejection(61311001, 61311002));
        assertNull(sequence.observeMode(1));
    }
    @Test public void rejectsUnsafeBaselineAndIgnoresActiveRetriggers() {
        HazardThanksSequence sequence = new HazardThanksSequence();
        assertEquals("mode_not_idle_or_direction", sequence.start(6, 1, 3, 2, 100));
        assertEquals("stalk_conflicts_with_mode", sequence.start(1, 2, 3, 2, 100));
        assertEquals("stalk_conflicts_with_mode", sequence.start(2, 5, 2, 2, 100));
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
            sequence.activate(1_000);
            assertNull(sequence.observeMode(6));
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
        sequence.activate(100);
        assertNull(sequence.observeMode(6));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(1, 200));
        assertEquals(HazardThanksSequence.SOUND_INVALID, sequence.observeSound(null, 250));
        assertEquals(HazardThanksSequence.SOUND_EDGE, sequence.observeSound(2, 275));
        assertEquals(0, sequence.completed());
        sequence.cancel();

        assertNull(sequence.start(1, 1, 3, 1, 300));
        sequence.activate(300);
        assertNull(sequence.observeMode(6));
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
        sequence.activate(4_000);
        assertNull(sequence.observeMode(6));
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
        sequence.activate(100);
        assertNull(sequence.observeMode(1));
        assertNull(sequence.observeMode(6));
        assertEquals("mode_left_hazards_1", sequence.observeMode(1));
        sequence.cancel();
        assertFalse(sequence.active());

        for (int mode : new int[]{2, 3, 4, 5, 7, 8, 9}) {
            sequence = new HazardThanksSequence();
            assertNull(sequence.start(1, 1, 3, 3, 100));
            sequence.activate(100);
            assertEquals("unexpected_mode_before_hazards_" + mode,
                    sequence.observeMode(mode));
        }
    }

    @Test public void overlapWaitsForPauseAndHeldStalkIsNotANewGesture() {
        for (int stalk : new int[]{2, 3, 4, 5}) {
            HazardThanksSequence s = new HazardThanksSequence();
            int direction = stalk <= 3 ? 2 : 4;
            assertNull(s.start(direction, stalk, 1, 2, 100));
            assertFalse(s.readyToActivate());
            assertFalse(s.expectedOwnHazards(stalk, 6));
            assertNull(s.observeStalk(stalk));
            assertNull(s.observeStalk(1));
            assertEquals(HazardThanksSequence.SOUND_EDGE, s.observeSound(2, 200));
            assertEquals(0, s.completed()); // Previous directional flash is not thanks.
            assertTrue(s.readyToActivate());
            s.activate(201);
            assertTrue(s.expectedOwnHazards(1, 6));
            assertFalse(s.expectedOwnHazards(stalk, 6));
            assertFalse(s.expectedOwnHazards(1, direction));
            assertNull(s.observeMode(direction));
            assertNull(s.observeMode(6));
            s.observeSound(1, 300);
            assertEquals(HazardThanksSequence.SOUND_CYCLE, s.observeSound(2, 500));
            assertEquals("manual_stalk_" + stalk, s.observeStalk(stalk));
            s.cancel();
            assertFalse(s.active());
        }
    }

    @Test public void recordedOverlapRunsCountTwoAndObserveNaturalReturn() {
        // GET edges from hazard-turn-overlap-20261117-114440-c7f4797e.
        // Actual counter only; no substituted OEM replies or physical-lamp claim.
        replay(2, 1, new long[]{52124013,52124102,52124107,52124508,52124510,
                52124871,52125214,52125636,52125645,52125986});
        replay(2, 2, new long[]{52141028,52141143,52141149,52141486,52141492,
                52141899,52142261,52142626,52142638,52142977});
        replay(4, 1, new long[]{52155792,52155906,52155912,52156263,52156264,
                52156687,52156987,52157419,52157441,52157750});
        replay(4, 4, new long[]{52167192,52167309,52167324,52167662,52167672,
                52168054,52168415,52168783,52168784,52169145});
    }

    private static void replay(int direction, int returnedMode, long[] t) {
        HazardThanksSequence s = new HazardThanksSequence();
        assertNull(s.start(direction, 1, 1, 2, t[0]));
        s.observeSound(2, t[1]);
        assertTrue(s.readyToActivate());
        s.activate(t[2]);
        assertTrue(s.observationIsAfterActivation(t[3]));
        assertNull(s.observeMode(6));
        s.observeSound(1, t[4]);
        assertEquals(HazardThanksSequence.SOUND_CYCLE, s.observeSound(2, t[5]));
        s.observeSound(1, t[6]);
        assertEquals(HazardThanksSequence.SOUND_TARGET_REACHED, s.observeSound(2, t[7]));
        s.cancel();
        s.awaitReturn(t[8]);
        assertFalse(s.returnObserved());
        assertTrue(s.observationIsAfterActivation(t[9]));
        assertNull(s.observeMode(returnedMode));
        assertTrue(s.returnObserved());
        assertEquals(direction == returnedMode, s.directionReturned());
        s.cancel();
        s.observeSound(1, t[9] + 1);
        s.observeSound(2, t[9] + 2);
        assertEquals(2, s.completed());
    }

    @Test public void phaseBoundariesRejectStaleInputsAndDoNotExtendTimeouts() {
        HazardThanksSequence s = new HazardThanksSequence();
        assertNull(s.start(4, 1, 1, 2, 1000));
        long generation = s.generation();
        assertFalse(s.timeoutDue(generation, 2999));
        assertTrue(s.timeoutDue(generation, 3000));
        s.observeSound(2, 2000);
        s.activate(2001);
        assertFalse(s.timeoutDue(generation, 4000));
        assertTrue(s.timeoutDue(generation, 4001));
        assertEquals("before_activation", s.modeObservationRejection(-1, 2000));
        assertEquals(HazardThanksSequence.SOUND_AMBIGUOUS, s.observeSound(1, 2200));
        assertEquals(0, s.completed());
        s.cancel();
        s.awaitReturn(3000);
        assertFalse(s.timeoutDue(generation, 10000));
        assertFalse(s.timeoutDue(s.generation(), 4999));
        assertTrue(s.timeoutDue(s.generation(), 5000));
        assertEquals("active_retrigger_ignored", s.start(1, 1, 3, 1, 4000));
        assertEquals("before_activation", s.modeObservationRejection(-1, 2999));
        assertNull(s.stalkObservationRejection(3100, 3120));
        assertEquals("overlaps_get", s.stalkObservationRejection(-1, 3110));
        assertNull(s.stalkObservationRejection(-1, 3121));
        assertEquals("get_overlaps_newer_observation", s.stalkObservationRejection(3110, 3130));
        assertEquals("manual_stalk_2", s.observeStalk(2));
        s.cancel();
        assertNull(s.observeMode(4));
        assertFalse(s.directionReturned());

        assertNull(s.start(2, 1, 1, 2, 5000));
        assertEquals("mode_changed_while_waiting_1", s.observeMode(1));
        s.cancel();
        assertFalse(s.readyToActivate());
    }
}

package com.byd.extend;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraButtonGesturePolicyTest {
    private static final long HOLD = 400L;
    private static final long DOUBLE = 300L;
    private static final int[][] NATIVE_LONG_PAIRS = {
            {294, -1}, {305, 306}, {304, 312}, {88, 303}, {87, 302}, {353, -1}
    };

    @Test
    public void singleWaitsPastInclusiveDoubleBoundary() {
        CameraButtonGesturePolicy policy = policy();
        List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 1));
        assertTrue(down(policy, 42, 100, bindings).consumed);
        up(policy, 42, 100, 150, bindings);
        assertTrue(policy.advance(450, bindings).actions.isEmpty());
        assertEquals(actions(CameraButtonBindings.Action.ReverseSource),
                policy.advance(451, bindings).actions);

        down(policy, 42, 1000, bindings);
        up(policy, 42, 1000, 1050, bindings);
        down(policy, 42, 1350, bindings);
        CameraButtonGesturePolicy.Result result = up(policy, 42, 1350, 1380, bindings);
        assertTrue(result.actions.isEmpty());
        assertTrue(policy.advance(2000, bindings).actions.isEmpty());
    }

    @Test
    public void doubleAndTripleClassifyOncePerPhysicalKey() {
        CameraButtonGesturePolicy policy = policy();
        List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.MirrorSource, 42,
                        CameraButtonBindings.Press.Double, 1),
                assignment(CameraButtonBindings.Action.MirrorVisibility, 42,
                        CameraButtonBindings.Press.Double, 1),
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 1));
        down(policy, 42, 100, bindings); up(policy, 42, 100, 140, bindings);
        down(policy, 42, 200, bindings);
        assertEquals(actions(CameraButtonBindings.Action.MirrorSource,
                        CameraButtonBindings.Action.MirrorVisibility),
                up(policy, 42, 200, 240, bindings).actions);

        down(policy, 42, 300, bindings); up(policy, 42, 300, 340, bindings);
        assertTrue(policy.advance(640, bindings).actions.isEmpty());
        assertEquals(actions(CameraButtonBindings.Action.ReverseSource),
                policy.advance(641, bindings).actions);
    }

    @Test
    public void timerAndNativeAliasesEmitOneHoldAndNextDownStartsFresh() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.MirrorSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            down(policy, pair[0], 100, bindings);
            assertEquals(actions(CameraButtonBindings.Action.MirrorSource),
                    policy.advance(500, bindings).actions);
            if (pair[1] >= 0) {
                assertTrue(event(policy, pair[1], KeyEvent.ACTION_DOWN, 0,
                        false, 500, 510, bindings).actions.isEmpty());
                up(policy, pair[0], 100, 550, bindings);
                assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                        false, 500, 560, bindings).actions.isEmpty());
            } else {
                up(policy, pair[0], 100, 550, bindings);
            }

            down(policy, pair[0], 600, bindings);
            assertEquals(actions(CameraButtonBindings.Action.MirrorSource),
                    policy.advance(1000, bindings).actions);
        }
    }

    @Test
    public void nativeAliasAloneIsHoldAndLateAliasTailIsDeduplicated() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.MirrorSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            assertEquals(actions(CameraButtonBindings.Action.MirrorSource),
                    event(policy, pair[1], KeyEvent.ACTION_DOWN, 0,
                            false, 100, 100, bindings).actions);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 100, 130, bindings).actions.isEmpty());

            down(policy, pair[0], 500, bindings);
            assertEquals(actions(CameraButtonBindings.Action.MirrorSource),
                    event(policy, pair[1], KeyEvent.ACTION_DOWN, 0,
                            false, 500, 600, bindings).actions);
            assertTrue(policy.advance(900, bindings).actions.isEmpty());
            up(policy, pair[0], 500, 950, bindings);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 500, 980, bindings).actions.isEmpty());

            down(policy, pair[0], 1500, bindings);
            policy.advance(1900, bindings);
            up(policy, pair[0], 1500, 1950, bindings);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_DOWN, 0,
                    false, 1960, 1960, bindings).actions.isEmpty());
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 1960, 1980, bindings).actions.isEmpty());
        }
    }

    @Test
    public void authoritativeMappingHasNoInventedNativeLongForPanoramaOrWheel() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            assertEquals(pair[0], CameraButtonGesturePolicy.canonicalKeyCode(pair[0]));
            if (pair[1] >= 0) {
                assertTrue(CameraButtonGesturePolicy.isNativeLongAlias(pair[1]));
                assertEquals(pair[0], CameraButtonGesturePolicy.canonicalKeyCode(pair[1]));
            }
        }
        assertFalse(CameraButtonGesturePolicy.isNativeLongAlias(294));
        assertFalse(CameraButtonGesturePolicy.isNativeLongAlias(353));
        assertFalse(CameraButtonGesturePolicy.isNativeLongAlias(352));
        assertEquals(352, CameraButtonGesturePolicy.canonicalKeyCode(352));
    }

    @Test
    public void concurrentKeysAndLostUpDoNotCrossClassify() {
        CameraButtonGesturePolicy policy = new CameraButtonGesturePolicy(HOLD, DOUBLE, 1_000L);
        List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.MirrorSource, 41,
                        CameraButtonBindings.Press.Single, 1),
                assignment(CameraButtonBindings.Action.MirrorVisibility, 42,
                        CameraButtonBindings.Press.Single, 1));
        down(policy, 41, 100, bindings);
        down(policy, 42, 120, bindings);
        up(policy, 42, 120, 150, bindings);
        up(policy, 41, 100, 170, bindings);
        assertEquals(actions(CameraButtonBindings.Action.MirrorVisibility,
                        CameraButtonBindings.Action.MirrorSource),
                policy.advance(471, bindings).actions);

        down(policy, 41, 1000, bindings);
        assertTrue(policy.advance(2000, bindings).actions.isEmpty());
        assertTrue(down(policy, 41, 2100, bindings).consumed);
    }

    @Test
    public void cancellationAndBindingChangesPreserveTailsWithoutActions() {
        CameraButtonGesturePolicy policy = policy();
        List<CameraButtonGesturePolicy.Assignment> reverse = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 7));
        down(policy, 42, 100, reverse);
        List<CameraButtonGesturePolicy.Assignment> changedContext = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 8));
        policy.bindingsChanged(changedContext);
        assertTrue(up(policy, 42, 100, 150, changedContext).consumed);
        assertTrue(policy.advance(1000, changedContext).actions.isEmpty());

        down(policy, 42, 1200, changedContext);
        event(policy, 42, KeyEvent.ACTION_DOWN, 0, true,
                1200, 1250, changedContext);
        assertTrue(up(policy, 42, 1200, 1300, changedContext).consumed);
        assertTrue(policy.advance(2000, changedContext).actions.isEmpty());
    }

    @Test
    public void reverseContextCancellationDoesNotCancelMirrorOnSameGesture() {
        CameraButtonGesturePolicy policy = policy();
        List<CameraButtonGesturePolicy.Assignment> initial = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 7),
                assignment(CameraButtonBindings.Action.MirrorVisibility, 42,
                        CameraButtonBindings.Press.Single, 1));
        down(policy, 42, 100, initial); up(policy, 42, 100, 150, initial);
        List<CameraButtonGesturePolicy.Assignment> changed = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 42,
                        CameraButtonBindings.Press.Single, 8),
                assignment(CameraButtonBindings.Action.MirrorVisibility, 42,
                        CameraButtonBindings.Press.Single, 1));
        assertEquals(actions(CameraButtonBindings.Action.MirrorVisibility),
                policy.advance(451, changed).actions);
    }

    @Test
    public void learningTargetsOneActionCanonicalizesAndTriggersNoBinding() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.ReverseSource, pair[0],
                            CameraButtonBindings.Press.Single, 1));
            policy.beginLearning(CameraButtonBindings.Action.MirrorSource);
            assertFalse(event(policy, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_DOWN, 0,
                    false, 10, 10, bindings).consumed);
            CameraButtonGesturePolicy.Result learned = event(policy, pair[1],
                    KeyEvent.ACTION_DOWN, 0, false, 100, 100, bindings);
            assertTrue(learned.consumed);
            assertEquals(CameraButtonBindings.Action.MirrorSource, learned.learnedAction);
            assertEquals(pair[0], learned.learnedKeyCode);
            assertTrue(learned.actions.isEmpty());
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 100, 130, bindings).consumed);
            policy.reset();
            assertFalse(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 100, 140, assignments()).consumed);
        }
    }

    @Test
    public void learningDrainsOwnedNativeFeedbackBeforeCapturingFreshDown() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.ReverseSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            down(policy, pair[0], 100, bindings);
            policy.beginLearning(CameraButtonBindings.Action.MirrorVisibility);

            CameraButtonGesturePolicy.Result nativeDown = event(policy, pair[1],
                    KeyEvent.ACTION_DOWN, 0, false, 200, 200, bindings);
            assertTrue(nativeDown.consumed);
            assertEquals(null, nativeDown.learnedAction);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 200, 220, bindings).consumed);
            assertTrue(up(policy, pair[0], 100, 240, bindings).consumed);

            CameraButtonGesturePolicy.Result fresh = down(policy, 42, 300, bindings);
            assertEquals(CameraButtonBindings.Action.MirrorVisibility, fresh.learnedAction);
            assertEquals(42, fresh.learnedKeyCode);
        }
    }

    @Test
    public void learningGuardsLateNativeTailThenLearnsSameButtonFreshCycle() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.ReverseSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            down(policy, pair[0], 100, bindings);
            policy.beginLearning(CameraButtonBindings.Action.MirrorSource);
            assertTrue(up(policy, pair[0], 100, 550, bindings).consumed);

            CameraButtonGesturePolicy.Result lateDown = event(policy, pair[1],
                    KeyEvent.ACTION_DOWN, 0, false, 560, 560, bindings);
            assertEquals(null, lateDown.learnedAction);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 560, 580, bindings).consumed);

            CameraButtonGesturePolicy.Result fresh = event(policy, pair[0],
                    KeyEvent.ACTION_DOWN, 0, false, 700, 700, bindings);
            assertEquals(CameraButtonBindings.Action.MirrorSource, fresh.learnedAction);
            assertEquals(pair[0], fresh.learnedKeyCode);
        }
    }

    @Test
    public void learningHonorsNativeGuardCreatedBeforeLearning() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.ReverseSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            down(policy, pair[0], 100, bindings);
            policy.advance(500, bindings);
            up(policy, pair[0], 100, 550, bindings);
            policy.beginLearning(CameraButtonBindings.Action.MirrorVisibility);

            CameraButtonGesturePolicy.Result late = event(policy, pair[1],
                    KeyEvent.ACTION_DOWN, 0, false, 560, 560, bindings);
            assertEquals(null, late.learnedAction);
            assertTrue(event(policy, pair[1], KeyEvent.ACTION_UP, 0,
                    false, 560, 580, bindings).consumed);
            CameraButtonGesturePolicy.Result fresh = down(policy, pair[0], 600, bindings);
            assertEquals(CameraButtonBindings.Action.MirrorVisibility, fresh.learnedAction);
            assertEquals(pair[0], fresh.learnedKeyCode);
        }
    }

    @Test
    public void freshOrdinaryDownInvalidatesNativeTailGuardAndLearnsImmediately() {
        for (int[] pair : NATIVE_LONG_PAIRS) {
            if (pair[1] < 0) continue;
            CameraButtonGesturePolicy policy = policy();
            List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                    assignment(CameraButtonBindings.Action.ReverseSource, pair[0],
                            CameraButtonBindings.Press.Hold, 1));
            down(policy, pair[0], 100, bindings);
            policy.advance(500, bindings);
            up(policy, pair[0], 100, 550, bindings);
            policy.beginLearning(CameraButtonBindings.Action.MirrorSource);

            CameraButtonGesturePolicy.Result fresh = down(policy, pair[0], 560, bindings);
            assertEquals(CameraButtonBindings.Action.MirrorSource, fresh.learnedAction);
            assertEquals(pair[0], fresh.learnedKeyCode);
        }
    }

    @Test
    public void learningCanCaptureSameKeyAfterLostOwnedTailExpires() {
        CameraButtonGesturePolicy policy = new CameraButtonGesturePolicy(HOLD, DOUBLE, 1_000L);
        List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.ReverseSource, 88,
                        CameraButtonBindings.Press.Hold, 1));
        down(policy, 88, 100, bindings);
        policy.advance(500, bindings);
        policy.beginLearning(CameraButtonBindings.Action.MirrorSource);
        policy.advance(1_100, bindings);

        CameraButtonGesturePolicy.Result fresh = down(policy, 88, 1_200, bindings);
        assertEquals(CameraButtonBindings.Action.MirrorSource, fresh.learnedAction);
        assertEquals(88, fresh.learnedKeyCode);
    }

    @Test
    public void disabledActionDoesNotOwnKeyAndRepeatTailSurvivesCancellation() {
        CameraButtonGesturePolicy policy = policy();
        assertFalse(down(policy, 42, 100, assignments()).consumed);
        List<CameraButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.MirrorVisibility, 42,
                        CameraButtonBindings.Press.Single, 1));
        assertTrue(event(policy, 42, KeyEvent.ACTION_DOWN, 2,
                false, 200, 250, bindings).consumed);
        policy.cancelActions();
        assertTrue(up(policy, 42, 200, 300, assignments()).consumed);
    }

    private static CameraButtonGesturePolicy policy() {
        return new CameraButtonGesturePolicy(HOLD, DOUBLE);
    }

    private static CameraButtonGesturePolicy.Assignment assignment(
            CameraButtonBindings.Action action, int key,
            CameraButtonBindings.Press press, long token) {
        return new CameraButtonGesturePolicy.Assignment(action,
                new CameraButtonBindings.Binding(key, press), token);
    }

    private static List<CameraButtonGesturePolicy.Assignment> assignments(
            CameraButtonGesturePolicy.Assignment... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static java.util.Set<CameraButtonBindings.Action> actions(
            CameraButtonBindings.Action... values) {
        java.util.EnumSet<CameraButtonBindings.Action> result =
                java.util.EnumSet.noneOf(CameraButtonBindings.Action.class);
        result.addAll(Arrays.asList(values));
        return result;
    }

    private static CameraButtonGesturePolicy.Result down(CameraButtonGesturePolicy policy,
            int key, long time, List<CameraButtonGesturePolicy.Assignment> bindings) {
        return event(policy, key, KeyEvent.ACTION_DOWN, 0, false, time, time, bindings);
    }

    private static CameraButtonGesturePolicy.Result up(CameraButtonGesturePolicy policy,
            int key, long downTime, long eventTime,
            List<CameraButtonGesturePolicy.Assignment> bindings) {
        return event(policy, key, KeyEvent.ACTION_UP, 0,
                false, downTime, eventTime, bindings);
    }

    private static CameraButtonGesturePolicy.Result event(CameraButtonGesturePolicy policy,
            int key, int action, int repeats, boolean cancelled,
            long downTime, long eventTime,
            List<CameraButtonGesturePolicy.Assignment> bindings) {
        return policy.onKey(key, action, repeats, cancelled,
                downTime, eventTime, eventTime, bindings);
    }
}

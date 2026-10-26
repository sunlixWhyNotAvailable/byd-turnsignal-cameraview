package com.byd.extend;

import android.view.KeyEvent;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
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
    public void hazardThanksClassifiesSteeringSingleDoubleAndHold() {
        CameraButtonGesturePolicy singlePolicy = policy();
        List<CameraButtonGesturePolicy.Assignment> singleBinding = assignments(
                assignment(CameraButtonBindings.Action.HazardThanks, 42,
                        CameraButtonBindings.Press.Single, 1));
        down(singlePolicy, 42, 100, singleBinding);
        up(singlePolicy, 42, 100, 150, singleBinding);
        assertEquals(actions(CameraButtonBindings.Action.HazardThanks),
                singlePolicy.advance(451, singleBinding).actions);

        CameraButtonGesturePolicy doublePolicy = policy();
        List<CameraButtonGesturePolicy.Assignment> doubleBinding = assignments(
                assignment(CameraButtonBindings.Action.HazardThanks, 42,
                        CameraButtonBindings.Press.Double, 1));
        down(doublePolicy, 42, 100, doubleBinding);
        up(doublePolicy, 42, 100, 140, doubleBinding);
        down(doublePolicy, 42, 200, doubleBinding);
        assertEquals(actions(CameraButtonBindings.Action.HazardThanks),
                up(doublePolicy, 42, 200, 240, doubleBinding).actions);

        CameraButtonGesturePolicy holdPolicy = policy();
        List<CameraButtonGesturePolicy.Assignment> holdBinding = assignments(
                assignment(CameraButtonBindings.Action.HazardThanks, 42,
                        CameraButtonBindings.Press.Hold, 1));
        down(holdPolicy, 42, 100, holdBinding);
        assertEquals(actions(CameraButtonBindings.Action.HazardThanks),
                holdPolicy.advance(500, holdBinding).actions);
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

package com.byd.extend;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public final class RescueButtonGesturePolicyTest {
    private static final CameraButtonBindings.Source SOS = CameraButtonBindings.Source.Sos;
    private static final CameraButtonBindings.Source HELP = CameraButtonBindings.Source.Help;

    @Test
    public void singleWaitsOnlyWhenDoubleIsAssignedAndBoundaryIsInclusive() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorSource, SOS,
                        CameraButtonBindings.Press.Double));

        RescueButtonGesturePolicy.Result first = policy.onEvent(3, 100L, bindings);
        assertNull(first.action);
        assertEquals(901L, policy.nextDeadline());
        assertNull(policy.advance(900L, bindings).action);

        RescueButtonGesturePolicy.Result doubled = policy.onEvent(3, 900L, bindings);
        assertEquals(CameraButtonBindings.Action.MirrorSource, doubled.action);
        assertEquals(CameraButtonBindings.Press.Double, doubled.press);
        assertEquals(Long.MAX_VALUE, policy.nextDeadline());

        policy.onEvent(3, 1_000L, bindings);
        assertNull(policy.advance(1_800L, bindings).action);
        RescueButtonGesturePolicy.Result single = policy.advance(1_801L, bindings);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone, single.action);
        assertEquals(CameraButtonBindings.Press.Single, single.press);
    }

    @Test
    public void singleFiresImmediatelyWithoutDoubleAndSameSourceShortsCanRepeat() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single));

        RescueButtonGesturePolicy.Result first = policy.onEvent(3, 100L, bindings);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone, first.action);
        assertEquals(CameraButtonBindings.Press.Single, first.press);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone,
                policy.onEvent(3, 100L, bindings).action);
    }

    @Test
    public void hazardThanksClassifiesSosSingleAndHelpDoubleButNotRescueHold() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> sosBindings = assignments(
                assignment(CameraButtonBindings.Action.HazardThanks, SOS,
                        CameraButtonBindings.Press.Single));
        List<RescueButtonGesturePolicy.Assignment> helpBindings = assignments(
                assignment(CameraButtonBindings.Action.HazardThanks, HELP,
                        CameraButtonBindings.Press.Double));

        RescueButtonGesturePolicy.Result sos = policy.onEvent(3, 100L, sosBindings);
        assertEquals(CameraButtonBindings.Action.HazardThanks, sos.action);
        assertEquals(SOS, sos.source);
        assertEquals(CameraButtonBindings.Press.Single, sos.press);

        assertNull(policy.onEvent(4, 200L, helpBindings).action);
        RescueButtonGesturePolicy.Result help = policy.onEvent(4, 1_000L, helpBindings);
        assertEquals(CameraButtonBindings.Action.HazardThanks, help.action);
        assertEquals(HELP, help.source);
        assertEquals(CameraButtonBindings.Press.Double, help.press);
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                SOS, CameraButtonBindings.Press.Hold));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                HELP, CameraButtonBindings.Press.Hold));
    }

    @Test
    public void shortBeyondDoubleBoundaryFiresFirstSingleAndStartsANewWindow() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorSource, SOS,
                        CameraButtonBindings.Press.Double));

        policy.onEvent(3, 100L, bindings);
        RescueButtonGesturePolicy.Result firstSingle = policy.onEvent(3, 901L, bindings);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone, firstSingle.action);
        assertEquals(CameraButtonBindings.Press.Single, firstSingle.press);
        assertEquals(1_702L, policy.nextDeadline());
        assertNull(policy.advance(1_701L, bindings).action);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone,
                policy.advance(1_702L, bindings).action);
    }

    @Test
    public void rescueButtonsHaveIndependentDoubleWindowsAndReturnOneAction() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.MirrorSource, SOS,
                        CameraButtonBindings.Press.Double),
                assignment(CameraButtonBindings.Action.AvasMicrophone, HELP,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorVisibility, HELP,
                        CameraButtonBindings.Press.Double));

        policy.onEvent(3, 100L, bindings);
        policy.onEvent(4, 200L, bindings);
        RescueButtonGesturePolicy.Result sosDouble = policy.onEvent(3, 900L, bindings);
        assertEquals(CameraButtonBindings.Action.MirrorSource, sosDouble.action);
        assertEquals(SOS, sosDouble.source);
        assertEquals(CameraButtonBindings.Press.Double, sosDouble.press);

        RescueButtonGesturePolicy.Result helpSingle = policy.advance(1_001L, bindings);
        assertEquals(CameraButtonBindings.Action.AvasMicrophone, helpSingle.action);
        assertEquals(HELP, helpSingle.source);
        assertEquals(CameraButtonBindings.Press.Single, helpSingle.press);
    }

    @Test
    public void holdCancelsOnlyItsSourceAndUnknownOrResetCancelsEveryPendingShort() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> bindings = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorSource, SOS,
                        CameraButtonBindings.Press.Double),
                assignment(CameraButtonBindings.Action.MirrorVisibility, HELP,
                        CameraButtonBindings.Press.Double));

        policy.onEvent(3, 100L, bindings);
        policy.onEvent(4, 120L, bindings);
        RescueButtonGesturePolicy.Result hold = policy.onEvent(1, 150L, bindings);
        assertTrue(hold.hold);
        assertEquals(SOS, hold.source);
        assertEquals(CameraButtonBindings.Press.Hold, hold.press);
        assertNull(hold.action);
        RescueButtonGesturePolicy.Result helpSingle = policy.advance(921L, bindings);
        assertNull(helpSingle.action);
        assertEquals(HELP, helpSingle.source);
        assertEquals(CameraButtonBindings.Press.Single, helpSingle.press);
        assertNull(policy.advance(1_000L, bindings).action);

        policy.onEvent(3, 1_100L, bindings);
        RescueButtonGesturePolicy.Result unknown = policy.onEvent(85, 1_200L, bindings);
        assertNull(unknown.action);
        assertFalse(unknown.hold);
        assertNull(policy.advance(2_000L, bindings).action);

        policy.onEvent(3, 2_100L, bindings);
        policy.reset();
        assertNull(policy.advance(3_000L, bindings).action);
    }

    @Test
    public void changedAssignmentsCancelPendingShorts() {
        RescueButtonGesturePolicy policy = new RescueButtonGesturePolicy();
        List<RescueButtonGesturePolicy.Assignment> original = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorSource, SOS,
                        CameraButtonBindings.Press.Double));
        List<RescueButtonGesturePolicy.Assignment> changed = assignments(
                assignment(CameraButtonBindings.Action.AvasMicrophone, SOS,
                        CameraButtonBindings.Press.Single),
                assignment(CameraButtonBindings.Action.MirrorSource, HELP,
                        CameraButtonBindings.Press.Double));

        policy.onEvent(3, 100L, original);
        assertNull(policy.advance(1_000L, changed).action);
        assertEquals(Long.MAX_VALUE, policy.nextDeadline());
    }

    private static RescueButtonGesturePolicy.Assignment assignment(
            CameraButtonBindings.Action action, CameraButtonBindings.Source source,
            CameraButtonBindings.Press press) {
        return new RescueButtonGesturePolicy.Assignment(action,
                new CameraButtonBindings.Binding(source, press));
    }

    private static List<RescueButtonGesturePolicy.Assignment> assignments(
            RescueButtonGesturePolicy.Assignment... values) {
        return Collections.unmodifiableList(Arrays.asList(values));
    }
}

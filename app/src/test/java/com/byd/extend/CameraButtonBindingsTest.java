package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CameraButtonBindingsTest {
    @Test
    public void sourceSwitchPreservesSteeringAssignmentAcrossSettingsReload() {
        for (CameraButtonBindings.Action action : new CameraButtonBindings.Action[] {
                CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Action.MirrorVisibility,
                CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Action.HazardThanks }) {
            CameraRuntimePreferences preferences = new CameraRuntimePreferences();
            CameraButtonBindings.Press originalPress = action == CameraButtonBindings.Action.AvasMicrophone
                    ? CameraButtonBindings.Press.Single : CameraButtonBindings.Press.Hold;
            String key = action == CameraButtonBindings.Action.MirrorSource
                    ? CameraButtonBindings.MIRROR_SOURCE_KEY_CODE
                    : action == CameraButtonBindings.Action.MirrorVisibility
                            ? CameraButtonBindings.MIRROR_VISIBILITY_KEY_CODE
                            : action == CameraButtonBindings.Action.AvasMicrophone
                                    ? CameraButtonBindings.MICROPHONE_KEY_CODE
                                    : CameraButtonBindings.HAZARD_THANKS_KEY_CODE;
            String press = action == CameraButtonBindings.Action.MirrorSource
                    ? CameraButtonBindings.MIRROR_SOURCE_PRESS
                    : action == CameraButtonBindings.Action.MirrorVisibility
                            ? CameraButtonBindings.MIRROR_VISIBILITY_PRESS
                            : action == CameraButtonBindings.Action.AvasMicrophone
                                    ? CameraButtonBindings.MICROPHONE_PRESS
                                    : CameraButtonBindings.HAZARD_THANKS_PRESS;
            // Actual pre-upgrade preference input: no saved steering-press backup yet.
            preferences.edit().putInt(key, 88).putString(press, originalPress.name()).apply();

            CameraButtonBindings.save(preferences, action, CameraButtonBindings.loadForSource(
                    preferences, action, CameraButtonBindings.Source.Sos));
            assertEquals(CameraButtonBindings.Press.Single,
                    CameraButtonBindings.load(preferences, action).press);
            CameraButtonBindings.save(preferences, action, new CameraButtonBindings.Binding(
                    CameraButtonBindings.Source.Help,
                    action == CameraButtonBindings.Action.AvasMicrophone
                            ? CameraButtonBindings.Press.Single : CameraButtonBindings.Press.Double));
            assertEquals(88, preferences.getInt(key, -1));

            // Exercise the shipped preference replica, not a mocked SharedPreferences.
            CameraRuntimePreferences reopened = new CameraRuntimePreferences();
            reopened.reconcile(preferences.getAll());
            CameraButtonBindings.save(reopened, action, CameraButtonBindings.loadForSource(
                    reopened, action, CameraButtonBindings.Source.AndroidKey));
            assertEquals(new CameraButtonBindings.Binding(88, originalPress),
                    CameraButtonBindings.load(reopened, action));
        }
    }

    @Test
    public void explicitResetDoesNotRestoreTheRememberedKey() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        CameraButtonBindings.Action action = CameraButtonBindings.Action.MirrorVisibility;
        CameraButtonBindings.save(preferences, action,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Hold));
        CameraButtonBindings.save(preferences, action, CameraButtonBindings.loadForSource(
                preferences, action, CameraButtonBindings.Source.Help));
        CameraButtonBindings.reset(preferences, action);
        assertFalse(CameraButtonBindings.loadForSource(preferences, action,
                CameraButtonBindings.Source.AndroidKey).isAssigned());

        CameraButtonBindings.save(preferences, action,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Hold));
        CameraButtonBindings.save(preferences, action, CameraButtonBindings.loadForSource(
                preferences, action, CameraButtonBindings.Source.Sos));
        CameraButtonBindings.reset(preferences);
        assertEquals(new CameraButtonBindings.Binding(-1, CameraButtonBindings.Press.Single),
                CameraButtonBindings.loadForSource(preferences, action,
                        CameraButtonBindings.Source.AndroidKey));

        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.HazardThanks,
                new CameraButtonBindings.Binding(305, CameraButtonBindings.Press.Hold));
        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.HazardThanks,
                new CameraButtonBindings.Binding(CameraButtonBindings.Source.Help,
                        CameraButtonBindings.Press.Double));
        CameraButtonBindings.reset(preferences);
        assertEquals(new CameraButtonBindings.Binding(-1, CameraButtonBindings.Press.Single),
                CameraButtonBindings.load(preferences, CameraButtonBindings.Action.HazardThanks));
    }

    @Test
    public void legacyConstructorRemainsAnAndroidKeyBinding() {
        CameraButtonBindings.Binding binding = new CameraButtonBindings.Binding(
                303, CameraButtonBindings.Press.Double);

        assertEquals(CameraButtonBindings.Source.AndroidKey, binding.source);
        assertEquals(88, binding.keyCode);
        assertEquals(CameraButtonBindings.Press.Double, binding.press);
        assertTrue(binding.isAssigned());
        assertEquals(new CameraButtonBindings.Binding(88, CameraButtonBindings.Press.Double),
                binding);
    }

    @Test
    public void rescueBindingIsAssignedWithoutAnAndroidKeyAndEqualityIncludesSource() {
        CameraButtonBindings.Binding sos = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Sos, 88, CameraButtonBindings.Press.Double);
        CameraButtonBindings.Binding sameSos = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Double);
        CameraButtonBindings.Binding help = new CameraButtonBindings.Binding(
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Double);

        assertEquals(CameraButtonBindings.UNASSIGNED, sos.keyCode);
        assertTrue(sos.isAssigned());
        assertEquals(sameSos, sos);
        assertEquals(sameSos.hashCode(), sos.hashCode());
        assertFalse(sos.equals(help));
        assertFalse(new CameraButtonBindings.Binding(CameraButtonBindings.Source.AndroidKey,
                CameraButtonBindings.UNASSIGNED, CameraButtonBindings.Press.Double).isAssigned());
    }

    @Test
    public void rescueOptionsKeepCallsGesturesSeparateFromLegacyGestures() {
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Single));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasMicrophone,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Double));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Double));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Hold));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.AvasEngine,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Single));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.ReverseSource,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Single));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.MirrorSource,
                CameraButtonBindings.Source.AndroidKey, CameraButtonBindings.Press.Hold));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                CameraButtonBindings.Source.AndroidKey, CameraButtonBindings.Press.Hold));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Double));
        assertTrue(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                CameraButtonBindings.Source.Help, CameraButtonBindings.Press.Single));
        assertFalse(CameraButtonBindings.supports(CameraButtonBindings.Action.HazardThanks,
                CameraButtonBindings.Source.Sos, CameraButtonBindings.Press.Hold));
    }

    @Test
    public void hazardThanksParticipatesInRescueBindingConflicts() {
        CameraRuntimePreferences preferences = new CameraRuntimePreferences();
        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.MirrorSource,
                new CameraButtonBindings.Binding(CameraButtonBindings.Source.Sos,
                        CameraButtonBindings.Press.Double));

        assertTrue(CameraButtonBindings.hasRescueConflict(preferences,
                CameraButtonBindings.Action.HazardThanks,
                new CameraButtonBindings.Binding(CameraButtonBindings.Source.Sos,
                        CameraButtonBindings.Press.Double)));
        assertFalse(CameraButtonBindings.hasRescueConflict(preferences,
                CameraButtonBindings.Action.HazardThanks,
                new CameraButtonBindings.Binding(CameraButtonBindings.Source.Sos,
                        CameraButtonBindings.Press.Hold)));
    }
}

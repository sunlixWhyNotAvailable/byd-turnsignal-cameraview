package com.byd.extend;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class CameraButtonBindingsTest {
    @Test
    public void defaultsAndMalformedValuesAreUnassignedSingle() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        assertEquals(new CameraButtonBindings.Binding(-1, CameraButtonBindings.Press.Single),
                CameraButtonBindings.load(preferences,
                        CameraButtonBindings.Action.MirrorSource));

        preferences.putString(CameraButtonBindings.MIRROR_SOURCE_KEY_CODE, "88");
        preferences.putString(CameraButtonBindings.MIRROR_SOURCE_PRESS, "Long");
        assertEquals(new CameraButtonBindings.Binding(-1, CameraButtonBindings.Press.Single),
                CameraButtonBindings.load(preferences,
                        CameraButtonBindings.Action.MirrorSource));
    }

    @Test
    public void mirrorBindingsRoundTripIndependentlyAndAllowDuplicates() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraButtonBindings.Binding binding = new CameraButtonBindings.Binding(
                88, CameraButtonBindings.Press.Double);
        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.MirrorSource, binding);
        CameraButtonBindings.save(preferences,
                CameraButtonBindings.Action.MirrorVisibility, binding);

        assertEquals(binding, CameraButtonBindings.load(preferences,
                CameraButtonBindings.Action.MirrorSource));
        assertEquals(binding, CameraButtonBindings.load(preferences,
                CameraButtonBindings.Action.MirrorVisibility));
    }

    @Test
    public void reverseBindingKeepsLegacyKeyAndSinglePress() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.ReverseSource,
                new CameraButtonBindings.Binding(42, CameraButtonBindings.Press.Hold));

        assertEquals(42, ReverseSteeringButtonPreferences.load(preferences));
        assertEquals(new CameraButtonBindings.Binding(42, CameraButtonBindings.Press.Single),
                CameraButtonBindings.load(preferences,
                        CameraButtonBindings.Action.ReverseSource));
    }

    @Test
    public void nativeLongCodesAreExposedAndSavedAsShortLabels() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        int[][] pairs = {{305, 306}, {304, 312}, {88, 303}, {87, 302}};
        for (int[] pair : pairs) {
            CameraButtonBindings.save(preferences, CameraButtonBindings.Action.MirrorSource,
                    new CameraButtonBindings.Binding(pair[1], CameraButtonBindings.Press.Hold));
            assertEquals(new CameraButtonBindings.Binding(
                            pair[0], CameraButtonBindings.Press.Hold),
                    CameraButtonBindings.load(preferences,
                            CameraButtonBindings.Action.MirrorSource));
        }
        assertEquals(294, new CameraButtonBindings.Binding(
                294, CameraButtonBindings.Press.Hold).keyCode);
        assertEquals(353, new CameraButtonBindings.Binding(
                353, CameraButtonBindings.Press.Hold).keyCode);
        assertEquals(352, new CameraButtonBindings.Binding(
                352, CameraButtonBindings.Press.Hold).keyCode);
    }

    @Test
    public void resetRestoresEveryBindingToUnassignedSingle() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        for (CameraButtonBindings.Action action : CameraButtonBindings.Action.values()) {
            CameraButtonBindings.save(preferences, action,
                    new CameraButtonBindings.Binding(42, CameraButtonBindings.Press.Double));
        }
        CameraButtonBindings.reset(preferences);
        for (CameraButtonBindings.Action action : CameraButtonBindings.Action.values()) {
            assertEquals(new CameraButtonBindings.Binding(-1,
                            CameraButtonBindings.Press.Single),
                    CameraButtonBindings.load(preferences, action));
        }
    }

    @Test
    public void rowResetKeepsItsPressAndDoesNotChangeOtherRows() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraButtonBindings.Binding source = new CameraButtonBindings.Binding(
                42, CameraButtonBindings.Press.Hold);
        CameraButtonBindings.Binding visibility = new CameraButtonBindings.Binding(
                43, CameraButtonBindings.Press.Double);
        CameraButtonBindings.save(
                preferences, CameraButtonBindings.Action.MirrorSource, source);
        CameraButtonBindings.save(
                preferences, CameraButtonBindings.Action.MirrorVisibility, visibility);

        CameraButtonBindings.reset(preferences, CameraButtonBindings.Action.MirrorSource);

        assertEquals(new CameraButtonBindings.Binding(
                        -1, CameraButtonBindings.Press.Hold),
                CameraButtonBindings.load(preferences,
                        CameraButtonBindings.Action.MirrorSource));
        assertEquals(visibility, CameraButtonBindings.load(preferences,
                CameraButtonBindings.Action.MirrorVisibility));

        CameraButtonBindings.reset(preferences,
                CameraButtonBindings.Action.MirrorVisibility);
        assertEquals(new CameraButtonBindings.Binding(
                        -1, CameraButtonBindings.Press.Double),
                CameraButtonBindings.load(preferences,
                        CameraButtonBindings.Action.MirrorVisibility));
    }
}

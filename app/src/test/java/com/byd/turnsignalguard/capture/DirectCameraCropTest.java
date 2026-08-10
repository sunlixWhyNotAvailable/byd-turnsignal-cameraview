package com.byd.turnsignalguard.capture;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

public final class DirectCameraCropTest {
    private static final float EPSILON = 0.0001f;

    @Test
    public void numericEntryUsesOnePercentFloorWhileTouchKeepsEighteenPercent() {
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "0.99", "1.00", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "1,00", "0,99", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT));

        DirectCameraCrop accepted = DirectCameraCrop.parsePercent(
                "0", "0", "1,00", "1.00", DirectCameraCrop.ASPECT_FREE,
                0, CameraRotation.MODE_FIT);
        assertEquals(SourceCropPolicy.MIN_SIZE, accepted.width, 0.0f);
        assertEquals(SourceCropPolicy.MIN_SIZE, accepted.height, 0.0f);

        DirectCameraCrop touched = DirectCameraCrop.of(
                0.2f, 0.2f, 0.4f, 0.4f, DirectCameraCrop.ASPECT_FREE)
                .resize(DirectCameraCrop.EDGE_RIGHT, -1.0f, 0.0f);
        assertEquals(0.18f, touched.width, EPSILON);
    }

    @Test
    public void activeRawAndCorrectedValuesMigrateOnceAroundTheirCenters() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 0), 0.4f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 1), 0.4f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 2), 0.005f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 3), 0.0025f);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 4),
                DirectCameraCrop.ASPECT_FREE);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 5), 0);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 6),
                CameraRotation.MODE_FIT);

        DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
        assertEquals(0.4025f, raw.left + raw.width / 2.0f, EPSILON);
        assertEquals(0.40125f, raw.top + raw.height / 2.0f, EPSILON);
        assertEquals(2.0f, raw.width / raw.height, EPSILON);
        assertEquals(0.02f, raw.width, EPSILON);
        assertEquals(0.01f, raw.height, EPSILON);

        String correctedPrefix = "direct_crop_v3_corrected_" + profile.id + "_";
        preferences.putFloat(correctedPrefix + "left", 0.7f);
        preferences.putFloat(correctedPrefix + "top", 0.3f);
        preferences.putFloat(correctedPrefix + "width", 0.004f);
        preferences.putFloat(correctedPrefix + "height", 0.008f);
        DirectCameraCrop corrected = DirectCameraCrop.loadCorrected(
                preferences, profile, raw);
        assertEquals(0.702f, corrected.left + corrected.width / 2.0f, EPSILON);
        assertEquals(0.304f, corrected.top + corrected.height / 2.0f, EPSILON);
        assertEquals(0.01f, corrected.width, EPSILON);
        assertEquals(0.02f, corrected.height, EPSILON);

        Map<String, ?> afterFirstLoad = new HashMap<>(preferences.getAll());
        DirectCameraCrop secondRaw = DirectCameraCrop.load(preferences, profile);
        DirectCameraCrop secondCorrected = DirectCameraCrop.loadCorrected(
                preferences, profile, secondRaw);
        assertEquals(afterFirstLoad, preferences.getAll());
        assertEquals(raw.width, secondRaw.width, 0.0f);
        assertEquals(corrected.height, secondCorrected.height, 0.0f);
    }

    @Test
    public void alignedRotationRejectsImpossibleFloorAndKeepsAcceptedGeometryValid() {
        assertThrows(IllegalArgumentException.class, () -> DirectCameraCrop.parsePercent(
                "0", "0", "100", "1", DirectCameraCrop.ASPECT_FREE,
                90, CameraRotation.MODE_ALIGNED));

        DirectCameraCrop accepted = DirectCameraCrop.parsePercent(
                "0", "0", "100", "2", DirectCameraCrop.ASPECT_FREE,
                90, CameraRotation.MODE_ALIGNED);
        assertTrue(accepted.width >= SourceCropPolicy.MIN_SIZE);
        assertTrue(accepted.height >= SourceCropPolicy.MIN_SIZE);
        SourceCropPolicy.requireValid(
                accepted.left, accepted.top, accepted.width, accepted.height);
    }

    @Test
    public void impossibleLegacyAlignedValueFallsBackOnceAndThenStaysStable() {
        TestSharedPreferences preferences = new TestSharedPreferences();
        CameraProfile profile = CameraProfile.of(CameraProfile.REAR_LEFT);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 0), 0.0f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 1), 0.0f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 2), 1.0f);
        preferences.putFloat(DirectCameraCrop.preferenceKey(profile, 3), 0.01f);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 4),
                DirectCameraCrop.ASPECT_FREE);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 5), 90);
        preferences.putInt(DirectCameraCrop.preferenceKey(profile, 6),
                CameraRotation.MODE_ALIGNED);

        DirectCameraCrop first = DirectCameraCrop.load(preferences, profile);
        Map<String, ?> afterFirstLoad = new HashMap<>(preferences.getAll());
        DirectCameraCrop second = DirectCameraCrop.load(preferences, profile);

        assertEquals(afterFirstLoad, preferences.getAll());
        assertEquals(first.left, second.left, 0.0f);
        assertEquals(first.top, second.top, 0.0f);
        assertEquals(first.width, second.width, 0.0f);
        assertEquals(first.height, second.height, 0.0f);
        assertTrue(second.width >= SourceCropPolicy.MIN_SIZE);
        assertTrue(second.height >= SourceCropPolicy.MIN_SIZE);
    }

    @Test
    public void alignedTouchMutationKeepsLastValidCropInsteadOfEscaping() {
        DirectCameraCrop valid = DirectCameraCrop.requireNormalized(
                0.0f, 0.4f, 0.01f, 0.01f, DirectCameraCrop.ASPECT_FREE,
                45, CameraRotation.MODE_ALIGNED);

        DirectCameraCrop result = CameraCropOverlayView.keepLastValid(
                valid, () -> valid.resize(
                        DirectCameraCrop.EDGE_RIGHT, 1.0f, 0.0f));

        assertEquals(valid, result);
        assertTrue(result.width >= SourceCropPolicy.MIN_SIZE);
        assertTrue(result.height >= SourceCropPolicy.MIN_SIZE);
    }
}

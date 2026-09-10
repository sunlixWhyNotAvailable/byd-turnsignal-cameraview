package com.byd.extend

import com.byd.extend.ui.*
import org.junit.Assert.*
import org.junit.Test

class CameraBorderUiTest {
    @Test fun snapshotReadsIndependentBordersForEveryEditableSource() {
        val prefs = TestSharedPreferences()
        val border = CameraBorderSettings.Border(6, 0xFF12ABEF.toInt())
        for (profile in CameraProfile.values()) CameraBorderSettings.writeBlind(prefs, profile, border)
        for (profile in ParkingCameraProfile.values()) CameraBorderSettings.writeParking(prefs, profile, border)
        for (pane in 1..3) for (front in listOf(false, true))
            CameraBorderSettings.writeReverse(prefs, pane, front, border)
        for (pane in listOf(ReverseCameraLayout.BACKGROUND_PANE_ID, ReverseCameraLayout.WIDGET_PANE_ID))
            CameraBorderSettings.writeReverseElement(prefs, pane, border)
        CameraBorderSettings.writeMirror(prefs, false, border)
        CameraBorderSettings.writeMirror(prefs, true, CameraBorderSettings.Border(9, 0xFF112233.toInt()))
        val state = readProductionUiState(prefs, false, false)
        val profiles = state.blind.profiles.values + state.parking.views.values.map { it.profile } +
            state.reverse.profiles.values + state.mirror.profile
        assertEquals(23, profiles.size) // four Blind, eight Parking, ten Reverse IDs, one selected Mirror
        profiles.forEach {
            assertEquals("6", it.borderWidth)
            assertEquals(border.borderArgb, it.borderArgb)
        }
        prefs.edit().putBoolean(RearviewMirrorSettings.PREF_FRONT_INTEGRATED, true)
            .putBoolean(RearviewMirrorSettings.PREF_SHOW_FRONT, true).apply()
        val front = readProductionUiState(prefs, false, false).mirror
        assertEquals("9", front.profile.borderWidth)
        assertEquals("9", front.borderWidth)
        assertEquals(0xFF112233.toInt(), front.borderArgb)
    }

    @Test fun sourceBoundBorderIntentKeepsOriginalMirrorIdentity() {
        val action = BydExtendUiAction.SetProfileBorder(CameraProfileId.Mirror, width = "4")
            .forMirrorSource(true) as BydExtendUiAction.SetProfileBorder
        assertEquals(true, action.mirrorFront)
        assertEquals("4", action.width)
    }

    @Test fun permissionsToneDoesNotChangeAdbOrLocationTone() {
        val state = CameraProbeActivity.productionHeader(LocalAdbClient.AccessState.Status.OK, true, true, false)
        assertEquals(StatusTone.Error, state.permissions.tone)
        assertEquals(StatusTone.Ok, state.adb.tone)
        assertEquals(StatusTone.Ok, state.location.tone)
        assertTrue(state.permissions.visible)
    }
}

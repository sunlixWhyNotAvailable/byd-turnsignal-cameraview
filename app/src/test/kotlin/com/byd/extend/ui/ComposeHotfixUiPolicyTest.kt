package com.byd.extend

import com.byd.extend.ui.CameraDisplayGeometry
import com.byd.extend.ui.CameraGroup
import com.byd.extend.ui.CameraProfileId
import com.byd.extend.ui.CameraSide
import com.byd.extend.ui.ReverseElement
import com.byd.extend.ui.ReverseUiState
import com.byd.extend.ui.StatusTone
import com.byd.extend.ui.StatusUiState
import com.byd.extend.ui.hasAnyFrontIntegration
import com.byd.extend.ui.panoramaStatusForDisplay
import com.byd.extend.ui.NumericDraftPolicy
import com.byd.extend.ui.ParkingView
import com.byd.extend.ui.RootTab
import com.byd.extend.ui.UiLanguage
import com.byd.extend.ui.UiStrings
import com.byd.extend.ui.bottomNavigationEqualWidth
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeHotfixUiPolicyTest {
    @Test
    fun reverseGearSwitchIsTheSingleGlobalRowImmediatelyAfterEnable() {
        val source = File("src/main/kotlin/com/byd/extend/ui/ReverseScreen.kt").readText()
        val controls = source.substring(
            source.indexOf("val profileControls:"),
            source.indexOf("ScreenSurface(colors"),
        )
        val enhanced = controls.indexOf("Enhanced reverse view")
        val gear = controls.indexOf("Switch cameras by gear")
        val widget = controls.indexOf("selected == ReverseElement.Widget")
        val integration = controls.indexOf("Integrate front camera")
        assertTrue(enhanced >= 0)
        assertTrue(gear > enhanced)
        assertTrue(widget > gear)
        assertTrue(integration > gear)
        assertEquals(1, Regex("ToggleId\\.ReverseSwitchByGear").findAll(controls).count())
        assertTrue(controls.contains("enabled = gearSwitchEnabled"))
        assertEquals(2, Regex("profileControls = profileControls").findAll(source).count())
    }

    @Test
    fun mirrorEnableUsesTheSharedCompactSwitchDefault() {
        val source = File("src/main/kotlin/com/byd/extend/ui/MirrorScreen.kt").readText()
        val header = source.substring(
            source.indexOf("private fun ColumnScope.MirrorProfileHeader"),
            source.indexOf("private fun ColumnScope.MirrorParameters"),
        )
        assertTrue(header.contains("pending = state.operation.pending"))
        assertTrue(header.contains("enabled = state.operation.enabled"))
        assertFalse(header.contains("compactSwitch = false"))

        val primitives = File("src/main/kotlin/com/byd/extend/ui/UiPrimitives.kt").readText()
        val switch = primitives.substring(
            primitives.indexOf("internal fun AppSwitch("),
            primitives.indexOf("internal fun Segmented("),
        )
        assertTrue(switch.contains("val width = if (compact) 42.dp else 56.dp"))
        assertTrue(switch.contains("val height = if (compact) 27.dp else 32.dp"))
    }

    @Test
    fun sharedSwitchPendingStateOnlyGatesInputAndNeverChangesVisuals() {
        val primitives = File("src/main/kotlin/com/byd/extend/ui/UiPrimitives.kt").readText()
        val switch = primitives.substring(
            primitives.indexOf("internal fun AppSwitch("),
            primitives.indexOf("internal fun Segmented("),
        )
        assertTrue(switch.contains("rememberPressFeedback(enabled && !pending)"))
        assertTrue(switch.contains("enabled = enabled && !pending"))
        assertTrue(switch.contains("if (checked) knob else knobOff"))
        assertTrue(switch.contains("if (checked) colors.accent else colors.disabled"))
        assertFalse(switch.contains("if (pending)"))
        assertFalse(switch.contains("knobPending"))
        assertFalse(switch.contains("colors.yellow"))
        assertFalse(switch.contains("colors.yellowSoft"))
    }

    @Test
    fun bottomNavigationGivesDebugWidthToSignalsAndFitsAllLocalizedTitles() {
        // 1920 px at the conservative 320 dpi viewport is 960 dp; the app shell removes 36 dp.
        val barWidth = 924.dp
        val equalWidth = bottomNavigationEqualWidth(barWidth)
        val signalsWidth = equalWidth * 2f - 48.dp
        val debugWidth = 48.dp
        assertEquals(48f, debugWidth.value, 0f)
        assertEquals(equalWidth.value * 2f - 48f, signalsWidth.value, .001f)
        assertEquals(barWidth.value - 12f - 6f * 8f,
            signalsWidth.value + debugWidth.value + equalWidth.value * 5f, .001f)

        val labels = UiLanguage.entries.map { UiStrings(it).tabs[0] }
        assertEquals(listOf("Поворотники та інтеграції", "Signals & integrations", "转向灯与集成"), labels)
        labels.forEach { label ->
            val longestLineToken = label.split(' ').maxOf { it.length }
            assertTrue(label, longestLineToken * 14f <= signalsWidth.value)
        }

        val source = File("src/main/kotlin/com/byd/extend/ui/BydExtendApp.kt").readText()
        val navigation = source.substring(
            source.indexOf("private fun BottomNavigation"),
            source.indexOf("private fun AppDialog"),
        )
        assertTrue(navigation.contains("height(60.dp)"))
        assertTrue(navigation.contains("padding(6.dp)"))
        assertTrue(navigation.contains("Arrangement.spacedBy(8.dp)"))
        assertTrue(navigation.contains("val equalWidth = bottomNavigationEqualWidth(maxWidth)"))
        assertTrue(navigation.contains("RootTab.Signals -> Modifier.weight(1f)"))
        assertTrue(navigation.contains("RootTab.Debug -> Modifier.width(48.dp)"))
        assertTrue(navigation.contains("else -> Modifier.width(equalWidth)"))
        assertTrue(navigation.contains("if (tab != RootTab.Debug)"))
        assertTrue(navigation.contains("contentDescription = strings.tabs[index]"))
        assertTrue(navigation.contains("selected = selected"))
        assertTrue(navigation.contains("role = Role.Tab"))
        assertTrue(navigation.contains("Modifier.size(20.dp)"))
        assertTrue(navigation.contains("fontSize = 14.sp"))
        assertTrue(navigation.contains("maxLines = 2"))
    }

    @Test
    fun reverseGearSwitchNeedsAnIntegratedCameraPane() {
        assertFalse(ReverseUiState().hasAnyFrontIntegration())
        assertFalse(ReverseUiState(frontIntegration = mapOf(
            ReverseElement.Background to true)).hasAnyFrontIntegration())
        for (pane in listOf(ReverseElement.Rear, ReverseElement.RearLeft, ReverseElement.RearRight)) {
            assertTrue(ReverseUiState(frontIntegration = mapOf(pane to true)).hasAnyFrontIntegration())
        }
    }

    @Test
    fun panoramaHeaderShowsOnlyOpeningAndErrorStates() {
        val opening = StatusUiState("Opening panorama…", StatusTone.Warning, true)
        val error = StatusUiState("Stock camera background unavailable", StatusTone.Error, true)
        assertEquals(opening, panoramaStatusForDisplay(opening))
        assertEquals(error, panoramaStatusForDisplay(error))
        assertFalse(panoramaStatusForDisplay(StatusUiState("Ready", StatusTone.Ok, true)).visible)
        assertFalse(panoramaStatusForDisplay(StatusUiState()).visible)
    }

    @Test
    fun numericDraftRejectsAndResetsBothFields() {
        val rejected = NumericDraftPolicy.resolve("999", "12", 0f..100f)
        assertFalse(rejected.valid)
        assertEquals("12", rejected.draft)
        assertEquals(12f, rejected.slider)

        // Locally valid can still fail paired-limit/crop validation in the synchronous backend.
        val submitted = NumericDraftPolicy.resolve("12.5", "12", 0f..100f)
        assertTrue(submitted.valid)
        assertEquals("12", submitted.draft)
        assertEquals(12f, submitted.slider)

        val reloaded = NumericDraftPolicy.resolve("12.5", "12.5", 0f..100f)
        assertTrue(reloaded.valid)
        assertEquals("12.5", reloaded.draft)
        assertEquals(12.5f, reloaded.slider)
    }

    @Test
    fun numericCanonicalizationAndMalformedInputUseTheAcceptedValue() {
        val canonicalized = NumericDraftPolicy.resolve("12.9", "13", 0f..100f)
        assertTrue(canonicalized.valid)
        assertEquals("13", canonicalized.draft)
        assertEquals(13f, canonicalized.slider)
        for (raw in listOf("", "-", "NaN", "Infinity", "-1")) {
            val rejected = NumericDraftPolicy.resolve(raw, "13", 0f..100f)
            assertFalse(raw, rejected.valid)
            assertEquals(raw, "13", rejected.draft)
            assertEquals(raw, 13f, rejected.slider, 0f)
        }
    }

    @Test
    fun blindGeometryUsesRealCanvasAspectAndChromeMargins() {
        val display = CameraDisplayGeometry(2000, 1000, 20, 30, 20, 70)
        val actual = productionPlacementGeometry(
            CameraProfileId.Blind(CameraGroup.Rear, CameraSide.Left),
            display, 40f, 1.5f, 1f, 1f)
        val expected = BlindSpotOverlayController.overlayGeometry(
            2000, 1000, 40, 1.5f, 1f, 1f, 20, 30, 70)
        assertEquals(expected[0], actual.left)
        assertEquals(expected[1], actual.top)
        assertEquals(expected[2], actual.width)
        assertEquals(expected[3], actual.height)
    }

    @Test
    fun parkingGeometryRemainsFourThreeOnNonSixteenNineCanvas() {
        val display = CameraDisplayGeometry(1500, 1000)
        val actual = productionPlacementGeometry(
            CameraProfileId.Parking(ParkingView.FrontLeft),
            display, 25f, 4f / 3f, .5f, .5f)
        val expected = ParkingCameraController.overlayGeometry(1500, 1000, 25, .5f, .5f)
        assertEquals(expected[0], actual.left)
        assertEquals(expected[1], actual.top)
        assertEquals(expected[2], actual.width)
        assertEquals(expected[3], actual.height)
    }
}

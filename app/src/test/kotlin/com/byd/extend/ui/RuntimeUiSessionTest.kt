package com.byd.extend.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RuntimeUiSessionTest {
    @Test
    fun selectionsRoundTripAndApplyBeforeRendering() {
        val initial = BydExtendUiState()
        val selected = RuntimeUiSelections.from(initial).copy(
            activeTab = RootTab.Reverse,
            signalsCategory = SignalsCategory.Weather,
            blindGroup = CameraGroup.Front,
            parkingView = ParkingView.RearRight,
            reverseElement = ReverseElement.Widget,
            reverseSource = ReverseSource.Front,
            settingsCategory = SettingsCategory.Logs,
            diagnosticMode = DiagnosticMode.Avm,
        )
        val restored = selected.applyTo(initial)

        assertEquals(RootTab.Reverse, restored.activeTab)
        assertEquals(SignalsCategory.Weather, restored.signals.category)
        assertEquals(CameraGroup.Front, restored.blind.selectedGroup)
        assertEquals(ParkingView.RearRight, restored.parking.selectedView)
        assertEquals(ReverseElement.Widget, restored.reverse.selectedElement)
        assertEquals(ReverseSource.Front, restored.reverse.selectedSource)
        assertEquals(true, restored.reverse.showFront)
        assertEquals(SettingsCategory.Logs, restored.settings.category)
        assertEquals(DiagnosticMode.Avm, restored.debug.mode)
    }

    @Test
    fun mainAndSidebarViewportsAndKeysRemainIndependent() {
        val session = RuntimeUiSession().getOrCreate(RuntimeUiSelections.from(BydExtendUiState()))
        session.recordViewport("signals:Weather", RuntimeViewportKind.Main, RuntimeViewport(420))
        session.recordViewport("signals:Avas", RuntimeViewportKind.Main, RuntimeViewport(180))
        session.recordViewport("signals", RuntimeViewportKind.Sidebar, RuntimeViewport(30))

        session.recordViewport("signals:Weather", RuntimeViewportKind.Main, null)

        assertEquals(RuntimeViewport(420),
            session.viewport("signals:Weather", RuntimeViewportKind.Main))
        assertEquals(RuntimeViewport(180),
            session.viewport("signals:Avas", RuntimeViewportKind.Main))
        assertEquals(RuntimeViewport(30),
            session.viewport("signals", RuntimeViewportKind.Sidebar))
        assertEquals(RuntimeViewport.Top,
            session.viewport("signals:Weather", RuntimeViewportKind.Sidebar))
    }

    @Test
    fun lazyViewportRetainsIdentityIndexAndIntraItemOffset() {
        val session = RuntimeUiSession().getOrCreate(RuntimeUiSelections.from(BydExtendUiState()))
        val viewport = RuntimeViewport(offset = 37, index = 6, identity = "logs:record-logcat")
        session.recordViewport("settings:Logs", RuntimeViewportKind.Main, viewport)
        assertEquals(viewport, session.viewport("settings:Logs", RuntimeViewportKind.Main))
    }

    @Test
    fun numericDraftOwnerSurvivesRowDisposalUntilCanonicalValueChanges() {
        val store = NumericDraftStore()
        val first = store.state("avas-volume-lock", "15", 0f..100f)
        first.draft.value = "1."
        first.invalid.value = true

        val restored = store.state("avas-volume-lock", "15", 0f..100f)
        assertSame(first, restored)
        assertEquals("1.", restored.draft.value)
        assertEquals(true, restored.invalid.value)

        val refreshed = store.state("avas-volume-lock", "20", 0f..100f)
        assertNotSame(first, refreshed)
        assertEquals("20", refreshed.draft.value)
        assertEquals(false, refreshed.invalid.value)
    }

    @Test
    fun mirrorCalibrationDraftIdentityIsSourceSpecific() {
        val target = NumberTarget.Profile(CameraProfileId.Mirror, ProfileNumber.Fov)
        val rear = target.forMirrorSource(false)
        val front = target.forMirrorSource(true)
        assertEquals(false, (rear as NumberTarget.Profile).mirrorFront)
        assertEquals(true, (front as NumberTarget.Profile).mirrorFront)
        assertNotEquals(rear, front)
    }

    @Test
    fun explicitClearDetachesOldCallbacksFromTheProcessHolder() {
        val holder = RuntimeUiSession()
        val firstSelections = RuntimeUiSelections.from(BydExtendUiState())
        val detached = holder.getOrCreate(firstSelections)
        detached.recordViewport("settings:Logs", RuntimeViewportKind.Main, RuntimeViewport(420))

        holder.clear()
        detached.select(firstSelections.copy(activeTab = RootTab.Debug))
        detached.recordViewport("settings:Logs", RuntimeViewportKind.Main, RuntimeViewport(260))
        val replacement = holder.getOrCreate(firstSelections.copy(activeTab = RootTab.Settings))

        assertEquals(RootTab.Settings, replacement.selections().activeTab)
        assertEquals(RuntimeViewport.Top,
            replacement.viewport("settings:Logs", RuntimeViewportKind.Main))
    }
}

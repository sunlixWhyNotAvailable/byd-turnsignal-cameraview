package com.byd.extend.ui

import org.junit.Assert.assertEquals
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

package com.byd.extend.ui

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Host key/isolation and source-wiring checks, not a rendered Android lifecycle test. */
class ScreenScrollRetentionTest {
    @Test
    fun rootTabsHaveIndependentViewports() {
        val state = BydExtendUiState()
        assertEquals(RootTab.entries.size,
            RootTab.entries.map { state.copy(activeTab = it).scrollKey() }.toSet().size)
    }

    @Test
    fun eachIntegrationAndSettingsCategoryHasItsOwnPosition() {
        val state = BydExtendUiState()
        val integrations = SignalsCategory.entries.map {
            state.copy(activeTab = RootTab.Signals,
                signals = state.signals.copy(category = it)).scrollKey()
        }
        val settings = SettingsCategory.entries.map {
            state.copy(activeTab = RootTab.Settings,
                settings = state.settings.copy(category = it)).scrollKey()
        }
        assertEquals(SignalsCategory.entries.size, integrations.toSet().size)
        assertEquals(SettingsCategory.entries.size, settings.toSet().size)
        assertTrue(integrations.toSet().intersect(settings.toSet()).isEmpty())
        assertEquals("signals", state.copy(activeTab = RootTab.Signals).sidebarScrollKey())
        assertEquals("settings", state.copy(activeTab = RootTab.Settings).sidebarScrollKey())
    }

    @Test
    fun onlyScrollStateIsKeyedAndSavedByAndroid() {
        val shell = source("BydExtendApp.kt")
        val savedScope = shell.substringAfter("viewportPositions.SaveableStateProvider(\"main:\$scrollKey\") {")
            .substringBefore("\n    }")
        assertTrue(shell.contains("val viewportPositions = rememberSaveableStateHolder()"))
        assertTrue(savedScope.contains("primaryScroll = rememberScrollState("))
        assertTrue(savedScope.contains("uiSession.viewport(scrollKey, RuntimeViewportKind.Main).offset"))
        assertFalse(savedScope.contains("cameraHost"))
        assertFalse(savedScope.contains("when (state.activeTab)"))
        assertTrue(shell.contains("snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }"))
        assertTrue(shell.contains("if (contentReady && !restored)"))
        assertTrue(shell.contains("Lifecycle.Event.ON_PAUSE"))
        assertTrue(shell.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(shell.contains("Lifecycle.Event.ON_DESTROY"))
        assertTrue(shell.contains("latestCaptureUiSession()"))
    }

    @Test
    fun integrationsAndSettingsUseTheRetainedPrimaryScroll() {
        val shell = source("BydExtendApp.kt")
        val integrations = shell.substringAfter("private fun SignalsScreen(")
            .substringBefore("private fun CategorySidebar(")
        assertTrue(integrations.contains(".verticalScroll(LocalPrimaryScroll.current)"))
        assertFalse(integrations.contains(".padding(12.dp).verticalScroll(rememberScrollState())"))
        assertTrue(source("SettingsScreen.kt").contains(".padding(12.dp).verticalScroll(LocalPrimaryScroll.current)"))
        assertTrue(shell.contains(".verticalScroll(scroll).selectableGroup()"))
        assertTrue(source("SettingsScreen.kt").contains(".verticalScroll(sidebarScroll).selectableGroup()"))
    }

    @Test
    fun scopedOperationFeedbackIsHiddenButOtherFeedbackRemainsEligible() {
        val settings = source("SettingsScreen.kt")
        assertTrue(settings.contains("state.feedbackOperation in setOf("))
        for (operation in listOf("Logs", "Compatibility", "Preset", "Import")) {
            assertTrue(settings.contains("SettingsOperation.$operation"))
        }
        assertTrue(settings.contains("state.feedback.visible && !operationFeedbackHidden"))
        assertTrue(settings.contains("\"Export configuration\""))
    }

    private fun source(name: String): String {
        val relative = Path.of("src/main/kotlin/com/byd/extend/ui", name)
        return String(Files.readAllBytes(if (Files.exists(relative)) relative else Path.of("app").resolve(relative)),
            Charsets.UTF_8)
    }
}

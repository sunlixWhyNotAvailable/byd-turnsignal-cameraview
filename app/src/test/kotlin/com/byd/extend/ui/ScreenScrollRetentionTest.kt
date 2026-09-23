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
        assertTrue(shell.contains("val viewportPositions = rememberSaveableStateHolder()"))
        assertTrue(shell.contains("rememberSavedScrollState(viewportPositions, \"main-scroll:\$scrollKey\""))
        assertTrue(shell.contains("rememberSavedLazyListState(viewportPositions, \"main-lazy:\$scrollKey\""))
        assertTrue(shell.contains("LocalPrimaryLazyList provides primaryLazyViewport"))
        assertTrue(source("UiPrimitives.kt").contains("identity = item?.key?.toString()"))
        assertTrue(source("UiPrimitives.kt").contains("processFallback.identity?.let(rowKeys::indexOf)"))
        assertTrue(source("UiPrimitives.kt").contains("layoutInfo.totalItemsCount == 0"))
        assertTrue(shell.contains("snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }"))
        assertTrue(shell.contains("if (contentReady && !restored)"))
        assertTrue(shell.contains("Lifecycle.Event.ON_PAUSE"))
        assertTrue(shell.contains("Lifecycle.Event.ON_STOP"))
        assertTrue(shell.contains("Lifecycle.Event.ON_DESTROY"))
        assertTrue(shell.contains("latestCaptureUiSession()"))
    }

    @Test
    fun lazyViewportRestorerIsStableAcrossSessionCaptureRecomposition() {
        val shell = source("BydExtendApp.kt")
        assertTrue(shell.contains("val initialLazyFallback = remember(primaryLazyList)"))
        assertTrue(shell.contains("remember(primaryLazyList, contentReady)"))
        assertFalse(shell.contains("remember(primaryLazyList, processMainViewport"))
    }

    @Test
    fun longFormsUseRetainedLazyListsWhileShortFormsKeepScrollState() {
        val shell = source("BydExtendApp.kt")
        val integrations = shell.substringAfter("private fun SignalsScreen(")
            .substringBefore("private fun CategorySidebar(")
        assertTrue(integrations.contains(".verticalScroll(LocalPrimaryScroll.current)"))
        assertTrue(integrations.contains("LazyForm(Modifier.fillMaxSize(), LocalPrimaryLazyList.current)"))
        assertTrue(source("SettingsScreen.kt").contains("LocalPrimaryLazyList.current"))
        assertTrue(source("CameraUiCommon.kt").contains("LocalPrimaryLazyList.current"))
        assertTrue(shell.contains(".verticalScroll(scroll).selectableGroup()"))
        assertTrue(source("SettingsScreen.kt").contains(".verticalScroll(sidebarScroll).selectableGroup()"))
    }

    @Test
    fun lazyFormStructuralBuildersReturnTheirOwningScope() {
        val names = listOf(
            "UiPrimitives.kt", "CameraUiCommon.kt", "BlindParkingScreens.kt", "MirrorScreen.kt",
            "ReverseScreen.kt", "SettingsScreen.kt", "AdbRecoveryScreen.kt", "BydExtendApp.kt",
        )
        val sources = names.associateWith(::source)
        for ((name, text) in sources) {
            assertFalse("$name must not use Unit-returning FormScope callbacks",
                text.contains("@Composable FormScope.() -> Unit"))
            assertFormScopeBuildersReturnTheirScope(name, text)
        }

        val primitives = sources.getValue("UiPrimitives.kt")
        assertTrue(primitives.contains(
            "fun row(key: String, content: @Composable ColumnScope.() -> Unit): FormScope"))
        val lazyForm = primitives.substringAfter("internal fun LazyForm(")
            .substringBefore("internal fun FormScope.FormSection(")
        assertTrue(lazyForm.contains("content: @Composable FormScope.() -> FormScope"))
        val formSection = primitives.substringAfter("internal fun FormScope.FormSection(")
        assertTrue(formSection.contains("content: @Composable FormScope.() -> FormScope"))

        val camera = sources.getValue("CameraUiCommon.kt")
        assertTrue(camera.contains("profileControls: @Composable FormScope.() -> FormScope"))
        assertTrue(camera.contains("controls: @Composable FormScope.() -> FormScope"))
        assertTrue(camera.contains("placementExtra: @Composable FormScope.() -> FormScope = { this }"))
        assertTrue(camera.contains("parameters: @Composable FormScope.() -> FormScope"))
        assertTrue(camera.contains("var stage by rememberSaveable(profile)"))
    }

    @Test
    fun onlyApprovedLongFormsSelectLazyMainViewport() {
        val state = BydExtendUiState()
        assertFalse(state.copy(activeTab = RootTab.Signals,
            signals = state.signals.copy(category = SignalsCategory.Weather)).usesLazyMainViewport())
        assertTrue(state.copy(activeTab = RootTab.Signals,
            signals = state.signals.copy(category = SignalsCategory.Avas)).usesLazyMainViewport())
        assertTrue(state.copy(activeTab = RootTab.Signals,
            signals = state.signals.copy(category = SignalsCategory.AdbRecovery)).usesLazyMainViewport())
        assertTrue(state.copy(activeTab = RootTab.Settings).usesLazyMainViewport())
        assertTrue(state.copy(activeTab = RootTab.Blind).usesLazyMainViewport())
        assertFalse(state.copy(activeTab = RootTab.Debug).usesLazyMainViewport())
    }

    @Test
    fun scopedOperationFeedbackIsHiddenButOtherFeedbackRemainsEligible() {
        val settings = source("SettingsScreen.kt")
        assertTrue(settings.contains("state.feedbackOperation in setOf("))
        for (operation in listOf("Logs", "Compatibility", "Preset", "Import")) {
            assertTrue(settings.contains("SettingsOperation.$operation"))
        }
        assertTrue(settings.contains("state.feedback.visible && !operationFeedbackHidden"))
    }

    @Test
    fun extendedLogsIsIndependentDefaultOffAndPrecedesLogcat() {
        assertFalse(SettingsUiState().extendedLogs)
        val settings = source("SettingsScreen.kt")
        val extended = settings.indexOf("ToggleId.ExtendedLogs")
        val logcat = settings.indexOf("ToggleId.RecordLogcat")
        assertTrue(extended >= 0)
        assertTrue(extended < logcat)
        assertTrue(settings.contains("Зберігати розширені логи"))
        assertTrue(settings.contains("May increase CPU load and storage use."))
        assertTrue(settings.contains("可能会增加 CPU 负载和存储空间占用。"))
        assertTrue(source("ProductionStateSnapshot.kt").contains(
            "preferences.getBoolean(DiagnosticLogPolicy.PREF_ENABLED, false)"))
    }

    private fun source(name: String): String {
        val relative = Path.of("src/main/kotlin/com/byd/extend/ui", name)
        return String(Files.readAllBytes(if (Files.exists(relative)) relative else Path.of("app").resolve(relative)),
            Charsets.UTF_8)
    }

    private fun assertFormScopeBuildersReturnTheirScope(name: String, text: String) {
        val declarations = Regex("\\bfun FormScope\\.(\\w+)\\s*\\(").findAll(text).toList()
        assertTrue("$name contains structural FormScope builders", declarations.isNotEmpty())
        for (declaration in declarations) {
            val opening = text.indexOf('(', declaration.range.first)
            var depth = 0
            var cursor = opening
            while (cursor < text.length) {
                when (text[cursor]) {
                    '(' -> depth++
                    ')' -> if (--depth == 0) {
                        cursor++
                        break
                    }
                }
                cursor++
            }
            assertTrue("$name.${declaration.groupValues[1]} has a complete signature", depth == 0)
            assertTrue("$name.${declaration.groupValues[1]} must explicitly return FormScope",
                text.substring(cursor).trimStart().startsWith(": FormScope"))
        }
    }
}

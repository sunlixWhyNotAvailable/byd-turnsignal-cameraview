package com.byd.extend.ui

/** Complete process-local selection state, kept separate from Android and Compose objects. */
data class RuntimeUiSelections(
    val activeTab: RootTab,
    val signalsCategory: SignalsCategory,
    val blindSection: CameraSection,
    val blindGroup: CameraGroup,
    val blindSide: CameraSide,
    val parkingSection: CameraSection,
    val parkingView: ParkingView,
    val reverseSection: CameraSection,
    val reverseElement: ReverseElement,
    val reverseSource: ReverseSource,
    val mirrorSection: CameraSection,
    val settingsCategory: SettingsCategory,
    val diagnosticMode: DiagnosticMode,
    val directSelection: Int?,
    val avmSelection: Int?,
    val avmOrientation: AvmOrientation,
) {
    fun applyTo(state: BydExtendUiState): BydExtendUiState = state.copy(
        activeTab = activeTab,
        signals = state.signals.copy(category = signalsCategory),
        blind = state.blind.copy(
            section = blindSection,
            selectedGroup = blindGroup,
            selectedSide = blindSide,
        ),
        parking = state.parking.copy(section = parkingSection, selectedView = parkingView),
        reverse = state.reverse.copy(
            section = reverseSection,
            selectedElement = reverseElement,
            selectedSource = reverseSource,
            showFront = reverseSource == ReverseSource.Front,
        ),
        // Mirror target/source select actual saved runtime output and choose which source-specific
        // calibration is loaded. Restore only its transient editor section so fresh model values
        // cannot be paired with a different process-session source.
        mirror = state.mirror.copy(section = mirrorSection),
        settings = state.settings.copy(category = settingsCategory),
        debug = state.debug.copy(
            mode = diagnosticMode,
            directSelection = directSelection,
            avmSelection = avmSelection,
            avmOrientation = avmOrientation,
        ),
    )

    companion object {
        fun from(state: BydExtendUiState) = RuntimeUiSelections(
            activeTab = state.activeTab,
            signalsCategory = state.signals.category,
            blindSection = state.blind.section,
            blindGroup = state.blind.selectedGroup,
            blindSide = state.blind.selectedSide,
            parkingSection = state.parking.section,
            parkingView = state.parking.selectedView,
            reverseSection = state.reverse.section,
            reverseElement = state.reverse.selectedElement,
            reverseSource = state.reverse.selectedSource,
            mirrorSection = state.mirror.section,
            settingsCategory = state.settings.category,
            diagnosticMode = state.debug.mode,
            directSelection = state.debug.directSelection,
            avmSelection = state.debug.avmSelection,
            avmOrientation = state.debug.avmOrientation,
        )
    }
}

enum class RuntimeViewportKind { Main, Sidebar }

data class RuntimeViewport(val offset: Int) {
    init { require(offset >= 0) }

    companion object { val Top = RuntimeViewport(0) }
}

/**
 * Process-only UI session. It intentionally contains no Activity, View, Compose, camera or disk
 * references. Clearing the holder detaches the current [Session], so callbacks from a closing UI
 * can update only their old object and cannot republish cleared process state.
 */
class RuntimeUiSession {
    private var current: Session? = null

    @Synchronized
    fun getOrCreate(initial: RuntimeUiSelections): Session =
        current ?: Session(initial).also { current = it }

    @Synchronized
    fun currentSelections(): RuntimeUiSelections? = current?.selections()

    @Synchronized
    fun updateSelections(value: RuntimeUiSelections) { current?.select(value) }

    @Synchronized
    fun clear() { current = null }

    class Session internal constructor(initial: RuntimeUiSelections) {
        private var selected = initial
        private val mainViewports = LinkedHashMap<String, RuntimeViewport>()
        private val sidebarViewports = LinkedHashMap<String, RuntimeViewport>()

        @Synchronized
        fun selections(): RuntimeUiSelections = selected

        @Synchronized
        fun select(value: RuntimeUiSelections) { selected = value }

        @Synchronized
        fun viewport(key: String, kind: RuntimeViewportKind): RuntimeViewport =
            viewportMap(kind)[key] ?: RuntimeViewport.Top

        @Synchronized
        fun recordViewport(key: String, kind: RuntimeViewportKind, position: RuntimeViewport?) {
            // Unmeasured/placeholder content supplies null and must not erase the last bookmark.
            if (position != null) viewportMap(kind)[key] = position
        }

        private fun viewportMap(kind: RuntimeViewportKind) = when (kind) {
            RuntimeViewportKind.Main -> mainViewports
            RuntimeViewportKind.Sidebar -> sidebarViewports
        }
    }

    companion object {
        @JvmField val INSTANCE = RuntimeUiSession()

        fun getOrCreate(initial: RuntimeUiSelections): Session =
            INSTANCE.getOrCreate(initial)

        fun updateSelections(value: RuntimeUiSelections) = INSTANCE.updateSelections(value)

        @JvmStatic fun clearProcessState() { INSTANCE.clear() }
    }
}

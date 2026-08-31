package com.byd.extend.ui

import androidx.compose.runtime.Immutable

enum class UiLanguage { Ukrainian, English }
enum class UiTheme { Dark, Light }
enum class RootTab { Signals, Blind, Parking, Reverse, Settings, Debug }
enum class CameraSection { Parameters, Placement, Calibration }
enum class CameraGroup { Rear, Front }
enum class CameraSide { Left, Right }
enum class DisplayTarget { Tablet, Cluster }
enum class CalibrationStage { Original, Correction, Output }
enum class ReverseSource { Rear, Front }
enum class DiagnosticMode { Signals, Direct, Avm }
enum class SettingsCategory { Permissions, CameraOutput, Logs }
enum class SettingsOperation { AutoStart, Adb, Update, Logs, Compatibility, Preset, Import }
enum class DialogKind { Background, Update, Shutdown, Progress, Message }
enum class StatusTone { Ok, Warning, Error, Neutral }
enum class AvmOrientation { Horizontal, Vertical }

/** Real target display bounds and the tablet chrome margins used by production overlays. */
@Immutable
data class CameraDisplayGeometry(
    val width: Int = 1920,
    val height: Int = 1080,
    val marginLeft: Int = 0,
    val marginTop: Int = 0,
    val marginRight: Int = 0,
    val marginBottom: Int = 0,
    val target: DisplayTarget = DisplayTarget.Tablet,
) {
    val aspect: Float get() = width.toFloat() / height.coerceAtLeast(1)

    companion object {
        fun default(target: DisplayTarget) = if (target == DisplayTarget.Cluster) {
            CameraDisplayGeometry(1920, 720, target = DisplayTarget.Cluster)
        } else {
            CameraDisplayGeometry(1920, 1080, 16, 36, 16, 88, DisplayTarget.Tablet)
        }
    }
}

/** Runtime-only indication that requested correction currently resolves to RAW input. */
@Immutable
data class ProductionPlacementGeometry(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val canvasWidth: Int,
    val canvasHeight: Int,
)

enum class GuardNumber { OutwardAngle, CentreTolerance, CorrectionDelayMs, MaximumSpeed }
enum class BlindNumber { MinimumSpeed, MaximumSpeed, SteeringAngle }
enum class ParkingNumber { TriggerDistance, MaximumSpeed }
enum class OutputNumber { CornerRadius, Transparency }
enum class ProfileNumber { Size, X, Y, OriginalX, OriginalY, OriginalWidth, OriginalHeight, Fov, CorrectedX, CorrectedY, CorrectedWidth, CorrectedHeight, Rotation }
enum class ReverseGeometryNumber { X, Y, Width, Height }

internal data class NumericDraftResult(
    val draft: String,
    val slider: Float,
    val valid: Boolean,
)

/** Shared synchronous numeric-control policy; rejected input always returns to canonical state. */
internal object NumericDraftPolicy {
    fun resolve(raw: String, canonical: String, range: ClosedFloatingPointRange<Float>): NumericDraftResult {
        val parsed = raw.toFloatOrNull()
        // Local validity does not imply backend acceptance (paired limits and crop bounds).
        // A successful synchronous dispatch supplies the new canonical value on recomposition.
        return NumericDraftResult(canonical,
            (canonical.toFloatOrNull() ?: range.start).coerceIn(range),
            parsed != null && parsed.isFinite() && parsed in range)
    }
}

enum class ToggleId {
    Guard,
    Music,
    Weather,
    AutoStart,
    AutomaticUpdate,
    BlindRear,
    BlindFront,
    BlindSharpTurn,
    BlindObjectOnly,
    BlindTurnRequired,
    ParkingView,
    ParkingAddCentral,
    ParkingAlongsideReverse,
    ParkingSynchronizeSize,
    ReverseEnabled,
    ReverseElementVisible,
    ReverseFrontIntegration,
    ProfileCorrection,
    ProfileMirror,
    AvmShowRaw,
    AvmDewarp,
}

enum class SelectionId {
    Root,
    Language,
    Theme,
    CameraSection,
    BlindGroup,
    BlindSide,
    BlindWarningMode,
    ParkingView,
    ReverseElement,
    ReverseSource,
    ProfileTarget,
    ProfileSourceAspect,
    ProfileProjection,
    ProfileOutputMode,
    ProfileStage,
    SettingsCategory,
    DiagnosticMode,
    DirectMode,
    AvmMode,
    AvmOrientation,
    CameraQuality,
}

enum class CommandId {
    WeatherRefresh,
    SaveProfilePreset,
    LoadProfilePreset,
    TransferProfilePreset,
    ResetProfilePlacement,
    ResetProfileOriginal,
    ResetProfileCorrection,
    ResetProfileOutput,
    EnableAllParking,
    DisableAllParking,
    ReverseNudgeLeft,
    ReverseNudgeUp,
    ReverseNudgeRight,
    ReverseNudgeDown,
    ReverseLower,
    ReverseRaise,
    ReverseResetLayout,
    SignalLeft,
    SignalRight,
    SignalHazard,
    SignalReset,
    StopDiagnosticCamera,
    OpenBackgroundSettings,
    GrantAdb,
    CheckForUpdates,
    ShareLogs,
    ClearLogs,
    ShareCompatibilityPackage,
    ExportCameraPresets,
    LoadCameraPresets,
    ImportLegacySettings,
    RestoreLegacyAccess,
    Shutdown,
    OpenWeatherAttribution,
    DismissDialog,
    ConfirmDialog,
    CancelOperation,
}

enum class ParkingView(val sourceIndex: Int) {
    FrontLeft(2), Front(4), FrontRight(3), RearRight(3), Rear(1), RearLeft(2), Left(2), Right(3),
}

enum class ReverseElement { Background, Widget, Rear, RearLeft, RearRight }

sealed interface CameraProfileId {
    @Immutable
    data class Blind(val group: CameraGroup, val side: CameraSide) : CameraProfileId

    @Immutable
    data class Parking(val view: ParkingView) : CameraProfileId

    @Immutable
    data class Reverse(val element: ReverseElement, val source: ReverseSource) : CameraProfileId
}

@Immutable
data class StatusUiState(
    val text: String = "",
    val tone: StatusTone = StatusTone.Neutral,
    val visible: Boolean = false,
)

@Immutable
data class OperationUiState(
    val enabled: Boolean = true,
    val pending: Boolean = false,
    val status: StatusUiState = StatusUiState(),
)

@Immutable
data class CropUiState(
    val x: String = "0",
    val y: String = "0",
    val width: String = "100",
    val height: String = "100",
)

@Immutable
data class CalibrationUiState(
    val original: CropUiState = CropUiState(),
    val sourceAspect: Int = 3,
    val correctionEnabled: Boolean = false,
    val fov: String = "100",
    val projection: Int = 0,
    val corrected: CropUiState = CropUiState(),
    val mirrored: Boolean = false,
    val outputMode: Int = 0,
    val rotation: String = "0",
    val rawFallback: Boolean = false,
)

internal object UiSelectionPreferences {
    const val BLIND_SECTION = "ui_blind_section"
    const val PARKING_SECTION = "ui_parking_section"
    const val REVERSE_SECTION = "ui_reverse_section"
    const val REVERSE_ELEMENT = "ui_reverse_element"
    const val REVERSE_SOURCE = "ui_reverse_source"
    const val SETTINGS_CATEGORY = "ui_settings_category"
    const val DEBUG_MODE = "ui_debug_mode"
}

@Immutable
data class CameraProfileUiState(
    val target: DisplayTarget = DisplayTarget.Tablet,
    val size: String = "30",
    val x: String = "0",
    val y: String = "0",
    val frameAspect: Float = 16f / 9f,
    val displayGeometry: CameraDisplayGeometry = CameraDisplayGeometry(),
    val calibration: CalibrationUiState = CalibrationUiState(),
    val presetAvailable: Boolean = false,
    val operation: OperationUiState = OperationUiState(),
)

@Immutable
data class HeaderUiState(
    val adb: StatusUiState = StatusUiState(),
    val location: StatusUiState = StatusUiState(),
    val weatherEnabled: Boolean = false,
)

@Immutable
data class GuardUiState(
    val enabled: Boolean = false,
    val operation: OperationUiState = OperationUiState(),
    val outwardAngle: String = "90",
    val centreTolerance: String = "10",
    val correctionDelayMs: String = "100",
    val maximumSpeed: String = "30",
)

@Immutable
data class MusicUiState(
    val enabled: Boolean = false,
    val operation: OperationUiState = OperationUiState(),
)

@Immutable
data class WeatherUiState(
    val enabled: Boolean = false,
    val refreshMinutes: String = "15",
    val operation: OperationUiState = OperationUiState(),
    val refresh: OperationUiState = OperationUiState(),
)

@Immutable
data class SignalsUiState(
    val guard: GuardUiState = GuardUiState(),
    val music: MusicUiState = MusicUiState(),
    val weather: WeatherUiState = WeatherUiState(),
)

@Immutable
data class BlindRuleUiState(
    val minimumSpeed: String = "0",
    val maximumSpeed: String = "10",
    val steeringAngle: String = "10",
    val sharpTurnEnabled: Boolean = false,
    val blindSpotOnly: Boolean = false,
    val turnRequired: Boolean = true,
    val warningMode: Int = 0,
)

@Immutable
data class BlindUiState(
    val section: CameraSection = CameraSection.Parameters,
    val selectedGroup: CameraGroup = CameraGroup.Rear,
    val selectedSide: CameraSide = CameraSide.Left,
    val rearEnabled: Boolean = false,
    val frontEnabled: Boolean = false,
    val rules: Map<CameraGroup, BlindRuleUiState> = emptyMap(),
    val profiles: Map<CameraProfileId.Blind, CameraProfileUiState> = emptyMap(),
)

@Immutable
data class ParkingViewUiState(
    val enabled: Boolean = false,
    val triggerDistance: String = "30",
    val addCentralCamera: Boolean = false,
    val profile: CameraProfileUiState = CameraProfileUiState(),
)

@Immutable
data class ParkingUiState(
    val section: CameraSection = CameraSection.Parameters,
    val selectedView: ParkingView = ParkingView.FrontLeft,
    val maximumSpeed: String = "10",
    val alongsideReverse: Boolean = false,
    val synchronizeSize: Boolean = false,
    val views: Map<ParkingView, ParkingViewUiState> = emptyMap(),
)

@Immutable
data class ReverseGeometryUiState(
    val x: String = "0",
    val y: String = "0",
    val width: String = "100",
    val height: String = "100",
    val visible: Boolean = true,
)

@Immutable
data class ReverseUiState(
    val enabled: Boolean = false,
    val section: CameraSection = CameraSection.Parameters,
    val selectedElement: ReverseElement = ReverseElement.RearLeft,
    val selectedSource: ReverseSource = ReverseSource.Rear,
    val showFront: Boolean = false,
    val frontIntegration: Map<ReverseElement, Boolean> = emptyMap(),
    val geometry: Map<ReverseElement, ReverseGeometryUiState> = emptyMap(),
    val profiles: Map<CameraProfileId.Reverse, CameraProfileUiState> = emptyMap(),
    val displayGeometry: CameraDisplayGeometry = CameraDisplayGeometry(),
    val zOrder: List<ReverseElement> = listOf(ReverseElement.Rear, ReverseElement.RearLeft, ReverseElement.RearRight),
)

@Immutable
data class CameraOutputUiState(
    val quality: Int = 1,
    val cornerRadius: String = "10",
    val transparency: String = "0",
)

@Immutable
data class SettingsUiState(
    val category: SettingsCategory = SettingsCategory.Permissions,
    val automaticStart: Boolean = false,
    val automaticStartOperation: OperationUiState = OperationUiState(),
    val adbOperation: OperationUiState = OperationUiState(),
    val cameraOutput: CameraOutputUiState = CameraOutputUiState(),
    val automaticUpdate: Boolean = true,
    val updateOperation: OperationUiState = OperationUiState(),
    val logOperation: OperationUiState = OperationUiState(),
    val compatibilityOperation: OperationUiState = OperationUiState(),
    val presetOperation: OperationUiState = OperationUiState(),
    val importOperation: OperationUiState = OperationUiState(),
    val restoreLegacyAccessVisible: Boolean = false,
    val feedback: StatusUiState = StatusUiState(),
)

@Immutable
data class DebugUiState(
    val mode: DiagnosticMode = DiagnosticMode.Signals,
    val manualSignalsAllowed: Boolean = false,
    val manualSignalStatus: StatusUiState = StatusUiState(),
    val directSelection: Int? = null,
    val directOperation: OperationUiState = OperationUiState(),
    val avmSelection: Int? = null,
    val avmOperation: OperationUiState = OperationUiState(),
    val avmOrientation: AvmOrientation = AvmOrientation.Horizontal,
    val avmShowRaw: Boolean = true,
    val avmDewarp: Boolean = false,
    /** Resolved tablet output bounds used to size diagnostic camera frames. */
    val displayGeometry: CameraDisplayGeometry = CameraDisplayGeometry(),
)

@Immutable
data class DialogUiState(
    val kind: DialogKind,
    val title: String,
    val message: String,
    val progress: Float? = null,
    val cancellable: Boolean = true,
    val confirmEnabled: Boolean = true,
)

@Immutable
data class BydExtendUiState(
    val activeTab: RootTab = RootTab.Signals,
    val legacyRuntimeBlocked: Boolean = false,
    val language: UiLanguage = UiLanguage.Ukrainian,
    val theme: UiTheme = UiTheme.Dark,
    val header: HeaderUiState = HeaderUiState(),
    val signals: SignalsUiState = SignalsUiState(),
    val blind: BlindUiState = BlindUiState(),
    val parking: ParkingUiState = ParkingUiState(),
    val reverse: ReverseUiState = ReverseUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val debug: DebugUiState = DebugUiState(),
    val dialog: DialogUiState? = null,
)

sealed interface NumberTarget {
    @Immutable data class Guard(val field: GuardNumber) : NumberTarget
    data object WeatherInterval : NumberTarget
    @Immutable data class Blind(val group: CameraGroup, val field: BlindNumber) : NumberTarget
    @Immutable data class Parking(val view: ParkingView?, val field: ParkingNumber) : NumberTarget
    @Immutable data class Profile(val profile: CameraProfileId, val field: ProfileNumber) : NumberTarget
    @Immutable data class ReverseGeometry(val element: ReverseElement, val field: ReverseGeometryNumber) : NumberTarget
    @Immutable data class Output(val field: OutputNumber) : NumberTarget
}

sealed interface ToggleTarget {
    @Immutable data class Simple(val id: ToggleId) : ToggleTarget
    @Immutable data class Blind(val id: ToggleId, val group: CameraGroup) : ToggleTarget
    @Immutable data class Parking(val id: ToggleId, val view: ParkingView? = null) : ToggleTarget
    @Immutable data class Reverse(val id: ToggleId, val element: ReverseElement? = null) : ToggleTarget
    @Immutable data class Profile(val id: ToggleId, val profile: CameraProfileId) : ToggleTarget
}

sealed interface SelectionTarget {
    @Immutable data class Simple(val id: SelectionId) : SelectionTarget
    @Immutable data class Profile(val id: SelectionId, val profile: CameraProfileId) : SelectionTarget
}

sealed interface BydExtendUiAction {
    @Immutable data class Navigate(val tab: RootTab) : BydExtendUiAction
    @Immutable data class SetLanguage(val language: UiLanguage) : BydExtendUiAction
    @Immutable data class SetTheme(val theme: UiTheme) : BydExtendUiAction
    @Immutable data class Toggle(val target: ToggleTarget, val value: Boolean) : BydExtendUiAction
    @Immutable data class CommitNumber(val target: NumberTarget, val value: String) : BydExtendUiAction
    @Immutable data class Select(val target: SelectionTarget, val index: Int) : BydExtendUiAction
    @Immutable data class MoveProfile(val profile: CameraProfileId, val x: Float, val y: Float) : BydExtendUiAction
    @Immutable data class Run(val command: CommandId, val profile: CameraProfileId? = null) : BydExtendUiAction
}

enum class CameraHostKind { Placement, CalibrationOriginal, CalibrationCorrected, CalibrationOutput, ReverseComposition, Direct, Avm }

@Immutable
data class CameraHostSlot(
    val kind: CameraHostKind,
    val profile: CameraProfileId? = null,
    val reverseElement: ReverseElement? = null,
    val sourceIndex: Int? = null,
    val modeIndex: Int? = null,
    val editable: Boolean = false,
)

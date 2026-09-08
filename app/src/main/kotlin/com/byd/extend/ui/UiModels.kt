package com.byd.extend.ui

import androidx.compose.runtime.Immutable
import com.byd.extend.RearviewMirrorSettings
import java.util.concurrent.atomic.AtomicLong

enum class UiLanguage { Ukrainian, English, Chinese }
enum class UiTheme { Dark, Light }
/** Stable persisted IDs intentionally differ from enum ordinals after Mirror was inserted. */
enum class RootTab(val legacyId: Int) {
    Signals(0), Blind(1), Parking(8), Reverse(5), Mirror(9), Settings(7), Debug(2);

    companion object {
        fun fromLegacyId(id: Int): RootTab = when (id) {
            1, 4 -> Blind
            8 -> Parking
            5 -> Reverse
            9 -> Mirror
            7 -> Settings
            2, 3 -> Debug
            else -> Signals
        }
    }
}
enum class CameraSection { Parameters, Placement, Calibration }
enum class CameraGroup { Rear, Front }
enum class CameraSide { Left, Right }
enum class DisplayTarget { Tablet, Cluster }
enum class CalibrationStage { Original, Correction, Output }
enum class ReverseSource { Rear, Front }
enum class DiagnosticMode { Signals, Direct, Avm }
enum class SettingsCategory { Permissions, CameraOutput, Logs }
enum class SettingsOperation { AutoStart, Adb, Update, Logs, Compatibility, Preset, Import }
enum class DialogKind { Background, Update, Shutdown, Progress, Message, ReverseButtonCapture }
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
enum class ProfileNumber { Size, X, Y, Width, Height, OriginalX, OriginalY, OriginalWidth, OriginalHeight, Fov, CorrectedX, CorrectedY, CorrectedWidth, CorrectedHeight, Rotation }
enum class MirrorNumber { X, Y, Width, Height, BorderWidth }
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

/** One slider gesture; disposed sessions cannot emit a late finish commit. */
internal class NumericPreviewSession {
    private var id: Long = 0L
    private var active = false
    private var closed = false
    private var disposed = false

    fun begin(): Long {
        if (disposed) return -1L
        if (!active || closed) {
            id = nextId.incrementAndGet()
            active = true
            closed = false
        }
        return id
    }

    fun finish(sessionId: Long): Boolean {
        if (disposed || closed || !active || id != sessionId) return false
        closed = true
        active = false
        return true
    }

    fun dispose() {
        disposed = true
        closed = true
        active = false
    }

    companion object {
        private val nextId = AtomicLong()
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
    ReverseSwitchByGear,
    MirrorEnabled,
    MirrorHidden,
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
    MirrorTarget,
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
    ReverseLearnButton,
    ReverseResetButton,
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
    MirrorSavePreset,
    MirrorLoadPreset,
    MirrorResetPlacement,
    MirrorResetOriginal,
    MirrorResetCorrection,
    MirrorResetOutput,
    MirrorHide,
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

    /** Independent rearview-mirror calibration and placement profile. */
    @Immutable
    data object Mirror : CameraProfileId
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
    const val MIRROR_SECTION = "ui_mirror_section"
    const val SETTINGS_CATEGORY = "ui_settings_category"
    const val DEBUG_MODE = "ui_debug_mode"
}

/** Shared key/action contract for the Java Mirror backend and the Compose editor. */
object MirrorUiContract {
    const val PREF_ENABLED = RearviewMirrorSettings.PREF_ENABLED
    const val PREF_HIDDEN = RearviewMirrorSettings.PREF_MANUAL_HIDDEN
    const val PREF_TARGET = RearviewMirrorSettings.PREF_TARGET
    const val PREF_X = RearviewMirrorSettings.PREF_X
    const val PREF_Y = RearviewMirrorSettings.PREF_Y
    const val PREF_WIDTH = RearviewMirrorSettings.PREF_WIDTH
    const val PREF_HEIGHT = RearviewMirrorSettings.PREF_HEIGHT
    const val PREF_BORDER_WIDTH = RearviewMirrorSettings.PREF_BORDER_DP
    const val PREF_BORDER_COLOR = RearviewMirrorSettings.PREF_BORDER_ARGB
    const val PREF_PRESET_AVAILABLE = RearviewMirrorSettings.PREF_PRESET_PRESENT
}

enum class MirrorBackendActionKind {
    SetEnabled, SetTarget, SetGeometry, SetBorder, SetCalibration, SavePreset, LoadPreset,
    ResetPlacement, ResetOriginal, ResetCorrection, ResetOutput, HideUntilOpen,
}

/** Identifies a non-numeric Mirror calibration mutation while preserving untouched model fields. */
enum class MirrorCalibrationField {
    CorrectionEnabled, Projection, Mirrored, OutputMode,
}

/** Java-friendly typed intent emitted for Mirror operations. */
data class MirrorBackendAction(
    val kind: MirrorBackendActionKind,
    val field: MirrorNumber? = null,
    /** ProfileNumber identifies a single Mirror calibration value without replacing untouched
     * persisted crop fields with a rounded UI snapshot. */
    val profileField: ProfileNumber? = null,
    val calibrationField: MirrorCalibrationField? = null,
    val value: String? = null,
    val target: DisplayTarget? = null,
    val geometry: MirrorGeometryUiState? = null,
    val calibration: CalibrationUiState? = null,
    val enabled: Boolean? = null,
    val borderArgb: Int? = null,
    val profile: CameraProfileId = CameraProfileId.Mirror,
)

@Immutable
data class CameraProfileUiState(
    val target: DisplayTarget = DisplayTarget.Tablet,
    val size: String = "30",
    val x: String = "0",
    val y: String = "0",
    val width: String = "30",
    val height: String = "20",
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
    val switchByGear: Boolean = false,
    val panoramaOperation: OperationUiState = OperationUiState(),
    val section: CameraSection = CameraSection.Parameters,
    val selectedElement: ReverseElement = ReverseElement.RearLeft,
    val selectedSource: ReverseSource = ReverseSource.Rear,
    val showFront: Boolean = false,
    val steeringKeyCode: Int = -1,
    val frontIntegration: Map<ReverseElement, Boolean> = emptyMap(),
    val geometry: Map<ReverseElement, ReverseGeometryUiState> = emptyMap(),
    val profiles: Map<CameraProfileId.Reverse, CameraProfileUiState> = emptyMap(),
    val displayGeometry: CameraDisplayGeometry = CameraDisplayGeometry(),
    val zOrder: List<ReverseElement> = listOf(ReverseElement.Rear, ReverseElement.RearLeft, ReverseElement.RearRight),
)

@Immutable
data class MirrorGeometryUiState(
    val x: String = "50",
    val y: String = "0",
    val width: String = "35",
    val height: String = "35",
)

/** Independent Mirror state; no Reverse visibility or calibration field is shared. */
@Immutable
data class MirrorUiState(
    val enabled: Boolean = false,
    val hidden: Boolean = false,
    val section: CameraSection = CameraSection.Parameters,
    val target: DisplayTarget = DisplayTarget.Tablet,
    val placement: MirrorGeometryUiState = MirrorGeometryUiState(),
    val displayGeometry: CameraDisplayGeometry = CameraDisplayGeometry(),
    val borderWidth: String = "0",
    val borderArgb: Int = 0xFF000000.toInt(),
    val profile: CameraProfileUiState = CameraProfileUiState(size = "35"),
    val operation: OperationUiState = OperationUiState(),
    val presetAvailable: Boolean = false,
    val overlayPermissionGranted: Boolean = false,
    val clusterAvailable: Boolean = false,
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
    val feedbackOperation: SettingsOperation? = null,
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
    val language: UiLanguage = UiLanguage.English,
    val theme: UiTheme = UiTheme.Dark,
    val header: HeaderUiState = HeaderUiState(),
    val signals: SignalsUiState = SignalsUiState(),
    val blind: BlindUiState = BlindUiState(),
    val parking: ParkingUiState = ParkingUiState(),
    val reverse: ReverseUiState = ReverseUiState(),
    val mirror: MirrorUiState = MirrorUiState(),
    val settings: SettingsUiState = SettingsUiState(),
    val debug: DebugUiState = DebugUiState(),
    val dialog: DialogUiState? = null,
)

sealed interface NumberTarget {
    @Immutable data class Guard(val field: GuardNumber) : NumberTarget
    data object WeatherInterval : NumberTarget
    @Immutable data class Blind(val group: CameraGroup, val field: BlindNumber) : NumberTarget
    @Immutable data class Parking(val view: ParkingView?, val field: ParkingNumber) : NumberTarget
    @Immutable data class Mirror(val field: MirrorNumber) : NumberTarget
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
    @Immutable data class SetMirrorBorderColor(val argb: Int) : BydExtendUiAction
    @Immutable data object RequestMirrorOverlayPermission : BydExtendUiAction
    @Immutable data class SetMirrorGeometry(val geometry: MirrorGeometryUiState) : BydExtendUiAction
    /** Atomic whole-display placement used by the four-corner Blind editor. */
    @Immutable data class SetProfileGeometry(
        val profile: CameraProfileId.Blind,
        val geometry: MirrorGeometryUiState,
    ) : BydExtendUiAction
    @Immutable data class Toggle(val target: ToggleTarget, val value: Boolean) : BydExtendUiAction
    /** Live, non-persisting numeric update emitted while a slider is dragged. */
    @Immutable data class PreviewNumber(
        val target: NumberTarget,
        val value: String,
        val sessionId: Long? = null,
    ) : BydExtendUiAction
    @Immutable data class CommitNumber(
        val target: NumberTarget,
        val value: String,
        val sessionId: Long? = null,
    ) : BydExtendUiAction
    @Immutable data class Select(val target: SelectionTarget, val index: Int) : BydExtendUiAction
    @Immutable data class MoveProfile(val profile: CameraProfileId, val x: Float, val y: Float) : BydExtendUiAction
    @Immutable data class Run @JvmOverloads constructor(
        val command: CommandId,
        val profile: CameraProfileId? = null,
        val reverseElement: ReverseElement? = null,
    ) : BydExtendUiAction
}

enum class CameraHostKind { Placement, CalibrationOriginal, CalibrationCorrected, CalibrationOutput, ReverseComposition, Mirror, Direct, Avm }

@Immutable
data class CameraHostSlot(
    val kind: CameraHostKind,
    val profile: CameraProfileId? = null,
    val reverseElement: ReverseElement? = null,
    val sourceIndex: Int? = null,
    val modeIndex: Int? = null,
    val editable: Boolean = false,
)

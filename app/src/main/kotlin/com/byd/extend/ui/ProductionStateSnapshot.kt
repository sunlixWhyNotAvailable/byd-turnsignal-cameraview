@file:JvmName("ProductionStateSnapshot")

package com.byd.extend

import android.content.SharedPreferences
import com.byd.extend.ui.*
import java.util.Locale

/** Reads production state through the same validated camera-domain objects used at runtime. */
@JvmOverloads
fun readProductionUiState(
    preferences: SharedPreferences,
    automaticStart: Boolean,
    legacyAccessRestoreVisible: Boolean,
    displayGeometry: (DisplayTarget) -> CameraDisplayGeometry = { CameraDisplayGeometry.default(it) },
): BydExtendUiState = BydExtendUiState(
    activeTab = storedRootTab(preferences),
    language = if (preferences.getString("ui_language", "uk") == "en") UiLanguage.English else UiLanguage.Ukrainian,
    theme = if (preferences.getBoolean("ui_dark_theme", true)) UiTheme.Dark else UiTheme.Light,
    header = HeaderUiState(weatherEnabled = preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false)),
    signals = readSignals(preferences),
    blind = readBlind(preferences, displayGeometry),
    parking = readParking(preferences, displayGeometry),
    reverse = readReverse(preferences, displayGeometry),
    settings = SettingsUiState(
        category = enumPreference(preferences, UiSelectionPreferences.SETTINGS_CATEGORY,
            SettingsCategory.Permissions),
        automaticStart = automaticStart,
        cameraOutput = CameraOutputUiState(
            quality = CameraBufferQuality.load(preferences),
            cornerRadius = BlindSpotOverlayController.readCornerRadius(preferences).toString(),
            transparency = BlindSpotOverlayController.readTransparencyPercent(preferences).toString(),
        ),
        automaticUpdate = preferences.getBoolean("update_auto_check_enabled", true),
        restoreLegacyAccessVisible = legacyAccessRestoreVisible,
    ),
    debug = DebugUiState(
        mode = enumPreference(preferences, UiSelectionPreferences.DEBUG_MODE,
            DiagnosticMode.Signals),
        avmOrientation = if (preferences.getBoolean("debug_avm_horizontal", true)) AvmOrientation.Horizontal else AvmOrientation.Vertical,
        avmShowRaw = preferences.getBoolean("debug_avm_show_raw", true),
        avmDewarp = preferences.getBoolean("debug_avm_dewarp", false),
        displayGeometry = displayGeometry(DisplayTarget.Tablet),
    ),
)

private fun readSignals(preferences: SharedPreferences) = SignalsUiState(
    guard = GuardUiState(
        enabled = preferences.getBoolean("guard_enabled", false),
        outwardAngle = decimal(preferences.getFloat("outward_deg", 90f)),
        centreTolerance = decimal(preferences.getFloat("center_deg", 10f)),
        correctionDelayMs = preferences.getInt("correction_delay_ms", 100).toString(),
        maximumSpeed = preferences.getInt("max_speed_kph", 30).toString(),
    ),
    music = MusicUiState(enabled = preferences.getBoolean("music_visualizer_enabled", false)),
    weather = WeatherUiState(
        enabled = preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false),
        refreshMinutes = preferences.getInt(
            WeatherRuntime.PREF_INTERVAL_MINUTES, WeatherRuntime.DEFAULT_INTERVAL_MINUTES).toString(),
    ),
)

private fun readBlind(
    preferences: SharedPreferences,
    displayGeometry: (DisplayTarget) -> CameraDisplayGeometry,
): BlindUiState {
    val selectedId = preferences.getInt("camera_selected_profile", CameraProfile.REAR_LEFT)
        .takeIf(CameraProfile::isValid) ?: CameraProfile.REAR_LEFT
    val selected = CameraProfile.of(selectedId)
    val profiles = CameraProfile.values().associate { profile ->
        val raw = DirectCameraCrop.load(preferences, profile)
        val target = BlindSpotOverlayController.readTarget(preferences, profile)
        val display = displayGeometry(
            if (target == CameraDisplayTarget.CLUSTER) DisplayTarget.Cluster else DisplayTarget.Tablet)
        val requestedAspect = BlindSpotOverlayController.readFrameAspect(
            preferences, profile, raw.outputAspect())
        val output = BlindSpotOverlayController.overlayGeometry(
            display.width, display.height, BlindSpotOverlayController.readScale(preferences, profile),
            requestedAspect, BlindSpotOverlayController.readPosition(preferences, profile, false),
            BlindSpotOverlayController.readPosition(preferences, profile, true),
            display.marginLeft.coerceAtLeast(0), display.marginTop.coerceAtLeast(0),
            display.marginBottom.coerceAtLeast(0))
        blindId(profile) to cameraProfile(
            target = target,
            size = BlindSpotOverlayController.readScale(preferences, profile),
            x = BlindSpotOverlayController.readPosition(preferences, profile, false),
            y = BlindSpotOverlayController.readPosition(preferences, profile, true),
            raw = raw,
            corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw),
            dewarp = CameraDewarpConfig.loadForProfile(preferences, profile),
            preset = CameraCalibrationPreset.hasCamera(preferences, profile),
            frameAspect = output[2].toFloat() / output[3].coerceAtLeast(1),
            displayGeometry = display,
        )
    }
    return BlindUiState(
        section = enumPreference(preferences, UiSelectionPreferences.BLIND_SECTION,
            CameraSection.Parameters),
        selectedGroup = if (selected.front()) CameraGroup.Front else CameraGroup.Rear,
        selectedSide = if (selected.right()) CameraSide.Right else CameraSide.Left,
        rearEnabled = preferences.getBoolean(BlindSpotOverlayController.PREF_ENABLED, false),
        frontEnabled = preferences.getBoolean(BlindSpotOverlayController.PREF_FRONT_ENABLED, false),
        rules = mapOf(
            CameraGroup.Rear to BlindRuleUiState(
                minimumSpeed = preferences.getInt(BlindSpotOverlayController.PREF_MIN_SPEED,
                    BlindSpotOverlayController.DEFAULT_MIN_SPEED_KPH).toString(),
                maximumSpeed = preferences.getInt(BlindSpotOverlayController.PREF_MAX_SPEED,
                    BlindSpotOverlayController.DEFAULT_MAX_SPEED_KPH).toString(),
                steeringAngle = decimal(preferences.getFloat(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE,
                    BlindSpotOverlayController.DEFAULT_REAR_SHARP_TURN_ANGLE_DEG)),
                sharpTurnEnabled = preferences.getBoolean(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED, false),
                blindSpotOnly = preferences.getBoolean(
                    BlindSpotOverlayController.PREF_REAR_BSD_ONLY, false),
                warningMode = BlindSpotOverlayController.readWarningMode(preferences),
            ),
            CameraGroup.Front to BlindRuleUiState(
                minimumSpeed = preferences.getInt(BlindSpotOverlayController.PREF_FRONT_MIN_SPEED,
                    BlindSpotOverlayController.DEFAULT_FRONT_MIN_SPEED_KPH).toString(),
                maximumSpeed = preferences.getInt(BlindSpotOverlayController.PREF_FRONT_MAX_SPEED,
                    BlindSpotOverlayController.DEFAULT_FRONT_MAX_SPEED_KPH).toString(),
                steeringAngle = decimal(preferences.getFloat(
                    BlindSpotOverlayController.PREF_FRONT_MIN_ANGLE,
                    BlindSpotOverlayController.DEFAULT_FRONT_MIN_ANGLE_DEG)),
                turnRequired = preferences.getBoolean(
                    BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED, true),
            ),
        ),
        profiles = profiles,
    )
}

private fun readParking(
    preferences: SharedPreferences,
    displayGeometry: (DisplayTarget) -> CameraDisplayGeometry,
): ParkingUiState {
    val defaultX = floatArrayOf(0f, .5f, 1f, 1f, .5f, 0f, 0f, 1f)
    val defaultY = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, .5f, .5f)
    val views = ParkingCameraProfile.values().associate { profile ->
        val view = ParkingView.entries[profile.id]
        val prefix = "parking_camera_${profile.wireName.lowercase(Locale.US)}_"
        val raw = DirectCameraCrop.load(preferences, profile)
        val rule = ParkingCameraSettings.readRule(preferences, profile)
        val display = displayGeometry(DisplayTarget.Tablet)
        val size = preferences.getInt(prefix + "scale", 25).coerceIn(
            BlindSpotOverlayController.MIN_SCALE_PERCENT,
            BlindSpotOverlayController.MAX_SCALE_PERCENT)
        val x = preferences.getFloat(prefix + "x", defaultX[profile.id]).coerceIn(0f, 1f)
        val y = preferences.getFloat(prefix + "y", defaultY[profile.id]).coerceIn(0f, 1f)
        val output = ParkingCameraController.overlayGeometry(
            display.width, display.height, size, x, y)
        view to ParkingViewUiState(
            enabled = rule.enabled,
            triggerDistance = rule.distanceCm.toString(),
            addCentralCamera = rule.addCentral,
            profile = cameraProfile(
                target = CameraDisplayTarget.TABLET,
                size = size,
                x = x,
                y = y,
                raw = raw,
                corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw),
                dewarp = CameraDewarpConfig.loadForParking(preferences, profile),
                preset = CameraCalibrationPreset.hasParking(preferences, profile),
                frameAspect = output[2].toFloat() / output[3].coerceAtLeast(1),
                displayGeometry = display,
            ),
        )
    }
    return ParkingUiState(
        section = enumPreference(preferences, UiSelectionPreferences.PARKING_SECTION,
            CameraSection.Parameters),
        selectedView = ParkingView.entries.getOrElse(preferences.getInt(
            "parking_camera_selected_profile", ParkingCameraProfile.FL)) {
            ParkingView.FrontLeft
        },
        maximumSpeed = ParkingCameraSettings.readMaxSpeed(preferences).toString(),
        alongsideReverse = ParkingCameraSettings.readAllowDuringReverse(preferences),
        synchronizeSize = preferences.getBoolean("parking_camera_scale_sync", false),
        views = views,
    )
}

private fun readReverse(
    preferences: SharedPreferences,
    displayGeometry: (DisplayTarget) -> CameraDisplayGeometry,
): ReverseUiState {
    val targetGeometry = displayGeometry(DisplayTarget.Tablet)
    val raw = ReverseCameraController.loadRawLayout(preferences)
    val frontRaw = ReverseCameraController.loadFrontRawLayout(preferences)
    val geometry = mapOf(
        ReverseElement.Background to geometry(raw.background,
            ReverseCameraController.loadVisibility(preferences, ReverseCameraLayout.BACKGROUND_PANE_ID)),
        ReverseElement.Widget to geometry(raw.widget,
            ReverseCameraController.loadWidgetVisible(preferences)),
        ReverseElement.Rear to geometry(raw.rear.destination,
            ReverseCameraController.loadVisibility(preferences, ReverseCameraLayout.REAR_CAMERA_INDEX)),
        ReverseElement.RearLeft to geometry(raw.rearLeft.destination,
            ReverseCameraController.loadVisibility(preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX)),
        ReverseElement.RearRight to geometry(raw.rearRight.destination,
            ReverseCameraController.loadVisibility(preferences, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX)),
    )
    val profiles = buildMap<CameraProfileId.Reverse, CameraProfileUiState> {
        for (index in ReverseCameraLayout.REAR_CAMERA_INDEX..ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
            val element = reverseElement(index)
            val rearPane = raw.pane(index)
            val rearDewarp = CameraDewarpConfig.loadForReverse(preferences, index)
            val corrected = ReverseCameraController.loadCorrectedSourceCrop(
                preferences, index, ReverseCameraLayout.centeredSourceCrop(rearPane.sourceCrop))
            put(CameraProfileId.Reverse(element, ReverseSource.Rear), reverseProfile(
                rearPane, corrected, rearDewarp, CameraCalibrationPreset.hasReverse(preferences, index),
                targetGeometry))

            val frontPane = frontRaw.pane(index)
            put(CameraProfileId.Reverse(element, ReverseSource.Front), reverseProfile(
                frontPane,
                ReverseCameraController.loadFrontCorrectedSourceCrop(preferences, index),
                CameraDewarpConfig.loadForReverseFront(preferences, index),
                CameraCalibrationPreset.hasReverseFront(preferences, index), targetGeometry))
        }
    }
    return ReverseUiState(
        enabled = preferences.getBoolean(
            ReverseCameraController.PREF_ENABLED, ReverseCameraController.DEFAULT_ENABLED),
        section = enumPreference(preferences, UiSelectionPreferences.REVERSE_SECTION,
            CameraSection.Parameters),
        selectedElement = enumPreference(preferences, UiSelectionPreferences.REVERSE_ELEMENT,
            ReverseElement.RearLeft),
        selectedSource = enumPreference(preferences, UiSelectionPreferences.REVERSE_SOURCE,
            ReverseSource.Rear),
        showFront = enumPreference(preferences, UiSelectionPreferences.REVERSE_SOURCE,
            ReverseSource.Rear) == ReverseSource.Front,
        frontIntegration = mapOf(
            ReverseElement.Rear to ReverseCameraController.loadCentralFrontIntegrated(preferences),
            ReverseElement.RearLeft to ReverseCameraController.loadFrontIntegrated(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
            ReverseElement.RearRight to ReverseCameraController.loadFrontIntegrated(
                preferences, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX),
        ),
        geometry = geometry,
        profiles = profiles,
        displayGeometry = targetGeometry,
        zOrder = raw.panes().sortedBy { it.zOrder }.map { reverseElement(it.cameraIndex) },
    )
}

private fun cameraProfile(
    target: Int,
    size: Int,
    x: Float,
    y: Float,
    raw: DirectCameraCrop,
    corrected: DirectCameraCrop,
    dewarp: CameraDewarpConfig,
    preset: Boolean,
    frameAspect: Float,
    displayGeometry: CameraDisplayGeometry,
) = CameraProfileUiState(
    target = if (target == CameraDisplayTarget.CLUSTER) DisplayTarget.Cluster else DisplayTarget.Tablet,
    size = size.toString(),
    x = percent(x), y = percent(y),
    frameAspect = frameAspect,
    displayGeometry = displayGeometry,
    calibration = calibration(raw, corrected, dewarp),
    presetAvailable = preset,
)

private fun reverseProfile(
    pane: ReverseCameraLayout.Pane,
    corrected: ReverseCameraLayout.Rect,
    dewarp: CameraDewarpConfig,
    preset: Boolean,
    displayGeometry: CameraDisplayGeometry,
) = CameraProfileUiState(
    target = DisplayTarget.Tablet,
    size = percent(pane.destination.width),
    x = percent(pane.destination.left), y = percent(pane.destination.top),
    frameAspect = ReverseCameraLayout.project(
        pane.destination, displayGeometry.width.coerceAtLeast(1),
        displayGeometry.height.coerceAtLeast(1)).let { it.width.toFloat() / it.height.coerceAtLeast(1) },
    displayGeometry = displayGeometry,
    calibration = CalibrationUiState(
        original = crop(pane.sourceCrop),
        sourceAspect = DirectCameraCrop.ASPECT_FREE,
        correctionEnabled = dewarp.enabled,
        fov = dewarp.fovDegrees.toString(),
        projection = dewarp.projection,
        corrected = crop(corrected),
        mirrored = pane.mirrorHorizontally,
        outputMode = pane.displayMode,
        rotation = pane.rotationDegrees.toString(),
    ),
    presetAvailable = preset,
)

private fun calibration(raw: DirectCameraCrop, corrected: DirectCameraCrop, dewarp: CameraDewarpConfig) =
    CalibrationUiState(
        original = CropUiState(percent(raw.left), percent(raw.top), percent(raw.width), percent(raw.height)),
        sourceAspect = raw.aspectMode,
        correctionEnabled = dewarp.enabled,
        fov = dewarp.fovDegrees.toString(),
        projection = dewarp.projection,
        corrected = CropUiState(percent(corrected.left), percent(corrected.top),
            percent(corrected.width), percent(corrected.height)),
        mirrored = raw.mirrorHorizontally,
        outputMode = raw.rotationMode,
        rotation = raw.rotationDegrees.toString(),
    )

private fun crop(rect: ReverseCameraLayout.Rect) = CropUiState(
    percent(rect.left), percent(rect.top), percent(rect.width), percent(rect.height))

private fun geometry(rect: ReverseCameraLayout.Rect, visible: Boolean) = ReverseGeometryUiState(
    percent(rect.left), percent(rect.top), percent(rect.width), percent(rect.height), visible)

private fun blindId(profile: CameraProfile) = CameraProfileId.Blind(
    if (profile.front()) CameraGroup.Front else CameraGroup.Rear,
    if (profile.right()) CameraSide.Right else CameraSide.Left)

private fun reverseElement(index: Int) = when (index) {
    ReverseCameraLayout.REAR_CAMERA_INDEX -> ReverseElement.Rear
    ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX -> ReverseElement.RearLeft
    else -> ReverseElement.RearRight
}

private fun storedRootTab(preferences: SharedPreferences): RootTab {
    val stored = if (preferences.contains("selected_tab")) preferences.getInt("selected_tab", 0)
    else if (preferences.getBoolean("camera_tab_selected", false)) 1 else 0
    return when (stored) {
        1, 4 -> RootTab.Blind
        8 -> RootTab.Parking
        5 -> RootTab.Reverse
        7 -> RootTab.Settings
        2, 3 -> RootTab.Debug
        else -> RootTab.Signals
    }
}

private fun percent(value: Float) = decimal(value * 100f)
private fun decimal(value: Float) = if (value == value.toInt().toFloat()) value.toInt().toString()
else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private inline fun <reified T : Enum<T>> enumPreference(
    preferences: SharedPreferences, key: String, fallback: T,
): T = enumValues<T>().getOrElse(preferences.getInt(key, fallback.ordinal)) { fallback }

/** Uses the production overlay/layout math while keeping Compose's drag state normalized. */
fun productionPlacementGeometry(
    profile: CameraProfileId,
    display: CameraDisplayGeometry,
    sizePercent: Float,
    frameAspect: Float,
    x: Float,
    y: Float,
): ProductionPlacementGeometry {
    val width = display.width.coerceAtLeast(1)
    val height = display.height.coerceAtLeast(1)
    val pixels = when (profile) {
        is CameraProfileId.Blind -> {
            BlindSpotOverlayController.overlayGeometry(
                width, height, sizePercent.toInt(), frameAspect, x, y,
                display.marginLeft.coerceAtLeast(0), display.marginTop.coerceAtLeast(0),
                display.marginBottom.coerceAtLeast(0),
            )
        }
        is CameraProfileId.Parking -> ParkingCameraController.overlayGeometry(
            width, height, sizePercent.toInt(), x, y)
        is CameraProfileId.Reverse -> intArrayOf(
            0, 0, width, height,
        )
    }
    return ProductionPlacementGeometry(
        pixels[0], pixels[1], pixels[2], pixels[3], width, height)
}

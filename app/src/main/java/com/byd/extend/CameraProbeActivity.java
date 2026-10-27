package com.byd.extend;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.text.method.DigitsKeyListener;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewParent;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.activity.ComponentActivity;

import com.byd.extend.ui.AvmOrientation;
import com.byd.extend.ui.AvasActionKind;
import com.byd.extend.ui.AvasAssetUiState;
import com.byd.extend.ui.AvasAuditionUiState;
import com.byd.extend.ui.AvasBackendAction;
import com.byd.extend.ui.AvasEngineUiState;
import com.byd.extend.ui.AvasPlaybackUiState;
import com.byd.extend.ui.AvasProfileUiState;
import com.byd.extend.ui.AvasUiState;
import com.byd.extend.ui.BydExtendUiAction;
import com.byd.extend.ui.CameraGroup;
import com.byd.extend.ui.CameraSection;
import com.byd.extend.ui.CameraHostKind;
import com.byd.extend.ui.CameraHostSlot;
import com.byd.extend.ui.CameraDisplayGeometry;
import com.byd.extend.ui.DisplayTarget;
import com.byd.extend.ui.CameraProfileId;
import com.byd.extend.ui.CameraSide;
import com.byd.extend.ui.CommandId;
import com.byd.extend.ui.DiagnosticMode;
import com.byd.extend.ui.DialogKind;
import com.byd.extend.ui.DialogUiState;
import com.byd.extend.ui.NumberTarget;
import com.byd.extend.ui.BlindNumber;
import com.byd.extend.ui.GuardNumber;
import com.byd.extend.ui.HeaderUiState;
import com.byd.extend.ui.OutputNumber;
import com.byd.extend.ui.ParkingNumber;
import com.byd.extend.ui.ProfileNumber;
import com.byd.extend.ui.ReverseElement;
import com.byd.extend.ui.ReverseGeometryNumber;
import com.byd.extend.ui.ReverseUiState;
import com.byd.extend.ui.ReverseSource;
import com.byd.extend.ui.ParkingView;
import com.byd.extend.ui.ProductionUiBackend;
import com.byd.extend.ui.ProductionUiController;
import com.byd.extend.ui.ProductionUiInstaller;
import com.byd.extend.ui.RootTab;
import com.byd.extend.ui.SelectionId;
import com.byd.extend.ui.SelectionTarget;
import com.byd.extend.ui.SettingsOperation;
import com.byd.extend.ui.StatusTone;
import com.byd.extend.ui.StatusUiState;
import com.byd.extend.ui.ToggleId;
import com.byd.extend.ui.ToggleTarget;
import com.byd.extend.ui.UiLanguage;
import com.byd.extend.ui.UiStrings;
import com.byd.extend.ui.MirrorBackendAction;
import com.byd.extend.ui.MirrorBackendActionKind;
import com.byd.extend.ui.MirrorGeometryUiState;
import com.byd.extend.ui.MirrorUiState;
import com.byd.extend.ui.CropUiState;
import com.byd.extend.ui.motion.MotionCompositionPreviewView;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraProbeActivity extends ComponentActivity
        implements SurfaceHolder.Callback, BlindSpotCameraView.Callback,
        ReverseCameraCompositionView.Callback, MotionCompositionPreviewView.Listener,
        ProductionUiBackend {
    private static final String TAG = "BydExtend";
    private static final String OPEN_METEO_URL = "https://open-meteo.com/";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST = 17;
    private static final String PREF_WEATHER_BACKGROUND_PERMISSION_ASKED =
            "weather_background_permission_asked";
    private static final String STATE_WEATHER_BACKGROUND_SETTINGS = "weather_background_settings";
    private static final int MICROPHONE_PERMISSION_REQUEST = 12;
    private static final int BLUETOOTH_PERMISSION_REQUEST = 16;
    private static final String PREF_BLUETOOTH_PERMISSION_ASKED = "calls_bluetooth_permission_asked";
    private static final String STATE_BLUETOOTH_PROFILES = "bluetooth_pending_profiles";
    private static final String STATE_BLUETOOTH_PERMISSION_IN_FLIGHT = "bluetooth_permission_in_flight";
    private static final int CAMERA_PRESET_REQUEST = 12;
    private static final int AVAS_AUDIO_REQUEST = 13;
    private static final int ARCHIVE_SAVE_REQUEST = 14;
    private static final int CAMERA_PRESET_SAVE_REQUEST = 15;
    private static final String STATE_PRESET_SAVE_NAME = "preset_save_name";
    private static final String STATE_ARCHIVE_SAVE_NAME = "archive_save_name";
    private static final String STATE_ARCHIVE_SAVE_COMPATIBILITY = "archive_save_compatibility";
    private static final String STATE_UPDATE_CHECK_DIALOG_REQUESTED = "update_check_dialog_requested";
    private static final String STATE_UPDATE_CHECK_DIALOG_RESULT = "update_check_dialog_result";
    private static final int UPDATE_CHECK_RESULT_NONE = 0;
    private static final int UPDATE_CHECK_RESULT_ERROR = 1;
    private static final int UPDATE_CHECK_RESULT_LATEST = 2;
    private static final float DEFAULT_OUTWARD_DEG = 90.0f;
    private static final float DEFAULT_CENTER_DEG = 10.0f;
    private static final int DEFAULT_CORRECTION_DELAY_MS = 100;
    private static final int DEFAULT_MAX_SPEED_KPH = 30;
    private static final int DEFAULT_CAMERA_MIN_SPEED_KPH = 10;
    private static final int DEFAULT_CAMERA_MAX_SPEED_KPH = 300;
    static final long ADB_AUTH_UI_SETTLE_MS = 600;
    static final long BACKGROUND_START_UI_SETTLE_MS = 600;
    static final long WEATHER_PERMISSION_UI_SETTLE_MS = 600;
    private static final String PREF_BACKGROUND_START_SETTINGS_SHOWN =
            "background_start_settings_shown";
    private static final String PREF_WEATHER_PERMISSION_REQUEST_PENDING =
            "weather_permission_request_pending";
    private static final String PREF_WEATHER_PERMISSION_MIGRATION_SEEN =
            "weather_permission_migration_seen";
    private static final String STATE_WEATHER_PERMISSION_IN_FLIGHT =
            "weather_permission_in_flight";
    private static final String STATE_CAMERA_PERMISSION_REQUESTED = "camera_permission_requested";
    private static final String STATE_MICROPHONE_PERMISSION_PENDING = "microphone_permission_pending";
    private static final String STATE_MANUAL_ADB_EVENT = "manual_adb_event";
    private static final String STATE_MANUAL_ADB_OPERATION = "manual_adb_operation";
    private static final String STATE_WEATHER_PERMISSION_PENDING =
            "weather_permission_pending";
    private static final String STATE_WEATHER_ENABLE_REQUESTED =
            "weather_enable_requested";
    private static final String STATE_WEATHER_REFRESH_AFTER_PERMISSION =
            "weather_refresh_after_permission";
    private static final String STATE_STARTUP_OVERLAY_PERMISSION_ATTEMPTED =
            "startup_overlay_permission_attempted";
    private static final String STATE_STARTUP_OVERLAY_SETTINGS_IN_FLIGHT =
            "startup_overlay_settings_in_flight";
    private static final String STATE_AVAS_IMPORT_PROFILE = "avas_import_profile";
    private static final String PREF_AVAS_IMPORT_PROFILE = "ui_avas_import_profile";
    private static final String PREF_AVAS_IMPORT_PHASE = "ui_avas_import_phase";
    private static final String AVAS_IMPORT_PHASE_PICKER = "picker";
    private static final String AVAS_IMPORT_PHASE_DECODING = "decoding";
    private static volatile String avasImportInFlightProfile;
    private static final String BYD_START_SETTINGS_PACKAGE = "com.byd.appstartmanagement";
    private static final String BYD_START_SETTINGS_CLASS =
            "com.byd.appstartmanagement.frame.AppStartManagement";
    private static final long CAMERA_PREVIEW_HANDOFF_MS = 250;
    private static final long CAMERA_PREVIEW_FIRST_FRAME_TIMEOUT_MS = 3_000;
    static final int CAMERA_PREVIEW_READY_FRAME_UPDATES = 2;
    private static final int TAB_GUARD = 0;
    private static final int TAB_CAMERAS = 1;
    private static final int TAB_CAMERA_DEBUG = 2;
    private static final int TAB_DIRECT_CAMERA_DEBUG = 3;
    private static final int TAB_CAMERA_CALIBRATION = 4;
    private static final int TAB_REVERSE_CAMERAS = 5;
    private static final int TAB_MUSIC = 6;
    private static final int TAB_SETTINGS = 7;
    private static final int TAB_PARKING_CAMERAS = 8;
    private static final int TAB_REARVIEW_MIRROR = 9;
    private static final int REVERSE_INSPECTOR_POSITION = 0;
    private static final int REVERSE_INSPECTOR_CROP = 1;
    private static final int REVERSE_INSPECTOR_ROTATION = 2;
    private static final int REVERSE_INSPECTOR_CORRECTION = 3;
    private static final int CROP_STAGE_NONE = -1;
    private static final int CROP_STAGE_RAW = 0;
    private static final int CROP_STAGE_CORRECTED = 1;
    private static final String PREF_FRONT_CAMERA_ENABLED = "camera_front_enabled";
    private static final String PREF_FRONT_CAMERA_MIN_SPEED = "camera_front_min_speed_kph";
    private static final String PREF_FRONT_CAMERA_MAX_SPEED = "camera_front_max_speed_kph";
    private static final String PREF_FRONT_CAMERA_MIN_ANGLE = "camera_front_min_angle_deg";
    private static final String PREF_FRONT_CAMERA_TURN_REQUIRED =
            "camera_front_turn_required";
    private static final int DEFAULT_FRONT_CAMERA_MIN_SPEED_KPH = 0;
    private static final int DEFAULT_FRONT_CAMERA_MAX_SPEED_KPH = 10;
    private static final float DEFAULT_FRONT_CAMERA_MIN_ANGLE_DEG = 10.0f;
    private static final long CALIBRATION_COPY_INTERVAL_MS = 100;
    static final int OUTPUT_MIRROR_BUTTON_WIDTH_DP = 120;
    static final int CALIBRATION_ROTATION_ROW_HEIGHT_DP = 42;
    static final int PARKING_SELECTOR_ORIENTATION = LinearLayout.HORIZONTAL;
    static final float PARKING_SELECTOR_BUTTON_WEIGHT = 1.0f;
    static final float PARKING_SETTINGS_WEIGHT = 0.65f;
    static final float PARKING_PREVIEW_WEIGHT = 0.35f;
    private static final String[] BLIND_CALIBRATION_LABELS = {
            "Задня ліва", "Задня права", "Передня ліва", "Передня права"};
    private static final String[] PARKING_CALIBRATION_LABELS = {
            "Перед-ліво", "Перед", "Перед-право",
            "Зад-праворуч", "Зад", "Зад-ліворуч", "Ліво", "Право"};
    private static final DirectCameraCrop FULL_CALIBRATION_CROP = DirectCameraCrop.of(
            0.0f, 0.0f, 1.0f, 1.0f,
            DirectCameraCrop.ASPECT_FREE, CameraRotation.DEFAULT_DEGREES);
    private static final String EXTRA_DIAGNOSTIC_AVM_MODE_INDEX =
            "com.byd.extend.extra.AVM_MODE_INDEX";
    private static final String EXTRA_DIAGNOSTIC_AVM_CLOSE =
            "com.byd.extend.extra.AVM_CLOSE";
    private static int activityCameraRequestSequence;
    private static long helperCallbackRegistrationSequence;
    private static final Object REVERSE_OWNER_LOCK = new Object();
    private static WeakReference<CameraProbeActivity> reverseOwner;
    private static long reverseOwnerTokenSequence;
    private static long reverseOwnerEpoch;

    static String calibrationTransferLabel(boolean rightCamera) {
        return rightCamera ? "← Перенести" : "Перенести →";
    }

    static String reverseTransferLabel(int cameraIndex) {
        return cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX
                ? "← Перенести" : "Перенести →";
    }

    static String parkingCalibrationTransferLabel(ParkingCameraProfile profile) {
        if (profile == null) throw new IllegalArgumentException("parking profile required");
        return profile.id == ParkingCameraProfile.FR
                || profile.id == ParkingCameraProfile.RR
                || profile.id == ParkingCameraProfile.REAR
                || profile.id == ParkingCameraProfile.RIGHT
                ? "← Перенести" : "Перенести →";
    }

    static String calibrationScopeLabel(boolean parking, int logicalId) {
        return parking
                ? "parking_" + ParkingCameraProfile.of(logicalId).wireName
                : "overlay_" + CameraProfile.of(logicalId).wireName;
    }

    static final class ReverseOwnerSnapshot {
        final CameraProbeActivity activity;
        final long token;
        final long epoch;

        ReverseOwnerSnapshot(CameraProbeActivity activity, long token, long epoch) {
            this.activity = activity;
            this.token = token;
            this.epoch = epoch;
        }
    }

    /**
     * Returns the single foreground Activity presence, including an ineligible transition
     * sentinel.  A non-null snapshot owns the key event and therefore prevents runtime fallback.
     */
    static boolean dispatchReverseSteeringToggle() {
        ReverseOwnerSnapshot snapshot;
        synchronized (REVERSE_OWNER_LOCK) {
            CameraProbeActivity owner = reverseOwner == null ? null : reverseOwner.get();
            if (owner == null || !owner.reverseOwnerPresent) return false;
            snapshot = new ReverseOwnerSnapshot(owner, owner.reverseOwnerToken,
                    reverseOwnerEpoch);
        }
        snapshot.activity.dispatchReverseSteeringToggle(snapshot.token, snapshot.epoch);
        return true;
    }

    static long reverseOwnerEpochSnapshot() {
        synchronized (REVERSE_OWNER_LOCK) {
            return reverseOwnerEpoch;
        }
    }

    /** True only if no foreground owner appeared or changed since the supplied snapshot. */
    static boolean reverseOwnerStillAbsent(long epoch) {
        synchronized (REVERSE_OWNER_LOCK) {
            CameraProbeActivity owner = reverseOwner == null ? null : reverseOwner.get();
            return reverseOwnerEpoch == epoch && (owner == null || !owner.reverseOwnerPresent);
        }
    }

    static void publishCameraSteeringButtonCaptured(CameraButtonBindings.Action action) {
        ReverseOwnerSnapshot snapshot;
        synchronized (REVERSE_OWNER_LOCK) {
            CameraProbeActivity owner = reverseOwner == null ? null : reverseOwner.get();
            if (owner == null || !owner.reverseOwnerPresent) return;
            snapshot = new ReverseOwnerSnapshot(owner, owner.reverseOwnerToken,
                    reverseOwnerEpoch);
        }
        snapshot.activity.mainHandler.post(() -> {
            if (!snapshot.activity.isReverseOwnerCurrent(snapshot.token, snapshot.epoch)) return;
            if (snapshot.activity.productionUi != null) {
                snapshot.activity.productionUi.dismissCameraButtonCaptureDialog(action);
                snapshot.activity.productionUi.reload();
            }
        });
    }

    static void publishMirrorSettingsChanged() {
        ReverseOwnerSnapshot snapshot;
        synchronized (REVERSE_OWNER_LOCK) {
            CameraProbeActivity owner = reverseOwner == null ? null : reverseOwner.get();
            if (owner == null || !owner.reverseOwnerPresent) return;
            snapshot = new ReverseOwnerSnapshot(owner, owner.reverseOwnerToken, reverseOwnerEpoch);
        }
        snapshot.activity.mainHandler.post(() -> {
            if (snapshot.activity.isReverseOwnerCurrent(snapshot.token, snapshot.epoch)) {
                snapshot.activity.refreshProductionMirrorState();
            }
        });
    }

    static void publishAvasMicrophoneChanged() {
        CameraProbeActivity owner;
        synchronized (REVERSE_OWNER_LOCK) {
            owner = reverseOwner == null ? null : reverseOwner.get();
        }
        if (owner != null) owner.mainHandler.post(() -> {
            if (!owner.activityDestroyed && owner.productionUi != null) {
                owner.productionUi.refreshAvasState();
                owner.refreshProductionHeader();
            }
        });
    }

    private void refreshProductionMirrorState() {
        if (productionUi == null || activityDestroyed || shutdownRequested) return;
        boolean changed = productionUi.getState().getMirror().getActiveFront()
                != RearviewMirrorSettings.activeFront(preferences);
        productionUi.reload();
        if (changed && selectedTab == TAB_REARVIEW_MIRROR) {
            onProductionCameraSelectionChanged(SelectionId.MirrorSource);
        }
    }

    private void publishReverseOwnerPresence() {
        synchronized (REVERSE_OWNER_LOCK) {
            if (!reverseOwnerPresent
                    || reverseOwner == null || reverseOwner.get() != this) {
                reverseOwnerToken = ++reverseOwnerTokenSequence;
                reverseOwnerPresent = true;
            }
            reverseOwner = new WeakReference<>(this);
            reverseOwnerEpoch++;
        }
    }

    private void publishReverseOwnerIneligible() {
        synchronized (REVERSE_OWNER_LOCK) {
            if (!reverseOwnerPresent
                    || reverseOwner == null || reverseOwner.get() != this) return;
            reverseOwnerEpoch++;
        }
    }

    private void clearReverseOwnerPresence() {
        synchronized (REVERSE_OWNER_LOCK) {
            if (!reverseOwnerPresent
                    || reverseOwner == null || reverseOwner.get() != this) return;
            reverseOwnerPresent = false;
            reverseOwnerEpoch++;
            reverseOwner = null;
        }
    }

    private boolean isReverseOwnerCurrent(long token, long epoch) {
        synchronized (REVERSE_OWNER_LOCK) {
            return reverseOwnerPresent && reverseOwnerToken == token
                    && reverseOwnerEpoch == epoch
                    && reverseOwner != null && reverseOwner.get() == this;
        }
    }

    private boolean isReverseToggleEligible() {
        if (!activityResumed || activityDestroyed || shutdownRequested
                || !requestedOpen || activePreview != reverseCameraPreview
                || !activeActivityCameraOpened || activeActivityCameraRequestId <= 0
                || reverseCameraPreview == null || productionUi == null
                || selectedTab != TAB_REVERSE_CAMERAS || isProductionCalibrationSection()) return false;
        if (!productionUi.getState().getReverse().getEnabled()) return false;
        return ReverseCameraController.loadWidgetVisible(preferences)
                && ReverseCameraController.hasAnyFrontIntegration(preferences);
    }

    private void dispatchReverseSteeringToggle(long token, long epoch) {
        // Capture the camera host/request at key-dispatch time.  The owner epoch alone
        // does not distinguish a close/open transition that reuses this Activity instance;
        // stale work must not toggle the newly-created Reverse request.
        final int expectedRequestId = activeActivityCameraRequestId;
        final View expectedPreview = reverseCameraPreview;
        mainHandler.post(() -> {
            if (!isReverseOwnerCurrent(token, epoch) || !isReverseToggleEligible()) return;
            if (activeActivityCameraRequestId != expectedRequestId
                    || reverseCameraPreview != expectedPreview
                    || activePreview != expectedPreview) return;
            int mode = reverseCameraPreview.sideMode();
            int next = mode == ReverseSideSelectorView.MODE_FRONT
                    ? ReverseSideSelectorView.MODE_REAR
                    : ReverseSideSelectorView.MODE_FRONT;
            reverseCameraPreview.setSideMode(next);
        });
    }

    static final class CalibrationEntry {
        final int originTab;
        final boolean parking;
        final int logicalId;

        private CalibrationEntry(int originTab, boolean parking, int logicalId) {
            this.originTab = originTab;
            this.parking = parking;
            this.logicalId = logicalId;
        }
    }



    private final ExecutorService ipcExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService logExportExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService avasImportExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CameraTransition cameraTransition = new CameraTransition();
    private final StartupOverlayPermissionFlow startupOverlayPermissionFlow =
            new StartupOverlayPermissionFlow();
    private final PreviewFreshnessGate productionPreviewFreshness =
            new PreviewFreshnessGate();
    private final PreviewFreshnessGate calibrationPreviewFreshness =
            new PreviewFreshnessGate();
    private final PreviewFreshnessGate reversePreviewFreshness =
            new PreviewFreshnessGate();
    private final ReverseCalibrationFreshnessGate reverseCalibrationFreshness =
            new ReverseCalibrationFreshnessGate();
    private final HelperCallbackRegistration<IBinder> helperCallbackRegistration =
            new HelperCallbackRegistration<>();
    private final Paint calibrationCropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private SharedPreferences preferences;
    private ProductionUiController productionUi;
    private AvasAudioLibrary avasLibrary;
    private final Map<String, AvasPlaybackUiState> avasPlayback = new HashMap<>();
    private AvasAuditionUiState avasAudition = new AvasAuditionUiState();
    private String avasEngineState = "stopped";
    private String avasEngineError = "";
    private boolean avasEngineTestActive;
    private String lastStoppedAvasSession = "";
    private final Map<String, Long> avasDurations = new HashMap<>();
    private final Set<String> avasDurationRequests = new HashSet<>();
    private long reverseOwnerToken;
    private boolean reverseOwnerPresent;
    private final EnumMap<CameraHostKind, View> productionCameraHosts =
            new EnumMap<>(CameraHostKind.class);
    private final EnumMap<CameraHostKind, CameraHostSlot> productionCameraSlots =
            new EnumMap<>(CameraHostKind.class);
    private final EnumMap<CameraHostKind, MotionPreviewOutput> motionPreviewOutputs =
            new EnumMap<>(CameraHostKind.class);

    private static final class MotionPreviewOutput {
        final CameraHostKind kind;
        final int direction;
        final MotionCompositionPreviewView view;
        final int requestId;
        Surface surface;
        IBinder cameraOwner;
        boolean openQueued;
        boolean opened;
        boolean openUncertain;
        boolean closeQueued;
        boolean releaseSurfaceWhenClosed;

        MotionPreviewOutput(CameraHostKind kind, int direction,
                MotionCompositionPreviewView view, Surface surface, int requestId) {
            this.kind = kind;
            this.direction = direction;
            this.view = view;
            this.surface = surface;
            this.requestId = requestId;
        }
    }

    /** Compose receives these roots; camera state always stays on the child owner views. */
    private FrameLayout productionPlacementHost;
    private CropMaskView productionPlacementCropMask;
    private View productionPlacementBorder;
    private FrameLayout productionDirectHost;
    private FrameLayout productionAvmHost;
    private BlindSpotCameraView cameraPreview;
    private View cameraPreviewCover;
    private TextureView debugPreview;
    private StockAvmOutput debugOutput;
    private View debugPreviewCover;
    private SurfaceView directCameraPreview;
    private View directCameraPreviewCover;
    private BlindSpotCameraView calibrationPreview;
    private View calibrationPreviewCover;
    private CameraCropOverlayView calibrationCropOverlay;
    private TextureView calibrationRawMirror;
    private TextureView calibrationCorrectedMirror;
    private FrameLayout calibrationRawMirrorHost;
    private FrameLayout calibrationCorrectedMirrorHost;
    private FrameLayout calibrationOutputHost;
    private CropMaskView calibrationOutputCropMask;
    private View productionCalibrationBorder;
    /**
     * Retained Compose calibration workspace identity.  Individual conditional stage hosts may
     * disappear (for example Corrected when correction is switched off), but this bundle keeps the
     * single owner and all three stage roots until an explicit workspace/tab teardown.  It is also
     * the identity gate used by release callbacks so a stale host cannot detach a replacement
     * owner's mirror.
     */
    private final CalibrationHostBundle calibrationHostBundle = new CalibrationHostBundle();
    private View calibrationRawMirrorCover;
    private View calibrationCorrectedMirrorCover;
    private CameraCropOverlayView productionCalibrationRawOverlay;
    private CameraCropOverlayView productionCalibrationCorrectedOverlay;
    // Overlay callbacks report MOVE and UP separately.  Keep the gesture's
    // initial crop so the final UP is classified as the same transform edit
    // instead of accidentally becoming a FREE geometry save.
    private DirectCameraCrop productionCalibrationGestureStart;
    private Integer calibrationHostSourceIndex;
    private boolean productionCalibrationGestureCorrected;
    private boolean productionCalibrationGestureTransformChanged;
    private boolean productionCalibrationGestureActive;
    private CameraCropOverlayView calibrationRawCropOverlay;
    private View calibrationRawPane;
    private View calibrationCorrectedPane;
    private final TextView[] calibrationCropTitles = new TextView[2];
    private final TextView[] calibrationCropReadouts = new TextView[2];
    private final Button[] calibrationCropInputButtons = new Button[2];
    private FrameLayout calibrationControlsHost;
    private View calibrationNormalControls;
    private View calibrationCropInputControls;
    private final EditText[] calibrationCropInputs = new EditText[4];
    private int calibrationCropInputStage = CROP_STAGE_NONE;
    private Button calibrationPresetLoadButton;
    private Button calibrationMirrorButton;
    private Button calibrationOutputMirrorButton;
    private DirectCameraCrop calibrationRawCrop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop calibrationCorrectedCrop = DirectCameraCrop.defaultFor(false);
    private long productionCalibrationHostGeneration;
    private ImageView calibrationCropPreview;
    private FrameLayout calibrationSourceHost;
    private View calibrationSourceFrame;
    private FrameLayout calibrationResultHost;
    private View calibrationResultFrame;
    private ReverseCameraCompositionView reverseCameraPreview;
    private ReverseSteeringSample reverseSteeringSample = ReverseSteeringSample.UNKNOWN;
    private final Runnable reverseSteeringExpired = this::applyReverseSteering;
    private FrameLayout productionReverseHost;
    private ReverseCameraEditorView productionReverseEditor;
    private boolean productionReverseEditorEditable;
    private ReverseCameraEditorView reverseCameraEditor;
    private ReverseCameraLayout reverseCameraLayout;
    private ReverseCameraLayout reverseRawCalibrationLayout;
    private ReverseCameraLayout reverseFrontCameraLayout;
    private ReverseCameraLayout reverseFrontRawCalibrationLayout;
    private Switch reverseCameraSwitch;
    private Switch reverseVisibilitySwitch;
    private Switch reverseFrontIntegrationSwitch;
    private Spinner reverseDisplayModeInput;
    private Button reverseCalibrationButton;
    private View reverseMainEditorPane;
    private View reverseCalibrationPane;
    private TextureView reverseCalibrationRawMirror;
    private TextureView reverseCalibrationCorrectedMirror;
    private CameraCropOverlayView reverseCalibrationRawOverlay;
    private CameraCropOverlayView reverseCalibrationCorrectedOverlay;
    private View reverseCalibrationCorrectedStage;
    private final TextView[] reverseCalibrationCropTitles = new TextView[2];
    private final TextView[] reverseCalibrationCropReadouts = new TextView[2];
    private final Button[] reverseCalibrationCropInputButtons = new Button[2];
    private FrameLayout reverseCalibrationControlsHost;
    private View reverseCalibrationNormalControls;
    private View reverseCalibrationCropInputControls;
    private final EditText[] reverseCalibrationCropInputs = new EditText[4];
    private int reverseCalibrationCropInputStage = CROP_STAGE_NONE;
    private Button reversePresetLoadButton;
    private Button reverseMirrorButton;
    private Button reverseOutputMirrorButton;
    private ImageView reverseCalibrationLivePreview;
    private FrameLayout reverseCalibrationLiveFrame;
    private int reverseCalibrationCameraIndex = -1;
    private boolean reverseCalibrationFront;
    private Button reverseRearCalibrationSourceButton;
    private Button reverseFrontCalibrationSourceButton;
    private View reverseCalibrationSourceSelector;
    private Bitmap reverseCalibrationCaptureBitmap;
    private Bitmap reverseCalibrationResultBitmap;
    private boolean reverseCalibrationCopyPending;
    private final Button[] reversePaneButtons = new Button[5];
    private final Button[] reverseInspectorButtons = new Button[4];
    private SeekBar reverseRotationSlider;
    private TextView reverseRotationValue;
    private Button reverseLowerButton;
    private Button reverseRaiseButton;
    private boolean reverseDisplayModeUiUpdating;
    private boolean reverseVisibilityUiUpdating;
    private boolean reverseRotationUiUpdating;
    private int reverseInspectorMode = REVERSE_INSPECTOR_POSITION;
    private View activePreview;
    private View activePreviewCover;
    private Button rawButton;
    private Button debugHorizontalButton;
    private Button debugVerticalButton;
    private Switch debugShowRawSwitch;
    private Switch debugDewarpSwitch;
    private Button closeButton;
    private Button guardTabButton;
    private Button calibrationTabButton;
    private Button cameraTabButton;
    private Button parkingTabButton;
    private Button cameraDebugTabButton;
    private Button directCameraDebugTabButton;
    private Button cameraAvmDebugSubtabButton;
    private Button reverseCameraTabButton;
    private Button musicTabButton;
    private Button settingsTabButton;
    private final Button[] calibrationCameraButtons = new Button[ParkingCameraProfile.COUNT];
    private final Button[] parkingCameraButtons = new Button[ParkingCameraProfile.COUNT];
    private Button calibrationResetButton;
    private SeekBar calibrationRotationSlider;
    private TextView calibrationRotationValue;
    private Spinner calibrationRotationModeInput;
    private boolean calibrationRotationUiUpdating;
    private boolean calibrationRotationModeUiUpdating;
    private Switch calibrationDewarpSwitch;
    private SeekBar calibrationDewarpFovSlider;
    private TextView calibrationDewarpFovValue;
    private Spinner calibrationDewarpProjectionInput;
    private boolean calibrationDewarpUiUpdating;
    private Switch reverseDewarpSwitch;
    private SeekBar reverseDewarpFovSlider;
    private TextView reverseDewarpFovValue;
    private Spinner reverseDewarpProjectionInput;
    private boolean reverseDewarpUiUpdating;
    private View guardPage;
    private View calibrationPage;
    private View cameraPage;
    private View parkingPage;
    private View cameraDebugPage;
    private View directCameraDebugPage;
    private View debugPage;
    private View reverseCameraPage;
    private View musicPage;
    private View settingsPage;
    private File logFile;
    private AsyncServiceLog activityLog;
    private volatile IBinder helper;
    private volatile boolean cameraSurfaceReady;
    private volatile boolean debugSurfaceReady;
    private volatile boolean directCameraSurfaceReady;
    private volatile boolean calibrationSurfaceReady;
    private volatile boolean reverseCameraSurfacesReady;
    private boolean activityCameraBufferRefreshPending;
    private volatile boolean cameraDiscovered;
    private volatile boolean requestedOpen;
    private boolean telemetryReady;
    private boolean cameraVehiclePowerOff;
    private boolean manualGearPark;
    private boolean manualTurnRequestPending;
    private StatusUiState manualSignalStatus = new StatusUiState("", StatusTone.Neutral, false);
    private boolean adbAuthPending;
    private LocalAdbClient.PromptMode adbAuthMode;
    private boolean adbAuthorizationRequested;
    private boolean adbAuthorizationStartScheduled;
    private String pendingManualAdbEvent;
    private String pendingManualAdbOperation;
    private boolean cameraPermissionPending;
    private boolean cameraPermissionRequested;
    private boolean microphonePermissionPending;
    private boolean microphonePermissionInFlight;
    private final java.util.EnumSet<CallsSettings.Profile> pendingBluetoothProfiles =
            java.util.EnumSet.noneOf(CallsSettings.Profile.class);
    private boolean bluetoothPermissionInFlight;
    private boolean weatherRefreshAfterPermission;
    private boolean weatherLocationPermissionPending;
    private boolean weatherLocationPermissionStartScheduled;
    private boolean weatherLocationPermissionInFlight;
    private boolean weatherEnableRequestedForPermission;
    private boolean weatherBackgroundSettingsOpened;
    private long weatherRefreshUiGeneration;
    private boolean backgroundStartSettingsRequired;
    private InstallationReminder installationReminder;
    private boolean backgroundStartSettingsActive;
    private boolean backgroundStartSettingsStartScheduled;
    private boolean helperBound;
    private boolean shutdownRequested;
    private boolean activityResumed;
    private boolean activityStarted;
    private boolean activityDestroyed;
    private boolean updateInstallRequested;
    private boolean updateInstallerOpened;
    private CameraButtonBindings.Action pendingButtonLearning;
    private long buttonLearningEpoch;
    private final SharedPreferences.OnSharedPreferenceChangeListener permissionsPreferenceListener =
            (prefs, key) -> mainHandler.post(() -> {
                if (activityStarted && !activityDestroyed) {
                    refreshProductionHeader();
                    if (productionUi != null) productionUi.refreshAdbRecoveryState();
                }
            });
    private final Runnable adbRecoveryListener = () -> {
        if (!activityStarted || activityDestroyed) return;
        if (productionUi != null) productionUi.refreshAdbRecoveryState();
        refreshProductionHeader();
    };
    private final WeatherRefreshAccessibilityService.ConnectionListener accessibilityConnectionListener =
            connected -> mainHandler.post(() -> {
                if (!activityStarted || activityDestroyed) return;
                refreshProductionHeader();
                // A lost live capture is canceled; an outstanding bounded reconnect may still finish.
                if (!connected && pendingButtonLearning == null) cancelReverseButtonLearningIfVisible();
            });
    private final LocalAdbClient.AccessStateListener adbAccessListener = state ->
            mainHandler.post(() -> {
                if (activityStarted && !activityDestroyed) {
                    refreshProductionHeader();
                    advanceStartupAuthorizationFlow();
                }
            });
    private final Runnable foregroundAdbListener = () -> mainHandler.post(() -> {
        if (!activityStarted || activityDestroyed) return;
        reconcileForegroundAdbAuthorization();
        updateControls();
        advanceStartupAuthorizationFlow();
    });
    private boolean updateCheckInFlight;
    private boolean updateCheckDialogRequested;
    private int updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
    private final UpdateHintRuntime.CheckListener updateCheckListener = new UpdateHintRuntime.CheckListener() {
        @Override public void onCheckStarted(boolean force) { onUpdateCheckStarted(force); }
        @Override public void onCheckDiscarded() {
            updateCheckInFlight = false;
            updateCheckDialogRequested = false;
            updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
            UpdateHintRuntime.get(CameraProbeActivity.this)
                    .setManualCheckDialogRequested(false);
            if (activityDestroyed || isFinishing()) return;
            DialogUiState dialog = productionUi == null ? null
                    : productionUi.getState().getDialog();
            if (runtimeDialogOwner == RuntimeDialogOwner.UPDATE && dialog != null
                    && runtimeText(R.string.runtime_update_checking).equals(
                            dialog.getUpdateStatus())) {
                clearRuntimeDialog();
            }
            publishSettingsOperation(SettingsOperation.Update, "", StatusTone.Neutral, false);
        }
        @Override public void onCheckFinished(AppUpdateManager.UpdateInfo available,
                Throwable error, boolean force) {
            onUpdateCheckFinished(available, error, force);
        }
    };
    private boolean logExportInProgress;
    private boolean compatibilityExportInProgress;
    private boolean exportArchiveToDocument;
    private File pendingArchiveDocument;
    private File pendingPresetDocument;
    private boolean pendingArchiveCompatibility;
    private volatile CompatibilityBundleExporter.ExportControl compatibilityExportControl;
    private volatile CompatibilityBundleExporter.ExportControl diagnosticExportControl;
    private CompatibilityBundleExporter.Progress diagnosticExportProgress;
    private boolean diagnosticDocumentCopy;
    private boolean settingsTransferInProgress;
    private SettingsOperation activeSettingsOperation;
    private boolean settingsReloadPending;
    private boolean legacyRuntimeBlocked;
    private AlertDialog settingsTransferDialog;
    private AlertDialog legacyImportOfferDialog;
    private boolean debugHorizontal = true;
    private int selectedCameraId = CameraProfile.REAR_LEFT;
    private int calibrationCameraId = CameraProfile.REAR_LEFT;
    private int calibrationParkingCameraId = ParkingCameraProfile.FL;
    private boolean calibrationParkingMode;
    private CameraProfileId calibrationHostProfile;
    private Integer transientCornerRadiusDp;
    private Integer transientTransparencyPercent;
    private CameraProfileId transientProfilePreviewId;
    private Float transientProfilePreviewFov;
    private Integer transientProfilePreviewStrength;
    private Integer transientProfilePreviewRotation;
    private int selectedParkingCameraId = ParkingCameraProfile.FL;
    private boolean calibrationCopyPending;
    private float dragStartRawX;
    private float dragStartRawY;
    private float dragStartX;
    private float dragStartY;
    private int placementGestureProfile = -1;
    private int placementGestureTarget = -1;
    private int pendingCameraViewpoint = -1;
    private int activeCameraViewpoint = -1;
    private int activeDirectCameraIndex = -1;
    private int retryStockViewpoint = -1;
    private boolean retryStockDebug;
    private boolean cameraShellRecoveryPending;
    private boolean cameraShellAvailable;
    /** Shell identity gate for Activity camera callbacks; stale epochs cannot invalidate a new host. */
    private long activityCameraShellEpoch;
    private long activityCameraShellLastDeathEpoch;
    /** Latest Stock AVM shell lifecycle epoch observed by this Activity. */
    private long activityAvmShellEpoch;
    private boolean activityCameraShellDeathHandled;
    private boolean invalidStockSurfaceRetryUsed;
    private boolean pendingCameraDebug;
    private boolean cameraHandoffPending;
    private boolean productionPreviewAwaitingFrame;
    private boolean productionPreviewRetryUsed;
    private int productionPreviewFrameUpdates;
    private int pendingReversePreviewRequestId;
    private int[] pendingReversePreviewGenerations;
    private int activeReversePreviewDisplayTarget = CameraDisplayTarget.TABLET;
    private int[] activeReversePreviewSourceIndexes = new int[0];
    private volatile int[] activeReversePreviewDirectGenerations = new int[0];
    private int reversePreviewBackgroundFailureRequestId;
    private Bitmap calibrationCaptureBitmap;
    private Bitmap calibrationResultBitmap;
    private int selectedTab = -1;
    private int selectedDebugMode;
    private volatile int activeActivityCameraRequestId;
    private CameraProfileId activeActivityCameraProfile;
    private int activeActivityConsumerGeneration;
    private volatile int[] activeActivityInputGenerations = new int[0];
    private int closingActivityCameraRequestId;
    private final ActivityCameraCloseIdentity activityCameraCloseIdentity =
            new ActivityCameraCloseIdentity();
    private boolean activeActivityCameraOpened;
    private boolean activeActivityCameraFresh;
    private boolean automaticPreviewIntent;
    private int automaticPreviewIntentTab = -1;
    private int automaticPreviewIntentRequestId;
    private final ResumeTabWarmup resumeTabWarmup = new ResumeTabWarmup();
    private int transitionTargetTab = -1;
    private boolean transitionTargetInputReady;
    private boolean activityClosePending;
    private boolean activityColdResetRequired;
    private boolean activityColdResetInFlight;
    private boolean activityColdResetFailed;
    private int activeReverseControllerRequestId;
    private boolean updateDownloadInFlight;
    private final UpdateHintRuntime.DownloadListener updateDownloadListener =
            this::onUpdateDownloadChanged;
    private enum RuntimeDialogOwner { NONE, BACKGROUND, LOGS, COMPATIBILITY, UPDATE, WEATHER }
    private RuntimeDialogOwner runtimeDialogOwner = RuntimeDialogOwner.NONE;
    private Runnable runtimeDialogConfirm;
    private Runnable runtimeDialogDismiss;
    private final Runnable finishCameraHandoff = this::openPendingStockAvm;
    private final Runnable productionPreviewFirstFrameTimeout =
            this::handleProductionPreviewFirstFrameTimeout;
    private final Runnable copyCalibrationFrame = this::copyCalibrationFrame;
    private final Runnable copyReverseCalibrationFrame = this::copyReverseCalibrationFrame;
    private final Runnable startBackgroundStartSettings = () -> {
        backgroundStartSettingsStartScheduled = false;
        if (shouldOpenBackgroundStartSettings(
                GuardRecovery.isAutoStartEnabled(this), cameraPermissionPending,
                hasWindowFocus(), backgroundStartSettingsRequired,
                backgroundStartSettingsActive, adbAuthPending)) {
            showInstallationReminder();
        }
    };
    private final Runnable startForegroundAdbAuthorization = () -> {
        adbAuthorizationStartScheduled = false;
        if (shouldStartForegroundAdbAuthorization(cameraPermissionPending,
                backgroundStartSettingsPending(), hasWindowFocus(),
                foregroundAdbAuthorizationReady(), adbAuthPending,
                adbAuthorizationRequested
                        || startupOverlayPermissionFlow.foregroundAuthorizationStarted())) {
            requestAdbAuthorization("adb_authorization_foreground_start",
                    "foreground_adb_authorization", true);
        } else {
            // A skipped delayed authorization must still release the import-offer gate.
            // Otherwise only a later focus/lifecycle callback resumes onboarding.
            advanceStartupAuthorizationFlow();
        }
    };
    private final Runnable startPendingWeatherLocationPermission = () -> {
        weatherLocationPermissionStartScheduled = false;
        if (!weatherLocationPermissionPending || weatherLocationPermissionInFlight
                || cameraPermissionPending || !activityResumed || !hasWindowFocus()
                || !runtimePermissionWindowAvailable()
                || settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress || adbAuthPending
                || productionUi != null && productionUi.getState().getDialog() != null) {
            advanceStartupAuthorizationFlow();
            return;
        }
        if (hasLocationPermission()) {
            if (hasBackgroundLocationPermission()) {
                weatherLocationPermissionPending = false;
                advanceStartupAuthorizationFlow();
            } else {
                showWeatherBackgroundPermission();
            }
            return;
        }
        weatherEnableRequestedForPermission = true;
        if (!requestLocationPermission(false, false)) {
            weatherLocationPermissionPending = false;
            advanceStartupAuthorizationFlow();
        }
    };

    private final Binder callback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == CameraHelperMain.CB_EVENT) {
                data.enforceInterface(CameraHelperMain.CALLBACK_DESCRIPTOR);
                acceptHelperEvent(data.readString());
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private final ServiceConnection helperConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            helperBound = true;
            attachHelper(service);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            if (helperBound) {
                unbindService(this);
                helperBound = false;
            }
            record("helper_service_blocked", "reason", "legacy_handover");
            legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(CameraProbeActivity.this);
            updateControls();
            advanceStartupAuthorizationFlow();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            IBinder disconnected = helper;
            helper = null;
            helperCallbackRegistration.disconnected(disconnected);
            reconcileForegroundAdbAuthorization();
            telemetryReady = false;
            manualGearPark = false;
            manualTurnRequestPending = false;
            resetAvasPlayback();
            cameraDiscovered = false;
            requestedOpen = false;
            if (!shutdownRequested && isAutoPreviewTab(selectedTab)) {
                armResumeAutoPreview();
            }
            cameraTransition.cancel();
            cameraShellRecoveryPending = false;
            cameraShellAvailable = false;
            activityCameraShellEpoch = 0;
            activityCameraShellLastDeathEpoch = 0;
            activityCameraShellDeathHandled = false;
            activityAvmShellEpoch = 0;
            pendingCameraViewpoint = -1;
            pendingCameraDebug = false;
            retryStockViewpoint = -1;
            retryStockDebug = false;
            String serviceStopped = runtimeText(R.string.runtime_status_service_stopped);
            publishGuardStatus(serviceStopped, StatusTone.Error);
            publishSettingsFeedback(serviceStopped, StatusTone.Error);
            publishAdbOperation(false);
            if (productionUi != null) {
                StatusUiState unavailable = new StatusUiState(
                        serviceStopped, StatusTone.Error, true);
                productionUi.setDiagnosticStatus(true, unavailable, false);
                productionUi.setDiagnosticStatus(false, unavailable, false);
                productionUi.setReversePanoramaStatus(
                        new StatusUiState("", StatusTone.Neutral, false), false);
                productionUi.refreshAvasState();
            }
            stopCalibrationCopies(true);
            clearPreview("helper_service_disconnected");
            activePreview = null;
            activeActivityCameraProfile = null;
            activePreviewCover = null;
            activeCameraViewpoint = -1;
            activeDirectCameraIndex = -1;
            activeActivityCameraRequestId = 0;
            activeActivityConsumerGeneration = 0;
            activeActivityInputGenerations = new int[0];
            activeActivityCameraOpened = false;
            activeActivityCameraFresh = false;
            clearActivityCameraCloseIdentity();
            activityClosePending = false;
            activityColdResetInFlight = false;
            activeReverseControllerRequestId = 0;
            record("helper_service_disconnected");
            updateControls();
            advanceStartupAuthorizationFlow();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            updateCheckDialogRequested = savedInstanceState.getBoolean(
                    STATE_UPDATE_CHECK_DIALOG_REQUESTED);
            updateCheckDialogResult = savedInstanceState.getInt(
                    STATE_UPDATE_CHECK_DIALOG_RESULT, UPDATE_CHECK_RESULT_NONE);
            startupOverlayPermissionFlow.restore(
                    savedInstanceState.getBoolean(STATE_STARTUP_OVERLAY_PERMISSION_ATTEMPTED),
                    savedInstanceState.getBoolean(STATE_STARTUP_OVERLAY_SETTINGS_IN_FLIGHT));
        }
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        if (savedInstanceState != null) {
            cameraPermissionRequested = savedInstanceState.getBoolean(STATE_CAMERA_PERMISSION_REQUESTED);
            microphonePermissionPending = savedInstanceState.getBoolean(STATE_MICROPHONE_PERMISSION_PENDING);
            String[] pendingProfiles = savedInstanceState.getStringArray(STATE_BLUETOOTH_PROFILES);
            if (pendingProfiles != null) {
                for (CallsSettings.Profile profile : CallsSettings.Profile.values()) {
                    for (String pending : pendingProfiles) {
                        if (profile.name().equals(pending)) pendingBluetoothProfiles.add(profile);
                    }
                }
            }
            bluetoothPermissionInFlight = savedInstanceState.getBoolean(STATE_BLUETOOTH_PERMISSION_IN_FLIGHT);
            pendingManualAdbEvent = savedInstanceState.getString(STATE_MANUAL_ADB_EVENT);
            pendingManualAdbOperation = savedInstanceState.getString(STATE_MANUAL_ADB_OPERATION);
        }
        avasLibrary = new AvasAudioLibrary(this, preferences);
        try {
            avasLibrary.initializeBuiltinConfig();
        } catch (RuntimeException error) {
            record("avas_builtin_config_failed", "error", error.toString());
            avasLibrary = null;
        }
        resetAvasPlayback();
        if (avasLibrary != null) avasImportExecutor.execute(() -> {
            try {
                avasLibrary.ensureBuiltins();
                mainHandler.post(() -> {
                    if (!activityDestroyed && productionUi != null) productionUi.refreshAvasState();
                });
            } catch (RuntimeException error) {
                record("avas_builtin_prepare_failed", "error", error.toString());
            }
        });
        // Picker state survives recreation. A decoder is process-local, so a persisted decoding
        // marker without the process-local counterpart is an orphan left by process death.
        String persistedImportProfile = preferences.getString(PREF_AVAS_IMPORT_PROFILE, null);
        String persistedImportPhase = preferences.getString(PREF_AVAS_IMPORT_PHASE, null);
        if (shouldClearAvasImportMarker(savedInstanceState != null, persistedImportPhase,
                persistedImportProfile, avasImportInFlightProfile)) {
            clearAvasImportMarker();
        }
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        migrateCameraFrameAspects();
        installationReminder = new InstallationReminder(this);
        backgroundStartSettingsRequired = installationReminder.pending();
        createLogFile();
        activityLog = new AsyncServiceLog(() -> logFile, 250L);
        boolean clearedShutdown = GuardRecovery.isUserShutdownActive(this);
        productionUi = new ProductionUiController(preferences, this, this);
        restoreArchiveDocumentState(savedInstanceState);
        restorePresetDocumentState(savedInstanceState);
        if (backgroundStartSettingsRequired) productionUi.selectInstallationSettings();
        selectedTab = rootTabToLegacy(productionUi.getState().getActiveTab());
        ProductionUiInstaller.install(this, productionUi);
        // Restore the persisted camera tab once its Compose mapping is known.  The intent is
        // transient and deliberately not armed for an explicit Shutdown flow.
        if (!shutdownRequested && isAutoPreviewTab(selectedTab)) armResumeAutoPreview();
        verifyMappings();
        acceptDiagnosticIntent(getIntent());
        acceptUpdateHintIntent(getIntent());

        record("activity_start", "log_path", logFile.getAbsolutePath(),
                "shutdown_cleared", clearedShutdown,
                "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "background_start_settings_required", backgroundStartSettingsRequired);
        restoreWeatherPermissionState(savedInstanceState);
        initializeStartupWeatherPermissionState();
        startAndBindHelperService();
        updateControls();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_CAMERA_PERMISSION_REQUESTED, cameraPermissionRequested);
        outState.putBoolean(STATE_MICROPHONE_PERMISSION_PENDING,
                microphonePermissionPending || microphonePermissionInFlight);
        outState.putStringArray(STATE_BLUETOOTH_PROFILES, pendingBluetoothProfiles.stream()
                .map(Enum::name).toArray(String[]::new));
        outState.putBoolean(STATE_BLUETOOTH_PERMISSION_IN_FLIGHT, bluetoothPermissionInFlight);
        outState.putString(STATE_MANUAL_ADB_EVENT, pendingManualAdbEvent);
        outState.putString(STATE_MANUAL_ADB_OPERATION, pendingManualAdbOperation);
        outState.putBoolean(STATE_WEATHER_PERMISSION_IN_FLIGHT,
                weatherLocationPermissionInFlight);
        outState.putBoolean(STATE_WEATHER_PERMISSION_PENDING,
                weatherLocationPermissionPending);
        outState.putBoolean(STATE_WEATHER_ENABLE_REQUESTED,
                weatherEnableRequestedForPermission);
        outState.putBoolean(STATE_WEATHER_REFRESH_AFTER_PERMISSION,
                weatherRefreshAfterPermission);
        outState.putBoolean(STATE_WEATHER_BACKGROUND_SETTINGS, weatherBackgroundSettingsOpened);
        outState.putBoolean(STATE_STARTUP_OVERLAY_PERMISSION_ATTEMPTED,
                startupOverlayPermissionFlow.overlayPermissionAttempted());
        outState.putBoolean(STATE_STARTUP_OVERLAY_SETTINGS_IN_FLIGHT,
                startupOverlayPermissionFlow.overlaySettingsInFlight());
        outState.putBoolean(STATE_UPDATE_CHECK_DIALOG_REQUESTED, updateCheckDialogRequested);
        outState.putInt(STATE_UPDATE_CHECK_DIALOG_RESULT, updateCheckDialogResult);
        outState.putString(STATE_AVAS_IMPORT_PROFILE,
                preferences.getString(PREF_AVAS_IMPORT_PROFILE, null));
        if (pendingArchiveDocument != null) {
            outState.putString(STATE_ARCHIVE_SAVE_NAME, pendingArchiveDocument.getName());
            outState.putBoolean(STATE_ARCHIVE_SAVE_COMPATIBILITY, pendingArchiveCompatibility);
        }
        if (pendingPresetDocument != null) {
            outState.putString(STATE_PRESET_SAVE_NAME, pendingPresetDocument.getName());
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptDiagnosticIntent(intent);
        acceptUpdateHintIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        UpdateHintRuntime updateRuntime = UpdateHintRuntime.get(this);
        updateRuntime.setCheckListener(updateCheckListener);
        updateRuntime.setDownloadListener(updateDownloadListener);
        updateCheckInFlight = updateRuntime.isChecking();
        if (updateCheckInFlight) showUpdateCheckProgress();
        publishReverseOwnerPresence();
        LocalAdbClient.setAccessStateListener(adbAccessListener);
        LocalAdbClient.AUTHORIZATION.addForegroundListener(foregroundAdbListener);
        reconcileForegroundAdbAuthorization();
        CameraHelperService.addAdbRecoveryListener(adbRecoveryListener);
        preferences.registerOnSharedPreferenceChangeListener(permissionsPreferenceListener);
        WeatherRefreshAccessibilityService.addConnectionListener(accessibilityConnectionListener);
        refreshProductionHeader();
        invalidStockSurfaceRetryUsed = false;
        productionUi.refreshAdbRecoveryState();
        CameraHelperService.activityOpened(this);
        if (!helperBound) startAndBindHelperService();
        enqueueHelperCallbackRegistration(helperCallbackRegistration.start());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LocalAdbClient.AUTHORIZATION.rearmUnsentAutomaticRequest()) {
            reconcileForegroundAdbAuthorization();
        }
        finishOverlaySettingsReturn();
        if (updateInstallerOpened) {
            updateInstallerOpened = false;
            updateInstallRequested = false;
        }
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        updateControls();
        if (productionUi != null) productionUi.reload();
        refreshProductionHeader();
        if (!helperBound) startAndBindHelperService();
        activityResumed = true;
        if (weatherBackgroundSettingsOpened) finishWeatherBackgroundPermission();
        showWeatherPermissionStatus();
        openPendingMotionPreviews();
        enqueueHelperCallbackRegistration(helperCallbackRegistration.start());
        publishReverseOwnerPresence();
        ensureAutomaticPreviewInputs();
        if (!resumeTabWarmup.required()
                && !activityCameraBufferRefreshPending
                && activePreview == reverseCameraPreview
                && requestedOpen
                && activeActivityCameraOpened
                && activeActivityCameraFresh) {
            armReverseCalibrationFreshness();
        }
        if (hasAutoPreviewIntent()) {
            record("activity_camera_reopen", "reason", "activity_resumed",
                    "selected_tab", selectedTab,
                    "request_id", automaticPreviewIntentRequestId);
            resumeSelectedCameraPreview();
        }
        resumeActivityCameraAfterShellRecovery("activity_resumed", 0);
        showDiagnosticExportProgress();
        restoreManualUpdateCheckDialog();
        showCachedUpdateIfAvailable();
        if (backgroundStartSettingsActive) {
            backgroundStartSettingsActive = false;
            record("background_start_settings_returned");
        }
        advanceStartupAuthorizationFlow();
    }

    @Override
    protected void onPause() {
        if (!shutdownRequested) {
            armResumeAutoPreviewIfNeeded();
        }
        activityResumed = false;
        closeMotionCompositionPreviews("activity_paused", false);
        reverseSteeringSample = ReverseSteeringSample.UNKNOWN;
        applyReverseSteering();
        publishReverseOwnerIneligible();
        cancelReverseButtonLearningIfVisible();
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(true);
        cancelPendingBackgroundStartSettings();
        cancelPendingWeatherLocationPermission();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            finishOverlaySettingsReturn();
            advanceStartupAuthorizationFlow();
            openPendingMotionPreviews();
        } else {
            cancelPendingBackgroundStartSettings();
            cancelPendingForegroundAdbAuthorization();
            cancelPendingWeatherLocationPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            finishBluetoothPermissionRequest();
        } else if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            microphonePermissionInFlight = false;
            microphonePermissionPending = false;
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            if (granted && !shutdownRequested)
                preferences.edit().putBoolean(AvasMicrophoneSettings.ENABLED, true).apply();
            if (!granted) Toast.makeText(this, runtimeText(R.string.avas_mic_permission),
                    Toast.LENGTH_LONG).show();
            if (productionUi != null) productionUi.refreshAvasState();
            refreshProductionHeader();
            advanceStartupAuthorizationFlow();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            cameraPermissionPending = false;
            refreshProductionHeader();
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            record("camera_permission", "granted", granted);
            if (!granted) publishSettingsFeedback(
                    runtimeText(R.string.runtime_camera_permission_missing), StatusTone.Error);
            advanceStartupAuthorizationFlow();
            updateControls();
            maybeOpenProductionPreview();
            maybeOpenCalibrationCamera();
            maybeOpenReversePreview();
            openPendingMotionPreviews();
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST) {
            finishWeatherBackgroundPermission();
            advanceStartupAuthorizationFlow();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            weatherLocationPermissionInFlight = false;
            boolean granted = false;
            for (int result : results) {
                if (result == PackageManager.PERMISSION_GRANTED) {
                    granted = true;
                    break;
                }
            }
            record("weather_location_permission", "granted", granted);
            boolean enableRequested = weatherEnableRequestedForPermission;
            weatherEnableRequestedForPermission = false;
            weatherLocationPermissionPending = false;
            preferences.edit()
                    .remove(PREF_WEATHER_PERMISSION_REQUEST_PENDING)
                    .putBoolean(PREF_WEATHER_PERMISSION_MIGRATION_SEEN, true)
                    .apply();
            boolean weatherStillEnabled = preferences.getBoolean(
                    WeatherRuntime.PREF_ENABLED, false);
            if (!granted) {
                weatherRefreshAfterPermission = false;
            } else if (shouldEnableWeatherAfterPermission(granted, enableRequested)) {
                preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).apply();
            }
            CameraHelperService.weatherSettingsChanged(this);
            if (productionUi != null) productionUi.reload();
            if (!granted) {
                Toast.makeText(this, runtimeText(R.string.runtime_weather_location_permission_needed),
                        Toast.LENGTH_LONG).show();
            } else if (shouldRefreshWeatherAfterPermission(
                    granted, weatherRefreshAfterPermission, weatherStillEnabled)) {
                weatherRefreshAfterPermission = false;
                requestWeatherRefresh();
            } else {
                weatherRefreshAfterPermission = false;
            }
            if (granted) queueWeatherBackgroundPermission(false);
            showWeatherPermissionStatus();
            refreshProductionHeader();
            advanceStartupAuthorizationFlow();
            updateControls();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        UpdateHintRuntime.get(this).removeDownloadListener(updateDownloadListener);
        stopAvasListAudition(avasAudition.getSessionId());
        preferences.unregisterOnSharedPreferenceChangeListener(permissionsPreferenceListener);
        CameraHelperService.removeAdbRecoveryListener(adbRecoveryListener);
        WeatherRefreshAccessibilityService.removeConnectionListener(accessibilityConnectionListener);
        clearReverseOwnerPresence();
        LocalAdbClient.clearAccessStateListener(adbAccessListener);
        LocalAdbClient.AUTHORIZATION.removeForegroundListener(foregroundAdbListener);
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(true);
        if (!shutdownRequested) {
            armResumeAutoPreviewIfNeeded();
            resumeTabWarmup.stopped();
        } else clearResumeAutoPreview();
        retryStockViewpoint = -1;
        retryStockDebug = false;
        cameraShellRecoveryPending = false;
        if (!shutdownRequested) {
            closeMotionCompositionPreviews("activity_stopped", false);
            if (shouldIssueActivityStoppedClose(cameraTransition.pending())) {
                activityClosePending = activityClosePending
                        || closeCamera("activity_stopped");
            }
            CameraHelperService.activityClosed(this);
        }
        // Keep the callback until onDestroy: stock-shell close completion and
        // reverse state can arrive after the Activity becomes non-visible.
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        stopAvasListAudition(avasAudition.getSessionId());
        cancelReverseButtonLearningIfVisible();
        clearReverseOwnerPresence();
        activityDestroyed = true;
        LocalAdbClient.AUTHORIZATION.removeForegroundListener(foregroundAdbListener);
        mainHandler.removeCallbacks(reverseSteeringExpired);
        UpdateHintRuntime.get(this).removeCheckListener(updateCheckListener);
        UpdateHintRuntime.get(this).removeDownloadListener(updateDownloadListener);
        LocalAdbClient.clearAccessStateListener(adbAccessListener);
        cancelPendingWeatherLocationPermission();
        CompatibilityBundleExporter.ExportControl exportControl =
                compatibilityExportControl;
        if (exportControl != null) exportControl.cancel();
        if (diagnosticExportControl != null) diagnosticExportControl.cancel();
        clearRuntimeDialog();
        resumeTabWarmup.clear();
        if (requestedOpen || cameraHandoffPending) closeCamera("activity_destroyed");
        clearActivityCameraCloseIdentity();
        activityClosePending = false;
        releaseAllProductionCameraHosts();
        clearResumeAutoPreview();
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(true);
        detachHelperCallback();
        if (helperBound) {
            unbindService(helperConnection);
            helperBound = false;
        }
        ipcExecutor.shutdown();
        logExportExecutor.shutdownNow();
        avasImportExecutor.shutdown();
        if (activityLog != null) activityLog.close();
        if (legacyImportOfferDialog != null) legacyImportOfferDialog.dismiss();
        legacyImportOfferDialog = null;
        dismissSettingsTransferDialog();
        super.onDestroy();
    }

    private void ensureAutomaticPreviewInputs() {
        if (cameraPreview != null) cameraPreview.ensureCameraInput();
        if (calibrationPreview != null) calibrationPreview.ensureCameraInput();
        if (reverseCameraPreview != null) reverseCameraPreview.ensurePreviewInputs();
    }

    private void retireAutomaticPreviewInputs() {
        productionPreviewFreshness.clear();
        calibrationPreviewFreshness.clear();
        reversePreviewFreshness.clear();
        stopReverseCalibrationCopies(true);
        if (cameraPreview != null) cameraPreview.retireCameraInput();
        if (calibrationPreview != null) calibrationPreview.retireCameraInput();
        if (reverseCameraPreview != null) reverseCameraPreview.retirePreviewInputs();
        cameraSurfaceReady = false;
        calibrationSurfaceReady = false;
        reverseCameraSurfacesReady = false;
        activeActivityInputGenerations = new int[0];
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
    }

    void runManualUpdateCheck() {
        runUpdateCheck(true);
    }

    private boolean beginSettingsTransfer(SettingsOperation operation) {
        if (settingsTransferInProgress || settingsReloadPending || logExportInProgress
                || compatibilityExportInProgress || shutdownRequested || activityDestroyed
                || isFinishing()) return false;
        settingsTransferInProgress = true;
        activeSettingsOperation = operation;
        recordOperationFeedback(runtimeText(R.string.runtime_status_operation_started));
        publishSettingsOperation(operation, runtimeText(R.string.runtime_status_operation_started), StatusTone.Warning, true);
        cancelPendingForegroundAdbAuthorization();
        cancelPendingBackgroundStartSettings();
        updateControls();
        return true;
    }

    private void showSettingsTransferProgress(String message) {
        dismissSettingsTransferDialog();
        recordOperationFeedback(message);
        settingsTransferDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_settings_transfer))
                .setMessage(message)
                .setCancelable(false)
                .create();
        settingsTransferDialog.show();
    }

    private void dismissSettingsTransferDialog() {
        if (settingsTransferDialog != null) settingsTransferDialog.dismiss();
        settingsTransferDialog = null;
    }

    private void finishSettingsTransfer() {
        settingsTransferInProgress = false;
        SettingsOperation operation = activeSettingsOperation;
        activeSettingsOperation = null;
        dismissSettingsTransferDialog();
        if (operation != null) publishSettingsOperation(
                operation, runtimeText(R.string.runtime_status_operation_complete), StatusTone.Ok, false);
        if (!activityDestroyed) {
            updateControls();
        }
    }

    private void reportSettingsTransferFailure(Throwable error) {
        reportSettingsTransferFailure(error, false);
    }

    private void reportSettingsTransferFailure(Throwable error, boolean presetImport) {
        record("settings_transfer_failed", "error", error.toString());
        SettingsOperation operation = activeSettingsOperation;
        finishSettingsTransfer();
        if (activityDestroyed || isFinishing()) return;
        String detail = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        String failure = runtimeText(R.string.runtime_settings_transfer_failed_detail, detail);
        recordOperationFeedback(failure);
        if (operation != null) publishSettingsOperation(
                operation, failure, StatusTone.Error, false);
        if (presetImport) {
            showSettingsResultToast(runtimeText(R.string.runtime_preset_load_failed));
            if (settingsReloadPending) recreate();
            return;
        }
        settingsTransferDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_settings_transfer_failed))
                .setMessage(detail)
                .setCancelable(!settingsReloadPending)
                .setPositiveButton(runtimeText(R.string.runtime_ok), (dialog, which) -> {
                    if (settingsReloadPending) recreate();
                })
                .create();
        settingsTransferDialog.show();
    }

    private void exportCameraPreset(boolean saveAs) {
        if (!beginSettingsTransfer(SettingsOperation.Preset)) return;
        showSettingsTransferProgress(runtimeText(R.string.runtime_preset_export_progress));
        logExportExecutor.execute(() -> {
            try {
                File file = CameraPresetFiles.write(getCacheDir(),
                        CameraSettingsTransfer.exportCameraPreset(this, preferences));
                mainHandler.post(() -> {
                    if (saveAs) {
                        dismissSettingsTransferDialog();
                        if (activityDestroyed || isFinishing() || !activityResumed) {
                            file.delete();
                            finishSettingsTransfer();
                            return;
                        }
                        choosePresetDocument(file);
                        return;
                    }
                    finishSettingsTransfer();
                    if (activityDestroyed || isFinishing()) return;
                    recordOperationFeedback(runtimeText(R.string.runtime_preset_ready));
                    if (!activityResumed) return;
                    try {
                        Uri uri = FileProvider.getUriForFile(
                                this, getPackageName() + ".fileprovider", file);
                        Intent share = new Intent(Intent.ACTION_SEND)
                                .setType("application/json")
                                .putExtra(Intent.EXTRA_STREAM, uri)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        share.setClipData(ClipData.newUri(
                                getContentResolver(), "BYD Extend camera preset", uri));
                        startActivity(Intent.createChooser(share,
                                runtimeText(R.string.runtime_preset_export_title)));
                        record("camera_preset_export", "bytes", file.length());
                    } catch (Exception error) {
                        reportSettingsTransferFailure(error);
                    }
                });
            } catch (Exception error) {
                mainHandler.post(() -> reportSettingsTransferFailure(error));
            }
        });
    }

    private void restorePresetDocumentState(Bundle state) {
        if (state == null || !state.containsKey(STATE_PRESET_SAVE_NAME)) return;
        pendingPresetDocument = CameraPresetFiles.restore(getCacheDir(),
                state.getString(STATE_PRESET_SAVE_NAME));
        activeSettingsOperation = SettingsOperation.Preset;
        settingsTransferInProgress = pendingPresetDocument != null;
        if (pendingPresetDocument == null) {
            finishPresetDocument(R.string.runtime_preset_document_failed, StatusTone.Error);
            return;
        }
        publishSettingsOperation(SettingsOperation.Preset,
                runtimeText(R.string.runtime_preset_choose_destination), StatusTone.Warning, true, true);
    }

    @SuppressWarnings("deprecation")
    private void choosePresetDocument(File file) {
        pendingPresetDocument = file;
        publishSettingsOperation(SettingsOperation.Preset,
                runtimeText(R.string.runtime_preset_choose_destination), StatusTone.Warning, true);
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, file.getName()), CAMERA_PRESET_SAVE_REQUEST);
        } catch (RuntimeException error) {
            pendingPresetDocument = null;
            file.delete();
            record("camera_preset_save", "state", "picker_failed", "error", error.toString());
            finishPresetDocument(R.string.runtime_archive_picker_failed, StatusTone.Error);
        }
    }

    private void acceptPresetDocument(int resultCode, Intent data) {
        final File file = pendingPresetDocument;
        pendingPresetDocument = null;
        if (file == null) {
            finishPresetDocument(R.string.runtime_preset_document_failed, StatusTone.Error);
            return;
        }
        final Uri destination = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (destination == null) {
            file.delete();
            finishPresetDocument(R.string.runtime_preset_save_canceled, StatusTone.Warning);
            return;
        }
        publishSettingsOperation(SettingsOperation.Preset,
                runtimeText(R.string.runtime_preset_saving), StatusTone.Warning, true);
        final Context appContext = getApplicationContext();
        try {
            logExportExecutor.execute(() -> {
                Throwable failure = null;
                try (OutputStream output = appContext.getContentResolver().openOutputStream(destination, "w")) {
                    ArchiveDocumentWriter.copy(file, output);
                } catch (Exception error) {
                    failure = error;
                    try {
                        android.provider.DocumentsContract.deleteDocument(appContext.getContentResolver(), destination);
                    } catch (Exception ignored) { /* The provider may retain an incomplete new document. */ }
                }
                final long bytes = file.length();
                file.delete();
                final Throwable error = failure;
                mainHandler.post(() -> {
                    record("camera_preset_save", "state", error == null ? "saved" : "failed",
                            "bytes", bytes, "error", error == null ? "" : error.toString());
                    finishPresetDocument(error == null ? R.string.runtime_preset_document_saved
                            : R.string.runtime_preset_document_failed, error == null ? StatusTone.Ok : StatusTone.Error);
                });
            });
        } catch (RuntimeException error) {
            file.delete();
            record("camera_preset_save", "state", "start_failed", "error", error.toString());
            finishPresetDocument(R.string.runtime_preset_document_failed, StatusTone.Error);
        }
    }

    private void finishPresetDocument(int message, StatusTone tone) {
        finishSettingsTransfer();
        if (activityDestroyed || isFinishing()) return;
        publishSettingsOperation(SettingsOperation.Preset, runtimeText(message), tone, false);
        recordOperationFeedback(runtimeText(message));
        showSettingsResultToast(runtimeText(message));
    }

    @SuppressWarnings("deprecation")
    private void chooseCameraPreset() {
        if (!beginSettingsTransfer(SettingsOperation.Preset)) return;
        recordOperationFeedback(runtimeText(R.string.runtime_preset_choose));
        try {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(open, CAMERA_PRESET_REQUEST);
        } catch (Exception error) {
            reportSettingsTransferFailure(error, true);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST) {
            finishBluetoothPermissionRequest();
            return;
        }
        if (requestCode == CAMERA_PRESET_SAVE_REQUEST) {
            acceptPresetDocument(resultCode, data);
            return;
        }
        if (requestCode == ARCHIVE_SAVE_REQUEST) {
            acceptArchiveDocument(resultCode, data);
            return;
        }
        if (requestCode == AVAS_AUDIO_REQUEST) {
            acceptAvasAudioResult(resultCode, data);
            return;
        }
        if (requestCode != CAMERA_PRESET_REQUEST) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishSettingsTransfer();
            String canceled = runtimeText(R.string.runtime_preset_load_canceled);
            recordOperationFeedback(canceled);
            return;
        }
        settingsTransferInProgress = true;
        readSettingsTransfer(false, data.getData());
    }

    private void readLegacySettings() {
        readLegacySettings(false);
    }

    private void readLegacySettings(boolean alreadyConfirmed) {
        if (!beginSettingsTransfer(SettingsOperation.Import)) return;
        readSettingsTransfer(true, null, alreadyConfirmed);
    }

    private void confirmLegacyAccessRestore() {
        if (settingsTransferInProgress || settingsReloadPending || logExportInProgress
                || compatibilityExportInProgress || shutdownRequested || activityDestroyed
                || !LegacySettingsImporter.needsAccessRestore(this)) return;
        new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_legacy_restore_title))
                .setMessage(runtimeText(R.string.runtime_legacy_restore_message))
                .setNegativeButton(runtimeText(R.string.runtime_cancel), null)
                .setPositiveButton(runtimeText(R.string.runtime_restore_access),
                        (dialog, which) -> restoreLegacyAccess())
                .show();
    }

    private void restoreLegacyAccess() {
        if (!beginSettingsTransfer(SettingsOperation.Import)) return;
        showSettingsTransferProgress(runtimeText(R.string.runtime_legacy_restore_progress));
        logExportExecutor.execute(() -> {
            boolean restored = false;
            Throwable failure = null;
            try {
                restored = LegacySettingsImporter.restoreLegacyAccess(
                        getApplicationContext(), this::record);
            } catch (Throwable error) {
                failure = error;
            }
            boolean result = restored;
            Throwable error = failure;
            mainHandler.post(() -> finishLegacyAccessRestore(result, error));
        });
    }

    private void finishLegacyAccessRestore(boolean restored, Throwable error) {
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        finishSettingsTransfer();
        if (activityDestroyed || isFinishing()) return;
        if (error != null || !restored) {
            String status = runtimeText(R.string.runtime_legacy_restore_failed);
            if (error != null) {
                String detail = error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage();
                status = runtimeText(R.string.runtime_legacy_restore_failed_detail, detail);
            }
            recordOperationFeedback(status);
            return;
        }
        try {
            CameraHelperService.settingsReloaded(getApplicationContext(), false);
        } catch (Throwable reloadError) {
            record("legacy_access_restore_reload_failed", "error", reloadError.toString());
        }
        recordOperationFeedback(runtimeText(R.string.runtime_legacy_restore_success));
        recreate();
    }

    private void readSettingsTransfer(boolean legacy, Uri uri) {
        readSettingsTransfer(legacy, uri, false);
    }

    private void readSettingsTransfer(boolean legacy, Uri uri, boolean legacyAlreadyConfirmed) {
        showSettingsTransferProgress(legacy
                ? runtimeText(R.string.runtime_legacy_reading)
                : runtimeText(R.string.runtime_preset_checking));
        logExportExecutor.execute(() -> {
            try {
                Map<String, Object> values;
                if (legacy) {
                    values = LegacySettingsImporter.readSettings(
                            getApplicationContext(), this::record);
                } else {
                    try (InputStream input = getContentResolver().openInputStream(uri)) {
                        values = CameraSettingsTransfer.parseCameraPreset(CameraPresetFiles.read(input));
                    }
                }
                mainHandler.post(() -> {
                    if (legacy && legacyAlreadyConfirmed) applySettingsTransfer(true, values);
                    else confirmSettingsTransfer(legacy, values);
                });
            } catch (Exception error) {
                mainHandler.post(() -> reportSettingsTransferFailure(error, !legacy));
            }
        });
    }

    private void confirmSettingsTransfer(boolean legacy, Map<String, Object> values) {
        dismissSettingsTransferDialog();
        if (activityDestroyed || isFinishing()) return;
        settingsTransferDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(legacy ? R.string.runtime_import_settings_title
                        : R.string.runtime_import_preset_title))
                .setMessage(runtimeText(legacy ? R.string.runtime_import_settings_message
                        : R.string.runtime_import_preset_message))
                .setNegativeButton(runtimeText(R.string.runtime_cancel),
                        (dialog, which) -> finishSettingsTransfer())
                .setOnCancelListener(dialog -> finishSettingsTransfer())
                .setPositiveButton(runtimeText(legacy ? R.string.runtime_import_and_stop
                        : R.string.runtime_import),
                        (dialog, which) ->
                        applySettingsTransfer(legacy, values))
                .create();
        settingsTransferDialog.show();
    }

    private void applySettingsTransfer(boolean legacy, Map<String, Object> values) {
        if (!legacy && runtimeBlockedByLegacy()) {
            finishSettingsTransfer();
            productionUi.setLegacyRuntimeBlocked(true);
            return;
        }
        settingsReloadPending = true;
        showSettingsTransferProgress(legacy
                ? runtimeText(R.string.runtime_save_settings)
                : runtimeText(R.string.runtime_apply_preset));
        logExportExecutor.execute(() -> {
            try {
                if (legacy) {
                    LegacySettingsImporter.applyAndHandover(
                            getApplicationContext(), values, this::record);
                } else {
                    CameraSettingsTransfer.applyCameraPreset(this, preferences, values);
                }
                if (legacy) {
                    // Full import is the only settings-transfer path that may schedule
                    // the package-local foreground location prompt after recreation.
                    preferences.edit()
                            .putBoolean(PREF_WEATHER_PERMISSION_REQUEST_PENDING, true)
                            .apply();
                }
                CameraHelperService.settingsReloaded(getApplicationContext(), legacy);
                mainHandler.post(() -> {
                    record("settings_transfer_applied", "legacy", legacy, "count", values.size());
                    finishSettingsTransfer();
                    if (activityDestroyed || isFinishing()) return;
                    recordOperationFeedback(runtimeText(legacy ? R.string.runtime_imported_settings
                            : R.string.runtime_preset_loaded));
                    if (!legacy) showSettingsResultToast(runtimeText(R.string.runtime_preset_loaded));
                    if (legacy) startupOverlayPermissionFlow.restore(false, false);
                    recreate();
                });
            } catch (Exception error) {
                mainHandler.post(() -> reportSettingsTransferFailure(error, !legacy));
            }
        });
    }

    private boolean showRuntimeDialog(RuntimeDialogOwner owner, DialogKind kind,
            String title, String message, boolean cancellable, String confirmLabel,
            String dismissLabel, String markdown, Runnable confirm, Runnable dismiss) {
        if (activityDestroyed || isFinishing() || shutdownRequested || !activityResumed
                || cameraPermissionPending || microphonePermissionInFlight || bluetoothPermissionInFlight
                || weatherLocationPermissionInFlight || adbAuthPending
                || settingsTransferDialog != null || legacyImportOfferDialog != null
                || productionUi == null || productionUi.getState().getDialog() != null) return false;
        runtimeDialogOwner = owner;
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        cancelPendingWeatherLocationPermission();
        runtimeDialogConfirm = confirm;
        runtimeDialogDismiss = dismiss;
        productionUi.showDialog(new DialogUiState(kind, title, message, null, cancellable, true,
                true, confirmLabel, dismissLabel, confirm != null,
                markdown == null ? "" : markdown,
                owner == RuntimeDialogOwner.UPDATE));
        return true;
    }

    private void clearRuntimeDialog() {
        runtimeDialogOwner = RuntimeDialogOwner.NONE;
        runtimeDialogConfirm = null;
        runtimeDialogDismiss = null;
        if (productionUi != null && productionUi.getState().getDialog() != null
                && productionUi.getState().getDialog().getManaged()) productionUi.showDialog(null);
    }

    private void updateRuntimeProgress(RuntimeDialogOwner owner, String message, boolean cancellable) {
        updateRuntimeProgress(owner, message, cancellable, null);
    }

    private void updateRuntimeProgress(RuntimeDialogOwner owner, String message,
            boolean cancellable, Float progress) {
        if (activityDestroyed || productionUi == null || runtimeDialogOwner != owner) return;
        DialogUiState current = productionUi.getState().getDialog();
        if (current == null || !current.getManaged() || current.getKind() != DialogKind.Progress) return;
        productionUi.showDialog(current.withRuntimeProgress(message, progress, cancellable,
                cancellable ? runtimeText(R.string.runtime_cancel) : null));
    }

    private boolean handleRuntimeDialogCommand(CommandId command) {
        if (productionUi == null || runtimeDialogOwner == RuntimeDialogOwner.NONE) return false;
        DialogUiState dialog = productionUi.getState().getDialog();
        if (dialog == null || !dialog.getManaged()) return false;
        boolean confirm = command == CommandId.ConfirmDialog;
        if (!confirm && command != CommandId.DismissDialog && command != CommandId.CancelOperation) {
            return false;
        }
        if (confirm && (!dialog.getConfirmVisible() || !dialog.getConfirmEnabled())
                || !confirm && !dialog.getCancellable() && dialog.getKind() != DialogKind.Background) {
            return true;
        }
        Runnable action = confirm ? runtimeDialogConfirm : runtimeDialogDismiss;
        boolean cancellingExport = !confirm && (runtimeDialogOwner == RuntimeDialogOwner.COMPATIBILITY
                || runtimeDialogOwner == RuntimeDialogOwner.LOGS)
                && dialog.getKind() == DialogKind.Progress;
        boolean updateAction = confirm && runtimeDialogOwner == RuntimeDialogOwner.UPDATE;
        if (!cancellingExport && !updateAction) clearRuntimeDialog();
        if (action != null) action.run();
        advanceStartupAuthorizationFlow();
        return true;
    }

    private void showInstallationReminder() {
        if (!backgroundStartSettingsRequired || backgroundStartSettingsActive
                || cameraPermissionPending || microphonePermissionInFlight || bluetoothPermissionInFlight
                || weatherLocationPermissionInFlight || adbAuthPending) return;
        UiStrings strings = new UiStrings(productionUi.getState().getLanguage(), this);
        showRuntimeDialog(RuntimeDialogOwner.BACKGROUND, DialogKind.Background,
                runtimeText(R.string.background_work),
                strings.text("Перевірте після встановлення або оновлення, інакше DiLink може зупинити BYD Extend у фоні.",
                        "Check after installing or updating; otherwise DiLink may stop BYD Extend in the background.",
                        "安装或更新后请检查此设置，否则 DiLink 可能会停止后台运行的 BYD Extend。"),
                true, strings.text("Відкрити", "Open", "打开"),
                strings.text("Зрозуміло", "Got it", "知道了"), "",
                () -> openBackgroundStartSettings("installation_reminder"),
                this::acknowledgeInstallationReminder);
    }

    private void acknowledgeInstallationReminder() {
        installationReminder.acknowledge();
        backgroundStartSettingsRequired = false;
        cancelPendingBackgroundStartSettings();
    }

    private void confirmDiagnosticLogShare() {
        confirmDiagnosticLogExport(false);
    }

    private void confirmDiagnosticLogExport(boolean saveAs) {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        showRuntimeDialog(RuntimeDialogOwner.LOGS, DialogKind.Message,
                runtimeText(saveAs ? R.string.runtime_archive_save_title : R.string.runtime_logs_share_title),
                runtimeText(R.string.runtime_logs_share_message), true,
                runtimeText(R.string.runtime_create), runtimeText(R.string.runtime_cancel), "",
                () -> startDiagnosticLogExport(saveAs), () -> {});
    }

    private void startDiagnosticLogExport(boolean saveAs) {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        logExportInProgress = true;
        exportArchiveToDocument = saveAs;
        diagnosticDocumentCopy = false;
        diagnosticExportProgress = null;
        diagnosticExportControl = newDiagnosticExportControl();
        showDiagnosticExportProgress();
        publishSettingsOperation(SettingsOperation.Logs,
                runtimeText(R.string.runtime_logs_progress), StatusTone.Warning, true, true);
        record("diagnostic_log_export", "state", "flush_requested");
        try {
            CameraHelperService.flushLogs(this, new ResultReceiver(mainHandler) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    exportDiagnosticLogsAfterFlush();
                }
            });
        } catch (Throwable error) {
            finishDiagnosticLogExport(null, error);
        }
    }

    private CompatibilityBundleExporter.ExportControl newDiagnosticExportControl() {
        final WeakReference<CameraProbeActivity> owner = new WeakReference<>(this);
        return new CompatibilityBundleExporter.ExportControl(progress -> {
            CameraProbeActivity target = owner.get();
            if (target == null || target.activityDestroyed) return;
            target.mainHandler.post(() -> target.updateDiagnosticExportProgress(progress));
        });
    }

    private void showDiagnosticExportProgress() {
        if (!logExportInProgress || diagnosticExportControl == null) return;
        if (runtimeDialogOwner == RuntimeDialogOwner.NONE) {
            showRuntimeDialog(RuntimeDialogOwner.LOGS, DialogKind.Progress,
                    runtimeText(exportArchiveToDocument ? R.string.runtime_archive_save_title : R.string.runtime_logs_share_title),
                    runtimeText(R.string.runtime_logs_progress), true, null,
                    runtimeText(R.string.runtime_cancel), "", null, this::cancelDiagnosticExport);
        }
        updateDiagnosticExportProgress(diagnosticExportProgress == null
                ? new CompatibilityBundleExporter.Progress(CompatibilityBundleExporter.Progress.Phase.PREPARING,
                    "", 0, 0, 0, -1) : diagnosticExportProgress);
    }

    private void cancelDiagnosticExport() {
        CompatibilityBundleExporter.ExportControl control = diagnosticExportControl;
        if (control == null) return;
        control.cancel();
        updateRuntimeProgress(RuntimeDialogOwner.LOGS,
                runtimeText(R.string.runtime_car_compat_canceling), false);
    }

    private void updateDiagnosticExportProgress(CompatibilityBundleExporter.Progress progress) {
        if (activityDestroyed || !logExportInProgress || diagnosticExportControl == null
                || diagnosticExportControl.isCancellationRequested()) return;
        diagnosticExportProgress = progress;
        if (productionUi == null || runtimeDialogOwner != RuntimeDialogOwner.LOGS) return;
        DialogUiState dialog = productionUi.getState().getDialog();
        if (dialog == null || dialog.getKind() != DialogKind.Progress) return;
        boolean finalizing = progress.phase == CompatibilityBundleExporter.Progress.Phase.ZIP
                || progress.phase == CompatibilityBundleExporter.Progress.Phase.COMPLETE;
        String message = runtimeText(finalizing ? R.string.runtime_archive_finalizing
                : diagnosticDocumentCopy ? R.string.runtime_archive_saving : R.string.runtime_logs_progress);
        if (!progress.currentPath.isEmpty()) message += "\n" + progress.currentPath;
        productionUi.showDialog(dialog.withArchiveProgress(message, progress.bytes, progress.totalBytes,
                finalizing, !finalizing, finalizing ? null : runtimeText(R.string.runtime_cancel)));
    }

    private void exportDiagnosticLogsAfterFlush() {
        if (!logExportInProgress) return;
        if (diagnosticExportControl != null && diagnosticExportControl.isCancellationRequested()) {
            finishDiagnosticLogExport(null, new CompatibilityBundleExporter.CancellationException());
            return;
        }
        if (activityDestroyed || shutdownRequested) {
            finishDiagnosticLogExport(null, null);
            return;
        }
        AsyncServiceLog log = activityLog;
        if (log != null) {
            log.flush(this::exportDiagnosticLogsAfterActivityFlush);
        } else exportDiagnosticLogsAfterActivityFlush();
    }

    private void exportDiagnosticLogsAfterActivityFlush() {
        final CompatibilityBundleExporter.ExportControl control = diagnosticExportControl;
        try {
            logExportExecutor.execute(() -> {
                File archive = null;
                Throwable failure = null;
                try {
                    DiagnosticLogExporter.Snapshot snapshot =
                            DiagnosticLogExporter.snapshot(this);
                    record("diagnostic_log_export", "state", "snapshot_ready",
                            "source_count", snapshot.sources.size());
                    archive = DiagnosticLogExporter.export(this, snapshot, control);
                } catch (Throwable error) {
                    failure = error;
                }
                File result = archive;
                Throwable error = failure;
                mainHandler.post(() -> finishDiagnosticLogExport(result, error));
            });
        } catch (Throwable error) {
            finishDiagnosticLogExport(null, error);
        }
    }

    private void finishDiagnosticLogExport(File archive, Throwable error) {
        boolean canceled = diagnosticExportControl != null
                && diagnosticExportControl.isCancellationRequested();
        diagnosticExportControl = null;
        diagnosticExportProgress = null;
        if (canceled) {
            if (archive != null) archive.delete();
            archive = null;
            error = new CompatibilityBundleExporter.CancellationException();
        }
        if (exportArchiveToDocument && archive != null && error == null
                && activityResumed && !activityDestroyed && !shutdownRequested) {
            chooseArchiveDocument(archive, false);
            return;
        }
        logExportInProgress = false;
        if (runtimeDialogOwner == RuntimeDialogOwner.LOGS) clearRuntimeDialog();
        if (!activityDestroyed) advanceStartupAuthorizationFlow();
        if (error != null) {
            record("diagnostic_log_export", "state", "failed", "error", error.toString());
            publishSettingsOperation(SettingsOperation.Logs,
                    runtimeText(canceled ? R.string.runtime_car_compat_canceled : R.string.runtime_logs_failed),
                    canceled ? StatusTone.Warning : StatusTone.Error, false);
            if (activityResumed && !activityDestroyed) {
                recordOperationFeedback(runtimeText(canceled
                        ? R.string.runtime_car_compat_canceled : R.string.runtime_logs_failed));
            }
            return;
        }
        if (archive == null) {
            publishSettingsOperation(SettingsOperation.Logs,
                    runtimeText(R.string.runtime_logs_empty), StatusTone.Warning, false);
            return;
        }
        if (!activityResumed || activityDestroyed) {
            boolean deleted = archive.delete();
            record("diagnostic_log_export", "state", "chooser_skipped",
                    "reason", "activity_inactive", "archive_deleted", deleted);
            publishSettingsOperation(SettingsOperation.Logs,
                    runtimeText(R.string.runtime_logs_inactive), StatusTone.Warning, false);
            return;
        }
        publishSettingsOperation(SettingsOperation.Logs,
                runtimeText(R.string.runtime_logs_ready), StatusTone.Ok, false);
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", archive);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newUri(
                    getContentResolver(), "BYD Extend logs", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            record("diagnostic_log_export", "state", "chooser_opened",
                    "archive", archive.getName(), "bytes", archive.length());
            startActivity(Intent.createChooser(share, runtimeText(R.string.runtime_logs_share_title)));
        } catch (Throwable shareError) {
            record("diagnostic_log_export", "state", "share_failed",
                    "error", shareError.toString());
            publishSettingsOperation(SettingsOperation.Logs,
                    runtimeText(R.string.runtime_share_menu_failed), StatusTone.Error, false);
            recordOperationFeedback(runtimeText(R.string.runtime_share_menu_failed));
        }
    }

    private void restoreArchiveDocumentState(Bundle state) {
        if (state == null || !state.containsKey(STATE_ARCHIVE_SAVE_NAME)) return;
        pendingArchiveCompatibility = state.getBoolean(STATE_ARCHIVE_SAVE_COMPATIBILITY);
        pendingArchiveDocument = ArchiveDocumentWriter.restore(getCacheDir(),
                state.getString(STATE_ARCHIVE_SAVE_NAME));
        if (pendingArchiveDocument == null) {
            publishSettingsOperation(pendingArchiveCompatibility
                            ? SettingsOperation.Compatibility : SettingsOperation.Logs,
                    runtimeText(R.string.runtime_archive_save_failed), StatusTone.Error, false, true);
            return;
        }
        logExportInProgress = !pendingArchiveCompatibility;
        compatibilityExportInProgress = pendingArchiveCompatibility;
        publishSettingsOperation(pendingArchiveCompatibility
                        ? SettingsOperation.Compatibility : SettingsOperation.Logs,
                runtimeText(R.string.runtime_archive_choose_destination), StatusTone.Warning, true, true);
    }

    @SuppressWarnings("deprecation")
    private void chooseArchiveDocument(File archive, boolean compatibility) {
        if (runtimeDialogOwner == (compatibility ? RuntimeDialogOwner.COMPATIBILITY : RuntimeDialogOwner.LOGS)) {
            clearRuntimeDialog();
        }
        pendingArchiveDocument = archive;
        pendingArchiveCompatibility = compatibility;
        publishSettingsOperation(compatibility ? SettingsOperation.Compatibility : SettingsOperation.Logs,
                runtimeText(R.string.runtime_archive_choose_destination), StatusTone.Warning, true);
        try {
            startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, archive.getName()), ARCHIVE_SAVE_REQUEST);
        } catch (RuntimeException error) {
            pendingArchiveDocument = null;
            archive.delete();
            record("archive_document_save", "state", "picker_failed", "error", error.toString());
            finishArchiveDocument(compatibility, R.string.runtime_archive_picker_failed, StatusTone.Error);
        }
    }

    private void acceptArchiveDocument(int resultCode, Intent data) {
        final File archive = pendingArchiveDocument;
        final boolean compatibility = pendingArchiveCompatibility;
        // Only a picker request is persisted. A canceled/interrupted copy must not restore a busy UI.
        pendingArchiveDocument = null;
        if (archive == null) {
            finishArchiveDocument(compatibility, R.string.runtime_archive_save_failed, StatusTone.Error);
            return;
        }
        final Uri destination = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (destination == null) {
            archive.delete();
            finishArchiveDocument(compatibility, R.string.runtime_archive_save_canceled, StatusTone.Warning);
            return;
        }
        publishSettingsOperation(compatibility ? SettingsOperation.Compatibility : SettingsOperation.Logs,
                runtimeText(R.string.runtime_archive_saving), StatusTone.Warning, true);
        if (!compatibility) {
            exportArchiveToDocument = true;
            diagnosticDocumentCopy = true;
            diagnosticExportProgress = null;
            diagnosticExportControl = newDiagnosticExportControl();
            showDiagnosticExportProgress();
        }
        final CompatibilityBundleExporter.ExportControl copyControl = compatibility ? null : diagnosticExportControl;
        final Context appContext = getApplicationContext();
        try {
            logExportExecutor.execute(() -> {
                Throwable failure = null;
                try (OutputStream output = appContext.getContentResolver().openOutputStream(destination, "w")) {
                    ArchiveDocumentWriter.copy(archive, output, copyControl);
                } catch (Exception error) {
                    failure = error;
                }
                final boolean canceled = copyControl != null && copyControl.isCancellationRequested();
                if (canceled && failure == null) failure = new CompatibilityBundleExporter.CancellationException();
                if (failure != null) {
                    // This URI is the new document just created by the picker, never an existing archive.
                    try {
                        android.provider.DocumentsContract.deleteDocument(appContext.getContentResolver(), destination);
                    } catch (Exception ignored) { /* Some providers cannot remove an incomplete document. */ }
                }
                archive.delete();
                Throwable error = failure;
                mainHandler.post(() -> {
                    record("archive_document_save", "state", error == null ? "saved" : "failed",
                            "compatibility", compatibility, "error", error == null ? "" : error.toString());
                    finishArchiveDocument(compatibility, canceled ? R.string.runtime_archive_save_canceled
                            : error == null ? R.string.runtime_archive_saved : R.string.runtime_archive_save_failed,
                            canceled ? StatusTone.Warning : error == null ? StatusTone.Ok : StatusTone.Error);
                });
            });
        } catch (RuntimeException error) {
            archive.delete();
            record("archive_document_save", "state", "start_failed", "error", error.toString());
            finishArchiveDocument(compatibility, R.string.runtime_archive_save_failed, StatusTone.Error);
        }
    }

    private void finishArchiveDocument(boolean compatibility, int message, StatusTone tone) {
        if (compatibility) compatibilityExportInProgress = false;
        else {
            logExportInProgress = false;
            diagnosticExportControl = null;
            diagnosticExportProgress = null;
            diagnosticDocumentCopy = false;
            if (runtimeDialogOwner == RuntimeDialogOwner.LOGS) clearRuntimeDialog();
        }
        if (activityDestroyed) return;
        publishSettingsOperation(compatibility ? SettingsOperation.Compatibility : SettingsOperation.Logs,
                runtimeText(message), tone, false);
        recordOperationFeedback(runtimeText(message));
        if (activityResumed) advanceStartupAuthorizationFlow();
    }

    private void runUpdateCheck(boolean force) {
        if (activityDestroyed) return;
        UpdateHintRuntime runtime = UpdateHintRuntime.get(this);
        UpdateHintRuntime.DownloadSnapshot operation = runtime.downloadOperation();
        if (operation != null && operation.phase != UpdateHintRuntime.DownloadPhase.FAILED) {
            runtime.showDownloadOperation();
            return;
        }
        if (operation != null) runtime.dismissDownloadOperation();
        runtime.check(force);
    }

    private void onUpdateCheckStarted(boolean force) {
        if (activityDestroyed || isFinishing()) return;
        updateCheckInFlight = true;
        showUpdateCheckProgress();
        updateCheckDialogRequested = force;
        updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
        UpdateHintRuntime.get(this).setManualCheckDialogRequested(force);
        if (force) restoreManualUpdateCheckDialog();
        record("update_check_started", "automatic", !force);
    }

    private void showUpdateCheckProgress() {
        publishSettingsOperation(SettingsOperation.Update,
                runtimeText(R.string.runtime_update_checking), StatusTone.Warning, true);
    }

    private void restoreManualUpdateCheckDialog() {
        if (!updateCheckDialogRequested || !activityResumed || activityDestroyed || isFinishing()) return;
        DialogUiState current = productionUi == null ? null
                : productionUi.getState().getDialog();
        boolean ownUpdateDialog = runtimeDialogOwner == RuntimeDialogOwner.UPDATE
                && current != null;
        boolean currentIsChecking = ownUpdateDialog
                && runtimeText(R.string.runtime_update_checking).equals(current.getUpdateStatus());
        if (ownUpdateDialog && !currentIsChecking) return;
        if (updateCheckInFlight) {
            if (!currentIsChecking) showUpdateCheckDialog();
            return;
        }
        if (updateCheckDialogResult == UPDATE_CHECK_RESULT_ERROR) {
            showUpdateStatus(runtimeText(R.string.runtime_update_check_error), true);
        } else if (updateCheckDialogResult == UPDATE_CHECK_RESULT_LATEST) {
            showUpdateStatus(runtimeText(R.string.runtime_up_to_date), false);
        } else {
            AppUpdateManager.UpdateInfo pending = UpdateHintRuntime.get(this).pendingOffer();
            if (pending == null) {
                updateCheckDialogRequested = false;
                UpdateHintRuntime.get(this).setManualCheckDialogRequested(false);
            } else if (showUpdateOffer(pending)) {
                updateCheckDialogRequested = false;
            }
        }
    }

    private void showUpdateCheckDialog() {
        showUpdateRuntimeDialog(DialogKind.Update,
                runtimeText(R.string.update_dialog_current_version, BuildConfig.VERSION_NAME),
                "", null, false, () -> {}, () -> {
                    updateCheckDialogRequested = false;
                    updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
                    UpdateHintRuntime.get(this).setManualCheckDialogRequested(false);
                }, runtimeText(R.string.runtime_update_checking), false, null, null);
    }

    private void onUpdateCheckFinished(AppUpdateManager.UpdateInfo available,
            Throwable error, boolean force) {
        updateCheckInFlight = false;
        if (activityDestroyed || isFinishing()) return;
        if (error != null) {
            record("update_check_finished", "result", "error", "error", error.toString());
            publishSettingsOperation(SettingsOperation.Update,
                    runtimeText(R.string.runtime_update_check_error), StatusTone.Error, false);
            if (force && updateCheckDialogRequested) {
                updateCheckDialogResult = UPDATE_CHECK_RESULT_ERROR;
                restoreManualUpdateCheckDialog();
            } else updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
        } else if (available == null) {
            publishSettingsOperation(SettingsOperation.Update,
                    runtimeText(R.string.runtime_up_to_date), StatusTone.Ok, false);
            record("update_check_finished", "result", "up_to_date");
            if (force && updateCheckDialogRequested) {
                updateCheckDialogResult = UPDATE_CHECK_RESULT_LATEST;
                restoreManualUpdateCheckDialog();
            } else updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
        } else {
            publishSettingsOperation(SettingsOperation.Update,
                    runtimeText(R.string.runtime_update_available_version, available.version),
                    StatusTone.Ok, false);
            record("update_check_finished", "result", "available", "version", available.version);
            updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
            if (force) {
                if (updateCheckDialogRequested) {
                    if (activityResumed && showUpdateOffer(available)) {
                        updateCheckDialogRequested = false;
                    }
                } else UpdateHintRuntime.get(this).consume(available.resultId);
            } else showCachedUpdateIfAvailable();
        }
    }

    private boolean showCachedUpdateIfAvailable() {
        if (updateCheckDialogRequested) return false;
        UpdateHintRuntime runtime = UpdateHintRuntime.get(this);
        UpdateHintRuntime.DownloadSnapshot operation = runtime.downloadOperation();
        if (operation != null && operation.dialogVisible) {
            return showUpdateOperation(operation);
        }
        AppUpdateManager.UpdateInfo available = runtime.pendingOffer();
        if (available == null || updateDownloadInFlight) return false;
        return showUpdateOffer(available);
    }

    private boolean showUpdateOffer(AppUpdateManager.UpdateInfo available) {
        UpdateHintRuntime runtime = UpdateHintRuntime.get(this);
        return showUpdateRuntimeDialog(DialogKind.Update,
                runtimeText(R.string.update_dialog_current_version, BuildConfig.VERSION_NAME),
                updateNotes(available), null, true,
                () -> {
                    updateCheckDialogRequested = false;
                    updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
                    runtime.setManualCheckDialogRequested(false);
                    runtime.consume(available.resultId);
                    startUpdateDownload(available);
                }, () -> {
                    updateCheckDialogRequested = false;
                    updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
                    runtime.setManualCheckDialogRequested(false);
                    runtime.consume(available.resultId);
                }, null, false,
                runtimeText(R.string.update_dialog_available_version, available.version),
                available.historyComplete ? null
                        : runtimeText(R.string.update_dialog_history_incomplete));
    }

    private void acceptUpdateHintIntent(Intent intent) {
        if (intent == null || productionUi == null) return;
        String resultId = intent.getStringExtra(UpdateHintRuntime.EXTRA_RESULT_ID);
        intent.removeExtra(UpdateHintRuntime.EXTRA_RESULT_ID);
        if (!UpdateHintRuntime.get(this).acceptsIntent(resultId)) return;
        productionUi.dispatch(new BydExtendUiAction.Navigate(RootTab.Settings));
        productionUi.dispatch(new BydExtendUiAction.Select(
                new SelectionTarget.Simple(SelectionId.SettingsCategory), 0));
        showCachedUpdateIfAvailable();
    }

    private void startUpdateDownload(AppUpdateManager.UpdateInfo info) {
        if (activityDestroyed) return;
        updateInstallRequested = true;
        refreshProductionHeader();
        record("update_download_started", "version", info.version);
        UpdateHintRuntime.get(this).startDownload(info);
    }

    private void onUpdateDownloadChanged(UpdateHintRuntime.DownloadSnapshot snapshot) {
        updateDownloadInFlight = snapshot != null
                && (snapshot.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING
                || snapshot.phase == UpdateHintRuntime.DownloadPhase.INSTALLING);
        if (snapshot == null || snapshot.phase == UpdateHintRuntime.DownloadPhase.FAILED
                || snapshot.phase == UpdateHintRuntime.DownloadPhase.CANCELLED) {
            updateInstallRequested = false;
        } else updateInstallRequested = true;
        if (activityDestroyed) return;
        refreshProductionHeader();
        if (snapshot != null && snapshot.dialogVisible && activityResumed) {
            showUpdateOperation(snapshot);
        }
    }

    private boolean showUpdateOperation(UpdateHintRuntime.DownloadSnapshot snapshot) {
        if (snapshot == null || !snapshot.dialogVisible) return false;
        updateInstallRequested = snapshot.phase != UpdateHintRuntime.DownloadPhase.FAILED
                && snapshot.phase != UpdateHintRuntime.DownloadPhase.CANCELLED;
        boolean activeUpdateDialog = runtimeDialogOwner == RuntimeDialogOwner.UPDATE
                && productionUi != null && productionUi.getState().getDialog() != null;
        if (!canPresentUpdateResult(activeUpdateDialog)) return false;
        boolean downloading = snapshot.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING
                || snapshot.phase == UpdateHintRuntime.DownloadPhase.INSTALLING;
        DialogKind kind = downloading ? DialogKind.Progress
                : snapshot.phase == UpdateHintRuntime.DownloadPhase.READY
                        || snapshot.phase == UpdateHintRuntime.DownloadPhase.CANCELLED
                        ? DialogKind.Update : DialogKind.Message;
        String status = null;
        if (snapshot.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING
                || snapshot.phase == UpdateHintRuntime.DownloadPhase.INSTALLING) {
            status = runtimeText(R.string.update_dialog_downloading);
        } else if (snapshot.phase == UpdateHintRuntime.DownloadPhase.FAILED) {
            status = runtimeText(R.string.update_dialog_download_failed,
                    snapshot.error == null ? "" : snapshot.error);
            record("update_failed", "error", snapshot.error == null ? "unknown" : snapshot.error);
        } else if (snapshot.installerError) {
            status = runtimeText(R.string.update_dialog_installer_error);
        }
        Float progress = downloading ? snapshot.progress / 100f : null;
        boolean shown = showUpdateRuntimeDialog(kind,
                runtimeText(R.string.update_dialog_current_version, BuildConfig.VERSION_NAME),
                updateNotes(snapshot.info), progress,
                snapshot.phase == UpdateHintRuntime.DownloadPhase.READY
                        || snapshot.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING
                        || snapshot.phase == UpdateHintRuntime.DownloadPhase.CANCELLED,
                () -> {
                    UpdateHintRuntime runtime = UpdateHintRuntime.get(this);
                    UpdateHintRuntime.DownloadSnapshot current = runtime.downloadOperation();
                    if (current == null || current != snapshot) return;
                    if (current.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING) {
                        runtime.cancelDownload();
                        return;
                    }
                    if (current.phase == UpdateHintRuntime.DownloadPhase.CANCELLED) {
                        startUpdateDownload(current.info);
                        return;
                    }
                    if (snapshot.phase == UpdateHintRuntime.DownloadPhase.READY
                            && current != null
                            && current.phase == UpdateHintRuntime.DownloadPhase.READY
                            && current.dialogVisible
                            && current.info.resultId.equals(snapshot.info.resultId)
                            && isForegroundUpdateOwner()
                            && runtime.installReady(this)) {
                        updateInstallerOpened = true;
                        record("update_install_opened", "version", snapshot.info.version);
                    }
                }, () -> UpdateHintRuntime.get(this).dismissDownloadOperation(),
                status, snapshot.phase == UpdateHintRuntime.DownloadPhase.FAILED
                        || snapshot.installerError,
                runtimeText(R.string.update_dialog_available_version, snapshot.info.version),
                snapshot.info.historyComplete ? null
                        : runtimeText(R.string.update_dialog_history_incomplete),
                snapshot.phase == UpdateHintRuntime.DownloadPhase.DOWNLOADING
                        ? runtimeText(R.string.runtime_cancel)
                        : runtimeText(R.string.update_dialog_action));
        if (shown && snapshot.phase == UpdateHintRuntime.DownloadPhase.READY) {
            maybeStartInitialInstallerHandoff(snapshot);
        }
        return shown;
    }

    private String updateNotes(AppUpdateManager.UpdateInfo info) {
        return ReleaseNotesSelector.selectHistory(
                info.releaseHistory, AppLanguage.read(preferences));
    }

    private void showUpdateStatus(String status, boolean isError) {
        showUpdateRuntimeDialog(DialogKind.Message,
                runtimeText(R.string.update_dialog_current_version, BuildConfig.VERSION_NAME),
                "", null, false, () -> {}, () -> {
                    updateCheckDialogRequested = false;
                    updateCheckDialogResult = UPDATE_CHECK_RESULT_NONE;
                    UpdateHintRuntime.get(this).setManualCheckDialogRequested(false);
                }, status, isError, null, null);
    }

    private boolean showUpdateRuntimeDialog(DialogKind kind, String message, String markdown,
            Float progress, boolean confirmEnabled, Runnable confirm, Runnable dismiss) {
        return showUpdateRuntimeDialog(kind, message, markdown, progress, confirmEnabled,
                confirm, dismiss, null, false, null, null);
    }

    private boolean showUpdateRuntimeDialog(DialogKind kind, String message, String markdown,
            Float progress, boolean confirmEnabled, Runnable confirm, Runnable dismiss,
            String updateStatus, boolean updateStatusIsError, String availableVersion,
            String historyWarning) {
        return showUpdateRuntimeDialog(kind, message, markdown, progress, confirmEnabled,
                confirm, dismiss, updateStatus, updateStatusIsError, availableVersion,
                historyWarning, runtimeText(R.string.update_dialog_action));
    }

    private boolean showUpdateRuntimeDialog(DialogKind kind, String message, String markdown,
            Float progress, boolean confirmEnabled, Runnable confirm, Runnable dismiss,
            String updateStatus, boolean updateStatusIsError, String availableVersion,
            String historyWarning, String actionLabel) {
        boolean replacing = runtimeDialogOwner == RuntimeDialogOwner.UPDATE
                && productionUi != null && productionUi.getState().getDialog() != null;
        if (!canPresentUpdateResult(replacing)) return false;
        runtimeDialogOwner = RuntimeDialogOwner.UPDATE;
        if (!replacing) {
            cancelPendingBackgroundStartSettings();
            cancelPendingForegroundAdbAuthorization();
            cancelPendingWeatherLocationPermission();
        }
        runtimeDialogConfirm = confirm;
        runtimeDialogDismiss = dismiss;
        productionUi.showDialog(new DialogUiState(kind,
                runtimeText(R.string.update_dialog_title), message, progress,
                true, confirmEnabled, true,
                actionLabel,
                runtimeText(R.string.update_dialog_close), true, markdown, true,
                null, null, null, updateStatus, updateStatusIsError,
                availableVersion, historyWarning));
        return true;
    }

    private boolean isForegroundUpdateOwner() {
        return activityResumed && !activityDestroyed && !isFinishing();
    }

    private void maybeStartInitialInstallerHandoff(UpdateHintRuntime.DownloadSnapshot snapshot) {
        if (snapshot == null || snapshot.phase != UpdateHintRuntime.DownloadPhase.READY
                || !snapshot.initialInstallerHandoffEligible
                || snapshot.initialInstallerHandoffAttempted
                || !isForegroundUpdateOwner()
                || runtimeDialogOwner != RuntimeDialogOwner.UPDATE
                || productionUi == null
                || productionUi.getState().getDialog() == null
                || !productionUi.getState().getDialog().getUpdatePresentation()) return;
        UpdateHintRuntime runtime = UpdateHintRuntime.get(this);
        if (!runtime.claimInitialInstallerHandoff(snapshot.info.resultId)) return;
        if (runtime.installReady(this)) {
            updateInstallerOpened = true;
            record("update_install_opened", "version", snapshot.info.version);
        }
    }

    private boolean canPresentUpdateResult(boolean replacingUpdateDialog) {
        return activityResumed && !activityDestroyed && !isFinishing() && !shutdownRequested
                && !cameraPermissionPending && !microphonePermissionInFlight
                && !bluetoothPermissionInFlight && pendingBluetoothProfiles.isEmpty()
                && !microphonePermissionPending && pendingManualAdbEvent == null
                && !backgroundStartSettingsPending()
                && !weatherLocationPermissionPending && !weatherLocationPermissionInFlight
                && !adbAuthPending && !adbAuthorizationStartScheduled
                && !startupOverlayPermissionFlow.foregroundAuthorizationInFlight()
                && startupOverlayPermissionFlow.canPresentStartupDialogs(
                        android.provider.Settings.canDrawOverlays(this))
                && !settingsTransferInProgress && !settingsReloadPending
                && legacyImportOfferDialog == null && settingsTransferDialog == null
                && !logExportInProgress && !compatibilityExportInProgress
                && productionUi != null && (replacingUpdateDialog
                        || productionUi.getState().getDialog() == null);
    }

    private void startAndBindHelperService() {
        if (helperBound || shutdownRequested) return;
        Intent service = new Intent(this, CameraHelperService.class);
        helperBound = bindService(service, helperConnection, Context.BIND_AUTO_CREATE);
        record("helper_service_start", "bind_requested", helperBound);
    }

    @Override
    @SuppressWarnings("deprecation")
    @android.annotation.SuppressLint("MissingSuperCall")
    public void onBackPressed() {
        if (productionUi != null && isProductionCalibrationSection()) {
            productionUi.dispatch(new BydExtendUiAction.Select(
                    new SelectionTarget.Simple(SelectionId.CameraSection),
                    CameraSection.Parameters.ordinal()));
            return;
        }
        moveTaskToBack(true);
    }

    private void attachHelper(IBinder received) {
        helper = received;
        IBinder registrationTarget = helperCallbackRegistration.connected(received);
        telemetryReady = false;
        manualGearPark = false;
        cameraDiscovered = false;
        publishGuardStatus(runtimeText(R.string.runtime_status_telemetry_connecting),
                StatusTone.Warning);
        if (productionUi != null) {
            productionUi.setDiagnosticStatus(true, new StatusUiState(
                    runtimeText(R.string.runtime_status_direct_search),
                    StatusTone.Warning, true), true);
            productionUi.setDiagnosticStatus(false, new StatusUiState(
                    runtimeText(R.string.runtime_status_avm_search),
                    StatusTone.Warning, true), true);
        }
        record("helper_service_connected");
        CameraHelperService.reportAvasStatus(this);
        updateControls();
        maybeOpenProductionPreview();
        openPendingMotionPreviews();
        enqueueHelperCallbackRegistration(registrationTarget);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        boolean ready = holder.getSurface().isValid();
        debugSurfaceReady = ready;
        if (debugPreviewCover != null) debugPreviewCover.setVisibility(View.VISIBLE);
        record("surface_created", "target", "debug", "valid", ready);
        updateControls();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        debugSurfaceReady = holder.getSurface().isValid();
        record("surface_changed", "target", "debug",
                "width", width, "height", height, "format", format);
        updateControls();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        debugSurfaceReady = false;
        if (activePreview == debugPreview) {
            closeCamera("surface_destroyed");
        }
        record("surface_destroyed", "target", "debug");
        updateControls();
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        if (view != calibrationPreview && view != cameraPreview) return;
        if (view == calibrationPreview) {
            calibrationSurfaceReady = surface.isValid();
            if (calibrationPreviewCover != null) {
                calibrationPreviewCover.setVisibility(View.VISIBLE);
            }
            record("surface_created", "target", "camera_calibration",
                    "valid", calibrationSurfaceReady, "width", width, "height", height,
                    "dewarp_pipeline", true);
            maybeOpenCalibrationCamera();
            updateControls();
            return;
        }
        cameraSurfaceReady = surface.isValid();
        if (cameraPreviewCover != null) cameraPreviewCover.setVisibility(View.VISIBLE);
        record("surface_created", "target", "production", "valid", cameraSurfaceReady,
                "width", width, "height", height,
                "buffer_width", view.cameraBufferWidth(),
                "buffer_height", view.cameraBufferHeight());
        maybeOpenProductionPreview();
        updateControls();
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height,
            int inputGeneration) {
        if (view != calibrationPreview && view != cameraPreview) return;
        onCameraSurfaceAvailable(view, surface, width, height);
        record("activity_camera_input_created",
                "target", view == calibrationPreview ? "camera_calibration" : "production",
                "input_generation", inputGeneration);
    }

    @Override
    public void onCameraSurfaceSizeChanged(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        if (view != calibrationPreview && view != cameraPreview) return;
        if (view == calibrationPreview) {
            calibrationSurfaceReady = surface.isValid();
            record("surface_changed", "target", "camera_calibration",
                    "width", width, "height", height);
            updateControls();
            return;
        }
        cameraSurfaceReady = surface.isValid();
        record("surface_changed", "target", "production",
                "width", width, "height", height,
                "buffer_width", view.cameraBufferWidth(),
                "buffer_height", view.cameraBufferHeight());
        updateControls();
    }

    @Override
    public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
        if (view != calibrationPreview && view != cameraPreview) return;
        if (view == calibrationPreview) {
            calibrationSurfaceReady = false;
            stopCalibrationCopies(true);
            if (activePreview == calibrationPreview) {
                closeCamera("calibration_surface_destroyed");
            }
            record("surface_destroyed", "target", "camera_calibration");
            updateControls();
            return;
        }
        cameraSurfaceReady = false;
        cancelProductionPreviewFirstFrameWait();
        if (activePreview == cameraPreview) closeCamera("surface_destroyed");
        record("surface_destroyed", "target", "production");
        updateControls();
    }

    @Override
    public void onCameraFrameUpdated(BlindSpotCameraView view) {
        onCameraFrameUpdated(view, view.cameraInputGeneration());
    }

    @Override
    public void onCameraFrameUpdated(
            BlindSpotCameraView view, int inputGeneration) {
        if (view == calibrationPreview) {
            int[] generations = {inputGeneration};
            boolean fresh = calibrationPreviewFreshness.accept(
                    activeActivityCameraRequestId, generations);
            if (activePreview == calibrationPreview && requestedOpen && fresh
                    && markActivityCameraFresh(activeActivityCameraRequestId)) {
                if (calibrationPreviewCover != null) {
                    calibrationPreviewCover.setVisibility(View.INVISIBLE);
                }
                if (calibrationRawMirrorCover != null) {
                    calibrationRawMirrorCover.setVisibility(View.INVISIBLE);
                }
                if (calibrationCorrectedMirrorCover != null) {
                    calibrationCorrectedMirrorCover.setVisibility(View.INVISIBLE);
                }
                publishCameraStatus(activeActivityCameraProfile, null,
                        runtimeText(R.string.runtime_status_first_frame), StatusTone.Ok, false);
                startCalibrationCopies();
            }
            return;
        }
        if (view != cameraPreview || activePreview != cameraPreview
                || !requestedOpen || !productionPreviewAwaitingFrame) return;
        int[] generations = {inputGeneration};
        if (!productionPreviewFreshness.accept(
                activeActivityCameraRequestId, generations)) return;
        if (++productionPreviewFrameUpdates < CAMERA_PREVIEW_READY_FRAME_UPDATES) return;
        if (!markActivityCameraFresh(activeActivityCameraRequestId)) return;
        productionPreviewAwaitingFrame = false;
        mainHandler.removeCallbacks(productionPreviewFirstFrameTimeout);
        if (cameraPreviewCover != null) cameraPreviewCover.setVisibility(View.INVISIBLE);
        publishCameraStatus(activeActivityCameraProfile, null,
                runtimeText(R.string.runtime_status_first_frame), StatusTone.Ok, false);
        record("camera_preview_first_frame", productionPreviewFrameFields(
                activeActivityCameraRequestId, activeActivityCameraProfile,
                activeDirectCameraIndex, productionPreviewFrameUpdates, false, false));
    }

    private void confirmCompatibilityBundleShare() {
        confirmCompatibilityBundleExport(false);
    }

    private void confirmCompatibilityBundleExport(boolean saveAs) {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        showRuntimeDialog(RuntimeDialogOwner.COMPATIBILITY, DialogKind.Message,
                runtimeText(saveAs ? R.string.runtime_archive_save_title : R.string.runtime_car_compat_title),
                runtimeText(R.string.runtime_car_compat_message), true,
                runtimeText(R.string.runtime_create), runtimeText(R.string.runtime_cancel), "",
                () -> startCompatibilityBundleExport(saveAs), () -> {});
    }

    private void startCompatibilityBundleExport(boolean saveAs) {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        compatibilityExportInProgress = true;
        exportArchiveToDocument = saveAs;
        publishSettingsOperation(SettingsOperation.Compatibility,
                runtimeText(R.string.runtime_car_compat_forming),
                StatusTone.Warning, true, true);
        record("compatibility_bundle_export", "state", "started");
        final WeakReference<CameraProbeActivity> owner = new WeakReference<>(this);
        final CompatibilityBundleExporter.ExportControl control =
                new CompatibilityBundleExporter.ExportControl(progress -> {
                    CameraProbeActivity target = owner.get();
                    if (target == null || target.activityDestroyed) return;
                    target.mainHandler.post(() -> target.updateCompatibilityExportProgress(progress));
                });
        compatibilityExportControl = control;
        final Context appContext = getApplicationContext();
        final SharedPreferences exportPreferences = preferences;
        try {
            showCompatibilityExportProgress();
            logExportExecutor.execute(() -> {
                File archive = null;
                Throwable failure = null;
                try {
                    archive = CompatibilityBundleExporter.export(appContext, exportPreferences, control);
                } catch (Throwable error) {
                    failure = error;
                }
                File result = archive;
                Throwable error = failure;
                mainHandler.post(() -> finishCompatibilityBundleExport(control, result, error));
            });
        } catch (Throwable error) {
            finishCompatibilityBundleExport(control, null, error);
        }
    }

    private void showCompatibilityExportProgress() {
        showRuntimeDialog(RuntimeDialogOwner.COMPATIBILITY, DialogKind.Progress,
                runtimeText(R.string.runtime_car_compat_title),
                runtimeText(R.string.runtime_car_compat_prepare), true, null,
                runtimeText(R.string.runtime_cancel), "", null, this::cancelCompatibilityBundleExport);
    }

    private void cancelCompatibilityBundleExport() {
        CompatibilityBundleExporter.ExportControl control = compatibilityExportControl;
        if (control == null || control.isCancellationRequested()) return;
        control.cancel();
        updateRuntimeProgress(RuntimeDialogOwner.COMPATIBILITY,
                runtimeText(R.string.runtime_car_compat_canceling), false);
        publishSettingsOperation(SettingsOperation.Compatibility,
                runtimeText(R.string.runtime_car_compat_canceling), StatusTone.Warning, true);
        record("compatibility_bundle_export", "state", "cancel_requested");
    }

    private void updateCompatibilityExportProgress(CompatibilityBundleExporter.Progress progress) {
        if (progress == null || activityDestroyed || !compatibilityExportInProgress
                || compatibilityExportControl == null
                || compatibilityExportControl.isCancellationRequested()) return;
        String text = localizedCompatibilityExportProgressText(progress);
        updateRuntimeProgress(RuntimeDialogOwner.COMPATIBILITY, text, true);
        publishSettingsOperation(SettingsOperation.Compatibility,
                text, StatusTone.Warning, true);
    }

    private String localizedCompatibilityExportProgressText(
            CompatibilityBundleExporter.Progress progress) {
        if (progress == null) return runtimeText(R.string.runtime_car_compat_prepare);
        switch (progress.phase) {
            case PREPARING:
                return runtimeText(R.string.runtime_car_compat_prepare);
            case TEXT:
                return runtimeText(R.string.runtime_car_compat_progress_text,
                        progress.index, progress.count);
            case REMOTE:
                String path = progress.currentPath == null || progress.currentPath.isEmpty()
                        ? runtimeText(R.string.runtime_file) : progress.currentPath;
                return runtimeText(R.string.runtime_car_compat_progress_file,
                        progress.index, progress.count, path,
                        formatBytes(progress.bytes), formatBytes(progress.totalBytes));
            case ZIP:
                return runtimeText(R.string.runtime_car_compat_pack);
            case COMPLETE:
                return runtimeText(R.string.runtime_car_compat_ready);
            case CANCELED:
                return runtimeText(R.string.runtime_car_compat_canceled);
            default:
                return runtimeText(R.string.runtime_car_compat_forming);
        }
    }

    private static String formatBytes(long value) {
        if (value < 1024L) return value + " B";
        if (value < 1024L * 1024L) return (value / 1024L) + " KiB";
        return (value / (1024L * 1024L)) + " MiB";
    }

    private void finishCompatibilityBundleExport(
            CompatibilityBundleExporter.ExportControl control, File archive, Throwable error) {
        if (exportArchiveToDocument && archive != null && error == null
                && (control == null || !control.isCancellationRequested())
                && activityResumed && !activityDestroyed && !shutdownRequested) {
            if (compatibilityExportControl == control) compatibilityExportControl = null;
            chooseArchiveDocument(archive, true);
            return;
        }
        compatibilityExportInProgress = false;
        if (compatibilityExportControl == control) compatibilityExportControl = null;
        if (runtimeDialogOwner == RuntimeDialogOwner.COMPATIBILITY) clearRuntimeDialog();
        if (!activityDestroyed) advanceStartupAuthorizationFlow();
        boolean cancelled = control != null && control.isCancellationRequested();
        if (cancelled) {
            boolean deleted = archive == null || !archive.exists() || archive.delete();
            record("compatibility_bundle_export", "state", "canceled",
                    "archive_deleted", deleted, "error", error == null ? "" : error.toString());
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_car_compat_canceled), StatusTone.Warning, false);
            if (activityResumed && !activityDestroyed) {
                recordOperationFeedback(runtimeText(R.string.runtime_car_compat_cancel_done));
            }
            return;
        }
        if (error != null) {
            record("compatibility_bundle_export", "state", "failed", "error", error.toString());
            String message = runtimeText(CompatibilityExportSpace.isLowSpace(error)
                    ? R.string.runtime_car_compat_low_space : R.string.runtime_car_compat_failed);
            publishSettingsOperation(SettingsOperation.Compatibility,
                    message, StatusTone.Error, false);
            if (activityResumed && !activityDestroyed) {
                recordOperationFeedback(message);
            }
            return;
        }
        if (archive == null) {
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_car_compat_empty), StatusTone.Warning, false);
            return;
        }
        if (!activityResumed || activityDestroyed) {
            boolean deleted = archive.delete();
            record("compatibility_bundle_export", "state", "chooser_skipped",
                    "reason", "activity_inactive", "archive_deleted", deleted);
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_car_compat_inactive), StatusTone.Warning, false);
            return;
        }
        publishSettingsOperation(SettingsOperation.Compatibility,
                runtimeText(R.string.runtime_car_compat_ready_status), StatusTone.Ok, false);
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", archive);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newUri(
                    getContentResolver(), "BYD vehicle compatibility package", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            record("compatibility_bundle_export", "state", "chooser_opened",
                    "archive", archive.getName(), "bytes", archive.length());
            startActivity(Intent.createChooser(share,
                    runtimeText(R.string.runtime_car_compat_share_title)));
        } catch (Throwable shareError) {
            record("compatibility_bundle_export", "state", "share_failed",
                    "error", shareError.toString());
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_share_menu_failed), StatusTone.Error, false);
            recordOperationFeedback(runtimeText(R.string.runtime_share_menu_failed));
        }
    }

    private boolean markActivityCameraFresh(int requestId) {
        if (!activityResumed || !requestedOpen || !activeActivityCameraOpened
                || requestId <= 0 || requestId != activeActivityCameraRequestId) {
            return false;
        }
        boolean first = !activeActivityCameraFresh;
        activeActivityCameraFresh = true;
        return first;
    }

    @Override
    public void onDewarpFallbackChanged(BlindSpotCameraView view) {
        if (view == calibrationPreview) {
            CameraProfileId profile = calibrationHostProfile != null
                    ? calibrationHostProfile : calibrationProfileForUi();
            CameraDewarpConfig dewarp = loadProductionCalibrationDewarp(profile);
            if (productionCalibrationRawOverlay != null) {
                updateProductionCalibrationOverlays(profile, dewarp, view.usesRawFallback());
            } else {
                updateCalibrationDisplay(dewarp.enabled);
            }
            publishRawFallbackState(profile, view);
        } else if (view == cameraPreview) {
            CameraHostSlot placement = productionCameraSlots.get(
                    selectedTab == TAB_REARVIEW_MIRROR
                            ? CameraHostKind.Mirror : CameraHostKind.Placement);
            CameraProfileId profile = placement != null && placement.getProfile() != null
                    ? placement.getProfile()
                    : activeActivityCameraProfile != null
                            ? activeActivityCameraProfile : selectedProductionProfile();
            publishRawFallbackState(profile, view);
        }
    }

    private CameraProfileId calibrationProfileForUi() {
        if (selectedTab == TAB_REARVIEW_MIRROR) return CameraProfileId.Mirror.INSTANCE;
        if (selectedTab == TAB_REVERSE_CAMERAS && productionUi != null) {
            CameraProfileId reverse = selectedProductionProfile();
            if (reverse != null) return reverse;
        }
        if (calibrationParkingMode) {
            return new CameraProfileId.Parking(
                    ParkingView.values()[calibrationParkingCameraId]);
        }
        return new CameraProfileId.Blind(
                calibrationCameraId < CameraProfile.FRONT_LEFT
                        ? CameraGroup.Rear : CameraGroup.Front,
                (calibrationCameraId & 1) == 1 ? CameraSide.Right : CameraSide.Left);
    }

    private void publishRawFallbackState(
            CameraProfileId profile, BlindSpotCameraView view) {
        if (profile == null || productionUi == null || view == null) return;
        productionUi.setCalibrationRawFallback(profile, view.usesRawFallback());
    }

    @Override
    public void onCameraRenderFailed(
            BlindSpotCameraView view, CameraDewarpRenderer.Event event) {
        boolean calibration = view == calibrationPreview;
        if (!calibration && view != cameraPreview) return;
        record("camera_preview_renderer_failed",
                "target", calibration ? "camera_calibration" : "production",
                "kind", event.kind, "lens", event.lens,
                "fov", event.fovDegrees,
                "projection", CameraDewarpConfig.projectionLabel(event.projection),
                "strength_selected", event.selectedStrength, "strength_effective", event.effectiveStrength,
                "horizontal_fov_precise", event.horizontalFov, "diagonal_fov", event.diagonalFov,
                "error", event.error);
        if (activePreview != view) return;
        CameraProfileId failedProfile = activeActivityCameraProfile;
        closeCamera("dewarp_renderer_failed");
        publishCameraStatus(failedProfile, null,
                "Помилка корекції камери; відкрийте preview повторно", StatusTone.Error, false);
    }

    private void onCalibrationDewarpStats(CameraDewarpRenderer.Stats stats) {
        if (!DiagnosticLogPolicy.extended()) return;
        if (stats == null || calibrationPreview == null) return;
        if (!CameraDewarpStatsEvent.shouldRecord(
                activityResumed, requestedOpen,
                activePreview == calibrationPreview, activeActivityCameraRequestId)
                || stats.requestId != activeActivityCameraRequestId
                || stats.contextGeneration != calibrationPreview.cameraInputGeneration()
                || stats.inputGeneration != calibrationPreview.cameraInputGeneration()) return;
        if (calibrationHostProfile instanceof CameraProfileId.Reverse) {
            record("camera_dewarp_stats", CameraDewarpStatsEvent.reverse(
                    stats.requestId,
                    reverseProfileIndex((CameraProfileId.Reverse) calibrationHostProfile),
                    stats));
            return;
        }
        Object[] event = calibrationParkingMode
                ? CameraDewarpStatsEvent.parkingCalibration(
                        stats.requestId,
                        ParkingCameraProfile.of(calibrationParkingCameraId), stats)
                : CameraDewarpStatsEvent.calibration(
                        stats.requestId, calibrationCameraId, stats);
        record("camera_dewarp_stats", event);
    }

    @Override
    public void onReverseSurfacesReady(int[] generations) {
        reverseCameraSurfacesReady = reverseCameraPreview != null
                && reverseCameraPreview.previewSurfacesReady();
        record("reverse_preview_surfaces", "state", "ready",
                "generations", java.util.Arrays.toString(generations));
        maybeOpenReversePreview();
        updateControls();
    }

    @Override
    public void onReverseFramesReady(int requestId, int[] generations) {
        if (activePreview != reverseCameraPreview) return;
        if (!reversePreviewFreshness.accept(requestId, generations)
                || !markActivityCameraFresh(requestId)) return;
        reverseCalibrationFreshness.markRequestFresh(
                requestId,
                reverseCalibrationCameraIndex,
                reverseCalibrationFront,
                reverseCalibrationCopiesRaw());
        publishCameraStatus(activeActivityCameraProfile, null,
                "First frame ready", StatusTone.Ok, false);
        if (reversePreviewBackgroundFailureRequestId == requestId) {
            publishReversePreviewBackgroundUnavailable();
        }
        record("camera_status", "profile", "reverse", "text", "Live preview",
                "tone", StatusTone.Ok.name(), "pending", false);
        record("reverse_preview_frames", "request_id", requestId,
                "generations", java.util.Arrays.toString(generations));
        startReverseCalibrationCopies();
    }

    @Override
    public void onReverseSurfaceLost(int cameraIndex, int generation) {
        stopReverseCalibrationCopies(true);
        boolean terminal = reverseSurfaceLossIsTerminal(
                cameraIndex,
                reverseCameraPreview != null
                        && reverseCameraPreview.centralFrontSurfaceRecoveryPending());
        reverseCameraSurfacesReady = reverseCameraPreview != null
                && reverseCameraPreview.previewSurfacesReady();
        record("reverse_preview_surfaces",
                "state", terminal ? "destroyed" : "optional_destroyed",
                "camera_index", cameraIndex, "generation", generation);
        if (!terminal) {
            updateControls();
            return;
        }
        reversePreviewFreshness.clear();
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
        if (activePreview == reverseCameraPreview) closeCamera("reverse_surface_destroyed");
        updateControls();
    }

    @Override
    public void onReverseOutputSetChanged(int displayTarget, int[] sourceIndexes) {
        reverseCameraSurfacesReady = false;
        if (activePreview == reverseCameraPreview && requestedOpen
                && (activeReversePreviewDisplayTarget != displayTarget
                || !java.util.Arrays.equals(activeReversePreviewSourceIndexes, sourceIndexes))
                && !cameraTransition.pending()) {
            closeCameraForTransition("reverse_output_set_changed");
        }
    }

    static boolean reverseSurfaceLossIsTerminal(
            int cameraIndex, boolean centralFrontRecoveryPending) {
        return cameraIndex != 4 || !centralFrontRecoveryPending;
    }

    @Override
    public void onReverseDewarpEvent(
            int cameraIndex, CameraDewarpRenderer.Event event) {
        record("reverse_preview_dewarp_event", "camera_index", cameraIndex,
                "kind", event.kind, "lens", event.lens,
                "fov", event.fovDegrees,
                "projection", CameraDewarpConfig.projectionLabel(event.projection),
                "strength_selected", event.selectedStrength, "strength_effective", event.effectiveStrength,
                "horizontal_fov_precise", event.horizontalFov, "diagonal_fov", event.diagonalFov,
                "error", event.error);
        if (cameraIndex == reverseCalibrationCameraIndex
                && ("dewarp_mesh_applied".equals(event.kind)
                || "dewarp_fallback_raw".equals(event.kind))) {
            stopReverseCalibrationCopies(true);
            // The Compose path has no legacy calibration overlays.  Keep the callback on the
            // production state path there; only the dormant View-built editor may render copies.
            if (reverseCalibrationPane != null
                    && reverseCalibrationPane.getVisibility() == View.VISIBLE) {
                updateReverseCalibrationDisplay();
            } else if (productionUi != null) {
                productionUi.reload();
            }
        }
        if (!CameraDewarpRenderer.isFatalEventKind(event.kind)
                || activePreview != reverseCameraPreview) return;
        CameraProfileId failedProfile = activeActivityCameraProfile;
        closeCamera("reverse_dewarp_renderer_failed");
        publishCameraStatus(failedProfile, null,
                "Помилка корекції камери; відкрийте preview повторно", StatusTone.Error, false);
    }

    @Override
    public void onReverseDewarpFallbackChanged(
            int cameraIndex, boolean frontSource, boolean active) {
        if (reverseCameraPreview == null
                || cameraIndex < ReverseCameraLayout.REAR_CAMERA_INDEX
                || cameraIndex > ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX
                || productionUi == null) return;
        // A role change must also clear its previous profile while the preview is closed.
        if (active && (activePreview != reverseCameraPreview
                || !requestedOpen || activeActivityCameraRequestId <= 0)) return;
        ReverseElement element = cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX
                ? ReverseElement.Rear
                : cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX
                        ? ReverseElement.RearLeft : ReverseElement.RearRight;
        ReverseSource source = frontSource ? ReverseSource.Front : ReverseSource.Rear;
        productionUi.setCalibrationRawFallback(
                new CameraProfileId.Reverse(element, source), active);
    }

    @Override
    public boolean automaticStartEnabled() {
        return GuardRecovery.isAutoStartEnabled(this);
    }

    @Override
    public boolean legacyAccessRestoreVisible() {
        return LegacySettingsImporter.needsAccessRestore(this);
    }

    @Override
    public boolean runtimeBlockedByLegacy() {
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        return legacyRuntimeBlocked;
    }

    @Override
    public com.byd.extend.ui.AdbRecoveryUiState productionAdbRecoveryState() {
        return com.byd.extend.ui.AdbRecoveryUiBridge.state(
                CameraHelperService.adbRecoverySnapshot(), new AdbReminderSettings(this),
                new WifiRetentionSettings(this));
    }

    @Override
    public void onProductionAdbRecoveryAction(com.byd.extend.ui.AdbRecoveryUiAction action) {
        if (shutdownRequested || activityDestroyed) return;
        AdbReminderSettings settings = new AdbReminderSettings(this);
        if (action instanceof com.byd.extend.ui.AdbRecoveryUiAction.SetKeepWifiCarOff) {
            boolean enabled = ((com.byd.extend.ui.AdbRecoveryUiAction.SetKeepWifiCarOff) action)
                    .getEnabled();
            try {
                new WifiRetentionSettings(this).setEnabled(enabled);
            } catch (RuntimeException error) {
                AdbRecoveryDiagnostics.failure(this::record, "wifi_retention_update_failed",
                        "set_wifi_retention", error, "requested_enabled", enabled, "stage", "save");
                Toast.makeText(this, runtimeText(R.string.runtime_wifi_retention_update_failed),
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                CameraHelperService.wifiRetentionSettingsChanged(this);
            } catch (RuntimeException error) {
                AdbRecoveryDiagnostics.failure(this::record, "wifi_retention_update_failed",
                        "set_wifi_retention", error, "requested_enabled", enabled,
                        "stage", "service_dispatch");
                Toast.makeText(this, runtimeText(R.string.runtime_wifi_retention_update_failed),
                        Toast.LENGTH_LONG).show();
            }
            return;
        } else if (action instanceof com.byd.extend.ui.AdbRecoveryUiAction.SetRecoveryEnabled) {
            settings.setRecoveryEnabled(
                    ((com.byd.extend.ui.AdbRecoveryUiAction.SetRecoveryEnabled) action).getEnabled());
        } else if (action instanceof com.byd.extend.ui.AdbRecoveryUiAction.SetReminderEnabled) {
            settings.setReminderEnabled(
                    ((com.byd.extend.ui.AdbRecoveryUiAction.SetReminderEnabled) action).getEnabled());
        } else if (action instanceof com.byd.extend.ui.AdbRecoveryUiAction.UpdateReminderAppearance) {
            settings.saveAppearance(
                    ((com.byd.extend.ui.AdbRecoveryUiAction.UpdateReminderAppearance) action).getAppearance());
        } else if (action == com.byd.extend.ui.AdbRecoveryUiAction.Retry.INSTANCE) {
            record("adb_recovery_manual_retry");
            CameraHelperService.retryAdbWifi(this);
            return;
        } else if (action == com.byd.extend.ui.AdbRecoveryUiAction.DismissReminder.INSTANCE) {
            CameraHelperService.suppressAdbReminder();
            return;
        }
        CameraHelperService.adbRecoverySettingsChanged(this);
        refreshProductionHeader();
    }

    @Override
    public AvasUiState productionAvasState() {
        AvasConfig config;
        try {
            config = avasLibrary == null ? AvasConfig.empty() : avasLibrary.loadConfig();
        } catch (IllegalArgumentException invalid) {
            record("avas_config_invalid", "error", invalid.toString());
            config = AvasConfig.empty();
        }
        List<AvasProfileUiState> profiles = new ArrayList<>();
        for (AvasConfig.Profile profile : config.profiles) {
            List<AvasAssetUiState> assets = new ArrayList<>();
            String selectedName = null;
            for (AvasConfig.Asset asset : profile.assets) {
                requestAvasDuration(asset.id);
                assets.add(new AvasAssetUiState(asset.id, asset.name,
                        avasLibrary != null && avasLibrary.preparedFile(asset.id).isFile(),
                        AvasBuiltinSounds.isBuiltinAsset(asset.id), avasDurations.get(asset.id)));
                if (asset.id.equals(profile.selectedAssetId)) selectedName = asset.name;
            }
            profiles.add(new AvasProfileUiState(profile.id, profile.enabled, profile.random,
                    selectedName, assets, profile.volume, avasPlayback(profile.id),
                    profile.selectedAssetId.isEmpty() ? null : profile.selectedAssetId,
                    profile.skipConcurrentLockUnlock, profile.onlyWhenLockingPoweredOff,
                    profile.internalSpeaker));
        }
        String importing = preferences == null ? null
                : preferences.getString(PREF_AVAS_IMPORT_PROFILE, null);
        if (!AvasConfig.PROFILE_IDS.contains(importing)) importing = null;
        return new AvasUiState(profiles, importing, avasAudition,
                new com.byd.extend.ui.AvasMicrophoneUiState(
                        AvasMicrophoneSettings.enabled(preferences),
                        AvasMicrophoneSettings.volume(preferences),
                        CameraButtonBindings.load(preferences, CameraButtonBindings.Action.AvasMicrophone),
                        AvasMicrophoneCapture.state(),
                        AvasMicrophoneSettings.noiseSuppression(preferences),
                        AvasMicrophoneSettings.echoCancellation(preferences),
                        AvasMicrophoneEffects.supportsNoiseSuppression(),
                        AvasMicrophoneEffects.supportsEchoCancellation()),
                new AvasEngineUiState(config.engine.enabled, config.engine.packId,
                        config.engine.exteriorEnabled, config.engine.exteriorVolume,
                        config.engine.interiorEnabled, config.engine.interiorVolume,
                        avasEngineState, avasEngineError, avasEngineTestActive,
                        CameraButtonBindings.load(preferences, CameraButtonBindings.Action.AvasEngine),
                        config.engine.navigationPriority, config.engine.buttonOnly));
    }

    @Override
    public void onProductionAvasAction(AvasBackendAction action) {
        if (action != null && !shutdownRequested && "engine".equals(action.getProfileId())) {
            onEngineAction(action);
            return;
        }
        if (action != null && !shutdownRequested
                && AvasMicrophoneSettings.PROFILE.equals(action.getProfileId())) {
            onMicrophoneAction(action);
            return;
        }
        if (action == null || shutdownRequested || avasLibrary == null
                || !AvasConfig.PROFILE_IDS.contains(action.getProfileId())) return;
        String profileId = action.getProfileId();
        try {
            AvasConfig config = avasLibrary.loadConfig();
            AvasConfig.Profile profile = config.profile(profileId);
            AvasActionKind kind = action.getKind();
            if (!profile.enabled && kind != AvasActionKind.SetEnabled
                    && kind != AvasActionKind.StopAudition) return;
            if (kind == AvasActionKind.ImportFiles) {
                chooseAvasAudio(profileId);
                return;
            }
            if (kind == AvasActionKind.StartAudition) {
                if (AvasMicrophoneCapture.busy()) return;
                String assetId = action.getStringValue();
                boolean member = false;
                for (AvasConfig.Asset asset : profile.assets) member |= asset.id.equals(assetId);
                if (!member || !activityStarted || !avasLibrary.preparedFile(assetId).isFile()) return;
                for (AvasPlaybackUiState playback : avasPlayback.values()) {
                    if (playback != AvasPlaybackUiState.Idle) return;
                }
                String sessionId = java.util.UUID.randomUUID().toString().replace("-", "");
                avasAudition = new AvasAuditionUiState(profileId, assetId, sessionId, "queued");
                CameraHelperService.startAvasAudition(this, profileId, assetId, sessionId);
                return;
            }
            if (kind == AvasActionKind.StopAudition) {
                stopAvasListAudition(action.getStringValue());
                return;
            }
            if (kind == AvasActionKind.DeleteAsset) {
                String assetId = action.getStringValue();
                if (assetId != null && assetId.equals(avasAudition.getAssetId())) {
                    stopAvasListAudition(avasAudition.getSessionId());
                }
                avasImportExecutor.execute(() -> {
                    try {
                        if (avasLibrary.removeAsset(profileId, assetId)) {
                            CameraHelperService.configureAvas(this);
                            mainHandler.post(() -> {
                                avasDurations.remove(assetId);
                                avasDurationRequests.remove(assetId);
                                if (!activityDestroyed && productionUi != null) productionUi.refreshAvasState();
                            });
                        }
                    } catch (RuntimeException error) {
                        mainHandler.post(() -> {
                            record("avas_delete_failed", "profile_id", profileId, "error", error.toString());
                            if (!activityDestroyed && !isFinishing()) Toast.makeText(this,
                                    runtimeText(R.string.runtime_avas_action_failed), Toast.LENGTH_LONG).show();
                        });
                    }
                });
                return;
            }
            if (kind == AvasActionKind.StartManual) {
                AvasPlaybackUiState playback = avasPlayback(profileId);
                if (!profile.selectedAssetId.isEmpty()
                        && playback != AvasPlaybackUiState.ManualQueued
                        && playback != AvasPlaybackUiState.ManualPlaying
                        && avasLibrary.preparedFile(profile.selectedAssetId).isFile()) {
                    avasPlayback.put(profileId, AvasPlaybackUiState.ManualQueued);
                    stopAvasListAudition(avasAudition.getSessionId());
                    CameraHelperService.startAvasManual(this, profileId);
                }
                return;
            }
            if (kind == AvasActionKind.StopManual) {
                AvasPlaybackUiState playback = avasPlayback(profileId);
                if (playback == AvasPlaybackUiState.ManualQueued
                        || playback == AvasPlaybackUiState.ManualPlaying) {
                    CameraHelperService.stopAvasManual(this, profileId);
                }
                return;
            }
            Boolean enabled = null;
            Boolean random = null;
            Integer volume = null;
            String selected = null;
            Boolean skipConcurrentLockUnlock = null;
            Boolean onlyWhenLockingPoweredOff = null;
            Boolean internalSpeaker = null;
            if (kind == AvasActionKind.SetEnabled && action.getBooleanValue() != null) {
                enabled = action.getBooleanValue();
                if (!enabled) stopAvasListAudition(avasAudition.getSessionId());
            } else if (kind == AvasActionKind.SetRandom && action.getBooleanValue() != null) {
                random = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetVolume && action.getIntValue() != null) {
                volume = Math.max(0, Math.min(100, action.getIntValue()));
            } else if (kind == AvasActionKind.SetInternalSpeaker && action.getBooleanValue() != null) {
                internalSpeaker = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetSkipConcurrentLockUnlock
                    && action.getBooleanValue() != null) {
                skipConcurrentLockUnlock = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetOnlyWhenLockingPoweredOff
                    && "power_off".equals(profileId) && action.getBooleanValue() != null) {
                onlyWhenLockingPoweredOff = action.getBooleanValue();
            } else if (kind == AvasActionKind.SelectAsset && action.getStringValue() != null) {
                selected = action.getStringValue();
            } else {
                return;
            }
            avasLibrary.updateProfileSettings(profileId, enabled, random, volume, selected,
                    skipConcurrentLockUnlock, onlyWhenLockingPoweredOff, internalSpeaker);
            CameraHelperService.configureAvas(this);
        } catch (RuntimeException invalid) {
            if (action.getKind() == AvasActionKind.StartAudition) {
                avasAudition = new AvasAuditionUiState();
                if (productionUi != null) productionUi.refreshAvasState();
            }
            if (action.getKind() == AvasActionKind.StartManual) {
                avasPlayback.put(profileId, AvasPlaybackUiState.Idle);
                if (productionUi != null) productionUi.refreshAvasState();
            }
            record("avas_ui_action_failed", "profile_id", profileId,
                    "action", action.getKind().name(), "error", invalid.toString());
            Toast.makeText(this, runtimeText(R.string.runtime_avas_action_failed),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onEngineAction(AvasBackendAction action) {
        if (avasLibrary == null) return;
        try {
            AvasConfig config = avasLibrary.loadConfig();
            AvasConfig.Engine engine = config.engine;
            AvasActionKind kind = action.getKind();
            if (kind == AvasActionKind.StartManual) {
                if (engine.enabled && (engine.exteriorEnabled || engine.interiorEnabled)
                        && !avasEngineTestActive && !"stopping".equals(avasEngineState)) {
                    CameraHelperService.startAvasEngine(this);
                }
                return;
            }
            if (kind == AvasActionKind.StopManual) {
                if (avasEngineTestActive) CameraHelperService.stopAvasEngine(this);
                return;
            }
            if (!engine.enabled && kind != AvasActionKind.SetEnabled) return;
            boolean enabled = engine.enabled;
            String packId = engine.packId;
            boolean exteriorEnabled = engine.exteriorEnabled;
            int exteriorVolume = engine.exteriorVolume;
            boolean interiorEnabled = engine.interiorEnabled;
            int interiorVolume = engine.interiorVolume;
            boolean navigationPriority = engine.navigationPriority;
            boolean buttonOnly = engine.buttonOnly;
            if (kind == AvasActionKind.SetEnabled && action.getBooleanValue() != null) {
                enabled = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetEngineNavigationPriority
                    && action.getBooleanValue() != null) {
                navigationPriority = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetEngineButtonOnly && action.getBooleanValue() != null
                    && CameraButtonBindings.load(preferences, CameraButtonBindings.Action.AvasEngine).keyCode >= 0) {
                buttonOnly = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetEnginePack && action.getStringValue() != null) {
                packId = action.getStringValue();
            } else if (kind == AvasActionKind.SetEngineExterior
                    && action.getBooleanValue() != null) {
                exteriorEnabled = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetEngineInterior
                    && action.getBooleanValue() != null) {
                interiorEnabled = action.getBooleanValue();
            } else if (kind == AvasActionKind.SetEngineExteriorVolume
                    && action.getIntValue() != null) {
                exteriorVolume = Math.max(0, Math.min(100, action.getIntValue()));
            } else if (kind == AvasActionKind.SetEngineInteriorVolume
                    && action.getIntValue() != null) {
                interiorVolume = Math.max(0, Math.min(100, action.getIntValue()));
            } else {
                return;
            }
            AvasConfig.Engine updated = new AvasConfig.Engine(enabled, packId,
                    exteriorEnabled, exteriorVolume, interiorEnabled, interiorVolume, navigationPriority, buttonOnly);
            avasLibrary.saveConfig(config.withEngine(updated));
            if (!enabled && productionUi != null
                    && productionUi.getState().getDialog() != null
                    && productionUi.getState().getDialog().getCaptureAction()
                            == CameraButtonBindings.Action.AvasEngine) {
                cancelReverseButtonLearning();
            }
            CameraHelperService.configureAvas(this);
        } catch (RuntimeException invalid) {
            record("avas_engine_ui_action_failed", "action", action.getKind().name(),
                    "error", invalid.toString());
            Toast.makeText(this, runtimeText(R.string.runtime_avas_action_failed),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void onMicrophoneAction(AvasBackendAction action) {
        if (action.getKind() == AvasActionKind.SetEnabled && action.getBooleanValue() != null) {
            if (!action.getBooleanValue()) microphonePermissionPending = false;
            if (action.getBooleanValue() && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestMicrophonePermission();
            } else {
                preferences.edit().putBoolean(AvasMicrophoneSettings.ENABLED,
                        action.getBooleanValue()).apply();
                if (!action.getBooleanValue()) WeatherRefreshAccessibilityService.stopMicrophone();
            }
        } else if (action.getKind() == AvasActionKind.StopManual) {
            WeatherRefreshAccessibilityService.stopMicrophone();
        } else if (AvasMicrophoneSettings.enabled(preferences)) {
            if (action.getKind() == AvasActionKind.SetNoiseSuppression && action.getBooleanValue() != null
                    && AvasMicrophoneEffects.supportsNoiseSuppression()) {
                preferences.edit().putBoolean(AvasMicrophoneSettings.NOISE_SUPPRESSION,
                        action.getBooleanValue()).apply();
            } else if (action.getKind() == AvasActionKind.SetEchoCancellation && action.getBooleanValue() != null
                    && AvasMicrophoneEffects.supportsEchoCancellation()) {
                preferences.edit().putBoolean(AvasMicrophoneSettings.ECHO_CANCELLATION,
                        action.getBooleanValue()).apply();
            } else if (action.getKind() == AvasActionKind.SetVolume && action.getIntValue() != null) {
                preferences.edit().putInt(AvasMicrophoneSettings.VOLUME,
                        Math.max(0, Math.min(100, action.getIntValue()))).apply();
            } else if (action.getKind() == AvasActionKind.StartManual) {
                stopAvasListAudition(avasAudition.getSessionId());
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    requestMicrophonePermission();
                else if (!WeatherRefreshAccessibilityService.startMicrophone())
                    Toast.makeText(this, runtimeText(R.string.avas_mic_unavailable), Toast.LENGTH_LONG).show();
            }
        }
        if (productionUi != null) productionUi.refreshAvasState();
        refreshProductionHeader();
    }

    private void requestMicrophonePermission() {
        if (microphonePermissionInFlight) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            microphonePermissionPending = false;
            preferences.edit().putBoolean(AvasMicrophoneSettings.ENABLED, true).apply();
            if (productionUi != null) productionUi.refreshAvasState();
            return;
        }
        if (!runtimePermissionWindowAvailable()) {
            microphonePermissionPending = true;
            record("microphone_permission_deferred", "reason", "startup_permissions");
            return;
        }
        microphonePermissionPending = false;
        microphonePermissionInFlight = true;
        try {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE_PERMISSION_REQUEST);
        } catch (RuntimeException failure) {
            microphonePermissionInFlight = false;
            record("microphone_permission_failed", "error", failure.toString());
            Toast.makeText(this, runtimeText(R.string.avas_mic_permission), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onProductionCallsEnabled(CallsSettings.Profile profile, boolean enabled) {
        if (!enabled || AppPermissionProvisioner.hasBluetoothCallAccess(this)) {
            pendingBluetoothProfiles.remove(profile);
            CallsSettings.setEnabled(preferences, profile, enabled);
        } else {
            pendingBluetoothProfiles.add(profile);
            requestBluetoothPermission();
        }
        if (productionUi != null) productionUi.refreshCallsSettings();
        refreshProductionHeader();
    }

    private void requestBluetoothPermission() {
        if (bluetoothPermissionInFlight || pendingBluetoothProfiles.isEmpty()) return;
        if (AppPermissionProvisioner.hasBluetoothCallAccess(this)) {
            finishBluetoothPermissionRequest();
            return;
        }
        if (!runtimePermissionWindowAvailable()) return;
        bluetoothPermissionInFlight = true;
        try {
            if (android.os.Build.VERSION.SDK_INT < 31
                    || preferences.getBoolean(PREF_BLUETOOTH_PERMISSION_ASKED, false)
                    && !shouldShowRequestPermissionRationale(Manifest.permission.BLUETOOTH_CONNECT)) {
                // A permanently denied permission can only be changed in Android settings.
                startActivityForResult(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())), BLUETOOTH_PERMISSION_REQUEST);
                record("calls_bluetooth_permission_settings_opened");
            } else {
                preferences.edit().putBoolean(PREF_BLUETOOTH_PERMISSION_ASKED, true).apply();
                requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        BLUETOOTH_PERMISSION_REQUEST);
                record("calls_bluetooth_permission_requested");
            }
        } catch (RuntimeException error) {
            record("calls_bluetooth_permission_failed", "error", error.toString());
            finishBluetoothPermissionRequest();
        }
    }

    private void finishBluetoothPermissionRequest() {
        bluetoothPermissionInFlight = false;
        boolean granted = AppPermissionProvisioner.hasBluetoothCallAccess(this);
        for (CallsSettings.Profile profile : pendingBluetoothProfiles) {
            CallsSettings.setEnabled(preferences, profile, granted && !shutdownRequested);
        }
        boolean requested = !pendingBluetoothProfiles.isEmpty();
        pendingBluetoothProfiles.clear();
        record("calls_bluetooth_permission_result", "granted", granted);
        if (requested && !granted && !shutdownRequested) {
            Toast.makeText(this, runtimeText(R.string.calls_bluetooth_permission), Toast.LENGTH_LONG).show();
        }
        if (productionUi != null) productionUi.refreshCallsSettings();
        refreshProductionHeader();
        advanceStartupAuthorizationFlow();
    }

    private AvasPlaybackUiState avasPlayback(String profileId) {
        AvasPlaybackUiState value = avasPlayback.get(profileId);
        return value == null ? AvasPlaybackUiState.Idle : value;
    }

    private void resetAvasPlayback() {
        avasAudition = new AvasAuditionUiState();
        avasEngineTestActive = false;
        avasEngineState = "stopped";
        avasEngineError = "";
        avasPlayback.clear();
        for (String profileId : AvasConfig.PROFILE_IDS) {
            avasPlayback.put(profileId, AvasPlaybackUiState.Idle);
        }
    }

    private void stopAvasListAudition(String sessionId) {
        if (sessionId == null || !sessionId.equals(avasAudition.getSessionId())) return;
        lastStoppedAvasSession = sessionId;
        avasAudition = new AvasAuditionUiState();
        try {
            CameraHelperService.stopAvasAudition(this, sessionId);
        } catch (RuntimeException error) {
            record("avas_audition_stop_failed", "error", error.toString());
        }
        if (!activityDestroyed && productionUi != null) productionUi.refreshAvasState();
    }

    private void requestAvasDuration(String assetId) {
        if (activityDestroyed || avasLibrary == null || avasImportExecutor.isShutdown()
                || !avasDurationRequests.add(assetId)) return;
        avasImportExecutor.execute(() -> {
            Long duration = avasLibrary.durationMillis(assetId);
            mainHandler.post(() -> {
                if (activityDestroyed) return;
                if (duration != null) avasDurations.put(assetId, duration);
                if (productionUi != null) productionUi.refreshAvasState();
            });
        });
    }

    @SuppressWarnings("deprecation")
    private void chooseAvasAudio(String profileId) {
        if (!AvasConfig.PROFILE_IDS.contains(profileId)
                || preferences.contains(PREF_AVAS_IMPORT_PROFILE)) return;
        if (!preferences.edit()
                .putString(PREF_AVAS_IMPORT_PROFILE, profileId)
                .putString(PREF_AVAS_IMPORT_PHASE, AVAS_IMPORT_PHASE_PICKER)
                .commit()) {
            Toast.makeText(this, runtimeText(R.string.runtime_avas_import_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                            "audio/*", "application/ogg", "application/octet-stream"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(open, AVAS_AUDIO_REQUEST);
        } catch (RuntimeException unavailable) {
            clearAvasImportMarker();
            if (productionUi != null) productionUi.refreshAvasState();
            Toast.makeText(this, runtimeText(R.string.runtime_avas_picker_unavailable),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void acceptAvasAudioResult(int resultCode, Intent data) {
        String profileId = preferences.getString(PREF_AVAS_IMPORT_PROFILE, null);
        if (!AvasConfig.PROFILE_IDS.contains(profileId)) return;
        if (resultCode != RESULT_OK || data == null) {
            clearAvasImportMarker();
            if (productionUi != null) productionUi.refreshAvasState();
            return;
        }
        Set<Uri> selected = new LinkedHashSet<>();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int index = 0; index < clips.getItemCount(); index++) {
                Uri uri = clips.getItemAt(index).getUri();
                if (uri != null) selected.add(uri);
            }
        }
        if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) {
            clearAvasImportMarker();
            if (productionUi != null) productionUi.refreshAvasState();
            return;
        }
        List<Uri> uris = new ArrayList<>(selected);
        avasImportInFlightProfile = profileId;
        if (!preferences.edit()
                .putString(PREF_AVAS_IMPORT_PHASE, AVAS_IMPORT_PHASE_DECODING)
                .commit()) {
            avasImportInFlightProfile = null;
            clearAvasImportMarker();
            if (productionUi != null) productionUi.refreshAvasState();
            Toast.makeText(this, runtimeText(R.string.runtime_avas_import_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        avasImportExecutor.execute(() -> {
            AvasAudioLibrary.ImportResult result = null;
            Throwable failure = null;
            try {
                result = avasLibrary.importFiles(profileId, uris);
            } catch (Throwable error) {
                failure = error;
            }
            AvasAudioLibrary.ImportResult completed = result;
            Throwable error = failure;
            mainHandler.post(() -> finishAvasImport(profileId, completed, error));
        });
    }

    private void finishAvasImport(String profileId, AvasAudioLibrary.ImportResult result,
            Throwable error) {
        int addedCount = 0;
        int failureCount = result == null ? 0 : result.failureCount;
        try {
            if (error != null || result == null) throw new IllegalStateException(
                    error == null ? "AVAS import failed" : error.getMessage(), error);
            avasLibrary.mergeImportedAssets(profileId, result);
            addedCount = result.added.size();
            CameraHelperService.configureAvas(this);
        } catch (Throwable failed) {
            record("avas_import_failed", "profile_id", profileId, "error", failed.toString());
            error = failed;
        } finally {
            if (profileId.equals(avasImportInFlightProfile)) avasImportInFlightProfile = null;
            clearAvasImportMarker();
            if (productionUi != null && !activityDestroyed) productionUi.refreshAvasState();
        }
        if (activityDestroyed || isFinishing()) return;
        String message = error == null
                ? runtimeText(R.string.runtime_avas_import_result, addedCount, failureCount)
                : runtimeText(R.string.runtime_avas_import_failed);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void clearAvasImportMarker() {
        preferences.edit()
                .remove(PREF_AVAS_IMPORT_PROFILE)
                .remove(PREF_AVAS_IMPORT_PHASE)
                .apply();
    }

    static boolean shouldClearAvasImportMarker(boolean hasSavedState, String phase,
            String persistedProfile, String inFlightProfile) {
        if (persistedProfile != null && persistedProfile.equals(inFlightProfile)) return false;
        return !hasSavedState || AVAS_IMPORT_PHASE_DECODING.equals(phase);
    }

    @Override
    public void onProductionUiAction(BydExtendUiAction action) {
        if (action instanceof BydExtendUiAction.Navigate) {
            selectProductionTab(rootTabToLegacy(
                    ((BydExtendUiAction.Navigate) action).getTab()));
            refreshProductionHeader();
            return;
        }
        if (action instanceof BydExtendUiAction.SetLanguage) {
            if (productionReverseEditor != null) productionReverseEditor.setLanguage(runtimeLanguage());
        } else if (action instanceof BydExtendUiAction.LearnCameraButton) {
            beginCameraButtonLearning(((BydExtendUiAction.LearnCameraButton) action).getAction());
        } else if (action instanceof BydExtendUiAction.ResetCameraButton) {
            CameraButtonBindings.Action target =
                    ((BydExtendUiAction.ResetCameraButton) action).getAction();
            if (cameraButtonActionEnabled(target)) {
                cancelReverseButtonLearning();
                CameraButtonBindings.reset(preferences, target);
                if (target == CameraButtonBindings.Action.AvasEngine) CameraHelperService.configureAvas(this);
                productionUi.reload();
            }
        } else if (action instanceof BydExtendUiAction.SetCameraButtonSource) {
            BydExtendUiAction.SetCameraButtonSource change =
                    (BydExtendUiAction.SetCameraButtonSource) action;
            if (cameraButtonActionEnabled(change.getAction())) {
                cancelReverseButtonLearning();
                CameraButtonBindings.Binding candidate = CameraButtonBindings.loadForSource(
                        preferences, change.getAction(), change.getSource());
                if (CameraButtonBindings.hasRescueConflict(preferences, change.getAction(), candidate)) {
                    Toast.makeText(this, runtimeText(R.string.calls_binding_conflict),
                            Toast.LENGTH_LONG).show();
                } else {
                    CameraButtonBindings.save(preferences, change.getAction(), candidate);
                    productionUi.reload();
                }
            }
        } else if (action instanceof BydExtendUiAction.SetCameraButtonPress) {
            BydExtendUiAction.SetCameraButtonPress change = (BydExtendUiAction.SetCameraButtonPress) action;
            if (cameraButtonActionEnabled(change.getAction())) {
                cancelReverseButtonLearning();
                CameraButtonBindings.Binding before = CameraButtonBindings.load(
                        preferences, change.getAction());
                CameraButtonBindings.Binding candidate = new CameraButtonBindings.Binding(
                        before.source, before.keyCode, change.getPress());
                if (CameraButtonBindings.supports(change.getAction(), candidate.source,
                        candidate.press)
                        && CameraButtonBindings.hasRescueConflict(preferences, change.getAction(), candidate)) {
                    Toast.makeText(this, runtimeText(R.string.calls_binding_conflict),
                            Toast.LENGTH_LONG).show();
                } else if (CameraButtonBindings.supports(change.getAction(), candidate.source,
                        candidate.press)) {
                    CameraButtonBindings.save(preferences, change.getAction(), candidate);
                    productionUi.reload();
                }
            }
        } else if (action instanceof BydExtendUiAction.SetProfileBorder) {
            saveProductionProfileBorder((BydExtendUiAction.SetProfileBorder) action);
        } else if (action instanceof BydExtendUiAction.Toggle) {
            handleProductionToggle((BydExtendUiAction.Toggle) action);
        } else if (action instanceof BydExtendUiAction.CommitNumber) {
            handleProductionNumber((BydExtendUiAction.CommitNumber) action);
        } else if (action instanceof BydExtendUiAction.Select) {
            handleProductionSelection((BydExtendUiAction.Select) action);
        } else if (action instanceof BydExtendUiAction.SetProfileGeometry) {
            BydExtendUiAction.SetProfileGeometry resize = (BydExtendUiAction.SetProfileGeometry) action;
            saveProductionBlindGeometry(preferences, resize.getProfile(), resize.getGeometry(),
                    resize.getDisplayTarget());
            CameraHelperService.cameraSettingsChanged(this);
        } else if (action instanceof BydExtendUiAction.MoveProfile) {
            BydExtendUiAction.MoveProfile move = (BydExtendUiAction.MoveProfile) action;
            saveProductionProfilePosition(move.getProfile(), move.getX(), move.getY(), move.getDisplayTarget());
        } else if (action instanceof BydExtendUiAction.Run) {
            handleProductionCommand((BydExtendUiAction.Run) action);
        }
        refreshProductionHeader();
    }

    @Override
    public void requestProductionMirrorOverlayPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) return;
        if (!startupOverlayPermissionFlow.beginManualOverlayPermissionRequest(
                false, startupOverlayPermissionBlocked())) return;
        launchOverlayPermissionSettings(false);
    }

    private void launchOverlayPermissionSettings(boolean startup) {
        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                record(startup ? "startup_overlay_permission_opened"
                                : "mirror_overlay_permission_opened",
                        "action", android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                return;
            }
        } catch (RuntimeException unavailable) {
            record(startup ? "startup_overlay_permission_unavailable"
                            : "mirror_overlay_permission_unavailable",
                    "error", unavailable.toString());
        }
        startupOverlayPermissionFlow.finishOverlaySettings();
        refreshProductionHeader();
        if (!startup) {
            Toast.makeText(this, runtimeText(R.string.runtime_overlay_permission_unavailable),
                    Toast.LENGTH_LONG).show();
        }
        advanceStartupAuthorizationFlow();
    }

    private void finishOverlaySettingsReturn() {
        if (!hasWindowFocus()) return;
        if (!startupOverlayPermissionFlow.finishOverlaySettings()) return;
        boolean granted = android.provider.Settings.canDrawOverlays(this);
        record("overlay_permission_settings_returned", "granted", granted);
        refreshProductionHeader();
    }

    private boolean startupOverlayPermissionBlocked() {
        return !activityResumed || !hasWindowFocus() || activityDestroyed || isFinishing()
                || shutdownRequested || cameraPermissionPending
                || microphonePermissionInFlight || bluetoothPermissionInFlight || backgroundStartSettingsPending()
                || weatherLocationPermissionInFlight
                || adbAuthPending || adbAuthorizationStartScheduled
                || startupOverlayPermissionFlow.foregroundAuthorizationInFlight()
                || settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress || updateDownloadInFlight
                || settingsTransferDialog != null || legacyImportOfferDialog != null
                || productionUi != null && productionUi.getState().getDialog() != null
                || !startupOverlayPermissionFlow.foregroundAuthorizationFinished();
    }

    private boolean runtimePermissionWindowAvailable() {
        return pendingManualAdbEvent == null && !startupOverlayPermissionBlocked()
                && !LocalAdbClient.AUTHORIZATION.pending()
                && !startupOverlayPermissionFlow.overlaySettingsInFlight();
    }

    private void requestCameraPermissionIfNeeded() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                || cameraPermissionRequested || !runtimePermissionWindowAvailable()) return;
        cameraPermissionRequested = true;
        cameraPermissionPending = true;
        record("camera_permission_requested");
        try {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } catch (RuntimeException failure) {
            cameraPermissionPending = false;
            record("camera_permission_failed", "error", failure.toString());
        }
    }

    @Override
    public void onProductionUpdateHintEnabledChanged(boolean enabled) {
        if (!enabled) UpdateHintOverlay.hide("disabled");
        refreshProductionHeader();
    }

    @Override
    public void onProductionUpdateHintAppearanceChanged(UpdateHintAppearance appearance) {
        UpdateHintOverlay.refreshAppearance();
    }

    @Override
    public void requestProductionUpdateHintOverlayPermission() {
        requestProductionMirrorOverlayPermission();
    }

    @Override
    public boolean productionUpdateHintOverlayPermissionGranted() {
        return android.provider.Settings.canDrawOverlays(this);
    }

    @Override
    public void onProductionMirrorAction(MirrorBackendAction action) {
        if (productionUi == null || action == null || shutdownRequested) return;
        if (action.getKind() == MirrorBackendActionKind.SetReturnOnAppOpen) {
            boolean restore = action.getEnabled() != null ? action.getEnabled()
                    : RearviewMirrorSettings.returnOnAppOpen(preferences);
            preferences.edit().putBoolean(
                    RearviewMirrorSettings.PREF_RETURN_ON_APP_OPEN, restore).apply();
            productionUi.reload();
            return;
        }
        if (action.getKind() == MirrorBackendActionKind.HideUntilOpen) {
            RearviewMirrorSettings.setHidden(preferences, true);
            CameraHelperService.mirrorSettingsChanged(this);
            refreshProductionMirrorState();
            return;
        }
        if (action.getKind() == MirrorBackendActionKind.SetSuppressWhilePanorama) {
            boolean suppress = action.getEnabled() != null ? action.getEnabled()
                    : productionUi.getState().getMirror().getSuppressWhilePanorama();
            preferences.edit().putBoolean(
                    RearviewMirrorSettings.PREF_SUPPRESS_WHILE_PANORAMA, suppress).apply();
            CameraHelperService.mirrorSettingsChanged(this);
            productionUi.reload();
            return;
        }
        RearviewMirrorSettings model = new RearviewMirrorSettings(preferences);
        RearviewMirrorSettings.Settings before = model.load();
        boolean front = before.activeFront();
        if (action.getFront() != null && action.getKind() != MirrorBackendActionKind.SetSource
                && action.getFront() != front) return;
        MirrorUiState ui = productionUi.getState().getMirror();
        boolean enabled = before.enabled;
        int target = before.target;
        CameraPlacement placement = before.placement;
        RearviewMirrorSettings.Calibration calibration = before.calibration(front);
        RearviewMirrorSettings.Calibration preset = before.preset(front);
        boolean integrated = before.frontIntegrated;
        boolean showFront = before.showFront;
        boolean copyRearToFront = false;
        int border = before.borderDp;
        int color = before.borderArgb;
        boolean hidden = before.manualHidden;
        try {
            switch (action.getKind()) {
                case SetFrontIntegration:
                    integrated = Boolean.TRUE.equals(action.getEnabled());
                    showFront = integrated && showFront;
                    break;
                case SetSource:
                    showFront = integrated && Boolean.TRUE.equals(action.getFront());
                    break;
                case CopyRearToFront:
                    if (!integrated || front) return;
                    copyRearToFront = true;
                    break;
                case SetEnabled:
                    enabled = action.getEnabled() != null ? action.getEnabled() : ui.getEnabled();
                    break;
                case SetTarget:
                    DisplayTarget selected = action.getTarget() != null
                            ? action.getTarget() : ui.getTarget();
                    target = selected == DisplayTarget.Cluster
                            ? RearviewMirrorSettings.TARGET_CLUSTER : RearviewMirrorSettings.TARGET_TABLET;
                    placement = RearviewMirrorSettings.placement(preferences, target);
                    break;
                case SetGeometry:
                    if (action.getTarget() != null && displayTargetValue(action.getTarget()) != target) return;
                    if (action.getField() == com.byd.extend.ui.MirrorNumber.BorderWidth) {
                        border = Integer.parseInt((action.getValue() != null
                                ? action.getValue() : ui.getBorderWidth()).trim());
                        break;
                    }
                    MirrorGeometryUiState geometry = action.getGeometry() != null
                            ? action.getGeometry() : ui.getPlacement();
                    if (action.getField() != null && action.getValue() != null) {
                        placement = editMirrorPlacement(before.placement, action.getField(), action.getValue());
                    } else {
                        placement = CameraPlacement.bounded(mirrorFraction(geometry.getX()),
                                mirrorFraction(geometry.getY()), mirrorFraction(geometry.getWidth()),
                                mirrorFraction(geometry.getHeight())).roundedTenths();
                    }
                    break;
                case SetBorder:
                    border = Math.round(mirrorNumber(ui.getBorderWidth()));
                    color = action.getBorderArgb() != null ? action.getBorderArgb() : ui.getBorderArgb();
                    break;
                case SetCalibration:
                    calibration = mergeMirrorCalibration(calibration, action);
                    break;
                case SavePreset:
                    preset = calibration;
                    break;
                case LoadPreset:
                    if (preset == null) {
                        recordOperationFeedback(runtimeText(R.string.runtime_preset_missing));
                        return;
                    }
                    calibration = preset;
                    break;
                case ResetPlacement:
                    placement = RearviewMirrorSettings.defaultPlacement(target);
                    break;
                case ResetOriginal:
                    calibration = mirrorCalibrationWithCrop(calibration, false,
                            RearviewMirrorSettings.defaultCalibration(front).raw);
                    break;
                case ResetCorrection:
                    RearviewMirrorSettings.Calibration defaultCalibration =
                            RearviewMirrorSettings.defaultCalibration(front);
                    calibration = new RearviewMirrorSettings.Calibration(calibration.raw,
                            defaultCalibration.corrected, defaultCalibration.enabled,
                            defaultCalibration.fovDegrees, defaultCalibration.projection,
                            calibration.mirrored, calibration.rotationDegrees,
                            calibration.rotationMode);
                    break;
                case ResetOutput:
                    RearviewMirrorSettings.Calibration outputDefaults = RearviewMirrorSettings.defaultCalibration(front);
                    calibration = new RearviewMirrorSettings.Calibration(calibration.raw,
                            calibration.corrected, calibration.enabled, calibration.fovDegrees,
                            calibration.projection, outputDefaults.mirrored, outputDefaults.rotationDegrees,
                            outputDefaults.rotationMode, calibration.strengthPercent, calibration.preciseFovDegrees);
                    break;
            }
            CameraBorderSettings.Border requestedBorder =
                    action.getKind() == MirrorBackendActionKind.SetBorder
                            || action.getKind() == MirrorBackendActionKind.SetGeometry
                            && action.getField() == com.byd.extend.ui.MirrorNumber.BorderWidth
                    ? new CameraBorderSettings.Border(border, color) : null;
            RearviewMirrorSettings.Settings updated = before.withCalibration(front, calibration)
                    .withPreset(front, preset);
            if (copyRearToFront) updated = updated.withCalibration(true, before.calibration);
            model.save(new RearviewMirrorSettings.Settings(enabled, target, placement, updated.calibration,
                    updated.preset, border, color, hidden, integrated, showFront,
                    updated.frontCalibration, updated.frontPreset));
            if (action.getKind() == MirrorBackendActionKind.SetBorder
                    || action.getKind() == MirrorBackendActionKind.SetGeometry
                    && action.getField() == com.byd.extend.ui.MirrorNumber.BorderWidth) {
                CameraBorderSettings.writeMirror(preferences, front, requestedBorder);
            } else if (action.getKind() == MirrorBackendActionKind.SavePreset) {
                CameraBorderSettings.saveMirrorPreset(preferences, front);
            } else if (action.getKind() == MirrorBackendActionKind.LoadPreset) {
                CameraBorderSettings.loadMirrorPreset(preferences, front);
            } else if (copyRearToFront) {
                CameraBorderSettings.writeMirror(
                        preferences, true, CameraBorderSettings.forMirror(preferences, false));
            }
            if (action.getKind() == MirrorBackendActionKind.SavePreset
                    || action.getKind() == MirrorBackendActionKind.LoadPreset) {
                recordOperationFeedback(runtimeText(action.getKind() == MirrorBackendActionKind.SavePreset
                        ? R.string.runtime_preset_saved : R.string.runtime_preset_loaded_short));
            }
            transientProfilePreviewId = null;
            transientProfilePreviewFov = null;
            transientProfilePreviewStrength = null;
            transientProfilePreviewRotation = null;
            notifyProductionProfileChanged(CameraProfileId.Mirror.INSTANCE);
            refreshProductionMirrorState();
        } catch (IllegalArgumentException invalid) {
            record("camera_validation_rejected", "profile", "mirror",
                    "reason", invalid.getMessage());
            productionUi.reload();
        }
    }

    @Override
    public String onProductionMirrorPreview(MirrorBackendAction action) {
        if (action == null) return null;
        try {
            if (action.getKind() == MirrorBackendActionKind.SetCalibration) {
                boolean front = RearviewMirrorSettings.activeFront(preferences);
                if (action.getFront() != null && action.getFront() != front) return null;
                RearviewMirrorSettings.Calibration c = mergeMirrorCalibration(
                        RearviewMirrorSettings.calibration(preferences, front), action);
                applyTransientDewarpConfig(CameraProfileId.Mirror.INSTANCE,
                        CameraDewarpConfig.of(RearviewMirrorSettings.lens(front),
                                c.enabled, c.fovDegrees, c.projection, c.strengthPercent, c.preciseFovDegrees));
                applyTransientCalibrationCrop(CameraProfileId.Mirror.INSTANCE,
                        mirrorDirectCrop(c.raw, c), mirrorDirectCrop(c.corrected, c));
            }
            return action.getValue();
        } catch (IllegalArgumentException invalid) { return null; }
    }

    private static float mirrorNumber(String text) {
        float value = Float.parseFloat(text.trim().replace(',', '.'));
        if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite mirror value");
        return value;
    }

    private static float mirrorFraction(String text) { return mirrorNumber(text) / 100.0f; }

    static CameraPlacement editMirrorPlacement(CameraPlacement before,
            com.byd.extend.ui.MirrorNumber field, String value) {
        float fraction = mirrorFraction(value);
        if (field == com.byd.extend.ui.MirrorNumber.X || field == com.byd.extend.ui.MirrorNumber.Y) {
            CameraPlacement moved = before.positionTenths(
                    field == com.byd.extend.ui.MirrorNumber.X ? fraction : before.x,
                    field == com.byd.extend.ui.MirrorNumber.Y ? fraction : before.y);
            return CameraPlacement.of(field == com.byd.extend.ui.MirrorNumber.X ? moved.x : before.x,
                    field == com.byd.extend.ui.MirrorNumber.Y ? moved.y : before.y, before.width, before.height);
        }
        if (field != com.byd.extend.ui.MirrorNumber.Width && field != com.byd.extend.ui.MirrorNumber.Height) {
            throw new IllegalArgumentException("Invalid Mirror placement field");
        }
        float size = clamp(Math.round(fraction * 1000.0f) / 1000.0f, CameraPlacement.MIN_SIZE, 1.0f);
        float width = field == com.byd.extend.ui.MirrorNumber.Width ? size : before.width;
        float height = field == com.byd.extend.ui.MirrorNumber.Height ? size : before.height;
        return CameraPlacement.of(Math.min(before.x, 1 - width), Math.min(before.y, 1 - height), width, height);
    }

    private RearviewMirrorSettings.Calibration mergeMirrorCalibration(
            RearviewMirrorSettings.Calibration before, MirrorBackendAction action) {
        if (action.getProfileField() != null || action.getCalibrationField() != null) {
            return mergeMirrorCalibration(before, action.getProfileField(),
                    action.getCalibrationField(), action.getValue());
        }
        if (action.getCalibration() != null) return mirrorCalibration(action.getCalibration());
        throw new IllegalArgumentException("Mirror calibration field required");
    }

    /** Merge one explicit edit into persisted precision, never into rounded presentation values. */
    static RearviewMirrorSettings.Calibration mergeMirrorCalibration(
            RearviewMirrorSettings.Calibration before, ProfileNumber field,
            com.byd.extend.ui.MirrorCalibrationField selection, String value) {
        if ((field == null) == (selection == null) || value == null) {
            throw new IllegalArgumentException("One Mirror calibration field required");
        }
        CameraPlacement raw = before.raw;
        CameraPlacement corrected = before.corrected;
        boolean enabled = before.enabled;
        boolean mirrored = before.mirrored;
        int fov = before.fovDegrees;
        float preciseFov = before.preciseFovDegrees;
        int strength = before.strengthPercent;
        int projection = before.projection;
        int rotation = before.rotationDegrees;
        int mode = before.rotationMode;
        if (selection != null) {
            switch (selection) {
                case CorrectionEnabled:
                case Mirrored:
                    if (!"true".equals(value) && !"false".equals(value)) {
                        throw new IllegalArgumentException("Invalid Mirror boolean");
                    }
                    if (selection == com.byd.extend.ui.MirrorCalibrationField.CorrectionEnabled) {
                        enabled = Boolean.parseBoolean(value);
                    } else mirrored = Boolean.parseBoolean(value);
                    break;
                case Projection:
                    projection = Integer.parseInt(value);
                    if (projection < 0 || projection > 1) throw new IllegalArgumentException("Invalid projection");
                    break;
                case OutputMode:
                    mode = Integer.parseInt(value);
                    if (!CameraRotation.isValidMode(mode)) throw new IllegalArgumentException("Invalid output mode");
                    break;
            }
        } else if (field == ProfileNumber.Fov) {
            preciseFov = mirrorNumber(value);
            if (preciseFov < 60 || preciseFov > 170) throw new IllegalArgumentException("Invalid FOV");
            fov = Math.round(preciseFov);
        } else if (field == ProfileNumber.Strength) {
            strength = checkedStrength(before.dewarp(CameraDewarpConfig.LENS_REAR), mirrorNumber(value));
        } else if (field == ProfileNumber.Rotation) {
            rotation = Math.round(mirrorNumber(value));
            if (rotation != CameraRotation.clamp(rotation)) throw new IllegalArgumentException("Invalid rotation");
        } else {
            boolean isCorrected = field == ProfileNumber.CorrectedX || field == ProfileNumber.CorrectedY
                    || field == ProfileNumber.CorrectedWidth || field == ProfileNumber.CorrectedHeight;
            boolean isRaw = field == ProfileNumber.OriginalX || field == ProfileNumber.OriginalY
                    || field == ProfileNumber.OriginalWidth || field == ProfileNumber.OriginalHeight;
            if (!isCorrected && !isRaw) throw new IllegalArgumentException("Invalid Mirror crop field");
            CameraPlacement crop = isCorrected ? corrected : raw;
            float x = crop.x, y = crop.y, width = crop.width, height = crop.height;
            float fraction = mirrorFraction(value);
            if (field == ProfileNumber.OriginalX || field == ProfileNumber.CorrectedX) x = fraction;
            else if (field == ProfileNumber.OriginalY || field == ProfileNumber.CorrectedY) y = fraction;
            else if (field == ProfileNumber.OriginalWidth || field == ProfileNumber.CorrectedWidth) {
                width = fraction;
                x = Math.min(x, 1.0f - width);
            } else {
                height = fraction;
                y = Math.min(y, 1.0f - height);
            }
            CameraPlacement accepted = CameraPlacement.source(x, y, width, height);
            if (isCorrected) corrected = accepted; else raw = accepted;
        }
        return new RearviewMirrorSettings.Calibration(raw, corrected, enabled, fov, projection,
                mirrored, rotation, mode, strength, preciseFov);
    }

    private static CameraPlacement mirrorCrop(CropUiState value) {
        return CameraPlacement.of(mirrorFraction(value.getX()), mirrorFraction(value.getY()),
                mirrorFraction(value.getWidth()), mirrorFraction(value.getHeight()));
    }

    private static RearviewMirrorSettings.Calibration mirrorCalibration(
            com.byd.extend.ui.CalibrationUiState value) {
        return new RearviewMirrorSettings.Calibration(mirrorCrop(value.getOriginal()),
                mirrorCrop(value.getCorrected()), value.getCorrectionEnabled(),
                Math.round(mirrorNumber(value.getFov())), value.getProjection(), value.getMirrored(),
                Math.round(mirrorNumber(value.getRotation())), value.getOutputMode(),
                value.getStrength(), mirrorNumber(value.getFov()));
    }

    private static RearviewMirrorSettings.Calibration mirrorCalibrationWithCrop(
            RearviewMirrorSettings.Calibration value, boolean corrected, CameraPlacement crop) {
        return new RearviewMirrorSettings.Calibration(corrected ? value.raw : crop,
                corrected ? crop : value.corrected, value.enabled, value.fovDegrees,
                value.projection, value.mirrored, value.rotationDegrees, value.rotationMode,
                value.strengthPercent, value.preciseFovDegrees);
    }

    private static DirectCameraCrop mirrorDirectCrop(
            CameraPlacement crop, RearviewMirrorSettings.Calibration transform) {
        return DirectCameraCrop.requireUiGeometry(crop.x, crop.y, crop.width, crop.height,
                0, CameraRotation.MODE_FIT).withOutputTransformPreservingGeometry(
                        transform.rotationDegrees, transform.rotationMode, transform.mirrored);
    }

    /** Starts global key capture only while the Accessibility filter is connected. */
    public boolean beginReverseButtonLearning() {
        return beginCameraButtonLearning(CameraButtonBindings.Action.ReverseSource);
    }

    private boolean beginCameraButtonLearning(CameraButtonBindings.Action action) {
        if (productionUi == null || activityDestroyed || shutdownRequested
                || action == null || !cameraButtonActionEnabled(action)
                || LegacySettingsImporter.blocksRuntime(this)) return false;
        cancelReverseButtonLearning();
        productionUi.showCameraButtonCaptureDialog(action);
        if (WeatherRefreshAccessibilityService.isConnected()
                && WeatherRefreshAccessibilityService.beginCameraButtonLearning(this, action)) {
            refreshProductionHeader();
            return true;
        }
        pendingButtonLearning = action;
        long epoch = ++buttonLearningEpoch;
        refreshProductionHeader();
        CameraHelperService.requestWeatherAccessibilityRecovery(this, "learning", connected -> {
            if (epoch != buttonLearningEpoch || pendingButtonLearning != action
                    || activityDestroyed || !activityResumed || shutdownRequested
                    || productionUi == null || productionUi.getState().getDialog() == null
                    || productionUi.getState().getDialog().getKind() != DialogKind.ReverseButtonCapture
                    || productionUi.getState().getDialog().getCaptureAction() != action
                    || !cameraButtonActionEnabled(action)) return;
            pendingButtonLearning = null;
            boolean started = connected && WeatherRefreshAccessibilityService
                    .beginCameraButtonLearning(this, action);
            if (!started) {
                cancelReverseButtonLearning();
                Toast.makeText(this, runtimeText(R.string.runtime_camera_button_unavailable),
                        Toast.LENGTH_LONG).show();
            }
            refreshProductionHeader();
        });
        return true;
    }

    private boolean cameraButtonActionEnabled(CameraButtonBindings.Action action) {
        if (action == null) return false;
        if (action != CameraButtonBindings.Action.AvasEngine) return true;
        try {
            return avasLibrary != null && avasLibrary.loadConfig().engine.enabled;
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    /** Cancels transient capture without changing the persisted binding. */
    public void cancelReverseButtonLearning() {
        ++buttonLearningEpoch;
        pendingButtonLearning = null;
        WeatherRefreshAccessibilityService.cancelSteeringButtonLearning();
        if (productionUi != null) productionUi.dismissReverseButtonCaptureDialog();
        refreshProductionHeader();
    }

    private void cancelReverseButtonLearningIfVisible() {
        synchronized (REVERSE_OWNER_LOCK) {
            // A destroyed Activity must not cancel a capture that a newer Activity owns.
            if (!reverseOwnerPresent || reverseOwner == null || reverseOwner.get() != this) return;
        }
        if (productionUi != null && productionUi.getState().getDialog() != null
                && productionUi.getState().getDialog().getKind()
                == DialogKind.ReverseButtonCapture) {
            // A successful first-down already leaves the modal while its UP tail is consumed;
            // dismissing that stale UI must not clear the held identity before the tail arrives.
            if (pendingButtonLearning != null || WeatherRefreshAccessibilityService.isCameraButtonLearning()) {
                cancelReverseButtonLearning();
            } else {
                productionUi.dismissReverseButtonCaptureDialog();
            }
        }
    }

    /** Clears only the persisted raw key binding and cancels any active capture. */
    public void resetReverseButtonBinding() {
        WeatherRefreshAccessibilityService.cancelSteeringButtonLearning();
        ReverseSteeringButtonPreferences.reset(preferences);
        if (productionUi != null) productionUi.dismissReverseButtonCaptureDialog();
        if (productionUi != null) productionUi.reload();
    }

    /**
     * Section navigation is a UI concern unless Calibration is entered or left.  Parameters and
     * Placement share the same live Reverse host; only the editor overlay changes visibility and
     * editability in place.
     */
    @Override
    public void onProductionCameraSectionChanged(
            RootTab tab, CameraSection previous, CameraSection next) {
        if (tab == null || previous == null || next == null || previous == next) return;
        if (previous == CameraSection.Calibration || next == CameraSection.Calibration) {
            onProductionCameraSelectionChanged(SelectionId.CameraSection);
            return;
        }
        if (tab != RootTab.Reverse) return;
        syncProductionReverseCompositionHost(next == CameraSection.Placement);
    }

    /** Reverse Parameters/Placement changes editor focus only; camera inputs stay attached. */
    @Override
    public void onProductionReverseElementFocusChanged(
            CameraSection section, ReverseElement previous, ReverseElement next) {
        if (section == null || previous == null || next == null || previous == next) return;
        if (section == CameraSection.Calibration) {
            onProductionCameraSelectionChanged(SelectionId.ReverseElement);
            return;
        }
        syncProductionReverseCompositionHost(section == CameraSection.Placement);
    }

    /** Persists one visibility bit and updates the already-open native Reverse overlay in place. */
    @Override
    public void onProductionReverseVisibilityChanged(ReverseElement element, boolean visible) {
        if (element == null) return;
        int pane = reverseElementIndex(element);
        if (pane == ReverseCameraLayout.WIDGET_PANE_ID) {
            ReverseCameraController.saveWidgetVisible(preferences, visible);
        } else if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            // Background is a fixed pane; never route it through camera-pane bit lookup.
            preferences.edit().putBoolean(
                    ReverseCameraController.PREF_BACKGROUND_VISIBLE, visible).apply();
        } else if (ReverseCameraLayout.REAR_CAMERA_INDEX <= pane
                && pane <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
            ReverseCameraController.saveVisibility(preferences, pane, visible);
        } else {
            return;
        }
        if (reverseCameraPreview != null) {
            reverseCameraPreview.applyVisibility(
                    ReverseCameraController.loadVisibilityMask(preferences));
            reverseCameraPreview.setWidgetVisible(
                    ReverseCameraController.loadWidgetVisible(preferences));
        }
        requestReverseVisibilityUpdate();
    }

    private void syncProductionReverseCompositionHost(boolean editable) {
        if (productionReverseEditor == null || reverseCameraPreview == null) return;
        syncProductionReverseDisplayTarget(productionUi == null ? null
                : productionUi.getState().getReverse().getSelectedTarget());
        productionReverseEditorEditable = editable;
        productionReverseEditor.setEditable(editable);
        productionReverseEditor.setVisibility(editable ? View.VISIBLE : View.GONE);
        productionReverseEditor.setLayoutModel(reverseCameraLayout);
        if (productionUi != null) {
            productionReverseEditor.setSelectedCameraSilently(reverseElementIndex(
                    productionUi.getState().getReverse().getSelectedElement()));
            boolean front = productionUi.getState().getReverse().getShowFront();
            int mode = front ? ReverseSideSelectorView.MODE_FRONT : ReverseSideSelectorView.MODE_REAR;
            if (reverseCameraPreview.sideMode() != mode) reverseCameraPreview.setSideMode(mode);
            ReverseCameraController.applyBorders(preferences, productionReverseEditor, front);
        }
        reverseCameraPreview.applyVisibility(
                ReverseCameraController.loadVisibilityMask(preferences));
        reverseCameraPreview.setWidgetVisible(
                ReverseCameraController.loadWidgetVisible(preferences));
    }

    private void syncProductionReverseDisplayTarget(com.byd.extend.ui.DisplayTarget target) {
        int displayTarget = target == com.byd.extend.ui.DisplayTarget.Cluster
                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
        if (reverseCameraPreview != null) {
            if (reverseCameraPreview.displayTarget() != displayTarget) {
                reverseCameraSurfacesReady = false;
                if (activePreview == reverseCameraPreview && requestedOpen
                        && !cameraTransition.pending()) {
                    closeCameraForTransition("reverse_display_changed");
                }
            }
            reverseCameraPreview.setDisplayTarget(displayTarget);
        }
        if (productionReverseEditor != null) {
            productionReverseEditor.setDisplayTarget(displayTarget);
            productionReverseEditor.setLanguage(runtimeLanguage());
        }
    }

    /**
     * Sends the typed visibility update to the helper's current Activity consumer group. The
     * request/generation identity is the current activity Reverse request; if no request is open,
     * persisted state is consumed by the next prepare without an unnecessary IPC.
     */
    private void requestReverseVisibilityUpdate() {
        if (helper == null || !helper.isBinderAlive()
                || !requestedOpen || activePreview != reverseCameraPreview
                || activeActivityCameraRequestId <= 0
                || activeReversePreviewDirectGenerations.length == 0) return;
        final IBinder current = helper;
        final int requestId = activeActivityCameraRequestId;
        // Each request publishes a new immutable generation array. Keep that bundle identity
        // through the IPC queue; a close/rebind makes an already queued update stale.
        final int[] generations = activeReversePreviewDirectGenerations;
        final int visibilityMask = ReverseCameraController.loadVisibilityMask(preferences);
        final boolean widgetVisible = ReverseCameraController.loadWidgetVisible(preferences);
        ipcExecutor.execute(() -> {
            if (current != helper || !requestedOpen || requestId != activeActivityCameraRequestId
                    || generations != activeReversePreviewDirectGenerations) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
                data.writeInt(requestId);
                data.writeInt(generations.length);
                for (int generation : generations) data.writeInt(generation);
                data.writeInt(visibilityMask);
                data.writeInt(widgetVisible ? 1 : 0);
                requireTransaction(current, CameraHelperMain.TX_UPDATE_REVERSE_VISIBILITY,
                        data, reply);
                record("ipc_reply", "operation", "reverse_visibility_update",
                        "request_id", requestId, "visibility_mask", visibilityMask,
                        "widget_visible", widgetVisible, "reply", reply.readString());
            } catch (Throwable error) {
                record("ipc_error", "operation", "reverse_visibility_update",
                        "request_id", requestId, "error", error.toString());
            } finally {
                reply.recycle();
                data.recycle();
            }
        });
    }

    /**
     * Applies a slider tick to the currently displayed native hosts without writing preferences,
     * notifying the helper service, or reloading the Compose snapshot.  The controller owns the
     * transient state and sends one CommitNumber when the gesture ends.
     */
    @Override
    public String onProductionUiPreview(NumberTarget target, String value) {
        if (target == null || value == null) return null;
        final float parsed;
        try {
            parsed = Float.parseFloat(value.trim());
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (!Float.isFinite(parsed)) return null;
        if (target instanceof NumberTarget.Profile) {
            NumberTarget.Profile profileTarget = (NumberTarget.Profile) target;
            ProfileNumber field = profileTarget.getField();
            if (profileTarget.getProfile() instanceof CameraProfileId.Blind
                    && (field == ProfileNumber.X || field == ProfileNumber.Y
                            || field == ProfileNumber.Width || field == ProfileNumber.Height)) {
                CameraPlacement p = loadProductionBlindPlacement(blindProfile(
                        (CameraProfileId.Blind) profileTarget.getProfile()));
                float minimum = field == ProfileNumber.Width || field == ProfileNumber.Height ? 5 : 0;
                float maximum = field == ProfileNumber.X ? (1 - p.width) * 100
                        : field == ProfileNumber.Y ? (1 - p.height) * 100 : 100;
                float accepted = Math.round(clamp(parsed, minimum, maximum) * 10) / 10.0f;
                return Float.toString(accepted);
            }
            int minimum;
            int maximum;
            if (field == ProfileNumber.Fov || field == ProfileNumber.Strength) {
                try {
                    if (field == ProfileNumber.Fov && (parsed < 60 || parsed > 170)) return null;
                    if (field == ProfileNumber.Strength) checkedStrength(
                            loadProductionCalibrationDewarp(profileTarget.getProfile()), parsed);
                    applyTransientProductionProfileNumber(profileTarget.getProfile(), field, parsed);
                    return field == ProfileNumber.Strength ? Integer.toString((int) parsed) : Float.toString(parsed);
                } catch (IllegalArgumentException invalid) { return null; }
            }
            if (field == ProfileNumber.Size) {
                minimum = 5;
                maximum = 60;
            } else if (field == ProfileNumber.Fov) {
                minimum = CameraDewarpConfig.MIN_FOV_DEGREES;
                maximum = CameraDewarpConfig.MAX_FOV_DEGREES;
            } else if (field == ProfileNumber.Rotation) {
                minimum = CameraRotation.MIN_DEGREES;
                maximum = CameraRotation.MAX_DEGREES;
            } else {
                return null;
            }
            int accepted = Math.round(parsed);
            if (accepted < minimum || accepted > maximum) return null;
            try {
                applyTransientProductionProfileNumber(
                        profileTarget.getProfile(), field, accepted);
            } catch (IllegalArgumentException invalid) {
                return null;
            }
            return Integer.toString(accepted);
        }
        if (target instanceof NumberTarget.Output) {
            NumberTarget.Output output = (NumberTarget.Output) target;
            int minimum = 0;
            int maximum = output.getField() == OutputNumber.CornerRadius ? 48 : 100;
            int accepted = Math.round(parsed);
            if (accepted < minimum || accepted > maximum) return null;
            int radius = output.getField() == OutputNumber.CornerRadius
                    ? accepted : transientCornerRadiusDp != null
                            ? transientCornerRadiusDp
                            : BlindSpotOverlayController.readCornerRadius(preferences);
            int transparency = output.getField() == OutputNumber.Transparency
                    ? accepted : transientTransparencyPercent != null
                            ? transientTransparencyPercent
                            : BlindSpotOverlayController.readTransparencyPercent(preferences);
            // A detached helper is not a validation failure: Compose must still accept the
            // normalized tick so the eventual CommitNumber can persist it.  Send the transient
            // visual update only when the current helper binder is already bound/alive.
            enqueueTransientCameraVisuals(radius, transparency);
            if (output.getField() == OutputNumber.CornerRadius) {
                transientCornerRadiusDp = accepted;
                refreshProductionBorderOverlays();
            } else transientTransparencyPercent = accepted;
            return Integer.toString(accepted);
        }
        return null;
    }

    private boolean enqueueTransientCameraVisuals(int cornerRadiusDp, int transparencyPercent) {
        IBinder current = helper;
        if (current == null || !current.isBinderAlive()) return false;
        ipcExecutor.execute(() -> {
            if (helper != current || !current.isBinderAlive()) return;
            Parcel data = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
                data.writeInt(cornerRadiusDp);
                data.writeInt(transparencyPercent);
                requireTransaction(current, CameraHelperMain.TX_UPDATE_VISUALS, data, reply);
                record("ipc_reply", "operation", "camera_visuals_preview",
                        "reply", reply.readString(), "corner_radius_dp", cornerRadiusDp,
                        "transparency_percent", transparencyPercent);
            } catch (Throwable error) {
                record("ipc_error", "operation", "camera_visuals_preview",
                        "error", error.toString());
            } finally {
                reply.recycle();
                data.recycle();
            }
        });
        return true;
    }

    private void applyTransientProductionProfileNumber(
            CameraProfileId id, ProfileNumber field, float value) {
        if (field == ProfileNumber.Size) {
            // Compose's placement geometry is derived from the accepted state value; no native
            // camera transform or preference write is needed for this field.
            return;
        }
        if (field == ProfileNumber.Fov || field == ProfileNumber.Strength) {
            transientProfilePreviewId = id;
            if (field == ProfileNumber.Fov) transientProfilePreviewFov = value;
            else transientProfilePreviewStrength = (int) value;
            CameraDewarpConfig current = loadProductionCalibrationDewarp(id);
            if (transientProfilePreviewFov != null) current = current.withHorizontalFov(transientProfilePreviewFov);
            if (transientProfilePreviewStrength != null) current = current.withStrength(transientProfilePreviewStrength);
            applyTransientDewarpConfig(id, current);
            return;
        }
        if (field != ProfileNumber.Rotation) return;
        transientProfilePreviewId = id;
        transientProfilePreviewRotation = Math.round(value);
        applyTransientOutputRotation(id, Math.round(value));
    }

    private void clearTransientProfilePreview(CameraProfileId id, ProfileNumber field) {
        if (id == null || !id.equals(transientProfilePreviewId)) return;
        if (field == ProfileNumber.Fov) transientProfilePreviewFov = null;
        else if (field == ProfileNumber.Strength) transientProfilePreviewStrength = null;
        else if (field == ProfileNumber.Rotation) transientProfilePreviewRotation = null;
        if (transientProfilePreviewFov == null && transientProfilePreviewStrength == null && transientProfilePreviewRotation == null) {
            transientProfilePreviewId = null;
        }
    }

    private void applyTransientDewarpConfig(
            CameraProfileId id, CameraDewarpConfig config) {
        CameraProfileId selected = selectedProductionProfile();
        if (cameraPreview != null && id.equals(selected)) {
            cameraPreview.applyDewarpConfig(config);
        }
        if (calibrationPreview != null && id.equals(calibrationHostProfile)) {
            calibrationPreview.applyDewarpConfig(config);
        }
    }

    private void applyTransientOutputRotation(CameraProfileId id, int rotation) {
        if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            boolean front = reverse.getSource() == ReverseSource.Front;
            DirectCameraCrop[] crops = reverseRotationPreviewCrops(
                    preferences, index, front, rotation);
            applyTransientCalibrationCrop(id, crops[0], crops[1]);
            return;
        }
        DirectCameraCrop raw;
        DirectCameraCrop corrected;
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            raw = DirectCameraCrop.load(preferences, profile);
            corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            raw = DirectCameraCrop.load(preferences, profile);
            corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw);
        } else {
            return;
        }
        raw = raw.withOutputTransformPreservingGeometry(
                rotation, raw.rotationMode, raw.mirrorHorizontally);
        corrected = corrected.withOutputTransformPreservingGeometry(
                rotation, corrected.rotationMode, corrected.mirrorHorizontally);
        applyTransientCalibrationCrop(id, raw, corrected);
    }

    /** Live rotation preserves independent RAW fallback and Corrected source geometries. */
    static DirectCameraCrop[] reverseRotationPreviewCrops(
            SharedPreferences settings, int cameraIndex, boolean front, int rotation) {
        ReverseCameraLayout layout = front
                ? ReverseCameraController.loadFrontRawLayout(settings)
                : ReverseCameraController.loadRawLayout(settings);
        ReverseCameraLayout.Pane pane = layout.pane(cameraIndex);
        ReverseCameraLayout.Rect corrected = front
                ? ReverseCameraController.loadFrontCorrectedSourceCrop(settings, cameraIndex)
                : ReverseCameraController.loadCorrectedSourceCrop(settings, cameraIndex,
                        ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop));
        return new DirectCameraCrop[]{
                directCrop(pane.sourceCrop, pane).withOutputTransformPreservingGeometry(
                        rotation, pane.displayMode, pane.mirrorHorizontally),
                directCrop(corrected, pane).withOutputTransformPreservingGeometry(
                        rotation, pane.displayMode, pane.mirrorHorizontally)
        };
    }

    private void applyTransientCalibrationCrop(
            CameraProfileId id, DirectCameraCrop raw, DirectCameraCrop corrected) {
        CameraDewarpConfig dewarp = loadProductionCalibrationDewarp(id);
        CameraProfileId selected = selectedProductionProfile();
        DirectCameraCrop previewRaw = raw;
        DirectCameraCrop previewActive = dewarp.enabled ? corrected : raw;
        if (cameraPreview != null && id.equals(selected)) {
            cameraPreview.applyRawFallbackCrop(previewRaw);
            applyDewarpSourceRoi(cameraPreview, previewRaw);
            cameraPreview.applyDirectCameraCrop(previewActive);
        }
        if (calibrationPreview != null && id.equals(calibrationHostProfile)) {
            calibrationPreview.applyRawFallbackCrop(previewRaw);
            applyDewarpSourceRoi(calibrationPreview, previewRaw);
            calibrationPreview.applyDirectCameraCrop(previewActive);
        }
    }

    @Override
    public CameraDisplayGeometry productionDisplayGeometry(DisplayTarget target) {
        int targetId = target == DisplayTarget.Cluster
                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
        int[] size = CameraDisplayTarget.displaySize(this, targetId);
        int width = Math.max(1, size[0]);
        int height = Math.max(1, size[1]);
        if (target == DisplayTarget.Cluster) {
            return new CameraDisplayGeometry(width, height, 0, 0, 0, 0, target);
        }
        return new CameraDisplayGeometry(
                width, height,
                dp(16), dp(36), dp(16), dp(88), target);
    }

    @Override
    public boolean productionMirrorOverlayPermissionGranted() {
        return android.provider.Settings.canDrawOverlays(this);
    }

    @Override
    public boolean productionMirrorClusterAvailable() {
        return CameraDisplayTarget.resolve(this, CameraDisplayTarget.CLUSTER) != null;
    }

    private void selectProductionTab(int tab) {
        if ((settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress)
                && !(legacyRuntimeBlocked && tab == TAB_SETTINGS)) return;
        if (!isValidTab(tab)) tab = TAB_GUARD;
        int previous = selectedTab;
        if (previous == tab) return;
        cancelCalibrationCropInput();
        cancelReverseCropInput();
        boolean transitionStarted = false;
        if (requestedOpen || cameraHandoffPending) {
            if (isAutoPreviewTab(tab)) armResumeAutoPreview();
            transitionStarted = closeCameraForTransition("camera_tab_changed");
        } else if (!cameraTransition.pending()) closeCamera("camera_tab_changed");
        releaseProductionHostsForTab(previous);
        selectedTab = tab;
        preferences.edit().putInt("selected_tab", tab).apply();
        invalidStockSurfaceRetryUsed = false;
        productionPreviewRetryUsed = false;
        clearResumeAutoPreview();
        retryStockViewpoint = -1;
        retryStockDebug = false;
        if (isAutoPreviewTab(tab)) {
            armResumeAutoPreview();
            if (!transitionStarted) {
                renewSelectedPreviewInputForTabSwitch();
                resumeSelectedCameraPreview();
            }
        }
        if (isAutoPreviewTab(tab) || tab == TAB_CAMERA_DEBUG) requestCameraPermissionIfNeeded();
    }

    private static int rootTabToLegacy(RootTab tab) {
        if (tab == RootTab.Blind) return TAB_CAMERAS;
        if (tab == RootTab.Parking) return TAB_PARKING_CAMERAS;
        if (tab == RootTab.Reverse) return TAB_REVERSE_CAMERAS;
        if (tab == RootTab.Mirror) return TAB_REARVIEW_MIRROR;
        if (tab == RootTab.Settings) return TAB_SETTINGS;
        if (tab == RootTab.Debug) return TAB_CAMERA_DEBUG;
        return TAB_GUARD;
    }

    private void handleProductionToggle(BydExtendUiAction.Toggle action) {
        ToggleTarget target = action.getTarget();
        boolean value = action.getValue();
        if (target instanceof ToggleTarget.Simple) {
            ToggleId id = ((ToggleTarget.Simple) target).getId();
            if (id == ToggleId.Guard) {
                preferences.edit().putBoolean("guard_enabled", value).apply();
                pushGuardConfigFromPreferences();
                updateControls();
            } else if (id == ToggleId.HazardThanks) {
                preferences.edit().putBoolean(HazardThanksSettings.ENABLED, value).apply();
                CameraHelperService.configureHazardThanks(this);
            } else if (id == ToggleId.Music) {
                preferences.edit().putBoolean("music_visualizer_enabled", value).apply();
                onMusicEnabledChanged(value);
            } else if (id == ToggleId.MusicCaptureFocusOnOpen) {
                if (!preferences.getBoolean("music_visualizer_enabled", false)) return;
                preferences.edit().putBoolean("music_capture_focus_on_open", value).apply();
                onMusicOptionsChanged();
            } else if (id == ToggleId.MusicEngineVisualization) {
                if (!preferences.getBoolean("music_visualizer_enabled", false)) return;
                preferences.edit().putBoolean("music_engine_visualization_enabled", value).apply();
                onMusicOptionsChanged();
            } else if (id == ToggleId.Weather) {
                onProductionWeatherEnabled(value);
            } else if (id == ToggleId.AutoStart) {
                onSettingsAutoStartChanged(value);
            } else if (id == ToggleId.AutomaticUpdate) {
                preferences.edit().putBoolean(UpdateHintRuntime.PREF_AUTO_CHECK, value).apply();
            } else if (id == ToggleId.ExtendedLogs) {
                preferences.edit().putBoolean(DiagnosticLogPolicy.PREF_ENABLED, value).apply();
                DiagnosticLogPolicy.configure(value);
                CameraHelperService.diagnosticSettingsChanged(this);
            } else if (id == ToggleId.RecordLogcat) {
                preferences.edit().putBoolean(ContinuousLogcatRecorder.PREF_ENABLED, value).apply();
                CameraHelperService.diagnosticSettingsChanged(this);
            } else if (id == ToggleId.ReverseEnabled) {
                preferences.edit().putBoolean(ReverseCameraController.PREF_ENABLED, value).apply();
                CameraHelperService.reverseCameraSettingsChanged(this);
            } else if (id == ToggleId.ReverseSwitchByGear) {
                if (!ReverseCameraController.hasAnyFrontIntegration(preferences)) return;
                preferences.edit().putBoolean(ReverseCameraController.PREF_SWITCH_BY_GEAR, value).apply();
                CameraHelperService.reverseCameraSettingsChanged(this);
            } else if (id == ToggleId.AvmShowRaw) {
                preferences.edit().putBoolean("debug_avm_show_raw", value).apply();
                applyDebugPreviewMode();
            } else if (id == ToggleId.AvmDewarp) {
                preferences.edit().putBoolean("debug_avm_dewarp", value).apply();
                if (requestedOpen && activePreview == debugPreview && activeCameraViewpoint >= 0) {
                    openStockAvmNow(activeCameraViewpoint, true);
                }
            }
            return;
        }
        if (target instanceof ToggleTarget.Blind) {
            ToggleTarget.Blind blind = (ToggleTarget.Blind) target;
            if (blind.getId() == ToggleId.BlindHoldAfterShortTurn) {
                String key = blind.getGroup() == CameraGroup.Front
                        ? BlindSpotOverlayController.PREF_FRONT_HOLD_AFTER_SHORT_TURN
                        : BlindSpotOverlayController.PREF_REAR_HOLD_AFTER_SHORT_TURN;
                preferences.edit().putBoolean(key, value).apply();
                CameraHelperService.cameraTriggerSettingsChanged(this);
                return;
            }
            if (blind.getId() == ToggleId.BlindSuppressWhilePanorama) {
                String key = blind.getGroup() == CameraGroup.Front
                        ? BlindSpotOverlayController.PREF_FRONT_SUPPRESS_WHILE_PANORAMA
                        : BlindSpotOverlayController.PREF_REAR_SUPPRESS_WHILE_PANORAMA;
                preferences.edit().putBoolean(key, value).apply();
                CameraHelperService.cameraTriggerSettingsChanged(this);
                return;
            }
            String key = blind.getId() == ToggleId.BlindRear
                    ? BlindSpotOverlayController.PREF_ENABLED
                    : blind.getId() == ToggleId.BlindFront
                            ? BlindSpotOverlayController.PREF_FRONT_ENABLED
                            : blind.getId() == ToggleId.BlindSharpTurn
                                    ? BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED
                                    : blind.getId() == ToggleId.BlindObjectOnly
                                            ? BlindSpotOverlayController.PREF_REAR_BSD_ONLY
                                            : BlindSpotOverlayController.PREF_FRONT_TURN_REQUIRED;
            preferences.edit().putBoolean(key, value).apply();
            CameraHelperService.cameraSettingsChanged(this);
            return;
        }
        if (target instanceof ToggleTarget.Parking) {
            ToggleTarget.Parking parking = (ToggleTarget.Parking) target;
            ParkingCameraProfile profile = parking.getView() == null
                    ? null : ParkingCameraProfile.of(parking.getView().ordinal());
            ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
            if (parking.getId() == ToggleId.ParkingView && profile != null) {
                settings.setRule(profile, settings.rule(profile).withEnabled(value));
            } else if (parking.getId() == ToggleId.ParkingAddCentral && profile != null) {
                settings.setRule(profile, settings.rule(profile).withAddCentral(value));
            } else if (parking.getId() == ToggleId.ParkingAlongsideReverse) {
                settings.setAllowDuringReverse(value);
            } else if (parking.getId() == ToggleId.ParkingSynchronizeSize) {
                preferences.edit().putBoolean("parking_camera_scale_sync", value).apply();
            }
            notifyParkingSettingsChanged();
            return;
        }
        if (target instanceof ToggleTarget.ReverseSource) {
            ToggleTarget.ReverseSource guidance = (ToggleTarget.ReverseSource) target;
            boolean front = guidance.getSource() == ReverseSource.Front;
            if (guidance.getId() == ToggleId.ReverseDirectionGuidelines) {
                ReverseGuidanceSettings.setDirectionGuidelinesEnabled(preferences, front, value);
            } else if (guidance.getId() == ToggleId.ReverseCameraShiftWithSteering) {
                ReverseGuidanceSettings.setCameraShiftWithSteeringEnabled(preferences, front, value);
            } else {
                return;
            }
            CameraHelperService.reverseCameraSettingsChanged(this);
            applyProductionReverseState();
            return;
        }
        if (target instanceof ToggleTarget.Reverse) {
            ToggleTarget.Reverse reverse = (ToggleTarget.Reverse) target;
            int pane = reverseElementIndex(reverse.getElement());
            if (reverse.getId() == ToggleId.ReverseElementVisible) {
                // The typed controller path persists one bit and performs one targeted native
                // update.  Keep this fallback path equivalent without triggering a full reload.
                onProductionReverseVisibilityChanged(reverse.getElement(), value);
                return;
            } else if (reverse.getId() == ToggleId.ReverseFrontIntegration && pane > 0) {
                ReverseCameraController.saveFrontIntegrated(preferences, pane, value);
            }
            CameraHelperService.reverseCameraSettingsChanged(this);
            applyProductionReverseState();
            return;
        }
        if (target instanceof ToggleTarget.Profile) {
            ToggleTarget.Profile profile = (ToggleTarget.Profile) target;
            if (profile.getId() == ToggleId.ProfileCorrection) {
                saveProductionDewarpEnabled(profile.getProfile(), value);
            } else if (profile.getId() == ToggleId.ProfileMirror) {
                saveProductionMirror(profile.getProfile(), value);
            }
        }
    }

    private void handleProductionNumber(BydExtendUiAction.CommitNumber action) {
        String text = action.getValue().trim();
        NumberTarget target = action.getTarget();
        final float value;
        try {
            value = Float.parseFloat(text);
        } catch (NumberFormatException invalid) {
            if (isCameraNumberTarget(target)) {
                recordCameraValidationRejected(target, text, "invalid_number");
            } else {
                publishSettingsFeedback(
                        runtimeText(R.string.runtime_invalid_number), StatusTone.Error);
            }
            return;
        }
        if (target instanceof NumberTarget.Guard) {
            saveProductionGuardNumber((NumberTarget.Guard) target, value);
        } else if (target == NumberTarget.WeatherInterval.INSTANCE) {
            int interval = Math.round(value);
            if (interval < 5 || interval > 180) return;
            preferences.edit().putInt(WeatherRuntime.PREF_INTERVAL_MINUTES, interval).apply();
            CameraHelperService.weatherSettingsChanged(this);
        } else if (target instanceof NumberTarget.Blind) {
            saveProductionBlindNumber((NumberTarget.Blind) target, value);
        } else if (target instanceof NumberTarget.BlindHoldDuration) {
            NumberTarget.BlindHoldDuration hold = (NumberTarget.BlindHoldDuration) target;
            int seconds = clamp(Math.round(value), BlindCameraHold.MIN_DURATION_SECONDS,
                    BlindCameraHold.MAX_DURATION_SECONDS);
            preferences.edit().putInt(hold.getGroup() == CameraGroup.Rear
                    ? BlindSpotOverlayController.PREF_REAR_HOLD_DURATION_SECONDS
                    : BlindSpotOverlayController.PREF_FRONT_HOLD_DURATION_SECONDS,
                    seconds).apply();
        } else if (target instanceof NumberTarget.Parking) {
            saveProductionParkingNumber((NumberTarget.Parking) target, value);
        } else if (target instanceof NumberTarget.Profile) {
            saveProductionProfileNumber((NumberTarget.Profile) target, value);
        } else if (target instanceof NumberTarget.ReverseGeometry) {
            saveProductionReverseGeometry((NumberTarget.ReverseGeometry) target, value);
        } else if (target instanceof NumberTarget.Output) {
            NumberTarget.Output output = (NumberTarget.Output) target;
            int rounded = Math.round(value);
            if (output.getField().name().equals("CornerRadius")) {
                rounded = clamp(rounded, 0, 48);
                preferences.edit().putInt(BlindSpotOverlayController.PREF_CORNER_RADIUS, rounded).apply();
                transientCornerRadiusDp = null;
                onCameraCornerRadiusChanged(rounded);
            } else {
                rounded = clamp(rounded, 0, 100);
                preferences.edit().putInt(BlindSpotOverlayController.PREF_TRANSPARENCY_PERCENT, rounded).apply();
                transientTransparencyPercent = null;
                onCameraTransparencyChanged(rounded);
            }
        }
    }

    private static boolean isCameraNumberTarget(NumberTarget target) {
        return target instanceof NumberTarget.Profile
                || target instanceof NumberTarget.Output
                || target instanceof NumberTarget.Blind
                || target instanceof NumberTarget.BlindHoldDuration
                || target instanceof NumberTarget.Parking
                || target instanceof NumberTarget.ReverseGeometry;
    }

    private void recordCameraValidationRejected(
            NumberTarget target, String value, String reason) {
        String profile = "unknown";
        String source = "unknown";
        String stage = "unknown";
        String field = "unknown";
        if (target instanceof NumberTarget.Profile) {
            NumberTarget.Profile profileTarget = (NumberTarget.Profile) target;
            CameraProfileId id = profileTarget.getProfile();
            profile = id == null ? "unknown" : id.toString();
            field = profileTarget.getField().name();
            stage = field.startsWith("Corrected") ? "corrected"
                    : field.startsWith("Original") ? "original"
                    : field.equals("Rotation") ? "output" : "correction";
            if (id instanceof CameraProfileId.Reverse) {
                source = ((CameraProfileId.Reverse) id).getSource().name();
            }
        } else if (target instanceof NumberTarget.Output) {
            field = ((NumberTarget.Output) target).getField().name();
            stage = "output";
        } else if (target instanceof NumberTarget.ReverseGeometry) {
            field = ((NumberTarget.ReverseGeometry) target).getField().name();
            stage = "reverse_geometry";
            profile = ((NumberTarget.ReverseGeometry) target).getElement().name();
        } else if (target instanceof NumberTarget.Blind) {
            field = ((NumberTarget.Blind) target).getField().name();
            profile = ((NumberTarget.Blind) target).getGroup().name();
            stage = "signals";
        } else if (target instanceof NumberTarget.BlindHoldDuration) {
            field = "HoldDuration";
            profile = ((NumberTarget.BlindHoldDuration) target).getGroup().name();
            stage = "signals";
        } else if (target instanceof NumberTarget.Parking) {
            field = ((NumberTarget.Parking) target).getField().name();
            profile = String.valueOf(((NumberTarget.Parking) target).getView());
            stage = "signals";
        }
        record("validation_rejected", "domain", "camera", "profile", profile,
                "source", source, "stage", stage, "field", field,
                "value", value, "reason", reason == null ? "invalid" : reason);
    }

    private void handleProductionSelection(BydExtendUiAction.Select action) {
        if (!(action.getTarget() instanceof SelectionTarget.Simple)) {
            SelectionTarget.Profile profile = (SelectionTarget.Profile) action.getTarget();
            saveProductionProfileSelection(profile, action.getIndex());
            return;
        }
        SelectionId id = ((SelectionTarget.Simple) action.getTarget()).getId();
        int index = action.getIndex();
        if (id == SelectionId.CameraSection
                || id == SelectionId.BlindGroup || id == SelectionId.BlindSide
                || id == SelectionId.ParkingView || id == SelectionId.ReverseElement
                || id == SelectionId.ReverseSource) {
            onProductionCameraSelectionChanged(id);
        } else if (id == SelectionId.ReverseTarget) {
            applyProductionReverseState();
            CameraHelperService.reverseCameraSettingsChanged(this);
        } else if (id == SelectionId.DiagnosticMode) {
            DiagnosticMode mode = productionUi.getState().getDebug().getMode();
            selectedDebugMode = mode == DiagnosticMode.Direct ? 0 : 1;
            preferences.edit().putInt("selected_debug_mode", mode.ordinal()).apply();
            if (requestedOpen || cameraHandoffPending) {
                closeCameraForTransition("debug_subtab_changed");
            }
        } else if (id == SelectionId.BlindWarningMode) {
            preferences.edit().putInt(BlindSpotOverlayController.PREF_WARNING_MODE,
                    clamp(index, 0, 2)).apply();
            CameraHelperService.cameraWarningSettingsChanged(this);
        } else if (id == SelectionId.DirectMode) {
            // Selection is authoritative immediately when the native Surface is ready.  If it
            // is not, the keyed AndroidView slot retains this one desired index for surfaceCreated.
            if (directCameraSurfaceReady && directCameraPreview != null
                    && helper != null && productionUi != null) {
                openDirectCamera(clamp(productionUi.getState().getDebug().getDirectSelection(), 0, 4));
            }
        } else if (id == SelectionId.AvmMode) {
            // The AVM slot is the single desired-mode holder until its Surface is ready; updates
            // remain idempotent because openStockAvm/openStockAvmNow gate the current request.
            if (debugSurfaceReady && debugPreview != null
                    && helper != null && productionUi != null) {
                int mode = clamp(productionUi.getState().getDebug().getAvmSelection(), 0,
                        StockAvmPreview.horizontalLayoutCount() - 1);
                openStockAvm(StockAvmPreview.horizontalViewpoint(mode), true);
            }
        } else if (id == SelectionId.AvmOrientation) {
            selectDebugOrientation(index == 0);
        } else if (id == SelectionId.CameraQuality) {
            int quality = clamp(index, 0, 3);
            preferences.edit().putInt(CameraBufferQuality.PREF_QUALITY, quality).apply();
            onCameraBufferQualityChanged(quality);
        } else if (id == SelectionId.HazardThanksFlashes) {
            preferences.edit().putInt(HazardThanksSettings.FLASH_COUNT,
                    clamp(index, 0, 2) + 1).apply();
            CameraHelperService.configureHazardThanks(this);
        }
    }

    private void onProductionCameraSelectionChanged(SelectionId id) {
        if (id == SelectionId.CameraSection && !isProductionCalibrationSection()) {
            // Parameters/Placement in the same root tab ends the retained calibration
            // workspace explicitly; a conditional stage's Compose onRelease is not teardown.
            releaseProductionCalibrationHosts();
        }
        CameraProfileId profile = selectedProductionProfile();
        if (profile != null) configureProductionCameraProfile(profile, null);
        if (id == SelectionId.ReverseSource && reverseCameraPreview != null) {
            boolean front = productionUi.getState().getReverse().getShowFront();
            reverseCameraPreview.setSideMode(front
                    ? ReverseSideSelectorView.MODE_FRONT : ReverseSideSelectorView.MODE_REAR);
            if (!isProductionCalibrationSection()) {
                // Both integrated inputs already belong to the retained composition.
                // Only the calibration workspace needs to reopen its selected single input.
                if (productionReverseEditor != null) {
                    ReverseCameraController.applyBorders(
                            preferences, productionReverseEditor, front);
                }
                return;
            }
        }
        if (id == SelectionId.BlindGroup || id == SelectionId.BlindSide) {
            preferences.edit().putInt("camera_selected_profile", selectedCameraId).apply();
        } else if (id == SelectionId.ParkingView) {
            preferences.edit().putInt(
                    "parking_camera_selected_profile", selectedParkingCameraId).apply();
        }
        if (requestedOpen || cameraHandoffPending) {
            armResumeAutoPreview();
            closeCameraForTransition("camera_profile_changed");
        } else {
            armResumeAutoPreview();
            renewSelectedPreviewInputForTabSwitch();
            resumeSelectedCameraPreview();
        }
    }

    private CameraProfileId selectedProductionProfile() {
        if (productionUi == null) return null;
        if (selectedTab == TAB_REARVIEW_MIRROR) return CameraProfileId.Mirror.INSTANCE;
        if (selectedTab == TAB_CAMERAS) {
            return new CameraProfileId.Blind(
                    productionUi.getState().getBlind().getSelectedGroup(),
                    productionUi.getState().getBlind().getSelectedSide());
        }
        if (selectedTab == TAB_PARKING_CAMERAS) {
            return new CameraProfileId.Parking(
                    productionUi.getState().getParking().getSelectedView());
        }
        if (selectedTab == TAB_REVERSE_CAMERAS) {
            ReverseElement element = productionUi.getState().getReverse().getSelectedElement();
            if (element == ReverseElement.Background || element == ReverseElement.Widget) return null;
            return new CameraProfileId.Reverse(element,
                    productionUi.getState().getReverse().getSelectedSource());
        }
        return null;
    }

    /** The combined Reverse composition has one lifecycle owner independent of selected pane. */
    private CameraProfileId reverseCompositionStatusProfile() {
        if (productionUi == null) return null;
        return new CameraProfileId.Reverse(
                ReverseElement.Rear,
                productionUi.getState().getReverse().getSelectedSource());
    }

    private void handleProductionCommand(BydExtendUiAction.Run action) {
        CommandId command = action.getCommand();
        if (handleRuntimeDialogCommand(command)) return;
        if (command == CommandId.ReverseLearnButton) beginReverseButtonLearning();
        else if (command == CommandId.ReverseResetButton) resetReverseButtonBinding();
        else if (command == CommandId.DismissDialog) cancelReverseButtonLearningIfVisible();
        else if (command == CommandId.WeatherRefresh) requestWeatherRefresh();
        else if (command == CommandId.SaveProfilePreset) saveProductionPreset(action.getProfile());
        else if (command == CommandId.LoadProfilePreset) loadProductionPreset(action.getProfile());
        else if (command == CommandId.TransferProfilePreset) transferProductionProfile(action.getProfile());
        else if (command == CommandId.ResetProfilePlacement
                || command == CommandId.ResetProfileOriginal
                || command == CommandId.ResetProfileCorrection
                || command == CommandId.ResetProfileOutput) {
            resetProductionProfile(action.getProfile(), command);
        } else if (command == CommandId.EnableAllParking
                || command == CommandId.DisableAllParking) {
            boolean enabled = command == CommandId.EnableAllParking;
            new ParkingCameraSettings(preferences).setAllEnabled(enabled);
            notifyParkingSettingsChanged();
        } else if (command == CommandId.ReverseNudgeLeft) nudgeProductionReverse(-0.01f, 0.0f);
        else if (command == CommandId.ReverseNudgeUp) nudgeProductionReverse(0.0f, -0.01f);
        else if (command == CommandId.ReverseNudgeRight) nudgeProductionReverse(0.01f, 0.0f);
        else if (command == CommandId.ReverseNudgeDown) nudgeProductionReverse(0.0f, 0.01f);
        else if (command == CommandId.ReverseLower) changeProductionReverseZ(false);
        else if (command == CommandId.ReverseRaise) changeProductionReverseZ(true);
        else if (command == CommandId.ReverseResetLayout) {
            resetProductionReverseLayout(action.getReverseElement(),
                    productionUi == null ? com.byd.extend.ui.DisplayTarget.Tablet
                            : productionUi.getState().getReverse().getSelectedTarget());
        } else if (manualSignalPayload(command) >= 0) requestManualTurnState(manualSignalPayload(command));
        else if (command == CommandId.StopDiagnosticCamera) stopActivityCameraManually("ui_stop");
        else if (command == CommandId.OpenBackgroundSettings) openBackgroundStartSettings("settings_button");
        else if (command == CommandId.GrantAdb) requestAdbAuthorization(
                "adb_authorization_manual", "settings_manual", false);
        else if (command == CommandId.CheckForUpdates) runUpdateCheck(true);
        else if (command == CommandId.ShareLogs) confirmDiagnosticLogShare();
        else if (command == CommandId.SaveLogs) confirmDiagnosticLogExport(true);
        else if (command == CommandId.ClearLogs) clearCaptureLogs();
        else if (command == CommandId.ShareCompatibilityPackage) confirmCompatibilityBundleShare();
        else if (command == CommandId.SaveCompatibilityPackage) confirmCompatibilityBundleExport(true);
        else if (command == CommandId.ExportCameraPresets) exportCameraPreset(false);
        else if (command == CommandId.SaveCameraPresets) exportCameraPreset(true);
        else if (command == CommandId.LoadCameraPresets) chooseCameraPreset();
        else if (command == CommandId.ImportLegacySettings) readLegacySettings();
        else if (command == CommandId.RestoreLegacyAccess) confirmLegacyAccessRestore();
        else if (command == CommandId.CancelOperation) cancelCompatibilityBundleExport();
        else if (command == CommandId.Shutdown) requestAppShutdown();
        else if (command == CommandId.OpenWeatherAttribution) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(OPEN_METEO_URL)));
            } catch (Throwable error) {
                publishSettingsFeedback("Open-Meteo: "
                        + OPEN_METEO_URL, StatusTone.Warning);
            }
        }
    }

    private void publishSettingsFeedback(String text, StatusTone tone) {
        if (productionUi == null) return;
        String localized = localizedCameraStatus(localizedGuardStatus(text));
        StatusUiState status = new StatusUiState(localized, tone, true);
        productionUi.setSettingsFeedback(status);
    }

    private void publishSettingsOperation(
            SettingsOperation operation, String text, StatusTone tone, boolean pending) {
        publishSettingsOperation(operation, text, tone, pending, false);
    }

    private void publishSettingsOperation(
            SettingsOperation operation, String text, StatusTone tone,
            boolean pending, boolean claimFeedback) {
        String localized = localizedCameraStatus(localizedGuardStatus(text));
        boolean diagnosticOnly = operation == SettingsOperation.Logs
                || operation == SettingsOperation.Compatibility
                || operation == SettingsOperation.Preset || operation == SettingsOperation.Import;
        if (diagnosticOnly) record("settings_operation", "operation", operation.name(),
                "pending", pending, "tone", tone.name(), "message", localized);
        if (productionUi != null) productionUi.setSettingsOperation(
                operation, new StatusUiState(diagnosticOnly ? "" : localized, tone,
                        !diagnosticOnly), pending, claimFeedback);
    }

    private void recordOperationFeedback(String message) {
        record("settings_operation_feedback", "message", message);
    }

    private void publishAdbOperation(boolean pending) {
        if (productionUi != null) productionUi.setSettingsOperation(SettingsOperation.Adb,
                new StatusUiState("", StatusTone.Neutral, false), pending);
    }

    private void refreshProductionHeader() {
        if (productionUi == null) return;
        productionUi.setMirrorOverlayPermissionGranted(productionMirrorOverlayPermissionGranted());
        productionUi.setMirrorClusterAvailable(productionMirrorClusterAvailable());
        LocalAdbClient.AccessState access = LocalAdbClient.readAccessState(this);
        productionUi.setHeader(productionHeader(access.status,
                preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false), hasLocationPermission(),
                RequiredPermissions.read(preferences,
                        activityStarted && (requestedOpen || hasAutoPreviewIntent()),
                        pendingButtonLearning != null || WeatherRefreshAccessibilityService.isCameraButtonLearning(),
                        updateInstallRequested).satisfied(
                        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
                        android.provider.Settings.canDrawOverlays(this),
                        AppPermissionProvisioner.hasAccessibilityAccess(this),
                        android.os.Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(),
                        AvasNotificationAccess.isGranted(this),
                        AppPermissionProvisioner.hasWriteSecureSettings(this),
                        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                        AppPermissionProvisioner.hasBluetoothCallAccess(this)),
                hasBackgroundLocationPermission()));
    }

    static HeaderUiState productionHeader(LocalAdbClient.AccessState.Status access,
            boolean weatherEnabled, boolean locationGranted) {
        return productionHeader(access, weatherEnabled, locationGranted, true);
    }

    static HeaderUiState productionHeader(LocalAdbClient.AccessState.Status access,
            boolean weatherEnabled, boolean locationGranted, boolean permissionsGranted) {
        return productionHeader(access, weatherEnabled, locationGranted, permissionsGranted, true);
    }

    private static HeaderUiState productionHeader(LocalAdbClient.AccessState.Status access,
            boolean weatherEnabled, boolean locationGranted, boolean permissionsGranted,
            boolean backgroundLocationGranted) {
        StatusTone adbTone = access == LocalAdbClient.AccessState.Status.OK
                ? StatusTone.Ok : access == LocalAdbClient.AccessState.Status.ERROR
                ? StatusTone.Error : StatusTone.Neutral;
        return new HeaderUiState(
                new StatusUiState("ADB", adbTone, true),
                new StatusUiState("", !locationGranted ? StatusTone.Error
                        : backgroundLocationGranted ? StatusTone.Ok : StatusTone.Warning,
                        weatherEnabled), weatherEnabled,
                new StatusUiState("", permissionsGranted ? StatusTone.Ok : StatusTone.Error, true));
    }

    private void publishManualSignalStatus(String text, StatusTone tone) {
        String localized = localizedGuardStatus(text);
        manualSignalStatus = new StatusUiState(localized, tone, true);
        publishGuardStatusLocalized(localized, tone);
    }

    private void publishGuardStatus(String text, StatusTone tone) {
        publishGuardStatusLocalized(localizedGuardStatus(text), tone);
    }

    private void publishGuardStatusLocalized(String text, StatusTone tone) {
        if (productionUi != null) productionUi.setGuardStatus(
                new StatusUiState(text, tone, true));
    }

    /** Translates fixed guard status copy but leaves telemetry/error details intact. */
    private String localizedGuardStatus(String text) {
        if (text == null) return "";
        if (text.equals("Службу зупинено")) return runtimeText(R.string.runtime_status_service_stopped);
        if (text.equals("Очікування внутрішньої служби...")) {
            return runtimeText(R.string.runtime_status_waiting_helper);
        }
        if (text.equals("Телеметрія готова")) return runtimeText(R.string.runtime_status_telemetry_ready);
        if (text.startsWith("Telemetry error: ")) {
            return runtimeText(R.string.runtime_status_telemetry_error,
                    text.substring("Telemetry error: ".length()));
        }
        if (text.startsWith("Helper відновлюється: ")) {
            return runtimeText(R.string.runtime_status_helper_restarting,
                    text.substring("Helper відновлюється: ".length()));
        }
        if (text.equals("Guard активний")) return runtimeText(R.string.runtime_status_guard_active);
        if (text.startsWith("Guard призупинено: ")) {
            return runtimeText(R.string.runtime_status_guard_paused,
                    text.substring("Guard призупинено: ".length()));
        }
        if (text.equals("Guard вимкнено")) return runtimeText(R.string.runtime_status_guard_disabled);
        if (text.equals("Лівий поворотник")) return runtimeText(R.string.runtime_status_turn_left);
        if (text.equals("Правий поворотник")) return runtimeText(R.string.runtime_status_turn_right);
        if (text.equals("Поворотник")) return runtimeText(R.string.runtime_status_turn_signal);
        if (text.equals("Поріг пройдено; очікування центру")) {
            return runtimeText(R.string.runtime_status_guard_armed);
        }
        if (text.equals("Маневр завершено")) return runtimeText(R.string.runtime_status_maneuver_complete);
        if (text.equals("Guard активний: швидкість нижче ліміту")) {
            return runtimeText(R.string.runtime_status_guard_speed_resumed);
        }
        if (text.startsWith("Очікування guard скасовано: ")) {
            return runtimeText(R.string.runtime_status_guard_speed_canceled,
                    text.substring("Очікування guard скасовано: ".length()));
        }
        if (text.equals("Ручне вимкнення; корекцію скасовано")) {
            return runtimeText(R.string.runtime_status_manual_cancel);
        }
        if (text.startsWith("Корекція: ")) {
            return runtimeText(R.string.runtime_status_correction,
                    text.substring("Корекція: ".length()));
        }
        if (text.equals("Корекцію підтверджено")) {
            return runtimeText(R.string.runtime_status_correction_confirmed);
        }
        if (text.startsWith("State поворотників скинуто: ")) {
            return runtimeText(R.string.runtime_status_latch_reset,
                    text.substring("State поворотників скинуто: ".length()));
        }
        if (text.startsWith("Скидання state не виконано: ")) {
            return runtimeText(R.string.runtime_status_latch_reset_failed,
                    text.substring("Скидання state не виконано: ".length()));
        }
        if (text.equals("Аварійка: очікування скидання state")) {
            return runtimeText(R.string.runtime_status_hazard_pending);
        }
        if (text.equals("Аварійку вимкнено; state скинуто в 0")) {
            return runtimeText(R.string.runtime_status_hazard_complete);
        }
        if (text.equals("Команду прийнято; перевірка blink...")) {
            return runtimeText(R.string.runtime_status_manual_accepted);
        }
        if (text.equals("Payload 0 прийнято; очищення перевірити після restart")) {
            return runtimeText(R.string.runtime_status_payload_zero);
        }
        if (text.startsWith("Стан підтверджено: ")) {
            return runtimeText(R.string.runtime_status_state_confirmed,
                    text.substring("Стан підтверджено: ".length()));
        }
        if (text.equals("Guard очікує швидкість нижче ліміту")) {
            return runtimeText(R.string.runtime_status_speed_waiting);
        }
        if (text.startsWith("Команда поворотників: payload ")) {
            String value = text.substring("Команда поворотників: payload ".length());
            int end = value.indexOf('.');
            try {
                int payload = Integer.parseInt(end >= 0 ? value.substring(0, end) : value);
                return runtimeText(R.string.runtime_status_manual_command, payload);
            } catch (NumberFormatException ignored) {
                // Keep an unknown diagnostic string untouched.
            }
        }
        if (text.equals("Guard IPC error")) return runtimeText(R.string.runtime_status_guard_ipc_error);
        if (text.equals("Turn-state IPC error")) return runtimeText(R.string.runtime_status_turn_ipc_error);
        if (text.equals("Camera helper недоступний")) {
            return runtimeText(R.string.runtime_status_helper_unavailable);
        }
        return text;
    }

    private void publishDiagnosticStatus(
            boolean direct, String text, StatusTone tone, boolean pending) {
        String localizedText = localizedCameraStatus(text);
        if (productionUi != null) productionUi.setDiagnosticStatus(
                direct, new StatusUiState(localizedText, tone, true), pending);
    }

    private void pushGuardConfigFromPreferences() {
        if (settingsTransferInProgress || settingsReloadPending || legacyRuntimeBlocked) return;
        final boolean requested = preferences.getBoolean("guard_enabled", false);
        final float outward = preferences.getFloat("outward_deg", DEFAULT_OUTWARD_DEG);
        final float center = preferences.getFloat("center_deg", DEFAULT_CENTER_DEG);
        final int delay = preferences.getInt(
                "correction_delay_ms", DEFAULT_CORRECTION_DELAY_MS);
        final int maximum = preferences.getInt("max_speed_kph", DEFAULT_MAX_SPEED_KPH);
        final IBinder current = helper;
        if (current == null) {
            if (requested) publishGuardStatus("Очікування внутрішньої служби...",
                    StatusTone.Warning);
            return;
        }
        ipcExecutor.execute(() -> transactGuardConfig(
                current, requested, outward, center, delay, maximum));
    }

    private void onProductionWeatherEnabled(boolean enabled) {
        weatherRefreshUiGeneration++;
        if (!enabled) {
            weatherRefreshAfterPermission = false;
            weatherLocationPermissionPending = false;
            weatherEnableRequestedForPermission = false;
            cancelPendingWeatherLocationPermission();
            preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, false).apply();
            CameraHelperService.weatherSettingsChanged(this);
            if (productionUi != null) {
                productionUi.setWeatherStatus(
                        new StatusUiState("", StatusTone.Neutral, false), false);
                productionUi.reload();
            }
            refreshProductionHeader();
            return;
        }
        if (hasLocationPermission()) {
            preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).apply();
            CameraHelperService.weatherSettingsChanged(this);
            if (productionUi != null) productionUi.reload();
            queueWeatherBackgroundPermission(true);
            showWeatherPermissionStatus();
            refreshProductionHeader();
            return;
        }
        weatherEnableRequestedForPermission = true;
        requestLocationPermission(true, false);
    }

    private void saveProductionGuardNumber(NumberTarget.Guard target, float value) {
        float outward = preferences.getFloat("outward_deg", DEFAULT_OUTWARD_DEG);
        float center = preferences.getFloat("center_deg", DEFAULT_CENTER_DEG);
        int delay = preferences.getInt("correction_delay_ms", DEFAULT_CORRECTION_DELAY_MS);
        int speed = preferences.getInt("max_speed_kph", DEFAULT_MAX_SPEED_KPH);
        if (target.getField() == GuardNumber.OutwardAngle) outward = value;
        else if (target.getField() == GuardNumber.CentreTolerance) center = value;
        else if (target.getField() == GuardNumber.CorrectionDelayMs) delay = Math.round(value);
        else speed = Math.round(value);
        if (!isValidGuardThresholds(outward, center)
                || delay < 0 || delay > 1_000 || speed < 0 || speed > 300) {
            publishSettingsFeedback(
                    runtimeText(R.string.runtime_guard_values_invalid), StatusTone.Error);
            return;
        }
        preferences.edit().putFloat("outward_deg", outward).putFloat("center_deg", center)
                .putInt("correction_delay_ms", delay).putInt("max_speed_kph", speed).apply();
        pushGuardConfigFromPreferences();
    }

    static boolean isValidGuardThresholds(float outward, float center) {
        return Float.isFinite(outward) && Float.isFinite(center)
                && outward >= 0.0f && outward <= 360.0f
                && center >= 0.0f && center <= 45.0f;
    }

    private void saveProductionBlindNumber(NumberTarget.Blind target, float value) {
        int minimumKeyValue;
        int maximumKeyValue;
        if (target.getGroup() == CameraGroup.Rear) {
            if (target.getField() == BlindNumber.SteeringAngle) {
                if (value < 0.0f || value > 780.0f) return;
                preferences.edit().putFloat(
                        BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE, value).apply();
                CameraHelperService.cameraTriggerSettingsChanged(this);
                return;
            }
            minimumKeyValue = preferences.getInt(BlindSpotOverlayController.PREF_MIN_SPEED,
                    BlindSpotOverlayController.DEFAULT_MIN_SPEED_KPH);
            maximumKeyValue = preferences.getInt(BlindSpotOverlayController.PREF_MAX_SPEED,
                    BlindSpotOverlayController.DEFAULT_MAX_SPEED_KPH);
            if (target.getField() == BlindNumber.MinimumSpeed) minimumKeyValue = Math.round(value);
            else maximumKeyValue = Math.round(value);
            if (minimumKeyValue < 0 || minimumKeyValue > maximumKeyValue
                    || maximumKeyValue > 300) {
                recordCameraValidationRejected(target, Float.toString(value),
                        "minimum_speed_exceeds_maximum_or_out_of_range");
                return;
            }
            preferences.edit().putInt(BlindSpotOverlayController.PREF_MIN_SPEED, minimumKeyValue)
                    .putInt(BlindSpotOverlayController.PREF_MAX_SPEED, maximumKeyValue).apply();
        } else {
            if (target.getField() == BlindNumber.SteeringAngle) {
                if (value < 0.0f || value > 780.0f) return;
                preferences.edit().putFloat(
                        BlindSpotOverlayController.PREF_FRONT_MIN_ANGLE, value).apply();
                CameraHelperService.cameraSettingsChanged(this);
                return;
            }
            minimumKeyValue = preferences.getInt(
                    BlindSpotOverlayController.PREF_FRONT_MIN_SPEED,
                    BlindSpotOverlayController.DEFAULT_FRONT_MIN_SPEED_KPH);
            maximumKeyValue = preferences.getInt(
                    BlindSpotOverlayController.PREF_FRONT_MAX_SPEED,
                    BlindSpotOverlayController.DEFAULT_FRONT_MAX_SPEED_KPH);
            if (target.getField() == BlindNumber.MinimumSpeed) minimumKeyValue = Math.round(value);
            else maximumKeyValue = Math.round(value);
            if (minimumKeyValue < 0 || minimumKeyValue > maximumKeyValue
                    || maximumKeyValue > 300) {
                recordCameraValidationRejected(target, Float.toString(value),
                        "minimum_speed_exceeds_maximum_or_out_of_range");
                return;
            }
            preferences.edit()
                    .putInt(BlindSpotOverlayController.PREF_FRONT_MIN_SPEED, minimumKeyValue)
                    .putInt(BlindSpotOverlayController.PREF_FRONT_MAX_SPEED, maximumKeyValue)
                    .apply();
        }
        CameraHelperService.cameraSettingsChanged(this);
    }

    private void saveProductionParkingNumber(NumberTarget.Parking target, float value) {
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        if (target.getField() == ParkingNumber.MaximumSpeed) {
            settings.setMaxSpeedKph(Math.round(value));
        } else if (target.getView() != null) {
            ParkingCameraProfile profile = ParkingCameraProfile.of(target.getView().ordinal());
            settings.setRule(profile, settings.rule(profile).withDistanceCm(Math.round(value)));
        }
        notifyParkingSettingsChanged();
    }

    private static int reverseElementIndex(ReverseElement element) {
        if (element == ReverseElement.Background) return ReverseCameraLayout.BACKGROUND_PANE_ID;
        if (element == ReverseElement.Widget) return ReverseCameraLayout.WIDGET_PANE_ID;
        if (element == ReverseElement.Rear) return ReverseCameraLayout.REAR_CAMERA_INDEX;
        if (element == ReverseElement.RearLeft) return ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX;
        if (element == ReverseElement.RearRight) return ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
        return ReverseCameraLayout.REAR_CAMERA_INDEX;
    }

    private static CameraProfile blindProfile(CameraProfileId.Blind id) {
        int base = id.getGroup() == CameraGroup.Front ? CameraProfile.FRONT_LEFT
                : CameraProfile.REAR_LEFT;
        return CameraProfile.of(base + (id.getSide() == CameraSide.Right ? 1 : 0));
    }

    private static ParkingCameraProfile parkingProfile(CameraProfileId.Parking id) {
        return ParkingCameraProfile.of(id.getView().ordinal());
    }

    private static int reverseProfileIndex(CameraProfileId.Reverse id) {
        return reverseElementIndex(id.getElement());
    }

    private static CameraBorderSettings.Border productionBorder(
            SharedPreferences preferences, CameraProfileId id, Boolean mirrorFront) {
        if (id instanceof CameraProfileId.Blind) {
            return CameraBorderSettings.forBlind(
                    preferences, blindProfile((CameraProfileId.Blind) id));
        }
        if (id instanceof CameraProfileId.Parking) {
            return CameraBorderSettings.forParking(
                    preferences, parkingProfile((CameraProfileId.Parking) id));
        }
        if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int pane = reverseProfileIndex(reverse);
            if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                    || pane == ReverseCameraLayout.WIDGET_PANE_ID) {
                return CameraBorderSettings.forReverseElement(preferences, pane);
            }
            return CameraBorderSettings.forReverse(
                    preferences, pane, reverse.getSource() == ReverseSource.Front);
        }
        if (id instanceof CameraProfileId.Mirror) {
            boolean front = mirrorFront != null
                    ? mirrorFront : RearviewMirrorSettings.activeFront(preferences);
            return CameraBorderSettings.forMirror(preferences, front);
        }
        throw new IllegalArgumentException("unsupported camera border profile");
    }

    private void saveProductionProfileBorder(BydExtendUiAction.SetProfileBorder action) {
        CameraProfileId id = action.getProfile();
        if (id == null || id instanceof CameraProfileId.Mirror
                && action.getMirrorFront() == null) return;
        try {
            writeProductionProfileBorder(preferences, id, action.getWidth(),
                    action.getArgb(), action.getMirrorFront());
            notifyProductionProfileChanged(id);
            if (productionUi != null) productionUi.reload();
        } catch (IllegalArgumentException invalid) {
            record("camera_validation_rejected", "profile", String.valueOf(id),
                    "field", action.getWidth() != null ? "border_width" : "border_color",
                    "reason", invalid.getMessage());
            if (productionUi != null) productionUi.reload();
        }
    }

    static void writeProductionProfileBorder(SharedPreferences preferences, CameraProfileId id,
            String widthText, Integer argb, Boolean mirrorFront) {
        if (preferences == null || id == null || id instanceof CameraProfileId.Mirror
                && mirrorFront == null) {
            throw new IllegalArgumentException("camera border target required");
        }
        CameraBorderSettings.Border before = productionBorder(preferences, id, mirrorFront);
        int width = widthText == null ? before.borderDp
                : Integer.parseInt(widthText.trim());
        CameraBorderSettings.Border border = new CameraBorderSettings.Border(
                width, argb == null ? before.borderArgb : argb);
        if (id instanceof CameraProfileId.Blind) {
            CameraBorderSettings.writeBlind(
                    preferences, blindProfile((CameraProfileId.Blind) id), border);
        } else if (id instanceof CameraProfileId.Parking) {
            CameraBorderSettings.writeParking(
                    preferences, parkingProfile((CameraProfileId.Parking) id), border);
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int pane = reverseProfileIndex(reverse);
            if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                    || pane == ReverseCameraLayout.WIDGET_PANE_ID) {
                CameraBorderSettings.writeReverseElement(preferences, pane, border);
            } else {
                CameraBorderSettings.writeReverse(preferences, pane,
                        reverse.getSource() == ReverseSource.Front, border);
            }
        } else if (id instanceof CameraProfileId.Mirror) {
            CameraBorderSettings.writeMirror(preferences, mirrorFront, border);
        } else {
            throw new IllegalArgumentException("unsupported camera border profile");
        }
    }

    private CameraPlacement loadProductionBlindPlacement(CameraProfile profile) {
        return loadProductionBlindPlacement(profile, BlindSpotOverlayController.readTarget(preferences, profile));
    }

    private CameraPlacement loadProductionBlindPlacement(CameraProfile profile, int target) {
        int[] display = CameraDisplayTarget.displaySize(this, target);
        boolean tablet = target == CameraDisplayTarget.TABLET;
        return BlindSpotOverlayController.readPlacement(preferences, profile, target, display[0], display[1],
                tablet ? dp(16) : 0, tablet ? dp(36) : 0, tablet ? dp(88) : 0);
    }

    static void saveProductionBlindGeometry(SharedPreferences preferences, CameraProfileId.Blind id,
            MirrorGeometryUiState geometry) {
        saveProductionBlindGeometry(preferences, id, geometry, null);
    }

    static void saveProductionBlindGeometry(SharedPreferences preferences, CameraProfileId.Blind id,
            MirrorGeometryUiState geometry, DisplayTarget origin) {
        CameraProfile profile = blindProfile(id);
        int target = BlindSpotOverlayController.readTarget(preferences, profile);
        if (origin != null && displayTargetValue(origin) != target) return;
        CameraPlacement placement = CameraPlacement.bounded(mirrorFraction(geometry.getX()),
                mirrorFraction(geometry.getY()), mirrorFraction(geometry.getWidth()),
                mirrorFraction(geometry.getHeight())).roundedTenths();
        BlindSpotOverlayController.writePlacement(preferences, profile, target, placement);
    }

    private static int displayTargetValue(DisplayTarget target) {
        return target == DisplayTarget.Cluster ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
    }

    private void saveProductionProfilePosition(CameraProfileId id, float x, float y, DisplayTarget origin) {
        float safeX = clamp(x, 0.0f, 1.0f);
        float safeY = clamp(y, 0.0f, 1.0f);
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            int target = BlindSpotOverlayController.readTarget(preferences, profile);
            if (origin != null && displayTargetValue(origin) != target) return;
            CameraPlacement before = loadProductionBlindPlacement(profile, target);
            BlindSpotOverlayController.writePlacement(preferences, profile, target,
                    before.positionTenths(safeX, safeY));
            CameraHelperService.cameraSettingsChanged(this);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            String prefix = parkingPlacementPrefix(profile);
            preferences.edit().putFloat(prefix + "x", safeX)
                    .putFloat(prefix + "y", safeY).apply();
            notifyParkingSettingsChanged();
        }
    }

    private void saveProductionDewarpEnabled(CameraProfileId id, boolean enabled) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            CameraDewarpConfig value = CameraDewarpConfig.loadForProfile(
                    preferences, profile).withEnabled(enabled);
            CameraDewarpConfig.saveForProfile(preferences, profile, value);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            CameraDewarpConfig value = CameraDewarpConfig.loadForParking(
                    preferences, profile).withEnabled(enabled);
            CameraDewarpConfig.saveForParking(preferences, profile, value);
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            CameraDewarpConfig value = reverse.getSource() == ReverseSource.Front
                    ? CameraDewarpConfig.loadForReverseFront(preferences, index)
                    : CameraDewarpConfig.loadForReverse(preferences, index);
            value = value.withEnabled(enabled);
            if (reverse.getSource() == ReverseSource.Front) {
                CameraDewarpConfig.saveForReverseFront(preferences, index, value);
            } else CameraDewarpConfig.saveForReverse(preferences, index, value);
        }
        notifyProductionProfileChanged(id);
    }

    private void saveProductionMirror(CameraProfileId id, boolean mirror) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            DirectCameraCrop crop = DirectCameraCrop.load(preferences, profile)
                    .withMirrorHorizontally(mirror);
            DirectCameraCrop.saveOutputTransform(preferences, profile, crop);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            DirectCameraCrop crop = DirectCameraCrop.load(preferences, profile)
                    .withMirrorHorizontally(mirror);
            DirectCameraCrop.saveOutputTransform(preferences, profile, crop);
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            if (reverse.getSource() == ReverseSource.Front) {
                ReverseCameraLayout layout = ReverseCameraController.loadFrontRawLayout(preferences);
                ReverseCameraLayout.Pane pane = layout.pane(index);
                ReverseCameraController.saveFrontPaneTransform(preferences, index,
                        pane.rotationDegrees, pane.displayMode, mirror);
            } else {
                ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
                ReverseCameraLayout.Pane pane = layout.pane(index);
                ReverseCameraController.saveRearPaneTransform(preferences, index,
                        pane.rotationDegrees, pane.displayMode, mirror);
            }
        }
        notifyProductionProfileChanged(id);
    }

    private void saveProductionProfileNumber(NumberTarget.Profile target, float value) {
        CameraProfileId id = target.getProfile();
        ProfileNumber field = target.getField();
        if (field == ProfileNumber.Size || field == ProfileNumber.X || field == ProfileNumber.Y
                || field == ProfileNumber.Width || field == ProfileNumber.Height) {
            saveProductionPlacementNumber(id, field, value, target.getDisplayTarget());
            return;
        }
        try {
            if (field == ProfileNumber.Fov) {
                saveProductionFov(id, value);
            } else if (field == ProfileNumber.Strength) {
                CameraDewarpConfig current = loadProductionCalibrationDewarp(id);
                saveProductionDewarp(id, current.withStrength(checkedStrength(current, value)));
            } else if (field == ProfileNumber.Rotation) {
                saveProductionOutputTransform(id, Math.round(value), null);
            } else {
                saveProductionCropNumber(id, field, value / 100.0f);
            }
            clearTransientProfilePreview(id, field);
            notifyProductionProfileChanged(id);
        } catch (IllegalArgumentException invalid) {
            recordCameraValidationRejected(
                    target, Float.toString(value), invalid.getMessage());
        }
    }

    private void saveProductionPlacementNumber(
            CameraProfileId id, ProfileNumber field, float value, DisplayTarget origin) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            int target = BlindSpotOverlayController.readTarget(preferences, profile);
            if (origin != null && displayTargetValue(origin) != target) return;
            if (field == ProfileNumber.Size) {
                int size = clamp(Math.round(value), BlindSpotOverlayController.MIN_SCALE_PERCENT,
                        BlindSpotOverlayController.MAX_SCALE_PERCENT);
                preferences.edit().putInt(BlindSpotOverlayController.scaleKey(profile), size).apply();
            } else {
                CameraPlacement before = loadProductionBlindPlacement(profile, target);
                CameraPlacement next;
                if (field == ProfileNumber.X || field == ProfileNumber.Y) {
                    CameraPlacement moved = before.positionTenths(field == ProfileNumber.X ? value / 100 : before.x,
                            field == ProfileNumber.Y ? value / 100 : before.y);
                    next = CameraPlacement.of(field == ProfileNumber.X ? moved.x : before.x,
                            field == ProfileNumber.Y ? moved.y : before.y, before.width, before.height);
                } else {
                    float size = clamp(Math.round(value * 10.0f) / 1000.0f, CameraPlacement.MIN_SIZE, 1.0f);
                    float width = field == ProfileNumber.Width ? size : before.width;
                    float height = field == ProfileNumber.Height ? size : before.height;
                    next = CameraPlacement.of(Math.min(before.x, 1 - width),
                            Math.min(before.y, 1 - height), width, height);
                }
                BlindSpotOverlayController.writePlacement(preferences, profile, target, next);
            }
            CameraHelperService.cameraSettingsChanged(this);
            return;
        }
        if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            String prefix = parkingPlacementPrefix(profile);
            SharedPreferences.Editor editor = preferences.edit();
            if (field == ProfileNumber.Size) {
                int size = clamp(Math.round(value), BlindSpotOverlayController.MIN_SCALE_PERCENT,
                        BlindSpotOverlayController.MAX_SCALE_PERCENT);
                if (preferences.getBoolean(parkingScaleSyncKey(), false)) {
                    for (ParkingCameraProfile item : ParkingCameraProfile.values()) {
                        editor.putInt(parkingPlacementPrefix(item) + "scale", size);
                    }
                } else editor.putInt(prefix + "scale", size);
            } else editor.putFloat(prefix + (field == ProfileNumber.X ? "x" : "y"),
                    clamp(value / 100.0f, 0.0f, 1.0f));
            editor.apply();
            notifyParkingSettingsChanged();
        }
    }

    private void saveProductionFov(CameraProfileId id, float fov) {
        if (fov < CameraDewarpConfig.MIN_FOV_DEGREES
                || fov > CameraDewarpConfig.MAX_FOV_DEGREES) {
            throw new IllegalArgumentException("FOV має бути 60..170°");
        }
        saveProductionDewarp(id, loadProductionCalibrationDewarp(id).withHorizontalFov(fov));
    }

    private void saveProductionDewarp(CameraProfileId id, CameraDewarpConfig value) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            CameraDewarpConfig.saveForProfile(preferences, profile,
                    value);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            CameraDewarpConfig.saveForParking(preferences, profile,
                    value);
        } else {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            if (reverse.getSource() == ReverseSource.Front) {
                CameraDewarpConfig.saveForReverseFront(preferences, index,
                        value);
            } else CameraDewarpConfig.saveForReverse(preferences, index,
                    value);
        }
    }

    private void saveProductionCropNumber(
            CameraProfileId id, ProfileNumber field, float value) {
        boolean corrected = field == ProfileNumber.CorrectedX
                || field == ProfileNumber.CorrectedY
                || field == ProfileNumber.CorrectedWidth
                || field == ProfileNumber.CorrectedHeight;
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            DirectCameraCrop crop = corrected
                    ? DirectCameraCrop.loadCorrected(preferences, profile, raw) : raw;
            crop = replaceCropValue(crop, field, value);
            if (corrected) DirectCameraCrop.saveCorrectedGeometryEdit(
                    preferences, profile, crop);
            else DirectCameraCrop.saveRawGeometryEdit(preferences, profile, crop);
            return;
        }
        if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            DirectCameraCrop crop = corrected
                    ? DirectCameraCrop.loadCorrected(preferences, profile, raw) : raw;
            crop = replaceCropValue(crop, field, value);
            if (corrected) DirectCameraCrop.saveCorrectedGeometryEdit(
                    preferences, profile, crop);
            else DirectCameraCrop.saveRawGeometryEdit(preferences, profile, crop);
            return;
        }
        CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
        int index = reverseProfileIndex(reverse);
        if (reverse.getSource() == ReverseSource.Front) {
            ReverseCameraLayout raw = ReverseCameraController.loadFrontRawLayout(preferences);
            ReverseCameraLayout.Pane pane = raw.pane(index);
            ReverseCameraLayout.Rect crop = corrected
                    ? ReverseCameraController.loadFrontCorrectedSourceCrop(preferences, index)
                    : pane.sourceCrop;
            crop = replaceReverseRectValue(crop, pane, field, value);
            ReverseCameraController.saveFrontSourceCrop(preferences, index, crop, corrected);
        } else {
            ReverseCameraLayout raw = ReverseCameraController.loadRawLayout(preferences);
            ReverseCameraLayout.Pane pane = raw.pane(index);
            ReverseCameraLayout.Rect crop = corrected
                    ? ReverseCameraController.loadCorrectedSourceCrop(preferences, index,
                            ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop))
                    : pane.sourceCrop;
            crop = replaceReverseRectValue(crop, pane, field, value);
            ReverseCameraController.saveSourceCrop(preferences, index, crop, corrected);
        }
    }

    private static DirectCameraCrop replaceCropValue(
            DirectCameraCrop crop, ProfileNumber field, float value) {
        float left = crop.left;
        float top = crop.top;
        float width = crop.width;
        float height = crop.height;
        if (field == ProfileNumber.OriginalX || field == ProfileNumber.CorrectedX) left = value;
        else if (field == ProfileNumber.OriginalY || field == ProfileNumber.CorrectedY) top = value;
        else if (field == ProfileNumber.OriginalWidth
                || field == ProfileNumber.CorrectedWidth) width = value;
        else if (field == ProfileNumber.OriginalHeight
                || field == ProfileNumber.CorrectedHeight) height = value;
        return crop.withIndependentGeometry(left, top, width, height);
    }

    /**
     * Applies a Reverse numeric ROI edit through the same strict geometry
     * validator as the on-canvas overlay.  ReverseCameraLayout.sourceCrop()
     * intentionally clamps values for legacy loads; numeric edits must reject
     * those values instead of silently reshaping the requested ROI.
     */
    private static ReverseCameraLayout.Rect replaceReverseRectValue(
            ReverseCameraLayout.Rect crop, ReverseCameraLayout.Pane pane,
            ProfileNumber field, float value) {
        float left = crop.left;
        float top = crop.top;
        float width = crop.width;
        float height = crop.height;
        if (field == ProfileNumber.OriginalX || field == ProfileNumber.CorrectedX) left = value;
        else if (field == ProfileNumber.OriginalY || field == ProfileNumber.CorrectedY) top = value;
        else if (field == ProfileNumber.OriginalWidth
                || field == ProfileNumber.CorrectedWidth) width = value;
        else if (field == ProfileNumber.OriginalHeight
                || field == ProfileNumber.CorrectedHeight) height = value;
        DirectCameraCrop accepted = DirectCameraCrop.requireUiGeometry(
                left, top, width, height, pane.rotationDegrees, pane.displayMode);
        return ReverseCameraLayout.sourceCrop(
                accepted.left, accepted.top, accepted.width, accepted.height);
    }

    private void saveProductionOutputTransform(
            CameraProfileId id, Integer rotation, Integer mode) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            DirectCameraCrop crop = DirectCameraCrop.load(preferences, profile);
            crop = strictOutputTransform(crop, rotation, mode);
            DirectCameraCrop.saveOutputTransform(preferences, profile, crop);
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            DirectCameraCrop crop = DirectCameraCrop.load(preferences, profile);
            crop = strictOutputTransform(crop, rotation, mode);
            DirectCameraCrop.saveOutputTransform(preferences, profile, crop);
        } else {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            if (reverse.getSource() == ReverseSource.Front) {
                ReverseCameraLayout layout = ReverseCameraController.loadFrontRawLayout(preferences);
                ReverseCameraLayout.Pane pane = layout.pane(index);
                requireReverseOutputTransform(pane, rotation, mode);
                ReverseCameraController.saveFrontPaneTransform(preferences, index,
                        rotation == null ? pane.rotationDegrees : rotation,
                        mode == null ? pane.displayMode : mode,
                        pane.mirrorHorizontally);
            } else {
                ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
                ReverseCameraLayout.Pane pane = layout.pane(index);
                requireReverseOutputTransform(pane, rotation, mode);
                ReverseCameraController.saveRearPaneTransform(preferences, index,
                        rotation == null ? pane.rotationDegrees : rotation,
                        mode == null ? pane.displayMode : mode,
                        pane.mirrorHorizontally);
            }
        }
        notifyProductionProfileChanged(id);
    }

    /** Reject output transforms that would silently resize an aligned crop. */
    private static DirectCameraCrop strictOutputTransform(
            DirectCameraCrop crop, Integer rotation, Integer mode) {
        int nextRotation = rotation == null
                ? crop.rotationDegrees : CameraRotation.clamp(rotation);
        int nextMode = mode == null ? crop.rotationMode : mode;
        DirectCameraCrop.requireUiGeometry(
                crop.left, crop.top, crop.width, crop.height,
                nextRotation, nextMode);
        return crop.withOutputTransformPreservingGeometry(
                nextRotation, nextMode, crop.mirrorHorizontally);
    }

    private static void requireReverseOutputTransform(
            ReverseCameraLayout.Pane pane, Integer rotation, Integer mode) {
        if (pane == null) throw new IllegalArgumentException("reverse pane required");
        int nextRotation = rotation == null
                ? pane.rotationDegrees : CameraRotation.clamp(rotation);
        int nextMode = mode == null ? pane.displayMode : mode;
        DirectCameraCrop.requireUiGeometry(
                pane.sourceCrop.left, pane.sourceCrop.top,
                pane.sourceCrop.width, pane.sourceCrop.height,
                nextRotation, nextMode);
    }

    private void saveProductionProfileSelection(
            SelectionTarget.Profile target, int index) {
        if (target.getId() == SelectionId.ProfileTarget
                && target.getProfile() instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) target.getProfile());
            preferences.edit().putInt(BlindSpotOverlayController.targetKey(profile),
                    CameraDisplayTarget.isValid(index) ? index : CameraDisplayTarget.TABLET).apply();
            CameraHelperService.cameraSettingsChanged(this);
        } else if (target.getId() == SelectionId.ProfileProjection) {
            saveProductionProjection(target.getProfile(), index);
        } else if (target.getId() == SelectionId.ProfileOutputMode
                && ReverseCameraLayout.isValidDisplayMode(index)) {
            try {
                saveProductionOutputTransform(target.getProfile(), null, index);
            } catch (IllegalArgumentException invalid) {
                CameraProfileId profile = target.getProfile();
                record("validation_rejected", "domain", "camera",
                        "profile", profile == null ? "unknown" : profile.toString(),
                        "source", profile instanceof CameraProfileId.Reverse
                                ? ((CameraProfileId.Reverse) profile).getSource().name() : "unknown",
                        "stage", "output", "field", "ProfileOutputMode",
                        "value", index, "reason", invalid.getMessage());
            }
        }
    }

    private void saveProductionProjection(CameraProfileId id, int projection) {
        if (!CameraDewarpConfig.isValidProjection(projection)) return;
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            CameraDewarpConfig.saveForProfile(preferences, profile,
                    CameraDewarpConfig.loadForProfile(preferences, profile)
                            .withProjection(projection));
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            CameraDewarpConfig.saveForParking(preferences, profile,
                    CameraDewarpConfig.loadForParking(preferences, profile)
                            .withProjection(projection));
        } else {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int cameraIndex = reverseProfileIndex(reverse);
            if (reverse.getSource() == ReverseSource.Front) {
                CameraDewarpConfig.saveForReverseFront(preferences, cameraIndex,
                        CameraDewarpConfig.loadForReverseFront(preferences, cameraIndex)
                                .withProjection(projection));
            } else CameraDewarpConfig.saveForReverse(preferences, cameraIndex,
                    CameraDewarpConfig.loadForReverse(preferences, cameraIndex)
                            .withProjection(projection));
        }
        notifyProductionProfileChanged(id);
    }

    private void notifyProductionProfileChanged(CameraProfileId id) {
        if (id instanceof CameraProfileId.Parking) notifyParkingSettingsChanged();
        else if (id instanceof CameraProfileId.Mirror) CameraHelperService.mirrorSettingsChanged(this);
        else if (id instanceof CameraProfileId.Reverse) {
            CameraHelperService.reverseCameraSettingsChanged(this);
            applyProductionReverseState();
            int pane = reverseProfileIndex((CameraProfileId.Reverse) id);
            if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                    || pane == ReverseCameraLayout.WIDGET_PANE_ID) {
                refreshProductionBorderOverlays();
                return;
            }
        } else CameraHelperService.cameraSettingsChanged(this);
        configureProductionCameraProfile(id, null);
        refreshProductionBorderOverlays();
    }

    /** Applies one validated persisted profile to the stable native Compose hosts. */
    private void configureProductionCameraProfile(
            CameraProfileId id, Integer requestedSourceIndex) {
        DirectCameraCrop raw;
        DirectCameraCrop corrected;
        CameraDewarpConfig dewarp;
        int sourceIndex;
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            selectedCameraId = profile.id;
            raw = DirectCameraCrop.load(preferences, profile);
            corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw);
            dewarp = CameraDewarpConfig.loadForProfile(preferences, profile);
            sourceIndex = profile.previewIndex;
            calibrationParkingMode = false;
            calibrationCameraId = profile.id;
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            selectedParkingCameraId = profile.id;
            raw = DirectCameraCrop.load(preferences, profile);
            corrected = DirectCameraCrop.loadCorrected(preferences, profile, raw);
            dewarp = CameraDewarpConfig.loadForParking(preferences, profile);
            sourceIndex = profile.physicalCameraIndex;
            calibrationParkingMode = true;
            calibrationParkingCameraId = profile.id;
            calibrationCameraId = parkingPreviewProfile(profile).id;
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int cameraIndex = reverseProfileIndex(reverse);
            boolean front = reverse.getSource() == ReverseSource.Front;
            ReverseCameraLayout layout = front
                    ? ReverseCameraController.loadFrontRawLayout(preferences)
                    : ReverseCameraController.loadRawLayout(preferences);
            // Keep the typed calibration layout context warm even when the
            // Compose calibration host is opened without the legacy Reverse
            // panel.  applyReverseCalibrationCrop() relies on these fields to
            // update the active and raw panes atomically.
            if (front) {
                reverseFrontRawCalibrationLayout = layout;
                reverseFrontCameraLayout =
                        ReverseCameraController.loadFrontLayout(preferences);
            } else {
                reverseRawCalibrationLayout = layout;
                reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
            }
            ReverseCameraLayout.Pane pane = layout.pane(cameraIndex);
            ReverseCameraLayout.Rect correctedRect = front
                    ? ReverseCameraController.loadFrontCorrectedSourceCrop(
                            preferences, cameraIndex)
                    : ReverseCameraController.loadCorrectedSourceCrop(
                            preferences, cameraIndex,
                            ReverseCameraLayout.centeredSourceCrop(pane.sourceCrop));
            raw = directCrop(pane.sourceCrop, pane);
            corrected = directCrop(correctedRect, pane);
            dewarp = front
                    ? CameraDewarpConfig.loadForReverseFront(preferences, cameraIndex)
                    : CameraDewarpConfig.loadForReverse(preferences, cameraIndex);
            sourceIndex = front && cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX
                    ? 4 : cameraIndex;
            reverseCalibrationCameraIndex = cameraIndex;
            reverseCalibrationFront = front;
            calibrationParkingMode = false;
        } else if (id instanceof CameraProfileId.Mirror) {
            raw = RearviewMirrorSettings.raw(preferences);
            corrected = RearviewMirrorSettings.corrected(preferences);
            dewarp = RearviewMirrorSettings.dewarp(preferences);
            sourceIndex = RearviewMirrorSettings.cameraIndex(preferences);
            calibrationParkingMode = false;
        } else return;
        if (requestedSourceIndex != null) sourceIndex = requestedSourceIndex;

        // AndroidView.update runs again when Compose accepts a slider preview.  Keep that
        // recomposition from re-reading stale persisted FOV/rotation over the live gesture.
        if (id.equals(transientProfilePreviewId)) {
            if (transientProfilePreviewFov != null) {
                dewarp = dewarp.withHorizontalFov(transientProfilePreviewFov);
            }
            if (transientProfilePreviewStrength != null) dewarp = dewarp.withStrength(transientProfilePreviewStrength);
            if (transientProfilePreviewRotation != null) {
                raw = raw.withOutputTransformPreservingGeometry(
                        transientProfilePreviewRotation, raw.rotationMode,
                        raw.mirrorHorizontally);
                corrected = corrected.withOutputTransformPreservingGeometry(
                        transientProfilePreviewRotation, corrected.rotationMode,
                        corrected.mirrorHorizontally);
            }
        }

        DirectCameraCrop previewRaw = raw;
        DirectCameraCrop active = dewarp.enabled ? corrected : raw;
        if (cameraPreview != null) {
            cameraPreview.applyRawFallbackCrop(previewRaw);
            applyDewarpSourceRoi(cameraPreview, previewRaw);
            cameraPreview.applyDewarpConfig(dewarp);
            cameraPreview.applyDirectCameraCrop(active);
        }
        if (calibrationPreview != null) {
            calibrationRawCrop = raw;
            calibrationCorrectedCrop = corrected;
            calibrationPreview.applyRawFallbackCrop(previewRaw);
            applyDewarpSourceRoi(calibrationPreview, previewRaw);
            calibrationPreview.applyDewarpConfig(dewarp);
            calibrationPreview.applyDirectCameraCrop(active);
        }
        activeDirectCameraIndex = requestedOpen ? activeDirectCameraIndex : sourceIndex;
    }

    private static DirectCameraCrop directCrop(
            ReverseCameraLayout.Rect crop, ReverseCameraLayout.Pane pane) {
        // Do not let the legacy normalizer constrain an existing ALIGNED ROI
        // while merely loading it.  Validate the stored geometry in neutral
        // coordinates, then apply the persisted output transform verbatim.
        return DirectCameraCrop.requireUiGeometry(
                crop.left, crop.top, crop.width, crop.height, 0, CameraRotation.MODE_FIT)
                .withOutputTransformPreservingGeometry(
                        pane.rotationDegrees, pane.displayMode,
                        pane.mirrorHorizontally);
    }

    private void saveProductionPreset(CameraProfileId id) {
        boolean saved = false;
        if (id instanceof CameraProfileId.Blind) {
            CameraCalibrationPreset.saveCamera(
                    preferences, blindProfile((CameraProfileId.Blind) id));
            saved = true;
        } else if (id instanceof CameraProfileId.Parking) {
            CameraCalibrationPreset.saveParking(
                    preferences, parkingProfile((CameraProfileId.Parking) id));
            saved = true;
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int pane = reverseProfileIndex(reverse);
            if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                    || pane == ReverseCameraLayout.WIDGET_PANE_ID) {
                CameraCalibrationPreset.saveReverseElement(preferences, pane);
            } else if (reverse.getSource() == ReverseSource.Front) {
                CameraCalibrationPreset.saveReverseFront(
                        preferences, pane);
            } else {
                CameraCalibrationPreset.saveReverse(preferences, pane);
            }
            saved = true;
        }
        showProductionPresetToast("save", id, saved,
                runtimeText(R.string.runtime_preset_saved),
                runtimeText(R.string.runtime_preset_save_failed));
        if (productionUi != null) productionUi.reload();
    }

    private void loadProductionPreset(CameraProfileId id) {
        boolean loaded = false;
        if (id instanceof CameraProfileId.Blind) {
            loaded = CameraCalibrationPreset.loadCamera(
                    preferences, blindProfile((CameraProfileId.Blind) id));
        } else if (id instanceof CameraProfileId.Parking) {
            loaded = CameraCalibrationPreset.loadParking(
                    preferences, parkingProfile((CameraProfileId.Parking) id));
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int pane = reverseProfileIndex(reverse);
            loaded = pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                    || pane == ReverseCameraLayout.WIDGET_PANE_ID
                    ? CameraCalibrationPreset.loadReverseElement(preferences, pane)
                    : reverse.getSource() == ReverseSource.Front
                    ? CameraCalibrationPreset.loadReverseFront(
                            preferences, pane)
                    : CameraCalibrationPreset.loadReverse(preferences, pane);
        }
        showProductionPresetToast("load", id, loaded,
                runtimeText(R.string.runtime_preset_loaded_short),
                runtimeText(R.string.runtime_preset_missing));
        if (loaded) notifyProductionProfileChanged(id);
    }

    private void transferProductionProfile(CameraProfileId id) {
        boolean transferred = false;
        CameraProfileId target = oppositeProductionProfile(id);
        try {
            if (id instanceof CameraProfileId.Blind) {
                CameraCalibrationPreset.mirrorCamera(
                        preferences, blindProfile((CameraProfileId.Blind) id));
                transferred = true;
            } else if (id instanceof CameraProfileId.Parking) {
                transferred = CameraCalibrationPreset.mirrorParking(
                        preferences, parkingProfile((CameraProfileId.Parking) id));
            } else if (id instanceof CameraProfileId.Reverse) {
                CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
                if (reverse.getElement() == ReverseElement.Rear) {
                    transferred = reverse.getSource() == ReverseSource.Rear
                            ? CameraCalibrationPreset.copyCentralReverseRearToFront(preferences)
                            : CameraCalibrationPreset.copyCentralReverseFrontToRear(preferences);
                } else {
                    transferred = reverse.getSource() == ReverseSource.Front
                            ? CameraCalibrationPreset.mirrorReverseFront(
                                    preferences, reverseProfileIndex(reverse))
                            : CameraCalibrationPreset.mirrorReverse(
                                    preferences, reverseProfileIndex(reverse));
                }
            }
        } catch (RuntimeException error) {
            record("camera_preset_error", "operation", "transfer", "error", error.toString());
        }
        showProductionPresetToast("transfer", id, transferred,
                runtimeText(R.string.runtime_settings_transferred) + " • "
                        + localizedProductionProfileLabel(target),
                runtimeText(R.string.runtime_transfer_failed));
        if (transferred) notifyProductionProfileChanged(id);
    }

    static CameraProfileId oppositeProductionProfile(CameraProfileId id) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfileId.Blind blind = (CameraProfileId.Blind) id;
            return new CameraProfileId.Blind(blind.getGroup(),
                    blind.getSide() == CameraSide.Left ? CameraSide.Right : CameraSide.Left);
        }
        if (id instanceof CameraProfileId.Parking) {
            int target = CameraCalibrationPreset.parkingMirrorTarget(
                    parkingProfile((CameraProfileId.Parking) id));
            return target < 0 ? null : new CameraProfileId.Parking(ParkingView.values()[target]);
        }
        if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            if (reverse.getElement() == ReverseElement.Rear) {
                return new CameraProfileId.Reverse(ReverseElement.Rear,
                        reverse.getSource() == ReverseSource.Rear
                                ? ReverseSource.Front : ReverseSource.Rear);
            }
            if (reverse.getElement() != ReverseElement.RearLeft
                    && reverse.getElement() != ReverseElement.RearRight) return null;
            return new CameraProfileId.Reverse(
                    reverse.getElement() == ReverseElement.RearLeft
                            ? ReverseElement.RearRight : ReverseElement.RearLeft,
                    reverse.getSource());
        }
        return null;
    }

    private String localizedProductionProfileLabel(CameraProfileId id) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfileId.Blind blind = (CameraProfileId.Blind) id;
            if (blind.getGroup() == CameraGroup.Front) {
                return runtimeText(blind.getSide() == CameraSide.Right
                        ? R.string.runtime_profile_front_right
                        : R.string.runtime_profile_front_left);
            }
            return runtimeText(blind.getSide() == CameraSide.Right
                    ? R.string.runtime_profile_rear_right : R.string.runtime_profile_rear_left);
        }
        if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            if (reverse.getSource() == ReverseSource.Front) {
                if (reverse.getElement() == ReverseElement.Rear) {
                    return runtimeText(R.string.runtime_profile_front);
                }
                return runtimeText(reverse.getElement() == ReverseElement.RearRight
                        ? R.string.runtime_profile_front_right
                        : R.string.runtime_profile_front_left);
            }
            if (reverse.getElement() == ReverseElement.Rear) {
                return runtimeText(R.string.runtime_profile_rear);
            }
            return runtimeText(reverse.getElement() == ReverseElement.RearRight
                    ? R.string.runtime_profile_rear_right : R.string.runtime_profile_rear_left);
        }
        if (id instanceof CameraProfileId.Parking) {
            switch (((CameraProfileId.Parking) id).getView().ordinal()) {
                case 0: return runtimeText(R.string.runtime_profile_parking_fl);
                case 1: return runtimeText(R.string.runtime_profile_parking_front);
                case 2: return runtimeText(R.string.runtime_profile_parking_fr);
                case 3: return runtimeText(R.string.runtime_profile_parking_rr);
                case 4: return runtimeText(R.string.runtime_profile_parking_rear);
                case 5: return runtimeText(R.string.runtime_profile_parking_rl);
                case 6: return runtimeText(R.string.runtime_profile_parking_left);
                case 7: return runtimeText(R.string.runtime_profile_parking_right);
                default: break;
            }
        }
        return runtimeText(R.string.runtime_profile_unknown);
    }

    private void showProductionPresetToast(
            String operation, Object profile, boolean success,
            String successMessage, String failureMessage) {
        record("camera_preset", "operation", operation,
                "outcome", success ? "success" : "failure",
                "profile", profile == null ? "unknown" : profile.toString());
        showProductionTopToast(success ? successMessage : failureMessage);
    }

    private void showSettingsResultToast(String message) {
        // Application-owned text toast survives the import's Activity recreation.
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
    }

    @SuppressWarnings("deprecation")
    private void showProductionTopToast(String message) {
        if (activityDestroyed || !activityResumed) return;
        // Match the accepted Preview foreground toast; text-only toasts ignore TOP on Android 12+.
        TextView label = new TextView(this);
        label.setText(message);
        label.setTextSize(14f);
        label.setTextColor(Color.WHITE);
        label.setGravity(Gravity.CENTER);
        label.setMaxWidth(getResources().getDisplayMetrics().widthPixels - dp(48));
        label.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF018212C);
        background.setCornerRadius(dp(8));
        label.setBackground(background);
        Toast toast = new Toast(this);
        toast.setDuration(Toast.LENGTH_LONG);
        toast.setView(label);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, dp(24));
        toast.show();
    }

    private void resetProductionReverseLayout(
            ReverseElement element, com.byd.extend.ui.DisplayTarget displayTarget) {
        boolean reset = false;
        try {
            if (element != null) {
                ReverseCameraController.resetSelectedLayout(preferences, element.name(),
                        displayTarget == com.byd.extend.ui.DisplayTarget.Cluster
                                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET);
                reset = true;
            }
        } catch (RuntimeException error) {
            record("camera_preset_error", "operation", "reset_layout", "error", error.toString());
        }
        String label = element == null ? "" : localizedReverseElementLabel(element);
        showProductionPresetToast("reset_layout", element, reset,
                runtimeText(R.string.runtime_layout_defaults_restored, label),
                runtimeText(R.string.runtime_layout_reset_failed));
        if (reset) {
            applyProductionReverseState();
            CameraHelperService.reverseCameraSettingsChanged(this);
        }
    }

    private void resetProductionProfile(CameraProfileId id, CommandId command) {
        boolean reset = false;
        try {
            reset = resetProductionProfileSettings(preferences, id, command,
                    productionDisplayGeometry(DisplayTarget.Tablet));
        } catch (RuntimeException error) {
            record("camera_preset_error", "operation", command.name(), "error", error.toString());
        }
        String label = localizedProductionResetScope(command)
                + " • " + localizedProductionProfileLabel(id);
        showProductionPresetToast("reset", id, reset,
                runtimeText(R.string.runtime_reset_defaults_restored, label),
                runtimeText(R.string.runtime_reset_failed_for, label));
        if (reset) notifyProductionProfileChanged(id);
    }

    private String localizedProductionResetScope(CommandId command) {
        if (command == CommandId.ResetProfilePlacement) {
            return runtimeText(R.string.runtime_reset_placement);
        }
        if (command == CommandId.ResetProfileOriginal) {
            return runtimeText(R.string.runtime_reset_original);
        }
        if (command == CommandId.ResetProfileCorrection) {
            return runtimeText(R.string.runtime_reset_correction);
        }
        return runtimeText(R.string.runtime_reset_output);
    }

    private String localizedReverseElementLabel(ReverseElement element) {
        if (element == null) return "";
        switch (element) {
            case Background: return runtimeText(R.string.runtime_reverse_background);
            case Widget: return runtimeText(R.string.runtime_reverse_widget);
            case Rear: return runtimeText(R.string.runtime_reverse_rear);
            case RearLeft: return runtimeText(R.string.runtime_reverse_rear_left);
            case RearRight: return runtimeText(R.string.runtime_reverse_rear_right);
            default: return element.name();
        }
    }

    static boolean resetProductionProfileSettings(
            SharedPreferences preferences, CameraProfileId id, CommandId command) {
        return resetProductionProfileSettings(preferences, id, command, null);
    }

    static boolean resetProductionProfileSettings(
            SharedPreferences preferences, CameraProfileId id, CommandId command,
            CameraDisplayGeometry tablet) {
        if (command == CommandId.ResetProfilePlacement) {
            if (id instanceof CameraProfileId.Blind) {
                if (tablet == null) return false;
                CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
                CameraCalibrationPreset.resetCameraPlacement(preferences, profile,
                        BlindSpotOverlayController.readTarget(preferences, profile),
                        tablet.getWidth(), tablet.getHeight(), tablet.getMarginLeft(),
                        tablet.getMarginTop(), tablet.getMarginBottom());
            } else if (id instanceof CameraProfileId.Parking) {
                ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
                float[] x = {0f, .5f, 1f, 1f, .5f, 0f, 0f, 1f};
                float[] y = {0f, 0f, 0f, 1f, 1f, 1f, .5f, .5f};
                String prefix = parkingPlacementPrefix(profile);
                preferences.edit().putInt(prefix + "scale", ParkingCameraSettings.DEFAULT_SCALE_PERCENT)
                        .putFloat(prefix + "x", x[profile.id])
                        .putFloat(prefix + "y", y[profile.id])
                        .putBoolean(parkingScaleSyncKey(), false).apply();
            } else return false;
            return true;
        }
        if (command != CommandId.ResetProfileOriginal
                && command != CommandId.ResetProfileCorrection
                && command != CommandId.ResetProfileOutput) return false;
        CameraCalibrationPreset.Stage stage = command == CommandId.ResetProfileOriginal
                ? CameraCalibrationPreset.Stage.ORIGINAL
                : command == CommandId.ResetProfileCorrection
                ? CameraCalibrationPreset.Stage.CORRECTION : CameraCalibrationPreset.Stage.OUTPUT;
        if (id instanceof CameraProfileId.Blind) {
            CameraCalibrationPreset.resetCameraStage(
                    preferences, blindProfile((CameraProfileId.Blind) id), stage);
        } else if (id instanceof CameraProfileId.Parking) {
            CameraCalibrationPreset.resetParkingStage(
                    preferences, parkingProfile((CameraProfileId.Parking) id), stage);
        } else if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            if (reverse.getElement() != ReverseElement.Rear
                    && reverse.getElement() != ReverseElement.RearLeft
                    && reverse.getElement() != ReverseElement.RearRight) return false;
            CameraCalibrationPreset.resetReverseStage(preferences, reverseProfileIndex(reverse),
                    reverse.getSource() == ReverseSource.Front, stage);
        } else return false;
        return true;
    }

    private void saveProductionReverseGeometry(NumberTarget.ReverseGeometry target, float value) {
        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
        int pane = reverseElementIndex(target.getElement());
        int targetId = target.getDisplayTarget() == com.byd.extend.ui.DisplayTarget.Cluster
                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
        ReverseCameraLayout.Rect current = layout.rectFor(pane, targetId);
        float normalized = value / 100.0f;
        float left = target.getField() == ReverseGeometryNumber.X ? normalized : current.left;
        float top = target.getField() == ReverseGeometryNumber.Y ? normalized : current.top;
        float width = target.getField() == ReverseGeometryNumber.Width ? normalized : current.width;
        float height = target.getField() == ReverseGeometryNumber.Height ? normalized : current.height;
        ReverseCameraLayout.Rect next = pane == ReverseCameraLayout.WIDGET_PANE_ID
                ? ReverseCameraLayout.widgetDestination(left, top, width, height)
                : ReverseCameraLayout.destination(left, top, width, height);
        layout = ReverseCameraLayout.withRect(layout, pane, targetId, next);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void nudgeProductionReverse(float x, float y) {
        ReverseUiState reverse = productionUi.getState().getReverse();
        int pane = reverseElementIndex(reverse.getSelectedElement());
        int target = reverse.getSelectedTarget() == com.byd.extend.ui.DisplayTarget.Cluster
                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
        ReverseCameraLayout layout = ReverseCameraLayout.move(
                ReverseCameraController.loadRawLayout(preferences), pane, x, y, target);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void changeProductionReverseZ(boolean raise) {
        ReverseUiState reverse = productionUi.getState().getReverse();
        int pane = reverseElementIndex(reverse.getSelectedElement());
        if (pane <= 0) return;
        int target = reverse.getSelectedTarget() == com.byd.extend.ui.DisplayTarget.Cluster
                ? CameraDisplayTarget.CLUSTER : CameraDisplayTarget.TABLET;
        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
        layout = raise ? ReverseCameraLayout.raise(layout, pane, target)
                : ReverseCameraLayout.lower(layout, pane, target);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void applyReverseSteering() {
        mainHandler.removeCallbacks(reverseSteeringExpired);
        if (reverseCameraPreview == null || activityDestroyed) return;
        long now = SystemClock.elapsedRealtime();
        float angle = activityResumed ? reverseSteeringSample.freshAngle(now) : Float.NaN;
        reverseCameraPreview.setSteeringShift(angle,
                reverseSteeringSample.minimumDegrees, reverseSteeringSample.maximumDegrees,
                ReverseGuidanceSettings.isCameraShiftWithSteeringEnabled(preferences, false),
                ReverseGuidanceSettings.isCameraShiftWithSteeringEnabled(preferences, true));
        if (Float.isFinite(angle)) mainHandler.postDelayed(reverseSteeringExpired,
                Math.max(1, reverseSteeringSample.observedMs
                        + ReverseSteeringSample.MAX_AGE_MS + 1 - now));
    }

    private void applyProductionReverseState() {
        if (productionUi != null) productionUi.reload();
        reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
        reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
        reverseFrontRawCalibrationLayout = ReverseCameraController.loadFrontRawLayout(preferences);
        reverseFrontCameraLayout = ReverseCameraController.loadFrontLayout(preferences);
        if (reverseCameraPreview != null) {
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
            applyReversePreviewDewarpConfigs();
            reverseCameraPreview.applyVisibility(
                    ReverseCameraController.loadVisibilityMask(preferences));
            ReverseCameraController.applyBorders(preferences, reverseCameraPreview);
            applyReverseSteering();
        }
        if (productionReverseEditor != null) {
            boolean front = productionUi != null
                    && productionUi.getState().getReverse().getShowFront();
            ReverseCameraController.applyBorders(
                    preferences, productionReverseEditor, front);
        }
    }

    @Override
    public void onMotionPreviewSurfaceAvailable(
            MotionCompositionPreviewView view, Surface surface) {
        CameraHostKind kind = motionPreviewKind(view.getDirection());
        if (productionCameraHosts.get(kind) != view || surface == null || !surface.isValid()) {
            if (surface != null) view.releaseSurface(surface);
            return;
        }
        MotionPreviewOutput previous = motionPreviewOutputs.get(kind);
        if (previous != null && previous.view == view && previous.surface == surface) return;
        if (previous != null) releaseMotionPreviewOutput(previous, "motion_preview_surface_replaced");
        MotionPreviewOutput output = new MotionPreviewOutput(
                kind, view.getDirection(), view, surface, nextActivityCameraRequestId());
        motionPreviewOutputs.put(kind, output);
        view.showUnavailable();
        requestCameraPermissionIfNeeded();
        openPendingMotionPreviews();
    }

    @Override
    public void onMotionPreviewSurfaceDestroyed(MotionCompositionPreviewView view) {
        MotionPreviewOutput output = motionPreviewOutputs.get(motionPreviewKind(view.getDirection()));
        if (output != null && output.view == view) {
            releaseMotionPreviewOutput(output, "motion_preview_surface_destroyed");
        }
    }

    private void openPendingMotionPreviews() {
        if (!activityResumed || activityDestroyed || shutdownRequested || helper == null) return;
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (!motionPreviewOutputs.isEmpty()) requestCameraPermissionIfNeeded();
            return;
        }
        if (!cameraDiscovered) return;
        IBinder current = helper;
        for (MotionPreviewOutput output : new ArrayList<>(motionPreviewOutputs.values())) {
            if (output.opened || output.openQueued || output.openUncertain || output.closeQueued
                    || output.releaseSurfaceWhenClosed || output.surface == null
                    || !output.surface.isValid()
                    || productionCameraHosts.get(output.kind) != output.view) continue;
            output.openQueued = true;
            output.cameraOwner = current;
            output.view.showUnavailable();
            ipcExecutor.execute(() -> transactOpenMotionPreview(current, output));
        }
    }

    private void closeMotionCompositionPreviews(String reason, boolean releaseSurface) {
        for (MotionPreviewOutput output : new ArrayList<>(motionPreviewOutputs.values())) {
            closeMotionPreviewOutput(output, reason, releaseSurface);
        }
    }

    private void releaseMotionPreviewOutput(MotionPreviewOutput output, String reason) {
        output.releaseSurfaceWhenClosed = true;
        motionPreviewOutputs.remove(output.kind, output);
        closeMotionPreviewOutput(output, reason, true);
    }

    private void closeMotionPreviewOutput(
            MotionPreviewOutput output, String reason, boolean releaseSurface) {
        if (releaseSurface) output.releaseSurfaceWhenClosed = true;
        output.view.showUnavailable();
        if (output.closeQueued) return;
        if (!output.opened && !output.openQueued && !output.openUncertain) {
            finishMotionPreviewClose(output);
            return;
        }
        IBinder current = output.cameraOwner;
        if (current == null || !current.isBinderAlive()) {
            output.opened = false;
            output.openQueued = false;
            finishMotionPreviewClose(output);
            return;
        }
        output.closeQueued = true;
        output.openUncertain = true;
        output.opened = false;
        output.openQueued = false;
        ipcExecutor.execute(() -> transactCloseMotionPreview(current, output, reason));
    }

    private void finishMotionPreviewClose(MotionPreviewOutput output) {
        output.closeQueued = false;
        output.openUncertain = false;
        output.cameraOwner = null;
        if (output.releaseSurfaceWhenClosed) {
            if (output.surface != null) output.view.releaseSurface(output.surface);
            output.surface = null;
        } else if (activityResumed && motionPreviewOutputs.get(output.kind) == output) {
            openPendingMotionPreviews();
        }
    }

    private void transactOpenMotionPreview(IBinder current, MotionPreviewOutput output) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            CameraGpuOutput.writeSurface(data, output.surface);
            data.writeInt(output.direction);
            data.writeInt(output.requestId);
            requireTransaction(current, CameraHelperMain.TX_OPEN_MOTION_PREVIEW, data, reply);
            String response = reply.readString();
            JSONObject json = new JSONObject(response);
            String kind = json.optString("kind");
            boolean opened = "camera_opened".equals(kind);
            record("motion_preview_open", "direction", output.direction,
                    "request_id", output.requestId, "result", kind,
                    "error", opened ? "" : json.optString("error"));
            runOnUiThread(() -> {
                output.openQueued = false;
                if (output.closeQueued || output.releaseSurfaceWhenClosed
                        || motionPreviewOutputs.get(output.kind) != output) return;
                output.opened = opened;
                output.openUncertain = false;
                if (!opened) output.view.showUnavailable();
            });
        } catch (Throwable error) {
            record("motion_preview_open_error", "direction", output.direction,
                    "request_id", output.requestId, "error", error.toString());
            runOnUiThread(() -> {
                output.openQueued = false;
                if (output.closeQueued || output.releaseSurfaceWhenClosed
                        || motionPreviewOutputs.get(output.kind) != output) return;
                output.opened = false;
                output.openUncertain = true; // A failed reply does not prove that the producer rejected the Surface.
                output.view.showUnavailable();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactCloseMotionPreview(
            IBinder current, MotionPreviewOutput output, String reason) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(output.direction);
            data.writeInt(output.requestId);
            requireTransaction(current, CameraHelperMain.TX_CLOSE_MOTION_PREVIEW, data, reply);
            String response = reply.readString();
            String kind = new JSONObject(response).optString("kind");
            boolean closed = "camera_closed".equals(kind) || "already_closed".equals(kind)
                    || "camera_close_ignored".equals(kind);
            record("motion_preview_close", "direction", output.direction,
                    "request_id", output.requestId, "reason", reason,
                    "result", kind, "closed", closed);
            runOnUiThread(() -> {
                if (closed) finishMotionPreviewClose(output);
                else {
                    output.closeQueued = false;
                    if (!output.releaseSurfaceWhenClosed && activityResumed
                            && motionPreviewOutputs.get(output.kind) == output) {
                        openPendingMotionPreviews();
                    }
                }
            });
        } catch (Throwable error) {
            record("motion_preview_close_error", "direction", output.direction,
                    "request_id", output.requestId, "reason", reason,
                    "error", error.toString());
            runOnUiThread(() -> {
                output.closeQueued = false;
                if (activityResumed && !output.releaseSurfaceWhenClosed
                        && motionPreviewOutputs.get(output.kind) == output) {
                    openPendingMotionPreviews();
                }
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static boolean isMotionPreviewKind(CameraHostKind kind) {
        return kind == CameraHostKind.MotionFrontPreview
                || kind == CameraHostKind.MotionRearPreview
                || kind == CameraHostKind.MotionLeftPreview
                || kind == CameraHostKind.MotionRightPreview;
    }

    private static CameraHostKind motionPreviewKind(int direction) {
        switch (direction) {
            case 0: return CameraHostKind.MotionFrontPreview;
            case 1: return CameraHostKind.MotionRearPreview;
            case 2: return CameraHostKind.MotionLeftPreview;
            case 3: return CameraHostKind.MotionRightPreview;
            default: throw new IllegalArgumentException("Motion direction must be in range 0..3");
        }
    }

    private static int motionPreviewDirection(CameraHostKind kind) {
        if (kind == CameraHostKind.MotionFrontPreview) return 0;
        if (kind == CameraHostKind.MotionRearPreview) return 1;
        if (kind == CameraHostKind.MotionLeftPreview) return 2;
        if (kind == CameraHostKind.MotionRightPreview) return 3;
        throw new IllegalArgumentException("Not a Motion preview host: " + kind);
    }

    @Override
    public View obtainProductionCameraHost(CameraHostSlot slot) {
        CameraHostKind kind = slot.getKind();
        if (isProductionCalibrationKind(kind)) ensureProductionCalibrationHosts();
        View existing = productionCameraHosts.get(kind);
        if (existing != null) {
            detachFromParent(existing);
            if (isProductionCalibrationKind(kind)) {
                calibrationHostBundle.activateStage(kind, existing);
            }
            productionCameraSlots.put(kind, slot);
            return existing;
        }
        View created;
        if (isMotionPreviewKind(kind)) {
            created = new MotionCompositionPreviewView(this, motionPreviewDirection(kind),
                    runtimeText(R.string.motion_camera_unavailable), this);
        } else if (isProductionPlacementKind(kind)) created = createProductionPlacementHost();
        else if (kind == CameraHostKind.ReverseComposition) {
            created = createProductionReverseHost();
        } else if (kind == CameraHostKind.Direct) {
            created = createProductionDirectHost();
        } else {
            created = createProductionAvmHost();
        }
        productionCameraHosts.put(kind, created);
        productionCameraSlots.put(kind, slot);
        return created;
    }

    @Override
    public void updateProductionCameraHost(View view, CameraHostSlot slot) {
        CameraHostSlot previousSlot = productionCameraSlots.get(slot.getKind());
        productionCameraSlots.put(slot.getKind(), slot);
        if (isMotionPreviewKind(slot.getKind())) {
            openPendingMotionPreviews();
            return;
        }
        CameraProfileId profile = slot.getProfile();
        if (isProductionCalibrationKind(slot.getKind()) && profile != null) {
            if (calibrationHostProfile != null
                    && !calibrationHostProfile.equals(profile)
                    && productionUi != null) {
                // Reused calibration owners emit fallback callbacks only on a
                // boolean transition; explicitly clear the old profile before
                // publishing the new owner's current state.
                productionUi.setCalibrationRawFallback(calibrationHostProfile, false);
            }
            if (calibrationHostProfile == null
                    || !calibrationHostProfile.equals(profile)
                    || profile instanceof CameraProfileId.Mirror
                    && !java.util.Objects.equals(calibrationHostSourceIndex, slot.getSourceIndex())) {
                productionCalibrationHostGeneration++;
                productionCalibrationGestureStart = null;
                productionCalibrationGestureActive = false;
                productionCalibrationGestureTransformChanged = false;
                bindProductionCalibrationOverlayListeners();
            }
            calibrationHostProfile = profile;
            calibrationHostSourceIndex = slot.getSourceIndex();
        }
        if (isProductionPlacementKind(slot.getKind()) && profile != null
                && previousSlot != null && previousSlot.getProfile() != null
                && !previousSlot.getProfile().equals(profile) && productionUi != null) {
            productionUi.setCalibrationRawFallback(previousSlot.getProfile(), false);
        }
        if (profile != null) {
            configureProductionCameraProfile(profile, slot.getSourceIndex());
            if (isProductionCalibrationKind(slot.getKind())) {
                boolean rawFallback = calibrationPreview != null
                        && calibrationPreview.usesRawFallback();
                updateProductionCalibrationOverlays(
                        profile, loadProductionCalibrationDewarp(profile),
                        rawFallback);
                publishRawFallbackState(profile, calibrationPreview);
            } else if (isProductionPlacementKind(slot.getKind())) {
                publishRawFallbackState(profile, cameraPreview);
            }
        }
        if (slot.getKind() == CameraHostKind.Direct && slot.getSourceIndex() != null
                && activeDirectCameraIndex != slot.getSourceIndex()) {
            openDirectCamera(slot.getSourceIndex());
        } else if (slot.getKind() == CameraHostKind.Avm && slot.getModeIndex() != null) {
            int mode = clamp(slot.getModeIndex(), 0, StockAvmPreview.horizontalLayoutCount() - 1);
            int viewpoint = StockAvmPreview.horizontalViewpoint(mode);
            if (activeCameraViewpoint != viewpoint) openStockAvm(viewpoint, true);
        } else if (slot.getKind() == CameraHostKind.ReverseComposition
                && productionReverseEditor != null) {
            syncProductionReverseDisplayTarget(slot.getDisplayTarget());
            productionReverseEditorEditable = slot.getEditable();
            productionReverseEditor.setEditable(productionReverseEditorEditable);
            // The placement editor is a conditional sibling of the composition.  Hide the
            // entire editor in Parameters so its outlines/selection cannot cover live panes.
            productionReverseEditor.setVisibility(
                    productionReverseEditorEditable ? View.VISIBLE : View.GONE);
            productionReverseEditor.setLayoutModel(reverseCameraLayout);
            if (slot.getReverseElement() != null) {
                int selected = reverseElementIndex(slot.getReverseElement());
                if (productionReverseEditor.selectedCamera() != selected) {
                    productionReverseEditor.setSelectedCameraSilently(selected);
                }
            }
            // Visibility and widget state are persisted independently from the editor host.  Push
            // both on every update so Compose recomposition cannot resurrect hidden panes/widget.
            reverseCameraPreview.applyVisibility(
                    ReverseCameraController.loadVisibilityMask(preferences));
            reverseCameraPreview.setWidgetVisible(
                    ReverseCameraController.loadWidgetVisible(preferences));
            ReverseCameraController.applyBorders(preferences, reverseCameraPreview);
            ReverseCameraController.applyBorders(preferences, productionReverseEditor,
                    productionUi != null
                            && productionUi.getState().getReverse().getShowFront());
        }
        refreshProductionBorderOverlays();
    }

    @Override
    public void releaseProductionCameraHost(View view, CameraHostSlot slot) {
        if (productionCameraHosts.get(slot.getKind()) != view) return;
        if (isMotionPreviewKind(slot.getKind())) {
            MotionPreviewOutput output = motionPreviewOutputs.get(slot.getKind());
            if (output != null && output.view == view) {
                releaseMotionPreviewOutput(output, "motion_preview_host_released");
            }
            productionCameraHosts.remove(slot.getKind(), view);
            productionCameraSlots.remove(slot.getKind());
            return;
        }
        if (isProductionCalibrationKind(slot.getKind())) {
            // Compose may release one conditional stage (notably Corrected when correction is
            // toggled off) while Output and Original still own the same camera input.  Keep the
            // retained calibration bundle alive; detach only the mirror surface represented by
            // this host. Explicit tab/workspace teardown owns owner/input retirement.
            calibrationHostBundle.releaseStage(slot.getKind(), view, () -> {
                if (calibrationPreview == null) return;
                if (slot.getKind() == CameraHostKind.CalibrationOriginal) {
                    calibrationPreview.setRawMirrorTexture(null);
                } else if (slot.getKind() == CameraHostKind.CalibrationCorrected) {
                    calibrationPreview.setCorrectedMirrorTexture(null);
                }
            });
            return;
        }
        productionCameraHosts.remove(slot.getKind(), view);
        productionCameraSlots.remove(slot.getKind());
        if (isProductionPlacementKind(slot.getKind()) && cameraPreview != null) {
            if (slot.getProfile() != null && productionUi != null) {
                productionUi.setCalibrationRawFallback(slot.getProfile(), false);
            }
            cameraPreview.retireCameraInput();
            cameraPreview = null;
            cameraPreviewCover = null;
            productionPlacementBorder = null;
            productionPlacementCropMask = null;
            productionPlacementHost = null;
        } else if (slot.getKind() == CameraHostKind.ReverseComposition
                && reverseCameraPreview != null) {
            clearReverseFallbackState();
            reverseCameraPreview.setCallback(null);
            reverseCameraPreview.retirePreviewInputs();
            reverseCameraPreview = null;
            productionReverseEditor = null;
            productionReverseHost = null;
            productionReverseEditorEditable = false;
        } else if (slot.getKind() == CameraHostKind.Direct) {
            directCameraPreview = null;
            directCameraPreviewCover = null;
            productionDirectHost = null;
            directCameraSurfaceReady = false;
        } else if (slot.getKind() == CameraHostKind.Avm) {
            retireDebugOutput();
            debugPreview = null;
            debugPreviewCover = null;
            productionAvmHost = null;
            debugSurfaceReady = false;
        }
    }

    private static boolean isProductionPlacementKind(CameraHostKind kind) {
        return kind == CameraHostKind.Placement || kind == CameraHostKind.Mirror;
    }

    static boolean slotBelongsToTab(CameraHostSlot slot, int tab) {
        if (isMotionPreviewKind(slot.getKind())) return tab == TAB_GUARD;
        if (slot.getProfile() instanceof CameraProfileId.Mirror) {
            return tab == TAB_REARVIEW_MIRROR;
        }
        if (isProductionCalibrationKind(slot.getKind())) {
            // Compose calibration is embedded in each real profile root; the legacy standalone
            // calibration tab must not retain an owner when Blind/Parking/Reverse is exited.
            CameraProfileId profile = slot.getProfile();
            if (profile instanceof CameraProfileId.Blind) return tab == TAB_CAMERAS;
            if (profile instanceof CameraProfileId.Parking) return tab == TAB_PARKING_CAMERAS;
            if (profile instanceof CameraProfileId.Reverse) return tab == TAB_REVERSE_CAMERAS;
            return tab == TAB_CAMERA_CALIBRATION;
        }
        if (slot.getKind() == CameraHostKind.Direct || slot.getKind() == CameraHostKind.Avm) {
            return tab == TAB_CAMERA_DEBUG;
        }
        if (slot.getKind() == CameraHostKind.ReverseComposition) {
            return tab == TAB_REVERSE_CAMERAS;
        }
        CameraProfileId profile = slot.getProfile();
        if (profile instanceof CameraProfileId.Blind) return tab == TAB_CAMERAS;
        if (profile instanceof CameraProfileId.Parking) return tab == TAB_PARKING_CAMERAS;
        return profile instanceof CameraProfileId.Reverse && tab == TAB_REVERSE_CAMERAS;
    }

    private void releaseProductionHostsForTab(int tab) {
        boolean releaseCalibration = false;
        for (Map.Entry<CameraHostKind, CameraHostSlot> entry
                : productionCameraSlots.entrySet()) {
            if (isProductionCalibrationKind(entry.getKey())
                    && slotBelongsToTab(entry.getValue(), tab)) {
                releaseCalibration = true;
                break;
            }
        }
        if (releaseCalibration) releaseProductionCalibrationHosts();
        for (Map.Entry<CameraHostKind, CameraHostSlot> entry
                : new EnumMap<>(productionCameraSlots).entrySet()) {
            if (!slotBelongsToTab(entry.getValue(), tab)) continue;
            View view = productionCameraHosts.remove(entry.getKey());
            productionCameraSlots.remove(entry.getKey());
            if (isProductionPlacementKind(entry.getKey()) && cameraPreview != null) {
                cameraPreview.retireCameraInput();
            } else if (entry.getKey() == CameraHostKind.ReverseComposition
                    && reverseCameraPreview != null) {
                clearReverseFallbackState();
                reverseCameraPreview.setCallback(null);
                reverseCameraPreview.retirePreviewInputs();
            } else if (isMotionPreviewKind(entry.getKey())) {
                MotionPreviewOutput output = motionPreviewOutputs.get(entry.getKey());
                if (output != null) releaseMotionPreviewOutput(
                        output, "motion_preview_tab_released");
            }
        }
        if (tab == TAB_CAMERAS || tab == TAB_PARKING_CAMERAS || tab == TAB_REARVIEW_MIRROR) {
            cameraPreview = null;
            cameraPreviewCover = null;
            productionPlacementBorder = null;
            productionPlacementCropMask = null;
            productionPlacementHost = null;
            cameraSurfaceReady = false;
            calibrationSurfaceReady = false;
        } else if (tab == TAB_REVERSE_CAMERAS) {
            reverseCameraPreview = null;
            productionReverseEditor = null;
            productionReverseHost = null;
            productionReverseEditorEditable = false;
            reverseCameraSurfacesReady = false;
            calibrationSurfaceReady = false;
        } else if (tab == TAB_CAMERA_DEBUG) {
            retireDebugOutput();
            directCameraPreview = null;
            debugPreview = null;
            directCameraPreviewCover = null;
            debugPreviewCover = null;
            productionDirectHost = null;
            productionAvmHost = null;
            directCameraSurfaceReady = false;
            debugSurfaceReady = false;
        }
    }

    private void releaseAllProductionCameraHosts() {
        closeMotionCompositionPreviews("motion_preview_activity_destroyed", true);
        motionPreviewOutputs.clear();
        retireDebugOutput();
        releaseProductionCalibrationHosts();
        if (cameraPreview != null) cameraPreview.retireCameraInput();
        if (reverseCameraPreview != null) {
            clearReverseFallbackState();
            reverseCameraPreview.setCallback(null);
            reverseCameraPreview.retirePreviewInputs();
        }
        productionCameraHosts.clear();
        productionCameraSlots.clear();
        cameraPreview = null;
        reverseCameraPreview = null;
        productionReverseEditor = null;
        productionReverseHost = null;
        productionReverseEditorEditable = false;
        directCameraPreview = null;
        debugPreview = null;
        cameraPreviewCover = null;
        productionPlacementBorder = null;
        productionPlacementCropMask = null;
        directCameraPreviewCover = null;
        debugPreviewCover = null;
        productionPlacementHost = null;
        productionDirectHost = null;
        productionAvmHost = null;
        transientCornerRadiusDp = null;
        transientTransparencyPercent = null;
        transientProfilePreviewId = null;
        transientProfilePreviewFov = null;
        transientProfilePreviewStrength = null;
        transientProfilePreviewRotation = null;
    }

    private View createProductionPlacementHost() {
        productionPlacementHost = cameraHostRoot();
        BlindSpotCameraView view = new BlindSpotCameraView(this);
        view.setAutomaticBufferQuality(CameraBufferQuality.load(preferences));
        view.setForceDewarpPipeline(true);
        view.setCallback(this);
        productionPlacementHost.addView(view, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        productionPlacementBorder = cameraBorderOverlay();
        productionPlacementHost.addView(productionPlacementBorder,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        productionPlacementCropMask = new CropMaskView(this);
        productionPlacementHost.addView(productionPlacementCropMask,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        view.setOutputCropMask(productionPlacementCropMask);
        cameraPreviewCover = blackCover();
        productionPlacementHost.addView(cameraPreviewCover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        cameraPreview = view;
        return productionPlacementHost;
    }

    private void ensureProductionCalibrationHosts() {
        if (calibrationHostBundle.isComplete(
                calibrationPreview, calibrationRawMirrorHost,
                calibrationCorrectedMirrorHost, calibrationOutputHost,
                calibrationRawMirror, calibrationCorrectedMirror)
                && calibrationOutputCropMask != null
                && productionCalibrationRawOverlay != null
                && productionCalibrationCorrectedOverlay != null) {
            // Stage AndroidViews can disappear independently; never infer owner death from a
            // missing conditional stage.  Restore map identity for a retained bundle instead.
            productionCameraHosts.put(CameraHostKind.CalibrationOriginal, calibrationRawMirrorHost);
            productionCameraHosts.put(CameraHostKind.CalibrationCorrected,
                    calibrationCorrectedMirrorHost);
            productionCameraHosts.put(CameraHostKind.CalibrationOutput, calibrationOutputHost);
            return;
        }
        // A retained owner without all three roots/maps cannot safely serve an obtain; retire the
        // incomplete bundle once before rebuilding all roots with one owner.
        if (calibrationHostBundle.hasAny() || calibrationPreview != null
                || calibrationRawMirrorHost != null
                || calibrationCorrectedMirrorHost != null || calibrationOutputHost != null
                || calibrationRawMirror != null || calibrationCorrectedMirror != null
                || calibrationOutputCropMask != null
                || productionCalibrationRawOverlay != null
                || productionCalibrationCorrectedOverlay != null) {
            releaseProductionCalibrationHosts();
        }
        BlindSpotCameraView owner = new BlindSpotCameraView(this);
        owner.setFusedOutput(false);
        owner.setAutomaticBufferQuality(CameraBufferQuality.load(preferences));
        owner.setForceDewarpPipeline(true);
        owner.setCallback(this);
        owner.setDewarpStatsSink(this::onCalibrationDewarpStats);
        TextureView raw = createProductionMirror(owner, true);
        TextureView corrected = createProductionMirror(owner, false);
        calibrationRawMirrorHost = cameraHostRoot();
        calibrationCorrectedMirrorHost = cameraHostRoot();
        calibrationOutputHost = cameraHostRoot();
        calibrationRawMirrorHost.addView(raw, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationCorrectedMirrorHost.addView(corrected, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationRawMirrorCover = blackCover();
        calibrationCorrectedMirrorCover = blackCover();
        calibrationPreviewCover = blackCover();
        calibrationRawMirrorHost.addView(calibrationRawMirrorCover,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationCorrectedMirrorHost.addView(calibrationCorrectedMirrorCover,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationOutputCropMask = new CropMaskView(this);
        productionCalibrationRawOverlay = new CameraCropOverlayView(this);
        productionCalibrationCorrectedOverlay = new CameraCropOverlayView(this);
        bindProductionCalibrationOverlayListeners();
        calibrationRawMirrorHost.addView(productionCalibrationRawOverlay,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationCorrectedMirrorHost.addView(productionCalibrationCorrectedOverlay,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationOutputHost.addView(owner, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        productionCalibrationBorder = cameraBorderOverlay();
        calibrationOutputHost.addView(productionCalibrationBorder,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationOutputHost.addView(calibrationOutputCropMask,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        owner.setOutputCropMask(calibrationOutputCropMask);
        calibrationOutputHost.addView(calibrationPreviewCover,
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationPreview = owner;
        calibrationRawMirror = raw;
        calibrationCorrectedMirror = corrected;
        calibrationHostBundle.bind(owner, calibrationRawMirrorHost,
                calibrationCorrectedMirrorHost, calibrationOutputHost, raw, corrected);
        productionCameraHosts.put(CameraHostKind.CalibrationOriginal, calibrationRawMirrorHost);
        productionCameraHosts.put(CameraHostKind.CalibrationCorrected,
                calibrationCorrectedMirrorHost);
        productionCameraHosts.put(CameraHostKind.CalibrationOutput, calibrationOutputHost);
    }

    private TextureView createProductionMirror(BlindSpotCameraView owner, boolean raw) {
        TextureView mirror = new TextureView(this);
        mirror.setOpaque(true);
        mirror.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(
                    SurfaceTexture texture, int width, int height) {
                if (!calibrationHostBundle.acceptMirror(owner, mirror, raw)) return;
                if (raw) owner.setRawMirrorTexture(texture);
                else owner.setCorrectedMirrorTexture(texture);
            }
            @Override public void onSurfaceTextureSizeChanged(
                    SurfaceTexture texture, int width, int height) {
                if (!calibrationHostBundle.acceptMirror(owner, mirror, raw)) return;
                owner.refreshMirrorBuffer(texture, raw);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                if (!calibrationHostBundle.acceptMirror(owner, mirror, raw)) return true;
                if (raw) owner.setRawMirrorTexture(null);
                else owner.setCorrectedMirrorTexture(null);
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
        return mirror;
    }

    private CameraDewarpConfig loadProductionCalibrationDewarp(CameraProfileId profile) {
        if (profile instanceof CameraProfileId.Mirror) return RearviewMirrorSettings.dewarp(preferences);
        if (profile instanceof CameraProfileId.Blind) {
            return CameraDewarpConfig.loadForProfile(
                    preferences, blindProfile((CameraProfileId.Blind) profile));
        }
        if (profile instanceof CameraProfileId.Parking) {
            return CameraDewarpConfig.loadForParking(
                    preferences, parkingProfile((CameraProfileId.Parking) profile));
        }
        if (profile instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) profile;
            int index = reverseProfileIndex(reverse);
            return reverse.getSource() == ReverseSource.Front
                    ? CameraDewarpConfig.loadForReverseFront(preferences, index)
                    : CameraDewarpConfig.loadForReverse(preferences, index);
        }
        return CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
    }

    private static int checkedStrength(CameraDewarpConfig config, float value) {
        if (!Float.isFinite(value) || value < 1 || value > 100 || value != Math.round(value)
                || !CameraCorrectionGeometry.strengthEditable(config.enabled, config.projection)) {
            throw new IllegalArgumentException("Correction strength unavailable or outside 1..100%");
        }
        return (int) value;
    }

    private void updateProductionCalibrationOverlays(
            CameraProfileId profile, CameraDewarpConfig dewarp, boolean rawFallback) {
        if (profile == null || dewarp == null) return;
        if (productionCalibrationRawOverlay != null) {
            productionCalibrationRawOverlay.setCrop(calibrationRawCrop.geometryOnly());
            productionCalibrationRawOverlay.setGridVisible(dewarp.enabled);
            productionCalibrationRawOverlay.setEnabled(true);
        }
        if (productionCalibrationCorrectedOverlay != null) {
            productionCalibrationCorrectedOverlay.setCrop(calibrationCorrectedCrop.geometryOnly());
            productionCalibrationCorrectedOverlay.setGridVisible(dewarp.enabled);
            productionCalibrationCorrectedOverlay.setEnabled(dewarp.enabled && !rawFallback);
        }
    }

    /** Rebinds retained overlay callbacks to the active calibration owner/profile generation. */
    private void bindProductionCalibrationOverlayListeners() {
        final long generation = productionCalibrationHostGeneration;
        final CameraCropOverlayView raw = productionCalibrationRawOverlay;
        final CameraCropOverlayView corrected = productionCalibrationCorrectedOverlay;
        if (raw != null) {
            raw.setListener((crop, finished) -> onProductionCalibrationCropChanged(
                    generation, false, crop, finished));
        }
        if (corrected != null) {
            corrected.setListener((crop, finished) -> onProductionCalibrationCropChanged(
                    generation, true, crop, finished));
        }
    }

    private void onProductionCalibrationCropChanged(
            long generation, boolean corrected, DirectCameraCrop crop, boolean finished) {
        if (generation != productionCalibrationHostGeneration) return;
        CameraProfileId profile = calibrationHostProfile;
        if (profile == null || calibrationPreview == null || crop == null) return;
        if (profile instanceof CameraProfileId.Mirror
                && !java.util.Objects.equals(calibrationHostSourceIndex,
                        RearviewMirrorSettings.cameraIndex(preferences))) return;
        CameraDewarpConfig dewarp = loadProductionCalibrationDewarp(profile);
        boolean rawFallback = calibrationPreview.usesRawFallback();
        if (corrected && (!dewarp.enabled || rawFallback)) {
            updateProductionCalibrationOverlays(profile, dewarp, rawFallback);
            return;
        }
        DirectCameraCrop base = corrected ? calibrationCorrectedCrop : calibrationRawCrop;
        if (!productionCalibrationGestureActive
                || productionCalibrationGestureCorrected != corrected) {
            productionCalibrationGestureStart = base;
            productionCalibrationGestureCorrected = corrected;
            productionCalibrationGestureTransformChanged = false;
            productionCalibrationGestureActive = true;
        }
        DirectCameraCrop gestureStart = productionCalibrationGestureStart;
        final DirectCameraCrop candidate;
        try {
            // Production overlays expose geometryOnly() crops; neutral metadata must not
            // overwrite the independently persisted Output transform.
            DirectCameraCrop geometry = crop.geometryOnly();
            candidate = base.withIndependentGeometry(
                    geometry.left, geometry.top, geometry.width, geometry.height);
        } catch (IllegalArgumentException invalid) {
            updateProductionCalibrationOverlays(profile, dewarp, rawFallback);
            return;
        }
        if (corrected) calibrationCorrectedCrop = candidate;
        else calibrationRawCrop = candidate;
        if (!corrected) {
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        }
        DirectCameraCrop active = dewarp.enabled && !rawFallback
                ? calibrationCorrectedCrop : calibrationRawCrop;
        calibrationPreview.applyDirectCameraCrop(active);
        updateProductionCalibrationOverlays(profile, dewarp, rawFallback);
        if (!finished) return;
        boolean changed = candidate.left != gestureStart.left
                || candidate.top != gestureStart.top
                || candidate.width != gestureStart.width
                || candidate.height != gestureStart.height;
        productionCalibrationGestureActive = false;
        productionCalibrationGestureStart = null;
        productionCalibrationGestureTransformChanged = false;
        if (!changed) return;
        try {
            saveProductionCalibrationCrop(profile, corrected, candidate);
        } catch (IllegalArgumentException invalid) {
            // The strict domain endpoint is authoritative; restore the last persisted value.
            configureProductionCameraProfile(profile, null);
            updateProductionCalibrationOverlays(
                    profile, loadProductionCalibrationDewarp(profile),
                    calibrationPreview.usesRawFallback());
        }
    }

    private void saveProductionCalibrationCrop(
            CameraProfileId profile, boolean corrected, DirectCameraCrop crop) {
        if (profile instanceof CameraProfileId.Mirror) {
            RearviewMirrorSettings model = new RearviewMirrorSettings(preferences);
            RearviewMirrorSettings.Settings before = model.load();
            RearviewMirrorSettings.Calibration calibration = mirrorCalibrationWithCrop(
                    before.calibration(before.activeFront()), corrected,
                    CameraPlacement.of(crop.left, crop.top, crop.width, crop.height));
            model.save(before.withCalibration(before.activeFront(), calibration));
            notifyProductionProfileChanged(profile);
            if (productionUi != null) productionUi.reload();
            return;
        }
        if (profile instanceof CameraProfileId.Blind) {
            CameraProfile camera = blindProfile((CameraProfileId.Blind) profile);
            if (corrected) calibrationCorrectedCrop =
                    DirectCameraCrop.saveCorrectedGeometryEdit(preferences, camera, crop);
            else calibrationRawCrop =
                    DirectCameraCrop.saveRawGeometryEdit(preferences, camera, crop);
        } else if (profile instanceof CameraProfileId.Parking) {
            ParkingCameraProfile parking = parkingProfile((CameraProfileId.Parking) profile);
            if (corrected) calibrationCorrectedCrop =
                    DirectCameraCrop.saveCorrectedGeometryEdit(preferences, parking, crop);
            else calibrationRawCrop =
                    DirectCameraCrop.saveRawGeometryEdit(preferences, parking, crop);
        } else if (profile instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) profile;
            reverseCalibrationCameraIndex = reverseProfileIndex(reverse);
            reverseCalibrationFront = reverse.getSource() == ReverseSource.Front;
            applyReverseCalibrationCrop(reverseRect(crop), corrected, true);
        }
        if (profile instanceof CameraProfileId.Parking) notifyParkingSettingsChanged();
        else if (profile instanceof CameraProfileId.Reverse) {
            CameraHelperService.reverseCameraSettingsChanged(this);
            if (productionUi != null) productionUi.reload();
        } else {
            CameraHelperService.cameraSettingsChanged(this);
            if (productionUi != null) productionUi.reload();
        }
    }

    private static boolean isProductionCalibrationKind(CameraHostKind kind) {
        return kind == CameraHostKind.CalibrationOriginal
                || kind == CameraHostKind.CalibrationCorrected
                || kind == CameraHostKind.CalibrationOutput;
    }

    /** Package-visible seam for JVM lifecycle tests; production uses the same identity gate. */
    static final class CalibrationHostBundle {
        private Object owner;
        private Object rawHost;
        private Object correctedHost;
        private Object outputHost;
        private Object rawMirror;
        private Object correctedMirror;
        private boolean rawActive;
        private boolean correctedActive;
        private boolean outputActive;

        void bind(Object owner, Object rawHost, Object correctedHost, Object outputHost) {
            bind(owner, rawHost, correctedHost, outputHost, null, null);
        }

        void bind(Object owner, Object rawHost, Object correctedHost, Object outputHost,
                Object rawMirror, Object correctedMirror) {
            this.owner = owner;
            this.rawHost = rawHost;
            this.correctedHost = correctedHost;
            this.outputHost = outputHost;
            this.rawMirror = rawMirror;
            this.correctedMirror = correctedMirror;
            this.rawActive = rawHost != null;
            this.correctedActive = correctedHost != null;
            this.outputActive = outputHost != null;
        }

        boolean hasAny() {
            return owner != null || rawHost != null || correctedHost != null || outputHost != null;
        }

        boolean isComplete(Object owner, Object rawHost, Object correctedHost, Object outputHost) {
            return this.owner == owner && owner != null
                    && this.rawHost == rawHost && rawHost != null
                    && this.correctedHost == correctedHost && correctedHost != null
                    && this.outputHost == outputHost && outputHost != null;
        }

        boolean isComplete(Object owner, Object rawHost, Object correctedHost, Object outputHost,
                Object rawMirror, Object correctedMirror) {
            return isComplete(owner, rawHost, correctedHost, outputHost)
                    && this.rawMirror == rawMirror && rawMirror != null
                    && this.correctedMirror == correctedMirror && correctedMirror != null;
        }

        boolean matches(CameraHostKind kind, Object host) {
            if (host == null || kind == null) return false;
            if (kind == CameraHostKind.CalibrationOriginal) return rawHost == host;
            if (kind == CameraHostKind.CalibrationCorrected) return correctedHost == host;
            if (kind == CameraHostKind.CalibrationOutput) return outputHost == host;
            return false;
        }

        void activateStage(CameraHostKind kind, Object host) {
            if (!matches(kind, host)) return;
            if (kind == CameraHostKind.CalibrationOriginal) rawActive = true;
            else if (kind == CameraHostKind.CalibrationCorrected) correctedActive = true;
            else if (kind == CameraHostKind.CalibrationOutput) outputActive = true;
        }

        boolean acceptMirror(Object owner, Object mirror, boolean raw) {
            return this.owner == owner
                    && (raw ? rawMirror == mirror && rawActive
                            : correctedMirror == mirror && correctedActive);
        }

        /** Returns true only for the current stage identity; the bundle remains retained. */
        boolean releaseStage(CameraHostKind kind, Object host, Runnable detachMirror) {
            if (!matches(kind, host)) return false;
            if (kind == CameraHostKind.CalibrationOriginal) {
                rawActive = false;
                if (detachMirror != null) detachMirror.run();
            } else if (kind == CameraHostKind.CalibrationCorrected) {
                correctedActive = false;
                if (detachMirror != null) detachMirror.run();
            } else if (kind == CameraHostKind.CalibrationOutput) {
                outputActive = false;
            }
            return true;
        }

        /** Clears the retained owner and all stage identities; repeated clears are no-ops. */
        boolean clear() {
            boolean hadBundle = hasAny();
            owner = null;
            rawHost = null;
            correctedHost = null;
            outputHost = null;
            rawMirror = null;
            correctedMirror = null;
            rawActive = false;
            correctedActive = false;
            outputActive = false;
            return hadBundle;
        }
    }

    private void releaseProductionCalibrationHosts() {
        productionCalibrationHostGeneration++;
        calibrationHostBundle.clear();
        BlindSpotCameraView owner = calibrationPreview;
        if (calibrationHostProfile != null && productionUi != null) {
            productionUi.setCalibrationRawFallback(calibrationHostProfile, false);
        }
        if (owner != null) {
            owner.setRawMirrorTexture(null);
            owner.setCorrectedMirrorTexture(null);
            owner.retireCameraInput();
        }
        productionCameraHosts.remove(CameraHostKind.CalibrationOriginal);
        productionCameraHosts.remove(CameraHostKind.CalibrationCorrected);
        productionCameraHosts.remove(CameraHostKind.CalibrationOutput);
        productionCameraSlots.remove(CameraHostKind.CalibrationOriginal);
        productionCameraSlots.remove(CameraHostKind.CalibrationCorrected);
        productionCameraSlots.remove(CameraHostKind.CalibrationOutput);
        calibrationPreview = null;
        calibrationRawMirror = null;
        calibrationCorrectedMirror = null;
        calibrationRawMirrorHost = null;
        calibrationCorrectedMirrorHost = null;
        calibrationOutputHost = null;
        productionCalibrationBorder = null;
        calibrationOutputCropMask = null;
        calibrationRawMirrorCover = null;
        calibrationCorrectedMirrorCover = null;
        productionCalibrationRawOverlay = null;
        productionCalibrationCorrectedOverlay = null;
        productionCalibrationGestureStart = null;
        productionCalibrationGestureActive = false;
        productionCalibrationGestureTransformChanged = false;
        calibrationHostProfile = null;
        calibrationPreviewCover = null;
        calibrationSurfaceReady = false;
    }

    private void clearReverseFallbackState() {
        if (productionUi == null) return;
        for (ReverseElement element : new ReverseElement[] {
                ReverseElement.Rear, ReverseElement.RearLeft, ReverseElement.RearRight}) {
            productionUi.setCalibrationRawFallback(
                    new CameraProfileId.Reverse(element, ReverseSource.Rear), false);
            productionUi.setCalibrationRawFallback(
                    new CameraProfileId.Reverse(element, ReverseSource.Front), false);
        }
    }

    private View createProductionReverseHost() {
        if (reverseCameraPreview == null) {
            reverseCameraPreview = new ReverseCameraCompositionView(this);
            reverseCameraPreview.setCallback(this);
            reverseCameraPreview.setAutomaticBufferQuality(CameraBufferQuality.load(preferences));
            reverseCameraPreview.setForceDewarpPipeline(true);
            reverseCameraPreview.enablePreviewBase();
            record("reverse_guidelines_unavailable", "reason", "oem_projection_not_compatible");
            applyProductionReverseState();
        }
        if (productionReverseHost == null) {
            productionReverseHost = cameraHostRoot();
            // ReverseCameraCompositionView owns its configured background pane and preview-base
            // layer.  The host must stay transparent so the normalized background rectangle (and
            // the Compose black frame outside it) remain the source of truth.
            productionReverseHost.setBackgroundColor(Color.TRANSPARENT);
            productionReverseHost.addView(reverseCameraPreview,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            productionReverseEditor = new ReverseCameraEditorView(this);
            productionReverseEditor.setBackgroundColor(Color.TRANSPARENT);
            productionReverseEditor.setLayoutModel(reverseCameraLayout);
            productionReverseEditor.setEditable(false);
            productionReverseEditor.setListener((layout, selectedCamera, finished) -> {
                if (!productionReverseEditorEditable || reverseCameraPreview == null) return;
                reverseCameraLayout = layout;
                reverseCameraPreview.applyLayout(layout);
                ReverseCameraController.saveEditorSelection(preferences, selectedCamera);
                if (productionUi != null) {
                    ReverseElement selectedElement = reverseElementForIndex(selectedCamera);
                    if (productionUi.getState().getReverse().getSelectedElement()
                            != selectedElement) {
                        productionUi.setReverseEditorSelection(selectedElement);
                    }
                }
                if (finished) {
                    ReverseCameraController.saveCompositionLayout(preferences, layout);
                    CameraHelperService.reverseCameraSettingsChanged(this);
                    record("reverse_layout_changed", "camera_index", selectedCamera);
                    if (productionUi != null) productionUi.reload();
                }
            });
            productionReverseHost.addView(productionReverseEditor,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
            ReverseCameraController.applyBorders(preferences, reverseCameraPreview);
            ReverseCameraController.applyBorders(preferences, productionReverseEditor,
                    productionUi != null
                            && productionUi.getState().getReverse().getShowFront());
        }
        syncProductionReverseDisplayTarget(productionUi == null ? null
                : productionUi.getState().getReverse().getSelectedTarget());
        return productionReverseHost;
    }

    private static ReverseElement reverseElementForIndex(int index) {
        if (index == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            return ReverseElement.Background;
        }
        if (index == ReverseCameraLayout.WIDGET_PANE_ID) return ReverseElement.Widget;
        if (index == ReverseCameraLayout.REAR_CAMERA_INDEX) return ReverseElement.Rear;
        if (index == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) return ReverseElement.RearLeft;
        return ReverseElement.RearRight;
    }

    private View createProductionDirectHost() {
        if (productionDirectHost == null) {
            productionDirectHost = cameraHostRoot();
            final SurfaceView surfaceView = new SurfaceView(this);
            directCameraPreview = surfaceView;
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    if (directCameraPreview != surfaceView) return;
                    directCameraSurfaceReady = holder.getSurface().isValid();
                    CameraHostSlot slot = productionCameraSlots.get(CameraHostKind.Direct);
                    if (directCameraSurfaceReady && slot != null
                            && slot.getSourceIndex() != null) {
                        openDirectCamera(slot.getSourceIndex());
                    }
                    updateControls();
                }
                @Override public void surfaceChanged(
                        SurfaceHolder holder, int format, int width, int height) {
                    if (directCameraPreview != surfaceView) return;
                    directCameraSurfaceReady = holder.getSurface().isValid(); updateControls();
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    if (directCameraPreview != surfaceView) return;
                    directCameraSurfaceReady = false;
                    if (activePreview == directCameraPreview) closeCamera("surface_destroyed");
                }
            });
            productionDirectHost.addView(surfaceView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            directCameraPreviewCover = blackCover();
            productionDirectHost.addView(directCameraPreviewCover,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return productionDirectHost;
    }

    private View createProductionAvmHost() {
        if (productionAvmHost == null) {
            productionAvmHost = cameraHostRoot();
            final TextureView textureView = new TextureView(this);
            debugPreview = textureView;
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                private StockAvmOutput output;
                @Override public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    if (debugPreview != textureView) return;
                    output = new StockAvmOutput(textureView, width, height);
                    debugOutput = output;
                    debugSurfaceReady = output.surface.isValid();
                    CameraHostSlot slot = productionCameraSlots.get(CameraHostKind.Avm);
                    if (debugSurfaceReady && slot != null && slot.getModeIndex() != null) {
                        int mode = clamp(slot.getModeIndex(), 0,
                                StockAvmPreview.horizontalLayoutCount() - 1);
                        openStockAvm(StockAvmPreview.horizontalViewpoint(mode), true);
                    }
                }
                @Override public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    if (output != null) output.refreshGeometry();
                }
                @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    if (output != null) output.retire();
                    if (debugOutput == output) debugOutput = null;
                    output = null;
                    if (debugPreview == textureView) {
                        debugSurfaceReady = false;
                        if (activePreview == debugPreview) closeCamera("surface_destroyed");
                    }
                    return !StockAvmOutput.retainOnDestroy(texture);
                }
                @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) { }
            });
            productionAvmHost.addView(textureView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            debugPreviewCover = blackCover();
            productionAvmHost.addView(debugPreviewCover,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        return productionAvmHost;
    }

    private void retireDebugOutput() {
        if (debugOutput != null) debugOutput.retire();
        debugOutput = null;
    }

    private Surface debugSurface() {
        return debugOutput == null ? null : debugOutput.surface;
    }

    private FrameLayout cameraHostRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(true);
        root.setClipToPadding(true);
        root.setBackgroundColor(Color.BLACK);
        return root;
    }

    private View cameraBorderOverlay() {
        View border = new View(this);
        border.setClickable(false);
        border.setFocusable(false);
        border.setVisibility(View.GONE);
        return border;
    }

    private void applyCameraBorder(
            View overlay, CameraBorderSettings.Border border) {
        if (overlay == null || border == null || border.borderDp == 0) {
            if (overlay != null) overlay.setVisibility(View.GONE);
            return;
        }
        android.graphics.drawable.GradientDrawable frame =
                new android.graphics.drawable.GradientDrawable();
        frame.setColor(Color.TRANSPARENT);
        int radiusDp = transientCornerRadiusDp != null
                ? transientCornerRadiusDp
                : BlindSpotOverlayController.readCornerRadius(preferences);
        frame.setCornerRadius(dp(radiusDp));
        frame.setStroke(dp(border.borderDp), border.borderArgb);
        overlay.setBackground(frame);
        overlay.setVisibility(View.VISIBLE);
    }

    private void refreshProductionBorderOverlays() {
        CameraHostSlot placement = productionCameraSlots.get(CameraHostKind.Placement);
        if (placement == null) placement = productionCameraSlots.get(CameraHostKind.Mirror);
        CameraProfileId placementProfile = placement == null ? null : placement.getProfile();
        Boolean mirrorFront = placementProfile instanceof CameraProfileId.Mirror
                && placement.getSourceIndex() != null
                ? placement.getSourceIndex() == RearviewMirrorSettings.FRONT_CAMERA_INDEX : null;
        applyCameraBorder(productionPlacementBorder,
                placementProfile == null ? null
                        : productionBorder(preferences, placementProfile, mirrorFront));

        CameraHostSlot output = productionCameraSlots.get(CameraHostKind.CalibrationOutput);
        CameraProfileId outputProfile = output == null ? null : output.getProfile();
        Boolean outputMirrorFront = outputProfile instanceof CameraProfileId.Mirror
                && output.getSourceIndex() != null
                ? output.getSourceIndex() == RearviewMirrorSettings.FRONT_CAMERA_INDEX : null;
        applyCameraBorder(productionCalibrationBorder,
                outputProfile == null ? null
                        : productionBorder(preferences, outputProfile, outputMirrorFront));
    }

    private View blackCover() {
        View cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        cover.setVisibility(View.VISIBLE);
        return cover;
    }

    private static void detachFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) parent).removeView(view);
        }
    }



    private static boolean isValidTab(int tab) {
        return tab == TAB_GUARD || tab == TAB_CAMERAS || tab == TAB_CAMERA_DEBUG
                || tab == TAB_DIRECT_CAMERA_DEBUG || tab == TAB_CAMERA_CALIBRATION
                || tab == TAB_REVERSE_CAMERAS || tab == TAB_MUSIC
                || tab == TAB_SETTINGS || tab == TAB_PARKING_CAMERAS
                || tab == TAB_REARVIEW_MIRROR;
    }

    private static int tabColor(boolean selected) {
        return Color.rgb(selected ? 82 : 42, selected ? 82 : 42, selected ? 82 : 42);
    }


    void requestAppShutdown() {
        if (shutdownRequested) return;
        shutdownRequested = true;
        LocalAdbClient.AUTHORIZATION.cancelForeground();
        WeatherRefreshAccessibilityService.stopMicrophone();
        UpdateHintRuntime.get(this).shutdown();
        com.byd.extend.ui.RuntimeUiSession.clearProcessState();
        clearResumeAutoPreview();
        record("user_shutdown_requested", "auto_start",
                GuardRecovery.isAutoStartEnabled(this), "guard_enabled",
                preferences.getBoolean("guard_enabled", false));
        updateControls();
        CameraHelperService.requestShutdown(this);
        finishAndRemoveTask();
    }

    void onSettingsAutoStartChanged(boolean checked) {
        record("auto_start_toggle", "enabled", checked);
        CameraHelperService.updateAutoStart(this, checked);
        advanceStartupAuthorizationFlow();
        updateControls();
    }

    void onCameraCornerRadiusChanged(int value) {
        record("camera_corner_radius", "radius_dp", value);
        refreshProductionBorderOverlays();
        CameraHelperService.cameraSettingsChanged(this);
    }

    void onCameraTransparencyChanged(int value) {
        record("camera_transparency", "percent", value);
        CameraHelperService.cameraSettingsChanged(this);
    }

    void onCameraBufferQualityChanged(int value) {
        record("camera_buffer_quality", "quality", CameraBufferQuality.label(value));
        if (cameraPreview != null) cameraPreview.setAutomaticBufferQuality(value);
        if (calibrationPreview != null) calibrationPreview.setAutomaticBufferQuality(value);
        if (reverseCameraPreview != null) {
            reverseCameraPreview.setAutomaticBufferQuality(value);
        }
        activityCameraBufferRefreshPending = true;
        notifyCameraQualityControllers(
                () -> CameraHelperService.cameraSettingsChanged(this),
                () -> CameraHelperService.reverseCameraSettingsChanged(this));
    }

    static void notifyCameraQualityControllers(Runnable overlay, Runnable reverse) {
        overlay.run();
        reverse.run();
    }

    void onMusicEnabledChanged(boolean checked) {
        record("music_toggle", "enabled", checked);
        CameraHelperService.musicSettingsChanged(this);
        if (productionUi != null) productionUi.reload();
        updateControls();
    }

    void onMusicOptionsChanged() {
        CameraHelperService.musicSettingsChanged(this);
        if (productionUi != null) productionUi.reload();
        updateControls();
    }

    String musicText(int resourceId) {
        return runtimeText(resourceId);
    }

    private void requestWeatherRefresh() {
        if (!hasLocationPermission()) {
            weatherRefreshAfterPermission = true;
            weatherEnableRequestedForPermission = true;
            requestLocationPermission(false, true);
            return;
        }
        long requestGeneration = ++weatherRefreshUiGeneration;
        if (productionUi != null) productionUi.setWeatherStatus(
                new StatusUiState(runtimeText(R.string.runtime_weather_updating),
                        StatusTone.Warning, true), true);
        CameraHelperService.weatherRefreshRequested(this, "app_button",
                new ResultReceiver(mainHandler) {
                    @Override
                    protected void onReceiveResult(int resultCode, Bundle resultData) {
                        if (activityDestroyed
                                || requestGeneration != weatherRefreshUiGeneration) return;
                        String message = resultData == null ? ""
                                : resultData.getString(CameraHelperService.WEATHER_RESULT_MESSAGE, "");
                        boolean ok = resultCode == CameraHelperService.WEATHER_RESULT_OK;
                        if (productionUi != null) productionUi.setWeatherStatus(
                                new StatusUiState(message, ok ? StatusTone.Ok
                                        : StatusTone.Error, !message.isEmpty()), false);
                    }
                });
    }

    private boolean requestLocationPermission(boolean enableWeather, boolean refreshAfter) {
        if (hasLocationPermission()) {
            weatherLocationPermissionPending = false;
            boolean enable = enableWeather || weatherEnableRequestedForPermission;
            boolean refresh = refreshAfter || weatherRefreshAfterPermission;
            weatherEnableRequestedForPermission = false;
            weatherRefreshAfterPermission = false;
            if (enable) onProductionWeatherEnabled(true);
            if (refresh) requestWeatherRefresh();
            return false;
        }
        if (settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress) return false;
        weatherEnableRequestedForPermission |= enableWeather;
        weatherRefreshAfterPermission |= refreshAfter;
        cancelPendingWeatherLocationPermission();
        if (!runtimePermissionWindowAvailable()) {
            weatherLocationPermissionPending = true;
            record("weather_location_permission_deferred", "reason", "startup_permissions");
            return true;
        }
        if (weatherLocationPermissionInFlight) return true;
        weatherLocationPermissionInFlight = true;
        record("weather_location_permission_requested",
                "enable", enableWeather, "refresh", refreshAfter);
        try {
            requestPermissions(new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION_REQUEST);
            return true;
        } catch (Throwable error) {
            weatherLocationPermissionInFlight = false;
            weatherLocationPermissionPending = false;
            weatherRefreshAfterPermission = false;
            weatherEnableRequestedForPermission = false;
            preferences.edit()
                    .remove(PREF_WEATHER_PERMISSION_REQUEST_PENDING)
                    .putBoolean(PREF_WEATHER_PERMISSION_MIGRATION_SEEN, true)
                    .apply();
            record("weather_location_permission_failed", "error", error.toString());
            if (productionUi != null) {
                productionUi.reload();
                productionUi.setWeatherStatus(new StatusUiState(
                        runtimeText(R.string.runtime_weather_permission_failed),
                        StatusTone.Error, true), false);
            }
            refreshProductionHeader();
            return false;
        }
    }

    private void initializeStartupWeatherPermissionState() {
        boolean requestPending = preferences.getBoolean(
                PREF_WEATHER_PERMISSION_REQUEST_PENDING, false);
        boolean completedImport = preferences.getBoolean(
                LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false);
        boolean migrationSeen = preferences.getBoolean(
                PREF_WEATHER_PERMISSION_MIGRATION_SEEN, false);
        boolean weatherEnabled = preferences.getBoolean(
                WeatherRuntime.PREF_ENABLED, false);
        boolean locationGranted = hasLocationPermission();
        weatherLocationPermissionPending |= shouldRequestStartupWeatherPermission(
                weatherEnabled, locationGranted, requestPending, completedImport, migrationSeen);
        if (!weatherLocationPermissionPending
                && (requestPending || (completedImport && !migrationSeen))) {
            preferences.edit()
                    .remove(PREF_WEATHER_PERMISSION_REQUEST_PENDING)
                    .putBoolean(PREF_WEATHER_PERMISSION_MIGRATION_SEEN, true)
                    .apply();
        }
        if (weatherEnabled && locationGranted && !hasBackgroundLocationPermission()
                && !preferences.getBoolean(PREF_WEATHER_BACKGROUND_PERMISSION_ASKED, false)) {
            weatherLocationPermissionPending = true;
        }
    }

    private void restoreWeatherPermissionState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        weatherBackgroundSettingsOpened =
                savedInstanceState.getBoolean(STATE_WEATHER_BACKGROUND_SETTINGS, false);
        // A saved Activity flag is not proof that a system permission window still exists.
        boolean interrupted = savedInstanceState.getBoolean(STATE_WEATHER_PERMISSION_IN_FLIGHT, false);
        weatherEnableRequestedForPermission = savedInstanceState.getBoolean(
                STATE_WEATHER_ENABLE_REQUESTED, false);
        weatherRefreshAfterPermission = savedInstanceState.getBoolean(
                STATE_WEATHER_REFRESH_AFTER_PERMISSION, false);
        if (savedInstanceState.containsKey(STATE_WEATHER_PERMISSION_PENDING)) {
            weatherLocationPermissionPending = savedInstanceState.getBoolean(
                    STATE_WEATHER_PERMISSION_PENDING, false);
        }
        if (interrupted && !hasLocationPermission()) weatherLocationPermissionPending = true;
    }

    private void cancelPendingWeatherLocationPermission() {
        weatherLocationPermissionStartScheduled = false;
        mainHandler.removeCallbacks(startPendingWeatherLocationPermission);
    }

    static boolean shouldRequestStartupWeatherPermission(
            boolean weatherEnabled, boolean locationGranted,
            boolean requestPending, boolean completedImport, boolean migrationSeen) {
        return weatherEnabled && !locationGranted
                && (requestPending || completedImport && !migrationSeen);
    }

    static boolean shouldEnableWeatherAfterPermission(
            boolean granted, boolean enableRequested) {
        // The saved request survives recreation before the new switch has been persisted.
        return granted && enableRequested;
    }

    static boolean shouldRefreshWeatherAfterPermission(
            boolean granted, boolean refreshAfterPermission, boolean weatherEnabled) {
        return granted && refreshAfterPermission && weatherEnabled;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasBackgroundLocationPermission() {
        return android.os.Build.VERSION.SDK_INT < 29
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        == PackageManager.PERMISSION_GRANTED;
    }

    private void queueWeatherBackgroundPermission(boolean explicitEnable) {
        if (!preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false)
                || !hasLocationPermission() || hasBackgroundLocationPermission()
                || !explicitEnable
                && preferences.getBoolean(PREF_WEATHER_BACKGROUND_PERMISSION_ASKED, false)) return;
        weatherLocationPermissionPending = true;
        advanceStartupAuthorizationFlow();
    }

    private void showWeatherBackgroundPermission() {
        if (showRuntimeDialog(RuntimeDialogOwner.WEATHER, DialogKind.Message,
                runtimeText(R.string.weather_background_title),
                runtimeText(R.string.weather_background_message), true,
                runtimeText(R.string.weather_background_allow),
                runtimeText(R.string.runtime_cancel), null,
                this::openWeatherBackgroundPermission, this::showWeatherPermissionStatus)) {
            weatherLocationPermissionPending = false;
            preferences.edit().putBoolean(PREF_WEATHER_BACKGROUND_PERMISSION_ASKED, true).apply();
        }
    }

    private void openWeatherBackgroundPermission() {
        try {
            weatherLocationPermissionInFlight = true;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                weatherBackgroundSettingsOpened = true;
                startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                        BACKGROUND_LOCATION_PERMISSION_REQUEST);
            }
        } catch (RuntimeException failure) {
            weatherLocationPermissionInFlight = false;
            weatherBackgroundSettingsOpened = false;
            record("weather_background_permission_failed", "error", failure.toString());
            showWeatherPermissionStatus();
        }
    }

    private void finishWeatherBackgroundPermission() {
        weatherBackgroundSettingsOpened = false;
        weatherLocationPermissionInFlight = false;
        record("weather_background_permission", "granted", hasBackgroundLocationPermission());
        if (preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false)) {
            CameraHelperService.weatherSettingsChanged(this);
        }
        showWeatherPermissionStatus();
        refreshProductionHeader();
    }

    private void showWeatherPermissionStatus() {
        if (productionUi == null || !preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false)
                || productionUi.getState().getSignals().getWeather().getRefresh().getPending()) return;
        if (!hasLocationPermission() || !hasBackgroundLocationPermission()) {
            productionUi.setWeatherStatus(new StatusUiState(runtimeText(hasLocationPermission()
                    ? R.string.weather_background_limited
                    : R.string.runtime_weather_location_permission_needed),
                    StatusTone.Warning, true), false);
        } else {
            String status = productionUi.getState().getSignals().getWeather()
                    .getRefresh().getStatus().getText();
            if (status.equals(runtimeText(R.string.weather_background_limited))
                    || status.equals(runtimeText(R.string.runtime_weather_location_permission_needed))) {
                productionUi.setWeatherStatus(new StatusUiState("", StatusTone.Neutral, false), false);
            }
        }
    }










    private void updateReverseCalibrationDisplay() {
        ReverseCameraLayout rawLayout = activeReverseRawCalibrationLayout();
        ReverseCameraLayout activeLayout = activeReverseCalibrationLayout();
        if (reverseCalibrationCameraIndex <= 0 || rawLayout == null || activeLayout == null) {
            return;
        }
        CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
        boolean rawFallback = reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex);
        CalibrationUiState ui = calibrationUiState(dewarp.enabled, rawFallback);
        if (!ui.correctedEditable
                && reverseCalibrationCropInputStage == CROP_STAGE_CORRECTED) {
            cancelReverseCropInput();
        }
        ReverseCameraLayout.Rect raw = rawLayout
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        ReverseCameraLayout.Rect corrected = activeLayout
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        reverseCalibrationRawOverlay.setCrop(reverseCrop(raw));
        reverseCalibrationCorrectedOverlay.setCrop(reverseCrop(corrected));
        reverseCalibrationCorrectedOverlay.setEnabled(ui.correctedEditable);
        reverseCalibrationCorrectedStage.setVisibility(
                ui.showCorrected ? View.VISIBLE : View.GONE);
        updateDewarpUi(true, dewarp);
        updateReverseCalibrationCropReadouts();
        ReverseCameraLayout.Pane activePane = activeLayout
                .pane(reverseCalibrationCameraIndex);
        reverseDisplayModeUiUpdating = true;
        reverseDisplayModeInput.setSelection(activePane.displayMode, false);
        reverseDisplayModeUiUpdating = false;
        reverseRotationUiUpdating = true;
        reverseRotationSlider.setProgress(
                activePane.rotationDegrees - CameraRotation.MIN_DEGREES);
        reverseRotationValue.setText(activePane.rotationDegrees + "°");
        reverseRotationUiUpdating = false;
        fitReverseCalibrationLiveFrame();
        armReverseCalibrationFreshness();
        startReverseCalibrationCopies();
    }

    private boolean reverseCalibrationCopiesRaw() {
        if (reverseCalibrationCameraIndex <= 0) return true;
        CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
        return !dewarp.enabled || reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex);
    }

    private void armReverseCalibrationFreshness() {
        if (reverseCalibrationCameraIndex <= 0
                || reverseCalibrationPane == null
                || reverseCalibrationPane.getVisibility() != View.VISIBLE
                || activePreview != reverseCameraPreview
                || activeActivityCameraRequestId <= 0) {
            reverseCalibrationFreshness.clear();
            return;
        }
        reverseCalibrationFreshness.arm(
                activeActivityCameraRequestId,
                reverseCalibrationCameraIndex,
                reverseCalibrationFront,
                reverseCalibrationCopiesRaw(),
                activeActivityCameraOpened && activeActivityCameraFresh);
    }

    private void updateReverseCalibrationCropReadouts() {
        if (reverseCalibrationCameraIndex <= 0
                || reverseCalibrationCropReadouts[CROP_STAGE_RAW] == null
                || activeReverseRawCalibrationLayout() == null
                || activeReverseCalibrationLayout() == null) return;
        CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
        CalibrationUiState ui = calibrationUiState(
                dewarp.enabled, reverseCameraPreview.editorUsesRawFallback(
                        reverseCalibrationCameraIndex));
        ReverseCameraLayout.Rect raw = activeReverseRawCalibrationLayout()
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        ReverseCameraLayout.Rect corrected = activeReverseCalibrationLayout()
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        reverseCalibrationCropReadouts[CROP_STAGE_RAW].setText(cropCoordinates(
                raw.left, raw.top, raw.width, raw.height));
        reverseCalibrationCropReadouts[CROP_STAGE_CORRECTED].setText(cropCoordinates(
                corrected.left, corrected.top, corrected.width, corrected.height));
        reverseCalibrationCropTitles[CROP_STAGE_CORRECTED].setText("CORRECTED");
        reverseCalibrationCropInputButtons[CROP_STAGE_RAW].setEnabled(true);
        reverseCalibrationCropInputButtons[CROP_STAGE_CORRECTED]
                .setEnabled(ui.correctedEditable);
        if (reversePresetLoadButton != null) {
            reversePresetLoadButton.setEnabled(reverseCalibrationFront
                    ? CameraCalibrationPreset.hasReverseFront(
                            preferences, reverseCalibrationCameraIndex)
                    : CameraCalibrationPreset.hasReverse(
                            preferences, reverseCalibrationCameraIndex));
        }
        int mirrorTarget = CameraCalibrationPreset.reverseMirrorTarget(
                reverseCalibrationCameraIndex);
        if (reverseMirrorButton != null) {
            reverseMirrorButton.setVisibility(mirrorTarget < 0 ? View.GONE : View.VISIBLE);
            reverseMirrorButton.setText(reverseTransferLabel(reverseCalibrationCameraIndex));
        }
        if (reverseOutputMirrorButton != null) {
            boolean background = reverseCalibrationCameraIndex
                    == ReverseCameraLayout.BACKGROUND_PANE_ID;
            reverseOutputMirrorButton.setVisibility(background ? View.GONE : View.VISIBLE);
            ReverseCameraLayout active = activeReverseCalibrationLayout();
            if (!background && active != null) {
                reverseOutputMirrorButton.setBackgroundColor(tabColor(
                        active.pane(reverseCalibrationCameraIndex)
                                .mirrorHorizontally));
            }
        }
    }

    private void persistReverseCalibrationCrop(
            ReverseCameraLayout.Rect crop, boolean corrected) {
        if (reverseCalibrationFront) {
            ReverseCameraController.saveFrontSourceCrop(
                    preferences, reverseCalibrationCameraIndex, crop, corrected);
        } else {
            ReverseCameraController.saveSourceCrop(
                    preferences, reverseCalibrationCameraIndex, crop, corrected);
        }
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_crop_saved", "camera_index", reverseCalibrationCameraIndex,
                "stage", corrected ? "corrected" : "raw",
                "left", crop.left, "top", crop.top,
                "width", crop.width, "height", crop.height);
    }

    private void applyReverseCalibrationCrop(
            ReverseCameraLayout.Rect value, boolean corrected, boolean persist) {
        if (reverseCalibrationCameraIndex <= 0) return;
        ReverseCameraLayout activeLayout = activeReverseCalibrationLayout();
        ReverseCameraLayout rawLayout = activeReverseRawCalibrationLayout();
        if (corrected) {
            ReverseCameraLayout.Pane pane = activeLayout
                    .pane(reverseCalibrationCameraIndex);
            activeLayout = ReverseCameraLayout.withPane(
                    activeLayout, reverseCalibrationCameraIndex, pane.destination, value);
            setActiveReverseCalibrationLayout(activeLayout, rawLayout);
        } else {
            ReverseCameraLayout.Pane pane = rawLayout
                    .pane(reverseCalibrationCameraIndex);
            rawLayout = ReverseCameraLayout.withPane(
                    rawLayout, reverseCalibrationCameraIndex, pane.destination, value);
            CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
            if (!dewarp.enabled) {
                ReverseCameraLayout.Pane active = activeLayout
                        .pane(reverseCalibrationCameraIndex);
                activeLayout = ReverseCameraLayout.withPane(
                        activeLayout, reverseCalibrationCameraIndex,
                        active.destination, value);
            }
            setActiveReverseCalibrationLayout(activeLayout, rawLayout);
        }
        updateReverseCalibrationCropReadouts();
        renderReverseCalibrationCrop();
        if (persist) persistReverseCalibrationCrop(value, corrected);
    }

    private static DirectCameraCrop reverseCrop(ReverseCameraLayout.Rect crop) {
        return DirectCameraCrop.of(crop.left, crop.top, crop.width, crop.height,
                DirectCameraCrop.ASPECT_FREE, 0, CameraRotation.MODE_FIT);
    }

    private static ReverseCameraLayout.Rect reverseRect(DirectCameraCrop crop) {
        return ReverseCameraLayout.sourceCrop(
                crop.left, crop.top, crop.width, crop.height);
    }

    private ReverseCameraLayout activeReverseCalibrationLayout() {
        return reverseCalibrationFront ? reverseFrontCameraLayout : reverseCameraLayout;
    }

    private ReverseCameraLayout activeReverseRawCalibrationLayout() {
        if (reverseCalibrationFront) {
            if (reverseFrontRawCalibrationLayout == null) {
                reverseFrontRawCalibrationLayout =
                        ReverseCameraController.loadFrontRawLayout(preferences);
            }
            return reverseFrontRawCalibrationLayout;
        }
        if (reverseRawCalibrationLayout == null) {
            reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
        }
        return reverseRawCalibrationLayout;
    }

    private CameraDewarpConfig loadReverseCalibrationDewarp() {
        return reverseCalibrationFront
                ? CameraDewarpConfig.loadForReverseFront(
                        preferences, reverseCalibrationCameraIndex)
                : CameraDewarpConfig.loadForReverse(
                        preferences, reverseCalibrationCameraIndex);
    }

    private void setActiveReverseCalibrationLayout(
            ReverseCameraLayout active, ReverseCameraLayout raw) {
        if (reverseCalibrationFront) {
            reverseFrontCameraLayout = active;
            reverseFrontRawCalibrationLayout = raw;
            applyReversePreviewDewarpConfigs();
        } else {
            reverseCameraLayout = active;
            reverseRawCalibrationLayout = raw;
            if (reverseCameraEditor != null) {
                reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            }
            if (reverseCameraPreview != null) {
                reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
                reverseCameraPreview.applyLayout(reverseCameraLayout);
            }
        }
    }

    private boolean shouldCopyReverseCalibrationFrame() {
        TextureView source = reverseCalibrationCopySource();
        return shouldCopyReverseCalibrationFrame(
                activityResumed,
                selectedTab == TAB_REVERSE_CAMERAS,
                reverseCalibrationCameraIndex > 0
                        && reverseCalibrationPane != null
                        && reverseCalibrationPane.getVisibility() == View.VISIBLE,
                activePreview == reverseCameraPreview,
                requestedOpen,
                activeActivityCameraOpened,
                activeActivityCameraFresh,
                reverseCalibrationFreshness.allows(
                        activeActivityCameraRequestId,
                        reverseCalibrationCameraIndex,
                        reverseCalibrationFront,
                        reverseCalibrationCopiesRaw()),
                source != null && source.isAvailable());
    }

    static boolean shouldCopyReverseCalibrationFrame(
            boolean resumed, boolean reverseTab, boolean paneVisible,
            boolean reversePreviewActive, boolean requestedOpen,
            boolean opened, boolean requestFresh,
            boolean selectedMirrorFresh, boolean sourceAvailable) {
        return resumed && reverseTab && paneVisible && reversePreviewActive
                && requestedOpen && opened && requestFresh
                && selectedMirrorFresh && sourceAvailable;
    }

    private TextureView reverseCalibrationCopySource() {
        if (reverseCalibrationCameraIndex <= 0) return null;
        return reverseCalibrationCopiesRaw()
                ? reverseCalibrationRawMirror : reverseCalibrationCorrectedMirror;
    }

    private void startReverseCalibrationCopies() {
        mainHandler.removeCallbacks(copyReverseCalibrationFrame);
        if (shouldCopyReverseCalibrationFrame()) {
            mainHandler.post(copyReverseCalibrationFrame);
        }
    }

    private void stopReverseCalibrationCopies(boolean clearPreview) {
        mainHandler.removeCallbacks(copyReverseCalibrationFrame);
        reverseCalibrationCopyPending = false;
        if (clearPreview) {
            reverseCalibrationFreshness.clear();
            reverseCalibrationCaptureBitmap = null;
            reverseCalibrationResultBitmap = null;
            if (reverseCalibrationLivePreview != null) {
                reverseCalibrationLivePreview.setImageDrawable(null);
            }
        }
    }

    private void copyReverseCalibrationFrame() {
        if (!shouldCopyReverseCalibrationFrame() || reverseCalibrationCopyPending) return;
        TextureView source = reverseCalibrationCopySource();
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= 0 || height <= 0) {
            mainHandler.postDelayed(
                    copyReverseCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
            return;
        }
        if (reverseCalibrationCaptureBitmap == null
                || reverseCalibrationCaptureBitmap.getWidth() != width
                || reverseCalibrationCaptureBitmap.getHeight() != height) {
            reverseCalibrationCaptureBitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
        }
        reverseCalibrationCopyPending = true;
        try {
            if (source.getBitmap(reverseCalibrationCaptureBitmap) == null) {
                reverseCalibrationCopyPending = false;
                if (shouldCopyReverseCalibrationFrame()) {
                    mainHandler.postDelayed(
                            copyReverseCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
                }
                return;
            }
            reverseCalibrationCopyPending = false;
            renderReverseCalibrationCrop();
        } catch (Throwable error) {
            reverseCalibrationCopyPending = false;
            record("reverse_calibration_texture_copy", "error", error.toString());
        }
        if (shouldCopyReverseCalibrationFrame()) {
            mainHandler.postDelayed(
                    copyReverseCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
        }
    }

    private void renderReverseCalibrationCrop() {
        if (reverseCalibrationCaptureBitmap == null
                || reverseCalibrationLiveFrame == null
                || reverseCalibrationLiveFrame.getWidth() <= 0
                || reverseCalibrationLiveFrame.getHeight() <= 0
                || reverseCalibrationCameraIndex <= 0) return;
        int width = reverseCalibrationLiveFrame.getWidth();
        int height = reverseCalibrationLiveFrame.getHeight();
        if (reverseCalibrationResultBitmap == null
                || reverseCalibrationResultBitmap.getWidth() != width
                || reverseCalibrationResultBitmap.getHeight() != height) {
            reverseCalibrationResultBitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
        }
        CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
        boolean corrected = dewarp.enabled && !reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex);
        ReverseCameraLayout.Pane pane = (corrected
                ? activeReverseCalibrationLayout() : activeReverseRawCalibrationLayout())
                .pane(reverseCalibrationCameraIndex);
        int rotationMode = pane.displayMode == ReverseCameraLayout.DISPLAY_MODE_FILL
                ? CameraRotation.MODE_FILL
                : pane.displayMode == ReverseCameraLayout.DISPLAY_MODE_STRETCH
                        ? CameraRotation.MODE_ALIGNED : CameraRotation.MODE_FIT;
        DirectCameraCrop crop = reverseCrop(pane.sourceCrop)
                .withOutputTransformPreservingGeometry(
                        pane.rotationDegrees, rotationMode, pane.mirrorHorizontally);
        int sourceWidth = reverseCalibrationCaptureBitmap.getWidth();
        int sourceHeight = reverseCalibrationCaptureBitmap.getHeight();
        Canvas canvas = new Canvas(reverseCalibrationResultBitmap);
        canvas.drawColor(Color.BLACK);
        Matrix transform = new Matrix();
        CameraRotation.setSourceCropTransformForInput(
                transform, pane.sourceCrop.left, pane.sourceCrop.top,
                pane.sourceCrop.width, pane.sourceCrop.height,
                new RectF(0, 0, width, height), pane.rotationDegrees,
                crop.rotationMode, ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, sourceWidth, sourceHeight,
                pane.mirrorHorizontally);
        float[] visibleCrop = CameraRotation.transformedCropCornersForInput(
                pane.sourceCrop.left, pane.sourceCrop.top,
                pane.sourceCrop.width, pane.sourceCrop.height,
                new RectF(0, 0, width, height), pane.rotationDegrees,
                crop.rotationMode, ReverseCameraCompositionView.SOURCE_WIDTH,
                ReverseCameraCompositionView.SOURCE_HEIGHT, sourceWidth, sourceHeight,
                pane.mirrorHorizontally);
        canvas.save();
        canvas.clipPath(cropPath(visibleCrop));
        canvas.drawBitmap(reverseCalibrationCaptureBitmap, transform, calibrationCropPaint);
        canvas.restore();
        reverseCalibrationLivePreview.setImageBitmap(reverseCalibrationResultBitmap);
        reverseCalibrationLivePreview.invalidate();
    }

    private float reverseCalibrationLiveAspect() {
        ReverseCameraLayout active = activeReverseCalibrationLayout();
        if (active == null || reverseCalibrationCameraIndex <= 0) return 4.0f / 3.0f;
        ReverseCameraLayout.Rect destination = active
                .pane(reverseCalibrationCameraIndex).destination;
        float aspect = destination.width * 1920.0f
                / (destination.height * 990.0f);
        return Float.isFinite(aspect) && aspect > 0.0f ? aspect : 4.0f / 3.0f;
    }

    private void fitReverseCalibrationLiveFrame() {
        if (reverseCalibrationLiveFrame == null
                || !(reverseCalibrationLiveFrame.getParent() instanceof FrameLayout)) return;
        fitAspectFrame((FrameLayout) reverseCalibrationLiveFrame.getParent(),
                reverseCalibrationLiveFrame, reverseCalibrationLiveAspect());
    }


    private void selectReverseInspector(int requestedMode) {
        reverseInspectorMode = REVERSE_INSPECTOR_POSITION;
        Button position = reverseInspectorButtons[REVERSE_INSPECTOR_POSITION];
        if (position != null) position.setBackgroundColor(tabColor(true));
    }

    private void updateReversePaneControls(int cameraIndex) {
        updateReversePaneControls(reversePaneUiBinding(preferences, cameraIndex));
    }

    private void updateReversePaneControls(ReversePaneUiBinding binding) {
        int cameraIndex = binding.cameraIndex;
        if (reverseCameraLayout == null) return;
        int[] paneIds = {ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraLayout.WIDGET_PANE_ID,
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX};
        for (int i = 0; i < reversePaneButtons.length; i++) {
            reversePaneButtons[i].setBackgroundColor(tabColor(paneIds[i] == cameraIndex));
            // Fixed Background/Widget elements have dedicated preference bits.  Never feed their
            // sentinel IDs into the camera-pane visibility lookup.
            boolean paneVisible;
            if (paneIds[i] == ReverseCameraLayout.WIDGET_PANE_ID) {
                paneVisible = binding.widgetVisible;
            } else if (paneIds[i] == ReverseCameraLayout.BACKGROUND_PANE_ID) {
                paneVisible = (binding.visibilityMask
                        & ReverseCameraLayout.VISIBILITY_BACKGROUND) != 0;
            } else {
                paneVisible = ReverseCameraLayout.isVisible(binding.visibilityMask, paneIds[i]);
            }
            reversePaneButtons[i].setPaintFlags(reversePaneButtonPaintFlags(
                    reversePaneButtons[i].getPaintFlags(),
                    paneVisible));
        }
        if (reverseVisibilitySwitch != null) {
            reverseVisibilityUiUpdating = true;
            reverseVisibilitySwitch.setText("Відображати: " + reversePaneLabel(cameraIndex));
            reverseVisibilitySwitch.setChecked(binding.visible);
            reverseVisibilityUiUpdating = false;
        }
        boolean background = cameraIndex == ReverseCameraLayout.BACKGROUND_PANE_ID;
        boolean widget = cameraIndex == ReverseCameraLayout.WIDGET_PANE_ID;
        boolean fixedPane = background || widget;
        if (reverseCalibrationButton != null) {
            reverseCalibrationButton.setVisibility(fixedPane ? View.GONE : View.VISIBLE);
        }
        if (reverseFrontIntegrationSwitch != null) {
            reverseVisibilityUiUpdating = true;
            reverseFrontIntegrationSwitch.setText(
                    cameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX
                            ? "Інтеграція передньої камери"
                            : "Інтеграція передніх камер");
            reverseFrontIntegrationSwitch.setVisibility(
                    reverseFrontIntegrationVisibility(cameraIndex));
            reverseFrontIntegrationSwitch.setChecked(binding.frontIntegrated);
            reverseVisibilityUiUpdating = false;
        }
        selectReverseInspector(reverseInspectorMode);
        ReverseCameraLayout.Pane pane = fixedPane ? null : reverseCameraLayout.pane(cameraIndex);
        reverseDisplayModeUiUpdating = true;
        reverseDisplayModeInput.setSelection(fixedPane
                ? ReverseCameraLayout.DEFAULT_DISPLAY_MODE : pane.displayMode, false);
        reverseDisplayModeInput.setEnabled(!fixedPane);
        reverseDisplayModeUiUpdating = false;
        reverseRotationUiUpdating = true;
        int rotationDegrees = fixedPane ? CameraRotation.DEFAULT_DEGREES
                : pane.rotationDegrees;
        reverseRotationSlider.setProgress(
                rotationDegrees - CameraRotation.MIN_DEGREES);
        reverseRotationSlider.setEnabled(!fixedPane);
        reverseRotationValue.setText(fixedPane ? "—" : rotationDegrees + "°");
        reverseRotationUiUpdating = false;
        if (reverseOutputMirrorButton != null) {
            reverseOutputMirrorButton.setVisibility(fixedPane ? View.GONE : View.VISIBLE);
            if (!fixedPane) {
                reverseOutputMirrorButton.setBackgroundColor(tabColor(
                        pane.mirrorHorizontally));
            }
        }
        if (fixedPane) {
            setDewarpControlsEnabled(true, false);
        } else {
            updateDewarpUi(true, binding.dewarp);
        }
        reverseLowerButton.setEnabled(!fixedPane && pane.zOrder > 0);
        reverseRaiseButton.setEnabled(!fixedPane && pane.zOrder < 2);
    }


    static ReversePaneUiBinding reversePaneUiBinding(
            SharedPreferences preferences, int cameraIndex) {
        return new ReversePaneUiBinding(cameraIndex,
                isReverseFixedPane(cameraIndex)
                        ? null : CameraDewarpConfig.loadForReverse(preferences, cameraIndex),
                ReverseCameraController.loadVisibilityMask(preferences),
                cameraIndex == ReverseCameraLayout.WIDGET_PANE_ID
                        ? ReverseCameraController.loadWidgetVisible(preferences)
                        : ReverseCameraController.loadVisibility(preferences, cameraIndex),
                ReverseCameraController.loadWidgetVisible(preferences),
                isReverseCameraPane(cameraIndex)
                        && ReverseCameraController.loadFrontIntegrated(
                                preferences, cameraIndex));
    }

    static int reversePaneButtonPaintFlags(int flags, boolean visible) {
        return visible
                ? flags & ~Paint.STRIKE_THRU_TEXT_FLAG
                : flags | Paint.STRIKE_THRU_TEXT_FLAG;
    }

    static boolean isReverseCameraPane(int cameraIndex) {
        return cameraIndex >= ReverseCameraLayout.REAR_CAMERA_INDEX
                && cameraIndex <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
    }

    static int reverseFrontIntegrationVisibility(int cameraIndex) {
        return isReverseCameraPane(cameraIndex) ? View.VISIBLE : View.INVISIBLE;
    }

    static boolean isReverseFixedPane(int cameraIndex) {
        return cameraIndex == ReverseCameraLayout.BACKGROUND_PANE_ID
                || cameraIndex == ReverseCameraLayout.WIDGET_PANE_ID;
    }

    static String reversePaneLabel(int cameraIndex) {
        switch (cameraIndex) {
            case ReverseCameraLayout.WIDGET_PANE_ID:
                return "Віджет";
            case ReverseCameraLayout.BACKGROUND_PANE_ID:
                return "Тло";
            case ReverseCameraLayout.REAR_CAMERA_INDEX:
                return "Rear";
            case ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX:
                return "Rear left";
            case ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX:
                return "Rear right";
            default:
                return "Unknown";
        }
    }

    static final class ReversePaneUiBinding {
        final int cameraIndex;
        final CameraDewarpConfig dewarp;
        final int visibilityMask;
        final boolean visible;
        final boolean widgetVisible;
        final boolean frontIntegrated;

        ReversePaneUiBinding(
                int cameraIndex, CameraDewarpConfig dewarp,
                int visibilityMask, boolean visible,
                boolean widgetVisible, boolean frontIntegrated) {
            this.cameraIndex = cameraIndex;
            this.dewarp = dewarp;
            this.visibilityMask = visibilityMask;
            this.visible = visible;
            this.widgetVisible = widgetVisible;
            this.frontIntegrated = frontIntegrated;
        }
    }

















    private static String parkingPlacementPrefix(ParkingCameraProfile profile) {
        return "parking_camera_" + profile.wireName.toLowerCase(Locale.US) + "_";
    }

    private static String parkingScaleSyncKey() {
        return "parking_camera_scale_sync";
    }

    private void notifyParkingSettingsChanged() {
        CameraHelperService.parkingCameraSettingsChanged(this);
    }

    private void notifyCalibrationSettingsChanged() {
        if (calibrationParkingMode) CameraHelperService.parkingCameraSettingsChanged(this);
        else CameraHelperService.cameraSettingsChanged(this);
    }





    private void cancelCalibrationCropInput() {
        calibrationCropInputStage = CROP_STAGE_NONE;
        if (calibrationNormalControls != null) {
            calibrationNormalControls.setVisibility(View.VISIBLE);
        }
        if (calibrationCropInputControls != null) {
            calibrationCropInputControls.setVisibility(View.GONE);
        }
        clearCropInputErrors(calibrationCropInputs);
    }



    private void cancelReverseCropInput() {
        reverseCalibrationCropInputStage = CROP_STAGE_NONE;
        if (reverseCalibrationNormalControls != null) {
            reverseCalibrationNormalControls.setVisibility(View.VISIBLE);
        }
        if (reverseCalibrationCropInputControls != null) {
            reverseCalibrationCropInputControls.setVisibility(View.GONE);
        }
        clearCropInputErrors(reverseCalibrationCropInputs);
    }



    private static void clearCropInputErrors(EditText[] inputs) {
        for (EditText input : inputs) {
            if (input != null) {
                input.setError(null);
                input.clearFocus();
            }
        }
    }

    private static String cropCoordinates(
            float x, float y, float width, float height) {
        return String.format(Locale.US, "X %.2f%%  Y %.2f%%\nW %.2f%%  H %.2f%%",
                x * 100.0f, y * 100.0f, width * 100.0f, height * 100.0f);
    }







    private void updateDewarpUi(boolean reverse, CameraDewarpConfig value) {
        Switch toggle = reverse ? reverseDewarpSwitch : calibrationDewarpSwitch;
        SeekBar slider = reverse ? reverseDewarpFovSlider : calibrationDewarpFovSlider;
        TextView fovValue = reverse ? reverseDewarpFovValue : calibrationDewarpFovValue;
        Spinner projectionInput = reverse
                ? reverseDewarpProjectionInput : calibrationDewarpProjectionInput;
        if (toggle == null) return;
        if (reverse) reverseDewarpUiUpdating = true;
        else calibrationDewarpUiUpdating = true;
        toggle.setChecked(value.enabled);
        if (!reverse && calibrationCropOverlay != null) {
            calibrationCropOverlay.setGridVisible(value.enabled);
            if (calibrationRawCropOverlay != null) {
                calibrationRawCropOverlay.setGridVisible(value.enabled);
            }
            updateCalibrationDisplay(value.enabled);
        }
        slider.setProgress(value.fovDegrees - CameraDewarpConfig.MIN_FOV_DEGREES);
        if (projectionInput != null) projectionInput.setSelection(value.projection, false);
        fovValue.setText("FOV: " + value.fovDegrees + "°");
        setDewarpControlsEnabled(reverse, true);
        if (reverse) reverseDewarpUiUpdating = false;
        else calibrationDewarpUiUpdating = false;
    }

    private void setDewarpControlsEnabled(boolean reverse, boolean enabled) {
        Switch toggle = reverse ? reverseDewarpSwitch : calibrationDewarpSwitch;
        SeekBar slider = reverse ? reverseDewarpFovSlider : calibrationDewarpFovSlider;
        Spinner projectionInput = reverse
                ? reverseDewarpProjectionInput : calibrationDewarpProjectionInput;
        if (toggle != null) toggle.setEnabled(enabled);
        if (slider != null) slider.setEnabled(enabled);
        if (projectionInput != null) projectionInput.setEnabled(enabled);
    }

    private void setCalibrationEditingState(CalibrationUiState ui) {
        if (calibrationRawCropOverlay != null) calibrationRawCropOverlay.setEnabled(true);
        if (calibrationCropOverlay != null) {
            calibrationCropOverlay.setEnabled(ui.correctedEditable);
        }
        if (calibrationResetButton != null) calibrationResetButton.setEnabled(true);
        if (calibrationRotationSlider != null) calibrationRotationSlider.setEnabled(true);
        if (calibrationRotationModeInput != null) {
            calibrationRotationModeInput.setEnabled(true);
        }
    }

    private void applyDewarpConfig(
            boolean reverse, CameraDewarpConfig value, boolean persist) {
        int lens = selectedDewarpLens(reverse);
        if (lens == 0) return;
        if (value.lens != lens) throw new IllegalArgumentException("dewarp lens mismatch");
        CameraDewarpConfig previous = loadSelectedDewarpConfig(reverse);
        if (persist) {
            saveSelectedDewarpConfig(reverse, value);
            if (previous.enabled != value.enabled) refreshDewarpCrops(reverse);
        }
        updateDewarpValueLabels(reverse, value);
        if (!reverse && calibrationCropOverlay != null) {
            calibrationCropOverlay.setGridVisible(value.enabled);
            if (calibrationRawCropOverlay != null) {
                calibrationRawCropOverlay.setGridVisible(value.enabled);
            }
        }
        CameraProfile calibrationProfile = CameraProfile.of(calibrationCameraId);
        if (!reverse && calibrationPreview != null) {
            calibrationPreview.applyDewarpConfig(value);
            updateCalibrationDisplay(value.enabled);
        }
        CameraProfile previewProfile = CameraProfile.of(selectedCameraId);
        if (!reverse && !calibrationParkingMode && cameraPreview != null
                && previewProfile.id == calibrationProfile.id) {
            cameraPreview.applyDewarpConfig(value);
        }
        if (reverse) {
            stopReverseCalibrationCopies(true);
            applyReversePreviewDewarpConfigs(
                    reverseCameraEditor.selectedCamera(), value);
            if (reverseCalibrationCameraIndex > 0) updateReverseCalibrationDisplay();
        }
        if (!persist) return;
        if (reverse) CameraHelperService.reverseCameraSettingsChanged(this);
        else notifyCalibrationSettingsChanged();
        record("camera_dewarp_changed", "scope", selectedDewarpScope(reverse),
                "lens", lens, "enabled", value.enabled,
                "fov_degrees", value.fovDegrees,
                "projection", CameraDewarpConfig.projectionLabel(value.projection),
                "crop_mode_changed", previous.enabled != value.enabled);
    }

    private void updateDewarpValueLabels(boolean reverse, CameraDewarpConfig value) {
        TextView label = reverse ? reverseDewarpFovValue : calibrationDewarpFovValue;
        if (label != null) label.setText("FOV: " + value.fovDegrees + "°");
    }

    private int selectedDewarpLens(boolean reverse) {
        if (!reverse) {
            if (calibrationParkingMode) {
                return CameraDewarpConfig.lensFor(
                        ParkingCameraProfile.of(calibrationParkingCameraId));
            }
            return CameraDewarpConfig.lensFor(CameraProfile.of(calibrationCameraId));
        }
        if (reverseCameraEditor == null
                || isReverseFixedPane(reverseCameraEditor.selectedCamera())) return 0;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        return reverseCalibrationFront
                ? CameraDewarpConfig.lensForReverseFrontCamera(cameraIndex)
                : CameraDewarpConfig.lensForReverseCamera(cameraIndex);
    }

    private CameraDewarpConfig loadSelectedDewarpConfig(boolean reverse) {
        if (!reverse) {
            if (calibrationParkingMode) {
                return CameraDewarpConfig.loadForParking(preferences,
                        ParkingCameraProfile.of(calibrationParkingCameraId));
            }
            return CameraDewarpConfig.loadForProfile(
                    preferences, CameraProfile.of(calibrationCameraId));
        }
        int cameraIndex = reverseCameraEditor.selectedCamera();
        return reverseCalibrationFront
                ? CameraDewarpConfig.loadForReverseFront(preferences, cameraIndex)
                : CameraDewarpConfig.loadForReverse(preferences, cameraIndex);
    }

    private void saveSelectedDewarpConfig(
            boolean reverse, CameraDewarpConfig value) {
        if (!reverse) {
            saveCalibrationDewarpStored(value);
            return;
        }
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (reverseCalibrationFront) {
            CameraDewarpConfig.saveForReverseFront(preferences, cameraIndex, value);
        } else {
            CameraDewarpConfig.saveForReverse(preferences, cameraIndex, value);
        }
    }

    private String selectedDewarpScope(boolean reverse) {
        if (reverse) return (reverseCalibrationFront ? "reverse_front_" : "reverse_")
                + reverseCameraEditor.selectedCamera();
        return calibrationScopeLabel(calibrationParkingMode,
                calibrationParkingMode ? calibrationParkingCameraId : calibrationCameraId);
    }

    private void refreshDewarpCrops(boolean reverse) {
        CameraProfile calibrationProfile = CameraProfile.of(calibrationCameraId);
        if (!reverse && calibrationCropOverlay != null) {
            CameraDewarpConfig dewarp = loadCalibrationDewarpStored();
            calibrationRawCrop = loadCalibrationRawStored();
            calibrationCorrectedCrop = loadCalibrationCorrectedStored(calibrationRawCrop);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            updateCalibrationDisplay(dewarp.enabled);
        }
        CameraProfile previewProfile = CameraProfile.of(selectedCameraId);
        if (!reverse && !calibrationParkingMode && cameraPreview != null
                && previewProfile.id == calibrationProfile.id) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, previewProfile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
        }
        if (reverse && reverseCameraLayout != null && reverseCameraEditor != null
                && reverseCameraPreview != null) {
            int selected = reverseCameraEditor.selectedCamera();
            reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
            reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
            reverseFrontRawCalibrationLayout =
                    ReverseCameraController.loadFrontRawLayout(preferences);
            reverseFrontCameraLayout = ReverseCameraController.loadFrontLayout(preferences);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            applyReversePreviewDewarpConfigs();
            reverseCameraPreview.applyVisibility(
                    ReverseCameraController.loadVisibilityMask(preferences));
            updateReversePaneControls(selected);
            if (reverseCalibrationCameraIndex > 0) {
                updateReverseCalibrationDisplay();
            }
        }
    }

    private void applyReversePreviewDewarpConfigs() {
        applyReversePreviewDewarpConfigs(0, null);
    }

    private void applyReversePreviewDewarpConfigs(
            int overrideCameraIndex, CameraDewarpConfig override) {
        if (reverseCameraPreview == null) return;
        CameraDewarpConfig rear = CameraDewarpConfig.loadForReverse(
                preferences, ReverseCameraLayout.REAR_CAMERA_INDEX);
        CameraDewarpConfig left = CameraDewarpConfig.loadForReverse(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        CameraDewarpConfig right = CameraDewarpConfig.loadForReverse(
                preferences, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX);
        if (override != null && !reverseCalibrationFront) {
            if (overrideCameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) rear = override;
            if (overrideCameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) left = override;
            if (overrideCameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) right = override;
        }
        reverseCameraPreview.applyDewarpConfigs(rear, left, right);
        if (reverseRawCalibrationLayout != null) {
            reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
        } else {
            reverseCameraPreview.applyRawFallbackLayout(
                    ReverseCameraController.loadRawLayout(preferences));
        }
        if (reverseCameraLayout != null) {
            reverseCameraPreview.applyLayout(reverseCameraLayout);
        }
        configureReversePreviewIntegratedFront(overrideCameraIndex,
                reverseCalibrationFront ? override : null);
    }

    private void configureReversePreviewIntegratedFront() {
        configureReversePreviewIntegratedFront(0, null);
    }

    private void configureReversePreviewIntegratedFront(
            int overrideCameraIndex, CameraDewarpConfig override) {
        if (reverseCameraPreview == null) return;
        if (reverseFrontRawCalibrationLayout == null) {
            reverseFrontRawCalibrationLayout =
                    ReverseCameraController.loadFrontRawLayout(preferences);
        }
        if (reverseFrontCameraLayout == null) {
            reverseFrontCameraLayout = ReverseCameraController.loadFrontLayout(preferences);
        }
        CameraDewarpConfig left = CameraDewarpConfig.loadForReverseFront(
                preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX);
        CameraDewarpConfig right = CameraDewarpConfig.loadForReverseFront(
                preferences, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX);
        CameraDewarpConfig central = CameraDewarpConfig.loadForReverseFront(
                preferences, ReverseCameraLayout.REAR_CAMERA_INDEX);
        if (override != null) {
            if (overrideCameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
                central = override;
            }
            if (overrideCameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX) {
                left = override;
            }
            if (overrideCameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX) {
                right = override;
            }
        }
        boolean centralIntegrated = ReverseCameraController.loadCentralFrontIntegrated(
                preferences);
        if (productionUi == null && reverseCalibrationFront
                && reverseCalibrationCameraIndex == ReverseCameraLayout.REAR_CAMERA_INDEX) {
            // Keep the central front source visible while calibrating it even when
            // the persisted integration toggle is still off.
            centralIntegrated = true;
        }
        reverseCameraPreview.configureIntegratedFront(
                reverseFrontCameraLayout, reverseFrontRawCalibrationLayout,
                left, right, central,
                ReverseCameraController.loadFrontIntegrated(
                        preferences, ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX),
                ReverseCameraController.loadFrontIntegrated(
                        preferences, ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX),
                centralIntegrated,
                true,
                ReverseCameraController.loadWidgetVisible(preferences));
        boolean showFront = productionUi != null
                ? productionUi.getState().getReverse().getShowFront()
                : reverseCalibrationFront && isReverseCameraPane(reverseCalibrationCameraIndex);
        if (showFront) {
            reverseCameraPreview.setSideMode(ReverseSideSelectorView.MODE_FRONT);
        }
    }




    private CameraProfile parkingPreviewProfile(ParkingCameraProfile profile) {
        switch (profile.id) {
            case ParkingCameraProfile.FL: return CameraProfile.of(CameraProfile.FRONT_LEFT);
            case ParkingCameraProfile.FR: return CameraProfile.of(CameraProfile.FRONT_RIGHT);
            case ParkingCameraProfile.RR: return CameraProfile.of(CameraProfile.REAR_RIGHT);
            case ParkingCameraProfile.RL: return CameraProfile.of(CameraProfile.REAR_LEFT);
            case ParkingCameraProfile.FRONT: return CameraProfile.of(CameraProfile.FRONT_LEFT);
            case ParkingCameraProfile.REAR: return CameraProfile.of(CameraProfile.REAR_LEFT);
            case ParkingCameraProfile.LEFT: return CameraProfile.of(CameraProfile.FRONT_LEFT);
            case ParkingCameraProfile.RIGHT: return CameraProfile.of(CameraProfile.FRONT_RIGHT);
            default: throw new IllegalArgumentException("invalid parking profile");
        }
    }

    private DirectCameraCrop loadCalibrationRawStored() {
        if (calibrationParkingMode) {
            return DirectCameraCrop.load(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId));
        }
        return DirectCameraCrop.load(preferences, CameraProfile.of(calibrationCameraId));
    }

    private DirectCameraCrop loadCalibrationCorrectedStored(DirectCameraCrop raw) {
        if (calibrationParkingMode) {
            return DirectCameraCrop.loadCorrected(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId), raw);
        }
        return DirectCameraCrop.loadCorrected(preferences,
                CameraProfile.of(calibrationCameraId), raw);
    }

    private CameraDewarpConfig loadCalibrationDewarpStored() {
        if (calibrationParkingMode) {
            return CameraDewarpConfig.loadForParking(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId));
        }
        return CameraDewarpConfig.loadForProfile(preferences, CameraProfile.of(calibrationCameraId));
    }



    private void saveCalibrationDewarpStored(CameraDewarpConfig value) {
        if (calibrationParkingMode) {
            CameraDewarpConfig.saveForParking(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId), value);
        } else {
            CameraDewarpConfig.saveForProfile(preferences,
                    CameraProfile.of(calibrationCameraId), value);
        }
    }













    private DirectCameraCrop loadCalibrationCrop(int cameraId) {
        CameraProfile profile = CameraProfile.of(cameraId);
        CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(
                preferences, profile);
        DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
        return dewarp.enabled
                ? DirectCameraCrop.loadCorrected(preferences, profile, raw) : raw;
    }

    private void migrateCameraFrameAspects() {
        for (CameraProfile profile : CameraProfile.values()) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(
                    preferences, profile);
            DirectCameraCrop active = dewarp.enabled
                    ? DirectCameraCrop.loadCorrected(preferences, profile, raw) : raw;
            BlindSpotOverlayController.readFrameAspect(
                    preferences, profile, active.outputAspect());
        }
    }

    private void updateCalibrationDisplay(boolean correctionEnabled) {
        boolean rawFallback = calibrationPreview != null && calibrationPreview.usesRawFallback();
        CalibrationUiState ui = calibrationUiState(correctionEnabled, rawFallback);
        if (!ui.correctedEditable
                && calibrationCropInputStage == CROP_STAGE_CORRECTED) {
            cancelCalibrationCropInput();
        }
        if (calibrationRawPane != null) {
            calibrationRawPane.setVisibility(View.VISIBLE);
        }
        if (calibrationCorrectedPane != null) {
            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams) calibrationCorrectedPane.getLayoutParams();
            if (params.width != ui.correctedPaneWidth
                    || params.weight != ui.correctedPaneWeight) {
                params.width = ui.correctedPaneWidth;
                params.weight = ui.correctedPaneWeight;
                calibrationCorrectedPane.setLayoutParams(params);
            }
            calibrationCorrectedPane.setVisibility(View.VISIBLE);
        }
        if (calibrationRawCropOverlay != null) {
            calibrationRawCropOverlay.setCrop(calibrationRawCrop.geometryOnly());
        }
        if (calibrationCropOverlay != null) {
            calibrationCropOverlay.setCrop(calibrationCorrectedCrop.geometryOnly());
        }
        DirectCameraCrop crop = ui.liveUsesCorrected
                ? calibrationCorrectedCrop : calibrationRawCrop;
        setCalibrationEditingState(ui);
        if (calibrationCropTitles[CROP_STAGE_RAW] != null) {
            calibrationCropTitles[CROP_STAGE_RAW].setText("RAW");
        }
        if (calibrationCropTitles[CROP_STAGE_CORRECTED] != null) {
            calibrationCropTitles[CROP_STAGE_CORRECTED].setText("CORRECTED");
        }
        updateCalibrationUi(crop);
        renderCalibrationCrop();
    }

    private static void applyDewarpSourceRoi(
            BlindSpotCameraView view, DirectCameraCrop crop) {
        view.applyDewarpSourceRoi(crop.left, crop.top, crop.width, crop.height);
    }





    private DirectCameraCrop currentCalibrationCrop() {
        if (calibrationCropOverlay == null) {
            return calibrationParkingMode
                    ? DirectCameraCrop.defaultFor(
                            ParkingCameraProfile.of(calibrationParkingCameraId))
                    : DirectCameraCrop.defaultFor(CameraProfile.of(calibrationCameraId));
        }
        boolean corrected = calibrationDewarpSwitch != null
                && calibrationDewarpSwitch.isChecked()
                && calibrationPreview != null && !calibrationPreview.usesRawFallback();
        return corrected ? calibrationCorrectedCrop : calibrationRawCrop;
    }

    private void updateCalibrationUi(DirectCameraCrop crop) {
        updateCalibrationCropReadouts();
        if (calibrationRotationSlider != null) {
            calibrationRotationUiUpdating = true;
            calibrationRotationSlider.setProgress(
                    crop.rotationDegrees - CameraRotation.MIN_DEGREES);
            calibrationRotationValue.setText(crop.rotationDegrees + "°");
            calibrationRotationUiUpdating = false;
        }
        if (calibrationRotationModeInput != null) {
            calibrationRotationModeUiUpdating = true;
            calibrationRotationModeInput.setSelection(crop.rotationMode, false);
            calibrationRotationModeUiUpdating = false;
        }
        if (calibrationResultHost != null && calibrationResultFrame != null) {
            fitAspectFrame(
                    calibrationResultHost, calibrationResultFrame,
                    currentCalibrationLiveAspect());
            calibrationResultFrame.post(this::renderCalibrationCrop);
        }
    }

    private float currentCalibrationLiveAspect() {
        return calibrationLiveAspect(
                preferences,
                calibrationParkingMode,
                calibrationParkingMode ? calibrationParkingCameraId : calibrationCameraId,
                currentCalibrationCrop().outputAspect());
    }

    static float calibrationLiveAspect(
            SharedPreferences preferences, boolean parking, int cameraId, float fallback) {
        return parking ? 4.0f / 3.0f : BlindSpotOverlayController.readFrameAspect(
                preferences, CameraProfile.of(cameraId), fallback);
    }

    private void updateCalibrationCropReadouts() {
        if (calibrationCropReadouts[CROP_STAGE_RAW] == null) return;
        CalibrationUiState ui = calibrationUiState(
                calibrationDewarpSwitch != null && calibrationDewarpSwitch.isChecked(),
                calibrationPreview != null && calibrationPreview.usesRawFallback());
        calibrationCropReadouts[CROP_STAGE_RAW].setText(cropCoordinates(
                calibrationRawCrop.left, calibrationRawCrop.top,
                calibrationRawCrop.width, calibrationRawCrop.height));
        calibrationCropReadouts[CROP_STAGE_CORRECTED].setText(cropCoordinates(
                calibrationCorrectedCrop.left, calibrationCorrectedCrop.top,
                calibrationCorrectedCrop.width, calibrationCorrectedCrop.height));
        calibrationCropInputButtons[CROP_STAGE_RAW].setEnabled(true);
        calibrationCropInputButtons[CROP_STAGE_CORRECTED]
                .setEnabled(ui.correctedEditable);
        if (calibrationPresetLoadButton != null) {
            calibrationPresetLoadButton.setEnabled(calibrationParkingMode
                    ? CameraCalibrationPreset.hasParking(preferences,
                            ParkingCameraProfile.of(calibrationParkingCameraId))
                    : CameraCalibrationPreset.hasCamera(preferences,
                            CameraProfile.of(calibrationCameraId)));
        }
        if (calibrationMirrorButton != null) {
            calibrationMirrorButton.setText(calibrationParkingMode
                    ? parkingCalibrationTransferLabel(
                            ParkingCameraProfile.of(calibrationParkingCameraId))
                    : calibrationTransferLabel(CameraProfile.of(calibrationCameraId).right()));
        }
        if (calibrationOutputMirrorButton != null) {
            calibrationOutputMirrorButton.setBackgroundColor(
                    tabColor(currentCalibrationCrop().mirrorHorizontally));
        }
    }

    private void fitAspectFrame(FrameLayout host, View frame, float aspect) {
        if (host.getWidth() <= 0 || host.getHeight() <= 0) return;
        int width = host.getWidth();
        int height = Math.round(width / aspect);
        if (height > host.getHeight()) {
            height = host.getHeight();
            width = Math.round(height * aspect);
        }
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) frame.getLayoutParams();
        if (params.width == width && params.height == height) return;
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        frame.setLayoutParams(params);
    }

    private void openCalibrationCamera(int index) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        Surface surface = calibrationPreview.getCameraSurface();
        if (current == null || !calibrationSurfaceReady || !surface.isValid()
                || requestedOpen || cameraHandoffPending) {
            record("open_rejected", "renderer", "direct_crop_calibration",
                    "camera_tag", "pano_h", "preview_index", index,
                    "reason", "camera_not_ready");
            return;
        }
        activePreview = calibrationPreview;
        activePreviewCover = calibrationPreviewCover;
        activeCameraViewpoint = -1;
        record("camera_preview_attach", "state", "started",
                "renderer", "direct_crop_calibration", "camera_tag", "pano_h",
                "preview_index", index, "exclusive", false,
                "status", "Opening pano_h / index " + index + "...");
        CameraHelperService.cameraPreviewStarted(this);
        openDirectCameraNow("pano_h", index, true);
    }

    private void maybeOpenCalibrationCamera() {
        if (!canAutoOpenSelectedPreview()
                || !isProductionCalibrationSection()
                || helper == null || !cameraDiscovered
                || calibrationPreview == null || requestedOpen || cameraHandoffPending
                || cameraTransition.pending()
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        if (prepareAutomaticResumeInputIfNeeded() || !calibrationSurfaceReady) return;
        Surface surface = calibrationPreview.getCameraSurface();
        if (surface != null && surface.isValid()) {
            CameraProfileId profile = selectedProductionProfile();
            if (profile == null) return;
            configureProductionCameraProfile(profile, null);
            int sourceIndex = profile instanceof CameraProfileId.Mirror
                    ? RearviewMirrorSettings.cameraIndex(preferences)
                    : profile instanceof CameraProfileId.Blind
                    ? blindProfile((CameraProfileId.Blind) profile).previewIndex
                    : profile instanceof CameraProfileId.Parking
                            ? parkingProfile((CameraProfileId.Parking) profile).physicalCameraIndex
                            : ((CameraProfileId.Reverse) profile).getSource()
                                    == ReverseSource.Front
                                    && reverseProfileIndex((CameraProfileId.Reverse) profile)
                                            == ReverseCameraLayout.REAR_CAMERA_INDEX
                                            ? 4 : reverseProfileIndex(
                                                    (CameraProfileId.Reverse) profile);
            openCalibrationCamera(sourceIndex);
        }
    }

    private boolean isProductionCalibrationSection() {
        if (productionUi == null) return selectedTab == TAB_CAMERA_CALIBRATION;
        if (selectedTab == TAB_REARVIEW_MIRROR) {
            return productionUi.getState().getMirror().getSection() == CameraSection.Calibration;
        }
        if (selectedTab == TAB_CAMERAS) {
            return productionUi.getState().getBlind().getSection()
                    == CameraSection.Calibration;
        }
        if (selectedTab == TAB_PARKING_CAMERAS) {
            return productionUi.getState().getParking().getSection()
                    == CameraSection.Calibration;
        }
        return selectedTab == TAB_REVERSE_CAMERAS
                && productionUi.getState().getReverse().getSection()
                        == CameraSection.Calibration;
    }

    private boolean shouldCopyCalibrationFrame() {
        return activityResumed && selectedTab == TAB_CAMERA_CALIBRATION && requestedOpen
                && activePreview == calibrationPreview && calibrationSurfaceReady
                && activeActivityCameraOpened && activeActivityCameraFresh;
    }

    private void startCalibrationCopies() {
        mainHandler.removeCallbacks(copyCalibrationFrame);
        // Compose displays the producer and its raw/corrected mirror surfaces directly.
        // The legacy TextureView.getBitmap/Canvas projection is intentionally not started.
    }

    private void stopCalibrationCopies(boolean clearPreview) {
        mainHandler.removeCallbacks(copyCalibrationFrame);
        if (clearPreview && calibrationCropPreview != null) {
            calibrationCropPreview.setImageDrawable(null);
        }
    }

    private void copyCalibrationFrame() {
        if (!shouldCopyCalibrationFrame() || calibrationCopyPending) return;
        CalibrationUiState ui = calibrationUiState(
                calibrationDewarpSwitch != null && calibrationDewarpSwitch.isChecked(),
                calibrationPreview != null && calibrationPreview.usesRawFallback());
        TextureView source = ui.copyRawMirror
                ? calibrationRawMirror : calibrationPreview.textureView();
        boolean sourceAvailable = source != null && source.isAvailable();
        int width = sourceAvailable ? source.getWidth() : 0;
        int height = sourceAvailable ? source.getHeight() : 0;
        if (width <= 0 || height <= 0) {
            if (shouldRetryCalibrationCopy(true, width, height)) {
                mainHandler.postDelayed(copyCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
            }
            return;
        }
        if (calibrationCaptureBitmap == null
                || calibrationCaptureBitmap.getWidth() != width
                || calibrationCaptureBitmap.getHeight() != height) {
            calibrationCaptureBitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
        }
        calibrationCopyPending = true;
        try {
            source.getBitmap(calibrationCaptureBitmap);
            calibrationCopyPending = false;
            if (shouldCopyCalibrationFrame()) renderCalibrationCrop();
            if (shouldCopyCalibrationFrame()) {
                mainHandler.postDelayed(copyCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
            }
        } catch (Throwable error) {
            calibrationCopyPending = false;
            record("calibration_texture_copy", "error", error.toString());
            if (shouldCopyCalibrationFrame()) {
                mainHandler.postDelayed(copyCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
            }
        }
    }

    private void renderCalibrationCrop() {
        if (calibrationCaptureBitmap == null || calibrationResultFrame == null
                || calibrationResultFrame.getWidth() <= 0
                || calibrationResultFrame.getHeight() <= 0) return;
        int width = calibrationResultFrame.getWidth();
        int height = calibrationResultFrame.getHeight();
        if (calibrationResultBitmap == null
                || calibrationResultBitmap.getWidth() != width
                || calibrationResultBitmap.getHeight() != height) {
            calibrationResultBitmap = Bitmap.createBitmap(
                    width, height, Bitmap.Config.ARGB_8888);
            calibrationCropPreview.setImageBitmap(calibrationResultBitmap);
        }
        DirectCameraCrop crop = currentCalibrationCrop();
        int sourceWidth = calibrationCaptureBitmap.getWidth();
        int sourceHeight = calibrationCaptureBitmap.getHeight();
        Canvas canvas = new Canvas(calibrationResultBitmap);
        canvas.drawColor(Color.BLACK);
        Matrix transform = new Matrix();
        CameraRotation.setSourceCropTransformForInput(
                transform,
                crop.left, crop.top, crop.width, crop.height,
                new RectF(0.0f, 0.0f, width, height),
                crop.rotationDegrees,
                crop.rotationMode,
                BlindSpotCameraView.BUFFER_WIDTH,
                BlindSpotCameraView.BUFFER_HEIGHT,
                sourceWidth, sourceHeight,
                crop.mirrorHorizontally);
        canvas.save();
        canvas.clipPath(cropPath(CameraRotation.transformedCropCornersForInput(
                crop.left, crop.top, crop.width, crop.height,
                new RectF(0.0f, 0.0f, width, height), crop.rotationDegrees,
                crop.rotationMode, BlindSpotCameraView.BUFFER_WIDTH,
                BlindSpotCameraView.BUFFER_HEIGHT, sourceWidth, sourceHeight,
                crop.mirrorHorizontally)));
        canvas.drawBitmap(calibrationCaptureBitmap, transform, calibrationCropPaint);
        canvas.restore();
        calibrationCropPreview.setImageBitmap(calibrationResultBitmap);
        calibrationCropPreview.invalidate();
    }

    private static Path cropPath(float[] corners) {
        Path path = new Path();
        if (corners == null || corners.length < 6) return path;
        path.moveTo(corners[0], corners[1]);
        for (int i = 2; i + 1 < corners.length; i += 2) {
            path.lineTo(corners[i], corners[i + 1]);
        }
        path.close();
        return path;
    }

    private void selectDebugOrientation(boolean horizontal) {
        if (debugHorizontal == horizontal) return;
        debugHorizontal = horizontal;
        preferences.edit().putBoolean("debug_avm_horizontal", horizontal).apply();
        record("debug_sdk_orientation", "orientation",
                horizontal ? "horizontal" : "vertical");
        if (productionUi != null && productionUi.getState().getDebug().getAvmSelection() == null) {
            if (activePreview == debugPreview || pendingCameraDebug) stopActivityCameraManually("avm_mode_deferred");
            return;
        }
        if (requestedOpen && activePreview == debugPreview && activeCameraViewpoint >= 0) {
            openStockAvmNow(activeCameraViewpoint, true);
        }
    }





    TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        return label;
    }

    TextView statusText(String text) {
        TextView status = new TextView(this);
        status.setText(text);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        return status;
    }

    Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setAllCaps(false);
        return button;
    }


    private void requestManualTurnState(int payload) {
        IBinder current = helper;
        if (!manualDiagnosticsAllowed() || payload < 0 || payload > 3) return;
        manualTurnRequestPending = true;
        publishManualSignalStatus("Команда поворотників: payload " + payload + "...",
                StatusTone.Warning);
        record("manual_turn_state_ui_request", "payload", payload);
        updateControls();
        ipcExecutor.execute(() -> transactManualTurnState(current, payload));
    }








    void clearCaptureLogs() {
        if (logExportInProgress || compatibilityExportInProgress || activityDestroyed) return;
        String clearing = runtimeText(R.string.runtime_logs_clearing);
        publishSettingsOperation(SettingsOperation.Logs,
                clearing, StatusTone.Warning, true);
        recordOperationFeedback(clearing);
        AsyncServiceLog log = activityLog;
        if (log != null) {
            log.flush(() -> logExportExecutor.execute(this::clearCaptureLogsOnWorker));
        } else logExportExecutor.execute(this::clearCaptureLogsOnWorker);
    }

    private void clearCaptureLogsOnWorker() {
        File captures = logFile == null ? null : logFile.getParentFile();
        LogClearResult result = deleteCaptureLogFiles(captures);
        try {
            result.deleted += AvasRecoveryDaemonController.clearContinuousLogcat(this);
        } catch (Exception failure) {
            result.failed++;
            record("continuous_logcat_clear_failed", "error", failure.toString());
        }
        record("logs_cleared", "deleted", result.deleted, "failed", result.failed);
        mainHandler.post(() -> {
            if (activityDestroyed || isFinishing()) return;
            String message = runtimeText(result.messageResource(), result.deleted, result.failed);
            StatusTone tone = result.failed == 0 ? StatusTone.Ok
                    : result.deleted > 0 ? StatusTone.Warning : StatusTone.Error;
            recordOperationFeedback(message);
            publishSettingsOperation(SettingsOperation.Logs, message, tone, false);
            showSettingsResultToast(message);
        });
    }

    static final class LogClearResult {
        int deleted;
        int failed;

        int messageResource() {
            return failed == 0 ? R.string.runtime_logs_cleared
                    : deleted > 0 ? R.string.runtime_logs_clear_partial
                    : R.string.runtime_logs_clear_failed;
        }
    }

    static LogClearResult deleteCaptureLogFiles(File captures) {
        LogClearResult result = new LogClearResult();
        File[] logs;
        try {
            logs = captures == null ? null
                    : captures.listFiles((directory, name) -> name.endsWith(".jsonl"));
        } catch (SecurityException unreadable) {
            logs = null;
        }
        if (logs == null) {
            result.failed++;
            return result;
        }
        for (File file : logs) {
            try {
                if (file.delete()) result.deleted++;
                else result.failed++;
            } catch (SecurityException denied) {
                result.failed++;
            }
        }
        return result;
    }



    private void openCamera(String viewName) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        Surface surface = debugSurface();
        if (current == null || !debugSurfaceReady || surface == null || !surface.isValid()) {
            record("open_rejected", "view", viewName, "reason", "camera_not_ready");
            return;
        }
        int index = previewIndex(viewName);
        int requestId = beginActivityCameraRequest(false);
        activePreview = debugPreview;
        activePreviewCover = debugPreviewCover;
        activeCameraViewpoint = -1;
        showPreview(debugPreview, activePreviewCover, false, false);
        requestedOpen = true;
        publishDiagnosticStatus(false, "Opening " + viewName + " (preview " + index + ")...",
                StatusTone.Warning, true);
        record("open_requested", "view", viewName, "preview_index", index,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "input_generations", java.util.Arrays.toString(
                        activeActivityInputGenerations));
        updateControls();
        ipcExecutor.execute(() -> transactOpen(
                current, surface, index, viewName, requestId));
    }

    private void openDirectCamera(int index) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        Surface surface = directCameraPreview.getHolder().getSurface();
        if (current == null || !directCameraSurfaceReady || !surface.isValid()
                || cameraHandoffPending) {
            record("open_rejected", "renderer", "direct_avm",
                    "camera_tag", "pano_h", "preview_index", index,
                    "reason", "camera_not_ready");
            return;
        }
        if (!directCameraSelectionAllowed(
                requestedOpen, activePreview == directCameraPreview,
                activeActivityCameraOpened)) {
            record("open_rejected", "renderer", "direct_avm",
                    "camera_tag", "pano_h", "preview_index", index,
                    "reason", "camera_busy");
            return;
        }
        if (requestedOpen) {
            if (activePreview != directCameraPreview || !activeActivityCameraOpened
                    || activeDirectCameraIndex == index) {
                record("open_rejected", "renderer", "direct_avm",
                        "camera_tag", "pano_h", "preview_index", index,
                        "reason", activePreview == directCameraPreview
                                && activeActivityCameraOpened
                                ? "camera_already_selected" : "camera_busy");
                return;
            }
            switchDirectCamera(index, current, surface);
            return;
        }
        activePreview = directCameraPreview;
        activePreviewCover = directCameraPreviewCover;
        activeCameraViewpoint = -1;
        publishDiagnosticStatus(true,
                "Opening pano_h / index " + index + "...",
                StatusTone.Warning, true);
        record("camera_preview_attach", "state", "started",
                "renderer", "direct_avm", "camera_tag", "pano_h",
                "preview_index", index, "exclusive", true);
        CameraHelperService.cameraPreviewStarted(this);
        openDirectCameraNow("pano_h", index, false);
    }

    private void switchDirectCamera(int index, IBinder current, Surface surface) {
        int previousIndex = activeDirectCameraIndex;
        int requestId = beginActivityCameraRequest(false);
        activePreview = directCameraPreview;
        activePreviewCover = directCameraPreviewCover;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = index;
        if (directCameraPreviewCover != null) {
            directCameraPreviewCover.setVisibility(View.VISIBLE);
        }
        publishDiagnosticStatus(true,
                "Switching pano_h / index " + index + "...",
                StatusTone.Warning, true);
        record("camera_preview_switch", "renderer", "direct_avm",
                "camera_tag", "pano_h", "from_preview_index", previousIndex,
                "preview_index", index, "request_id", requestId,
                "exclusive", true);
        ipcExecutor.execute(() -> transactOpenDirect(
                current, surface, "pano_h", index, requestId, true));
    }

    private void maybeOpenReversePreview() {
        if (!canAutoOpenSelectedPreview()
                || selectedTab != TAB_REVERSE_CAMERAS || helper == null || !cameraDiscovered
                || isProductionCalibrationSection()
                || reverseCameraPreview == null
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending || cameraTransition.pending()) {
            return;
        }
        if (reverseCameraPreview.previewSourceIndexes().length == 0) {
            clearReversePanoramaStatus();
            return;
        }
        if (prepareAutomaticResumeInputIfNeeded() || !reverseCameraSurfacesReady
                || !reverseCameraPreview.previewSurfacesReady()) return;
        int requestId = nextActivityCameraRequestId();
        ReverseCameraCompositionView.SurfaceBundle bundle;
        try {
            bundle = reverseCameraPreview.acquirePreviewSurfaces(requestId);
            beginActivityCameraRequest(true, requestId, bundle.frameGenerations);
            activeReversePreviewDisplayTarget = reverseCameraPreview.displayTarget();
            activeReversePreviewSourceIndexes = bundle.sourceIndexes.clone();
            activeReversePreviewDirectGenerations = bundle.generations.clone();
            reversePreviewBackgroundFailureRequestId = 0;
            reverseCameraPreview.setDewarpStatsContext(
                    requestId, bundle.generations);
            pendingReversePreviewRequestId = requestId;
            pendingReversePreviewGenerations = bundle.frameGenerations.clone();
        } catch (Throwable error) {
            activeActivityCameraRequestId = 0;
            activeActivityCameraProfile = null;
            activeActivityConsumerGeneration = 0;
            activeActivityInputGenerations = new int[0];
            activeActivityCameraOpened = false;
            activeActivityCameraFresh = false;
            pendingReversePreviewRequestId = 0;
            pendingReversePreviewGenerations = null;
            record("camera_status", "profile", "reverse", "text",
                    "Reverse camera surface unavailable",
                    "tone", StatusTone.Error.name(), "pending", false);
            record("reverse_preview_error", "stage", "acquire_surfaces",
                    "error", error.toString());
            return;
        }
        activePreview = reverseCameraPreview;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        requestedOpen = true;
        if (bundle.panoramaSurface != null) {
            publishReversePanoramaStatus(
                    runtimeText(R.string.runtime_status_opening_panorama),
                    StatusTone.Warning, true);
        } else clearReversePanoramaStatus();
        publishCameraStatus(activeActivityCameraProfile, null,
                "Відкриття камер заднього ходу...", StatusTone.Warning, true);
        record("camera_status", "profile", "reverse", "text",
                "Відкриття камер заднього ходу...",
                "tone", StatusTone.Warning.name(), "pending", true);
        CameraHelperService.cameraPreviewStarted(this);
        record("reverse_preview_open",
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "display_target", activeReversePreviewDisplayTarget,
                "source_indexes", java.util.Arrays.toString(bundle.sourceIndexes),
                "panorama", bundle.panoramaSurface != null,
                "input_generations", java.util.Arrays.toString(bundle.frameGenerations),
                "generations", java.util.Arrays.toString(bundle.generations));
        updateControls();
        IBinder current = helper;
        ipcExecutor.execute(() -> transactOpenReversePreview(
                current, bundle, requestId));
    }

    private void maybeOpenProductionPreview() {
        if (!canAutoOpenSelectedPreview()
                || (selectedTab != TAB_CAMERAS && selectedTab != TAB_PARKING_CAMERAS
                        && selectedTab != TAB_REARVIEW_MIRROR)
                || isProductionCalibrationSection()
                || helper == null || !cameraDiscovered
                || cameraPreview == null
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending || cameraTransition.pending()) {
            return;
        }
        if (prepareAutomaticResumeInputIfNeeded() || !cameraSurfaceReady) return;
        CameraProfileId selected = selectedProductionProfile();
        if (selected != null) configureProductionCameraProfile(selected, null);
        if (selectedTab == TAB_REARVIEW_MIRROR) {
            openProductionDirectCamera(RearviewMirrorSettings.cameraIndex(preferences));
            return;
        }
        if (selectedTab == TAB_PARKING_CAMERAS) {
            openProductionDirectCamera(
                    ParkingCameraProfile.of(selectedParkingCameraId).physicalCameraIndex);
            return;
        }
        CameraProfile profile = CameraProfile.of(selectedCameraId);
        openStockAvm(profile.right()
                ? StockAvmPreview.VIEW_BLIND_SPOT_RIGHT
                : StockAvmPreview.VIEW_BLIND_SPOT_LEFT, false);
    }

    private void openProductionDirectCamera(int index) {
        if (!canStartActivityCamera() || cameraPreview == null) return;
        IBinder current = helper;
        Surface surface = cameraPreview.getCameraSurface();
        if (current == null || !cameraSurfaceReady || surface == null || !surface.isValid()
                || requestedOpen || cameraHandoffPending) return;
        activePreview = cameraPreview;
        activePreviewCover = cameraPreviewCover;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = index;
        showPreview(cameraPreview, cameraPreviewCover, false, false);
        int requestId = beginActivityCameraRequest(
                true, cameraPreview.cameraInputGeneration());
        cameraPreview.setDewarpStatsContext(
                requestId, cameraPreview.cameraInputGeneration());
        requestedOpen = true;
        publishCameraStatus(activeActivityCameraProfile, null,
                "Opening direct parking camera / index " + index + "...",
                StatusTone.Warning, true);
        CameraHelperService.cameraPreviewStarted(this);
        record("open_requested", "renderer", "direct_parking",
                "camera_tag", "pano_h", "preview_index", index,
                "request_id", requestId, "exclusive", false);
        ipcExecutor.execute(() -> transactOpenDirect(
                current, surface, "pano_h", index, requestId, false));
    }

    private void openDirectCameraNow(String cameraTag, int index, boolean calibration) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        View target = calibration ? calibrationPreview : directCameraPreview;
        View cover = calibration ? calibrationPreviewCover : directCameraPreviewCover;
        boolean surfaceReady = calibration ? calibrationSurfaceReady : directCameraSurfaceReady;
        Surface surface = calibration ? calibrationPreview.getCameraSurface()
                : directCameraPreview.getHolder().getSurface();
        String renderer = calibration ? "direct_crop_calibration" : "direct_avm";
        if (current == null || !surfaceReady || !surface.isValid()) {
            record("open_rejected", "renderer", renderer,
                    "camera_tag", cameraTag, "preview_index", index,
                    "reason", "camera_not_ready_after_handoff");
            activePreview = null;
            activeActivityCameraProfile = null;
            activePreviewCover = null;
            CameraHelperService.cameraPreviewStopped(this);
            updateControls();
            return;
        }
        activePreview = target;
        activePreviewCover = cover;
        activeDirectCameraIndex = index;
        int requestId = beginActivityCameraRequest(
                calibration, calibration
                        ? new int[]{calibrationPreview.cameraInputGeneration()}
                        : new int[0]);
        if (calibration) {
            activeActivityCameraProfile = calibrationHostProfile != null
                    ? calibrationHostProfile : calibrationProfileForUi();
            calibrationPreview.setDewarpStatsContext(
                    requestId, calibrationPreview.cameraInputGeneration());
        }
        showPreview(target, cover, false, false);
        requestedOpen = true;
        if (!calibration) publishDiagnosticStatus(true,
                "Opening " + cameraTag + " / index " + index + "...",
                StatusTone.Warning, true);
        else publishCameraStatus(activeActivityCameraProfile, null,
                "Opening " + cameraTag + " / index " + index + "...",
                StatusTone.Warning, true);
        record("open_requested", "renderer", renderer,
                "camera_tag", cameraTag, "preview_index", index,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "input_generations", java.util.Arrays.toString(
                        activeActivityInputGenerations));
        updateControls();
        boolean exclusive = activityPreviewUsesExclusiveConsumer(!calibration);
        ipcExecutor.execute(() -> transactOpenDirect(
                current, surface, cameraTag, index, requestId, exclusive));
    }

    static boolean activityPreviewUsesExclusiveConsumer(boolean manualOrDebug) {
        return manualOrDebug;
    }

    /**
     * Direct AVM buttons may start an initial request or switch an already-open
     * direct preview.  A request in flight for another preview must not race
     * with a direct-camera selection.
     */
    static boolean directCameraSelectionAllowed(
            boolean requestedOpen, boolean directPreviewActive, boolean cameraOpened) {
        return !requestedOpen || (directPreviewActive && cameraOpened);
    }

    private static int previewIndex(String viewName) {
        switch (viewName) {
            case "raw": return 0;
            case "rear": return 1;
            case "left": return 2;
            case "right": return 3;
            case "front": return 4;
            default: throw new IllegalArgumentException("Unknown view: " + viewName);
        }
    }

    private static synchronized int nextActivityCameraRequestId() {
        activityCameraRequestSequence = activityCameraRequestSequence == Integer.MAX_VALUE
                ? 1 : activityCameraRequestSequence + 1;
        return activityCameraRequestSequence;
    }

    private int beginActivityCameraRequest(boolean automatic, int... inputGenerations) {
        return beginActivityCameraRequest(
                automatic, nextActivityCameraRequestId(), inputGenerations);
    }

    private int beginActivityCameraRequest(
            boolean automatic, int requestId, int[] inputGenerations) {
        if (requestId <= 0) throw new IllegalArgumentException("camera request required");
        if (automatic && (inputGenerations == null || inputGenerations.length == 0)) {
            throw new IllegalArgumentException("automatic camera input required");
        }
        activeActivityCameraRequestId = requestId;
        // Compose selects the next profile before notifying the backend; retain this request's
        // owner.  Reverse composition is one combined frame, so its status remains anchored to
        // the canonical Rear pane even when Background, Widget, or another pane is selected.
        boolean combinedReverseComposition = selectedTab == TAB_REVERSE_CAMERAS
                && !isProductionCalibrationSection();
        activeActivityCameraProfile = automatic
                ? combinedReverseComposition
                        ? reverseCompositionStatusProfile() : selectedProductionProfile()
                : null;
        activeActivityConsumerGeneration = 0;
        activeActivityInputGenerations = inputGenerations == null
                ? new int[0] : inputGenerations.clone();
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        clearActivityCameraCloseIdentity();
        if (automatic) {
            if (selectedTab == TAB_REVERSE_CAMERAS) {
                stopReverseCalibrationCopies(true);
            }
            automaticPreviewIntent = true;
            automaticPreviewIntentTab = selectedTab;
            automaticPreviewIntentRequestId = requestId;
        }
        return requestId;
    }

    private void openStockAvm(int viewpoint, boolean debug) {
        if (!canStartActivityCamera()) return;
        if (debug && !debugAvmModeAvailable(viewpoint)) return;
        View target = debug ? debugPreview : cameraPreview;
        boolean surfaceReady = debug ? debugSurfaceReady : cameraSurfaceReady;
        Surface surface = debug
                ? debugSurface()
                : cameraPreview.getCameraSurface();
        boolean right = viewpoint == StockAvmPreview.VIEW_BLIND_SPOT_RIGHT;
        if (!debug && !right && viewpoint != StockAvmPreview.VIEW_BLIND_SPOT_LEFT) {
            record("open_rejected", "renderer", "direct_blind_spot",
                    "viewpoint", viewpoint, "reason", "unsupported_direction");
            return;
        }
        String viewName = debug ? StockAvmPreview.viewName(viewpoint)
                : right ? "direct_pano_h_index_3" : "direct_pano_h_index_2";
        String renderer = debug ? "stock_avm" : "direct_blind_spot";
        if (helper == null || !surfaceReady || surface == null || !surface.isValid()) {
            record("open_rejected", "renderer", renderer,
                    "view", viewName, "reason", "camera_not_ready");
            return;
        }
        if (requestedOpen) {
            openStockAvmNow(viewpoint, debug);
            return;
        }
        if (!debug) {
            openStockAvmNow(viewpoint, false);
            return;
        }
        if (cameraHandoffPending) return;
        cameraHandoffPending = true;
        pendingCameraViewpoint = viewpoint;
        pendingCameraDebug = debug;
        if (debug) publishDiagnosticStatus(false,
                "Preparing " + viewName + "...", StatusTone.Warning, true);
        record("camera_preview_handoff", "state", "started",
                "renderer", renderer, "viewpoint", viewpoint,
                "delay_ms", CAMERA_PREVIEW_HANDOFF_MS);
        CameraHelperService.cameraPreviewStarted(this);
        target.postDelayed(finishCameraHandoff, CAMERA_PREVIEW_HANDOFF_MS);
        updateControls();
    }

    private void openPendingStockAvm() {
        if (!canStartActivityCamera()) {
            closeCamera("activity_paused");
            return;
        }
        if (!cameraHandoffPending) return;
        int viewpoint = pendingCameraViewpoint;
        boolean debug = pendingCameraDebug;
        cameraHandoffPending = false;
        pendingCameraViewpoint = -1;
        record("camera_preview_handoff", "state", "completed", "viewpoint", viewpoint);
        openStockAvmNow(viewpoint, debug);
    }

    private void openStockAvmNow(int viewpoint, boolean debug) {
        if (!canStartActivityCamera()) return;
        if (debug && !debugAvmModeAvailable(viewpoint)) return;
        IBinder current = helper;
        View target = debug ? debugPreview : cameraPreview;
        View cover = debug ? debugPreviewCover : cameraPreviewCover;
        boolean surfaceReady = debug ? debugSurfaceReady : cameraSurfaceReady;
        Surface surface = debug
                ? debugSurface()
                : cameraPreview.getCameraSurface();
        boolean right = viewpoint == StockAvmPreview.VIEW_BLIND_SPOT_RIGHT;
        String viewName = debug ? StockAvmPreview.viewName(viewpoint)
                : right ? "direct_pano_h_index_3" : "direct_pano_h_index_2";
        String renderer = debug ? "stock_avm" : "direct_blind_spot";
        if (current == null || !surfaceReady || surface == null || !surface.isValid()) {
            record("open_rejected", "renderer", renderer,
                    "view", viewName, "reason", "camera_not_ready");
            CameraHelperService.cameraPreviewStopped(this);
            updateControls();
            return;
        }
        activePreview = target;
        activePreviewCover = cover;
        activeCameraViewpoint = viewpoint;
        activeDirectCameraIndex = -1;
        showPreview(target, cover, false, false);
        int requestId = beginActivityCameraRequest(
                !debug, debug ? new int[0]
                        : new int[]{cameraPreview.cameraInputGeneration()});
        if (!debug) {
            CameraProfile profile = CameraProfile.of(selectedCameraId);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDewarpConfig(
                    CameraDewarpConfig.loadForProfile(preferences, profile));
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            requestedOpen = true;
            publishCameraStatus(activeActivityCameraProfile, null,
                    "Opening " + viewName + "...", StatusTone.Warning, true);
            int previewIndex = right ? 3 : 2;
            record("open_requested", "renderer", renderer,
                    "camera_tag", "pano_h", "preview_index", previewIndex,
                    "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                    "request_id", requestId, "consumer_generation", 0,
                    "input_generations", java.util.Arrays.toString(
                            activeActivityInputGenerations),
                    "direction", right ? "right" : "left",
                    "status", "Opening " + viewName + "...",
                    "camera_id", profile.id, "camera", profile.wireName);
            updateControls();
            ipcExecutor.execute(() -> transactOpenDirect(
                    current, surface, "pano_h", previewIndex, requestId, false));
            return;
        }
        applyDebugPreviewMode();
        requestedOpen = true;
        boolean horizontal = productionUi == null ? debugHorizontal
                : productionUi.getState().getDebug().getAvmOrientation()
                        == AvmOrientation.Horizontal;
        boolean stockDewarp = shouldUseStockDewarp(
                debug, preferences.getBoolean("debug_avm_dewarp", false));
        publishDiagnosticStatus(false, "Opening " + viewName + "...",
                StatusTone.Warning, true);
        record("open_requested", "renderer", "stock_avm",
                "view", viewName, "viewpoint", viewpoint,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "input_generations", java.util.Arrays.toString(
                        activeActivityInputGenerations),
                "orientation", horizontal ? "horizontal" : "vertical",
                "show_raw", preferences.getBoolean("debug_avm_show_raw", true),
                "dewarp", stockDewarp,
                "target", "debug");
        updateControls();
        ipcExecutor.execute(() -> transactOpenStockAvm(
                current, surface, viewpoint, horizontal, stockDewarp, requestId));
    }

    private void acceptDiagnosticIntent(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null || productionUi == null) return;
        if (intent.getBooleanExtra(EXTRA_DIAGNOSTIC_AVM_CLOSE, false)) {
            productionUi.dispatch(new BydExtendUiAction.Run(CommandId.StopDiagnosticCamera, null));
            return;
        }
        if (!intent.hasExtra(EXTRA_DIAGNOSTIC_AVM_MODE_INDEX)) return;

        int index = intent.getIntExtra(EXTRA_DIAGNOSTIC_AVM_MODE_INDEX, -1);
        if (index < 0 || index >= StockAvmPreview.horizontalLayoutCount()) {
            record("diagnostic_mode_rejected", "index", index, "reason", "invalid_index");
            return;
        }

        productionUi.dispatch(new BydExtendUiAction.Navigate(RootTab.Debug));
        productionUi.dispatch(new BydExtendUiAction.Select(
                new SelectionTarget.Simple(SelectionId.DiagnosticMode), DiagnosticMode.Avm.ordinal()));
        productionUi.dispatch(new BydExtendUiAction.Select(
                new SelectionTarget.Simple(SelectionId.AvmMode), index));
        String mode = StockAvmPreview.horizontalLayoutName(index);
        record("diagnostic_mode_requested", "index", index, "view", mode);
    }

    private void verifyMappings() {
        boolean valid = StockAvmPreview.horizontalLayoutCount() == 51
                && "VIEW_2D_TOP".equals(StockAvmPreview.horizontalLayoutName(0))
                && "VIEW_2D_RIGHT_CLAIRVOYANCE".equals(
                        StockAvmPreview.horizontalLayoutName(50));
        if (!valid) throw new IllegalStateException("Camera preview mapping self-check failed");
        record("mapping_self_check", "ok", true);
    }

    private boolean closeCamera(String reason) {
        int closingRequestId = activeActivityCameraRequestId;
        if (activePreview == reverseCameraPreview || selectedTab == TAB_REVERSE_CAMERAS) {
            clearReversePanoramaStatus();
        }
        if (!requestedOpen && !cameraHandoffPending) {
            if (!shouldPreserveAutoPreviewAfterClose(reason)) {
                clearResumeAutoPreview();
            }
            IBinder current = helper;
            if (closingRequestId > 0 && current != null) {
                if (activityCameraCloseIdentity.requestId() != closingRequestId) {
                    captureActivityCameraCloseIdentity(closingRequestId, reason);
                }
                final int dormantRequestId = closingRequestId;
                record("close_requested", "reason", reason,
                        "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                        "request_id", dormantRequestId, "dormant", true);
                ipcExecutor.execute(() -> transactClose(
                        current, reason, dormantRequestId));
            }
            return false;
        }
        if (!shouldPreserveAutoPreviewAfterClose(reason)) {
            clearResumeAutoPreview();
        }
        cancelProductionPreviewFirstFrameWait();
        CameraProfileId closingProfile = activeActivityCameraProfile;
        DiagnosticMode closingDiagnostic = activeCameraDiagnosticMode();
        if (cameraHandoffPending) {
            if (cameraPreview != null) cameraPreview.removeCallbacks(finishCameraHandoff);
            if (debugPreview != null) debugPreview.removeCallbacks(finishCameraHandoff);
            cameraHandoffPending = false;
            pendingCameraViewpoint = -1;
            pendingCameraDebug = false;
            record("camera_preview_handoff", "state", "canceled", "reason", reason);
        }
        boolean wasOpen = requestedOpen;
        if (closingRequestId <= 0) closingRequestId = activeActivityCameraRequestId;
        final int exactClosingRequestId = closingRequestId;
        int closingConsumerGeneration = activeActivityConsumerGeneration;
        captureActivityCameraCloseIdentity(exactClosingRequestId, reason);
        requestedOpen = false;
        stopCalibrationCopies(true);
        clearPreview(reason);
        IBinder current = helper;
        record("close_requested", "reason", reason,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", exactClosingRequestId,
                "consumer_generation", closingConsumerGeneration,
                "input_generations", java.util.Arrays.toString(activeActivityInputGenerations));
        if (wasOpen && current != null) {
            ipcExecutor.execute(() -> transactClose(
                    current, reason, exactClosingRequestId));
        }
        activePreview = null;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = -1;
        activeActivityCameraRequestId = 0;
        activeActivityCameraProfile = null;
        activeActivityConsumerGeneration = 0;
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        activeActivityInputGenerations = new int[0];
        CameraHelperService.cameraPreviewStopped(this);
        publishCameraStatus(closingProfile, closingDiagnostic,
                "Camera closed", StatusTone.Warning, false);
        updateControls();
        return wasOpen && current != null;
    }

    private void captureActivityCameraCloseIdentity(int requestId, String reason) {
        closingActivityCameraRequestId = requestId;
        activityCameraCloseIdentity.capture(requestId, reason,
                activityCameraShellEpoch);
    }

    private void clearActivityCameraCloseIdentity() {
        closingActivityCameraRequestId = 0;
        activityCameraCloseIdentity.clear();
    }

    private boolean matchesActivityCameraClose(JSONObject event) {
        return event != null && (matchesUnopenedStockClose(event)
                || activityCameraCloseIdentity.matchesTerminal(
                event.optInt("request_id", 0), event.optString("reason"),
                event.optLong("camera_shell_epoch", 0),
                event.optLong("avm_shell_epoch", 0),
                "stock_avm_shell".equals(event.optString("renderer"))));
    }

    private boolean matchesUnopenedStockClose(JSONObject event) {
        return "camera_closed".equals(event.optString("kind"))
                && "helper".equals(event.optString("source"))
                && "stock_avm_shell".equals(event.optString("renderer"))
                && event.optBoolean("closed_without_open", false)
                && event.optString("error").isEmpty()
                && event.optLong("avm_shell_epoch", 0) == 0
                && activityCameraCloseIdentity.matchesUnopenedTerminal(
                        event.optInt("request_id", 0), event.optString("reason"),
                        event.optLong("camera_shell_epoch", 0));
    }

    private boolean matchesActivityCameraCloseFailure(JSONObject event) {
        return event != null && activityCameraCloseIdentity.matchesCloseFailure(
                event.optInt("request_id", 0), event.optString("reason"),
                event.optLong("camera_shell_epoch", 0),
                event.optLong("avm_shell_epoch", 0),
                "stock_avm_shell".equals(event.optString("renderer")));
    }

    private void failOwnedActivityCameraClose(int requestId, String failure) {
        String reason = activityCameraCloseIdentity.reason();
        if (requestId <= 0 || requestId != activityCameraCloseIdentity.requestId()
                || reason == null) return;
        if (CameraTransition.reasonEquals(
                reason, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)
                && activityColdResetInFlight && cameraTransition.matches(reason)) {
            failActivityColdReset(requestId, failure);
        } else if (CameraTransition.owns(reason) && cameraTransition.matches(reason)) {
            failCameraTransition(reason, failure);
        } else if ("activity_stopped".equals(reason) && activityClosePending) {
            finishActivityStoppedClose(requestId, failure);
        } else {
            record("activity_camera_close", "state", "failed", "reason", reason,
                    "request_id", requestId, "error", failure);
            activityClosePending = false;
            clearActivityCameraCloseIdentity();
            updateControls();
        }
    }

    private void stopActivityCameraManually(String reason) {
        closeCamera(reason);
    }

    private boolean closeCameraForTransition(String reason) {
        if (isAutoPreviewTab(selectedTab) && !shutdownRequested) {
            armResumeAutoPreview();
        }
        captureCameraTransitionTarget();
        boolean openWithoutHelper = requestedOpen && helper == null;
        String token = cameraTransition.begin(reason);
        boolean waiting = closeCamera(token);
        if (!waiting) {
            if (openWithoutHelper) {
                failCameraTransition(token, "helper unavailable");
                return true;
            } else {
                finishCameraTransition(token, "local");
            }
        }
        return waiting;
    }

    private void finishCameraTransition(String token, String source) {
        if (!cameraTransition.complete(token)) {
            record("camera_transition_ignored", "token", token, "source", source);
            return;
        }
        int targetTab = transitionTargetTab;
        boolean targetInputReady = transitionTargetInputReady;
        transitionTargetTab = -1;
        transitionTargetInputReady = false;
        activityClosePending = false;
        clearActivityCameraCloseIdentity();
        record("camera_transition_completed", "token", token, "source", source,
                "selected_tab", selectedTab);
        if (shouldRenewTransitionInputAfterClose(
                        targetTab == selectedTab, targetInputReady,
                        activityDestroyed, shutdownRequested, activityResumed)
                && CameraTransition.reasonEquals(token, "camera_tab_changed")) {
            renewSelectedPreviewInputForTabSwitch();
        }
        resumeSelectedCameraPreview();
        updateControls();
    }

    private void failCameraTransition(String token, String failure) {
        if (!resolveFailedCameraTransition(cameraTransition, token)) {
            record("camera_transition_ignored", "token", token, "source", "failure");
            return;
        }
        transitionTargetTab = -1;
        transitionTargetInputReady = false;
        activityClosePending = false;
        clearActivityCameraCloseIdentity();
        clearResumeAutoPreview();
        retireAutomaticPreviewInputs();
        record("camera_transition_close_failed", "token", token, "error", failure);
        updateControls();
    }

    @Override
    public void onReverseDewarpStats(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        if (!DiagnosticLogPolicy.extended()) return;
        if (!CameraDewarpStatsEvent.shouldRecord(
                activityResumed, requestedOpen,
                activePreview == reverseCameraPreview, activeActivityCameraRequestId)
                || stats.requestId != activeActivityCameraRequestId) return;
        int sourceOffset = -1;
        for (int i = 0; i < activeReversePreviewSourceIndexes.length; i++) {
            if (activeReversePreviewSourceIndexes[i] == cameraIndex) sourceOffset = i;
        }
        if (sourceOffset < 0
                || stats.contextGeneration != activeReversePreviewDirectGenerations[sourceOffset]) return;
        record("camera_dewarp_stats", CameraDewarpStatsEvent.reverse(
                stats.requestId, cameraIndex, stats));
    }

    private void renewSelectedPreviewInputForTabSwitch() {
        if (refreshAutomaticPreviewBuffersIfPending()) return;
        if (isProductionCalibrationSection() && calibrationPreview != null) {
            calibrationPreview.retireCameraInput();
            calibrationPreview.ensureCameraInput();
        } else if ((selectedTab == TAB_CAMERAS || selectedTab == TAB_PARKING_CAMERAS
                || selectedTab == TAB_REARVIEW_MIRROR)
                && cameraPreview != null) {
            cameraPreview.retireCameraInput();
            cameraPreview.ensureCameraInput();
        } else if (selectedTab == TAB_REVERSE_CAMERAS && reverseCameraPreview != null) {
            reverseCameraPreview.retirePreviewInputs();
            reverseCameraPreview.ensurePreviewInputs();
        }
    }

    private boolean prepareAutomaticResumeInputIfNeeded() {
        if (!resumeTabWarmup.consume()) return false;
        renewSelectedPreviewInputForTabSwitch();
        return true;
    }

    private boolean refreshAutomaticPreviewBuffersIfPending() {
        if (!activityCameraBufferRefreshPending) return false;
        activityCameraBufferRefreshPending = false;
        resumeTabWarmup.clear();
        retireAutomaticPreviewInputs();
        ensureAutomaticPreviewInputs();
        return true;
    }

    private boolean selectedPreviewInputReady() {
        if (isProductionCalibrationSection() && calibrationPreview != null) {
            return calibrationPreview.isCameraSurfaceReady();
        }
        if ((selectedTab == TAB_CAMERAS || selectedTab == TAB_PARKING_CAMERAS
                || selectedTab == TAB_REARVIEW_MIRROR)
                && cameraPreview != null) return cameraPreview.isCameraSurfaceReady();
        return selectedTab == TAB_REVERSE_CAMERAS && reverseCameraPreview != null
                && reverseCameraPreview.previewSurfacesReady();
    }

    private void captureCameraTransitionTarget() {
        transitionTargetTab = selectedTab;
        transitionTargetInputReady = selectedPreviewInputReady();
    }

    static boolean shouldIssueActivityStoppedClose(boolean transitionPending) {
        return !transitionPending;
    }

    static boolean shouldRenewSelectedInputAfterClose(
            boolean activityDestroyed, boolean shutdownRequested, boolean activityResumed) {
        return activityResumed && !activityDestroyed && !shutdownRequested;
    }

    static boolean shouldRenewTransitionInputAfterClose(
            boolean targetStillSelected, boolean targetInputReady,
            boolean activityDestroyed, boolean shutdownRequested, boolean activityResumed) {
        return targetStillSelected && targetInputReady
                && shouldRenewSelectedInputAfterClose(
                        activityDestroyed, shutdownRequested, activityResumed);
    }

    static final class ResumeTabWarmup {
        private boolean required;

        void stopped() {
            required = true;
        }

        boolean required() {
            return required;
        }

        boolean consume() {
            if (!required) return false;
            required = false;
            return true;
        }

        void clear() {
            required = false;
        }
    }

    static final class ReverseCalibrationFreshnessGate {
        private int requestId;
        private int cameraIndex;
        private boolean front;
        private boolean raw;
        private boolean requestFresh;
        private boolean mirrorFresh;

        void arm(
                int requestId, int cameraIndex, boolean front, boolean raw,
                boolean requestFresh) {
            if (requestId <= 0 || cameraIndex <= 0) {
                clear();
                return;
            }
            if (matches(requestId, cameraIndex, front, raw)) {
                this.requestFresh |= requestFresh;
                return;
            }
            this.requestId = requestId;
            this.cameraIndex = cameraIndex;
            this.front = front;
            this.raw = raw;
            this.requestFresh = requestFresh;
            mirrorFresh = false;
        }

        boolean markRequestFresh(
                int requestId, int cameraIndex, boolean front, boolean raw) {
            if (!matches(requestId, cameraIndex, front, raw)) return false;
            requestFresh = true;
            mirrorFresh = false;
            return true;
        }

        boolean allows(int requestId, int cameraIndex, boolean front, boolean raw) {
            return requestFresh && mirrorFresh
                    && matches(requestId, cameraIndex, front, raw);
        }

        private boolean matches(
                int requestId, int cameraIndex, boolean front, boolean raw) {
            return this.requestId == requestId
                    && this.cameraIndex == cameraIndex
                    && this.front == front
                    && this.raw == raw;
        }

        void clear() {
            requestId = 0;
            cameraIndex = 0;
            front = false;
            raw = false;
            requestFresh = false;
            mirrorFresh = false;
        }
    }

    static boolean resolveFailedCameraTransition(
            CameraTransition transition, String token) {
        return transition.complete(token);
    }

    private void resumeSelectedCameraPreview() {
        if (cameraTransition.pending() || !canResumeSelectedPreview()) return;
        refreshAutomaticPreviewBuffersIfPending();
        if (retryStockViewpoint >= 0) {
            int viewpoint = retryStockViewpoint;
            boolean debug = retryStockDebug;
            retryStockViewpoint = -1;
            retryStockDebug = false;
            openStockAvm(viewpoint, debug);
            if (requestedOpen || cameraHandoffPending) clearResumeAutoPreview();
            return;
        }
        if (isProductionCalibrationSection()) maybeOpenCalibrationCamera();
        else if (selectedTab == TAB_CAMERAS || selectedTab == TAB_PARKING_CAMERAS
                || selectedTab == TAB_REARVIEW_MIRROR) {
            maybeOpenProductionPreview();
        }
        else if (selectedTab == TAB_REVERSE_CAMERAS) maybeOpenReversePreview();
    }

    private void publishCameraEventStatus(
            JSONObject event, String text, StatusTone tone, boolean pending) {
        DiagnosticMode diagnostic = activeCameraDiagnosticMode();
        if (activePreview == null && activeActivityCameraProfile == null) {
            String renderer = event.optString("renderer");
            if ("direct_avm".equals(renderer)) diagnostic = DiagnosticMode.Direct;
            else if (renderer.startsWith("stock_avm")) diagnostic = DiagnosticMode.Avm;
        }
        publishCameraStatus(activeActivityCameraProfile, diagnostic, text, tone, pending);
    }

    private void publishReversePreviewBackgroundUnavailable() {
        publishReversePanoramaStatus(
                runtimeText(R.string.runtime_status_reverse_background_unavailable),
                StatusTone.Error, false);
    }

    private void publishReversePanoramaStatus(
            String text, StatusTone tone, boolean pending) {
        if (productionUi != null) {
            productionUi.setReversePanoramaStatus(
                    new StatusUiState(text, tone, true), pending);
        }
    }

    private void clearReversePanoramaStatus() {
        if (productionUi != null) {
            productionUi.setReversePanoramaStatus(
                    new StatusUiState("", StatusTone.Neutral, false), false);
        }
    }

    /** A failed optional background is not a failed direct-camera composition. */
    private boolean handleReversePreviewBackgroundEvent(JSONObject event) {
        if (!"reverse_preview_background".equals(event.optString("component"))) return false;
        String kind = event.optString("kind");
        String renderer = event.optString("renderer");
        int requestId = event.optInt("request_id", 0);
        if ("stock_avm_shell_died".equals(kind)
                && activityCameraCloseIdentity.matchesShellDeath(requestId,
                        event.optLong("camera_shell_epoch", 0),
                        event.optLong("avm_shell_epoch", 0))) {
            String error = event.optString("error", "stock background closed unexpectedly");
            failOwnedActivityCameraClose(requestId, error);
            return true;
        }
        if (("camera_closed".equals(kind) && matchesActivityCameraClose(event))
                || ("camera_closed".equals(kind)
                && !event.optString("error").isEmpty()
                && matchesActivityCameraCloseFailure(event))
                || ("camera_error".equals(kind)
                && isMatchingStockShellCloseError(renderer, event.optString("stage"),
                        activityCameraCloseIdentity.requestId(), requestId)
                && matchesActivityCameraCloseFailure(event))) return false;
        if (!isCurrentReverseBackgroundEvent(activePreview == reverseCameraPreview
                        && reverseCameraPreview != null, requestedOpen,
                activeActivityCameraRequestId, activityCameraShellEpoch,
                activityAvmShellEpoch, event)) {
            recordIgnoredActivityCameraEvent(kind, event);
            return true;
        }
        if ("camera_error".equals(kind) || "camera_closed".equals(kind)
                || "stock_avm_shell_died".equals(kind)
                || "stock_avm_input_detached".equals(kind)) {
            reversePreviewBackgroundFailureRequestId = requestId;
            reverseCameraPreview.markPreviewBackgroundUnavailable(requestId);
            publishReversePreviewBackgroundUnavailable();
        } else if ("camera_opened".equals(kind)) {
            rememberActivityAvmShellEpoch(event, true);
            if (reversePreviewBackgroundFailureRequestId != requestId) {
                clearReversePanoramaStatus();
            }
        }
        return true;
    }

    static boolean isCurrentReverseBackgroundEvent(
            boolean reversePreview, boolean requested, int requestId,
            long cameraEpoch, long avmEpoch, JSONObject event) {
        String source = event.optString("source");
        return reversePreview && requested && requestId > 0
                && requestId == event.optInt("request_id", 0)
                && ("helper".equals(source) || "stock_avm_shell".equals(source))
                && isMatchingActivityCameraShellEpoch(cameraEpoch,
                        event.optLong("camera_shell_epoch", 0))
                && (event.optLong("avm_shell_epoch", 0) <= 0
                        || isMatchingActivityAvmShellEpoch(avmEpoch,
                                event.optLong("avm_shell_epoch", 0)));
    }

    /** Stage callbacks are advisory; accept them only while the current AVM request owns Debug. */
    private boolean isCurrentDiagnosticStageEvent(JSONObject event) {
        if (event == null || !requestedOpen || activePreview != debugPreview) return false;
        String source = event.optString("source");
        if (!source.isEmpty() && !"helper".equals(source)
                && !"stock_avm_shell".equals(source)) return false;
        String renderer = event.optString("renderer");
        if (!renderer.isEmpty() && !renderer.startsWith("stock_avm")) return false;
        return isDiagnosticStageIdentityMatch(
                activeActivityCameraRequestId, event.optInt("request_id", 0),
                activityCameraShellEpoch, event.optLong("camera_shell_epoch", 0));
    }

    static boolean isDiagnosticStageIdentityMatch(
            int activeRequestId, int eventRequestId, long activeEpoch, long eventEpoch) {
        // A new timestamp or a shared helper epoch cannot identify the request that emitted a stage.
        return activeRequestId > 0 && eventRequestId == activeRequestId
                && (eventEpoch == 0L || eventEpoch > 0L && eventEpoch == activeEpoch);
    }

    private boolean isMatchingActivityCameraRenderer(JSONObject event) {
        if (event == null) return false;
        String renderer = event.optString("renderer");
        if (renderer.isEmpty()) return true; // Persistent camera consumers omit renderer.
        if (activePreview == debugPreview || pendingCameraDebug) {
            return renderer.startsWith("stock_avm");
        }
        if (activePreview == directCameraPreview || activePreview == calibrationPreview) {
            return renderer.startsWith("direct");
        }
        if (activePreview == cameraPreview) {
            // Automatic blind-spot/parking preview may be served by the direct renderer or the
            // stock AVM shell, depending on the selected source.  Both belong to this host.
            return renderer.startsWith("direct") || renderer.startsWith("stock_avm");
        }
        return true;
    }

    static boolean isTerminalDiagnosticStage(String stage) {
        if (stage == null) return false;
        String value = stage.trim().toLowerCase(Locale.US);
        return value.equals("opened") || value.equals("closed") || value.equals("completed")
                || value.endsWith("_opened") || value.endsWith("_closed")
                || value.endsWith("_completed") || value.equals("error")
                || value.endsWith("_error") || value.endsWith("_failed");
    }

    private DiagnosticMode activeCameraDiagnosticMode() {
        if (activePreview != null && activePreview == directCameraPreview) {
            return DiagnosticMode.Direct;
        }
        if ((activePreview != null && activePreview == debugPreview)
                || (cameraHandoffPending && pendingCameraDebug)) return DiagnosticMode.Avm;
        return null;
    }

    private void publishCameraStatus(
            CameraProfileId profile, DiagnosticMode diagnostic,
            String text, StatusTone tone, boolean pending) {
        String localizedText = localizedCameraStatus(text);
        if (diagnostic != null) {
            if (productionUi != null) {
                productionUi.setDiagnosticStatus(diagnostic == DiagnosticMode.Direct,
                        new StatusUiState(localizedText, tone, true), pending);
            }
        } else {
            if (productionUi != null && profile != null) {
                productionUi.setProfileStatus(profile,
                        new StatusUiState(localizedText, tone, true), pending);
            }
            record("camera_status", "profile", profile == null ? "unknown" : profile.toString(),
                    "text", text, "tone", tone.name(), "pending", pending);
        }
    }

    private String runtimeLanguage() {
        return preferences == null ? AppLanguage.ENGLISH : AppLanguage.read(preferences);
    }

    /** Resolves app-owned runtime copy without changing the base Activity locale. */
    private String runtimeText(int id, Object... args) {
        Context localized = AppLanguage.localizedContext(this, runtimeLanguage());
        return args.length == 0 ? localized.getString(id) : localized.getString(id, args);
    }

    /** Lifecycle status text follows the selected app language while logs retain raw events. */
    private String localizedCameraStatus(String text) {
        if (text == null) return "";
        if (text.startsWith("First frame ready")) {
            return runtimeText(R.string.runtime_status_first_frame)
                    + text.substring("First frame ready".length());
        }
        if (text.startsWith("Перший кадр готовий")) {
            return runtimeText(R.string.runtime_status_first_frame)
                    + text.substring("Перший кадр готовий".length());
        }
        if (text.startsWith("Очікування перших кадрів")) {
            return runtimeText(R.string.runtime_status_waiting_frames);
        }
        if (text.startsWith("Waiting for first frames")) {
            return runtimeText(R.string.runtime_status_waiting_frames);
        }
        if (text.startsWith("Відкриття камер заднього ходу")) {
            String value = runtimeText(R.string.runtime_status_opening,
                    runtimeText(R.string.runtime_status_reverse_cameras));
            return value + text.substring("Відкриття камер заднього ходу".length());
        }
        if (text.startsWith("Камера закрита")) {
            return runtimeText(R.string.runtime_status_camera_closed)
                    + text.substring("Камера закрита".length());
        }
        if (text.startsWith("Camera closed")) {
            return runtimeText(R.string.runtime_status_camera_closed)
                    + text.substring("Camera closed".length());
        }
        if (text.startsWith("Помилка камери:")) {
            return runtimeText(R.string.runtime_status_camera_error,
                    text.substring("Помилка камери:".length()).trim());
        }
        if (text.startsWith("Camera error:")) {
            return runtimeText(R.string.runtime_status_camera_error,
                    text.substring("Camera error:".length()).trim());
        }
        if (text.startsWith("Open failed: ")) {
            return runtimeText(R.string.runtime_status_open_failed,
                    text.substring("Open failed: ".length()));
        }
        if (text.startsWith("Stock AVM failed: ")) {
            return runtimeText(R.string.runtime_status_stock_avm_failed,
                    text.substring("Stock AVM failed: ".length()));
        }
        if (text.startsWith("AVM Surface invalid; retrying once...")) {
            return runtimeText(R.string.runtime_status_avm_surface_retry);
        }
        if (text.startsWith("Opening reverse cameras")) {
            return runtimeText(R.string.runtime_status_opening,
                    runtimeText(R.string.runtime_status_reverse_cameras))
                    + text.substring("Opening reverse cameras".length());
        }
        if (text.startsWith("Opening ")) {
            return runtimeText(R.string.runtime_status_opening,
                    text.substring("Opening ".length()));
        }
        if (text.startsWith("Preparing ")) {
            return runtimeText(R.string.runtime_status_preparing,
                    text.substring("Preparing ".length()));
        }
        if (text.startsWith("Switching ")) {
            return runtimeText(R.string.runtime_status_switching,
                    text.substring("Switching ".length()));
        }
        if (text.startsWith("Showing ")) {
            return runtimeText(R.string.runtime_status_showing,
                    text.substring("Showing ".length()));
        }
        if (text.startsWith("Stock AVM: ")) {
            return runtimeText(R.string.runtime_status_stock_avm,
                    text.substring("Stock AVM: ".length()));
        }
        if (text.equals("AVM camera ready")) {
            return runtimeText(R.string.runtime_status_avm_ready);
        }
        if (text.startsWith("Camera discovery failed: ")) {
            return runtimeText(R.string.runtime_status_camera_discovery_failed,
                    text.substring("Camera discovery failed: ".length()));
        }
        if (text.startsWith("Detected ")) {
            return runtimeText(R.string.runtime_status_detected,
                    text.substring("Detected ".length()));
        }
        if (text.equals("Помилка корекції камери; відкрийте preview повторно")) {
            return runtimeText(R.string.runtime_status_dewarp_error);
        }
        if (text.equals("Camera helper недоступний")) {
            return runtimeText(R.string.runtime_status_helper_unavailable);
        }
        return text;
    }

    private void enqueueHelperCallbackRegistration(IBinder target) {
        if (target == null) return;
        HelperCallbackRegistration.Operation<IBinder> operation =
                helperCallbackRegistration.queue(
                        target, nextHelperCallbackRegistrationGeneration());
        if (operation != null) ipcExecutor.execute(() -> registerCallback(operation));
    }

    private void registerCallback(HelperCallbackRegistration.Operation<IBinder> operation) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeStrongBinder(callback);
            data.writeLong(operation.generation);
            requireTransaction(operation.connection,
                    CameraHelperMain.TX_REGISTER_CALLBACK, data, reply);
            String result = reply.readString();
            boolean accepted = "callback_registered".equals(
                    new JSONObject(result).optString("kind"));
            boolean active = accepted
                    && helperCallbackRegistration.registered(operation);
            if (!accepted) helperCallbackRegistration.failed(operation);
            record("ipc_reply", "operation", "register_callback", "reply", result,
                    "registration_generation", operation.generation,
                    "accepted", accepted, "active", active);
            if (active) {
                runOnUiThread(() -> {
                    pushGuardConfigFromPreferences();
                    CameraHelperService.reportAvasStatus(CameraProbeActivity.this);
                    advanceStartupAuthorizationFlow();
                });
            }
        } catch (Throwable error) {
            record("ipc_error", "operation", "register_callback", "error", error.toString());
            if (helperCallbackRegistration.failed(operation)) {
                mainHandler.post(() -> {
                    if (activityDestroyed) return;
                    updateControls();
                    advanceStartupAuthorizationFlow();
                });
            }
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void detachHelperCallback() {
        HelperCallbackRegistration.Operation<IBinder> operation =
                helperCallbackRegistration.stop();
        if (operation != null) {
            ipcExecutor.execute(() -> transactDetachCallback(operation, callback));
        }
    }

    private void transactDetachCallback(
            HelperCallbackRegistration.Operation<IBinder> operation,
            IBinder expectedCallback) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeStrongBinder(expectedCallback);
            data.writeLong(operation.generation);
            requireTransaction(operation.connection,
                    CameraHelperMain.TX_DETACH_CALLBACK, data, reply);
            helperCallbackRegistration.detached(operation);
            record("ipc_reply", "operation", "detach_callback", "reply", reply.readString(),
                    "registration_generation", operation.generation);
        } catch (Throwable error) {
            record("ipc_error", "operation", "detach_callback", "error", error.toString());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static synchronized long nextHelperCallbackRegistrationGeneration() {
        if (helperCallbackRegistrationSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("helper callback generation exhausted");
        }
        return ++helperCallbackRegistrationSequence;
    }

    private void transactGuardConfig(
            IBinder current, boolean enabled, float outward, float center, int delayMs,
            int maxSpeedKph) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(enabled ? 1 : 0);
            data.writeFloat(outward);
            data.writeFloat(center);
            data.writeInt(delayMs);
            data.writeInt(maxSpeedKph);
            requireTransaction(current, CameraHelperMain.TX_SET_GUARD, data, reply);
            record("ipc_reply", "operation", "set_guard", "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "set_guard", "error", error.toString());
            runOnUiThread(() -> publishGuardStatus("Guard IPC error", StatusTone.Error));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactOpen(
            IBinder current, Surface surface, int index, String viewName, int requestId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(index);
            data.writeString(viewName);
            data.writeInt(requestId);
            requireTransaction(current, CameraHelperMain.TX_OPEN, data, reply);
            record("ipc_reply", "operation", "open", "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "open", "request_id", requestId,
                    "error", error.toString());
            runOnUiThread(() -> {
                if (activeActivityCameraRequestId != requestId) return;
                requestedOpen = false;
                activeActivityCameraRequestId = 0;
                activeActivityConsumerGeneration = 0;
                activeActivityCameraOpened = false;
                activeActivityCameraFresh = false;
                activeActivityInputGenerations = new int[0];
                String message = "Open failed: " + error.getClass().getSimpleName();
                publishCameraStatus(activeActivityCameraProfile, activeCameraDiagnosticMode(),
                        message, StatusTone.Error, false);
                activeActivityCameraProfile = null;
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactOpenDirect(
            IBinder current, Surface surface, String cameraTag, int index, int requestId,
            boolean exclusive) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            CameraGpuOutput.writeSurface(data, surface);
            data.writeString(cameraTag);
            data.writeInt(index);
            data.writeInt(requestId);
            data.writeInt(exclusive ? 1 : 0);
            requireTransaction(current, CameraHelperMain.TX_OPEN_DIRECT, data, reply);
            record("ipc_reply", "operation", "open_direct",
                    "camera_tag", cameraTag, "preview_index", index,
                    "request_id", requestId, "exclusive", exclusive,
                    "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "open_direct",
                    "camera_tag", cameraTag, "preview_index", index,
                    "request_id", requestId,
                    "error", error.toString());
            runOnUiThread(() -> {
                if (activeActivityCameraRequestId != requestId) return;
                requestedOpen = false;
                activeActivityCameraRequestId = 0;
                activeActivityConsumerGeneration = 0;
                activeActivityCameraOpened = false;
                activeActivityCameraFresh = false;
                activeActivityInputGenerations = new int[0];
                CameraHelperService.cameraPreviewStopped(this);
                String message = "Open failed: " + error.getClass().getSimpleName();
                publishCameraStatus(activeActivityCameraProfile, activeCameraDiagnosticMode(),
                        message, StatusTone.Error, false);
                activeActivityCameraProfile = null;
                stopCalibrationCopies(true);
                clearPreview("direct_open_failed");
                activePreview = null;
                activePreviewCover = null;
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactOpenReversePreview(
            IBinder current, ReverseCameraCompositionView.SurfaceBundle bundle, int requestId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(requestId);
            data.writeInt(bundle.panoramaSurface != null ? 1 : 0);
            if (bundle.panoramaSurface != null) CameraGpuOutput.writeSurface(data, bundle.panoramaSurface);
            data.writeInt(bundle.sourceIndexes.length);
            for (int i = 0; i < bundle.sourceIndexes.length; i++) {
                data.writeInt(bundle.sourceIndexes[i]);
                CameraGpuOutput.writeSurface(data, bundle.surfaces[i]);
            }
            requireTransaction(current, CameraHelperMain.TX_OPEN_REVERSE_PREVIEW_TARGETED, data, reply);
            String result = reply.readString();
            record("ipc_reply", "operation", "open_reverse_preview",
                    "request_id", requestId, "reply", result);
            JSONObject json = new JSONObject(result);
            String acceptedKind = bundle.panoramaSurface != null
                    ? "reverse_preview_shell_open_queued" : "camera_opened";
            if (!acceptedKind.equals(json.optString("kind"))) {
                throw new IllegalStateException(json.optString("error", json.optString("kind")));
            }
        } catch (Throwable error) {
            record("ipc_error", "operation", "open_reverse_preview",
                    "request_id", requestId, "error", error.toString());
            runOnUiThread(() -> {
                if (!failClosedReversePreviewRequest(requestId)) return;
                CameraHelperService.cameraPreviewStopped(this);
                clearReversePanoramaStatus();
                publishCameraStatus(activeActivityCameraProfile, null,
                        "Open failed: " + error.getClass().getSimpleName(),
                        StatusTone.Error, false);
                activeActivityCameraProfile = null;
                if (activePreview == reverseCameraPreview) {
                    reverseCameraPreview.clearFrames();
                    activePreview = null;
                }
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private boolean failClosedReversePreviewRequest(int requestId) {
        if (activeActivityCameraRequestId != requestId) return false;
        clearResumeAutoPreview();
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
        requestedOpen = false;
        activeActivityCameraRequestId = 0;
        activeActivityConsumerGeneration = 0;
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        activeActivityInputGenerations = new int[0];
        return true;
    }

    private void transactOpenStockAvm(
            IBinder current, Surface surface, int viewpoint, boolean horizontal,
            boolean stockDewarp, int requestId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            CameraGpuOutput.writeSurface(data, surface);
            data.writeInt(viewpoint);
            data.writeInt(horizontal ? 1 : 0);
            data.writeInt(stockDewarp ? 1 : 0);
            data.writeInt(requestId);
            requireTransaction(current, CameraHelperMain.TX_OPEN_STOCK_AVM, data, reply);
            record("ipc_reply", "operation", "open_stock_avm",
                    "viewpoint", viewpoint, "orientation",
                    horizontal ? "horizontal" : "vertical",
                    "dewarp", stockDewarp, "request_id", requestId,
                    "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "open_stock_avm",
                    "viewpoint", viewpoint, "request_id", requestId,
                    "error", error.toString());
            runOnUiThread(() -> {
                if (activeActivityCameraRequestId != requestId) return;
                requestedOpen = false;
                activeActivityCameraRequestId = 0;
                activeActivityConsumerGeneration = 0;
                activeActivityCameraOpened = false;
                activeActivityCameraFresh = false;
                activeActivityInputGenerations = new int[0];
                CameraHelperService.cameraPreviewStopped(this);
                publishCameraStatus(activeActivityCameraProfile, activeCameraDiagnosticMode(),
                        "Stock AVM failed: " + error.getClass().getSimpleName(),
                        StatusTone.Error, false);
                activeActivityCameraProfile = null;
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static boolean shouldUseStockDewarp(boolean debug, boolean enabled) {
        return debug && enabled;
    }

    private void transactClose(IBinder current, String reason, int requestId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeString(reason);
            data.writeInt(requestId);
            requireTransaction(current, CameraHelperMain.TX_CLOSE, data, reply);
            String result = reply.readString();
            record("ipc_reply", "operation", "close",
                    "request_id", requestId, "reply", result);
            runOnUiThread(() -> handleCloseReply(reason, requestId, result, null));
        } catch (Throwable error) {
            record("ipc_error", "operation", "close",
                    "request_id", requestId, "error", error.toString());
            runOnUiThread(() -> handleCloseReply(reason, requestId, null, error));
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void handleCloseReply(
            String reason, int requestId, String result, Throwable error) {
        if (CameraTransition.reasonEquals(
                reason, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)) {
            if (!activityCameraCloseIdentity.matchesOperation(requestId, reason)
                    || !cameraTransition.matches(reason) || !activityColdResetInFlight) {
                clearStaleColdResetInFlight(reason);
                record("activity_camera_cold_reset", "state", "ignored",
                        "request_id", requestId, "reason", "stale_reply");
                return;
            }
            if (error != null) {
                failActivityColdReset(requestId, error.toString());
                return;
            }
            if (isDeferredColdResetReply(result)) {
                activityColdResetInFlight = false;
                cameraTransition.cancel();
                activityClosePending = false;
                clearActivityCameraCloseIdentity();
                record("activity_camera_cold_reset", "state", "deferred_reverse",
                        "request_id", requestId);
                return;
            }
            // The producer teardown has already happened, but the stock AVM
            // shell may still own its input Surface.  A queued reply is only
            // an acknowledgement of the request; retirement/reopen must wait
            // for the matching shell camera_closed event below.
            if (isQueuedColdResetReply(result)) {
                int expectedShellRequestId = expectedColdResetShellRequestId(
                        closingActivityCameraRequestId, automaticPreviewIntentRequestId);
                if (expectedShellRequestId <= 0) {
                    failActivityColdReset(requestId, "stock shell request id unavailable");
                    return;
                }
                closingActivityCameraRequestId = expectedShellRequestId;
                activityCameraCloseIdentity.updateRequestId(expectedShellRequestId);
                record("activity_camera_cold_reset", "state", "waiting_shell_close",
                        "request_id", requestId,
                        "shell_request_id", expectedShellRequestId);
                return;
            }
            if (!isSuccessfulColdResetReply(result)) {
                failActivityColdReset(requestId, result);
                return;
            }
            completeActivityColdReset(reason, requestId, "ipc_reply");
            return;
        }
        if ("activity_stopped".equals(reason)) {
            if (!activityClosePending
                    || !activityCameraCloseIdentity.matchesOperation(requestId, reason)) {
                record("activity_camera_close", "state", "ignored",
                        "reason", "stale_reply", "request_id", requestId,
                        "expected_request_id", closingActivityCameraRequestId);
                return;
            }
            if (error != null) {
                finishActivityStoppedClose(requestId, error.toString());
            } else if (isStockShellCloseQueued(result)) {
                activityClosePending = true;
                record("activity_camera_close", "state", "waiting_shell_close",
                        "reason", reason, "request_id", requestId);
            } else if (isSuccessfulCameraCloseReply(result)) {
                finishActivityStoppedClose(requestId, null);
            } else {
                finishActivityStoppedClose(requestId,
                        result == null ? "invalid close reply" : result);
            }
            return;
        }
        if (CameraTransition.owns(reason)) {
            if (!activityCameraCloseIdentity.matchesOperation(requestId, reason)) {
                record("camera_transition_ignored", "token", reason,
                        "source", "stale_close_reply", "request_id", requestId);
                return;
            }
            boolean queued = error == null && isStockShellCloseQueued(result);
            boolean successful = error == null && isSuccessfulCameraCloseReply(result);
            if (queued) return;
            if (shouldCompleteCameraTransitionClose(error != null, queued, successful)) {
                finishCameraTransition(reason, "ipc_reply");
            } else {
                failCameraTransition(reason,
                        error == null ? String.valueOf(result) : error.toString());
            }
        }
    }

    static boolean shouldCompleteCameraTransitionClose(
            boolean transactionFailed, boolean shellCloseQueued,
            boolean closeSucceeded) {
        return !transactionFailed && !shellCloseQueued && closeSucceeded;
    }

    private void completeActivityColdReset(String reason, int requestId, String source) {
        if (!activityColdResetInFlight || !cameraTransition.matches(reason)
                || !cameraTransition.complete(reason)) {
            clearStaleColdResetInFlight(reason);
            record("activity_camera_cold_reset", "state", "ignored",
                    "request_id", requestId, "reason", "stale_completion");
            return;
        }
        activityColdResetInFlight = false;
        activityColdResetRequired = false;
        activityColdResetFailed = false;
        activityClosePending = false;
        clearActivityCameraCloseIdentity();
        retireAutomaticPreviewInputs();
        ensureAutomaticPreviewInputs();
        record("activity_camera_cold_reset", "state", "completed",
                "request_id", requestId, "source", source);
        resumeSelectedCameraPreview();
        updateControls();
    }

    private void failActivityColdReset(int requestId, String failure) {
        activityColdResetInFlight = false;
        activityColdResetFailed = true;
        activityColdResetRequired = false;
        cameraTransition.cancel();
        activityClosePending = false;
        clearActivityCameraCloseIdentity();
        record("activity_camera_cold_reset", "state", "failed",
                "request_id", requestId, "error", failure);
        updateControls();
    }

    private void clearStaleColdResetInFlight(String reason) {
        // A canceled request has no transition left to own its late reply.
        // Never clear a newer pending transition just because an older token
        // arrived on the serialized executor.
        if (activityColdResetInFlight && !cameraTransition.pending()) {
            activityColdResetInFlight = false;
            if (activityCameraCloseIdentity.matchesReason(reason)) {
                activityClosePending = false;
                clearActivityCameraCloseIdentity();
            }
            if (shouldRetainColdResetAfterCancel(
                    activityResumed, shutdownRequested)) {
                activityColdResetRequired = true;
            }
        }
    }

    private void finishActivityStoppedClose(int requestId, String failure) {
        if (!shouldFinishActivityStoppedClose(
                activityClosePending, closingActivityCameraRequestId, requestId)) {
            record("activity_camera_close", "state", "ignored",
                    "reason", "stale_or_duplicate", "request_id", requestId,
                    "expected_request_id", closingActivityCameraRequestId);
            return;
        }
        activityClosePending = false;
        if (failure != null && !failure.trim().isEmpty()) {
            clearResumeAutoPreview();
            resumeTabWarmup.clear();
            retireAutomaticPreviewInputs();
            record("activity_camera_close", "state", "failed",
                    "reason", "activity_stopped", "request_id", requestId,
                    "error", failure);
            clearActivityCameraCloseIdentity();
            updateControls();
            return;
        }
        clearActivityCameraCloseIdentity();
        if (!shouldRenewSelectedInputAfterClose(
                activityDestroyed, shutdownRequested, activityResumed)) return;
        resumeTabWarmup.clear();
        renewSelectedPreviewInputForTabSwitch();
        if (activityResumed && hasAutoPreviewIntent()) resumeSelectedCameraPreview();
    }

    static boolean shouldFinishActivityStoppedClose(
            boolean pending, int expectedRequestId, int eventRequestId) {
        return pending && expectedRequestId > 0 && eventRequestId == expectedRequestId;
    }

    private static boolean isStockShellCloseQueued(String result) {
        if (result == null) return false;
        try {
            JSONObject json = new JSONObject(result);
            return isQueuedCloseResult(
                    json.optString("kind"), json.optString("error", ""));
        } catch (Throwable error) {
            return false;
        }
    }

    private static boolean isSuccessfulCameraCloseReply(String result) {
        if (result == null) return false;
        try {
            JSONObject json = new JSONObject(result);
            return isSuccessfulCameraCloseResult(
                    json.optString("kind"), json.optString("error", ""));
        } catch (Throwable error) {
            return false;
        }
    }

    static boolean isSuccessfulColdResetReply(String result) {
        if (result == null || result.trim().isEmpty()) return false;
        try {
            JSONObject json = new JSONObject(result);
            return isSuccessfulColdResetResult(
                    json.optString("kind"), json.optString("error", ""));
        } catch (Throwable error) {
            return false;
        }
    }

    static boolean isSuccessfulColdResetResult(String kind, String error) {
        return isSuccessfulCameraCloseResult(kind, error);
    }

    static boolean isQueuedColdResetResult(String kind, String error) {
        return isQueuedCloseResult(kind, error);
    }

    static boolean isDeferredColdResetResult(String kind, String error) {
        return CameraHelperMain.COLD_RESET_DEFERRED_REVERSE.equals(kind)
                && error != null && error.isEmpty();
    }

    private static boolean isDeferredColdResetReply(String result) {
        if (result == null || result.trim().isEmpty()) return false;
        try {
            JSONObject json = new JSONObject(result);
            return isDeferredColdResetResult(
                    json.optString("kind"), json.optString("error", ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isQueuedColdResetReply(String result) {
        if (result == null || result.trim().isEmpty()) return false;
        try {
            JSONObject json = new JSONObject(result);
            return isQueuedColdResetResult(
                    json.optString("kind"), json.optString("error", ""));
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean isSuccessfulShellCloseEvent(
            String renderer, String kind, String error) {
        return "stock_avm_shell".equals(renderer)
                && "camera_closed".equals(kind)
                && error != null && error.isEmpty();
    }

    static boolean isMatchingPendingActivityShellClose(
            boolean closePending, int expectedRequestId,
            int eventRequestId, String renderer) {
        return closePending && expectedRequestId > 0
                && expectedRequestId == eventRequestId
                && "stock_avm_shell".equals(renderer);
    }

    static boolean isMatchingColdResetShellCallback(
            String token, String reason, String renderer, String kind, String error,
            int expectedRequestId, int eventRequestId,
            long currentCameraShellEpoch, long eventCameraShellEpoch) {
        return CameraTransition.reasonEquals(
                token, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)
                && token.equals(reason)
                && isSuccessfulShellCloseEvent(renderer, kind, error)
                && expectedRequestId > 0 && expectedRequestId == eventRequestId
                && isMatchingActivityCameraShellEpochStrict(
                currentCameraShellEpoch, eventCameraShellEpoch);
    }

    static boolean isMatchingPendingActivityTransitionShellClose(
            String token, String reason, String renderer, String kind, String error,
            int expectedRequestId, int eventRequestId,
            long currentCameraShellEpoch, long eventCameraShellEpoch,
            long currentAvmEpoch, long eventAvmEpoch) {
        return token != null && token.equals(reason)
                && CameraTransition.owns(token)
                && isSuccessfulShellCloseEvent(renderer, kind, error)
                && expectedRequestId > 0 && expectedRequestId == eventRequestId
                && isMatchingActivityCameraShellEpochStrict(
                currentCameraShellEpoch, eventCameraShellEpoch)
                && isMatchingActivityAvmShellEpoch(currentAvmEpoch, eventAvmEpoch);
    }

    static boolean shouldRetainColdResetAfterCancel(
            boolean activityResumed, boolean shutdownRequested) {
        return !activityResumed && !shutdownRequested;
    }

    static int expectedColdResetShellRequestId(
            int closingRequestId, int automaticIntentRequestId) {
        return closingRequestId > 0 ? closingRequestId
                : automaticIntentRequestId > 0 ? automaticIntentRequestId : 0;
    }

    private static boolean isSuccessfulCameraCloseResult(String kind, String error) {
        return ("camera_closed".equals(kind) || "already_closed".equals(kind))
                && error != null && error.isEmpty();
    }

    private static boolean isQueuedCloseResult(String kind, String error) {
        return "stock_avm_shell_close_queued".equals(kind)
                && error != null && error.isEmpty();
    }

    private void maybeStartForegroundAdbAuthorization() {
        if (!shouldStartForegroundAdbAuthorization(cameraPermissionPending,
                backgroundStartSettingsPending(), hasWindowFocus(),
                foregroundAdbAuthorizationReady(),
                adbAuthPending, adbAuthorizationRequested
                        || startupOverlayPermissionFlow.foregroundAuthorizationStarted())) {
            cancelPendingForegroundAdbAuthorization();
            return;
        }
        if (adbAuthorizationStartScheduled) return;
        adbAuthorizationStartScheduled = mainHandler.postDelayed(
                startForegroundAdbAuthorization, ADB_AUTH_UI_SETTLE_MS);
        record("adb_authorization_foreground_scheduled",
                "delay_ms", ADB_AUTH_UI_SETTLE_MS);
    }

    private void cancelPendingForegroundAdbAuthorization() {
        adbAuthorizationStartScheduled = false;
        mainHandler.removeCallbacks(startForegroundAdbAuthorization);
    }

    private void advanceStartupAuthorizationFlow() {
        if (bluetoothPermissionInFlight) return;
        if (startupOverlayPermissionFlow.overlaySettingsInFlight()) return;
        if (settingsTransferInProgress || settingsReloadPending) return;
        if (productionUi != null && productionUi.getState().getDialog() != null) {
            cancelPendingBackgroundStartSettings();
            cancelPendingForegroundAdbAuthorization();
            return;
        }
        if (pendingManualAdbEvent != null) {
            if (foregroundAdbAuthorizationReady() && hasWindowFocus()
                    && !cameraPermissionPending && !backgroundStartSettingsActive) {
                requestAdbAuthorization(pendingManualAdbEvent, pendingManualAdbOperation, false);
            }
            return;
        }
        if (shouldOpenBackgroundStartSettings(
                GuardRecovery.isAutoStartEnabled(this), cameraPermissionPending,
                hasWindowFocus(), backgroundStartSettingsRequired,
                backgroundStartSettingsActive, adbAuthPending)) {
            cancelPendingForegroundAdbAuthorization();
            if (!backgroundStartSettingsStartScheduled) {
                backgroundStartSettingsStartScheduled = mainHandler.postDelayed(
                        startBackgroundStartSettings, BACKGROUND_START_UI_SETTLE_MS);
                record("background_start_settings_scheduled",
                        "delay_ms", BACKGROUND_START_UI_SETTLE_MS);
            }
            updateControls();
            return;
        }
        cancelPendingBackgroundStartSettings();
        maybeStartForegroundAdbAuthorization();
        if (!runtimePermissionWindowAvailable()) return;
        if (weatherLocationPermissionPending) {
            if (!weatherLocationPermissionInFlight && !weatherLocationPermissionStartScheduled
                    && activityResumed && hasWindowFocus() && !cameraPermissionPending) {
                weatherLocationPermissionStartScheduled = mainHandler.postDelayed(
                        startPendingWeatherLocationPermission,
                        WEATHER_PERMISSION_UI_SETTLE_MS);
                record("weather_location_permission_scheduled",
                        "delay_ms", WEATHER_PERMISSION_UI_SETTLE_MS);
            }
            cancelPendingForegroundAdbAuthorization();
            updateControls();
            return;
        }
        if (microphonePermissionPending) {
            requestMicrophonePermission();
            if (microphonePermissionInFlight) return;
        }
        if (!pendingBluetoothProfiles.isEmpty()) {
            requestBluetoothPermission();
            return;
        }
        if (isAutoPreviewTab(selectedTab) || selectedTab == TAB_CAMERA_DEBUG) {
            requestCameraPermissionIfNeeded();
            if (cameraPermissionPending) return;
        }
        boolean overlayPermissionGranted = android.provider.Settings.canDrawOverlays(this);
        if (startupOverlayPermissionFlow.beginStartupOverlayPermissionRequest(
                overlayPermissionGranted, false)) {
            launchOverlayPermissionSettings(true);
            return;
        }
        maybeShowLegacyImportOffer();
        showCachedUpdateIfAvailable();
    }

    private void maybeShowLegacyImportOffer() {
        if (!activityResumed || !hasWindowFocus() || cameraPermissionPending
                || bluetoothPermissionInFlight || !pendingBluetoothProfiles.isEmpty()
                || microphonePermissionInFlight || microphonePermissionPending || pendingManualAdbEvent != null
                || backgroundStartSettingsPending() || weatherLocationPermissionPending
                || weatherLocationPermissionInFlight || weatherLocationPermissionStartScheduled
                || adbAuthPending || adbAuthorizationStartScheduled
                || startupOverlayPermissionFlow.foregroundAuthorizationInFlight()
                || !startupOverlayPermissionFlow.canPresentStartupDialogs(
                        android.provider.Settings.canDrawOverlays(this))
                || settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress
                || updateDownloadInFlight
                || productionUi != null && productionUi.getState().getDialog() != null
                || settingsTransferDialog != null || legacyImportOfferDialog != null
                || shutdownRequested || activityDestroyed || isFinishing()
                || LocalAdbClient.readAccessState(this).status
                        != LocalAdbClient.AccessState.Status.OK) return;
        boolean compatible = LegacySettingsImporter.hasCompatibleLegacy(this);
        boolean handled = preferences.getBoolean(
                LegacySettingsImporter.PREF_IMPORT_OFFER_HANDLED, false);
        boolean complete = preferences.getBoolean(
                LegacySettingsImporter.PREF_HANDOVER_COMPLETE, false);
        if (!LegacySettingsImporter.shouldOfferImport(compatible, handled, complete)) return;
        legacyImportOfferDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_legacy_offer_title))
                .setMessage(runtimeText(R.string.runtime_legacy_offer_message))
                .setCancelable(false)
                .setNegativeButton(runtimeText(R.string.runtime_cancel), (dialog, which) -> {
                    markLegacyImportOfferHandled("cancel");
                    legacyImportOfferDialog = null;
                })
                .setPositiveButton(runtimeText(R.string.runtime_ok), (dialog, which) -> {
                    markLegacyImportOfferHandled("import");
                    legacyImportOfferDialog = null;
                    readLegacySettings(true);
                })
                .create();
        legacyImportOfferDialog.show();
        record("legacy_import_offer", "state", "shown");
    }

    private void markLegacyImportOfferHandled(String action) {
        boolean stored = preferences.edit().putBoolean(
                LegacySettingsImporter.PREF_IMPORT_OFFER_HANDLED, true).commit();
        record("legacy_import_offer", "state", action, "stored", stored);
    }

    void openBackgroundStartSettings(String reason) {
        if (cameraPermissionPending || backgroundStartSettingsActive || adbAuthPending
                || bluetoothPermissionInFlight) return;
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        acknowledgeInstallationReminder();
        backgroundStartSettingsActive = true;
        record("background_start_settings_open_requested", "reason", reason);
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(BYD_START_SETTINGS_PACKAGE, BYD_START_SETTINGS_CLASS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            preferences.edit().putBoolean(PREF_BACKGROUND_START_SETTINGS_SHOWN, true).apply();
            record("background_start_settings_opened", "reason", reason);
        } catch (Throwable error) {
            backgroundStartSettingsActive = false;
            record("background_start_settings_open_failed", "reason", reason,
                    "error", error.toString());
            publishSettingsFeedback(
                    runtimeText(R.string.runtime_background_settings_unavailable),
                    StatusTone.Error);
            advanceStartupAuthorizationFlow();
        }
        updateControls();
    }

    private void cancelPendingBackgroundStartSettings() {
        backgroundStartSettingsStartScheduled = false;
        mainHandler.removeCallbacks(startBackgroundStartSettings);
    }

    private boolean backgroundStartSettingsPending() {
        return backgroundStartSettingsRequired || backgroundStartSettingsActive
                || backgroundStartSettingsStartScheduled;
    }

    private boolean foregroundAdbAuthorizationReady() {
        return activityResumed && !activityDestroyed && !isFinishing() && !shutdownRequested
                && !bluetoothPermissionInFlight
                && !settingsTransferInProgress && !settingsReloadPending
                && !weatherLocationPermissionInFlight && !microphonePermissionInFlight
                && !startupOverlayPermissionFlow.overlaySettingsInFlight()
                && (productionUi == null || productionUi.getState().getDialog() == null);
    }

    boolean requestAdbAuthorization(
            String event, String operation, boolean automatic) {
        LocalAdbClient.PromptMode mode = automatic
                ? LocalAdbClient.PromptMode.AUTO_ONCE
                : LocalAdbClient.PromptMode.FORCE;
        if (shutdownRequested || activityDestroyed || isFinishing()) {
            record("adb_authorization_request_rejected", "automatic", automatic,
                    "reason", "activity_unavailable");
            return false;
        }
        if (automatic && (settingsTransferInProgress || settingsReloadPending
                || weatherLocationPermissionInFlight || microphonePermissionInFlight
                || cameraPermissionPending || backgroundStartSettingsPending()
                || !foregroundAdbAuthorizationReady() || adbAuthPending)) {
            record("adb_authorization_request_rejected", "automatic", true,
                    "reason", "startup_pending");
            return false;
        }
        if (!automatic) {
            cancelPendingBackgroundStartSettings();
            cancelPendingWeatherLocationPermission();
            if (!foregroundAdbAuthorizationReady() || !hasWindowFocus()
                    || cameraPermissionPending || backgroundStartSettingsActive) {
                cancelPendingForegroundAdbAuthorization();
                LocalAdbClient.AUTHORIZATION.cancelForeground();
                pendingManualAdbEvent = event;
                pendingManualAdbOperation = operation;
                reconcileForegroundAdbAuthorization();
                record("adb_authorization_manual_queued", "reason", "active_window");
                return true;
            }
            pendingManualAdbEvent = null;
            pendingManualAdbOperation = null;
        }
        cancelPendingForegroundAdbAuthorization();
        try {
            AdbAuthorizationSession.Submission submission =
                    ForegroundAdbAuthorization.request(this, mode, operation);
            record(event, "automatic", automatic, "mode", mode.name(),
                    "request_id", submission.snapshot.requestId,
                    "disposition", submission.disposition.name());
        } catch (java.util.concurrent.RejectedExecutionException stoppedExecutor) {
            reconcileForegroundAdbAuthorization();
            record("adb_authorization_request_failed", "reason", "executor_stopped");
            return false;
        }
        reconcileForegroundAdbAuthorization();
        updateControls();
        advanceStartupAuthorizationFlow();
        return true;
    }

    private void reconcileForegroundAdbAuthorization() {
        AdbAuthorizationSession.RequestSnapshot snapshot =
                LocalAdbClient.AUTHORIZATION.foregroundSnapshot();
        adbAuthPending = snapshot != null && snapshot.isActive();
        adbAuthMode = adbAuthPending ? snapshot.mode : null;
        adbAuthorizationRequested = snapshot != null;
        startupOverlayPermissionFlow.reconcileAuthorization(
                adbAuthorizationRequested, adbAuthPending
                        || LocalAdbClient.AUTHORIZATION.foregroundPermissionsPending());
        publishAdbOperation(adbAuthPending);
        refreshProductionHeader();
    }

    static boolean shouldStartForegroundAdbAuthorization(boolean permissionPending,
            boolean startupSettingsPending, boolean hasFocus, boolean uiReady,
            boolean authorizationPending, boolean alreadyRequested) {
        return !permissionPending && !startupSettingsPending && hasFocus && uiReady
                && !authorizationPending && !alreadyRequested;
    }

    static boolean shouldOpenBackgroundStartSettings(boolean autoStartEnabled,
            boolean permissionPending, boolean hasFocus, boolean settingsRequired,
            boolean settingsActive, boolean authorizationPending) {
        return !permissionPending && hasFocus && settingsRequired
                && !settingsActive && !authorizationPending;
    }

    private void transactManualTurnState(IBinder current, int payload) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(payload);
            requireTransaction(current, CameraHelperMain.TX_SET_TURN_STATE, data, reply);
            record("ipc_reply", "operation", "set_turn_state", "payload", payload,
                    "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "set_turn_state", "payload", payload,
                    "error", error.toString());
            runOnUiThread(() -> {
                manualTurnRequestPending = false;
                publishManualSignalStatus("Turn-state IPC error", StatusTone.Error);
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private static void requireTransaction(IBinder binder, int code, Parcel data, Parcel reply)
            throws RemoteException {
        if (!binder.transact(code, data, reply, 0)) {
            throw new RemoteException("Binder transaction " + code + " was rejected");
        }
        reply.readException();
    }

    private void acceptHelperEvent(String line) {
        if (line == null) return;
        ipcExecutor.execute(() -> {
            writeLine(line);
            final JSONObject parsed;
            try {
                parsed = new JSONObject(line);
            } catch (Throwable error) {
                Log.e(TAG, "Invalid helper JSON", error);
                return;
            }
            mainHandler.post(() -> {
            try {
                JSONObject json = parsed;
                String kind = json.optString("kind");
                if ("camera_vehicle_power_gate".equals(kind)) {
                    boolean wasOff = cameraVehiclePowerOff;
                    cameraVehiclePowerOff = json.optBoolean("off");
                    if (wasOff && !cameraVehiclePowerOff) resumeSelectedCameraPreview();
                    return;
                }
                if ("reverse_steering_state".equals(kind)) {
                    reverseSteeringSample = ReverseSteeringSample.fromEvent(json);
                    applyReverseSteering();
                    return;
                }
                if ("helper_death".equals(kind) || "helper_ping_failed".equals(kind)) {
                    reverseSteeringSample = ReverseSteeringSample.UNKNOWN;
                    applyReverseSteering();
                }
                if ("avas_status".equals(kind)) {
                    acceptAvasStatus(json);
                    return;
                }
                if ("avas_error".equals(kind)) {
                    acceptAvasError(json);
                    return;
                }
                // Validate a stock death against the live epoch before retiring that epoch.
                boolean backgroundHandled = handleReversePreviewBackgroundEvent(json);
                if (!backgroundHandled && "stock_avm_shell_died".equals(kind)
                        && activityCameraCloseIdentity.matchesShellDeath(
                                json.optInt("request_id", 0),
                                json.optLong("camera_shell_epoch", 0),
                                json.optLong("avm_shell_epoch", 0))) {
                    int deadRequest = json.optInt("request_id", 0);
                    failOwnedActivityCameraClose(deadRequest,
                            json.optString("error", "stock renderer died"));
                    backgroundHandled = true;
                }
                rememberActivityAvmShellEpoch(json, false);
                if (backgroundHandled) return;
                if ("reverse_camera_start".equals(kind)) {
                    int requestId = json.optInt("request_id", -1);
                    if (requestId > 0) activeReverseControllerRequestId = requestId;
                }
                if ("reverse_camera_state".equals(kind)) {
                    int previousRequestId = activeReverseControllerRequestId;
                    activeReverseControllerRequestId = normalizedReverseCameraRequestId(
                            json.optInt("request_id", 0));
                    if (shouldResumeAfterReverseCameraState(
                            hasResumeAutoPreviewIntent(0), previousRequestId,
                            activeReverseControllerRequestId)) {
                        resumeSelectedCameraPreview();
                    }
                    updateControls();
                    return;
                }
                if ("reverse_camera_stopped".equals(kind)) {
                    int requestId = json.optInt("request_id", -1);
                    if (shouldHandleReverseCameraStopped(requestId)) {
                        resumeSelectedCameraPreview();
                    } else {
                        record("activity_camera_event_ignored", "event_kind", kind,
                                "event_source", json.optString("source"),
                                "request_id", requestId,
                                "resume_request_id",
                                automaticPreviewIntentRequestId,
                                "selected_tab", selectedTab,
                                "resume_tab", selectedTab,
                                "activity_resumed", activityResumed);
                    }
                    updateControls();
                    return;
                }
                if (isOverlayCameraEvent(kind, json.optString("camera_owner"))) return;
                if ("camera_shell_died".equals(kind)) {
                    handleActivityCameraShellDied(json);
                } else if ("camera_shell_attached".equals(kind)) {
                    handleActivityCameraShellAttached(json);
                } else if ("camera_opened".equals(kind)) {
                    int requestId = json.optInt("request_id", 0);
                    if (!isCurrentActivityCameraEvent(
                            requestedOpen, activeActivityCameraRequestId, json)
                            || !isMatchingActivityCameraRenderer(json)) {
                        recordIgnoredActivityCameraEvent(kind, json);
                    } else {
                        rememberActivityAvmShellEpoch(json, true);
                        activeActivityCameraOpened = true;
                        activeActivityCameraFresh = false;
                        activeActivityConsumerGeneration = json.optInt(
                                "consumer_generation", 0);
                        // SurfaceView has no frame callback.  Reveal only after the current
                        // request's camera_opened event and a still-valid producer surface.
                        if (activePreview == directCameraPreview
                                && directCameraSurfaceReady
                                && directCameraPreview != null
                                && directCameraPreview.getHolder().getSurface().isValid()
                                && directCameraPreviewCover != null) {
                            directCameraPreviewCover.setVisibility(View.INVISIBLE);
                        } else if (activePreview == debugPreview
                                && debugSurfaceReady
                                && debugPreview != null
                                && debugSurface() != null && debugSurface().isValid()
                                && debugPreviewCover != null) {
                            debugPreviewCover.setVisibility(View.INVISIBLE);
                        }
                        if (activePreview == cameraPreview) {
                            armProductionPreviewFirstFrame(requestId);
                        } else if (activePreview == calibrationPreview) {
                            calibrationPreviewFreshness.arm(
                                    requestId, activeActivityInputGenerations);
                        } else if (activePreview == reverseCameraPreview) {
                            stopReverseCalibrationCopies(true);
                            armReverseCalibrationFreshness();
                            armReversePreviewFrames(requestId);
                        }
                        if (shouldRearmStockSurfaceRecovery(
                            kind, json.optString("renderer"))) {
                            if (invalidStockSurfaceRetryUsed) {
                                record("stock_avm_recovery", "state", "rearmed",
                                        "reason", "camera_opened");
                            }
                            invalidStockSurfaceRetryUsed = false;
                        }
                        if (activePreview == reverseCameraPreview
                                && ("reverse_preview_with_stock_base".equals(json.optString("view"))
                                || "reverse_preview_direct".equals(json.optString("view")))) {
                            if (reversePreviewBackgroundFailureRequestId == requestId) {
                                publishReversePreviewBackgroundUnavailable();
                            } else {
                                publishCameraEventStatus(json, "Очікування перших кадрів...",
                                        StatusTone.Warning, false);
                            }
                        } else if (activePreview == cameraPreview || activePreview == calibrationPreview) {
                            publishCameraEventStatus(json, "Очікування перших кадрів...",
                                    StatusTone.Warning, false);
                        } else publishCameraEventStatus(json,
                                json.optString("renderer").startsWith("stock_avm")
                                || json.optInt("preview_index", -1) < 0
                                ? "Showing " + json.optString("view")
                                : "Showing " + json.optString("view")
                                        + " (preview " + json.optInt("preview_index") + ")",
                                StatusTone.Ok, false);
                    }
                } else if ("stock_avm_stage".equals(kind)) {
                    if ("select_renderer".equals(json.optString("stage")) && productionUi != null) {
                        productionUi.setAvmRenderer(json.optString("detail").split(";", 2)[0]);
                    }
                    if (!isCurrentDiagnosticStageEvent(json)) {
                        recordIgnoredActivityCameraEvent(kind, json);
                    } else {
                        String stage = json.optString("stage");
                        boolean terminal = isTerminalDiagnosticStage(stage);
                        publishCameraEventStatus(json, "Stock AVM: " + stage,
                                terminal ? StatusTone.Ok : StatusTone.Warning, !terminal);
                    }
                } else if ("camera_discovery".equals(kind)) {
                    cameraDiscovered = json.optBoolean("ok");
                    if (cameraDiscovered) openPendingMotionPreviews();
                    String status = cameraDiscovered
                            ? "AVM camera ready"
                            : "Camera discovery failed: " + json.optString("error");
                    StatusTone tone = cameraDiscovered ? StatusTone.Ok : StatusTone.Error;
                    record("camera_status", "profile", "diagnostic",
                            "text", status, "tone", tone.name(), "pending", false,
                            "candidate_ids", json.optString("candidate_ids"));
                    publishDiagnosticStatus(false, status, tone, false);
                    publishDiagnosticStatus(true, cameraDiscovered
                            ? "Detected " + json.optString("candidate_ids") : status,
                            tone, false);
                    maybeOpenCalibrationCamera();
                    maybeOpenProductionPreview();
                    maybeOpenReversePreview();
                } else if ("camera_error".equals(kind)) {
                    int requestId = json.optInt("request_id", 0);
                    if (isMatchingStockShellCloseError(
                            json.optString("renderer"), json.optString("stage"),
                            activityCameraCloseIdentity.requestId(), requestId)
                            && matchesActivityCameraCloseFailure(json)) {
                        String failure = json.optString("error", "stock shell close failed");
                        failOwnedActivityCameraClose(requestId, failure);
                        return;
                    }
                    boolean accepted = isCurrentActivityCameraEvent(
                            requestedOpen, activeActivityCameraRequestId, json)
                            && isMatchingActivityCameraRenderer(json);
                    if (!accepted) {
                        recordIgnoredActivityCameraEvent(kind, json);
                    } else {
                        if (CameraHelperMain.isSourceRecovery(json.optString("stage"))) {
                            if (!cameraTransition.pending()) {
                                retryStockViewpoint = activePreview == debugPreview ? activeCameraViewpoint : -1;
                                retryStockDebug = activePreview == debugPreview;
                                closeCameraForTransition(json.optString("stage"));
                            }
                            return;
                        }
                        if (selectedTab == TAB_REVERSE_CAMERAS
                                && activePreview == reverseCameraPreview) {
                            clearResumeAutoPreview();
                            pendingReversePreviewRequestId = 0;
                            pendingReversePreviewGenerations = null;
                        }
                        if (isInvalidStockSurfaceError(
                            json.optString("renderer"), json.optString("stage"),
                            json.optString("error"))) {
                            if (cameraTransition.pending()) return;
                            if (!invalidStockSurfaceRetryUsed && requestedOpen) {
                                invalidStockSurfaceRetryUsed = true;
                                retryStockViewpoint = activePreview == debugPreview
                                        ? activeCameraViewpoint : -1;
                                retryStockDebug = activePreview == debugPreview;
                                publishCameraEventStatus(json,
                                        "AVM Surface invalid; retrying once...",
                                        StatusTone.Warning, true);
                                record("stock_avm_recovery", "state", "attempt",
                                        "viewpoint", retryStockViewpoint,
                                        "debug", retryStockDebug);
                                closeCameraForTransition("invalid_stock_surface_recovery");
                                return;
                            }
                        }
                        boolean resumeOverlay = requestedOpen;
                        requestedOpen = false;
                        activeActivityCameraRequestId = 0;
                        activeActivityConsumerGeneration = 0;
                        activeActivityCameraOpened = false;
                        activeActivityCameraFresh = false;
                        activeActivityInputGenerations = new int[0];
                        clearActivityCameraCloseIdentity();
                        pendingCameraViewpoint = -1;
                        pendingCameraDebug = false;
                        stopCalibrationCopies(true);
                        publishCameraEventStatus(json,
                                "Camera error: " + json.optString("error"),
                                StatusTone.Error, false);
                        clearPreview("camera_error");
                        activePreview = null;
                        activeActivityCameraProfile = null;
                        activePreviewCover = null;
                        if (resumeOverlay) CameraHelperService.cameraPreviewStopped(this);
                    }
                } else if ("camera_closed".equals(kind)) {
                    String reason = json.optString("reason");
                    int requestId = json.optInt("request_id", 0);
                    String renderer = json.optString("renderer");
                    String error = json.optString("error", "");
                    if (!error.isEmpty() && matchesActivityCameraCloseFailure(json)) {
                        failOwnedActivityCameraClose(requestId, error);
                        return;
                    }
                    if (CameraTransition.reasonEquals(
                            reason, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)) {
                        boolean requestMatches = matchesActivityCameraClose(json);
                        boolean shellEvent = "stock_avm_shell".equals(renderer)
                                && "camera_closed".equals(kind);
                        if (!activityColdResetInFlight || !cameraTransition.matches(reason)
                                || !requestMatches || !shellEvent) {
                            clearStaleColdResetInFlight(reason);
                            recordIgnoredActivityCameraEvent(kind, json);
                            return;
                        }
                        boolean cameraEpochMatches = isMatchingActivityCameraShellEpochStrict(
                                activityCameraCloseIdentity.cameraShellEpoch(),
                                json.optLong("camera_shell_epoch", 0));
                        boolean avmEpochMatches = activityCameraCloseIdentity.avmShellEpoch()
                                == json.optLong("avm_shell_epoch", 0);
                        if (!cameraEpochMatches || !avmEpochMatches) {
                            clearStaleColdResetInFlight(reason);
                            recordIgnoredActivityCameraEvent(kind, json);
                        } else if (!isMatchingColdResetShellCallback(
                                reason, reason, renderer, kind, error,
                                activityCameraCloseIdentity.requestId(), requestId,
                                activityCameraCloseIdentity.cameraShellEpoch(),
                                json.optLong("camera_shell_epoch", 0))) {
                            failActivityColdReset(requestId,
                                    error == null || error.isEmpty()
                                    ? "invalid stock shell close callback" : error);
                        } else {
                            completeActivityColdReset(reason, requestId, "shell_callback");
                        }
                        return;
                    }
                    if (isMatchingPendingActivityShellClose(
                            activityClosePending, closingActivityCameraRequestId,
                            requestId, renderer) && matchesActivityCameraClose(json)) {
                        finishActivityStoppedClose(
                                requestId, error == null || error.isEmpty() ? null : error);
                        return;
                    }
                    if ("activity_stopped".equals(reason)) {
                        recordIgnoredActivityCameraEvent(kind, json);
                        return;
                    }
                    boolean accepted = isCurrentActivityCameraTerminalEvent(requestId, json)
                            && isMatchingActivityCameraRenderer(json);
                    if (!accepted) {
                        recordIgnoredActivityCameraEvent(kind, json);
                        return;
                    }
                    if (cameraTransition.pending() && !cameraTransition.matches(reason)) {
                        recordIgnoredActivityCameraEvent(kind, json);
                        return;
                    }
                    if (CameraTransition.owns(reason)) {
                        if ("stock_avm_shell".equals(renderer)) {
                            boolean unopenedClose = matchesUnopenedStockClose(json);
                            boolean identityMatches = cameraTransition.matches(reason)
                                    && matchesActivityCameraClose(json)
                                    && isMatchingActivityCameraShellEpochStrict(
                                    activityCameraCloseIdentity.cameraShellEpoch(),
                                    json.optLong("camera_shell_epoch", 0))
                                    && (unopenedClose || isMatchingActivityAvmShellEpoch(
                                    activityCameraCloseIdentity.avmShellEpoch(),
                                    json.optLong("avm_shell_epoch", 0)));
                            if (!identityMatches) {
                                recordIgnoredActivityCameraEvent(kind, json);
                            } else if (unopenedClose || isMatchingPendingActivityTransitionShellClose(
                                    cameraTransition.pendingToken(), reason, renderer, kind, error,
                                    activityCameraCloseIdentity.requestId(), requestId,
                                    activityCameraCloseIdentity.cameraShellEpoch(),
                                    json.optLong("camera_shell_epoch", 0),
                                    activityCameraCloseIdentity.avmShellEpoch(),
                                    json.optLong("avm_shell_epoch", 0))) {
                                finishCameraTransition(reason, "shell_callback");
                            } else {
                                failCameraTransition(reason, error);
                            }
                        } else {
                            record("camera_transition_close_observed", "token", reason,
                                    "source", json.optString("source"));
                        }
                    } else if (!isIntermediateCameraClose(reason)) {
                        if (shouldWaitForStockShellClose(
                                json.optString("view"), renderer,
                                json.optBoolean("stock_close_pending", false))) {
                            record("activity_camera_close", "state", "waiting_shell_close",
                                    "reason", reason, "request_id", requestId);
                            return;
                        }
                        if (!error.isEmpty()) {
                            failOwnedActivityCameraClose(requestId, error);
                            return;
                        }
                        if (shouldArmAutoPreviewAfterCameraClose(reason)) {
                            armResumeAutoPreview();
                        }
                        boolean resumeOverlay = requestedOpen;
                        requestedOpen = false;
                        activeActivityCameraRequestId = 0;
                        activeActivityConsumerGeneration = 0;
                        activeActivityCameraOpened = false;
                        activeActivityCameraFresh = false;
                        activeActivityInputGenerations = new int[0];
                        clearActivityCameraCloseIdentity();
                        pendingCameraViewpoint = -1;
                        pendingCameraDebug = false;
                        stopCalibrationCopies(true);
                        // A locally closed request already published to its captured profile.
                        if (resumeOverlay) publishCameraEventStatus(json, "Camera closed",
                                StatusTone.Warning, false);
                        clearPreview("camera_closed");
                        activePreview = null;
                        activeActivityCameraProfile = null;
                        activePreviewCover = null;
                        if (resumeOverlay) CameraHelperService.cameraPreviewStopped(this);
                        if (hasAutoPreviewIntent()) {
                            // TextureView recreates the lost input itself. If that callback
                            // arrived before this matching close, keep it; if it arrives later,
                            // onCameraSurfaceAvailable performs the same gated resume.
                            if (shouldRenewAutomaticInputAfterTerminalClose(reason)) {
                                renewSelectedPreviewInputForTabSwitch();
                            }
                            resumeSelectedCameraPreview();
                        }
                    }
                } else if ("telemetry_ready".equals(kind)) {
                    telemetryReady = json.optBoolean("ok");
                    publishGuardStatus(telemetryReady
                                    ? "Телеметрія готова"
                                    : "Telemetry error: " + json.optString("error"),
                            telemetryReady ? StatusTone.Ok : StatusTone.Error);
                } else if ("reverse_gear_state".equals(kind)) {
                    manualGearPark = json.optBoolean("valid") && json.optInt("raw", -1) == 1;
                } else if ("reverse_gear_listener".equals(kind)) {
                    if (!json.optBoolean("ok") || "stopped".equals(json.optString("action"))) {
                        manualGearPark = false;
                    }
                } else if ("adb_auth_start".equals(kind) || "adb_auth_state".equals(kind)
                        || "authorization_superseded".equals(kind)
                        || "adb_auth_auto_blocked".equals(kind) || "adb_auth_result".equals(kind)) {
                    // Camera status can arrive late; only the process RSA owner completes UI work.
                    reconcileForegroundAdbAuthorization();
                } else if ("helper_launch".equals(kind) && !json.optBoolean("ok")) {
                    telemetryReady = false;
                    manualGearPark = false;
                    record("helper_launch_ui_detail_suppressed", "error", json.optString("error"));
                } else if ("helper_death".equals(kind)
                        || "helper_ping_failed".equals(kind)) {
                    avasEngineTestActive = false;
                    avasEngineState = "stopped";
                    avasEngineError = "";
                    if ("helper_death".equals(kind)
                            && avasAudition.getSessionId() != null
                            && avasAudition.getSessionId().equals(
                                    json.optString("audition_session_id"))) {
                        avasAudition = new AvasAuditionUiState();
                        if (productionUi != null) productionUi.refreshAvasState();
                    }
                    if (productionUi != null) productionUi.refreshAvasState();
                    telemetryReady = false;
                    manualGearPark = false;
                    publishGuardStatus("Helper відновлюється: "
                            + json.optString("error"), StatusTone.Error);
                    record("helper_recovery_ui_detail_suppressed", "error", json.optString("error"));
                } else if ("guard_config".equals(kind)) {
                    if (json.optBoolean("active")) {
                        publishGuardStatus("Guard активний", StatusTone.Ok);
                    } else if (json.optBoolean("requested")) {
                        publishGuardStatus("Guard призупинено: "
                                + json.optString("reason"), StatusTone.Warning);
                    } else {
                        publishGuardStatus("Guard вимкнено", StatusTone.Warning);
                    }
                } else if ("driver_activation".equals(kind)) {
                    String direction = json.optString("direction");
                    publishGuardStatus("left".equals(direction)
                            ? "Лівий поворотник"
                            : "right".equals(direction)
                                    ? "Правий поворотник" : "Поворотник",
                            StatusTone.Ok);
                } else if ("guard_armed".equals(kind)) {
                    publishGuardStatus("Поріг пройдено; очікування центру",
                            StatusTone.Warning);
                } else if ("guard_completed".equals(kind)) {
                    publishGuardStatus("Маневр завершено", StatusTone.Ok);
                } else if ("guard_speed_deferred_resumed".equals(kind)) {
                    publishGuardStatus("Guard активний: швидкість нижче ліміту",
                            StatusTone.Ok);
                } else if ("guard_speed_deferred_canceled".equals(kind)) {
                    publishGuardStatus("Очікування guard скасовано: "
                            + json.optString("reason"), StatusTone.Warning);
                } else if ("manual_cancel".equals(kind)) {
                    publishGuardStatus("Ручне вимкнення; корекцію скасовано",
                            StatusTone.Warning);
                } else if ("correction_requested".equals(kind)) {
                    publishGuardStatus("Корекція: " + json.optString("direction"),
                            StatusTone.Warning);
                } else if ("correction_confirmed".equals(kind)) {
                    publishGuardStatus("Корекцію підтверджено", StatusTone.Ok);
                } else if ("control_latch_reset_accepted".equals(kind)) {
                    publishGuardStatus("State поворотників скинуто: "
                            + json.optString("reason"), StatusTone.Ok);
                } else if ("control_latch_reset_failed".equals(kind)) {
                    publishGuardStatus("Скидання state не виконано: "
                            + json.optString("error"), StatusTone.Error);
                } else if ("hazard_cleanup_pending".equals(kind)) {
                    publishGuardStatus("Аварійка: очікування скидання state",
                            StatusTone.Warning);
                } else if ("hazard_cleanup_completed".equals(kind)) {
                    publishGuardStatus("Аварійку вимкнено; state скинуто в 0",
                            StatusTone.Ok);
                } else if ("hazard_cleanup_failed".equals(kind)
                        || "hazard_cleanup_canceled".equals(kind)) {
                    publishGuardStatus(kind + ": " + json.optString("reason"),
                            StatusTone.Error);
                } else if ("manual_turn_state_requested".equals(kind)) {
                    manualTurnRequestPending = true;
                    publishManualSignalStatus("Команду прийнято; перевірка blink...",
                            StatusTone.Warning);
                } else if ("manual_turn_state_confirmed".equals(kind)) {
                    manualTurnRequestPending = false;
                    if (json.optInt("payload") == 0
                            && !json.optBoolean("observable_transition")) {
                        publishManualSignalStatus(
                                "Payload 0 прийнято; очищення перевірити після restart",
                                StatusTone.Warning);
                    } else {
                        publishManualSignalStatus("Стан підтверджено: "
                                + json.optString("action"), StatusTone.Ok);
                    }
                } else if ("manual_turn_state_rejected".equals(kind)
                        || "manual_turn_state_failed".equals(kind)) {
                    manualTurnRequestPending = false;
                    publishManualSignalStatus(kind + ": " + json.optString("reason"),
                            StatusTone.Error);
                } else if ("correction_failed".equals(kind)
                        || "guard_suppressed".equals(kind)
                        || "telemetry_error".equals(kind)) {
                    if ("telemetry_error".equals(kind)) telemetryReady = false;
                    if ("guard_suppressed".equals(kind)
                            && "speed_above_limit".equals(json.optString("reason"))) {
                        publishGuardStatus("Guard очікує швидкість нижче ліміту",
                                StatusTone.Warning);
                    } else {
                        publishGuardStatus(kind + ": " + json.optString("reason"),
                                StatusTone.Error);
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "Invalid helper JSON", error);
            }
            advanceStartupAuthorizationFlow();
            updateControls();
            });
        });
    }

    private void acceptAvasStatus(JSONObject event) {
        JSONObject profiles = event.optJSONObject("profiles");
        if (profiles == null) return;
        Map<String, AvasPlaybackUiState> next = new HashMap<>();
        for (String profileId : AvasConfig.PROFILE_IDS) {
            String value = profiles.optString(profileId, "idle");
            AvasPlaybackUiState playback;
            if ("manual_queued".equals(value)) playback = AvasPlaybackUiState.ManualQueued;
            else if ("manual_playing".equals(value)) playback = AvasPlaybackUiState.ManualPlaying;
            else if ("automatic_playing".equals(value)) playback = AvasPlaybackUiState.AutomaticPlaying;
            else if ("automatic_queued".equals(value)) playback = AvasPlaybackUiState.AutomaticQueued;
            else playback = AvasPlaybackUiState.Idle;
            next.put(profileId, playback);
        }
        avasPlayback.clear();
        avasPlayback.putAll(next);
        JSONObject engine = event.optJSONObject("engine");
        avasEngineTestActive = false;
        if (helper == null || !helperCallbackRegistration.registered()) {
            avasEngineState = "stopped";
            avasEngineError = "";
        } else if (engine == null) {
            avasEngineState = "stopped";
            avasEngineError = "";
        } else {
            String state = engine.optString("state", "stopped");
            avasEngineState = "starting".equals(state) || "active".equals(state)
                    || "stopping".equals(state) || "error".equals(state)
                    ? state : "stopped";
            avasEngineError = engine.optString("error", "");
            avasEngineTestActive = engine.optBoolean("testActive", false);
        }
        JSONObject audition = event.optJSONObject("audition");
        if (audition != null && avasAudition.getSessionId() != null
                && avasAudition.getSessionId().equals(audition.optString("sessionId"))) {
            avasAudition = "idle".equals(audition.optString("state"))
                    ? new AvasAuditionUiState()
                    : new AvasAuditionUiState(audition.optString("profileId"),
                            audition.optString("assetId"), audition.optString("sessionId"),
                            audition.optString("state"));
        }
        if (productionUi != null) productionUi.refreshAvasState();
    }

    private void acceptAvasError(JSONObject event) {
        String profileId = event.optString("profile_id");
        String stage = event.optString("stage");
        if ("audition".equals(stage)) {
            if (!event.optString("session_id").equals(avasAudition.getSessionId())) return;
            avasAudition = new AvasAuditionUiState();
        } else if ("audition_stop".equals(stage)
                && !event.optString("session_id").equals(lastStoppedAvasSession)) {
            return;
        }
        if (AvasConfig.PROFILE_IDS.contains(profileId)
                && ("manual_start".equals(stage) || "manual_stop".equals(stage)
                        || "manual_playback".equals(stage))) {
            avasPlayback.put(profileId, AvasPlaybackUiState.Idle);
        }
        if ("engine".equals(profileId)
                && ("engine_start".equals(stage) || "engine_stop".equals(stage)
                        || "engine_toggle".equals(stage))) {
            if (!"engine_toggle".equals(stage)) avasEngineTestActive = false;
            avasEngineState = "error";
            avasEngineError = event.optString("error", "");
        }
        if (productionUi != null) productionUi.refreshAvasState();
        String detail = event.optString("error");
        record("avas_ui_error", "stage", stage, "profile_id", profileId,
                "asset_id", event.optString("asset_id"), "error", detail);
        if (!activityDestroyed && !isFinishing()) {
            Toast.makeText(this, runtimeText(R.string.runtime_avas_command_failed,
                    detail.isEmpty() ? stage : detail), Toast.LENGTH_LONG).show();
        }
    }

    private void handleActivityCameraShellDied(JSONObject event) {
        long shellEpoch = event.optLong("camera_shell_epoch", 0);
        if (!shouldAcceptActivityCameraShellDeath(
                activityCameraShellEpoch, shellEpoch,
                activityCameraShellLastDeathEpoch, activityCameraShellDeathHandled)) {
            record("activity_camera_shell_death_ignored",
                    "reason", "stale_or_duplicate_epoch",
                    "selected_tab", selectedTab,
                    "camera_shell_epoch", shellEpoch,
                    "active_camera_shell_epoch", activityCameraShellEpoch);
            return;
        }
        if (shellEpoch > 0) {
            activityCameraShellEpoch = shellEpoch;
            activityCameraShellLastDeathEpoch = shellEpoch;
        }
        activityCameraShellDeathHandled = true;
        cameraShellAvailable = false;
        boolean transitionPending = cameraTransition.pending();
        boolean hadResumeIntent = hasAutoPreviewIntent();
        boolean debugRequestClaimed = activePreview == debugPreview
                || (cameraHandoffPending && pendingCameraDebug);
        boolean shellDependentSelection = selectedTab == TAB_REVERSE_CAMERAS
                || selectedTab == TAB_CAMERA_DEBUG && selectedDebugMode == 1;
        boolean invalidate = shouldInvalidateActivityForCameraShellDeath(
                activePreview == debugPreview,
                activePreview == reverseCameraPreview,
                cameraHandoffPending && pendingCameraDebug,
                shellDependentSelection
                        && (transitionPending || hadResumeIntent || hasScopedShellRetry()));
        if (!invalidate) {
            record("activity_camera_shell_death_ignored",
                    "selected_tab", selectedTab,
                    "active_direct_preview", activePreview == cameraPreview
                            || activePreview == calibrationPreview
                            || activePreview == directCameraPreview,
                    "camera_shell_epoch", event.optLong("camera_shell_epoch", 0));
            return;
        }
        if (activePreview == debugPreview && selectedTab == TAB_CAMERA_DEBUG
                && activeCameraViewpoint >= 0) {
            retryStockViewpoint = activeCameraViewpoint;
            retryStockDebug = true;
        }
        boolean reverseFailClosed = selectedTab == TAB_REVERSE_CAMERAS;
        if (shouldAutoRecoverAfterCameraShellDeath(reverseFailClosed)) {
            if (isAutoPreviewTab(selectedTab)) armResumeAutoPreview();
        } else {
            clearResumeAutoPreview();
        }
        cameraShellRecoveryPending = !reverseFailClosed;
        boolean previewClaimed = requestedOpen || cameraHandoffPending;
        cameraTransition.cancel();
        if (activityCameraCloseIdentity.ownsCameraShellEpoch(shellEpoch)) {
            activityClosePending = false;
            clearActivityCameraCloseIdentity();
        }
        if (reverseFailClosed) clearReverseShellFailureState();
        if (cameraPreview != null) cameraPreview.removeCallbacks(finishCameraHandoff);
        if (debugPreview != null) debugPreview.removeCallbacks(finishCameraHandoff);
        cameraHandoffPending = false;
        pendingCameraViewpoint = -1;
        pendingCameraDebug = false;
        requestedOpen = false;
        stopCalibrationCopies(true);
        clearPreview("camera_shell_died");
        activePreview = null;
        activeActivityCameraProfile = null;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = -1;
        activeActivityCameraRequestId = 0;
        activeActivityConsumerGeneration = 0;
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        activeActivityInputGenerations = new int[0];
        if (previewClaimed) CameraHelperService.cameraPreviewStopped(this);
        // Death is a terminal fail-closed state for the matching AVM/debug request.  Recovery is
        // driven by the later shell-attached event, never by a stale pending indicator.
        if (debugRequestClaimed) {
            publishDiagnosticStatus(false, "Camera helper недоступний",
                    StatusTone.Error, false);
        }
        record("activity_camera_output_invalidated",
                "camera_shell_epoch", shellEpoch,
                "reopen_pending", cameraShellRecoveryPending,
                "status", reverseFailClosed
                        ? "Camera closed; reopen the tab to retry"
                        : "Camera helper відновлюється...");
    }

    private void rememberActivityAvmShellEpoch(
            JSONObject event, boolean acceptedCameraOpen) {
        if (event == null) return;
        String kind = event.optString("kind");
        String renderer = event.optString("renderer");
        int requestId = event.optInt("request_id", 0);
        long cameraEpoch = event.optLong("camera_shell_epoch", 0);
        long avmEpoch = event.optLong("avm_shell_epoch", 0);
        if ("camera_shell_close_owner".equals(kind)) {
            // The controller emits this from the exact request-scoped state being closed,
            // including cleanup for an open canceled before it can publish as camera_opened.
            activityCameraCloseIdentity.bindStockCloseOwner(
                    requestId, event.optString("reason"), cameraEpoch, avmEpoch);
        }
        activityAvmShellEpoch = nextActivityAvmShellEpoch(
                activityAvmShellEpoch,
                kind, renderer, avmEpoch, acceptedCameraOpen);
    }

    static long nextActivityAvmShellEpoch(
            long currentEpoch, String kind, String renderer,
            long eventEpoch, boolean acceptedCameraOpen) {
        if (eventEpoch <= 0) return currentEpoch;
        if ("stock_avm_shell_died".equals(kind)) {
            return eventEpoch == currentEpoch ? 0 : currentEpoch;
        }
        if ("stock_avm_shell_attached".equals(kind)) {
            return Math.max(currentEpoch, eventEpoch);
        }
        if (acceptedCameraOpen && "camera_opened".equals(kind)
                && "stock_avm_shell".equals(renderer)) {
            return Math.max(currentEpoch, eventEpoch);
        }
        // In particular, camera_closed must validate against the already tracked epoch.
        return currentEpoch;
    }

    static boolean shouldAutoRecoverAfterCameraShellDeath(boolean reverseTabSelected) {
        return !reverseTabSelected;
    }

    private void clearReverseShellFailureState() {
        clearResumeAutoPreview();
        activityClosePending = false;
        clearActivityCameraCloseIdentity();
        activityColdResetRequired = false;
        activityColdResetInFlight = false;
        activityColdResetFailed = false;
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
    }

    private void handleActivityCameraShellAttached(JSONObject event) {
        long shellEpoch = event.optLong("camera_shell_epoch", 0);
        if (!shouldAcceptActivityCameraShellAttach(
                activityCameraShellEpoch, shellEpoch, cameraShellAvailable)) {
            record("activity_camera_shell_attach_ignored",
                    "reason", "stale_or_duplicate_epoch",
                    "camera_shell_epoch", shellEpoch,
                    "active_camera_shell_epoch", activityCameraShellEpoch);
            return;
        }
        if (shellEpoch > 0) activityCameraShellEpoch = shellEpoch;
        activityCameraShellDeathHandled = false;
        cameraShellAvailable = true;
        if ((!hasAutoPreviewIntent() && !hasScopedShellRetry())
                || (!isAutoPreviewTab(selectedTab) && !hasScopedShellRetry())) {
            cameraShellRecoveryPending = false;
            return;
        }
        resumeActivityCameraAfterShellRecovery(
                "camera_shell_attached", shellEpoch);
    }

    private void resumeActivityCameraAfterShellRecovery(String reason, long epoch) {
        if (!shouldResumeActivityCameraRecovery(
                cameraShellRecoveryPending, activityResumed, cameraShellAvailable)) return;
        if (!hasResumeAutoPreviewIntent(0) && !hasScopedShellRetry()) {
            cameraShellRecoveryPending = false;
            return;
        }
        cameraShellRecoveryPending = false;
        record("activity_camera_reopen",
                "reason", reason,
                "camera_shell_epoch", epoch,
                "selected_tab", selectedTab);
        resumeSelectedCameraPreview();
    }

    static boolean shouldRecoverActivityCamera(
            boolean requestedOpen, boolean handoffPending, boolean transitionPending) {
        return requestedOpen || handoffPending || transitionPending;
    }

    static boolean shouldInvalidateActivityForCameraShellDeath(
            boolean stockPreviewActive,
            boolean reversePreviewActive,
            boolean stockHandoffPending,
            boolean shellDependentRecoveryPending) {
        return stockPreviewActive || reversePreviewActive
                || stockHandoffPending || shellDependentRecoveryPending;
    }

    private void armResumeAutoPreviewIfNeeded() {
        if (shutdownRequested || !shouldResumeActivityPreviewAfterStop(
                false, isAutoPreviewTab(selectedTab), requestedOpen,
                cameraHandoffPending, cameraTransition.pending())) return;
        armResumeAutoPreview();
    }

    private void armResumeAutoPreview() {
        if (shutdownRequested || !isAutoPreviewTab(selectedTab)) return;
        automaticPreviewIntent = true;
        automaticPreviewIntentTab = selectedTab;
        automaticPreviewIntentRequestId = activeActivityCameraRequestId;
    }

    private void clearResumeAutoPreview() {
        automaticPreviewIntent = false;
        automaticPreviewIntentTab = -1;
        automaticPreviewIntentRequestId = 0;
    }

    private boolean hasAutoPreviewIntent() {
        return automaticPreviewIntent
                && automaticPreviewIntentTab == selectedTab
                && isAutoPreviewTab(selectedTab);
    }

    private boolean canStartActivityCamera() {
        return !cameraVehiclePowerOff && canStartActivityCamera(
                activityResumed, shutdownRequested, activityDestroyed,
                activityClosePending, closingActivityCameraRequestId);
    }

    static boolean canStartActivityCamera(
            boolean activityResumed, boolean shutdownRequested,
            boolean activityDestroyed, boolean activityClosePending,
            int closingRequestId) {
        return activityResumed && !shutdownRequested && !activityDestroyed
                && !activityClosePending && closingRequestId <= 0;
    }

    private boolean canAutoOpenSelectedPreview() {
        return canStartActivityCamera() && activeReverseControllerRequestId <= 0
                && !requestedOpen && !cameraHandoffPending && !cameraTransition.pending()
                && hasAutoPreviewIntent();
    }

    private boolean hasResumeAutoPreviewIntent(int eventRequestId) {
        return !shutdownRequested
                && activityResumed && hasAutoPreviewIntent()
                && (eventRequestId <= 0
                        || automaticPreviewIntentRequestId > 0
                        && automaticPreviewIntentRequestId == eventRequestId);
    }

    private boolean canResumeSelectedPreview() {
        return canStartActivityCamera()
                && !shouldDeferActivityPreviewForReverse(activeReverseControllerRequestId)
                && (!cameraShellRecoveryPending || cameraShellAvailable)
                && (hasResumeAutoPreviewIntent(0) || hasScopedShellRetry());
    }

    static boolean shouldDeferActivityPreviewForReverse(int activeRequestId) {
        return activeRequestId > 0;
    }

    static boolean cameraPolicyChanged(
            int savedMinimum, int savedMaximum, int minimum, int maximum) {
        return savedMinimum != minimum || savedMaximum != maximum;
    }

    static boolean frontCameraPolicyChanged(
            int savedMinimum, int savedMaximum, float savedAngle,
            int minimum, int maximum, float angle) {
        return cameraPolicyChanged(savedMinimum, savedMaximum, minimum, maximum)
                || Float.compare(savedAngle, angle) != 0;
    }

    private boolean hasScopedShellRetry() {
        return selectedTab == TAB_CAMERA_DEBUG
                && retryStockViewpoint >= 0 && retryStockDebug;
    }

    private boolean shouldHandleReverseCameraStopped(int eventRequestId) {
        int previousRequestId = activeReverseControllerRequestId;
        activeReverseControllerRequestId = reverseRequestAfterStopped(
                activeReverseControllerRequestId, eventRequestId);
        return previousRequestId > 0 && activeReverseControllerRequestId == 0
                && hasResumeAutoPreviewIntent(0);
    }

    static int reverseRequestAfterStopped(int activeRequestId, int eventRequestId) {
        return activeRequestId > 0 && eventRequestId == activeRequestId
                ? 0 : activeRequestId;
    }

    static int normalizedReverseCameraRequestId(int reportedRequestId) {
        return Math.max(0, reportedRequestId);
    }

    static boolean shouldResumeAfterReverseCameraState(
            boolean restoreIntent, int previousRequestId, int reportedRequestId) {
        return restoreIntent && previousRequestId > 0 && reportedRequestId == 0;
    }

    private boolean isCurrentActivityCameraTerminalEvent(int eventRequestId) {
        return isCurrentActivityCameraTerminalEvent(
                requestedOpen, activeActivityCameraRequestId,
                closingActivityCameraRequestId, eventRequestId);
    }

    static boolean isCurrentActivityCameraTerminalEvent(
            boolean requestedOpen, int activeRequestId,
            int closingRequestId, int eventRequestId) {
        int expectedRequestId = requestedOpen ? activeRequestId : closingRequestId;
        return expectedRequestId > 0 && eventRequestId == expectedRequestId;
    }

    private boolean shouldPreserveAutoPreviewAfterClose(String reason) {
        if (shutdownRequested) return false;
        if (shouldPreserveAutoPreviewForTemporarySurfaceLoss(
                reason, hasAutoPreviewIntent(),
                activePreview == cameraPreview && !isProductionCalibrationSection(),
                activePreview == calibrationPreview && isProductionCalibrationSection())) {
            return true;
        }
        if (CameraTransition.owns(reason)
                || "activity_stopped".equals(reason)
                || "replace_with_multi_preview".equals(reason)) return true;
        return !activityResumed && hasAutoPreviewIntent();
    }

    static boolean shouldPreserveAutoPreviewForTemporarySurfaceLoss(
            String reason, boolean existingSelectedIntent,
            boolean productionOwner, boolean calibrationOwner) {
        if (!existingSelectedIntent) return false;
        return (productionOwner && "surface_destroyed".equals(reason))
                || (calibrationOwner
                        && "calibration_surface_destroyed".equals(reason));
    }

    static boolean shouldRenewAutomaticInputAfterTerminalClose(String reason) {
        return !"surface_destroyed".equals(reason)
                && !"calibration_surface_destroyed".equals(reason);
    }

    static boolean shouldWaitForStockShellClose(
            String view, String renderer, boolean stockClosePending) {
        return "reverse_preview_with_stock_base".equals(view)
                && !"stock_avm_shell".equals(renderer) && stockClosePending;
    }

    private boolean shouldArmAutoPreviewAfterCameraClose(String reason) {
        return shouldArmAutoPreviewAfterTakeover(
                shutdownRequested, activityResumed, isAutoPreviewTab(selectedTab), reason);
    }

    static boolean shouldArmAutoPreviewAfterTakeover(
            boolean shutdown, boolean resumed, boolean autoPreviewTab, String reason) {
        return !shutdown && resumed && autoPreviewTab
                && "replace_with_multi_preview".equals(reason);
    }

    static boolean shouldResumeActivityPreviewAfterStop(
            boolean shutdown, boolean autoPreviewTab,
            boolean requestedOpen, boolean handoffPending, boolean transitionPending) {
        return !shutdown && autoPreviewTab
                && shouldRecoverActivityCamera(
                        requestedOpen, handoffPending, transitionPending);
    }

    private static boolean isAutoPreviewTab(int tab) {
        return tab == TAB_CAMERAS
                || tab == TAB_CAMERA_CALIBRATION
                || tab == TAB_PARKING_CAMERAS
                || tab == TAB_REARVIEW_MIRROR
                || tab == TAB_REVERSE_CAMERAS;
    }

    static CalibrationUiState calibrationUiState(
            boolean correctionEnabled, boolean rawFallback) {
        return new CalibrationUiState(
                correctionEnabled,
                correctionEnabled && !rawFallback,
                correctionEnabled && !rawFallback);
    }

    static final class CalibrationUiState {
        final boolean showCorrected;
        final boolean correctedEditable;
        final boolean liveUsesCorrected;
        final int correctedPaneWidth;
        final float correctedPaneWeight;
        final boolean copyRawMirror;

        CalibrationUiState(
                boolean showCorrected,
                boolean correctedEditable,
                boolean liveUsesCorrected) {
            this.showCorrected = showCorrected;
            this.correctedEditable = correctedEditable;
            this.liveUsesCorrected = liveUsesCorrected;
            correctedPaneWidth = showCorrected ? 0 : 1;
            correctedPaneWeight = showCorrected ? 1.0f : 0.0f;
            copyRawMirror = !showCorrected;
        }
    }

    static final class StartupOverlayPermissionFlow {
        private boolean foregroundAuthorizationStarted;
        private boolean foregroundAuthorizationFinished;
        private boolean overlayPermissionAttempted;
        private boolean overlaySettingsInFlight;

        void restore(boolean overlayAttempted, boolean overlayInFlight) {
            // RSA state belongs to the live process request, never a saved Activity flag.
            // Overlay attempts belong to this Activity flow, not restorable app preferences.
            foregroundAuthorizationStarted = false;
            foregroundAuthorizationFinished = false;
            overlayPermissionAttempted = overlayAttempted;
            overlaySettingsInFlight = overlayAttempted && overlayInFlight;
        }

        void reconcileAuthorization(boolean started, boolean pending) {
            foregroundAuthorizationStarted = started;
            foregroundAuthorizationFinished = started && !pending;
        }

        boolean beginStartupOverlayPermissionRequest(
                boolean permissionGranted, boolean anotherStepActive) {
            if (permissionGranted || anotherStepActive || foregroundAuthorizationInFlight()
                    || overlayPermissionAttempted || overlaySettingsInFlight) return false;
            overlayPermissionAttempted = true;
            overlaySettingsInFlight = true;
            return true;
        }

        boolean beginManualOverlayPermissionRequest(
                boolean permissionGranted, boolean anotherStepActive) {
            if (permissionGranted || anotherStepActive || overlaySettingsInFlight) return false;
            overlayPermissionAttempted = true;
            overlaySettingsInFlight = true;
            return true;
        }

        boolean finishOverlaySettings() {
            if (!overlaySettingsInFlight) return false;
            overlaySettingsInFlight = false;
            return true;
        }

        boolean canPresentStartupDialogs(boolean permissionGranted) {
            return !overlaySettingsInFlight && (permissionGranted || overlayPermissionAttempted);
        }

        boolean foregroundAuthorizationStarted() { return foregroundAuthorizationStarted; }
        boolean foregroundAuthorizationFinished() { return foregroundAuthorizationFinished; }
        boolean foregroundAuthorizationInFlight() {
            return foregroundAuthorizationStarted && !foregroundAuthorizationFinished;
        }
        boolean overlayPermissionAttempted() { return overlayPermissionAttempted; }
        boolean overlaySettingsInFlight() { return overlaySettingsInFlight; }
    }

    static final class HelperCallbackRegistration<T> {
        static final class Operation<T> {
            final T connection;
            final long generation;

            Operation(T connection, long generation) {
                this.connection = connection;
                this.generation = generation;
            }
        }

        private boolean desired;
        private T connection;
        private long issuedGeneration;
        private long desiredGeneration;
        private long completedGeneration;
        private boolean registrationFailed;

        synchronized T start() {
            if (desired && !registrationFailed) return null;
            desired = true;
            registrationFailed = false;
            return connection;
        }

        synchronized Operation<T> stop() {
            if (!desired) return null;
            desired = false;
            completedGeneration = 0;
            return connection == null || desiredGeneration <= 0
                    ? null : new Operation<>(connection, desiredGeneration);
        }

        synchronized T connected(T value) {
            if (value == null) throw new IllegalArgumentException("helper is null");
            connection = value;
            desiredGeneration = 0;
            completedGeneration = 0;
            registrationFailed = false;
            return desired ? value : null;
        }

        synchronized void disconnected(T value) {
            if (connection != value) return;
            connection = null;
            desiredGeneration = 0;
            completedGeneration = 0;
            registrationFailed = false;
        }

        synchronized Operation<T> queue(T value, long generation) {
            if (!desired || connection != value) return null;
            if (generation <= 0 || generation <= issuedGeneration) {
                throw new IllegalArgumentException("new callback generation required");
            }
            issuedGeneration = generation;
            desiredGeneration = generation;
            completedGeneration = 0;
            registrationFailed = false;
            return new Operation<>(value, generation);
        }

        synchronized boolean registered(Operation<T> operation) {
            if (!desired || operation == null || connection != operation.connection
                    || desiredGeneration != operation.generation) return false;
            completedGeneration = operation.generation;
            registrationFailed = false;
            return true;
        }

        synchronized boolean failed(Operation<T> operation) {
            if (!isCurrent(operation)) return false;
            completedGeneration = 0;
            registrationFailed = true;
            return true;
        }

        synchronized boolean isCurrent(Operation<T> operation) {
            return desired && operation != null && connection == operation.connection
                    && desiredGeneration == operation.generation;
        }

        synchronized void detached(Operation<T> operation) {
            if (operation == null || connection != operation.connection) return;
            if (completedGeneration == operation.generation) completedGeneration = 0;
            if (!desired && desiredGeneration == operation.generation) {
                desiredGeneration = 0;
            }
        }

        synchronized boolean registered() {
            return completedGeneration > 0;
        }
    }

    static final class PreviewFreshnessGate {
        private int requestId;
        private int[] surfaceGenerations;

        void arm(int requestId, int[] surfaceGenerations) {
            if (requestId <= 0 || !validInputGenerations(surfaceGenerations)) {
                throw new IllegalArgumentException("camera input identity required");
            }
            this.requestId = requestId;
            this.surfaceGenerations = surfaceGenerations.clone();
        }

        void clear() {
            requestId = 0;
            surfaceGenerations = null;
        }

        boolean accept(int requestId, int[] generations) {
            return this.requestId > 0 && this.requestId == requestId
                    && java.util.Arrays.equals(surfaceGenerations, generations);
        }

        private static boolean validInputGenerations(int[] generations) {
            if (generations == null || generations.length == 0) return false;
            for (int generation : generations) if (generation <= 0) return false;
            return true;
        }
    }

    static boolean shouldResumeActivityCameraRecovery(
            boolean pending, boolean resumed, boolean shellAvailable) {
        return pending && resumed && shellAvailable;
    }

    private boolean isCurrentActivityCameraTerminalEvent(
            int eventRequestId, JSONObject event) {
        if (!requestedOpen) {
            return matchesActivityCameraClose(event)
                    || (!event.optString("error").isEmpty()
                    && matchesActivityCameraCloseFailure(event));
        }
        if (activeActivityCameraRequestId <= 0
                || activeActivityCameraRequestId != eventRequestId) return false;
        if (!isMatchingActivityCameraShellEpoch(
                activityCameraShellEpoch, event.optLong("camera_shell_epoch", 0))) {
            return false;
        }
        if ("stock_avm_shell".equals(event.optString("renderer"))) {
            return isMatchingCurrentAvmShellEpoch(event);
        }
        return true;
    }

    static boolean shouldHandleReverseCameraStopped(
            boolean restoreIntent, int activeRequestId, int eventRequestId) {
        return restoreIntent && activeRequestId > 0 && eventRequestId == activeRequestId;
    }

    private void clearPreview(String reason) {
        activeReversePreviewSourceIndexes = new int[0];
        activeReversePreviewDirectGenerations = new int[0];
        if (activePreview == cameraPreview) cancelProductionPreviewFirstFrameWait();
        if (activePreview == reverseCameraPreview && reverseCameraPreview != null) {
            stopReverseCalibrationCopies(true);
            reverseCameraPreview.clearFrames();
            clearReversePanoramaStatus();
        }
        calibrationPreviewFreshness.clear();
        reversePreviewFreshness.clear();
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
        if (cameraPreviewCover != null) cameraPreviewCover.setVisibility(View.VISIBLE);
        if (calibrationPreviewCover != null) {
            calibrationPreviewCover.setVisibility(View.VISIBLE);
        }
        if (calibrationRawMirrorCover != null) {
            calibrationRawMirrorCover.setVisibility(View.VISIBLE);
        }
        if (calibrationCorrectedMirrorCover != null) {
            calibrationCorrectedMirrorCover.setVisibility(View.VISIBLE);
        }
        if (directCameraPreviewCover != null) {
            directCameraPreviewCover.setVisibility(View.VISIBLE);
        }
        if (debugPreviewCover != null) debugPreviewCover.setVisibility(View.VISIBLE);
        record("preview_cleared", "reason", reason, "ok", true, "error", "");
    }

    private void armProductionPreviewFirstFrame(int requestId) {
        productionPreviewFreshness.arm(requestId, activeActivityInputGenerations);
        productionPreviewFrameUpdates = 0;
        productionPreviewAwaitingFrame = true;
        if (cameraPreviewCover != null) cameraPreviewCover.setVisibility(View.VISIBLE);
        mainHandler.removeCallbacks(productionPreviewFirstFrameTimeout);
        mainHandler.postDelayed(
                productionPreviewFirstFrameTimeout,
                CAMERA_PREVIEW_FIRST_FRAME_TIMEOUT_MS);
    }

    private void cancelProductionPreviewFirstFrameWait() {
        productionPreviewFreshness.clear();
        productionPreviewAwaitingFrame = false;
        productionPreviewFrameUpdates = 0;
        mainHandler.removeCallbacks(productionPreviewFirstFrameTimeout);
    }

    private void armReversePreviewFrames(int requestId) {
        if (reverseCameraPreview == null
                || pendingReversePreviewRequestId != requestId
                || pendingReversePreviewGenerations == null) return;
        int[] generations = pendingReversePreviewGenerations;
        reversePreviewFreshness.arm(requestId, generations);
        reverseCameraPreview.armPreviewFrames(requestId, generations);
        if (reversePreviewBackgroundFailureRequestId == requestId) {
            reverseCameraPreview.markPreviewBackgroundUnavailable(requestId);
        }
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
    }

    private void handleProductionPreviewFirstFrameTimeout() {
        if (!shouldRetryProductionPreviewFrame(
                selectedTab == TAB_CAMERAS, activePreview == cameraPreview,
                requestedOpen, productionPreviewAwaitingFrame)) return;
        record("camera_preview_first_frame_timeout", productionPreviewFrameFields(
                activeActivityCameraRequestId, activeActivityCameraProfile,
                activeDirectCameraIndex, productionPreviewFrameUpdates,
                true, !productionPreviewRetryUsed));
        if (!productionPreviewRetryUsed) {
            productionPreviewRetryUsed = true;
            closeCameraForTransition("production_first_frame_timeout");
            return;
        }
        closeCamera("production_first_frame_timeout_final");
        record("camera_status", "profile", selectedProductionProfile() == null
                        ? "unknown" : selectedProductionProfile().toString(),
                "text", "Кадр камери не отримано", "tone", StatusTone.Error.name(),
                "pending", false);
    }

    static boolean shouldRetryProductionPreviewFrame(
            boolean camerasTab, boolean activePreview, boolean requestedOpen,
            boolean awaitingFrame) {
        return camerasTab && activePreview && requestedOpen && awaitingFrame;
    }

    /** Diagnostic identity follows the opened request, never the currently selected Blind tab. */
    static Object[] productionPreviewFrameFields(
            int requestId, CameraProfileId profile, int directIndex, int frameUpdates,
            boolean timeout, boolean retry) {
        CameraProfile blind = profile instanceof CameraProfileId.Blind
                ? blindProfile((CameraProfileId.Blind) profile) : null;
        String profileName = profile == null ? "unknown" : profile.toString();
        String name = blind == null ? profileName : blind.wireName;
        Integer index = blind != null ? Integer.valueOf(blind.previewIndex)
                : directIndex >= 0 ? Integer.valueOf(directIndex) : null;
        // record() omits null values through JSONObject.put, retaining camera_id only for Blind.
        return new Object[]{
                "request_id", requestId, "profile", profileName,
                "preview_index", index, "camera_id", blind == null ? null : blind.id,
                "frame_updates", timeout ? null : frameUpdates,
                "status", timeout ? "No frame received for " + name : "Showing " + name,
                "retry", timeout ? retry : null};
    }

    private void showPreview(
            View target, View cover, boolean cropLeft, boolean cropRight) {
        if (cover != null) cover.setVisibility(View.VISIBLE);
        boolean crop = cropLeft || cropRight;
        target.setPivotX(cropRight ? target.getWidth() : 0.0f);
        target.setPivotY(0.0f);
        target.setScaleX(crop ? 2.2f : 1.0f);
        target.setScaleY(crop ? 2.0f : 1.0f);
    }

    private void applyDebugPreviewMode() {
        if (debugOutput != null) debugOutput.refreshGeometry();
        // Keep the existing TS-only corner presentation outside the Sea Lion BYD correction.
        if (debugPreview != null && productionUi != null
                && "TS".equals(productionUi.getState().getDebug().getAvmRenderer())) {
            float startX = preferences.getBoolean("debug_avm_show_raw", true) ? 0f
                    : StockAvmPreview.focusedTileStartX(activeCameraViewpoint);
            debugPreview.setPivotX(debugPreview.getWidth());
            debugPreview.setPivotY(0f);
            debugPreview.setScaleX(startX > 0f ? 1f / (1f - startX) : 1f);
            debugPreview.setScaleY(1f);
        }
    }

    private boolean debugAvmModeAvailable(int viewpoint) {
        if (productionUi != null && "TS".equals(productionUi.getState().getDebug().getAvmRenderer()))
            return true;
        boolean available = BydAvmDisplayContract.nativeView(
                StockAvmPreview.layoutName(viewpoint), debugHorizontal) != null;
        if (!available) record("diagnostic_mode_rejected", "viewpoint", viewpoint,
                "orientation", debugHorizontal ? "horizontal" : "vertical", "reason", "support_deferred");
        return available;
    }

    private void updateControls() {
        boolean manualAllowed = manualDiagnosticsAllowed();
        if (productionUi != null) {
            productionUi.setLegacyRuntimeBlocked(legacyRuntimeBlocked);
            productionUi.setManualDiagnostics(manualAllowed, manualSignalStatus);
        }
    }

    private boolean manualDiagnosticsAllowed() {
        return manualDiagnosticsAllowed(helper != null, telemetryReady, manualGearPark,
                preferences.getBoolean("guard_enabled", false), manualTurnRequestPending,
                legacyRuntimeBlocked || settingsTransferInProgress || settingsReloadPending
                        || shutdownRequested);
    }

    static boolean manualDiagnosticsAllowed(boolean helperReady, boolean telemetryReady,
            boolean gearPark, boolean guardEnabled, boolean pending, boolean blocked) {
        return helperReady && telemetryReady && gearPark && !guardEnabled && !pending && !blocked;
    }

    static int manualSignalPayload(CommandId command) {
        if (command == CommandId.SignalLeft) return 2;
        if (command == CommandId.SignalRight) return 3;
        if (command == CommandId.SignalHazard) return 1;
        if (command == CommandId.SignalReset) return 0;
        return -1;
    }

    static boolean shouldEnableManualAdbAuthorization(
            boolean uiReady,
            boolean authorizationPending,
            LocalAdbClient.PromptMode mode) {
        return uiReady;
    }

    static boolean shouldRetryCalibrationCopy(boolean active, int width, int height) {
        return active && (width <= 0 || height <= 0);
    }

    static boolean isOverlayCameraEvent(String kind, String owner) {
        return (CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(owner)
                || CameraHelperMain.CAMERA_OWNER_PARKING.equals(owner)
                || CameraHelperMain.CAMERA_OWNER_MIRROR.equals(owner)
                || CameraHelperMain.CAMERA_OWNER_REVERSE.equals(owner))
                && ("camera_opened".equals(kind)
                        || "camera_error".equals(kind)
                        || "camera_closed".equals(kind));
    }

    private void recordIgnoredActivityCameraEvent(
            String kind, JSONObject event) {
        record("activity_camera_event_ignored", "event_kind", kind,
                "camera_owner", event.optString("camera_owner"),
                "request_id", event.optInt("request_id", 0),
                "consumer_generation", event.optInt("consumer_generation", 0),
                "active_request_id", activeActivityCameraRequestId,
                "active_consumer_generation", activeActivityConsumerGeneration,
                "event_source", event.optString("source"));
    }

    static boolean isCurrentActivityCameraEvent(
            boolean requestedOpen, int activeRequestId, int eventRequestId, String source) {
        return requestedOpen && "helper".equals(source)
                && activeRequestId > 0 && eventRequestId == activeRequestId;
    }

    /** Accepts only a new shell attachment, or a same-epoch reattach after a recorded death. */
    static boolean shouldAcceptActivityCameraShellAttach(
            long currentEpoch, long eventEpoch, boolean shellAvailable) {
        // A shell identity is established by a positive attach epoch.  Reattach always means a
        // new shell instance; accepting the dead epoch would reopen stale surfaces.
        return eventEpoch > 0 && (currentEpoch <= 0 || eventEpoch > currentEpoch);
    }

    /** A shell death is terminal once per epoch; older and duplicate deaths are ignored. */
    static boolean shouldAcceptActivityCameraShellDeath(
            long currentEpoch, long eventEpoch, long lastDeathEpoch,
            boolean deathHandled) {
        return currentEpoch > 0 && eventEpoch == currentEpoch
                && eventEpoch != lastDeathEpoch && !deathHandled;
    }

    static boolean isMatchingActivityCameraShellEpoch(
            long currentEpoch, long eventEpoch) {
        return currentEpoch <= 0 || eventEpoch <= 0 || eventEpoch == currentEpoch;
    }

    static boolean isMatchingActivityCameraShellEpochStrict(
            long currentEpoch, long eventEpoch) {
        return currentEpoch > 0 && eventEpoch > 0 && eventEpoch == currentEpoch;
    }

    static boolean isMatchingActivityAvmShellEpoch(
            long currentEpoch, long eventEpoch) {
        return currentEpoch > 0 && eventEpoch > 0 && eventEpoch == currentEpoch;
    }

    private boolean isMatchingCurrentAvmShellEpoch(JSONObject event) {
        return isMatchingActivityAvmShellEpoch(
                activityAvmShellEpoch, event.optLong("avm_shell_epoch", 0));
    }

    private boolean isCurrentActivityCameraEvent(
            boolean requested, int activeRequestId, JSONObject event) {
        return isCurrentActivityCameraEvent(
                requested, activeRequestId, event.optInt("request_id", 0),
                event.optString("source"))
                && isMatchingActivityCameraShellEpoch(
                        activityCameraShellEpoch,
                        event.optLong("camera_shell_epoch", 0));
    }

    static boolean isMatchingStockShellCloseError(
            String renderer, String stage,
            int closingRequestId, int eventRequestId) {
        return "stock_avm_shell".equals(renderer) && "close".equals(stage)
                && closingRequestId > 0 && closingRequestId == eventRequestId;
    }

    static boolean isIntermediateCameraClose(String reason) {
        return "preview_handoff".equals(reason)
                || (reason != null && reason.startsWith("replace_")
                        && !"replace_with_multi_preview".equals(reason));
    }

    static boolean isInvalidStockSurfaceError(
            String renderer, String stage, String error) {
        String combined = (renderer == null ? "" : renderer) + " "
                + (stage == null ? "" : stage) + " "
                + (error == null ? "" : error);
        String lower = combined.toLowerCase(Locale.US);
        return lower.contains("stock_avm")
                && lower.contains("surface")
                && (lower.contains("invalid") || lower.contains("get_camera_input_surface"));
    }

    static boolean shouldRearmStockSurfaceRecovery(String kind, String renderer) {
        return "camera_opened".equals(kind) && "stock_avm_shell".equals(renderer);
    }

    private void createLogFile() {
        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        File captures = new File(base, "captures");
        if (!captures.isDirectory() && !captures.mkdirs()) captures = getFilesDir();
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        logFile = new File(captures, "guard-camera-" + stamp + ".jsonl");
    }

    private void record(String kind, Object... fields) {
        if (!DiagnosticLogPolicy.shouldPersist(kind)) return;
        try {
            JSONObject json = new JSONObject();
            json.put("kind", kind);
            json.put("source", "activity");
            json.put("wall_time", new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(new Date()));
            json.put("t_ms", SystemClock.elapsedRealtime());
            for (int i = 0; i + 1 < fields.length; i += 2) {
                json.put(String.valueOf(fields[i]), fields[i + 1]);
            }
            AsyncServiceLog log = activityLog;
            if (log != null) log.appendRaw(json.toString());
        } catch (Throwable error) {
            Log.e(TAG, "Unable to record event " + kind, error);
        }
    }

    private void writeLine(String line) {
        AsyncServiceLog log = activityLog;
        if (log != null) log.appendRaw(line);
    }


    int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static final class Thresholds {
        final float outward;
        final float center;

        Thresholds(float outward, float center) {
            this.outward = outward;
            this.center = center;
        }
    }
}

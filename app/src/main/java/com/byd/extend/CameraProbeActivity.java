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
import com.byd.extend.ui.NumberTarget;
import com.byd.extend.ui.BlindNumber;
import com.byd.extend.ui.GuardNumber;
import com.byd.extend.ui.HeaderUiState;
import com.byd.extend.ui.OutputNumber;
import com.byd.extend.ui.ParkingNumber;
import com.byd.extend.ui.ProfileNumber;
import com.byd.extend.ui.ReverseElement;
import com.byd.extend.ui.ReverseGeometryNumber;
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

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraProbeActivity extends ComponentActivity
        implements SurfaceHolder.Callback, BlindSpotCameraView.Callback,
        ReverseCameraCompositionView.Callback, ProductionUiBackend {
    private static final String TAG = "BydExtend";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final int LOCATION_PERMISSION_REQUEST = 11;
    private static final int CAMERA_PRESET_REQUEST = 12;
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
    private static final String STATE_WEATHER_PERMISSION_PENDING =
            "weather_permission_pending";
    private static final String STATE_WEATHER_ENABLE_REQUESTED =
            "weather_enable_requested";
    private static final String STATE_WEATHER_REFRESH_AFTER_PERMISSION =
            "weather_refresh_after_permission";
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

    static String[] calibrationLabels(boolean parking) {
        return (parking ? PARKING_CALIBRATION_LABELS : BLIND_CALIBRATION_LABELS).clone();
    }

    static String calibrationLabel(boolean parking, int logicalId) {
        String[] labels = parking ? PARKING_CALIBRATION_LABELS : BLIND_CALIBRATION_LABELS;
        if (logicalId < 0 || logicalId >= labels.length) {
            throw new IllegalArgumentException("invalid calibration camera");
        }
        return labels[logicalId];
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

    static void publishReverseSteeringButtonCaptured() {
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
                snapshot.activity.productionUi.dismissReverseButtonCaptureDialog();
                snapshot.activity.productionUi.reload();
            }
        });
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

    static CalibrationEntry calibrationEntry(int originTab, boolean parking, int logicalId) {
        int normalizedOrigin = originTab == TAB_PARKING_CAMERAS
                ? TAB_PARKING_CAMERAS : TAB_CAMERAS;
        int normalizedId = parking
                ? (ParkingCameraProfile.isValid(logicalId)
                        ? logicalId : ParkingCameraProfile.FL)
                : (CameraProfile.isValid(logicalId)
                        ? logicalId : CameraProfile.REAR_LEFT);
        return new CalibrationEntry(normalizedOrigin, parking, normalizedId);
    }

    static boolean calibrationNeedsIdentityRebind(
            boolean calibrationPreviewOpen,
            boolean activeParking, int activeLogicalId, int activePhysicalIndex,
            boolean requestedParking, int requestedLogicalId, int requestedPhysicalIndex) {
        return calibrationPreviewOpen && requestedPhysicalIndex >= 0
                && (activeParking != requestedParking
                || activeLogicalId != requestedLogicalId
                || activePhysicalIndex != requestedPhysicalIndex);
    }

    static int migrateStoredTab(int tab) {
        return tab == TAB_CAMERA_CALIBRATION ? TAB_CAMERAS : tab;
    }

    private final ExecutorService ipcExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService logExportExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AppUpdateManager updateManager = new AppUpdateManager();
    private final CameraTransition cameraTransition = new CameraTransition();
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
    private final Button[] viewButtons = new Button[4];
    private final Button[] stockAvmButtons = new Button[2];
    private final Button[] horizontalLayoutButtons =
            new Button[StockAvmPreview.horizontalLayoutCount()];
    private final Button[] directCameraIndexButtons = new Button[5];
    private final Button[] turnStateButtons = new Button[4];

    private SharedPreferences preferences;
    private ProductionUiController productionUi;
    private long reverseOwnerToken;
    private boolean reverseOwnerPresent;
    private final EnumMap<CameraHostKind, View> productionCameraHosts =
            new EnumMap<>(CameraHostKind.class);
    private final EnumMap<CameraHostKind, CameraHostSlot> productionCameraSlots =
            new EnumMap<>(CameraHostKind.class);
    /** Compose receives these roots; camera state always stays on the child owner views. */
    private FrameLayout productionPlacementHost;
    private CropMaskView productionPlacementCropMask;
    private FrameLayout productionDirectHost;
    private FrameLayout productionAvmHost;
    private Switch guardSwitch;
    private Switch cameraSwitch;
    private Switch rearSharpTurnSwitch;
    private Switch rearBsdOnlySwitch;
    private Switch frontCameraSwitch;
    private Switch frontTurnRequiredSwitch;
    private EditText outwardInput;
    private EditText centerInput;
    private EditText correctionDelayInput;
    private EditText maxSpeedInput;
    private EditText cameraMinSpeedInput;
    private EditText cameraMaxSpeedInput;
    private EditText rearSharpTurnAngleInput;
    private EditText frontCameraMinSpeedInput;
    private EditText frontCameraMaxSpeedInput;
    private EditText frontCameraMinAngleInput;
    private Spinner cameraWarningModeInput;
    private SeekBar cameraScaleInput;
    private TextView cameraScaleValue;
    private FrameLayout cameraPositionWidget;
    private FrameLayout cameraPositionHost;
    private Button cameraLeftPositionButton;
    private Button cameraRightPositionButton;
    private Button cameraRearGroupButton;
    private Button cameraFrontGroupButton;
    private View rearCameraControlPane;
    private View frontCameraControlPane;
    private Button cameraTabletTargetButton;
    private Button cameraClusterTargetButton;
    private TextView guardStatus;
    private TextView cameraStatus;
    private TextView rearCameraPolicyStatus;
    private TextView frontCameraPolicyStatus;
    private TextView debugCameraStatus;
    private TextView directCameraStatus;
    private TextView calibrationStatus;
    private TextView calibrationResultTitle;
    private TextView reverseCameraStatus;
    private TextView debugLayoutTitle;
    private TextView activationCount;
    private TextView correctionCount;
    private long lifetimeActivations;
    private long lifetimeCorrections;
    private BlindSpotCameraView cameraPreview;
    private FrameLayout cameraPreviewHost;
    private View cameraPreviewFrame;
    private View cameraPreviewCover;
    private SurfaceView debugPreview;
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
    private CameraProbeSettingsPanel settingsPanel;
    private CameraProbeMusicPanel musicPanel;
    private CameraProbeWeatherPanel weatherPanel;
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
    private boolean manualGearPark;
    private boolean manualTurnRequestPending;
    private StatusUiState manualSignalStatus = new StatusUiState("", StatusTone.Neutral, false);
    private boolean adbAuthPending;
    private LocalAdbClient.PromptMode adbAuthMode;
    private boolean adbAuthorizationRequested;
    private boolean adbAuthorizationStartScheduled;
    private boolean cameraPermissionPending;
    private boolean weatherRefreshAfterPermission;
    private boolean weatherLocationPermissionPending;
    private boolean weatherLocationPermissionStartScheduled;
    private boolean weatherLocationPermissionInFlight;
    private boolean weatherEnableRequestedForPermission;
    private long weatherRefreshUiGeneration;
    private boolean backgroundStartSettingsRequired;
    private boolean backgroundStartSettingsActive;
    private boolean backgroundStartSettingsStartScheduled;
    private boolean helperBound;
    private boolean shutdownRequested;
    private boolean activityResumed;
    private boolean activityStarted;
    private boolean activityDestroyed;
    private final LocalAdbClient.AccessStateListener adbAccessListener = state ->
            mainHandler.post(() -> {
                if (activityStarted && !activityDestroyed) {
                    refreshProductionHeader();
                    advanceStartupAuthorizationFlow();
                }
            });
    private boolean updateCheckInFlight;
    private boolean logExportInProgress;
    private boolean compatibilityExportInProgress;
    private volatile CompatibilityBundleExporter.ExportControl compatibilityExportControl;
    private AlertDialog compatibilityExportProgressDialog;
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
    private int calibrationOriginTab = TAB_CAMERAS;
    private boolean calibrationParkingMode;
    private CameraProfileId calibrationHostProfile;
    private Integer transientCornerRadiusDp;
    private Integer transientTransparencyPercent;
    private CameraProfileId transientProfilePreviewId;
    private Integer transientProfilePreviewFov;
    private Integer transientProfilePreviewRotation;
    private int selectedParkingCameraId = ParkingCameraProfile.FL;
    private boolean parkingUiUpdating;
    private final float[] parkingCameraX = new float[ParkingCameraProfile.COUNT];
    private final float[] parkingCameraY = new float[ParkingCameraProfile.COUNT];
    private final int[] parkingCameraScale = new int[ParkingCameraProfile.COUNT];
    private EditText parkingDistanceInput;
    private EditText parkingMaxSpeedInput;
    private Switch parkingCameraSwitch;
    private Switch parkingAddCentralSwitch;
    private Switch parkingAllowDuringReverseSwitch;
    private Switch parkingScaleSyncSwitch;
    private SeekBar parkingScaleInput;
    private TextView parkingScaleValue;
    private FrameLayout parkingPositionHost;
    private FrameLayout parkingPositionWidget;
    private FrameLayout parkingPositionFrame;
    private TextView parkingPositionHandle;
    private Button parkingCalibrationButton;
    private Button parkingEnableAllButton;
    private Button parkingDisableAllButton;
    private Button cameraCalibrationButton;
    private Button calibrationBackButton;
    private boolean calibrationCopyPending;
    private final float[] cameraX = new float[CameraProfile.COUNT];
    private final float[] cameraY = new float[CameraProfile.COUNT];
    private final int[] cameraScale = new int[CameraProfile.COUNT];
    private final int[] cameraTarget = new int[CameraProfile.COUNT];
    private float dragStartRawX;
    private float dragStartRawY;
    private float dragStartX;
    private float dragStartY;
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
    private AlertDialog updateDialog;
    private AlertDialog updateProgressDialog;
    private final Runnable finishCameraHandoff = this::openPendingStockAvm;
    private final Runnable productionPreviewFirstFrameTimeout =
            this::handleProductionPreviewFirstFrameTimeout;
    private final Runnable copyCalibrationFrame = this::copyCalibrationFrame;
    private final Runnable copyReverseCalibrationFrame = this::copyReverseCalibrationFrame;
    private final Runnable runStartupUpdateCheck = this::runStartupUpdateCheck;
    private final Runnable startBackgroundStartSettings = () -> {
        backgroundStartSettingsStartScheduled = false;
        if (shouldOpenBackgroundStartSettings(
                GuardRecovery.isAutoStartEnabled(this), cameraPermissionPending,
                hasWindowFocus(), backgroundStartSettingsRequired,
                backgroundStartSettingsActive, adbAuthPending)) {
            openBackgroundStartSettings("first_run");
        }
    };
    private final Runnable startForegroundAdbAuthorization = () -> {
        adbAuthorizationStartScheduled = false;
        if (weatherLocationPermissionPending || weatherLocationPermissionInFlight) {
            advanceStartupAuthorizationFlow();
            return;
        }
        if (shouldStartForegroundAdbAuthorization(cameraPermissionPending,
                backgroundStartSettingsPending(), hasWindowFocus(),
                helper != null || legacyRuntimeBlocked, adbAuthPending,
                adbAuthorizationRequested)) {
            requestAdbAuthorization(
                    "adb_authorization_foreground_start",
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
                || settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress || adbAuthPending) {
            advanceStartupAuthorizationFlow();
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
            adbAuthPending = false;
            adbAuthMode = null;
            adbAuthorizationRequested = false;
            cancelPendingForegroundAdbAuthorization();
            telemetryReady = false;
            manualGearPark = false;
            manualTurnRequestPending = false;
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
            closingActivityCameraRequestId = 0;
            activityClosePending = false;
            activityColdResetInFlight = false;
            activeReverseControllerRequestId = 0;
            record("helper_service_disconnected");
            updateControls();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        preferences = getSharedPreferences("settings", MODE_PRIVATE);
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        migrateCameraFrameAspects();
        backgroundStartSettingsRequired = GuardRecovery.isAutoStartEnabled(this)
                && !preferences.getBoolean(PREF_BACKGROUND_START_SETTINGS_SHOWN, false);
        lifetimeActivations = preferences.getLong("activation_count", 0);
        lifetimeCorrections = preferences.getLong("correction_count", 0);
        createLogFile();
        activityLog = new AsyncServiceLog(() -> logFile, 250L);
        boolean clearedShutdown = GuardRecovery.isUserShutdownActive(this);
        productionUi = new ProductionUiController(preferences, this, this);
        selectedTab = rootTabToLegacy(productionUi.getState().getActiveTab());
        ProductionUiInstaller.install(this, productionUi);
        // Restore the persisted camera tab once its Compose mapping is known.  The intent is
        // transient and deliberately not armed for an explicit Shutdown flow.
        if (!shutdownRequested && isAutoPreviewTab(selectedTab)) armResumeAutoPreview();
        verifyMappings();
        acceptDiagnosticIntent(getIntent());

        record("activity_start", "log_path", logFile.getAbsolutePath(),
                "shutdown_cleared", clearedShutdown,
                "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "background_start_settings_required", backgroundStartSettingsRequired);
        cameraPermissionPending = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        restoreWeatherPermissionState(savedInstanceState);
        initializeStartupWeatherPermissionState();
        startAndBindHelperService();

        if (cameraPermissionPending) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            record("camera_permission", "granted", true);
        }
        updateControls();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(STATE_WEATHER_PERMISSION_IN_FLIGHT,
                weatherLocationPermissionInFlight);
        outState.putBoolean(STATE_WEATHER_PERMISSION_PENDING,
                weatherLocationPermissionPending);
        outState.putBoolean(STATE_WEATHER_ENABLE_REQUESTED,
                weatherEnableRequestedForPermission);
        outState.putBoolean(STATE_WEATHER_REFRESH_AFTER_PERMISSION,
                weatherRefreshAfterPermission);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        acceptDiagnosticIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        activityStarted = true;
        publishReverseOwnerPresence();
        LocalAdbClient.setAccessStateListener(adbAccessListener);
        refreshProductionHeader();
        invalidStockSurfaceRetryUsed = false;
        CameraHelperService.activityOpened(this);
        if (!helperBound) startAndBindHelperService();
        enqueueHelperCallbackRegistration(helperCallbackRegistration.start());
    }

    @Override
    protected void onResume() {
        super.onResume();
        legacyRuntimeBlocked = LegacySettingsImporter.blocksRuntime(this);
        updateControls();
        refreshProductionHeader();
        if (!helperBound) startAndBindHelperService();
        activityResumed = true;
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
        showCachedUpdateIfAvailable();
        scheduleStartupUpdateCheck();
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
        publishReverseOwnerIneligible();
        cancelReverseButtonLearningIfVisible();
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(true);
        mainHandler.removeCallbacks(runStartupUpdateCheck);
        cancelPendingBackgroundStartSettings();
        cancelPendingWeatherLocationPermission();
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            advanceStartupAuthorizationFlow();
        } else {
            cancelPendingBackgroundStartSettings();
            cancelPendingForegroundAdbAuthorization();
            cancelPendingWeatherLocationPermission();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            cameraPermissionPending = false;
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            record("camera_permission", "granted", granted);
            if (!granted) publishSettingsFeedback(
                    runtimeText(R.string.runtime_camera_permission_missing), StatusTone.Error);
            advanceStartupAuthorizationFlow();
            updateControls();
            maybeOpenProductionPreview();
            maybeOpenCalibrationCamera();
            maybeOpenReversePreview();
        } else if (requestCode == LOCATION_PERMISSION_REQUEST) {
            weatherLocationPermissionInFlight = false;
            if (weatherPanel != null) weatherPanel.setLocationPermissionPending(false);
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
                preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, false).apply();
            } else if (shouldEnableWeatherAfterPermission(granted, enableRequested)) {
                preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, true).apply();
            }
            CameraHelperService.weatherSettingsChanged(this);
            if (productionUi != null) productionUi.reload();
            if (!granted) {
                Toast.makeText(this, runtimeText(R.string.runtime_weather_disabled),
                        Toast.LENGTH_LONG).show();
            } else if (shouldRefreshWeatherAfterPermission(
                    granted, weatherRefreshAfterPermission, weatherStillEnabled)) {
                weatherRefreshAfterPermission = false;
                requestWeatherRefresh();
            } else {
                weatherRefreshAfterPermission = false;
            }
            refreshProductionHeader();
            advanceStartupAuthorizationFlow();
            updateControls();
        }
    }

    @Override
    protected void onStop() {
        activityStarted = false;
        clearReverseOwnerPresence();
        LocalAdbClient.clearAccessStateListener(adbAccessListener);
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
            if (shouldPersistEditableSettings(shutdownRequested,
                    settingsTransferInProgress, settingsReloadPending)) {
                saveEditableTriggerSettings();
            }
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
        cancelReverseButtonLearningIfVisible();
        clearReverseOwnerPresence();
        activityDestroyed = true;
        LocalAdbClient.clearAccessStateListener(adbAccessListener);
        cancelPendingWeatherLocationPermission();
        CompatibilityBundleExporter.ExportControl exportControl =
                compatibilityExportControl;
        if (exportControl != null) exportControl.cancel();
        if (compatibilityExportProgressDialog != null) {
            compatibilityExportProgressDialog.dismiss();
            compatibilityExportProgressDialog = null;
        }
        resumeTabWarmup.clear();
        if (requestedOpen || cameraHandoffPending) closeCamera("activity_destroyed");
        releaseAllProductionCameraHosts();
        clearResumeAutoPreview();
        mainHandler.removeCallbacks(runStartupUpdateCheck);
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(true);
        detachHelperCallback();
        if (helperBound) {
            unbindService(helperConnection);
            helperBound = false;
        }
        ipcExecutor.shutdown();
        updateExecutor.shutdownNow();
        logExportExecutor.shutdownNow();
        if (activityLog != null) activityLog.close();
        if (updateDialog != null) updateDialog.dismiss();
        if (updateProgressDialog != null) updateProgressDialog.dismiss();
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

    private void scheduleStartupUpdateCheck() {
        mainHandler.removeCallbacks(runStartupUpdateCheck);
        long remainingMs = UpdateAutoCheckRuntime.remainingMs();
        if (remainingMs >= 0L) mainHandler.postDelayed(runStartupUpdateCheck, remainingMs);
    }

    private void runStartupUpdateCheck() {
        if (!activityResumed || activityDestroyed) return;
        if (!UpdateAutoCheckRuntime.consumeIfReady()) {
            scheduleStartupUpdateCheck();
            return;
        }
        if (updateCheckInFlight) return;
        runUpdateCheck(false);
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
        if (settingsPanel != null) settingsPanel.setSettingsTransferInProgress(true);
        publishSettingsFeedback(runtimeText(R.string.runtime_status_operation_started), StatusTone.Warning);
        publishSettingsOperation(operation, runtimeText(R.string.runtime_status_operation_started), StatusTone.Warning, true);
        cancelPendingForegroundAdbAuthorization();
        cancelPendingBackgroundStartSettings();
        updateControls();
        return true;
    }

    private void showSettingsTransferProgress(String message) {
        dismissSettingsTransferDialog();
        if (settingsPanel != null) settingsPanel.setTransferStatus(message);
        publishSettingsFeedback(message, StatusTone.Warning);
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
            if (settingsPanel != null) settingsPanel.setSettingsTransferInProgress(false);
            updateControls();
        }
    }

    private void reportSettingsTransferFailure(Throwable error) {
        record("settings_transfer_failed", "error", error.toString());
        SettingsOperation operation = activeSettingsOperation;
        finishSettingsTransfer();
        if (activityDestroyed || isFinishing()) return;
        String detail = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        String failure = runtimeText(R.string.runtime_settings_transfer_failed_detail, detail);
        if (settingsPanel != null) settingsPanel.setTransferStatus(failure);
        publishSettingsFeedback(failure, StatusTone.Error);
        if (operation != null) publishSettingsOperation(
                operation, failure, StatusTone.Error, false);
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

    private void exportCameraPreset() {
        if (!beginSettingsTransfer(SettingsOperation.Preset)) return;
        showSettingsTransferProgress(runtimeText(R.string.runtime_preset_export_progress));
        logExportExecutor.execute(() -> {
            try {
                File file = CameraPresetFiles.write(getCacheDir(),
                        CameraSettingsTransfer.exportCameraPreset(preferences));
                mainHandler.post(() -> {
                    finishSettingsTransfer();
                    if (activityDestroyed || isFinishing()) return;
                    if (settingsPanel != null) settingsPanel.setTransferStatus(
                            runtimeText(R.string.runtime_preset_ready));
                    publishSettingsFeedback(
                            runtimeText(R.string.runtime_preset_ready), StatusTone.Ok);
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

    @SuppressWarnings("deprecation")
    private void chooseCameraPreset() {
        if (!beginSettingsTransfer(SettingsOperation.Preset)) return;
        if (settingsPanel != null) settingsPanel.setTransferStatus(
                runtimeText(R.string.runtime_preset_choose));
        publishSettingsFeedback(runtimeText(R.string.runtime_preset_choose), StatusTone.Warning);
        try {
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*");
            startActivityForResult(open, CAMERA_PRESET_REQUEST);
        } catch (Exception error) {
            reportSettingsTransferFailure(error);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != CAMERA_PRESET_REQUEST) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishSettingsTransfer();
            String canceled = runtimeText(R.string.runtime_preset_load_canceled);
            if (settingsPanel != null) settingsPanel.setTransferStatus(
                    canceled);
            publishSettingsFeedback(canceled, StatusTone.Warning);
            return;
        }
        settingsTransferInProgress = true;
        if (settingsPanel != null) settingsPanel.setSettingsTransferInProgress(true);
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
            if (settingsPanel != null) settingsPanel.setTransferStatus(status);
            publishSettingsFeedback(status, StatusTone.Error);
            Toast.makeText(this, runtimeText(R.string.runtime_legacy_restore_failed),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            CameraHelperService.settingsReloaded(getApplicationContext(), false);
        } catch (Throwable reloadError) {
            record("legacy_access_restore_reload_failed", "error", reloadError.toString());
        }
        if (settingsPanel != null) {
            settingsPanel.setLegacyRestoreAvailable(false);
            settingsPanel.setServiceStatus(
                    runtimeText(R.string.runtime_legacy_restore_success));
            settingsPanel.setTransferStatus(
                    runtimeText(R.string.runtime_legacy_restore_success));
        }
        publishSettingsFeedback(
                runtimeText(R.string.runtime_legacy_restore_success), StatusTone.Ok);
        Toast.makeText(this, runtimeText(R.string.runtime_legacy_restore_success),
                Toast.LENGTH_LONG).show();
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
                mainHandler.post(() -> reportSettingsTransferFailure(error));
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
                    CameraSettingsTransfer.applyCameraPreset(preferences, values);
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
                    Toast.makeText(this, runtimeText(legacy ? R.string.runtime_imported_settings
                            : R.string.runtime_preset_loaded), Toast.LENGTH_LONG).show();
                    recreate();
                });
            } catch (Exception error) {
                mainHandler.post(() -> reportSettingsTransferFailure(error));
            }
        });
    }

    static boolean shouldPersistEditableSettings(
            boolean shutdown, boolean transferring, boolean reloading) {
        return !shutdown && !transferring && !reloading;
    }

    private void saveEditableTriggerSettings() {
        if (cameraMinSpeedInput != null && cameraMaxSpeedInput != null) saveRearCameraSpeedRange();
        if (rearSharpTurnAngleInput != null) saveRearTriggerPolicy();
        if (frontCameraMinSpeedInput != null && frontCameraMaxSpeedInput != null
                && frontCameraMinAngleInput != null) saveFrontCameraPolicy();
        if (parkingDistanceInput != null) saveParkingRule();
        if (parkingMaxSpeedInput != null) saveParkingMaxSpeed();
    }

    private void confirmDiagnosticLogShare() {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_logs_share_title))
                .setMessage(runtimeText(R.string.runtime_logs_share_message))
                .setNegativeButton(runtimeText(R.string.runtime_cancel), null)
                .setPositiveButton(runtimeText(R.string.runtime_create),
                        (dialog, which) -> startDiagnosticLogExport())
                .show();
    }

    private void startDiagnosticLogExport() {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        logExportInProgress = true;
        if (settingsPanel != null) settingsPanel.setLogExportInProgress(true);
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

    private void exportDiagnosticLogsAfterFlush() {
        if (!logExportInProgress) return;
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
        try {
            logExportExecutor.execute(() -> {
                File archive = null;
                Throwable failure = null;
                try {
                    DiagnosticLogExporter.Snapshot snapshot =
                            DiagnosticLogExporter.snapshot(this);
                    record("diagnostic_log_export", "state", "snapshot_ready",
                            "source_count", snapshot.sources.size());
                    archive = DiagnosticLogExporter.export(this, snapshot);
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
        logExportInProgress = false;
        if (!activityDestroyed && settingsPanel != null) {
            settingsPanel.setLogExportInProgress(false);
        }
        if (!activityDestroyed) advanceStartupAuthorizationFlow();
        if (error != null) {
            record("diagnostic_log_export", "state", "failed", "error", error.toString());
            publishSettingsOperation(SettingsOperation.Logs,
                    runtimeText(R.string.runtime_logs_failed), StatusTone.Error, false);
            if (activityResumed && !activityDestroyed) {
                Toast.makeText(this, runtimeText(R.string.runtime_logs_failed), Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, runtimeText(R.string.runtime_share_menu_failed), Toast.LENGTH_LONG).show();
        }
    }

    private void runUpdateCheck(boolean force) {
        if (updateCheckInFlight || activityDestroyed) return;
        updateCheckInFlight = true;
        publishSettingsOperation(SettingsOperation.Update,
                runtimeText(R.string.runtime_update_checking), StatusTone.Warning, true);
        if (settingsPanel != null) settingsPanel.setUpdateButton(
                runtimeText(R.string.runtime_update_check_button), false);
        record("update_check_started", "automatic", !force);
        updateExecutor.execute(() -> {
            try {
                AppUpdateManager.UpdateInfo available = updateManager.checkForUpdate(
                        getApplicationContext(), force);
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    restoreUpdateButton();
                    if (available == null) {
                        publishSettingsOperation(SettingsOperation.Update,
                                runtimeText(R.string.runtime_up_to_date), StatusTone.Ok, false);
                        record("update_check_finished", "result", "up_to_date");
                        if (force) showUpdateMessage(
                                runtimeText(R.string.runtime_update),
                                runtimeText(R.string.runtime_up_to_date));
                    } else {
                        publishSettingsOperation(SettingsOperation.Update,
                                runtimeText(R.string.runtime_update_available_version,
                                        available.version),
                                StatusTone.Ok, false);
                        record("update_check_finished", "result", "available",
                                "version", available.version);
                        showCachedUpdateIfAvailable();
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    restoreUpdateButton();
                    record("update_check_finished", "result", "error",
                            "error", error.toString());
                    publishSettingsOperation(SettingsOperation.Update,
                            runtimeText(R.string.runtime_update_check_error), StatusTone.Error, false);
                    if (force) showUpdateError(error);
                });
            }
        });
    }

    private void restoreUpdateButton() {
        if (settingsPanel == null) return;
        settingsPanel.setUpdateButton(
                runtimeText(R.string.runtime_update),
                !activityDestroyed && updateProgressDialog == null);
    }

    private void showCachedUpdateIfAvailable() {
        AppUpdateManager.UpdateInfo available = AppUpdateManager.cachedAvailable();
        if (!activityResumed || activityDestroyed || available == null
                || updateDialog != null || updateProgressDialog != null) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView versions = label(runtimeText(R.string.runtime_installed_version,
                BuildConfig.VERSION_NAME) + "\n"
                + runtimeText(R.string.runtime_available_version, available.version));
        versions.setTextSize(16);
        body.addView(versions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        String notes = available.releaseNotes == null ? "" : available.releaseNotes.trim();
        if (!notes.isEmpty()) {
            TextView releaseNotes = label("");
            releaseNotes.setTextSize(16);
            releaseNotes.setLineSpacing(0.0f, 1.15f);
            releaseNotes.setPadding(0, dp(16), 0, 0);
            releaseNotes.setText(ReleaseNotesMarkdown.render(notes));
            body.addView(releaseNotes, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        updateDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_update_available))
                .setView(scroll)
                .setPositiveButton(runtimeText(R.string.runtime_update), (dialog, which) -> {
                    AppUpdateManager.clearCachedAvailable();
                    startUpdateDownload(available);
                })
                .setNegativeButton(runtimeText(R.string.runtime_update_later), (dialog, which) ->
                        AppUpdateManager.clearCachedAvailable())
                .setOnCancelListener(dialog -> AppUpdateManager.clearCachedAvailable())
                .create();
        updateDialog.setOnDismissListener(dialog -> updateDialog = null);
        updateDialog.show();
    }

    private void startUpdateDownload(AppUpdateManager.UpdateInfo info) {
        if (activityDestroyed || updateProgressDialog != null) return;
        record("update_download_started", "version", info.version);
        updateProgressDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_update_download_title, info.version))
                .setMessage(runtimeText(R.string.runtime_update_download, 0))
                .setCancelable(false)
                .create();
        updateProgressDialog.show();
        updateExecutor.execute(() -> {
            try {
                File file = updateManager.downloadAndVerify(
                        getApplicationContext(), info, progress -> runOnUiThread(() -> {
                            if (updateProgressDialog != null) {
                                updateProgressDialog.setMessage(
                                        runtimeText(R.string.runtime_update_download, progress));
                            }
                        }));
                runOnUiThread(() -> {
                    dismissUpdateProgress();
                    try {
                        updateManager.install(CameraProbeActivity.this, info, file);
                        record("update_install_opened", "version", info.version);
                    } catch (Throwable error) {
                        showUpdateError(error);
                    }
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    dismissUpdateProgress();
                    showUpdateError(error);
                });
            }
        });
    }

    private void dismissUpdateProgress() {
        if (updateProgressDialog == null) return;
        updateProgressDialog.dismiss();
        updateProgressDialog = null;
    }

    private void showUpdateError(Throwable error) {
        record("update_failed", "error", error.toString());
        if (activityDestroyed || isFinishing()) return;
        showUpdateMessage(runtimeText(R.string.runtime_update_failed),
                error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
    }

    private void showUpdateMessage(String title, String message) {
        if (activityDestroyed || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(runtimeText(R.string.runtime_ok), null)
                .show();
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
        if (selectedTab == TAB_CAMERA_CALIBRATION) {
            closeSharedCalibration();
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
        updateControls();
        maybeOpenProductionPreview();
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
        int[] generations = {inputGeneration};
        if (view == calibrationPreview) {
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
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_car_compat_title))
                .setMessage(runtimeText(R.string.runtime_car_compat_message))
                .setNegativeButton(runtimeText(R.string.runtime_cancel), null)
                .setPositiveButton(runtimeText(R.string.runtime_create),
                        (dialog, which) -> startCompatibilityBundleExport())
                .show();
    }

    private void startCompatibilityBundleExport() {
        if (logExportInProgress || compatibilityExportInProgress
                || shutdownRequested || activityDestroyed) return;
        compatibilityExportInProgress = true;
        publishSettingsOperation(SettingsOperation.Compatibility,
                runtimeText(R.string.runtime_car_compat_forming),
                StatusTone.Warning, true, true);
        if (settingsPanel != null) settingsPanel.setCompatibilityExportInProgress(true);
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
        compatibilityExportProgressDialog = new AlertDialog.Builder(this)
                .setTitle(runtimeText(R.string.runtime_car_compat_title))
                .setMessage(runtimeText(R.string.runtime_car_compat_prepare))
                .setNegativeButton(runtimeText(R.string.runtime_cancel),
                        (dialog, which) -> cancelCompatibilityBundleExport())
                .setCancelable(false)
                .create();
        compatibilityExportProgressDialog.show();
    }

    private void cancelCompatibilityBundleExport() {
        CompatibilityBundleExporter.ExportControl control = compatibilityExportControl;
        if (control == null || control.isCancellationRequested()) return;
        control.cancel();
        if (compatibilityExportProgressDialog != null) {
            compatibilityExportProgressDialog.setMessage(
                    runtimeText(R.string.runtime_car_compat_canceling));
            Button cancel = compatibilityExportProgressDialog.getButton(AlertDialog.BUTTON_NEGATIVE);
            if (cancel != null) cancel.setEnabled(false);
        }
        if (settingsPanel != null) settingsPanel.setTransferStatus(
                runtimeText(R.string.runtime_car_compat_canceling));
        publishSettingsOperation(SettingsOperation.Compatibility,
                runtimeText(R.string.runtime_car_compat_canceling), StatusTone.Warning, true);
        record("compatibility_bundle_export", "state", "cancel_requested");
    }

    private void updateCompatibilityExportProgress(CompatibilityBundleExporter.Progress progress) {
        if (progress == null || activityDestroyed || !compatibilityExportInProgress
                || compatibilityExportControl == null
                || compatibilityExportControl.isCancellationRequested()) return;
        String text = localizedCompatibilityExportProgressText(progress);
        if (compatibilityExportProgressDialog != null) {
            compatibilityExportProgressDialog.setMessage(text);
        }
        if (settingsPanel != null) settingsPanel.setTransferStatus(text);
        publishSettingsOperation(SettingsOperation.Compatibility,
                text, StatusTone.Warning, true);
    }

    static String compatibilityExportProgressText(CompatibilityBundleExporter.Progress progress) {
        if (progress == null) return "Підготовка...";
        switch (progress.phase) {
            case PREPARING:
                return "Підготовка пакета...";
            case TEXT:
                return String.format(Locale.US, "Текстові джерела: %d/%d",
                        progress.index, progress.count);
            case REMOTE:
                String path = progress.currentPath == null || progress.currentPath.isEmpty()
                        ? "файл" : progress.currentPath;
                return String.format(Locale.US, "Файл %d/%d: %s (%s / %s)",
                        progress.index, progress.count, path,
                        formatBytes(progress.bytes), formatBytes(progress.totalBytes));
            case ZIP:
                return "Пакування архіву...";
            case COMPLETE:
                return "Пакет готовий.";
            case CANCELED:
                return "Скасування завершено.";
            default:
                return "Формування пакета...";
        }
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
        compatibilityExportInProgress = false;
        if (compatibilityExportControl == control) compatibilityExportControl = null;
        if (compatibilityExportProgressDialog != null) {
            compatibilityExportProgressDialog.dismiss();
            compatibilityExportProgressDialog = null;
        }
        if (!activityDestroyed && settingsPanel != null) {
            settingsPanel.setCompatibilityExportInProgress(false);
        }
        if (!activityDestroyed) advanceStartupAuthorizationFlow();
        boolean cancelled = control != null && control.isCancellationRequested();
        if (cancelled) {
            boolean deleted = archive == null || !archive.exists() || archive.delete();
            record("compatibility_bundle_export", "state", "canceled",
                    "archive_deleted", deleted, "error", error == null ? "" : error.toString());
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_car_compat_canceled), StatusTone.Warning, false);
            if (activityResumed && !activityDestroyed) {
                Toast.makeText(this, runtimeText(R.string.runtime_car_compat_cancel_done),
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (error != null) {
            record("compatibility_bundle_export", "state", "failed", "error", error.toString());
            publishSettingsOperation(SettingsOperation.Compatibility,
                    runtimeText(R.string.runtime_car_compat_failed), StatusTone.Error, false);
            if (activityResumed && !activityDestroyed) {
                Toast.makeText(this, runtimeText(R.string.runtime_car_compat_failed),
                        Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, runtimeText(R.string.runtime_share_menu_failed),
                    Toast.LENGTH_LONG).show();
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
                "error", event.error);
        if (activePreview != view) return;
        CameraProfileId failedProfile = activeActivityCameraProfile;
        closeCamera("dewarp_renderer_failed");
        publishCameraStatus(failedProfile, null,
                "Помилка корекції камери; відкрийте preview повторно", StatusTone.Error, false);
    }

    private void onCalibrationDewarpStats(CameraDewarpRenderer.Stats stats) {
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
        reverseCameraSurfacesReady = true;
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
    public void onProductionUiAction(BydExtendUiAction action) {
        if (action instanceof BydExtendUiAction.Navigate) {
            selectProductionTab(rootTabToLegacy(
                    ((BydExtendUiAction.Navigate) action).getTab()));
            return;
        }
        if (action instanceof BydExtendUiAction.Toggle) {
            handleProductionToggle((BydExtendUiAction.Toggle) action);
        } else if (action instanceof BydExtendUiAction.CommitNumber) {
            handleProductionNumber((BydExtendUiAction.CommitNumber) action);
        } else if (action instanceof BydExtendUiAction.Select) {
            handleProductionSelection((BydExtendUiAction.Select) action);
        } else if (action instanceof BydExtendUiAction.SetProfileGeometry) {
            BydExtendUiAction.SetProfileGeometry resize = (BydExtendUiAction.SetProfileGeometry) action;
            saveProductionBlindGeometry(preferences, resize.getProfile(), resize.getGeometry());
            CameraHelperService.cameraSettingsChanged(this);
        } else if (action instanceof BydExtendUiAction.MoveProfile) {
            BydExtendUiAction.MoveProfile move = (BydExtendUiAction.MoveProfile) action;
            saveProductionProfilePosition(move.getProfile(), move.getX(), move.getY());
        } else if (action instanceof BydExtendUiAction.Run) {
            handleProductionCommand((BydExtendUiAction.Run) action);
        }
    }

    @Override
    public void requestProductionMirrorOverlayPermission() {
        if (android.provider.Settings.canDrawOverlays(this)) return;
        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
                return;
            }
        } catch (RuntimeException unavailable) {
            record("mirror_overlay_permission_unavailable", "error", unavailable.toString());
        }
        Toast.makeText(this, runtimeText(R.string.runtime_overlay_permission_unavailable),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onProductionMirrorAction(MirrorBackendAction action) {
        if (productionUi == null || action == null || shutdownRequested) return;
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
        MirrorUiState ui = productionUi.getState().getMirror();
        boolean enabled = before.enabled;
        int target = before.target;
        CameraPlacement placement = before.placement;
        RearviewMirrorSettings.Calibration calibration = before.calibration;
        RearviewMirrorSettings.Calibration preset = before.preset;
        int border = before.borderDp;
        int color = before.borderArgb;
        boolean hidden = before.manualHidden;
        try {
            switch (action.getKind()) {
                case SetEnabled:
                    enabled = action.getEnabled() != null ? action.getEnabled() : ui.getEnabled();
                    break;
                case SetTarget:
                    DisplayTarget selected = action.getTarget() != null
                            ? action.getTarget() : ui.getTarget();
                    target = selected == DisplayTarget.Cluster
                            ? RearviewMirrorSettings.TARGET_CLUSTER : RearviewMirrorSettings.TARGET_TABLET;
                    break;
                case SetGeometry:
                    if (action.getField() == com.byd.extend.ui.MirrorNumber.BorderWidth) {
                        border = Math.round(mirrorNumber(action.getValue() != null
                                ? action.getValue() : ui.getBorderWidth()));
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
                    calibration = mergeMirrorCalibration(before.calibration, action);
                    break;
                case SavePreset:
                    preset = calibration;
                    break;
                case LoadPreset:
                    if (preset == null) {
                        Toast.makeText(this, runtimeText(R.string.runtime_preset_missing),
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    calibration = preset;
                    break;
                case ResetPlacement:
                    placement = RearviewMirrorSettings.defaults().placement;
                    break;
                case ResetOriginal:
                    calibration = mirrorCalibrationWithCrop(calibration, false,
                            CameraPlacement.of(0, 0, 1, 1));
                    break;
                case ResetCorrection:
                    RearviewMirrorSettings.Calibration defaultCalibration =
                            RearviewMirrorSettings.defaults().calibration;
                    calibration = new RearviewMirrorSettings.Calibration(calibration.raw,
                            defaultCalibration.corrected, defaultCalibration.enabled,
                            defaultCalibration.fovDegrees, defaultCalibration.projection,
                            calibration.mirrored, calibration.rotationDegrees,
                            calibration.rotationMode);
                    break;
                case ResetOutput:
                    calibration = new RearviewMirrorSettings.Calibration(calibration.raw,
                            calibration.corrected, calibration.enabled, calibration.fovDegrees,
                            calibration.projection, false, 0, CameraRotation.MODE_FIT);
                    break;
                case HideUntilOpen:
                    hidden = true;
                    break;
            }
            model.save(new RearviewMirrorSettings.Settings(enabled, target, placement, calibration,
                    preset, border, color, hidden));
            if (action.getKind() == MirrorBackendActionKind.SavePreset
                    || action.getKind() == MirrorBackendActionKind.LoadPreset) {
                Toast.makeText(this, runtimeText(action.getKind() == MirrorBackendActionKind.SavePreset
                        ? R.string.runtime_preset_saved : R.string.runtime_preset_loaded_short),
                        Toast.LENGTH_LONG).show();
            }
            transientProfilePreviewId = null;
            transientProfilePreviewFov = null;
            transientProfilePreviewRotation = null;
            notifyProductionProfileChanged(CameraProfileId.Mirror.INSTANCE);
            productionUi.reload();
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
                RearviewMirrorSettings.Calibration c = mergeMirrorCalibration(
                        new RearviewMirrorSettings(preferences).load().calibration, action);
                applyTransientDewarpConfig(CameraProfileId.Mirror.INSTANCE,
                        CameraDewarpConfig.of(CameraDewarpConfig.LENS_REAR,
                                c.enabled, c.fovDegrees, c.projection));
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

    private static RearviewMirrorSettings.Calibration mergeMirrorCalibration(
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
            fov = Math.round(mirrorNumber(value));
            if (fov < 60 || fov > 170) throw new IllegalArgumentException("Invalid FOV");
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
                mirrored, rotation, mode);
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
                Math.round(mirrorNumber(value.getRotation())), value.getOutputMode());
    }

    private static RearviewMirrorSettings.Calibration mirrorCalibrationWithCrop(
            RearviewMirrorSettings.Calibration value, boolean corrected, CameraPlacement crop) {
        return new RearviewMirrorSettings.Calibration(corrected ? value.raw : crop,
                corrected ? crop : value.corrected, value.enabled, value.fovDegrees,
                value.projection, value.mirrored, value.rotationDegrees, value.rotationMode);
    }

    private static DirectCameraCrop mirrorDirectCrop(
            CameraPlacement crop, RearviewMirrorSettings.Calibration transform) {
        return DirectCameraCrop.requireUiGeometry(crop.x, crop.y, crop.width, crop.height,
                0, CameraRotation.MODE_FIT).withOutputTransformPreservingGeometry(
                        transform.rotationDegrees, transform.rotationMode, transform.mirrored);
    }

    /** Starts global key capture only while the Accessibility filter is connected. */
    public boolean beginReverseButtonLearning() {
        if (productionUi == null || activityDestroyed || shutdownRequested
                || LegacySettingsImporter.blocksRuntime(this)) return false;
        boolean started = WeatherRefreshAccessibilityService
                .beginSteeringButtonLearning(this);
        if (!started) {
            Toast.makeText(this,
                    runtimeText(R.string.runtime_camera_button_unavailable),
                    Toast.LENGTH_LONG).show();
            return false;
        }
        productionUi.showReverseButtonCaptureDialog();
        return true;
    }

    /** Cancels transient capture without changing the persisted binding. */
    public void cancelReverseButtonLearning() {
        WeatherRefreshAccessibilityService.cancelSteeringButtonLearning();
        if (productionUi != null) productionUi.dismissReverseButtonCaptureDialog();
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
            if (WeatherRefreshAccessibilityService.isSteeringButtonLearning()) {
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
        productionReverseEditorEditable = editable;
        productionReverseEditor.setEditable(editable);
        productionReverseEditor.setVisibility(editable ? View.VISIBLE : View.GONE);
        productionReverseEditor.setLayoutModel(reverseCameraLayout);
        if (productionUi != null) {
            productionReverseEditor.setSelectedCameraSilently(reverseElementIndex(
                    productionUi.getState().getReverse().getSelectedElement()));
        }
        reverseCameraPreview.applyVisibility(
                ReverseCameraController.loadVisibilityMask(preferences));
        reverseCameraPreview.setWidgetVisible(
                ReverseCameraController.loadWidgetVisible(preferences));
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
                || (activeActivityInputGenerations.length != 4
                && activeActivityInputGenerations.length != 5)) return;
        final IBinder current = helper;
        final int requestId = activeActivityCameraRequestId;
        // Each request publishes a new immutable generation array. Keep that bundle identity
        // through the IPC queue; a close/rebind makes an already queued update stale.
        final int[] bundleGenerations = activeActivityInputGenerations;
        // The existing transaction carries direct-pane generations, without the stock base.
        final int[] generations = java.util.Arrays.copyOfRange(
                bundleGenerations, 1, bundleGenerations.length);
        final int visibilityMask = ReverseCameraController.loadVisibilityMask(preferences);
        final boolean widgetVisible = ReverseCameraController.loadWidgetVisible(preferences);
        ipcExecutor.execute(() -> {
            if (current != helper || !requestedOpen || requestId != activeActivityCameraRequestId
                    || bundleGenerations != activeActivityInputGenerations) return;
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
            if (output.getField() == OutputNumber.CornerRadius) transientCornerRadiusDp = accepted;
            else transientTransparencyPercent = accepted;
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
            CameraProfileId id, ProfileNumber field, int value) {
        if (field == ProfileNumber.Size) {
            // Compose's placement geometry is derived from the accepted state value; no native
            // camera transform or preference write is needed for this field.
            return;
        }
        if (field == ProfileNumber.Fov) {
            transientProfilePreviewId = id;
            transientProfilePreviewFov = value;
            CameraDewarpConfig current = loadProductionCalibrationDewarp(id);
            applyTransientDewarpConfig(id, current.withFov(value));
            return;
        }
        if (field != ProfileNumber.Rotation) return;
        transientProfilePreviewId = id;
        transientProfilePreviewRotation = value;
        applyTransientOutputRotation(id, value);
    }

    private void clearTransientProfilePreview(CameraProfileId id, ProfileNumber field) {
        if (id == null || !id.equals(transientProfilePreviewId)) return;
        if (field == ProfileNumber.Fov) transientProfilePreviewFov = null;
        else if (field == ProfileNumber.Rotation) transientProfilePreviewRotation = null;
        if (transientProfilePreviewFov == null && transientProfilePreviewRotation == null) {
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
            } else if (id == ToggleId.Music) {
                preferences.edit().putBoolean("music_visualizer_enabled", value).apply();
                onMusicEnabledChanged(value);
            } else if (id == ToggleId.Weather) {
                onProductionWeatherEnabled(value);
            } else if (id == ToggleId.AutoStart) {
                onSettingsAutoStartChanged(value);
            } else if (id == ToggleId.AutomaticUpdate) {
                preferences.edit().putBoolean("update_auto_check_enabled", value).apply();
                if (value) scheduleStartupUpdateCheck();
                else mainHandler.removeCallbacks(runStartupUpdateCheck);
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
            resetProductionReverseLayout(action.getReverseElement());
        } else if (manualSignalPayload(command) >= 0) requestManualTurnState(manualSignalPayload(command));
        else if (command == CommandId.StopDiagnosticCamera) stopActivityCameraManually("ui_stop");
        else if (command == CommandId.OpenBackgroundSettings) openBackgroundStartSettings("settings_button");
        else if (command == CommandId.GrantAdb) requestAdbAuthorization(
                "adb_authorization_manual", "settings_manual", false);
        else if (command == CommandId.CheckForUpdates) runUpdateCheck(true);
        else if (command == CommandId.ShareLogs) confirmDiagnosticLogShare();
        else if (command == CommandId.ClearLogs) clearCaptureLogs();
        else if (command == CommandId.ShareCompatibilityPackage) confirmCompatibilityBundleShare();
        else if (command == CommandId.ExportCameraPresets) exportCameraPreset();
        else if (command == CommandId.LoadCameraPresets) chooseCameraPreset();
        else if (command == CommandId.ImportLegacySettings) readLegacySettings();
        else if (command == CommandId.RestoreLegacyAccess) confirmLegacyAccessRestore();
        else if (command == CommandId.CancelOperation) cancelCompatibilityBundleExport();
        else if (command == CommandId.Shutdown) requestAppShutdown();
        else if (command == CommandId.OpenWeatherAttribution) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse(CameraProbeWeatherPanel.OPEN_METEO_URL)));
            } catch (Throwable error) {
                publishSettingsFeedback("Open-Meteo: "
                        + CameraProbeWeatherPanel.OPEN_METEO_URL, StatusTone.Warning);
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
        if (productionUi != null) productionUi.setSettingsOperation(
                operation, new StatusUiState(localized, tone, true), pending, claimFeedback);
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
                preferences.getBoolean(WeatherRuntime.PREF_ENABLED, false), hasLocationPermission()));
    }

    static HeaderUiState productionHeader(LocalAdbClient.AccessState.Status access,
            boolean weatherEnabled, boolean locationGranted) {
        StatusTone adbTone = access == LocalAdbClient.AccessState.Status.OK
                ? StatusTone.Ok : access == LocalAdbClient.AccessState.Status.ERROR
                ? StatusTone.Error : StatusTone.Neutral;
        return new HeaderUiState(
                new StatusUiState("ADB", adbTone, true),
                new StatusUiState("", locationGranted ? StatusTone.Ok : StatusTone.Error,
                        weatherEnabled), weatherEnabled);
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
        if (guardStatus != null) guardStatus.setText(text);
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
        TextView legacy = direct ? directCameraStatus : debugCameraStatus;
        if (legacy != null) legacy.setText(localizedText);
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

    private CameraPlacement loadProductionBlindPlacement(CameraProfile profile) {
        int target = BlindSpotOverlayController.readTarget(preferences, profile);
        int[] display = CameraDisplayTarget.displaySize(this, target);
        boolean tablet = target == CameraDisplayTarget.TABLET;
        return BlindSpotOverlayController.readPlacement(preferences, profile, display[0], display[1],
                tablet ? dp(16) : 0, tablet ? dp(36) : 0, tablet ? dp(88) : 0);
    }

    static void saveProductionBlindGeometry(SharedPreferences preferences, CameraProfileId.Blind id,
            MirrorGeometryUiState geometry) {
        CameraPlacement placement = CameraPlacement.bounded(mirrorFraction(geometry.getX()),
                mirrorFraction(geometry.getY()), mirrorFraction(geometry.getWidth()),
                mirrorFraction(geometry.getHeight())).roundedTenths();
        BlindSpotOverlayController.writePlacement(preferences, blindProfile(id), placement);
    }

    private void saveProductionProfilePosition(CameraProfileId id, float x, float y) {
        float safeX = clamp(x, 0.0f, 1.0f);
        float safeY = clamp(y, 0.0f, 1.0f);
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            CameraPlacement before = loadProductionBlindPlacement(profile);
            BlindSpotOverlayController.writePlacement(preferences, profile,
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
            saveProductionPlacementNumber(id, field, value);
            return;
        }
        try {
            if (field == ProfileNumber.Fov) {
                saveProductionFov(id, Math.round(value));
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
            CameraProfileId id, ProfileNumber field, float value) {
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            if (field == ProfileNumber.Size) {
                int size = clamp(Math.round(value), BlindSpotOverlayController.MIN_SCALE_PERCENT,
                        BlindSpotOverlayController.MAX_SCALE_PERCENT);
                preferences.edit().putInt(BlindSpotOverlayController.scaleKey(profile), size).apply();
            } else {
                CameraPlacement before = loadProductionBlindPlacement(profile);
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
                BlindSpotOverlayController.writePlacement(preferences, profile, next);
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

    private void saveProductionFov(CameraProfileId id, int fov) {
        if (fov < CameraDewarpConfig.MIN_FOV_DEGREES
                || fov > CameraDewarpConfig.MAX_FOV_DEGREES) {
            throw new IllegalArgumentException("FOV має бути 60..170°");
        }
        if (id instanceof CameraProfileId.Blind) {
            CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
            CameraDewarpConfig.saveForProfile(preferences, profile,
                    CameraDewarpConfig.loadForProfile(preferences, profile).withFov(fov));
        } else if (id instanceof CameraProfileId.Parking) {
            ParkingCameraProfile profile = parkingProfile((CameraProfileId.Parking) id);
            CameraDewarpConfig.saveForParking(preferences, profile,
                    CameraDewarpConfig.loadForParking(preferences, profile).withFov(fov));
        } else {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            int index = reverseProfileIndex(reverse);
            if (reverse.getSource() == ReverseSource.Front) {
                CameraDewarpConfig.saveForReverseFront(preferences, index,
                        CameraDewarpConfig.loadForReverseFront(preferences, index).withFov(fov));
            } else CameraDewarpConfig.saveForReverse(preferences, index,
                    CameraDewarpConfig.loadForReverse(preferences, index).withFov(fov));
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
        } else CameraHelperService.cameraSettingsChanged(this);
        configureProductionCameraProfile(id, null);
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
            sourceIndex = ReverseCameraLayout.REAR_CAMERA_INDEX;
            calibrationParkingMode = false;
        } else return;
        if (requestedSourceIndex != null) sourceIndex = requestedSourceIndex;

        // AndroidView.update runs again when Compose accepts a slider preview.  Keep that
        // recomposition from re-reading stale persisted FOV/rotation over the live gesture.
        if (id.equals(transientProfilePreviewId)) {
            if (transientProfilePreviewFov != null) {
                dewarp = dewarp.withFov(transientProfilePreviewFov);
            }
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
            if (reverse.getSource() == ReverseSource.Front) {
                CameraCalibrationPreset.saveReverseFront(
                        preferences, reverseProfileIndex(reverse));
            } else {
                CameraCalibrationPreset.saveReverse(
                        preferences, reverseProfileIndex(reverse));
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
            loaded = reverse.getSource() == ReverseSource.Front
                    ? CameraCalibrationPreset.loadReverseFront(
                            preferences, reverseProfileIndex(reverse))
                    : CameraCalibrationPreset.loadReverse(
                            preferences, reverseProfileIndex(reverse));
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
                if (reverse.getElement() == ReverseElement.Rear
                        && reverse.getSource() == ReverseSource.Rear) {
                    transferred = CameraCalibrationPreset.copyCentralReverseRearToFront(preferences);
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
            if (reverse.getElement() == ReverseElement.Rear
                    && reverse.getSource() == ReverseSource.Rear) {
                return new CameraProfileId.Reverse(ReverseElement.Rear, ReverseSource.Front);
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

    static String productionProfileLabel(CameraProfileId id, boolean english) {
        UiStrings strings = new UiStrings(english ? UiLanguage.English : UiLanguage.Ukrainian);
        if (id instanceof CameraProfileId.Parking) {
            return strings.getParkingViews().get(((CameraProfileId.Parking) id).getView().ordinal());
        }
        if (id instanceof CameraProfileId.Blind) {
            CameraProfileId.Blind blind = (CameraProfileId.Blind) id;
            boolean front = blind.getGroup() == CameraGroup.Front;
            boolean right = blind.getSide() == CameraSide.Right;
            return english ? (front ? "Front " : "Rear ") + (right ? "right" : "left")
                    : (front ? "Передня " : "Задня ") + (right ? "права" : "ліва");
        }
        if (id instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) id;
            if (reverse.getSource() == ReverseSource.Front) {
                if (reverse.getElement() == ReverseElement.Rear) return english ? "Front" : "Передня";
                boolean right = reverse.getElement() == ReverseElement.RearRight;
                return english ? "Front " + (right ? "right" : "left")
                        : "Передня " + (right ? "права" : "ліва");
            }
            return strings.getReverseElements().get(reverse.getElement().ordinal());
        }
        return english ? "Unknown camera" : "Невідома камера";
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

    private void resetProductionReverseLayout(ReverseElement element) {
        boolean reset = false;
        try {
            if (element != null) {
                ReverseCameraController.resetSelectedLayout(preferences, element.name());
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
            reset = resetProductionProfileSettings(preferences, id, command);
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

    static String productionResetScope(CommandId command, boolean english) {
        if (command == CommandId.ResetProfilePlacement) return english ? "Placement" : "Розташування";
        if (command == CommandId.ResetProfileOriginal) return english ? "Original area" : "Область оригіналу";
        if (command == CommandId.ResetProfileCorrection) return english ? "Correction" : "Корекція";
        return english ? "Output" : "Вивід";
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
        if (command == CommandId.ResetProfilePlacement) {
            if (id instanceof CameraProfileId.Blind) {
                CameraProfile profile = blindProfile((CameraProfileId.Blind) id);
                preferences.edit()
                        .remove(BlindSpotOverlayController.placementWidthKey(profile))
                        .remove(BlindSpotOverlayController.placementHeightKey(profile))
                        .putFloat(BlindSpotOverlayController.frameAspectKey(profile),
                                BlindSpotOverlayController.defaultFrameAspect(profile))
                        .putFloat(BlindSpotOverlayController.positionKey(profile, false),
                                BlindSpotOverlayController.defaultPosition(profile, false))
                        .putFloat(BlindSpotOverlayController.positionKey(profile, true),
                                BlindSpotOverlayController.defaultPosition(profile, true))
                        .putInt(BlindSpotOverlayController.scaleKey(profile),
                                BlindSpotOverlayController.defaultScale(profile))
                        .putInt(BlindSpotOverlayController.targetKey(profile),
                                BlindSpotOverlayController.defaultTarget(profile)).apply();
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
        ReverseCameraLayout.Rect current = pane == ReverseCameraLayout.BACKGROUND_PANE_ID
                ? layout.background : pane == ReverseCameraLayout.WIDGET_PANE_ID
                        ? layout.widget : layout.pane(pane).destination;
        float normalized = value / 100.0f;
        float left = target.getField() == ReverseGeometryNumber.X ? normalized : current.left;
        float top = target.getField() == ReverseGeometryNumber.Y ? normalized : current.top;
        float width = target.getField() == ReverseGeometryNumber.Width ? normalized : current.width;
        float height = target.getField() == ReverseGeometryNumber.Height ? normalized : current.height;
        ReverseCameraLayout.Rect next = pane == ReverseCameraLayout.WIDGET_PANE_ID
                ? ReverseCameraLayout.widgetDestination(left, top, width, height)
                : ReverseCameraLayout.destination(left, top, width, height);
        if (pane == ReverseCameraLayout.BACKGROUND_PANE_ID) {
            layout = ReverseCameraLayout.withBackground(layout, next);
        } else if (pane == ReverseCameraLayout.WIDGET_PANE_ID) {
            layout = ReverseCameraLayout.withWidget(layout, next);
        } else layout = ReverseCameraLayout.withPane(
                layout, pane, next, layout.pane(pane).sourceCrop);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void nudgeProductionReverse(float x, float y) {
        int pane = reverseElementIndex(productionUi.getState().getReverse().getSelectedElement());
        ReverseCameraLayout layout = ReverseCameraLayout.move(
                ReverseCameraController.loadRawLayout(preferences), pane, x, y);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void changeProductionReverseZ(boolean raise) {
        int pane = reverseElementIndex(productionUi.getState().getReverse().getSelectedElement());
        if (pane <= 0) return;
        ReverseCameraLayout layout = ReverseCameraController.loadRawLayout(preferences);
        layout = raise ? ReverseCameraLayout.raise(layout, pane)
                : ReverseCameraLayout.lower(layout, pane);
        ReverseCameraController.saveCompositionLayout(preferences, layout);
        applyProductionReverseState();
        CameraHelperService.reverseCameraSettingsChanged(this);
    }

    private void applyProductionReverseState() {
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
        }
        if (productionUi != null) productionUi.reload();
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
        if (isProductionPlacementKind(kind)) created = createProductionPlacementHost();
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
                    || !calibrationHostProfile.equals(profile)) {
                productionCalibrationHostGeneration++;
                productionCalibrationGestureStart = null;
                productionCalibrationGestureActive = false;
                productionCalibrationGestureTransformChanged = false;
                bindProductionCalibrationOverlayListeners();
            }
            calibrationHostProfile = profile;
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
        }
    }

    @Override
    public void releaseProductionCameraHost(View view, CameraHostSlot slot) {
        if (productionCameraHosts.get(slot.getKind()) != view) return;
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
            }
        }
        if (tab == TAB_CAMERAS || tab == TAB_PARKING_CAMERAS || tab == TAB_REARVIEW_MIRROR) {
            cameraPreview = null;
            cameraPreviewCover = null;
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
                    before.calibration, corrected,
                    CameraPlacement.of(crop.left, crop.top, crop.width, crop.height));
            model.save(new RearviewMirrorSettings.Settings(before.enabled, before.target,
                    before.placement, calibration, before.preset, before.borderDp, before.borderArgb,
                    before.manualHidden));
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

    private void saveProductionCalibrationTransform(
            CameraProfileId profile, DirectCameraCrop crop) {
        if (profile instanceof CameraProfileId.Mirror) {
            RearviewMirrorSettings model = new RearviewMirrorSettings(preferences);
            RearviewMirrorSettings.Settings before = model.load();
            RearviewMirrorSettings.Calibration c = before.calibration;
            RearviewMirrorSettings.Calibration calibration = new RearviewMirrorSettings.Calibration(
                    c.raw, c.corrected, c.enabled, c.fovDegrees, c.projection,
                    crop.mirrorHorizontally, crop.rotationDegrees, crop.rotationMode);
            model.save(new RearviewMirrorSettings.Settings(before.enabled, before.target,
                    before.placement, calibration, before.preset, before.borderDp, before.borderArgb,
                    before.manualHidden));
            notifyProductionProfileChanged(profile);
            if (productionUi != null) productionUi.reload();
            return;
        }
        if (profile instanceof CameraProfileId.Blind) {
            CameraProfile camera = blindProfile((CameraProfileId.Blind) profile);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, camera);
            DirectCameraCrop accepted = strictOutputTransform(
                    raw, crop.rotationDegrees, crop.rotationMode);
            DirectCameraCrop.saveOutputTransform(preferences, camera, accepted);
        } else if (profile instanceof CameraProfileId.Parking) {
            ParkingCameraProfile parking = parkingProfile((CameraProfileId.Parking) profile);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, parking);
            DirectCameraCrop accepted = strictOutputTransform(
                    raw, crop.rotationDegrees, crop.rotationMode);
            DirectCameraCrop.saveOutputTransform(preferences, parking, accepted);
        } else if (profile instanceof CameraProfileId.Reverse) {
            CameraProfileId.Reverse reverse = (CameraProfileId.Reverse) profile;
            int index = reverseProfileIndex(reverse);
            int displayMode = crop.rotationMode == CameraRotation.MODE_FILL
                    ? ReverseCameraLayout.DISPLAY_MODE_FILL
                    : crop.rotationMode == CameraRotation.MODE_ALIGNED
                            ? ReverseCameraLayout.DISPLAY_MODE_STRETCH
                            : ReverseCameraLayout.DISPLAY_MODE_FIT;
            if (reverse.getSource() == ReverseSource.Front) {
                ReverseCameraController.saveFrontPaneTransform(
                        preferences, index, crop.rotationDegrees,
                        displayMode, crop.mirrorHorizontally);
            } else {
                ReverseCameraController.saveRearPaneTransform(
                        preferences, index, crop.rotationDegrees,
                        displayMode, crop.mirrorHorizontally);
            }
        }
        notifyProductionProfileChanged(profile);
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
        }
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
            final SurfaceView surfaceView = new SurfaceView(this);
            debugPreview = surfaceView;
            surfaceView.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
            surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
                @Override public void surfaceCreated(SurfaceHolder holder) {
                    if (debugPreview != surfaceView) return;
                    debugSurfaceReady = holder.getSurface().isValid();
                    CameraHostSlot slot = productionCameraSlots.get(CameraHostKind.Avm);
                    if (debugSurfaceReady && slot != null && slot.getModeIndex() != null) {
                        int mode = clamp(slot.getModeIndex(), 0,
                                StockAvmPreview.horizontalLayoutCount() - 1);
                        openStockAvm(StockAvmPreview.horizontalViewpoint(mode), true);
                    }
                }
                @Override public void surfaceChanged(
                        SurfaceHolder holder, int format, int width, int height) {
                    if (debugPreview != surfaceView) return;
                    debugSurfaceReady = holder.getSurface().isValid();
                }
                @Override public void surfaceDestroyed(SurfaceHolder holder) {
                    if (debugPreview != surfaceView) return;
                    debugSurfaceReady = false;
                    if (activePreview == debugPreview) closeCamera("surface_destroyed");
                }
            });
            productionAvmHost.addView(surfaceView, new FrameLayout.LayoutParams(
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

    private FrameLayout cameraHostRoot() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(true);
        root.setClipToPadding(true);
        root.setBackgroundColor(Color.BLACK);
        return root;
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

    private void buildUi() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.rgb(18, 18, 18));

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        guardTabButton = button("Поворотники");
        calibrationTabButton = button("Калібрування камер");
        cameraTabButton = button("Камери сліпих зон");
        parkingTabButton = button("Камери паркування");
        reverseCameraTabButton = button("Задній хід");
        cameraDebugTabButton = button("Відладка");
        musicTabButton = button("Фічі");
        settingsTabButton = button("Налаштування");
        guardTabButton.setTextSize(14);
        calibrationTabButton.setTextSize(14);
        cameraTabButton.setTextSize(14);
        parkingTabButton.setTextSize(14);
        reverseCameraTabButton.setTextSize(14);
        cameraDebugTabButton.setTextSize(14);
        musicTabButton.setTextSize(14);
        settingsTabButton.setTextSize(14);
        tabs.addView(guardTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(cameraTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        calibrationTabButton.setVisibility(View.GONE);
        tabs.addView(parkingTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(reverseCameraTabButton,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(musicTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(settingsTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(cameraDebugTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(tabs);

        FrameLayout pages = new FrameLayout(this);
        ScrollView guardScroll = new ScrollView(this);
        guardScroll.setFillViewport(true);
        guardScroll.addView(buildGuardPanel(), new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        guardPage = guardScroll;
        calibrationPage = buildCameraCalibrationPanel();
        cameraPage = buildCameraPanel();
        parkingPage = buildParkingCameraPanel();
        reverseCameraPage = buildReverseCameraPanel();
        // Permissions are package-local, not imported settings. A full legacy import (or the
        // first start after an imported 0.53.0 install) schedules the normal foreground prompt;
        // camera-only presets never set that marker.
        musicPanel = new CameraProbeMusicPanel(this, preferences);
        weatherPanel = new CameraProbeWeatherPanel(
                this, preferences, new CameraProbeWeatherPanel.Listener() {
                    @Override
                    public void onEnableRequested(boolean enabled) {
                        onWeatherEnableRequested(enabled);
                    }

                    @Override
                    public void onIntervalChanged(int intervalMinutes) {
                        record("weather_interval", "minutes", intervalMinutes);
                        CameraHelperService.weatherSettingsChanged(CameraProbeActivity.this);
                    }

                    @Override
                    public void onManualRefresh() {
                        requestWeatherRefresh();
                    }
                });
        LinearLayout features = new LinearLayout(this);
        features.setOrientation(LinearLayout.HORIZONTAL);
        features.addView(musicPanel.view(), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        View featuresDivider = new View(this);
        featuresDivider.setBackgroundColor(Color.DKGRAY);
        features.addView(featuresDivider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));
        features.addView(weatherPanel.view(), new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        musicPage = features;
        cameraDebugPage = buildCameraDebugPanel();
        directCameraDebugPage = buildDirectCameraDebugPanel();
        debugPage = buildCombinedDebugPanel();
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsPanel = new CameraProbeSettingsPanel(
                this, preferences, this::confirmDiagnosticLogShare,
                this::confirmCompatibilityBundleShare, this::exportCameraPreset,
                this::chooseCameraPreset, this::readLegacySettings,
                LegacySettingsImporter.needsAccessRestore(this)
                        ? this::confirmLegacyAccessRestore : null);
        settingsScroll.addView(settingsPanel.view(), new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        settingsPage = settingsScroll;
        pages.addView(guardPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(calibrationPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(cameraPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(parkingPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(reverseCameraPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(musicPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(debugPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(settingsPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        guardTabButton.setOnClickListener(view -> selectTab(TAB_GUARD));
        calibrationTabButton.setOnClickListener(
                view -> selectTab(TAB_CAMERA_CALIBRATION));
        cameraTabButton.setOnClickListener(view -> selectTab(TAB_CAMERAS));
        parkingTabButton.setOnClickListener(view -> selectTab(TAB_PARKING_CAMERAS));
        reverseCameraTabButton.setOnClickListener(view -> selectTab(TAB_REVERSE_CAMERAS));
        musicTabButton.setOnClickListener(view -> selectTab(TAB_MUSIC));
        cameraDebugTabButton.setOnClickListener(view -> selectTab(TAB_CAMERA_DEBUG));
        settingsTabButton.setOnClickListener(view -> selectTab(TAB_SETTINGS));
        setContentView(root);
        int initialTab = preferences.contains("selected_tab")
                ? preferences.getInt("selected_tab", TAB_GUARD)
                : preferences.getBoolean("camera_tab_selected", false)
                        ? TAB_CAMERAS : TAB_GUARD;
        initialTab = migrateStoredTab(initialTab);
        if (legacyRuntimeBlocked) initialTab = TAB_SETTINGS;
        if (initialTab == TAB_DIRECT_CAMERA_DEBUG) {
            selectDebugMode(0);
            initialTab = TAB_CAMERA_DEBUG;
        }
        selectTab(initialTab);
    }

    private void selectTab(int tab) {
        if (settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress) return;
        cancelCalibrationCropInput();
        cancelReverseCropInput();
        if (tab == TAB_CAMERA_CALIBRATION && calibrationOriginTab != TAB_CAMERAS
                && calibrationOriginTab != TAB_PARKING_CAMERAS) {
            calibrationOriginTab = TAB_CAMERAS;
        }
        if (tab == TAB_DIRECT_CAMERA_DEBUG) tab = TAB_CAMERA_DEBUG;
        if (tab == TAB_CAMERA_CALIBRATION && selectedTab != TAB_CAMERA_CALIBRATION
                && calibrationOriginTab == TAB_CAMERAS) {
            calibrationParkingMode = false;
        }
        if (!isValidTab(tab)) tab = TAB_GUARD;
        int previousTab = selectedTab;
        if (previousTab == TAB_PARKING_CAMERAS && tab != TAB_PARKING_CAMERAS) {
            saveParkingRule();
            saveParkingMaxSpeed();
        }
        if (previousTab != tab) {
            invalidStockSurfaceRetryUsed = false;
            if (previousTab != -1 && !requestedOpen && !cameraHandoffPending) {
                closeCamera("camera_tab_changed");
            }
            clearResumeAutoPreview();
            retryStockViewpoint = -1;
            retryStockDebug = false;
        }
        selectedTab = tab;
        if (previousTab != tab && cameraTransition.pending()) {
            captureCameraTransitionTarget();
        }
        if (previousTab != tab) clearResumeAutoPreview();
        if (previousTab == TAB_REVERSE_CAMERAS && tab != TAB_REVERSE_CAMERAS
                && reverseCalibrationCameraIndex > 0) {
            closeReverseCalibration();
        }
        if (previousTab != TAB_CAMERAS && tab == TAB_CAMERAS) {
            productionPreviewRetryUsed = false;
        }
        boolean transitionAttempted = false;
        boolean transitionStarted = false;
        if (previousTab != -1 && previousTab != tab
                && (requestedOpen || cameraHandoffPending)) {
            transitionAttempted = true;
            if (isAutoPreviewTab(tab)) armResumeAutoPreview();
            transitionStarted = closeCameraForTransition("camera_tab_changed");
        }
        guardPage.setVisibility(tab == TAB_GUARD ? View.VISIBLE : View.GONE);
        calibrationPage.setVisibility(
                tab == TAB_CAMERA_CALIBRATION ? View.VISIBLE : View.GONE);
        cameraPage.setVisibility(tab == TAB_CAMERAS ? View.VISIBLE : View.GONE);
        parkingPage.setVisibility(tab == TAB_PARKING_CAMERAS ? View.VISIBLE : View.GONE);
        reverseCameraPage.setVisibility(
                tab == TAB_REVERSE_CAMERAS ? View.VISIBLE : View.GONE);
        musicPage.setVisibility(tab == TAB_MUSIC ? View.VISIBLE : View.GONE);
        debugPage.setVisibility(tab == TAB_CAMERA_DEBUG ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (previousTab != -1 && previousTab != tab
                && shouldRenewIdleTabInput(
                        resumeTabWarmup.required(),
                        transitionAttempted, cameraTransition.pending(),
                        activityClosePending, closingActivityCameraRequestId)) {
            renewSelectedPreviewInputForTabSwitch();
        }
        guardTabButton.setBackgroundColor(tabColor(tab == TAB_GUARD));
        calibrationTabButton.setBackgroundColor(
                tabColor(tab == TAB_CAMERA_CALIBRATION));
        cameraTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERAS));
        parkingTabButton.setBackgroundColor(tabColor(tab == TAB_PARKING_CAMERAS));
        reverseCameraTabButton.setBackgroundColor(
                tabColor(tab == TAB_REVERSE_CAMERAS));
        musicTabButton.setBackgroundColor(tabColor(tab == TAB_MUSIC));
        cameraDebugTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERA_DEBUG));
        settingsTabButton.setBackgroundColor(tabColor(tab == TAB_SETTINGS));
        int persistedTab = tab == TAB_CAMERA_CALIBRATION ? calibrationOriginTab : tab;
        preferences.edit().putInt("selected_tab", persistedTab).apply();
        if (!transitionStarted && previousTab != tab && isAutoPreviewTab(tab)) {
            armResumeAutoPreview();
        }
        if (previousTab == -1 && isAutoPreviewTab(tab)) {
            armResumeAutoPreview();
        }
        if (!transitionStarted && tab == TAB_CAMERA_CALIBRATION) {
            maybeOpenCalibrationCamera();
        }
        if (tab == TAB_CAMERAS) {
            updateCameraPositionHandle();
            updateProductionPreviewSize();
            if (!transitionStarted) maybeOpenProductionPreview();
        }
        if (!transitionStarted && tab == TAB_REVERSE_CAMERAS) maybeOpenReversePreview();
        if (tab == TAB_PARKING_CAMERAS) selectParkingCamera(selectedParkingCameraId);
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

    private View buildGuardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, dp(18), 0);

        guardSwitch = new Switch(this);
        guardSwitch.setText("Захист поворотника");
        guardSwitch.setTextColor(Color.WHITE);
        guardSwitch.setTextSize(20);
        guardSwitch.setChecked(preferences.getBoolean("guard_enabled", false));
        guardSwitch.setEnabled(true);
        panel.addView(guardSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        guardStatus = statusText("Запуск внутрішньої служби...");
        panel.addView(guardStatus);

        TextView manualTitle = label("Ручне керування (тільки P)");
        manualTitle.setPadding(0, dp(16), 0, dp(6));
        panel.addView(manualTitle);

        LinearLayout turnRow1 = new LinearLayout(this);
        turnRow1.setOrientation(LinearLayout.HORIZONTAL);
        turnStateButtons[0] = turnStateButton("Лівий поворотник", 2);
        turnStateButtons[1] = turnStateButton("Правий поворотник", 3);
        turnRow1.addView(turnStateButtons[0], new LinearLayout.LayoutParams(0, dp(58), 1));
        turnRow1.addView(turnStateButtons[1], new LinearLayout.LayoutParams(0, dp(58), 1));
        panel.addView(turnRow1);

        LinearLayout turnRow2 = new LinearLayout(this);
        turnRow2.setOrientation(LinearLayout.HORIZONTAL);
        turnStateButtons[2] = turnStateButton("Аварійка", 1);
        turnStateButtons[3] = turnStateButton("Скинути стан\nповоротників", 0);
        turnRow2.addView(turnStateButtons[2], new LinearLayout.LayoutParams(0, dp(58), 1));
        turnRow2.addView(turnStateButtons[3], new LinearLayout.LayoutParams(0, dp(58), 1));
        panel.addView(turnRow2);

        TextView thresholdsTitle = label("Пороги керма");
        thresholdsTitle.setPadding(0, dp(24), 0, dp(8));
        panel.addView(thresholdsTitle);

        LinearLayout outwardRow = valueRow("Поворот у напрямку", "°");
        outwardInput = numberInput(preferences.getFloat("outward_deg", DEFAULT_OUTWARD_DEG));
        outwardRow.addView(outwardInput, 1, new LinearLayout.LayoutParams(dp(120), dp(54)));
        panel.addView(outwardRow);

        LinearLayout centerRow = valueRow("Повернення до центру ±", "°");
        centerInput = numberInput(preferences.getFloat("center_deg", DEFAULT_CENTER_DEG));
        centerRow.addView(centerInput, 1, new LinearLayout.LayoutParams(dp(120), dp(54)));
        panel.addView(centerRow);

        LinearLayout delayRow = valueRow("Затримка корекції", "мс");
        correctionDelayInput = numberInput(preferences.getInt(
                "correction_delay_ms", DEFAULT_CORRECTION_DELAY_MS));
        correctionDelayInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        delayRow.addView(correctionDelayInput, 1,
                new LinearLayout.LayoutParams(dp(120), dp(54)));
        panel.addView(delayRow);

        LinearLayout maxSpeedRow = valueRow("Максимальна швидкість", "км/год");
        maxSpeedInput = numberInput(preferences.getInt(
                "max_speed_kph", DEFAULT_MAX_SPEED_KPH));
        maxSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxSpeedRow.addView(maxSpeedInput, 1,
                new LinearLayout.LayoutParams(dp(120), dp(54)));
        panel.addView(maxSpeedRow);

        View.OnFocusChangeListener saveThreshold = (view, hasFocus) -> {
            if (!hasFocus) saveThresholdsAndPush();
        };
        outwardInput.setOnFocusChangeListener(saveThreshold);
        centerInput.setOnFocusChangeListener(saveThreshold);
        correctionDelayInput.setOnFocusChangeListener(saveThreshold);
        maxSpeedInput.setOnFocusChangeListener(saveThreshold);

        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(1, dp(16)));

        TextView lifetime = label("За весь час");
        lifetime.setPadding(0, 0, 0, dp(8));
        panel.addView(lifetime);

        LinearLayout counters = new LinearLayout(this);
        counters.setOrientation(LinearLayout.HORIZONTAL);
        activationCount = counter("Увімкнень");
        correctionCount = counter("Корекцій");
        counters.addView(activationCount, new LinearLayout.LayoutParams(0, dp(100), 1));
        counters.addView(correctionCount, new LinearLayout.LayoutParams(0, dp(100), 1));
        panel.addView(counters);

        guardSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("guard_enabled", checked).apply();
            record("guard_toggle", "enabled", checked);
            updateControls();
            pushGuardConfig();
        });
        return panel;
    }

    void requestAppShutdown() {
        if (shutdownRequested) return;
        shutdownRequested = true;
        com.byd.extend.ui.RuntimeUiSession.clearProcessState();
        clearResumeAutoPreview();
        record("user_shutdown_requested", "auto_start",
                GuardRecovery.isAutoStartEnabled(this), "guard_enabled",
                guardSwitch != null && guardSwitch.isChecked());
        updateControls();
        CameraHelperService.requestShutdown(this);
        finishAndRemoveTask();
    }

    void onSettingsAutoStartChanged(boolean checked) {
        record("auto_start_toggle", "enabled", checked);
        CameraHelperService.updateAutoStart(this, checked);
        if (checked) {
            backgroundStartSettingsRequired = true;
        } else {
            backgroundStartSettingsRequired = false;
            cancelPendingBackgroundStartSettings();
        }
        advanceStartupAuthorizationFlow();
        updateControls();
    }

    void onCameraCornerRadiusChanged(int value) {
        record("camera_corner_radius", "radius_dp", value);
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
        updateControls();
    }

    private void onWeatherEnableRequested(boolean enabled) {
        onProductionWeatherEnabled(enabled);
        record("weather_toggle", "enabled", enabled,
                "permission", hasLocationPermission());
    }

    private void requestWeatherRefresh() {
        if (!hasLocationPermission()) {
            weatherRefreshAfterPermission = true;
            weatherEnableRequestedForPermission = true;
            requestLocationPermission(false, true);
            return;
        }
        long requestGeneration = ++weatherRefreshUiGeneration;
        if (weatherPanel != null) weatherPanel.setBusy(true);
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
                        if (weatherPanel != null) weatherPanel.reportResult(ok, message);
                        if (productionUi != null) productionUi.setWeatherStatus(
                                new StatusUiState(message, ok ? StatusTone.Ok
                                        : StatusTone.Error, !message.isEmpty()), false);
                    }
                });
    }

    private boolean requestLocationPermission(boolean enableWeather, boolean refreshAfter) {
        if (hasLocationPermission()) return false;
        if (settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress) return false;
        weatherEnableRequestedForPermission |= enableWeather;
        weatherRefreshAfterPermission |= refreshAfter;
        cancelPendingWeatherLocationPermission();
        if (weatherLocationPermissionInFlight) return true;
        weatherLocationPermissionInFlight = true;
        if (weatherPanel != null) weatherPanel.setLocationPermissionPending(true);
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
            if (weatherPanel != null) {
                weatherPanel.setLocationPermissionPending(false);
                weatherPanel.setEnabledState(false);
            }
            preferences.edit().putBoolean(WeatherRuntime.PREF_ENABLED, false).apply();
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
        weatherLocationPermissionPending = shouldRequestStartupWeatherPermission(
                weatherEnabled, locationGranted, requestPending, completedImport, migrationSeen);
        if (!weatherLocationPermissionPending
                && (requestPending || (completedImport && !migrationSeen))) {
            preferences.edit()
                    .remove(PREF_WEATHER_PERMISSION_REQUEST_PENDING)
                    .putBoolean(PREF_WEATHER_PERMISSION_MIGRATION_SEEN, true)
                    .apply();
        }
    }

    private void restoreWeatherPermissionState(Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        weatherLocationPermissionInFlight = savedInstanceState.getBoolean(
                STATE_WEATHER_PERMISSION_IN_FLIGHT, false);
        weatherEnableRequestedForPermission = savedInstanceState.getBoolean(
                STATE_WEATHER_ENABLE_REQUESTED, false);
        weatherRefreshAfterPermission = savedInstanceState.getBoolean(
                STATE_WEATHER_REFRESH_AFTER_PERMISSION, false);
        if (savedInstanceState.containsKey(STATE_WEATHER_PERMISSION_PENDING)) {
            weatherLocationPermissionPending = savedInstanceState.getBoolean(
                    STATE_WEATHER_PERMISSION_PENDING, false);
        }
        if (weatherLocationPermissionInFlight && weatherPanel != null) {
            weatherPanel.setLocationPermissionPending(true);
        }
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

    private View buildReverseCameraPanel() {
        reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
        reverseFrontCameraLayout = ReverseCameraController.loadFrontLayout(preferences);
        reverseFrontRawCalibrationLayout =
                ReverseCameraController.loadFrontRawLayout(preferences);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, dp(8), 0, 0);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        reverseCameraSwitch = new Switch(this);
        reverseCameraSwitch.setText("Покращений задній вид");
        reverseCameraSwitch.setTextColor(Color.WHITE);
        reverseCameraSwitch.setTextSize(20);
        reverseCameraSwitch.setChecked(preferences.getBoolean(
                ReverseCameraController.PREF_ENABLED,
                ReverseCameraController.DEFAULT_ENABLED));
        Button reset = button("Скинути вигляд");
        top.addView(reverseCameraSwitch, new LinearLayout.LayoutParams(0, dp(54), 1));
        top.addView(reset, new LinearLayout.LayoutParams(dp(210), dp(50)));
        root.addView(top);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout editorPane = new LinearLayout(this);
        editorPane.setOrientation(LinearLayout.VERTICAL);
        editorPane.setPadding(0, 0, dp(8), 0);
        LinearLayout previewPane = new LinearLayout(this);
        previewPane.setOrientation(LinearLayout.VERTICAL);
        previewPane.setPadding(dp(8), 0, 0, 0);

        editorPane.addView(label("Розташування вікон"));
        reverseCameraEditor = new ReverseCameraEditorView(this);
        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
        editorPane.addView(reverseCanvasHost(reverseCameraEditor),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout selectors = new LinearLayout(this);
        String[] paneNames = {"Тло", "Віджет", "Rear", "Rear left", "Rear right"};
        int[] paneIds = {ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraLayout.WIDGET_PANE_ID,
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX};
        for (int i = 0; i < reversePaneButtons.length; i++) {
            int cameraIndex = paneIds[i];
            reversePaneButtons[i] = button(paneNames[i]);
            reversePaneButtons[i].setOnClickListener(
                    view -> reverseCameraEditor.selectCamera(cameraIndex));
            selectors.addView(reversePaneButtons[i],
                    new LinearLayout.LayoutParams(0, dp(38), 1));
        }
        editorPane.addView(selectors);

        LinearLayout reverseVisibilityRow = new LinearLayout(this);
        reverseVisibilityRow.setOrientation(LinearLayout.HORIZONTAL);
        reverseFrontIntegrationSwitch = new Switch(this);
        reverseVisibilitySwitch = new Switch(this);
        reverseVisibilitySwitch.setTextColor(Color.WHITE);
        reverseVisibilitySwitch.setTextSize(17);
        reverseFrontIntegrationSwitch.setText("Інтеграція передніх камер");
        reverseFrontIntegrationSwitch.setTextColor(Color.WHITE);
        reverseFrontIntegrationSwitch.setTextSize(17);
        reverseVisibilityRow.addView(reverseVisibilitySwitch,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        reverseVisibilityRow.addView(reverseFrontIntegrationSwitch,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        editorPane.addView(reverseVisibilityRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout rotationRow = new LinearLayout(this);
        rotationRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rotationLabel = label("Поворот");
        reverseRotationSlider = new SeekBar(this);
        reverseRotationSlider.setMax(
                CameraRotation.MAX_DEGREES - CameraRotation.MIN_DEGREES);
        reverseRotationValue = label("0°");
        reverseRotationValue.setGravity(Gravity.CENTER);
        reverseRotationSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress, boolean fromUser) {
                        if (fromUser && !reverseRotationUiUpdating) {
                            updateReverseRotation(progress + CameraRotation.MIN_DEGREES);
                        }
                    }

                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        persistReverseRotation();
                    }
                });
        rotationRow.addView(rotationLabel, new LinearLayout.LayoutParams(dp(100), dp(42)));
        rotationRow.addView(reverseRotationSlider,
                new LinearLayout.LayoutParams(0, dp(42), 1));
        rotationRow.addView(reverseRotationValue,
                new LinearLayout.LayoutParams(dp(64), dp(42)));
        reverseOutputMirrorButton = button("Віддзеркалити");
        reverseOutputMirrorButton.setOnClickListener(
                view -> toggleReverseOutputMirror());
        rotationRow.addView(reverseOutputMirrorButton,
                new LinearLayout.LayoutParams(dp(OUTPUT_MIRROR_BUTTON_WIDTH_DP),
                        dp(CALIBRATION_ROTATION_ROW_HEIGHT_DP)));
        reverseDisplayModeInput = new Spinner(this);
        ArrayAdapter<String> displayModeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"Fit", "Fill", "Stretch"});
        displayModeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        reverseDisplayModeInput.setAdapter(displayModeAdapter);
        LinearLayout zRow = new LinearLayout(this);
        reverseLowerButton = button("Нижче шар");
        reverseRaiseButton = button("Вище шар");
        zRow.addView(reverseLowerButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        zRow.addView(reverseRaiseButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout positionPanel = new LinearLayout(this);
        positionPanel.setOrientation(LinearLayout.VERTICAL);
        positionPanel.addView(zRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        View correctionPanel = buildDewarpControls(true);

        LinearLayout mainActions = new LinearLayout(this);
        reverseInspectorButtons[REVERSE_INSPECTOR_POSITION] = button("Позиція");
        reverseInspectorButtons[REVERSE_INSPECTOR_POSITION]
                .setBackgroundColor(tabColor(true));
        reverseCalibrationButton = button("Калібрування");
        reverseCalibrationButton.setBackgroundColor(tabColor(false));
        mainActions.addView(reverseInspectorButtons[REVERSE_INSPECTOR_POSITION],
                new LinearLayout.LayoutParams(0, dp(38), 1));
        mainActions.addView(reverseCalibrationButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        editorPane.addView(mainActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        editorPane.addView(positionPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        reverseCameraStatus = statusText("Очікування AVM camera...");
        previewPane.addView(reverseCameraStatus);
        reverseCameraPreview = new ReverseCameraCompositionView(this);
        reverseCameraPreview.setAutomaticBufferQuality(
                CameraBufferQuality.load(preferences));
        reverseCameraPreview.setForceDewarpPipeline(true);
        applyReversePreviewDewarpConfigs();
        reverseCameraPreview.enablePreviewBase();
        reverseCameraPreview.setCallback(this);
        reverseCameraPreview.applyRawFallbackLayout(
                ReverseCameraController.loadRawLayout(preferences));
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        configureReversePreviewIntegratedFront();
        reverseCameraPreview.applyVisibility(
                ReverseCameraController.loadVisibilityMask(preferences));
        previewPane.addView(reverseCanvasHost(reverseCameraPreview),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout leftHost = new FrameLayout(this);
        reverseMainEditorPane = editorPane;
        leftHost.addView(editorPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        reverseCalibrationPane = buildReverseCalibrationPane(rotationRow, correctionPanel);
        reverseCalibrationPane.setVisibility(View.GONE);
        leftHost.addView(reverseCalibrationPane, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        content.addView(leftHost, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        content.addView(previewPane, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        reverseCameraEditor.setListener((layout, selectedCamera, finished) -> {
            reverseCameraLayout = layout;
            reverseCameraPreview.applyLayout(layout);
            ReverseCameraController.saveEditorSelection(preferences, selectedCamera);
            updateReversePaneControls(selectedCamera);
            if (finished) {
                ReverseCameraController.saveCompositionLayout(preferences, layout);
                CameraHelperService.reverseCameraSettingsChanged(this);
                record("reverse_layout_changed", "camera_index", selectedCamera);
            }
        });
        reverseCameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(ReverseCameraController.PREF_ENABLED, checked).apply();
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_camera_toggle", "enabled", checked);
        });
        reverseVisibilitySwitch.setOnCheckedChangeListener((button, checked) -> {
            if (reverseVisibilityUiUpdating || reverseCameraEditor == null) return;
            int cameraIndex = reverseCameraEditor.selectedCamera();
            if (cameraIndex == ReverseCameraLayout.WIDGET_PANE_ID) {
                ReverseCameraController.saveWidgetVisible(preferences, checked);
                configureReversePreviewIntegratedFront();
            } else {
                ReverseCameraController.saveVisibility(preferences, cameraIndex, checked);
            }
            int mask = ReverseCameraController.loadVisibilityMask(preferences);
            reverseCameraPreview.applyVisibility(mask);
            updateReversePaneControls(cameraIndex);
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_pane_visibility_changed",
                    "camera_index", cameraIndex, "visible", checked,
                    "visibility_mask", mask);
        });
        reverseFrontIntegrationSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (reverseVisibilityUiUpdating || reverseCameraEditor == null) return;
            int cameraIndex = reverseCameraEditor.selectedCamera();
            if (!isReverseCameraPane(cameraIndex)) return;
            ReverseCameraController.saveFrontIntegrated(
                    preferences, cameraIndex, checked);
            configureReversePreviewIntegratedFront();
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_front_integration_changed",
                    "camera_index", cameraIndex, "enabled", checked);
        });
        reset.setOnClickListener(view -> {
            ReverseCameraController.resetLayout(preferences);
            reverseCameraEditor.selectCamera(ReverseCameraLayout.REAR_CAMERA_INDEX);
            refreshCalibrationSettings("reverse_layout_reset");
        });
        reverseLowerButton.setOnClickListener(view -> changeReverseZ(false));
        reverseRaiseButton.setOnClickListener(view -> changeReverseZ(true));
        reverseCalibrationButton.setOnClickListener(view -> openReverseCalibration());
        reverseDisplayModeInput.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (reverseDisplayModeUiUpdating
                                || !ReverseCameraLayout.isValidDisplayMode(position)
                                || reverseCameraEditor == null
                                || isReverseFixedPane(
                                        reverseCameraEditor.selectedCamera())) {
                            return;
                        }
                        int cameraIndex = reverseCameraEditor.selectedCamera();
                        ReverseCameraLayout active = activeReverseCalibrationLayout();
                        if (active.pane(cameraIndex).displayMode == position) return;
                        active = ReverseCameraLayout.withDisplayMode(
                                active, cameraIndex, position);
                        setActiveReverseCalibrationLayout(
                                active, activeReverseRawCalibrationLayout());
                        renderReverseCalibrationCrop();
                        persistReverseCalibrationTransform(cameraIndex);
                        CameraHelperService.reverseCameraSettingsChanged(
                                CameraProbeActivity.this);
                        record("reverse_display_mode_changed",
                                "camera_index", cameraIndex,
                                "mode", reverseDisplayModeLabel(position));
                    }

                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        ReversePaneUiBinding restored = restoredReversePaneUiBinding(preferences);
        reverseCameraEditor.selectCamera(restored.cameraIndex);
        return root;
    }

    private View buildReverseCalibrationPane(View rotationPanel, View correctionPanel) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout sourceSelector = new LinearLayout(this);
        reverseCalibrationSourceSelector = sourceSelector;
        reverseRearCalibrationSourceButton = button("Задня");
        reverseFrontCalibrationSourceButton = button("Передня");
        reverseRearCalibrationSourceButton.setOnClickListener(
                view -> selectReverseCalibrationSource(false));
        reverseFrontCalibrationSourceButton.setOnClickListener(
                view -> selectReverseCalibrationSource(true));
        sourceSelector.addView(reverseRearCalibrationSourceButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        sourceSelector.addView(reverseFrontCalibrationSourceButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        root.addView(sourceSelector, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));

        LinearLayout stages = new LinearLayout(this);
        stages.setOrientation(LinearLayout.VERTICAL);
        LinearLayout sourceRow = new LinearLayout(this);
        sourceRow.setOrientation(LinearLayout.HORIZONTAL);

        reverseCalibrationRawMirror = new TextureView(this);
        reverseCalibrationRawMirror.setOpaque(true);
        reverseCalibrationRawOverlay = new CameraCropOverlayView(this);
        configureReverseMirror(reverseCalibrationRawMirror, true);
        sourceRow.addView(buildReverseCalibrationStage(
                "RAW", CROP_STAGE_RAW,
                reverseCalibrationRawMirror, reverseCalibrationRawOverlay),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        reverseCalibrationCorrectedMirror = new TextureView(this);
        reverseCalibrationCorrectedMirror.setOpaque(true);
        reverseCalibrationCorrectedOverlay = new CameraCropOverlayView(this);
        configureReverseMirror(reverseCalibrationCorrectedMirror, false);
        reverseCalibrationCorrectedStage = buildReverseCalibrationStage(
                "CORRECTED", CROP_STAGE_CORRECTED, reverseCalibrationCorrectedMirror,
                reverseCalibrationCorrectedOverlay);
        sourceRow.addView(reverseCalibrationCorrectedStage,
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        stages.addView(sourceRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout liveStage = new LinearLayout(this);
        liveStage.setOrientation(LinearLayout.VERTICAL);
        LinearLayout liveHeader = new LinearLayout(this);
        liveHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView liveTitle = label("LIVE");
        liveTitle.setGravity(Gravity.CENTER);
        liveHeader.addView(liveTitle, new LinearLayout.LayoutParams(0, dp(28), 1));
        liveHeader.addView(reverseDisplayModeInput,
                new LinearLayout.LayoutParams(dp(150), dp(38)));
        liveStage.addView(liveHeader);
        FrameLayout liveHost = new FrameLayout(this);
        liveHost.setBackgroundColor(Color.BLACK);
        reverseCalibrationLiveFrame = new FrameLayout(this);
        reverseCalibrationLiveFrame.setBackgroundColor(Color.BLACK);
        reverseCalibrationLivePreview = new ImageView(this);
        reverseCalibrationLivePreview.setBackgroundColor(Color.BLACK);
        reverseCalibrationLivePreview.setScaleType(ImageView.ScaleType.FIT_XY);
        reverseCalibrationLiveFrame.addView(reverseCalibrationLivePreview,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        liveHost.addView(reverseCalibrationLiveFrame,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        liveHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) ->
                fitReverseCalibrationLiveFrame());
        liveStage.addView(liveHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        stages.addView(liveStage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(stages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        FrameLayout controlHost = new FrameLayout(this);
        controlHost.addView(rotationPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        controlHost.addView(correctionPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        correctionPanel.setVisibility(View.GONE);
        reverseCalibrationNormalControls = controlHost;
        reverseCalibrationCropInputControls = buildCropInputControls(true);
        reverseCalibrationCropInputControls.setVisibility(View.GONE);
        reverseCalibrationControlsHost = new FrameLayout(this);
        reverseCalibrationControlsHost.addView(reverseCalibrationNormalControls,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        reverseCalibrationControlsHost.addView(reverseCalibrationCropInputControls,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(reverseCalibrationControlsHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));
        LinearLayout actions = new LinearLayout(this);
        Button rotation = button("Поворот");
        Button correction = button("Корекція");
        Button savePreset = button("Зберегти");
        reversePresetLoadButton = button("Завантажити");
        reverseMirrorButton = button("Перенести →");
        reverseMirrorButton.setTextSize(10);
        Button back = button("Назад");
        rotation.setOnClickListener(view -> {
            cancelReverseCropInput();
            rotationPanel.setVisibility(View.VISIBLE);
            correctionPanel.setVisibility(View.GONE);
            rotation.setBackgroundColor(tabColor(true));
            correction.setBackgroundColor(tabColor(false));
        });
        correction.setOnClickListener(view -> {
            cancelReverseCropInput();
            rotationPanel.setVisibility(View.GONE);
            correctionPanel.setVisibility(View.VISIBLE);
            rotation.setBackgroundColor(tabColor(false));
            correction.setBackgroundColor(tabColor(true));
        });
        back.setOnClickListener(view -> closeReverseCalibration());
        savePreset.setOnClickListener(view -> saveReverseCalibrationPreset());
        reversePresetLoadButton.setOnClickListener(
                view -> loadReverseCalibrationPreset());
        reverseMirrorButton.setOnClickListener(view -> mirrorReverseCalibration());
        rotation.setBackgroundColor(tabColor(true));
        correction.setBackgroundColor(tabColor(false));
        savePreset.setBackgroundColor(tabColor(false));
        reversePresetLoadButton.setBackgroundColor(tabColor(false));
        reverseMirrorButton.setBackgroundColor(tabColor(false));
        back.setBackgroundColor(tabColor(false));
        actions.addView(rotation, new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(correction, new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(savePreset, new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(reversePresetLoadButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(reverseMirrorButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        actions.addView(back, new LinearLayout.LayoutParams(0, dp(38), 1));
        root.addView(actions);

        reverseCalibrationRawOverlay.setListener((crop, finished) -> {
            if (reverseCalibrationCameraIndex <= 0) return;
            applyReverseCalibrationCrop(reverseRect(crop), false, finished);
        });
        reverseCalibrationCorrectedOverlay.setListener((crop, finished) -> {
            if (reverseCalibrationCameraIndex <= 0) return;
            CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
            CalibrationUiState ui = calibrationUiState(
                    dewarp.enabled, reverseCameraPreview.editorUsesRawFallback(
                            reverseCalibrationCameraIndex));
            if (!ui.correctedEditable) return;
            applyReverseCalibrationCrop(reverseRect(crop), true, finished);
        });
        return root;
    }

    private View buildReverseCalibrationStage(
            String title, int stageIndex,
            TextureView preview, CameraCropOverlayView overlay) {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.addView(buildCropHeader(title, true, stageIndex),
                new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(Color.BLACK);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(Color.BLACK);
        frame.setClipChildren(true);
        frame.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        host.addView(frame, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        host.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitAspectFrame(
                host, frame, DirectCameraCrop.SOURCE_WIDTH / DirectCameraCrop.SOURCE_HEIGHT));
        stage.addView(host, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return stage;
    }

    private void configureReverseMirror(TextureView mirror, boolean raw) {
        mirror.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(
                        BlindSpotCameraView.BUFFER_WIDTH, BlindSpotCameraView.BUFFER_HEIGHT);
                if (reverseCalibrationCameraIndex <= 0) return;
                if (raw) reverseCameraPreview.setEditorRawMirror(
                        reverseCalibrationCameraIndex, texture);
                else reverseCameraPreview.setEditorCorrectedMirror(
                        reverseCalibrationCameraIndex, texture);
                startReverseCalibrationCopies();
            }

            @Override
            public void onSurfaceTextureSizeChanged(
                    SurfaceTexture texture, int width, int height) {
                texture.setDefaultBufferSize(
                        BlindSpotCameraView.BUFFER_WIDTH, BlindSpotCameraView.BUFFER_HEIGHT);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                stopReverseCalibrationCopies(true);
                if (reverseCalibrationCameraIndex > 0) {
                    if (raw) reverseCameraPreview.setEditorRawMirror(
                            reverseCalibrationCameraIndex, null);
                    else reverseCameraPreview.setEditorCorrectedMirror(
                            reverseCalibrationCameraIndex, null);
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                if (reverseCalibrationFreshness.markMirrorFresh(
                        activeActivityCameraRequestId,
                        reverseCalibrationCameraIndex,
                        reverseCalibrationFront, raw)) {
                    startReverseCalibrationCopies();
                }
            }
        });
    }

    private void openReverseCalibration() {
        if (reverseCameraEditor == null) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (isReverseFixedPane(cameraIndex)) return;
        stopReverseCalibrationCopies(true);
        reverseCalibrationCameraIndex = cameraIndex;
        reverseCalibrationFront = false;
        reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
        reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
        if (reverseFrontRawCalibrationLayout == null) {
            reverseFrontRawCalibrationLayout =
                    ReverseCameraController.loadFrontRawLayout(preferences);
        }
        if (reverseFrontCameraLayout == null) {
            reverseFrontCameraLayout = ReverseCameraController.loadFrontLayout(preferences);
        }
        applyReversePreviewDewarpConfigs();
        reverseCameraPreview.applyVisibility(
                ReverseCameraController.loadVisibilityMask(preferences));
        if (reverseCalibrationSourceSelector != null) {
            reverseCalibrationSourceSelector.setVisibility(
                    isReverseCameraPane(cameraIndex) ? View.VISIBLE : View.GONE);
        }
        updateReverseCalibrationSourceButtons();
        reverseMainEditorPane.setVisibility(View.GONE);
        reverseCalibrationPane.setVisibility(View.VISIBLE);
        reverseCameraEditor.setEditable(false);
        attachReverseCalibrationMirrors();
        updateReverseCalibrationDisplay();
        startReverseCalibrationCopies();
    }

    private void closeReverseCalibration() {
        cancelReverseCropInput();
        stopReverseCalibrationCopies(true);
        if (reverseCalibrationCameraIndex > 0) {
            reverseCameraPreview.setEditorRawMirror(reverseCalibrationCameraIndex, null);
            reverseCameraPreview.setEditorCorrectedMirror(reverseCalibrationCameraIndex, null);
        }
        reverseCalibrationCameraIndex = -1;
        reverseCalibrationFront = false;
        if (reverseCameraPreview != null) {
            reverseCameraPreview.setSideMode(ReverseSideSelectorView.MODE_REAR);
            // Drop any temporary central-front visibility used while calibrating
            // that source; the persisted integration toggle remains authoritative.
            configureReversePreviewIntegratedFront();
        }
        if (reverseCalibrationPane != null) reverseCalibrationPane.setVisibility(View.GONE);
        if (reverseMainEditorPane != null) reverseMainEditorPane.setVisibility(View.VISIBLE);
        if (reverseCameraEditor != null) reverseCameraEditor.setEditable(true);
    }

    private void selectReverseCalibrationSource(boolean front) {
        if (reverseCalibrationCameraIndex <= 0
                || (front && !isReverseCameraPane(reverseCalibrationCameraIndex))) return;
        cancelReverseCropInput();
        stopReverseCalibrationCopies(true);
        // Detach mirrors from the currently active source before changing side.
        // The same logical center index maps to Rear or physical pano_h index 4
        // in Front mode; leaving the old binding attached keeps a stale producer
        // alive and can make LIVE preview follow the previous source.
        if (reverseCalibrationRawMirror.isAvailable()) {
            reverseCameraPreview.setEditorRawMirror(
                    reverseCalibrationCameraIndex, null);
        }
        if (reverseCalibrationCorrectedMirror.isAvailable()) {
            reverseCameraPreview.setEditorCorrectedMirror(
                    reverseCalibrationCameraIndex, null);
        }
        reverseCalibrationFront = front;
        applyReversePreviewDewarpConfigs();
        // Rebind the live mirror to the selected source pane.  Central Front
        // uses physical index 4 while Rear remains on index 1.
        attachReverseCalibrationMirrors();
        updateReverseCalibrationSourceButtons();
        updateReverseCalibrationDisplay();
    }

    private void updateReverseCalibrationSourceButtons() {
        if (reverseRearCalibrationSourceButton != null) {
            reverseRearCalibrationSourceButton.setBackgroundColor(
                    tabColor(!reverseCalibrationFront));
        }
        if (reverseFrontCalibrationSourceButton != null) {
            reverseFrontCalibrationSourceButton.setBackgroundColor(
                    tabColor(reverseCalibrationFront));
            reverseFrontCalibrationSourceButton.setEnabled(
                    isReverseCameraPane(reverseCalibrationCameraIndex));
        }
    }

    private void attachReverseCalibrationMirrors() {
        if (reverseCalibrationCameraIndex <= 0) return;
        if (reverseCalibrationRawMirror.isAvailable()) {
            reverseCameraPreview.setEditorRawMirror(
                    reverseCalibrationCameraIndex,
                    reverseCalibrationRawMirror.getSurfaceTexture());
        }
        if (reverseCalibrationCorrectedMirror.isAvailable()) {
            reverseCameraPreview.setEditorCorrectedMirror(
                    reverseCalibrationCameraIndex,
                    reverseCalibrationCorrectedMirror.getSurfaceTexture());
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

    private FrameLayout reverseCanvasHost(View child) {
        FrameLayout host = new FrameLayout(this);
        host.setBackgroundColor(Color.rgb(28, 28, 28));
        host.addView(child, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        host.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            int availableWidth = right - left;
            int availableHeight = bottom - top;
            if (availableWidth <= 0 || availableHeight <= 0) return;
            float aspect = 1920.0f / 990.0f;
            int width = Math.min(availableWidth, Math.round(availableHeight * aspect));
            int height = Math.min(availableHeight, Math.round(width / aspect));
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            params.width = Math.max(1, width);
            params.height = Math.max(1, height);
            params.gravity = Gravity.CENTER;
            child.setLayoutParams(params);
        });
        return host;
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

    static ReversePaneUiBinding restoredReversePaneUiBinding(SharedPreferences preferences) {
        return reversePaneUiBinding(
                preferences, ReverseCameraController.loadEditorSelection(preferences));
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

    static boolean isReverseSideCamera(int cameraIndex) {
        return cameraIndex == ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX
                || cameraIndex == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX;
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

    private static String reverseDisplayModeLabel(int mode) {
        if (mode == ReverseCameraLayout.DISPLAY_MODE_FILL) return "Fill";
        if (mode == ReverseCameraLayout.DISPLAY_MODE_STRETCH) return "Stretch";
        return "Fit";
    }

    private void updateReverseRotation(int degrees) {
        if (reverseRotationUiUpdating || reverseCameraEditor == null
                || isReverseFixedPane(reverseCameraEditor.selectedCamera())) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        int safeDegrees = CameraRotation.clamp(degrees);
        ReverseCameraLayout active = ReverseCameraLayout.withRotation(
                activeReverseCalibrationLayout(), cameraIndex, safeDegrees);
        setActiveReverseCalibrationLayout(active, activeReverseRawCalibrationLayout());
        reverseRotationValue.setText(safeDegrees + "°");
        renderReverseCalibrationCrop();
    }

    private void toggleReverseOutputMirror() {
        if (reverseCameraEditor == null || reverseCameraLayout == null) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (isReverseFixedPane(cameraIndex)) return;
        ReverseCameraLayout active = activeReverseCalibrationLayout();
        ReverseCameraLayout raw = activeReverseRawCalibrationLayout();
        boolean mirror = !active.pane(cameraIndex).mirrorHorizontally;
        active = ReverseCameraLayout.withMirrorHorizontally(active, cameraIndex, mirror);
        raw = ReverseCameraLayout.withMirrorHorizontally(raw, cameraIndex, mirror);
        setActiveReverseCalibrationLayout(active, raw);
        if (reverseCalibrationFront) updateReverseCalibrationDisplay();
        else updateReversePaneControls(cameraIndex);
        renderReverseCalibrationCrop();
        persistReverseCalibrationTransform(cameraIndex);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_output_mirror_changed", "camera_index", cameraIndex,
                "mirror", mirror);
    }

    private void persistReverseRotation() {
        if (reverseCameraEditor == null
                || isReverseFixedPane(reverseCameraEditor.selectedCamera())) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        persistReverseCalibrationTransform(cameraIndex);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_rotation_applied", "camera_index", cameraIndex,
                "degrees", activeReverseCalibrationLayout()
                        .pane(cameraIndex).rotationDegrees);
    }

    private void persistReverseCalibrationTransform(int cameraIndex) {
        if (reverseCalibrationFront) {
            ReverseCameraLayout.Pane pane = reverseFrontCameraLayout.pane(cameraIndex);
            ReverseCameraController.saveFrontPaneTransform(
                    preferences, cameraIndex, pane.rotationDegrees,
                    pane.displayMode, pane.mirrorHorizontally);
        } else {
            ReverseCameraLayout.Pane pane = reverseCameraLayout.pane(cameraIndex);
            ReverseCameraController.saveRearPaneTransform(
                    preferences, cameraIndex, pane.rotationDegrees,
                    pane.displayMode, pane.mirrorHorizontally);
        }
    }

    private void changeReverseZ(boolean raise) {
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (isReverseFixedPane(cameraIndex)) return;
        reverseCameraLayout = raise
                ? ReverseCameraLayout.raise(reverseCameraLayout, cameraIndex)
                : ReverseCameraLayout.lower(reverseCameraLayout, cameraIndex);
        ReverseCameraController.saveCompositionLayout(preferences, reverseCameraLayout);
        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        updateReversePaneControls(cameraIndex);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_z_changed", "camera_index", cameraIndex,
                "action", raise ? "raise" : "lower");
    }

    private View buildCameraPanel() {
        BlindSpotOverlayController.migrateOverlayPreferences(preferences);
        loadCameraProfiles();
        int savedCamera = preferences.getInt("camera_selected_profile", CameraProfile.REAR_LEFT);
        selectedCameraId = CameraProfile.isValid(savedCamera)
                ? savedCamera : CameraProfile.REAR_LEFT;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(0, dp(8), 0, 0);

        LinearLayout settingsPane = new LinearLayout(this);
        settingsPane.setOrientation(LinearLayout.VERTICAL);
        settingsPane.setPadding(0, 0, dp(12), 0);

        LinearLayout groupRow = new LinearLayout(this);
        cameraRearGroupButton = button("Задні камери");
        cameraFrontGroupButton = button("Передні камери");
        groupRow.addView(cameraRearGroupButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        groupRow.addView(cameraFrontGroupButton, new LinearLayout.LayoutParams(0, dp(44), 1));
        settingsPane.addView(groupRow);
        cameraCalibrationButton = button("Калібрування");
        settingsPane.addView(cameraCalibrationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        FrameLayout policyHost = new FrameLayout(this);
        LinearLayout rearPolicy = new LinearLayout(this);
        rearPolicy.setOrientation(LinearLayout.VERTICAL);
        cameraSwitch = new Switch(this);
        cameraSwitch.setText("Задні камери за поворотником");
        cameraSwitch.setTextColor(Color.WHITE);
        cameraSwitch.setTextSize(17);
        cameraSwitch.setChecked(preferences.getBoolean(
                BlindSpotOverlayController.PREF_ENABLED, false));
        rearPolicy.addView(cameraSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView minSpeedLabel = label("Мін. швидкість для задніх камер");
        minSpeedLabel.setTextSize(15);
        minSpeedLabel.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.addView(minSpeedLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        cameraMinSpeedInput = numberInput(preferences.getInt(
                BlindSpotOverlayController.PREF_MIN_SPEED, DEFAULT_CAMERA_MIN_SPEED_KPH));
        cameraMinSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        speedRow.addView(cameraMinSpeedInput, new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView minSpeedUnit = label("км/год");
        minSpeedUnit.setTextSize(15);
        minSpeedUnit.setGravity(Gravity.CENTER);
        speedRow.addView(minSpeedUnit, new LinearLayout.LayoutParams(dp(72), dp(46)));
        rearPolicy.addView(speedRow);

        LinearLayout maxSpeedRow = new LinearLayout(this);
        maxSpeedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView maxSpeedLabel = label("Макс. швидкість для задніх камер");
        maxSpeedLabel.setTextSize(15);
        maxSpeedRow.addView(maxSpeedLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        cameraMaxSpeedInput = numberInput(preferences.getInt(
                BlindSpotOverlayController.PREF_MAX_SPEED, DEFAULT_CAMERA_MAX_SPEED_KPH));
        cameraMaxSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxSpeedRow.addView(cameraMaxSpeedInput,
                new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView maxSpeedUnit = label("км/год");
        maxSpeedUnit.setTextSize(15);
        maxSpeedUnit.setGravity(Gravity.CENTER);
        maxSpeedRow.addView(maxSpeedUnit, new LinearLayout.LayoutParams(dp(72), dp(46)));
        rearPolicy.addView(maxSpeedRow);

        LinearLayout rearTriggerRow = new LinearLayout(this);
        rearTriggerRow.setOrientation(LinearLayout.HORIZONTAL);
        rearTriggerRow.setGravity(Gravity.CENTER_VERTICAL);
        rearSharpTurnSwitch = new Switch(this);
        rearSharpTurnSwitch.setText("Різкий поворот");
        rearSharpTurnSwitch.setTextColor(Color.WHITE);
        rearSharpTurnSwitch.setTextSize(14);
        rearSharpTurnSwitch.setChecked(preferences.getBoolean(
                BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED, false));
        rearTriggerRow.addView(rearSharpTurnSwitch,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        rearSharpTurnAngleInput = numberInput(Math.round(preferences.getFloat(
                BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE,
                BlindSpotOverlayController.DEFAULT_REAR_SHARP_TURN_ANGLE_DEG)));
        rearSharpTurnAngleInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        rearTriggerRow.addView(rearSharpTurnAngleInput,
                new LinearLayout.LayoutParams(dp(72), dp(42)));
        TextView rearAngleUnit = label("°");
        rearAngleUnit.setTextSize(15);
        rearAngleUnit.setGravity(Gravity.CENTER);
        rearTriggerRow.addView(rearAngleUnit,
                new LinearLayout.LayoutParams(dp(32), dp(46)));
        rearBsdOnlySwitch = new Switch(this);
        rearBsdOnlySwitch.setText("Лише BSD");
        rearBsdOnlySwitch.setTextColor(Color.WHITE);
        rearBsdOnlySwitch.setTextSize(14);
        rearBsdOnlySwitch.setChecked(preferences.getBoolean(
                BlindSpotOverlayController.PREF_REAR_BSD_ONLY, false));
        rearTriggerRow.addView(rearBsdOnlySwitch,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        rearPolicy.addView(rearTriggerRow);

        LinearLayout warningRow = new LinearLayout(this);
        warningRow.setOrientation(LinearLayout.HORIZONTAL);
        warningRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView warningLabel = label("Підсвітка сліпої зони");
        warningLabel.setTextSize(15);
        warningLabel.setGravity(Gravity.CENTER_VERTICAL);
        warningRow.addView(warningLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        cameraWarningModeInput = new Spinner(this);
        String[] warningModes = {"Вимкнена", "Постійно", "Пульсація"};
        ArrayAdapter<String> warningAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, warningModes);
        warningAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraWarningModeInput.setAdapter(warningAdapter);
        int initialWarningMode = BlindSpotOverlayController.readWarningMode(preferences);
        cameraWarningModeInput.setSelection(initialWarningMode, false);
        warningRow.addView(cameraWarningModeInput,
                new LinearLayout.LayoutParams(dp(176), dp(46)));
        rearPolicy.addView(warningRow);
        rearCameraPolicyStatus = statusText("");
        rearCameraPolicyStatus.setVisibility(View.GONE);
        rearPolicy.addView(rearCameraPolicyStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
        rearCameraControlPane = rearPolicy;
        policyHost.addView(rearPolicy, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout frontPolicy = new LinearLayout(this);
        frontPolicy.setOrientation(LinearLayout.VERTICAL);
        frontCameraSwitch = new Switch(this);
        frontCameraSwitch.setText("Передні камери");
        frontCameraSwitch.setTextColor(Color.WHITE);
        frontCameraSwitch.setTextSize(17);
        frontCameraSwitch.setChecked(preferences.getBoolean(
                PREF_FRONT_CAMERA_ENABLED, false));
        frontPolicy.addView(frontCameraSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout frontSpeedRow = new LinearLayout(this);
        frontSpeedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView frontSpeedLabel = label("Мін. швидкість для передніх камер");
        frontSpeedLabel.setTextSize(15);
        frontSpeedRow.addView(frontSpeedLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        frontCameraMinSpeedInput = numberInput(preferences.getInt(
                PREF_FRONT_CAMERA_MIN_SPEED, DEFAULT_FRONT_CAMERA_MIN_SPEED_KPH));
        frontCameraMinSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        frontSpeedRow.addView(frontCameraMinSpeedInput,
                new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView frontSpeedUnit = label("км/год");
        frontSpeedUnit.setTextSize(15);
        frontSpeedUnit.setGravity(Gravity.CENTER);
        frontSpeedRow.addView(frontSpeedUnit, new LinearLayout.LayoutParams(dp(72), dp(46)));
        frontPolicy.addView(frontSpeedRow);

        LinearLayout frontMaxSpeedRow = new LinearLayout(this);
        frontMaxSpeedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView frontMaxSpeedLabel = label("Макс. швидкість для передніх камер");
        frontMaxSpeedLabel.setTextSize(15);
        frontMaxSpeedRow.addView(frontMaxSpeedLabel,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        frontCameraMaxSpeedInput = numberInput(preferences.getInt(
                PREF_FRONT_CAMERA_MAX_SPEED, DEFAULT_FRONT_CAMERA_MAX_SPEED_KPH));
        frontCameraMaxSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        frontMaxSpeedRow.addView(frontCameraMaxSpeedInput,
                new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView frontMaxSpeedUnit = label("км/год");
        frontMaxSpeedUnit.setTextSize(15);
        frontMaxSpeedUnit.setGravity(Gravity.CENTER);
        frontMaxSpeedRow.addView(frontMaxSpeedUnit,
                new LinearLayout.LayoutParams(dp(72), dp(46)));
        frontPolicy.addView(frontMaxSpeedRow);

        LinearLayout frontAngleRow = new LinearLayout(this);
        frontAngleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView frontAngleLabel = label("Мін. кут керма");
        frontAngleLabel.setTextSize(15);
        frontAngleRow.addView(frontAngleLabel, new LinearLayout.LayoutParams(0, dp(46), 1));
        frontCameraMinAngleInput = numberInput(Math.round(preferences.getFloat(
                PREF_FRONT_CAMERA_MIN_ANGLE, DEFAULT_FRONT_CAMERA_MIN_ANGLE_DEG)));
        frontCameraMinAngleInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        frontAngleRow.addView(frontCameraMinAngleInput,
                new LinearLayout.LayoutParams(dp(84), dp(44)));
        TextView frontAngleUnit = label("град.");
        frontAngleUnit.setTextSize(15);
        frontAngleUnit.setGravity(Gravity.CENTER);
        frontAngleRow.addView(frontAngleUnit, new LinearLayout.LayoutParams(dp(72), dp(46)));
        frontPolicy.addView(frontAngleRow);

        frontTurnRequiredSwitch = new Switch(this);
        frontTurnRequiredSwitch.setText("Обов'язково поворотник для передніх камер");
        frontTurnRequiredSwitch.setTextColor(Color.WHITE);
        frontTurnRequiredSwitch.setTextSize(15);
        frontTurnRequiredSwitch.setChecked(preferences.getBoolean(
                PREF_FRONT_CAMERA_TURN_REQUIRED, true));
        frontPolicy.addView(frontTurnRequiredSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        frontCameraPolicyStatus = statusText("");
        frontCameraPolicyStatus.setVisibility(View.GONE);
        frontPolicy.addView(frontCameraPolicyStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
        frontCameraControlPane = frontPolicy;
        policyHost.addView(frontPolicy, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        settingsPane.addView(policyHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(264)));

        LinearLayout cameraSide = new LinearLayout(this);
        cameraSide.setOrientation(LinearLayout.HORIZONTAL);
        cameraLeftPositionButton = button("Ліва камера");
        cameraRightPositionButton = button("Права камера");
        cameraSide.addView(cameraLeftPositionButton,
                new LinearLayout.LayoutParams(0, dp(54), 1));
        cameraSide.addView(cameraRightPositionButton,
                new LinearLayout.LayoutParams(0, dp(54), 1));
        settingsPane.addView(cameraSide, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout targetRow = new LinearLayout(this);
        targetRow.setOrientation(LinearLayout.HORIZONTAL);
        cameraTabletTargetButton = button("На планшеті");
        cameraClusterTargetButton = button("На приборці");
        targetRow.addView(cameraTabletTargetButton,
                new LinearLayout.LayoutParams(0, dp(54), 1));
        targetRow.addView(cameraClusterTargetButton,
                new LinearLayout.LayoutParams(0, dp(54), 1));
        settingsPane.addView(targetRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleLabel = label("Розмір");
        scaleLabel.setTextSize(15);
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(dp(78), dp(54)));
        cameraScaleInput = new SeekBar(this);
        cameraScaleInput.setMax(BlindSpotOverlayController.MAX_SCALE_PERCENT
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        cameraScaleInput.setProgress(cameraScale[selectedCameraId]
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        scaleRow.addView(cameraScaleInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        cameraScaleValue = label(cameraScale[selectedCameraId] + "%");
        cameraScaleValue.setGravity(Gravity.CENTER);
        scaleRow.addView(cameraScaleValue,
                new LinearLayout.LayoutParams(dp(64), dp(54)));
        settingsPane.addView(scaleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));

        LinearLayout previewPane = new LinearLayout(this);
        previewPane.setOrientation(LinearLayout.VERTICAL);
        previewPane.setPadding(dp(12), 0, 0, 0);
        cameraStatus = statusText("Запуск внутрішньої служби...");
        previewPane.addView(cameraStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));

        previewPane.addView(buildProductionPreview(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        panel.addView(settingsPane, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.42f));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(70, 70, 70));
        panel.addView(divider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(previewPane, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 0.58f));

        cameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(BlindSpotOverlayController.PREF_ENABLED, checked).apply();
            record("camera_toggle", "enabled", checked);
            if (!checked) closeCamera("camera_disabled");
            hideCameraPolicyStatus(rearCameraPolicyStatus);
            CameraHelperService.cameraSettingsChanged(this);
            updateControls();
        });
        cameraMinSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveRearCameraSpeedRange();
        });
        cameraMaxSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveRearCameraSpeedRange();
        });
        rearSharpTurnSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ENABLED, checked).apply();
            record("rear_sharp_turn_setting", "enabled", checked);
            CameraHelperService.cameraTriggerSettingsChanged(this);
            updateControls();
        });
        rearSharpTurnAngleInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveRearTriggerPolicy();
        });
        rearBsdOnlySwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(
                    BlindSpotOverlayController.PREF_REAR_BSD_ONLY, checked).apply();
            record("rear_bsd_only_setting", "enabled", checked);
            CameraHelperService.cameraTriggerSettingsChanged(this);
        });
        frontCameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_FRONT_CAMERA_ENABLED, checked).apply();
            record("front_camera_toggle", "enabled", checked);
            hideCameraPolicyStatus(frontCameraPolicyStatus);
            CameraHelperService.cameraSettingsChanged(this);
            updateControls();
        });
        frontCameraMinSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveFrontCameraPolicy();
        });
        frontCameraMaxSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveFrontCameraPolicy();
        });
        frontCameraMinAngleInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveFrontCameraPolicy();
        });
        frontTurnRequiredSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(PREF_FRONT_CAMERA_TURN_REQUIRED, checked).apply();
            record("front_camera_turn_required", "enabled", checked);
            CameraHelperService.cameraSettingsChanged(this);
        });
        cameraWarningModeInput.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (!BlindSpotOverlayController.isWarningMode(position)
                                || position == BlindSpotOverlayController.readWarningMode(
                                        preferences)) {
                            return;
                        }
                        preferences.edit()
                                .putInt(BlindSpotOverlayController.PREF_WARNING_MODE, position)
                                .apply();
                        record("camera_warning_setting", "mode", position,
                                "mode_name", warningModes[position]);
                        CameraHelperService.cameraWarningSettingsChanged(
                                CameraProbeActivity.this);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {}
                });
        cameraScaleInput.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int scale = BlindSpotOverlayController.MIN_SCALE_PERCENT + progress;
                cameraScale[selectedCameraId] = scale;
                cameraScaleValue.setText(scale + "%");
                updateCameraPositionHandle();
                updateProductionPreviewSize();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                saveOverlayPlacement();
            }
        });
        cameraRearGroupButton.setOnClickListener(
                view -> selectCameraGroup(CameraProfile.GROUP_REAR));
        cameraFrontGroupButton.setOnClickListener(
                view -> selectCameraGroup(CameraProfile.GROUP_FRONT));
        cameraLeftPositionButton.setOnClickListener(view -> selectCameraProfile(
                CameraProfile.of(selectedCameraId).front()
                        ? CameraProfile.FRONT_LEFT : CameraProfile.REAR_LEFT, true));
        cameraRightPositionButton.setOnClickListener(view -> selectCameraProfile(
                CameraProfile.of(selectedCameraId).front()
                        ? CameraProfile.FRONT_RIGHT : CameraProfile.REAR_RIGHT, true));
        cameraTabletTargetButton.setOnClickListener(
                view -> selectCameraTarget(CameraDisplayTarget.TABLET));
        cameraClusterTargetButton.setOnClickListener(
                view -> selectCameraTarget(CameraDisplayTarget.CLUSTER));
        cameraPreviewFrame.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    dragStartRawX = event.getRawX();
                    dragStartRawY = event.getRawY();
                    dragStartX = view.getX();
                    dragStartY = view.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    moveCameraPositionHandle(
                            dragStartX + event.getRawX() - dragStartRawX,
                            dragStartY + event.getRawY() - dragStartRawY);
                    captureCameraPositionHandle();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    captureCameraPositionHandle();
                    saveOverlayPlacement();
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    view.performClick();
                    return true;
                default:
                    return false;
            }
        });
        cameraCalibrationButton.setOnClickListener(view -> openSharedCalibration(
                TAB_CAMERAS, false, selectedCameraId));
        selectCameraProfile(selectedCameraId, false);
        return panel;
    }

    private View buildParkingCameraPanel() {
        ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
        loadParkingCameraProfiles();
        int savedParkingCamera = preferences.getInt(
                "parking_camera_selected_profile", ParkingCameraProfile.FL);
        selectedParkingCameraId = ParkingCameraProfile.isValid(savedParkingCamera)
                ? savedParkingCamera : ParkingCameraProfile.FL;
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(0, dp(8), 0, 0);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(0, 0, dp(12), 0);
        LinearLayout selectors = new LinearLayout(this);
        selectors.setOrientation(PARKING_SELECTOR_ORIENTATION);
        for (int id = 0; id < ParkingCameraProfile.COUNT; id++) {
            final int selectedId = id;
            parkingCameraButtons[id] = button(calibrationLabel(true, id));
            parkingCameraButtons[id].setTextSize(11);
            parkingCameraButtons[id].setOnClickListener(view -> {
                if (selectedId != selectedParkingCameraId) {
                    saveParkingRule();
                    saveParkingMaxSpeed();
                }
                selectParkingCamera(selectedId);
            });
            selectors.addView(parkingCameraButtons[id], new LinearLayout.LayoutParams(
                    0, dp(46), PARKING_SELECTOR_BUTTON_WEIGHT));
        }
        controls.addView(selectors, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout bulkActions = new LinearLayout(this);
        bulkActions.setOrientation(LinearLayout.HORIZONTAL);
        parkingEnableAllButton = button("Увімкнути все");
        parkingDisableAllButton = button("Вимкнути все");
        bulkActions.addView(parkingEnableAllButton, new LinearLayout.LayoutParams(
                0, dp(46), 1));
        bulkActions.addView(parkingDisableAllButton, new LinearLayout.LayoutParams(
                0, dp(46), 1));
        controls.addView(bulkActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        parkingAllowDuringReverseSwitch = new Switch(this);
        parkingAllowDuringReverseSwitch.setText("Вмикати разом із камерами заднього ходу");
        parkingAllowDuringReverseSwitch.setTextColor(Color.WHITE);
        parkingAllowDuringReverseSwitch.setTextSize(16);
        LinearLayout.LayoutParams allowDuringReverseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        allowDuringReverseParams.bottomMargin = dp(8);
        controls.addView(parkingAllowDuringReverseSwitch, allowDuringReverseParams);

        parkingCameraSwitch = new Switch(this);
        parkingCameraSwitch.setText("Камера увімкнена");
        parkingCameraSwitch.setTextColor(Color.WHITE);
        controls.addView(parkingCameraSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout distanceRow = new LinearLayout(this);
        distanceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView distanceLabel = label("Поріг");
        distanceRow.addView(distanceLabel, new LinearLayout.LayoutParams(0, dp(44), 1));
        parkingDistanceInput = numberInput(settings.rule(selectedParkingCameraId).distanceCm);
        parkingDistanceInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        distanceRow.addView(parkingDistanceInput, new LinearLayout.LayoutParams(dp(80), dp(42)));
        distanceRow.addView(label("см"), new LinearLayout.LayoutParams(dp(40), dp(42)));
        controls.addView(distanceRow);

        parkingAddCentralSwitch = new Switch(this);
        parkingAddCentralSwitch.setText("Додати центральну камеру");
        parkingAddCentralSwitch.setTextColor(Color.WHITE);
        controls.addView(parkingAddCentralSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout maxSpeedRow = new LinearLayout(this);
        maxSpeedRow.setGravity(Gravity.CENTER_VERTICAL);
        maxSpeedRow.addView(label("Макс. швидкість"), new LinearLayout.LayoutParams(0, dp(44), 1));
        parkingMaxSpeedInput = numberInput(settings.maxSpeedKph());
        parkingMaxSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        maxSpeedRow.addView(parkingMaxSpeedInput, new LinearLayout.LayoutParams(dp(80), dp(42)));
        maxSpeedRow.addView(label("км/год"), new LinearLayout.LayoutParams(dp(64), dp(42)));
        controls.addView(maxSpeedRow);

        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        scaleRow.addView(label("Розмір"), new LinearLayout.LayoutParams(dp(78), dp(48)));
        parkingScaleInput = new SeekBar(this);
        parkingScaleInput.setMax(BlindSpotOverlayController.MAX_SCALE_PERCENT
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        scaleRow.addView(parkingScaleInput, new LinearLayout.LayoutParams(0, dp(48), 1));
        parkingScaleValue = label(ParkingCameraSettings.DEFAULT_SCALE_PERCENT + "%");
        parkingScaleValue.setGravity(Gravity.CENTER);
        scaleRow.addView(parkingScaleValue, new LinearLayout.LayoutParams(dp(56), dp(48)));
        controls.addView(scaleRow);

        parkingScaleSyncSwitch = new Switch(this);
        parkingScaleSyncSwitch.setText("Синхронізувати");
        parkingScaleSyncSwitch.setTextColor(Color.WHITE);
        controls.addView(parkingScaleSyncSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        parkingCalibrationButton = button("Калібрування");
        controls.addView(parkingCalibrationButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.addView(label("Розміщення на планшеті"), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));
        parkingPositionHost = new FrameLayout(this);
        parkingPositionHost.setBackgroundColor(Color.BLACK);
        parkingPositionWidget = new FrameLayout(this);
        parkingPositionWidget.setClipChildren(true);
        parkingPositionWidget.setBackgroundColor(Color.rgb(32, 32, 32));
        parkingPositionFrame = new FrameLayout(this);
        parkingPositionFrame.setBackgroundColor(Color.rgb(60, 60, 60));
        parkingPositionHandle = label("Камера");
        parkingPositionHandle.setTextColor(Color.WHITE);
        parkingPositionHandle.setGravity(Gravity.CENTER);
        parkingPositionHandle.setBackgroundColor(Color.rgb(70, 110, 150));
        parkingPositionFrame.addView(parkingPositionHandle, new FrameLayout.LayoutParams(
                dp(120), dp(80)));
        parkingPositionWidget.addView(parkingPositionFrame, new FrameLayout.LayoutParams(
                dp(1), dp(1)));
        parkingPositionHost.addView(parkingPositionWidget,
                new FrameLayout.LayoutParams(dp(1), dp(1), Gravity.CENTER));
        parkingPositionHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateParkingPositionCanvasSize());
        parkingPositionWidget.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateParkingPositionHandle());
        preview.addView(parkingPositionHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        parkingPositionFrame.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                    dragStartRawX = event.getRawX();
                    dragStartRawY = event.getRawY();
                    dragStartX = view.getX();
                    dragStartY = view.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    moveParkingPositionHandle(dragStartX + event.getRawX() - dragStartRawX,
                            dragStartY + event.getRawY() - dragStartRawY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    captureParkingPositionHandle();
                    saveParkingPlacement();
                    view.getParent().requestDisallowInterceptTouchEvent(false);
                    return true;
                default:
                    return false;
            }
        });

        panel.addView(controls, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, PARKING_SETTINGS_WEIGHT));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(70, 70, 70));
        panel.addView(divider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(preview, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, PARKING_PREVIEW_WEIGHT));

        parkingCameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (parkingUiUpdating) return;
            ParkingCameraSettings.Rule rule = ParkingCameraSettings.readRule(
                    preferences, ParkingCameraProfile.of(selectedParkingCameraId));
            settings.setRule(ParkingCameraProfile.of(selectedParkingCameraId),
                    rule.withEnabled(checked));
            notifyParkingSettingsChanged();
        });
        parkingEnableAllButton.setOnClickListener(view -> setAllParkingCamerasEnabled(settings, true));
        parkingDisableAllButton.setOnClickListener(view -> setAllParkingCamerasEnabled(settings, false));
        parkingAllowDuringReverseSwitch.setChecked(settings.allowDuringReverse());
        parkingAllowDuringReverseSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (parkingUiUpdating) return;
            settings.setAllowDuringReverse(checked);
            notifyParkingSettingsChanged();
        });
        parkingAddCentralSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (parkingUiUpdating) return;
            ParkingCameraSettings.Rule rule = ParkingCameraSettings.readRule(
                    preferences, ParkingCameraProfile.of(selectedParkingCameraId));
            settings.setRule(ParkingCameraProfile.of(selectedParkingCameraId),
                    rule.withAddCentral(checked));
            notifyParkingSettingsChanged();
        });
        parkingDistanceInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveParkingRule();
        });
        parkingMaxSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveParkingMaxSpeed();
        });
        parkingScaleInput.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (parkingUiUpdating) return;
                int scale = BlindSpotOverlayController.MIN_SCALE_PERCENT + progress;
                parkingCameraScale[selectedParkingCameraId] = scale;
                if (parkingScaleSyncSwitch.isChecked()) {
                    for (int i = 0; i < parkingCameraScale.length; i++) {
                        parkingCameraScale[i] = scale;
                    }
                }
                parkingScaleValue.setText(scale + "%");
                updateParkingPositionHandle();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) { saveParkingPlacement(); }
        });
        parkingScaleSyncSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (parkingUiUpdating) return;
            if (checked) {
                int scale = parkingCameraScale[selectedParkingCameraId];
                for (int i = 0; i < parkingCameraScale.length; i++) parkingCameraScale[i] = scale;
            }
            saveParkingPlacement();
        });
        parkingCalibrationButton.setOnClickListener(view -> openSharedCalibration(
                TAB_PARKING_CAMERAS, true, selectedParkingCameraId));
        selectParkingCamera(selectedParkingCameraId);
        return panel;
    }

    private void loadParkingCameraProfiles() {
        int[] defaultScale = new int[ParkingCameraProfile.COUNT];
        java.util.Arrays.fill(defaultScale, ParkingCameraSettings.DEFAULT_SCALE_PERCENT);
        float[] defaultX = {0.0f, 0.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.0f, 1.0f};
        float[] defaultY = {0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 0.5f, 0.5f};
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            String prefix = parkingPlacementPrefix(profile);
            parkingCameraScale[profile.id] = clamp(preferences.getInt(
                    prefix + "scale", defaultScale[profile.id]),
                    BlindSpotOverlayController.MIN_SCALE_PERCENT,
                    BlindSpotOverlayController.MAX_SCALE_PERCENT);
            parkingCameraX[profile.id] = clamp(preferences.getFloat(
                    prefix + "x", defaultX[profile.id]), 0.0f, 1.0f);
            parkingCameraY[profile.id] = clamp(preferences.getFloat(
                    prefix + "y", defaultY[profile.id]), 0.0f, 1.0f);
        }
    }

    private void selectParkingCamera(int cameraId) {
        if (!ParkingCameraProfile.isValid(cameraId)) return;
        selectedParkingCameraId = cameraId;
        if (preferences.getInt("parking_camera_selected_profile", cameraId) != cameraId) {
            preferences.edit().putInt("parking_camera_selected_profile", cameraId).apply();
        }
        ParkingCameraProfile profile = ParkingCameraProfile.of(cameraId);
        parkingUiUpdating = true;
        ParkingCameraSettings.Rule rule = ParkingCameraSettings.readRule(preferences, profile);
        parkingCameraSwitch.setChecked(rule.enabled);
        parkingDistanceInput.setText(String.valueOf(rule.distanceCm));
        parkingAddCentralSwitch.setVisibility(profile.corner() ? View.VISIBLE : View.GONE);
        parkingAddCentralSwitch.setText(profile.rear()
                ? "Додати задню камеру" : "Додати передню камеру");
        parkingAddCentralSwitch.setChecked(rule.addCentral);
        parkingMaxSpeedInput.setText(String.valueOf(
                ParkingCameraSettings.readMaxSpeed(preferences)));
        parkingAllowDuringReverseSwitch.setChecked(
                ParkingCameraSettings.readAllowDuringReverse(preferences));
        parkingScaleSyncSwitch.setChecked(preferences.getBoolean(
                parkingScaleSyncKey(), false));
        parkingScaleInput.setProgress(parkingCameraScale[cameraId]
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        parkingScaleValue.setText(parkingCameraScale[cameraId] + "%");
        for (int i = 0; i < parkingCameraButtons.length; i++) {
            parkingCameraButtons[i].setBackgroundColor(tabColor(i == cameraId));
        }
        parkingPositionHandle.setText(parkingCameraButtons[cameraId].getText());
        parkingUiUpdating = false;
        updateParkingPositionHandle();
    }

    private void setAllParkingCamerasEnabled(
            ParkingCameraSettings settings, boolean enabled) {
        settings.setAllEnabled(enabled);
        parkingUiUpdating = true;
        parkingCameraSwitch.setChecked(enabled);
        parkingUiUpdating = false;
        notifyParkingSettingsChanged();
    }

    private void saveParkingRule() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        if (parkingDistanceInput == null) return;
        try {
            int distance = ParkingCameraSettings.clampDistanceCm(
                    Integer.parseInt(parkingDistanceInput.getText().toString()));
            ParkingCameraProfile profile = ParkingCameraProfile.of(selectedParkingCameraId);
            ParkingCameraSettings settings = new ParkingCameraSettings(preferences);
            settings.setRule(profile, settings.rule(profile).withDistanceCm(distance));
            parkingDistanceInput.setText(String.valueOf(distance));
            notifyParkingSettingsChanged();
        } catch (NumberFormatException error) {
            parkingDistanceInput.setText(String.valueOf(ParkingCameraSettings.readRule(
                    preferences, ParkingCameraProfile.of(selectedParkingCameraId)).distanceCm));
        }
    }

    private void saveParkingMaxSpeed() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        if (parkingMaxSpeedInput == null) return;
        try {
            int speed = ParkingCameraSettings.clampSpeed(
                    Integer.parseInt(parkingMaxSpeedInput.getText().toString()));
            new ParkingCameraSettings(preferences).setMaxSpeedKph(speed);
            parkingMaxSpeedInput.setText(String.valueOf(speed));
            notifyParkingSettingsChanged();
        } catch (NumberFormatException error) {
            parkingMaxSpeedInput.setText(String.valueOf(
                    ParkingCameraSettings.readMaxSpeed(preferences)));
        }
    }

    private void updateParkingPositionHandle() {
        if (parkingPositionWidget == null || parkingPositionFrame == null) return;
        if (parkingPositionWidget.getWidth() <= 0
                || parkingPositionWidget.getHeight() <= 0) return;
        int[] geometry = ParkingCameraController.overlayGeometry(
                parkingPositionWidget.getWidth(), parkingPositionWidget.getHeight(),
                parkingCameraScale[selectedParkingCameraId],
                parkingCameraX[selectedParkingCameraId],
                parkingCameraY[selectedParkingCameraId]);
        FrameLayout.LayoutParams handleParams =
                (FrameLayout.LayoutParams) parkingPositionHandle.getLayoutParams();
        handleParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
        handleParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
        parkingPositionHandle.setLayoutParams(handleParams);
        parkingPositionFrame.getLayoutParams().width = geometry[2];
        parkingPositionFrame.getLayoutParams().height = geometry[3];
        parkingPositionFrame.requestLayout();
        parkingPositionFrame.setX(geometry[0]);
        parkingPositionFrame.setY(geometry[1]);
    }

    private void updateParkingPositionCanvasSize() {
        if (parkingPositionHost == null || parkingPositionWidget == null) return;
        int availableWidth = parkingPositionHost.getWidth();
        int availableHeight = parkingPositionHost.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        int[] displaySize = CameraDisplayTarget.displaySize(
                this, CameraDisplayTarget.TABLET);
        float displayAspect = displaySize[0] <= 1 || displaySize[1] <= 1
                ? 16.0f / 9.0f : (float) displaySize[0] / displaySize[1];
        int[] size = BlindSpotOverlayController.fitAspect(
                availableWidth, availableWidth, availableHeight, displayAspect);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                parkingPositionWidget.getLayoutParams();
        if (params.width == size[0] && params.height == size[1]) {
            updateParkingPositionHandle();
            return;
        }
        params.width = size[0];
        params.height = size[1];
        params.gravity = Gravity.CENTER;
        parkingPositionWidget.setLayoutParams(params);
        parkingPositionWidget.post(this::updateParkingPositionHandle);
    }

    private void moveParkingPositionHandle(float x, float y) {
        if (parkingPositionWidget == null || parkingPositionFrame == null) return;
        float maxX = Math.max(0, parkingPositionWidget.getWidth()
                - parkingPositionFrame.getWidth());
        float maxY = Math.max(0, parkingPositionWidget.getHeight()
                - parkingPositionFrame.getHeight());
        parkingPositionFrame.setX(clamp(x, 0.0f, maxX));
        parkingPositionFrame.setY(clamp(y, 0.0f, maxY));
    }

    private void captureParkingPositionHandle() {
        if (parkingPositionWidget == null || parkingPositionFrame == null) return;
        float maxX = Math.max(0, parkingPositionWidget.getWidth()
                - parkingPositionFrame.getWidth());
        float maxY = Math.max(0, parkingPositionWidget.getHeight()
                - parkingPositionFrame.getHeight());
        parkingCameraX[selectedParkingCameraId] = maxX == 0 ? 0
                : parkingPositionFrame.getX() / maxX;
        parkingCameraY[selectedParkingCameraId] = maxY == 0 ? 0
                : parkingPositionFrame.getY() / maxY;
    }

    private void saveParkingPlacement() {
        if (parkingPositionWidget != null) captureParkingPositionHandle();
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean(parkingScaleSyncKey(), parkingScaleSyncSwitch != null
                        && parkingScaleSyncSwitch.isChecked());
        for (ParkingCameraProfile profile : ParkingCameraProfile.values()) {
            String prefix = parkingPlacementPrefix(profile);
            editor.putInt(prefix + "scale", parkingCameraScale[profile.id])
                    .putFloat(prefix + "x", parkingCameraX[profile.id])
                    .putFloat(prefix + "y", parkingCameraY[profile.id]);
        }
        editor.apply();
        notifyParkingSettingsChanged();
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

    private View buildCropHeader(String title, boolean reverse, int stage) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView heading = label(title);
        heading.setTextSize(8);
        heading.setGravity(Gravity.CENTER);
        TextView values = label("");
        values.setTextSize(8);
        values.setGravity(Gravity.CENTER);
        Button input = button("Ввести");
        input.setTextSize(10);
        input.setPadding(0, 0, 0, 0);
        if (reverse) {
            reverseCalibrationCropTitles[stage] = heading;
            reverseCalibrationCropReadouts[stage] = values;
            reverseCalibrationCropInputButtons[stage] = input;
            input.setOnClickListener(view -> openReverseCropInput(stage));
        } else {
            calibrationCropTitles[stage] = heading;
            calibrationCropReadouts[stage] = values;
            calibrationCropInputButtons[stage] = input;
            input.setOnClickListener(view -> openCalibrationCropInput(stage));
        }
        row.addView(heading, new LinearLayout.LayoutParams(dp(78), dp(28)));
        row.addView(values, new LinearLayout.LayoutParams(0, dp(28), 1));
        row.addView(input, new LinearLayout.LayoutParams(dp(70), dp(28)));
        return row;
    }

    private View buildCropInputControls(boolean reverse) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(Color.rgb(24, 24, 24));
        EditText[] inputs = reverse ? reverseCalibrationCropInputs : calibrationCropInputs;
        String[] labels = {"X, %", "Y, %", "W, %", "H, %"};
        for (int i = 0; i < inputs.length; i++) {
            LinearLayout cell = new LinearLayout(this);
            cell.setOrientation(LinearLayout.VERTICAL);
            TextView title = label(labels[i]);
            title.setTextSize(10);
            title.setGravity(Gravity.CENTER);
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setTextColor(Color.WHITE);
            input.setTextSize(14);
            input.setGravity(Gravity.CENTER);
            input.setSelectAllOnFocus(true);
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL
                    | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setKeyListener(DigitsKeyListener.getInstance("0123456789-.,"));
            inputs[i] = input;
            cell.addView(title, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(12)));
            cell.addView(input, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
            row.addView(cell, new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        Button apply = button("Застосувати");
        Button cancel = button("Скасувати");
        apply.setOnClickListener(view -> {
            if (reverse) applyReverseCropInput();
            else applyCalibrationCropInput();
        });
        cancel.setOnClickListener(view -> {
            if (reverse) cancelReverseCropInput();
            else cancelCalibrationCropInput();
        });
        row.addView(apply, new LinearLayout.LayoutParams(0, dp(44), 1));
        row.addView(cancel, new LinearLayout.LayoutParams(0, dp(44), 1));
        return row;
    }

    private void openCalibrationCropInput(int requestedStage) {
        boolean correctedEnabled = calibrationDewarpSwitch != null
                && calibrationDewarpSwitch.isChecked();
        CalibrationUiState ui = calibrationUiState(
                correctedEnabled, calibrationPreview.usesRawFallback());
        if (requestedStage == CROP_STAGE_CORRECTED && !ui.correctedEditable) return;
        calibrationCropInputStage = requestedStage;
        DirectCameraCrop crop = requestedStage == CROP_STAGE_CORRECTED
                ? calibrationCorrectedCrop : calibrationRawCrop;
        fillCropInputs(calibrationCropInputs,
                crop.left, crop.top, crop.width, crop.height);
        calibrationNormalControls.setVisibility(View.GONE);
        calibrationCropInputControls.setVisibility(View.VISIBLE);
        calibrationCropInputs[0].requestFocus();
    }

    private void applyCalibrationCropInput() {
        if (calibrationCropInputStage == CROP_STAGE_NONE) return;
        if (calibrationCropInputStage == CROP_STAGE_CORRECTED
                && (calibrationDewarpSwitch == null
                        || !calibrationDewarpSwitch.isChecked()
                        || calibrationPreview.usesRawFallback())) {
            cancelCalibrationCropInput();
            Toast.makeText(this, runtimeText(R.string.runtime_crop_corrected_unavailable),
                    Toast.LENGTH_LONG).show();
            return;
        }
        DirectCameraCrop current = calibrationCropInputStage == CROP_STAGE_CORRECTED
                ? calibrationCorrectedCrop : calibrationRawCrop;
        try {
            DirectCameraCrop crop = DirectCameraCrop.parsePercent(
                    calibrationCropInputs[0].getText().toString(),
                    calibrationCropInputs[1].getText().toString(),
                    calibrationCropInputs[2].getText().toString(),
                    calibrationCropInputs[3].getText().toString(),
                    DirectCameraCrop.ASPECT_FREE,
                    current.rotationDegrees, current.rotationMode)
                    .withMirrorHorizontally(current.mirrorHorizontally);
            if (calibrationCropInputStage == CROP_STAGE_CORRECTED) {
                calibrationCorrectedCrop = crop;
                saveCalibrationCrop(crop);
            } else {
                calibrationRawCrop = crop;
                saveCalibrationRawCrop(crop);
            }
            cancelCalibrationCropInput();
        } catch (IllegalArgumentException error) {
            showCropInputError(calibrationCropInputs, error.getMessage());
        }
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

    private void openReverseCropInput(int stage) {
        if (reverseCalibrationCameraIndex <= 0) return;
        CameraDewarpConfig dewarp = loadReverseCalibrationDewarp();
        if (stage == CROP_STAGE_CORRECTED
                && (!dewarp.enabled || reverseCameraPreview.editorUsesRawFallback(
                        reverseCalibrationCameraIndex))) return;
        reverseCalibrationCropInputStage = stage;
        ReverseCameraLayout.Rect crop = stage == CROP_STAGE_CORRECTED
                ? activeReverseCalibrationLayout()
                        .pane(reverseCalibrationCameraIndex).sourceCrop
                : activeReverseRawCalibrationLayout()
                        .pane(reverseCalibrationCameraIndex).sourceCrop;
        fillCropInputs(reverseCalibrationCropInputs,
                crop.left, crop.top, crop.width, crop.height);
        reverseCalibrationNormalControls.setVisibility(View.GONE);
        reverseCalibrationCropInputControls.setVisibility(View.VISIBLE);
        reverseCalibrationCropInputs[0].requestFocus();
    }

    private void applyReverseCropInput() {
        if (reverseCalibrationCropInputStage == CROP_STAGE_NONE) return;
        CameraDewarpConfig dewarp = reverseCalibrationCameraIndex <= 0
                ? null : loadReverseCalibrationDewarp();
        if (reverseCalibrationCropInputStage == CROP_STAGE_CORRECTED
                && (dewarp == null || !dewarp.enabled
                        || reverseCameraPreview.editorUsesRawFallback(
                                reverseCalibrationCameraIndex))) {
            cancelReverseCropInput();
            Toast.makeText(this, runtimeText(R.string.runtime_crop_corrected_unavailable),
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DirectCameraCrop crop = DirectCameraCrop.parsePercent(
                    reverseCalibrationCropInputs[0].getText().toString(),
                    reverseCalibrationCropInputs[1].getText().toString(),
                    reverseCalibrationCropInputs[2].getText().toString(),
                    reverseCalibrationCropInputs[3].getText().toString(),
                    DirectCameraCrop.ASPECT_FREE, 0, CameraRotation.MODE_FIT);
            applyReverseCalibrationCrop(reverseRect(crop),
                    reverseCalibrationCropInputStage == CROP_STAGE_CORRECTED, true);
            cancelReverseCropInput();
        } catch (IllegalArgumentException error) {
            showCropInputError(reverseCalibrationCropInputs, error.getMessage());
        }
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

    private static void fillCropInputs(
            EditText[] inputs, float x, float y, float width, float height) {
        float[] values = {x, y, width, height};
        for (int i = 0; i < inputs.length; i++) {
            inputs[i].setText(String.format(Locale.US, "%.2f", values[i] * 100.0f));
        }
    }

    private void showCropInputError(EditText[] inputs, String message) {
        inputs[0].setError(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
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

    private View buildCameraCalibrationPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, 0);

        calibrationBackButton = button("Назад");
        calibrationBackButton.setOnClickListener(view -> closeSharedCalibration());
        panel.addView(calibrationBackButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        calibrationStatus = statusText("Пошук direct camera...");
        panel.addView(calibrationStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        for (int cameraId = 0; cameraId < calibrationCameraButtons.length; cameraId++) {
            final int selectedId = cameraId;
            calibrationCameraButtons[cameraId] = button(
                    cameraId < CameraProfile.COUNT
                            ? calibrationLabel(false, cameraId)
                            : calibrationLabel(true, cameraId - CameraProfile.COUNT));
            calibrationCameraButtons[cameraId].setOnClickListener(
                    view -> selectCalibrationLogicalCamera(selectedId, true));
            if (cameraId >= CameraProfile.COUNT) {
                calibrationCameraButtons[cameraId].setVisibility(View.GONE);
            }
            controls.addView(calibrationCameraButtons[cameraId],
                    new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        panel.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout calibrationActions = new LinearLayout(this);
        calibrationActions.setOrientation(LinearLayout.HORIZONTAL);
        calibrationResetButton = button("Скинути");
        Button savePreset = button("Зберегти");
        calibrationPresetLoadButton = button("Завантажити");
        calibrationMirrorButton = button("Перенести →");
        calibrationMirrorButton.setTextSize(10);
        calibrationActions.addView(calibrationResetButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        calibrationActions.addView(savePreset,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        calibrationActions.addView(calibrationPresetLoadButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        calibrationActions.addView(calibrationMirrorButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        calibrationNormalControls = calibrationActions;
        calibrationCropInputControls = buildCropInputControls(false);
        calibrationCropInputControls.setVisibility(View.GONE);
        calibrationControlsHost = new FrameLayout(this);
        calibrationControlsHost.addView(calibrationNormalControls,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationControlsHost.addView(calibrationCropInputControls,
                new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
        panel.addView(calibrationControlsHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        savePreset.setOnClickListener(view -> saveCameraCalibrationPreset());
        calibrationPresetLoadButton.setOnClickListener(
                view -> loadCameraCalibrationPreset());
        calibrationMirrorButton.setOnClickListener(
                view -> mirrorCameraCalibration());

        LinearLayout rotationModeRow = new LinearLayout(this);
        rotationModeRow.setGravity(Gravity.CENTER_VERTICAL);
        rotationModeRow.addView(label("Режим повороту"),
                new LinearLayout.LayoutParams(0, dp(42), 1));
        calibrationRotationModeInput = new Spinner(this);
        ArrayAdapter<String> rotationModeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{CameraRotation.modeLabel(CameraRotation.MODE_FIT),
                        CameraRotation.modeLabel(CameraRotation.MODE_FILL),
                        CameraRotation.modeLabel(CameraRotation.MODE_ALIGNED)});
        rotationModeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        calibrationRotationModeInput.setAdapter(rotationModeAdapter);
        rotationModeRow.addView(calibrationRotationModeInput,
                new LinearLayout.LayoutParams(dp(196), dp(42)));
        panel.addView(rotationModeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

        LinearLayout rotationRow = new LinearLayout(this);
        rotationRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView rotationLabel = label("Поворот");
        calibrationRotationSlider = new SeekBar(this);
        calibrationRotationSlider.setMax(
                CameraRotation.MAX_DEGREES - CameraRotation.MIN_DEGREES);
        calibrationRotationValue = label("0°");
        calibrationRotationValue.setGravity(Gravity.CENTER);
        calibrationRotationSlider.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar, int progress, boolean fromUser) {
                        if (!fromUser || calibrationRotationUiUpdating) return;
                        if (!applyCalibrationOutputTransform(
                                progress + CameraRotation.MIN_DEGREES,
                                calibrationRawCrop.rotationMode)) return;
                        updateCalibrationUi(currentCalibrationCrop());
                        renderCalibrationCrop();
                    }

                    @Override public void onStartTrackingTouch(SeekBar seekBar) {}

                    @Override
                    public void onStopTrackingTouch(SeekBar seekBar) {
                        saveCalibrationCrop(currentCalibrationCrop());
                    }
                });
        rotationRow.addView(rotationLabel, new LinearLayout.LayoutParams(dp(100), dp(42)));
        rotationRow.addView(calibrationRotationSlider,
                new LinearLayout.LayoutParams(0, dp(42), 1));
        rotationRow.addView(calibrationRotationValue,
                new LinearLayout.LayoutParams(dp(64), dp(42)));
        calibrationOutputMirrorButton = button("Віддзеркалити");
        calibrationOutputMirrorButton.setOnClickListener(
                view -> toggleCalibrationOutputMirror());
        rotationRow.addView(calibrationOutputMirrorButton,
                new LinearLayout.LayoutParams(dp(OUTPUT_MIRROR_BUTTON_WIDTH_DP),
                        dp(CALIBRATION_ROTATION_ROW_HEIGHT_DP)));
        panel.addView(rotationRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        panel.addView(buildDewarpControls(false), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout rawPane = new LinearLayout(this);
        rawPane.setOrientation(LinearLayout.VERTICAL);
        rawPane.addView(buildCropHeader("RAW", false, CROP_STAGE_RAW),
                new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        FrameLayout rawHost = new FrameLayout(this);
        rawHost.setBackgroundColor(Color.BLACK);
        FrameLayout rawFrame = new FrameLayout(this);
        rawFrame.setBackgroundColor(Color.BLACK);
        rawFrame.setClipChildren(true);
        calibrationRawMirror = new TextureView(this);
        calibrationRawMirror.setOpaque(true);
        calibrationRawMirror.setSurfaceTextureListener(
                new TextureView.SurfaceTextureListener() {
                    @Override
                    public void onSurfaceTextureAvailable(
                            SurfaceTexture texture, int width, int height) {
                        texture.setDefaultBufferSize(
                                BlindSpotCameraView.BUFFER_WIDTH,
                                BlindSpotCameraView.BUFFER_HEIGHT);
                        calibrationPreview.setRawMirrorTexture(texture);
                    }

                    @Override
                    public void onSurfaceTextureSizeChanged(
                            SurfaceTexture texture, int width, int height) {
                        texture.setDefaultBufferSize(
                                BlindSpotCameraView.BUFFER_WIDTH,
                                BlindSpotCameraView.BUFFER_HEIGHT);
                    }

                    @Override
                    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                        calibrationPreview.setRawMirrorTexture(null);
                        return true;
                    }

                    @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
                });
        rawFrame.addView(calibrationRawMirror, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationRawCropOverlay = new CameraCropOverlayView(this);
        rawFrame.addView(calibrationRawCropOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        rawHost.addView(rawFrame, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        rawHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitAspectFrame(
                rawHost, rawFrame,
                DirectCameraCrop.SOURCE_WIDTH / DirectCameraCrop.SOURCE_HEIGHT));
        rawPane.addView(rawHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        calibrationRawPane = rawPane;
        previews.addView(rawPane, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout sourcePane = new LinearLayout(this);
        sourcePane.setOrientation(LinearLayout.VERTICAL);
        sourcePane.addView(buildCropHeader("CORRECTED", false, CROP_STAGE_CORRECTED),
                new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        calibrationSourceHost = new FrameLayout(this);
        calibrationSourceHost.setBackgroundColor(Color.BLACK);
        FrameLayout sourceFrame = new FrameLayout(this);
        sourceFrame.setBackgroundColor(Color.BLACK);
        sourceFrame.setClipChildren(true);
        calibrationSourceFrame = sourceFrame;

        calibrationPreview = new BlindSpotCameraView(this);
        calibrationPreview.setAutomaticBufferQuality(
                CameraBufferQuality.load(preferences));
        calibrationPreview.setAlpha(1.0f);
        calibrationPreview.setForceDewarpPipeline(true);
        calibrationPreview.setCallback(this);
        calibrationPreview.setDewarpStatsSink(this::onCalibrationDewarpStats);
        calibrationPreview.applyRawFallbackCrop(FULL_CALIBRATION_CROP);
        calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
        calibrationPreview.applyDewarpConfig(CameraDewarpConfig.loadForProfile(
                preferences, CameraProfile.of(calibrationCameraId)));
        sourceFrame.addView(calibrationPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationPreviewCover = new View(this);
        calibrationPreviewCover.setBackgroundColor(Color.BLACK);
        sourceFrame.addView(calibrationPreviewCover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationCropOverlay = new CameraCropOverlayView(this);
        sourceFrame.addView(calibrationCropOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationSourceHost.addView(sourceFrame,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        calibrationSourceHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitAspectFrame(
                calibrationSourceHost, calibrationSourceFrame,
                DirectCameraCrop.SOURCE_WIDTH / DirectCameraCrop.SOURCE_HEIGHT));
        sourcePane.addView(calibrationSourceHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        calibrationCorrectedPane = sourcePane;
        previews.addView(sourcePane, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout resultPane = new LinearLayout(this);
        resultPane.setOrientation(LinearLayout.VERTICAL);
        calibrationResultTitle = label("LIVE · 4:3");
        calibrationResultTitle.setGravity(Gravity.CENTER);
        resultPane.addView(calibrationResultTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        calibrationResultHost = new FrameLayout(this);
        calibrationResultHost.setBackgroundColor(Color.BLACK);
        FrameLayout resultFrame = new FrameLayout(this);
        resultFrame.setBackgroundColor(Color.BLACK);
        calibrationResultFrame = resultFrame;
        calibrationCropPreview = new ImageView(this);
        calibrationCropPreview.setBackgroundColor(Color.BLACK);
        calibrationCropPreview.setScaleType(ImageView.ScaleType.FIT_XY);
        resultFrame.addView(calibrationCropPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        calibrationResultHost.addView(resultFrame,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        calibrationResultHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> fitAspectFrame(
                calibrationResultHost, calibrationResultFrame,
                currentCalibrationLiveAspect()));
        resultPane.addView(calibrationResultHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        previews.addView(resultPane, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        panel.addView(previews, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        calibrationCropOverlay.setListener((crop, finished) -> {
            CalibrationUiState ui = calibrationUiState(
                    calibrationDewarpSwitch != null
                            && calibrationDewarpSwitch.isChecked(),
                    calibrationPreview.usesRawFallback());
            if (!ui.correctedEditable) return;
            DirectCameraCrop output = calibrationRawCrop.withIndependentGeometry(
                    crop.left, crop.top, crop.width, crop.height);
            calibrationCorrectedCrop = output;
            updateCalibrationUi(output);
            renderCalibrationCrop();
            if (finished) saveCalibrationCrop(output);
        });
        calibrationRawCropOverlay.setListener((crop, finished) -> {
            calibrationRawCrop = calibrationRawCrop.withIndependentGeometry(
                    crop.left, crop.top, crop.width, crop.height);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            updateCalibrationUi(currentCalibrationCrop());
            if (finished) saveCalibrationRawCrop(calibrationRawCrop);
        });
        calibrationRotationModeInput.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        if (calibrationRotationModeUiUpdating
                                || !CameraRotation.isValidMode(position)
                                || currentCalibrationCrop().rotationMode == position) return;
                        if (!applyCalibrationOutputTransform(
                                calibrationRawCrop.rotationDegrees, position)) return;
                        DirectCameraCrop crop = currentCalibrationCrop();
                        updateCalibrationUi(crop);
                        renderCalibrationCrop();
                        saveCalibrationCrop(crop);
                    }

                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        calibrationResetButton.setOnClickListener(view -> resetCalibrationCrop());
        selectCalibrationCamera(CameraProfile.REAR_LEFT, false);
        return panel;
    }

    private boolean applyCalibrationOutputTransform(int rotationDegrees, int rotationMode) {
        try {
            DirectCameraCrop.requireUiGeometry(
                    calibrationRawCrop.left, calibrationRawCrop.top,
                    calibrationRawCrop.width, calibrationRawCrop.height,
                    CameraRotation.clamp(rotationDegrees), rotationMode);
            DirectCameraCrop.requireUiGeometry(
                    calibrationCorrectedCrop.left, calibrationCorrectedCrop.top,
                    calibrationCorrectedCrop.width, calibrationCorrectedCrop.height,
                    CameraRotation.clamp(rotationDegrees), rotationMode);
            DirectCameraCrop nextRaw = calibrationRawCrop.withOutputTransformPreservingGeometry(
                    rotationDegrees, rotationMode, calibrationRawCrop.mirrorHorizontally);
            DirectCameraCrop nextCorrected =
                    calibrationCorrectedCrop.withOutputTransformPreservingGeometry(
                            nextRaw.rotationDegrees, nextRaw.rotationMode,
                            calibrationCorrectedCrop.mirrorHorizontally);
            calibrationRawCrop = nextRaw;
            calibrationCorrectedCrop = nextCorrected;
            return true;
        } catch (IllegalArgumentException error) {
            updateCalibrationUi(currentCalibrationCrop());
            String message = error.getMessage() == null
                    ? "Некоректна геометрія crop" : error.getMessage();
            calibrationStatus.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private View buildCombinedDebugPanel() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout tabs = new LinearLayout(this);
        directCameraDebugTabButton = button("Direct camera");
        cameraAvmDebugSubtabButton = button("Режими AVM");
        tabs.addView(directCameraDebugTabButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        tabs.addView(cameraAvmDebugSubtabButton,
                new LinearLayout.LayoutParams(0, dp(44), 1));
        root.addView(tabs);

        FrameLayout pages = new FrameLayout(this);
        pages.addView(directCameraDebugPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(cameraDebugPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(pages, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        selectedDebugMode = clamp(preferences.getInt("selected_debug_mode", 0), 0, 1);
        directCameraDebugTabButton.setOnClickListener(view -> selectDebugMode(0));
        cameraAvmDebugSubtabButton.setOnClickListener(view -> selectDebugMode(1));
        selectDebugMode(selectedDebugMode);
        return root;
    }

    private View buildDewarpControls(boolean reverse) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(reverse ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        Switch toggle = new Switch(this);
        toggle.setText("Корекція fisheye");
        toggle.setTextColor(Color.WHITE);
        toggle.setTextSize(15);
        if (reverse) reverseDewarpSwitch = toggle;
        else calibrationDewarpSwitch = toggle;

        LinearLayout fovCell = new LinearLayout(this);
        fovCell.setOrientation(LinearLayout.VERTICAL);
        TextView fovValue = label("FOV");
        fovValue.setTextSize(12);
        fovValue.setGravity(Gravity.CENTER);
        SeekBar fovSlider = new SeekBar(this);
        fovSlider.setMax(CameraDewarpConfig.MAX_FOV_DEGREES
                - CameraDewarpConfig.MIN_FOV_DEGREES);
        if (reverse) {
            reverseDewarpFovSlider = fovSlider;
            reverseDewarpFovValue = fovValue;
        } else {
            calibrationDewarpFovSlider = fovSlider;
            calibrationDewarpFovValue = fovValue;
        }
        fovSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(
                    SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && !(reverse
                        ? reverseDewarpUiUpdating : calibrationDewarpUiUpdating)) {
                    applyDewarpFromControls(reverse, false);
                }
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                applyDewarpFromControls(reverse, true);
            }
        });
        LinearLayout projectionCell = new LinearLayout(this);
        projectionCell.setOrientation(LinearLayout.VERTICAL);
        TextView projectionLabel = label("Проєкція");
        projectionLabel.setTextSize(12);
        projectionLabel.setGravity(Gravity.CENTER);
        projectionCell.addView(projectionLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(reverse ? 16 : 20)));
        Spinner projectionInput = new Spinner(this);
        ArrayAdapter<String> projectionAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"Rectilinear", "Cylindrical"});
        projectionAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        projectionInput.setAdapter(projectionAdapter);
        if (reverse) reverseDewarpProjectionInput = projectionInput;
        else calibrationDewarpProjectionInput = projectionInput;

        Button reset = button(reverse ? "Скинути" : "Скинути корекцію");
        reset.setOnClickListener(view -> {
            if (reverse) {
                cancelReverseCropInput();
                if (reverseCalibrationCameraIndex <= 0) return;
                if (reverseCalibrationFront) {
                    CameraCalibrationPreset.resetReverseFrontToDefault(
                            preferences, reverseCalibrationCameraIndex);
                } else {
                    CameraCalibrationPreset.resetReverseToDefault(
                            preferences, reverseCalibrationCameraIndex);
                }
                refreshCalibrationSettings("reverse_calibration_reset");
                return;
            }
            cancelCalibrationCropInput();
            int lens = selectedDewarpLens(reverse);
            if (lens == 0) return;
            CameraDewarpConfig value = CameraDewarpConfig.disabled(lens);
            updateDewarpUi(reverse, value);
            applyDewarpConfig(reverse, value, true);
        });
        if (reverse) {
            LinearLayout topRow = new LinearLayout(this);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            projectionCell.addView(projectionInput, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(26)));
            topRow.addView(toggle, new LinearLayout.LayoutParams(0, dp(42), 1));
            topRow.addView(projectionCell, new LinearLayout.LayoutParams(0, dp(42), 1));
            topRow.addView(reset, new LinearLayout.LayoutParams(0, dp(40), 1));
            root.addView(topRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));

            LinearLayout fovRow = new LinearLayout(this);
            fovRow.setGravity(Gravity.CENTER_VERTICAL);
            fovRow.addView(fovValue, new LinearLayout.LayoutParams(dp(82), dp(42)));
            fovRow.addView(fovSlider, new LinearLayout.LayoutParams(0, dp(42), 1));
            root.addView(fovRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
        } else {
            root.addView(toggle, new LinearLayout.LayoutParams(dp(210), dp(58)));
            fovCell.addView(fovValue, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(21)));
            fovCell.addView(fovSlider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(37)));
            root.addView(fovCell, new LinearLayout.LayoutParams(0, dp(58), 1));
            projectionCell.addView(projectionInput, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
            root.addView(projectionCell, new LinearLayout.LayoutParams(dp(190), dp(58)));
            root.addView(reset, new LinearLayout.LayoutParams(dp(200), dp(48)));
        }
        toggle.setOnCheckedChangeListener((button, checked) -> {
            boolean updating = reverse ? reverseDewarpUiUpdating : calibrationDewarpUiUpdating;
            if (updating) return;
            if (reverse) cancelReverseCropInput();
            else cancelCalibrationCropInput();
            CameraDewarpConfig current = dewarpFromControls(reverse).withEnabled(checked);
            applyDewarpConfig(reverse, current, true);
        });
        projectionInput.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(
                    AdapterView<?> parent, View view, int position, long id) {
                boolean updating = reverse
                        ? reverseDewarpUiUpdating : calibrationDewarpUiUpdating;
                if (updating || !CameraDewarpConfig.isValidProjection(position)) return;
                if (reverse) cancelReverseCropInput();
                else cancelCalibrationCropInput();
                int lens = selectedDewarpLens(reverse);
                if (lens == 0
                        || loadSelectedDewarpConfig(reverse).projection == position) {
                    return;
                }
                applyDewarpConfig(reverse,
                        dewarpFromControls(reverse).withProjection(position), true);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        return root;
    }

    private void applyDewarpFromControls(boolean reverse, boolean persist) {
        applyDewarpConfig(reverse, dewarpFromControls(reverse), persist);
    }

    private CameraDewarpConfig dewarpFromControls(boolean reverse) {
        Switch toggle = reverse ? reverseDewarpSwitch : calibrationDewarpSwitch;
        SeekBar slider = reverse ? reverseDewarpFovSlider : calibrationDewarpFovSlider;
        Spinner projectionInput = reverse
                ? reverseDewarpProjectionInput : calibrationDewarpProjectionInput;
        int lens = selectedDewarpLens(reverse);
        if (lens == 0 || slider == null) {
            return CameraDewarpConfig.disabled(CameraDewarpConfig.LENS_LEFT);
        }
        int projection = projectionInput == null
                ? CameraDewarpConfig.DEFAULT_PROJECTION
                : projectionInput.getSelectedItemPosition();
        return CameraDewarpConfig.of(lens, toggle != null && toggle.isChecked(),
                CameraDewarpConfig.MIN_FOV_DEGREES + slider.getProgress(), projection);
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
            updateProductionPreviewSize();
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
        if (reverseCalibrationFront
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
        if (reverseCalibrationFront && isReverseCameraPane(reverseCalibrationCameraIndex)) {
            reverseCameraPreview.setSideMode(ReverseSideSelectorView.MODE_FRONT);
        }
    }

    private void selectDebugMode(int mode) {
        int next = clamp(mode, 0, 1);
        if (selectedDebugMode != next) {
            retryStockViewpoint = -1;
            retryStockDebug = false;
        }
        if (selectedDebugMode != next && (requestedOpen || cameraHandoffPending)
                && (activePreview == directCameraPreview || activePreview == debugPreview)) {
            closeCamera("debug_subtab_changed");
        }
        selectedDebugMode = next;
        preferences.edit().putInt("selected_debug_mode", next).apply();
        if (directCameraDebugPage != null) {
            directCameraDebugPage.setVisibility(next == 0 ? View.VISIBLE : View.GONE);
        }
        if (cameraDebugPage != null) {
            cameraDebugPage.setVisibility(next == 1 ? View.VISIBLE : View.GONE);
        }
        if (directCameraDebugTabButton != null) {
            directCameraDebugTabButton.setBackgroundColor(tabColor(next == 0));
        }
        if (cameraAvmDebugSubtabButton != null) {
            cameraAvmDebugSubtabButton.setBackgroundColor(tabColor(next == 1));
        }
    }

    private View buildCameraDebugPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, 0);

        debugCameraStatus = statusText("Запуск внутрішньої служби...");
        panel.addView(debugCameraStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        debugHorizontalButton = button("Horizontal");
        debugVerticalButton = button("Vertical");
        debugShowRawSwitch = new Switch(this);
        debugShowRawSwitch.setText("Show raw");
        debugShowRawSwitch.setTextColor(Color.WHITE);
        debugShowRawSwitch.setTextSize(16);
        debugShowRawSwitch.setChecked(true);
        debugDewarpSwitch = new Switch(this);
        debugDewarpSwitch.setText("Dewarp");
        debugDewarpSwitch.setTextColor(Color.WHITE);
        debugDewarpSwitch.setTextSize(16);
        debugDewarpSwitch.setChecked(false);
        controls.addView(debugHorizontalButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        controls.addView(debugVerticalButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        controls.addView(debugShowRawSwitch, new LinearLayout.LayoutParams(dp(180), dp(52)));
        controls.addView(debugDewarpSwitch, new LinearLayout.LayoutParams(dp(160), dp(52)));

        panel.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        panel.addView(buildPreviewFrame(true), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        debugLayoutTitle = label("Режими AVM (VIEW_GROUP_H)");
        debugLayoutTitle.setPadding(0, dp(4), 0, 0);
        panel.addView(debugLayoutTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < horizontalLayoutButtons.length; i++) {
            final int viewpoint = StockAvmPreview.horizontalViewpoint(i);
            Button mode = button(String.format(Locale.US, "%02d  %s",
                    i + 1, StockAvmPreview.horizontalLayoutName(i)));
            mode.setOnClickListener(view -> {
                invalidStockSurfaceRetryUsed = false;
                openStockAvm(viewpoint, true);
            });
            horizontalLayoutButtons[i] = mode;
            modes.addView(mode, new LinearLayout.LayoutParams(dp(300), dp(54)));
        }
        closeButton = button("Close");
        closeButton.setOnClickListener(
                view -> stopActivityCameraManually("user_close"));
        modes.addView(closeButton, new LinearLayout.LayoutParams(dp(130), dp(54)));

        HorizontalScrollView modeScroll = new HorizontalScrollView(this);
        modeScroll.setHorizontalScrollBarEnabled(true);
        modeScroll.addView(modes, new HorizontalScrollView.LayoutParams(
                HorizontalScrollView.LayoutParams.WRAP_CONTENT,
                HorizontalScrollView.LayoutParams.MATCH_PARENT));
        panel.addView(modeScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(62)));

        debugHorizontalButton.setOnClickListener(view -> selectDebugOrientation(true));
        debugVerticalButton.setOnClickListener(view -> selectDebugOrientation(false));
        debugShowRawSwitch.setOnCheckedChangeListener((button, checked) -> {
            applyDebugPreviewMode();
            record("debug_show_raw", "enabled", checked,
                    "viewpoint", activeCameraViewpoint);
        });
        debugDewarpSwitch.setOnCheckedChangeListener((button, checked) -> {
            record("debug_stock_dewarp", "enabled", checked,
                    "viewpoint", activeCameraViewpoint);
            if (requestedOpen && activePreview == debugPreview
                    && activeCameraViewpoint >= 0 && !cameraTransition.pending()) {
                retryStockViewpoint = activeCameraViewpoint;
                retryStockDebug = true;
                closeCameraForTransition("debug_stock_dewarp_changed");
            } else {
                updateControls();
            }
        });
        updateDebugOrientationControls();
        return panel;
    }

    private View buildDirectCameraDebugPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(8), 0, 0);

        directCameraStatus = statusText("Пошук direct camera...");
        panel.addView(directCameraStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(32)));

        LinearLayout indices = new LinearLayout(this);
        indices.setOrientation(LinearLayout.HORIZONTAL);
        String[] labels = {"All", "Rear", "Left", "Right", "Front"};
        for (int index = 0; index < directCameraIndexButtons.length; index++) {
            final int previewIndex = index;
            Button button = button(labels[index]);
            button.setOnClickListener(view -> openDirectCamera(previewIndex));
            directCameraIndexButtons[index] = button;
            indices.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        panel.addView(indices, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackgroundColor(Color.BLACK);
        directCameraPreview = new SurfaceView(this);
        directCameraPreview.setAlpha(1.0f);
        directCameraPreview.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        directCameraPreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                directCameraSurfaceReady = holder.getSurface().isValid();
                if (directCameraPreviewCover != null) {
                    directCameraPreviewCover.setVisibility(View.VISIBLE);
                }
                record("surface_created", "target", "direct_camera",
                        "valid", directCameraSurfaceReady);
                updateControls();
            }

            @Override
            public void surfaceChanged(
                    SurfaceHolder holder, int format, int width, int height) {
                directCameraSurfaceReady = holder.getSurface().isValid();
                record("surface_changed", "target", "direct_camera",
                        "width", width, "height", height, "format", format);
                updateControls();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                directCameraSurfaceReady = false;
                if (activePreview == directCameraPreview) {
                    closeCamera("direct_surface_destroyed");
                }
                record("surface_destroyed", "target", "direct_camera");
                updateControls();
            }
        });
        previewFrame.addView(directCameraPreview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        directCameraPreviewCover = new View(this);
        directCameraPreviewCover.setBackgroundColor(Color.BLACK);
        previewFrame.addView(directCameraPreviewCover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        panel.addView(previewFrame, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        return panel;
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

    private void saveCalibrationRawStored(DirectCameraCrop crop) {
        if (calibrationParkingMode) {
            DirectCameraCrop.save(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId), crop);
        } else {
            DirectCameraCrop.save(preferences, CameraProfile.of(calibrationCameraId), crop);
        }
    }

    private void saveCalibrationCorrectedStored(DirectCameraCrop crop) {
        if (calibrationParkingMode) {
            DirectCameraCrop.saveCorrected(preferences,
                    ParkingCameraProfile.of(calibrationParkingCameraId), crop);
        } else {
            DirectCameraCrop.saveCorrected(preferences, CameraProfile.of(calibrationCameraId), crop);
        }
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

    private void selectCalibrationCamera(int cameraId, boolean open) {
        cancelCalibrationCropInput();
        if (!CameraProfile.isValid(cameraId)) return;
        CameraProfile profile = CameraProfile.of(cameraId);
        int requestedPhysicalIndex = profile.previewIndex;
        boolean calibrationOpen = requestedOpen && activePreview == calibrationPreview;
        boolean switchingOpenCamera = calibrationNeedsIdentityRebind(
                calibrationOpen,
                calibrationParkingMode,
                calibrationParkingMode ? calibrationParkingCameraId : calibrationCameraId,
                activeDirectCameraIndex,
                false, cameraId, requestedPhysicalIndex);
        calibrationParkingMode = false;
        for (int i = 0; i < calibrationCameraButtons.length; i++) {
            calibrationCameraButtons[i].setVisibility(i < CameraProfile.COUNT
                    ? View.VISIBLE : View.GONE);
            if (i < CameraProfile.COUNT) {
                calibrationCameraButtons[i].setText(calibrationLabel(false, i));
            }
        }
        if (open && requestedOpen && activePreview != calibrationPreview) return;
        if (open && requestedOpen && activePreview == calibrationPreview
                && calibrationCameraId == cameraId && !switchingOpenCamera) return;
        calibrationCameraId = cameraId;
        if (switchingOpenCamera) closeCameraForTransition("calibration_camera_changed");
        CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(
                preferences, profile);
        calibrationRawCrop = DirectCameraCrop.load(preferences, profile);
        calibrationCorrectedCrop = DirectCameraCrop.loadCorrected(
                preferences, profile, calibrationRawCrop);
        calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
        applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        calibrationPreview.applyDewarpConfig(dewarp);
        updateCalibrationDisplay(dewarp.enabled);
        updateDewarpUi(false, dewarp);
        for (int i = 0; i < calibrationCameraButtons.length; i++) {
            if (calibrationCameraButtons[i].getVisibility() == View.VISIBLE) {
                calibrationCameraButtons[i].setBackgroundColor(tabColor(i == cameraId));
            }
        }
        record("calibration_camera_selected", "camera_id", cameraId,
                "camera", profile.wireName, "preview_index", profile.previewIndex);
        if (open && !switchingOpenCamera) openCalibrationCamera(requestedPhysicalIndex);
    }

    private void selectCalibrationLogicalCamera(int logicalId, boolean open) {
        if (!calibrationParkingMode) {
            if (logicalId < CameraProfile.COUNT) {
                selectCalibrationCamera(logicalId, open);
            }
            return;
        }
        if (!ParkingCameraProfile.isValid(logicalId)) return;
        cancelCalibrationCropInput();
        ParkingCameraProfile parkingProfile = ParkingCameraProfile.of(logicalId);
        int requestedPhysicalIndex = parkingProfile.physicalCameraIndex;
        boolean calibrationOpen = requestedOpen && activePreview == calibrationPreview;
        boolean switchingOpenCamera = calibrationNeedsIdentityRebind(
                calibrationOpen,
                calibrationParkingMode,
                calibrationParkingMode ? calibrationParkingCameraId : calibrationCameraId,
                activeDirectCameraIndex,
                true, logicalId, requestedPhysicalIndex);
        if (open && requestedOpen && activePreview != calibrationPreview) return;
        if (open && requestedOpen && activePreview == calibrationPreview
                && calibrationParkingCameraId == logicalId && !switchingOpenCamera) return;
        calibrationParkingCameraId = logicalId;
        for (int i = 0; i < calibrationCameraButtons.length; i++) {
            calibrationCameraButtons[i].setVisibility(View.VISIBLE);
            calibrationCameraButtons[i].setText(calibrationLabel(true, i));
        }
        CameraProfile previewProfile = parkingPreviewProfile(parkingProfile);
        calibrationCameraId = previewProfile.id;
        if (switchingOpenCamera) closeCameraForTransition("parking_calibration_camera_changed");
        calibrationRawCrop = loadCalibrationRawStored();
        calibrationCorrectedCrop = loadCalibrationCorrectedStored(calibrationRawCrop);
        CameraDewarpConfig dewarp = loadCalibrationDewarpStored();
        calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
        applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        calibrationPreview.applyDewarpConfig(dewarp);
        updateCalibrationDisplay(dewarp.enabled);
        updateDewarpUi(false, dewarp);
        for (int i = 0; i < calibrationCameraButtons.length; i++) {
            calibrationCameraButtons[i].setBackgroundColor(tabColor(i == logicalId));
            calibrationCameraButtons[i].setVisibility(View.VISIBLE);
        }
        record("parking_calibration_camera_selected", "camera_id", logicalId,
                "camera", parkingProfile.wireName,
                "preview_index", previewProfile.previewIndex);
        if (open && !switchingOpenCamera) {
            openCalibrationCamera(requestedPhysicalIndex);
        }
    }

    private void openSharedCalibration(int originTab, boolean parking, int logicalId) {
        CalibrationEntry entry = calibrationEntry(originTab, parking, logicalId);
        calibrationOriginTab = entry.originTab;
        calibrationParkingMode = entry.parking;
        if (entry.parking) {
            calibrationParkingCameraId = entry.logicalId;
            selectCalibrationLogicalCamera(calibrationParkingCameraId, false);
        } else {
            calibrationCameraId = entry.logicalId;
            selectCalibrationCamera(calibrationCameraId, false);
        }
        selectTab(TAB_CAMERA_CALIBRATION);
    }

    private void closeSharedCalibration() {
        if (selectedTab != TAB_CAMERA_CALIBRATION) return;
        cancelCalibrationCropInput();
        stopCalibrationCopies(true);
        closeCamera("calibration_back");
        int origin = calibrationOriginTab == TAB_PARKING_CAMERAS
                ? TAB_PARKING_CAMERAS : TAB_CAMERAS;
        if (origin == TAB_PARKING_CAMERAS) {
            selectedParkingCameraId = calibrationParkingCameraId;
        } else {
            selectedCameraId = calibrationCameraId;
            if (preferences.getInt("camera_selected_profile", selectedCameraId)
                    != selectedCameraId) {
                preferences.edit().putInt("camera_selected_profile", selectedCameraId).apply();
            }
        }
        selectTab(origin);
        if (origin == TAB_PARKING_CAMERAS) {
            selectParkingCamera(calibrationParkingCameraId);
        } else {
            selectCameraProfile(calibrationCameraId, true);
        }
    }

    private void saveCameraCalibrationPreset() {
        if (calibrationParkingMode) {
            ParkingCameraProfile profile = ParkingCameraProfile.of(calibrationParkingCameraId);
            CameraCalibrationPreset.saveParking(preferences, profile);
            updateCalibrationCropReadouts();
            notifyCalibrationSettingsChanged();
            Toast.makeText(this, runtimeText(R.string.runtime_preset_saved), Toast.LENGTH_LONG).show();
            record("parking_calibration_preset_saved", "camera", profile.wireName);
            return;
        }
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraCalibrationPreset.saveCamera(preferences, profile);
        updateCalibrationCropReadouts();
        Toast.makeText(this, runtimeText(R.string.runtime_preset_saved), Toast.LENGTH_LONG).show();
        record("camera_calibration_preset_saved", "camera", profile.wireName);
    }

    private void loadCameraCalibrationPreset() {
        cancelCalibrationCropInput();
        if (calibrationParkingMode) {
            ParkingCameraProfile profile = ParkingCameraProfile.of(calibrationParkingCameraId);
            if (!CameraCalibrationPreset.loadParking(preferences, profile)) {
                Toast.makeText(this, runtimeText(R.string.runtime_preset_missing),
                        Toast.LENGTH_LONG).show();
                return;
            }
            refreshCalibrationSettings("parking_preset_loaded");
            Toast.makeText(this, runtimeText(R.string.runtime_preset_loaded_short), Toast.LENGTH_LONG).show();
            return;
        }
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        if (!CameraCalibrationPreset.loadCamera(preferences, profile)) {
            Toast.makeText(this, runtimeText(R.string.runtime_preset_missing),
                    Toast.LENGTH_LONG).show();
            return;
        }
        refreshCalibrationSettings("camera_preset_loaded");
        Toast.makeText(this, runtimeText(R.string.runtime_preset_loaded_short), Toast.LENGTH_LONG).show();
    }

    private void mirrorCameraCalibration() {
        cancelCalibrationCropInput();
        if (calibrationParkingMode) {
            ParkingCameraProfile profile = ParkingCameraProfile.of(calibrationParkingCameraId);
            if (!CameraCalibrationPreset.mirrorParking(preferences, profile)) return;
            refreshCalibrationSettings("parking_calibration_mirrored");
            Toast.makeText(this, runtimeText(R.string.runtime_settings_mirrored),
                    Toast.LENGTH_LONG).show();
            return;
        }
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraCalibrationPreset.mirrorCamera(preferences, profile);
        refreshCalibrationSettings("camera_calibration_mirrored");
        Toast.makeText(this, runtimeText(R.string.runtime_settings_mirrored),
                Toast.LENGTH_LONG).show();
    }

    private void toggleCalibrationOutputMirror() {
        cancelCalibrationCropInput();
        boolean mirror = !currentCalibrationCrop().mirrorHorizontally;
        calibrationRawCrop = calibrationRawCrop.withMirrorHorizontally(mirror);
        calibrationCorrectedCrop = calibrationCorrectedCrop.withMirrorHorizontally(mirror);
        saveCalibrationRawStored(calibrationRawCrop);
        saveCalibrationCorrectedStored(calibrationCorrectedCrop);
        updateCalibrationUi(currentCalibrationCrop());
        renderCalibrationCrop();
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        if (!calibrationParkingMode && cameraPreview != null && selectedCameraId == profile.id) {
            cameraPreview.applyRawFallbackCrop(calibrationRawCrop);
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            updateProductionPreviewSize();
        }
        notifyCalibrationSettingsChanged();
        record("direct_output_mirror_changed", "camera_id", profile.id,
                "camera", calibrationParkingMode
                        ? ParkingCameraProfile.of(calibrationParkingCameraId).wireName
                        : profile.wireName, "mirror", mirror);
    }

    private void saveReverseCalibrationPreset() {
        if (reverseCalibrationCameraIndex <= 0) return;
        if (reverseCalibrationFront) {
            CameraCalibrationPreset.saveReverseFront(
                    preferences, reverseCalibrationCameraIndex);
        } else {
            CameraCalibrationPreset.saveReverse(
                    preferences, reverseCalibrationCameraIndex);
        }
        updateReverseCalibrationCropReadouts();
        Toast.makeText(this, runtimeText(R.string.runtime_preset_saved), Toast.LENGTH_LONG).show();
        record("reverse_calibration_preset_saved",
                "camera_index", reverseCalibrationCameraIndex);
    }

    private void loadReverseCalibrationPreset() {
        cancelReverseCropInput();
        boolean loaded = reverseCalibrationCameraIndex > 0
                && (reverseCalibrationFront
                        ? CameraCalibrationPreset.loadReverseFront(
                                preferences, reverseCalibrationCameraIndex)
                        : CameraCalibrationPreset.loadReverse(
                                preferences, reverseCalibrationCameraIndex));
        if (!loaded) {
            Toast.makeText(this, runtimeText(R.string.runtime_preset_missing),
                    Toast.LENGTH_LONG).show();
            return;
        }
        refreshCalibrationSettings("reverse_preset_loaded");
        Toast.makeText(this, runtimeText(R.string.runtime_preset_loaded_short), Toast.LENGTH_LONG).show();
    }

    private void mirrorReverseCalibration() {
        cancelReverseCropInput();
        boolean mirrored = reverseCalibrationCameraIndex > 0
                && (reverseCalibrationFront
                        ? CameraCalibrationPreset.mirrorReverseFront(
                                preferences, reverseCalibrationCameraIndex)
                        : CameraCalibrationPreset.mirrorReverse(
                                preferences, reverseCalibrationCameraIndex));
        if (!mirrored) return;
        refreshCalibrationSettings("reverse_calibration_mirrored");
        Toast.makeText(this, runtimeText(R.string.runtime_settings_mirrored),
                Toast.LENGTH_LONG).show();
    }

    private void refreshCalibrationSettings(String reason) {
        if (reverseCalibrationCameraIndex > 0) stopReverseCalibrationCopies(true);
        CameraProfile calibrationProfile = CameraProfile.of(calibrationCameraId);
        CameraDewarpConfig calibrationDewarp = loadCalibrationDewarpStored();
        calibrationRawCrop = loadCalibrationRawStored();
        calibrationCorrectedCrop = loadCalibrationCorrectedStored(calibrationRawCrop);
        if (calibrationPreview != null) {
            calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            calibrationPreview.applyDewarpConfig(calibrationDewarp);
            updateDewarpUi(false, calibrationDewarp);
        }

        CameraProfile previewProfile = CameraProfile.of(selectedCameraId);
        if (!calibrationParkingMode && cameraPreview != null) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, previewProfile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            cameraPreview.applyDewarpConfig(
                    CameraDewarpConfig.loadForProfile(preferences, previewProfile));
            updateProductionPreviewSize();
        }

        if (reverseCameraPreview != null && reverseCameraEditor != null) {
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
            if (reverseCalibrationCameraIndex > 0) updateReverseCalibrationDisplay();
        }
        updateCalibrationCropReadouts();
        notifyCalibrationSettingsChanged();
        CameraHelperService.reverseCameraSettingsChanged(this);
        record(reason, "camera_id", calibrationCameraId,
                "reverse_camera_index", reverseCalibrationCameraIndex);
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

    private void saveCalibrationCrop(DirectCameraCrop crop) {
        CameraDewarpConfig dewarp = loadCalibrationDewarpStored();
        if (!dewarp.enabled || calibrationPreview.usesRawFallback()) {
            saveCalibrationRawCrop(crop);
            return;
        }
        calibrationCorrectedCrop = calibrationParkingMode
                ? DirectCameraCrop.saveCorrectedGeometryEdit(
                        preferences,
                        ParkingCameraProfile.of(calibrationParkingCameraId), crop)
                : DirectCameraCrop.saveCorrectedGeometryEdit(
                        preferences, CameraProfile.of(calibrationCameraId), crop);
        finishCalibrationCropSave("corrected", calibrationCorrectedCrop, dewarp);
    }

    private void saveCalibrationRawCrop(DirectCameraCrop crop) {
        CameraDewarpConfig dewarp = loadCalibrationDewarpStored();
        calibrationRawCrop = calibrationParkingMode
                ? DirectCameraCrop.saveRawGeometryEdit(
                        preferences,
                        ParkingCameraProfile.of(calibrationParkingCameraId), crop)
                : DirectCameraCrop.saveRawGeometryEdit(
                        preferences, CameraProfile.of(calibrationCameraId), crop);
        calibrationCorrectedCrop = loadCalibrationCorrectedStored(calibrationRawCrop);
        applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        updateCalibrationDisplay(dewarp.enabled);
        finishCalibrationCropSave("raw", calibrationRawCrop, dewarp);
    }

    private void finishCalibrationCropSave(
            String stage, DirectCameraCrop crop, CameraDewarpConfig dewarp) {
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        record("direct_crop_saved", "camera_id", profile.id, "camera",
                calibrationParkingMode
                        ? ParkingCameraProfile.of(calibrationParkingCameraId).wireName
                        : profile.wireName,
                "stage", stage,
                "dewarp_enabled", dewarp.enabled,
                "dewarp_projection", CameraDewarpConfig.projectionLabel(dewarp.projection),
                "x", crop.left, "y", crop.top,
                "width", crop.width, "height", crop.height,
                "aspect", DirectCameraCrop.aspectLabel(crop.aspectMode),
                "rotation_degrees", crop.rotationDegrees,
                "rotation_mode", CameraRotation.modeLabel(crop.rotationMode),
                "output_aspect", crop.outputAspect());
        if (!calibrationParkingMode) {
            updateCameraPositionHandle();
            updateProductionPreviewSize();
        }
        notifyCalibrationSettingsChanged();
    }

    private void resetCalibrationCrop() {
        cancelCalibrationCropInput();
        if (!calibrationParkingMode) {
            CameraCalibrationPreset.resetCameraToDefault(
                    preferences, CameraProfile.of(calibrationCameraId));
            loadCameraProfiles();
            refreshCalibrationSettings("camera_calibration_reset");
            return;
        }
        DirectCameraCrop crop = DirectCameraCrop.defaultFor(
                ParkingCameraProfile.of(calibrationParkingCameraId));
        calibrationRawCrop = crop;
        calibrationCorrectedCrop = crop.centered();
        saveCalibrationCorrectedStored(calibrationCorrectedCrop);
        updateCalibrationDisplay(loadCalibrationDewarpStored().enabled);
        saveCalibrationRawCrop(crop);
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
        if (calibrationResultTitle != null) {
            calibrationResultTitle.setText(
                    "LIVE · " + DirectCameraCrop.aspectLabel(crop.aspectMode)
                            + " · " + CameraRotation.modeLabel(crop.rotationMode));
        }
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
                    ? ReverseCameraLayout.REAR_CAMERA_INDEX
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
                ? calibrationRawMirror : calibrationPreview;
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
        updateDebugOrientationControls();
        record("debug_sdk_orientation", "orientation",
                horizontal ? "horizontal" : "vertical");
        if (requestedOpen && activePreview == debugPreview && activeCameraViewpoint >= 0) {
            openStockAvmNow(activeCameraViewpoint, true);
        }
    }

    private void updateDebugOrientationControls() {
        if (debugHorizontalButton != null) {
            debugHorizontalButton.setBackgroundColor(tabColor(debugHorizontal));
        }
        if (debugVerticalButton != null) {
            debugVerticalButton.setBackgroundColor(tabColor(!debugHorizontal));
        }
        if (debugLayoutTitle != null) {
            debugLayoutTitle.setText("Режими AVM (VIEW_GROUP_"
                    + (debugHorizontal ? "H" : "V") + ")");
        }
    }

    private View buildProductionPreview() {
        cameraPreviewHost = new FrameLayout(this);
        cameraPreviewHost.setBackgroundColor(Color.BLACK);
        cameraPositionHost = cameraPreviewHost;
        cameraPositionWidget = new FrameLayout(this);
        cameraPositionWidget.setClipChildren(true);
        cameraPositionWidget.setBackgroundColor(Color.rgb(38, 38, 38));
        cameraPreviewFrame = buildPreviewFrame(false);
        cameraPositionWidget.addView(cameraPreviewFrame,
                new FrameLayout.LayoutParams(1, 1));
        cameraPreviewHost.addView(cameraPositionWidget,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        cameraPreviewHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateCameraPositionCanvasSize());
        cameraPositionWidget.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateCameraPositionHandle());
        return cameraPreviewHost;
    }

    private void updateProductionPreviewSize() {
        if (cameraPreviewHost == null || cameraPreviewFrame == null
                || cameraScaleInput == null || cameraPreviewHost.getWidth() == 0) return;
        DirectCameraCrop crop = loadCalibrationCrop(selectedCameraId);
        updateCameraPositionCanvasSize();
        updateCameraPositionHandle();
        if (cameraPreview != null) {
            CameraProfile profile = CameraProfile.of(selectedCameraId);
            CameraDewarpConfig dewarp = CameraDewarpConfig.loadForProfile(
                    preferences, profile);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            cameraPreview.post(() -> {
                cameraPreview.applyRawFallbackCrop(raw);
                applyDewarpSourceRoi(cameraPreview, raw);
                cameraPreview.applyDewarpConfig(dewarp);
                cameraPreview.applyDirectCameraCrop(crop);
            });
        }
    }

    private View buildPreviewFrame(boolean debug) {
        FrameLayout frame = new FrameLayout(this);
        frame.setClipChildren(true);
        View cover = new View(this);
        cover.setBackgroundColor(Color.BLACK);
        if (debug) {
            SurfaceView surface = new SurfaceView(this);
            surface.setAlpha(1.0f);
            surface.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
            surface.getHolder().addCallback(this);
            frame.addView(surface, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            debugPreview = surface;
            debugPreviewCover = cover;
        } else {
            BlindSpotCameraView surface = new BlindSpotCameraView(this);
            surface.setAutomaticBufferQuality(CameraBufferQuality.load(preferences));
            surface.setForceDewarpPipeline(true);
            CameraProfile profile = CameraProfile.of(selectedCameraId);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            surface.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(surface, raw);
            surface.applyDewarpConfig(
                    CameraDewarpConfig.loadForProfile(preferences, profile));
            surface.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            surface.setCallback(this);
            frame.addView(surface, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
            cameraPreview = surface;
            cameraPreviewCover = cover;
        }
        frame.addView(cover, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private LinearLayout valueRow(String title, String suffix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text = new TextView(this);
        text.setText(title);
        text.setTextColor(Color.LTGRAY);
        text.setTextSize(16);
        row.addView(text, new LinearLayout.LayoutParams(0, dp(60), 1));
        TextView unit = new TextView(this);
        unit.setText(suffix);
        unit.setTextColor(Color.LTGRAY);
        unit.setTextSize(18);
        unit.setGravity(Gravity.CENTER);
        row.addView(unit, new LinearLayout.LayoutParams(dp(68), dp(60)));
        return row;
    }

    private EditText numberInput(float value) {
        EditText input = new EditText(this);
        input.setText(formatAngle(value));
        input.setTextColor(Color.WHITE);
        input.setTextSize(18);
        input.setGravity(Gravity.CENTER);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return input;
    }

    private TextView counter(String title) {
        TextView counter = new TextView(this);
        counter.setTextColor(Color.WHITE);
        counter.setTextSize(30);
        counter.setGravity(Gravity.CENTER);
        counter.setText("0\n" + title);
        return counter;
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

    private Button turnStateButton(String text, int payload) {
        Button button = button(text);
        button.setOnClickListener(view -> requestManualTurnState(payload));
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

    private void saveThresholdsAndPush() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        Thresholds thresholds = readThresholds();
        Integer correctionDelay = readCorrectionDelay();
        Integer maxSpeed = readMaxSpeed();
        if (thresholds == null || correctionDelay == null || maxSpeed == null) {
            guardStatus.setText(
                    "Некоректні значення: кути, затримка 0..1000 мс або швидкість 0..300");
            pushGuardConfig();
            return;
        }
        preferences.edit()
                .putFloat("outward_deg", thresholds.outward)
                .putFloat("center_deg", thresholds.center)
                .putInt("correction_delay_ms", correctionDelay)
                .putInt("max_speed_kph", maxSpeed)
                .apply();
        record("guard_thresholds", "outward_deg", thresholds.outward,
                "center_deg", thresholds.center,
                "correction_delay_ms", correctionDelay,
                "max_speed_kph", maxSpeed);
        pushGuardConfig();
    }

    private void saveRearCameraSpeedRange() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        try {
            int minimum = Integer.parseInt(cameraMinSpeedInput.getText().toString());
            int maximum = Integer.parseInt(cameraMaxSpeedInput.getText().toString());
            if (minimum < 0 || minimum > maximum || maximum > 300) {
                throw new NumberFormatException();
            }
            int savedMinimum = preferences.getInt(
                    BlindSpotOverlayController.PREF_MIN_SPEED,
                    DEFAULT_CAMERA_MIN_SPEED_KPH);
            int savedMaximum = preferences.getInt(
                    BlindSpotOverlayController.PREF_MAX_SPEED,
                    DEFAULT_CAMERA_MAX_SPEED_KPH);
            if (!cameraPolicyChanged(
                    savedMinimum, savedMaximum, minimum, maximum)) return;
            preferences.edit()
                    .putInt(BlindSpotOverlayController.PREF_MIN_SPEED, minimum)
                    .putInt(BlindSpotOverlayController.PREF_MAX_SPEED, maximum)
                    .apply();
            record("camera_settings", "minimum_speed_kph", minimum,
                    "maximum_speed_kph", maximum);
            CameraHelperService.cameraSettingsChanged(this);
        } catch (NumberFormatException error) {
            cameraMinSpeedInput.setText(String.valueOf(preferences.getInt(
                    BlindSpotOverlayController.PREF_MIN_SPEED,
                    DEFAULT_CAMERA_MIN_SPEED_KPH)));
            cameraMaxSpeedInput.setText(String.valueOf(preferences.getInt(
                    BlindSpotOverlayController.PREF_MAX_SPEED,
                    DEFAULT_CAMERA_MAX_SPEED_KPH)));
            cameraStatus.setText("Швидкість має відповідати 0 <= мін. <= макс. <= 300");
        }
    }

    private void saveRearTriggerPolicy() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        try {
            int angle = Integer.parseInt(rearSharpTurnAngleInput.getText().toString());
            if (angle < 0 || angle > 780) throw new NumberFormatException();
            float saved = preferences.getFloat(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE,
                    BlindSpotOverlayController.DEFAULT_REAR_SHARP_TURN_ANGLE_DEG);
            if (Float.compare(saved, angle) == 0) return;
            preferences.edit().putFloat(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE, angle).apply();
            record("rear_sharp_turn_setting", "threshold_deg", angle);
            CameraHelperService.cameraTriggerSettingsChanged(this);
        } catch (NumberFormatException error) {
            rearSharpTurnAngleInput.setText(String.valueOf(Math.round(preferences.getFloat(
                    BlindSpotOverlayController.PREF_REAR_SHARP_TURN_ANGLE,
                    BlindSpotOverlayController.DEFAULT_REAR_SHARP_TURN_ANGLE_DEG))));
            cameraStatus.setText("Кут різкого повороту має бути 0..780°");
        }
    }

    private void saveFrontCameraPolicy() {
        if (settingsTransferInProgress || settingsReloadPending) return;
        try {
            int minimum = Integer.parseInt(frontCameraMinSpeedInput.getText().toString());
            int maximum = Integer.parseInt(frontCameraMaxSpeedInput.getText().toString());
            int angle = Integer.parseInt(frontCameraMinAngleInput.getText().toString());
            if (minimum < 0 || minimum > maximum || maximum > 300
                    || angle < 0 || angle > 780) throw new NumberFormatException();
            int savedMinimum = preferences.getInt(
                    PREF_FRONT_CAMERA_MIN_SPEED, DEFAULT_FRONT_CAMERA_MIN_SPEED_KPH);
            int savedMaximum = preferences.getInt(
                    PREF_FRONT_CAMERA_MAX_SPEED, DEFAULT_FRONT_CAMERA_MAX_SPEED_KPH);
            float savedAngle = preferences.getFloat(
                    PREF_FRONT_CAMERA_MIN_ANGLE, DEFAULT_FRONT_CAMERA_MIN_ANGLE_DEG);
            if (!frontCameraPolicyChanged(savedMinimum, savedMaximum, savedAngle,
                    minimum, maximum, angle)) return;
            preferences.edit()
                    .putInt(PREF_FRONT_CAMERA_MIN_SPEED, minimum)
                    .putInt(PREF_FRONT_CAMERA_MAX_SPEED, maximum)
                    .putFloat(PREF_FRONT_CAMERA_MIN_ANGLE, angle)
                    .apply();
            record("front_camera_settings", "minimum_speed_kph", minimum,
                    "maximum_speed_kph", maximum, "minimum_angle_deg", angle);
            CameraHelperService.cameraSettingsChanged(this);
        } catch (NumberFormatException error) {
            frontCameraMinSpeedInput.setText(
                    String.valueOf(preferences.getInt(PREF_FRONT_CAMERA_MIN_SPEED,
                            DEFAULT_FRONT_CAMERA_MIN_SPEED_KPH)));
            frontCameraMaxSpeedInput.setText(
                    String.valueOf(preferences.getInt(PREF_FRONT_CAMERA_MAX_SPEED,
                            DEFAULT_FRONT_CAMERA_MAX_SPEED_KPH)));
            frontCameraMinAngleInput.setText(String.valueOf(Math.round(preferences.getFloat(
                    PREF_FRONT_CAMERA_MIN_ANGLE, DEFAULT_FRONT_CAMERA_MIN_ANGLE_DEG))));
            cameraStatus.setText("Швидкість: 0 <= мін. <= макс. <= 300; кут: 0..780");
        }
    }

    private void loadCameraProfiles() {
        for (CameraProfile profile : CameraProfile.values()) {
            cameraScale[profile.id] = BlindSpotOverlayController.readScale(
                    preferences, profile);
            cameraTarget[profile.id] = BlindSpotOverlayController.readTarget(
                    preferences, profile);
            cameraX[profile.id] = BlindSpotOverlayController.readPosition(
                    preferences, profile, false);
            cameraY[profile.id] = BlindSpotOverlayController.readPosition(
                    preferences, profile, true);
        }
    }

    private void saveOverlayPlacement() {
        if (cameraScaleInput == null || cameraPreviewFrame == null) return;
        CameraProfile profile = CameraProfile.of(selectedCameraId);
        int scale = BlindSpotOverlayController.MIN_SCALE_PERCENT
                + cameraScaleInput.getProgress();
        cameraScale[selectedCameraId] = scale;
        preferences.edit()
                .putInt(cameraScaleKey(profile), cameraScale[selectedCameraId])
                .putInt(cameraTargetKey(profile), cameraTarget[selectedCameraId])
                .putFloat(cameraXKey(profile), cameraX[selectedCameraId])
                .putFloat(cameraYKey(profile), cameraY[selectedCameraId])
                .apply();
        record("camera_overlay_settings",
                "camera_id", profile.id, "camera", profile.wireName,
                "scale_percent", cameraScale[selectedCameraId],
                "target", CameraDisplayTarget.name(cameraTarget[selectedCameraId]),
                "x", cameraX[selectedCameraId], "y", cameraY[selectedCameraId]);
        CameraHelperService.cameraSettingsChanged(this);
    }

    private void selectCameraGroup(int group) {
        CameraProfile current = CameraProfile.of(selectedCameraId);
        int cameraId;
        if (group == CameraProfile.GROUP_FRONT) {
            cameraId = current.right() ? CameraProfile.FRONT_RIGHT : CameraProfile.FRONT_LEFT;
        } else {
            cameraId = current.right() ? CameraProfile.REAR_RIGHT : CameraProfile.REAR_LEFT;
        }
        selectCameraProfile(cameraId, true);
    }

    private void selectCameraProfile(int cameraId, boolean open) {
        if (!CameraProfile.isValid(cameraId)) return;
        if (selectedCameraId != cameraId) productionPreviewRetryUsed = false;
        boolean switchingOpenCamera = open && requestedOpen
                && activePreview == cameraPreview && selectedCameraId != cameraId;
        selectedCameraId = cameraId;
        if (switchingOpenCamera) closeCameraForTransition("production_camera_changed");
        CameraProfile profile = CameraProfile.of(cameraId);
        if (preferences.getInt("camera_selected_profile", cameraId) != cameraId) {
            preferences.edit().putInt("camera_selected_profile", cameraId).apply();
        }
        boolean front = profile.front();
        rearCameraControlPane.setVisibility(front ? View.GONE : View.VISIBLE);
        frontCameraControlPane.setVisibility(front ? View.VISIBLE : View.GONE);
        cameraRearGroupButton.setBackgroundColor(tabColor(!front));
        cameraFrontGroupButton.setBackgroundColor(tabColor(front));
        cameraLeftPositionButton.setText(front ? "Передня ліва" : "Задня ліва");
        cameraRightPositionButton.setText(front ? "Передня права" : "Задня права");
        cameraLeftPositionButton.setBackgroundColor(tabColor(!profile.right()));
        cameraRightPositionButton.setBackgroundColor(tabColor(profile.right()));
        int scale = cameraScale[cameraId];
        cameraScaleInput.setProgress(scale - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        cameraScaleValue.setText(scale + "%");
        updateCameraTargetButtons();
        updateCameraPositionCanvasSize();
        updateCameraPositionHandle();
        DirectCameraCrop crop = loadCalibrationCrop(cameraId);
        if (cameraPreview != null) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDirectCameraCrop(crop);
        }
        record("camera_profile_selected", "camera_id", cameraId,
                "camera", profile.wireName, "preview_index", profile.previewIndex);
        updateControls();
        if (!open || switchingOpenCamera) return;
        if (requestedOpen && activePreview != cameraPreview) return;
        openStockAvm(profile.right()
                ? StockAvmPreview.VIEW_BLIND_SPOT_RIGHT
                : StockAvmPreview.VIEW_BLIND_SPOT_LEFT, false);
    }

    private void selectCameraTarget(int target) {
        if (!CameraDisplayTarget.isValid(target)) return;
        cameraTarget[selectedCameraId] = target;
        updateCameraTargetButtons();
        updateCameraPositionCanvasSize();
        saveOverlayPlacement();
    }

    private void updateCameraTargetButtons() {
        if (cameraTabletTargetButton == null || cameraClusterTargetButton == null) return;
        int target = cameraTarget[selectedCameraId];
        cameraTabletTargetButton.setBackgroundColor(Color.rgb(
                target == CameraDisplayTarget.TABLET ? 78 : 50,
                target == CameraDisplayTarget.TABLET ? 78 : 50,
                target == CameraDisplayTarget.TABLET ? 78 : 50));
        cameraClusterTargetButton.setBackgroundColor(Color.rgb(
                target == CameraDisplayTarget.CLUSTER ? 78 : 50,
                target == CameraDisplayTarget.CLUSTER ? 78 : 50,
                target == CameraDisplayTarget.CLUSTER ? 78 : 50));
    }

    private void updateCameraPositionCanvasSize() {
        if (cameraPositionHost == null || cameraPositionWidget == null) return;
        int availableWidth = cameraPositionHost.getWidth();
        int availableHeight = cameraPositionHost.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) return;
        int target = cameraTarget[selectedCameraId];
        int aspectWidth = target == CameraDisplayTarget.CLUSTER
                ? CameraDisplayTarget.CLUSTER_REFERENCE_WIDTH : 16;
        int aspectHeight = target == CameraDisplayTarget.CLUSTER
                ? CameraDisplayTarget.CLUSTER_REFERENCE_HEIGHT : 9;
        int width = Math.min(availableWidth, availableHeight * aspectWidth / aspectHeight);
        int height = width * aspectHeight / aspectWidth;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                cameraPositionWidget.getLayoutParams();
        if (params.width == width && params.height == height) {
            updateCameraPositionHandle();
            return;
        }
        params.width = width;
        params.height = height;
        params.gravity = Gravity.CENTER;
        cameraPositionWidget.setLayoutParams(params);
        cameraPositionWidget.post(this::updateCameraPositionHandle);
    }

    private void updateCameraPositionHandle() {
        if (cameraPositionWidget == null || cameraPreviewFrame == null
                || cameraScaleInput == null || cameraPositionWidget.getWidth() == 0) return;
        int scale = cameraScale[selectedCameraId];
        DirectCameraCrop crop = loadCalibrationCrop(selectedCameraId);
        CameraProfile profile = CameraProfile.of(selectedCameraId);
        float frameAspect = BlindSpotOverlayController.readFrameAspect(
                preferences, profile, crop.outputAspect());
        int requestedWidth = cameraPositionWidget.getWidth() * scale / 100;
        int[] size = BlindSpotOverlayController.fitAspect(requestedWidth,
                cameraPositionWidget.getWidth(), cameraPositionWidget.getHeight(),
                frameAspect);
        int width = size[0];
        int height = size[1];
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                cameraPreviewFrame.getLayoutParams();
        params.width = width;
        params.height = height;
        cameraPreviewFrame.setLayoutParams(params);
        cameraPreviewFrame.setX((cameraPositionWidget.getWidth() - width)
                * cameraX[selectedCameraId]);
        cameraPreviewFrame.setY((cameraPositionWidget.getHeight() - height)
                * cameraY[selectedCameraId]);
    }

    private void moveCameraPositionHandle(float x, float y) {
        float maxX = Math.max(0, cameraPositionWidget.getWidth()
                - cameraPreviewFrame.getWidth());
        float maxY = Math.max(0, cameraPositionWidget.getHeight()
                - cameraPreviewFrame.getHeight());
        cameraPreviewFrame.setX(Math.max(0, Math.min(maxX, x)));
        cameraPreviewFrame.setY(Math.max(0, Math.min(maxY, y)));
    }

    private void captureCameraPositionHandle() {
        float maxX = Math.max(0, cameraPositionWidget.getWidth()
                - cameraPreviewFrame.getWidth());
        float maxY = Math.max(0, cameraPositionWidget.getHeight()
                - cameraPreviewFrame.getHeight());
        cameraX[selectedCameraId] = maxX == 0 ? 0 : cameraPreviewFrame.getX() / maxX;
        cameraY[selectedCameraId] = maxY == 0 ? 0 : cameraPreviewFrame.getY() / maxY;
    }

    private static String cameraScaleKey(CameraProfile profile) {
        if (profile.rear()) {
            return profile.right() ? BlindSpotOverlayController.PREF_RIGHT_SCALE
                    : BlindSpotOverlayController.PREF_LEFT_SCALE;
        }
        return profile.right() ? "camera_front_right_scale_percent"
                : "camera_front_left_scale_percent";
    }

    private static String cameraTargetKey(CameraProfile profile) {
        if (profile.rear()) {
            return profile.right() ? BlindSpotOverlayController.PREF_RIGHT_TARGET
                    : BlindSpotOverlayController.PREF_LEFT_TARGET;
        }
        return profile.right() ? "camera_front_right_display_target"
                : "camera_front_left_display_target";
    }

    private static String cameraXKey(CameraProfile profile) {
        if (profile.rear()) {
            return profile.right() ? BlindSpotOverlayController.PREF_RIGHT_X
                    : BlindSpotOverlayController.PREF_LEFT_X;
        }
        return profile.right() ? "camera_front_right_x" : "camera_front_left_x";
    }

    private static String cameraYKey(CameraProfile profile) {
        if (profile.rear()) {
            return profile.right() ? BlindSpotOverlayController.PREF_RIGHT_Y
                    : BlindSpotOverlayController.PREF_LEFT_Y;
        }
        return profile.right() ? "camera_front_right_y" : "camera_front_left_y";
    }

    void clearCaptureLogs() {
        if (logExportInProgress || compatibilityExportInProgress || activityDestroyed) return;
        String clearing = runtimeText(R.string.runtime_logs_clearing);
        publishSettingsOperation(SettingsOperation.Logs,
                clearing, StatusTone.Warning, true);
        publishSettingsFeedback(clearing, StatusTone.Warning);
        AsyncServiceLog log = activityLog;
        if (log != null) {
            log.flush(() -> logExportExecutor.execute(this::clearCaptureLogsOnWorker));
        } else logExportExecutor.execute(this::clearCaptureLogsOnWorker);
    }

    private void clearCaptureLogsOnWorker() {
        File captures = logFile == null ? null : logFile.getParentFile();
        File[] logs = captures == null ? null
                : captures.listFiles((directory, name) -> name.endsWith(".jsonl"));
        int deleted = 0;
        int failed = 0;
        if (logs != null) {
            for (File file : logs) {
                if (file.delete()) deleted++;
                else failed++;
            }
        }
        record("logs_cleared", "deleted", deleted, "failed", failed);
        int deletedCount = deleted;
        int failedCount = failed;
        mainHandler.post(() -> {
            String message = failedCount == 0
                    ? runtimeText(R.string.runtime_logs_cleared, deletedCount)
                    : runtimeText(R.string.runtime_logs_clear_partial,
                            deletedCount, failedCount);
            StatusTone tone = failedCount == 0 ? StatusTone.Ok : StatusTone.Warning;
            publishSettingsFeedback(message, tone);
            publishSettingsOperation(SettingsOperation.Logs, message, tone, false);
        });
    }

    private Thresholds readThresholds() {
        try {
            float outward = Float.parseFloat(outwardInput.getText().toString());
            float center = Float.parseFloat(centerInput.getText().toString());
            if (!isValidGuardThresholds(outward, center)) {
                return null;
            }
            return new Thresholds(outward, center);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer readCorrectionDelay() {
        try {
            int value = Integer.parseInt(correctionDelayInput.getText().toString());
            return value >= 0 && value <= 1_000 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Integer readMaxSpeed() {
        try {
            int value = Integer.parseInt(maxSpeedInput.getText().toString());
            return value >= 0 && value <= 300 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void pushGuardConfig() {
        if (settingsTransferInProgress || settingsReloadPending || legacyRuntimeBlocked) return;
        Thresholds thresholds = readThresholds();
        Integer correctionDelay = readCorrectionDelay();
        Integer maxSpeed = readMaxSpeed();
        boolean requested = guardSwitch.isChecked();
        boolean enabled = requested && thresholds != null && correctionDelay != null
                && maxSpeed != null;
        if (thresholds == null || correctionDelay == null || maxSpeed == null) {
            thresholds = new Thresholds(DEFAULT_OUTWARD_DEG, DEFAULT_CENTER_DEG);
            correctionDelay = DEFAULT_CORRECTION_DELAY_MS;
            maxSpeed = DEFAULT_MAX_SPEED_KPH;
            if (requested) guardStatus.setText("Некоректні налаштування; guard неактивний");
        }
        IBinder current = helper;
        if (current == null) {
            if (enabled) guardStatus.setText("Очікування внутрішньої служби...");
            return;
        }
        float outward = thresholds.outward;
        float center = thresholds.center;
        int delayMs = correctionDelay;
        int maxSpeedKph = maxSpeed;
        ipcExecutor.execute(() -> transactGuardConfig(
                current, enabled, outward, center, delayMs, maxSpeedKph));
    }

    private void openCamera(String viewName) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        Surface surface = debugPreview.getHolder().getSurface();
        if (current == null || !debugSurfaceReady || !surface.isValid()) {
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
                || reverseCameraPreview == null
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending || cameraTransition.pending()) {
            return;
        }
        if (prepareAutomaticResumeInputIfNeeded() || !reverseCameraSurfacesReady) return;
        int requestId = nextActivityCameraRequestId();
        ReverseCameraCompositionView.SurfaceBundle bundle;
        try {
            bundle = reverseCameraPreview.acquirePreviewSurfaces(requestId);
            beginActivityCameraRequest(true, requestId, bundle.generations);
            reversePreviewBackgroundFailureRequestId = 0;
            reverseCameraPreview.setDewarpStatsContext(
                    requestId, java.util.Arrays.copyOfRange(
                            bundle.generations, 1, bundle.generations.length));
            pendingReversePreviewRequestId = requestId;
            pendingReversePreviewGenerations = bundle.generations.clone();
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
        publishReversePanoramaStatus(
                runtimeText(R.string.runtime_status_opening_panorama),
                StatusTone.Warning, true);
        publishCameraStatus(activeActivityCameraProfile, null,
                "Відкриття камер заднього ходу...", StatusTone.Warning, true);
        record("camera_status", "profile", "reverse", "text",
                "Відкриття камер заднього ходу...",
                "tone", StatusTone.Warning.name(), "pending", true);
        CameraHelperService.cameraPreviewStarted(this);
        record("reverse_preview_open",
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "input_generations", java.util.Arrays.toString(bundle.generations),
                "generations", java.util.Arrays.toString(bundle.generations));
        updateControls();
        IBinder current = helper;
        ipcExecutor.execute(() -> transactOpenReversePreview(
                current, bundle.surfaces, requestId));
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
            openProductionDirectCamera(ReverseCameraLayout.REAR_CAMERA_INDEX);
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
                && inputGenerations != null
                && (inputGenerations.length == 4 || inputGenerations.length == 5);
        activeActivityCameraProfile = automatic
                ? combinedReverseComposition
                        ? reverseCompositionStatusProfile() : selectedProductionProfile()
                : null;
        activeActivityConsumerGeneration = 0;
        activeActivityInputGenerations = inputGenerations == null
                ? new int[0] : inputGenerations.clone();
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        closingActivityCameraRequestId = 0;
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
        View target = debug ? debugPreview : cameraPreview;
        boolean surfaceReady = debug ? debugSurfaceReady : cameraSurfaceReady;
        Surface surface = debug
                ? debugPreview.getHolder().getSurface()
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
        IBinder current = helper;
        View target = debug ? debugPreview : cameraPreview;
        View cover = debug ? debugPreviewCover : cameraPreviewCover;
        boolean surfaceReady = debug ? debugSurfaceReady : cameraSurfaceReady;
        Surface surface = debug
                ? debugPreview.getHolder().getSurface()
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
            updateProductionPreviewSize();
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
        closingActivityCameraRequestId = exactClosingRequestId;
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
        closingActivityCameraRequestId = 0;
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
        closingActivityCameraRequestId = 0;
        clearResumeAutoPreview();
        retireAutomaticPreviewInputs();
        record("camera_transition_close_failed", "token", token, "error", failure);
        updateControls();
    }

    @Override
    public void onReverseDewarpStats(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        if (!CameraDewarpStatsEvent.shouldRecord(
                activityResumed, requestedOpen,
                activePreview == reverseCameraPreview, activeActivityCameraRequestId)
                || stats.requestId != activeActivityCameraRequestId
                || cameraIndex < 1
                || cameraIndex >= activeActivityInputGenerations.length
                || stats.contextGeneration != activeActivityInputGenerations[cameraIndex]) return;
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

    static boolean shouldRenewIdleTabInput(
            boolean resumedFromNonCameraTab,
            boolean transitionAttempted, boolean transitionPending,
            boolean activityClosePending, int closingRequestId) {
        return !resumedFromNonCameraTab
                && !transitionAttempted && !transitionPending
                && !activityClosePending && closingRequestId <= 0;
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

        boolean markMirrorFresh(
                int requestId, int cameraIndex, boolean front, boolean raw) {
            if (!requestFresh || !matches(requestId, cameraIndex, front, raw)) return false;
            mirrorFresh = true;
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

    private void startActivityResumeColdReset() {
        if (!activityResumed || activityDestroyed || shutdownRequested
                || !activityColdResetRequired || activityColdResetInFlight
                || activityColdResetFailed || activityClosePending || helper == null
                || cameraTransition.pending()
                || shouldDeferActivityPreviewForReverse(activeReverseControllerRequestId)) {
            return;
        }
        int expectedShellRequestId = expectedColdResetShellRequestId(
                closingActivityCameraRequestId, automaticPreviewIntentRequestId);
        if (expectedShellRequestId > 0) {
            closingActivityCameraRequestId = expectedShellRequestId;
        }
        String token = cameraTransition.begin(CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);
        activityColdResetInFlight = true;
        record("activity_camera_cold_reset", "state", "requested",
                "token", token, "request_id", 0, "selected_tab", selectedTab);
        IBinder current = helper;
        ipcExecutor.execute(() -> transactClose(
                current, token, 0));
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
                && (activityClosePending || cameraTransition.pending())
                && isCurrentReverseBackgroundEvent(true, true,
                        closingActivityCameraRequestId, activityCameraShellEpoch,
                        activityAvmShellEpoch, event)) {
            String error = event.optString("error", "stock background closed unexpectedly");
            String token = cameraTransition.pendingToken();
            if (activityColdResetInFlight) failActivityColdReset(requestId, error);
            else if (token != null) failCameraTransition(token, error);
            else finishActivityStoppedClose(requestId, error);
            return true;
        }
        // A requested whole-composition close still needs its matching shell acknowledgement.
        if (isMatchingStockShellCloseError(renderer, event.optString("stage"),
                closingActivityCameraRequestId, requestId)
                || "camera_closed".equals(kind)
                && closingActivityCameraRequestId > 0
                && closingActivityCameraRequestId == requestId
                && (activityClosePending || cameraTransition.pending())) return false;
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
            record("ipc_reply", "operation", "register_callback", "reply", result,
                    "registration_generation", operation.generation,
                    "accepted", accepted, "active", active);
            if (active) {
                runOnUiThread(() -> {
                    pushGuardConfigFromPreferences();
                    advanceStartupAuthorizationFlow();
                });
            }
        } catch (Throwable error) {
            record("ipc_error", "operation", "register_callback", "error", error.toString());
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
            surface.writeToParcel(data, 0);
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
            IBinder current, Surface[] surfaces, int requestId) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(surfaces.length);
            for (Surface surface : surfaces) surface.writeToParcel(data, 0);
            data.writeInt(requestId);
            requireTransaction(current, CameraHelperMain.TX_OPEN_REVERSE_PREVIEW, data, reply);
            String result = reply.readString();
            record("ipc_reply", "operation", "open_reverse_preview",
                    "request_id", requestId, "reply", result);
            JSONObject json = new JSONObject(result);
            if (!"reverse_preview_shell_open_queued".equals(json.optString("kind"))) {
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
            surface.writeToParcel(data, 0);
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
            if (!cameraTransition.matches(reason) || !activityColdResetInFlight) {
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
            if (!activityClosePending || closingActivityCameraRequestId <= 0
                    || requestId != closingActivityCameraRequestId) {
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
        closingActivityCameraRequestId = 0;
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
            closingActivityCameraRequestId = 0;
            updateControls();
            return;
        }
        closingActivityCameraRequestId = 0;
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
                helper != null || legacyRuntimeBlocked,
                adbAuthPending, adbAuthorizationRequested)) {
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
        if (settingsTransferInProgress || settingsReloadPending) return;
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
        if (weatherLocationPermissionPending) {
            if (adbAuthPending) {
                cancelPendingWeatherLocationPermission();
                updateControls();
                return;
            }
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
        maybeStartForegroundAdbAuthorization();
        maybeShowLegacyImportOffer();
    }

    private void maybeShowLegacyImportOffer() {
        if (!activityResumed || !hasWindowFocus() || cameraPermissionPending
                || backgroundStartSettingsPending() || weatherLocationPermissionPending
                || weatherLocationPermissionInFlight || weatherLocationPermissionStartScheduled
                || adbAuthPending || adbAuthorizationStartScheduled
                || settingsTransferInProgress || settingsReloadPending
                || logExportInProgress || compatibilityExportInProgress
                || updateDialog != null || updateProgressDialog != null
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
        if (cameraPermissionPending || backgroundStartSettingsActive || adbAuthPending) return;
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        backgroundStartSettingsRequired = false;
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

    boolean requestAdbAuthorization(
            String event, String operation, boolean automatic) {
        IBinder current = helper;
        boolean authorizeOnly = LegacySettingsImporter.blocksRuntime(this);
        LocalAdbClient.PromptMode mode = automatic
                ? LocalAdbClient.PromptMode.AUTO_ONCE
                : LocalAdbClient.PromptMode.FORCE;
        if (settingsTransferInProgress || settingsReloadPending
                || weatherLocationPermissionPending || weatherLocationPermissionInFlight
                || current == null && !authorizeOnly || automatic && adbAuthPending
                || !automatic && adbAuthPending
                && adbAuthMode == LocalAdbClient.PromptMode.FORCE) {
            return false;
        }
        cancelPendingForegroundAdbAuthorization();
        adbAuthPending = true;
        adbAuthMode = mode;
        publishAdbOperation(true);
        updateControls();
        record(event, "automatic", automatic, "mode", mode.name());
        ipcExecutor.execute(() -> {
            if (!authorizeOnly) {
                transactAdbAuthorization(current, operation, automatic, mode);
                return;
            }
            LocalAdbClient.Result result = LocalAdbClient.authorize(
                    getApplicationContext(), mode, this::record);
            mainHandler.post(() -> {
                if (activityDestroyed) return;
                adbAuthPending = false;
                adbAuthMode = null;
                adbAuthorizationRequested = result.ok;
                publishAdbOperation(false);
                updateControls();
                advanceStartupAuthorizationFlow();
            });
        });
        return true;
    }

    private void transactAdbAuthorization(
            IBinder current,
            String operation,
            boolean automatic,
            LocalAdbClient.PromptMode mode) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeInt(mode == LocalAdbClient.PromptMode.FORCE
                    ? CameraHelperMain.ADB_AUTH_MODE_FORCE
                    : CameraHelperMain.ADB_AUTH_MODE_AUTO_ONCE);
            requireTransaction(current, CameraHelperMain.TX_RETRY_ADB_AUTH, data, reply);
            String response = reply.readString();
            JSONObject result = new JSONObject(response);
            String requestResult = result.optString("result", "error");
            if (!"accepted".equals(requestResult) && !"coalesced".equals(requestResult)) {
                throw new IllegalStateException("ADB request rejected: " + response);
            }
            record("ipc_reply", "operation", operation, "reply", response,
                    "mode", mode.name(), "request_result", requestResult,
                    "replaced_auto", result.optBoolean("replaced_auto"));
            runOnUiThread(() -> {
                adbAuthorizationRequested = true;
                updateControls();
                advanceStartupAuthorizationFlow();
            });
        } catch (Throwable error) {
            record("ipc_error", "operation", operation, "error", error.toString());
            runOnUiThread(() -> {
                if (adbAuthMode == mode) {
                    adbAuthPending = false;
                    adbAuthMode = null;
                }
                if (automatic) adbAuthorizationRequested = false;
                publishAdbOperation(false);
                updateControls();
                advanceStartupAuthorizationFlow();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static boolean shouldStartForegroundAdbAuthorization(boolean permissionPending,
            boolean startupSettingsPending, boolean hasFocus, boolean helperConnected,
            boolean authorizationPending, boolean alreadyRequested) {
        return !permissionPending && !startupSettingsPending && hasFocus && helperConnected
                && !authorizationPending && !alreadyRequested;
    }

    static boolean shouldOpenBackgroundStartSettings(boolean autoStartEnabled,
            boolean permissionPending, boolean hasFocus, boolean settingsRequired,
            boolean settingsActive, boolean authorizationPending) {
        return autoStartEnabled && !permissionPending && hasFocus && settingsRequired
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

    private void handleCameraLaneEvent(JSONObject event) {
        if (rearCameraPolicyStatus == null || frontCameraPolicyStatus == null) return;
        String kind = event.optString("kind");
        int cameraId = event.optInt("camera_id", -1);
        TextView lane = CameraProfile.isValid(cameraId)
                ? (CameraProfile.of(cameraId).front()
                        ? frontCameraPolicyStatus : rearCameraPolicyStatus)
                : null;
        if ("overlay_camera_ready".equals(kind) && lane != null) {
            hideCameraPolicyStatus(lane);
        } else if ("overlay_visibility".equals(kind) && lane != null) {
            hideCameraPolicyStatus(lane);
        } else if ("overlay_camera_output_unavailable".equals(kind) && lane != null) {
            showCameraPolicyStatus(lane, "Помилка: " + event.optString("reason"));
        } else if ("overlay_camera_retry".equals(kind)) {
            if (cameraSwitch.isChecked()) {
                showCameraPolicyStatus(rearCameraPolicyStatus, "Відновлення AVM...");
            }
            if (frontCameraSwitch.isChecked()) {
                showCameraPolicyStatus(frontCameraPolicyStatus, "Відновлення AVM...");
            }
        } else if (CameraHelperMain.CAMERA_OWNER_OVERLAY.equals(
                event.optString("camera_owner"))) {
            if ("camera_opened".equals(kind)) {
                if (cameraSwitch.isChecked()) hideCameraPolicyStatus(rearCameraPolicyStatus);
                if (frontCameraSwitch.isChecked()) {
                    hideCameraPolicyStatus(frontCameraPolicyStatus);
                }
            } else if ("camera_error".equals(kind)) {
                if (isExpectedOverlayColdResetClose(
                        event.optString("stage"), event.optString("error"))) {
                    hideCameraPolicyStatus(rearCameraPolicyStatus);
                    hideCameraPolicyStatus(frontCameraPolicyStatus);
                    return;
                }
                String error = "Помилка: " + event.optString("error");
                if (cameraSwitch.isChecked()) {
                    showCameraPolicyStatus(rearCameraPolicyStatus, error);
                }
                if (frontCameraSwitch.isChecked()) {
                    showCameraPolicyStatus(frontCameraPolicyStatus, error);
                }
            }
        }
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
                // Validate a stock death against the live epoch before retiring that epoch.
                boolean backgroundHandled = handleReversePreviewBackgroundEvent(json);
                rememberActivityAvmShellEpoch(json, false);
                if (backgroundHandled) return;
                if (musicPanel != null) musicPanel.acceptEvent(json);
                handleCameraLaneEvent(json);
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
                                && debugPreview.getHolder().getSurface().isValid()
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
                                && "reverse_preview_with_stock_base".equals(
                                        json.optString("view"))) {
                            if (reversePreviewBackgroundFailureRequestId == requestId) {
                                publishReversePreviewBackgroundUnavailable();
                            } else {
                                publishCameraEventStatus(json, "Очікування перших кадрів...",
                                        StatusTone.Warning, false);
                            }
                        } else publishCameraEventStatus(json,
                                json.optString("renderer").startsWith("stock_avm")
                                || json.optInt("preview_index", -1) < 0
                                ? "Showing " + json.optString("view")
                                : "Showing " + json.optString("view")
                                        + " (preview " + json.optInt("preview_index") + ")",
                                StatusTone.Ok, false);
                    }
                } else if ("stock_avm_stage".equals(kind)) {
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
                            closingActivityCameraRequestId, requestId)) {
                        String failure = json.optString("error", "stock shell close failed");
                        String transitionToken = cameraTransition.pendingToken();
                        if (transitionToken != null) {
                            failCameraTransition(transitionToken, failure);
                        } else if (activityClosePending) {
                            finishActivityStoppedClose(requestId, failure);
                        } else {
                            recordIgnoredActivityCameraEvent(kind, json);
                        }
                        return;
                    }
                    boolean accepted = isCurrentActivityCameraEvent(
                            requestedOpen, activeActivityCameraRequestId, json)
                            && isMatchingActivityCameraRenderer(json);
                    if (!accepted) {
                        recordIgnoredActivityCameraEvent(kind, json);
                    } else {
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
                        closingActivityCameraRequestId = 0;
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
                    if (CameraTransition.reasonEquals(
                            reason, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)) {
                        boolean requestMatches = closingActivityCameraRequestId > 0
                                && closingActivityCameraRequestId == requestId;
                        boolean shellEvent = "stock_avm_shell".equals(renderer)
                                && "camera_closed".equals(kind);
                        if (!activityColdResetInFlight || !cameraTransition.matches(reason)
                                || !requestMatches || !shellEvent) {
                            clearStaleColdResetInFlight(reason);
                            recordIgnoredActivityCameraEvent(kind, json);
                            return;
                        }
                        boolean cameraEpochMatches = isMatchingActivityCameraShellEpochStrict(
                                activityCameraShellEpoch,
                                json.optLong("camera_shell_epoch", 0));
                        boolean avmEpochMatches = isMatchingCurrentAvmShellEpoch(json);
                        if (!cameraEpochMatches || !avmEpochMatches) {
                            clearStaleColdResetInFlight(reason);
                            recordIgnoredActivityCameraEvent(kind, json);
                        } else if (!isMatchingColdResetShellCallback(
                                reason, reason, renderer, kind, error,
                                closingActivityCameraRequestId, requestId,
                                activityCameraShellEpoch,
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
                            requestId, renderer)) {
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
                            boolean identityMatches = cameraTransition.matches(reason)
                                    && closingActivityCameraRequestId > 0
                                    && closingActivityCameraRequestId == requestId
                                    && isMatchingActivityCameraShellEpochStrict(
                                    activityCameraShellEpoch,
                                    json.optLong("camera_shell_epoch", 0))
                                    && isMatchingCurrentAvmShellEpoch(json);
                            if (!identityMatches) {
                                recordIgnoredActivityCameraEvent(kind, json);
                            } else if (isMatchingPendingActivityTransitionShellClose(
                                    cameraTransition.pendingToken(), reason, renderer, kind, error,
                                    closingActivityCameraRequestId, requestId,
                                    activityCameraShellEpoch,
                                    json.optLong("camera_shell_epoch", 0),
                                    activityAvmShellEpoch, json.optLong("avm_shell_epoch", 0))) {
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
                        closingActivityCameraRequestId = 0;
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
                            renewSelectedPreviewInputForTabSwitch();
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
                } else if ("adb_auth_start".equals(kind)) {
                    adbAuthPending = true;
                    LocalAdbClient.PromptMode eventMode = adbPromptMode(
                            json.optString("mode"));
                    if (adbAuthMode != LocalAdbClient.PromptMode.FORCE
                            || eventMode == LocalAdbClient.PromptMode.FORCE) {
                        adbAuthMode = eventMode;
                    }
                    publishAdbOperation(true);
                } else if ("adb_auth_state".equals(kind)) {
                    adbAuthPending = json.optBoolean("pending");
                    adbAuthMode = adbAuthPending
                            ? adbPromptMode(json.optString("mode")) : null;
                    publishAdbOperation(adbAuthPending);
                } else if ("authorization_superseded".equals(kind)) {
                    adbAuthMode = adbPromptMode(json.optString("next_mode"));
                    adbAuthPending = adbAuthMode != null;
                    publishAdbOperation(adbAuthPending);
                } else if ("adb_auth_auto_blocked".equals(kind)) {
                    publishAdbOperation(adbAuthPending);
                } else if ("adb_auth_result".equals(kind)) {
                    publishAdbOperation(adbAuthPending);
                } else if ("helper_launch".equals(kind) && !json.optBoolean("ok")) {
                    telemetryReady = false;
                    manualGearPark = false;
                    publishSettingsFeedback(runtimeText(R.string.runtime_status_helper_error,
                                    json.optString("error")),
                            StatusTone.Error);
                } else if ("helper_death".equals(kind)
                        || "helper_ping_failed".equals(kind)) {
                    telemetryReady = false;
                    manualGearPark = false;
                    publishGuardStatus("Helper відновлюється: "
                            + json.optString("error"), StatusTone.Error);
                    publishSettingsFeedback("Helper відновлюється: "
                            + json.optString("error"), StatusTone.Error);
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
                } else if ("lifetime_counters".equals(kind)) {
                    lifetimeActivations = json.optLong(
                            "activation_count", lifetimeActivations);
                    lifetimeCorrections = json.optLong(
                            "correction_count", lifetimeCorrections);
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
        activityAvmShellEpoch = nextActivityAvmShellEpoch(
                activityAvmShellEpoch,
                event.optString("kind"), event.optString("renderer"),
                event.optLong("avm_shell_epoch", 0), acceptedCameraOpen);
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
        closingActivityCameraRequestId = 0;
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
        return activityResumed && !shutdownRequested && !activityDestroyed
                && !activityClosePending && closingActivityCameraRequestId <= 0;
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
        int expectedRequestId = requestedOpen
                ? activeActivityCameraRequestId : closingActivityCameraRequestId;
        return expectedRequestId > 0 && eventRequestId == expectedRequestId;
    }

    private boolean shouldPreserveAutoPreviewAfterClose(String reason) {
        if (shutdownRequested) return false;
        if (CameraTransition.owns(reason)
                || "activity_stopped".equals(reason)
                || "replace_with_multi_preview".equals(reason)) return true;
        return !activityResumed && hasAutoPreviewIntent();
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

        synchronized T start() {
            if (desired) return null;
            desired = true;
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
            return desired ? value : null;
        }

        synchronized void disconnected(T value) {
            if (connection != value) return;
            connection = null;
            desiredGeneration = 0;
            completedGeneration = 0;
        }

        synchronized Operation<T> queue(T value, long generation) {
            if (!desired || connection != value) return null;
            if (generation <= 0 || generation <= issuedGeneration) {
                throw new IllegalArgumentException("new callback generation required");
            }
            issuedGeneration = generation;
            desiredGeneration = generation;
            completedGeneration = 0;
            return new Operation<>(value, generation);
        }

        synchronized boolean registered(Operation<T> operation) {
            if (!desired || operation == null || connection != operation.connection
                    || desiredGeneration != operation.generation) return false;
            completedGeneration = operation.generation;
            return true;
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
        if (!isCurrentActivityCameraTerminalEvent(eventRequestId)) return false;
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

    private void updateCounters() {
        // Lifetime counters are intentionally omitted from the approved Compose UI.
        if (activationCount != null) {
            activationCount.setText(lifetimeActivations + "\nУвімкнень");
        }
        if (correctionCount != null) {
            correctionCount.setText(lifetimeCorrections + "\nКорекцій");
        }
    }

    private void clearPreview(String reason) {
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

    private static void showCameraPolicyStatus(TextView view, String message) {
        view.setText(message);
        view.setVisibility(View.VISIBLE);
    }

    private static void hideCameraPolicyStatus(TextView view) {
        view.setText("");
        view.setVisibility(View.GONE);
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
        if (debugPreview == null) return;
        boolean raw = preferences.getBoolean("debug_avm_show_raw", true);
        float startX = raw ? 0.0f
                : StockAvmPreview.focusedTileStartX(activeCameraViewpoint);
        debugPreview.setPivotX(debugPreview.getWidth());
        debugPreview.setPivotY(0.0f);
        debugPreview.setScaleX(startX > 0.0f ? 1.0f / (1.0f - startX) : 1.0f);
        debugPreview.setScaleY(1.0f);
    }

    private void updateControls() {
        boolean manualAllowed = manualDiagnosticsAllowed();
        if (productionUi != null) {
            productionUi.setLegacyRuntimeBlocked(legacyRuntimeBlocked);
            productionUi.setManualDiagnostics(manualAllowed, manualSignalStatus);
        }
        if (musicPanel != null) musicPanel.setControlEnabled(!shutdownRequested);
        if (weatherPanel != null) weatherPanel.setControlsEnabled(
                !shutdownRequested && !legacyRuntimeBlocked
                        && !settingsTransferInProgress && !settingsReloadPending
                        && !logExportInProgress && !compatibilityExportInProgress);
        if (settingsPanel != null) {
            settingsPanel.setControlsEnabled(
                    shutdownRequested,
                    !shutdownRequested && !cameraPermissionPending
                            && !backgroundStartSettingsActive
                            && !backgroundStartSettingsStartScheduled && !adbAuthPending,
                    !settingsTransferInProgress && !settingsReloadPending
                            && !backgroundStartSettingsPending()
                            && shouldEnableManualAdbAuthorization(
                                    helper != null || legacyRuntimeBlocked,
                                    adbAuthPending, adbAuthMode),
                    !requestedOpen && !cameraHandoffPending);
            if (legacyRuntimeBlocked) {
                settingsPanel.setServiceStatus(
                        runtimeText(R.string.runtime_legacy_runtime_blocked));
            }
        }
        if (guardSwitch != null) {
            boolean enabled = guardSwitch.isChecked();
            boolean guardReady = helper != null && telemetryReady;
            guardSwitch.setEnabled(guardReady || enabled);
            outwardInput.setEnabled(enabled && guardReady);
            centerInput.setEnabled(enabled && guardReady);
            correctionDelayInput.setEnabled(enabled && guardReady);
            maxSpeedInput.setEnabled(enabled && guardReady);
        }
        boolean permission = checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        boolean rearCameraEnabled = cameraSwitch != null && cameraSwitch.isChecked();
        boolean frontCameraEnabled = frontCameraSwitch != null && frontCameraSwitch.isChecked();
        boolean selectedCameraEnabled = CameraProfile.of(selectedCameraId).front()
                ? frontCameraEnabled : rearCameraEnabled;
        if (cameraMinSpeedInput != null) cameraMinSpeedInput.setEnabled(rearCameraEnabled);
        if (rearSharpTurnSwitch != null) rearSharpTurnSwitch.setEnabled(rearCameraEnabled);
        if (rearSharpTurnAngleInput != null) {
            rearSharpTurnAngleInput.setEnabled(
                    rearCameraEnabled && rearSharpTurnSwitch.isChecked());
        }
        if (rearBsdOnlySwitch != null) rearBsdOnlySwitch.setEnabled(rearCameraEnabled);
        if (frontCameraMinSpeedInput != null) {
            frontCameraMinSpeedInput.setEnabled(frontCameraEnabled);
        }
        if (frontTurnRequiredSwitch != null) {
            frontTurnRequiredSwitch.setEnabled(frontCameraEnabled);
        }
        if (cameraScaleInput != null) cameraScaleInput.setEnabled(selectedCameraEnabled);
        if (cameraLeftPositionButton != null) {
            cameraLeftPositionButton.setEnabled(true);
        }
        if (cameraRightPositionButton != null) {
            cameraRightPositionButton.setEnabled(true);
        }
        if (cameraTabletTargetButton != null) {
            cameraTabletTargetButton.setEnabled(true);
            cameraClusterTargetButton.setEnabled(true);
        }
        if (cameraPreviewFrame != null) cameraPreviewFrame.setEnabled(selectedCameraEnabled);
        boolean debugStockReady = helper != null && debugSurfaceReady
                && permission && !cameraHandoffPending;
        boolean debugRawReady = debugStockReady && cameraDiscovered;
        for (Button button : viewButtons) {
            if (button != null) button.setEnabled(debugRawReady && !requestedOpen);
        }
        boolean stockReady = helper != null && cameraSurfaceReady
                && permission && !cameraHandoffPending;
        for (Button button : stockAvmButtons) {
            if (button != null) button.setEnabled(stockReady);
        }
        for (Button button : horizontalLayoutButtons) {
            if (button != null) button.setEnabled(debugStockReady);
        }
        if (debugHorizontalButton != null) debugHorizontalButton.setEnabled(debugStockReady);
        if (debugVerticalButton != null) debugVerticalButton.setEnabled(debugStockReady);
        if (debugShowRawSwitch != null) debugShowRawSwitch.setEnabled(debugSurfaceReady);
        if (debugDewarpSwitch != null) {
            debugDewarpSwitch.setEnabled(debugStockReady && !cameraTransition.pending());
        }
        if (rawButton != null) rawButton.setEnabled(debugRawReady && !requestedOpen);
        if (closeButton != null) closeButton.setEnabled(requestedOpen || cameraHandoffPending);
        boolean directReady = helper != null && directCameraSurfaceReady
                && permission && cameraDiscovered && !cameraHandoffPending;
        for (Button button : directCameraIndexButtons) {
            if (button != null) button.setEnabled(directReady
                    && directCameraSelectionAllowed(
                    requestedOpen, activePreview == directCameraPreview,
                    activeActivityCameraOpened));
        }
        boolean calibrationReady = helper != null && calibrationSurfaceReady
                && permission && cameraDiscovered && !cameraHandoffPending
                && (!requestedOpen || activePreview == calibrationPreview);
        for (Button button : calibrationCameraButtons) {
            if (button != null) button.setEnabled(calibrationReady);
        }
        if (calibrationResetButton != null) {
            calibrationResetButton.setEnabled(
                    !calibrationParkingMode || calibrationDewarpSwitch == null
                            || !calibrationDewarpSwitch.isChecked());
        }
        for (Button button : turnStateButtons) {
            if (button != null) button.setEnabled(manualAllowed);
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
            boolean helperConnected,
            boolean authorizationPending,
            LocalAdbClient.PromptMode mode) {
        return helperConnected
                && (!authorizationPending || mode != LocalAdbClient.PromptMode.FORCE);
    }

    static boolean shouldRetryCalibrationCopy(boolean active, int width, int height) {
        return active && (width <= 0 || height <= 0);
    }

    static LocalAdbClient.PromptMode adbPromptMode(String value) {
        if (LocalAdbClient.PromptMode.AUTO_ONCE.name().equals(value)) {
            return LocalAdbClient.PromptMode.AUTO_ONCE;
        }
        if (LocalAdbClient.PromptMode.FORCE.name().equals(value)) {
            return LocalAdbClient.PromptMode.FORCE;
        }
        if (LocalAdbClient.PromptMode.NEVER.name().equals(value)) {
            return LocalAdbClient.PromptMode.NEVER;
        }
        return null;
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

    static boolean isExpectedOverlayColdResetClose(String stage, String error) {
        return stage != null && stage.equals(error)
                && CameraTransition.reasonEquals(
                        stage, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET);
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

    private static String formatAngle(float value) {
        return value == Math.round(value)
                ? String.valueOf(Math.round(value))
                : String.format(Locale.US, "%.1f", value);
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

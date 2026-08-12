package com.byd.turnsignalguard.capture;

import android.Manifest;
import android.app.Activity;
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
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
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

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CameraProbeActivity extends Activity
        implements SurfaceHolder.Callback, BlindSpotCameraView.Callback,
        ReverseCameraCompositionView.Callback {
    private static final String TAG = "BydTurnSignalGuard";
    private static final int CAMERA_PERMISSION_REQUEST = 10;
    private static final float DEFAULT_OUTWARD_DEG = 90.0f;
    private static final float DEFAULT_CENTER_DEG = 10.0f;
    private static final int DEFAULT_CORRECTION_DELAY_MS = 100;
    private static final int DEFAULT_MAX_SPEED_KPH = 30;
    private static final int DEFAULT_CAMERA_MIN_SPEED_KPH = 10;
    private static final int DEFAULT_CAMERA_MAX_SPEED_KPH = 300;
    static final long ADB_AUTH_UI_SETTLE_MS = 600;
    static final long BACKGROUND_START_UI_SETTLE_MS = 600;
    private static final String ADB_WAITING_STATUS = "Очікування ADB/RSA...";
    private static final String PREF_BACKGROUND_START_SETTINGS_SHOWN =
            "background_start_settings_shown";
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
    private static final DirectCameraCrop FULL_CALIBRATION_CROP = DirectCameraCrop.of(
            0.0f, 0.0f, 1.0f, 1.0f,
            DirectCameraCrop.ASPECT_FREE, CameraRotation.DEFAULT_DEGREES);
    private static final String EXTRA_DIAGNOSTIC_AVM_MODE_INDEX =
            "com.byd.turnsignalguard.capture.extra.AVM_MODE_INDEX";
    private static final String EXTRA_DIAGNOSTIC_AVM_CLOSE =
            "com.byd.turnsignalguard.capture.extra.AVM_CLOSE";
    private static int activityCameraRequestSequence;
    private static long helperCallbackRegistrationSequence;

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
    private final HelperCallbackRegistration<IBinder> helperCallbackRegistration =
            new HelperCallbackRegistration<>();
    private final Paint calibrationCropPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Object logLock = new Object();
    private final Button[] viewButtons = new Button[4];
    private final Button[] stockAvmButtons = new Button[2];
    private final Button[] horizontalLayoutButtons =
            new Button[StockAvmPreview.horizontalLayoutCount()];
    private final Button[] directCameraIndexButtons = new Button[5];
    private final Button[] turnStateButtons = new Button[4];
    private final Button[] calibrationAspectButtons = new Button[4];

    private SharedPreferences preferences;
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
    private TextView cameraPositionHandle;
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
    private DirectCameraCrop calibrationRawCrop = DirectCameraCrop.defaultFor(false);
    private DirectCameraCrop calibrationCorrectedCrop = DirectCameraCrop.defaultFor(false);
    private ImageView calibrationCropPreview;
    private FrameLayout calibrationSourceHost;
    private View calibrationSourceFrame;
    private FrameLayout calibrationResultHost;
    private View calibrationResultFrame;
    private ReverseCameraCompositionView reverseCameraPreview;
    private ReverseCameraEditorView reverseCameraEditor;
    private ReverseCameraLayout reverseCameraLayout;
    private ReverseCameraLayout reverseRawCalibrationLayout;
    private Switch reverseCameraSwitch;
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
    private ImageView reverseCalibrationLivePreview;
    private FrameLayout reverseCalibrationLiveFrame;
    private int reverseCalibrationCameraIndex = -1;
    private Bitmap reverseCalibrationCaptureBitmap;
    private Bitmap reverseCalibrationResultBitmap;
    private boolean reverseCalibrationCopyPending;
    private final Button[] reversePaneButtons = new Button[4];
    private final Button[] reverseNudgeButtons = new Button[4];
    private final Button[] reverseInspectorButtons = new Button[4];
    private SeekBar reverseRotationSlider;
    private TextView reverseRotationValue;
    private Button reverseLowerButton;
    private Button reverseRaiseButton;
    private boolean reverseDisplayModeUiUpdating;
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
    private Button cameraDebugTabButton;
    private Button directCameraDebugTabButton;
    private Button cameraAvmDebugSubtabButton;
    private Button reverseCameraTabButton;
    private Button musicTabButton;
    private Button settingsTabButton;
    private Button directCameraCloseButton;
    private final Button[] calibrationCameraButtons = new Button[CameraProfile.COUNT];
    private Button calibrationResetButton;
    private Button calibrationStopButton;
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
    private View cameraDebugPage;
    private View directCameraDebugPage;
    private View debugPage;
    private View reverseCameraPage;
    private View musicPage;
    private View settingsPage;
    private CameraProbeSettingsPanel settingsPanel;
    private CameraProbeMusicPanel musicPanel;
    private File logFile;
    private volatile IBinder helper;
    private volatile boolean cameraSurfaceReady;
    private volatile boolean debugSurfaceReady;
    private volatile boolean directCameraSurfaceReady;
    private volatile boolean calibrationSurfaceReady;
    private volatile boolean reverseCameraSurfacesReady;
    private volatile boolean cameraDiscovered;
    private volatile boolean requestedOpen;
    private boolean telemetryReady;
    private boolean manualTurnRequestPending;
    private boolean adbAuthPending;
    private LocalAdbClient.PromptMode adbAuthMode;
    private boolean adbAuthorizationRequested;
    private boolean adbAuthorizationStartScheduled;
    private boolean cameraPermissionPending;
    private boolean backgroundStartSettingsRequired;
    private boolean backgroundStartSettingsActive;
    private boolean backgroundStartSettingsStartScheduled;
    private boolean helperBound;
    private boolean shutdownRequested;
    private boolean activityResumed;
    private boolean activityDestroyed;
    private boolean updateCheckInFlight;
    private boolean logExportInProgress;
    private boolean debugHorizontal = true;
    private int selectedCameraId = CameraProfile.REAR_LEFT;
    private int calibrationCameraId = CameraProfile.REAR_LEFT;
    private boolean calibrationCopyPending;
    private final float[] cameraX = new float[CameraProfile.COUNT];
    private final float[] cameraY = new float[CameraProfile.COUNT];
    private final int[] cameraScale = new int[CameraProfile.COUNT];
    private final int[] cameraTarget = new int[CameraProfile.COUNT];
    private float dragStartRawX;
    private float dragStartRawY;
    private float dragStartX;
    private float dragStartY;
    private int pendingDiagnosticAvmModeIndex = -1;
    private int pendingCameraViewpoint = -1;
    private int activeCameraViewpoint = -1;
    private int activeDirectCameraIndex = -1;
    private int retryStockViewpoint = -1;
    private boolean retryStockDebug;
    private boolean cameraShellRecoveryPending;
    private boolean cameraShellAvailable;
    private boolean invalidStockSurfaceRetryUsed;
    private boolean pendingCameraDebug;
    private boolean cameraHandoffPending;
    private boolean productionPreviewAwaitingFrame;
    private boolean productionPreviewRetryUsed;
    private int productionPreviewFrameUpdates;
    private int productionPreviewFrameRequest;
    private int pendingReversePreviewRequestId;
    private int[] pendingReversePreviewGenerations;
    private Bitmap calibrationCaptureBitmap;
    private Bitmap calibrationResultBitmap;
    private int selectedTab = -1;
    private int selectedDebugMode;
    private int activeActivityCameraRequestId;
    private int activeActivityConsumerGeneration;
    private int[] activeActivityInputGenerations = new int[0];
    private int closingActivityCameraRequestId;
    private boolean activeActivityCameraOpened;
    private boolean activeActivityCameraFresh;
    private boolean automaticPreviewIntent;
    private int automaticPreviewIntentTab = -1;
    private int automaticPreviewIntentRequestId;
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
        if (shouldStartForegroundAdbAuthorization(cameraPermissionPending,
                backgroundStartSettingsPending(), hasWindowFocus(), helper != null, adbAuthPending,
                adbAuthorizationRequested)) {
            requestAdbAuthorization(
                    "adb_authorization_foreground_start",
                    "foreground_adb_authorization", true);
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
        public void onServiceDisconnected(ComponentName name) {
            IBinder disconnected = helper;
            helper = null;
            helperCallbackRegistration.disconnected(disconnected);
            adbAuthPending = false;
            adbAuthMode = null;
            adbAuthorizationRequested = false;
            cancelPendingForegroundAdbAuthorization();
            telemetryReady = false;
            manualTurnRequestPending = false;
            cameraDiscovered = false;
            requestedOpen = false;
            if (!shutdownRequested && isAutoPreviewTab(selectedTab)) {
                armResumeAutoPreview();
            }
            cameraTransition.cancel();
            cameraShellRecoveryPending = false;
            cameraShellAvailable = false;
            retryStockViewpoint = -1;
            retryStockDebug = false;
            guardStatus.setText("Службу зупинено");
            settingsPanel.setServiceStatus("Службу зупинено");
            settingsPanel.setAdbStatus("ADB/helper недоступний");
            cameraStatus.setText("Службу зупинено");
            debugCameraStatus.setText("Службу зупинено");
            directCameraStatus.setText("Службу зупинено");
            calibrationStatus.setText("Службу зупинено");
            reverseCameraStatus.setText("Службу зупинено");
            musicPanel.setStatus("Helper недоступний");
            stopCalibrationCopies(true);
            clearPreview("helper_service_disconnected");
            activePreview = null;
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
        migrateCameraFrameAspects();
        backgroundStartSettingsRequired = GuardRecovery.isAutoStartEnabled(this)
                && !preferences.getBoolean(PREF_BACKGROUND_START_SETTINGS_SHOWN, false);
        lifetimeActivations = preferences.getLong("activation_count", 0);
        lifetimeCorrections = preferences.getLong("correction_count", 0);
        createLogFile();
        boolean clearedShutdown = GuardRecovery.isUserShutdownActive(this);
        GuardRecovery.setUserShutdownActive(this, false);
        buildUi();
        verifyMappings();
        acceptDiagnosticIntent(getIntent());

        record("activity_start", "log_path", logFile.getAbsolutePath(),
                "shutdown_cleared", clearedShutdown,
                "auto_start", GuardRecovery.isAutoStartEnabled(this),
                "background_start_settings_required", backgroundStartSettingsRequired);
        cameraPermissionPending = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        startAndBindHelperService();

        if (cameraPermissionPending) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
        } else {
            record("camera_permission", "granted", true);
        }
        updateCounters();
        updateControls();
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
        invalidStockSurfaceRetryUsed = false;
        CameraHelperService.activityOpened(this);
        if (!helperBound) startAndBindHelperService();
        enqueueHelperCallbackRegistration(helperCallbackRegistration.start());
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (!activityColdResetRequired && !activityColdResetInFlight) {
            ensureAutomaticPreviewInputs();
        }
        if (activityColdResetRequired) {
            startActivityResumeColdReset();
        } else if (hasAutoPreviewIntent()) {
            record("activity_camera_reopen", "reason", "activity_resumed",
                    "selected_tab", selectedTab,
                    "request_id", automaticPreviewIntentRequestId);
            resumeSelectedCameraPreview();
        }
        if (!activityColdResetRequired) {
            resumeActivityCameraAfterShellRecovery("activity_resumed", 0);
        }
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
        stopCalibrationCopies(true);
        mainHandler.removeCallbacks(runStartupUpdateCheck);
        cancelPendingBackgroundStartSettings();
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
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            cameraPermissionPending = false;
            boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
            record("camera_permission", "granted", granted);
            if (!granted) cameraStatus.setText("Немає CAMERA permission");
            advanceStartupAuthorizationFlow();
            updateControls();
            maybeOpenProductionPreview();
            maybeOpenCalibrationCamera();
            maybeOpenReversePreview();
        }
    }

    @Override
    protected void onStop() {
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        stopCalibrationCopies(true);
        stopReverseCalibrationCopies(false);
        if (!shutdownRequested) {
            // A failed reset is a fail-closed latch for the current lifecycle.
            // The next explicit stop starts a new lifecycle attempt and is the
            // only place where that latch may be cleared.
            activityColdResetFailed = false;
            armResumeAutoPreviewIfNeeded();
        } else clearResumeAutoPreview();
        cameraTransition.cancel();
        // A reset started by a prior resume is no longer owned after stop.
        // Its reply may still arrive on the serialized IPC executor; treat it
        // as stale and let the next resume issue one fresh reset.
        boolean retainColdReset = shouldRetainColdResetAfterCancel(
                activityResumed, shutdownRequested);
        activityColdResetInFlight = false;
        if (retainColdReset) activityColdResetRequired = true;
        retryStockViewpoint = -1;
        retryStockDebug = false;
        cameraShellRecoveryPending = false;
        if (!shutdownRequested) {
            if (cameraMinSpeedInput != null && cameraMaxSpeedInput != null) {
                saveRearCameraSpeedRange();
            }
            if (rearSharpTurnAngleInput != null) saveRearTriggerPolicy();
            if (frontCameraMinSpeedInput != null && frontCameraMaxSpeedInput != null
                    && frontCameraMinAngleInput != null) saveFrontCameraPolicy();
            if (hasAutoPreviewIntent() || requestedOpen || cameraHandoffPending) {
                activityColdResetRequired = true;
            }
            boolean closePending = activityClosePending || closeCamera("activity_stopped");
            if (activityColdResetRequired) activityClosePending = closePending;
            CameraHelperService.activityClosed(this);
        }
        // Keep the callback until onDestroy: stock-shell close completion and
        // reverse state can arrive after the Activity becomes non-visible.
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        if (requestedOpen || cameraHandoffPending) closeCamera("activity_destroyed");
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
        if (updateDialog != null) updateDialog.dismiss();
        if (updateProgressDialog != null) updateProgressDialog.dismiss();
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

    private void confirmDiagnosticLogShare() {
        if (logExportInProgress || shutdownRequested || activityDestroyed) return;
        new AlertDialog.Builder(this)
                .setTitle("Поділитися логами")
                .setMessage("Архів міститиме всі збережені логи застосунку та, якщо ADB "
                        + "вже авторизовано, системні logcat/DropBox. Дані не редагуються й "
                        + "можуть містити приватну або системну інформацію. Передавайте "
                        + "архів лише приватним довіреним каналом.")
                .setNegativeButton("Скасувати", null)
                .setPositiveButton("Створити", (dialog, which) -> startDiagnosticLogExport())
                .show();
    }

    private void startDiagnosticLogExport() {
        if (logExportInProgress || shutdownRequested || activityDestroyed) return;
        logExportInProgress = true;
        settingsPanel.setLogExportInProgress(true);
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
        if (!logExportInProgress || activityDestroyed) {
            logExportInProgress = false;
            return;
        }
        final DiagnosticLogExporter.Snapshot snapshot;
        try {
            snapshot = DiagnosticLogExporter.snapshot(this);
        } catch (Throwable error) {
            finishDiagnosticLogExport(null, error);
            return;
        }
        record("diagnostic_log_export", "state", "snapshot_ready",
                "source_count", snapshot.sources.size());
        try {
            logExportExecutor.execute(() -> {
                File archive = null;
                Throwable failure = null;
                try {
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
        if (error != null) {
            record("diagnostic_log_export", "state", "failed", "error", error.toString());
            if (activityResumed && !activityDestroyed) {
                Toast.makeText(this, "Не вдалося створити архів логів", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (archive == null) return;
        if (!activityResumed || activityDestroyed) {
            boolean deleted = archive.delete();
            record("diagnostic_log_export", "state", "chooser_skipped",
                    "reason", "activity_inactive", "archive_deleted", deleted);
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", archive);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("application/zip")
                    .putExtra(Intent.EXTRA_STREAM, uri);
            share.setClipData(ClipData.newUri(
                    getContentResolver(), "BYD Turn Signal Guard logs", uri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            record("diagnostic_log_export", "state", "chooser_opened",
                    "archive", archive.getName(), "bytes", archive.length());
            startActivity(Intent.createChooser(share, "Поділитися логами"));
        } catch (Throwable shareError) {
            record("diagnostic_log_export", "state", "share_failed",
                    "error", shareError.toString());
            Toast.makeText(this, "Не вдалося відкрити меню поширення", Toast.LENGTH_LONG).show();
        }
    }

    private void runUpdateCheck(boolean force) {
        if (updateCheckInFlight || activityDestroyed) return;
        updateCheckInFlight = true;
        if (settingsPanel != null) settingsPanel.setUpdateButton("Перевірка...", false);
        record("update_check_started", "automatic", !force);
        updateExecutor.execute(() -> {
            try {
                AppUpdateManager.UpdateInfo available = updateManager.checkForUpdate(
                        getApplicationContext(), force);
                runOnUiThread(() -> {
                    updateCheckInFlight = false;
                    restoreUpdateButton();
                    if (available == null) {
                        record("update_check_finished", "result", "up_to_date");
                        if (force) showUpdateMessage(
                                "Оновлення", "Встановлено актуальну версію.");
                    } else {
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
                    if (force) showUpdateError(error);
                });
            }
        });
    }

    private void restoreUpdateButton() {
        if (settingsPanel == null) return;
        settingsPanel.setUpdateButton(
                "Оновлення", !activityDestroyed && updateProgressDialog == null);
    }

    private void showCachedUpdateIfAvailable() {
        AppUpdateManager.UpdateInfo available = AppUpdateManager.cachedAvailable();
        if (!activityResumed || activityDestroyed || available == null
                || updateDialog != null || updateProgressDialog != null) return;

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(12));
        TextView versions = label("Встановлена версія: " + BuildConfig.VERSION_NAME
                + "\nДоступна версія: " + available.version);
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
                .setTitle("Доступне оновлення")
                .setView(scroll)
                .setPositiveButton("Оновити", (dialog, which) -> {
                    AppUpdateManager.clearCachedAvailable();
                    startUpdateDownload(available);
                })
                .setNegativeButton("Пізніше", (dialog, which) ->
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
                .setTitle("Оновлення " + info.version)
                .setMessage("Завантаження: 0%")
                .setCancelable(false)
                .create();
        updateProgressDialog.show();
        updateExecutor.execute(() -> {
            try {
                File file = updateManager.downloadAndVerify(
                        getApplicationContext(), info, progress -> runOnUiThread(() -> {
                            if (updateProgressDialog != null) {
                                updateProgressDialog.setMessage("Завантаження: " + progress + "%");
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
        showUpdateMessage("Не вдалося оновити застосунок",
                error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
    }

    private void showUpdateMessage(String title, String message) {
        if (activityDestroyed || isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("OK", null)
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
    public void onBackPressed() {
        moveTaskToBack(true);
    }

    private void attachHelper(IBinder received) {
        helper = received;
        IBinder registrationTarget = helperCallbackRegistration.connected(received);
        telemetryReady = false;
        cameraDiscovered = false;
        guardStatus.setText("Підключення телеметрії...");
        settingsPanel.setServiceStatus("Служба підключена");
        settingsPanel.setAdbStatus("Перевірка ADB/RSA...");
        cameraStatus.setText("Пошук AVM camera...");
        debugCameraStatus.setText("Пошук AVM camera...");
        directCameraStatus.setText("Пошук direct camera...");
        calibrationStatus.setText("Пошук direct camera...");
        reverseCameraStatus.setText("Пошук AVM camera...");
        record("helper_service_connected");
        updateControls();
        maybeOpenProductionPreview();
        enqueueHelperCallbackRegistration(registrationTarget);
        if (activityResumed && activityColdResetRequired) {
            startActivityResumeColdReset();
        }
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
                "buffer_width", BlindSpotCameraView.BUFFER_WIDTH,
                "buffer_height", BlindSpotCameraView.BUFFER_HEIGHT);
        maybeOpenProductionPreview();
        updateControls();
    }

    @Override
    public void onCameraSurfaceAvailable(
            BlindSpotCameraView view, Surface surface, int width, int height,
            int inputGeneration) {
        onCameraSurfaceAvailable(view, surface, width, height);
        record("activity_camera_input_created",
                "target", view == calibrationPreview ? "camera_calibration" : "production",
                "input_generation", inputGeneration);
    }

    @Override
    public void onCameraSurfaceSizeChanged(
            BlindSpotCameraView view, Surface surface, int width, int height) {
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
                "buffer_width", BlindSpotCameraView.BUFFER_WIDTH,
                "buffer_height", BlindSpotCameraView.BUFFER_HEIGHT);
        updateControls();
    }

    @Override
    public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
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
        cameraStatus.setText("Showing " + CameraProfile.of(selectedCameraId).wireName);
        record("camera_preview_first_frame", "request_id", productionPreviewFrameRequest,
                "frame_updates", productionPreviewFrameUpdates,
                "camera_id", selectedCameraId);
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
        if (view != calibrationPreview) return;
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        updateCalibrationDisplay(CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile)).enabled);
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
        if (activePreview == view) closeCamera("dewarp_renderer_failed");
        TextView status = calibration ? calibrationStatus : cameraStatus;
        status.setText("Помилка корекції камери; відкрийте preview повторно");
    }

    private void onCalibrationDewarpStats(CameraDewarpRenderer.Stats stats) {
        int requestId = activeActivityCameraRequestId;
        if (!CameraDewarpStatsEvent.shouldRecord(
                activityResumed, requestedOpen,
                activePreview == calibrationPreview, requestId)) return;
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraDewarpConfig config = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile));
        record("camera_dewarp_stats", CameraDewarpStatsEvent.calibration(
                requestId, calibrationCameraId, config, stats));
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
        reverseCameraStatus.setText("Live preview");
        record("reverse_preview_frames", "request_id", requestId,
                "generations", java.util.Arrays.toString(generations));
        startReverseCalibrationCopies();
    }

    @Override
    public void onReverseSurfaceLost(int cameraIndex, int generation) {
        reversePreviewFreshness.clear();
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
        reverseCameraSurfacesReady = reverseCameraPreview != null
                && reverseCameraPreview.previewSurfacesReady();
        record("reverse_preview_surfaces", "state", "destroyed",
                "camera_index", cameraIndex, "generation", generation);
        if (activePreview == reverseCameraPreview) closeCamera("reverse_surface_destroyed");
        updateControls();
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
            updateReverseCalibrationDisplay();
        }
        if (!CameraDewarpRenderer.isFatalEventKind(event.kind)
                || activePreview != reverseCameraPreview) return;
        closeCamera("reverse_dewarp_renderer_failed");
        reverseCameraStatus.setText("Помилка корекції камери; відкрийте preview повторно");
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
        cameraTabButton = button("Камери");
        reverseCameraTabButton = button("Задній хід");
        cameraDebugTabButton = button("Відладка");
        musicTabButton = button("Музика");
        settingsTabButton = button("Налаштування");
        guardTabButton.setTextSize(14);
        calibrationTabButton.setTextSize(14);
        cameraTabButton.setTextSize(14);
        reverseCameraTabButton.setTextSize(14);
        cameraDebugTabButton.setTextSize(14);
        musicTabButton.setTextSize(14);
        settingsTabButton.setTextSize(14);
        tabs.addView(guardTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(cameraTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(calibrationTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
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
        reverseCameraPage = buildReverseCameraPanel();
        musicPanel = new CameraProbeMusicPanel(this, preferences);
        musicPage = musicPanel.view();
        cameraDebugPage = buildCameraDebugPanel();
        directCameraDebugPage = buildDirectCameraDebugPanel();
        debugPage = buildCombinedDebugPanel();
        ScrollView settingsScroll = new ScrollView(this);
        settingsScroll.setFillViewport(true);
        settingsPanel = new CameraProbeSettingsPanel(
                this, preferences, this::confirmDiagnosticLogShare);
        settingsScroll.addView(settingsPanel.view(), new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        settingsPage = settingsScroll;
        pages.addView(guardPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(calibrationPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(cameraPage, new FrameLayout.LayoutParams(
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
        reverseCameraTabButton.setOnClickListener(view -> selectTab(TAB_REVERSE_CAMERAS));
        musicTabButton.setOnClickListener(view -> selectTab(TAB_MUSIC));
        cameraDebugTabButton.setOnClickListener(view -> selectTab(TAB_CAMERA_DEBUG));
        settingsTabButton.setOnClickListener(view -> selectTab(TAB_SETTINGS));
        setContentView(root);
        int initialTab = preferences.contains("selected_tab")
                ? preferences.getInt("selected_tab", TAB_GUARD)
                : preferences.getBoolean("camera_tab_selected", false)
                        ? TAB_CAMERAS : TAB_GUARD;
        if (initialTab == TAB_DIRECT_CAMERA_DEBUG) {
            selectDebugMode(0);
            initialTab = TAB_CAMERA_DEBUG;
        }
        selectTab(initialTab);
    }

    private void selectTab(int tab) {
        cancelCalibrationCropInput();
        cancelReverseCropInput();
        if (tab == TAB_DIRECT_CAMERA_DEBUG) tab = TAB_CAMERA_DEBUG;
        if (!isValidTab(tab)) tab = TAB_GUARD;
        int previousTab = selectedTab;
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
        reverseCameraPage.setVisibility(
                tab == TAB_REVERSE_CAMERAS ? View.VISIBLE : View.GONE);
        musicPage.setVisibility(tab == TAB_MUSIC ? View.VISIBLE : View.GONE);
        debugPage.setVisibility(tab == TAB_CAMERA_DEBUG ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);
        if (previousTab != -1 && previousTab != tab
                && shouldRenewIdleTabInput(
                        transitionAttempted, cameraTransition.pending(),
                        activityClosePending, closingActivityCameraRequestId)) {
            renewSelectedPreviewInputForTabSwitch();
        }
        guardTabButton.setBackgroundColor(tabColor(tab == TAB_GUARD));
        calibrationTabButton.setBackgroundColor(
                tabColor(tab == TAB_CAMERA_CALIBRATION));
        cameraTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERAS));
        reverseCameraTabButton.setBackgroundColor(
                tabColor(tab == TAB_REVERSE_CAMERAS));
        musicTabButton.setBackgroundColor(tabColor(tab == TAB_MUSIC));
        cameraDebugTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERA_DEBUG));
        settingsTabButton.setBackgroundColor(tabColor(tab == TAB_SETTINGS));
        preferences.edit().putInt("selected_tab", tab).apply();
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
    }

    private static boolean isValidTab(int tab) {
        return tab == TAB_GUARD || tab == TAB_CAMERAS || tab == TAB_CAMERA_DEBUG
                || tab == TAB_DIRECT_CAMERA_DEBUG || tab == TAB_CAMERA_CALIBRATION
                || tab == TAB_REVERSE_CAMERAS || tab == TAB_MUSIC
                || tab == TAB_SETTINGS;
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

    void onMusicEnabledChanged(boolean checked) {
        record("music_toggle", "enabled", checked);
        CameraHelperService.musicSettingsChanged(this);
        updateControls();
    }

    private View buildReverseCameraPanel() {
        reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
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
        String[] paneNames = {"Тло", "Rear", "Rear left", "Rear right"};
        int[] paneIds = {ReverseCameraLayout.BACKGROUND_PANE_ID,
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

        LinearLayout nudgeRow = new LinearLayout(this);
        String[] nudgeNames = {"Вліво", "Вверх", "Вправо", "Вниз"};
        float[] nudgeX = {-0.01f, 0.0f, 0.01f, 0.0f};
        float[] nudgeY = {0.0f, -0.01f, 0.0f, 0.01f};
        for (int i = 0; i < reverseNudgeButtons.length; i++) {
            final float deltaX = nudgeX[i];
            final float deltaY = nudgeY[i];
            reverseNudgeButtons[i] = button(nudgeNames[i]);
            reverseNudgeButtons[i].setOnClickListener(
                    view -> nudgeReversePane(deltaX, deltaY));
            nudgeRow.addView(reverseNudgeButtons[i],
                    new LinearLayout.LayoutParams(0, dp(38), 1));
        }
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
        reverseDisplayModeInput = new Spinner(this);
        ArrayAdapter<String> displayModeAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"Fit", "Fill", "Stretch"});
        displayModeAdapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        reverseDisplayModeInput.setAdapter(displayModeAdapter);
        LinearLayout zRow = new LinearLayout(this);
        reverseLowerButton = button("Нижче");
        reverseRaiseButton = button("Вище");
        zRow.addView(reverseLowerButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        zRow.addView(reverseRaiseButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        LinearLayout positionPanel = new LinearLayout(this);
        positionPanel.setOrientation(LinearLayout.VERTICAL);
        positionPanel.addView(nudgeRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        positionPanel.addView(zRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));
        View correctionPanel = buildDewarpControls(true);

        LinearLayout mainActions = new LinearLayout(this);
        reverseInspectorButtons[REVERSE_INSPECTOR_POSITION] = button("Позиція");
        reverseInspectorButtons[REVERSE_INSPECTOR_POSITION]
                .setBackgroundColor(tabColor(true));
        reverseCalibrationButton = button("Калібрування");
        mainActions.addView(reverseInspectorButtons[REVERSE_INSPECTOR_POSITION],
                new LinearLayout.LayoutParams(0, dp(38), 1));
        mainActions.addView(reverseCalibrationButton,
                new LinearLayout.LayoutParams(0, dp(38), 1));
        editorPane.addView(mainActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38)));
        editorPane.addView(positionPanel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(84)));

        reverseCameraStatus = statusText("Очікування AVM camera...");
        previewPane.addView(reverseCameraStatus);
        reverseCameraPreview = new ReverseCameraCompositionView(this);
        reverseCameraPreview.setForceDewarpPipeline(true);
        applyReversePreviewDewarpConfigs();
        reverseCameraPreview.enablePreviewBase();
        reverseCameraPreview.setCallback(this);
        reverseCameraPreview.applyRawFallbackLayout(
                ReverseCameraController.loadRawLayout(preferences));
        reverseCameraPreview.applyLayout(reverseCameraLayout);
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
            updateReversePaneControls(selectedCamera);
            if (finished) {
                ReverseCameraController.saveLayout(preferences, layout);
                CameraHelperService.reverseCameraSettingsChanged(this);
                record("reverse_layout_changed", "camera_index", selectedCamera);
            }
        });
        reverseCameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(ReverseCameraController.PREF_ENABLED, checked).apply();
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_camera_toggle", "enabled", checked);
        });
        reset.setOnClickListener(view -> {
            ReverseCameraController.resetLayout(preferences);
            reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            reverseCameraPreview.applyRawFallbackLayout(
                    ReverseCameraController.loadRawLayout(preferences));
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            reverseCameraEditor.selectCamera(ReverseCameraLayout.REAR_CAMERA_INDEX);
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_layout_reset");
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
                                || reverseCameraEditor.selectedCamera()
                                        == ReverseCameraLayout.BACKGROUND_PANE_ID) {
                            return;
                        }
                        int cameraIndex = reverseCameraEditor.selectedCamera();
                        if (reverseCameraLayout.pane(cameraIndex).displayMode == position) return;
                        reverseCameraLayout = ReverseCameraLayout.withDisplayMode(
                                reverseCameraLayout, cameraIndex, position);
                        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
                        reverseCameraPreview.applyLayout(reverseCameraLayout);
                        renderReverseCalibrationCrop();
                        ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
                        CameraHelperService.reverseCameraSettingsChanged(
                                CameraProbeActivity.this);
                        record("reverse_display_mode_changed",
                                "camera_index", cameraIndex,
                                "mode", reverseDisplayModeLabel(position));
                    }

                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        updateReversePaneControls(ReverseCameraLayout.REAR_CAMERA_INDEX);
        return root;
    }

    private View buildReverseCalibrationPane(View rotationPanel, View correctionPanel) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

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
                oldLeft, oldTop, oldRight, oldBottom) -> fitAspectFrame(
                liveHost, reverseCalibrationLiveFrame, 4.0f / 3.0f));
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
        reverseMirrorButton = button("Дзеркало →");
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
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                    CameraDewarpConfig.lensForReverseCamera(
                            reverseCalibrationCameraIndex));
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
                if (reverseCalibrationCameraIndex > 0) {
                    if (raw) reverseCameraPreview.setEditorRawMirror(
                            reverseCalibrationCameraIndex, null);
                    else reverseCameraPreview.setEditorCorrectedMirror(
                            reverseCalibrationCameraIndex, null);
                }
                return true;
            }

            @Override public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
        });
    }

    private void openReverseCalibration() {
        if (reverseCameraEditor == null) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (cameraIndex == ReverseCameraLayout.BACKGROUND_PANE_ID) return;
        reverseCalibrationCameraIndex = cameraIndex;
        reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
        reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
        reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        reverseMainEditorPane.setVisibility(View.GONE);
        reverseCalibrationPane.setVisibility(View.VISIBLE);
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
        if (reverseCalibrationPane != null) reverseCalibrationPane.setVisibility(View.GONE);
        if (reverseMainEditorPane != null) reverseMainEditorPane.setVisibility(View.VISIBLE);
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
        if (reverseCalibrationCameraIndex <= 0 || reverseRawCalibrationLayout == null) return;
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensForReverseCamera(reverseCalibrationCameraIndex));
        boolean rawFallback = reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex);
        CalibrationUiState ui = calibrationUiState(dewarp.enabled, rawFallback);
        if (!ui.correctedEditable
                && reverseCalibrationCropInputStage == CROP_STAGE_CORRECTED) {
            cancelReverseCropInput();
        }
        ReverseCameraLayout.Rect raw = reverseRawCalibrationLayout
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        ReverseCameraLayout.Rect corrected = reverseCameraLayout
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        reverseCalibrationRawOverlay.setCrop(reverseCrop(raw));
        reverseCalibrationCorrectedOverlay.setCrop(reverseCrop(corrected));
        reverseCalibrationCorrectedOverlay.setEnabled(ui.correctedEditable);
        reverseCalibrationCorrectedStage.setVisibility(
                ui.showCorrected ? View.VISIBLE : View.GONE);
        updateReverseCalibrationCropReadouts();
        reverseDisplayModeUiUpdating = true;
        reverseDisplayModeInput.setSelection(reverseCameraLayout
                .pane(reverseCalibrationCameraIndex).displayMode, false);
        reverseDisplayModeUiUpdating = false;
        renderReverseCalibrationCrop();
        startReverseCalibrationCopies();
    }

    private void updateReverseCalibrationCropReadouts() {
        if (reverseCalibrationCameraIndex <= 0
                || reverseCalibrationCropReadouts[CROP_STAGE_RAW] == null
                || reverseRawCalibrationLayout == null || reverseCameraLayout == null) return;
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensForReverseCamera(reverseCalibrationCameraIndex));
        CalibrationUiState ui = calibrationUiState(
                dewarp.enabled, reverseCameraPreview.editorUsesRawFallback(
                        reverseCalibrationCameraIndex));
        ReverseCameraLayout.Rect raw = reverseRawCalibrationLayout
                .pane(reverseCalibrationCameraIndex).sourceCrop;
        ReverseCameraLayout.Rect corrected = reverseCameraLayout
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
            reversePresetLoadButton.setEnabled(CameraCalibrationPreset.hasReverse(
                    preferences, reverseCalibrationCameraIndex));
        }
        int mirrorTarget = CameraCalibrationPreset.reverseMirrorTarget(
                reverseCalibrationCameraIndex);
        if (reverseMirrorButton != null) {
            reverseMirrorButton.setVisibility(mirrorTarget < 0 ? View.GONE : View.VISIBLE);
            reverseMirrorButton.setText(reverseCalibrationCameraIndex
                    == ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX
                    ? "← Дзеркало" : "Дзеркало →");
        }
    }

    private void persistReverseCalibrationCrop(
            ReverseCameraLayout.Rect crop, boolean corrected) {
        ReverseCameraController.saveSourceCrop(
                preferences, reverseCalibrationCameraIndex, crop, corrected);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_crop_saved", "camera_index", reverseCalibrationCameraIndex,
                "stage", corrected ? "corrected" : "raw",
                "left", crop.left, "top", crop.top,
                "width", crop.width, "height", crop.height);
    }

    private void applyReverseCalibrationCrop(
            ReverseCameraLayout.Rect value, boolean corrected, boolean persist) {
        if (reverseCalibrationCameraIndex <= 0) return;
        if (corrected) {
            ReverseCameraLayout.Pane pane = reverseCameraLayout
                    .pane(reverseCalibrationCameraIndex);
            reverseCameraLayout = ReverseCameraLayout.withPane(
                    reverseCameraLayout, reverseCalibrationCameraIndex,
                    pane.destination, value);
            reverseCameraPreview.applyLayout(reverseCameraLayout);
        } else {
            ReverseCameraLayout.Pane pane = reverseRawCalibrationLayout
                    .pane(reverseCalibrationCameraIndex);
            reverseRawCalibrationLayout = ReverseCameraLayout.withPane(
                    reverseRawCalibrationLayout, reverseCalibrationCameraIndex,
                    pane.destination, value);
            reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                    CameraDewarpConfig.lensForReverseCamera(
                            reverseCalibrationCameraIndex));
            if (!dewarp.enabled) {
                ReverseCameraLayout.Pane active = reverseCameraLayout
                        .pane(reverseCalibrationCameraIndex);
                reverseCameraLayout = ReverseCameraLayout.withPane(
                        reverseCameraLayout, reverseCalibrationCameraIndex,
                        active.destination, value);
                reverseCameraPreview.applyLayout(reverseCameraLayout);
            }
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

    private boolean shouldCopyReverseCalibrationFrame() {
        if (reverseCalibrationCameraIndex <= 0
                || reverseCalibrationPane == null
                || reverseCalibrationPane.getVisibility() != View.VISIBLE
                || activePreview != reverseCameraPreview || !requestedOpen) return false;
        TextureView source = reverseCalibrationCopySource();
        return source != null && source.isAvailable();
    }

    private TextureView reverseCalibrationCopySource() {
        if (reverseCalibrationCameraIndex <= 0) return null;
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensForReverseCamera(reverseCalibrationCameraIndex));
        return dewarp.enabled && !reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex)
                ? reverseCalibrationCorrectedMirror : reverseCalibrationRawMirror;
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
        if (clearPreview && reverseCalibrationLivePreview != null) {
            reverseCalibrationLivePreview.setImageDrawable(null);
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
            source.getBitmap(reverseCalibrationCaptureBitmap);
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
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensForReverseCamera(reverseCalibrationCameraIndex));
        boolean corrected = dewarp.enabled && !reverseCameraPreview.editorUsesRawFallback(
                reverseCalibrationCameraIndex);
        ReverseCameraLayout.Pane pane = (corrected
                ? reverseCameraLayout : reverseRawCalibrationLayout)
                .pane(reverseCalibrationCameraIndex);
        int rotationMode = pane.displayMode == ReverseCameraLayout.DISPLAY_MODE_FILL
                ? CameraRotation.MODE_FILL
                : pane.displayMode == ReverseCameraLayout.DISPLAY_MODE_STRETCH
                        ? CameraRotation.MODE_ALIGNED : CameraRotation.MODE_FIT;
        DirectCameraCrop crop = reverseCrop(pane.sourceCrop)
                .withOutputTransform(pane.rotationDegrees, rotationMode);
        int sourceWidth = reverseCalibrationCaptureBitmap.getWidth();
        int sourceHeight = reverseCalibrationCaptureBitmap.getHeight();
        Canvas canvas = new Canvas(reverseCalibrationResultBitmap);
        canvas.drawColor(Color.BLACK);
        Matrix transform = new Matrix();
        CameraRotation.setSourceCropTransform(transform,
                new RectF(crop.left * sourceWidth, crop.top * sourceHeight,
                        crop.right() * sourceWidth, crop.bottom() * sourceHeight),
                new RectF(0, 0, width, height), crop.rotationDegrees,
                crop.rotationMode, new RectF(0, 0, sourceWidth, sourceHeight),
                ReverseCameraLayout.mirrorHorizontally(reverseCalibrationCameraIndex));
        canvas.drawBitmap(
                reverseCalibrationCaptureBitmap, transform, calibrationCropPaint);
        reverseCalibrationLivePreview.setImageBitmap(reverseCalibrationResultBitmap);
        reverseCalibrationLivePreview.invalidate();
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
        if (reverseCameraLayout == null) return;
        int[] paneIds = {ReverseCameraLayout.BACKGROUND_PANE_ID,
                ReverseCameraLayout.REAR_CAMERA_INDEX,
                ReverseCameraLayout.REAR_LEFT_CAMERA_INDEX,
                ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX};
        for (int i = 0; i < reversePaneButtons.length; i++) {
            reversePaneButtons[i].setBackgroundColor(tabColor(paneIds[i] == cameraIndex));
        }
        boolean background = cameraIndex == ReverseCameraLayout.BACKGROUND_PANE_ID;
        if (reverseCalibrationButton != null) {
            reverseCalibrationButton.setVisibility(background ? View.GONE : View.VISIBLE);
        }
        selectReverseInspector(reverseInspectorMode);
        ReverseCameraLayout.Pane pane = background ? null : reverseCameraLayout.pane(cameraIndex);
        reverseDisplayModeUiUpdating = true;
        reverseDisplayModeInput.setSelection(background
                ? ReverseCameraLayout.DEFAULT_DISPLAY_MODE : pane.displayMode, false);
        reverseDisplayModeInput.setEnabled(!background);
        reverseDisplayModeUiUpdating = false;
        reverseRotationUiUpdating = true;
        int rotationDegrees = background ? CameraRotation.DEFAULT_DEGREES
                : pane.rotationDegrees;
        reverseRotationSlider.setProgress(
                rotationDegrees - CameraRotation.MIN_DEGREES);
        reverseRotationSlider.setEnabled(!background);
        reverseRotationValue.setText(background ? "—" : rotationDegrees + "°");
        reverseRotationUiUpdating = false;
        if (background) {
            setDewarpControlsEnabled(true, false);
        } else {
            updateDewarpUi(true, CameraDewarpConfig.load(preferences,
                    CameraDewarpConfig.lensForReverseCamera(cameraIndex)));
        }
        reverseLowerButton.setEnabled(!background && pane.zOrder > 0);
        reverseRaiseButton.setEnabled(!background && pane.zOrder < 2);
    }

    private static String reverseDisplayModeLabel(int mode) {
        if (mode == ReverseCameraLayout.DISPLAY_MODE_FILL) return "Fill";
        if (mode == ReverseCameraLayout.DISPLAY_MODE_STRETCH) return "Stretch";
        return "Fit";
    }

    private void updateReverseRotation(int degrees) {
        if (reverseRotationUiUpdating || reverseCameraEditor == null
                || reverseCameraEditor.selectedCamera()
                        == ReverseCameraLayout.BACKGROUND_PANE_ID) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        int safeDegrees = CameraRotation.clamp(degrees);
        reverseCameraLayout = ReverseCameraLayout.withRotation(
                reverseCameraLayout, cameraIndex, safeDegrees);
        reverseRotationValue.setText(safeDegrees + "°");
        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        renderReverseCalibrationCrop();
    }

    private void persistReverseRotation() {
        if (reverseCameraEditor == null || reverseCameraEditor.selectedCamera()
                == ReverseCameraLayout.BACKGROUND_PANE_ID) return;
        int cameraIndex = reverseCameraEditor.selectedCamera();
        ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_rotation_applied", "camera_index", cameraIndex,
                "degrees", reverseCameraLayout.pane(cameraIndex).rotationDegrees);
    }

    private void changeReverseZ(boolean raise) {
        int cameraIndex = reverseCameraEditor.selectedCamera();
        if (cameraIndex == ReverseCameraLayout.BACKGROUND_PANE_ID) return;
        reverseCameraLayout = raise
                ? ReverseCameraLayout.raise(reverseCameraLayout, cameraIndex)
                : ReverseCameraLayout.lower(reverseCameraLayout, cameraIndex);
        ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        updateReversePaneControls(cameraIndex);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_z_changed", "camera_index", cameraIndex,
                "action", raise ? "raise" : "lower");
    }

    private void nudgeReversePane(float deltaX, float deltaY) {
        int cameraIndex = reverseCameraEditor.selectedCamera();
        reverseCameraLayout = ReverseCameraLayout.move(
                reverseCameraLayout, cameraIndex, deltaX, deltaY);
        ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
        reverseCameraEditor.setLayoutModel(reverseCameraLayout);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("reverse_position_nudged", "camera_index", cameraIndex,
                "delta_x", deltaX, "delta_y", deltaY);
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
        selectCameraProfile(selectedCameraId, false);
        return panel;
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
            Toast.makeText(this, "CORRECTED зараз недоступний",
                    Toast.LENGTH_SHORT).show();
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
                    current.aspectMode, current.rotationDegrees, current.rotationMode);
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
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensForReverseCamera(reverseCalibrationCameraIndex));
        if (stage == CROP_STAGE_CORRECTED
                && (!dewarp.enabled || reverseCameraPreview.editorUsesRawFallback(
                        reverseCalibrationCameraIndex))) return;
        reverseCalibrationCropInputStage = stage;
        ReverseCameraLayout.Rect crop = stage == CROP_STAGE_CORRECTED
                ? reverseCameraLayout.pane(reverseCalibrationCameraIndex).sourceCrop
                : reverseRawCalibrationLayout.pane(reverseCalibrationCameraIndex).sourceCrop;
        fillCropInputs(reverseCalibrationCropInputs,
                crop.left, crop.top, crop.width, crop.height);
        reverseCalibrationNormalControls.setVisibility(View.GONE);
        reverseCalibrationCropInputControls.setVisibility(View.VISIBLE);
        reverseCalibrationCropInputs[0].requestFocus();
    }

    private void applyReverseCropInput() {
        if (reverseCalibrationCropInputStage == CROP_STAGE_NONE) return;
        CameraDewarpConfig dewarp = reverseCalibrationCameraIndex <= 0
                ? null : CameraDewarpConfig.load(preferences,
                        CameraDewarpConfig.lensForReverseCamera(
                                reverseCalibrationCameraIndex));
        if (reverseCalibrationCropInputStage == CROP_STAGE_CORRECTED
                && (dewarp == null || !dewarp.enabled
                        || reverseCameraPreview.editorUsesRawFallback(
                                reverseCalibrationCameraIndex))) {
            cancelReverseCropInput();
            Toast.makeText(this, "CORRECTED зараз недоступний",
                    Toast.LENGTH_SHORT).show();
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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

        calibrationStatus = statusText("Пошук direct camera...");
        panel.addView(calibrationStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        String[] cameraNames = {"Задня ліва", "Задня права",
                "Передня ліва", "Передня права"};
        for (int cameraId = 0; cameraId < calibrationCameraButtons.length; cameraId++) {
            final int selectedId = cameraId;
            calibrationCameraButtons[cameraId] = button(cameraNames[cameraId]);
            calibrationCameraButtons[cameraId].setOnClickListener(
                    view -> selectCalibrationCamera(selectedId, true));
            controls.addView(calibrationCameraButtons[cameraId],
                    new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        panel.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46)));

        LinearLayout aspectControls = new LinearLayout(this);
        aspectControls.setOrientation(LinearLayout.HORIZONTAL);
        for (int mode = DirectCameraCrop.ASPECT_FOUR_THREE;
                mode <= DirectCameraCrop.ASPECT_FREE; mode++) {
            final int selectedMode = mode;
            Button aspectButton = button(DirectCameraCrop.aspectLabel(mode));
            aspectButton.setOnClickListener(view -> selectCalibrationAspect(selectedMode));
            calibrationAspectButtons[mode] = aspectButton;
            aspectControls.addView(aspectButton,
                    new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        calibrationResetButton = button("Скинути");
        calibrationStopButton = button("Stop");
        Button savePreset = button("Зберегти");
        calibrationPresetLoadButton = button("Завантажити");
        calibrationMirrorButton = button("Віддзеркалити →");
        calibrationMirrorButton.setTextSize(10);
        aspectControls.addView(calibrationResetButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        aspectControls.addView(calibrationStopButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        aspectControls.addView(savePreset,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        aspectControls.addView(calibrationPresetLoadButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        aspectControls.addView(calibrationMirrorButton,
                new LinearLayout.LayoutParams(0, dp(46), 1));
        calibrationNormalControls = aspectControls;
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
        calibrationPreview.setAlpha(1.0f);
        calibrationPreview.setForceDewarpPipeline(true);
        calibrationPreview.setCallback(this);
        calibrationPreview.setDewarpStatsSink(this::onCalibrationDewarpStats);
        calibrationPreview.applyRawFallbackCrop(FULL_CALIBRATION_CROP);
        calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
        calibrationPreview.applyDewarpConfig(CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.LENS_LEFT));
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
                currentCalibrationCrop().outputAspect()));
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
            DirectCameraCrop output = calibrationRawCrop.withGeometry(crop);
            calibrationCorrectedCrop = output;
            updateCalibrationUi(output);
            renderCalibrationCrop();
            if (finished) saveCalibrationCrop(output);
        });
        calibrationRawCropOverlay.setListener((crop, finished) -> {
            calibrationRawCrop = calibrationRawCrop.withGeometry(crop);
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
        calibrationStopButton.setOnClickListener(
                view -> stopActivityCameraManually("calibration_user_stop"));
        selectCalibrationCamera(CameraProfile.REAR_LEFT, false);
        return panel;
    }

    private boolean applyCalibrationOutputTransform(int rotationDegrees, int rotationMode) {
        try {
            DirectCameraCrop nextRaw = calibrationRawCrop.withOutputTransform(
                    rotationDegrees, rotationMode);
            DirectCameraCrop nextCorrected = calibrationCorrectedCrop.withOutputTransform(
                    nextRaw.rotationDegrees, nextRaw.rotationMode);
            calibrationRawCrop = nextRaw;
            calibrationCorrectedCrop = nextCorrected;
            return true;
        } catch (IllegalArgumentException error) {
            updateCalibrationUi(currentCalibrationCrop());
            String message = error.getMessage() == null
                    ? "Некоректна геометрія crop" : error.getMessage();
            calibrationStatus.setText(message);
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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

        Button reset = button("Скинути корекцію");
        reset.setOnClickListener(view -> {
            if (reverse) cancelReverseCropInput();
            else cancelCalibrationCropInput();
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
                        || CameraDewarpConfig.load(preferences, lens).projection == position) {
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
        for (Button button : calibrationAspectButtons) {
            if (button != null) button.setEnabled(true);
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
        CameraDewarpConfig previous = CameraDewarpConfig.load(preferences, lens);
        if (persist) {
            CameraDewarpConfig.save(preferences, value);
            if (previous.enabled != value.enabled) refreshDewarpCrops(lens);
        }
        updateDewarpValueLabels(reverse, value);
        if (reverse && CameraDewarpConfig.lensFor(
                CameraProfile.of(calibrationCameraId)) == lens) {
            updateDewarpUi(false, value);
        } else if (!reverse && selectedDewarpLens(true) == lens) {
            updateDewarpUi(true, value);
        }
        if (!reverse && calibrationCropOverlay != null) {
            calibrationCropOverlay.setGridVisible(value.enabled);
            if (calibrationRawCropOverlay != null) {
                calibrationRawCropOverlay.setGridVisible(value.enabled);
            }
        }
        if (calibrationPreview != null
                && CameraDewarpConfig.lensFor(CameraProfile.of(calibrationCameraId)) == lens) {
            calibrationPreview.applyDewarpConfig(value);
            updateCalibrationDisplay(value.enabled);
        }
        if (cameraPreview != null
                && CameraDewarpConfig.lensFor(CameraProfile.of(selectedCameraId)) == lens) {
            cameraPreview.applyDewarpConfig(value);
        }
        applyReversePreviewDewarpConfigs(lens, value);
        if (!persist) return;
        CameraHelperService.cameraSettingsChanged(this);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record("camera_dewarp_changed", "lens", lens, "enabled", value.enabled,
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
            return CameraDewarpConfig.lensFor(CameraProfile.of(calibrationCameraId));
        }
        if (reverseCameraEditor == null || reverseCameraEditor.selectedCamera()
                == ReverseCameraLayout.BACKGROUND_PANE_ID) return 0;
        return CameraDewarpConfig.lensForReverseCamera(
                reverseCameraEditor.selectedCamera());
    }

    private void refreshDewarpCrops(int lens) {
        CameraProfile calibrationProfile = CameraProfile.of(calibrationCameraId);
        if (CameraDewarpConfig.lensFor(calibrationProfile) == lens
                && calibrationCropOverlay != null) {
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(preferences, lens);
            calibrationRawCrop = DirectCameraCrop.load(preferences, calibrationProfile);
            calibrationCorrectedCrop = DirectCameraCrop.loadCorrected(
                    preferences, calibrationProfile, calibrationRawCrop);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            updateCalibrationDisplay(dewarp.enabled);
        }
        CameraProfile previewProfile = CameraProfile.of(selectedCameraId);
        if (cameraPreview != null && CameraDewarpConfig.lensFor(previewProfile) == lens) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, previewProfile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            updateProductionPreviewSize();
        }
        if (reverseCameraLayout != null && reverseCameraEditor != null
                && reverseCameraPreview != null) {
            int selected = reverseCameraEditor.selectedCamera();
            reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            reverseCameraPreview.applyRawFallbackLayout(
                    ReverseCameraController.loadRawLayout(preferences));
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            updateReversePaneControls(selected);
            if (reverseCalibrationCameraIndex > 0) {
                reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
                updateReverseCalibrationDisplay();
            }
        }
    }

    private void applyReversePreviewDewarpConfigs() {
        applyReversePreviewDewarpConfigs(0, null);
    }

    private void applyReversePreviewDewarpConfigs(
            int overrideLens, CameraDewarpConfig override) {
        if (reverseCameraPreview == null) return;
        CameraDewarpConfig rear = overrideLens == CameraDewarpConfig.LENS_REAR
                ? override : CameraDewarpConfig.load(preferences, CameraDewarpConfig.LENS_REAR);
        CameraDewarpConfig left = overrideLens == CameraDewarpConfig.LENS_LEFT
                ? override : CameraDewarpConfig.load(preferences, CameraDewarpConfig.LENS_LEFT);
        CameraDewarpConfig right = overrideLens == CameraDewarpConfig.LENS_RIGHT
                ? override : CameraDewarpConfig.load(preferences, CameraDewarpConfig.LENS_RIGHT);
        reverseCameraPreview.applyDewarpConfigs(rear, left, right);
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
        directCameraCloseButton = button("Stop");
        directCameraCloseButton.setOnClickListener(
                view -> stopActivityCameraManually("direct_user_stop"));
        indices.addView(directCameraCloseButton,
                new LinearLayout.LayoutParams(0, dp(52), 1));
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

    private void selectCalibrationCamera(int cameraId, boolean open) {
        cancelCalibrationCropInput();
        if (!CameraProfile.isValid(cameraId)) return;
        if (open && requestedOpen && activePreview != calibrationPreview) return;
        if (open && requestedOpen && activePreview == calibrationPreview
                && calibrationCameraId == cameraId) return;
        boolean switchingOpenCamera = open && requestedOpen
                && activePreview == calibrationPreview
                && calibrationCameraId != cameraId;
        calibrationCameraId = cameraId;
        if (switchingOpenCamera) closeCameraForTransition("calibration_camera_changed");
        CameraProfile profile = CameraProfile.of(cameraId);
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile));
        calibrationRawCrop = DirectCameraCrop.load(preferences, profile);
        calibrationCorrectedCrop = DirectCameraCrop.loadCorrected(
                preferences, profile, calibrationRawCrop);
        calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
        applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        calibrationPreview.applyDewarpConfig(dewarp);
        updateCalibrationDisplay(dewarp.enabled);
        updateDewarpUi(false, dewarp);
        for (int i = 0; i < calibrationCameraButtons.length; i++) {
            calibrationCameraButtons[i].setBackgroundColor(tabColor(i == cameraId));
        }
        record("calibration_camera_selected", "camera_id", cameraId,
                "camera", profile.wireName, "preview_index", profile.previewIndex);
        if (open && !switchingOpenCamera) openCalibrationCamera(profile.previewIndex);
    }

    private void saveCameraCalibrationPreset() {
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraCalibrationPreset.saveCamera(preferences, profile);
        updateCalibrationCropReadouts();
        Toast.makeText(this, "Пресет збережено", Toast.LENGTH_SHORT).show();
        record("camera_calibration_preset_saved", "camera", profile.wireName);
    }

    private void loadCameraCalibrationPreset() {
        cancelCalibrationCropInput();
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        if (!CameraCalibrationPreset.loadCamera(preferences, profile)) {
            Toast.makeText(this, "Пресет відсутній або некоректний",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        refreshCalibrationSettings("camera_preset_loaded");
        Toast.makeText(this, "Пресет завантажено", Toast.LENGTH_SHORT).show();
    }

    private void mirrorCameraCalibration() {
        cancelCalibrationCropInput();
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraCalibrationPreset.mirrorCamera(preferences, profile);
        refreshCalibrationSettings("camera_calibration_mirrored");
        Toast.makeText(this, "Налаштування віддзеркалено",
                Toast.LENGTH_SHORT).show();
    }

    private void saveReverseCalibrationPreset() {
        if (reverseCalibrationCameraIndex <= 0) return;
        CameraCalibrationPreset.saveReverse(preferences, reverseCalibrationCameraIndex);
        updateReverseCalibrationCropReadouts();
        Toast.makeText(this, "Пресет збережено", Toast.LENGTH_SHORT).show();
        record("reverse_calibration_preset_saved",
                "camera_index", reverseCalibrationCameraIndex);
    }

    private void loadReverseCalibrationPreset() {
        cancelReverseCropInput();
        if (reverseCalibrationCameraIndex <= 0
                || !CameraCalibrationPreset.loadReverse(
                        preferences, reverseCalibrationCameraIndex)) {
            Toast.makeText(this, "Пресет відсутній або некоректний",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        refreshCalibrationSettings("reverse_preset_loaded");
        Toast.makeText(this, "Пресет завантажено", Toast.LENGTH_SHORT).show();
    }

    private void mirrorReverseCalibration() {
        cancelReverseCropInput();
        if (reverseCalibrationCameraIndex <= 0
                || !CameraCalibrationPreset.mirrorReverse(
                        preferences, reverseCalibrationCameraIndex)) return;
        refreshCalibrationSettings("reverse_calibration_mirrored");
        Toast.makeText(this, "Налаштування віддзеркалено",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshCalibrationSettings(String reason) {
        CameraProfile calibrationProfile = CameraProfile.of(calibrationCameraId);
        CameraDewarpConfig calibrationDewarp = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(calibrationProfile));
        calibrationRawCrop = DirectCameraCrop.load(preferences, calibrationProfile);
        calibrationCorrectedCrop = DirectCameraCrop.loadCorrected(
                preferences, calibrationProfile, calibrationRawCrop);
        if (calibrationPreview != null) {
            calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            calibrationPreview.applyDewarpConfig(calibrationDewarp);
            updateDewarpUi(false, calibrationDewarp);
        }

        CameraProfile previewProfile = CameraProfile.of(selectedCameraId);
        if (cameraPreview != null) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, previewProfile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            cameraPreview.applyDewarpConfig(CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(previewProfile)));
            updateProductionPreviewSize();
        }

        applyReversePreviewDewarpConfigs();
        if (reverseCameraPreview != null && reverseCameraEditor != null) {
            int selected = reverseCameraEditor.selectedCamera();
            reverseRawCalibrationLayout = ReverseCameraController.loadRawLayout(preferences);
            reverseCameraLayout = ReverseCameraController.loadLayout(preferences);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            reverseCameraPreview.applyRawFallbackLayout(reverseRawCalibrationLayout);
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            updateReversePaneControls(selected);
            if (reverseCalibrationCameraIndex > 0) updateReverseCalibrationDisplay();
        }
        updateCalibrationCropReadouts();
        CameraHelperService.cameraSettingsChanged(this);
        CameraHelperService.reverseCameraSettingsChanged(this);
        record(reason, "camera_id", calibrationCameraId,
                "reverse_camera_index", reverseCalibrationCameraIndex);
    }

    private DirectCameraCrop loadCalibrationCrop(int cameraId) {
        CameraProfile profile = CameraProfile.of(cameraId);
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile));
        DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
        return dewarp.enabled
                ? DirectCameraCrop.loadCorrected(preferences, profile, raw) : raw;
    }

    private void migrateCameraFrameAspects() {
        for (CameraProfile profile : CameraProfile.values()) {
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(profile));
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
            calibrationCorrectedPane.setVisibility(
                    ui.showCorrected ? View.VISIBLE : View.GONE);
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
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile));
        if (!dewarp.enabled || calibrationPreview.usesRawFallback()) {
            saveCalibrationRawCrop(crop);
            return;
        }
        calibrationCorrectedCrop = calibrationRawCrop.withGeometry(crop);
        DirectCameraCrop.save(preferences, profile, calibrationRawCrop);
        DirectCameraCrop.saveCorrected(preferences, profile, calibrationCorrectedCrop);
        finishCalibrationCropSave("corrected", calibrationCorrectedCrop, dewarp);
    }

    private void saveCalibrationRawCrop(DirectCameraCrop crop) {
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                preferences, CameraDewarpConfig.lensFor(profile));
        calibrationRawCrop = calibrationRawCrop.withGeometry(crop);
        calibrationCorrectedCrop = DirectCameraCrop.preserveCenterAndAspect(
                calibrationCorrectedCrop, calibrationRawCrop);
        DirectCameraCrop.save(preferences, profile, calibrationRawCrop);
        DirectCameraCrop.saveCorrected(preferences, profile, calibrationCorrectedCrop);
        applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
        updateCalibrationDisplay(dewarp.enabled);
        finishCalibrationCropSave("raw", calibrationRawCrop, dewarp);
    }

    private void finishCalibrationCropSave(
            String stage, DirectCameraCrop crop, CameraDewarpConfig dewarp) {
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        record("direct_crop_saved", "camera_id", profile.id, "camera", profile.wireName,
                "stage", stage,
                "dewarp_enabled", dewarp.enabled,
                "dewarp_projection", CameraDewarpConfig.projectionLabel(dewarp.projection),
                "x", crop.left, "y", crop.top,
                "width", crop.width, "height", crop.height,
                "aspect", DirectCameraCrop.aspectLabel(crop.aspectMode),
                "rotation_degrees", crop.rotationDegrees,
                "rotation_mode", CameraRotation.modeLabel(crop.rotationMode),
                "output_aspect", crop.outputAspect());
        updateCameraPositionHandle();
        updateProductionPreviewSize();
        CameraHelperService.cameraSettingsChanged(this);
    }

    private void resetCalibrationCrop() {
        cancelCalibrationCropInput();
        DirectCameraCrop crop = DirectCameraCrop.defaultFor(
                CameraProfile.of(calibrationCameraId).right(),
                calibrationRawCrop.aspectMode);
        calibrationRawCrop = crop;
        calibrationCorrectedCrop = crop.centered();
        DirectCameraCrop.saveCorrected(preferences,
                CameraProfile.of(calibrationCameraId), calibrationCorrectedCrop);
        updateCalibrationDisplay(CameraDewarpConfig.load(preferences,
                CameraDewarpConfig.lensFor(
                        CameraProfile.of(calibrationCameraId))).enabled);
        saveCalibrationRawCrop(crop);
    }

    private void selectCalibrationAspect(int aspectMode) {
        cancelCalibrationCropInput();
        calibrationRawCrop = calibrationRawCrop.withAspectMode(aspectMode);
        calibrationCorrectedCrop = DirectCameraCrop.preserveCenterAndAspect(
                calibrationCorrectedCrop, calibrationRawCrop);
        saveCalibrationRawCrop(calibrationRawCrop);
    }

    private DirectCameraCrop currentCalibrationCrop() {
        if (calibrationCropOverlay == null) {
            return DirectCameraCrop.defaultFor(CameraProfile.of(calibrationCameraId));
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
        for (int mode = 0; mode < calibrationAspectButtons.length; mode++) {
            Button button = calibrationAspectButtons[mode];
            if (button != null) button.setBackgroundColor(tabColor(mode == crop.aspectMode));
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
                    crop.outputAspect());
            calibrationResultFrame.post(this::renderCalibrationCrop);
        }
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
        CameraProfile profile = CameraProfile.of(calibrationCameraId);
        if (calibrationPresetLoadButton != null) {
            calibrationPresetLoadButton.setEnabled(
                    CameraCalibrationPreset.hasCamera(preferences, profile));
        }
        if (calibrationMirrorButton != null) {
            calibrationMirrorButton.setText(profile.right()
                    ? "← Віддзеркалити" : "Віддзеркалити →");
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
        calibrationStatus.setText("Opening pano_h / index " + index + "...");
        record("camera_preview_attach", "state", "started",
                "renderer", "direct_crop_calibration", "camera_tag", "pano_h",
                "preview_index", index, "exclusive", false);
        CameraHelperService.cameraPreviewStarted(this);
        openDirectCameraNow("pano_h", index, true);
    }

    private void maybeOpenCalibrationCamera() {
        if (!canAutoOpenSelectedPreview()
                || selectedTab != TAB_CAMERA_CALIBRATION || helper == null || !cameraDiscovered
                || !calibrationSurfaceReady || requestedOpen || cameraHandoffPending
                || cameraTransition.pending()
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Surface surface = calibrationPreview.getCameraSurface();
        if (surface != null && surface.isValid()) {
            CameraProfile profile = CameraProfile.of(calibrationCameraId);
            calibrationRawCrop = DirectCameraCrop.load(preferences, profile);
            calibrationPreview.applyDirectCameraCrop(FULL_CALIBRATION_CROP);
            applyDewarpSourceRoi(calibrationPreview, calibrationRawCrop);
            calibrationPreview.applyDewarpConfig(CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(profile)));
            openCalibrationCamera(CameraProfile.of(calibrationCameraId).previewIndex);
        }
    }

    private boolean shouldCopyCalibrationFrame() {
        return activityResumed && selectedTab == TAB_CAMERA_CALIBRATION && requestedOpen
                && activePreview == calibrationPreview && calibrationSurfaceReady
                && activeActivityCameraOpened && activeActivityCameraFresh;
    }

    private void startCalibrationCopies() {
        mainHandler.removeCallbacks(copyCalibrationFrame);
        if (shouldCopyCalibrationFrame()) mainHandler.post(copyCalibrationFrame);
    }

    private void stopCalibrationCopies(boolean clearPreview) {
        mainHandler.removeCallbacks(copyCalibrationFrame);
        if (clearPreview && calibrationCropPreview != null) {
            calibrationCropPreview.setImageDrawable(null);
        }
    }

    private void copyCalibrationFrame() {
        if (!shouldCopyCalibrationFrame() || calibrationCopyPending) return;
        int width = calibrationPreview.getWidth();
        int height = calibrationPreview.getHeight();
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
            calibrationPreview.getBitmap(calibrationCaptureBitmap);
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
        CameraRotation.setSourceCropTransform(
                transform,
                new RectF(
                        crop.left * sourceWidth,
                        crop.top * sourceHeight,
                        crop.right() * sourceWidth,
                        crop.bottom() * sourceHeight),
                new RectF(0.0f, 0.0f, width, height),
                crop.rotationDegrees,
                crop.rotationMode,
                new RectF(0.0f, 0.0f, sourceWidth, sourceHeight),
                false);
        canvas.drawBitmap(calibrationCaptureBitmap, transform, calibrationCropPaint);
        calibrationCropPreview.setImageBitmap(calibrationResultBitmap);
        calibrationCropPreview.invalidate();
    }

    private void selectDebugOrientation(boolean horizontal) {
        if (debugHorizontal == horizontal) return;
        debugHorizontal = horizontal;
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
            CameraDewarpConfig dewarp = CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(profile));
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
            surface.setForceDewarpPipeline(true);
            CameraProfile profile = CameraProfile.of(selectedCameraId);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            surface.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(surface, raw);
            surface.applyDewarpConfig(CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(profile)));
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
        if (current == null || !telemetryReady || manualTurnRequestPending) return;
        manualTurnRequestPending = true;
        guardStatus.setText("Команда поворотників: payload " + payload + "...");
        record("manual_turn_state_ui_request", "payload", payload);
        updateControls();
        ipcExecutor.execute(() -> transactManualTurnState(current, payload));
    }

    private void saveThresholdsAndPush() {
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
        preferences.edit().putInt("camera_selected_profile", cameraId).apply();
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
        File captures = logFile == null ? null : logFile.getParentFile();
        File[] logs = captures == null ? null
                : captures.listFiles((directory, name) -> name.endsWith(".jsonl"));
        int deleted = 0;
        int failed = 0;
        synchronized (logLock) {
            if (logs != null) {
                for (File file : logs) {
                    if (file.delete()) deleted++;
                    else failed++;
                }
            }
        }
        record("logs_cleared", "deleted", deleted, "failed", failed);
        settingsPanel.setServiceStatus(failed == 0
                ? "Старі JSONL очищено: " + deleted
                : "Очищено " + deleted + ", не видалено " + failed);
    }

    private Thresholds readThresholds() {
        try {
            float outward = Float.parseFloat(outwardInput.getText().toString());
            float center = Float.parseFloat(centerInput.getText().toString());
            if (!Float.isFinite(outward) || !Float.isFinite(center)
                    || outward < 30.0f || outward > 360.0f
                    || center < 2.0f || center > 45.0f || center >= outward) {
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
        debugCameraStatus.setText("Opening " + viewName + " (preview " + index + ")...");
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
                || requestedOpen || cameraHandoffPending) {
            record("open_rejected", "renderer", "direct_avm",
                    "camera_tag", "pano_h", "preview_index", index,
                    "reason", "camera_not_ready");
            return;
        }
        activePreview = directCameraPreview;
        activePreviewCover = directCameraPreviewCover;
        activeCameraViewpoint = -1;
        directCameraStatus.setText(
                "Opening pano_h / index " + index + "...");
        record("camera_preview_attach", "state", "started",
                "renderer", "direct_avm", "camera_tag", "pano_h",
                "preview_index", index, "exclusive", true);
        CameraHelperService.cameraPreviewStarted(this);
        openDirectCameraNow("pano_h", index, false);
    }

    private void maybeOpenReversePreview() {
        if (!canAutoOpenSelectedPreview()
                || selectedTab != TAB_REVERSE_CAMERAS || helper == null || !cameraDiscovered
                || reverseCameraPreview == null || !reverseCameraSurfacesReady
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending || cameraTransition.pending()) {
            return;
        }
        int requestId = nextActivityCameraRequestId();
        ReverseCameraCompositionView.SurfaceBundle bundle;
        try {
            bundle = reverseCameraPreview.acquirePreviewSurfaces(requestId);
            beginActivityCameraRequest(true, requestId, bundle.generations);
            pendingReversePreviewRequestId = requestId;
            pendingReversePreviewGenerations = bundle.generations.clone();
        } catch (Throwable error) {
            activeActivityCameraRequestId = 0;
            activeActivityConsumerGeneration = 0;
            activeActivityInputGenerations = new int[0];
            activeActivityCameraOpened = false;
            activeActivityCameraFresh = false;
            pendingReversePreviewRequestId = 0;
            pendingReversePreviewGenerations = null;
            reverseCameraStatus.setText("Surface unavailable");
            record("reverse_preview_error", "stage", "acquire_surfaces",
                    "error", error.toString());
            return;
        }
        activePreview = reverseCameraPreview;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        requestedOpen = true;
        reverseCameraStatus.setText("Opening stock base + pano_h indexes 1/2/3...");
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
                || selectedTab != TAB_CAMERAS || helper == null || !cameraDiscovered
                || cameraPreview == null || !cameraSurfaceReady
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending || cameraTransition.pending()) {
            return;
        }
        CameraProfile profile = CameraProfile.of(selectedCameraId);
        openStockAvm(profile.right()
                ? StockAvmPreview.VIEW_BLIND_SPOT_RIGHT
                : StockAvmPreview.VIEW_BLIND_SPOT_LEFT, false);
    }

    private void openDirectCameraNow(String cameraTag, int index, boolean calibration) {
        if (!canStartActivityCamera()) return;
        IBinder current = helper;
        View target = calibration ? calibrationPreview : directCameraPreview;
        View cover = calibration ? calibrationPreviewCover : directCameraPreviewCover;
        TextView status = calibration ? calibrationStatus : directCameraStatus;
        boolean surfaceReady = calibration ? calibrationSurfaceReady : directCameraSurfaceReady;
        Surface surface = calibration ? calibrationPreview.getCameraSurface()
                : directCameraPreview.getHolder().getSurface();
        String renderer = calibration ? "direct_crop_calibration" : "direct_avm";
        if (current == null || !surfaceReady || !surface.isValid()) {
            record("open_rejected", "renderer", renderer,
                    "camera_tag", cameraTag, "preview_index", index,
                    "reason", "camera_not_ready_after_handoff");
            activePreview = null;
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
        showPreview(target, cover, false, false);
        requestedOpen = true;
        status.setText("Opening " + cameraTag + " / index " + index + "...");
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
        activeActivityConsumerGeneration = 0;
        activeActivityInputGenerations = inputGenerations == null
                ? new int[0] : inputGenerations.clone();
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        closingActivityCameraRequestId = 0;
        if (automatic) {
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
        cameraStatus(debug).setText("Preparing " + viewName + "...");
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
        int requestId = beginActivityCameraRequest(
                !debug, debug ? new int[0]
                        : new int[]{cameraPreview.cameraInputGeneration()});
        if (!debug) {
            updateProductionPreviewSize();
            CameraProfile profile = CameraProfile.of(selectedCameraId);
            DirectCameraCrop raw = DirectCameraCrop.load(preferences, profile);
            cameraPreview.applyRawFallbackCrop(raw);
            applyDewarpSourceRoi(cameraPreview, raw);
            cameraPreview.applyDewarpConfig(CameraDewarpConfig.load(
                    preferences, CameraDewarpConfig.lensFor(profile)));
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(selectedCameraId));
            requestedOpen = true;
            int previewIndex = right ? 3 : 2;
            cameraStatus.setText("Opening " + viewName + "...");
            record("open_requested", "renderer", renderer,
                    "camera_tag", "pano_h", "preview_index", previewIndex,
                    "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                    "request_id", requestId, "consumer_generation", 0,
                    "input_generations", java.util.Arrays.toString(
                            activeActivityInputGenerations),
                    "direction", right ? "right" : "left",
                    "camera_id", profile.id, "camera", profile.wireName);
            updateControls();
            ipcExecutor.execute(() -> transactOpenDirect(
                    current, surface, "pano_h", previewIndex, requestId, false));
            return;
        }
        applyDebugPreviewMode();
        requestedOpen = true;
        boolean horizontal = debugHorizontal;
        boolean stockDewarp = shouldUseStockDewarp(
                debug, debugDewarpSwitch != null && debugDewarpSwitch.isChecked());
        cameraStatus(debug).setText("Opening " + viewName + "...");
        record("open_requested", "renderer", "stock_avm",
                "view", viewName, "viewpoint", viewpoint,
                "camera_owner", CameraHelperMain.CAMERA_OWNER_ACTIVITY,
                "request_id", requestId, "consumer_generation", 0,
                "input_generations", java.util.Arrays.toString(
                        activeActivityInputGenerations),
                "orientation", horizontal ? "horizontal" : "vertical",
                "show_raw", debugShowRawSwitch.isChecked(),
                "dewarp", stockDewarp,
                "target", "debug");
        updateControls();
        ipcExecutor.execute(() -> transactOpenStockAvm(
                current, surface, viewpoint, horizontal, stockDewarp, requestId));
    }

    private void acceptDiagnosticIntent(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) return;
        if (intent.getBooleanExtra(EXTRA_DIAGNOSTIC_AVM_CLOSE, false)) {
            pendingDiagnosticAvmModeIndex = -1;
            closeCamera("diagnostic_intent");
            return;
        }
        if (!intent.hasExtra(EXTRA_DIAGNOSTIC_AVM_MODE_INDEX)) return;

        int index = intent.getIntExtra(EXTRA_DIAGNOSTIC_AVM_MODE_INDEX, -1);
        if (index < 0 || index >= StockAvmPreview.horizontalLayoutCount()) {
            record("diagnostic_mode_rejected", "index", index, "reason", "invalid_index");
            return;
        }

        invalidStockSurfaceRetryUsed = false;
        selectDebugMode(1);
        selectTab(TAB_CAMERA_DEBUG);
        pendingDiagnosticAvmModeIndex = index;
        String mode = StockAvmPreview.horizontalLayoutName(index);
        debugCameraStatus.setText("Queued " + mode + "...");
        record("diagnostic_mode_requested", "index", index, "view", mode);
        updateControls();
    }

    private void maybeOpenPendingDiagnosticMode(boolean stockReady) {
        if (!stockReady || pendingDiagnosticAvmModeIndex < 0) return;
        int index = pendingDiagnosticAvmModeIndex;
        pendingDiagnosticAvmModeIndex = -1;
        openStockAvm(StockAvmPreview.horizontalViewpoint(index), true);
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
        TextView status = activeCameraStatus();
        if (cameraHandoffPending) {
            cameraPreview.removeCallbacks(finishCameraHandoff);
            debugPreview.removeCallbacks(finishCameraHandoff);
            cameraHandoffPending = false;
            pendingCameraViewpoint = -1;
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
        CameraHelperService.cameraPreviewStopped(this);
        status.setText("Camera closed");
        activePreview = null;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = -1;
        activeActivityCameraRequestId = 0;
        activeActivityConsumerGeneration = 0;
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        activeActivityInputGenerations = new int[0];
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
        String token = cameraTransition.begin(reason);
        boolean waiting = closeCamera(token);
        if (!waiting && closingActivityCameraRequestId <= 0) {
            finishCameraTransition(token, "local");
        }
        return waiting;
    }

    private void finishCameraTransition(String token, String source) {
        if (!cameraTransition.complete(token)) {
            record("camera_transition_ignored", "token", token, "source", source);
            return;
        }
        activityClosePending = false;
        closingActivityCameraRequestId = 0;
        record("camera_transition_completed", "token", token, "source", source,
                "selected_tab", selectedTab);
        if (CameraTransition.reasonEquals(token, "camera_tab_changed")) {
            renewSelectedPreviewInputForTabSwitch();
        }
        resumeSelectedCameraPreview();
        updateControls();
    }

    @Override
    public void onReverseDewarpStats(
            int cameraIndex, CameraDewarpRenderer.Stats stats) {
        int requestId = activeActivityCameraRequestId;
        if (!CameraDewarpStatsEvent.shouldRecord(
                activityResumed, requestedOpen,
                activePreview == reverseCameraPreview, requestId)) return;
        int lens = CameraDewarpConfig.lensForReverseCamera(cameraIndex);
        CameraDewarpConfig config = CameraDewarpConfig.load(preferences, lens);
        record("camera_dewarp_stats", CameraDewarpStatsEvent.reverse(
                requestId, cameraIndex, config, stats));
    }

    private void renewSelectedPreviewInputForTabSwitch() {
        if (selectedTab == TAB_CAMERA_CALIBRATION && calibrationPreview != null) {
            calibrationPreview.retireCameraInput();
            calibrationPreview.ensureCameraInput();
        } else if (selectedTab == TAB_REVERSE_CAMERAS && reverseCameraPreview != null) {
            reverseCameraPreview.retirePreviewInputs();
            reverseCameraPreview.ensurePreviewInputs();
        }
    }

    static boolean shouldRenewIdleTabInput(
            boolean transitionAttempted, boolean transitionPending,
            boolean activityClosePending, int closingRequestId) {
        return !transitionAttempted && !transitionPending
                && !activityClosePending && closingRequestId <= 0;
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
        if (cameraTransition.pending() || activityColdResetRequired
                || activityColdResetInFlight || !canResumeSelectedPreview()) return;
        if (retryStockViewpoint >= 0) {
            int viewpoint = retryStockViewpoint;
            boolean debug = retryStockDebug;
            retryStockViewpoint = -1;
            retryStockDebug = false;
            openStockAvm(viewpoint, debug);
            if (requestedOpen || cameraHandoffPending) clearResumeAutoPreview();
            return;
        }
        if (selectedTab == TAB_CAMERA_CALIBRATION) maybeOpenCalibrationCamera();
        else if (selectedTab == TAB_CAMERAS) maybeOpenProductionPreview();
        else if (selectedTab == TAB_REVERSE_CAMERAS) maybeOpenReversePreview();
    }

    private TextView cameraStatus(boolean debug) {
        return debug ? debugCameraStatus : cameraStatus;
    }

    private TextView activeCameraStatus() {
        if (activePreview == reverseCameraPreview) return reverseCameraStatus;
        if (activePreview == calibrationPreview) return calibrationStatus;
        if (activePreview == directCameraPreview) return directCameraStatus;
        return cameraStatus(activePreview == debugPreview
                || (!requestedOpen && pendingCameraDebug));
    }

    private TextView cameraStatusForEvent(JSONObject event) {
        if (!event.optString("view").startsWith("direct_")) return activeCameraStatus();
        if (activePreview == cameraPreview) return cameraStatus;
        return activePreview == calibrationPreview ? calibrationStatus : directCameraStatus;
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
                    pushGuardConfig();
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
            runOnUiThread(() -> guardStatus.setText("Guard IPC error"));
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
                cameraStatus(activePreview == debugPreview).setText(
                        "Open failed: " + error.getClass().getSimpleName());
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
                TextView status = activePreview == calibrationPreview
                        ? calibrationStatus : directCameraStatus;
                status.setText(
                        "Open failed: " + error.getClass().getSimpleName());
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
                reverseCameraStatus.setText(
                        "Open failed: " + error.getClass().getSimpleName());
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
                cameraStatus(activePreview == debugPreview).setText(
                        "Stock AVM failed: " + error.getClass().getSimpleName());
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
                record("camera_transition_close_failed", "token", reason,
                        "request_id", requestId,
                        "error", error == null ? String.valueOf(result) : error.toString());
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
        cameraStatus.setText("Camera reset failed");
        calibrationStatus.setText("Camera reset failed");
        reverseCameraStatus.setText("Camera reset failed");
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
        if (closingActivityCameraRequestId > 0
                && requestId != closingActivityCameraRequestId) {
            record("activity_camera_close", "state", "ignored",
                    "reason", "request_mismatch", "request_id", requestId,
                    "expected_request_id", closingActivityCameraRequestId);
            return;
        }
        activityClosePending = false;
        if (failure != null && !failure.trim().isEmpty()) {
            activityColdResetRequired = false;
            activityColdResetFailed = true;
            retireAutomaticPreviewInputs();
            record("activity_camera_close", "state", "failed",
                    "reason", "activity_stopped", "request_id", requestId,
                    "error", failure);
            cameraStatus.setText("Camera close failed");
            calibrationStatus.setText("Camera close failed");
            reverseCameraStatus.setText("Camera close failed");
            closingActivityCameraRequestId = 0;
            updateControls();
            return;
        }
        closingActivityCameraRequestId = 0;
        if (activityColdResetRequired && !activityResumed) {
            retireAutomaticPreviewInputs();
        }
        if (activityResumed && activityColdResetRequired) {
            startActivityResumeColdReset();
        }
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

    static boolean isMatchingColdResetShellCallback(
            String token, String reason, String renderer, String kind, String error,
            int expectedRequestId, int eventRequestId) {
        return CameraTransition.reasonEquals(
                token, CameraHelperMain.ACTIVITY_RESUME_COLD_RESET)
                && token.equals(reason)
                && isSuccessfulShellCloseEvent(renderer, kind, error)
                && expectedRequestId > 0 && expectedRequestId == eventRequestId;
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
                backgroundStartSettingsPending(), hasWindowFocus(), helper != null,
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
            Toast.makeText(this,
                    "Вимкніть BYD Turn Signal Guard у списку Disable background Apps",
                    Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            backgroundStartSettingsActive = false;
            record("background_start_settings_open_failed", "reason", reason,
                    "error", error.toString());
            settingsPanel.setServiceStatus("Системне вікно фонового запуску недоступне");
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
        LocalAdbClient.PromptMode mode = automatic
                ? LocalAdbClient.PromptMode.AUTO_ONCE
                : LocalAdbClient.PromptMode.FORCE;
        if (current == null || automatic && adbAuthPending
                || !automatic && adbAuthPending
                && adbAuthMode == LocalAdbClient.PromptMode.FORCE) {
            return false;
        }
        cancelPendingForegroundAdbAuthorization();
        adbAuthPending = true;
        adbAuthMode = mode;
        settingsPanel.setAdbStatus(ADB_WAITING_STATUS);
        updateControls();
        record(event, "automatic", automatic, "mode", mode.name());
        ipcExecutor.execute(() -> transactAdbAuthorization(
                current, operation, automatic, mode));
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
            });
        } catch (Throwable error) {
            record("ipc_error", "operation", operation, "error", error.toString());
            runOnUiThread(() -> {
                if (adbAuthMode == mode) {
                    adbAuthPending = false;
                    adbAuthMode = null;
                }
                if (automatic) adbAuthorizationRequested = false;
                settingsPanel.setAdbStatus("ADB retry IPC error");
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
                guardStatus.setText("Turn-state IPC error");
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
        writeLine(line);
        runOnUiThread(() -> {
            try {
                JSONObject json = new JSONObject(line);
                String kind = json.optString("kind");
                musicPanel.acceptEvent(json);
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
                        if (activityColdResetRequired) startActivityResumeColdReset();
                        else resumeSelectedCameraPreview();
                    }
                    updateControls();
                    return;
                }
                if ("reverse_camera_stopped".equals(kind)) {
                    int requestId = json.optInt("request_id", -1);
                    if (shouldHandleReverseCameraStopped(requestId)) {
                        if (activityColdResetRequired) startActivityResumeColdReset();
                        else resumeSelectedCameraPreview();
                    } else {
                        record("activity_camera_event_ignored", "kind", kind,
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
                            requestedOpen, activeActivityCameraRequestId, requestId,
                            json.optString("source"))) {
                        recordIgnoredActivityCameraEvent(kind, json);
                    } else {
                        activeActivityCameraOpened = true;
                        activeActivityCameraFresh = false;
                        activeActivityConsumerGeneration = json.optInt(
                                "consumer_generation", 0);
                        if (activePreview == cameraPreview) {
                            armProductionPreviewFirstFrame(requestId);
                        } else if (activePreview == calibrationPreview) {
                            calibrationPreviewFreshness.arm(
                                    requestId, activeActivityInputGenerations);
                        } else if (activePreview == reverseCameraPreview) {
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
                            reverseCameraStatus.setText("Очікування перших кадрів...");
                        } else cameraStatusForEvent(json).setText(
                                json.optString("renderer").startsWith("stock_avm")
                                || json.optInt("preview_index", -1) < 0
                                ? "Showing " + json.optString("view")
                                : "Showing " + json.optString("view")
                                        + " (preview " + json.optInt("preview_index") + ")");
                    }
                } else if ("stock_avm_stage".equals(kind)) {
                    cameraStatusForEvent(json).setText(
                            "Stock AVM: " + json.optString("stage"));
                } else if ("camera_discovery".equals(kind)) {
                    cameraDiscovered = json.optBoolean("ok");
                    String status = cameraDiscovered
                            ? "AVM camera ready"
                            : "Camera discovery failed: " + json.optString("error");
                    cameraStatus.setText(status);
                    debugCameraStatus.setText(status);
                    directCameraStatus.setText(cameraDiscovered
                            ? "Detected " + json.optString("candidate_ids")
                            : status);
                    calibrationStatus.setText(cameraDiscovered
                            ? "Direct camera ready: index 2=left, 3=right"
                            : status);
                    reverseCameraStatus.setText(cameraDiscovered
                            ? "AVM ready: indexes 1/2/3" : status);
                    maybeOpenCalibrationCamera();
                    maybeOpenProductionPreview();
                    maybeOpenReversePreview();
                } else if ("camera_error".equals(kind)) {
                    int requestId = json.optInt("request_id", 0);
                    boolean accepted = isCurrentActivityCameraEvent(
                            requestedOpen, activeActivityCameraRequestId, requestId,
                            json.optString("source"));
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
                                cameraStatusForEvent(json).setText(
                                        "AVM Surface invalid; retrying once...");
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
                        stopCalibrationCopies(true);
                        cameraStatusForEvent(json).setText(
                                "Camera error: " + json.optString("error"));
                        clearPreview("camera_error");
                        activePreview = null;
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
                        if (!isMatchingColdResetShellCallback(
                                reason, reason, renderer, kind, error,
                                closingActivityCameraRequestId, requestId)) {
                            failActivityColdReset(requestId,
                                    error == null || error.isEmpty()
                                    ? "invalid stock shell close callback" : error);
                        } else {
                            completeActivityColdReset(reason, requestId, "shell_callback");
                        }
                        return;
                    }
                    if ("activity_stopped".equals(reason)) {
                        boolean requestMatches = closingActivityCameraRequestId > 0
                                && closingActivityCameraRequestId == requestId;
                        if (!activityClosePending || !requestMatches
                                || !"stock_avm_shell".equals(renderer)) {
                            recordIgnoredActivityCameraEvent(kind, json);
                            return;
                        }
                        finishActivityStoppedClose(
                                requestId, error == null || error.isEmpty() ? null : error);
                        return;
                    }
                    boolean accepted = isCurrentActivityCameraTerminalEvent(requestId);
                    if (!accepted) {
                        recordIgnoredActivityCameraEvent(kind, json);
                        return;
                    }
                    if (CameraTransition.owns(reason)) {
                        if ("stock_avm_shell".equals(renderer)) {
                            if (isSuccessfulShellCloseEvent(
                                    renderer, kind, error)) {
                                finishCameraTransition(reason, "shell_callback");
                            } else {
                                record("camera_transition_close_failed",
                                        "token", reason, "request_id", requestId,
                                        "error", error);
                            }
                        } else {
                            record("camera_transition_close_observed", "token", reason,
                                    "source", json.optString("source"));
                        }
                    } else if (!isIntermediateCameraClose(reason)) {
                        if (shouldWaitForStockShellClose(
                                json.optString("view"), renderer)) {
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
                        stopCalibrationCopies(true);
                        cameraStatusForEvent(json).setText("Camera closed");
                        clearPreview("camera_closed");
                        activePreview = null;
                        activePreviewCover = null;
                        if (resumeOverlay) CameraHelperService.cameraPreviewStopped(this);
                        if (hasAutoPreviewIntent()) {
                            renewSelectedPreviewInputForTabSwitch();
                            resumeSelectedCameraPreview();
                        }
                    }
                } else if ("telemetry_ready".equals(kind)) {
                    telemetryReady = json.optBoolean("ok");
                    guardStatus.setText(telemetryReady
                            ? "Телеметрія готова"
                            : "Telemetry error: " + json.optString("error"));
                } else if ("adb_auth_start".equals(kind)) {
                    adbAuthPending = true;
                    LocalAdbClient.PromptMode eventMode = adbPromptMode(
                            json.optString("mode"));
                    if (adbAuthMode != LocalAdbClient.PromptMode.FORCE
                            || eventMode == LocalAdbClient.PromptMode.FORCE) {
                        adbAuthMode = eventMode;
                    }
                    settingsPanel.setAdbStatus(ADB_WAITING_STATUS);
                } else if ("adb_auth_state".equals(kind)) {
                    adbAuthPending = json.optBoolean("pending");
                    adbAuthMode = adbAuthPending
                            ? adbPromptMode(json.optString("mode")) : null;
                    if (adbAuthPending) {
                        settingsPanel.setAdbStatus(ADB_WAITING_STATUS);
                    } else if (settingsPanel.isAdbStatus(ADB_WAITING_STATUS)) {
                        settingsPanel.setAdbStatus(telemetryReady
                                ? "ADB/RSA авторизовано" : "ADB авторизація потрібна");
                    }
                } else if ("authorization_superseded".equals(kind)) {
                    adbAuthMode = adbPromptMode(json.optString("next_mode"));
                    adbAuthPending = adbAuthMode != null;
                    if (adbAuthPending) settingsPanel.setAdbStatus(ADB_WAITING_STATUS);
                } else if ("adb_auth_auto_blocked".equals(kind)) {
                    settingsPanel.setAdbStatus("ADB авторизація потрібна; натисніть повторити");
                } else if ("adb_auth_result".equals(kind)) {
                    if (!json.optBoolean("ok")) {
                        telemetryReady = false;
                        settingsPanel.setAdbStatus("ADB: " + json.optString("error"));
                    } else {
                        settingsPanel.setAdbStatus("ADB/RSA авторизовано");
                    }
                } else if ("helper_launch".equals(kind) && !json.optBoolean("ok")) {
                    telemetryReady = false;
                    settingsPanel.setServiceStatus("Helper: " + json.optString("error"));
                } else if ("helper_death".equals(kind)
                        || "helper_ping_failed".equals(kind)) {
                    telemetryReady = false;
                    guardStatus.setText("Helper відновлюється: " + json.optString("error"));
                    settingsPanel.setServiceStatus("Helper відновлюється: "
                            + json.optString("error"));
                    if (musicPanel.isChecked()) musicPanel.setStatus("Helper недоступний");
                } else if ("guard_config".equals(kind)) {
                    if (json.optBoolean("active")) {
                        guardStatus.setText("Guard активний");
                    } else if (json.optBoolean("requested")) {
                        guardStatus.setText("Guard призупинено: "
                                + json.optString("reason"));
                    } else {
                        guardStatus.setText("Guard вимкнено");
                    }
                } else if ("driver_activation".equals(kind)) {
                    String direction = json.optString("direction");
                    guardStatus.setText("left".equals(direction)
                            ? "Лівий поворотник"
                            : "right".equals(direction)
                                    ? "Правий поворотник" : "Поворотник");
                } else if ("guard_armed".equals(kind)) {
                    guardStatus.setText("Поріг пройдено; очікування центру");
                } else if ("guard_completed".equals(kind)) {
                    guardStatus.setText("Маневр завершено");
                } else if ("guard_speed_deferred_resumed".equals(kind)) {
                    guardStatus.setText("Guard активний: швидкість нижче ліміту");
                } else if ("guard_speed_deferred_canceled".equals(kind)) {
                    guardStatus.setText("Очікування guard скасовано: "
                            + json.optString("reason"));
                } else if ("manual_cancel".equals(kind)) {
                    guardStatus.setText("Ручне вимкнення; корекцію скасовано");
                } else if ("correction_requested".equals(kind)) {
                    guardStatus.setText("Корекція: " + json.optString("direction"));
                } else if ("correction_confirmed".equals(kind)) {
                    guardStatus.setText("Корекцію підтверджено");
                } else if ("lifetime_counters".equals(kind)) {
                    lifetimeActivations = json.optLong(
                            "activation_count", lifetimeActivations);
                    lifetimeCorrections = json.optLong(
                            "correction_count", lifetimeCorrections);
                    updateCounters();
                } else if ("control_latch_reset_accepted".equals(kind)) {
                    guardStatus.setText("State поворотників скинуто: "
                            + json.optString("reason"));
                } else if ("control_latch_reset_failed".equals(kind)) {
                    guardStatus.setText("Скидання state не виконано: "
                            + json.optString("error"));
                } else if ("hazard_cleanup_pending".equals(kind)) {
                    guardStatus.setText("Аварійка: очікування скидання state");
                } else if ("hazard_cleanup_completed".equals(kind)) {
                    guardStatus.setText("Аварійку вимкнено; state скинуто в 0");
                } else if ("hazard_cleanup_failed".equals(kind)
                        || "hazard_cleanup_canceled".equals(kind)) {
                    guardStatus.setText(kind + ": " + json.optString("reason"));
                } else if ("manual_turn_state_requested".equals(kind)) {
                    guardStatus.setText("Команду прийнято; перевірка blink...");
                } else if ("manual_turn_state_confirmed".equals(kind)) {
                    manualTurnRequestPending = false;
                    if (json.optInt("payload") == 0
                            && !json.optBoolean("observable_transition")) {
                        guardStatus.setText(
                                "Payload 0 прийнято; очищення перевірити після restart");
                    } else {
                        guardStatus.setText("Стан підтверджено: "
                                + json.optString("action"));
                    }
                } else if ("manual_turn_state_rejected".equals(kind)
                        || "manual_turn_state_failed".equals(kind)) {
                    manualTurnRequestPending = false;
                    guardStatus.setText(kind + ": " + json.optString("reason"));
                } else if ("correction_failed".equals(kind)
                        || "guard_suppressed".equals(kind)
                        || "telemetry_error".equals(kind)) {
                    if ("telemetry_error".equals(kind)) telemetryReady = false;
                    if ("guard_suppressed".equals(kind)
                            && "speed_above_limit".equals(json.optString("reason"))) {
                        guardStatus.setText("Guard очікує швидкість нижче ліміту");
                    } else {
                        guardStatus.setText(kind + ": " + json.optString("reason"));
                    }
                }
            } catch (Throwable error) {
                Log.e(TAG, "Invalid helper JSON", error);
            }
            advanceStartupAuthorizationFlow();
            updateControls();
        });
    }

    private void handleActivityCameraShellDied(JSONObject event) {
        cameraShellAvailable = false;
        boolean transitionPending = cameraTransition.pending();
        boolean hadResumeIntent = hasAutoPreviewIntent();
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
        cameraPreview.removeCallbacks(finishCameraHandoff);
        debugPreview.removeCallbacks(finishCameraHandoff);
        cameraHandoffPending = false;
        pendingCameraViewpoint = -1;
        requestedOpen = false;
        stopCalibrationCopies(true);
        clearPreview("camera_shell_died");
        activePreview = null;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        activeDirectCameraIndex = -1;
        activeActivityCameraRequestId = 0;
        activeActivityConsumerGeneration = 0;
        activeActivityCameraOpened = false;
        activeActivityCameraFresh = false;
        activeActivityInputGenerations = new int[0];
        if (previewClaimed) CameraHelperService.cameraPreviewStopped(this);
        debugCameraStatus.setText("Camera helper відновлюється...");
        reverseCameraStatus.setText(reverseFailClosed
                ? "Camera closed; reopen the tab to retry"
                : "Camera helper відновлюється...");
        record("activity_camera_output_invalidated",
                "camera_shell_epoch", event.optLong("camera_shell_epoch", 0),
                "reopen_pending", cameraShellRecoveryPending);
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
        cameraShellAvailable = true;
        if ((!hasAutoPreviewIntent() && !hasScopedShellRetry())
                || (!isAutoPreviewTab(selectedTab) && !hasScopedShellRetry())) {
            cameraShellRecoveryPending = false;
            return;
        }
        resumeActivityCameraAfterShellRecovery(
                "camera_shell_attached", event.optLong("camera_shell_epoch", 0));
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
                && !activityColdResetRequired && !activityColdResetInFlight
                && !activityColdResetFailed
                && hasAutoPreviewIntent();
    }

    private boolean hasResumeAutoPreviewIntent(int eventRequestId) {
        return !shutdownRequested
                && activityResumed && hasAutoPreviewIntent()
                && !activityColdResetFailed
                && (eventRequestId <= 0
                        || automaticPreviewIntentRequestId > 0
                        && automaticPreviewIntentRequestId == eventRequestId);
    }

    private boolean canResumeSelectedPreview() {
        return canStartActivityCamera()
                && !shouldDeferActivityPreviewForReverse(activeReverseControllerRequestId)
                && !activityColdResetRequired && !activityColdResetInFlight
                && !activityColdResetFailed
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
        return requestedOpen && activeActivityCameraRequestId > 0
                && eventRequestId == activeActivityCameraRequestId
                || !requestedOpen && closingActivityCameraRequestId > 0
                && eventRequestId == closingActivityCameraRequestId;
    }

    private boolean shouldPreserveAutoPreviewAfterClose(String reason) {
        if (shutdownRequested) return false;
        if (CameraTransition.owns(reason)
                || "activity_stopped".equals(reason)
                || "replace_with_multi_preview".equals(reason)) return true;
        return !activityResumed && hasAutoPreviewIntent();
    }

    static boolean shouldWaitForStockShellClose(String view, String renderer) {
        return "reverse_preview_with_stock_base".equals(view)
                && !"stock_avm_shell".equals(renderer);
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

        CalibrationUiState(
                boolean showCorrected,
                boolean correctedEditable,
                boolean liveUsesCorrected) {
            this.showCorrected = showCorrected;
            this.correctedEditable = correctedEditable;
            this.liveUsesCorrected = liveUsesCorrected;
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

    static boolean isCurrentActivityCameraTerminalEvent(
            int activeRequestId, int eventRequestId) {
        return activeRequestId > 0
                ? eventRequestId == activeRequestId : eventRequestId <= 0;
    }

    static boolean shouldHandleReverseCameraStopped(
            boolean restoreIntent, int activeRequestId, int eventRequestId) {
        return restoreIntent && activeRequestId > 0 && eventRequestId == activeRequestId;
    }

    private void updateCounters() {
        activationCount.setText(lifetimeActivations + "\nУвімкнень");
        correctionCount.setText(lifetimeCorrections + "\nКорекцій");
    }

    private void clearPreview(String reason) {
        if (activePreview == cameraPreview) cancelProductionPreviewFirstFrameWait();
        if (activePreview == reverseCameraPreview && reverseCameraPreview != null) {
            reverseCameraPreview.clearFrames();
        }
        calibrationPreviewFreshness.clear();
        reversePreviewFreshness.clear();
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
        if (activePreviewCover != null) activePreviewCover.setVisibility(View.VISIBLE);
        record("preview_cleared", "reason", reason, "ok", true, "error", "");
    }

    private void armProductionPreviewFirstFrame(int requestId) {
        productionPreviewFreshness.arm(requestId, activeActivityInputGenerations);
        productionPreviewFrameRequest++;
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
        pendingReversePreviewRequestId = 0;
        pendingReversePreviewGenerations = null;
    }

    private void handleProductionPreviewFirstFrameTimeout() {
        if (!shouldRetryProductionPreviewFrame(
                selectedTab == TAB_CAMERAS, activePreview == cameraPreview,
                requestedOpen, productionPreviewAwaitingFrame)) return;
        record("camera_preview_first_frame_timeout",
                "request_id", productionPreviewFrameRequest,
                "camera_id", selectedCameraId,
                "retry", !productionPreviewRetryUsed);
        if (!productionPreviewRetryUsed) {
            productionPreviewRetryUsed = true;
            cameraStatus.setText("Кадр не отримано; повторне відкриття...");
            closeCameraForTransition("production_first_frame_timeout");
            return;
        }
        closeCamera("production_first_frame_timeout_final");
        cameraStatus.setText("Кадр камери не отримано");
    }

    static boolean shouldRetryProductionPreviewFrame(
            boolean camerasTab, boolean activePreview, boolean requestedOpen,
            boolean awaitingFrame) {
        return camerasTab && activePreview && requestedOpen && awaitingFrame;
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
        if (cover != null) cover.setVisibility(View.INVISIBLE);
        boolean crop = cropLeft || cropRight;
        target.setPivotX(cropRight ? target.getWidth() : 0.0f);
        target.setPivotY(0.0f);
        target.setScaleX(crop ? 2.2f : 1.0f);
        target.setScaleY(crop ? 2.0f : 1.0f);
    }

    private void applyDebugPreviewMode() {
        if (debugPreview == null) return;
        if (debugPreviewCover != null) debugPreviewCover.setVisibility(View.INVISIBLE);
        boolean raw = debugShowRawSwitch == null || debugShowRawSwitch.isChecked();
        float startX = raw ? 0.0f
                : StockAvmPreview.focusedTileStartX(activeCameraViewpoint);
        debugPreview.setPivotX(debugPreview.getWidth());
        debugPreview.setPivotY(0.0f);
        debugPreview.setScaleX(startX > 0.0f ? 1.0f / (1.0f - startX) : 1.0f);
        debugPreview.setScaleY(1.0f);
    }

    private void updateControls() {
        if (musicPanel != null) musicPanel.setControlEnabled(!shutdownRequested);
        if (settingsPanel != null) {
            settingsPanel.setControlsEnabled(
                    shutdownRequested,
                    !shutdownRequested && !cameraPermissionPending
                            && !backgroundStartSettingsActive
                            && !backgroundStartSettingsStartScheduled && !adbAuthPending,
                    !backgroundStartSettingsPending()
                            && shouldEnableManualAdbAuthorization(
                                    helper != null, adbAuthPending, adbAuthMode),
                    !requestedOpen && !cameraHandoffPending);
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
            cameraLeftPositionButton.setEnabled(selectedCameraEnabled);
        }
        if (cameraRightPositionButton != null) {
            cameraRightPositionButton.setEnabled(selectedCameraEnabled);
        }
        if (cameraTabletTargetButton != null) {
            cameraTabletTargetButton.setEnabled(selectedCameraEnabled);
            cameraClusterTargetButton.setEnabled(selectedCameraEnabled);
        }
        if (cameraPositionHandle != null) {
            cameraPositionHandle.setEnabled(selectedCameraEnabled);
            cameraPositionHandle.setAlpha(selectedCameraEnabled ? 1.0f : 0.45f);
        }
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
            if (button != null) button.setEnabled(directReady && !requestedOpen);
        }
        if (directCameraCloseButton != null) {
            directCameraCloseButton.setEnabled(
                    (requestedOpen || cameraHandoffPending)
                            && activePreview == directCameraPreview);
        }
        boolean calibrationReady = helper != null && calibrationSurfaceReady
                && permission && cameraDiscovered && !cameraHandoffPending
                && (!requestedOpen || activePreview == calibrationPreview);
        for (Button button : calibrationCameraButtons) {
            if (button != null) button.setEnabled(calibrationReady);
        }
        if (calibrationResetButton != null) {
            calibrationResetButton.setEnabled(
                    calibrationDewarpSwitch == null || !calibrationDewarpSwitch.isChecked());
            calibrationStopButton.setEnabled(
                    (requestedOpen || cameraHandoffPending)
                            && activePreview == calibrationPreview);
        }
        for (Button button : turnStateButtons) {
            if (button != null) button.setEnabled(
                    helper != null && telemetryReady && !manualTurnRequestPending
                            && !guardSwitch.isChecked());
        }
        maybeOpenPendingDiagnosticMode(debugStockReady);
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
        record("activity_camera_event_ignored", "kind", kind,
                "camera_owner", event.optString("camera_owner"),
                "request_id", event.optInt("request_id", 0),
                "consumer_generation", event.optInt("consumer_generation", 0),
                "active_request_id", activeActivityCameraRequestId,
                "active_consumer_generation", activeActivityConsumerGeneration,
                "source", event.optString("source"));
    }

    static boolean isCurrentActivityCameraEvent(
            boolean requestedOpen, int activeRequestId, int eventRequestId, String source) {
        return requestedOpen && "helper".equals(source)
                && activeRequestId > 0 && eventRequestId == activeRequestId;
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
            writeLine(json.toString());
        } catch (Throwable error) {
            Log.e(TAG, "Unable to record event " + kind, error);
        }
    }

    private void writeLine(String line) {
        synchronized (logLock) {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(logFile, true), StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write('\n');
            } catch (Throwable error) {
                Log.e(TAG, "Unable to append " + logFile, error);
            }
        }
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

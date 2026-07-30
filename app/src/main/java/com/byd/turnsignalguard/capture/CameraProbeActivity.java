package com.byd.turnsignalguard.capture;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
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
    private static final int DEFAULT_CAMERA_MIN_SPEED_KPH = 20;
    static final long ADB_AUTH_UI_SETTLE_MS = 600;
    static final long BACKGROUND_START_UI_SETTLE_MS = 600;
    private static final String ADB_WAITING_STATUS = "Очікування ADB/RSA...";
    private static final String PREF_BACKGROUND_START_SETTINGS_SHOWN =
            "background_start_settings_shown";
    private static final String BYD_START_SETTINGS_PACKAGE = "com.byd.appstartmanagement";
    private static final String BYD_START_SETTINGS_CLASS =
            "com.byd.appstartmanagement.frame.AppStartManagement";
    private static final long CAMERA_PREVIEW_HANDOFF_MS = 250;
    private static final int TAB_GUARD = 0;
    private static final int TAB_CAMERAS = 1;
    private static final int TAB_CAMERA_DEBUG = 2;
    private static final int TAB_DIRECT_CAMERA_DEBUG = 3;
    private static final int TAB_CAMERA_CALIBRATION = 4;
    private static final int TAB_REVERSE_CAMERAS = 5;
    private static final int TAB_MUSIC = 6;
    private static final long CALIBRATION_COPY_INTERVAL_MS = 100;
    private static final String EXTRA_DIAGNOSTIC_AVM_MODE_INDEX =
            "com.byd.turnsignalguard.capture.extra.AVM_MODE_INDEX";
    private static final String EXTRA_DIAGNOSTIC_AVM_CLOSE =
            "com.byd.turnsignalguard.capture.extra.AVM_CLOSE";

    private final ExecutorService ipcExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService updateExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AppUpdateManager updateManager = new AppUpdateManager();
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
    private Switch autoStartSwitch;
    private Switch guardSwitch;
    private Switch cameraSwitch;
    private Switch musicSwitch;
    private EditText outwardInput;
    private EditText centerInput;
    private EditText correctionDelayInput;
    private EditText maxSpeedInput;
    private EditText cameraMinSpeedInput;
    private Spinner cameraWarningModeInput;
    private SeekBar cameraScaleInput;
    private TextView cameraScaleValue;
    private FrameLayout cameraPositionWidget;
    private FrameLayout cameraPositionHost;
    private TextView cameraPositionHandle;
    private Button cameraLeftPositionButton;
    private Button cameraRightPositionButton;
    private Button cameraTabletTargetButton;
    private Button cameraClusterTargetButton;
    private TextView guardStatus;
    private TextView cameraStatus;
    private TextView debugCameraStatus;
    private TextView directCameraStatus;
    private TextView calibrationStatus;
    private TextView calibrationCropValues;
    private TextView calibrationResultTitle;
    private TextView reverseCameraStatus;
    private TextView musicStatus;
    private TextView musicJournalText;
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
    private SurfaceView calibrationPreview;
    private View calibrationPreviewCover;
    private CameraCropOverlayView calibrationCropOverlay;
    private ImageView calibrationCropPreview;
    private FrameLayout calibrationSourceHost;
    private View calibrationSourceFrame;
    private FrameLayout calibrationResultHost;
    private View calibrationResultFrame;
    private ReverseCameraCompositionView reverseCameraPreview;
    private ReverseCameraEditorView reverseCameraEditor;
    private ReverseCameraLayout reverseCameraLayout;
    private Switch reverseCameraSwitch;
    private final EditText[] reverseCropInputs = new EditText[4];
    private final Button[] reversePaneButtons = new Button[3];
    private Button reverseLowerButton;
    private Button reverseRaiseButton;
    private View activePreview;
    private View activePreviewCover;
    private Button rawButton;
    private Button debugHorizontalButton;
    private Button debugVerticalButton;
    private Switch debugShowRawSwitch;
    private Button closeButton;
    private Button adbRetryButton;
    private Button backgroundStartSettingsButton;
    private Button updateButton;
    private Button shutdownButton;
    private Button clearLogsButton;
    private Button guardTabButton;
    private Button calibrationTabButton;
    private Button cameraTabButton;
    private Button cameraDebugTabButton;
    private Button directCameraDebugTabButton;
    private Button reverseCameraTabButton;
    private Button musicTabButton;
    private Button directCameraCloseButton;
    private Button calibrationLeftButton;
    private Button calibrationRightButton;
    private Button calibrationResetButton;
    private Button calibrationStopButton;
    private View guardPage;
    private View calibrationPage;
    private View cameraPage;
    private View cameraDebugPage;
    private View directCameraDebugPage;
    private View reverseCameraPage;
    private View musicPage;
    private final ArrayDeque<String> musicJournal = new ArrayDeque<>();
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
    private boolean editingRightCameraPosition;
    private boolean debugHorizontal = true;
    private boolean calibrationRightCamera;
    private boolean pendingDirectCalibration;
    private boolean calibrationCopyPending;
    private float cameraLeftX;
    private float cameraLeftY;
    private float cameraRightX;
    private float cameraRightY;
    private int cameraLeftScale;
    private int cameraRightScale;
    private int cameraLeftTarget;
    private int cameraRightTarget;
    private float dragStartRawX;
    private float dragStartRawY;
    private float dragStartX;
    private float dragStartY;
    private int pendingDiagnosticAvmModeIndex = -1;
    private int pendingCameraViewpoint = -1;
    private int pendingDirectCameraIndex = -1;
    private String pendingDirectCameraTag;
    private int activeCameraViewpoint = -1;
    private boolean pendingCameraDebug;
    private boolean cameraHandoffPending;
    private Bitmap calibrationCaptureBitmap;
    private Bitmap calibrationResultBitmap;
    private int lastCalibrationCopyResult = PixelCopy.SUCCESS;
    private int selectedTab = -1;
    private int reversePreviewRequestSequence;
    private AlertDialog updateDialog;
    private AlertDialog updateProgressDialog;
    private final Runnable finishCameraHandoff = this::openPendingStockAvm;
    private final Runnable finishDirectCameraHandoff = this::openPendingDirectCamera;
    private final Runnable copyCalibrationFrame = this::copyCalibrationFrame;
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
            helper = null;
            adbAuthPending = false;
            adbAuthMode = null;
            adbAuthorizationRequested = false;
            cancelPendingForegroundAdbAuthorization();
            telemetryReady = false;
            manualTurnRequestPending = false;
            cameraDiscovered = false;
            requestedOpen = false;
            guardStatus.setText("Службу зупинено");
            cameraStatus.setText("Службу зупинено");
            debugCameraStatus.setText("Службу зупинено");
            directCameraStatus.setText("Службу зупинено");
            calibrationStatus.setText("Службу зупинено");
            reverseCameraStatus.setText("Службу зупинено");
            musicStatus.setText("Helper недоступний");
            stopCalibrationCopies(true);
            clearPreview("helper_service_disconnected");
            activePreview = null;
            activePreviewCover = null;
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
        CameraHelperService.activityOpened(this);
        if (!helperBound) startAndBindHelperService();
        if (helper != null) ipcExecutor.execute(this::registerCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
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
        activityResumed = false;
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
            maybeOpenCalibrationCamera();
            maybeOpenReversePreview();
        }
    }

    @Override
    protected void onStop() {
        cancelPendingBackgroundStartSettings();
        cancelPendingForegroundAdbAuthorization();
        if (!shutdownRequested) {
            if (cameraMinSpeedInput != null) saveCameraMinSpeed();
            closeCamera("activity_stopped");
            detachHelperCallback();
            CameraHelperService.activityClosed(this);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        mainHandler.removeCallbacks(runStartupUpdateCheck);
        stopCalibrationCopies(true);
        if (helperBound) {
            unbindService(helperConnection);
            helperBound = false;
        }
        ipcExecutor.shutdown();
        updateExecutor.shutdownNow();
        if (updateDialog != null) updateDialog.dismiss();
        if (updateProgressDialog != null) updateProgressDialog.dismiss();
        super.onDestroy();
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

    private void runManualUpdateCheck() {
        runUpdateCheck(true);
    }

    private void runUpdateCheck(boolean force) {
        if (updateCheckInFlight || activityDestroyed) return;
        updateCheckInFlight = true;
        if (updateButton != null) {
            updateButton.setEnabled(false);
            updateButton.setText("Перевірка...");
        }
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
        if (updateButton == null) return;
        updateButton.setText("Оновлення");
        updateButton.setEnabled(!activityDestroyed && updateProgressDialog == null);
    }

    private void showCachedUpdateIfAvailable() {
        AppUpdateManager.UpdateInfo available = AppUpdateManager.cachedAvailable();
        if (!activityResumed || activityDestroyed || available == null
                || updateDialog != null || updateProgressDialog != null) return;
        String message = "Встановлена версія: " + BuildConfig.VERSION_NAME
                + "\nДоступна версія: " + available.version;
        String notes = available.releaseNotes == null ? "" : available.releaseNotes.trim();
        if (!notes.isEmpty()) message += "\n\n" + notes;
        updateDialog = new AlertDialog.Builder(this)
                .setTitle("Доступне оновлення")
                .setMessage(message)
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
        telemetryReady = false;
        cameraDiscovered = false;
        guardStatus.setText("Підключення телеметрії...");
        cameraStatus.setText("Пошук AVM camera...");
        debugCameraStatus.setText("Пошук AVM camera...");
        directCameraStatus.setText("Пошук direct camera...");
        calibrationStatus.setText("Пошук direct camera...");
        reverseCameraStatus.setText("Пошук AVM camera...");
        record("helper_service_connected");
        updateControls();
        ipcExecutor.execute(this::registerCallback);
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
        cameraSurfaceReady = surface.isValid();
        if (cameraPreviewCover != null) cameraPreviewCover.setVisibility(View.VISIBLE);
        record("surface_created", "target", "production", "valid", cameraSurfaceReady,
                "width", width, "height", height,
                "buffer_width", BlindSpotCameraView.BUFFER_WIDTH,
                "buffer_height", BlindSpotCameraView.BUFFER_HEIGHT);
        updateControls();
    }

    @Override
    public void onCameraSurfaceSizeChanged(
            BlindSpotCameraView view, Surface surface, int width, int height) {
        cameraSurfaceReady = surface.isValid();
        record("surface_changed", "target", "production",
                "width", width, "height", height,
                "buffer_width", BlindSpotCameraView.BUFFER_WIDTH,
                "buffer_height", BlindSpotCameraView.BUFFER_HEIGHT);
        updateControls();
    }

    @Override
    public void onCameraSurfaceDestroyed(BlindSpotCameraView view) {
        cameraSurfaceReady = false;
        if (activePreview == cameraPreview) closeCamera("surface_destroyed");
        record("surface_destroyed", "target", "production");
        updateControls();
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
        reverseCameraStatus.setText("Live preview");
        record("reverse_preview_frames", "request_id", requestId,
                "generations", java.util.Arrays.toString(generations));
    }

    @Override
    public void onReverseSurfaceLost(int cameraIndex, int generation) {
        reverseCameraSurfacesReady = reverseCameraPreview != null
                && reverseCameraPreview.surfacesReady();
        record("reverse_preview_surfaces", "state", "destroyed",
                "camera_index", cameraIndex, "generation", generation);
        if (activePreview == reverseCameraPreview) closeCamera("reverse_surface_destroyed");
        updateControls();
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
        reverseCameraTabButton = button("Камери заднього ходу");
        cameraDebugTabButton = button("Режими AVM");
        directCameraDebugTabButton = button("Direct camera");
        musicTabButton = button("Музика");
        guardTabButton.setTextSize(14);
        calibrationTabButton.setTextSize(14);
        cameraTabButton.setTextSize(14);
        reverseCameraTabButton.setTextSize(14);
        cameraDebugTabButton.setTextSize(14);
        directCameraDebugTabButton.setTextSize(14);
        musicTabButton.setTextSize(14);
        tabs.addView(guardTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(cameraTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(reverseCameraTabButton,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(calibrationTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(musicTabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
        tabs.addView(directCameraDebugTabButton,
                new LinearLayout.LayoutParams(0, dp(48), 1));
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
        musicPage = buildMusicPanel();
        cameraDebugPage = buildCameraDebugPanel();
        directCameraDebugPage = buildDirectCameraDebugPanel();
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
        pages.addView(cameraDebugPage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        pages.addView(directCameraDebugPage, new FrameLayout.LayoutParams(
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
        directCameraDebugTabButton.setOnClickListener(
                view -> selectTab(TAB_DIRECT_CAMERA_DEBUG));
        setContentView(root);
        int initialTab = preferences.contains("selected_tab")
                ? preferences.getInt("selected_tab", TAB_GUARD)
                : preferences.getBoolean("camera_tab_selected", false)
                        ? TAB_CAMERAS : TAB_GUARD;
        selectTab(initialTab);
    }

    private void selectTab(int tab) {
        if (!isValidTab(tab)) tab = TAB_GUARD;
        if (selectedTab != -1 && selectedTab != tab
                && (requestedOpen || cameraHandoffPending)) {
            closeCamera("camera_tab_changed");
        }
        selectedTab = tab;
        guardPage.setVisibility(tab == TAB_GUARD ? View.VISIBLE : View.GONE);
        calibrationPage.setVisibility(
                tab == TAB_CAMERA_CALIBRATION ? View.VISIBLE : View.GONE);
        cameraPage.setVisibility(tab == TAB_CAMERAS ? View.VISIBLE : View.GONE);
        reverseCameraPage.setVisibility(
                tab == TAB_REVERSE_CAMERAS ? View.VISIBLE : View.GONE);
        musicPage.setVisibility(tab == TAB_MUSIC ? View.VISIBLE : View.GONE);
        cameraDebugPage.setVisibility(tab == TAB_CAMERA_DEBUG ? View.VISIBLE : View.GONE);
        directCameraDebugPage.setVisibility(
                tab == TAB_DIRECT_CAMERA_DEBUG ? View.VISIBLE : View.GONE);
        guardTabButton.setBackgroundColor(tabColor(tab == TAB_GUARD));
        calibrationTabButton.setBackgroundColor(
                tabColor(tab == TAB_CAMERA_CALIBRATION));
        cameraTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERAS));
        reverseCameraTabButton.setBackgroundColor(
                tabColor(tab == TAB_REVERSE_CAMERAS));
        musicTabButton.setBackgroundColor(tabColor(tab == TAB_MUSIC));
        cameraDebugTabButton.setBackgroundColor(tabColor(tab == TAB_CAMERA_DEBUG));
        directCameraDebugTabButton.setBackgroundColor(
                tabColor(tab == TAB_DIRECT_CAMERA_DEBUG));
        preferences.edit().putInt("selected_tab", tab).apply();
        if (tab == TAB_CAMERA_CALIBRATION) maybeOpenCalibrationCamera();
        if (tab == TAB_CAMERAS) {
            updateCameraPositionHandle();
            updateProductionPreviewSize();
        }
        if (tab == TAB_REVERSE_CAMERAS) maybeOpenReversePreview();
    }

    private static boolean isValidTab(int tab) {
        return tab == TAB_GUARD || tab == TAB_CAMERAS || tab == TAB_CAMERA_DEBUG
                || tab == TAB_DIRECT_CAMERA_DEBUG || tab == TAB_CAMERA_CALIBRATION
                || tab == TAB_REVERSE_CAMERAS || tab == TAB_MUSIC;
    }

    private static int tabColor(boolean selected) {
        return Color.rgb(selected ? 82 : 42, selected ? 82 : 42, selected ? 82 : 42);
    }

    private View buildGuardPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, 0, dp(18), 0);

        autoStartSwitch = new Switch(this);
        autoStartSwitch.setText("Авто-запуск");
        autoStartSwitch.setTextColor(Color.WHITE);
        autoStartSwitch.setTextSize(20);
        autoStartSwitch.setChecked(GuardRecovery.isAutoStartEnabled(this));
        panel.addView(autoStartSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        backgroundStartSettingsButton = button("Налаштувати фоновий запуск DiLink");
        backgroundStartSettingsButton.setOnClickListener(
                view -> openBackgroundStartSettings("manual"));
        panel.addView(backgroundStartSettingsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        LinearLayout shutdownRow = new LinearLayout(this);
        shutdownRow.setOrientation(LinearLayout.HORIZONTAL);
        shutdownRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView shutdownLabel = label("Shutdown");
        shutdownRow.addView(shutdownLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        shutdownButton = button("Вимкнути");
        updateButton = button("Оновлення");
        shutdownRow.addView(updateButton, new LinearLayout.LayoutParams(dp(170), dp(50)));
        shutdownRow.addView(shutdownButton, new LinearLayout.LayoutParams(dp(150), dp(50)));
        panel.addView(shutdownRow);

        clearLogsButton = button("Очистити старі логи");
        clearLogsButton.setOnClickListener(view -> clearCaptureLogs());
        panel.addView(clearLogsButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

        View runtimeDivider = new View(this);
        runtimeDivider.setBackgroundColor(Color.rgb(70, 70, 70));
        LinearLayout.LayoutParams runtimeDividerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        runtimeDividerParams.setMargins(0, dp(10), 0, dp(10));
        panel.addView(runtimeDivider, runtimeDividerParams);

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

        adbRetryButton = button("Повторити ADB авторизацію");
        adbRetryButton.setOnClickListener(view -> requestAdbAuthorization(
                "adb_authorization_retry_ui", "retry_adb_auth", false));
        panel.addView(adbRetryButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)));

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
        autoStartSwitch.setOnCheckedChangeListener((button, checked) -> {
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
        });
        updateButton.setOnClickListener(view -> runManualUpdateCheck());
        shutdownButton.setOnClickListener(view -> requestAppShutdown());
        return panel;
    }

    private void requestAppShutdown() {
        if (shutdownRequested) return;
        shutdownRequested = true;
        record("user_shutdown_requested", "auto_start",
                GuardRecovery.isAutoStartEnabled(this), "guard_enabled",
                guardSwitch != null && guardSwitch.isChecked());
        updateControls();
        CameraHelperService.requestShutdown(this);
        finishAndRemoveTask();
    }

    private View buildMusicPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(0, dp(12), 0, 0);

        musicSwitch = new Switch(this);
        musicSwitch.setText("Синхронізація підсвітки з музикою");
        musicSwitch.setTextColor(Color.WHITE);
        musicSwitch.setTextSize(20);
        musicSwitch.setChecked(preferences.getBoolean("music_visualizer_enabled", false));
        panel.addView(musicSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        musicStatus = statusText(musicSwitch.isChecked()
                ? "Очікування helper..." : "Вимкнено");
        panel.addView(musicStatus);

        TextView journalTitle = label("Журнал");
        journalTitle.setPadding(0, dp(24), 0, dp(8));
        panel.addView(journalTitle);

        ScrollView journalScroll = new ScrollView(this);
        musicJournalText = new TextView(this);
        musicJournalText.setTextColor(Color.LTGRAY);
        musicJournalText.setTextSize(15);
        musicJournalText.setPadding(dp(12), dp(12), dp(12), dp(12));
        musicJournalText.setText("Подій ще немає");
        journalScroll.addView(musicJournalText, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        panel.addView(journalScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        musicSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean("music_visualizer_enabled", checked).apply();
            musicStatus.setText(checked ? "Очікування helper..." : "Вимкнено");
            record("music_toggle", "enabled", checked);
            CameraHelperService.musicSettingsChanged(this);
            updateControls();
        });
        return panel;
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
        String[] paneNames = {"Rear", "Rear left", "Rear right"};
        for (int i = 0; i < reversePaneButtons.length; i++) {
            int cameraIndex = i + 1;
            reversePaneButtons[i] = button(paneNames[i]);
            reversePaneButtons[i].setOnClickListener(
                    view -> reverseCameraEditor.selectCamera(cameraIndex));
            selectors.addView(reversePaneButtons[i],
                    new LinearLayout.LayoutParams(0, dp(46), 1));
        }
        editorPane.addView(selectors);

        LinearLayout crop = new LinearLayout(this);
        crop.setGravity(Gravity.CENTER_VERTICAL);
        String[] cropNames = {"L", "T", "R", "B"};
        for (int i = 0; i < reverseCropInputs.length; i++) {
            TextView cropLabel = label(cropNames[i]);
            cropLabel.setGravity(Gravity.CENTER);
            crop.addView(cropLabel, new LinearLayout.LayoutParams(dp(28), dp(48)));
            reverseCropInputs[i] = numberInput(0);
            reverseCropInputs[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            crop.addView(reverseCropInputs[i], new LinearLayout.LayoutParams(0, dp(48), 1));
        }
        Button applyCrop = button("Apply");
        crop.addView(applyCrop, new LinearLayout.LayoutParams(dp(100), dp(48)));
        editorPane.addView(crop);

        LinearLayout zRow = new LinearLayout(this);
        reverseLowerButton = button("Нижче");
        reverseRaiseButton = button("Вище");
        zRow.addView(reverseLowerButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        zRow.addView(reverseRaiseButton, new LinearLayout.LayoutParams(0, dp(46), 1));
        editorPane.addView(zRow);

        reverseCameraStatus = statusText("Очікування AVM camera...");
        previewPane.addView(reverseCameraStatus);
        reverseCameraPreview = new ReverseCameraCompositionView(this);
        reverseCameraPreview.setCallback(this);
        reverseCameraPreview.applyLayout(reverseCameraLayout);
        previewPane.addView(reverseCanvasHost(reverseCameraPreview),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        content.addView(editorPane, new LinearLayout.LayoutParams(0,
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
            reverseCameraLayout = ReverseCameraLayout.defaults();
            ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            reverseCameraEditor.selectCamera(ReverseCameraLayout.REAR_CAMERA_INDEX);
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_layout_reset");
        });
        applyCrop.setOnClickListener(view -> applyReverseCrop());
        reverseLowerButton.setOnClickListener(view -> changeReverseZ(false));
        reverseRaiseButton.setOnClickListener(view -> changeReverseZ(true));
        updateReversePaneControls(ReverseCameraLayout.REAR_CAMERA_INDEX);
        return root;
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

    private void updateReversePaneControls(int cameraIndex) {
        if (reverseCameraLayout == null) return;
        ReverseCameraLayout.Pane pane = reverseCameraLayout.pane(cameraIndex);
        for (int i = 0; i < reversePaneButtons.length; i++) {
            reversePaneButtons[i].setBackgroundColor(tabColor(i + 1 == cameraIndex));
        }
        float[] values = {pane.sourceCrop.left, pane.sourceCrop.top,
                pane.sourceCrop.right(), pane.sourceCrop.bottom()};
        for (int i = 0; i < reverseCropInputs.length; i++) {
            reverseCropInputs[i].setText(String.valueOf(Math.round(values[i] * 100.0f)));
        }
        reverseLowerButton.setEnabled(pane.zOrder > 0);
        reverseRaiseButton.setEnabled(pane.zOrder < 2);
    }

    private void applyReverseCrop() {
        try {
            float left = percent(reverseCropInputs[0]);
            float top = percent(reverseCropInputs[1]);
            float right = percent(reverseCropInputs[2]);
            float bottom = percent(reverseCropInputs[3]);
            if (right <= left || bottom <= top) throw new IllegalArgumentException();
            int cameraIndex = reverseCameraEditor.selectedCamera();
            ReverseCameraLayout.Pane pane = reverseCameraLayout.pane(cameraIndex);
            ReverseCameraLayout.Rect crop = ReverseCameraLayout.sourceCrop(
                    left, top, right - left, bottom - top);
            reverseCameraLayout = ReverseCameraLayout.withPane(
                    reverseCameraLayout, cameraIndex, pane.destination, crop);
            ReverseCameraController.saveLayout(preferences, reverseCameraLayout);
            reverseCameraEditor.setLayoutModel(reverseCameraLayout);
            reverseCameraPreview.applyLayout(reverseCameraLayout);
            CameraHelperService.reverseCameraSettingsChanged(this);
            record("reverse_crop_applied", "camera_index", cameraIndex,
                    "left", left, "top", top, "right", right, "bottom", bottom);
        } catch (Throwable error) {
            Toast.makeText(this, "Crop: значення 0..100, Right>Left, Bottom>Top",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void changeReverseZ(boolean raise) {
        int cameraIndex = reverseCameraEditor.selectedCamera();
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

    private static float percent(EditText input) {
        int value = Integer.parseInt(input.getText().toString());
        if (value < 0 || value > 100) throw new IllegalArgumentException();
        return value / 100.0f;
    }

    private View buildCameraPanel() {
        BlindSpotOverlayController.migrateOverlayPreferences(preferences);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setPadding(0, dp(8), 0, 0);

        LinearLayout settingsPane = new LinearLayout(this);
        settingsPane.setOrientation(LinearLayout.VERTICAL);
        settingsPane.setPadding(0, 0, dp(12), 0);

        cameraSwitch = new Switch(this);
        cameraSwitch.setText("Камери за поворотником");
        cameraSwitch.setTextColor(Color.WHITE);
        cameraSwitch.setTextSize(20);
        cameraSwitch.setChecked(preferences.getBoolean("camera_enabled", false));
        settingsPane.addView(cameraSwitch, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)));

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView minSpeedLabel = label("Мін. швидкість");
        minSpeedLabel.setTextSize(15);
        minSpeedLabel.setGravity(Gravity.CENTER_VERTICAL);
        speedRow.addView(minSpeedLabel, new LinearLayout.LayoutParams(0, dp(54), 1));
        cameraMinSpeedInput = numberInput(preferences.getInt(
                "camera_min_speed_kph", DEFAULT_CAMERA_MIN_SPEED_KPH));
        cameraMinSpeedInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        speedRow.addView(cameraMinSpeedInput, new LinearLayout.LayoutParams(dp(96), dp(50)));
        TextView minSpeedUnit = label("км/год");
        minSpeedUnit.setTextSize(15);
        minSpeedUnit.setGravity(Gravity.CENTER);
        speedRow.addView(minSpeedUnit, new LinearLayout.LayoutParams(dp(78), dp(54)));
        settingsPane.addView(speedRow);

        LinearLayout warningRow = new LinearLayout(this);
        warningRow.setOrientation(LinearLayout.HORIZONTAL);
        warningRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView warningLabel = label(
                "Червона підсвітка, якщо в сліпій зоні є об'єкт");
        warningLabel.setTextSize(15);
        warningLabel.setGravity(Gravity.CENTER_VERTICAL);
        warningRow.addView(warningLabel, new LinearLayout.LayoutParams(0, dp(66), 1));
        cameraWarningModeInput = new Spinner(this);
        String[] warningModes = {"Вимкнена", "Постійно", "Пульсація"};
        ArrayAdapter<String> warningAdapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, warningModes);
        warningAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraWarningModeInput.setAdapter(warningAdapter);
        int initialWarningMode = BlindSpotOverlayController.readWarningMode(preferences);
        cameraWarningModeInput.setSelection(initialWarningMode, false);
        warningRow.addView(cameraWarningModeInput,
                new LinearLayout.LayoutParams(dp(176), dp(58)));
        settingsPane.addView(warningRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(70)));

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

        cameraLeftScale = BlindSpotOverlayController.readScale(preferences, false);
        cameraRightScale = BlindSpotOverlayController.readScale(preferences, true);
        cameraLeftTarget = BlindSpotOverlayController.readTarget(preferences, false);
        cameraRightTarget = BlindSpotOverlayController.readTarget(preferences, true);
        LinearLayout scaleRow = new LinearLayout(this);
        scaleRow.setOrientation(LinearLayout.HORIZONTAL);
        scaleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView scaleLabel = label("Розмір");
        scaleLabel.setTextSize(15);
        scaleRow.addView(scaleLabel, new LinearLayout.LayoutParams(dp(78), dp(54)));
        cameraScaleInput = new SeekBar(this);
        cameraScaleInput.setMax(BlindSpotOverlayController.MAX_SCALE_PERCENT
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        cameraScaleInput.setProgress(cameraLeftScale
                - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        scaleRow.addView(cameraScaleInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        cameraScaleValue = label(cameraLeftScale + "%");
        cameraScaleValue.setGravity(Gravity.CENTER);
        scaleRow.addView(cameraScaleValue,
                new LinearLayout.LayoutParams(dp(64), dp(54)));
        settingsPane.addView(scaleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60)));

        cameraLeftX = BlindSpotOverlayController.readPosition(preferences, false, false);
        cameraLeftY = BlindSpotOverlayController.readPosition(preferences, false, true);
        cameraRightX = BlindSpotOverlayController.readPosition(preferences, true, false);
        cameraRightY = BlindSpotOverlayController.readPosition(preferences, true, true);
        cameraPositionWidget = new FrameLayout(this);
        cameraPositionWidget.setClipChildren(true);
        cameraPositionWidget.setBackgroundColor(Color.rgb(38, 38, 38));
        cameraPositionHandle = new TextView(this);
        cameraPositionHandle.setText("L");
        cameraPositionHandle.setTextColor(Color.WHITE);
        cameraPositionHandle.setTextSize(22);
        cameraPositionHandle.setGravity(Gravity.CENTER);
        cameraPositionHandle.setBackgroundColor(Color.rgb(35, 120, 70));
        cameraPositionWidget.addView(cameraPositionHandle,
                new FrameLayout.LayoutParams(dp(80), dp(60)));

        cameraPositionHost = new FrameLayout(this);
        cameraPositionHost.setPadding(0, dp(8), 0, dp(8));
        cameraPositionHost.addView(cameraPositionWidget,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        cameraPositionHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> {
            updateCameraPositionCanvasSize();
        });
        settingsPane.addView(cameraPositionHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        cameraPositionWidget.post(this::updateCameraPositionHandle);

        LinearLayout previewPane = new LinearLayout(this);
        previewPane.setOrientation(LinearLayout.VERTICAL);
        previewPane.setPadding(dp(12), 0, 0, 0);
        cameraStatus = statusText("Запуск внутрішньої служби...");
        previewPane.addView(cameraStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(36)));

        previewPane.addView(buildProductionPreview(), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout referenceControls = new LinearLayout(this);
        referenceControls.setOrientation(LinearLayout.HORIZONTAL);
        int[] viewpoints = {
                StockAvmPreview.VIEW_BLIND_SPOT_LEFT,
                StockAvmPreview.VIEW_BLIND_SPOT_RIGHT
        };
        String[] stockLabels = {"Rear left", "Rear right"};
        for (int i = 0; i < viewpoints.length; i++) {
            final int viewpoint = viewpoints[i];
            Button button = button(stockLabels[i]);
            button.setOnClickListener(view -> openStockAvm(viewpoint, false));
            stockAvmButtons[i] = button;
            referenceControls.addView(button, new LinearLayout.LayoutParams(0, dp(52), 1));
        }
        previewPane.addView(referenceControls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        panel.addView(settingsPane, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(70, 70, 70));
        panel.addView(divider, new LinearLayout.LayoutParams(dp(1),
                LinearLayout.LayoutParams.MATCH_PARENT));
        panel.addView(previewPane, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        cameraSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(BlindSpotOverlayController.PREF_ENABLED, checked).apply();
            record("camera_toggle", "enabled", checked);
            if (!checked) closeCamera("camera_disabled");
            CameraHelperService.cameraSettingsChanged(this);
            updateControls();
        });
        cameraMinSpeedInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus) saveCameraMinSpeed();
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
                if (editingRightCameraPosition) cameraRightScale = scale;
                else cameraLeftScale = scale;
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
        cameraLeftPositionButton.setOnClickListener(view -> selectCameraPosition(false));
        cameraRightPositionButton.setOnClickListener(view -> selectCameraPosition(true));
        cameraTabletTargetButton.setOnClickListener(
                view -> selectCameraTarget(CameraDisplayTarget.TABLET));
        cameraClusterTargetButton.setOnClickListener(
                view -> selectCameraTarget(CameraDisplayTarget.CLUSTER));
        cameraPositionHandle.setOnTouchListener((view, event) -> {
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
        selectCameraPosition(false);
        return panel;
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
        calibrationLeftButton = button("Ліва камера");
        calibrationRightButton = button("Права камера");
        calibrationResetButton = button("Скинути crop");
        calibrationStopButton = button("Stop");
        controls.addView(calibrationLeftButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(calibrationRightButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(calibrationResetButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        controls.addView(calibrationStopButton, new LinearLayout.LayoutParams(0, dp(50), 1));
        panel.addView(controls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

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
        panel.addView(aspectControls, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        calibrationCropValues = statusText("");
        calibrationCropValues.setGravity(Gravity.CENTER);
        panel.addView(calibrationCropValues, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));

        LinearLayout previews = new LinearLayout(this);
        previews.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout sourcePane = new LinearLayout(this);
        sourcePane.setOrientation(LinearLayout.VERTICAL);
        TextView sourceTitle = label("Повний кадр");
        sourceTitle.setGravity(Gravity.CENTER);
        sourcePane.addView(sourceTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
        calibrationSourceHost = new FrameLayout(this);
        calibrationSourceHost.setBackgroundColor(Color.BLACK);
        FrameLayout sourceFrame = new FrameLayout(this);
        sourceFrame.setBackgroundColor(Color.BLACK);
        sourceFrame.setClipChildren(true);
        calibrationSourceFrame = sourceFrame;

        calibrationPreview = new SurfaceView(this);
        calibrationPreview.setAlpha(1.0f);
        calibrationPreview.getHolder().setFormat(android.graphics.PixelFormat.OPAQUE);
        calibrationPreview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                calibrationSurfaceReady = holder.getSurface().isValid();
                if (calibrationPreviewCover != null) {
                    calibrationPreviewCover.setVisibility(View.VISIBLE);
                }
                record("surface_created", "target", "camera_calibration",
                        "valid", calibrationSurfaceReady);
                updateControls();
                maybeOpenCalibrationCamera();
            }

            @Override
            public void surfaceChanged(
                    SurfaceHolder holder, int format, int width, int height) {
                calibrationSurfaceReady = holder.getSurface().isValid();
                record("surface_changed", "target", "camera_calibration",
                        "width", width, "height", height, "format", format);
                updateControls();
                maybeOpenCalibrationCamera();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                calibrationSurfaceReady = false;
                stopCalibrationCopies(true);
                if (activePreview == calibrationPreview) {
                    closeCamera("calibration_surface_destroyed");
                }
                record("surface_destroyed", "target", "camera_calibration");
                updateControls();
            }
        });
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
        previews.addView(sourcePane, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

        LinearLayout resultPane = new LinearLayout(this);
        resultPane.setOrientation(LinearLayout.VERTICAL);
        calibrationResultTitle = label("Фактичний crop 4:3");
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
            updateCalibrationUi(crop);
            renderCalibrationCrop();
            if (finished) saveCalibrationCrop(crop);
        });
        calibrationLeftButton.setOnClickListener(
                view -> selectCalibrationCamera(false, true));
        calibrationRightButton.setOnClickListener(
                view -> selectCalibrationCamera(true, true));
        calibrationResetButton.setOnClickListener(view -> resetCalibrationCrop());
        calibrationStopButton.setOnClickListener(
                view -> closeCamera("calibration_user_stop"));
        selectCalibrationCamera(false, false);
        return panel;
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
        controls.addView(debugHorizontalButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        controls.addView(debugVerticalButton, new LinearLayout.LayoutParams(dp(140), dp(52)));
        controls.addView(debugShowRawSwitch, new LinearLayout.LayoutParams(dp(180), dp(52)));

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
            mode.setOnClickListener(view -> openStockAvm(viewpoint, true));
            horizontalLayoutButtons[i] = mode;
            modes.addView(mode, new LinearLayout.LayoutParams(dp(300), dp(54)));
        }
        closeButton = button("Close");
        closeButton.setOnClickListener(view -> closeCamera("user_close"));
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
        directCameraCloseButton.setOnClickListener(view -> closeCamera("direct_user_stop"));
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

    private void selectCalibrationCamera(boolean rightCamera, boolean open) {
        if (open && requestedOpen && activePreview != calibrationPreview) return;
        if (open && requestedOpen && activePreview == calibrationPreview
                && calibrationRightCamera == rightCamera) return;
        boolean switchingOpenCamera = open && requestedOpen
                && activePreview == calibrationPreview
                && calibrationRightCamera != rightCamera;
        if (switchingOpenCamera) closeCamera("calibration_camera_changed");

        calibrationRightCamera = rightCamera;
        DirectCameraCrop crop = loadCalibrationCrop(rightCamera);
        calibrationCropOverlay.setCrop(crop);
        updateCalibrationUi(crop);
        renderCalibrationCrop();
        calibrationLeftButton.setBackgroundColor(tabColor(!rightCamera));
        calibrationRightButton.setBackgroundColor(tabColor(rightCamera));
        record("calibration_camera_selected", "side", rightCamera ? "right" : "left",
                "preview_index", rightCamera ? 3 : 2);
        if (open) {
            calibrationPreview.postDelayed(
                    () -> openCalibrationCamera(rightCamera ? 3 : 2),
                    switchingOpenCamera ? CAMERA_PREVIEW_HANDOFF_MS : 0);
        }
    }

    private DirectCameraCrop loadCalibrationCrop(boolean rightCamera) {
        DirectCameraCrop fallback = DirectCameraCrop.defaultFor(rightCamera);
        return DirectCameraCrop.of(
                preferences.getFloat(rightCamera
                        ? DirectCameraCrop.PREF_RIGHT_X : DirectCameraCrop.PREF_LEFT_X,
                        fallback.left),
                preferences.getFloat(rightCamera
                        ? DirectCameraCrop.PREF_RIGHT_Y : DirectCameraCrop.PREF_LEFT_Y,
                        fallback.top),
                preferences.getFloat(rightCamera
                        ? DirectCameraCrop.PREF_RIGHT_WIDTH : DirectCameraCrop.PREF_LEFT_WIDTH,
                        fallback.width),
                preferences.getFloat(rightCamera
                        ? DirectCameraCrop.PREF_RIGHT_HEIGHT : DirectCameraCrop.PREF_LEFT_HEIGHT,
                        fallback.height),
                preferences.getInt(rightCamera
                        ? DirectCameraCrop.PREF_RIGHT_ASPECT : DirectCameraCrop.PREF_LEFT_ASPECT,
                        DirectCameraCrop.ASPECT_FOUR_THREE));
    }

    private void saveCalibrationCrop(DirectCameraCrop crop) {
        String side = calibrationRightCamera ? "right" : "left";
        preferences.edit()
                .putFloat(calibrationRightCamera
                        ? DirectCameraCrop.PREF_RIGHT_X : DirectCameraCrop.PREF_LEFT_X, crop.left)
                .putFloat(calibrationRightCamera
                        ? DirectCameraCrop.PREF_RIGHT_Y : DirectCameraCrop.PREF_LEFT_Y, crop.top)
                .putFloat(calibrationRightCamera
                        ? DirectCameraCrop.PREF_RIGHT_WIDTH
                        : DirectCameraCrop.PREF_LEFT_WIDTH, crop.width)
                .putFloat(calibrationRightCamera
                        ? DirectCameraCrop.PREF_RIGHT_HEIGHT
                        : DirectCameraCrop.PREF_LEFT_HEIGHT, crop.height)
                .putInt(calibrationRightCamera
                        ? DirectCameraCrop.PREF_RIGHT_ASPECT
                        : DirectCameraCrop.PREF_LEFT_ASPECT, crop.aspectMode)
                .apply();
        record("direct_crop_saved", "side", side,
                "x", crop.left, "y", crop.top,
                "width", crop.width, "height", crop.height,
                "aspect", DirectCameraCrop.aspectLabel(crop.aspectMode),
                "output_aspect", crop.outputAspect());
        updateCameraPositionHandle();
        updateProductionPreviewSize();
        CameraHelperService.cameraSettingsChanged(this);
    }

    private void resetCalibrationCrop() {
        DirectCameraCrop crop = DirectCameraCrop.defaultFor(
                calibrationRightCamera, currentCalibrationCrop().aspectMode);
        calibrationCropOverlay.setCrop(crop);
        updateCalibrationUi(crop);
        renderCalibrationCrop();
        saveCalibrationCrop(crop);
    }

    private void selectCalibrationAspect(int aspectMode) {
        DirectCameraCrop crop = currentCalibrationCrop().withAspectMode(aspectMode);
        calibrationCropOverlay.setCrop(crop);
        updateCalibrationUi(crop);
        renderCalibrationCrop();
        saveCalibrationCrop(crop);
    }

    private DirectCameraCrop currentCalibrationCrop() {
        return calibrationCropOverlay == null
                ? DirectCameraCrop.defaultFor(calibrationRightCamera)
                : calibrationCropOverlay.getCrop();
    }

    private void updateCalibrationUi(DirectCameraCrop crop) {
        updateCalibrationCropValues(crop);
        if (calibrationResultTitle != null) {
            calibrationResultTitle.setText(
                    "Фактичний crop " + DirectCameraCrop.aspectLabel(crop.aspectMode));
        }
        for (int mode = 0; mode < calibrationAspectButtons.length; mode++) {
            Button button = calibrationAspectButtons[mode];
            if (button != null) button.setBackgroundColor(tabColor(mode == crop.aspectMode));
        }
        if (calibrationResultHost != null && calibrationResultFrame != null) {
            fitAspectFrame(calibrationResultHost, calibrationResultFrame, crop.outputAspect());
            calibrationResultFrame.post(this::renderCalibrationCrop);
        }
    }

    private void updateCalibrationCropValues(DirectCameraCrop crop) {
        calibrationCropValues.setText(String.format(Locale.US,
                "%s %s: x %.1f%%  y %.1f%%  w %.1f%%  h %.1f%%",
                calibrationRightCamera ? "R" : "L",
                DirectCameraCrop.aspectLabel(crop.aspectMode),
                crop.left * 100.0f, crop.top * 100.0f,
                crop.width * 100.0f, crop.height * 100.0f));
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
        IBinder current = helper;
        Surface surface = calibrationPreview.getHolder().getSurface();
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
        cameraHandoffPending = true;
        pendingDirectCalibration = true;
        pendingDirectCameraIndex = index;
        pendingDirectCameraTag = "pano_h";
        calibrationStatus.setText("Preparing pano_h / index " + index + "...");
        record("camera_preview_handoff", "state", "started",
                "renderer", "direct_crop_calibration", "camera_tag", "pano_h",
                "preview_index", index, "delay_ms", CAMERA_PREVIEW_HANDOFF_MS);
        CameraHelperService.cameraPreviewStarted(this);
        calibrationPreview.postDelayed(
                finishDirectCameraHandoff, CAMERA_PREVIEW_HANDOFF_MS);
        updateControls();
    }

    private void maybeOpenCalibrationCamera() {
        if (selectedTab != TAB_CAMERA_CALIBRATION || helper == null || !cameraDiscovered
                || !calibrationSurfaceReady || requestedOpen || cameraHandoffPending
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        Surface surface = calibrationPreview.getHolder().getSurface();
        if (surface != null && surface.isValid()) {
            openCalibrationCamera(calibrationRightCamera ? 3 : 2);
        }
    }

    private boolean shouldCopyCalibrationFrame() {
        return selectedTab == TAB_CAMERA_CALIBRATION && requestedOpen
                && activePreview == calibrationPreview && calibrationSurfaceReady;
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
            PixelCopy.request(calibrationPreview, calibrationCaptureBitmap, result -> {
                calibrationCopyPending = false;
                if (result != lastCalibrationCopyResult) {
                    record("calibration_pixel_copy", "result", result,
                            "recovered", result == PixelCopy.SUCCESS);
                    lastCalibrationCopyResult = result;
                }
                if (result == PixelCopy.SUCCESS && shouldCopyCalibrationFrame()) {
                    renderCalibrationCrop();
                }
                if (shouldCopyCalibrationFrame()) {
                    mainHandler.postDelayed(
                            copyCalibrationFrame, CALIBRATION_COPY_INTERVAL_MS);
                }
            }, mainHandler);
        } catch (Throwable error) {
            calibrationCopyPending = false;
            record("calibration_pixel_copy", "error", error.toString());
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
        DirectCameraCrop crop = calibrationCropOverlay.getCrop();
        int sourceWidth = calibrationCaptureBitmap.getWidth();
        int sourceHeight = calibrationCaptureBitmap.getHeight();
        Rect source = new Rect(
                Math.round(crop.left * sourceWidth),
                Math.round(crop.top * sourceHeight),
                Math.round(crop.right() * sourceWidth),
                Math.round(crop.bottom() * sourceHeight));
        Canvas canvas = new Canvas(calibrationResultBitmap);
        canvas.drawColor(Color.BLACK);
        canvas.drawBitmap(calibrationCaptureBitmap, source,
                new Rect(0, 0, width, height), calibrationCropPaint);
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
        cameraPreviewFrame = buildPreviewFrame(false);
        cameraPreviewHost.addView(cameraPreviewFrame,
                new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        cameraPreviewHost.addOnLayoutChangeListener((view, left, top, right, bottom,
                oldLeft, oldTop, oldRight, oldBottom) -> updateProductionPreviewSize());
        return cameraPreviewHost;
    }

    private void updateProductionPreviewSize() {
        if (cameraPreviewHost == null || cameraPreviewFrame == null
                || cameraScaleInput == null || cameraPreviewHost.getWidth() == 0) return;
        int scale = BlindSpotOverlayController.MIN_SCALE_PERCENT
                + cameraScaleInput.getProgress();
        int requestedWidth = getResources().getDisplayMetrics().widthPixels * scale / 100;
        boolean right = activeCameraViewpoint == StockAvmPreview.VIEW_BLIND_SPOT_RIGHT;
        DirectCameraCrop crop = loadCalibrationCrop(right);
        int[] size = BlindSpotOverlayController.fitAspect(requestedWidth,
                cameraPreviewHost.getWidth(), cameraPreviewHost.getHeight(),
                crop.outputAspect());
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                cameraPreviewFrame.getLayoutParams();
        if (params.width != size[0] || params.height != size[1]) {
            params.width = size[0];
            params.height = size[1];
            params.gravity = Gravity.CENTER;
            cameraPreviewFrame.setLayoutParams(params);
        }
        if (cameraPreview != null) {
            cameraPreview.post(() -> cameraPreview.applyDirectCameraCrop(crop));
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
            surface.applyDirectCameraCrop(loadCalibrationCrop(false));
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

    private TextView label(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(17);
        return label;
    }

    private TextView statusText(String text) {
        TextView status = new TextView(this);
        status.setText(text);
        status.setTextColor(Color.LTGRAY);
        status.setTextSize(14);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        return status;
    }

    private Button button(String text) {
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

    private void saveCameraMinSpeed() {
        try {
            int value = Integer.parseInt(cameraMinSpeedInput.getText().toString());
            if (value < 0 || value > 300) throw new NumberFormatException();
            preferences.edit().putInt(BlindSpotOverlayController.PREF_MIN_SPEED, value).apply();
            record("camera_settings", "minimum_speed_kph", value);
            CameraHelperService.cameraSettingsChanged(this);
        } catch (NumberFormatException error) {
            cameraMinSpeedInput.setText(String.valueOf(DEFAULT_CAMERA_MIN_SPEED_KPH));
            cameraStatus.setText("Мінімальна швидкість має бути 0..300 км/год");
        }
    }

    private void saveOverlayPlacement() {
        if (cameraScaleInput == null || cameraPositionHandle == null) return;
        int scale = BlindSpotOverlayController.MIN_SCALE_PERCENT
                + cameraScaleInput.getProgress();
        if (editingRightCameraPosition) cameraRightScale = scale;
        else cameraLeftScale = scale;
        preferences.edit()
                .putInt(BlindSpotOverlayController.PREF_LEFT_SCALE, cameraLeftScale)
                .putInt(BlindSpotOverlayController.PREF_RIGHT_SCALE, cameraRightScale)
                .putInt(BlindSpotOverlayController.PREF_LEFT_TARGET, cameraLeftTarget)
                .putInt(BlindSpotOverlayController.PREF_RIGHT_TARGET, cameraRightTarget)
                .putFloat(BlindSpotOverlayController.PREF_LEFT_X, cameraLeftX)
                .putFloat(BlindSpotOverlayController.PREF_LEFT_Y, cameraLeftY)
                .putFloat(BlindSpotOverlayController.PREF_RIGHT_X, cameraRightX)
                .putFloat(BlindSpotOverlayController.PREF_RIGHT_Y, cameraRightY)
                .apply();
        record("camera_overlay_settings",
                "left_scale_percent", cameraLeftScale,
                "right_scale_percent", cameraRightScale,
                "left_target", CameraDisplayTarget.name(cameraLeftTarget),
                "right_target", CameraDisplayTarget.name(cameraRightTarget),
                "left_x", cameraLeftX, "left_y", cameraLeftY,
                "right_x", cameraRightX, "right_y", cameraRightY);
        CameraHelperService.cameraSettingsChanged(this);
    }

    private void selectCameraPosition(boolean right) {
        editingRightCameraPosition = right;
        cameraLeftPositionButton.setBackgroundColor(Color.rgb(
                right ? 50 : 78, right ? 50 : 78, right ? 50 : 78));
        cameraRightPositionButton.setBackgroundColor(Color.rgb(
                right ? 78 : 50, right ? 78 : 50, right ? 78 : 50));
        cameraPositionHandle.setText(right ? "R" : "L");
        cameraPositionHandle.setBackgroundColor(right
                ? Color.rgb(35, 95, 155) : Color.rgb(35, 120, 70));
        int scale = right ? cameraRightScale : cameraLeftScale;
        cameraScaleInput.setProgress(scale - BlindSpotOverlayController.MIN_SCALE_PERCENT);
        cameraScaleValue.setText(scale + "%");
        updateCameraTargetButtons();
        updateCameraPositionCanvasSize();
        updateCameraPositionHandle();
    }

    private void selectCameraTarget(int target) {
        if (!CameraDisplayTarget.isValid(target)) return;
        if (editingRightCameraPosition) cameraRightTarget = target;
        else cameraLeftTarget = target;
        updateCameraTargetButtons();
        updateCameraPositionCanvasSize();
        saveOverlayPlacement();
    }

    private void updateCameraTargetButtons() {
        if (cameraTabletTargetButton == null || cameraClusterTargetButton == null) return;
        int target = editingRightCameraPosition ? cameraRightTarget : cameraLeftTarget;
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
        int availableHeight = cameraPositionHost.getHeight() - dp(16);
        if (availableWidth <= 0 || availableHeight <= 0) return;
        int target = editingRightCameraPosition ? cameraRightTarget : cameraLeftTarget;
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
        if (cameraPositionWidget == null || cameraPositionHandle == null
                || cameraScaleInput == null || cameraPositionWidget.getWidth() == 0) return;
        int scale = editingRightCameraPosition ? cameraRightScale : cameraLeftScale;
        boolean right = editingRightCameraPosition;
        DirectCameraCrop crop = loadCalibrationCrop(right);
        int requestedWidth = cameraPositionWidget.getWidth() * scale / 100;
        int[] size = BlindSpotOverlayController.fitAspect(requestedWidth,
                cameraPositionWidget.getWidth(), cameraPositionWidget.getHeight(),
                crop.outputAspect());
        int width = size[0];
        int height = size[1];
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams)
                cameraPositionHandle.getLayoutParams();
        params.width = width;
        params.height = height;
        cameraPositionHandle.setLayoutParams(params);
        float x = editingRightCameraPosition ? cameraRightX : cameraLeftX;
        float y = editingRightCameraPosition ? cameraRightY : cameraLeftY;
        cameraPositionHandle.setX((cameraPositionWidget.getWidth() - width) * x);
        cameraPositionHandle.setY((cameraPositionWidget.getHeight() - height) * y);
    }

    private void moveCameraPositionHandle(float x, float y) {
        float maxX = Math.max(0, cameraPositionWidget.getWidth()
                - cameraPositionHandle.getWidth());
        float maxY = Math.max(0, cameraPositionWidget.getHeight()
                - cameraPositionHandle.getHeight());
        cameraPositionHandle.setX(Math.max(0, Math.min(maxX, x)));
        cameraPositionHandle.setY(Math.max(0, Math.min(maxY, y)));
    }

    private void captureCameraPositionHandle() {
        float maxX = Math.max(0, cameraPositionWidget.getWidth()
                - cameraPositionHandle.getWidth());
        float maxY = Math.max(0, cameraPositionWidget.getHeight()
                - cameraPositionHandle.getHeight());
        float x = maxX == 0 ? 0 : cameraPositionHandle.getX() / maxX;
        float y = maxY == 0 ? 0 : cameraPositionHandle.getY() / maxY;
        if (editingRightCameraPosition) {
            cameraRightX = x;
            cameraRightY = y;
        } else {
            cameraLeftX = x;
            cameraLeftY = y;
        }
    }

    private void clearCaptureLogs() {
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
        TextView status = guardStatus == null ? cameraStatus : guardStatus;
        status.setText(failed == 0
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
        IBinder current = helper;
        Surface surface = debugPreview.getHolder().getSurface();
        if (current == null || !debugSurfaceReady || !surface.isValid()) {
            record("open_rejected", "view", viewName, "reason", "camera_not_ready");
            return;
        }
        int index = previewIndex(viewName);
        activePreview = debugPreview;
        activePreviewCover = debugPreviewCover;
        activeCameraViewpoint = -1;
        showPreview(debugPreview, activePreviewCover, false, false);
        requestedOpen = true;
        debugCameraStatus.setText("Opening " + viewName + " (preview " + index + ")...");
        record("open_requested", "view", viewName, "preview_index", index);
        updateControls();
        ipcExecutor.execute(() -> transactOpen(current, surface, index, viewName));
    }

    private void openDirectCamera(int index) {
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
        cameraHandoffPending = true;
        pendingDirectCalibration = false;
        pendingDirectCameraIndex = index;
        pendingDirectCameraTag = "pano_h";
        directCameraStatus.setText(
                "Preparing pano_h / index " + index + "...");
        record("camera_preview_handoff", "state", "started",
                "renderer", "direct_avm", "camera_tag", "pano_h",
                "preview_index", index, "delay_ms", CAMERA_PREVIEW_HANDOFF_MS);
        CameraHelperService.cameraPreviewStarted(this);
        directCameraPreview.postDelayed(
                finishDirectCameraHandoff, CAMERA_PREVIEW_HANDOFF_MS);
        updateControls();
    }

    private void maybeOpenReversePreview() {
        if (selectedTab != TAB_REVERSE_CAMERAS || helper == null || !cameraDiscovered
                || reverseCameraPreview == null || !reverseCameraSurfacesReady
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED
                || requestedOpen || cameraHandoffPending) {
            return;
        }
        int requestId = ++reversePreviewRequestSequence;
        ReverseCameraCompositionView.SurfaceBundle bundle;
        try {
            bundle = reverseCameraPreview.acquireSurfaces(requestId);
            reverseCameraPreview.armFrames(requestId, bundle.generations);
        } catch (Throwable error) {
            reverseCameraStatus.setText("Surface unavailable");
            record("reverse_preview_error", "stage", "acquire_surfaces",
                    "error", error.toString());
            return;
        }
        activePreview = reverseCameraPreview;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        requestedOpen = true;
        reverseCameraStatus.setText("Opening pano_h indexes 1/2/3...");
        CameraHelperService.cameraPreviewStarted(this);
        record("reverse_preview_open", "request_id", requestId,
                "generations", java.util.Arrays.toString(bundle.generations));
        updateControls();
        IBinder current = helper;
        ipcExecutor.execute(() -> transactOpenReversePreview(
                current, bundle.surfaces, requestId));
    }

    private void openPendingDirectCamera() {
        if (!cameraHandoffPending || pendingDirectCameraIndex < 0) return;
        int index = pendingDirectCameraIndex;
        String cameraTag = pendingDirectCameraTag;
        boolean calibration = pendingDirectCalibration;
        cameraHandoffPending = false;
        pendingDirectCalibration = false;
        pendingDirectCameraIndex = -1;
        pendingDirectCameraTag = null;
        record("camera_preview_handoff", "state", "completed",
                "renderer", calibration ? "direct_crop_calibration" : "direct_avm",
                "camera_tag", cameraTag,
                "preview_index", index);
        openDirectCameraNow(cameraTag, index, calibration);
    }

    private void openDirectCameraNow(String cameraTag, int index, boolean calibration) {
        IBinder current = helper;
        SurfaceView target = calibration ? calibrationPreview : directCameraPreview;
        View cover = calibration ? calibrationPreviewCover : directCameraPreviewCover;
        TextView status = calibration ? calibrationStatus : directCameraStatus;
        boolean surfaceReady = calibration ? calibrationSurfaceReady : directCameraSurfaceReady;
        Surface surface = target.getHolder().getSurface();
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
        showPreview(target, cover, false, false);
        requestedOpen = true;
        status.setText("Opening " + cameraTag + " / index " + index + "...");
        record("open_requested", "renderer", renderer,
                "camera_tag", cameraTag, "preview_index", index);
        updateControls();
        ipcExecutor.execute(() -> transactOpenDirect(current, surface, cameraTag, index));
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

    private void openStockAvm(int viewpoint, boolean debug) {
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
        if (!cameraHandoffPending) return;
        int viewpoint = pendingCameraViewpoint;
        boolean debug = pendingCameraDebug;
        cameraHandoffPending = false;
        pendingCameraViewpoint = -1;
        record("camera_preview_handoff", "state", "completed", "viewpoint", viewpoint);
        openStockAvmNow(viewpoint, debug);
    }

    private void openStockAvmNow(int viewpoint, boolean debug) {
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
        if (!debug) {
            updateProductionPreviewSize();
            cameraPreview.applyDirectCameraCrop(loadCalibrationCrop(right));
            cover.setVisibility(View.INVISIBLE);
            requestedOpen = true;
            int previewIndex = right ? 3 : 2;
            cameraStatus.setText("Opening " + viewName + "...");
            record("open_requested", "renderer", renderer,
                    "camera_tag", "pano_h", "preview_index", previewIndex,
                    "direction", right ? "right" : "left");
            updateControls();
            ipcExecutor.execute(() -> transactOpenDirect(
                    current, surface, "pano_h", previewIndex));
            return;
        }
        applyDebugPreviewMode();
        requestedOpen = true;
        boolean horizontal = debugHorizontal;
        cameraStatus(debug).setText("Opening " + viewName + "...");
        record("open_requested", "renderer", "stock_avm",
                "view", viewName, "viewpoint", viewpoint,
                "orientation", horizontal ? "horizontal" : "vertical",
                "show_raw", debugShowRawSwitch.isChecked(),
                "target", "debug");
        updateControls();
        ipcExecutor.execute(() -> transactOpenStockAvm(
                current, surface, viewpoint, horizontal));
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

    private void closeCamera(String reason) {
        if (!requestedOpen && !cameraHandoffPending) return;
        TextView status = activeCameraStatus();
        if (cameraHandoffPending) {
            cameraPreview.removeCallbacks(finishCameraHandoff);
            debugPreview.removeCallbacks(finishCameraHandoff);
            directCameraPreview.removeCallbacks(finishDirectCameraHandoff);
            calibrationPreview.removeCallbacks(finishDirectCameraHandoff);
            cameraHandoffPending = false;
            pendingCameraViewpoint = -1;
            pendingDirectCalibration = false;
            pendingDirectCameraIndex = -1;
            pendingDirectCameraTag = null;
            record("camera_preview_handoff", "state", "canceled", "reason", reason);
        }
        boolean wasOpen = requestedOpen;
        requestedOpen = false;
        stopCalibrationCopies(true);
        clearPreview(reason);
        IBinder current = helper;
        record("close_requested", "reason", reason);
        if (wasOpen && current != null) {
            ipcExecutor.execute(() -> transactClose(current, reason));
        }
        CameraHelperService.cameraPreviewStopped(this);
        status.setText("Camera closed");
        activePreview = null;
        activePreviewCover = null;
        activeCameraViewpoint = -1;
        updateControls();
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

    private void registerCallback() {
        IBinder current = helper;
        if (current == null) return;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeStrongBinder(callback);
            requireTransaction(current, CameraHelperMain.TX_REGISTER_CALLBACK, data, reply);
            record("ipc_reply", "operation", "register_callback", "reply", reply.readString());
            runOnUiThread(() -> {
                pushGuardConfig();
                advanceStartupAuthorizationFlow();
            });
        } catch (Throwable error) {
            record("ipc_error", "operation", "register_callback", "error", error.toString());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void detachHelperCallback() {
        IBinder current = helper;
        if (current != null) ipcExecutor.execute(() -> transactSimple(
                current, CameraHelperMain.TX_DETACH_CALLBACK, "detach_callback"));
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

    private void transactOpen(IBinder current, Surface surface, int index, String viewName) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(index);
            data.writeString(viewName);
            requireTransaction(current, CameraHelperMain.TX_OPEN, data, reply);
            record("ipc_reply", "operation", "open", "reply", reply.readString());
        } catch (Throwable error) {
            requestedOpen = false;
            record("ipc_error", "operation", "open", "error", error.toString());
            runOnUiThread(() -> {
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
            IBinder current, Surface surface, String cameraTag, int index) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeString(cameraTag);
            data.writeInt(index);
            requireTransaction(current, CameraHelperMain.TX_OPEN_DIRECT, data, reply);
            record("ipc_reply", "operation", "open_direct",
                    "camera_tag", cameraTag, "preview_index", index,
                    "reply", reply.readString());
        } catch (Throwable error) {
            requestedOpen = false;
            record("ipc_error", "operation", "open_direct",
                    "camera_tag", cameraTag, "preview_index", index,
                    "error", error.toString());
            CameraHelperService.cameraPreviewStopped(this);
            runOnUiThread(() -> {
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
            for (Surface surface : surfaces) surface.writeToParcel(data, 0);
            requireTransaction(current, CameraHelperMain.TX_OPEN_REVERSE_PREVIEW, data, reply);
            String result = reply.readString();
            record("ipc_reply", "operation", "open_reverse_preview",
                    "request_id", requestId, "reply", result);
            JSONObject json = new JSONObject(result);
            if (!"camera_opened".equals(json.optString("kind"))) {
                throw new IllegalStateException(json.optString("error", json.optString("kind")));
            }
        } catch (Throwable error) {
            requestedOpen = false;
            record("ipc_error", "operation", "open_reverse_preview",
                    "request_id", requestId, "error", error.toString());
            CameraHelperService.cameraPreviewStopped(this);
            runOnUiThread(() -> {
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

    private void transactOpenStockAvm(
            IBinder current, Surface surface, int viewpoint, boolean horizontal) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            surface.writeToParcel(data, 0);
            data.writeInt(viewpoint);
            data.writeInt(horizontal ? 1 : 0);
            requireTransaction(current, CameraHelperMain.TX_OPEN_STOCK_AVM, data, reply);
            record("ipc_reply", "operation", "open_stock_avm",
                    "viewpoint", viewpoint, "orientation",
                    horizontal ? "horizontal" : "vertical", "reply", reply.readString());
        } catch (Throwable error) {
            requestedOpen = false;
            record("ipc_error", "operation", "open_stock_avm",
                    "viewpoint", viewpoint, "error", error.toString());
            CameraHelperService.cameraPreviewStopped(this);
            runOnUiThread(() -> {
                cameraStatus(activePreview == debugPreview).setText(
                        "Stock AVM failed: " + error.getClass().getSimpleName());
                updateControls();
            });
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactClose(IBinder current, String reason) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            data.writeString(reason);
            requireTransaction(current, CameraHelperMain.TX_CLOSE, data, reply);
            record("ipc_reply", "operation", "close", "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", "close", "error", error.toString());
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void transactSimple(IBinder current, int code, String operation) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CameraHelperMain.DESCRIPTOR);
            requireTransaction(current, code, data, reply);
            record("ipc_reply", "operation", operation, "reply", reply.readString());
        } catch (Throwable error) {
            record("ipc_error", "operation", operation, "error", error.toString());
        } finally {
            reply.recycle();
            data.recycle();
        }
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

    private void openBackgroundStartSettings(String reason) {
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
            guardStatus.setText("Системне вікно фонового запуску недоступне");
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

    private boolean requestAdbAuthorization(
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
        guardStatus.setText(ADB_WAITING_STATUS);
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
                guardStatus.setText("ADB retry IPC error");
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

    private void handleMusicEvent(JSONObject event) {
        String kind = event.optString("kind");
        if ("music_journal_snapshot".equals(kind)) {
            musicJournal.clear();
            JSONArray events = event.optJSONArray("events");
            if (events != null) {
                for (int i = 0; i < events.length(); i++) {
                    try {
                        appendMusicJournal(formatMusicEvent(
                                new JSONObject(events.optString(i))));
                    } catch (Throwable ignored) {
                    }
                }
            }
            renderMusicJournal();
            return;
        }
        if (!kind.startsWith("music_")) return;
        if (CameraHelperMain.HelperBinder.isMusicJournalEvent(kind)) {
            appendMusicJournal(formatMusicEvent(event));
            renderMusicJournal();
        }

        if ("music_runtime_status".equals(kind)) {
            boolean enabled = event.optBoolean("enabled");
            String error = event.optString("error");
            if (!enabled) musicStatus.setText("Вимкнено");
            else if (!error.isEmpty()) musicStatus.setText("Помилка: " + error);
            else if (event.optBoolean("stop_pending")) {
                musicStatus.setText("Завершення через 3 с");
            } else if (event.optBoolean("session_active")) {
                musicStatus.setText("Синхронізація активна");
            } else {
                musicStatus.setText("Очікування музики");
            }
        } else if ("music_visualizer_start".equals(kind)) {
            musicStatus.setText(event.optBoolean("ok", true)
                    ? "Синхронізація активна"
                    : "Помилка: " + event.optString("error"));
        } else if ("music_visualizer_stop_pending".equals(kind)) {
            musicStatus.setText("Завершення через 3 с");
        } else if ("music_visualizer_stop".equals(kind)) {
            musicStatus.setText(event.optBoolean("ok", true)
                    ? "Очікування музики"
                    : "Помилка: " + event.optString("error"));
        } else if ("music_runtime_config".equals(kind)) {
            if (!event.optBoolean("enabled")) musicStatus.setText("Вимкнено");
            else if (!event.optString("error").isEmpty()) {
                musicStatus.setText("Помилка: " + event.optString("error"));
            } else if (event.optBoolean("session_active")) {
                musicStatus.setText("Синхронізація активна");
            } else {
                musicStatus.setText("Очікування музики");
            }
        } else if ("music_runtime_error".equals(kind)) {
            musicStatus.setText("Помилка: " + event.optString("error"));
        }
    }

    private void appendMusicJournal(String line) {
        if (line == null || line.isEmpty()) return;
        while (musicJournal.size() >= 20) musicJournal.removeFirst();
        musicJournal.addLast(line);
    }

    private void renderMusicJournal() {
        if (musicJournalText == null) return;
        if (musicJournal.isEmpty()) {
            musicJournalText.setText("Подій ще немає");
            return;
        }
        StringBuilder text = new StringBuilder();
        for (String line : musicJournal) {
            if (text.length() > 0) text.append('\n');
            text.append(line);
        }
        musicJournalText.setText(text);
    }

    private static String formatMusicEvent(JSONObject event) {
        String kind = event.optString("kind");
        String wallTime = event.optString("wall_time");
        String time = wallTime.length() >= 19 ? wallTime.substring(11, 19) : "--:--:--";
        String message;
        if ("music_runtime_config".equals(kind)) {
            message = event.optBoolean("enabled") ? "Функцію увімкнено" : "Функцію вимкнено";
        } else if ("music_playback_state".equals(kind)) {
            message = event.optBoolean("active") ? "Виявлено відтворення" : "Відтворення зупинено";
        } else if ("music_visualizer_start".equals(kind)) {
            message = event.optBoolean("ok", true)
                    ? "Синхронізацію запущено" : "Помилка запуску";
        } else if ("music_visualizer_stop_pending".equals(kind)) {
            message = "Зупинка через 3 секунди";
        } else if ("music_visualizer_stop".equals(kind)) {
            message = event.optBoolean("ok", true)
                    ? "Синхронізацію зупинено" : "Помилка зупинки";
        } else if ("music_runtime_error".equals(kind)) {
            message = "Помилка: " + event.optString("error");
        } else if ("music_runtime_status".equals(kind)) {
            message = "Стан оновлено";
        } else {
            message = kind;
        }
        return time + "  " + message;
    }

    private void acceptHelperEvent(String line) {
        if (line == null) return;
        writeLine(line);
        runOnUiThread(() -> {
            try {
                JSONObject json = new JSONObject(line);
                String kind = json.optString("kind");
                handleMusicEvent(json);
                if ("reverse_camera_stopped".equals(kind)) {
                    maybeOpenReversePreview();
                    updateControls();
                    return;
                }
                if (isOverlayCameraEvent(kind, json.optString("camera_owner"))) return;
                if ("camera_opened".equals(kind)) {
                    if (activePreview == reverseCameraPreview
                            && "reverse_preview".equals(json.optString("view"))) {
                        reverseCameraStatus.setText("Очікування перших кадрів...");
                    } else cameraStatusForEvent(json).setText(
                            json.optString("renderer").startsWith("stock_avm")
                            || json.optInt("preview_index", -1) < 0
                            ? "Showing " + json.optString("view")
                            : "Showing " + json.optString("view")
                                    + " (preview " + json.optInt("preview_index") + ")");
                    if (activePreview == calibrationPreview) startCalibrationCopies();
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
                    maybeOpenReversePreview();
                } else if ("camera_error".equals(kind)) {
                    boolean resumeOverlay = requestedOpen;
                    requestedOpen = false;
                    stopCalibrationCopies(true);
                    cameraStatusForEvent(json).setText(
                            "Camera error: " + json.optString("error"));
                    clearPreview("camera_error");
                    activePreview = null;
                    activePreviewCover = null;
                    if (resumeOverlay) CameraHelperService.cameraPreviewStopped(this);
                } else if ("camera_closed".equals(kind)) {
                    if (!isIntermediateCameraClose(json.optString("reason"))) {
                        boolean resumeOverlay = requestedOpen;
                        requestedOpen = false;
                        stopCalibrationCopies(true);
                        cameraStatusForEvent(json).setText("Camera closed");
                        clearPreview("camera_closed");
                        activePreview = null;
                        activePreviewCover = null;
                        if (resumeOverlay) CameraHelperService.cameraPreviewStopped(this);
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
                    guardStatus.setText(ADB_WAITING_STATUS);
                } else if ("adb_auth_state".equals(kind)) {
                    adbAuthPending = json.optBoolean("pending");
                    adbAuthMode = adbAuthPending
                            ? adbPromptMode(json.optString("mode")) : null;
                    if (adbAuthPending) {
                        guardStatus.setText(ADB_WAITING_STATUS);
                    } else if (ADB_WAITING_STATUS.contentEquals(guardStatus.getText())) {
                        guardStatus.setText(telemetryReady
                                ? "Телеметрія готова" : "ADB авторизація потрібна");
                    }
                } else if ("authorization_superseded".equals(kind)) {
                    adbAuthMode = adbPromptMode(json.optString("next_mode"));
                    adbAuthPending = adbAuthMode != null;
                    if (adbAuthPending) guardStatus.setText(ADB_WAITING_STATUS);
                } else if ("adb_auth_auto_blocked".equals(kind)) {
                    guardStatus.setText("ADB авторизація потрібна; натисніть повторити");
                } else if ("adb_auth_result".equals(kind)) {
                    if (!json.optBoolean("ok")) {
                        telemetryReady = false;
                        guardStatus.setText("ADB: " + json.optString("error"));
                    }
                } else if ("helper_launch".equals(kind) && !json.optBoolean("ok")) {
                    telemetryReady = false;
                    guardStatus.setText("Helper: " + json.optString("error"));
                } else if ("helper_death".equals(kind)
                        || "helper_ping_failed".equals(kind)) {
                    telemetryReady = false;
                    guardStatus.setText("Helper відновлюється: " + json.optString("error"));
                    if (musicSwitch.isChecked()) musicStatus.setText("Helper недоступний");
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

    private void updateCounters() {
        activationCount.setText(lifetimeActivations + "\nУвімкнень");
        correctionCount.setText(lifetimeCorrections + "\nКорекцій");
    }

    private void clearPreview(String reason) {
        if (activePreview == reverseCameraPreview && reverseCameraPreview != null) {
            reverseCameraPreview.clearFrames();
        }
        if (activePreviewCover != null) activePreviewCover.setVisibility(View.VISIBLE);
        record("preview_cleared", "reason", reason, "ok", true, "error", "");
    }

    private void showPreview(
            SurfaceView target, View cover, boolean cropLeft, boolean cropRight) {
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
        if (autoStartSwitch != null) autoStartSwitch.setEnabled(!shutdownRequested);
        if (musicSwitch != null) musicSwitch.setEnabled(!shutdownRequested);
        if (backgroundStartSettingsButton != null) {
            backgroundStartSettingsButton.setEnabled(!shutdownRequested
                    && !cameraPermissionPending && !backgroundStartSettingsActive
                    && !backgroundStartSettingsStartScheduled && !adbAuthPending);
        }
        if (shutdownButton != null) shutdownButton.setEnabled(!shutdownRequested);
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
        boolean cameraEnabled = cameraSwitch != null && cameraSwitch.isChecked();
        if (cameraMinSpeedInput != null) cameraMinSpeedInput.setEnabled(cameraEnabled);
        if (cameraScaleInput != null) cameraScaleInput.setEnabled(cameraEnabled);
        if (cameraLeftPositionButton != null) cameraLeftPositionButton.setEnabled(cameraEnabled);
        if (cameraRightPositionButton != null) cameraRightPositionButton.setEnabled(cameraEnabled);
        if (cameraPositionHandle != null) {
            cameraPositionHandle.setEnabled(cameraEnabled);
            cameraPositionHandle.setAlpha(cameraEnabled ? 1.0f : 0.45f);
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
        if (calibrationLeftButton != null) {
            calibrationLeftButton.setEnabled(calibrationReady);
            calibrationRightButton.setEnabled(calibrationReady);
            calibrationResetButton.setEnabled(true);
            calibrationStopButton.setEnabled(
                    (requestedOpen || cameraHandoffPending)
                            && activePreview == calibrationPreview);
        }
        if (clearLogsButton != null) {
            clearLogsButton.setEnabled(!requestedOpen && !cameraHandoffPending);
        }
        for (Button button : turnStateButtons) {
            if (button != null) button.setEnabled(
                    helper != null && telemetryReady && !manualTurnRequestPending
                            && !guardSwitch.isChecked());
        }
        if (adbRetryButton != null) {
            adbRetryButton.setEnabled(!backgroundStartSettingsPending()
                    && shouldEnableManualAdbAuthorization(
                            helper != null, adbAuthPending, adbAuthMode));
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

    static boolean isIntermediateCameraClose(String reason) {
        return "preview_handoff".equals(reason) || "replace_preview".equals(reason);
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

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
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

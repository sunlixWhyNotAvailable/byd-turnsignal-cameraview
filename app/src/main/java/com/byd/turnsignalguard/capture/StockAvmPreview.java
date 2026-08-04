package com.byd.turnsignalguard.capture;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Parcel;
import android.view.Surface;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StockAvmPreview {
    static final int VIEW_REAR_DUAL = 2018;
    static final int VIEW_REAR_LEFT = 2031;
    static final int VIEW_REAR_RIGHT = 2033;
    private static final int DIAGNOSTIC_VIEW_BASE = 10_000;
    static final int VIEW_BLIND_SPOT_LEFT = DIAGNOSTIC_VIEW_BASE + 8;
    static final int VIEW_BLIND_SPOT_RIGHT = DIAGNOSTIC_VIEW_BASE + 9;
    static final int VIEW_REAR_LEFT_CLAIRVOYANCE = 11_000;
    static final int VIEW_REAR_RIGHT_CLAIRVOYANCE = 11_001;
    static final float NORMAL_CAMERA_TILE_START_X = 0.433121019f;
    private static final String[] HORIZONTAL_LAYOUTS = {
            "VIEW_2D_TOP",
            "VIEW_2D_TOP_FULL",
            "VIEW_2D_FRONT",
            "VIEW_2D_REAR",
            "VIEW_2D_FRONT_FULL",
            "VIEW_2D_REAR_FULL",
            "VIEW_2D_LEFT_FRONT",
            "VIEW_2D_RIGHT_FRONT",
            "VIEW_2D_LEFT_REAR",
            "VIEW_2D_RIGHT_REAR",
            "VIEW_2D_FRONT_WHEELS",
            "VIEW_2D_REAR_WHEELS",
            "VIEW_2D_ALL_WHEELS",
            "VIEW_2D_ALL_WHEELS_OVER_SIZE",
            "VIEW_2D_FRONT_TOP",
            "VIEW_2D_REAR_TOP",
            "VIEW_3D_FRONT",
            "VIEW_3D_REAR",
            "VIEW_3D_LEFT",
            "VIEW_3D_RIGHT",
            "VIEW_3D_LEFT_FRONT",
            "VIEW_3D_RIGHT_FRONT",
            "VIEW_3D_LEFT_REAR",
            "VIEW_3D_RIGHT_REAR",
            "VIEW_3D_FREE_MODE",
            "VIEW_AHEAD_LEFT",
            "VIEW_AHEAD_RIGHT",
            "VIEW_TRANSPARENT_FRONT",
            "VIEW_TRANSPARENT_REAR",
            "VIEW_TURN_LEFT",
            "VIEW_TURN_RIGHT",
            "VIEW_3D_UAV",
            "VIEW_3D_OVERLOOK_VEHICLE_HEAD_ZOOM_IN",
            "VIEW_3D_OVERLOOK_VEHICLE_REAR_ZOOM_IN",
            "VIEW_2D_FRONT_APA",
            "VIEW_2D_REAR_APA",
            "VIEW_2D_FRONT_SINGLE_APA",
            "VIEW_2D_REAR_SINGLE_APA",
            "VIEW_2D_FRONT_FULL_APA",
            "VIEW_2D_REAR_FULL_APA",
            "VIEW_3D_TOP",
            "VIEW_3D_SUPER_TOP",
            "VIEW_2D_MECANUM",
            "VIEW_2D_IN_PLACE",
            "VIEW_CALI",
            "VIEW_2D_REAR_LEFT_AND_RIGHT",
            "VIEW_2D_FRONT_LEFT_AND_RIGHT",
            "VIEW_2D_FRONT_CLAIRVOYANCE",
            "VIEW_2D_REAR_CLAIRVOYANCE",
            "VIEW_2D_LEFT_CLAIRVOYANCE",
            "VIEW_2D_RIGHT_CLAIRVOYANCE"
    };

    private static final String STOCK_PACKAGE = "com.byd.avc";
    private static final String STOCK_SDK_API = "com.byd.avc.panosdk.TSAPI";
    private static final String RESOURCE_CACHE = "/data/local/tmp/bydturnguard_avm";
    private static final long DISPLAY_READY_TIMEOUT_MS = 500;
    private static final long DISPLAY_STATUS_POLL_MS = 20;
    private static final long INPUT_SURFACE_READY_TIMEOUT_MS = 500;

    private final Context baseContext;
    private final BiConsumer<String, String> stageSink;
    private Object panoApi;
    private Surface surface;
    private Surface inputSurface;
    private int viewpoint = -1;

    StockAvmPreview(Context context, BiConsumer<String, String> stageSink) {
        baseContext = context;
        this.stageSink = stageSink;
    }

    static Config readConfig(Context context, BiConsumer<String, String> stageSink)
            throws Exception {
        String currentStage = "config_package_context";
        try {
            Context stockContext = context.createPackageContext(
                    STOCK_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = stockContext.getClassLoader();
            stageSink.accept(currentStage, stockContext.getApplicationInfo().sourceDir);

            currentStage = "config_set_stock_context";
            invokeStatic(loader, "com.byd.avc.util.devicestates.DeviceStates", "setAVCContext",
                    new Class<?>[]{Context.class}, stockContext);
            stageSink.accept(currentStage, "ok");

            currentStage = "config_init_boot_board";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarStatus", "initBootBoard");
            stageSink.accept(currentStage, "ok");

            currentStage = "config_init_panorama_state";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarSetting",
                    "initPanoramaState", new Class<?>[]{Context.class}, stockContext);
            stageSink.accept(currentStage, "ok");

            currentStage = "config_init_panorama_db_state";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarSetting",
                    "initPanoramaDBState", new Class<?>[]{Context.class}, stockContext);
            stageSink.accept(currentStage, "ok");

            currentStage = "config_init_car_body_type";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarStatus",
                    "initCarBodyTypeValue", new Class<?>[]{Context.class}, stockContext);
            stageSink.accept(currentStage, "ok");

            currentStage = "config_read_values";
            int panoramaState = (Integer) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarSetting", "getPanoramaState");
            int panoWidth = (Integer) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarSetting", "getPanoWidth");
            int panoHeight = (Integer) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarSetting", "getPanoHeight");
            int modelValue = (Integer) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarStatus", "getCarBodyTypeValue",
                    new Class<?>[]{Context.class}, stockContext);
            int modelValueOrigin = (Integer) getStaticField(loader,
                    "com.byd.avc.util.devicestates.CarStatus", "modelValueOrigin");
            String carSeries = (String) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarStatus", "getCarSeries");
            String carType = (String) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarStatus", "getCarBodyType",
                    new Class<?>[]{Context.class}, stockContext);
            String subCarType = (String) invokeStatic(loader,
                    "com.byd.avc.util.devicestates.CarStatus", "getSubCarType",
                    new Class<?>[]{Context.class}, stockContext);
            Config config = new Config(panoramaState, modelValue, modelValueOrigin,
                    panoWidth, panoHeight, carSeries, carType, subCarType);
            stageSink.accept(currentStage, config.detail());
            return config;
        } catch (Throwable error) {
            throw new StageException(currentStage, root(error));
        }
    }

    synchronized boolean open(
            Surface requestedSurface, int requestedViewpoint, Config config,
            boolean horizontal) throws Exception {
        if (!isAllowedViewpoint(requestedViewpoint)) {
            requestedSurface.release();
            throw new IllegalArgumentException("Unsupported stock AVM viewpoint: "
                    + requestedViewpoint);
        }
        config.validate();
        if (!requestedSurface.isValid()) {
            requestedSurface.release();
            throw new IllegalArgumentException("Surface is invalid");
        }
        if (panoApi != null) {
            try {
                applyOrientation(panoApi, horizontal);
                int result = applyViewpoint(panoApi, requestedViewpoint);
                viewpoint = requestedViewpoint;
                stage("set_viewpoint", requestedViewpoint + ";layout="
                        + layoutName(requestedViewpoint) + ";orientation="
                        + orientationName(horizontal) + ";result=" + result);
                return false;
            } finally {
                requestedSurface.release();
            }
        }

        Object openedApi = null;
        String currentStage = "package_context";
        try {
            Context stockContext = baseContext.createPackageContext(
                    STOCK_PACKAGE, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = stockContext.getClassLoader();
            ApplicationInfo info = stockContext.getApplicationInfo();
            stage(currentStage, "base=" + baseContext.getPackageName()
                    + ";base_op=" + baseContext.getOpPackageName()
                    + ";stock=" + stockContext.getPackageName()
                    + ";stock_op=" + stockContext.getOpPackageName()
                    + ";source=" + info.sourceDir + ";native=" + info.nativeLibraryDir);

            currentStage = "set_stock_context";
            invokeStatic(loader, "com.byd.avc.util.devicestates.DeviceStates", "setAVCContext",
                    new Class<?>[]{Context.class}, stockContext);
            stage(currentStage, "ok");

            currentStage = "init_boot_board";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarStatus", "initBootBoard");
            stage(currentStage, "ok");

            currentStage = "init_panorama_state";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarSetting",
                    "initPanoramaState", new Class<?>[]{Context.class}, stockContext);
            stage(currentStage, "ok");

            currentStage = "apply_vehicle_config";
            applyConfig(loader, stockContext, config);
            stage(currentStage, config.detail());

            currentStage = "set_sub_car_type";
            invokeStatic(loader, "com.byd.avc.util.devicestates.CarStatus",
                    "setSettingSubCarType");
            stage(currentStage, "ok");

            currentStage = "set_screen_density";
            invokeStatic(loader, "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus",
                    "setDensity");
            stage(currentStage, "ok");

            currentStage = "init_device_controller";
            invokeStatic(loader, "com.byd.avc.util.devicestates.DevControler", "initDev");
            stage(currentStage, "ok");

            currentStage = "read_car_color";
            invokeStatic(loader, "com.byd.avc.util.CarBodyColorController", "getCarBodyColor");
            stage(currentStage, "ok");

            currentStage = "use_existing_resources";
            Class<?> resources = Class.forName(
                    "com.byd.avc.resources.ResourcesManager", true, loader);
            resources.getField("isAllPanoResourceOK").setBoolean(null, true);
            resources.getField("isAllCarModelResourceOK").setBoolean(null, true);
            resources.getField("isZMResResourceOK").setBoolean(null, true);
            stage(currentStage, "stock_resources_already_present");

            currentStage = "resolve_sdk_config";
            SdkConfig sdkConfig = resolveSdkConfig(loader, stockContext, config);
            stage(currentStage, sdkConfig.detail());

            currentStage = "disable_sdk_debug";
            boolean debugChanged = disableSdkDebug(
                    new File(sdkConfig.resourcePath, "Vehicle_Configuration.json"));
            stage(currentStage, debugChanged ? "changed_to_false" : "already_false");

            currentStage = "patch_rear_clairvoyance";
            boolean clairvoyanceChanged = patchRearClairvoyance(
                    new File(sdkConfig.resourcePath, "Vehicle_Configuration.json"));
            stage(currentStage, clairvoyanceChanged
                    ? "rear_ids_14_15_single_applied" : "already_applied");

            currentStage = "set_camera_index";
            invokeStatic(loader, "com.byd.avc.panobase.PanoCameraIndex", "setDirections",
                    new Class<?>[]{String.class}, sdkConfig.cameraDirections);
            stage(currentStage, sdkConfig.cameraDirections);

            currentStage = "get_panosdk_api";
            Class<?> apiClass = Class.forName(STOCK_SDK_API, true, loader);
            Method getInstance = apiClass.getDeclaredMethod("getInstance");
            getInstance.setAccessible(true);
            openedApi = getInstance.invoke(null);
            if (openedApi == null) {
                throw new IllegalStateException("panosdk.TSAPI.getInstance returned null");
            }
            stage(currentStage, openedApi.getClass().getName());

            currentStage = "initialize_panosdk";
            Object initialized = invoke(openedApi, "initialize",
                    new Class<?>[]{Context.class, String.class, String.class, String.class,
                            String.class, String.class, String.class, String.class, String.class,
                            int.class, int.class, int.class, int.class, int.class, String.class,
                            int.class, int.class},
                    stockContext, config.carType, config.subCarType, config.subCarType,
                    String.valueOf(sdkConfig.resolution), sdkConfig.carBodyColor,
                    sdkConfig.resourcePath, sdkConfig.parameterPath, sdkConfig.panoResourcePath,
                    config.panoWidth, config.panoHeight,
                    sdkConfig.screenWidth, sdkConfig.screenHeight, 0,
                    sdkConfig.commonPath, sdkConfig.cameraType, 0);
            if (!Boolean.TRUE.equals(initialized)) {
                throw new IllegalStateException("panosdk.TSAPI.initialize returned "
                        + initialized);
            }
            stage(currentStage, "ok");

            currentStage = "set_screen_rotation";
            applyOrientation(openedApi, horizontal);
            stage(currentStage, orientationName(horizontal));

            currentStage = "verify_initialize_status";
            Object statusOwner = invokeStatic(loader,
                    "com.ts.avm.bydsdk.CameraSdkStatus", "getInstance");
            String sdkStatus = String.valueOf(invoke(
                    statusOwner, "getStatus", new Class<?>[0]));
            if (!"Initialized".equals(sdkStatus) && !"Configured".equals(sdkStatus)
                    && !"Started".equals(sdkStatus)) {
                throw new IllegalStateException("camera SDK status is " + sdkStatus);
            }
            stage(currentStage, sdkStatus);

            currentStage = "init_display";
            Object displayReady = invoke(openedApi, "initDisplay",
                    new Class<?>[]{Surface.class}, requestedSurface);
            if (!Boolean.TRUE.equals(displayReady)) {
                throw new IllegalStateException("panosdk.TSAPI.initDisplay returned "
                        + displayReady);
            }
            stage(currentStage, "ok");

            currentStage = "verify_display_status";
            String displayStatus = awaitDisplayReady(loader);
            if (!isDisplayReadyStatus(displayStatus)) {
                throw new IllegalStateException("camera SDK display status is " + displayStatus);
            }
            stage(currentStage, displayStatus);

            currentStage = "set_viewpoint";
            int layoutResult = applyViewpoint(openedApi, requestedViewpoint);
            stage(currentStage, requestedViewpoint + ";layout="
                    + layoutName(requestedViewpoint) + ";result=" + layoutResult);

            currentStage = "get_camera_input_surface";
            Surface openedInput = awaitInputSurface(openedApi);
            if (openedInput == null || !openedInput.isValid()) {
                throw new IllegalStateException("AVM input Surface is invalid");
            }
            stage(currentStage, "valid");

            panoApi = openedApi;
            surface = requestedSurface;
            inputSurface = openedInput;
            viewpoint = requestedViewpoint;
            return true;
        } catch (Throwable error) {
            terminate(openedApi);
            requestedSurface.release();
            throw new StageException(currentStage, root(error));
        }
    }

    synchronized boolean isOpen() {
        return panoApi != null;
    }

    synchronized int getViewpoint() {
        return viewpoint;
    }

    synchronized Surface getInputSurface() {
        if (inputSurface == null || !inputSurface.isValid()) {
            throw new IllegalStateException("AVM input Surface is unavailable");
        }
        return inputSurface;
    }

    synchronized void close() throws Exception {
        Object activeApi = panoApi;
        Surface activeSurface = surface;
        panoApi = null;
        surface = null;
        inputSurface = null;
        viewpoint = -1;
        Throwable failure = terminate(activeApi);
        if (activeSurface != null) activeSurface.release();
        // AvmController retains this exact Surface in a static cache across SDK sessions.
        stage("closed", failure == null ? "ok" : summary(failure));
        if (failure != null) throw new Exception(failure);
    }

    static boolean isAllowedViewpoint(int viewpoint) {
        return viewpoint == VIEW_REAR_DUAL
                || viewpoint == VIEW_REAR_LEFT
                || viewpoint == VIEW_REAR_RIGHT
                || viewpoint == VIEW_REAR_LEFT_CLAIRVOYANCE
                || viewpoint == VIEW_REAR_RIGHT_CLAIRVOYANCE
                || isDiagnosticViewpoint(viewpoint);
    }

    static String viewName(int viewpoint) {
        switch (viewpoint) {
            case VIEW_REAR_DUAL: return "rear_dual_2018";
            case VIEW_REAR_LEFT: return "rear_left_2031";
            case VIEW_REAR_RIGHT: return "rear_right_2033";
            case VIEW_REAR_LEFT_CLAIRVOYANCE: return "rear_left_clairvoyance_test";
            case VIEW_REAR_RIGHT_CLAIRVOYANCE: return "rear_right_clairvoyance_test";
            default:
                if (isDiagnosticViewpoint(viewpoint)) return layoutName(viewpoint);
                throw new IllegalArgumentException("Unsupported viewpoint: " + viewpoint);
        }
    }

    static String layoutName(int viewpoint) {
        switch (viewpoint) {
            case VIEW_REAR_DUAL: return "VIEW_2D_REAR_WHEELS";
            case VIEW_REAR_LEFT:
            case VIEW_REAR_RIGHT:
                return "VIEW_2D_REAR_WHEELS";
            case VIEW_REAR_LEFT_CLAIRVOYANCE: return "VIEW_2D_REAR_LEFT";
            case VIEW_REAR_RIGHT_CLAIRVOYANCE: return "VIEW_2D_REAR_RIGHT";
            default:
                int index = viewpoint - DIAGNOSTIC_VIEW_BASE;
                if (index >= 0 && index < HORIZONTAL_LAYOUTS.length) {
                    return HORIZONTAL_LAYOUTS[index];
                }
                throw new IllegalArgumentException("Unsupported viewpoint: " + viewpoint);
        }
    }

    static int horizontalLayoutCount() {
        return HORIZONTAL_LAYOUTS.length;
    }

    static int horizontalViewpoint(int index) {
        if (index < 0 || index >= HORIZONTAL_LAYOUTS.length) {
            throw new IllegalArgumentException("Unsupported horizontal layout index: " + index);
        }
        return DIAGNOSTIC_VIEW_BASE + index;
    }

    static String horizontalLayoutName(int index) {
        return layoutName(horizontalViewpoint(index));
    }

    static boolean usesTopCameraTileCrop(int viewpoint) {
        String layout = layoutName(viewpoint);
        return "VIEW_2D_LEFT_REAR".equals(layout)
                || "VIEW_2D_RIGHT_REAR".equals(layout);
    }

    static float focusedTileStartX(int viewpoint) {
        return usesTopCameraTileCrop(viewpoint) ? NORMAL_CAMERA_TILE_START_X : 0.0f;
    }

    private static boolean isDiagnosticViewpoint(int viewpoint) {
        int index = viewpoint - DIAGNOSTIC_VIEW_BASE;
        return index >= 0 && index < HORIZONTAL_LAYOUTS.length;
    }

    private void stage(String name, String detail) {
        stageSink.accept(name, detail);
    }

    private static void applyConfig(ClassLoader loader, Context stockContext, Config config)
            throws Exception {
        config.validate();
        setStaticField(loader, "com.byd.avc.util.devicestates.CarSetting",
                "panoramaState", config.panoramaState);
        invokeStatic(loader, "com.byd.avc.BYDAutoPanoService.PanoramaDevices",
                "setPanoramaState", new Class<?>[]{int.class}, config.panoramaState);
        setStaticField(loader, "com.byd.avc.util.devicestates.CarStatus",
                "modelValue", config.modelValue);
        setStaticField(loader, "com.byd.avc.util.devicestates.CarStatus",
                "modelValueOrigin", config.modelValueOrigin);
        setStaticField(loader, "com.byd.avc.util.devicestates.CarStatus",
                "mCarSeries", config.carSeries);

        int width = (Integer) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getPanoWidth");
        int height = (Integer) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getPanoHeight");
        String carType = (String) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "getCarBodyType",
                new Class<?>[]{Context.class}, stockContext);
        String subCarType = (String) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "getSubCarType",
                new Class<?>[]{Context.class}, stockContext);
        if (width != config.panoWidth || height != config.panoHeight) {
            throw new IllegalStateException("pano dimensions changed: " + width + "x" + height);
        }
        if (!config.carType.equals(carType) || !config.subCarType.equals(subCarType)) {
            throw new IllegalStateException("vehicle config mismatch: "
                    + carType + "/" + subCarType);
        }
    }

    private static SdkConfig resolveSdkConfig(
            ClassLoader loader, Context stockContext, Config config) throws Exception {
        int resolution = resolution(config.panoramaState, config.panoHeight);
        String resolutionDir = resolutionDirectory(resolution);
        boolean mode3d = (Boolean) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "is3DMode",
                new Class<?>[]{Context.class}, stockContext);
        String resourcePath = RESOURCE_CACHE + "/model";
        String parameterPath = RESOURCE_CACHE + "/parameters";
        String panoResourcePath = RESOURCE_CACHE + "/pano";
        String commonPath = RESOURCE_CACHE + "/common";
        copyAssetDirectory(loader, stockContext,
                "resource_ts/" + config.carType + "/CarModel/" + config.subCarType,
                resourcePath);
        copyAssetDirectory(loader, stockContext,
                "resource_ts/" + config.carType + "/" + resolutionDir,
                parameterPath);
        copyAssetDirectory(loader, stockContext,
                mode3d ? "pano_3d_resources/" + config.carType : "pano_resources",
                panoResourcePath);
        copyAssetDirectory(loader, stockContext, "resource_ts/common", commonPath);

        int screenWidth = Math.round((Float) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getPadWidth"));
        int screenHeight = Math.round((Float) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getPadHeight"));
        String carBodyColor = (String) invokeStatic(loader,
                "com.byd.avc.util.CarBodyColorController", "getCurrentColor");
        int cameraType = (Integer) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getPanoCameraType");
        String supplier = (String) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getCameraSupplier");
        Map<?, ?> functionDomainSuppliers = (Map<?, ?>) getStaticField(loader,
                "com.byd.avc.autostudy.utils.CameraAutoStudyMap",
                "FUNCTION_DOMAIN_CONTROLLER_SUPPLIER_MAP");
        String cameraDirections = supplier != null && !supplier.isEmpty()
                && functionDomainSuppliers.containsValue(supplier)
                ? "rear_left_right_front" : "rear_left_front_right";

        requireDirectory("resourcePath", resourcePath);
        if (screenWidth < 320 || screenWidth > 4096
                || screenHeight < 240 || screenHeight > 4096) {
            throw new IllegalStateException("invalid screen dimensions: "
                    + screenWidth + "x" + screenHeight);
        }
        return new SdkConfig(resolution, resourcePath, parameterPath, panoResourcePath,
                commonPath, screenWidth, screenHeight,
                carBodyColor == null ? "" : carBodyColor, cameraType,
                cameraDirections, supplier == null ? "" : supplier);
    }

    private static void copyAssetDirectory(
            ClassLoader loader, Context stockContext, String assetPath, String destination)
            throws Exception {
        Object copied = invokeStatic(loader, "com.byd.avc.resources.ResourcesManager",
                "copyFilesFromAssets", new Class<?>[]{Context.class, String.class, String.class},
                stockContext, assetPath, destination);
        if (!Boolean.TRUE.equals(copied)) {
            throw new IllegalStateException("asset copy failed: " + assetPath);
        }
        requireDirectory(assetPath, destination);
        String[] files = new File(destination).list();
        if (files == null || files.length == 0) {
            throw new IllegalStateException("asset directory is empty: " + assetPath);
        }
    }

    static int resolution(int panoramaState, int panoHeight) {
        if (panoramaState == 1) return panoHeight == 1200 ? 3 : 4;
        return 2;
    }

    static String resolutionDirectory(int resolution) {
        switch (resolution) {
            case 4: return "1300P";
            case 3: return "1200P";
            case 2: return "960P";
            case 1: return "720P";
            case 0: return "480P";
            default: throw new IllegalArgumentException("unsupported resolution: " + resolution);
        }
    }

    private static void requireDirectory(String name, String path) {
        File directory = new File(path);
        if (!directory.isDirectory()) {
            throw new IllegalStateException(name + " is missing: " + path);
        }
    }

    static boolean disableSdkDebug(File configFile) throws Exception {
        String text = new String(Files.readAllBytes(configFile.toPath()),
                StandardCharsets.UTF_8);
        Matcher enabled = Pattern.compile("\\\"isDebug\\\"\\s*:\\s*true").matcher(text);
        if (!enabled.find()) {
            if (Pattern.compile("\\\"isDebug\\\"\\s*:\\s*false").matcher(text).find()) {
                return false;
            }
            throw new IllegalStateException("isDebug is missing: " + configFile);
        }
        String patched = enabled.replaceFirst("\"isDebug\": false");
        if (Pattern.compile("\\\"isDebug\\\"\\s*:\\s*true").matcher(patched).find()) {
            throw new IllegalStateException("multiple enabled isDebug values: " + configFile);
        }
        Files.write(configFile.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    static boolean patchRearClairvoyance(File configFile) throws Exception {
        String text = new String(Files.readAllBytes(configFile.toPath()),
                StandardCharsets.UTF_8);
        Pattern existing = Pattern.compile(
                "(?m)^(\\s*)\\\"VIEW_2D_REAR_(LEFT|RIGHT)\\\"\\s*:[^\\r\\n]*$");
        Matcher existingMatcher = existing.matcher(text);
        int existingCount = countMatches(existingMatcher);
        if (existingCount == 4) {
            existingMatcher.reset();
            StringBuffer migrated = new StringBuffer();
            int mappingIndex = 0;
            while (existingMatcher.find()) {
                boolean horizontal = mappingIndex++ < 2;
                String area = "LEFT".equals(existingMatcher.group(2)) ? "14" : "15";
                String trailingComma = existingMatcher.group(0).trim().endsWith(",")
                        ? "," : "";
                String replacement = existingMatcher.group(1) + "\"VIEW_2D_REAR_"
                        + existingMatcher.group(2) + "\": [" + (horizontal ? "15" : "0")
                        + ",[" + area + ",-1,-1,0,65535]]" + trailingComma;
                existingMatcher.appendReplacement(
                        migrated, Matcher.quoteReplacement(replacement));
            }
            existingMatcher.appendTail(migrated);
            String patched = migrated.toString();
            if (patched.equals(text)) return false;
            Files.write(configFile.toPath(), patched.getBytes(StandardCharsets.UTF_8));
            return true;
        }
        if (existingCount != 0) {
            throw new IllegalStateException("partial rear clairvoyance mapping");
        }

        Pattern anchor = Pattern.compile(
                "(?m)^(\\s*)\\\"VIEW_2D_RIGHT_CLAIRVOYANCE\\\"\\s*:\\s*"
                        + "\\[(7|8),([^\\r\\n]*)$");
        Matcher matcher = anchor.matcher(text);
        StringBuffer output = new StringBuffer();
        int replacements = 0;
        while (matcher.find()) {
            String indent = matcher.group(1);
            String layout = "8".equals(matcher.group(2)) ? "15" : "0";
            String replacement = matcher.group(0) + ",\n"
                    + indent + "\"VIEW_2D_REAR_LEFT\": [" + layout
                    + ",[14,-1,-1,0,65535]],\n"
                    + indent + "\"VIEW_2D_REAR_RIGHT\": [" + layout
                    + ",[15,-1,-1,0,65535]]";
            matcher.appendReplacement(output, Matcher.quoteReplacement(replacement));
            replacements++;
        }
        matcher.appendTail(output);
        if (replacements != 2) {
            throw new IllegalStateException("expected H/V clairvoyance anchors, found "
                    + replacements);
        }
        String patched = output.toString();
        if (patched.equals(text)) return false;
        Files.write(configFile.toPath(), patched.getBytes(StandardCharsets.UTF_8));
        return true;
    }

    private static int countMatches(Matcher matcher) {
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    private static void applyOrientation(Object api, boolean horizontal) throws Exception {
        ClassLoader loader = api.getClass().getClassLoader();
        Object controller = invokeStatic(loader,
                "com.ts.avm.bydsdk.AvmController", "getInstance");
        invoke(controller, "setScreenRotation", new Class<?>[]{int.class}, horizontal ? 1 : 0);
    }

    private static String orientationName(boolean horizontal) {
        return horizontal ? "horizontal=1" : "vertical=0";
    }

    private static Object getStaticField(ClassLoader loader, String className, String field)
            throws Exception {
        Field value = Class.forName(className, true, loader).getDeclaredField(field);
        value.setAccessible(true);
        return value.get(null);
    }

    private static void setStaticField(
            ClassLoader loader, String className, String field, Object newValue) throws Exception {
        Field value = Class.forName(className, true, loader).getDeclaredField(field);
        value.setAccessible(true);
        value.set(null, newValue);
    }

    private static Object invoke(Object target, String method, Class<?>[] types, Object... args)
            throws Exception {
        return target.getClass().getMethod(method, types).invoke(target, args);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int applyViewpoint(Object api, int viewpoint) throws Exception {
        ClassLoader loader = api.getClass().getClassLoader();
        Class<?> controllerType = Class.forName(
                "com.ts.avm.bydsdk.AvmController", true, loader);
        Class<?> viewType = Class.forName(
                "com.ts.avm.bydsdk.AvmController$vehicleModelView", true, loader);
        Object controller = controllerType.getMethod("getInstance").invoke(null);
        Object view = Enum.valueOf((Class) viewType, layoutName(viewpoint));
        Object result = controllerType.getMethod(
                "setLayout", viewType, boolean.class).invoke(controller, view, false);
        return ((Number) result).intValue();
    }

    private static Object invokeStatic(ClassLoader loader, String className, String method)
            throws Exception {
        return invokeStatic(loader, className, method, new Class<?>[0]);
    }

    private static Object invokeStatic(
            ClassLoader loader, String className, String method, Class<?>[] types, Object... args)
            throws Exception {
        return Class.forName(className, true, loader).getMethod(method, types).invoke(null, args);
    }

    private static Throwable terminate(Object api) {
        if (api == null) return null;
        Throwable first = null;
        for (String method : new String[]{"freeDisplay", "terminate"}) {
            try {
                invoke(api, method, new Class<?>[0]);
            } catch (Throwable error) {
                if (first == null) first = root(error);
            }
        }
        return first;
    }

    private static String cameraSdkStatus(ClassLoader loader) throws Exception {
        Object owner = invokeStatic(loader, "com.ts.avm.bydsdk.CameraSdkStatus", "getInstance");
        return String.valueOf(invoke(owner, "getStatus", new Class<?>[0]));
    }

    private static String awaitDisplayReady(ClassLoader loader) throws Exception {
        long deadline = System.nanoTime() + DISPLAY_READY_TIMEOUT_MS * 1_000_000L;
        String status;
        do {
            status = cameraSdkStatus(loader);
            if (isDisplayReadyStatus(status)) return status;
            Thread.sleep(DISPLAY_STATUS_POLL_MS);
        } while (System.nanoTime() < deadline);
        return status;
    }

    private static Surface awaitInputSurface(Object api) throws Exception {
        long deadline = System.nanoTime() + INPUT_SURFACE_READY_TIMEOUT_MS * 1_000_000L;
        Surface value = null;
        do {
            if (value == null) {
                value = (Surface) invoke(api, "getCameraSurface", new Class<?>[0]);
            }
            if (value != null && value.isValid()) return value;
            Thread.sleep(DISPLAY_STATUS_POLL_MS);
        } while (System.nanoTime() < deadline);
        return value;
    }

    static boolean isDisplayReadyStatus(String status) {
        return "Configured".equals(status) || "Started".equals(status);
    }

    private static Throwable root(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof InvocationTargetException
                || current instanceof ExceptionInInitializerError
                || current instanceof NoClassDefFoundError)) {
            current = current.getCause();
        }
        return current;
    }

    private static String summary(Throwable error) {
        Throwable root = root(error);
        String message = root.getMessage();
        return root.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    static final class StageException extends Exception {
        final String stage;

        StageException(String stage, Throwable cause) {
            super(summary(cause), cause);
            this.stage = stage;
        }
    }

    private static final class SdkConfig {
        final int resolution;
        final String resourcePath;
        final String parameterPath;
        final String panoResourcePath;
        final String commonPath;
        final int screenWidth;
        final int screenHeight;
        final String carBodyColor;
        final int cameraType;
        final String cameraDirections;
        final String cameraSupplier;

        SdkConfig(int resolution, String resourcePath, String parameterPath,
                String panoResourcePath, String commonPath, int screenWidth, int screenHeight,
                String carBodyColor, int cameraType, String cameraDirections,
                String cameraSupplier) {
            this.resolution = resolution;
            this.resourcePath = resourcePath;
            this.parameterPath = parameterPath;
            this.panoResourcePath = panoResourcePath;
            this.commonPath = commonPath;
            this.screenWidth = screenWidth;
            this.screenHeight = screenHeight;
            this.carBodyColor = carBodyColor;
            this.cameraType = cameraType;
            this.cameraDirections = cameraDirections;
            this.cameraSupplier = cameraSupplier;
        }

        String detail() {
            return "resolution=" + resolution
                    + ";resource=" + resourcePath
                    + ";parameters=" + parameterPath
                    + ";pano=" + panoResourcePath
                    + ";common=" + commonPath
                    + ";screen=" + screenWidth + "x" + screenHeight
                    + ";camera_type=" + cameraType
                    + ";supplier=" + cameraSupplier
                    + ";directions=" + cameraDirections;
        }
    }

    static final class Config {
        final int panoramaState;
        final int modelValue;
        final int modelValueOrigin;
        final int panoWidth;
        final int panoHeight;
        final String carSeries;
        final String carType;
        final String subCarType;

        Config(
                int panoramaState,
                int modelValue,
                int modelValueOrigin,
                int panoWidth,
                int panoHeight,
                String carSeries,
                String carType,
                String subCarType) {
            this.panoramaState = panoramaState;
            this.modelValue = modelValue;
            this.modelValueOrigin = modelValueOrigin;
            this.panoWidth = panoWidth;
            this.panoHeight = panoHeight;
            this.carSeries = carSeries;
            this.carType = carType;
            this.subCarType = subCarType;
            validate();
        }

        void writeToParcel(Parcel parcel) {
            parcel.writeInt(panoramaState);
            parcel.writeInt(modelValue);
            parcel.writeInt(modelValueOrigin);
            parcel.writeInt(panoWidth);
            parcel.writeInt(panoHeight);
            parcel.writeString(carSeries);
            parcel.writeString(carType);
            parcel.writeString(subCarType);
        }

        static Config readFromParcel(Parcel parcel) {
            return new Config(parcel.readInt(), parcel.readInt(), parcel.readInt(),
                    parcel.readInt(), parcel.readInt(), parcel.readString(),
                    parcel.readString(), parcel.readString());
        }

        void validate() {
            if (panoramaState != 1 && panoramaState != 4 && panoramaState != 5) {
                throw new IllegalArgumentException("unsupported panorama state: "
                        + panoramaState);
            }
            if (modelValue < 0 || modelValue > 10_000
                    || modelValueOrigin < 0 || modelValueOrigin > 10_000) {
                throw new IllegalArgumentException("invalid model values");
            }
            if (panoWidth < 320 || panoWidth > 4096 || panoHeight < 240 || panoHeight > 2160) {
                throw new IllegalArgumentException("invalid pano dimensions");
            }
            if (!"denza".equals(carSeries) && !"dynasty".equals(carSeries)
                    && !"ocean".equals(carSeries) && !"F".equals(carSeries)) {
                throw new IllegalArgumentException("unsupported car series: " + carSeries);
            }
            requireText("carType", carType);
            requireText("subCarType", subCarType);
        }

        String detail() {
            return "panorama=" + panoramaState
                    + ";model=" + modelValue
                    + ";origin=" + modelValueOrigin
                    + ";series=" + carSeries
                    + ";car=" + carType
                    + ";sub=" + subCarType
                    + ";pano=" + panoWidth + "x" + panoHeight;
        }

        private static void requireText(String name, String value) {
            if (value == null || value.isEmpty() || value.length() > 64) {
                throw new IllegalArgumentException("invalid " + name);
            }
        }
    }
}

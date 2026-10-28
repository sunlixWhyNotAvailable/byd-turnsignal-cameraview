package com.byd.extend;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.view.Surface;
import java.io.File;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.BiConsumer;
import static com.byd.extend.StockAvmPreview.*;

/** The BYD renderer contract from SL06 AutoVideo, separate from TS JSON/layout APIs. */
final class BydStockAvm implements AutoCloseable {
    static final String CLOUD_CONFIG_PATH =
            "/collect2/cota/apps/com.byd.avc/avm_config.yaml";

    private final BiConsumer<String, String> stage;
    private Object api;
    private Surface input;
    private String configuration;
    private int screenWidth;
    private int screenHeight;

    BydStockAvm(BiConsumer<String, String> stage) { this.stage = stage; }

    void open(Context context, Config config, ReverseSteeringModelConfig.Selection selection,
            Surface display, int viewpoint, boolean horizontal) throws Exception {
        nativeViewName(viewpoint, horizontal);
        ClassLoader loader = context.getClassLoader();
        String nextConfiguration = config.detail() + ";" + config.intrinsic + ";"
                + selection.resourceSuffix + ";" + selection.packageLastUpdateTime;
        if (api == null || !nextConfiguration.equals(configuration)) {
            preflightCloudConfigStat();
        }
        if (api != null && !nextConfiguration.equals(configuration)) {
            close();
            invoke(api, "terminate", new Class<?>[0]);
            awaitRenderer();
            api = null;
        }
        if (api != null) {
            attach(display, viewpoint, horizontal);
            return;
        }
        Class<?> type = Class.forName("com.byd.carvis.AvmAPI", true, loader);
        Method initialize;
        boolean explicitCommon;
        try {
            initialize = type.getMethod("initialize", String.class, int.class, int.class,
                    int.class, int.class, String.class, String.class, Context.class);
            explicitCommon = true;
        } catch (NoSuchMethodException absent) {
            initialize = type.getMethod("initialize", String.class, int.class, int.class,
                    int.class, int.class, String.class, Context.class);
            explicitCommon = false;
        }
        Method setLayoutVersion = null;
        try {
            setLayoutVersion = type.getMethod("setLayoutVerison", int.class);
        } catch (NoSuchMethodException absent) {
            // Old AutoVideo selects its Java UI layout without a native layout setter.
        }
        String root = "/data/local/tmp/bydextend_avm/byd/" + config.carType
                + selection.resourceSuffix + "/" + config.panoHeight + "/" + selection.packageLastUpdateTime;
        // Preserve the old OEM model layout, separate from the flat eight-argument cache.
        String model = explicitCommon ? root + "/model"
                : root + "/sdk7/" + config.carType + "/CarModel" + selection.resourceSuffix + "/resource";
        String parameters = root + "/parameters", pano = root + "/pano";
        String common = explicitCommon ? root + "/common"
                : new File(model, "../../../common").getCanonicalPath();
        String[] commonAssets = context.getAssets().list("resource_byd/common");
        boolean hasCommonAssets = commonAssets != null && commonAssets.length > 0;
        stage.accept("byd_sdk_contract", "initialize_args=" + (explicitCommon ? 8 : 7)
                + ";layout_method=" + (setLayoutVersion != null)
                + ";common_assets=" + hasCommonAssets);
        if (explicitCommon || hasCommonAssets)
            copyAssetDirectory(loader, context, "resource_byd/common", common);
        copyAssetDirectory(loader, context,
                "resource_byd/" + config.carType + "/CarModel" + selection.resourceSuffix, model);
        copyAssetDirectory(loader, context, "resource_byd/" + config.carType + "/"
                + parameterDirectory(config.panoramaState, config.panoHeight), parameters);
        boolean mode3d = (Boolean) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "is3DMode",
                new Class<?>[]{Context.class}, context);
        copyAssetDirectory(loader, context,
                mode3d ? "pano_3d_resources/" + config.carType : "pano_resources", pano);
        stage.accept("byd_resources_ready", "ok");
        int width = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getOriginalScreenWidth")).intValue();
        int height = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.DeviceStates$ScreenStatus", "getOriginalScreenHeight")).intValue();
        if (width < 320 || width > 4096 || height < 240 || height > 4096)
            throw new IllegalStateException("Invalid OEM display size: " + width + "x" + height);
        screenWidth = width;
        screenHeight = height;
        boolean board7325L = (Boolean) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarStatus", "isBootBoard7325L");
        String supplier = (String) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getCameraSupplier");
        Map<?, ?> suppliers = (Map<?, ?>) getStaticField(loader,
                "com.byd.avc.autostudy.utils.CameraAutoStudyMap", "FUNCTION_DOMAIN_CONTROLLER_SUPPLIER_MAP");
        boolean rearLeftRightFront = board7325L || supplier != null && !supplier.isEmpty()
                && suppliers.containsValue(supplier);
        int cameraType = ((Number) invokeStatic(loader,
                "com.byd.avc.util.devicestates.CarSetting", "getPanoCameraType")).intValue();
        invokeStatic(loader, "com.byd.avc.util.CarBodyColorController", "getCarBodyColor");
        String color = (String) invokeStatic(loader,
                "com.byd.avc.util.CarBodyColorController", "getCurrentColor");
        stage.accept("byd_before_initialize", "ok");
        api = type.getConstructor().newInstance();
        Object uiType = invokeStatic(loader, "com.byd.avc.util.devicestates.DevControler", "getUiType");
        String uiName = ((Enum<?>) uiType).name();
        int layoutVersion = "UI_TYPE_5X".equals(uiName) ? 5
                : "UI_TYPE_PLATFORM".equals(uiName) ? 7 : 0;
        if (layoutVersion != 0 && setLayoutVersion != null)
            setLayoutVersion.invoke(api, layoutVersion);
        invoke(api, "setCameraIndex", new Class<?>[]{int.class, int.class, int.class, int.class},
                0, 1, rearLeftRightFront ? 2 : 3, rearLeftRightFront ? 3 : 2);
        if (explicitCommon) {
            initialize.invoke(api, model, config.panoWidth, config.panoHeight, width, height,
                    parameters, common, context);
        } else {
            initialize.invoke(api, model, config.panoWidth, config.panoHeight, width, height,
                    parameters, context);
        }
        stage.accept("byd_sdk_initialized", "ok");
        invoke(api, "setCameraType", new Class<?>[]{int.class, int.class}, cameraType, 0);
        invoke(api, "setAutomaticCalibrationStr", new Class<?>[]{String.class}, config.intrinsic);
        invoke(api, "setCarResources", new Class<?>[]{String.class, String.class, String.class, String.class},
                config.carType, config.subCarType, color == null ? "" : color, pano);
        stage.accept("byd_initialize", "model=" + model + ";parameters=" + parameters
                + ";common=" + (hasCommonAssets ? common : "model_local")
                + ";screen=" + width + "x" + height + ";layout_version=" + layoutVersion
                + ";camera_order=" + (rearLeftRightFront ? "rear_left_right_front" : "rear_left_front_right"));
        configuration = nextConfiguration;
        attach(display, viewpoint, horizontal);
    }

    private void preflightCloudConfigStat() throws CloudConfigStatException {
        int uid = Process.myUid();
        int pid = Process.myPid();
        try {
            Os.stat(CLOUD_CONFIG_PATH);
            stage.accept("byd_cloud_config_stat", "operation=stat;path=" + CLOUD_CONFIG_PATH
                    + ";renderer=BYD;implementation=AvmAPI.initialize;uid=" + uid
                    + ";pid=" + pid + ";result=present");
        } catch (ErrnoException failure) {
            boolean optionalMissing = failure.errno == OsConstants.ENOENT;
            stage.accept("byd_cloud_config_stat", "operation=stat;path=" + CLOUD_CONFIG_PATH
                    + ";renderer=BYD;implementation=AvmAPI.initialize;uid=" + uid
                    + ";pid=" + pid + ";result=" + (optionalMissing ? "optional_missing" : "error")
                    + ";errno=" + failure.errno + ";errno_name="
                    + StockAvmShellProtocol.errnoName(failure.errno));
            if (optionalMissing) return;
            throw new CloudConfigStatException(failure);
        }
    }

    static final class CloudConfigStatException extends Exception {
        final int errno;

        CloudConfigStatException(ErrnoException cause) {
            super("BYD cloud config stat failed: path=" + CLOUD_CONFIG_PATH
                    + ";errno=" + cause.errno + ";errno_name="
                    + StockAvmShellProtocol.errnoName(cause.errno), cause);
            errno = cause.errno;
        }
    }

    private void attach(Surface display, int viewpoint, boolean horizontal) throws Exception {
        if (viewpoint != VIEW_REAR_DUAL && viewpoint != VIEW_REAR_LEFT && viewpoint != VIEW_REAR_RIGHT) {
            int[] canvas = BydAvmDisplayContract.canvas(screenWidth, screenHeight, horizontal);
            CameraGpuOutput.configureStockCanvas(display, canvas[0], canvas[1]);
            stage.accept("byd_canvas", "width=" + canvas[0] + ";height=" + canvas[1]
                    + ";orientation=" + (horizontal ? "horizontal" : "vertical"));
        }
        if (!Boolean.TRUE.equals(invoke(api, "initDisplay", new Class<?>[]{Context.class, Surface.class},
                null, display))) throw new IllegalStateException("BYD initDisplay rejected surface");
        awaitRenderer();
        stage.accept("byd_display_ready", "ok");
        setViewpoint(viewpoint, horizontal);
        long deadline = System.nanoTime() + 3_000_000_000L;
        do {
            SurfaceTexture texture = (SurfaceTexture) invoke(api, "getCameraSurfaceTexture", new Class<?>[0]);
            if (texture != null) {
                input = new Surface(texture);
                if (!input.isValid()) throw new IllegalStateException("Invalid BYD input Surface");
                stage.accept("byd_input_surface", "ready");
                return;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("BYD input SurfaceTexture unavailable");
    }

    void setViewpoint(int viewpoint, boolean horizontal) throws Exception {
        String name = nativeViewName(viewpoint, horizontal);
        int nativeView = api.getClass().getField(name).getInt(null);
        invoke(api, "setViewpoint", new Class<?>[]{int.class}, nativeView);
        stage.accept("byd_viewpoint", "requested=" + viewpoint + ";native=" + name + ":" + nativeView);
    }

    static String nativeViewName(int view, boolean horizontal) {
        if (view == VIEW_REAR_DUAL) return horizontal ? "LANDSCAPE_REARLR" : "PORTRAIT_REAR_LR";
        if (view == VIEW_REAR_LEFT) return "PORTRAIT_LEFT_REARLR";
        if (view == VIEW_REAR_RIGHT) return "PORTRAIT_RIGHT_REARLR";
        String layout = layoutName(view);
        String nativeView = BydAvmDisplayContract.nativeView(layout, horizontal);
        if (nativeView == null) throw new IllegalArgumentException("BYD layout support deferred: " + layout);
        return nativeView;
    }

    Surface input() { return input; }

    static String parameterDirectory(int panoramaState, int height) {
        if (panoramaState != 1) return "960P";
        return height == 1300 ? "1300P" : height == 1080 ? "1080P" : "1200P";
    }

    @Override public void close() throws Exception {
        if (api != null) {
            invoke(api, "freeDisplay", new Class<?>[0]);
            awaitRenderer();
        }
        // Release our producer wrapper, not the SurfaceTexture owned by the OEM renderer.
        if (input != null) { input.release(); input = null; }
    }

    private void awaitRenderer() throws Exception {
        // AvmAPI queues attach/free on its HandlerThread; Java return is not EGL completion.
        if (!(api instanceof HandlerThread)) throw new IllegalStateException("Unknown BYD render queue");
        Handler handler = new Handler(((HandlerThread) api).getLooper());
        java.util.concurrent.FutureTask<Void> barrier = new java.util.concurrent.FutureTask<>(() -> null);
        if (!handler.post(barrier)) throw new IllegalStateException("BYD render queue stopped");
        barrier.get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}

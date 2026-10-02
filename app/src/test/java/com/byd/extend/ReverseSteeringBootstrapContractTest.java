package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.NodeList;

/** Host-side boundaries; the stock APK's actual initializer still requires vehicle validation. */
public final class ReverseSteeringBootstrapContractTest {
    @Test public void oemInitializationRequiresMainLooperBeforeTouchingStockClasses() throws Exception {
        String model = source("java/com/byd/extend/ReverseSteeringModelConfig.java");
        assertBefore(model, "Looper.myLooper() != Looper.getMainLooper()", "context.createPackageContext");
        assertTrue(model.contains("throw new IllegalStateException(\"OEM steering model requires"));
        assertBefore(model, "invokeStatic(loader, CAR_STATUS, \"initBootBoard\"", "return () ->");
        assertBefore(model, "return () ->", "getAssets().open(assetPath)");
        assertFalse(model.contains("StockAvmPreview.readConfig"));
    }

    @Test public void dedicatedMainHandlerPreparesModelDespiteServiceRuntimeInjection() throws Exception {
        String controller = source("java/com/byd/extend/TurnSignalController.java");
        String sync = between(controller, "private void syncSteeringModel(",
                "private void prepareSteeringModelOnMain()");
        String prepare = between(controller, "private void prepareSteeringModelOnMain()",
                "private void reportSteeringModelFailure(");
        assertTrue(controller.contains(
                "private final Handler steeringMainHandler = new Handler(Looper.getMainLooper());"));
        assertTrue(sync.contains("steeringMainHandler.post(this::prepareSteeringModelOnMain)"));
        assertFalse(sync.contains("handler.post(this::prepareSteeringModelOnMain)"));
        assertFalse(sync.contains("ReverseSteeringModelConfig.load(context)"));
        assertBefore(prepare, "ReverseSteeringModelConfig.prepareLoad(context)", "worker.execute(");
        assertBefore(prepare, "worker.execute(", "prepared.call()");
        assertFalse(prepare.contains("requireTransact("));
        assertTrue(sync.contains("TX_CONFIGURE_REVERSE_STEERING"));
        // Follow the actual three-argument service path, not the unused main-thread overload.
        String service = source("java/com/byd/extend/CameraHelperService.java");
        String create = between(service, "private synchronized void ensureHelperCreated()",
                "private void ensureHelperStarted()");
        assertTrue(service.contains("runtimeHandler = new Handler(runtimeThread.getLooper())"));
        assertTrue(create.contains("getApplicationContext(), runtimeHandler, this::acceptHelperLine"));
        String helper = source("java/com/byd/extend/CameraHelperMain.java");
        String constructor = between(helper,
                "HelperBinder(Context context, Handler callbackHandler, Consumer<String> logSink)",
                "void startGuardRuntime()");
        assertTrue(constructor.contains(
                "context, callbackHandler, this::acceptShellEvent, this::acceptControllerEvent"));
        assertTrue(controller.contains("this.handler = handler;"));
    }

    @Test public void pendingShutdownReconnectAndFailureKeepExistingRuntimeSafe() throws Exception {
        String controller = source("java/com/byd/extend/TurnSignalController.java");
        String sync = between(controller, "private void syncSteeringModel(",
                "private void transactParkingRadarConfig(");
        assertTrue(sync.contains("if (steeringModelLoadPending) return"));
        assertTrue(sync.contains("steeringModelLoadPending = false"));
        assertTrue(sync.contains("if (stopped || value == null || helper != value || !healthy) return"));
        assertTrue(sync.contains("if (stopped) return"));
        assertTrue(sync.contains("syncSteeringModel(helper, true)"));
        assertTrue(sync.contains("catch (RejectedExecutionException ignored)"));
        assertTrue(sync.contains("Log.getStackTraceString(failure)"));
        assertFalse(sync.contains("failPendingAvas("));
        assertFalse(sync.contains("clearHelper("));
    }

    @Test public void blindRearEnableLabelUsesAllFourLocalesWithoutChangingToggleTargets()
            throws Exception {
        String screen = source("kotlin/com/byd/extend/ui/BlindParkingScreens.kt");
        assertTrue(screen.contains("strings.resource(com.byd.extend.R.string.enable_rear_cameras)"));
        assertFalse(screen.contains("Включити задні камери"));
        assertTrue(screen.contains("ToggleId.BlindRear else ToggleId.BlindFront"));
        assertTrue(screen.contains("state.rearEnabled else state.frontEnabled"));
        String[] locales = {"values", "values-uk", "values-ru", "values-zh-rCN"};
        String[] labels = {"Enable cameras", "Включити камери", "Включить камеры", "启用摄像头"};
        for (int i = 0; i < locales.length; i++) {
            NodeList strings = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(mainPath("res/" + locales[i] + "/strings.xml").toFile())
                    .getElementsByTagName("string");
            String actual = null;
            for (int j = 0; j < strings.getLength(); j++) {
                if ("enable_rear_cameras".equals(strings.item(j).getAttributes()
                        .getNamedItem("name").getNodeValue())) {
                    actual = strings.item(j).getTextContent();
                }
            }
            assertEquals(locales[i], "\"" + labels[i] + "\"", actual);
        }
    }

    private static String between(String source, String start, String end) {
        return source.substring(source.indexOf(start), source.indexOf(end, source.indexOf(start)));
    }

    private static void assertBefore(String source, String first, String second) {
        int start = source.indexOf(first);
        assertTrue("missing " + first, start >= 0);
        assertTrue(second + " must follow " + first, source.indexOf(second) > start);
    }

    private static String source(String relative) throws Exception {
        return new String(Files.readAllBytes(mainPath(relative)), StandardCharsets.UTF_8);
    }

    private static Path mainPath(String relative) {
        Path root = Files.isDirectory(Path.of("app/src/main"))
                ? Path.of("app/src/main") : Path.of("src/main");
        return root.resolve(relative);
    }
}

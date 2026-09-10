package com.byd.extend;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Focused source contracts for global key filtering and lifecycle cancellation. */
public final class WeatherAccessibilityContractTest {
    @Test
    public void serviceRequestsKeyFilteringAndKeepsWeatherMetadata() throws Exception {
        String xml = readProjectFile("app/src/main/res/xml/weather_refresh_accessibility_service.xml");
        assertTrue(xml.contains("flagRequestFilterKeyEvents"));
        assertTrue(xml.contains("android:canRequestFilterKeyEvents=\"true\""));
        assertTrue(xml.contains("com.byd.weatherdata"));

        String service = readProjectFile(
                "app/src/main/java/com/byd/extend/WeatherRefreshAccessibilityService.java");
        assertTrue(service.contains("steeringGestures.onKey("));
        assertTrue(service.contains("event.getDownTime()"));
        assertTrue(service.contains("publishCameraSteeringButtonCaptured"));
        assertTrue(service.contains("requestReverseSteeringToggle"));
        assertTrue(service.contains("requestMirrorButtonAction"));
        assertTrue(service.contains("mirrorEnabled && RearviewMirrorSettings.frontIntegrated"));
        assertTrue(service.contains("CameraButtonBindings.Action.MirrorVisibility, 1L"));
    }

    @Test
    public void captureIsCancelledOnPauseAndDestroyOnlyForCurrentOwner() throws Exception {
        String activity = readProjectFile(
                "app/src/main/java/com/byd/extend/CameraProbeActivity.java");
        int pause = activity.indexOf("protected void onPause()");
        int stop = activity.indexOf("protected void onStop()", pause);
        String pauseBody = activity.substring(pause, stop);
        int destroy = activity.indexOf("protected void onDestroy()");
        int destroyEnd = activity.indexOf("private void", destroy);
        String destroyBody = activity.substring(destroy, destroyEnd < 0 ? activity.length() : destroyEnd);
        assertTrue(pauseBody.contains("cancelReverseButtonLearningIfVisible()"));
        assertTrue(destroyBody.contains("cancelReverseButtonLearningIfVisible()"));
        assertTrue(activity.contains("reverseOwner.get() != this) return"));
        assertTrue(activity.contains("isCameraButtonLearning()"));
    }

    @Test
    public void recoveryIsBoundedAndConnectionLifecycleIsObservable() throws Exception {
        String helper = readProjectFile(
                "app/src/main/java/com/byd/extend/CameraHelperService.java");
        String recovery = helper.substring(helper.indexOf("private void recoverWeatherAccessibility"),
                helper.indexOf("private boolean applyWeatherAccessibility"));
        assertTrue(helper.contains("ACCESSIBILITY_CONNECTION_WAIT_MS = 5_000"));
        assertTrue(recovery.contains("applyWeatherAccessibility(true, false, epoch)"));
        assertTrue(recovery.contains("applyWeatherAccessibility(true, true, epoch)"));
        assertEquals(2, occurrences(recovery,
                "WeatherRefreshAccessibilityService.awaitConnection("));
        assertFalse(helper.contains("settings put secure accessibility_enabled 0"));

        String service = readProjectFile(
                "app/src/main/java/com/byd/extend/WeatherRefreshAccessibilityService.java");
        assertTrue(service.contains("static boolean isConnected()"));
        assertTrue(service.contains("static void addConnectionListener"));
        assertTrue(service.contains("public boolean onUnbind(android.content.Intent intent)"));
        String interrupt = service.substring(service.indexOf("public void onInterrupt()"),
                service.indexOf("public boolean onUnbind", service.indexOf("public void onInterrupt()")));
        assertFalse(interrupt.contains("publishConnection"));
    }

    @Test
    public void activityOwnerWinsBeforeRuntimeFallbackAndInactiveRuntimeIsNoOp() throws Exception {
        String helper = readProjectFile(
                "app/src/main/java/com/byd/extend/CameraHelperService.java");
        int route = helper.indexOf("static void requestReverseSteeringToggle(Context context)");
        int routeEnd = helper.indexOf("static void musicSettingsChanged", route);
        String routeBody = helper.substring(route, routeEnd);
        assertTrue(routeBody.indexOf("dispatchReverseSteeringToggle()")
                < routeBody.indexOf("reverseOwnerEpochSnapshot()"));
        assertTrue(routeBody.contains("reverseOwnerStillAbsent(ownerEpoch)"));

        String controller = readProjectFile(
                "app/src/main/java/com/byd/extend/ReverseCameraController.java");
        int toggle = controller.indexOf("void requestSteeringToggle(long ownerEpoch");
        int toggleEnd = controller.indexOf("static boolean hasAnyFrontIntegration", toggle);
        String toggleBody = controller.substring(toggle, toggleEnd);
        assertTrue(toggleBody.contains("activeRequestId <= 0"));
        assertTrue(toggleBody.contains("!visible"));
        assertTrue(toggleBody.contains("activeHelper == null"));
    }

    private static String readProjectFile(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        if (!Files.exists(path) && relativePath.startsWith("app/")) {
            path = Path.of(relativePath.substring("app/".length()));
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0;
                index += needle.length()) count++;
        return count;
    }
}

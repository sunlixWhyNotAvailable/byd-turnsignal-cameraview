package com.byd.extend;

import static org.junit.Assert.assertTrue;

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
        assertTrue(service.contains("steeringState.apply("));
        assertTrue(service.contains("event.getDownTime()"));
        assertTrue(service.contains("Decision.LEARNED"));
        assertTrue(service.contains("Decision.TOGGLE"));
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
        assertTrue(activity.contains("isSteeringButtonLearning()"));
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
}

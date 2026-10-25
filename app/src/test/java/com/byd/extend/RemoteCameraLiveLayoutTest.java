package com.byd.extend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class RemoteCameraLiveLayoutTest {
    @Test public void combinedPlacesExteriorLeftAndInteriorsRight() {
        RemoteCameraLiveLayout.Rect front = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.BOTH, RemoteCameraView.FRONT, 1280, 720);
        RemoteCameraLiveLayout.Rect cabin = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.BOTH, RemoteCameraView.CABIN, 1280, 720);
        RemoteCameraLiveLayout.Rect driver = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.BOTH, RemoteCameraView.DRIVER, 1280, 720);

        assertNotNull(front); assertNotNull(cabin); assertNotNull(driver);
        assertEquals(0, front.left); assertEquals(426, front.right);
        assertEquals(853, cabin.left); assertEquals(0, cabin.top);
        assertEquals(1280, cabin.right); assertEquals(360, cabin.bottom);
        assertEquals(853, driver.left); assertEquals(360, driver.top);
        assertEquals(1280, driver.right); assertEquals(720, driver.bottom);
    }

    @Test public void nativeInteriorFramesFitWithoutStretching() {
        RemoteCameraLiveLayout.Rect cabinBounds = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.BOTH, RemoteCameraView.CABIN, 1280, 720);
        RemoteCameraLiveLayout.Rect driverBounds = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.BOTH, RemoteCameraView.DRIVER, 1280, 720);
        RemoteCameraLiveLayout.Rect cabin = RemoteCameraLiveLayout.fit(cabinBounds, 1920, 1080);
        RemoteCameraLiveLayout.Rect driver = RemoteCameraLiveLayout.fit(driverBounds, 1280, 800);

        assertEquals(427, cabin.width()); assertEquals(240, cabin.height());
        assertEquals(427, driver.width()); assertEquals(266, driver.height());
        assertEquals(853, cabin.left); assertEquals(47, driver.top - 360);
    }

    @Test public void unrequestedInteriorHasNoLayoutAndSingleInteriorUsesFullFrame() {
        assertNull(RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.COMBINED, RemoteCameraView.ALLOW_CABIN,
                RemoteCameraView.DRIVER, 1280, 720));
        RemoteCameraLiveLayout.Rect driver = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.INTERIOR, RemoteCameraView.ALLOW_DRIVER,
                RemoteCameraView.DRIVER, 1280, 720);
        assertNotNull(driver);
        assertEquals(0, driver.left); assertEquals(0, driver.top);
        assertEquals(1280, driver.right); assertEquals(720, driver.bottom);
    }

    @Test public void interiorOnlyPairUsesHorizontalCells() {
        RemoteCameraLiveLayout.Rect cabin = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.INTERIOR, RemoteCameraView.BOTH, RemoteCameraView.CABIN, 1280, 720);
        RemoteCameraLiveLayout.Rect driver = RemoteCameraLiveLayout.channelBounds(
                RemoteCameraView.INTERIOR, RemoteCameraView.BOTH, RemoteCameraView.DRIVER, 1280, 720);
        assertEquals(0, cabin.left); assertEquals(640, cabin.right);
        assertEquals(640, driver.left); assertEquals(1280, driver.right);
        assertEquals(0, cabin.top); assertEquals(720, driver.bottom);
    }
}

package com.byd.extend;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

public final class PanoCameraSourceTest {
    @Test
    public void sealDmiUsesAdvertisedLowResolutionPanorama() throws Exception {
        List<String> queried = new ArrayList<>();
        PanoCameraSource source = PanoCameraSource.resolve(tag -> {
            queried.add(tag);
            return "pano_l".equals(tag) ? 2 : -1;
        });
        assertEquals(2, source.id);
        assertEquals("pano_l", source.tag);
        assertEquals(Arrays.asList("pano_h", "pano_l"), queried);
    }

    @Test
    public void existingHighResolutionSourceTakesPrecedence() throws Exception {
        List<String> queried = new ArrayList<>();
        PanoCameraSource source = PanoCameraSource.resolve(tag -> {
            queried.add(tag);
            return "pano_h".equals(tag) ? 4 : 2;
        });
        assertEquals(4, source.id);
        assertEquals("pano_h", source.tag);
        assertEquals(Arrays.asList("pano_h"), queried);
    }

    @Test
    public void missingPanoramaDoesNotTryDvrOrDms() {
        List<String> queried = new ArrayList<>();
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> PanoCameraSource.resolve(tag -> {
                    queried.add(tag);
                    return -1;
                }));
        assertTrue(error.getMessage().contains("pano_h and pano_l"));
        assertEquals(Arrays.asList("pano_h", "pano_l"), queried);
    }

    @Test
    public void unsupportedPresentIdStillReachesExistingOpenTimeGuard() throws Exception {
        PanoCameraSource source = PanoCameraSource.resolve(tag -> {
            assertEquals("pano_h", tag);
            return 10000;
        });
        assertEquals(10000, source.id);
        assertEquals("pano_h", source.tag);
    }

    @Test
    public void lookupFailureIsNotMisreportedAsMissingSource() {
        Exception error = new Exception("framework lookup failed");
        assertSame(error, assertThrows(Exception.class,
                () -> PanoCameraSource.resolve(tag -> { throw error; })));
    }

    @Test
    public void zeroIsAValidAdvertisedCameraId() throws Exception {
        assertEquals(0, PanoCameraSource.resolve(tag -> 0).id);
    }

    @Test
    public void bothPanoramaTagsUseSharedProducerRouting() {
        assertTrue(PanoCameraSource.isPanorama("pano_h"));
        assertTrue(PanoCameraSource.isPanorama("pano_l"));
        for (String tag : new String[]{null, "rear", "dms", "apa", "byd_apa", ""}) {
            assertFalse(PanoCameraSource.isPanorama(tag));
        }
    }

    @Test
    public void sealDmiLowResolutionFrontAndRightUseCorrectPhysicalChannels() {
        int[] expected = {0, 1, 2, 4, 3};
        for (int logical = 0; logical < expected.length; logical++) {
            assertEquals(expected[logical],
                    PanoCameraSource.physicalPreviewIndex("pano_l", logical));
        }
    }

    @Test
    public void otherCameraBackendsKeepTheirExistingChannelOrder() {
        for (String tag : new String[]{"pano_h", "apa", "byd_apa", "rear", null, ""}) {
            for (int logical = 0; logical <= 4; logical++) {
                assertEquals(logical, PanoCameraSource.physicalPreviewIndex(tag, logical));
            }
        }
    }

    @Test
    public void specialRawAndDetectorChannelsAreNotRemapped() {
        for (int special : new int[]{-1, 5, 4123, 4321}) {
            assertEquals(special, PanoCameraSource.physicalPreviewIndex("pano_l", special));
        }
    }
}

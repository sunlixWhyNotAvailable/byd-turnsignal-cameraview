package com.byd.extend;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AvasExteriorVolumeTest {
    @Test public void compensatesMeasuredDbInsteadOfIndicesAndNeverBoosts() {
        assertEquals(.1f, AvasExteriorVolume.compensation(-40, -20), .000001f);
        assertEquals(.501187f, AvasExteriorVolume.compensation(-26, -20), .000001f);
        assertEquals(1f, AvasExteriorVolume.compensation(-20, -40), 0f);
        assertEquals(1f, AvasExteriorVolume.compensation(-20, -20), 0f);
        assertTrue(Float.isNaN(AvasExteriorVolume.compensation(Float.NaN, -20)));
        assertTrue(Float.isNaN(AvasExteriorVolume.compensation(-40, Float.NEGATIVE_INFINITY)));
    }
}

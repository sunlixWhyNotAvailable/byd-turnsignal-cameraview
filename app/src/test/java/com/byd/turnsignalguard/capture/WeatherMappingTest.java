package com.byd.turnsignalguard.capture;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class WeatherMappingTest {
    @Test
    public void weatherCodesMapToSupportedBydIds() {
        assertEquals(0, WeatherMapping.weatherId(0));
        assertEquals(2, WeatherMapping.weatherId(3));
        assertEquals(18, WeatherMapping.weatherId(45));
        assertEquals(7, WeatherMapping.weatherId(61));
        assertEquals(19, WeatherMapping.weatherId(67));
        assertEquals(14, WeatherMapping.weatherId(71));
        assertEquals(4, WeatherMapping.weatherId(95));
        assertEquals(-1, WeatherMapping.weatherId(999));
    }

    @Test
    public void everyOpenMeteoWmoCodeHasABydCategory() {
        int[] codes = {0, 1, 2, 3, 45, 48, 51, 53, 55, 56, 57, 61, 63, 65,
                66, 67, 71, 73, 75, 77, 80, 81, 82, 85, 86, 95, 96, 99};
        for (int code : codes) assertTrue(WeatherMapping.weatherId(code) >= 0);
    }

    @Test
    public void intervalBoundsAndIncompletePayloadFailClosed() {
        assertEquals(5, WeatherMapping.clampIntervalMinutes(-1));
        assertEquals(15, WeatherMapping.clampIntervalMinutes(15));
        assertEquals(180, WeatherMapping.clampIntervalMinutes(999));
        assertFalse(WeatherMapping.isComplete(null));
        assertFalse(WeatherMapping.isComplete(""));
    }

    @Test
    public void completeForecastRequiresEightHoursAndSevenDays() {
        for (int hours = 0; hours < 8; hours++) {
            assertFalse(WeatherMapping.hasRequiredForecastCounts(hours, 7));
        }
        assertFalse(WeatherMapping.hasRequiredForecastCounts(8, 6));
        assertTrue(WeatherMapping.hasRequiredForecastCounts(8, 7));
    }

    @Test
    public void attributionIsStableAndNonEmpty() {
        assertTrue(WeatherMapping.ATTRIBUTION.contains("Open-Meteo"));
    }
}

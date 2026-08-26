package com.byd.turnsignalguard.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.time.LocalDate;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
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
    public void completeForecastRequiresEightHoursAndSixteenDays() {
        assertEquals(16, WeatherMapping.REQUIRED_DAILY_COUNT);
        assertEquals(1, WeatherMapping.CURRENT_DAY_INDEX);
        for (int hours = 0; hours < 8; hours++) {
            assertFalse(WeatherMapping.hasRequiredForecastCounts(hours, 16));
        }
        assertFalse(WeatherMapping.hasRequiredForecastCounts(8, 15));
        assertTrue(WeatherMapping.hasRequiredForecastCounts(8, 16));
    }

    @Test
    public void toBydJsonMapsExactStockForecastShape() throws Exception {
        JSONObject forecast = new JSONObject()
                .put("timezone", "Europe/Kyiv")
                .put("current", new JSONObject()
                        .put("time", "2026-08-26T12:00")
                        .put("weather_code", 1)
                        .put("temperature_2m", 23)
                        .put("relative_humidity_2m", 55)
                        .put("apparent_temperature", 23)
                        .put("pressure_msl", 1012)
                        .put("visibility", 12000)
                        .put("wind_speed_10m", 14)
                        .put("wind_direction_10m", 90)
                        .put("wind_gusts_10m", 22)
                        .put("cloud_cover", 40)
                        .put("precipitation", 0.2)
                        .put("uv_index", 5));
        JSONObject hourly = new JSONObject();
        JSONArray hourlyTimes = new JSONArray();
        JSONArray hourlyCodes = new JSONArray();
        JSONArray hourlyTemperatures = new JSONArray();
        JSONArray hourlyRain = new JSONArray();
        JSONArray hourlyDirections = new JSONArray();
        JSONArray hourlySpeeds = new JSONArray();
        JSONArray hourlyDay = new JSONArray();
        for (int i = 0; i < 8; i++) {
            hourlyTimes.put(String.format(Locale.US, "2026-08-26T%02d:00", 12 + i));
            hourlyCodes.put(1);
            hourlyTemperatures.put(23 + i);
            hourlyRain.put(i);
            hourlyDirections.put(90);
            hourlySpeeds.put(14);
            hourlyDay.put(1);
        }
        hourly.put("time", hourlyTimes)
                .put("weather_code", hourlyCodes)
                .put("temperature_2m", hourlyTemperatures)
                .put("precipitation_probability", hourlyRain)
                .put("wind_direction_10m", hourlyDirections)
                .put("wind_speed_10m", hourlySpeeds)
                .put("is_day", hourlyDay);
        forecast.put("hourly", hourly);

        JSONObject daily = new JSONObject();
        JSONArray dailyTimes = new JSONArray();
        JSONArray dailyCodes = new JSONArray();
        JSONArray dailyMax = new JSONArray();
        JSONArray dailyMin = new JSONArray();
        JSONArray dailySunrise = new JSONArray();
        JSONArray dailySunset = new JSONArray();
        JSONArray dailyUv = new JSONArray();
        JSONArray dailyWind = new JSONArray();
        JSONArray dailyDirection = new JSONArray();
        LocalDate startDate = LocalDate.of(2026, 8, 25);
        for (int i = 0; i < 16; i++) {
            LocalDate date = startDate.plusDays(i);
            dailyTimes.put(date.toString());
            dailyCodes.put(1);
            dailyMax.put(25 + i);
            dailyMin.put(15 + i);
            dailySunrise.put(date + "T05:00");
            dailySunset.put(date + "T20:00");
            dailyUv.put(5);
            dailyWind.put(14);
            dailyDirection.put(90);
        }
        daily.put("time", dailyTimes)
                .put("weather_code", dailyCodes)
                .put("temperature_2m_max", dailyMax)
                .put("temperature_2m_min", dailyMin)
                .put("sunrise", dailySunrise)
                .put("sunset", dailySunset)
                .put("uv_index_max", dailyUv)
                .put("wind_speed_10m_max", dailyWind)
                .put("wind_direction_10m_dominant", dailyDirection);
        forecast.put("daily", daily);

        JSONObject airQuality = new JSONObject().put("current", new JSONObject()
                .put("european_aqi", 42).put("pm10", 18).put("pm2_5", 9));
        String payload = WeatherMapping.toBydJson(forecast, airQuality,
                "Київ", "Kyiv", 1_756_200_000_000L);
        assertTrue(WeatherMapping.isComplete(payload));
        JSONObject root = new JSONObject(payload);
        assertEquals("0", root.getString("resultcode"));
        assertEquals("success", root.getString("resultinfo"));
        JSONObject data = root.getJSONObject("data");
        assertEquals("Київ", data.getJSONObject("city").getString("name"));
        assertEquals(8, data.getJSONObject("hourlys").getJSONArray("hourlyweathers").length());
        JSONArray days = data.getJSONObject("dailys").getJSONArray("dailyweathers");
        assertEquals(16, days.length());
        assertEquals("2026-08-25", days.getJSONObject(0).getString("publictimeFmt"));
        assertEquals("2026-09-09", days.getJSONObject(15).getString("publictimeFmt"));
        assertEquals(-1, days.getJSONObject(0).getInt("aqivalue"));
        assertEquals(42, days.getJSONObject(1).getInt("aqivalue"));
        assertEquals(-1, days.getJSONObject(2).getInt("aqivalue"));
        assertEquals(42, data.getJSONObject("aqi").getInt("aqivalue"));
        assertTrue(data.has("aqidays"));
        assertTrue(data.has("alarm"));
        assertTrue(data.has("liveInfos"));
        assertTrue(data.has("weatherDesc"));
        assertTrue(data.has("mobilelink"));
    }

    @Test
    public void toBydJsonRejectsMalformedOrIncompleteForecast() throws Exception {
        JSONObject malformed = new JSONObject()
                .put("current", "not an object")
                .put("hourly", new JSONObject())
                .put("daily", new JSONObject());
        try {
            WeatherMapping.toBydJson(malformed, null, "City", "City", 1L);
            fail("malformed forecast accepted");
        } catch (JSONException expected) {
            // Expected.
        }

        try {
            WeatherMapping.toBydJson(new JSONObject(), null, "City", "City", 1L);
            fail("incomplete forecast accepted");
        } catch (JSONException expected) {
            // Expected.
        }

        JSONObject incomplete = new JSONObject()
                .put("current", new JSONObject())
                .put("hourly", new JSONObject().put("time", new JSONArray()))
                .put("daily", new JSONObject().put("time", new JSONArray()));
        try {
            WeatherMapping.toBydJson(incomplete, null, "City", "City", 1L);
            fail("incomplete arrays accepted");
        } catch (JSONException expected) {
            // Expected.
        }
    }

    @Test
    public void attributionIsStableAndNonEmpty() {
        assertTrue(WeatherMapping.ATTRIBUTION.contains("Open-Meteo"));
    }
}

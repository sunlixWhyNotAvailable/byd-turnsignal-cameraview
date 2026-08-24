package com.byd.turnsignalguard.capture;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Locale;

/** Pure Open-Meteo to the stock BYD WeatherData JSON mapping. */
public final class WeatherMapping {
    static final int REQUIRED_HOURLY_COUNT = 8;
    static final int REQUIRED_DAILY_COUNT = 7;

    private WeatherMapping() {
    }

    public static final String ATTRIBUTION = "Weather data by Open-Meteo.com";

    public static String toBydJson(JSONObject forecast, JSONObject airQuality,
            String cityName, String englishCityName, long nowMs) throws JSONException {
        if (forecast == null || !forecast.has("current")
                || !forecast.has("hourly") || !forecast.has("daily")) {
            throw new JSONException("incomplete Open-Meteo response");
        }
        JSONObject current = forecast.getJSONObject("current");
        JSONObject hourly = forecast.getJSONObject("hourly");
        JSONObject daily = forecast.getJSONObject("daily");
        JSONArray hours = hourly.optJSONArray("time");
        JSONArray days = daily.optJSONArray("time");
        if (hours == null || days == null
                || !hasRequiredForecastCounts(hours.length(), days.length())) {
            throw new JSONException("incomplete forecast arrays");
        }
        String city = nonEmpty(cityName, "Location");
        String english = nonEmpty(englishCityName, city);
        long update = parseRequiredTime(current, "time");
        int code = weatherId(requireNumber(current, "weather_code"));
        int temperature = requireNumber(current, "temperature_2m");
        int humidity = requireNumber(current, "relative_humidity_2m");
        int pressure = requireNumber(current, "pressure_msl");
        int visibility = requireNumber(current, "visibility") / 1000;
        int windSpeed = requireNumber(current, "wind_speed_10m");
        int windDegrees = requireNumber(current, "wind_direction_10m");
        int uvIndex = requireNumber(current, "uv_index");
        if (code < 0) throw new JSONException("unsupported current weather code");
        JSONObject condition = new JSONObject()
                .put("temperature", temperature)
                .put("realfeel", requireNumber(current, "apparent_temperature"))
                .put("cnweatherid", code)
                .put("weatherid", code)
                .put("zmweatherid", code)
                .put("weathertext", weatherText(current.optInt("weather_code", -1)))
                .put("uVIndex", uvIndex)
                .put("humidity", humidity)
                .put("pressure", pressure)
                .put("visibility", visibility)
                .put("windspeed", windSpeed)
                .put("windlevel", windLevel(windSpeed))
                .put("winddegrees", windDegrees)
                .put("winddir", windDirection(windDegrees))
                .put("winddirtext", windDirection(windDegrees))
                .put("windgustspeed", requireNumber(current, "wind_gusts_10m"))
                .put("cloudCover", requireNumber(current, "cloud_cover"))
                .put("precipitation", requireDouble(current, "precipitation"))
                .put("updatetime", update)
                .put("updatetimeFmt", formatTime(update));

        JSONObject hourlyData = new JSONObject().put("expiretime", nowMs + 6 * 60 * 60 * 1000L);
        JSONArray hourlyItems = new JSONArray();
        for (int i = 0; i < REQUIRED_HOURLY_COUNT; i++) {
            long date = parseRequiredTimeAt(hours, i, "hourly time");
            int hourlyCode = weatherId(requireNumberAt(hourly, "weather_code", i));
            if (hourlyCode < 0) throw new JSONException("unsupported hourly weather code");
            hourlyItems.put(new JSONObject()
                    .put("date", date)
                    .put("temp", requireNumberAt(hourly, "temperature_2m", i))
                    .put("cnweatherid", hourlyCode)
                    .put("weatherid", hourlyCode)
                    .put("zmweatherid", hourlyCode)
                    .put("rainprobability", requireNumberAt(hourly, "precipitation_probability", i))
                    .put("wd", windDirection(requireNumberAt(hourly, "wind_direction_10m", i)))
                    .put("wp", windLevel(requireNumberAt(hourly, "wind_speed_10m", i)))
                    .put("isdaynight", requireNumberAt(hourly, "is_day", i) == 1));
        }
        hourlyData.put("hourlyweathers", hourlyItems);

        long firstDayTime = parseRequiredTimeAt(days, 0, "daily time");
        JSONObject dailyData = new JSONObject()
                .put("publictime", firstDayTime)
                .put("publictimeFmt", formatDate(firstDayTime))
                .put("expiretime", nowMs + 24 * 60 * 60 * 1000L);
        JSONArray dailyItems = new JSONArray();
        for (int i = 0; i < REQUIRED_DAILY_COUNT; i++) {
            long publicTime = parseRequiredTimeAt(days, i, "daily time");
            int dailyCode = weatherId(requireNumberAt(daily, "weather_code", i));
            if (dailyCode < 0) throw new JSONException("unsupported daily weather code");
            long sunrise = parseRequiredTimeAt(daily, "sunrise", i);
            long sunset = parseRequiredTimeAt(daily, "sunset", i);
            JSONObject dayCondition = new JSONObject()
                    .put("cnweatherid", dailyCode)
                    .put("weatherid", dailyCode)
                    .put("zmweatherid", dailyCode)
                    .put("weathertext", weatherText(requireNumberAt(daily, "weather_code", i)))
                    .put("windlevel", windLevel(requireNumberAt(daily, "wind_speed_10m_max", i)))
                    .put("windspeed", requireNumberAt(daily, "wind_speed_10m_max", i))
                    .put("winddir", windDirection(requireNumberAt(daily, "wind_direction_10m_dominant", i)));
            int dailyUv = requireNumberAt(daily, "uv_index_max", i);
            int dailyAqi = i == 0 ? airQualityValue(airQuality) : -1;
            dailyItems.put(new JSONObject()
                    .put("publictime", publicTime)
                    .put("publictimeFmt", formatDate(publicTime))
                    .put("mintemp", requireNumberAt(daily, "temperature_2m_min", i))
                    .put("maxtemp", requireNumberAt(daily, "temperature_2m_max", i))
                    .put("lv", aqiLevelNumber(dailyAqi))
                    .put("aqivalue", dailyAqi)
                    .put("aqivaluetext", dailyAqi < 0 ? "--" : String.valueOf(dailyAqi))
                    .put("uvIndex", dailyUv)
                    .put("sunRise", sunrise)
                    .put("sunRiseFmt", formatTime(sunrise))
                    .put("sunSet", sunset)
                    .put("sunSetFmt", formatTime(sunset))
                    .put("conditionDay", dayCondition)
                    .put("conditionNight", dayCondition));
        }
        dailyData.put("dailyweathers", dailyItems);

        int aqi = airQualityValue(airQuality);
        JSONObject aqiData = new JSONObject()
                .put("aqivalue", aqi)
                .put("aqivaluetext", aqi < 0 ? "--" : String.valueOf(aqi))
                .put("aqidesc", aqiDescription(aqi))
                .put("lv", aqiLevelNumber(aqi))
                .put("pm25", airQualityNumber(airQuality, "pm2_5"))
                .put("pm10", airQualityNumber(airQuality, "pm10"))
                .put("updatetime", nowMs);

        JSONObject data = new JSONObject()
                .put("city", new JSONObject()
                        .put("name", city)
                        .put("englishCityName", english)
                        .put("countryname", "")
                        .put("englishCountryName", "")
                        .put("countryCode", "")
                        .put("timezone", forecast.optString("timezone", "")))
                .put("condition", condition)
                .put("hourlys", hourlyData)
                .put("dailys", dailyData)
                .put("aqi", aqiData)
                .put("aqidays", new JSONArray())
                .put("alarm", new JSONArray())
                .put("liveInfos", new JSONArray())
                .put("weatherDesc", ATTRIBUTION)
                .put("mobilelink", "https://open-meteo.com/");
        return new JSONObject()
                .put("resultcode", "0")
                .put("resultinfo", "success")
                .put("servertime", nowMs)
                .put("data", data)
                .toString();
    }

    public static boolean isComplete(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            if (!"0".equals(root.optString("resultcode"))
                    || root.optString("resultinfo").isEmpty()
                    || root.optLong("servertime", 0L) <= 0L) return false;
            JSONObject data = root.optJSONObject("data");
            JSONObject city = data == null ? null : data.optJSONObject("city");
            JSONObject condition = data == null ? null : data.optJSONObject("condition");
            JSONObject daily = data == null ? null : data.optJSONObject("dailys");
            JSONObject hourly = data == null ? null : data.optJSONObject("hourlys");
            JSONArray days = daily == null ? null : daily.optJSONArray("dailyweathers");
            JSONArray hours = hourly == null ? null : hourly.optJSONArray("hourlyweathers");
            if (city == null || text(city, "name") == null || text(city, "englishCityName") == null
                    || condition == null || !numberPresent(condition, "temperature")
                    || !numberPresent(condition, "cnweatherid") || text(condition, "weathertext") == null
                    || !numberPresent(condition, "uVIndex") || !numberPresent(condition, "windspeed")
                    || !numberPresent(condition, "windlevel") || text(condition, "winddir") == null
                    || text(condition, "winddirtext") == null || !numberPresent(condition, "visibility")
                    || !numberPresent(condition, "humidity") || !numberPresent(condition, "pressure")
                    || !numberPresent(condition, "updatetime") || text(condition, "updatetimeFmt") == null
                    || days == null || hours == null
                    || !hasRequiredForecastCounts(hours.length(), days.length())) {
                return false;
            }
            JSONObject firstDay = days.optJSONObject(0);
            if (firstDay == null || !numberPresent(firstDay, "publictime")
                    || text(firstDay, "publictimeFmt") == null
                    || !numberPresent(firstDay, "mintemp") || !numberPresent(firstDay, "maxtemp")
                    || !numberPresent(firstDay, "lv") || !numberPresent(firstDay, "sunRise")
                    || !numberPresent(firstDay, "sunSet") || !firstDay.has("conditionDay")) return false;
            JSONObject conditionDay = firstDay.optJSONObject("conditionDay");
            if (conditionDay == null || !numberPresent(conditionDay, "cnweatherid")
                    || text(conditionDay, "weathertext") == null) return false;
            for (int i = 0; i < REQUIRED_HOURLY_COUNT; i++) {
                JSONObject item = hours.optJSONObject(i);
                if (item == null || !numberPresent(item, "date") || !numberPresent(item, "temp")
                        || !numberPresent(item, "cnweatherid")) return false;
            }
            return true;
        } catch (JSONException | RuntimeException ignored) {
            return false;
        }
    }

    public static int clampIntervalMinutes(int value) {
        return Math.max(5, Math.min(180, value));
    }

    static boolean hasRequiredForecastCounts(int hourlyCount, int dailyCount) {
        return hourlyCount >= REQUIRED_HOURLY_COUNT && dailyCount >= REQUIRED_DAILY_COUNT;
    }

    public static int weatherId(int openMeteoCode) {
        if (openMeteoCode == 0) return 0;
        if (openMeteoCode == 1 || openMeteoCode == 2) return 1;
        if (openMeteoCode == 3) return 2;
        if (openMeteoCode == 45 || openMeteoCode == 48) return 18;
        if (openMeteoCode == 56 || openMeteoCode == 57
                || openMeteoCode == 66 || openMeteoCode == 67) return 19;
        if (openMeteoCode == 51 || openMeteoCode == 61) return 7;
        if (openMeteoCode == 53 || openMeteoCode == 63) return 8;
        if (openMeteoCode == 55 || openMeteoCode == 65) return 9;
        if (openMeteoCode == 71) return 14;
        if (openMeteoCode == 73) return 15;
        if (openMeteoCode == 75) return 16;
        if (openMeteoCode == 77) return 14;
        if (openMeteoCode >= 80 && openMeteoCode <= 82) return 3;
        if (openMeteoCode >= 85 && openMeteoCode <= 86) return 13;
        if (openMeteoCode == 95) return 4;
        if (openMeteoCode == 96 || openMeteoCode == 99) return 5;
        return -1;
    }

    private static String weatherText(int code) {
        if (code == 0) return "Clear";
        if (code == 1 || code == 2) return "Cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code == 56 || code == 57 || code == 66 || code == 67) return "Freezing rain";
        if (code >= 51 && code <= 65 || code >= 80 && code <= 82) return "Rain";
        if (code >= 71 && code <= 77 || code >= 85 && code <= 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Unknown";
    }

    private static int number(JSONObject object, String key) {
        return number(object, key, 0);
    }

    private static int number(JSONObject object, String key, int fallback) {
        if (!object.has(key) || object.isNull(key)) return fallback;
        double value = object.optDouble(key, Double.NaN);
        return Double.isNaN(value) ? fallback : (int) Math.round(value);
    }

    private static int requireNumber(JSONObject object, String key) throws JSONException {
        if (object == null || !object.has(key) || object.isNull(key)) {
            throw new JSONException("missing " + key);
        }
        double value = object.optDouble(key, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JSONException("invalid " + key);
        return (int) Math.round(value);
    }

    private static double requireDouble(JSONObject object, String key) throws JSONException {
        if (object == null || !object.has(key) || object.isNull(key)) {
            throw new JSONException("missing " + key);
        }
        double value = object.optDouble(key, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value)) throw new JSONException("invalid " + key);
        return value;
    }

    private static int requireNumberAt(JSONObject object, String key, int index) throws JSONException {
        JSONArray values = object == null ? null : object.optJSONArray(key);
        if (values == null || index < 0 || index >= values.length() || values.isNull(index)) {
            throw new JSONException("missing " + key + "[" + index + "]");
        }
        double value = values.optDouble(index, Double.NaN);
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new JSONException("invalid " + key + "[" + index + "]");
        }
        return (int) Math.round(value);
    }

    private static int numberAt(JSONObject object, String key, int index, int fallback) {
        JSONArray array = object.optJSONArray(key);
        return array == null || index < 0 || index >= array.length() || array.isNull(index)
                ? fallback : (int) Math.round(array.optDouble(index, fallback));
    }

    private static String text(JSONObject object, String key) {
        String value = object.optString(key, "");
        return value.isEmpty() ? null : value;
    }

    private static boolean numberPresent(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return false;
        double value = object.optDouble(key, Double.NaN);
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return -1L;
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (RuntimeException ignored) {
        }
        try {
            return LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    private static long parseRequiredTime(JSONObject object, String key) throws JSONException {
        String value = object == null ? "" : object.optString(key, "");
        long parsed = parseTime(value);
        if (parsed <= 0L) throw new JSONException("missing " + key);
        return parsed;
    }

    private static long parseRequiredTimeAt(JSONArray values, int index, String name) throws JSONException {
        if (values == null || index < 0 || index >= values.length()) throw new JSONException("missing " + name);
        long parsed = parseTime(values.optString(index, ""));
        if (parsed <= 0L) throw new JSONException("missing " + name);
        return parsed;
    }

    private static long parseRequiredTimeAt(JSONObject object, String key, int index) throws JSONException {
        JSONArray values = object == null ? null : object.optJSONArray(key);
        return parseRequiredTimeAt(values, index, key);
    }

    private static String formatTime(long value) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(value));
    }

    private static String formatDate(long value) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(value));
    }

    private static int windLevel(int kmh) {
        if (kmh < 1) return 0;
        return Math.min(10, (kmh + 4) / 5);
    }

    private static String windDirection(int degrees) {
        String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return labels[((degrees % 360 + 360) % 360 + 22) / 45 % labels.length];
    }

    private static int airQualityValue(JSONObject airQuality) {
        if (airQuality == null) return -1;
        JSONObject current = airQuality.optJSONObject("current");
        if (current != null && current.has("european_aqi")) return number(current, "european_aqi", -1);
        return numberAt(airQuality, "european_aqi", 0, -1);
    }

    private static int airQualityNumber(JSONObject airQuality, String key) {
        if (airQuality == null) return -1;
        JSONObject current = airQuality.optJSONObject("current");
        if (current != null && current.has(key)) return number(current, key, -1);
        return numberAt(airQuality, key, 0, -1);
    }

    private static int aqiLevelNumber(int value) {
        if (value < 0) return 0;
        return value <= 20 ? 1 : value <= 40 ? 2 : value <= 60 ? 3 : value <= 80 ? 4 : value <= 100 ? 5 : 6;
    }

    private static String aqiDescription(int value) {
        return value < 0 ? "Unknown" : value <= 20 ? "Good" : value <= 40 ? "Fair"
                : value <= 60 ? "Moderate" : value <= 80 ? "Poor" : value <= 100 ? "Very poor" : "Extremely poor";
    }

}

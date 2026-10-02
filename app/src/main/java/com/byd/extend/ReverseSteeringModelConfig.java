package com.byd.extend;

import android.content.Context;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/** Active-model steering clamp bounds from the stock AVC asset configuration. */
final class ReverseSteeringModelConfig {
    private static final String STOCK_PACKAGE = "com.byd.avc";
    private static final String CAR_STATUS = "com.byd.avc.util.devicestates.CarStatus";
    private static final String DEVICE_STATES = "com.byd.avc.util.devicestates.DeviceStates";
    private static final int MAX_CONFIG_BYTES = 1_048_576;

    final float minimumDegrees;
    final float maximumDegrees;
    final String model;

    ReverseSteeringModelConfig(
            float minimumDegrees, float maximumDegrees, String model) {
        if (!ReverseSteeringShift.hasValidBounds(minimumDegrees, maximumDegrees)) {
            throw new IllegalArgumentException("invalid active-model steering bounds");
        }
        if (model == null || model.trim().isEmpty() || model.length() > 256) {
            throw new IllegalArgumentException("active model identity unavailable");
        }
        this.minimumDegrees = minimumDegrees;
        this.maximumDegrees = maximumDegrees;
        this.model = model;
    }

    /** Resolve OEM classes on the app Looper; defer asset I/O to the controller worker. */
    static Callable<ReverseSteeringModelConfig> prepareLoad(Context context) throws Exception {
        // CarStatus creates a Handler in its static initializer. A failed first load poisons it.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("OEM steering model requires the app main Looper");
        }
        Context stockContext = context.createPackageContext(STOCK_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        ClassLoader loader = stockContext.getClassLoader();
        invokeStatic(loader, DEVICE_STATES, "setAVCContext",
                new Class<?>[]{Context.class}, stockContext);
        invokeStatic(loader, CAR_STATUS, "initBootBoard", new Class<?>[0]);
        invokeStatic(loader, CAR_STATUS, "initCarBodyTypeValue",
                new Class<?>[]{Context.class}, stockContext);

        String carType = (String) invokeStatic(loader, CAR_STATUS, "getCarBodyType",
                new Class<?>[]{Context.class}, stockContext);
        String subCarType = (String) invokeStatic(loader, CAR_STATUS, "getSubCarType",
                new Class<?>[]{Context.class}, stockContext);
        requirePathPart("car type", carType);
        requirePathPart("sub-car type", subCarType);

        String series = "";
        try {
            Object value = invokeStatic(loader, CAR_STATUS, "getCarSeries", new Class<?>[0]);
            if (value instanceof String) series = (String) value;
        } catch (Exception ignored) {
            // Series is useful for diagnostics but is not part of the selected asset path.
        }
        String model = (series.isEmpty() ? "" : series + "/") + carType + "/" + subCarType;
        String assetPath = "resource_ts/" + carType + "/CarModel/" + subCarType
                + "/Vehicle_Configuration.json";
        return () -> {
            String json;
            try (InputStream input = stockContext.getAssets().open(assetPath)) {
                json = readUtf8(input);
            }
            return fromJson(model, json);
        };
    }

    static ReverseSteeringModelConfig fromJson(String model, String json) throws Exception {
        JSONObject root = new JSONObject(normalizeOemJson(json));
        JSONObject project = root.optJSONObject("PROJECT_CONFIG");
        if (project == null || !project.has("AngleMin") || !project.has("AngleMax")
                || project.isNull("AngleMin") || project.isNull("AngleMax")) {
            throw new IllegalArgumentException("explicit PROJECT_CONFIG steering bounds missing");
        }
        int minimumMilliDegrees = exactInt(project.get("AngleMin"), "AngleMin");
        int maximumMilliDegrees = exactInt(project.get("AngleMax"), "AngleMax");
        float minimum = minimumMilliDegrees / 1000.0f;
        float maximum = maximumMilliDegrees / 1000.0f;
        return new ReverseSteeringModelConfig(minimum, maximum, model);
    }

    boolean sameModel(ReverseSteeringModelConfig other) {
        return other != null && model.equals(other.model)
                && minimumDegrees == other.minimumDegrees && maximumDegrees == other.maximumDegrees;
    }

    /** Mirrors the stock VehicleConfiguration line cleanup before reading the JSON object. */
    static String normalizeOemJson(String source) {
        if (source == null) throw new IllegalArgumentException("model configuration is missing");
        StringBuilder normalized = new StringBuilder(source.length());
        boolean checkLine = false;
        for (String sourceLine : source.split("\\r?\\n")) {
            String line = sourceLine.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment).trim();
            if (checkLine && !line.contains("}") && !line.contains("]")) {
                normalized.append(',');
            }
            checkLine = false;
            if (line.endsWith("}") || line.endsWith("},")) {
                if (normalized.length() > 0 && !isCorrectEnd(normalized)) {
                    normalized.deleteCharAt(normalized.length() - 1);
                }
            } else if (!line.endsWith(",") && !line.contains("{") && !line.contains("[")) {
                checkLine = true;
            }
            normalized.append(line);
        }
        if (normalized.length() == 0) {
            throw new IllegalArgumentException("model configuration is empty");
        }
        return normalized.toString();
    }

    private static boolean isCorrectEnd(StringBuilder value) {
        return value.toString().endsWith("}") || value.toString().endsWith("]")
                || value.charAt(value.length() - 1) != ',';
    }

    private static int exactInt(Object value, String key) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " is not an integer");
        }
        Number number = (Number) value;
        double numeric = number.doubleValue();
        int result = number.intValue();
        if (!Double.isFinite(numeric) || numeric != result) {
            throw new IllegalArgumentException(key + " is not an integer");
        }
        return result;
    }

    private static void requirePathPart(String label, String value) {
        if (value == null || value.isEmpty() || value.contains("/")
                || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("active " + label + " unavailable");
        }
    }

    private static Object invokeStatic(
            ClassLoader loader, String className, String methodName,
            Class<?>[] parameterTypes, Object... values) throws Exception {
        Class<?> type = loader.loadClass(className);
        Method method = type.getMethod(methodName, parameterTypes);
        return method.invoke(null, values);
    }

    private static String readUtf8(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > MAX_CONFIG_BYTES) {
                throw new IllegalArgumentException("active model configuration is too large");
            }
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

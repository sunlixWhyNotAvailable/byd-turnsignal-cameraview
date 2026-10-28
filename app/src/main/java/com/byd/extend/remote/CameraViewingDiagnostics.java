package com.byd.extend.remote;

import android.util.Log;
import com.byd.extend.DiagnosticLogPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** Preserve the failure chain without putting access credentials into exported logs. */
public final class CameraViewingDiagnostics {
    private static volatile android.content.Context appContext;

    public static void initializeApp(android.content.Context context) {
        appContext = context.getApplicationContext();
    }
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?|wss?)://[^\\s<>\"']+");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)([\"']?(?:password|credential|privateProxyCredential|token|authkey|authorization|key|secret)[\"']?\\s*[:=]\\s*)"
            + "(?:\"[^\"]*\"|'[^']*'|[^\\s,;}]+)");
    private static final Pattern AUTH_KEY = Pattern.compile("\\btskey-[A-Za-z0-9_-]+\\b");
    private static final Pattern AUTH_HEADER = Pattern.compile("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");

    private CameraViewingDiagnostics() {}

    public static String safeText(String text, String... secrets) {
        if (text == null) return "";
        String safe = URL.matcher(text).replaceAll("[url redacted]");
        safe = AUTH_HEADER.matcher(safe).replaceAll("[authorization redacted]");
        safe = SECRET.matcher(safe).replaceAll("$1[redacted]");
        safe = AUTH_KEY.matcher(safe).replaceAll("[redacted]");
        if (secrets != null) for (String secret : secrets)
            if (secret != null && !secret.isEmpty()) safe = safe.replace(secret, "[redacted]");
        return safe;
    }

    public static String describe(Throwable failure, String... secrets) {
        StringBuilder result = new StringBuilder();
        append(result, failure, "", Collections.newSetFromMap(new IdentityHashMap<>()), secrets);
        return result.toString();
    }

    private static void append(StringBuilder result, Throwable failure, String prefix,
            Set<Throwable> seen, String[] secrets) {
        if (failure == null) return;
        result.append(prefix).append(failure.getClass().getName());
        if (!seen.add(failure)) { result.append(" [cycle]\n"); return; }
        result.append(": ").append(safeText(failure.getMessage(), secrets)).append('\n');
        for (StackTraceElement frame : failure.getStackTrace()) result.append("  at ").append(frame).append('\n');
        for (Throwable suppressed : failure.getSuppressed()) append(result, suppressed, "Suppressed: ", seen, secrets);
        append(result, failure.getCause(), "Caused by: ", seen, secrets);
    }

    public static void failure(String stage, String provider, Throwable failure, String... secrets) {
        write(stage, provider, describe(failure, secrets));
    }

    public static void detail(String stage, String provider, String detail, String... secrets) {
        write(stage, provider, safeText(detail, secrets));
    }

    public static void verbose(String stage, String provider, Supplier<String> detail, String... secrets) {
        if (!DiagnosticLogPolicy.extended()) return;
        write(stage, provider, safeText(detail.get(), secrets));
    }

    private static void write(String stage, String provider, String text) {
        String prefix = "stage=" + stage + " provider=" + provider + " ";
        if (android.os.Process.myUid() == 2000) {
            com.byd.extend.CameraViewingShellMain.logViewingEvent(stage, provider, text);
        } else {
            com.byd.extend.CameraHelperService.logViewingEvent(appContext, stage, provider, text);
        }
        List<String> parts = chunks(text);
        for (int i = 0; i < parts.size(); i++)
            Log.w("CameraViewing", prefix + "part=" + (i + 1) + "/" + parts.size() + " " + parts.get(i));
    }

    static List<String> chunks(String text) {
        List<String> parts = new ArrayList<>();
        // 768 UTF-16 units leave room for context even with four-byte UTF-8 characters.
        for (String line : text.split("\n")) {
            if (line.isEmpty()) { parts.add(""); continue; }
            for (int start = 0; start < line.length();) {
                int end = Math.min(start + 768, line.length());
                if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
                        && Character.isLowSurrogate(line.charAt(end))) end--;
                parts.add(line.substring(start, end));
                start = end;
            }
        }
        return parts;
    }
}

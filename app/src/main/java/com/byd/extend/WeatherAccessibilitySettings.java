package com.byd.extend;

import java.util.LinkedHashSet;

/** Pure helpers for the secure Accessibility service-list setting. */
public final class WeatherAccessibilitySettings {
    public static final String SERVICE_COMPONENT =
            "com.byd.extend/com.byd.extend.WeatherRefreshAccessibilityService";
    public static final String SHORT_SERVICE_COMPONENT =
            "com.byd.extend/.WeatherRefreshAccessibilityService";
    // This component belonged to Extend 0.53.0, not to the separately installed legacy app.
    static final String LEGACY_SERVICE_COMPONENT =
            "com.byd.extend/com.byd.turnsignalguard.capture.WeatherRefreshAccessibilityService";

    private WeatherAccessibilitySettings() {}

    public static boolean hasOwnService(String current) {
        if (current == null || current.isEmpty()) return false;
        for (String entry : current.split(":")) {
            entry = entry.trim();
            if (SERVICE_COMPONENT.equals(entry) || SHORT_SERVICE_COMPONENT.equals(entry)) {
                return true;
            }
        }
        return false;
    }

    static boolean hasLegacyService(String current) {
        if (current == null) return false;
        for (String entry : current.split(":")) {
            if (LEGACY_SERVICE_COMPONENT.equals(entry.trim())) return true;
        }
        return false;
    }

    /**
     * Adds or removes only {@link #SERVICE_COMPONENT} from a colon-delimited setting.
     *
     * <p>Other valid entries retain their original order and spelling. Empty or malformed
     * entries are dropped before the value is passed back to the system setting.</p>
     */
    public static String transformEnabledAccessibilityServices(
            String current, boolean enabled) {
        LinkedHashSet<String> components = new LinkedHashSet<>();
        if (current != null && !current.isEmpty()) {
            for (String entry : current.split(":", -1)) {
                entry = entry.trim();
                if (isValidComponent(entry)
                        && !SERVICE_COMPONENT.equals(entry)
                        && !SHORT_SERVICE_COMPONENT.equals(entry)
                        && !LEGACY_SERVICE_COMPONENT.equals(entry)) components.add(entry);
            }
        }
        if (enabled) components.add(SERVICE_COMPONENT);
        return String.join(":", components);
    }

    private static boolean isValidComponent(String value) {
        int slash = value.indexOf('/');
        return slash > 0 && slash == value.lastIndexOf('/')
                && validPackage(value.substring(0, slash))
                && validClass(value.substring(slash + 1));
    }

    private static boolean validPackage(String value) {
        if (value.isEmpty() || value.charAt(0) == '.' || value.charAt(value.length() - 1) == '.') {
            return false;
        }
        boolean segmentHasCharacter = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (!segmentHasCharacter) return false;
                segmentHasCharacter = false;
            } else if (asciiLetterOrDigit(c) || c == '_') {
                segmentHasCharacter = true;
            } else {
                return false;
            }
        }
        return segmentHasCharacter;
    }

    private static boolean validClass(String value) {
        if (value.isEmpty()) return false;
        int start = value.charAt(0) == '.' ? 1 : 0;
        if (start == 1 && (value.length() == 1 || value.charAt(1) == '.')) return false;
        boolean segmentHasCharacter = false;
        for (int i = start; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '.') {
                if (!segmentHasCharacter) return false;
                segmentHasCharacter = false;
            } else if (asciiLetterOrDigit(c) || c == '_' || c == '$') {
                segmentHasCharacter = true;
            } else {
                return false;
            }
        }
        return segmentHasCharacter;
    }

    private static boolean asciiLetterOrDigit(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9';
    }
}

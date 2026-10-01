package com.byd.extend;

import android.view.KeyEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Narrow policy for routing wheel media keys to a matching active MediaSession. */
final class MusicMediaKeyPolicy {
    static final int OEM_WHEEL_KEY = 353;
    static final int MAX_REPEAT_COUNT = 20;
    static final long MAX_KEY_HOLD_MS = 10 * 60 * 1_000L;
    private static final Pattern PACKAGE_NAME = Pattern.compile(
            "[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+");

    private MusicMediaKeyPolicy() {}

    static int standardKeyCode(int rawKeyCode) {
        return rawKeyCode == OEM_WHEEL_KEY ? KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE : rawKeyCode;
    }

    static boolean isMediaKey(int rawKeyCode) {
        switch (standardKeyCode(rawKeyCode)) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_CLOSE:
            case KeyEvent.KEYCODE_MEDIA_EJECT:
            case KeyEvent.KEYCODE_MEDIA_RECORD:
            case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK:
                return true;
            default:
                return false;
        }
    }

    static boolean shouldRouteInitialDown(boolean musicEnabled, boolean captureFocus,
            boolean learning, boolean cameraConsumed, int rawKeyCode, int action, int repeats) {
        return musicEnabled && captureFocus && !learning && !cameraConsumed
                && action == KeyEvent.ACTION_DOWN && repeats == 0
                && isMediaKey(rawKeyCode);
    }

    static boolean isValidCommand(String foregroundPackage, int rawKeyCode, int action,
            int repeats, long downTime, long eventTime) {
        if (!isMediaKey(rawKeyCode)
                || action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP
                || repeats < 0 || repeats > MAX_REPEAT_COUNT
                || downTime < 0 || eventTime < downTime
                || eventTime - downTime > MAX_KEY_HOLD_MS) return false;
        return action == KeyEvent.ACTION_UP && (foregroundPackage == null
                || foregroundPackage.isEmpty()) || isValidPackage(foregroundPackage);
    }

    static boolean isValidPackage(String packageName) {
        return packageName != null && packageName.length() <= 255
                && PACKAGE_NAME.matcher(packageName).matches();
    }

    static final class RoutedKeys {
        private final Map<Integer, Long> downTimes = new HashMap<>();

        boolean owns(int rawKeyCode, long downTime) {
            Long routed = downTimes.get(standardKeyCode(rawKeyCode));
            return routed != null && routed == downTime;
        }

        void begin(int rawKeyCode, long downTime) {
            downTimes.put(standardKeyCode(rawKeyCode), downTime);
        }

        boolean finish(int rawKeyCode, long downTime) {
            int keyCode = standardKeyCode(rawKeyCode);
            if (!owns(keyCode, downTime)) return false;
            downTimes.remove(keyCode);
            return true;
        }

        Map<Integer, Long> drain() {
            Map<Integer, Long> pending = new HashMap<>(downTimes);
            downTimes.clear();
            return pending;
        }

        void clear() {
            downTimes.clear();
        }
    }
}

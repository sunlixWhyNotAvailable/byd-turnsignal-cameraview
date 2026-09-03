package com.byd.extend;

import android.view.KeyEvent;

/** Pure key identity/edge rules shared by the global Accessibility key filter and tests. */
public final class ReverseSteeringButtonPolicy {
    public enum Decision {
        PASS,
        CONSUME,
        LEARNED,
        TOGGLE
    }

    /** Small synchronized state machine for one global key filter instance. */
    public static final class State {
        private boolean learning;
        private int heldKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
        private long heldDownTime = -1L;

        public synchronized void beginLearning() {
            learning = true;
            clearHeld();
        }

        public synchronized void cancel() {
            learning = false;
            clearHeld();
        }

        public synchronized boolean isLearning() {
            return learning;
        }

        /** Applies one event; persistence and action dispatch remain outside this pure policy. */
        public synchronized Decision apply(
                int action, int keyCode, int repeatCount, long downTime, int configuredKeyCode) {
            if (learning) {
                // Back is reserved for the modal's existing onDismiss/cancel path.
                if (keyCode == KeyEvent.KEYCODE_BACK) return Decision.PASS;
                if (isFirstDown(action, repeatCount)) {
                    learning = false;
                    heldKeyCode = keyCode;
                    heldDownTime = downTime;
                    return Decision.LEARNED;
                }
                return Decision.CONSUME;
            }
            if (isHeldTail(keyCode, downTime,
                    heldKeyCode, heldDownTime)) {
                if (action == KeyEvent.ACTION_UP) clearHeld();
                return Decision.CONSUME;
            }
            if (!isMappedKey(keyCode, configuredKeyCode)) return Decision.PASS;
            if (isFirstDown(action, repeatCount)) {
                heldKeyCode = keyCode;
                heldDownTime = downTime;
                return Decision.TOGGLE;
            }
            return Decision.CONSUME;
        }

        private void clearHeld() {
            heldKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
            heldDownTime = -1L;
        }
    }

    private ReverseSteeringButtonPolicy() {
    }

    public static boolean isFirstDown(int action, int repeatCount) {
        return action == KeyEvent.ACTION_DOWN && repeatCount == 0;
    }

    public static boolean isMappedKey(int keyCode, int configuredKeyCode) {
        return configuredKeyCode >= 0 && keyCode == configuredKeyCode;
    }

    /** Returns true only for the exact DOWN/UP tail of the currently held physical press. */
    public static boolean isHeldTail(int keyCode, long downTime,
            int heldKeyCode, long heldDownTime) {
        return heldKeyCode >= 0 && keyCode == heldKeyCode && downTime == heldDownTime;
    }
}

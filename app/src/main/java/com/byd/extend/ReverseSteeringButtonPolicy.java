package com.byd.extend;

import android.view.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/** Pure gesture rules shared by learning and the global Accessibility key filter. */
public final class ReverseSteeringButtonPolicy {
    public enum Decision {
        PASS,
        CONSUME,
        LEARNED,
        TOGGLE
    }

    /** Synchronized state machine for one global key filter instance. */
    public static final class State {
        private final List<OwnedCycle> ownedCycles = new ArrayList<>();

        private boolean learning;
        private int sequenceKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
        private int sequenceConfiguredKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
        private long sequenceContextToken = -1L;
        private boolean sequenceLearning;
        private boolean sequenceInvalid;
        private int pressCount;
        private boolean pressActive;
        private int activeKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
        private long activeDownTime = -1L;
        private long deadline = -1L;
        private long multiPressTimeout;
        private int confirmedKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;

        public synchronized void beginLearning() {
            learning = true;
            clearSequence();
        }

        /** Cancels actions and learning but preserves tails of cycles already consumed. */
        public synchronized void cancel() {
            learning = false;
            clearSequence();
        }

        /** Clears all state when this filter instance can no longer receive cycle tails. */
        public synchronized void reset() {
            cancel();
            ownedCycles.clear();
        }

        /** Cancels a pending action after an external binding/context change. */
        public synchronized void bindingChanged() {
            clearSequence();
        }

        public synchronized boolean isLearning() {
            return learning;
        }

        public synchronized long getDeadline() {
            return deadline;
        }

        public synchronized int getConfirmedKeyCode() {
            return confirmedKeyCode;
        }

        /**
         * Completes the inclusive first-UP to second-DOWN window. The scheduled deadline is
         * one millisecond after that window, matching HUD's integer-uptime boundary.
         */
        public synchronized Decision advanceTime(
                long eventTime, int configuredKeyCode, long contextToken) {
            if (deadline < 0L || eventTime < deadline) return Decision.PASS;
            if (configuredKeyCode != sequenceConfiguredKeyCode
                    || contextToken != sequenceContextToken) {
                clearSequence();
                return Decision.PASS;
            }
            boolean single = !sequenceInvalid && !pressActive && pressCount == 1;
            boolean learn = sequenceLearning && learning;
            int learnedKeyCode = sequenceKeyCode;
            clearSequence();
            if (!single) return Decision.PASS;
            if (learn) {
                learning = false;
                confirmedKeyCode = learnedKeyCode;
                return Decision.LEARNED;
            }
            return Decision.TOGGLE;
        }

        /** Applies one event; persistence, timers, and action dispatch remain outside the policy. */
        public synchronized Decision apply(int action, int keyCode, int repeatCount,
                long downTime, long eventTime, int flags, int configuredKeyCode,
                long contextToken, long longPressTimeout, long configuredMultiPressTimeout) {
            if (sequenceKeyCode >= 0 && (configuredKeyCode != sequenceConfiguredKeyCode
                    || contextToken != sequenceContextToken)) {
                clearSequence();
            }

            boolean owned = isOwnedCycle(keyCode, downTime);
            if (learning && keyCode == KeyEvent.KEYCODE_BACK && !owned) {
                // Back remains owned by the modal's existing dismiss/cancel path.
                return Decision.PASS;
            }

            boolean assigned = !learning
                    && isAssignedPhysicalKey(keyCode, configuredKeyCode);
            boolean candidate = learning && (sequenceKeyCode < 0
                    || isSamePhysicalButton(keyCode, sequenceKeyCode));
            if (!assigned && !candidate && !owned) {
                // Keep unrelated concurrent keys away from this learning candidate without
                // letting them alter its gesture classification.
                return learning ? Decision.CONSUME : Decision.PASS;
            }

            boolean continuingOwnedCycle = owned;
            if (action == KeyEvent.ACTION_DOWN && !owned) {
                ownedCycles.add(new OwnedCycle(keyCode, downTime));
            }

            if (!assigned && !candidate) {
                finishOwnedCycleIfNeeded(action, keyCode, downTime);
                return Decision.CONSUME;
            }

            // OEM semantic feedback is not another physical press/release. In particular, a
            // late alias UP must not end a new ordinary press or cancel its pending Single.
            if (isExplicitLongKeyCode(keyCode)) {
                finishOwnedCycleIfNeeded(action, keyCode, downTime);
                return Decision.CONSUME;
            }

            if (action == KeyEvent.ACTION_DOWN && (repeatCount != 0 || pressActive
                    || continuingOwnedCycle)) {
                if (pressActive && (flags & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    sequenceInvalid = true;
                }
                return Decision.CONSUME;
            }

            if (sequenceKeyCode < 0 && action == KeyEvent.ACTION_DOWN) {
                sequenceKeyCode = canonicalShortKeyCode(keyCode);
                sequenceConfiguredKeyCode = configuredKeyCode;
                sequenceContextToken = contextToken;
                sequenceLearning = learning;
                multiPressTimeout = Math.max(0L, configuredMultiPressTimeout);
            }

            if (action == KeyEvent.ACTION_DOWN) {
                sequenceInvalid = (flags & (KeyEvent.FLAG_CANCELED
                        | KeyEvent.FLAG_LONG_PRESS)) != 0;
                deadline = -1L;
                pressCount++;
                pressActive = true;
                activeKeyCode = keyCode;
                activeDownTime = downTime;
            } else if (action == KeyEvent.ACTION_UP) {
                boolean matchingPress = pressActive && activeKeyCode == keyCode
                        && activeDownTime == downTime;
                if (matchingPress) {
                    boolean rejected = sequenceInvalid || repeatCount > 0
                            || (flags & (KeyEvent.FLAG_CANCELED | KeyEvent.FLAG_LONG_PRESS)) != 0
                            || eventTime < activeDownTime
                            || eventTime - activeDownTime >= Math.max(0L, longPressTimeout);
                    if (rejected || pressCount == 2) {
                        // Double/Hold is complete on release; the next DOWN starts a new gesture.
                        clearSequence();
                    } else {
                        pressActive = false;
                        activeKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
                        activeDownTime = -1L;
                        deadline = eventTime + multiPressTimeout + 1L;
                    }
                }
            } else {
                clearSequence();
            }

            finishOwnedCycleIfNeeded(action, keyCode, downTime);
            return Decision.CONSUME;
        }

        /** Used while new interception is blocked so an already-consumed cycle stays well formed. */
        public synchronized boolean consumeOwnedTail(int action, int keyCode, long downTime) {
            if (!isOwnedCycle(keyCode, downTime)) return false;
            finishOwnedCycleIfNeeded(action, keyCode, downTime);
            return true;
        }

        private boolean isOwnedCycle(int keyCode, long downTime) {
            for (OwnedCycle cycle : ownedCycles) {
                if (cycle.keyCode == keyCode && cycle.downTime == downTime) return true;
            }
            return false;
        }

        private void finishOwnedCycleIfNeeded(int action, int keyCode, long downTime) {
            if (action != KeyEvent.ACTION_UP) return;
            for (int index = ownedCycles.size() - 1; index >= 0; index--) {
                OwnedCycle cycle = ownedCycles.get(index);
                if (cycle.keyCode == keyCode && cycle.downTime == downTime) {
                    ownedCycles.remove(index);
                    return;
                }
            }
        }

        private void clearSequence() {
            sequenceKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
            sequenceConfiguredKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
            sequenceContextToken = -1L;
            sequenceLearning = false;
            sequenceInvalid = false;
            pressCount = 0;
            pressActive = false;
            activeKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
            activeDownTime = -1L;
            deadline = -1L;
            multiPressTimeout = 0L;
            confirmedKeyCode = ReverseSteeringButtonPreferences.UNASSIGNED;
        }
    }

    private static final class OwnedCycle {
        private final int keyCode;
        private final long downTime;

        private OwnedCycle(int keyCode, long downTime) {
            this.keyCode = keyCode;
            this.downTime = downTime;
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

    public static boolean isAssignedPhysicalKey(int keyCode, int configuredKeyCode) {
        return configuredKeyCode >= 0
                && canonicalShortKeyCode(keyCode) == canonicalShortKeyCode(configuredKeyCode);
    }

    public static boolean isExplicitLongKeyCode(int keyCode) {
        return keyCode == 303 || keyCode == 302 || keyCode == 306 || keyCode == 352;
    }

    private static boolean isSamePhysicalButton(int firstKeyCode, int secondKeyCode) {
        return canonicalShortKeyCode(firstKeyCode) == canonicalShortKeyCode(secondKeyCode);
    }

    private static int canonicalShortKeyCode(int keyCode) {
        if (keyCode == 303) return 88;
        if (keyCode == 302) return 87;
        if (keyCode == 306) return 305;
        if (keyCode == 352) return 351;
        return keyCode;
    }
}

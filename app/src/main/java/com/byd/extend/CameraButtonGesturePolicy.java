package com.byd.extend;

import android.view.KeyEvent;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Pure classification state for global camera-button bindings. */
final class CameraButtonGesturePolicy {
    static final long TAIL_TIMEOUT_MS = 3_000L;

    static final class Assignment {
        final CameraButtonBindings.Action action;
        final CameraButtonBindings.Binding binding;
        final long contextToken;

        Assignment(CameraButtonBindings.Action action, CameraButtonBindings.Binding binding,
                long contextToken) {
            this.action = action;
            this.binding = binding;
            this.contextToken = contextToken;
        }
    }

    static final class Result {
        boolean consumed;
        CameraButtonBindings.Action learnedAction;
        int learnedKeyCode = CameraButtonBindings.UNASSIGNED;
        final EnumSet<CameraButtonBindings.Action> actions =
                EnumSet.noneOf(CameraButtonBindings.Action.class);
    }

    private final long holdTimeout;
    private final long doubleTimeout;
    private final long tailTimeout;
    private final Map<Integer, ActivePress> presses = new HashMap<>();
    private final Map<Integer, PendingSingle> pendingSingles = new HashMap<>();
    private final Map<Integer, Long> lateNativeTails = new HashMap<>();
    private CameraButtonBindings.Action learningAction;

    CameraButtonGesturePolicy(long holdTimeout, long doubleTimeout) {
        this(holdTimeout, doubleTimeout, TAIL_TIMEOUT_MS);
    }

    CameraButtonGesturePolicy(long holdTimeout, long doubleTimeout, long tailTimeout) {
        this.holdTimeout = Math.max(0L, holdTimeout);
        this.doubleTimeout = Math.max(0L, doubleTimeout);
        this.tailTimeout = Math.max(0L, tailTimeout);
    }

    void beginLearning(CameraButtonBindings.Action action) {
        cancelActions();
        learningAction = action;
    }

    boolean isLearning() {
        return learningAction != null;
    }

    CameraButtonBindings.Action learningAction() {
        return learningAction;
    }

    void cancelLearning() {
        learningAction = null;
    }

    /** Drops every possible action while retaining already-consumed physical tails. */
    void cancelActions() {
        pendingSingles.clear();
        for (ActivePress press : presses.values()) {
            press.assignments.clear();
            press.cancelled = true;
        }
    }

    void bindingsChanged(List<Assignment> current) {
        refresh(current);
    }

    void reset() {
        learningAction = null;
        presses.clear();
        pendingSingles.clear();
        lateNativeTails.clear();
    }

    Result onKey(int rawKey, int action, int repeats, boolean cancelled,
            long downTime, long eventTime, long now, List<Assignment> current) {
        Result result = new Result();
        refresh(current);
        int key = canonicalKeyCode(rawKey);
        expireLostPress(key, now);
        ActivePress press = presses.get(key);
        Long lateNativeTail = lateNativeTails.get(key);
        if (lateNativeTail != null && eventTime > lateNativeTail) {
            lateNativeTails.remove(key);
            lateNativeTail = null;
        }

        if (learningAction != null) {
            if (rawKey == KeyEvent.KEYCODE_BACK && press == null) return result;
            result.consumed = true;
            if (lateNativeTail != null) {
                if (isNativeLongAlias(rawKey)) {
                    if (action == KeyEvent.ACTION_UP) lateNativeTails.remove(key);
                    return result;
                }
                if (cancelled || action != KeyEvent.ACTION_DOWN || repeats != 0) return result;
                lateNativeTails.remove(key);
            }
            if (press != null) {
                boolean freshAfterLostTail = press.expiredTimerHold
                        && action == KeyEvent.ACTION_DOWN && repeats == 0
                        && press.downTime != downTime;
                if (freshAfterLostTail) {
                    presses.remove(key);
                    press = null;
                } else {
                    if (action == KeyEvent.ACTION_DOWN) {
                        press.lastSeen = now;
                        if (isNativeLongAlias(rawKey)) {
                            press.nativeDown = true;
                            press.nativeSeen = true;
                        } else if (press.downTime == downTime) {
                            press.ordinaryDown = true;
                        }
                    } else if (action == KeyEvent.ACTION_UP) {
                        if (isNativeLongAlias(rawKey)) {
                            press.nativeDown = false;
                        } else if (press.downTime == downTime) {
                            press.ordinaryDown = false;
                            boolean held = press.timerHeld || (eventTime >= press.downAt
                                    && eventTime - press.downAt >= holdTimeout);
                            if (held && !press.nativeSeen
                                    && hasNativeLongAlias(key)) {
                                lateNativeTails.put(key, eventTime + doubleTimeout);
                            }
                        }
                        if (!press.ordinaryDown && !press.nativeDown) presses.remove(key);
                    }
                    return result;
                }
            }
            if (!cancelled && action == KeyEvent.ACTION_DOWN && repeats == 0) {
                CameraButtonBindings.Action learned = learningAction;
                learningAction = null;
                result.learnedAction = learned;
                result.learnedKeyCode = key;
                ActivePress tail = press == null || press.downTime != downTime
                        ? new ActivePress(downTime, eventTime, now, false,
                                !isNativeLongAlias(rawKey), isNativeLongAlias(rawKey),
                                new EnumMap<>(CameraButtonBindings.Action.class))
                        : press;
                tail.cancelled = true;
                presses.put(key, tail);
            }
            return result;
        }

        EnumMap<CameraButtonBindings.Action, Assignment> assigned = assignmentsFor(key, current);
        boolean nativeAlias = isNativeLongAlias(rawKey);
        if (assigned.isEmpty() && press == null
                && pendingSingles.get(key) == null
                && !(nativeAlias && lateNativeTail != null)) return result;
        result.consumed = true;
        if (nativeAlias) {
            onNativeLong(key, action, repeats, cancelled, downTime, eventTime, now,
                    assigned, result);
            return result;
        }
        if (cancelled) {
            pendingSingles.remove(key);
            if (action == KeyEvent.ACTION_UP && press != null
                    && press.downTime == downTime) {
                press.ordinaryDown = false;
                if (!press.nativeDown) presses.remove(key);
            } else if (press != null) {
                press.cancelled = true;
                press.assignments.clear();
            }
            return result;
        }

        if (action == KeyEvent.ACTION_DOWN) {
            if (repeats != 0) {
                if (press == null) {
                    ActivePress tail = new ActivePress(downTime, eventTime, now, false,
                            true, false, new EnumMap<>(CameraButtonBindings.Action.class));
                    tail.cancelled = true;
                    presses.put(key, tail);
                }
                return result;
            }
            if (press != null && press.expiredTimerHold) {
                presses.remove(key);
                press = null;
                if (assigned.isEmpty()) return result;
            }
            if (press != null) {
                press.lastSeen = now;
                press.ordinaryDown = true;
                return result;
            }
            lateNativeTails.remove(key);
            PendingSingle first = pendingSingles.remove(key);
            boolean second = first != null && eventTime >= first.upAt
                    && eventTime - first.upAt <= doubleTimeout;
            if (first != null && !second) emit(first.assignments,
                    CameraButtonBindings.Press.Single, result);
            EnumMap<CameraButtonBindings.Action, Assignment> candidates =
                    second ? first.assignments : assigned;
            press = new ActivePress(downTime, eventTime, now, second,
                    true, false, new EnumMap<>(candidates));
            presses.put(key, press);
        } else if (action == KeyEvent.ACTION_UP && press != null && press.ordinaryDown
                && press.downTime == downTime) {
            press.ordinaryDown = false;
            if (!press.nativeDown) presses.remove(key);
            if (press.cancelled) return result;
            if (press.held) {
                if (press.timerHeld && !press.nativeSeen && hasNativeLongAlias(key)) {
                    lateNativeTails.put(key, eventTime + doubleTimeout);
                }
            } else if (eventTime >= press.downAt
                    && eventTime - press.downAt >= holdTimeout) {
                press.held = true;
                press.timerHeld = true;
                if (hasNativeLongAlias(key)) {
                    lateNativeTails.put(key, eventTime + doubleTimeout);
                }
                emit(press.assignments, CameraButtonBindings.Press.Hold, result);
            } else if (press.second) {
                emit(press.assignments, CameraButtonBindings.Press.Double, result);
            } else if (!press.assignments.isEmpty()) {
                pendingSingles.put(key, new PendingSingle(eventTime, press.assignments));
            }
        }
        return result;
    }

    Result advance(long now, List<Assignment> current) {
        Result result = new Result();
        refresh(current);
        Iterator<Map.Entry<Integer, ActivePress>> active = presses.entrySet().iterator();
        while (active.hasNext()) {
            Map.Entry<Integer, ActivePress> entry = active.next();
            ActivePress press = entry.getValue();
            if (!press.expiredTimerHold && now >= press.lastSeen + tailTimeout) {
                if (press.timerHeld && press.ordinaryDown) press.expiredTimerHold = true;
                else active.remove();
                continue;
            }
            if (!press.cancelled && !press.held && now >= press.downAt + holdTimeout) {
                press.held = true;
                press.timerHeld = true;
                emit(press.assignments, CameraButtonBindings.Press.Hold, result);
            }
        }
        Iterator<Map.Entry<Integer, PendingSingle>> pending = pendingSingles.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<Integer, PendingSingle> entry = pending.next();
            PendingSingle single = entry.getValue();
            if (now > single.upAt + doubleTimeout) {
                pending.remove();
                emit(single.assignments, CameraButtonBindings.Press.Single, result);
            }
        }
        return result;
    }

    long nextDeadline() {
        long next = Long.MAX_VALUE;
        for (ActivePress press : presses.values()) {
            if (!press.expiredTimerHold) next = Math.min(next, press.lastSeen + tailTimeout);
            if (!press.cancelled && !press.held) {
                next = Math.min(next, press.downAt + holdTimeout);
            }
        }
        for (PendingSingle single : pendingSingles.values()) {
            next = Math.min(next, single.upAt + doubleTimeout + 1L);
        }
        return next;
    }

    private void onNativeLong(int key, int action, int repeats, boolean cancelled,
            long downTime, long eventTime, long now,
            EnumMap<CameraButtonBindings.Action, Assignment> assigned, Result result) {
        Long lateUntil = lateNativeTails.get(key);
        if (lateUntil != null) {
            if (eventTime <= lateUntil) {
                if (action == KeyEvent.ACTION_UP) lateNativeTails.remove(key);
                return;
            }
            lateNativeTails.remove(key);
        }
        ActivePress press = presses.get(key);
        if (press != null && press.expiredTimerHold && action == KeyEvent.ACTION_UP) {
            presses.remove(key);
            return;
        }
        if (cancelled) {
            if (press != null && (action == KeyEvent.ACTION_DOWN || press.nativeDown)) {
                press.cancelled = true;
                press.assignments.clear();
                if (action == KeyEvent.ACTION_UP) {
                    press.nativeDown = false;
                    if (!press.ordinaryDown) presses.remove(key);
                }
            }
            return;
        }
        if (action == KeyEvent.ACTION_DOWN) {
            if (press != null) press.lastSeen = now;
            if (repeats != 0) return;
            if (press == null) {
                PendingSingle first = pendingSingles.remove(key);
                if (first != null && (eventTime < first.upAt
                        || eventTime - first.upAt > doubleTimeout)) {
                    emit(first.assignments, CameraButtonBindings.Press.Single, result);
                }
                press = new ActivePress(downTime, eventTime, now, false,
                        false, true, assigned);
                presses.put(key, press);
            } else {
                press.nativeDown = true;
            }
            press.nativeSeen = true;
            if (!press.cancelled && !press.held) {
                press.held = true;
                emit(press.assignments, CameraButtonBindings.Press.Hold, result);
            }
        } else if (action == KeyEvent.ACTION_UP && press != null && press.nativeDown) {
            press.nativeDown = false;
            if (!press.ordinaryDown) presses.remove(key);
        }
    }

    private void refresh(List<Assignment> current) {
        for (ActivePress press : presses.values()) retainCurrent(press.assignments, current);
        Iterator<PendingSingle> pending = pendingSingles.values().iterator();
        while (pending.hasNext()) {
            PendingSingle single = pending.next();
            retainCurrent(single.assignments, current);
            if (single.assignments.isEmpty()) pending.remove();
        }
    }

    private static void retainCurrent(
            EnumMap<CameraButtonBindings.Action, Assignment> values,
            List<Assignment> current) {
        Iterator<Map.Entry<CameraButtonBindings.Action, Assignment>> iterator =
                values.entrySet().iterator();
        while (iterator.hasNext()) {
            Assignment old = iterator.next().getValue();
            Assignment replacement = find(current, old.action);
            if (replacement == null || replacement.contextToken != old.contextToken
                    || !replacement.binding.equals(old.binding)) iterator.remove();
        }
    }

    private static EnumMap<CameraButtonBindings.Action, Assignment> assignmentsFor(
            int key, List<Assignment> values) {
        EnumMap<CameraButtonBindings.Action, Assignment> result =
                new EnumMap<>(CameraButtonBindings.Action.class);
        if (values == null) return result;
        for (Assignment value : values) {
            if (value != null && value.binding != null && value.binding.isAssigned()
                    && canonicalKeyCode(value.binding.keyCode) == key) {
                result.put(value.action, value);
            }
        }
        return result;
    }

    private static Assignment find(List<Assignment> values, CameraButtonBindings.Action action) {
        if (values == null) return null;
        for (Assignment value : values) {
            if (value != null && value.action == action) return value;
        }
        return null;
    }

    private static void emit(EnumMap<CameraButtonBindings.Action, Assignment> assignments,
            CameraButtonBindings.Press press, Result result) {
        for (Assignment assignment : assignments.values()) {
            if (assignment.binding.press == press) result.actions.add(assignment.action);
        }
    }

    private void expireLostPress(int key, long now) {
        ActivePress press = presses.get(key);
        if (press == null || press.expiredTimerHold || now < press.lastSeen + tailTimeout) return;
        if (press.timerHeld && press.ordinaryDown) press.expiredTimerHold = true;
        else presses.remove(key);
    }

    static int canonicalKeyCode(int keyCode) {
        if (keyCode == 303) return 88;
        if (keyCode == 302) return 87;
        if (keyCode == 306) return 305;
        if (keyCode == 312) return 304;
        return keyCode;
    }

    static boolean isNativeLongAlias(int keyCode) {
        return keyCode == 303 || keyCode == 302 || keyCode == 306 || keyCode == 312;
    }

    private static boolean hasNativeLongAlias(int keyCode) {
        return keyCode == 88 || keyCode == 87 || keyCode == 305 || keyCode == 304;
    }

    private static final class PendingSingle {
        final long upAt;
        final EnumMap<CameraButtonBindings.Action, Assignment> assignments;

        PendingSingle(long upAt,
                EnumMap<CameraButtonBindings.Action, Assignment> assignments) {
            this.upAt = upAt;
            this.assignments = new EnumMap<>(assignments);
        }
    }

    private static final class ActivePress {
        final long downTime;
        final long downAt;
        final boolean second;
        final EnumMap<CameraButtonBindings.Action, Assignment> assignments;
        long lastSeen;
        boolean ordinaryDown;
        boolean nativeDown;
        boolean held;
        boolean timerHeld;
        boolean nativeSeen;
        boolean expiredTimerHold;
        boolean cancelled;

        ActivePress(long downTime, long downAt, long lastSeen, boolean second,
                boolean ordinaryDown, boolean nativeDown,
                EnumMap<CameraButtonBindings.Action, Assignment> assignments) {
            this.downTime = downTime;
            this.downAt = downAt;
            this.lastSeen = lastSeen;
            this.second = second;
            this.ordinaryDown = ordinaryDown;
            this.nativeDown = nativeDown;
            this.assignments = assignments;
        }
    }
}

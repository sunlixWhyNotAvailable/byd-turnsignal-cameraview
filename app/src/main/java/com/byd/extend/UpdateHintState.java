package com.byd.extend;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;

/** Immutable update-hint state exchanged by coordination protocol v1. */
public final class UpdateHintState {
    public static final int PROTOCOL_VERSION = 1;
    public static final String NONE = "NONE";
    public static final String PENDING = "PENDING";
    public static final String VISIBLE = "VISIBLE";
    private static final int MAX_PREFERRED_SIZE_PERCENT = 1_000;
    private static final int MAX_PREFERRED_DIMENSION_PX = 100_000;

    public static final Comparator<UpdateHintState> DISPLAY_ORDER =
            Comparator.comparingLong((UpdateHintState state) -> state.requestedAtElapsedNanos)
                    .thenComparingInt(state -> ownerPriority(state.ownerPackage))
                    .thenComparing(state -> state.ownerPackage)
                    .thenComparing(state -> state.eventId);

    public final int protocolVersion;
    public final String ownerPackage;
    public final String processSessionId;
    public final long revision;
    public final String eventId;
    public final String phase;
    public final long requestedAtElapsedNanos;
    public final long expiresAtElapsedMs;
    public final int displayId;
    public final int preferredSizePercent;
    public final int preferredWidthPx;
    public final int preferredHeightPx;

    public UpdateHintState(
            int protocolVersion,
            String ownerPackage,
            String processSessionId,
            long revision,
            String eventId,
            String phase,
            long requestedAtElapsedNanos,
            long expiresAtElapsedMs,
            int displayId,
            int preferredSizePercent,
            int preferredWidthPx,
            int preferredHeightPx) {
        this.protocolVersion = protocolVersion;
        this.ownerPackage = safe(ownerPackage);
        this.processSessionId = safe(processSessionId);
        this.revision = revision;
        this.eventId = safe(eventId);
        this.phase = safe(phase);
        this.requestedAtElapsedNanos = requestedAtElapsedNanos;
        this.expiresAtElapsedMs = expiresAtElapsedMs;
        this.displayId = displayId;
        this.preferredSizePercent = preferredSizePercent;
        this.preferredWidthPx = preferredWidthPx;
        this.preferredHeightPx = preferredHeightPx;
    }

    public boolean isNone() {
        return NONE.equals(phase);
    }

    public boolean isActiveAt(long nowElapsedNanos, long nowElapsedMs) {
        if (!isValid() || isNone()) return false;
        if (VISIBLE.equals(phase)) return expiresAtElapsedMs > nowElapsedMs;
        return requestedAtElapsedNanos > nowElapsedNanos - 500_000_000L;
    }

    public boolean isValid() {
        if (protocolVersion != PROTOCOL_VERSION || ownerPackage.isEmpty()
                || processSessionId.isEmpty() || revision < 0) return false;
        if (NONE.equals(phase)) return true;
        if ((!PENDING.equals(phase) && !VISIBLE.equals(phase)) || eventId.isEmpty()
                || requestedAtElapsedNanos <= 0 || displayId < 0 || preferredSizePercent <= 0
                || preferredSizePercent > MAX_PREFERRED_SIZE_PERCENT
                || preferredWidthPx <= 0 || preferredWidthPx > MAX_PREFERRED_DIMENSION_PX
                || preferredHeightPx <= 0 || preferredHeightPx > MAX_PREFERRED_DIMENSION_PX) {
            return false;
        }
        return PENDING.equals(phase) ? expiresAtElapsedMs == 0 : expiresAtElapsedMs > 0;
    }

    public UpdateHintState withGeometry(int sizePercent, int widthPx, int heightPx, long newRevision) {
        return new UpdateHintState(PROTOCOL_VERSION, ownerPackage, processSessionId, newRevision,
                eventId, phase, requestedAtElapsedNanos, expiresAtElapsedMs, displayId,
                sizePercent, widthPx, heightPx);
    }

    public UpdateHintState visible(long shownElapsedMs, long newRevision) {
        return new UpdateHintState(PROTOCOL_VERSION, ownerPackage, processSessionId, newRevision,
                eventId, VISIBLE, requestedAtElapsedNanos, shownElapsedMs + 10_000L, displayId,
                preferredSizePercent, preferredWidthPx, preferredHeightPx);
    }

    public static UpdateHintState none(String ownerPackage, String sessionId, long revision) {
        return new UpdateHintState(PROTOCOL_VERSION, ownerPackage, sessionId, revision,
                "", NONE, 0, 0, 0, 0, 0, 0);
    }

    private static int ownerPriority(String ownerPackage) {
        if ("com.bydhud.app".equals(ownerPackage)) return 0;
        if ("com.byd.extend".equals(ownerPackage)) return 1;
        if ("com.bydcollector.collector".equals(ownerPackage)) return 2;
        return 3;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Fences revisions within a process session and permanently retires replaced sessions. */
    public static final class SessionRecord {
        private String sessionId;
        private long revision = -1;
        private UpdateHintState state;
        private final Set<String> retiredSessions = new HashSet<>();
        private final Set<String> retiredEventIds = new HashSet<>();

        public boolean accept(UpdateHintState candidate) {
            if (candidate == null || !candidate.isValid()
                    || retiredSessions.contains(candidate.processSessionId)) return false;
            if (sessionId != null && !sessionId.equals(candidate.processSessionId)) {
                retiredSessions.add(sessionId);
                revision = -1;
                state = null;
                retiredEventIds.clear();
            }
            if (candidate.processSessionId.equals(sessionId) && candidate.revision <= revision) {
                return false;
            }
            if (!candidate.isNone() && retiredEventIds.contains(candidate.eventId)) return false;
            if (state != null && !state.isNone() && !candidate.isNone()
                    && state.eventId.equals(candidate.eventId)) {
                if (candidate.requestedAtElapsedNanos != state.requestedAtElapsedNanos
                        || candidate.displayId != state.displayId) return false;
                if (VISIBLE.equals(state.phase) && (PENDING.equals(candidate.phase)
                        || candidate.expiresAtElapsedMs != state.expiresAtElapsedMs)) return false;
            }
            if (candidate.isNone() && state != null && !state.isNone()) {
                retiredEventIds.add(state.eventId);
            }
            sessionId = candidate.processSessionId;
            revision = candidate.revision;
            state = candidate;
            return true;
        }

        public boolean confirmDeath(String deadSessionId) {
            if (deadSessionId == null || !deadSessionId.equals(sessionId)
                    || state == null || retiredSessions.contains(deadSessionId)) return false;
            retiredSessions.add(deadSessionId);
            state = null;
            return true;
        }

        public String sessionId() {
            return sessionId;
        }

        public UpdateHintState state() {
            return state;
        }
    }
}

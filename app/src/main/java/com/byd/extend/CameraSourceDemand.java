package com.byd.extend;

/** Small, internal source-demand rules for the shared persistent camera session. */
final class CameraSourceDemand {
    private CameraSourceDemand() {}

    /** Startup is permissive; only five completed failed GETs release a confirmed OFF. */
    static final class PowerGate {
        static final class Snapshot {
            final boolean known;
            final boolean off;
            final int consecutiveFailedReads;
            final long revision;

            Snapshot(boolean known, boolean off, int consecutiveFailedReads) {
                this(known, off, consecutiveFailedReads, 0);
            }

            Snapshot(boolean known, boolean off, int consecutiveFailedReads, long revision) {
                this.known = known;
                this.off = off;
                this.consecutiveFailedReads = consecutiveFailedReads;
                this.revision = revision;
            }

            boolean sameAs(Snapshot other) {
                return other != null && known == other.known && off == other.off
                        && consecutiveFailedReads == other.consecutiveFailedReads
                        && revision == other.revision;
            }

            boolean sameDecisionAs(Snapshot other) {
                return other != null && known == other.known && off == other.off
                        && consecutiveFailedReads == other.consecutiveFailedReads;
            }
        }

        private volatile Snapshot state = new Snapshot(false, false, 0);

        boolean observe(int raw) {
            int normalized = AvasEventPolicy.normalizedPower(raw);
            if (normalized < 0) return false;
            return observeValid(normalized);
        }

        boolean observeValid(int raw) {
            int normalized = AvasEventPolicy.normalizedPower(raw);
            if (normalized < 0) return false;
            Snapshot previous = state;
            return updateDecision(previous, true, normalized == 0, 0);
        }

        boolean failedRead() {
            Snapshot previous = state;
            int failures = Math.min(5, previous.consecutiveFailedReads + 1);
            boolean known = previous.known;
            boolean off = previous.off;
            if (failures == 5) {
                known = false;
                off = false;
            }
            return updateDecision(previous, known, off, failures);
        }

        boolean update(Snapshot next) {
            if (next == null || next.consecutiveFailedReads < 0
                    || next.consecutiveFailedReads > 5 || next.revision < 0
                    || next.off && !next.known
                    || next.known && next.consecutiveFailedReads == 5)
                throw new IllegalArgumentException("invalid camera power state");
            Snapshot previous = state;
            if (next.sameAs(previous)) return false;
            state = next;
            return true;
        }

        private boolean updateDecision(Snapshot previous,
                boolean known, boolean off, int consecutiveFailedReads) {
            Snapshot next = new Snapshot(known, off, consecutiveFailedReads,
                    previous.revision + 1);
            if (next.sameDecisionAs(previous)) return false;
            state = next;
            return true;
        }

        Snapshot snapshot() { return state; }

        boolean ordinaryAllowed() { return !state.known || !state.off; }
    }

    static int bit(int sourceIndex) {
        if (sourceIndex < 0 || sourceIndex > 4) {
            throw new IllegalArgumentException("invalid camera source index " + sourceIndex);
        }
        return 1 << sourceIndex;
    }

    static int fromIndexes(int[] indexes, boolean firstSurfaceDirect) {
        if (indexes == null) return 0;
        int mask = 0;
        for (int i = 0; i < indexes.length; i++) {
            int index = indexes[i];
            if (firstSurfaceDirect && i == 0) continue;
            mask |= bit(index);
        }
        return mask;
    }

    static int reverse(int visibilityMask, boolean centralFrontIntegrated,
            boolean widgetVisible, boolean switchByGear) {
        ReverseCameraLayout.requireVisibilityMask(visibilityMask);
        int mask = 0;
        for (int index = ReverseCameraLayout.REAR_CAMERA_INDEX;
                index <= ReverseCameraLayout.REAR_RIGHT_CAMERA_INDEX; index++) {
            if (ReverseCameraLayout.isVisible(visibilityMask, index)) mask |= bit(index);
        }
        if (centralFrontIntegrated
                && ReverseCameraLayout.isVisible(
                        visibilityMask, ReverseCameraLayout.REAR_CAMERA_INDEX)
                && (widgetVisible || switchByGear)) {
            mask |= bit(4);
        }
        return mask;
    }

    static int blindStandby(boolean rearEnabled, boolean frontEnabled) {
        return rearEnabled || frontEnabled ? bit(2) | bit(3) : 0;
    }

    static int mirror(boolean visible, boolean frontIntegrated) {
        return visible ? bit(1) | (frontIntegrated ? bit(4) : 0) : 0;
    }

    static final class ReverseWarmLease {
        private long generation;
        private int requestId;
        private int producerEpoch;
        private int sourceMask;

        long arm(int request, int epoch, int demandMask) {
            if (request <= 0 || epoch <= 0) {
                throw new IllegalArgumentException("reverse warm lease identity required");
            }
            generation++;
            requestId = request;
            producerEpoch = epoch;
            sourceMask = demandMask;
            return generation;
        }

        void clear() {
            generation++;
            requestId = 0;
            producerEpoch = 0;
            sourceMask = 0;
        }

        boolean expire(long expectedGeneration, int expectedRequest, int expectedEpoch) {
            if (generation != expectedGeneration || requestId != expectedRequest
                    || producerEpoch != expectedEpoch || requestId <= 0
                    || producerEpoch <= 0) return false;
            clear();
            return true;
        }

        int sourceMask() {
            return sourceMask;
        }

        int requestId() {
            return requestId;
        }

        int producerEpoch() {
            return producerEpoch;
        }

        long generation() {
            return generation;
        }
    }
}

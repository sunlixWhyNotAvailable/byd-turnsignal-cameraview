package com.byd.extend;

/** Small, internal source-demand rules for the shared persistent camera session. */
final class CameraSourceDemand {
    private CameraSourceDemand() {}

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

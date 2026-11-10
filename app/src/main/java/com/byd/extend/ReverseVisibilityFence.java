package com.byd.extend;

import java.util.Arrays;

/** UI-thread state: a completed hide cannot be undone by an already-sent show. */
final class ReverseVisibilityFence {
    private int requestId;
    private int[] generations;
    void hide(int request, int[] surfaces) { requestId = request; generations = surfaces.clone(); }
    boolean permits(int request, int[] surfaces) {
        return requestId != request || !Arrays.equals(generations, surfaces);
    }
    void newSession() { requestId = 0; generations = null; }
}

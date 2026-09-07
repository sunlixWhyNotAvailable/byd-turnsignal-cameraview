package com.byd.extend.ui

/**
 * Process-only UI session data.  It intentionally contains no Android references, views or
 * persistence.  Scroll offsets are keyed by semantic tab/subsection/profile identity so an
 * Activity recreation can restore the measured position without retaining a view hierarchy.
 */
class RuntimeUiSession {
    private val scrollOffsets = LinkedHashMap<String, Int>()

    @Synchronized
    fun scrollOffset(key: String): Int = scrollOffsets[key]?.coerceAtLeast(0) ?: 0

    @Synchronized
    fun rememberScrollOffset(key: String, value: Int, measuredMax: Int = 0) {
        // During first composition max is zero and value is a placeholder.  Never replace a
        // remembered non-zero position with that transient zero before content is measured.
        if (measuredMax == Int.MAX_VALUE) return
        if (value == 0 && measuredMax <= 0 && scrollOffsets[key]?.let { it > 0 } == true) return
        scrollOffsets[key] = value.coerceAtLeast(0)
    }

    @Synchronized
    fun clear() { scrollOffsets.clear() }

    companion object {
        /** A restored screen may stay short until its first runtime status is available. */
        fun canRestoreScroll(measuredMax: Int): Boolean =
            measuredMax > 0 && measuredMax != Int.MAX_VALUE

        /** Shared for normal Activity recreation and reset on explicit process shutdown. */
        @JvmField
        val INSTANCE = RuntimeUiSession()

        @JvmStatic
        fun clearProcessState() { INSTANCE.clear() }
    }
}

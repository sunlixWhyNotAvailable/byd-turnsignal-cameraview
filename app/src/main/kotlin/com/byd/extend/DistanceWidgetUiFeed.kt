package com.byd.extend

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Functional Binder data, deliberately separate from diagnostic JSON/log filtering. */
object DistanceWidgetUiFeed {
    private val handler = Handler(Looper.getMainLooper())
    private val expire = Runnable {
        if (frame.valid && !frame.fresh(SystemClock.elapsedRealtime()))
            frame = DistanceFrame.unavailable(frame.generation, frame.receivedAt, "stale")
    }
    var frame by mutableStateOf(DistanceFrame.unavailable(0,0,"inactive"))
        private set
    @JvmStatic fun accept(bundle: Bundle, metadataError: java.util.function.Consumer<Exception>) {
        val next = DistanceFrameCodec.decode(bundle, metadataError)
        if (next.receivedAt < frame.receivedAt ||
            next.receivedAt == frame.receivedAt && next.generation < frame.generation) return
        frame = if (next.valid && !next.fresh(SystemClock.elapsedRealtime()))
            DistanceFrame.unavailable(next.generation, next.receivedAt, "stale_delivery") else next
        handler.removeCallbacks(expire)
        if (frame.valid) handler.postDelayed(expire,
            (next.receivedAt + DistanceFrame.STALE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    @JvmStatic fun disconnected() {
        handler.removeCallbacks(expire)
        frame = DistanceFrame.unavailable(0,0,"disconnected")
    }
}

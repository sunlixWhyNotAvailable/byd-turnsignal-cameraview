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
        frame = frame.at(SystemClock.elapsedRealtime())
    }
    var frame by mutableStateOf(DistanceFrame.unavailable(0,0,"inactive"))
        private set
    @JvmStatic fun accept(bundle: Bundle, metadataError: java.util.function.Consumer<Exception>) {
        val next = DistanceFrameCodec.decode(bundle, metadataError)
        if (next.receivedAt < frame.receivedAt ||
            next.receivedAt == frame.receivedAt && next.generation < frame.generation) return
        frame = next.at(SystemClock.elapsedRealtime())
        handler.removeCallbacks(expire)
        if (frame.adasValid) handler.postDelayed(expire,
            (frame.adasReceivedAt + DistanceFrame.STALE_MS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
    }
    @JvmStatic fun disconnected() {
        handler.removeCallbacks(expire)
        frame = DistanceFrame.unavailable(0,0,"disconnected")
    }
}

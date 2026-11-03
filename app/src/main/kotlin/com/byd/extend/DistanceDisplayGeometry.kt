package com.byd.extend

import android.content.Context
import android.util.DisplayMetrics

object DistanceDisplayGeometry {
    fun read(context: Context, target: DistanceTarget): Pair<Int, Int>? {
        val display = CameraDisplayTarget.resolve(context, target.ordinal) ?: return null
        val metrics = DisplayMetrics(); display.getRealMetrics(metrics)
        return (metrics.widthPixels to metrics.heightPixels).takeIf { it.first > 0 && it.second > 0 }
    }
    fun radius(context: Context): Float = BlindSpotOverlayController.readCornerRadius(
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)).toFloat()
}

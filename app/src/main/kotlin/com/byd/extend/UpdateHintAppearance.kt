package com.byd.extend

import android.content.Context
import android.content.SharedPreferences
import java.io.Serializable

/** Persisted preferred appearance; coordinator scaling never writes back into these values. */
data class UpdateHintAppearance @JvmOverloads constructor(
    val transparencyPercent: Int = DEFAULT_TRANSPARENCY_PERCENT,
    val cornerRadiusDp: Int = DEFAULT_CORNER_RADIUS_DP,
    val borderWidthDp: Int = DEFAULT_BORDER_WIDTH_DP,
    val borderArgb: Int = DEFAULT_BORDER_ARGB,
    val sizePercent: Int = DEFAULT_SIZE_PERCENT,
) : Serializable {
    val alpha: Float get() = 1f - transparencyPercent.coerceIn(TRANSPARENCY_RANGE) / 100f
    val scale: Float get() = sizePercent.coerceIn(SIZE_RANGE) / 100f

    fun normalized() = copy(
        transparencyPercent = transparencyPercent.coerceIn(TRANSPARENCY_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(CORNER_RANGE),
        borderWidthDp = borderWidthDp.coerceIn(BORDER_RANGE),
        borderArgb = borderArgb or 0xFF000000.toInt(),
        sizePercent = sizePercent.coerceIn(SIZE_RANGE),
    )

    fun save(preferences: SharedPreferences) {
        val value = normalized()
        preferences.edit()
            .putInt("transparency", value.transparencyPercent)
            .putInt("corner", value.cornerRadiusDp)
            .putInt("border_width", value.borderWidthDp)
            .putInt("border_color", value.borderArgb)
            .putInt("size", value.sizePercent)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "update_hint_appearance"
        const val ENABLED_PREFERENCE = "update_hint_enabled"
        const val DEFAULT_TRANSPARENCY_PERCENT = 0
        const val DEFAULT_CORNER_RADIUS_DP = 18
        const val DEFAULT_BORDER_WIDTH_DP = 1
        const val DEFAULT_BORDER_ARGB: Int = -867506 // #F2C34E
        const val DEFAULT_SIZE_PERCENT = 100

        @JvmField val TRANSPARENCY_RANGE = 0..100
        @JvmField val CORNER_RANGE = 0..40
        @JvmField val BORDER_RANGE = 0..16
        @JvmField val SIZE_RANGE = 50..150

        @JvmStatic
        fun preferences(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

        @JvmStatic
        fun sharedPrefs(context: Context): SharedPreferences = preferences(context)

        @JvmStatic
        fun defaults(): UpdateHintAppearance = UpdateHintAppearance()

        /** Production deliberately has no Preview red-to-yellow migration. */
        @JvmStatic
        fun read(preferences: SharedPreferences): UpdateHintAppearance {
            val defaults = defaults()
            return UpdateHintAppearance(
                preferences.getInt("transparency", defaults.transparencyPercent),
                preferences.getInt("corner", defaults.cornerRadiusDp),
                preferences.getInt("border_width", defaults.borderWidthDp),
                preferences.getInt("border_color", defaults.borderArgb),
                preferences.getInt("size", defaults.sizePercent),
            ).normalized()
        }
    }
}

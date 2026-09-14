package com.byd.extend

import android.content.Context
import android.content.SharedPreferences
import java.io.Serializable

/** User-owned reminder values. Runtime viewport fitting must never write scaled values here. */
data class AdbReminderAppearance @JvmOverloads constructor(
    val widgetEnabled: Boolean = true,
    val delaySeconds: Int = 5,
    val opacityPercent: Int = 90,
    val borderEnabled: Boolean = true,
    val borderArgb: Int = DEFAULT_COLOR,
    val borderThicknessDp: Int = 2,
    val widthPercent: Int = 60,
    val cornerRadiusDp: Int = 14,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
) : Serializable {
    val alpha: Float get() = opacityPercent.coerceIn(OPACITY_RANGE) / 100f

    fun normalized() = copy(
        delaySeconds = delaySeconds.coerceIn(DELAY_RANGE),
        opacityPercent = opacityPercent.coerceIn(OPACITY_RANGE),
        borderArgb = borderArgb or 0xFF000000.toInt(),
        borderThicknessDp = borderThicknessDp.coerceIn(THICKNESS_RANGE),
        widthPercent = widthPercent.coerceIn(WIDTH_RANGE),
        cornerRadiusDp = cornerRadiusDp.coerceIn(RADIUS_RANGE),
        x = x.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
        y = y.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0.5f,
    )

    companion object {
        const val DEFAULT_COLOR: Int = 0xFFF2C34E.toInt()
        @JvmField val DELAY_RANGE = 0..120
        @JvmField val OPACITY_RANGE = 0..100
        @JvmField val THICKNESS_RANGE = 1..12
        @JvmField val WIDTH_RANGE = 30..95
        @JvmField val RADIUS_RANGE = 0..48
    }
}

/** Single preference contract shared by the UI, recovery owner and overlay. */
class AdbReminderSettings {
    private val preferences: SharedPreferences

    constructor(context: Context) {
        preferences = context.applicationContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    internal constructor(preferences: SharedPreferences) {
        this.preferences = preferences
    }

    fun recoveryEnabled(): Boolean = preferences.getBoolean(PREF_RECOVERY_ENABLED, true)

    fun setRecoveryEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_RECOVERY_ENABLED, enabled).apply()
    }

    fun setReminderEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(PREF_REMINDER_ENABLED, enabled).apply()
    }

    fun readAppearance(): AdbReminderAppearance = AdbReminderAppearance(
        widgetEnabled = preferences.getBoolean(PREF_REMINDER_ENABLED, true),
        delaySeconds = preferences.getInt(PREF_DELAY_SECONDS, 5),
        opacityPercent = preferences.getInt(PREF_OPACITY_PERCENT, 90),
        borderEnabled = preferences.getBoolean(PREF_BORDER_ENABLED, true),
        borderArgb = preferences.getInt(PREF_BORDER_ARGB, AdbReminderAppearance.DEFAULT_COLOR),
        borderThicknessDp = preferences.getInt(PREF_BORDER_THICKNESS_DP, 2),
        widthPercent = preferences.getInt(PREF_WIDTH_PERCENT, 60),
        cornerRadiusDp = preferences.getInt(PREF_CORNER_RADIUS_DP, 14),
        x = preferences.getFloat(PREF_X, 0.5f),
        y = preferences.getFloat(PREF_Y, 0.5f),
    ).normalized()

    fun saveAppearance(appearance: AdbReminderAppearance) {
        val value = appearance.normalized()
        preferences.edit()
            .putBoolean(PREF_REMINDER_ENABLED, value.widgetEnabled)
            .putInt(PREF_DELAY_SECONDS, value.delaySeconds)
            .putInt(PREF_OPACITY_PERCENT, value.opacityPercent)
            .putBoolean(PREF_BORDER_ENABLED, value.borderEnabled)
            .putInt(PREF_BORDER_ARGB, value.borderArgb)
            .putInt(PREF_BORDER_THICKNESS_DP, value.borderThicknessDp)
            .putInt(PREF_WIDTH_PERCENT, value.widthPercent)
            .putInt(PREF_CORNER_RADIUS_DP, value.cornerRadiusDp)
            .putFloat(PREF_X, value.x)
            .putFloat(PREF_Y, value.y)
            .apply()
    }

    companion object {
        const val PREFERENCES_NAME = "settings"
        const val PREF_RECOVERY_ENABLED = "adb_recovery_enabled"
        const val PREF_REMINDER_ENABLED = "adb_reminder_enabled"
        const val PREF_DELAY_SECONDS = "adb_reminder_delay"
        const val PREF_OPACITY_PERCENT = "adb_reminder_opacity"
        const val PREF_BORDER_ENABLED = "adb_reminder_border"
        const val PREF_BORDER_ARGB = "adb_reminder_color"
        const val PREF_BORDER_THICKNESS_DP = "adb_reminder_thickness"
        const val PREF_WIDTH_PERCENT = "adb_reminder_size"
        const val PREF_CORNER_RADIUS_DP = "adb_reminder_radius"
        const val PREF_X = "adb_reminder_x"
        const val PREF_Y = "adb_reminder_y"

        @JvmStatic fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }
}

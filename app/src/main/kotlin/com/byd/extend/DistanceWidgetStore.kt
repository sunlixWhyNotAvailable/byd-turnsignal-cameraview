package com.byd.extend

import android.content.Context
import android.content.SharedPreferences

/** Uses the existing app/helper preference replica and configuration export. */
object DistanceWidgetStore {
    const val CONFIG = "distance_widget_config"
    const val ENABLED = "distance_widget_enabled"
    const val HIDDEN = "distance_widget_hidden"
    const val PRESET = "distance_widget_preset"
    /** Visibility-button preferences: never configuration; older files carrying them still import. */
    @JvmField val BUTTON_KEYS: Set<String> = setOf("distance_widget_key_code", "distance_widget_press",
        "distance_widget_source", "distance_widget_press_android")
    @JvmStatic fun transferKeys(): Set<String> = setOf(CONFIG, ENABLED, PRESET)

    @JvmStatic fun exportValues(preferences: SharedPreferences): Map<String, Any> = buildMap {
        val settings = read(preferences)
        put(CONFIG, settings.copy(hiddenByButton = false).encode())
        put(ENABLED, settings.enabled)
        // Re-encoding validates the stored preset and drops an older button copy.
        preferences.getString(PRESET, null)?.let { put(PRESET, DistanceWidgetSettings.decode(it).encode()) }
    }
    @JvmStatic fun validateTransfer(key: String, value: Any) {
        require(key in transferKeys())
        if (key == ENABLED) require(value is Boolean) else DistanceWidgetSettings.decode(value as String)
    }

    @JvmStatic fun read(preferences: SharedPreferences): DistanceWidgetSettings {
        val value = preferences.getString(CONFIG, null)?.let(DistanceWidgetSettings::decode) ?: DistanceWidgetSettings()
        return value.copy(enabled = preferences.getBoolean(ENABLED, false),
            hiddenByButton = preferences.getBoolean(HIDDEN, false))
    }

    @JvmStatic fun save(context: Context, value: DistanceWidgetSettings) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val enabledBefore = preferences.getBoolean(ENABLED, false)
        // Manual visibility belongs to the helper button action, never a stale editor snapshot.
        preferences.edit().putString(CONFIG, value.copy(hiddenByButton = false).encode())
            .putBoolean(ENABLED, value.enabled).apply()
        if (value.enabled && !enabledBefore) CameraHelperService.startPersistent(context, "distance_enabled")
    }

    @JvmStatic fun savePreset(preferences: SharedPreferences) {
        preferences.edit().putString(PRESET, read(preferences).copy(enabled = false, hiddenByButton = false).encode()).apply()
    }

    /** Reset/load change widget settings only; the button keeps its own reset like other tabs. */
    @JvmStatic fun applyPreset(context: Context, reset: Boolean) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val next = if (reset) DistanceWidgetSettings() else
            DistanceWidgetSettings.decode(requireNotNull(preferences.getString(PRESET, null)))
        save(context, read(preferences).applyPreset(next))
    }
}

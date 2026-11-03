package com.byd.extend

import android.content.Context
import android.content.SharedPreferences

/** Uses the existing app/helper preference replica and configuration export. */
object DistanceWidgetStore {
    const val CONFIG = "distance_widget_config"
    const val ENABLED = "distance_widget_enabled"
    const val HIDDEN = "distance_widget_hidden"
    const val PRESET = "distance_widget_preset"
    @JvmStatic fun transferKeys(): Set<String> = setOf(CONFIG, ENABLED, PRESET,
        "distance_widget_key_code", "distance_widget_press", "distance_widget_source", "distance_widget_press_android")

    @JvmStatic fun exportValues(preferences: SharedPreferences): Map<String, Any> = buildMap {
        val settings = read(preferences)
        put(CONFIG, settings.copy(hiddenByButton = false).encode())
        put(ENABLED, settings.enabled)
        put("distance_widget_key_code", settings.binding.keyCode)
        put("distance_widget_press", settings.binding.press.name)
        put("distance_widget_press_android", settings.binding.press.name)
        put("distance_widget_source", CameraButtonBindings.Source.AndroidKey.name)
        preferences.getString(PRESET, null)?.let { validateTransfer(PRESET, it); put(PRESET, it) }
    }
    @JvmStatic fun validateTransfer(key: String, value: Any) {
        require(key in transferKeys())
        when (key) {
            CONFIG, PRESET -> DistanceWidgetSettings.decode(value as String)
            ENABLED -> require(value is Boolean)
            "distance_widget_key_code" -> require(value is Int && value >= -1)
            "distance_widget_source" -> require(value == CameraButtonBindings.Source.AndroidKey.name)
            else -> DistancePress.valueOf(value as String)
        }
    }

    @JvmStatic fun read(preferences: SharedPreferences): DistanceWidgetSettings {
        val value = preferences.getString(CONFIG, null)?.let(DistanceWidgetSettings::decode) ?: DistanceWidgetSettings()
        val binding = CameraButtonBindings.load(preferences, CameraButtonBindings.Action.DistanceVisibility)
        return value.copy(enabled = preferences.getBoolean(ENABLED, false),
            hiddenByButton = preferences.getBoolean(HIDDEN, false),
            binding = DistanceBinding(binding.keyCode, DistancePress.valueOf(binding.press.name)))
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

    @JvmStatic fun applyPreset(context: Context, reset: Boolean) {
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val current = read(preferences)
        val next = if (reset) DistanceWidgetSettings() else
            DistanceWidgetSettings.decode(requireNotNull(preferences.getString(PRESET, null)))
        CameraButtonBindings.save(preferences, CameraButtonBindings.Action.DistanceVisibility,
            CameraButtonBindings.Binding(next.binding.keyCode, CameraButtonBindings.Press.valueOf(next.binding.press.name)))
        save(context, current.applyPreset(next))
    }
}

package com.byd.extend.ui

internal class UiStrings(private val language: UiLanguage) {
    val ukrainian: Boolean get() = language == UiLanguage.Ukrainian
    fun text(ukrainianText: String, englishText: String): String =
        if (ukrainian) ukrainianText else englishText

    val subtitle get() = text(
        "Поворотники, камери та інші функції | v1.0.0",
        "Turn signals, cameras and other features | v1.0.0",
    )
    val tabs get() = listOf(
        text("Поворотники та функції", "Signals & functions"),
        text("Камери сліпих зон", "Blind-zone cameras"),
        text("Камери паркування", "Parking cameras"),
        text("Камери заднього ходу", "Reverse cameras"),
        text("Налаштування", "Settings"),
        text("Відладка", "Debug"),
    )
    val cameraSections get() = listOf(
        text("Параметри", "Parameters"),
        text("Розташування", "Placement"),
        text("Калібрування", "Calibration"),
    )
    val reverseSections get() = listOf(
        text("Параметри", "Parameters"),
        text("Композиція", "Layout"),
        text("Калібрування", "Calibration"),
    )
    val calibrationStages get() = listOf(
        text("Оригінал", "Original"), text("Корекція", "Correction"), text("Вивід", "Output"),
    )
    val parkingViews get() = listOf(
        text("Перед-ліво", "Front left"), text("Перед", "Front"),
        text("Перед-право", "Front right"), text("Зад-праворуч", "Rear right"),
        text("Зад", "Rear"), text("Зад-ліворуч", "Rear left"),
        text("Ліво", "Left"), text("Право", "Right"),
    )
    val reverseElements get() = listOf(
        text("Тло", "Background"), text("Віджет", "Widget"), text("Задня", "Rear"),
        text("Задня ліва", "Rear left"), text("Задня права", "Rear right"),
    )
    val settingsCategories get() = listOf(
        text("Дозволи та служба", "Permissions and runtime"),
        text("Параметри виводу камер", "Camera output settings"),
        text("Логи", "Logs"),
    )
    val debugModes get() = listOf(
        text("Поворотники", "Turn signals"), "Direct camera", text("Режими AVM", "AVM modes"),
    )
}

internal val AvmModeNames = listOf(
    "VIEW_2D_TOP", "VIEW_2D_TOP_FULL", "VIEW_2D_FRONT", "VIEW_2D_REAR",
    "VIEW_2D_FRONT_FULL", "VIEW_2D_REAR_FULL", "VIEW_2D_LEFT_FRONT", "VIEW_2D_RIGHT_FRONT",
    "VIEW_2D_LEFT_REAR", "VIEW_2D_RIGHT_REAR", "VIEW_2D_FRONT_WHEELS", "VIEW_2D_REAR_WHEELS",
    "VIEW_2D_ALL_WHEELS", "VIEW_2D_ALL_WHEELS_OVER_SIZE", "VIEW_2D_FRONT_TOP", "VIEW_2D_REAR_TOP",
    "VIEW_3D_FRONT", "VIEW_3D_REAR", "VIEW_3D_LEFT", "VIEW_3D_RIGHT", "VIEW_3D_LEFT_FRONT",
    "VIEW_3D_RIGHT_FRONT", "VIEW_3D_LEFT_REAR", "VIEW_3D_RIGHT_REAR", "VIEW_3D_FREE_MODE",
    "VIEW_AHEAD_LEFT", "VIEW_AHEAD_RIGHT", "VIEW_TRANSPARENT_FRONT", "VIEW_TRANSPARENT_REAR",
    "VIEW_TURN_LEFT", "VIEW_TURN_RIGHT", "VIEW_3D_UAV", "VIEW_3D_OVERLOOK_VEHICLE_HEAD_ZOOM_IN",
    "VIEW_3D_OVERLOOK_VEHICLE_REAR_ZOOM_IN", "VIEW_2D_FRONT_APA", "VIEW_2D_REAR_APA",
    "VIEW_2D_FRONT_SINGLE_APA", "VIEW_2D_REAR_SINGLE_APA", "VIEW_2D_FRONT_FULL_APA",
    "VIEW_2D_REAR_FULL_APA", "VIEW_3D_TOP", "VIEW_3D_SUPER_TOP", "VIEW_2D_MECANUM",
    "VIEW_2D_IN_PLACE", "VIEW_CALI", "VIEW_2D_REAR_LEFT_AND_RIGHT", "VIEW_2D_FRONT_LEFT_AND_RIGHT",
    "VIEW_2D_FRONT_CLAIRVOYANCE", "VIEW_2D_REAR_CLAIRVOYANCE", "VIEW_2D_LEFT_CLAIRVOYANCE",
    "VIEW_2D_RIGHT_CLAIRVOYANCE",
)

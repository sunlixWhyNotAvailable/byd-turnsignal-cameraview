package com.byd.extend.ui

import android.content.Context
import android.content.res.Resources
import com.byd.extend.AppLanguage
import com.byd.extend.R
import java.util.Locale

/**
 * App-owned strings are resolved from packaged resources.  The literal arguments remain the
 * source-level fallback for tests and for labels which intentionally contain runtime data; normal
 * labels match the English resource value and are then resolved through the selected locale.
 */
internal class UiStrings @JvmOverloads constructor(
    private val language: UiLanguage,
    context: Context? = null,
) {
    private val resources: Resources? = context?.let {
        AppLanguage.localizedContext(it, language.wire()).resources
    }
    private val englishResources: Resources? = context?.let {
        AppLanguage.localizedContext(it, AppLanguage.ENGLISH).resources
    }
    private val ukrainianResources: Resources? = context?.let {
        AppLanguage.localizedContext(it, AppLanguage.UKRAINIAN).resources
    }
    private val resolvedIds = mutableMapOf<String, Int>()

    val ukrainian: Boolean get() = language == UiLanguage.Ukrainian
    val chinese: Boolean get() = language == UiLanguage.Chinese
    fun text(ukrainianText: String, englishText: String): String =
        packaged(ukrainianText, englishText) ?: if (ukrainian) ukrainianText else englishText
    fun text(ukrainianText: String, englishText: String, chineseText: String): String =
        packaged(ukrainianText, englishText) ?: when {
            ukrainian -> ukrainianText
            chinese -> chineseText
            else -> englishText
        }

    /** Resolves a packaged formatted string while retaining a source-level fallback. */
    fun format(ukrainianFormat: String, englishFormat: String, argument: String): String =
        format(ukrainianFormat, englishFormat, englishFormat, argument)

    fun format(
        ukrainianFormat: String,
        englishFormat: String,
        chineseFormat: String,
        argument: String,
    ): String {
        val source = resources
        val id = if (source == null) 0 else resolvedIds.getOrPut(
            resourceKey(ukrainianFormat, englishFormat)) {
            findResourceId(source, ukrainianFormat, englishFormat)
        }
        if (id != 0 && source != null) return source.getString(id, argument)
        return when {
            ukrainian -> ukrainianFormat.replace("%1\$s", argument)
            chinese -> chineseFormat.replace("%1\$s", argument)
            else -> englishFormat.replace("%1\$s", argument)
        }
    }

    private fun packaged(ukrainianText: String, englishText: String): String? {
        val source = resources ?: return null
        val id = resolvedIds.getOrPut(resourceKey(ukrainianText, englishText)) {
            findResourceId(source, ukrainianText, englishText)
        }
        return id.takeIf { it != 0 }?.let { source.getString(it) }
    }

    private fun resourceKey(ukrainianText: String, englishText: String) =
        "$ukrainianText\u0000$englishText"

    private fun findResourceId(
        source: Resources,
        ukrainianText: String,
        englishText: String,
    ): Int {
        val normalized = englishText.lowercase(Locale.US)
            .replace(Regex("%[0-9]+\\$[a-z]"), "")
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        if (normalized.isNotEmpty()) {
            val direct = source.getIdentifier(normalized, "string",
                R::class.java.`package`?.name ?: "com.byd.extend")
            if (direct != 0 && resourceMatches(direct, ukrainianText, englishText, source)) {
                return direct
            }
        }
        // Some approved IDs use a semantic name rather than a word-for-word key.  Match the
        // packaged default English value without maintaining a translation table in Kotlin.
        return R.string::class.java.declaredFields.firstNotNullOfOrNull { field ->
            runCatching { field.getInt(null) }
                .takeIf { it.isSuccess }
                ?.getOrNull()
                ?.takeIf { id -> resourceMatches(id, ukrainianText, englishText, source) }
        } ?: 0
    }

    private fun resourceMatches(
        id: Int,
        ukrainianText: String,
        englishText: String,
        selectedSource: Resources,
    ): Boolean = runCatching {
        (englishResources ?: selectedSource).getString(id) == englishText &&
            (ukrainianResources ?: selectedSource).getString(id) == ukrainianText
    }.getOrDefault(false)

    private fun UiLanguage.wire() = when (this) {
        UiLanguage.Ukrainian -> AppLanguage.UKRAINIAN
        UiLanguage.English -> AppLanguage.ENGLISH
        UiLanguage.Chinese -> AppLanguage.CHINESE
    }

    val subtitle get() = format(
        "Поворотники, камери та інші функції | v%1\$s",
        "Turn signals, cameras and other features | v%1\$s",
        "转向灯、摄像头及其他功能 | v%1\$s",
        "1.1.0",
    )
    val tabs get() = listOf(
        text("Інтеграції BYD", "BYD integrations", "BYD 集成"),
        text("Камери сліпих зон", "Blind-zone cameras", "盲区摄像头"),
        text("Камери паркування", "Parking cameras", "泊车摄像头"),
        text("Камери заднього ходу", "Reverse cameras", "倒车摄像头"),
        text("Дзеркало заднього виду", "Rearview mirror", "后视镜"),
        text("Налаштування", "Settings", "设置"),
        text("Відладка", "Debug", "调试"),
    )
    val cameraSections get() = listOf(
        text("Параметри", "Parameters", "参数"),
        text("Розташування", "Placement", "位置"),
        text("Калібрування", "Calibration", "校准"),
    )
    val reverseSections get() = listOf(
        text("Параметри", "Parameters", "参数"),
        text("Композиція", "Layout", "布局"),
        text("Калібрування", "Calibration", "校准"),
    )
    val calibrationStages get() = listOf(
        text("Оригінал", "Original", "原始"), text("Корекція", "Correction", "校正"),
        text("Вивід", "Output", "输出"),
    )
    val parkingViews get() = listOf(
        text("Перед-ліво", "Front left", "左前"), text("Перед", "Front", "前方"),
        text("Перед-право", "Front right", "右前"), text("Зад-праворуч", "Rear right", "右后"),
        text("Зад", "Rear", "后方"), text("Зад-ліворуч", "Rear left", "左后"),
        text("Ліво", "Left", "左侧"), text("Право", "Right", "右侧"),
    )
    val reverseElements get() = listOf(
        text("Тло", "Background", "背景"), text("Віджет", "Widget", "小组件"), text("Задня", "Rear", "后置"),
        text("Задня ліва", "Rear left", "左后"), text("Задня права", "Rear right", "右后"),
    )
    val settingsCategories get() = listOf(
        text("Дозволи та служба", "Permissions and runtime", "权限与服务"),
        text("Параметри виводу камер", "Camera output settings", "摄像头输出设置"),
        text("Логи", "Logs", "日志"),
    )
    val debugModes get() = listOf(
        text("Поворотники", "Turn signals", "转向灯"),
        text("Пряма камера", "Direct camera", "直连摄像头"),
        text("Режими AVM", "AVM modes", "AVM 模式"),
    )
}

/** HUD-compatible key labels; unknown firmware key codes remain visible for diagnostics. */
internal fun steeringButtonLabel(keyCode: Int, strings: UiStrings): String {
    if (keyCode < 0) return ""
    val name = when (keyCode) {
        294 -> strings.text("Панорама", "Panorama", "全景影像")
        304 -> strings.text("Мікрофон", "Microphone", "麦克风")
        88 -> strings.text("Попередній трек", "Previous track", "上一曲")
        87 -> strings.text("Наступний трек", "Next track", "下一曲")
        353 -> strings.text("Коліщатко — натискання", "Wheel press", "滚轮按下")
        305 -> strings.text("Ліва зірочка", "Left star", "左侧星号键")
        309 -> strings.text("Режими приборки / завершення виклику",
            "Dashboard modes / end call", "仪表模式 / 挂断电话")
        310 -> strings.text("Круговий огляд", "Surround view", "全景影像")
        320 -> strings.text("Голосове керування", "Voice control", "语音控制")
        321 -> strings.text("Ліва додаткова", "Left auxiliary", "左侧辅助键")
        351 -> strings.text("Права зірочка", "Right star", "右侧星号键")
        383 -> strings.text("Права додаткова", "Right auxiliary", "右侧辅助键")
        else -> return strings.format(
            "Кнопка (код %1\$s)", "Button (code %1\$s)", "按键（代码 %1\$s）",
            keyCode.toString())
    }
    return "$name ($keyCode)"
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

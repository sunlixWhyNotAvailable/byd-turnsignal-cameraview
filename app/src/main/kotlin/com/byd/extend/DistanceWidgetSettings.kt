package com.byd.extend

import org.json.JSONArray
import org.json.JSONObject

data class DistanceZone(val id: Int, val endCm: Int, val color: Int)

enum class DistanceDisplayFormat { Always, Obstacles }

data class DistanceWidgetFrame(val x: Float, val y: Float, val width: Float, val height: Float)

data class DistanceWidgetProfile(
    val sizePercent: Int = 100,
    val transparency: Int = 0,
    val borderDp: Int = 2,
    val borderColor: Int = 0xFFF2C34E.toInt(),
    val x: Float = 50f,
    val y: Float = 50f,
    val backgroundPercent: Int = 100,
) {
    fun frame(displayWidth: Float, displayHeight: Float): DistanceWidgetFrame {
        val width = minOf(500f * sizePercent / 100f, displayWidth, displayHeight * 500f / 620f)
        val height = width * 620f / 500f
        return DistanceWidgetFrame((displayWidth - width) * x / 100f,
            (displayHeight - height) * y / 100f, width, height)
    }

    fun backgroundFrame(displayWidth: Float, displayHeight: Float): DistanceWidgetFrame {
        val frame = frame(displayWidth, displayHeight)
        val scale = backgroundPercent / 100f
        return DistanceWidgetFrame(frame.x + frame.width * (1 - scale) / 2,
            frame.y + frame.height * (1 - scale) / 2, frame.width * scale, frame.height * scale)
    }

    fun encode() = JSONObject().put("sizePercent", sizePercent).put("transparency", transparency)
        .put("borderDp", borderDp).put("borderColor", borderColor).put("x", x).put("y", y)
        .put("backgroundPercent", backgroundPercent)

    companion object {
        fun decode(data: JSONObject) = DistanceWidgetProfile(
            data.optInt("sizePercent", 100).coerceIn(20, 100),
            data.optInt("transparency", 0).coerceIn(0, 100),
            data.optInt("borderDp", 2).coerceIn(0, 16),
            data.optInt("borderColor", 0xFFF2C34E.toInt()),
            data.optDouble("x", 50.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 100f) ?: 50f,
            data.optDouble("y", 50.0).takeIf { it.isFinite() }?.toFloat()?.coerceIn(0f, 100f) ?: 50f,
            data.optInt("backgroundPercent", 100).coerceIn(70, 100))
    }
}

data class DistanceWidgetSettings(
    val enabled: Boolean = false,
    val zones: List<DistanceZone> = listOf(
        DistanceZone(0, 25, 0xFFFF5656.toInt()),
        DistanceZone(1, 50, 0xFFFF982E.toInt()),
        DistanceZone(2, 75, 0xFFF2C34E.toInt()),
        DistanceZone(3, 100, 0xFF54D898.toInt()),
    ),
    val showDistance: Boolean = false,
    val carModel: String = "uke",
    val carColor: String = "white",
    val target: DistanceTarget = DistanceTarget.Tablet,
    val format: DistanceDisplayFormat = DistanceDisplayFormat.Obstacles,
    val tablet: DistanceWidgetProfile = DistanceWidgetProfile(sizePercent = 50, transparency = 30,
        borderColor = 0xFF54D898.toInt(), x = 0f, y = 56f, backgroundPercent = 80),
    val cluster: DistanceWidgetProfile = DistanceWidgetProfile(),
    val hiddenByButton: Boolean = false,
) {
    val profile get() = if (target == DistanceTarget.Tablet) tablet else cluster

    fun withProfile(value: DistanceWidgetProfile) =
        if (target == DistanceTarget.Tablet) copy(tablet = value) else copy(cluster = value)

    fun toggleVisibility() = copy(hiddenByButton = !hiddenByButton)

    // A preset must not enable the integration or undo a user's manual hide.
    fun applyPreset(preset: DistanceWidgetSettings) =
        preset.copy(enabled = enabled, hiddenByButton = hiddenByButton)

    fun visible(vehicleOn: Boolean, streamAvailable: Boolean, distances: List<Double?>): Boolean =
        enabled && !hiddenByButton && vehicleOn && streamAvailable &&
            (format == DistanceDisplayFormat.Always || distances.any { it != null && zoneAt(it) != null })

    fun zoneAt(distanceCm: Double): DistanceZone? =
        if (!distanceCm.isFinite() || distanceCm < 0) null else zones.firstOrNull { distanceCm <= it.endCm }

    fun withBoundary(id: Int, endCm: Int): DistanceWidgetSettings {
        val index = zones.indexOfFirst { it.id == id }
        require(index >= 0)
        val lower = if (index == 0) 0 else zones[index - 1].endCm
        val upper = zones.getOrNull(index + 1)?.endCm ?: 10000
        require(endCm > lower && endCm < upper)
        return copy(zones = zones.map { if (it.id == id) it.copy(endCm = endCm) else it })
    }

    fun addZone(endCm: Int, color: Int): DistanceWidgetSettings {
        require(endCm in 1..9999 && zones.none { it.endCm == endCm })
        val id = (zones.maxOfOrNull { it.id } ?: 3) + 1
        return copy(zones = (zones + DistanceZone(id, endCm, color)).sortedBy { it.endCm })
    }

    fun removeZone(id: Int): DistanceWidgetSettings {
        require(id >= 4)
        return copy(zones = zones.filterNot { it.id == id })
    }

    fun encode(): String = JSONObject().apply {
        put("enabled", enabled)
        put("showDistance", showDistance)
        put("carModel", carModel)
        put("carColor", carColor)
        put("target", target.name)
        put("format", format.name)
        put("tablet", tablet.encode())
        put("cluster", cluster.encode())
        put("hiddenByButton", hiddenByButton)
        put("zones", JSONArray().apply {
            zones.forEach { zone -> put(JSONObject().apply {
                put("id", zone.id); put("endCm", zone.endCm); put("color", zone.color)
            }) }
        })
    }.toString()

    companion object {
        fun decode(json: String): DistanceWidgetSettings {
            val data = JSONObject(json)
            val rows = data.getJSONArray("zones")
            val zones = List(rows.length()) { index -> rows.getJSONObject(index).let {
                DistanceZone(it.getInt("id"), it.getInt("endCm"), it.getInt("color"))
            } }.sortedBy { it.endCm }
            require(zones.map { it.id }.distinct().size == zones.size && zones.all { it.id >= 0 })
            require((0..3).all { id -> zones.any { it.id == id } })
            require(zones.all { it.endCm in 1..9999 } && zones.zipWithNext().all { (a, b) -> a.endCm < b.endCm })
            val car = DistanceCarCatalog.resolve(data.optString("carModel", "uke"), data.optString("carColor", "white"))
            // The old single appearance initializes both displays without discarding user edits.
            val legacy = DistanceWidgetProfile.decode(data)
            // Older files may still carry keyCode/press: the button belongs to CameraButtonBindings.
            return DistanceWidgetSettings(enabled = data.getBoolean("enabled"), zones = zones,
                showDistance = data.optBoolean("showDistance", true), carModel = car.model, carColor = car.color,
                target = DistanceTarget.entries.firstOrNull { it.name == data.optString("target") } ?: DistanceTarget.Tablet,
                format = DistanceDisplayFormat.entries.firstOrNull { it.name == data.optString("format") }
                    ?: DistanceDisplayFormat.Obstacles,
                tablet = data.optJSONObject("tablet")?.let(DistanceWidgetProfile::decode) ?: legacy,
                cluster = data.optJSONObject("cluster")?.let(DistanceWidgetProfile::decode) ?: legacy,
                hiddenByButton = data.optBoolean("hiddenByButton", false))
        }
    }
}

enum class DistanceTarget { Tablet, Cluster }

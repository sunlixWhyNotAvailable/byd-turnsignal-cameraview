package com.byd.extend

import android.os.Bundle
import java.util.function.Consumer

/** Additive metadata: older peers still exchange the original eight distances. */
internal object DistanceFrameCodec {
    fun encode(frame: DistanceFrame) = Bundle().apply {
        putLong("generation", frame.generation); putLong("received", frame.receivedAt)
        putBoolean("valid", frame.valid); putString("status", frame.status)
        putDoubleArray("distances", frame.distances())
        putInt("sources_version", 1)
        putLong("adas_received", frame.adasReceivedAt); putBoolean("adas_valid", frame.adasValid)
        putInt("radar_mask", frame.radarMask); putBoolean("radar_available", frame.radarAvailable)
        putLong("radar_core_generation", frame.radarCoreGeneration)
        putLong("radar_side_generation", frame.radarSideGeneration)
        putParcelableArrayList("objects", ArrayList(frame.objects.map { objectValue -> Bundle().apply {
            if (objectValue.id != null) {
                putInt("id", objectValue.id); putInt("type", objectValue.type)
                putLong("continuity", objectValue.continuity)
            }
            putDoubleArray("distances", objectValue.distances())
            objectValue.geometry()?.let { putDoubleArray("geometry", it) }
            objectValue.coverage()?.let { putDoubleArray("coverage", it) }
        } }))
    }

    @Suppress("DEPRECATION")
    fun decode(bundle: Bundle, metadataError: Consumer<Exception>): DistanceFrame {
        val values = requireNotNull(bundle.getDoubleArray("distances")) { "Missing distance sectors" }
        val objects = try {
            bundle.getParcelableArrayList<Bundle>("objects")?.map { row ->
                val identified = row.containsKey("id") && row.containsKey("type")
                val id = if (identified) row.getInt("id") else null
                val type = if (identified) row.getInt("type") else null
                val distances = requireNotNull(row.getDoubleArray("distances")) { "Missing object sectors" }
                DistanceFrame.validateDistances(distances)
                try {
                    DistanceObject(id, type, row.getLong("continuity"), distances,
                        row.getDoubleArray("geometry"), row.getDoubleArray("coverage"))
                } catch (error: Exception) {
                    metadataError.accept(error)
                    DistanceObject(id, type, row.getLong("continuity"), distances)
                }
            }.orEmpty()
        } catch (error: Exception) {
            metadataError.accept(error)
            emptyList()
        }
        if (bundle.containsKey("sources_version")) try {
            require(bundle.getInt("sources_version") == 1) { "Unknown distance sources version" }
            require(listOf("adas_received", "adas_valid", "radar_mask", "radar_available",
                "radar_core_generation", "radar_side_generation").all(bundle::containsKey)) { "Incomplete distance sources" }
            return DistanceFrame(bundle.getLong("generation"), bundle.getLong("received"),
                bundle.getBoolean("valid"), bundle.getString("status", "unavailable"), values, objects,
                bundle.getLong("adas_received"), bundle.getBoolean("adas_valid"),
                bundle.getInt("radar_mask"), bundle.getBoolean("radar_available"),
                bundle.getLong("radar_core_generation"), bundle.getLong("radar_side_generation"))
        } catch (error: Exception) { metadataError.accept(error) }
        return DistanceFrame(bundle.getLong("generation"), bundle.getLong("received"),
            bundle.getBoolean("valid"), bundle.getString("status", "unavailable"), values, objects)
    }
}

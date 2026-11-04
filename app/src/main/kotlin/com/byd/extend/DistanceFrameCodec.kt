package com.byd.extend

import android.os.Bundle
import java.util.function.Consumer

/** Additive metadata: older peers still exchange the original eight distances. */
internal object DistanceFrameCodec {
    fun encode(frame: DistanceFrame) = Bundle().apply {
        putLong("generation", frame.generation); putLong("received", frame.receivedAt)
        putBoolean("valid", frame.valid); putString("status", frame.status)
        putDoubleArray("distances", frame.distances())
        putParcelableArrayList("objects", ArrayList(frame.objects.map { objectValue -> Bundle().apply {
            if (objectValue.id != null) {
                putInt("id", objectValue.id); putInt("type", objectValue.type)
                putLong("continuity", objectValue.continuity)
            }
            putDoubleArray("distances", objectValue.distances())
        } }))
    }

    @Suppress("DEPRECATION")
    fun decode(bundle: Bundle, metadataError: Consumer<Exception>): DistanceFrame {
        val values = requireNotNull(bundle.getDoubleArray("distances")) { "Missing distance sectors" }
        val objects = try {
            bundle.getParcelableArrayList<Bundle>("objects")?.map { row ->
                val identified = row.containsKey("id") && row.containsKey("type")
                DistanceObject(if (identified) row.getInt("id") else null,
                    if (identified) row.getInt("type") else null, row.getLong("continuity"),
                    requireNotNull(row.getDoubleArray("distances")) { "Missing object sectors" })
            }.orEmpty()
        } catch (error: Exception) {
            metadataError.accept(error)
            emptyList()
        }
        return DistanceFrame(bundle.getLong("generation"), bundle.getLong("received"),
            bundle.getBoolean("valid"), bundle.getString("status", "unavailable"), values, objects)
    }
}

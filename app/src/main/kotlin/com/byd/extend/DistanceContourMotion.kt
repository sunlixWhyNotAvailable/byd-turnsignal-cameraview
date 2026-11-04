package com.byd.extend

import kotlin.math.*

/** Presentation only. Raw numbers, colors, eligibility and the 500 ms deadline never interpolate. */
internal class DistanceContourMotion {
    companion object {
        const val DURATION_MS = 100L
        const val FRAME_INTERVAL_MS = 34L // At most 30 scheduled animation frames per second.
    }
    private data class Key(val id: Int, val type: Int, val continuity: Long)
    private var generation = Long.MIN_VALUE
    private var received = Long.MIN_VALUE
    private var started = 0L
    private var moving = false
    private var owners = arrayOfNulls<Key>(8)
    private var keys = emptySet<Key>()
    private var raw: List<DistanceReading?> = List(8) { null }
    private var from = DoubleArray(8) { Double.NaN }
    private var targetSpans = emptyList<DistanceContourSpan>()
    private var fromSpans = emptyList<DistanceContourSpan>()

    fun clear() {
        generation = Long.MIN_VALUE; received = Long.MIN_VALUE; moving = false
        owners.fill(null); keys = emptySet(); raw = List(8) { null }
        targetSpans = emptyList(); fromSpans = emptyList()
    }

    fun accept(frame: DistanceFrame, readings: List<DistanceReading?>, now: Long): Boolean {
        if (!frame.fresh(now)) { clear(); return false }
        if (generation != frame.generation || !fresh(now)) clear()
        val unique = frame.objects.filter { it.id != null && it.continuity > 0 }
            .groupBy { it.id }.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val nextKeys = unique.values.map { Key(it.id!!, it.type!!, it.continuity) }.toSet()
        val nextOwners = Array<Key?>(8) { sector ->
            val value = readings[sector]?.cm ?: return@Array null
            val nearest = frame.objects.filter { it.distance(sector) == value }
            val objectValue = nearest.singleOrNull() ?: return@Array null
            if (unique[objectValue.id] !== objectValue) null
            else Key(objectValue.id!!, objectValue.type!!, objectValue.continuity)
        }
        // Settings recomposition or repeated unchanged telemetry must not restart a transition.
        if (generation == frame.generation && raw == readings && owners.contentEquals(nextOwners)) {
            received = frame.receivedAt; keys = nextKeys
            return false
        }
        val previousReadings = readingsAt(now)
        val previousSpans = spansAt(now)
        val nextSpans = DistanceWidgetContour.spans(readings)
        from = DoubleArray(8) { sector ->
            val current = readings[sector]?.cm ?: return@DoubleArray Double.NaN
            val owner = nextOwners[sector]
            if (owner != null && owner in keys && owner == owners[sector] && current >= (raw[sector]?.cm ?: current))
                min(current, previousReadings[sector]?.cm ?: current)
            else current
        }

        fun boundary(target: Float, owner: Key?, first: Boolean): Float {
            if (owner == null || owner !in keys) return target
            return previousSpans.asSequence().mapIndexedNotNull { index, span ->
                if (span.sectors.size == 8) return@mapIndexedNotNull null
                val sector = if (first) span.sectors.first() else span.sectors.last()
                if (owners[sector] != owner) return@mapIndexedNotNull null
                val old = if (first) span.start else span.end
                val previousTarget = if (first) targetSpans[index].start else targetSpans[index].end
                val delta = ((previousTarget - target + 12f) % 8f) - 4f
                // Check measured adjacency; unfinished motion may still trail the previous target.
                if (abs(delta) <= 1.001f) target + delta + (old - previousTarget) else null
            }.minByOrNull { abs(it - target) } ?: target
        }
        fromSpans = nextSpans.mapIndexed { index, span ->
            if (span.sectors.size == 8) return@mapIndexed span
            var start = min(span.sectors.first().toFloat(), boundary(span.start, nextOwners[span.sectors.first()], true))
            var end = max(span.sectors.first() + span.sectors.size - 1f,
                boundary(span.end, nextOwners[span.sectors.last()], false))
            // Separate current runs remain separate while old edges retract.
            var previousEnd = nextSpans[(index + nextSpans.size - 1) % nextSpans.size].end
            while (previousEnd >= span.start) previousEnd -= 8f
            var nextStart = nextSpans[(index + 1) % nextSpans.size].start
            while (nextStart <= span.end) nextStart += 8f
            start = max(start, (previousEnd + span.start) / 2 + .001f)
            end = min(end, (nextStart + span.end) / 2 - .001f)
            span.copy(start = start, end = end)
        }
        raw = readings; owners = nextOwners; keys = nextKeys
        targetSpans = nextSpans; generation = frame.generation; received = frame.receivedAt; started = now
        moving = fromSpans != targetSpans || raw.indices.any { raw[it]?.cm?.let { cm -> cm != from[it] } == true }
        return moving
    }

    private fun fresh(now: Long) = received != Long.MIN_VALUE && now >= received && now - received < DistanceFrame.STALE_MS
    private fun fraction(now: Long): Float {
        val t = ((now - started).toFloat() / DURATION_MS).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }
    fun active(now: Long) = moving && fresh(now) && now - started < DURATION_MS
    internal fun readingsAt(now: Long): List<DistanceReading?> {
        if (!fresh(now)) return List(8) { null }
        if (!active(now)) return raw
        val t = fraction(now)
        return raw.mapIndexed { index, reading -> reading?.copy(cm = min(reading.cm, from[index] + (reading.cm - from[index]) * t)) }
    }
    internal fun spansAt(now: Long): List<DistanceContourSpan> {
        if (!fresh(now)) return emptyList()
        if (!active(now)) return targetSpans
        val t = fraction(now)
        return targetSpans.mapIndexed { index, target -> target.copy(
            start = fromSpans[index].start + (target.start - fromSpans[index].start) * t,
            end = fromSpans[index].end + (target.end - fromSpans[index].end) * t) }
    }
    fun contours(now: Long, maximum: Int, background: Int): List<DistanceContour> =
        DistanceWidgetContour.buildSpans(readingsAt(now), maximum, background, spansAt(now))
}

package com.byd.extend

import kotlin.math.*

data class DistanceReading(val cm: Double, val color: Int)
data class DistanceContourPoint(val x: Float, val y: Float, val color: Int)
data class DistanceContour(val points: List<DistanceContourPoint>, val closed: Boolean)
internal data class DistanceContourSpan(val sectors: List<Int>, val start: Float, val end: Float,
    val positions: List<Float> = sectors.indices.map { sectors.first() + it.toFloat() }) {
    val closed: Boolean get() = end - start >= 7.9999f
}

/** Clockwise: front, front-right, right, rear-right, rear, rear-left, left, front-left. */
object DistanceWidgetContour {
    fun build(readings: List<DistanceReading?>, maximumCm: Int, backgroundPercent: Int = 100): List<DistanceContour> =
        buildSpans(readings, maximumCm, backgroundPercent, spans(readings))

    internal fun spans(readings: List<DistanceReading?>): List<DistanceContourSpan> {
        if (readings.all { it == null }) return emptyList()
        val closed = readings.all { it != null }
        val groups = mutableListOf<List<Int>>()
        if (closed) groups += readings.indices.toList()
        else {
            val start = (readings.indexOfFirst { it == null } + 1) % 8
            var run = mutableListOf<Int>()
            repeat(8) { offset ->
                val index = (start + offset) % 8
                if (readings[index] != null) run += index
                else if (run.isNotEmpty()) { groups += run; run = mutableListOf() }
            }
            if (run.isNotEmpty()) groups += run
        }
        return groups.map { group ->
            val start = if (closed) 0f else group.first() - .5f
            DistanceContourSpan(group, start, start + group.size)
        }
    }

    /** Keep disconnected coverage disconnected, including ties between different objects. */
    internal fun spans(readings: List<DistanceReading?>, frame: DistanceFrame): List<DistanceContourSpan> {
        data class Part(val sector: Int, val start: Float, val end: Float)
        val parts = mutableListOf<Part>()
        readings.forEachIndexed { sector, reading ->
            if (reading == null) return@forEachIndexed
            val winners = frame.objects.filter { it.distance(sector) == reading.cm }
            if (frame.radarSector(sector) || winners.isEmpty() || winners.any { !it.hasCoverage() })
                parts += Part(sector, sector - .5f, sector + .5f)
            else winners.forEach {
                parts += Part(sector, it.coverageStart(sector).toFloat(), it.coverageEnd(sector).toFloat())
            }
        }
        if (parts.isEmpty()) return emptyList()
        val runs = mutableListOf<MutableList<Part>>()
        var end = Float.NEGATIVE_INFINITY
        for (part in parts.sortedBy { it.start }) {
            if (runs.isEmpty() || part.start > end + .0001f) runs += mutableListOf(part)
            else runs.last() += part
            end = if (runs.last().size == 1) part.end else max(end, part.end)
        }
        if (runs.size > 1 && runs.last().maxOf { it.end } >= runs.first().first().start + 8f - .0001f) {
            val first = runs.removeAt(0).map { it.copy(start = it.start + 8, end = it.end + 8) }
            runs.last().addAll(first)
        }
        return runs.map { run ->
            val anchors = run.sortedBy { (it.start + it.end) / 2 }.groupBy { (it.start + it.end) / 2 }
                .map { (position, members) -> position to members.minBy { readings[it.sector]!!.cm }.sector }
            DistanceContourSpan(anchors.map { it.second }, run.minOf { it.start }, run.maxOf { it.end }, anchors.map { it.first })
        }
    }

    internal fun buildSpans(readings: List<DistanceReading?>, maximumCm: Int, backgroundPercent: Int,
        spans: List<DistanceContourSpan>): List<DistanceContour> {
        require(backgroundPercent in 70..100)
        require(readings.size == 8 && maximumCm > 0)
        require(readings.filterNotNull().all { it.cm >= 0 && it.cm <= maximumCm })
        if (readings.all { it == null }) return emptyList()
        return spans.map { span ->
            val group = span.sectors
            val closed = span.closed
            val steps = ceil((span.end - span.start) * 24).toInt().coerceAtLeast(1)
            val anchors = if (closed) listOf(span.positions.last() - 8 to group.last()) +
                span.positions.zip(group) + listOf(span.positions.first() + 8 to group.first())
                else span.positions.zip(group)
            val points = (0..steps).map { step ->
                val position = span.start + (span.end - span.start) * step / steps
                val high = anchors.indexOfFirst { it.first >= position }.let { if (it < 0) anchors.lastIndex else it }
                val low = (high - 1).coerceAtLeast(0)
                val a = readings[anchors[low].second]!!
                val b = readings[anchors[high].second]!!
                val t = if (high == low) 0f else ((position - anchors[low].first) /
                    (anchors[high].first - anchors[low].first)).coerceIn(0f,1f).let { it * it * (3f - 2f * it) }
                point(position, (a.cm + (b.cm - a.cm) * t).toFloat(), maximumCm,
                    blend(a.color, b.color, t), backgroundPercent)
            }.toMutableList()
            if (closed) points[points.lastIndex] = points.first()
            DistanceContour(points, closed)
        }
    }

    fun point(sector: Float, distanceCm: Float, maximumCm: Int, color: Int, backgroundPercent: Int = 100): DistanceContourPoint {
        require(backgroundPercent in 70..100)
        require(maximumCm > 0 && distanceCm.isFinite() && distanceCm in 0f..maximumCm.toFloat())
        val angle = sector * PI / 4
        val dx = sin(angle).toFloat()
        val dy = -cos(angle).toFloat()
        // Expand the same rounded car outline by a distance-dependent gap, including its corners.
        val gap = 12f + (96f - 250f * (1 - backgroundPercent / 100f)) * distanceCm / maximumCm
        val width = 68f + gap
        val height = 123f + gap
        val radius = 42f + gap
        val x = abs(dx)
        val y = abs(dy)
        val reach = when {
            x > 0.00001f && width / x * y <= height - radius -> width / x
            y > 0.00001f && height / y * x <= width - radius -> height / y
            else -> {
                val dot = x * (width - radius) + y * (height - radius)
                dot + sqrt((dot * dot - (width - radius).pow(2) - (height - radius).pow(2) + radius.pow(2)).coerceAtLeast(0f))
            }
        }
        return DistanceContourPoint(300f + dx * reach, 300f + dy * reach, color)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        var result = 0
        for (shift in listOf(0, 8, 16, 24)) {
            val from = (a ushr shift) and 255
            val to = (b ushr shift) and 255
            result = result or ((from + (to - from) * t).roundToInt() shl shift)
        }
        return result
    }
}

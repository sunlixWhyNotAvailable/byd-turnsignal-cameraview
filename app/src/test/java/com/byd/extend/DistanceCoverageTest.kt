package com.byd.extend

import org.junit.Assert.*
import org.junit.Test

/** Fixtures reach 150 cm, so their zones stay fixed when the user-facing defaults change. */
internal val distanceFixtureSettings = DistanceWidgetSettings(zones = listOf(
    DistanceZone(0, 30, 0xFFFF5656.toInt()), DistanceZone(1, 60, 0xFFFF982E.toInt()),
    DistanceZone(2, 100, 0xFFF2C34E.toInt()), DistanceZone(3, 150, 0xFF54D898.toInt())))

/** Coordinates enter the real model, continuity, fusion and shared contour engine. */
class DistanceCoverageTest {
    private val settings = distanceFixtureSettings
    private val continuity = DistanceObjectContinuity()
    private fun box(id: Int, x: Double, y: Double, length: Double = 10.0, width: Double = 10.0) =
        AdasDistanceModel.Obstacle(id, 2, x, y, length, width, 0.0)
    private fun frame(time: Long, vararg boxes: AdasDistanceModel.Obstacle): DistanceFrame {
        val result = AdasDistanceModel.evaluate(boxes.toList())
        return DistanceFrame(1,time,true,"ready",result.distances,continuity.accept(result.objects))
    }
    private fun readings(frame: DistanceFrame) = frame.distances().map { cm ->
        settings.zoneAt(cm)?.let { DistanceReading(cm,it.color) }
    }
    private fun spans(frame: DistanceFrame) = DistanceWidgetContour.spans(readings(frame),frame)

    @Test fun wideRearBoxKeepsThreeDistancesButOnlyCoversRearBumper() {
        val frame = frame(1000,box(7,-405.25,-.75,400.0,180.0))
        for (i in 3..5) assertEquals(120.0,frame.distances()[i],0.0)
        val span=spans(frame).single()
        assertEquals(listOf(3,4,5),span.sectors)
        assertTrue(span.start > 3.5f && span.end < 4.5f)
        val motion=DistanceContourMotion()
        assertFalse(motion.accept(frame,readings(frame),1000))
        val contour=motion.contours(1000,150,100).single()
        assertTrue(contour.points.all { it.y > 450f })
        for (i in 3..5) assertTrue(motion.labelPosition(i) in span.start..span.end)
    }

    @Test fun narrowCornerIsNotExpandedToSideOrOtherRearSectors() {
        val frame=frame(1000,box(4,-160.25,-172.0))
        assertEquals(listOf(3),frame.distances().indices.filter { frame.distances()[it].isFinite() })
        val span=spans(frame).single()
        assertEquals(span.start,span.end,0f)
        assertTrue(span.start > 3.5f && span.end < 4f)
        val contour=DistanceWidgetContour.buildSpans(readings(frame),150,100,listOf(span)).single()
        assertEquals(contour.points.first(),contour.points.last())
        assertFalse(contour.closed)
    }

    @Test fun physicalCoverageInAllEightDirectionsAndFrontSeamIsContinuous() {
        val boxes=listOf(box(1,432.75,-.75),box(1,432.75,-132.0),box(1,156.25,-132.0),
            box(1,-120.25,-132.0),box(1,-120.25,-.75),box(1,-120.25,130.5),
            box(1,156.25,130.5),box(1,432.75,130.5))
        boxes.forEachIndexed { sector, box ->
            val frame=frame(1000,box)
            val span=spans(frame).single()
            assertEquals(listOf(sector),span.sectors)
            assertTrue(span.end-span.start < .2f)
            assertTrue(DistanceWidgetContour.buildSpans(readings(frame),150,70,listOf(span))
                .flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() })
        }
        val front=frame(1100,box(1,522.75,-.75,200.0,180.0))
        val seam=spans(front).single()
        assertEquals(listOf(7,0,1),seam.sectors)
        assertTrue(seam.end-seam.start < 1f)
        val contour=DistanceWidgetContour.buildSpans(readings(front),150,100,listOf(seam)).single()
        assertTrue(contour.points.all { it.y < 177f }) // Above the car's front edge, including rounded corners.
    }

    @Test fun separateObjectsDoNotFillTheGapAndRadarRetainsItsSector() {
        val frame=frame(1000,box(1,432.75,-30.0),box(2,432.75,30.0))
        val partial=spans(frame)
        assertEquals(2,partial.size)
        assertTrue(partial[0].end < partial[1].start)
        val radar=ParkingRadarState().apply { begin(0,1); registered(0,1); callback(0x99000062.toInt(),65,1000,1) }
        val sources=DistanceSources().apply { adas(frame); radar(radar.snapshot()) }
        val combined=sources.frame(1000)
        assertEquals(65.0,combined.distances()[0],0.0)
        val full=spans(combined).single()
        assertEquals(AdasDistanceModel.contourStart(0).toFloat(),full.start,0f)
        assertEquals(AdasDistanceModel.contourEnd(0).toFloat(),full.end,0f)
        val old=DistanceFrame(1,1000,true,"ready",frame.distances())
        assertEquals(full,spans(old).single())
    }

    @Test fun recordedMixedRadarAndAdasShareBodyBoundariesWithoutChangingDistances() {
        // Owner capture 11 Nov 07:44:03.949: the two broken joins were right/rear-right and rear-left/left.
        val measured = frame(1000,
            AdasDistanceModel.Obstacle(6610,1,41.509864807128906,-220.3990936279297,
                459.64544677734375,190.98590087890625,-1.8288514521626142),
            AdasDistanceModel.Obstacle(6613,4,19.773096084594727,236.77928161621094,
                462.74005126953125,192.26861572265625,-4.119942786595011))
        val radar = ParkingRadarState().apply {
            begin(1,1); registered(1,1)
            callback(911212560,35,1000,1); callback(911212552,50,1000,1)
        }
        val sources = DistanceSources().apply { adas(measured); radar(radar.snapshot()) }
        val combined = sources.frame(1000)
        val expected = measured.distances().apply { this[2]=35.0; this[6]=50.0 }
        assertArrayEquals(expected,combined.distances(),0.0)
        val joined = spans(combined)
        assertEquals(listOf(listOf(2,3),listOf(5,6)),joined.map { it.sectors })
        assertEquals(AdasDistanceModel.contourStart(2).toFloat(),joined[0].start,0f)
        assertEquals(AdasDistanceModel.contourEnd(6).toFloat(),joined[1].end,0f)
        val motion = DistanceContourMotion()
        motion.accept(combined,readings(combined),1000)
        assertEquals(joined,motion.spansAt(1000))
        assertEquals(2f,motion.labelPosition(2),.00001f)
        assertEquals(6f,motion.labelPosition(6),.00001f)
        val expired = sources.frame(1500)
        assertFalse(expired.distances()[3].isFinite())
        assertFalse(expired.distances()[5].isFinite())
        assertEquals(35.0,expired.distances()[2],0.0)
        assertEquals(50.0,expired.distances()[6],0.0)
    }

    @Test fun fullBodyRegionsJoinAcrossCornersAndSeamAndPlaceCornerLabelsInsideThem() {
        val values = DoubleArray(8) { 65.0 }
        val full = DistanceFrame(1,1000,true,"ready",values)
        val ring = spans(full).single()
        assertTrue(ring.closed)
        val contour = DistanceWidgetContour.buildSpans(readings(full),150,100,listOf(ring)).single()
        assertEquals(contour.points.first(),contour.points.last())
        val motion=DistanceContourMotion(); motion.accept(full,readings(full),1000)
        for (sector in 0..7) assertEquals(
            ((AdasDistanceModel.contourStart(sector)+AdasDistanceModel.contourEnd(sector))/2).toFloat(),
            motion.labelPosition(sector),.00001f)
        for (i in 1..6) values[i]=Double.NaN
        val seam = spans(DistanceFrame(1,1000,true,"ready",values)).single()
        assertEquals(listOf(7,0),seam.sectors)
        assertEquals(AdasDistanceModel.contourStart(7).toFloat(),seam.start,0f)
        assertEquals(AdasDistanceModel.contourEnd(0).toFloat()+8,seam.end,0f)
    }

    @Test fun sameDistanceGeometryMovesWithoutRestartingDuplicatesOrFreshness() {
        val motion=DistanceContourMotion()
        val first=frame(1000,box(1,432.75,25.0))
        motion.accept(first,readings(first),1000)
        val second=frame(1100,box(1,432.75,-25.0))
        assertArrayEquals(first.distances(),second.distances(),0.0)
        assertTrue(motion.accept(second,readings(second),1100))
        val start=motion.spansAt(1100).single().start
        val end=spans(second).single().start
        assertEquals((start+end)/2,motion.spansAt(1150).single().start,.0001f)
        assertFalse(motion.accept(second,readings(second),1150))
        assertFalse(motion.active(1200))
        assertEquals(spans(second),motion.spansAt(1200))
        assertTrue(motion.contours(1600,150,100).isEmpty())
        assertFalse(motion.active(1600))
    }

    @Test fun allBodyCoverageRemainsClosedAndSharedCornerLabelsDoNotJumpToSectorCenters() {
        val around=frame(1000,box(1,156.25,-.75,1000.0,1000.0))
        val ring=DistanceWidgetContour.buildSpans(readings(around),150,100,spans(around)).single()
        assertTrue(ring.closed)
        assertEquals(ring.points.first(),ring.points.last())
        val boundary=frame(1100,box(2,432.75,95.5-192.5/4+5))
        val motion=DistanceContourMotion()
        motion.accept(boundary,readings(boundary),1100)
        val measured=boundary.objects.single()
        for (sector in listOf(0,7)) {
            val label=motion.labelPosition(sector).toDouble()
            assertTrue(label >= measured.coverageStart(sector)-.00001)
            assertTrue(label <= measured.coverageEnd(sector)+.00001)
        }
    }
}

package com.byd.extend

import org.junit.Assert.*
import org.junit.Test

/** Controlled sector/time inputs exercise the actual presentation engine, not an OEM substitute. */
class DistanceContourMotionTest {
    private val settings = DistanceWidgetSettings()
    private fun objectValue(id: Int?, vararg sectors: Pair<Int, Double>, token: Long = 1, type: Int = 1): DistanceObject {
        val values = DoubleArray(8) { Double.NaN }
        sectors.forEach { (sector, cm) -> values[sector] = cm }
        return DistanceObject(id, if (id == null) null else type, token, values)
    }
    private fun frame(at: Long, vararg objects: DistanceObject, generation: Long = 1): DistanceFrame {
        val values = DoubleArray(8) { sector -> objects.map { it.distance(sector) }.filter { it.isFinite() }.minOrNull() ?: Double.NaN }
        return DistanceFrame(generation, at, true, "ready", values, objects.toList())
    }
    private fun feed(motion: DistanceContourMotion, frame: DistanceFrame, now: Long = frame.receivedAt) =
        motion.accept(frame, frame.distances().map { cm -> settings.zoneAt(cm)?.let { DistanceReading(cm,it.color) } }, now)

    @Test fun everyDirectionRecedesSmoothlyButCloserDistancesAndColorsAreImmediate() {
        for (sector in 0..7) {
            val motion = DistanceContourMotion()
            feed(motion, frame(1000, objectValue(1, sector to 30.0)))
            assertTrue(feed(motion, frame(1100, objectValue(1, sector to 100.0))))
            assertEquals(65.0, motion.readingsAt(1150)[sector]!!.cm, .001)
            assertEquals(settings.zoneAt(100.0)!!.color, motion.readingsAt(1150)[sector]!!.color)
            assertEquals(100.0, motion.readingsAt(1200)[sector]!!.cm, 0.0)
            assertFalse(motion.active(1200))
            feed(motion, frame(1160, objectValue(1, sector to 10.0)))
            assertEquals(10.0, motion.readingsAt(1160)[sector]!!.cm, 0.0)
            assertEquals(settings.zoneAt(10.0)!!.color, motion.readingsAt(1160)[sector]!!.color)
            assertFalse(motion.active(1160))
        }
    }

    @Test fun adjacentSectorAndWrapSeamMoveEdgesWithoutHidingNewDirection() {
        for (direction in listOf(-1,1)) for (sector in 0..7) {
            val next = (sector + direction + 8) % 8
            val motion = DistanceContourMotion()
            feed(motion, frame(1000, objectValue(1, sector to 60.0)))
            feed(motion, frame(1100, objectValue(1, next to 60.0)))
            val initial = motion.spansAt(1100).single()
            val middle = motion.spansAt(1150).single()
            val end = motion.spansAt(1200).single()
            assertTrue(next.toFloat() in initial.start..initial.end)
            assertTrue(initial.end - initial.start <= 1.501f)
            assertTrue(middle.start in minOf(initial.start,end.start)..maxOf(initial.start,end.start))
            assertEquals(next - .5f, end.start, .001f)
            assertEquals(next + .5f, end.end, .001f)
            assertTrue(motion.contours(1150,150,70).flatMap { it.points }.all { it.x.isFinite() && it.y.isFinite() })
        }
    }

    @Test fun contourRetargetPreservesCurrentEdgeAndClosestObjectHandoverIsImmediate() {
        val motion=DistanceContourMotion()
        feed(motion,frame(1000,objectValue(1,0 to 60.0)))
        feed(motion,frame(1100,objectValue(1,1 to 60.0)))
        val edge=motion.spansAt(1150).single().start
        feed(motion,frame(1150,objectValue(1,2 to 60.0)))
        assertEquals(edge,motion.spansAt(1150).single().start,.001f)
        assertTrue(2f in motion.spansAt(1150).single().start..motion.spansAt(1150).single().end)
        motion.clear()
        feed(motion,frame(1000,objectValue(1,0 to 30.0),objectValue(2,0 to 60.0)))
        assertFalse(feed(motion,frame(1100,objectValue(1,0 to 120.0),objectValue(2,0 to 100.0))))
        assertEquals(100.0,motion.readingsAt(1100)[0]!!.cm,0.0)
        assertFalse(feed(motion,frame(1200,objectValue(1,0 to 130.0),objectValue(2,0 to 130.0))))
        assertEquals(130.0,motion.readingsAt(1200)[0]!!.cm,0.0)
    }

    @Test fun retargetStartsAtCurrentPositionAndDuplicateInputDoesNotExtendDeadline() {
        val motion = DistanceContourMotion()
        feed(motion, frame(1000, objectValue(1, 0 to 20.0)))
        feed(motion, frame(1100, objectValue(1, 0 to 100.0)))
        assertEquals(60.0,motion.readingsAt(1150)[0]!!.cm,.001)
        val next = frame(1150,objectValue(1,0 to 140.0))
        feed(motion,next)
        assertEquals(60.0,motion.readingsAt(1150)[0]!!.cm,.001)
        assertFalse(feed(motion,next,1175))
        assertEquals(100.0,motion.readingsAt(1200)[0]!!.cm,.001)
        assertEquals(140.0,motion.readingsAt(1250)[0]!!.cm,.001)
        assertFalse(motion.active(1250))
    }

    @Test fun approachingFrameIsImmediateEvenDuringAnUnfinishedRetreat() {
        val motion=DistanceContourMotion()
        feed(motion,frame(1000,objectValue(1,0 to 30.0)))
        feed(motion,frame(1100,objectValue(1,0 to 100.0)))
        assertTrue(motion.readingsAt(1125)[0]!!.cm < 80.0)
        assertFalse(feed(motion,frame(1125,objectValue(1,0 to 80.0))))
        assertEquals(80.0,motion.readingsAt(1125)[0]!!.cm,0.0)
    }

    @Test fun replacementsMissingIdentityDuplicatesAndGenerationNeverMorph() {
        val replacements = listOf(
            objectValue(2,0 to 100.0), objectValue(1,0 to 100.0,token=2),
            objectValue(1,0 to 100.0,type=2), objectValue(null,0 to 100.0))
        for (replacement in replacements) {
            val motion = DistanceContourMotion()
            feed(motion,frame(1000,objectValue(1,0 to 30.0)))
            assertFalse(feed(motion,frame(1100,replacement)))
            assertEquals(100.0,motion.readingsAt(1100)[0]!!.cm,0.0)
        }
        val motion = DistanceContourMotion()
        feed(motion,frame(1000,objectValue(1,0 to 30.0)))
        assertFalse(feed(motion,frame(1100,objectValue(1,0 to 100.0),objectValue(1,1 to 70.0))))
        assertFalse(feed(motion,frame(1200,objectValue(1,0 to 140.0),generation=2)))
    }

    @Test fun disappearanceExpiryAndExplicitStopRemoveAllHistory() {
        val motion = DistanceContourMotion()
        feed(motion,frame(1000,objectValue(1,0 to 30.0)))
        feed(motion,frame(1100,objectValue(1,0 to 100.0)))
        feed(motion,frame(1120))
        assertTrue(motion.contours(1120,150,100).isEmpty())
        assertFalse(feed(motion,frame(1140,objectValue(1,0 to 120.0))))
        assertTrue(motion.contours(1640,150,100).isEmpty())
        assertFalse(motion.active(1640))
        feed(motion,frame(1700,objectValue(1,0 to 140.0)))
        motion.clear()
        assertTrue(motion.contours(1701,150,100).isEmpty())
        assertFalse(feed(motion,frame(1702,objectValue(1,0 to 100.0))))
        feed(motion,DistanceFrame.unavailable(1,1710,"off"))
        assertTrue(motion.contours(1710,150,100).isEmpty())
    }

    @Test fun separatedRunsRemainOpenAndFullRingKeepsGradients() {
        val motion = DistanceContourMotion()
        feed(motion,frame(1000,objectValue(1,0 to 30.0,1 to 30.0),objectValue(2,3 to 100.0)))
        feed(motion,frame(1100,objectValue(1,0 to 30.0),objectValue(2,3 to 100.0)))
        val partial = motion.contours(1150,150,100)
        assertEquals(2,partial.size)
        assertTrue(partial.none { it.closed })
        feed(motion,frame(1200,objectValue(1,*Array(8) { it to if (it%2==0) 30.0 else 100.0 })))
        val ring = motion.contours(1200,150,100).single()
        assertTrue(ring.closed); assertEquals(ring.points.first(),ring.points.last())
        assertTrue(ring.points.any { it.color != settings.zoneAt(30.0)!!.color && it.color != settings.zoneAt(100.0)!!.color })
    }

    @Test fun sourceContinuitySurvivesSkippedUiFramesButNotMissingObjectsOrDuplicateIds() {
        val continuity = DistanceObjectContinuity()
        val first = continuity.accept(listOf(objectValue(1,0 to 30.0))).single()
        assertEquals(first.continuity,continuity.accept(listOf(objectValue(1,0 to 40.0))).single().continuity)
        continuity.accept(emptyList()) // UI can coalesce this frame; the source still observes disappearance.
        val returned = continuity.accept(listOf(objectValue(1,0 to 100.0))).single()
        assertNotEquals(first.continuity,returned.continuity)
        val motion = DistanceContourMotion()
        feed(motion,frame(1000,first))
        assertFalse(feed(motion,frame(1100,returned)))
        val duplicates = continuity.accept(listOf(objectValue(1,0 to 30.0),objectValue(1,1 to 60.0)))
        assertTrue(duplicates.all { it.id == null })
        val next = continuity.accept(listOf(objectValue(1,0 to 100.0))).single()
        assertNotEquals(returned.continuity,next.continuity)
        continuity.clear()
        assertNotEquals(next.continuity,continuity.accept(listOf(objectValue(1,0 to 100.0))).single().continuity)
    }

    @Test fun radarSnapsEvenWhenItsValueMatchesAnAdasObjectAndFallbackDoesNotMorph() {
        val motion = DistanceContourMotion()
        val radar = ParkingRadarState().apply { begin(0,1); registered(0,1) }
        val sources = DistanceSources()
        sources.adas(frame(1000, objectValue(1,0 to 30.0,4 to 30.0)))
        feed(motion,sources.frame(1000))
        radar.callback(0x99000062.toInt(),100,1100,1); sources.radar(radar.snapshot())
        sources.adas(frame(1100,objectValue(1,0 to 100.0,4 to 100.0)))
        feed(motion,sources.frame(1100))
        assertEquals(100.0,motion.readingsAt(1100)[0]!!.cm,0.0)
        assertEquals(65.0,motion.readingsAt(1150)[4]!!.cm,.001)
        radar.callback(0x99000062.toInt(),150,1150,1); sources.radar(radar.snapshot())
        sources.adas(frame(1150,objectValue(1,0 to 140.0,4 to 100.0)))
        feed(motion,sources.frame(1150))
        assertEquals(140.0,motion.readingsAt(1150)[0]!!.cm,0.0)
        radar.callback(0x99000062.toInt(),65,1200,1); sources.radar(radar.snapshot())
        feed(motion,sources.frame(1200))
        assertEquals(65.0,motion.readingsAt(1200)[0]!!.cm,0.0)
        assertEquals(65.0,motion.readingsAt(2000)[0]!!.cm,0.0)
        assertNull(motion.readingsAt(2000)[4])
        assertEquals(1,motion.spansAt(2000).size)
        assertFalse(motion.active(2000))
    }

    @Test fun authoritativeSideMeasurementAboveDisplayThresholdDoesNotShowAdasBand() {
        val radar = ParkingRadarState().apply { begin(1,1); registered(1,1); callback(0x36500008,200,1000,1) }
        val sources = DistanceSources().apply { adas(frame(1000,objectValue(1,6 to 30.0))); radar(radar.snapshot()) }
        val motion = DistanceContourMotion()
        feed(motion,sources.frame(1000))
        assertNull(motion.readingsAt(1000)[6])
        assertTrue(motion.contours(1000,150,100).isEmpty())
    }

    @Test fun persistentRadarDoesNotPreserveAnExpiredAdasAnimationTrack() {
        val radar=ParkingRadarState().apply { begin(1,1); registered(1,1); callback(0x36500008,50,1000,1) }
        val sources=DistanceSources().apply { radar(radar.snapshot()) }
        val motion=DistanceContourMotion()
        sources.adas(frame(1000,objectValue(1,0 to 30.0)))
        feed(motion,sources.frame(1000))
        // Latest-value delivery can skip the intermediate stale notification.
        sources.adas(frame(1600,objectValue(1,0 to 100.0)))
        assertFalse(feed(motion,sources.frame(1600)))
        assertEquals(100.0,motion.readingsAt(1600)[0]!!.cm,0.0)
        assertEquals(50.0,motion.readingsAt(1600)[6]!!.cm,0.0)
    }
}

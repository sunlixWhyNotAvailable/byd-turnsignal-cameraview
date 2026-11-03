package com.byd.extend

import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import java.time.Duration
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[33])
class DistanceWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    @Before fun reset() { prefs.edit().clear().commit(); DistanceWidgetUiFeed.disconnected() }

    @Test fun visibilityUsesRawThresholdsAndManualHideSurvivesModeChanges() {
        val settings=DistanceWidgetSettings(enabled=true)
        assertEquals(0, settings.zoneAt(30.0)?.id)
        assertEquals(1, settings.zoneAt(30.001)?.id)
        assertEquals(3, settings.zoneAt(150.0)?.id)
        assertNull(settings.zoneAt(150.001)); assertNull(settings.zoneAt(Double.NaN))
        assertFalse(settings.visible(true,true,List(8){null}))
        assertTrue(settings.copy(format=DistanceDisplayFormat.Always).visible(true,true,List(8){null}))
        assertFalse(settings.visible(false,true,listOf(10.0)))
        assertFalse(settings.visible(true,false,listOf(10.0)))
        val hidden=settings.toggleVisibility()
        assertFalse(hidden.copy(format=DistanceDisplayFormat.Always).visible(true,true,listOf(10.0)))
        assertTrue(hidden.toggleVisibility().visible(true,true,listOf(10.0)))
        val extra=settings.addZone(250,0xffaabbcc.toInt())
        assertNotNull(extra.zoneAt(151.0)); assertNull(extra.zoneAt(250.1))
        assertEquals(settings,extra.removeZone(4))
        assertThrows(IllegalArgumentException::class.java) { settings.withBoundary(0,60) }
        assertThrows(IllegalArgumentException::class.java) { settings.removeZone(0) }
    }

    @Test fun contoursJoinNeighborsAcrossSeamAndLeaveMissingSectorsOpen() {
        val a=DistanceReading(30.0,0xffff0000.toInt()); val b=DistanceReading(100.0,0xff00ff00.toInt())
        val ring=DistanceWidgetContour.build(List(8){if(it%2==0)a else b},150).single()
        assertTrue(ring.closed); assertEquals(ring.points.first(),ring.points.last())
        val partial=DistanceWidgetContour.build(listOf(a,null,null,b,b,null,null,b),150)
        assertEquals(2,partial.size); assertTrue(partial.none{it.closed})
        assertTrue(partial.any { line -> line.points.any { it.color!=a.color && it.color!=b.color } })
        val near=DistanceWidgetContour.point(0f,30f,150,a.color)
        val far=DistanceWidgetContour.point(0f,150f,150,a.color)
        assertTrue(far.y<near.y)
        val directions=(0..7).map{DistanceWidgetContour.point(it.toFloat(),30f,150,a.color)}
        assertTrue(directions[1].x>300 && directions[1].y<300)
        assertTrue(directions[3].x>300 && directions[3].y>300)
        assertTrue(directions[5].x<300 && directions[5].y>300)
        assertTrue(directions[7].x<300 && directions[7].y<300)
        assertTrue(DistanceWidgetContour.build(List(8){null},150).isEmpty())
    }

    @Test fun physicalSizeAndIndependentProfilesRoundTrip() {
        val initial=DistanceWidgetSettings(tablet=DistanceWidgetProfile(x=20f,y=30f,borderDp=0),
            cluster=DistanceWidgetProfile(sizePercent=20,x=80f,y=90f))
        val restored=DistanceWidgetSettings.decode(initial.encode())
        assertEquals(initial,restored)
        val tablet=restored.tablet.frame(1920f,1080f)
        assertEquals(500f,tablet.width,0f); assertEquals(620f,tablet.height,0f)
        assertEquals(284f,tablet.x,.01f); assertEquals(138f,tablet.y,.01f)
        val cluster=restored.cluster.frame(1920f,720f)
        assertEquals(100f,cluster.width,0f); assertEquals(124f,cluster.height,0f)
        val small=restored.tablet.frame(240f,300f)
        assertTrue(small.width<=240 && small.height<=300)
        assertEquals(500f/620,small.width/small.height,.0001f)
    }

    @Test fun presetLoadResetAndHelperReattachPreserveManualVisibility() {
        prefs.edit().putBoolean(DistanceWidgetStore.ENABLED,true).putBoolean(DistanceWidgetStore.HIDDEN,true).commit()
        val chosen=DistanceWidgetStore.read(prefs).copy(carModel="sk2",carColor="red",
            tablet=DistanceWidgetProfile(sizePercent=35,x=25f,backgroundPercent=70),
            cluster=DistanceWidgetProfile(sizePercent=60,y=75f,backgroundPercent=85))
        DistanceWidgetStore.save(context,chosen)
        CameraButtonBindings.save(prefs,CameraButtonBindings.Action.DistanceVisibility,
            CameraButtonBindings.Binding(305,CameraButtonBindings.Press.Double))
        DistanceWidgetStore.savePreset(prefs)
        DistanceWidgetStore.applyPreset(context,true)
        assertEquals(100,DistanceWidgetStore.read(prefs).tablet.backgroundPercent)
        assertEquals(100,DistanceWidgetStore.read(prefs).cluster.backgroundPercent)
        assertTrue(DistanceWidgetStore.read(prefs).hiddenByButton)
        assertTrue(prefs.contains(DistanceWidgetStore.PRESET))
        DistanceWidgetStore.applyPreset(context,false)
        val reopened=DistanceWidgetStore.read(context.getSharedPreferences("settings",0))
        assertEquals(chosen.tablet,reopened.tablet); assertEquals(chosen.cluster,reopened.cluster)
        assertEquals(DistancePress.Double,reopened.binding.press)
        assertTrue(reopened.enabled); assertTrue(reopened.hiddenByButton)
        val helper=CameraRuntimePreferences()
        helper.reconcile(prefs.all,true)
        helper.edit().putBoolean(DistanceWidgetStore.HIDDEN,false).apply()
        helper.reconcile(prefs.all,false)
        assertFalse(DistanceWidgetStore.read(helper).hiddenByButton)
    }

    @Test fun currentPresetExportsBothProfilesAndOldImportsKeepNewSettings() {
        val chosen=DistanceWidgetSettings(carModel="sk24",carColor="black",
            tablet=DistanceWidgetProfile(backgroundPercent=70),cluster=DistanceWidgetProfile(x=80f,backgroundPercent=85))
        DistanceWidgetStore.save(context,chosen); DistanceWidgetStore.savePreset(prefs)
        prefs.edit().putBoolean(DistanceWidgetStore.HIDDEN,true).commit()
        val exported=CameraSettingsTransfer.exportCameraPreset(context,prefs)
        val destination=context.getSharedPreferences("restored",0)
        CameraSettingsTransfer.applyCameraPreset(destination,CameraSettingsTransfer.parseCameraPreset(exported))
        assertEquals(chosen,DistanceWidgetStore.read(destination))
        assertTrue(destination.contains(DistanceWidgetStore.PRESET))
        assertFalse(destination.contains(DistanceWidgetStore.HIDDEN))
        val old=CameraSettingsTransfer.exportCameraPreset(prefs)
        CameraSettingsTransfer.applyCameraPreset(prefs,CameraSettingsTransfer.parseCameraPreset(old))
        assertEquals(chosen.copy(hiddenByButton=true),DistanceWidgetStore.read(prefs))
        CameraSettingsTransfer.applyLegacySettings(prefs,CameraSettingsTransfer.parseLegacySettings("<map/>"))
        assertEquals(chosen.copy(hiddenByButton=true),DistanceWidgetStore.read(prefs))
        val config=JSONObject(CompatibilityBundleExporter.sanitizedPreferences(prefs))
        assertEquals(chosen,DistanceWidgetSettings.decode(config.getString(DistanceWidgetStore.CONFIG)))
        assertThrows(IllegalArgumentException::class.java) {
            CameraSettingsTransfer.parseLegacySettings("<map><string name=\"distance_widget_source\">Sos</string></map>")
        }
        prefs.edit().putBoolean("parking_front_enabled",true).commit()
        assertFalse(CameraHelperService.anyParkingEnabled(prefs)); assertFalse(DistanceWidgetStore.read(prefs).enabled)
    }

    @Test fun previewExpiresWithoutAnotherCallbackAndIgnoresAnOlderFrame() {
        val time=SystemClock.elapsedRealtime()
        fun input(at:Long,generation:Long)=Bundle().apply {
            putLong("generation",generation);putLong("received",at);putBoolean("valid",true)
            putDoubleArray("distances",doubleArrayOf(30.0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN))
        }
        DistanceWidgetUiFeed.accept(input(time,1))
        assertTrue(DistanceWidgetUiFeed.frame.valid)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(DistanceWidgetUiFeed.frame.valid)
        DistanceWidgetUiFeed.accept(input(SystemClock.elapsedRealtime(),2))
        DistanceWidgetUiFeed.accept(input(time,1))
        assertEquals(2,DistanceWidgetUiFeed.frame.generation)
        assertTrue(DistanceWidgetUiFeed.frame.valid)
        DistanceWidgetUiFeed.disconnected()
        DistanceWidgetUiFeed.accept(input(time,1))
        assertFalse(DistanceWidgetUiFeed.frame.valid)
    }

    @Test fun backgroundShrinksAroundSameCenterAndLegacyProfilesKeepFullBackground() {
        val original=DistanceWidgetSettings(tablet=DistanceWidgetProfile(x=17f,y=80f),
            cluster=DistanceWidgetProfile(x=90f,y=5f))
        val old=JSONObject(original.encode()).apply {
            getJSONObject("tablet").remove("backgroundPercent")
            getJSONObject("cluster").remove("backgroundPercent")
        }
        assertEquals(original,DistanceWidgetSettings.decode(old.toString()))
        old.getJSONObject("tablet").put("backgroundPercent",2)
        old.getJSONObject("cluster").put("backgroundPercent",120)
        assertEquals(70,DistanceWidgetSettings.decode(old.toString()).tablet.backgroundPercent)
        assertEquals(100,DistanceWidgetSettings.decode(old.toString()).cluster.backgroundPercent)
        for (size in listOf(20,100)) for (percent in 70..100) {
            val profile=original.tablet.copy(sizePercent=size,backgroundPercent=percent)
            val full=profile.frame(1920f,1080f); val background=profile.backgroundFrame(1920f,1080f)
            assertEquals(full.x+full.width/2,background.x+background.width/2,.001f)
            assertEquals(full.y+full.height/2,background.y+background.height/2,.001f)
            assertEquals(full.width*percent/100,background.width,.001f)
            assertEquals(full.height*percent/100,background.height,.001f)
            for (sector in 0..15) {
                val near=DistanceWidgetContour.point(sector/2f,0f,150,7,percent)
                assertEquals(DistanceWidgetContour.point(sector/2f,0f,150,7),near)
                val far=DistanceWidgetContour.point(sector/2f,150f,150,7,percent)
                val oldFar=DistanceWidgetContour.point(sector/2f,150f,150,7)
                assertTrue(kotlin.math.hypot(far.x-300,far.y-300)<=kotlin.math.hypot(oldFar.x-300,oldFar.y-300)+.001f)
                assertEquals(7,far.color)
            }
            val ring=DistanceWidgetContour.build(List(8){DistanceReading(150.0,7)},150,percent).single()
            assertTrue(ring.closed); assertEquals(ring.points.first(),ring.points.last())
        }
    }

    @Test fun detailLoggingCanBeDisabledWithoutAffectingFunctionalDistances() {
        try {
            DiagnosticLogPolicy.configure(false)
            assertFalse(DiagnosticLogPolicy.shouldProduce("distance_frame_detail"))
            assertFalse(DiagnosticLogPolicy.shouldProduce("distance_render_detail"))
            assertTrue(DiagnosticLogPolicy.shouldProduce("distance_source_error"))
            val values=AdasDistanceModel.distances(listOf(AdasDistanceModel.Obstacle(432.75,-.75,10.0,10.0,0.0)))
            assertEquals(30.0,values[0],.0001)
            DiagnosticLogPolicy.configure(true)
            assertTrue(DiagnosticLogPolicy.shouldProduce("distance_frame_detail"))
        } finally { DiagnosticLogPolicy.configure(false) }
    }
}

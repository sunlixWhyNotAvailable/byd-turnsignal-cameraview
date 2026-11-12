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
import com.byd.extend.ui.distance.distanceCoordinateText
import com.byd.extend.ui.distance.distanceCoordinateCommit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE, sdk=[33])
class DistanceWidgetTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun acceptFrame(bundle: Bundle) = DistanceWidgetUiFeed.accept(bundle) { throw AssertionError(it) }
    @Before fun reset() { prefs.edit().clear().commit(); DistanceWidgetUiFeed.disconnected() }

    @Test fun fusedTransportExpiresOnlyAdasAndDisconnectClearsPersistentRadar() {
        val time=SystemClock.elapsedRealtime()
        val radar=ParkingRadarState().apply { begin(0,1); registered(0,1); callback(0x99000062.toInt(),65,time,1) }
        val sources=DistanceSources().apply {
            adas(DistanceFrame(4,time,true,"ready",DoubleArray(8) { 81.0 }))
            radar(radar.snapshot())
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        val frame=sources.frame(SystemClock.elapsedRealtime())
        val bundle=DistanceFrameCodec.encode(frame)
        val decoded=DistanceFrameCodec.decode(bundle) { throw AssertionError(it) }
        assertArrayEquals(frame.distances(),decoded.distances(),0.0)
        assertEquals(frame.radarMask,decoded.radarMask)
        assertEquals(time,decoded.adasReceivedAt)
        acceptFrame(bundle)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertTrue(DistanceWidgetUiFeed.frame.valid)
        assertFalse(DistanceWidgetUiFeed.frame.adasValid)
        assertEquals(65.0,DistanceWidgetUiFeed.frame.distances()[0],0.0)
        assertTrue(DistanceWidgetUiFeed.frame.distances()[1].isNaN())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertTrue(DistanceWidgetUiFeed.frame.fresh(SystemClock.elapsedRealtime()))
        DistanceWidgetUiFeed.disconnected()
        assertFalse(DistanceWidgetUiFeed.frame.valid)
    }

    @Test fun invalidOptionalSourceMetadataFallsBackToExpiringOriginalDistances() {
        val time=SystemClock.elapsedRealtime()
        val bundle=DistanceFrameCodec.encode(DistanceFrame(1,time,true,"ready",DoubleArray(8) { 50.0 }))
        bundle.putInt("radar_mask",256)
        var errors=0
        val decoded=DistanceFrameCodec.decode(bundle) { errors++ }
        assertEquals(1,errors)
        assertEquals(50.0,decoded.distances()[0],0.0)
        assertFalse(decoded.fresh(time+500))
    }

    @Test fun visibilityUsesRawThresholdsAndManualHideSurvivesModeChanges() {
        val settings=DistanceWidgetSettings(enabled=true)
        assertEquals(0, settings.zoneAt(25.0)?.id)
        assertEquals(1, settings.zoneAt(25.001)?.id)
        assertEquals(3, settings.zoneAt(100.0)?.id)
        assertNull(settings.zoneAt(100.001)); assertNull(settings.zoneAt(Double.NaN))
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
        assertFalse(JSONObject(prefs.getString(DistanceWidgetStore.PRESET,null)!!).has("keyCode"))
        DistanceWidgetStore.applyPreset(context,true)
        assertEquals(DistanceWidgetSettings().tablet,DistanceWidgetStore.read(prefs).tablet)
        assertEquals(80,DistanceWidgetStore.read(prefs).tablet.backgroundPercent)
        assertEquals(100,DistanceWidgetStore.read(prefs).cluster.backgroundPercent)
        assertTrue(DistanceWidgetStore.read(prefs).hiddenByButton)
        assertTrue(prefs.contains(DistanceWidgetStore.PRESET))
        val button=CameraButtonBindings.load(prefs,CameraButtonBindings.Action.DistanceVisibility)
        assertEquals(305,button.keyCode); assertEquals(CameraButtonBindings.Press.Double,button.press)
        CameraButtonBindings.reset(prefs,CameraButtonBindings.Action.DistanceVisibility)
        DistanceWidgetStore.applyPreset(context,false)
        val reopened=DistanceWidgetStore.read(context.getSharedPreferences("settings",0))
        assertEquals(chosen.tablet,reopened.tablet); assertEquals(chosen.cluster,reopened.cluster)
        assertEquals(CameraButtonBindings.UNASSIGNED,
            CameraButtonBindings.load(prefs,CameraButtonBindings.Action.DistanceVisibility).keyCode)
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
        val distanceButton=CameraButtonBindings.Action.DistanceVisibility
        CameraButtonBindings.save(prefs,distanceButton,CameraButtonBindings.Binding(305,CameraButtonBindings.Press.Hold))
        val exported=CameraSettingsTransfer.exportCameraPreset(context,prefs)
        val exportedSettings=JSONObject(exported).getJSONObject("settings")
        DistanceWidgetStore.BUTTON_KEYS.forEach { assertFalse(it,exportedSettings.has(it)) }
        for (key in listOf(DistanceWidgetStore.CONFIG,DistanceWidgetStore.PRESET)) {
            val widget=JSONObject(exportedSettings.getString(key))
            assertFalse(widget.has("keyCode")); assertFalse(widget.has("press"))
        }
        val destination=context.getSharedPreferences("restored",0)
        CameraButtonBindings.save(destination,distanceButton,CameraButtonBindings.Binding(88,CameraButtonBindings.Press.Single))
        CameraSettingsTransfer.applyCameraPreset(destination,CameraSettingsTransfer.parseCameraPreset(exported))
        assertEquals(chosen,DistanceWidgetStore.read(destination))
        assertTrue(destination.contains(DistanceWidgetStore.PRESET))
        assertFalse(destination.contains(DistanceWidgetStore.HIDDEN))
        assertEquals(88,CameraButtonBindings.load(destination,distanceButton).keyCode)
        // An older file still carries the button: it imports, but the button stays local.
        exportedSettings.put("distance_widget_key_code",305).put("distance_widget_press","Hold")
            .put("distance_widget_press_android","Hold").put("distance_widget_source","AndroidKey")
            .put(DistanceWidgetStore.CONFIG,JSONObject(exportedSettings.getString(DistanceWidgetStore.CONFIG))
                .put("keyCode",305).put("press","Hold").toString())
        val oldFile=JSONObject(exported).put("settings",exportedSettings).toString()
        CameraSettingsTransfer.applyCameraPreset(destination,CameraSettingsTransfer.parseCameraPreset(oldFile))
        assertEquals(chosen,DistanceWidgetStore.read(destination))
        val kept=CameraButtonBindings.load(destination,distanceButton)
        assertEquals(88,kept.keyCode); assertEquals(CameraButtonBindings.Press.Single,kept.press)
        val old=CameraSettingsTransfer.exportCameraPreset(prefs)
        CameraSettingsTransfer.applyCameraPreset(prefs,CameraSettingsTransfer.parseCameraPreset(old))
        assertEquals(chosen.copy(hiddenByButton=true),DistanceWidgetStore.read(prefs))
        CameraSettingsTransfer.applyLegacySettings(prefs,CameraSettingsTransfer.parseLegacySettings("<map/>"))
        assertEquals(chosen.copy(hiddenByButton=true),DistanceWidgetStore.read(prefs))
        val config=JSONObject(CompatibilityBundleExporter.sanitizedPreferences(prefs))
        assertEquals(chosen,DistanceWidgetSettings.decode(config.getString(DistanceWidgetStore.CONFIG)))
        assertTrue(CameraSettingsTransfer.parseLegacySettings(
            "<map><string name=\"distance_widget_source\">Sos</string></map>").isEmpty())
        prefs.edit().putBoolean("parking_front_enabled",true).commit()
        assertFalse(CameraHelperService.anyParkingEnabled(prefs)); assertFalse(DistanceWidgetStore.read(prefs).enabled)
    }

    @Test fun previewExpiresWithoutAnotherCallbackAndIgnoresAnOlderFrame() {
        val time=SystemClock.elapsedRealtime()
        fun input(at:Long,generation:Long)=Bundle().apply {
            putLong("generation",generation);putLong("received",at);putBoolean("valid",true)
            putDoubleArray("distances",doubleArrayOf(30.0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN))
        }
        acceptFrame(input(time,1))
        assertTrue(DistanceWidgetUiFeed.frame.valid)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(DistanceWidgetUiFeed.frame.valid)
        acceptFrame(input(SystemClock.elapsedRealtime(),2))
        acceptFrame(input(time,1))
        assertEquals(2,DistanceWidgetUiFeed.frame.generation)
        assertTrue(DistanceWidgetUiFeed.frame.valid)
        DistanceWidgetUiFeed.disconnected()
        acceptFrame(input(time,1))
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
            assertFalse(DiagnosticLogPolicy.shouldProduce("distance_animation_metrics"))
            assertTrue(DiagnosticLogPolicy.shouldProduce("distance_source_error"))
            val values=AdasDistanceModel.distances(listOf(AdasDistanceModel.Obstacle(432.75,-.75,10.0,10.0,0.0)))
            assertEquals(30.0,values[0],.0001)
            DiagnosticLogPolicy.configure(true)
            assertTrue(DiagnosticLogPolicy.shouldProduce("distance_frame_detail"))
        } finally { DiagnosticLogPolicy.configure(false) }
    }

    @Test fun coordinateDisplayAndUneditedBlurPreserveStoredPrecisionInBothProfiles() {
        val original = DistanceWidgetSettings(tablet=DistanceWidgetProfile(x=12.345678f,y=99.87654f),
            cluster=DistanceWidgetProfile(x=43.12345f,y=50f))
        assertEquals("12.3",distanceCoordinateText(original.tablet.x))
        assertEquals("99.9",distanceCoordinateText(original.tablet.y))
        assertEquals("50",distanceCoordinateText(original.cluster.y))
        assertEquals("0",distanceCoordinateText(0f)); assertEquals("100",distanceCoordinateText(100f))
        val committed=original.copy(tablet=original.tablet.copy(
            x=distanceCoordinateCommit(original.tablet.x,distanceCoordinateText(original.tablet.x)),
            y=distanceCoordinateCommit(original.tablet.y,distanceCoordinateText(original.tablet.y))))
        DistanceWidgetStore.save(context,committed); DistanceWidgetStore.savePreset(prefs)
        assertEquals(original,DistanceWidgetStore.read(prefs))
        assertEquals(original,DistanceWidgetSettings.decode(committed.encode()))
        assertEquals(13.3f,distanceCoordinateCommit(original.tablet.x,"13.3"),0f)
        assertEquals(12.345f,distanceCoordinateCommit(original.tablet.x,"12.345"),0f)
    }

    @Test fun objectMetadataRoundTripsAndBadOptionalMetadataKeepsRealDistancesWithCause() {
        val time=SystemClock.elapsedRealtime()
        val values=doubleArrayOf(30.0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN)
        val row=DistanceObject(17,2,9,values)
        val input=DistanceFrame(3,time,true,"ready",values,listOf(row))
        val restored=DistanceFrameCodec.decode(DistanceFrameCodec.encode(input)) { throw AssertionError(it) }
        assertArrayEquals(values,restored.distances(),0.0)
        assertEquals(17,restored.objects.single().id); assertEquals(2,restored.objects.single().type)
        assertEquals(9,restored.objects.single().continuity)
        restored.objects.single().distances()[0]=120.0
        assertEquals(30.0,restored.objects.single().distance(0),0.0)
        val bad=DistanceFrameCodec.encode(input).apply {
            putParcelableArrayList("objects", arrayListOf(Bundle().apply {putDoubleArray("distances",doubleArrayOf(1.0))}))
        }
        val errors=mutableListOf<Exception>()
        val compatible=DistanceFrameCodec.decode(bad) { errors.add(it) }
        assertTrue(compatible.objects.isEmpty()); assertArrayEquals(values,compatible.distances(),0.0)
        assertTrue(errors.single() is IllegalArgumentException)
        assertTrue(errors.single().message!!.contains("Eight distance sectors"))
        acceptFrame(DistanceFrameCodec.encode(input))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
        assertFalse(DistanceWidgetUiFeed.frame.valid)
        assertTrue(DistanceWidgetUiFeed.frame.objects.isEmpty())
    }

    @Test fun physicalCoverageRoundTripsAndOptionalCorruptionDoesNotDiscardIdentityOrDistances() {
        val result=AdasDistanceModel.evaluate(listOf(AdasDistanceModel.Obstacle(17,2,-405.25,-.75,400.0,180.0,0.0)))
        val input=DistanceFrame(3,1000,true,"ready",result.distances,DistanceObjectContinuity().accept(result.objects))
        val restored=DistanceFrameCodec.decode(DistanceFrameCodec.encode(input)) { throw AssertionError(it) }
        assertEquals(input.objects,restored.objects)
        restored.objects.single().geometry()!![0]=0.0
        assertEquals(-405.25,restored.objects.single().geometry()!![0],0.0)
        val broken=DistanceFrameCodec.encode(input)
        @Suppress("DEPRECATION")
        val rows=broken.getParcelableArrayList<Bundle>("objects")!!
        rows.single().putDoubleArray("coverage",doubleArrayOf(1.0))
        val errors=mutableListOf<Exception>()
        val compatible=DistanceFrameCodec.decode(broken) { errors.add(it) }
        assertArrayEquals(input.distances(),compatible.distances(),0.0)
        assertEquals(17,compatible.objects.single().id)
        assertFalse(compatible.objects.single().hasCoverage())
        assertEquals("Eight contour intervals required",errors.single().message)
    }

    @Test fun extendedDiagnosticContainsRawIdentityGeometryAndWinningIndicesInSerializableForm() {
        val result=AdasDistanceModel.evaluate(listOf(
            AdasDistanceModel.Obstacle(17,2,-405.25,-.75,400.0,180.0,0.0),
            AdasDistanceModel.Obstacle(18,3,-500.25,-.75,400.0,180.0,0.0)))
        val frame=DistanceFrame(3,1000,true,"ready",result.distances,DistanceObjectContinuity().accept(result.objects))
        val sources=DistanceSources().apply { adas(frame) }
        val json=JSONObject(DiagnosticEvent("distance","distance_frame_detail",*sources.diagnostic(sources.frame(1000))).line())
        assertEquals("distance_frame_detail",json.getString("kind"))
        val obj=json.getJSONArray("adas_objects").getJSONObject(0)
        assertEquals(17,obj.getInt("id")); assertEquals(2,obj.getInt("type"))
        assertEquals(-405.25,obj.getJSONObject("obb").getDouble("x_cm"),0.0)
        assertEquals(180.0,obj.getJSONObject("obb").getDouble("width_cm"),0.0)
        assertTrue(obj.getJSONArray("sector_cm").isNull(0))
        assertTrue(obj.getJSONArray("contour_eighth_turns").getDouble(6)>3.5)
        val indices=json.getJSONArray("sectors").getJSONObject(4).getJSONArray("adas_object_indices")
        assertEquals(1,indices.length()); assertEquals(0,indices.getInt(0))
    }
}

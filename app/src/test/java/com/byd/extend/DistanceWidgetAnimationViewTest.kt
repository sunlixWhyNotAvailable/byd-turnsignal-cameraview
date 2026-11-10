package com.byd.extend

import android.app.Activity
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.View
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real View/Looper scheduling with application resources; no helper or OEM responses replaced. */
@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[33])
class DistanceWidgetAnimationViewTest {
    private fun frame(cm:Double): DistanceFrame {
        val values=DoubleArray(8) { if(it==0) cm else Double.NaN }
        return DistanceFrame(1,SystemClock.elapsedRealtime(),true,"ready",values,
            listOf(DistanceObject(1,1,1,values)))
    }
    @Test fun scheduledWorkEndsWhenHiddenOrDetachedAndUnchangedTelemetryDoesNotStartIt() {
        // Robolectric's legacy graphics defaults to invisible windows, even after Activity.visible().
        val previousVisibility=System.setProperty("robolectric.areWindowsMarkedVisible","true")
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            DiagnosticLogPolicy.configure(true)
            val view=DistanceWidgetView(activity.get())
            activity.get().setContentView(view)
            activity.visible()
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.isAttachedToWindow)
            assertEquals(View.VISIBLE,view.windowVisibility)
            var steps=0
            var retargets=0L
            view.animationTiming={_,_,retarget -> steps++;retargets+=retarget}
            view.update(DistanceWidgetSettings(),0f,frame(30.0))
            view.update(DistanceWidgetSettings(),0f,frame(100.0))
            assertEquals(1L,retargets)
            val initial=steps
            repeat(2) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34)) }
            assertEquals(2,steps-initial)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(34))
            assertEquals(3,steps-initial)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(3,steps-initial)
            view.update(DistanceWidgetSettings(),0f,frame(30.0))
            view.update(DistanceWidgetSettings(),0f,frame(100.0))
            view.stopAnimation() // The helper hides its surface without detaching this View.
            val stopped=steps
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(stopped,steps)
            view.update(DistanceWidgetSettings(),0f,frame(30.0))
            view.update(DistanceWidgetSettings(),0f,frame(100.0))
            activity.get().setContentView(View(activity.get()))
            assertFalse(view.isAttachedToWindow)
            val detached=steps
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(detached,steps)
            activity.get().setContentView(view)
            view.update(DistanceWidgetSettings(),0f,frame(100.0))
            val unchanged=steps
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(unchanged,steps)
        } finally {
            DiagnosticLogPolicy.configure(false)
            activity.pause().stop().destroy()
            if(previousVisibility==null) System.clearProperty("robolectric.areWindowsMarkedVisible")
            else System.setProperty("robolectric.areWindowsMarkedVisible",previousVisibility)
        }
    }

    @Test fun fusedFrameKeepsOriginalAdasAgeAndStaticRadarSchedulesNoAnimation() {
        val previous=System.setProperty("robolectric.areWindowsMarkedVisible","true")
        val activity=Robolectric.buildActivity(Activity::class.java).setup()
        try {
            DiagnosticLogPolicy.configure(true)
            val view=DistanceWidgetView(activity.get())
            activity.get().setContentView(view); activity.visible(); shadowOf(Looper.getMainLooper()).idle()
            val time=SystemClock.elapsedRealtime()
            val radar=ParkingRadarState().apply { begin(0,1); registered(0,1); callback(0x99000062.toInt(),65,time,1) }
            val sources=DistanceSources().apply { adas(frame(81.0)); radar(radar.snapshot()) }
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            view.update(DistanceWidgetSettings(),0f,sources.frame(SystemClock.elapsedRealtime()))
            assertEquals(time,view.sourceReceivedAt)
            sources.adas(DistanceFrame.unavailable(1,SystemClock.elapsedRealtime(),"stale"))
            view.update(DistanceWidgetSettings(),0f,sources.frame(SystemClock.elapsedRealtime()))
            var steps=0; view.animationTiming={_,_,_->steps++}
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5))
            assertEquals(0,steps)
        } finally {
            DiagnosticLogPolicy.configure(false); activity.pause().stop().destroy()
            if(previous==null) System.clearProperty("robolectric.areWindowsMarkedVisible")
            else System.setProperty("robolectric.areWindowsMarkedVisible",previous)
        }
    }
}

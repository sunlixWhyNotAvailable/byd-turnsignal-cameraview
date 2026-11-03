package com.byd.extend

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Identical drawing path for the app preview and the non-touchable helper overlay. */
class DistanceWidgetView(context: Context) : View(context) {
    private var settings = DistanceWidgetSettings()
    private var radius = 0f
    private var pixelScale = 1f
    var drawTiming: ((Long) -> Unit)? = null
    private var readings: List<DistanceReading?> = List(8) { null }
    private var contours = emptyList<DistanceContour>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private var art: DistanceCarArt? = null
    private var bitmap: Bitmap? = null
    private val clip = Path()

    fun update(value: DistanceWidgetSettings, cornerRadius: Float, frame: DistanceFrame, displayScale: Float = 1f) {
        val nextRadius = cornerRadius * resources.displayMetrics.density * displayScale
        val nextReadings = if (frame.fresh(android.os.SystemClock.elapsedRealtime()))
            frame.distances().map { cm -> value.zoneAt(cm)?.let { DistanceReading(cm, it.color) } }
            else List(8) { null }
        if (art != null && settings == value && radius == nextRadius && readings == nextReadings && pixelScale == displayScale) return
        settings = value
        radius = nextRadius
        pixelScale = displayScale
        val car = DistanceCarCatalog.resolve(value.carModel, value.carColor)
        if (art != car) {
            bitmap = BitmapFactory.decodeResource(resources, car.drawable, BitmapFactory.Options().apply { inScaled = false })
            art = car
        }
        readings = nextReadings
        contours = DistanceWidgetContour.build(readings, value.zones.last().endCm, value.profile.backgroundPercent)
        alpha = 1f - value.profile.transparency / 100f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val timing = drawTiming.takeIf { DiagnosticLogPolicy.extended() }
        val started = if (timing != null) android.os.SystemClock.elapsedRealtimeNanos() else 0
        super.onDraw(canvas)
        val bounds = RectF(0f, 0f, width.toFloat(), height.toFloat())
        val inset = (1 - settings.profile.backgroundPercent / 100f) / 2
        bounds.inset(width * inset, height * inset)
        clip.reset(); clip.addRoundRect(bounds, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip); canvas.drawColor(Color.BLACK)
        val factor = min(width / 500f, height / 620f)
        canvas.save(); canvas.translate(width / 2f - 300 * factor, height / 2f - 300 * factor)
        canvas.scale(factor, factor)
        art?.let { car -> bitmap?.let { image ->
            val scale = min(136f / car.width, 246f / car.height)
            val w = car.width * scale; val h = car.height * scale
            paint.shader = null; paint.style = Paint.Style.FILL
            canvas.drawBitmap(image, Rect(car.left, car.top, car.left + car.width, car.top + car.height),
                RectF(300 - w/2, 300 - h/2, 300 + w/2, 300 + h/2), paint)
        } }
        paint.strokeWidth = 8f; paint.strokeCap = Paint.Cap.ROUND
        contours.forEach { contour -> contour.points.zipWithNext().forEach { (a,b) ->
            if (a.x != b.x || a.y != b.y) {
                paint.shader = LinearGradient(a.x,a.y,b.x,b.y,a.color,b.color,Shader.TileMode.CLAMP)
                canvas.drawLine(a.x,a.y,b.x,b.y,paint)
            }
        } }
        paint.shader = null
        if (settings.showDistance) readings.forEachIndexed { index, reading -> reading?.let {
            val p = DistanceWidgetContour.point(index.toFloat(), it.cm.toFloat(), settings.zones.last().endCm,
                it.color, settings.profile.backgroundPercent)
            val dx = p.x-300; val dy=p.y-300; val length=hypot(dx,dy)
            text.color=it.color; text.textSize=24f
            canvas.drawText(it.cm.roundToInt().toString(),p.x+dx/length*36f,
                p.y+dy/length*36f-(text.ascent()+text.descent())/2,text)
        } }
        canvas.restore()
        if (settings.profile.borderDp > 0) {
            val stroke = settings.profile.borderDp * resources.displayMetrics.density * pixelScale
            paint.style=Paint.Style.STROKE; paint.strokeWidth=stroke; paint.color=settings.profile.borderColor
            bounds.inset(stroke/2,stroke/2)
            canvas.drawRoundRect(bounds,max(0f,radius-stroke/2),max(0f,radius-stroke/2),paint)
            paint.style=Paint.Style.FILL
        }
        canvas.restore()
        timing?.invoke((android.os.SystemClock.elapsedRealtimeNanos() - started) / 1000)
    }
}

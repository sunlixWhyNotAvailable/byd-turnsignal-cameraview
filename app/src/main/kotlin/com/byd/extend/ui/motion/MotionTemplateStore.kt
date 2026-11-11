package com.byd.extend.ui.motion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.byd.extend.R
import com.byd.extend.OptionalFonts
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class MotionTemplateStore(private val context: Context) {
    val preferences = context.getSharedPreferences("motion_screen", Context.MODE_PRIVATE)
    private val images = File(context.filesDir, "motion_backgrounds").apply { mkdirs() }
    private var previewBackground: String? = null
    private var previewImage: Bitmap? = null
    private val fontResolver = OptionalFonts(context)
    fun font(index: Int, style: Int = Typeface.NORMAL): Typeface = fontResolver.get(index, style)

    fun read(): MotionTemplates {
        val json = preferences.getString("templates", null) ?: return MotionTemplates()
        return try {
            val root = JSONObject(json)
            val saved = root.getJSONArray("saved")
            MotionTemplates((0 until saved.length()).map { decode(saved.getJSONObject(it)) }, root.getString("active"))
        } catch (_: Exception) {
            // Preserve the original preference; never overwrite unreadable saved work on opening.
            MotionTemplates()
        }
    }

    fun write(state: MotionTemplates) {
        val json = JSONObject().put("active", state.activeId)
            .put("saved", JSONArray(state.saved.map { encode(it) }))
        check(preferences.edit().putString("templates", json.toString()).commit())
    }

    private fun encode(template: MotionTemplate) = JSONObject().put("id", template.id).put("name", template.name)
        .put("background", template.background).put("fill", template.fill)
        .put("texts", JSONArray(template.texts.map { t -> JSONObject()
            .put("id", t.id).put("default", t.defaultCaption).put("text", t.text)
            .put("x", t.x).put("y", t.y).put("width", t.width).put("size", t.size).put("color", t.color)
            .put("font", t.font).put("bold", t.bold).put("italic", t.italic)
            .put("alignment", t.alignment).put("effect", t.effect) }))

    private fun decode(json: JSONObject): MotionTemplate {
        val blocks = json.getJSONArray("texts")
        return MotionTemplate(json.getString("id"), json.getString("name"), json.getString("background"),
            json.getBoolean("fill"), (0 until blocks.length()).map { i ->
                val t = blocks.getJSONObject(i)
                MotionText(t.getString("id"), t.getInt("default").coerceIn(0, 2), t.getString("text"),
                    t.getDouble("x").toFloat().coerceIn(0f, 1f), t.getDouble("y").toFloat().coerceIn(0f, 1f),
                    t.getDouble("width").toFloat().coerceIn(.2f, 1f), t.getInt("size").coerceIn(16, 96),
                    t.getInt("color"), t.getInt("font").coerceIn(0, 7), t.getBoolean("bold"), t.getBoolean("italic"),
                    t.getInt("alignment").coerceIn(0, 3), t.getInt("effect").coerceIn(0, 2))
            })
    }

    fun import(uri: Uri): String {
        val file = File(images, UUID.randomUUID().toString() + ".image")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { output ->
                val buffer = ByteArray(16384)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 32L * 1024 * 1024) { "Image exceeds 32 MiB" }
                    output.write(buffer, 0, count)
                }
            } }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp"))
            requireNotNull(bitmap(file.name)).recycle()
            return file.name
        } catch (failure: Exception) {
            file.delete()
            throw failure
        }
    }

    fun bitmap(background: String): Bitmap? {
        val resource = when (background) {
            "lens" -> R.drawable.motion_tesla_lens
            "iris" -> R.drawable.motion_tesla_iris
            "owl" -> R.drawable.motion_tesla_owl
            else -> null
        }
        if (resource != null) return BitmapFactory.decodeResource(context.resources, resource)
        if (!background.matches(Regex("[a-f0-9-]+\\.image"))) return null
        val file = File(images, background)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        options.inSampleSize = 1
        while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        return BitmapFactory.decodeFile(file.path, options)
    }

    @Synchronized
    fun previewBitmap(background: String): Bitmap? {
        if (previewBackground != background || previewImage == null) {
            previewImage = bitmap(background)?.also { it.prepareToDraw() }
            previewBackground = background
        }
        return previewImage
    }

    fun discardUnused(background: String, state: MotionTemplates) {
        if (background.matches(Regex("[a-f0-9-]+\\.image")) && state.saved.none { it.background == background }) {
            File(images, background).delete()
        }
    }
}

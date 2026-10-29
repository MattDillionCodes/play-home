package com.matthew.playhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * A wallpaper: one of the six built-in PlayStation-style photos ([res]; 1920x1080 sRGB JPEGs, the
 * TV's fastest format), one you added from your phone ([file]), or Live, the animated theme drawn
 * in code (neither). Identified by [id], which is what's saved; the built-ins keep the names they
 * had when this was an enum, so saved choices still work.
 */
class Wallpaper private constructor(val id: String, val title: String, val res: Int = 0, val file: File? = null) {
    val isPhoto get() = res != 0 || file != null
    val isMine get() = file != null

    override fun equals(other: Any?) = other is Wallpaper && other.id == id
    override fun hashCode() = id.hashCode()

    companion object {
        val WAVES = Wallpaper("WAVES", "Waves", R.drawable.wp_waves)
        val PARTICLES = Wallpaper("PARTICLES", "Particles", R.drawable.wp_particles)
        val FLOW = Wallpaper("FLOW", "Flow", R.drawable.wp_flow)
        val SHAPES = Wallpaper("SHAPES", "Shapes", R.drawable.wp_shapes)
        val GLOW = Wallpaper("GLOW", "Glow", R.drawable.wp_glow)
        val LINES = Wallpaper("LINES", "Lines", R.drawable.wp_lines)
        val LIVE = Wallpaper("LIVE", "Live")
        private val builtIn = listOf(WAVES, PARTICLES, FLOW, SHAPES, GLOW, LINES, LIVE)

        /** How many of your own the picker keeps; hold OK on one to remove it and make room. */
        const val MAX_MINE = 6

        /** Where your own live: files/wallpapers/<time added>.jpg, already cleaned to 1920x1080. */
        fun folder(context: Context) = File(context.filesDir, "wallpapers").apply { mkdirs() }

        fun of(file: File) = Wallpaper("file:${file.name}", "Yours", file = file)

        /** Yours, oldest first, titled "Yours 1", "Yours 2", ... */
        fun mine(context: Context): List<Wallpaper> =
            folder(context).listFiles { f -> f.extension == "jpg" }.orEmpty().sortedBy { it.name }
                .mapIndexed { i, f -> Wallpaper("file:${f.name}", "Yours ${i + 1}", file = f) }

        /** Built-ins (Live last), then yours. */
        fun all(context: Context) = builtIn + mine(context)

        fun named(context: Context, id: String?) = all(context).find { it.id == id } ?: WAVES
    }
}

object Wallpapers {
    /**
     * Decoding a full-screen JPEG takes tens of ms: off the UI thread, one at a time. A task that
     * throws (an app's odd banner, a decode running out of memory) is logged and dropped; on
     * Android an uncaught throw on any thread would otherwise take Home down with it.
     */
    val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PlayHomeWorker").apply {
            setUncaughtExceptionHandler { _, e -> Log.w("PlayHome", "worker task failed", e) }
        }
    }

    /** Full size, unscaled (drawable-nodpi), in sRGB so the TV does no per-pixel colour conversion. */
    fun decode(context: Context, w: Wallpaper): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inScaled = false
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        return when {
            w.file != null -> BitmapFactory.decodeFile(w.file.path, options)
            w.res != 0 -> BitmapFactory.decodeResource(context.resources, w.res, options)
            else -> null
        }
    }

    /** A 240x135 thumbnail for the picker; Live gets a drawn stand-in for its animated theme. */
    /** Thumbnails (~130 KB each) decoded once, so reopening the picker decodes nothing. UI thread only. */
    private val thumbs = HashMap<String, Bitmap>()

    fun thumb(context: Context, w: Wallpaper): Bitmap {
        val key = w.file?.let { it.path + "@" + it.lastModified() } ?: w.id
        thumbs[key]?.let { return it }
        return decodeThumb(context, w).also { thumbs[key] = it }
    }

    /** Drops the thumbnails of wallpapers that are gone (your own, removed). */
    fun forgetThumbsExcept(keep: List<Wallpaper>) {
        val live = keep.map { w -> w.file?.let { it.path + "@" + it.lastModified() } ?: w.id }.toSet()
        thumbs.keys.retainAll(live)
    }

    private fun decodeThumb(context: Context, w: Wallpaper): Bitmap {
        val small = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = 8
        }
        if (w.file != null) BitmapFactory.decodeFile(w.file.path, small)?.let { return it }
        if (w.res != 0) return BitmapFactory.decodeResource(context.resources, w.res, small)
        val b = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, 70f, 135f, intArrayOf(Color.rgb(2, 5, 14), Color.rgb(4, 14, 38), Color.rgb(9, 30, 78)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        c.drawCircle(190f, 18f, 130f, Paint().apply {
            shader = RadialGradient(190f, 18f, 130f, Color.argb(120, 34, 98, 220), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f; color = Color.argb(70, 160, 205, 255) }
        for (k in 0..1) {
            var px = 0f
            var py = 100f + k * 10f
            for (x in 6..240 step 6) {
                val y = 100f + k * 10f + 8f * kotlin.math.sin(x / 40f + k * 2f)
                c.drawLine(px, py, x.toFloat(), y, line)
                px = x.toFloat(); py = y
            }
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 220, 235, 255) }
        listOf(30f to 40f, 80f to 70f, 140f to 30f, 200f to 80f, 110f to 110f, 170f to 55f).forEach { (x, y) -> c.drawCircle(x, y, 1.6f, dot) }
        return b
    }
}

/** The picker's "Add your own" tile: a plus on PlayStation blue. */
fun addWallpaperThumb(): Bitmap {
    val b = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888)
    val c = Canvas(b)
    c.drawPaint(Paint().apply {
        shader = LinearGradient(0f, 0f, 240f, 135f, Color.rgb(28, 92, 196), Color.rgb(8, 22, 58), Shader.TileMode.CLAMP)
    })
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 7f
        strokeCap = Paint.Cap.ROUND
    }
    c.drawLine(120f, 44f, 120f, 91f, p)
    c.drawLine(96.5f, 67.5f, 143.5f, 67.5f, p)
    return b
}

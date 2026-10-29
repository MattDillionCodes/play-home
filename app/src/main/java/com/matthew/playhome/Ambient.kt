package com.matthew.playhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The backdrop: the wallpaper (a photo, or Live: a gradient with a soft bloom, two slow PS4-style
 * waves and drifting PS5-style motes of light), the focused app's banner as hero art fading in
 * from the right, and shades that keep text readable. Live animates only while [awake]; Home puts
 * it to sleep a few seconds after the last remote press, and photos never animate at all, so an
 * idle TV isn't redrawing the screen 60 times a second.
 *
 * Colours follow [Look.dark] when the view is created; in Light mode photos and art are washed
 * pale towards the background tone so dark text reads over them.
 */
class AmbientView(context: Context) : View(context) {
    private class Mote(val x: Float, val y: Float, val r: Float, val rise: Float, val sway: Float, val alpha: Float, val phase: Float)

    private val dark = Look.dark
    private val motes = ArrayList<Mote>()
    private val dot = softDot(64)
    private val dotPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        if (!dark) colorFilter = PorterDuffColorFilter(Look.blue, PorterDuff.Mode.SRC_IN) // blue motes on a pale sky
    }
    private val base = Paint()
    private val bloom = Paint()
    private val shadeLeft = Paint()
    private val shadeBottom = Paint()
    private val shadeTop = Paint()
    private val waveFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val waveLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1.5f)
    }
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { if (!dark) colorFilter = wash(0.38f) }
    private val artPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        // Hero art sits back a little: dimmed in Dark, washed pale in Light.
        colorFilter = if (!dark) wash(0.6f) else ColorMatrixColorFilter(ColorMatrix().apply {
            setScale(0.7f, 0.7f, 0.7f, 1f) // full colour, just dimmed enough for white text
        })
    }
    private val wave = Path()
    private val box = RectF()

    private var art: Bitmap? = null
    private var oldArt: Bitmap? = null
    private var mix = 1f
    private val fade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 520
        interpolator = Look.ease
        addUpdateListener { mix = it.animatedValue as Float; invalidate() }
    }

    private var wallpaper = Wallpaper.LIVE
    private var photo: Bitmap? = null
    private var oldPhoto: Bitmap? = null
    private var photoMix = 1f
    private val photoFade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 600
        interpolator = Look.ease
        addUpdateListener {
            photoMix = it.animatedValue as Float
            if (it.animatedFraction >= 1f) oldPhoto = null // faded out: let it go (8 MB)
            invalidate()
        }
    }
    private var generation = 0 // drops a slow decode that a newer choice has overtaken

    // Photo wallpapers: photo + hero art + shades are composed into one opaque bitmap off the UI
    // thread, so each frame the TV's GPU fills the screen once instead of four or five times.
    private var frame: Bitmap? = null
    private var oldFrame: Bitmap? = null
    private var frameMix = 1f
    private val framePaint = Paint()
    /** A finished-with frame for the next compose to draw into, instead of allocating 8 MB per tile. */
    private val spare = AtomicReference<Bitmap?>(null)
    private val frameFade = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 400
        interpolator = Look.ease
        addUpdateListener {
            frameMix = it.animatedValue as Float
            if (it.animatedFraction >= 1f) { spare.set(oldFrame); oldFrame = null } // faded out: recycle it
            invalidate()
        }
    }
    @Volatile private var composeTicket = 0 // read on the worker, to skip composes already overtaken

    private var t = 0f // seconds of animation so far; stands still while asleep, so nothing jumps on waking
    private var lastFrame = 0L

    var awake = false
        set(v) {
            if (field == v) return
            field = v
            lastFrame = 0L
            if (v) invalidate()
        }

    /** Crossfades to [next] (null: just the wallpaper). */
    fun showArt(next: Bitmap?) {
        if (next === art) return
        if (mix >= 0.5f) oldArt = art // mid-fade, keep whichever is mostly on screen as the outgoing one
        art = next
        if (wallpaper.isPhoto) recompose() else fade.start()
    }

    /** Switches wallpaper with a crossfade; photos decode in the background first. */
    fun setWallpaper(next: Wallpaper) {
        if (next == wallpaper && (photo != null || !next.isPhoto)) return
        wallpaper = next
        val ticket = ++generation
        Wallpapers.worker.execute {
            val decoded = Wallpapers.decode(context, next)
            post {
                if (ticket != generation) return@post
                val previous = photo
                photo = decoded
                if (next.isPhoto) {
                    oldPhoto = null // photo to photo: the composed frames do the crossfade
                    recompose()
                } else { // to Live: the composed frame steps aside and the live layers fade the old photo out
                    oldPhoto = previous
                    frame = null
                    oldFrame = null
                    photoFade.start()
                }
                invalidate()
            }
        }
    }

    /** Rebuilds the composed frame for the current photo and art, then crossfades to it. */
    private fun recompose() {
        val p = photo ?: return
        val w = width
        val h = height
        if (w == 0 || h == 0) return // onSizeChanged calls back once there's a size
        val a = art
        val ticket = ++composeTicket
        Wallpapers.worker.execute {
            // Holding ◀ ▶ queues a compose per tile; only the newest is worth 8 MB and a full redraw.
            if (ticket != composeTicket) return@execute
            val composed = compose(p, a, w, h)
            post {
                if (ticket != composeTicket) return@post
                oldFrame = if (frameMix >= 0.5f) frame else oldFrame
                frame = composed
                frameFade.start()
            }
        }
    }

    /** Photo, art and shades flattened into one bitmap, with the same filters the live path uses. */
    private fun compose(photo: Bitmap, art: Bitmap?, w: Int, h: Int): Bitmap {
        // The photo is opaque and drawn over all of it, so a reused frame needs no clearing.
        val b = spare.getAndSet(null)?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val fw = w.toFloat()
        val fh = h.toFloat()
        c.drawBitmap(photo, null, RectF(0f, 0f, fw, fh), Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = photoPaint.colorFilter })
        if (art != null) {
            c.drawBitmap(art, null, RectF(fw * 0.28f, 0f, fw, fh * 0.72f),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = artPaint.colorFilter })
        }
        c.drawRect(0f, 0f, fw * 0.62f, fh, Paint().apply { shader = shadeLeft.shader })
        c.drawRect(0f, fh * 0.42f, fw, fh, Paint().apply { shader = shadeBottom.shader })
        c.drawRect(0f, 0f, fw, fh * 0.24f, Paint().apply { shader = shadeTop.shader })
        return b
    }

    /** A colour filter that keeps [keep] of the image and fills the rest with the background tone. */
    private fun wash(keep: Float): ColorMatrixColorFilter {
        val add = 1f - keep
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            keep, 0f, 0f, 0f, add * Color.red(Look.ink),
            0f, keep, 0f, 0f, add * Color.green(Look.ink),
            0f, 0f, keep, 0f, add * Color.blue(Look.ink),
            0f, 0f, 0f, 1f, 0f,
        )))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        val clamp = Shader.TileMode.CLAMP
        base.shader = LinearGradient(0f, 0f, fw * 0.3f, fh,
            if (dark) intArrayOf(Color.rgb(2, 5, 14), Color.rgb(4, 14, 38), Color.rgb(9, 30, 78))
            else intArrayOf(Color.rgb(246, 249, 253), Color.rgb(232, 239, 249), Color.rgb(206, 222, 244)),
            floatArrayOf(0f, 0.55f, 1f), clamp)
        val bloomColor = if (dark) Color.rgb(34, 98, 220) else Color.rgb(120, 175, 245)
        bloom.shader = RadialGradient(fw * 0.8f, fh * 0.12f, fw * 0.55f,
            Look.withAlpha(bloomColor, if (dark) 120 else 90), Look.withAlpha(bloomColor, 0), clamp)
        shadeLeft.shader = LinearGradient(0f, 0f, fw * 0.62f, 0f,
            intArrayOf(ink(240), ink(160), ink(0)), floatArrayOf(0f, 0.45f, 1f), clamp)
        shadeBottom.shader = LinearGradient(0f, fh, 0f, fh * 0.42f, ink(250), ink(0), clamp)
        shadeTop.shader = LinearGradient(0f, 0f, 0f, fh * 0.24f, ink(170), ink(0), clamp)
        val waveColor = if (dark) Color.rgb(110, 170, 255) else Look.blue
        waveFill.shader = LinearGradient(0f, fh * 0.6f, 0f, fh,
            Look.withAlpha(waveColor, if (dark) 26 else 18), Look.withAlpha(waveColor, 0), clamp)
        val rnd = Random(5)
        motes.clear()
        repeat(MOTES) {
            motes += Mote(
                x = rnd.nextFloat() * fw,
                y = rnd.nextFloat() * fh,
                r = dpf(1.2f + rnd.nextFloat() * 3.2f),
                rise = dpf(5f + rnd.nextFloat() * 12f),
                sway = dpf(8f + rnd.nextFloat() * 18f),
                alpha = 0.15f + rnd.nextFloat() * 0.45f,
                phase = rnd.nextFloat() * 6.28f,
            )
        }
        if (wallpaper.isPhoto) recompose()
    }

    private fun ink(alpha: Int) = Look.withAlpha(Look.ink, alpha)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val live = !wallpaper.isPhoto
        if (!live && frame != null) {
            // Composed photo frame: one opaque full-screen draw (two only while crossfading).
            oldFrame?.takeIf { frameMix < 1f }?.let { canvas.drawBitmap(it, 0f, 0f, null) }
            framePaint.alpha = (frameMix * 255).toInt()
            canvas.drawBitmap(frame!!, 0f, 0f, framePaint)
            return
        }
        if (awake && live) {
            val now = SystemClock.uptimeMillis()
            if (lastFrame != 0L) t += min(0.05f, (now - lastFrame) / 1000f)
            lastFrame = now
        } else {
            lastFrame = 0L
        }
        canvas.drawRect(0f, 0f, w, h, base)
        if (live) canvas.drawCircle(w * 0.8f, h * 0.12f, w * 0.55f, bloom) // only where the bloom reaches
        box.set(0f, 0f, w, h)
        oldPhoto?.takeIf { photoMix < 1f }?.let {
            photoPaint.alpha = ((1f - photoMix) * 255).toInt()
            canvas.drawBitmap(it, null, box, photoPaint)
        }
        photo?.let {
            photoPaint.alpha = (photoMix * 255).toInt()
            canvas.drawBitmap(it, null, box, photoPaint)
        }
        box.set(w * 0.28f, 0f, w, h * 0.72f) // hero art: 16:9, top right; its faded edges melt into the hub
        oldArt?.takeIf { mix < 1f }?.let {
            artPaint.alpha = ((1f - mix) * 255).toInt()
            canvas.drawBitmap(it, null, box, artPaint)
        }
        art?.let {
            artPaint.alpha = (mix * 255).toInt()
            canvas.drawBitmap(it, null, box, artPaint)
        }
        // Each shade covers only its own band, to keep full-screen overdraw low on the TV's GPU.
        canvas.drawRect(0f, 0f, w * 0.62f, h, shadeLeft)
        canvas.drawRect(0f, h * 0.42f, w, h, shadeBottom)
        canvas.drawRect(0f, 0f, w, h * 0.24f, shadeTop)
        if (live) {
            drawWaves(canvas, w, h)
            drawMotes(canvas, h)
            if (awake) postInvalidateOnAnimation()
        }
    }

    private fun drawWaves(canvas: Canvas, w: Float, h: Float) {
        val step = w / 40f
        val lineColor = if (dark) Color.rgb(160, 205, 255) else Look.blue
        for (k in 0..1) {
            wave.reset()
            val mid = h * (0.72f + 0.08f * k)
            val amp = h * (0.05f - 0.018f * k)
            val freq = 2f * PI.toFloat() * (1f + 0.45f * k) / w
            val speed = 0.2f + 0.12f * k
            var x = 0f
            while (x <= w + step) {
                val y = mid + amp * sin(x * freq + t * speed + k * 2.1f) +
                    amp * 0.35f * sin(x * freq * 2.3f - t * speed * 1.4f)
                if (x == 0f) wave.moveTo(x, y) else wave.lineTo(x, y)
                x += step
            }
            waveLine.color = Look.withAlpha(lineColor, 46 - 16 * k)
            canvas.drawPath(wave, waveLine)
            wave.lineTo(w + step, h)
            wave.lineTo(0f, h)
            wave.close()
            canvas.drawPath(wave, waveFill)
        }
    }

    private fun drawMotes(canvas: Canvas, h: Float) {
        for (m in motes) {
            val y = ((m.y - t * m.rise) % h + h) % h
            val x = m.x + sin(t * 0.3f + m.phase) * m.sway
            val a = m.alpha * (0.6f + 0.4f * sin(t * 0.8f + m.phase * 3f))
            dotPaint.alpha = (a * 255).toInt().coerceIn(0, 255)
            val s = m.r * 3f
            box.set(x - s, y - s, x + s, y + s)
            canvas.drawBitmap(dot, null, box, dotPaint)
        }
    }

    /** A soft white dot with a bright core, drawn once and stamped for every mote. */
    private fun softDot(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { b ->
        val r = size / 2f
        Canvas(b).drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, intArrayOf(Color.WHITE, Color.argb(110, 190, 220, 255), Color.TRANSPARENT),
                floatArrayOf(0f, 0.22f, 1f), Shader.TileMode.CLAMP)
        })
    }

    private companion object {
        const val MOTES = 36
    }
}

package com.matthew.playhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.DisplayMetrics
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.VectorDrawable
import android.graphics.drawable.Drawable

/** Bitmaps drawn once per app: the square tile and the hero backdrop. Home caches them by package. */
object Art {
    /** Tile art is drawn at the Library's size (the biggest it's shown) and scaled down on the row. */
    const val TILE_DP = 104

    /**
     * Full-bleed square art, like a PS tile. Adaptive icons are 108 units with the visible 72 in
     * the middle, so their layers are drawn 1.5x and centred: edge to edge, no launcher mask.
     * Older icons sit on a dark version of the banner's colour instead.
     */
    fun tile(context: Context, app: App): Bitmap {
        val size = context.dp(TILE_DP)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(tone(context, app))
        val icon = app.icon.copy(context)
        if (icon is AdaptiveIconDrawable) {
            val bleed = size / 4
            listOfNotNull(icon.background, icon.foreground).forEach {
                it.setBounds(-bleed, -bleed, size + bleed, size + bleed)
                it.draw(c)
            }
        } else {
            val m = size / 5
            icon.setBounds(m, m, size - m, size - m)
            icon.draw(c)
        }
        return b
    }

    /** The Library tile: PlayStation blue with a grid. */
    fun library(context: Context): Bitmap {
        val size = context.dp(TILE_DP)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(),
                Color.rgb(28, 92, 196), Color.rgb(8, 22, 58), Shader.TileMode.CLAMP)
        })
        drawGlyph(c, Glyph.GRID, size / 2f, size / 2f, size * 0.5f, Color.WHITE, Paint(Paint.ANTI_ALIAS_FLAG))
        return b
    }

    /**
     * The hero backdrop, rendered once at exactly the size it's shown ([w] x [h]) so it's never
     * scaled twice. The source is the app's sharpest banner: most apps ship banners for several
     * screen densities, and the extra-high one is up to 4x the pixels of the one this screen
     * would normally pick. Apps without a banner get their icon, large, on its own colour.
     * The left and bottom edges fade out so the art melts into the wallpaper.
     */
    fun hero(context: Context, app: App, w: Int, h: Int): Bitmap? {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val banner = sharpestBanner(context, app)
        val iw = banner?.intrinsicWidth ?: 0
        val ih = banner?.intrinsicHeight ?: 0
        if (banner != null && banner !is VectorDrawable && iw in 1 until w && ih in 1 until h) {
            // A bitmap smaller than the art (TV banners top out at 640x360): enlarge it with
            // bicubic instead of the canvas's bilinear, which smears edges and logo text.
            val small = Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
            banner.setBounds(0, 0, iw, ih)
            banner.draw(Canvas(small))
            c.drawBitmap(bicubic(small, w, h), 0f, 0f, null)
            small.recycle()
        } else if (banner != null) { // vectors, or already big enough: draw straight at size
            banner.setBounds(0, 0, w, h)
            banner.draw(c)
        } else {
            c.drawColor(tone(context, app))
            val icon = app.icon.copy(context)
            val s = h * 0.62f // icon, centred in the part of the art that isn't faded out
            val cx = w * 0.62f
            val cy = h * 0.42f
            icon.setBounds((cx - s / 2).toInt(), (cy - s / 2).toInt(), (cx + s / 2).toInt(), (cy + s / 2).toInt())
            icon.draw(c)
        }
        val fw = w.toFloat()
        val fh = h.toFloat()
        val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
        mask.shader = LinearGradient(0f, 0f, fw * 0.45f, 0f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, mask)
        mask.shader = LinearGradient(0f, fh, 0f, fh * 0.5f, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, fw, fh, mask)
        return b
    }

    /**
     * Catmull-Rom bicubic resize, done separably (rows, then columns): about 20 M multiply-adds
     * for a banner, a few tens of ms on the TV's CPU, run on the worker thread once per app.
     */
    private fun bicubic(src: Bitmap, w: Int, h: Int): Bitmap {
        val sw = src.width
        val sh = src.height
        val input = IntArray(sw * sh).also { src.getPixels(it, 0, sw, 0, 0, sw, sh) }
        val (xIdx, xWt) = taps(sw, w)
        val rows = IntArray(w * sh)
        for (y in 0 until sh) {
            val from = y * sw
            for (x in 0 until w) rows[y * w + x] = mix(input, xIdx, xWt, x, from, 1)
        }
        val (yIdx, yWt) = taps(sh, h)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) out[y * w + x] = mix(rows, yIdx, yWt, y, x, w)
        }
        return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** For each of [dst] outputs: the 4 source positions it reads from [src] and their weights. */
    private fun taps(src: Int, dst: Int): Pair<IntArray, FloatArray> {
        val idx = IntArray(dst * 4)
        val wt = FloatArray(dst * 4)
        for (i in 0 until dst) {
            val centre = (i + 0.5f) * src / dst - 0.5f
            val base = kotlin.math.floor(centre).toInt()
            val t = centre - base
            val t2 = t * t
            val t3 = t2 * t
            wt[i * 4] = (-t3 + 2 * t2 - t) / 2
            wt[i * 4 + 1] = (3 * t3 - 5 * t2 + 2) / 2
            wt[i * 4 + 2] = (-3 * t3 + 4 * t2 + t) / 2
            wt[i * 4 + 3] = (t3 - t2) / 2
            for (k in 0..3) idx[i * 4 + k] = (base - 1 + k).coerceIn(0, src - 1)
        }
        return idx to wt
    }

    /** One output pixel: the weighted sum of 4 pixels at [from] + idx * [stride], each channel clamped. */
    private fun mix(px: IntArray, idx: IntArray, wt: FloatArray, i: Int, from: Int, stride: Int): Int {
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        for (k in 0..3) {
            val p = px[from + idx[i * 4 + k] * stride]
            val f = wt[i * 4 + k]
            a += (p ushr 24) * f
            r += (p shr 16 and 255) * f
            g += (p shr 8 and 255) * f
            b += (p and 255) * f
        }
        fun ch(v: Float) = v.toInt().coerceIn(0, 255)
        return (ch(a) shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /** The app's banner at the highest density it ships (falls back to the usual one). */
    private fun sharpestBanner(context: Context, app: App): Drawable? {
        val ai = app.info
        val id = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        if (id != 0) {
            runCatching {
                context.packageManager.getResourcesForApplication(ai.applicationInfo)
                    .getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
            }.getOrNull()?.let { return it.mutate() }
        }
        return app.banner?.copy(context)
    }

    /** A dark version of the banner's main colour (PS navy when there's no banner). */
    private fun tone(context: Context, app: App): Int {
        val banner = app.banner ?: return Color.rgb(18, 32, 62)
        val tiny = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        banner.copy(context).apply { setBounds(0, 0, 4, 4); draw(Canvas(tiny)) }
        var r = 0
        var g = 0
        var bl = 0
        for (x in 0 until 4) for (y in 0 until 4) {
            val p = tiny.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); bl += Color.blue(p)
        }
        tiny.recycle()
        return Color.rgb(r * 7 / 160, g * 7 / 160, bl * 7 / 160) // average of 16, at 70%
    }

    /** Drawables carry their own bounds, so drawing at a new size needs a private copy. */
    private fun Drawable.copy(context: Context): Drawable =
        constantState?.newDrawable(context.resources)?.mutate() ?: this
}

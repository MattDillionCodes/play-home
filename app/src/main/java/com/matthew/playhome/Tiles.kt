package com.matthew.playhome

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A square app icon with the PS5 focus treatment: a white ring and a soft blue-white glow, drawn
 * in the padding around the icon so focusing never shifts the art. Every parent up the tree must
 * leave clipChildren off, or the glow is cut off.
 */
class IconView(context: Context, private val art: Bitmap) : View(context) {
    var lit = false
        set(v) { if (field != v) { field = v; invalidate() } }

    /** A dimmer ring: the tile stays selected while focus is on the Open / ··· buttons under it. */
    var held = false
        set(v) { if (field != v) { field = v; invalidate() } }

    private val pad = dpf(PAD_DP.toFloat())
    private val shader = BitmapShader(art, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    private val artPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).also { it.shader = shader }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(1f)
        color = Look.edge
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(3f)
        color = Look.ring
    }
    // The glow is a wide faint stroke, not a shadow layer: a blurred shadow is re-rendered every
    // frame while the tile grows, which the TV's GPU can't keep up with.
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(8f)
    }
    private val src = RectF(0f, 0f, art.width.toFloat(), art.height.toFloat())
    private val box = RectF()
    private val m = Matrix()

    override fun onDraw(canvas: Canvas) {
        box.set(pad, pad, width - pad, height - pad)
        if (box.width() <= 0f) return
        val r = min(box.width(), box.height()) * 0.2f // square tiles and 16:9 wallpaper thumbnails alike
        m.setRectToRect(src, box, Matrix.ScaleToFit.FILL) // the size animates, so map every frame
        shader.setLocalMatrix(m)
        canvas.drawRoundRect(box, r, r, artPaint)
        canvas.drawRoundRect(box, r, r, edge)
        if (lit || held) {
            val g = dpf(3.5f)
            val h = g + dpf(3f)
            halo.color = Look.withAlpha(Look.glow, if (lit) 70 else 30)
            canvas.drawRoundRect(box.left - h, box.top - h, box.right + h, box.bottom + h, r + h, r + h, halo)
            ring.alpha = if (lit) 255 else 110
            canvas.drawRoundRect(box.left - g, box.top - g, box.right + g, box.bottom + g, r + g, r + g, ring)
        }
    }

    companion object {
        const val PAD_DP = 7
    }
}

/**
 * One Home row entry, the PS5 way: a square icon that grows when focused, with the app's name
 * sliding out beside it and pushing the rest of the row along. [grow] runs 0 (resting) to 1.
 */
class RowTile(context: Context, art: Bitmap, name: String) : LinearLayout(context) {
    val icon = IconView(context, art)
    private val label = TextView(context).apply {
        text = name
        textSize = 21f
        typeface = Look.medium
        setTextColor(Look.text)
        isSingleLine = true
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), 0, dp(22), 0)
        alpha = 0f
    }
    private val small = dp(SIZE_DP + 2 * IconView.PAD_DP)
    private val big = dp(FOCUSED_DP + 2 * IconView.PAD_DP)
    private val labelWidth = min(dp(340), label.paint.measureText(name).toInt() + dp(34) + 1)
    private var anim: ValueAnimator? = null
    private var target = 0f
    private var grow = 0f
        set(v) {
            field = v
            val s = small + ((big - small) * v).toInt()
            icon.layoutParams.width = s
            icon.layoutParams.height = s
            label.layoutParams.width = (labelWidth * v).toInt()
            label.alpha = v * v // the name fades in late, once there's room for it
            label.ellipsize = if (v >= 1f) TextUtils.TruncateAt.END else null // clip while sliding, "…" once out
            requestLayout()
        }

    /** Stays grown (with a dim ring) while focus is on the hub buttons for this tile. */
    var held: Boolean
        get() = icon.held
        set(v) { icon.held = v; settle() }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        defaultFocusHighlightEnabled = false
        clipChildren = false
        clipToPadding = false
        id = View.generateViewId()
        addView(icon, LayoutParams(small, small))
        addView(label, LayoutParams(0, MATCH_PARENT))
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        icon.lit = gainFocus
        settle()
    }

    private fun settle() {
        val next = if (isFocused || icon.held) 1f else 0f
        if (next == target && (anim?.isRunning == true || grow == next)) return // already there, or on its way
        target = next
        anim?.cancel()
        anim = ValueAnimator.ofFloat(grow, next).apply {
            duration = 200
            interpolator = Look.ease
            addUpdateListener { grow = it.animatedValue as Float }
            start()
        }
    }

    companion object {
        const val SIZE_DP = 64
        const val FOCUSED_DP = 96
    }
}

enum class Glyph { SEARCH, GEAR, HOME, GRID, NOTE, DISPLAY, WIFI, DOTS, IMAGE, CONTRAST, ARRANGE }

/** A round icon button (top bar, hub, Control Center): translucent at rest, solid white when focused. */
class GlyphButton(context: Context, var glyph: Glyph, var label: String) : View(context) {
    /** A slash through the glyph: a toggle that's switched off. */
    var crossed = false
        set(v) { field = v; invalidate() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        id = View.generateViewId()
        contentDescription = label
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) 1.12f else 1f
        animate().scaleX(s).scaleY(s).setInterpolator(Look.ease).setDuration(160).start()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f
        fill.color = if (isFocused) Look.focusFill else Look.chip
        canvas.drawCircle(cx, cy, r, fill)
        val color = if (isFocused) Look.onFocus else Look.text
        drawGlyph(canvas, glyph, cx, cy, r * 0.95f, color, ink)
        if (crossed) {
            ink.style = Paint.Style.STROKE
            ink.strokeWidth = r * 0.1f
            canvas.drawLine(cx - r * 0.45f, cy + r * 0.45f, cx + r * 0.45f, cy - r * 0.45f, ink)
        }
    }
}

private val glyphPath = Path()
private val glyphBox = RectF()

/** Line icons drawn in code (no icon assets). [s] is the size of the square they fill, centred on cx, cy. */
fun drawGlyph(c: Canvas, g: Glyph, cx: Float, cy: Float, s: Float, color: Int, p: Paint) {
    p.color = color
    p.style = Paint.Style.STROKE
    p.strokeWidth = s * 0.1f
    p.strokeCap = Paint.Cap.ROUND
    p.strokeJoin = Paint.Join.ROUND
    val path = glyphPath.apply { reset() }
    when (g) {
        Glyph.SEARCH -> {
            c.drawCircle(cx - s * 0.08f, cy - s * 0.08f, s * 0.26f, p)
            c.drawLine(cx + s * 0.12f, cy + s * 0.12f, cx + s * 0.36f, cy + s * 0.36f, p)
        }
        Glyph.GEAR -> {
            p.strokeCap = Paint.Cap.BUTT
            p.strokeWidth = s * 0.14f
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0 + 22.5)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                c.drawLine(cx + ca * s * 0.26f, cy + sa * s * 0.26f, cx + ca * s * 0.41f, cy + sa * s * 0.41f, p)
            }
            p.strokeWidth = s * 0.1f
            c.drawCircle(cx, cy, s * 0.26f, p)
            c.drawCircle(cx, cy, s * 0.08f, p)
        }
        Glyph.HOME -> {
            path.moveTo(cx - s * 0.4f, cy - s * 0.02f)
            path.lineTo(cx, cy - s * 0.38f)
            path.lineTo(cx + s * 0.4f, cy - s * 0.02f)
            path.moveTo(cx - s * 0.27f, cy - s * 0.14f)
            path.lineTo(cx - s * 0.27f, cy + s * 0.36f)
            path.lineTo(cx + s * 0.27f, cy + s * 0.36f)
            path.lineTo(cx + s * 0.27f, cy - s * 0.14f)
            c.drawPath(path, p)
        }
        Glyph.GRID -> for (dx in intArrayOf(-1, 1)) for (dy in intArrayOf(-1, 1)) {
            val x = cx + dx * s * 0.19f
            val y = cy + dy * s * 0.19f
            val h = s * 0.13f
            c.drawRoundRect(x - h, y - h, x + h, y + h, s * 0.05f, s * 0.05f, p)
        }
        Glyph.NOTE -> {
            p.style = Paint.Style.FILL
            c.drawOval(cx - s * 0.3f, cy + s * 0.14f, cx + s * 0.02f, cy + s * 0.38f, p)
            p.style = Paint.Style.STROKE
            path.moveTo(cx, cy + s * 0.26f)
            path.lineTo(cx, cy - s * 0.38f)
            path.lineTo(cx + s * 0.3f, cy - s * 0.24f)
            c.drawPath(path, p)
        }
        Glyph.DISPLAY -> {
            c.drawRoundRect(cx - s * 0.4f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.18f, s * 0.06f, s * 0.06f, p)
            c.drawLine(cx - s * 0.18f, cy + s * 0.36f, cx + s * 0.18f, cy + s * 0.36f, p)
        }
        Glyph.WIFI -> {
            val oy = cy + s * 0.3f
            for (r in floatArrayOf(0.2f, 0.4f, 0.6f)) {
                glyphBox.set(cx - s * r, oy - s * r, cx + s * r, oy + s * r)
                c.drawArc(glyphBox, 225f, 90f, false, p)
            }
            p.style = Paint.Style.FILL
            c.drawCircle(cx, oy, s * 0.06f, p)
        }
        Glyph.IMAGE -> { // a picture frame with a mountain and a sun
            c.drawRoundRect(cx - s * 0.4f, cy - s * 0.3f, cx + s * 0.4f, cy + s * 0.3f, s * 0.06f, s * 0.06f, p)
            path.moveTo(cx - s * 0.3f, cy + s * 0.2f)
            path.lineTo(cx - s * 0.08f, cy - s * 0.04f)
            path.lineTo(cx + s * 0.06f, cy + s * 0.1f)
            path.lineTo(cx + s * 0.14f, cy + s * 0.02f)
            path.lineTo(cx + s * 0.3f, cy + s * 0.2f)
            c.drawPath(path, p)
            p.style = Paint.Style.FILL
            c.drawCircle(cx + s * 0.18f, cy - s * 0.13f, s * 0.06f, p)
        }
        Glyph.CONTRAST -> { // a circle, half filled: dark / light
            c.drawCircle(cx, cy, s * 0.34f, p)
            p.style = Paint.Style.FILL
            glyphBox.set(cx - s * 0.34f, cy - s * 0.34f, cx + s * 0.34f, cy + s * 0.34f)
            c.drawArc(glyphBox, 90f, 180f, true, p)
        }
        Glyph.ARRANGE -> { // three rows with an up/down arrow beside them: reorder
            for (k in -1..1) c.drawLine(cx - s * 0.38f, cy + k * s * 0.24f, cx + s * 0.08f, cy + k * s * 0.24f, p)
            c.drawLine(cx + s * 0.28f, cy - s * 0.34f, cx + s * 0.28f, cy + s * 0.34f, p)
            path.moveTo(cx + s * 0.16f, cy - s * 0.2f); path.lineTo(cx + s * 0.28f, cy - s * 0.34f); path.lineTo(cx + s * 0.4f, cy - s * 0.2f)
            path.moveTo(cx + s * 0.16f, cy + s * 0.2f); path.lineTo(cx + s * 0.28f, cy + s * 0.34f); path.lineTo(cx + s * 0.4f, cy + s * 0.2f)
            c.drawPath(path, p)
        }
        Glyph.DOTS -> {
            p.style = Paint.Style.FILL
            for (k in -1..1) c.drawCircle(cx + k * s * 0.28f, cy, s * 0.07f, p)
        }
    }
}

/** A capsule button. The [primary] one is the hub's big Open; any pill turns solid white when focused. */
class Pill(context: Context, text: CharSequence, private val primary: Boolean = false) : TextView(context) {
    init {
        this.text = text
        textSize = if (primary) 20f else 17f
        typeface = Look.medium
        gravity = Gravity.CENTER
        isFocusable = true
        isClickable = true
        defaultFocusHighlightEnabled = false
        id = View.generateViewId()
        minWidth = if (primary) dp(170) else 0
        val side = dp(if (primary) 36 else 22)
        setPadding(side, dp(11), side, dp(11))
        restyle(false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        restyle(gainFocus)
    }

    private fun restyle(focused: Boolean) {
        background = GradientDrawable().apply {
            cornerRadius = dpf(100f)
            setColor(when {
                focused -> Look.focusFill
                primary -> Look.chipStrong
                else -> Look.chip
            })
            if (!focused) setStroke(dp(1), Look.chipStroke)
        }
        setTextColor(if (focused) Look.onFocus else Look.text)
        val s = if (focused) 1.05f else 1f
        animate().scaleX(s).scaleY(s).setInterpolator(Look.ease).setDuration(160).start()
    }
}

/** A remote-button hint for the bottom bar: a small outlined key and what it does here. */
fun hint(context: Context, key: String, action: String): View = LinearLayout(context).apply {
    gravity = Gravity.CENTER_VERTICAL
    addView(TextView(context).apply {
        text = key
        textSize = 11f
        typeface = Look.medium
        letterSpacing = 0.08f
        setTextColor(Look.text)
        setPadding(dp(8), dp(3), dp(8), dp(3))
        background = GradientDrawable().apply {
            cornerRadius = dpf(6f)
            setStroke(dp(1), Look.dim)
        }
    })
    addView(TextView(context).apply {
        text = action
        textSize = 14f
        typeface = Look.regular
        setTextColor(Look.dim)
    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
}

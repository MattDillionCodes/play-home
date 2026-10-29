package com.matthew.playhome

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Wallpaper picker: a strip of thumbnails along the bottom with the screen above left clear.
 * Moving onto a thumbnail previews that wallpaper behind it straight away; OK keeps it, Back puts
 * the old one back. The built-ins come first, then yours, then "Add your own" (a QR code to send
 * one from your phone); hold OK on one of yours to remove it.
 */
class WallpaperPicker(
    private val activity: Activity,
    private val sounds: Sounds,
    private val preview: (Wallpaper) -> Unit,
    private val onDone: (kept: Wallpaper?) -> Unit,
    private val onAdd: () -> Unit,
    private val onRemove: (Wallpaper) -> Unit,
) {
    private val strip = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(6), dp(8), dp(6), dp(8))
    }
    /** Scrolls sideways once your own wallpapers make the strip wider than the screen. */
    private val scroller = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(strip)
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(48), dp(60), dp(48), dp(64)) // bottom: room for the hint bar
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Look.withAlpha(Look.ink, 0), Look.withAlpha(Look.ink, 220), Look.withAlpha(Look.ink, 245)))
        addView(TextView(activity).apply {
            text = "Wallpaper"
            textSize = 26f
            typeface = Look.light
            setTextColor(Look.text)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(10) })
        addView(scroller, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = -dp(6) })
    }
    val view = FrameLayout(activity).apply {
        visibility = View.GONE
        clipChildren = false
        addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }
    var isOpen = false
        private set
    private var current = Wallpaper.WAVES
    private var choices: List<Wallpaper> = emptyList()

    fun open(current: Wallpaper) {
        this.current = current
        isOpen = true
        fill(focus = current)
        view.visibility = View.VISIBLE
        panel.translationY = panel.dpf(260f)
        panel.animate().translationY(0f).setInterpolator(Look.ease).setDuration(280).start()
    }

    /** After a removal: rebuild the strip, keeping focus near where it was. */
    fun refresh(current: Wallpaper, focusIndex: Int) {
        this.current = current
        fill(focus = null, index = focusIndex)
    }

    /** Back: restore the wallpaper that was there before. */
    fun cancel() {
        if (!isOpen) return
        preview(current)
        close(kept = null)
    }

    /** The remote buttons that do something here, for Home's hint bar. */
    fun hints(focus: View?): List<Pair<String, String>> {
        val w = (focus?.tag as? Wallpaper)
        return when {
            focus?.tag == ADD -> listOf("OK" to "Add from your phone", "BACK" to "Cancel")
            w != null && w.isMine -> listOf("OK" to "Use this", "HOLD OK" to "Remove", "BACK" to "Cancel")
            else -> listOf("◀ ▶" to "Preview", "OK" to "Use this", "BACK" to "Cancel")
        }
    }

    private fun close(kept: Wallpaper?) {
        isOpen = false
        panel.animate().translationY(panel.dpf(260f)).setInterpolator(Look.ease).setDuration(220)
            .withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
        onDone(kept)
    }

    private fun fill(focus: Wallpaper?, index: Int = 0) {
        choices = Wallpaper.all(activity)
        Wallpapers.forgetThumbsExcept(choices)
        strip.removeAllViews()
        choices.forEach { strip.addView(thumb(it), gap()) }
        strip.addView(addTile(), gap())
        val at = if (focus != null) choices.indexOf(focus).coerceAtLeast(0) else index.coerceIn(0, strip.childCount - 1)
        strip.getChildAt(0).let { it.nextFocusLeftId = it.id }
        strip.getChildAt(strip.childCount - 1).let { it.nextFocusRightId = it.id }
        strip.getChildAt(at).requestFocus()
    }

    private fun gap() = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = activity.dp(10) }

    private fun addTile(): View = tile(addWallpaperThumb(), "Add your own").apply {
        tag = ADD
        setOnClickListener { sounds.select(); onAdd() }
    }

    private fun thumb(w: Wallpaper): View = tile(Wallpapers.thumb(activity, w), if (w == current) "${w.title}  ·  Current" else w.title).apply {
        tag = w
        onFocused = { preview(w) }
        setOnClickListener { sounds.select(); close(kept = w) }
        if (w.isMine) setOnLongClickListener { sounds.select(); onRemove(w); true }
    }

    /** A 16:9 thumbnail with its name under it; [onFocused] runs when it gets focus. */
    private inner class Tile(image: IconView, name: TextView) : LinearLayout(activity) {
        var onFocused: () -> Unit = {}

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            id = View.generateViewId()
            val pad = dp(2 * IconView.PAD_DP)
            addView(image, LayoutParams(dp(100) + pad, dp(56) + pad)) // 16:9
            addView(name, LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            setOnFocusChangeListener { _, focused ->
                image.lit = focused
                name.alpha = if (focused) 1f else 0.75f
                if (focused) onFocused()
            }
        }
    }

    private fun tile(art: android.graphics.Bitmap, title: String): Tile {
        val name = TextView(activity).apply {
            text = title
            textSize = 14f
            typeface = Look.medium
            setTextColor(Look.text)
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            alpha = 0.75f
        }
        return Tile(IconView(activity, art), name)
    }

    private companion object {
        const val ADD = "add"
    }
}

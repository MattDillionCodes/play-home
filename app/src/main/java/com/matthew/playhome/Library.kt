package com.matthew.playhome

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Library, after the PS5's Game Library: every app on the TV in one grid, A to Z. OK opens
 * an app; holding OK pins it to the Home row (or unpins it), and pinned apps say "On Home".
 */
class LibraryOverlay(
    private val activity: Activity,
    private val sounds: Sounds,
    private val onOpen: (App) -> Unit,
    private val onPin: (App) -> Unit,
) {
    private val grid = GridLayout(activity).apply {
        columnCount = COLUMNS
        clipChildren = false
        clipToPadding = false
    }
    private val count = TextView(activity).apply {
        textSize = 16f
        typeface = Look.regular
        setTextColor(Look.dim)
    }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(dp(36), dp(34), dp(48), dp(90))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(TextView(activity).apply {
                text = "Library"
                textSize = 36f
                typeface = Look.light
                setTextColor(Look.text)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            addView(count, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply { marginStart = dp(12); bottomMargin = dp(22) })
            addView(grid)
        })
    }
    val view = FrameLayout(activity).apply {
        visibility = View.GONE
        alpha = 0f
        setBackgroundColor(Look.overlay)
        addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }
    var isOpen = false
        private set

    private var apps: List<App> = emptyList()
    private var art: (App) -> Bitmap = { error("Library opened without art") }
    private var pinned: Set<String> = emptySet()

    fun open(apps: List<App>, art: (App) -> Bitmap, pinned: Set<String>) {
        this.apps = apps
        this.art = art
        this.pinned = pinned
        isOpen = true
        fill(focus = 0)
        scroll.scrollTo(0, 0)
        view.animate().cancel() // runs a pending close's end action (GONE) before we show it
        view.visibility = View.VISIBLE
        view.translationY = view.dpf(30f)
        view.animate().alpha(1f).translationY(0f).setInterpolator(Look.ease).setDuration(260).start()
    }

    /** Re-draws the "On Home" badges after a pin change, keeping focus where it was. */
    fun refresh(pinned: Set<String>) {
        this.pinned = pinned
        fill(focus = grid.indexOfChild(grid.focusedChild).coerceAtLeast(0))
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        view.animate().alpha(0f).translationY(view.dpf(30f)).setInterpolator(Look.ease).setDuration(200)
            .withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
    }

    private fun fill(focus: Int) {
        grid.removeAllViews()
        apps.forEach { app ->
            grid.addView(tile(app), GridLayout.LayoutParams().apply {
                width = activity.dp(Art.TILE_DP + 2 * IconView.PAD_DP)
                setMargins(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(16))
            })
        }
        count.text = when (apps.size) { 0 -> "No apps yet"; 1 -> "1 app"; else -> "${apps.size} apps" }
        // With nothing to show, the screen itself holds focus, so OK can't reach the row behind it.
        view.isFocusable = apps.isEmpty()
        (grid.getChildAt(focus) ?: view.takeIf { apps.isEmpty() })?.requestFocus()
    }

    private fun tile(app: App): View {
        val size = activity.dp(Art.TILE_DP + 2 * IconView.PAD_DP)
        val icon = IconView(activity, art(app))
        val name = TextView(activity).apply {
            text = app.label
            textSize = 15f
            typeface = Look.medium
            setTextColor(Look.text)
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0.72f
        }
        val badge = TextView(activity).apply {
            text = if (app.pkg in pinned) "On Home" else ""
            textSize = 12f
            typeface = Look.medium
            setTextColor(Look.accent)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(badge, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnFocusChangeListener { v, focused ->
                icon.lit = focused
                name.alpha = if (focused) 1f else 0.72f
                val s = if (focused) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setInterpolator(Look.ease).setDuration(180).start()
            }
            setOnClickListener { sounds.select(); onOpen(app) }
            setOnLongClickListener { sounds.select(); onPin(app); true }
        }
    }

    private companion object {
        const val COLUMNS = 6
    }
}

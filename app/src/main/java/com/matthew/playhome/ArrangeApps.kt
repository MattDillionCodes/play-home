package com.matthew.playhome

import android.app.Activity
import android.graphics.Bitmap
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Settings → Arrange apps: every app in a grid, in the order the Home row uses. OK picks an app
 * up, the arrows move it, OK drops it (Back puts it back). The first [ROW_MAX] are the Home row;
 * the rest are marked "Library only". Moving anything switches the row to this custom order;
 * the Row order button switches between Custom and Most recent (the arrangement is kept either
 * way), and Reset puts it back to A to Z.
 */
class ArrangeApps(
    private val activity: Activity,
    private val sounds: Sounds,
    private val history: History,
    private val onClose: () -> Unit,
) {
    private val grid = GridLayout(activity).apply {
        columnCount = COLUMNS
        clipChildren = false
        clipToPadding = false
    }
    private val note = TextView(activity).apply {
        textSize = 16f
        typeface = Look.regular
        setTextColor(Look.dim)
    }
    private val modeButton = Pill(activity, "").apply { setOnClickListener { sounds.select(); toggleMode() } }
    private val resetButton = Pill(activity, "Reset to A–Z").apply { setOnClickListener { sounds.select(); resetAz() } }
    private val scroll = ScrollView(activity).apply {
        isVerticalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        setPadding(dp(36), dp(34), dp(48), dp(90))
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(TextView(activity).apply {
                text = "Arrange apps"
                textSize = 36f
                typeface = Look.light
                setTextColor(Look.text)
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12) })
            addView(note, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12); topMargin = dp(2) })
            addView(LinearLayout(activity).apply {
                clipChildren = false
                addView(modeButton)
                addView(resetButton, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(12); topMargin = dp(18); bottomMargin = dp(22) })
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
    private var order = mutableListOf<App>()
    private var art: (App) -> Bitmap = { error("Arrange opened without art") }
    private var moving = -1 // index of the app being carried, or -1
    private var movingFrom = -1

    fun open(apps: List<App>, art: (App) -> Bitmap) {
        this.apps = apps
        this.art = art
        order = homeRow(apps, history.lastOpened(), history.pinned(), history.rowOrder()).toMutableList()
        moving = -1
        isOpen = true
        render()
        fill(focus = 0)
        scroll.scrollTo(0, 0)
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.translationY = view.dpf(30f)
        view.animate().alpha(1f).translationY(0f).setInterpolator(Look.ease).setDuration(260).start()
    }

    /** Back: drop a carried app where it came from, or close. */
    fun back() {
        if (moving >= 0) {
            val app = order.removeAt(moving)
            order.add(movingFrom, app)
            val at = movingFrom
            moving = -1
            fill(focus = at)
            return
        }
        close()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        moving = -1
        view.animate().alpha(0f).translationY(view.dpf(30f)).setInterpolator(Look.ease).setDuration(200)
            .withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
        onClose()
    }

    /** The remote buttons that do something here, for Home's hint bar. */
    fun hints(focus: View?): List<Pair<String, String>> = when {
        moving >= 0 -> listOf("◀ ▶ ▲ ▼" to "Move", "OK" to "Drop", "BACK" to "Put back")
        focus != null && focus.parent === grid -> listOf("OK" to "Pick up", "BACK" to "Done")
        else -> listOf("OK" to "Select", "BACK" to "Done")
    }

    private fun render() {
        val custom = history.customOrder
        modeButton.text = if (custom) "Row order: Custom" else "Row order: Most recent"
        note.text = if (custom) "Home follows this order. The first $ROW_MAX are on the Home row."
        else "Home puts pinned and recently used apps first. Move an app to use your own order."
    }

    private fun toggleMode() {
        history.customOrder = !history.customOrder
        if (history.customOrder && history.savedOrder().isEmpty()) history.saveOrder(order.map { it.pkg })
        order = homeRow(apps, history.lastOpened(), history.pinned(), history.rowOrder()).toMutableList()
        render()
        fill(focus = -1)
        modeButton.requestFocus()
    }

    private fun resetAz() {
        order = apps.sortedBy { it.label.lowercase() }.toMutableList()
        save()
        fill(focus = -1)
        resetButton.requestFocus()
    }

    /** Saves the grid as the custom order and switches the row to it. */
    private fun save() {
        history.saveOrder(order.map { it.pkg })
        history.customOrder = true
        render()
    }

    private fun pickUp(i: Int) {
        moving = i
        movingFrom = i
        fill(focus = i)
    }

    private fun drop() {
        val at = moving
        moving = -1
        save()
        fill(focus = at)
    }

    /** Carries the app [step] places along (±1 sideways, ±a row up / down), shifting the rest. */
    private fun carry(step: Int): Boolean {
        val to = moving + step
        if (to !in order.indices) return true // at an edge: stay put, but don't let focus wander off
        order.add(to, order.removeAt(moving))
        val from = moving
        moving = to
        // Only the tiles between the old and new spot changed (number, or carried look), not the
        // whole grid: with 100+ apps and key repeat, a full rebuild per press is what lags.
        for (i in minOf(from, to)..maxOf(from, to)) {
            grid.removeViewAt(i)
            grid.addView(tile(order[i], i), i, tileParams())
        }
        grid.getChildAt(to).requestFocus()
        return true
    }

    private fun tileParams() = GridLayout.LayoutParams().apply {
        width = activity.dp(Art.TILE_DP + 2 * IconView.PAD_DP)
        setMargins(activity.dp(12), activity.dp(6), activity.dp(12), activity.dp(16))
    }

    /** [focus] -1 leaves focus where it is (on the buttons). */
    private fun fill(focus: Int) {
        grid.removeAllViews()
        order.forEachIndexed { i, app -> grid.addView(tile(app, i), tileParams()) }
        if (focus >= 0) (grid.getChildAt(focus) ?: modeButton).requestFocus()
    }

    private fun tile(app: App, i: Int): View {
        val size = activity.dp(Art.TILE_DP + 2 * IconView.PAD_DP)
        val carried = i == moving
        val icon = IconView(activity, art(app))
        val name = TextView(activity).apply {
            text = "${i + 1}.  ${app.label}"
            textSize = 15f
            typeface = Look.medium
            setTextColor(Look.text)
            gravity = Gravity.CENTER_HORIZONTAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            alpha = 0.8f
        }
        val badge = TextView(activity).apply {
            text = when {
                carried -> "Moving"
                i >= ROW_MAX -> "Library only"
                else -> ""
            }
            textSize = 12f
            typeface = Look.medium
            setTextColor(if (carried) Look.accent else Look.dim)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true
            defaultFocusHighlightEnabled = false
            clipChildren = false
            if (i >= ROW_MAX && !carried) alpha = 0.6f // past the Home row
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(name, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(2) })
            addView(badge, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            if (carried) { scaleX = 1.12f; scaleY = 1.12f; translationZ = dpf(12f) } // lifted
            setOnFocusChangeListener { v, focused ->
                icon.lit = focused
                name.alpha = if (focused) 1f else 0.8f
                if (!carried) {
                    val s = if (focused) 1.06f else 1f
                    v.animate().scaleX(s).scaleY(s).setInterpolator(Look.ease).setDuration(160).start()
                }
            }
            setOnClickListener {
                sounds.select()
                if (moving < 0) pickUp(i) else drop()
            }
            setOnKeyListener { _, key, e ->
                if (moving < 0 || e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (key) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> carry(-1)
                    KeyEvent.KEYCODE_DPAD_RIGHT -> carry(1)
                    KeyEvent.KEYCODE_DPAD_UP -> carry(-COLUMNS)
                    KeyEvent.KEYCODE_DPAD_DOWN -> carry(COLUMNS)
                    else -> false
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 6
    }
}

package com.matthew.playhome

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * PS5-style Home for Google TV.
 *
 * Top left, the Apps tab; under it one row of square app tiles. The focused tile grows
 * and shows its name, its banner becomes the backdrop, and below sits the hub: the app's name and
 * a big Open button, as on a PS5 game hub. The row ends with the Library tile
 * (every app, A to Z). The Control Center (gear, or Menu / Info on the remote) slides up from the
 * bottom with the clock, volume, HDMI inputs and settings shortcuts.
 *
 * Everything uses one focus style (white ring and glow), and the bottom-right corner always shows
 * which remote buttons do something on the current screen.
 */
class HomeActivity : Activity() {
    private enum class Tab(val title: String) { APPS("Apps") } // one tab for now; the bar keeps the PS5 look

    private companion object {
        const val MARGIN = 48 // dp: side safe area
        const val GAP = 14 // dp between row tiles
        const val ANCHOR = 4 // focus moves freely over the first four tiles, then the row scrolls under it
        const val IDLE_MS = 12_000L // stop animating the backdrop this long after the last press
        const val WEATHER_EVERY_MS = 15 * 60 * 1000L
        const val HERO_CACHE = 4
        const val KEY_CONTROLS = "reopenControls"
        const val APPEARANCE_SHORTCUT = 4 // its place in the Control Center's shortcut row
    }

    private lateinit var root: FrameLayout
    private lateinit var ambient: AmbientView
    private lateinit var home: FrameLayout // the Home screen itself; its focus is blocked while an overlay is up
    private lateinit var tabViews: Map<Tab, TextView>
    private lateinit var search: GlyphButton
    private lateinit var gear: GlyphButton
    private lateinit var time: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var rowHolder: FrameLayout
    private lateinit var row: LinearLayout
    private lateinit var title: TextView
    private lateinit var meta: TextView
    private lateinit var openButton: Pill
    private lateinit var moreButton: GlyphButton
    private lateinit var hints: LinearLayout
    private lateinit var toast: TextView
    private lateinit var optionsLayer: FrameLayout
    private lateinit var optionsCard: LinearLayout
    private lateinit var library: LibraryOverlay
    private lateinit var controls: ControlCenter
    private lateinit var picker: WallpaperPicker
    private lateinit var arrange: ArrangeApps
    private lateinit var uploads: UploadScreen
    private lateinit var sounds: Sounds
    private val history by lazy { History(this) }

    private var apps: List<App> = emptyList()
    private var tab = Tab.APPS
    private var selected: RowTile? = null // the row's current tile, even while focus is up in the tabs or down in the hub
    private var currentApp: App? = null // what the hub shows; null = the Library tile
    private var optionsOpen = false
    private var optionsReturn: View? = null
    private var hintKey = ""
    private var reopenControlsAt = -1 // after Appearance rebuilds the screen, put the Control Center back
    private val tileCache = HashMap<String, Bitmap>()
    /** Full-size hero art is ~4 MB a piece: keep only the last few, touched from the worker thread. */
    private val heroCache = object : LinkedHashMap<String, Bitmap?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) = size > HERO_CACHE
    }
    private val libraryArt by lazy { Art.library(this) }
    private val step by lazy { dp(RowTile.SIZE_DP + 2 * IconView.PAD_DP + GAP) }
    private val controlKeys = setOf(
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_SETTINGS, KeyEvent.KEYCODE_NOTIFICATION,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Look.dark = history.dark // before any view reads a colour
        reopenControlsAt = savedInstanceState?.getInt(KEY_CONTROLS, -1) ?: -1
        sounds = Sounds(this).apply { enabled = history.sounds }
        root = FrameLayout(this).apply { clipChildren = false }
        ambient = AmbientView(this).apply { setWallpaper(history.wallpaper) }
        root.addView(ambient, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        home = FrameLayout(this).apply { clipChildren = false }
        home.addView(buildRow(), FrameLayout.LayoutParams(MATCH_PARENT, dp(128)).apply { topMargin = dp(84) })
        home.addView(buildHub(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(MARGIN), dp(240), 0, 0) })
        home.addView(buildTabs(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.START)
            .apply { setMargins(dp(MARGIN - 16), dp(26), 0, 0) })
        home.addView(buildStatus(), FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, dp(26), dp(MARGIN), 0) })
        root.addView(home, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        library = LibraryOverlay(this, sounds, onOpen = { launch(it) }, onPin = { togglePin(it) })
        root.addView(library.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        controls = ControlCenter(this, sounds, history,
            onHome = { closeControls(); focusFirst() },
            onLibrary = { controls.close(); openLibrary() },
            onArrange = { controls.close(); arrange.open(apps, ::tileArt) },
            onWallpaper = { controls.close(); openPicker() },
            onAppearance = { switchAppearance() })
        root.addView(controls.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        picker = WallpaperPicker(this, sounds,
            preview = { ambient.setWallpaper(it) },
            onDone = { kept ->
                if (kept != null) { history.wallpaper = kept; showToast("Wallpaper: ${kept.title}") }
                home.animate().alpha(1f).setDuration(250).start()
                showHeroArt()
                home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                selected?.requestFocus()
            },
            onAdd = {
                if (Wallpaper.mine(this).size >= Wallpaper.MAX_MINE) {
                    showToast("You have ${Wallpaper.MAX_MINE} of your own. Hold OK on one to remove it first.")
                } else {
                    picker.cancel()
                    openUploads()
                }
            },
            onRemove = { w ->
                val at = Wallpaper.all(this).indexOf(w)
                w.file?.delete()
                showToast("Removed ${w.title}")
                picker.refresh(history.wallpaper, at) // a removed current wallpaper falls back to Waves
            })
        root.addView(picker.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        uploads = UploadScreen(this, sounds,
            preview = { ambient.setWallpaper(it) },
            restore = { ambient.setWallpaper(history.wallpaper) },
            onKeep = { w ->
                history.wallpaper = w
                ambient.setWallpaper(w)
                showToast("Wallpaper added")
            },
            onClose = {
                home.animate().alpha(1f).setDuration(250).start()
                showHeroArt()
                home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                selected?.requestFocus()
            },
            toast = ::showToast)
        root.addView(uploads.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        arrange = ArrangeApps(this, sounds, history, onClose = {
            home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            fillRow() // in the new order, focus on the first app
        })
        root.addView(arrange.view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(buildOptions(), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        hints = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(hints, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
            .apply { setMargins(0, 0, dp(MARGIN), dp(24)) })
        toast = TextView(this).apply {
            textSize = 16f
            typeface = Look.medium
            setTextColor(Look.text)
            alpha = 0f
            setPadding(dp(22), dp(11), dp(22), dp(11))
            background = GradientDrawable().apply {
                cornerRadius = dpf(100f)
                setColor(Look.surface)
                setStroke(dp(1), Look.chipStroke)
            }
        }
        root.addView(toast, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { topMargin = dp(28) })
        setContentView(root)

        root.viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
            // Keep the tile looking selected while you're on its Open / ··· buttons (or its menu).
            selected?.held = now === openButton || now === moreButton || optionsOpen
            root.post { updateHints(currentFocus) } // after the new view's own focus handling has run
        }

        // Boot-up: Home fades in and the row slides in from the right, like the PS5 after its logo.
        if (savedInstanceState != null) return // rebuilt (Appearance): no boot-up show
        home.alpha = 0f
        rowHolder.translationX = dpf(90f)
        home.animate().alpha(1f).setStartDelay(150).setDuration(700).start()
        rowHolder.animate().translationX(0f).setInterpolator(Look.ease).setStartDelay(150).setDuration(800).start()
    }

    // --- Top bar: the Apps tab (left); search, Control Center, profile, clock (right) ---

    private fun buildTabs(): View {
        tabViews = Tab.entries.associateWith { t ->
            TextView(this).apply {
                text = t.title
                textSize = 24f
                typeface = Look.medium
                id = View.generateViewId()
                isFocusable = true
                defaultFocusHighlightEnabled = false
                setPadding(dp(16), dp(6), dp(16), dp(8))
                // Landing on a tab switches to it straight away, as on the PS5.
                setOnFocusChangeListener { _, focused -> if (focused && tab != t) switchTab(t) else styleTabs() }
                setOnClickListener { sounds.select(); selected?.requestFocus() } // OK drops into the row
            }
        }
        tabViews.getValue(Tab.APPS).let { it.nextFocusLeftId = it.id }
        styleTabs()
        return LinearLayout(this).apply { tabViews.values.forEach { addView(it) } }
    }

    private fun styleTabs() = tabViews.forEach { (t, v) ->
        v.setTextColor(if (t == tab) Look.text else Look.faint)
        v.background = if (!v.isFocused) null else GradientDrawable().apply {
            cornerRadius = dpf(100f)
            setColor(Look.chip)
            setStroke(dp(2), Look.ring)
        }
    }

    private fun switchTab(t: Tab) {
        tab = t
        styleTabs()
        fillRow(takeFocus = false)
    }

    private fun buildStatus(): View {
        search = GlyphButton(this, Glyph.SEARCH, "Search").apply { tap { openSearch() } }
        gear = GlyphButton(this, Glyph.GEAR, "Control Center").apply {
            tap { openControls() }
            nextFocusRightId = id
        }
        val avatar = TextView(this).apply {
            text = profileInitial()
            textSize = 17f
            typeface = Look.medium
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Look.blue, Color.rgb(98, 54, 255))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.argb(200, 255, 255, 255))
            }
        }
        time = TextView(this).apply {
            textSize = 22f
            typeface = Look.light
            setTextColor(Look.text)
        }
        weatherLabel = TextView(this).apply {
            textSize = 20f
            typeface = Look.regular
            setTextColor(Look.text)
            visibility = View.GONE // until the first forecast arrives
        }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(search, LinearLayout.LayoutParams(dp(44), dp(44)))
            addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(14) })
            addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(22) })
            addView(weatherLabel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(20) })
            addView(time, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
        }
    }

    /** The profile bubble's letter: the first of the TV's name (Settings → Device name), e.g. "Living Room TV" → L. */
    private fun profileInitial(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "TV"

    /** Google TV's search (the Assistant), if this TV has it. */
    private fun openSearch() {
        val tries = listOf(Intent("android.search.action.GLOBAL_SEARCH"), Intent(Intent.ACTION_ASSIST))
        if (tries.none { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }) {
            showToast("Search isn't available on this TV")
        }
    }

    // --- The row: square tiles, the focused one grown with its name, then the Library tile ----------

    private fun buildRow(): View {
        row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(MARGIN - IconView.PAD_DP), 0, 0, 0)
        }
        // Wider than the screen so every tile gets laid out; the row scrolls by translation.
        rowHolder = FrameLayout(this).apply {
            clipChildren = false
            addView(row, FrameLayout.LayoutParams(dp(4000), MATCH_PARENT))
        }
        return rowHolder
    }

    /**
     * Rebuilds the row with every app, ordered by [homeRow], and selects [focusPkg] if it's
     * there (else the first tile). [takeFocus] false leaves focus where it is (e.g. on a tab).
     */
    private fun fillRow(focusPkg: String? = null, takeFocus: Boolean = true) {
        val items: List<App?> = homeRow(apps, history.lastOpened(), history.pinned(), history.rowOrder()).take(ROW_MAX) + null
        row.removeAllViews()
        row.translationX = 0f
        items.forEachIndexed { i, app ->
            val tile = RowTile(this, app?.let(::tileArt) ?: libraryArt, app?.label ?: "Library")
            tile.nextFocusDownId = openButton.id
            tile.nextFocusUpId = tabViews.getValue(tab).id
            if (i == 0) tile.nextFocusLeftId = tile.id
            if (i == items.lastIndex) tile.nextFocusRightId = tile.id
            tile.tap { if (app != null) launch(app) else openLibrary() }
            tile.setOnLongClickListener {
                if (app != null) { sounds.select(); showOptions(app) }
                true
            }
            tile.setOnFocusChangeListener { _, focused -> if (focused) select(tile, app) }
            row.addView(tile, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(GAP) })
        }
        val index = items.indexOfFirst { it != null && it.pkg == focusPkg }.coerceAtLeast(0)
        val tile = row.getChildAt(index) as RowTile
        if (takeFocus) {
            tile.requestFocus()
        } else {
            select(tile, items[index])
            tile.held = currentFocus === openButton || currentFocus === moreButton
        }
    }

    /** A tile became the row's selection: scroll it into place and show its app in the hub. */
    private fun select(tile: RowTile, app: App?) {
        selected = tile
        (tabViews.values + listOf(search, gear)).forEach { it.nextFocusDownId = tile.id }
        openButton.nextFocusUpId = tile.id
        moreButton.nextFocusUpId = tile.id
        val i = row.indexOfChild(tile)
        row.animate().translationX(-maxOf(0, i - ANCHOR) * step.toFloat())
            .setInterpolator(Look.ease).setDuration(220).start()
        showHub(app)
    }

    private fun focusFirst() {
        row.getChildAt(0)?.requestFocus()
    }

    // --- The hub: name, details, Open / Play and the ··· menu; the banner as the backdrop ---------

    private fun buildHub(): View {
        title = TextView(this).apply {
            textSize = 46f
            typeface = Look.light
            setTextColor(Look.text)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(620)
            includeFontPadding = false
        }
        meta = TextView(this).apply {
            textSize = 16f
            typeface = Look.regular
            setTextColor(Look.dim)
        }
        openButton = Pill(this, "Open", primary = true).apply {
            tap { currentApp?.let(::launch) ?: openLibrary() }
            nextFocusLeftId = id
            nextFocusDownId = id
        }
        moreButton = GlyphButton(this, Glyph.DOTS, "Options").apply {
            tap { currentApp?.let(::showOptions) }
            nextFocusRightId = id
            nextFocusDownId = id
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            // Explicit WRAP_CONTENT: a vertical LinearLayout's default is MATCH_PARENT, which would
            // squeeze the title to the width of the button row.
            addView(title, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(meta, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(10) })
            addView(LinearLayout(this@HomeActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                addView(openButton)
                addView(moreButton, LinearLayout.LayoutParams(dp(50), dp(50)).apply { marginStart = dp(16) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28) })
        }
    }

    private val swapArt = Runnable { showHeroArt() }

    /** Renders the focused app's hero art in the background (it's full-size), then shows it. */
    private fun showHeroArt() {
        val app = currentApp ?: return ambient.showArt(null)
        val w = (root.width * 0.72f).toInt()
        val h = (root.height * 0.72f).toInt()
        if (w == 0) return
        Wallpapers.worker.execute {
            val art = heroCache.getOrPut(app.pkg) { Art.hero(this, app, w, h) }
            // Not while the wallpaper picker or Add your own is up: they show the wallpaper alone.
            runOnUiThread { if (currentApp === app && !picker.isOpen && !uploads.isOpen) ambient.showArt(art) }
        }
    }

    private fun showHub(app: App?) {
        currentApp = app
        title.animate().cancel()
        title.alpha = 0.4f
        title.text = app?.label ?: "Library"
        title.animate().alpha(1f).setDuration(180).start()
        meta.text = if (app == null) libraryMeta() else appMeta(app)
        openButton.text = if (app == null) "Open Library" else "Open"
        moreButton.visibility = if (app == null) View.INVISIBLE else View.VISIBLE
        // The backdrop waits a beat, so holding ◀ ▶ across the row doesn't flash every banner past.
        root.removeCallbacks(swapArt)
        root.postDelayed(swapArt, 90)
    }

    private fun appMeta(app: App): String {
        val opened = history.lastOpened()[app.pkg]
        val now = System.currentTimeMillis()
        val last = when {
            opened == null -> "Not opened yet"
            now - opened < DateUtils.MINUTE_IN_MILLIS -> "Opened just now"
            else -> "Opened " + DateUtils.getRelativeTimeSpanString(opened, now, DateUtils.MINUTE_IN_MILLIS)
        }
        val pinned = if (app.pkg in history.pinned()) "Pinned" else null
        return listOfNotNull(if (app.isGame) "Game" else "App", last, pinned).joinToString("   ·   ")
    }

    private fun libraryMeta(): String {
        val total = if (apps.size == 1) "1 app" else "${apps.size} apps"
        return "Everything on this TV, A to Z   ·   $total"
    }

    private fun tileArt(app: App): Bitmap = tileCache.getOrPut(app.pkg) { Art.tile(this, app) }


    /** [fresh]: throw away the app's existing screens and start from its first one. */
    private fun launch(app: App, fresh: Boolean = false) {
        history.markOpened(app.pkg)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            .setClassName(app.pkg, app.info.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or
                if (fresh) Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        runCatching { startActivity(intent) }.onFailure { showToast("Couldn't open ${app.label}") }
    }

    /**
     * ··· → Refresh: restart a misbehaving app. While Home is up the app is in the background, so
     * its process can be stopped (Android 12 on the TV; from Android 14 the system only lets an
     * app stop its own, and there this just reopens it from its first screen), then it's opened
     * fresh. A short pause lets the old process finish dying first.
     */
    private fun refresh(app: App) {
        getSystemService(ActivityManager::class.java).killBackgroundProcesses(app.pkg)
        showToast("Refreshing ${app.label}…")
        root.postDelayed({ launch(app, fresh = true) }, 700)
    }

    /** Hold OK → pin / unpin; with a custom row order (Arrange apps), move to the front instead. */
    private fun togglePin(app: App) {
        if (history.customOrder) {
            history.moveToFront(app.pkg)
            showToast("Moved ${app.label} to the front")
        } else {
            val pinned = history.togglePin(app.pkg)
            showToast(if (pinned) "Pinned ${app.label} to Home" else "Unpinned ${app.label}")
        }
        fillRow(focusPkg = app.pkg, takeFocus = currentFocus is RowTile)
        if (library.isOpen) library.refresh(history.pinned().toSet())
    }

    // --- Options (···, or hold OK on a tile) --------------------------------------------------

    private fun buildOptions(): View {
        optionsCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(10), dp(14), dp(10), dp(10))
            elevation = dpf(24f)
            background = GradientDrawable().apply {
                cornerRadius = dpf(22f)
                setColor(Look.surface)
                setStroke(dp(1), Look.chipStroke)
            }
        }
        optionsLayer = FrameLayout(this).apply {
            visibility = View.GONE
            clipChildren = false
            setBackgroundColor(Look.withAlpha(Look.ink, 110))
            addView(optionsCard, FrameLayout.LayoutParams(dp(300), WRAP_CONTENT, Gravity.TOP or Gravity.START))
        }
        return optionsLayer
    }

    private fun showOptions(app: App) {
        val pinned = app.pkg in history.pinned()
        optionsCard.removeAllViews()
        optionsCard.addView(TextView(this).apply {
            text = app.label
            textSize = 14f
            typeface = Look.medium
            setTextColor(Look.dim)
            setPadding(dp(14), 0, dp(14), dp(8))
        })
        listOf(
            "Open" to { launch(app) },
            "Refresh" to { refresh(app) },
            (if (history.customOrder) "Move to front" else if (pinned) "Unpin from Home" else "Pin to Home") to { togglePin(app) },
            "App info" to {
                runCatching {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.pkg, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                Unit
            },
        ).forEach { (label, action) ->
            optionsCard.addView(Pill(this, label).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                tap { closeOptions(); action() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        // Opens beside the ··· button, PS5-style, whichever way it was asked for.
        val at = IntArray(2)
        moreButton.getLocationInWindow(at)
        (optionsCard.layoutParams as FrameLayout.LayoutParams).setMargins(at[0] + moreButton.width + dp(18), at[1] - dp(10), 0, 0)
        optionsReturn = currentFocus
        optionsOpen = true
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        optionsLayer.animate().cancel()
        optionsLayer.visibility = View.VISIBLE
        optionsLayer.alpha = 0f
        optionsLayer.animate().alpha(1f).setDuration(160).start()
        optionsCard.getChildAt(1).requestFocus()
    }

    private fun closeOptions() {
        if (!optionsOpen) return
        optionsOpen = false
        optionsLayer.animate().alpha(0f).setDuration(140).withEndAction { if (!optionsOpen) optionsLayer.visibility = View.GONE }.start()
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        (optionsReturn?.takeIf { it.isAttachedToWindow } ?: selected)?.requestFocus()
    }

    // --- Library and Control Center ---------------------------------------------------------------

    private fun openLibrary() {
        closeOptions()
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        library.open(apps, ::tileArt, history.pinned().toSet())
    }

    private fun closeLibrary() {
        library.close()
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        selected?.requestFocus()
    }

    private fun openControls(focus: Int = 0) {
        if (controls.isOpen) return
        closeOptions()
        library.close()
        picker.cancel()
        arrange.close()
        uploads.close()
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        controls.open(focus)
    }

    /** Wallpaper picker, with Home and the app art cleared away so you see the whole wallpaper. */
    private fun openPicker() {
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        home.animate().alpha(0f).setDuration(250).start()
        ambient.showArt(null)
        picker.open(history.wallpaper)
    }

    /** Add your own wallpaper: the QR code screen, with Home cleared away like the picker. */
    private fun openUploads() {
        home.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        home.animate().alpha(0f).setDuration(250).start()
        ambient.showArt(null)
        uploads.open()
    }

    /** Dark ⇄ Light. Every view reads its colours when built, so the screen is rebuilt in the new mode. */
    private fun switchAppearance() {
        history.dark = !history.dark
        reopenControlsAt = APPEARANCE_SHORTCUT
        recreate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_CONTROLS, reopenControlsAt)
    }

    private fun closeControls() {
        controls.close()
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        selected?.requestFocus()
    }

    // --- Hints and toasts --------------------------------------------------------------------------

    private fun updateHints(focus: View?) = when {
        optionsOpen -> setHints("OK" to "Select", "BACK" to "Close")
        uploads.isOpen -> setHints(*uploads.hints().toTypedArray())
        picker.isOpen -> setHints(*picker.hints(focus).toTypedArray())
        arrange.isOpen -> setHints(*arrange.hints(focus).toTypedArray())
        controls.isOpen -> setHints(*controls.hints(focus).toTypedArray())
        library.isOpen -> setHints("OK" to "Open", "HOLD OK" to (if (history.customOrder) "Move to front" else "Pin / Unpin"), "BACK" to "Close")
        focus is RowTile && currentApp != null -> setHints("OK" to openButton.text.toString(), "HOLD OK" to "Options")
        focus is RowTile -> setHints("OK" to "Open Library")
        tabViews.values.any { it === focus } -> setHints("▼" to "Choose an app")
        else -> setHints("OK" to "Select", "BACK" to "Back to start")
    }

    private fun setHints(vararg pairs: Pair<String, String>) {
        val key = pairs.joinToString("|") { "${it.first}=${it.second}" }
        if (key == hintKey) return
        hintKey = key
        hints.removeAllViews()
        pairs.forEachIndexed { i, (k, action) ->
            hints.addView(hint(this, k, action), LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                .apply { if (i > 0) marginStart = dp(26) })
        }
    }

    private fun showToast(message: String) {
        toast.text = message
        toast.animate().cancel()
        toast.alpha = 0f
        toast.animate().alpha(1f).setDuration(180).start()
        root.removeCallbacks(hideToast)
        root.postDelayed(hideToast, 2200)
    }

    private val hideToast = Runnable { toast.animate().alpha(0f).setDuration(250).start() }

    /** Click with the select chime. */
    private fun View.tap(action: () -> Unit) = setOnClickListener { sounds.select(); action() }

    // --- Lifecycle and keys ---------------------------------------------------------------------

    private val sleep = Runnable { ambient.awake = false }

    /** Animate the backdrop for a while; any press keeps it going. */
    private fun wake() {
        ambient.awake = true
        root.removeCallbacks(sleep)
        root.postDelayed(sleep, IDLE_MS)
    }

    private fun updateClock() {
        time.text = DateFormat.getTimeFormat(this).format(Date())
    }

    private val refreshWeather = Runnable { fetchWeather() }

    /** Weather next to the clock: refetched every 15 minutes while Home is on screen. */
    private fun fetchWeather() {
        root.removeCallbacks(refreshWeather)
        Weather.latest?.let(::showWeather) // what we have now, even if it's due a refresh
        if (SystemClock.elapsedRealtime() - Weather.fetchedAt < WEATHER_EVERY_MS && Weather.latest != null) {
            root.postDelayed(refreshWeather, WEATHER_EVERY_MS)
            return
        }
        Net.io.execute {
            runCatching { Weather.fetch() }
                .onFailure { Log.w("PlayHome", "weather", it) }
                .onSuccess { w ->
                    if (w == null) return@onSuccess // a time zone with no city: no weather, no guess
                    Weather.latest = w
                    Weather.fetchedAt = SystemClock.elapsedRealtime()
                    runOnUiThread { showWeather(w) }
                }
        }
        root.postDelayed(refreshWeather, WEATHER_EVERY_MS)
    }

    private fun showWeather(w: Weather) {
        weatherLabel.text = "${w.symbol}  ${w.temp}°"
        weatherLabel.visibility = View.VISIBLE
    }

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = updateClock()
    }
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            reloadApps()
            fillRow(focusPkg = currentApp?.pkg, takeFocus = currentFocus is RowTile)
        }
    }

    private var appsSignature: String? = null

    /** Reloads the apps and drops their cached art, but only if something was installed, removed or updated. */
    private fun reloadApps() {
        val now = Apps.signature(this)
        if (now == appsSignature) return
        appsSignature = now
        apps = Apps.load(this)
        tileCache.clear()
        Wallpapers.worker.execute { heroCache.clear() }
    }

    override fun onStart() {
        super.onStart()
        reloadApps() // every time: apps installed or updated while another app was in front count too
        updateClock()
        fetchWeather()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        registerReceiver(packageReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
    }

    /** Back on Home: overlays closed and the row rebuilt, so the app you just used comes first. */
    override fun onResume() {
        super.onResume()
        closeOptions()
        library.close()
        controls.close()
        picker.cancel()
        arrange.close()
        uploads.close()
        home.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        fillRow()
        wake()
        if (reopenControlsAt >= 0) { openControls(reopenControlsAt); reopenControlsAt = -1 }
        // The first focus lands before the window is attached, when focus listeners don't fire yet.
        root.post { updateHints(currentFocus) }
    }

    override fun onPause() {
        uploads.close() // never leave the upload server running behind another app
        root.removeCallbacks(sleep)
        ambient.awake = false // never animate behind a running app
        super.onPause()
    }

    override fun onStop() {
        root.removeCallbacks(refreshWeather)
        unregisterReceiver(clockReceiver)
        unregisterReceiver(packageReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        sounds.release()
        super.onDestroy()
    }

    /** Home pressed while already home: everything closed, back to the first tile. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        closeOptions()
        picker.cancel()
        arrange.close()
        uploads.close()
        if (library.isOpen) closeLibrary()
        if (controls.isOpen) closeControls()
        focusFirst()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) wake()
        // Menu / Info (and the TCL remote's gear, if the system lets it through) act as the PS button.
        if (event.keyCode in controlKeys) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                sounds.select()
                if (controls.isOpen) closeControls() else openControls()
            }
            return true
        }
        val before = currentFocus
        val handled = super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && before != null && currentFocus != null && currentFocus !== before) sounds.move()
        root.post { updateHints(currentFocus) } // e.g. the volume card switching to adjust mode
        return handled
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            sounds.back()
            when {
                optionsOpen -> closeOptions()
                uploads.isOpen -> uploads.back()
                picker.isOpen -> picker.cancel()
                controls.isOpen -> closeControls()
                library.isOpen -> closeLibrary()
                arrange.isOpen -> arrange.back()
                else -> focusFirst()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

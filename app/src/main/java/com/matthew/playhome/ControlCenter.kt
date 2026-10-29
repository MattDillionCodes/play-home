package com.matthew.playhome

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date

/**
 * PS5-style Control Center: slides up from the bottom over a dimmed Home. Cards on top (clock,
 * weather, volume, one per HDMI input) over a row of round shortcuts (Home, Library, Arrange apps, Wallpaper,
 * Appearance, sounds, settings), with the focused shortcut's name shown above the row as on the PS5.
 */
class ControlCenter(
    private val activity: Activity,
    private val sounds: Sounds,
    private val history: History,
    private val onHome: () -> Unit,
    private val onLibrary: () -> Unit,
    private val onArrange: () -> Unit,
    private val onWallpaper: () -> Unit,
    private val onAppearance: () -> Unit,
) {
    private class Shortcut(val glyph: Glyph, val label: String, val action: (GlyphButton) -> Unit)

    private val audio = activity.getSystemService(AudioManager::class.java)
    private val tvInputs: TvInputManager? = activity.getSystemService(TvInputManager::class.java)
    private val cards = LinearLayout(activity).apply { clipChildren = false; clipToPadding = false; setPadding(dp(6), dp(8), dp(6), dp(8)) }
    /** Scrolls sideways when the TV has more HDMI inputs than fit across the screen. */
    private val cardStrip = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
        addView(cards)
    }
    private val caption = TextView(activity).apply {
        textSize = 18f
        typeface = Look.medium
        setTextColor(Look.text)
    }
    private val shortcuts = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
    }
    private val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        clipToPadding = false
        setPadding(dp(48), dp(80), dp(48), dp(64)) // bottom: room for the hint bar
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Look.withAlpha(Look.ink, 120), Look.withAlpha(Look.ink, 240), Look.withAlpha(Look.ink, 252)))
        addView(cardStrip, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { marginStart = -dp(6) })
        addView(caption, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(28); bottomMargin = dp(12) })
        addView(shortcuts, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
    }
    private val scrim = View(activity).apply { setBackgroundColor(Look.scrim) }
    val view = FrameLayout(activity).apply {
        visibility = View.GONE
        clipChildren = false
        addView(scrim, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(panel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }
    var isOpen = false
        private set

    /** Opens with focus on shortcut [focus] (Appearance reopens on itself after the screen rebuilds). */
    fun open(focus: Int = 0) {
        if (isOpen) return
        isOpen = true
        build()
        view.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(220).start()
        panel.translationY = panel.dpf(360f)
        panel.animate().translationY(0f).setInterpolator(Look.ease).setDuration(300).start()
        shortcuts.getChildAt(focus)?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        scrim.animate().alpha(0f).setDuration(200).start()
        panel.animate().translationY(panel.dpf(360f)).setInterpolator(Look.ease).setDuration(240)
            .withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
    }

    /** The remote buttons that do something with [focus] focused, for Home's hint bar. */
    fun hints(focus: View?): List<Pair<String, String>> = when {
        focus is VolumeCard && focus.adjusting -> listOf("◀ ▶" to "Volume", "OK" to "Done")
        focus is VolumeCard -> listOf("OK" to "Adjust volume", "BACK" to "Close")
        else -> listOf("OK" to "Select", "BACK" to "Close")
    }

    private fun build() {
        cards.removeAllViews()
        cards.addView(clockCard(), LinearLayout.LayoutParams(dp(220), dp(128)))
        cards.addView(weatherCard(), LinearLayout.LayoutParams(dp(240), dp(128)).apply { marginStart = dp(18) })
        cards.addView(VolumeCard(activity, audio, sounds), LinearLayout.LayoutParams(dp(280), dp(128)).apply { marginStart = dp(18) })
        inputCards().forEach { cards.addView(it, LinearLayout.LayoutParams(dp(210), dp(128)).apply { marginStart = dp(18) }) }

        shortcuts.removeAllViews()
        val items = listOf(
            Shortcut(Glyph.HOME, "Home") { onHome() },
            Shortcut(Glyph.GRID, "Library") { onLibrary() },
            Shortcut(Glyph.ARRANGE, "Arrange apps") { onArrange() },
            Shortcut(Glyph.IMAGE, "Wallpaper") { onWallpaper() },
            Shortcut(Glyph.CONTRAST, if (Look.dark) "Appearance: Dark" else "Appearance: Light") { onAppearance() },
            Shortcut(Glyph.NOTE, soundsLabel()) { toggleSounds(it) },
            Shortcut(Glyph.DISPLAY, "Picture & Display") { openSettings(Settings.ACTION_DISPLAY_SETTINGS) },
            Shortcut(Glyph.WIFI, "Network & Internet") { openSettings(Settings.ACTION_WIFI_SETTINGS) },
            Shortcut(Glyph.GEAR, "All Settings") { openSettings(Settings.ACTION_SETTINGS) },
        )
        items.forEachIndexed { i, s ->
            val button = GlyphButton(activity, s.glyph, s.label).apply {
                crossed = s.glyph == Glyph.NOTE && !sounds.enabled
                setOnFocusChangeListener { v, focused -> if (focused) caption.text = (v as GlyphButton).label }
                setOnClickListener { v -> sounds.select(); s.action(v as GlyphButton) }
            }
            shortcuts.addView(button, LinearLayout.LayoutParams(dp(62), dp(62)).apply { if (i > 0) marginStart = dp(22) })
        }
        shortcuts.getChildAt(0).let { it.nextFocusLeftId = it.id }
        shortcuts.getChildAt(items.lastIndex).let { it.nextFocusRightId = it.id }
    }

    private fun toggleSounds(button: GlyphButton) {
        sounds.enabled = !sounds.enabled
        history.sounds = sounds.enabled
        button.crossed = !sounds.enabled
        button.label = soundsLabel()
        caption.text = button.label
    }

    private fun soundsLabel() = if (sounds.enabled) "UI sounds: On" else "UI sounds: Off"

    private fun openSettings(action: String) {
        runCatching { activity.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun clockCard() = Card(activity).apply {
        val now = Date()
        addView(cardCaption(activity, DateFormat.format("EEEE", now).toString()))
        addView(TextView(activity).apply {
            text = DateFormat.getTimeFormat(activity).format(now)
            textSize = 34f
            typeface = Look.light
            setTextColor(Look.text)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(TextView(activity).apply {
            text = DateFormat.format("MMMM d", now)
            textSize = 15f
            setTextColor(Look.dim)
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
    }

    /** Weather: the latest forecast Home fetched (Open-Meteo), or a placeholder until one arrives. */
    private fun weatherCard() = Card(activity).apply {
        val w = Weather.latest
        addView(cardCaption(activity, if (w == null) "Weather" else "Weather · ${w.place}"))
        addView(TextView(activity).apply {
            text = if (w == null) "—" else "${w.symbol}  ${w.temp}°"
            textSize = 30f
            typeface = Look.light
            setTextColor(Look.text)
            includeFontPadding = false
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(TextView(activity).apply {
            text = if (w == null) "No forecast yet" else "${w.condition}  ·  H ${w.high}°  L ${w.low}°"
            textSize = 15f
            setTextColor(Look.dim)
            isSingleLine = true
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
    }

    /** One card per physical HDMI port: its name, the CEC device on it, and a green dot for signal. */
    private fun inputCards(): List<View> {
        val all = runCatching { tvInputs?.tvInputList.orEmpty() }.getOrDefault(emptyList())
        return all.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null }
            .sortedBy { it.loadLabel(activity).toString() }
            .map { port ->
                val name = (port.loadCustomLabel(activity) ?: port.loadLabel(activity)).toString()
                val device = all.firstOrNull { it.parentId == port.id }?.loadLabel(activity)?.toString()
                val live = runCatching { tvInputs?.getInputState(port.id) == TvInputManager.INPUT_STATE_CONNECTED }
                    .getOrDefault(false)
                Card(activity).apply {
                    isFocusable = true
                    addView(cardCaption(activity, "Input"))
                    addView(TextView(activity).apply {
                        text = name
                        textSize = 20f
                        typeface = Look.medium
                        setTextColor(Look.text)
                    }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
                    if (device != null && device != name) addView(TextView(activity).apply {
                        text = device
                        textSize = 14f
                        setTextColor(Look.dim)
                        isSingleLine = true
                    })
                    if (live) addView(TextView(activity).apply {
                        text = "● Signal"
                        textSize = 13f
                        setTextColor(Color.rgb(48, 209, 88))
                    })
                    setOnClickListener {
                        sounds.select()
                        // TCL's player only matches VIEW with the channel MIME type attached.
                        runCatching {
                            activity.startActivity(Intent(Intent.ACTION_VIEW)
                                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(port.id), "vnd.android.cursor.item/channel")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
            }
    }

    private fun dp(v: Int) = activity.dp(v)
}

/** A small all-caps label at the top of a card. */
private fun cardCaption(context: Context, text: String) = TextView(context).apply {
    this.text = text.uppercase()
    textSize = 12f
    typeface = Look.medium
    letterSpacing = 0.12f
    setTextColor(Look.dim)
}

/** A Control Center card: rounded and translucent; focus adds the same white ring the tiles use. */
open class Card(context: Context) : LinearLayout(context) {
    private val r = dpf(18f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dpf(3f)
        color = Look.ring
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { // a soft glow without a per-frame shadow blur
        style = Paint.Style.STROKE
        strokeWidth = dpf(8f)
        color = Look.withAlpha(Look.glow, 70)
    }

    init {
        orientation = VERTICAL
        setWillNotDraw(false)
        defaultFocusHighlightEnabled = false
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        fill.color = if (isFocused) Look.surfaceFocused else Look.surface // solid: nothing behind bleeds through
        canvas.drawRoundRect(0f, 0f, w, h, r, r, fill)
        if (isFocused) {
            val g = dpf(4f)
            val o = g + dpf(3f)
            canvas.drawRoundRect(-o, -o, w + o, h + o, r + o, r + o, halo)
            canvas.drawRoundRect(-g, -g, w + g, h + g, r + g, r + g, ring)
        }
        super.onDraw(canvas)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val s = if (gainFocus) 1.03f else 1f
        animate().scaleX(s).scaleY(s).setInterpolator(Look.ease).setDuration(160).start()
        invalidate()
    }
}

/**
 * Volume card. OK starts adjusting, then ◀ ▶ change the volume and OK or Back finish; the rest of
 * the time ◀ ▶ move between cards as usual, so the card never traps focus.
 */
class VolumeCard(context: Context, private val audio: AudioManager, private val sounds: Sounds) : Card(context) {
    private val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private val step = maxOf(1, max / 50)
    private var value = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    var adjusting = false
        private set
    private val number = TextView(context).apply {
        textSize = 34f
        typeface = Look.light
        setTextColor(Look.text)
        includeFontPadding = false
    }
    private val bar = object : View(context) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            val h = height.toFloat()
            val r = h / 2
            p.color = Look.chipStrong
            canvas.drawRoundRect(0f, 0f, width.toFloat(), h, r, r, p)
            p.color = if (adjusting) Look.accent else Look.text
            val f = if (max == 0) 0f else value / max.toFloat()
            canvas.drawRoundRect(0f, 0f, maxOf(h, width * f), h, r, r, p)
        }
    }

    init {
        isFocusable = true
        addView(cardCaption(context, "Volume"))
        addView(number, LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })
        addView(bar, LayoutParams(MATCH_PARENT, dp(8)).apply { topMargin = dp(14) })
        render()
    }

    private fun render() {
        number.text = if (adjusting) "◀  $value  ▶" else "$value"
        bar.invalidate()
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when {
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                adjusting = !adjusting
                sounds.select()
            }
            adjusting && keyCode == KeyEvent.KEYCODE_BACK -> {
                adjusting = false
                sounds.back()
            }
            adjusting && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) -> {
                value = (value + if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) step else -step).coerceIn(0, max)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                sounds.move()
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        render()
        return true
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && adjusting) { adjusting = false; render() } // Up/Down out of the card ends adjusting
    }
}

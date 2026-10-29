package com.matthew.playhome

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * "Add your own" wallpaper: a QR code to scan with your phone, which opens a page served by the
 * TV ([UploadServer]) for sending one photo. The photo then fills the screen as a preview with
 * Keep / Discard; only Keep makes it a wallpaper. The server lives exactly as long as the QR
 * code is on screen (and at most five minutes).
 */
class UploadScreen(
    private val activity: Activity,
    private val sounds: Sounds,
    private val preview: (Wallpaper) -> Unit,
    private val restore: () -> Unit,
    private val onKeep: (Wallpaper) -> Unit,
    private val onClose: () -> Unit,
    private val toast: (String) -> Unit,
) {
    private var server: UploadServer? = null
    private val pending = AtomicReference<File?>(null) // a received photo awaiting Keep / Discard

    private val qr = ImageView(activity)
    private val address = TextView(activity).apply {
        textSize = 13f
        typeface = Look.regular
        setTextColor(Look.dim)
    }
    private val timer = TextView(activity).apply {
        textSize = 15f
        typeface = Look.medium
        setTextColor(Look.text)
    }
    private val again = Pill(activity, "New code").apply {
        visibility = View.GONE
        setOnClickListener { sounds.select(); startServer() }
    }
    /** Always there, so focus has somewhere to be on this screen (and not on Home behind it). */
    private val closeButton = Pill(activity, "Close").apply { setOnClickListener { sounds.back(); close() } }
    private val scanPanel = LinearLayout(activity).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(64), 0, dp(64), 0)
        addView(FrameLayout(activity).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(20f); setColor(android.graphics.Color.WHITE) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(qr, FrameLayout.LayoutParams(dp(250), dp(250)))
        })
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(label("Add your own wallpaper", 34f, Look.text, Look.light))
            addView(label("1.  Scan the code with your phone's camera.\n" +
                "2.  Pick a photo and tap Send to TV.\n" +
                "3.  Approve it here with the remote.", 18f, Look.text, Look.regular).apply { setLineSpacing(dpf(6f), 1f) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(label("Your phone needs to be on the same Wi-Fi as the TV. The code only works while " +
                "this screen is open, and nothing is used until you approve it.", 14f, Look.dim, Look.regular),
                LinearLayout.LayoutParams(dp(470), WRAP_CONTENT).apply { topMargin = dp(18) })
            addView(timer, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(16) })
            addView(address, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(LinearLayout(activity).apply {
                clipChildren = false
                addView(closeButton)
                addView(again, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(14) })
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(18) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(40) })
    }
    private val keep = Pill(activity, "Keep", primary = true).apply { setOnClickListener { sounds.select(); keepPending() } }
    private val discard = Pill(activity, "Discard").apply { setOnClickListener { sounds.back(); discardPending() } }
    private val reviewPanel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(dp(48), dp(80), dp(48), dp(64))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Look.withAlpha(Look.ink, 0), Look.withAlpha(Look.ink, 220), Look.withAlpha(Look.ink, 245)))
        addView(label("Use this wallpaper?", 30f, Look.text, Look.light))
        addView(label("It came from your phone. Keep adds it to your wallpapers and uses it now.", 16f, Look.dim, Look.regular),
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(LinearLayout(activity).apply {
            clipChildren = false
            addView(keep)
            addView(discard, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16) })
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(20) })
    }
    private val backdrop = View(activity).apply { setBackgroundColor(Look.overlay) }
    val view = FrameLayout(activity).apply {
        visibility = View.GONE
        clipChildren = false
        addView(backdrop, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(scanPanel, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(reviewPanel, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
    }
    @Volatile var isOpen = false // read on the server thread too
        private set
    val reviewing get() = reviewPanel.visibility == View.VISIBLE

    fun open() {
        isOpen = true
        // Photos left waiting if the app was stopped mid-review last time.
        activity.cacheDir.listFiles { f -> f.name.startsWith("upload-") }?.forEach { it.delete() }
        showScan()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(220).start()
        closeButton.requestFocus()
        startServer()
    }

    /** Back: discard a photo being reviewed, otherwise leave. */
    fun back() {
        if (reviewing) discardPending() else close()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        stopServer()
        val unreviewed = pending.getAndSet(null)
        if (unreviewed != null) { unreviewed.delete(); restore() } // was on screen as a preview
        view.removeCallbacks(tick)
        view.animate().alpha(0f).setDuration(180).withEndAction { if (!isOpen) view.visibility = View.GONE }.start()
        onClose()
    }

    fun hints(): List<Pair<String, String>> =
        if (reviewing) listOf("OK" to "Select", "BACK" to "Discard") else listOf("OK" to "Select", "BACK" to "Close")

    private fun startServer() {
        stopServer()
        val host = UploadServer.lanAddress()
        if (host == null) {
            qr.setImageDrawable(null)
            timer.text = "This TV isn't on a network right now."
            address.text = ""
            again.visibility = View.VISIBLE
            return
        }
        val s = runCatching { UploadServer(activity.cacheDir, ::received) }.getOrNull() ?: run {
            timer.text = "Couldn't start. Try again."
            again.visibility = View.VISIBLE
            return
        }
        server = s
        s.start()
        val url = s.url(host)
        qr.setImageBitmap(Qr.encode(url).bitmap(8))
        address.text = "Or type it in: $url"
        again.visibility = View.GONE
        view.post(tick)
    }

    private fun stopServer() {
        server?.stop()
        server = null
    }

    /** Once a second: the countdown, and a fresh-code button when time's up. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            val s = server ?: return
            if (!isOpen) return
            val left = s.secondsLeft
            if (left <= 0) {
                stopServer()
                qr.setImageDrawable(null)
                timer.text = "This code has expired."
                again.visibility = View.VISIBLE
                return
            }
            timer.text = "Code works for %d:%02d".format(left / 60, left % 60)
            view.postDelayed(this, 1000)
        }
    }

    /** From the server thread: a cleaned photo arrived. Taken only if none is awaiting review. */
    private fun received(file: File): Boolean {
        if (!isOpen || !pending.compareAndSet(null, file)) return false
        activity.runOnUiThread {
            if (!isOpen) { pending.getAndSet(null)?.delete(); return@runOnUiThread }
            preview(Wallpaper.of(file))
            backdrop.animate().alpha(0f).setDuration(300).start()
            scanPanel.visibility = View.GONE
            reviewPanel.visibility = View.VISIBLE
            keep.requestFocus()
        }
        return true
    }

    private fun keepPending() {
        val file = pending.getAndSet(null) ?: return
        val kept = File(Wallpaper.folder(activity), "${System.currentTimeMillis()}.jpg")
        val saved = runCatching {
            if (!file.renameTo(kept)) { file.copyTo(kept, overwrite = true); file.delete() }
            Wallpaper.mine(activity).first { it.file?.name == kept.name }
        }.getOrNull()
        if (saved == null) { // e.g. the TV's storage is full
            file.delete()
            kept.delete()
            restore()
            toast("Couldn't save that photo")
        } else {
            onKeep(saved)
        }
        close()
    }

    private fun discardPending() {
        pending.getAndSet(null)?.delete()
        restore()
        showScan()
        if (server?.isRunning != true) { again.visibility = View.VISIBLE; again.requestFocus() } else closeButton.requestFocus()
    }

    private fun showScan() {
        reviewPanel.visibility = View.GONE
        scanPanel.visibility = View.VISIBLE
        backdrop.alpha = 1f
    }

    private fun label(text: String, size: Float, color: Int, face: android.graphics.Typeface) = TextView(activity).apply {
        this.text = text
        textSize = size
        typeface = face
        setTextColor(color)
    }
}

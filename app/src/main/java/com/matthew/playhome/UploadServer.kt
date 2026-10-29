package com.matthew.playhome

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.media.ExifInterface
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The tiny web server behind "Add your own" wallpaper: your phone scans the QR code, gets a page
 * from the TV, and sends one photo. It's safe by construction:
 *
 * - It runs only while the Add wallpaper screen is open, and stops itself after [LIFETIME_MS].
 * - The only URL is `/u/<token>`, a fresh 128-bit random token each time; anything else gets a
 *   plain 404, so there's nothing to find by guessing or scanning.
 * - It only answers devices on the local network (private / link-local / loopback addresses).
 * - A wrong token is refused from the first line; headers are capped at 8 KB and must arrive
 *   within 5 s, an upload ([MAX_UPLOAD] bytes max) within 90 s; [stop] closes every connection.
 * - Only files Android can decode as an image are accepted, and the file itself is never kept:
 *   the picture is decoded, turned upright, cropped to 1920x1080 and written as a brand-new
 *   JPEG, so no EXIF / location / anything else from the original survives.
 * - Nothing becomes a wallpaper until it's approved on the TV ([onImage] just proposes it).
 *
 * It's plain HTTP (a TV on a home network can't get a trusted HTTPS certificate); the random
 * token and the short lifetime are the protection.
 */
class UploadServer(
    private val workDir: File,
    /** Called on the server thread with the cleaned 1920x1080 JPEG; false = busy (one awaits approval). */
    private val onImage: (File) -> Boolean,
) {
    val token: String = ByteArray(16).also { SecureRandom().nextBytes(it) }
        .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    private val path = "/u/$token"
    private val server = ServerSocket(0) // any free port; it's in the QR code
    val port: Int = server.localPort
    private val startedAt = SystemClock.elapsedRealtime() // immune to the TV's clock being adjusted
    @Volatile private var running = true

    // A few handlers, so a browser's idle "preconnect" socket can't hold up the real request.
    private val handlers = Executors.newFixedThreadPool(HANDLERS) { r -> Thread(r, "PlayHomeUpload").apply { isDaemon = true } }
    private val deadlines = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "PlayHomeUploadDeadline").apply { isDaemon = true } }
    /** Every connection still open, with the deadline that will close it; [stop] closes them all. */
    private val open = ConcurrentHashMap<Socket, ScheduledFuture<*>>()
    /** One photo is read and decoded at a time: a decode can take tens of MB, and Home must not run out. */
    private val decoding = Semaphore(1)

    val secondsLeft: Int get() = ((LIFETIME_MS - (SystemClock.elapsedRealtime() - startedAt)) / 1000).toInt().coerceAtLeast(0)
    val isRunning get() = running && secondsLeft > 0

    fun url(host: String) = "http://$host:$port$path"

    fun start() {
        thread(name = "PlayHomeUploadAccept", isDaemon = true) {
            server.soTimeout = 1000 // wake up every second to notice the lifetime running out
            while (isRunning) {
                val socket = try { server.accept() } catch (e: java.net.SocketTimeoutException) { continue } catch (e: IOException) {
                    if (!isRunning) break
                    Thread.sleep(100) // e.g. out of file descriptors for a moment: keep serving, don't spin
                    continue
                }
                val from = socket.inetAddress
                if (!(from.isSiteLocalAddress || from.isLinkLocalAddress || from.isLoopbackAddress)) { socket.close(); continue } // not this network
                if (!admit(from)) { socket.close(); continue }
                deadline(socket, QUEUE_DEADLINE_MS) // waiting for a free handler
                try {
                    handlers.execute {
                        // The header clock starts when a handler picks the socket up, so idle sockets
                        // hogging the handlers can't make a real request time out while it waits its turn.
                        deadline(socket, HEAD_DEADLINE_MS)
                        // Throwable, not Exception: an OutOfMemoryError here must not take Home down.
                        try { handle(socket) } catch (e: Throwable) { Log.w("PlayHome", "upload", e) }
                        finally { open.remove(socket)?.cancel(false); runCatching { socket.close() } }
                    }
                } catch (e: Exception) { // stopped meanwhile
                    open.remove(socket)?.cancel(false)
                    socket.close()
                }
            }
            stop()
        }
    }

    /** Shuts everything: the port, and any connection still being read or answered. */
    fun stop() {
        running = false
        runCatching { server.close() }
        open.keys.forEach { runCatching { it.close() } }
        open.clear()
        handlers.shutdownNow()
        deadlines.shutdownNow()
    }

    /**
     * Whether to take a new connection from [from], given how many are already open ([open].size in
     * all, [openFrom] from that address). Turning one away just closes it.
     */
    private fun admit(from: InetAddress): Boolean {
        // TODO(human): cap open connections so a flood from the LAN can't use up the TV's sockets.
        return open.size < 32
    }

    private fun openFrom(from: InetAddress) = open.keys.count { it.inetAddress == from }

    /** (Re)sets the hard limit after which [socket] is closed, however slowly it's trickling bytes. */
    private fun deadline(socket: Socket, ms: Long) {
        val kill = runCatching { deadlines.schedule({ runCatching { socket.close() } }, ms, TimeUnit.MILLISECONDS) }.getOrNull()
            ?: return run { runCatching { socket.close() } }
        open.put(socket, kill)?.cancel(false)
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = READ_TIMEOUT_MS
        val input = socket.getInputStream().buffered()
        val out = socket.getOutputStream()
        fun respond(code: Int, reason: String, type: String, body: ByteArray) {
            val head = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; img-src blob:\r\n" +
                "Connection: close\r\n\r\n"
            out.write(head.toByteArray())
            out.write(body)
            out.flush()
        }
        fun text(code: Int, reason: String, message: String) = respond(code, reason, "text/plain; charset=utf-8", message.toByteArray())

        // The request line first: a wrong token is turned away before anything else is read.
        val requestLine = readLine(input, MAX_HEAD) ?: return text(400, "Bad Request", "Bad request.")
        val parts = requestLine.split(" ")
        if (parts.size < 3) return text(400, "Bad Request", "Bad request.")
        val method = parts[0]
        // Constant-time comparison, so response timing gives nothing away about the token.
        if (!MessageDigest.isEqual(parts[1].toByteArray(), path.toByteArray())) return text(404, "Not Found", "Not found.")
        val headers = HashMap<String, String>()
        var budget = MAX_HEAD - requestLine.length
        while (true) {
            val line = readLine(input, budget) ?: return text(400, "Bad Request", "Bad request.")
            if (line.isEmpty()) break
            budget -= line.length + 2
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        when (method) {
            "GET" -> respond(200, "OK", "text/html; charset=utf-8", PAGE.toByteArray())
            "POST" -> {
                val length = headers["content-length"]?.toLongOrNull()
                    ?: return text(411, "Length Required", "Please send the photo again.")
                if (length <= 0) return text(400, "Bad Request", "That file is empty.")
                if (length > MAX_UPLOAD) return text(413, "Payload Too Large", "That file is too big (25 MB max).")
                if (!decoding.tryAcquire()) return text(409, "Conflict", "The TV is busy with another photo. Try again in a moment.")
                try {
                    deadline(socket, BODY_DEADLINE_MS) // a real photo gets longer, but still not forever
                    val bytes = readExactly(input, length.toInt()) ?: return text(400, "Bad Request", "The upload was cut off. Try again.")
                    val cleaned = (try { clean(bytes) } catch (e: OutOfMemoryError) { null })
                        ?: return text(415, "Unsupported Media Type", "That doesn't look like a photo. Try a JPEG or PNG.")
                    if (!isRunning || !onImage(cleaned)) {
                        cleaned.delete()
                        return text(409, "Conflict", "The TV isn't ready for a photo right now. Check the TV and try again.")
                    }
                    text(200, "OK", "Sent! Now approve it on your TV.")
                } finally {
                    decoding.release()
                }
            }
            else -> text(405, "Method Not Allowed", "Not allowed.")
        }
    }

    /** One CRLF-terminated line, at most [max] bytes; null if longer or the stream ends. */
    private fun readLine(input: InputStream, max: Int): String? {
        val buf = StringBuilder()
        while (buf.length < max) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return buf.removeSuffix("\r").toString()
            buf.append(c.toChar())
        }
        return null
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray? {
        val bytes = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(bytes, read, n - read)
            if (r < 0) return null
            read += r
        }
        return bytes
    }

    /**
     * Decodes the upload as an image (rejecting anything that isn't one, or is absurdly large),
     * rotates it upright per EXIF, centre-crops to 1920x1080 and writes a fresh sRGB JPEG.
     */
    private fun clean(bytes: ByteArray): File? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toLong() * h > 60_000_000L) return null
        val degrees = exifRotation(bytes)
        // Phones store most portrait photos sideways plus a "rotate 90" tag: size the decode for
        // how the picture will stand, or it comes out half as sharp as it could.
        val (uw, uh) = if (degrees % 180 == 0) w to h else h to w
        var sample = 1
        while (uw / (sample * 2) >= OUT_W && uh / (sample * 2) >= OUT_H) sample *= 2
        // Odd shapes (a 60000x1000 panorama) would still decode huge: cap what's held in memory.
        while ((w / sample).toLong() * (h / sample) > MAX_DECODED_PIXELS) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) // the TV's fastest format
        }) ?: return null
        // One matrix turns it upright and centre-crops it to cover 16:9, drawn straight into the
        // output, so there's never a second full-size copy of the photo in memory.
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        val turned = RectF(0f, 0f, decoded.width.toFloat(), decoded.height.toFloat()).also { m.mapRect(it) }
        val scale = maxOf(OUT_W / turned.width(), OUT_H / turned.height())
        m.postTranslate(-turned.left, -turned.top)
        m.postScale(scale, scale)
        m.postTranslate((OUT_W - turned.width() * scale) / 2, (OUT_H - turned.height() * scale) / 2)
        val out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(decoded, m, Paint(Paint.FILTER_BITMAP_FLAG))
        decoded.recycle()
        val file = File(workDir, "upload-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        out.recycle()
        return file
    }

    /** Clockwise degrees the stored picture must turn to stand upright (0, 90, 180 or 270). */
    private fun exifRotation(bytes: ByteArray): Int = runCatching {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    companion object {
        const val LIFETIME_MS = 5 * 60 * 1000L
        const val MAX_UPLOAD = 25L * 1024 * 1024
        private const val MAX_HEAD = 8192
        private const val MAX_DECODED_PIXELS = 8_000_000L // 32 MB decoded; plenty for a 1920x1080 crop
        private const val HANDLERS = 3
        private const val READ_TIMEOUT_MS = 5_000
        private const val QUEUE_DEADLINE_MS = 15_000L // waiting for a handler
        private const val HEAD_DEADLINE_MS = 5_000L // request line and headers
        private const val BODY_DEADLINE_MS = 90_000L // the whole upload: 25 MB at ~280 KB/s
        private const val OUT_W = 1920
        private const val OUT_H = 1080

        /** This TV's address on the home network (Ethernet or Wi-Fi), or null if it has none. */
        fun lanAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                // Ethernet / Wi-Fi before anything else (a VPN's address is no use to the phone).
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()

        /** The phone page: no external resources, one file picker, one button. */
        private val PAGE = """
            <!doctype html>
            <html lang="en"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Play Home wallpaper</title>
            <style>
              :root { color-scheme: dark; }
              body { margin: 0; min-height: 100vh; font: 17px/1.45 -apple-system, system-ui, sans-serif;
                     background: radial-gradient(circle at 80% 0%, #1b4fbf 0, #04122e 45%, #02050e 100%); color: #fff; }
              main { max-width: 440px; margin: 0 auto; padding: 32px 20px; }
              h1 { font-weight: 300; font-size: 28px; margin: 0 0 6px; }
              p { color: #b8c4dc; margin: 0 0 22px; }
              label { display: block; border: 1.5px dashed #5a7bb8; border-radius: 18px; padding: 22px; text-align: center; cursor: pointer; }
              input { display: none; }
              img { display: none; width: 100%; aspect-ratio: 16/9; object-fit: cover; border-radius: 14px; margin-top: 16px; }
              button { width: 100%; margin-top: 18px; padding: 15px; border: 0; border-radius: 999px; font-size: 18px;
                       background: #fff; color: #03060f; font-weight: 600; }
              button:disabled { opacity: .5; }
              #status { margin-top: 16px; text-align: center; color: #fff; min-height: 1.5em; }
            </style></head>
            <body><main>
              <h1>Send a wallpaper</h1>
              <p>Pick a photo. It's cropped to fit the TV, and you'll approve it on the TV before it's used.</p>
              <label for="file" id="pick">Choose a photo</label>
              <input id="file" type="file" accept="image/*">
              <img id="preview" alt="">
              <button id="send" disabled>Send to TV</button>
              <div id="status"></div>
            </main>
            <script>
              const file = document.getElementById('file'), send = document.getElementById('send');
              const status = document.getElementById('status'), preview = document.getElementById('preview');
              file.onchange = () => {
                const f = file.files[0];
                send.disabled = !f;
                if (f) { preview.src = URL.createObjectURL(f); preview.style.display = 'block';
                         document.getElementById('pick').textContent = 'Choose a different photo'; }
              };
              send.onclick = async () => {
                const f = file.files[0];
                if (!f) return;
                // Checked here too: the TV refuses it before reading, which a browser mid-upload can only report as a dropped connection.
                if (f.size > $MAX_UPLOAD) { status.textContent = 'That photo is too big (25 MB max).'; return; }
                send.disabled = true;
                status.textContent = 'Sending…';
                try {
                  const r = await fetch(location.pathname, { method: 'POST', body: f });
                  status.textContent = await r.text();
                } catch (e) {
                  status.textContent = "Couldn't reach the TV. Is the Add wallpaper screen still open?";
                }
                send.disabled = false;
              };
            </script>
            </body></html>
        """.trimIndent()
    }
}

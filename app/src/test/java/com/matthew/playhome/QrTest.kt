package com.matthew.playhome

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Round trip: everything our encoder makes must decode back, exactly, with ZXing. */
class QrTest {
    private fun decode(qr: Qr): String {
        val px = 4
        val quiet = 4
        val side = (qr.size + quiet * 2) * px
        val pixels = IntArray(side * side) { 0xFFFFFFFF.toInt() }
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (!qr[x, y]) continue
            for (dy in 0 until px) for (dx in 0 until px) {
                pixels[((y + quiet) * px + dy) * side + (x + quiet) * px + dx] = 0xFF000000.toInt()
            }
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to true, DecodeHintType.CHARACTER_SET to "UTF-8")).text
    }

    @Test fun typicalUploadUrl() {
        val url = "http://192.168.1.20:41263/u/yaxSX6GJCr3gleFh4rp2IA"
        assertEquals(url, decode(Qr.encode(url)))
    }

    /** Every length the encoder accepts, which walks through all ten versions and their block layouts. */
    @Test fun everyLengthFromOneTo213Bytes() {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;="
        val sizes = sortedSetOf<Int>()
        for (n in 1..213) {
            val text = String(CharArray(n) { alphabet[(it * 7 + n) % alphabet.length] })
            val qr = Qr.encode(text)
            sizes += qr.size
            assertEquals("length $n", text, decode(qr))
        }
        assertEquals("versions 1 to 10", (1..10).map { it * 4 + 17 }.toSortedSet(), sizes)
    }

    @Test fun utf8() {
        val text = "Café ☕ – http://tv.local/ü"
        assertEquals(text, decode(Qr.encode(text)))
    }

    @Test fun tooLongIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Qr.encode("x".repeat(214)) }
    }
}

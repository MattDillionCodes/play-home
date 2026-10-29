package com.matthew.playhome

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * A small QR code encoder (byte mode, error correction level M, versions 1 to 10: up to 213
 * bytes, plenty for a URL), so Play Home stays dependency-free. Follows the structure of
 * Project Nayuki's reference QR Code generator: build the data codewords, add Reed-Solomon
 * error correction per block, interleave, place modules around the function patterns, mask.
 */
class Qr private constructor(val size: Int) {
    private val modules = Array(size) { BooleanArray(size) }
    private val isFunction = Array(size) { BooleanArray(size) }

    operator fun get(x: Int, y: Int) = modules[y][x]

    /** Black modules on white, with the 4-module quiet zone scanners need. [px] per module. */
    fun bitmap(px: Int): Bitmap {
        val quiet = 4
        val side = (size + quiet * 2) * px
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val ink = Paint().apply { color = Color.BLACK }
        for (y in 0 until size) for (x in 0 until size) {
            if (modules[y][x]) {
                val l = ((x + quiet) * px).toFloat()
                val t = ((y + quiet) * px).toFloat()
                c.drawRect(l, t, l + px, t + px, ink)
            }
        }
        return b
    }

    companion object {
        // Level M, indexed by version (index 0 unused).
        private val ECC_PER_BLOCK = intArrayOf(-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26)
        private val NUM_BLOCKS = intArrayOf(-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5)
        private const val FORMAT_M = 0 // level M's 2-bit format code
        private const val MASK = 0 // any of the 8 masks decodes; 0 is fine for a short URL on a TV

        /** Encodes [text] (UTF-8) in the smallest version that fits. */
        fun encode(text: String): Qr {
            val data = text.toByteArray(Charsets.UTF_8)
            val version = (1..10).firstOrNull { v -> 4 + (if (v < 10) 8 else 16) + data.size * 8 <= dataCodewords(v) * 8 }
                ?: throw IllegalArgumentException("Too long for a QR code here: ${data.size} bytes")
            val qr = Qr(version * 4 + 17)
            qr.drawFunctionPatterns(version)
            qr.drawCodewords(addEccAndInterleave(version, dataCodewordsFor(version, data)))
            qr.applyMask(MASK)
            qr.drawFormatBits(MASK)
            return qr
        }

        private fun rawDataModules(v: Int): Int {
            var result = (16 * v + 128) * v + 64
            if (v >= 2) {
                val numAlign = v / 7 + 2
                result -= (25 * numAlign - 10) * numAlign - 55
                if (v >= 7) result -= 36
            }
            return result
        }

        private fun dataCodewords(v: Int) = rawDataModules(v) / 8 - ECC_PER_BLOCK[v] * NUM_BLOCKS[v]

        /** Mode + length + bytes, terminator, byte-aligned, then the 0xEC / 0x11 pad pattern. */
        private fun dataCodewordsFor(v: Int, data: ByteArray): ByteArray {
            val bits = ArrayList<Boolean>()
            fun put(value: Int, len: Int) { for (i in len - 1 downTo 0) bits += (value ushr i) and 1 != 0 }
            put(0b0100, 4) // byte mode
            put(data.size, if (v < 10) 8 else 16)
            data.forEach { put(it.toInt() and 0xFF, 8) }
            val capacity = dataCodewords(v) * 8
            put(0, minOf(4, capacity - bits.size))
            put(0, (8 - bits.size % 8) % 8)
            var pad = 0xEC
            while (bits.size < capacity) { put(pad, 8); pad = pad xor 0xEC xor 0x11 }
            return ByteArray(bits.size / 8) { i ->
                var b = 0
                for (j in 0 until 8) b = (b shl 1) or (if (bits[i * 8 + j]) 1 else 0)
                b.toByte()
            }
        }

        private fun addEccAndInterleave(v: Int, data: ByteArray): ByteArray {
            val numBlocks = NUM_BLOCKS[v]
            val eccLen = ECC_PER_BLOCK[v]
            val raw = rawDataModules(v) / 8
            val numShort = numBlocks - raw % numBlocks
            val shortLen = raw / numBlocks
            val divisor = rsDivisor(eccLen)
            val blocks = ArrayList<ByteArray>()
            var k = 0
            for (i in 0 until numBlocks) {
                val datLen = shortLen - eccLen + (if (i < numShort) 0 else 1)
                val dat = data.copyOfRange(k, k + datLen)
                k += datLen
                val ecc = rsRemainder(dat, divisor)
                // Short blocks get a placeholder byte so every block has the same length to interleave.
                val block = if (i < numShort) dat + byteArrayOf(0) + ecc else dat + ecc
                blocks += block
            }
            val out = ArrayList<Byte>(raw)
            for (i in blocks[0].indices) {
                for ((j, block) in blocks.withIndex()) {
                    if (i != shortLen - eccLen || j >= numShort) out += block[i]
                }
            }
            return out.toByteArray()
        }

        private fun rsDivisor(degree: Int): IntArray {
            val result = IntArray(degree)
            result[degree - 1] = 1
            var root = 1
            repeat(degree) {
                for (j in result.indices) {
                    result[j] = gfMultiply(result[j], root)
                    if (j + 1 < result.size) result[j] = result[j] xor result[j + 1]
                }
                root = gfMultiply(root, 0x02)
            }
            return result
        }

        private fun rsRemainder(data: ByteArray, divisor: IntArray): ByteArray {
            val result = IntArray(divisor.size)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor result[0]
                System.arraycopy(result, 1, result, 0, result.size - 1)
                result[result.size - 1] = 0
                for (i in result.indices) result[i] = result[i] xor gfMultiply(divisor[i], factor)
            }
            return ByteArray(result.size) { result[it].toByte() }
        }

        /** Multiplication in GF(2^8) with the QR polynomial 0x11D. */
        private fun gfMultiply(x: Int, y: Int): Int {
            var z = 0
            for (i in 7 downTo 0) {
                z = (z shl 1) xor ((z ushr 7) * 0x11D)
                z = z xor ((y ushr i) and 1) * x
            }
            return z
        }
    }

    private fun setFunction(x: Int, y: Int, dark: Boolean) {
        modules[y][x] = dark
        isFunction[y][x] = true
    }

    private fun drawFunctionPatterns(v: Int) {
        for (i in 0 until size) {
            setFunction(6, i, i % 2 == 0) // timing patterns
            setFunction(i, 6, i % 2 == 0)
        }
        drawFinder(3, 3)
        drawFinder(size - 4, 3)
        drawFinder(3, size - 4)
        val align = alignmentPositions(v)
        val last = align.size - 1
        for (i in align.indices) for (j in align.indices) {
            val corner = (i == 0 && j == 0) || (i == 0 && j == last) || (i == last && j == 0)
            if (!corner) drawAlignment(align[i], align[j])
        }
        drawFormatBits(0) // reserves the format areas; redrawn with the real mask at the end
        drawVersion(v)
    }

    private fun drawFinder(x: Int, y: Int) {
        for (dy in -4..4) for (dx in -4..4) {
            val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
            val xx = x + dx
            val yy = y + dy
            if (xx in 0 until size && yy in 0 until size) setFunction(xx, yy, dist != 2 && dist != 4)
        }
    }

    private fun drawAlignment(x: Int, y: Int) {
        for (dy in -2..2) for (dx in -2..2) setFunction(x + dx, y + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
    }

    private fun alignmentPositions(v: Int): IntArray {
        if (v == 1) return IntArray(0)
        val numAlign = v / 7 + 2
        val step = (v * 4 + numAlign * 2 + 1) / (numAlign * 2 - 2) * 2
        val result = IntArray(numAlign)
        result[0] = 6
        var pos = size - 7
        for (i in numAlign - 1 downTo 1) { result[i] = pos; pos -= step }
        return result
    }

    private fun drawFormatBits(mask: Int) {
        val data = FORMAT_M shl 3 or mask
        var rem = data
        repeat(10) { rem = (rem shl 1) xor ((rem ushr 9) * 0x537) }
        val bits = (data shl 10 or rem) xor 0x5412
        fun bit(i: Int) = (bits ushr i) and 1 != 0
        for (i in 0..5) setFunction(8, i, bit(i))
        setFunction(8, 7, bit(6))
        setFunction(8, 8, bit(7))
        setFunction(7, 8, bit(8))
        for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
        for (i in 0 until 8) setFunction(size - 1 - i, 8, bit(i))
        for (i in 8 until 15) setFunction(8, size - 15 + i, bit(i))
        setFunction(8, size - 8, true) // the always-dark module
    }

    private fun drawVersion(v: Int) {
        if (v < 7) return
        var rem = v
        repeat(12) { rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25) }
        val bits = v shl 12 or rem
        for (i in 0 until 18) {
            val dark = (bits ushr i) and 1 != 0
            val a = size - 11 + i % 3
            val b = i / 3
            setFunction(a, b, dark)
            setFunction(b, a, dark)
        }
    }

    /** Places the codewords in the zigzag, two columns at a time, skipping function modules. */
    private fun drawCodewords(data: ByteArray) {
        var i = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = (right + 1) and 2 == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y][x] && i < data.size * 8) {
                        modules[y][x] = (data[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 != 0
                        i++
                    }
                }
            }
            right -= 2
        }
    }

    private fun applyMask(mask: Int) {
        for (y in 0 until size) for (x in 0 until size) {
            val invert = when (mask) {
                0 -> (x + y) % 2 == 0
                1 -> y % 2 == 0
                2 -> x % 3 == 0
                3 -> (x + y) % 3 == 0
                4 -> (x / 3 + y / 2) % 2 == 0
                5 -> x * y % 2 + x * y % 3 == 0
                6 -> (x * y % 2 + x * y % 3) % 2 == 0
                else -> ((x + y) % 2 + x * y % 3) % 2 == 0
            }
            if (invert && !isFunction[y][x]) modules[y][x] = !modules[y][x]
        }
    }
}

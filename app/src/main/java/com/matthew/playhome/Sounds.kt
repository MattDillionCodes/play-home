package com.matthew.playhome

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The PlayStation-style UI sounds: a soft glassy tick when focus moves, a rising two-note chime on
 * select and a falling one on back. Synthesised into tiny WAVs at startup (no audio assets) and
 * played through SoundPool, which has the lowest latency for short clips.
 */
class Sounds(context: Context) {
    private class Tone(val hz: Double, val start: Double, val length: Double, val level: Double)

    private val pool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build())
        .build()
    private val dir = context.cacheDir
    private val move = load("move", Tone(1568.0, 0.0, 0.05, 0.8), Tone(3136.0, 0.0, 0.03, 0.2))
    private val select = load("select", Tone(880.0, 0.0, 0.16, 0.7), Tone(1318.5, 0.055, 0.2, 0.6))
    private val back = load("back", Tone(1318.5, 0.0, 0.12, 0.6), Tone(880.0, 0.05, 0.16, 0.6))

    var enabled = true

    fun move() = play(move, 0.16f)
    fun select() = play(select, 0.26f)
    fun back() = play(back, 0.22f)

    private fun play(id: Int, volume: Float) {
        if (enabled) pool.play(id, volume, volume, 1, 0, 1f)
    }

    private fun load(name: String, vararg tones: Tone): Int {
        val file = File(dir, "$name.wav")
        writeWav(file, tones.toList())
        return pool.load(file.path, 1)
    }

    /** 16-bit mono sine tones, each with a 4 ms attack (no click) and an exponential decay. */
    private fun writeWav(file: File, tones: List<Tone>) {
        val rate = 44100
        val n = (tones.maxOf { it.start + it.length } * rate).toInt() + 1
        val mix = DoubleArray(n)
        for (t in tones) {
            val first = (t.start * rate).toInt()
            val len = (t.length * rate).toInt()
            for (i in 0 until len) {
                val x = i / rate.toDouble()
                val envelope = min(1.0, x / 0.004) * exp(-x / (t.length / 4))
                mix[first + i] += sin(2 * PI * t.hz * x) * envelope * t.level
            }
        }
        val data = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + n * 2); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(n * 2)
            mix.forEach { putShort((it * 0.5 * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()) }
        }
        file.writeBytes(data.array())
    }

    fun release() = pool.release()
}

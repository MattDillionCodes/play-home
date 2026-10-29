package com.matthew.playhome

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The shared look: PlayStation blue-black in Dark mode, pale blue-white in Light (like the PS4's
 * light themes). Every colour comes from here, read when a view is built, so switching [dark]
 * means rebuilding the screen (Home recreates itself).
 */
object Look {
    var dark = true

    val blue = Color.rgb(0, 112, 209) // PlayStation blue (#0070D1)

    /** The background tone: shades over the wallpaper fade towards it. */
    val ink get() = if (dark) Color.rgb(3, 6, 15) else Color.rgb(236, 241, 248)
    val text get() = if (dark) Color.WHITE else Color.rgb(12, 20, 38)
    val dim get() = if (dark) Color.argb(178, 255, 255, 255) else Color.argb(170, 12, 20, 38)
    val faint get() = if (dark) Color.argb(115, 255, 255, 255) else Color.argb(110, 12, 20, 38)
    val shadow get() = if (dark) Color.argb(150, 0, 0, 0) else Color.TRANSPARENT
    /** Accents: "On Home", the volume bar while adjusting. */
    val accent get() = if (dark) Color.rgb(90, 169, 255) else blue

    /** Focus: solid white in Dark, solid PlayStation blue in Light, with [onFocus] text on it. */
    val focusFill get() = if (dark) Color.WHITE else blue
    val onFocus get() = if (dark) Color.rgb(3, 6, 15) else Color.WHITE
    val ring get() = if (dark) Color.WHITE else blue
    val glow get() = if (dark) Color.argb(170, 120, 180, 255) else Color.argb(120, 0, 112, 209)

    /** Translucent buttons at rest. */
    val chip get() = if (dark) Color.argb(38, 255, 255, 255) else Color.argb(22, 12, 20, 38)
    val chipStrong get() = if (dark) Color.argb(64, 255, 255, 255) else Color.argb(36, 12, 20, 38)
    val chipStroke get() = if (dark) Color.argb(70, 255, 255, 255) else Color.argb(60, 12, 20, 38)
    val edge get() = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(40, 12, 20, 38)

    /** Solid panels: cards, menus, toasts. */
    val surface get() = if (dark) Color.rgb(22, 30, 50) else Color.WHITE
    val surfaceFocused get() = if (dark) Color.rgb(44, 56, 84) else Color.rgb(222, 234, 250)
    /** Behind the Control Center / menus, and the Library's backdrop. */
    val scrim get() = withAlpha(ink, if (dark) 175 else 150)
    val overlay get() = withAlpha(ink, 240)

    fun withAlpha(color: Int, alpha: Int) = (alpha shl 24) or (color and 0xFFFFFF)

    val light: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    /** Quick out, soft landing: PS5 motion is snappy, never floaty. */
    val ease = PathInterpolator(0.2f, 0f, 0f, 1f)
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Float) = v * resources.displayMetrics.density
fun View.dp(v: Int) = context.dp(v)
fun View.dpf(v: Float) = context.dpf(v)

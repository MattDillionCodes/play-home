package com.matthew.playhome

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

/** A TV-launchable app: its banner (16:9, the hero art) and icon (the square tile). */
class App(val info: ActivityInfo, val label: String, val banner: Drawable?, val icon: Drawable, val isGame: Boolean) {
    val pkg: String get() = info.packageName
}

object Apps {
    /** System entries kept off Home and the Library; Settings lives in the Control Center. */
    private val hidden = setOf(
        "com.android.tv.settings", // Settings
        "com.tcl.suspension",      // TCL quick panel
        "com.tcl.dashboard",       // TCL HOME
        "com.tcl.tv",              // TV (inputs are cards in the Control Center)
    )

    /** Every app with a TV launcher entry, A to Z. Games are the ones that declare themselves games. */
    fun load(context: Context): List<App> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != context.packageName && it.activityInfo.packageName !in hidden }
            .map { ri ->
                val ai = ri.activityInfo
                val appInfo = ai.applicationInfo
                @Suppress("DEPRECATION")
                val game = appInfo.category == ApplicationInfo.CATEGORY_GAME ||
                    appInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0
                App(ai, ri.loadLabel(pm).toString(), ai.loadBanner(pm) ?: appInfo.loadBanner(pm), ai.loadIcon(pm), game)
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * Which TV apps are installed and when each was last updated. Cheap next to [load] (no labels,
     * icons or banners), so Home can check it every time it comes back and reload only on a change.
     */
    fun signature(context: Context): String {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .sorted()
            .joinToString(",") { pkg -> pkg + "@" + runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L) }
    }
}

/** What Home remembers: when it last opened each app, which apps are pinned, and the sound setting. */
class History(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.getSharedPreferences("play", Context.MODE_PRIVATE)

    /** Package → when Home last launched it (ms since epoch). Saved as "pkg=ms,pkg=ms". */
    fun lastOpened(): Map<String, Long> =
        prefs.getString("opened", "").orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=')
            if (parts.size != 2) null else parts[1].toLongOrNull()?.let { parts[0] to it }
        }.toMap()

    fun markOpened(pkg: String) {
        val all = lastOpened() + (pkg to System.currentTimeMillis())
        prefs.edit().putString("opened", all.entries.joinToString(",") { "${it.key}=${it.value}" }).apply()
    }

    /** Pinned packages, oldest pin first. Uninstalled ones stay saved in case they come back. */
    fun pinned(): List<String> = prefs.getString("pinned", "").orEmpty().split(',').filter { it.isNotBlank() }

    /** Pins the app, or unpins it if it was pinned; returns whether it's pinned now. */
    fun togglePin(pkg: String): Boolean {
        val pins = pinned().toMutableList()
        val nowPinned = !pins.remove(pkg)
        if (nowPinned) pins.add(pkg)
        prefs.edit().putString("pinned", pins.joinToString(",")).apply()
        return nowPinned
    }

    /** Custom row order (Settings → Arrange apps): on, or the automatic pins-then-recent order. */
    var customOrder: Boolean
        get() = prefs.getBoolean("custom", false)
        set(v) = prefs.edit().putBoolean("custom", v).apply()

    /** The arranged order, kept even while the row is back on automatic, for switching back. */
    fun savedOrder(): List<String> = prefs.getString("order", "").orEmpty().split(',').filter { it.isNotBlank() }

    fun saveOrder(pkgs: List<String>) = prefs.edit().putString("order", pkgs.joinToString(",")).apply()

    /** What [homeRow] should follow: the arranged order when it's on, else null (automatic). */
    fun rowOrder(): List<String>? = if (customOrder) savedOrder() else null

    /** In a custom order, Hold OK → this instead of pinning: the app goes to the front. */
    fun moveToFront(pkg: String) = saveOrder(listOf(pkg) + savedOrder().filter { it != pkg })

    var sounds: Boolean
        get() = prefs.getBoolean("sounds", true)
        set(v) = prefs.edit().putBoolean("sounds", v).apply()

    var wallpaper: Wallpaper
        get() = Wallpaper.named(appContext, prefs.getString("wallpaper", null))
        set(v) = prefs.edit().putString("wallpaper", v.id).apply()

    var dark: Boolean
        get() = prefs.getBoolean("dark", true)
        set(v) = prefs.edit().putBoolean("dark", v).apply()
}

/** Apps on the Home row before the Library tile. */
const val ROW_MAX = 10

/**
 * Orders the apps for the Home row. The caller keeps the first ten and puts the Library tile
 * after them, so with more than ten apps this decides which ones are one press away.
 * [lastOpened] maps package → when Home last launched it; [pinned] is the packages pinned from
 * the Library (Hold OK), oldest first. [apps] arrives A to Z.
 *
 * Pins first, in the order they were pinned, so favourites never move; then the
 * rest most recent first, like the PS4/PS5 row; apps never opened from Home last, A to Z (the
 * sort is stable, so they keep the order they arrived in).
 *
 * With a [custom] order (Arrange apps) that's followed exactly instead; apps it doesn't know yet
 * (new installs) go at the end, A to Z.
 */
fun homeRow(apps: List<App>, lastOpened: Map<String, Long>, pinned: List<String>, custom: List<String>? = null): List<App> {
    if (custom != null) {
        val rank = custom.withIndex().associate { it.value to it.index }
        return apps.sortedBy { rank[it.pkg] ?: Int.MAX_VALUE }
    }
    val (pins, rest) = apps.partition { it.pkg in pinned }
    return pins.sortedBy { pinned.indexOf(it.pkg) } + rest.sortedByDescending { lastOpened[it.pkg] ?: 0L }
}

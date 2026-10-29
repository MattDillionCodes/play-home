package com.matthew.playhome

import android.content.pm.ActivityInfo
import android.graphics.drawable.ColorDrawable
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeRowTest {
    private fun app(pkg: String) = App(ActivityInfo().apply { packageName = pkg }, pkg, null, ColorDrawable(), false)

    // As loaded: A to Z.
    private val apps = listOf("a", "b", "c", "d", "e").map(::app)

    private fun order(lastOpened: Map<String, Long> = emptyMap(), pinned: List<String> = emptyList(), custom: List<String>? = null) =
        homeRow(apps, lastOpened, pinned, custom).map { it.pkg }

    @Test fun neverOpenedStaysAtoZ() = assertEquals(listOf("a", "b", "c", "d", "e"), order())

    @Test fun mostRecentFirstThenNeverOpenedAtoZ() =
        assertEquals(listOf("d", "b", "a", "c", "e"), order(lastOpened = mapOf("b" to 100L, "d" to 200L)))

    @Test fun pinsFirstInPinOrder() =
        assertEquals(listOf("e", "c", "d", "a", "b"), order(lastOpened = mapOf("d" to 200L), pinned = listOf("e", "c")))

    @Test fun uninstalledPinsAreIgnored() = assertEquals(listOf("c", "a", "b", "d", "e"), order(pinned = listOf("gone", "c")))

    @Test fun customOrderIsFollowedExactly() =
        assertEquals(listOf("c", "a", "e", "b", "d"),
            order(lastOpened = mapOf("d" to 999L), pinned = listOf("d"), custom = listOf("c", "a", "e", "b", "d")))

    @Test fun appsMissingFromCustomOrderGoLastAtoZ() =
        assertEquals(listOf("e", "b", "a", "c", "d"), order(custom = listOf("e", "b", "uninstalled")))
}

class WeatherPlaceTest {
    @Test fun cityFromTimeZone() {
        assertEquals("Chicago", Weather.timeZoneCity("America/Chicago"))
        assertEquals("Los Angeles", Weather.timeZoneCity("America/Los_Angeles"))
        assertEquals("Buenos Aires", Weather.timeZoneCity("America/Argentina/Buenos_Aires"))
        assertEquals("London", Weather.timeZoneCity("Europe/London"))
    }

    @Test fun noCityNoGuess() {
        assertEquals(null, Weather.timeZoneCity("UTC"))
        assertEquals(null, Weather.timeZoneCity("Etc/GMT+5"))
        assertEquals(null, Weather.timeZoneCity("GMT"))
    }

    @Test fun units() {
        assertEquals(true, Weather.fahrenheit("US"))
        assertEquals(false, Weather.fahrenheit("GB"))
        assertEquals(false, Weather.fahrenheit("CA"))
    }
}

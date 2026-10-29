package com.matthew.playhome

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Network work on its own thread, so a slow request never holds up a wallpaper decode. */
object Net {
    val io = Executors.newSingleThreadExecutor()

    fun json(url: String): JSONObject = (URL(url).openConnection() as HttpURLConnection).run {
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("User-Agent", "PlayHome")
        try { JSONObject(String(inputStream.use { it.readBytes() })) } finally { disconnect() }
    }
}

/**
 * Current conditions and today's high / low from Open-Meteo (free, no API key), for the city in
 * the TV's time zone. Temperatures are in [unit] (°F in the US, °C elsewhere).
 */
class Weather(val place: String, val temp: Int, val high: Int, val low: Int, val unit: String, val code: Int, val isDay: Boolean) {
    /** WMO weather code → emoji. */
    val symbol: String
        get() = when (code) {
            0 -> if (isDay) "☀️" else "🌙"
            1, 2 -> if (isDay) "🌤️" else "☁️"
            3 -> "☁️"
            45, 48 -> "🌫️"
            in 51..57 -> "🌦️"
            in 61..67, in 80..82 -> "🌧️"
            in 71..77, 85, 86 -> "🌨️"
            in 95..99 -> "⛈️"
            else -> "🌡️"
        }

    /** WMO weather code → a word or two. */
    val condition: String
        get() = when (code) {
            0 -> if (isDay) "Sunny" else "Clear"
            1 -> "Mostly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Fog"
            in 51..57 -> "Drizzle"
            in 61..67, in 80..82 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 95..99 -> "Thunderstorms"
            else -> "—"
        }

    companion object {
        /** Last forecast and when it arrived (elapsedRealtime), kept across Home being rebuilt. */
        @Volatile var latest: Weather? = null
        @Volatile var fetchedAt = 0L

        private const val FORECAST = "https://api.open-meteo.com/v1/forecast?current=temperature_2m,weather_code,is_day" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"
        private const val GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="

        /** Coordinates of the time zone's city, looked up once (they don't move). */
        @Volatile private var located: Triple<String, Double, Double>? = null

        /**
         * The city in the TV's time zone: "America/Chicago" → "Chicago", "America/Argentina/
         * Buenos_Aires" → "Buenos Aires". Null for zones without one ("UTC", "Etc/GMT+5"): then
         * there's simply no weather, rather than a guess. No location permission involved.
         */
        fun timeZoneCity(zone: String = TimeZone.getDefault().id): String? {
            if ('/' !in zone || zone.startsWith("Etc/")) return null
            return zone.substringAfterLast('/').replace('_', ' ').takeIf { it.isNotBlank() }
        }

        /** °F where people use it (the US, Liberia, Myanmar), °C everywhere else. */
        fun fahrenheit(country: String = Locale.getDefault().country) = country in setOf("US", "LR", "MM")

        /** Null when the time zone has no city or the city can't be found. Network: call off the UI thread. */
        fun fetch(): Weather? {
            val city = timeZoneCity() ?: return null
            val (place, lat, lon) = located?.takeIf { it.first == city } ?: run {
                val hit = Net.json(GEOCODE + URLEncoder.encode(city, "UTF-8")).optJSONArray("results")?.optJSONObject(0)
                    ?: return null
                Triple(city, hit.getDouble("latitude"), hit.getDouble("longitude")).also { located = it }
            }
            val f = fahrenheit()
            val root = Net.json("$FORECAST&latitude=$lat&longitude=$lon" + if (f) "&temperature_unit=fahrenheit" else "")
            val now = root.getJSONObject("current")
            val day = root.getJSONObject("daily")
            return Weather(
                place = place,
                temp = now.getDouble("temperature_2m").roundToInt(),
                high = day.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
                low = day.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
                unit = if (f) "°F" else "°C",
                code = now.getInt("weather_code"),
                isDay = now.getInt("is_day") == 1,
            )
        }
    }
}

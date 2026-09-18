package dev.aoframe.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Direct-to-OpenMeteo (public API, no key needed) client for the weather
 * widget. Called directly from the app rather than through any backend -
 * one less moving part, and OpenMeteo needs no auth either way.
 *
 * Sea surface temperature comes from Open-Meteo's separate Marine
 * Weather API (a different host/model than the land forecast above,
 * https://open-meteo.com/en/docs/marine-weather-api) - fetched as a
 * second request, independent failure: a marine outage, a grid point
 * with no coverage, or the sea coordinates simply not being configured
 * just leaves seaSurfaceTempC null, never blocks the land forecast.
 *
 * Coordinates come from config (immich-secrets.json's weatherLatitude/
 * weatherLongitude, optionally weatherSeaLatitude/weatherSeaLongitude for
 * a distinct sea-surface-temperature point) rather than hardcoded, since
 * every user of this app has a different location.
 */
class WeatherClient(
    private val latitude: Double,
    private val longitude: Double,
    private val seaLatitude: Double? = null,
    private val seaLongitude: Double? = null,
    private val baseUrl: String = "https://api.open-meteo.com",
    private val marineBaseUrl: String = "https://marine-api.open-meteo.com"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): WeatherSnapshot = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v1/forecast" +
            "?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,apparent_temperature,precipitation_probability,weather_code" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code" +
            "&timezone=auto&forecast_days=3"
        val request = Request.Builder().url(url).get().build()

        val snapshot = http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException("OpenMeteo API ${response.code}: $body")
            }
            parse(JSONObject(body))
        }
        snapshot.copy(seaSurfaceTempC = fetchSeaSurfaceTemp())
    }

    private fun fetchSeaSurfaceTemp(): Double? {
        if (seaLatitude == null || seaLongitude == null) return null
        return try {
            val url = "$marineBaseUrl/v1/marine" +
                "?latitude=$seaLatitude&longitude=$seaLongitude" +
                "&current=sea_surface_temperature&timezone=auto"
            val request = Request.Builder().url(url).get().build()

            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("OpenMeteo Marine API ${response.code}: $body")
                }
                JSONObject(body).getJSONObject("current")
                    .optDouble("sea_surface_temperature")
                    .takeUnless { it.isNaN() }
            }
        } catch (error: Exception) {
            null
        }
    }

    private fun parse(json: JSONObject): WeatherSnapshot {
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val maxTemps = daily.getJSONArray("temperature_2m_max")
        val minTemps = daily.getJSONArray("temperature_2m_min")
        val precipMax = daily.getJSONArray("precipitation_probability_max")
        val dailyCodes = daily.getJSONArray("weather_code")

        val dailyForecasts = (0 until maxTemps.length()).map { i ->
            DailyForecast(
                maxTempC = maxTemps.getDouble(i),
                minTempC = minTemps.getDouble(i),
                precipitationProbabilityMax = precipMax.optInt(i, 0),
                weatherCode = dailyCodes.optInt(i, -1)
            )
        }

        return WeatherSnapshot(
            currentTempC = current.getDouble("temperature_2m"),
            feelsLikeTempC = current.getDouble("apparent_temperature"),
            precipitationProbability = current.optInt("precipitation_probability", 0),
            weatherCode = current.optInt("weather_code", -1),
            daily = dailyForecasts
        )
    }
}

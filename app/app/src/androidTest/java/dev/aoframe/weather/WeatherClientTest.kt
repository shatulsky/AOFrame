package dev.aoframe.weather

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Fast, deterministic test for WeatherClient's JSON parsing, using
 * MockWebServer instead of the real OpenMeteo API - androidTest, not a
 * plain JVM test, for the same org.json-needs-a-real-Android-runtime
 * reason as ImmichClientTest (see that file's header comment).
 */
class WeatherClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: WeatherClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Same mock server for both - fetch() tells the two calls apart
        // by path (/v1/forecast vs /v1/marine), not by host.
        val url = server.url("/").toString().removeSuffix("/")
        client = WeatherClient(
            latitude = 50.45,
            longitude = 30.52,
            seaLatitude = 46.48,
            seaLongitude = 30.72,
            baseUrl = url,
            marineBaseUrl = url
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun parsesCurrentAndDailyForecast() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "current": {"temperature_2m": 24.3, "apparent_temperature": 26.1, "precipitation_probability": 10, "weather_code": 2},
                    "daily": {
                        "temperature_2m_max": [28.0, 27.5, 25.0],
                        "temperature_2m_min": [18.0, 17.5, 16.0],
                        "precipitation_probability_max": [5, 20, 40],
                        "weather_code": [0, 3, 61]
                    }
                }
                """.trimIndent()
            )
        )
        server.enqueue(
            MockResponse().setBody(
                """{"current": {"sea_surface_temperature": 22.4}}"""
            )
        )

        val snapshot = client.fetch()

        assertEquals(24.3, snapshot.currentTempC, 0.001)
        assertEquals(26.1, snapshot.feelsLikeTempC, 0.001)
        assertEquals(10, snapshot.precipitationProbability)
        assertEquals(2, snapshot.weatherCode)
        assertEquals(3, snapshot.daily.size)
        assertEquals(28.0, snapshot.daily[0].maxTempC, 0.001)
        assertEquals(16.0, snapshot.daily[2].minTempC, 0.001)
        assertEquals(40, snapshot.daily[2].precipitationProbabilityMax)
        assertEquals(0, snapshot.daily[0].weatherCode)
        assertEquals(61, snapshot.daily[2].weatherCode)
        assertEquals(22.4, snapshot.seaSurfaceTempC!!, 0.001)
    }

    @Test
    fun seaSurfaceTempIsNullWhenMarineRequestFails() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                    "current": {"temperature_2m": 24.3, "apparent_temperature": 26.1, "precipitation_probability": 10, "weather_code": 2},
                    "daily": {
                        "temperature_2m_max": [28.0],
                        "temperature_2m_min": [18.0],
                        "precipitation_probability_max": [5],
                        "weather_code": [0]
                    }
                }
                """.trimIndent()
            )
        )
        server.enqueue(MockResponse().setResponseCode(500))

        val snapshot = client.fetch()

        // The land forecast still comes through - a marine outage never
        // blocks it (same reasoning as startWeatherLoop()'s catch block
        // in MainActivity, one level up).
        assertEquals(24.3, snapshot.currentTempC, 0.001)
        assertEquals(null, snapshot.seaSurfaceTempC)
    }
}

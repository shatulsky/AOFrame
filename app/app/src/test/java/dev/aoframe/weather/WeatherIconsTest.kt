package dev.aoframe.weather

import dev.aoframe.R
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherIconsTest {
    @Test
    fun clearSkyIsSunDuringTheDay() {
        assertEquals(R.drawable.ic_weather_clear_day, WeatherIcons.iconFor(0, isDaytime = true))
    }

    @Test
    fun clearSkyIsMoonAtNight() {
        assertEquals(R.drawable.ic_weather_clear_night, WeatherIcons.iconFor(0, isDaytime = false))
    }

    @Test
    fun overcastHasNoDayNightVariant() {
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(3, isDaytime = true))
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(3, isDaytime = false))
    }

    @Test
    fun rainCodesAllMapToRain() {
        assertEquals(R.drawable.ic_weather_rain, WeatherIcons.iconFor(61, isDaytime = true))
        assertEquals(R.drawable.ic_weather_rain, WeatherIcons.iconFor(80, isDaytime = true))
    }

    @Test
    fun thunderstormWithHailUsesTheHailIcon() {
        assertEquals(R.drawable.ic_weather_thunderstorms_hail, WeatherIcons.iconFor(96, isDaytime = true))
    }

    @Test
    fun unknownCodeFallsBackToCloudy() {
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(-1, isDaytime = true))
    }
}

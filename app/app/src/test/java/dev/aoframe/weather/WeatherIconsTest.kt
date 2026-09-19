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
    fun mostlyClearHasDayAndNightVariants() {
        assertEquals(R.drawable.ic_weather_mostly_clear_day, WeatherIcons.iconFor(1, isDaytime = true))
        assertEquals(R.drawable.ic_weather_mostly_clear_night, WeatherIcons.iconFor(1, isDaytime = false))
    }

    @Test
    fun partlyCloudyHasDayAndNightVariants() {
        assertEquals(R.drawable.ic_weather_partly_cloudy_day, WeatherIcons.iconFor(2, isDaytime = true))
        assertEquals(R.drawable.ic_weather_partly_cloudy_night, WeatherIcons.iconFor(2, isDaytime = false))
    }

    @Test
    fun overcastHasNoDayNightVariant() {
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(3, isDaytime = true))
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(3, isDaytime = false))
    }

    @Test
    fun fogCodesHaveDayAndNightVariants() {
        assertEquals(R.drawable.ic_weather_fog_day, WeatherIcons.iconFor(45, isDaytime = true))
        assertEquals(R.drawable.ic_weather_fog_night, WeatherIcons.iconFor(48, isDaytime = false))
    }

    @Test
    fun drizzleCodesAllMapToDrizzle() {
        for (code in listOf(51, 53, 55, 56, 57)) {
            assertEquals(R.drawable.ic_weather_drizzle, WeatherIcons.iconFor(code, isDaytime = true))
        }
    }

    @Test
    fun rainCodesAllMapToRain() {
        for (code in listOf(61, 63, 65, 66, 67, 80, 81, 82)) {
            assertEquals(R.drawable.ic_weather_rain, WeatherIcons.iconFor(code, isDaytime = true))
        }
    }

    @Test
    fun snowCodesAllMapToSnow() {
        for (code in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals(R.drawable.ic_weather_snow, WeatherIcons.iconFor(code, isDaytime = true))
        }
    }

    @Test
    fun thunderstormWithoutHailUsesThePlainThunderstormIcon() {
        assertEquals(R.drawable.ic_weather_thunderstorms, WeatherIcons.iconFor(95, isDaytime = true))
    }

    @Test
    fun thunderstormWithHailUsesTheHailIcon() {
        assertEquals(R.drawable.ic_weather_thunderstorms_hail, WeatherIcons.iconFor(96, isDaytime = true))
        assertEquals(R.drawable.ic_weather_thunderstorms_hail, WeatherIcons.iconFor(99, isDaytime = true))
    }

    @Test
    fun unknownCodeFallsBackToCloudy() {
        assertEquals(R.drawable.ic_weather_cloudy, WeatherIcons.iconFor(-1, isDaytime = true))
    }
}

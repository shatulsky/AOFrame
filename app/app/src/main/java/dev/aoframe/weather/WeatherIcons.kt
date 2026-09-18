package dev.aoframe.weather

import androidx.annotation.DrawableRes
import dev.aoframe.R

/**
 * Open-Meteo's weather_code follows the WMO code table
 * (https://open-meteo.com/en/docs, "WMO Weather interpretation codes").
 * Icons are vector drawables converted from Meteocons
 * (https://github.com/basmilius/meteocons, MIT license, monochrome
 * style) - not emoji, for a consistent look across launchers/renderers.
 * Converted via svg2vectordrawable, with hand-added circle paths where
 * that tool silently drops SVG <circle> elements.
 */
object WeatherIcons {
    @DrawableRes
    fun iconFor(weatherCode: Int, isDaytime: Boolean): Int = when (weatherCode) {
        0 -> if (isDaytime) R.drawable.ic_weather_clear_day else R.drawable.ic_weather_clear_night
        1 -> if (isDaytime) R.drawable.ic_weather_mostly_clear_day else R.drawable.ic_weather_mostly_clear_night
        2 -> if (isDaytime) R.drawable.ic_weather_partly_cloudy_day else R.drawable.ic_weather_partly_cloudy_night
        3 -> R.drawable.ic_weather_cloudy
        45, 48 -> if (isDaytime) R.drawable.ic_weather_fog_day else R.drawable.ic_weather_fog_night
        51, 53, 55, 56, 57 -> R.drawable.ic_weather_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_weather_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_weather_snow
        95 -> R.drawable.ic_weather_thunderstorms
        96, 99 -> R.drawable.ic_weather_thunderstorms_hail
        else -> R.drawable.ic_weather_cloudy
    }
}

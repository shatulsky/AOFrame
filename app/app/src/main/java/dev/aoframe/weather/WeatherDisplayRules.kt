package dev.aoframe.weather

/** Pure display rules of the weather widget (no Android types). */
object WeatherDisplayRules {
    private const val FORECAST_DAYS = 3

    /** The current-weather icon uses the day variant from 06:00 to 19:59. */
    fun isDaytime(hour: Int): Boolean = hour in 6..19

    /**
     * The widget stays hidden until every forecast row can be filled.
     * A forecast with fewer days stays hidden rather than show empty rows.
     */
    fun shouldShowWidget(forecastDays: Int): Boolean = forecastDays >= FORECAST_DAYS

    fun visibleForecastDays(forecastDays: Int): Int = minOf(forecastDays, FORECAST_DAYS)
}

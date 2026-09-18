package dev.aoframe.weather

data class DailyForecast(
    val maxTempC: Double,
    val minTempC: Double,
    val precipitationProbabilityMax: Int,
    val weatherCode: Int
)

data class WeatherSnapshot(
    val currentTempC: Double,
    val feelsLikeTempC: Double,
    val precipitationProbability: Int,
    val weatherCode: Int,
    val daily: List<DailyForecast>,
    // Null when Open-Meteo's marine model has no sea-surface-temperature
    // grid point for these coordinates, or the marine request itself
    // fails - never blocks the land forecast from rendering.
    val seaSurfaceTempC: Double? = null
)

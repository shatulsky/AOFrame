package dev.aoframe.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherDisplayRulesTest {
    @Test
    fun dayBoundaries() {
        assertFalse(WeatherDisplayRules.isDaytime(5))
        assertTrue(WeatherDisplayRules.isDaytime(6))
        assertTrue(WeatherDisplayRules.isDaytime(19))
        assertFalse(WeatherDisplayRules.isDaytime(20))
        assertFalse(WeatherDisplayRules.isDaytime(0))
    }

    @Test
    fun widgetStaysHiddenUntilThreeForecastDays() {
        assertFalse(WeatherDisplayRules.shouldShowWidget(0))
        assertFalse(WeatherDisplayRules.shouldShowWidget(2))
        assertTrue(WeatherDisplayRules.shouldShowWidget(3))
        assertTrue(WeatherDisplayRules.shouldShowWidget(7))
    }

    @Test
    fun visibleDaysAreCappedAtThree() {
        assertEquals(0, WeatherDisplayRules.visibleForecastDays(0))
        assertEquals(2, WeatherDisplayRules.visibleForecastDays(2))
        assertEquals(3, WeatherDisplayRules.visibleForecastDays(7))
    }
}

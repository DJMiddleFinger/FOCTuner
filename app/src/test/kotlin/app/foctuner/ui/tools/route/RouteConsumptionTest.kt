package app.foctuner.ui.tools.route

import app.foctuner.prefs.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals

class RouteConsumptionTest {
    private val measured = RouteState(rideWhPerKm = 18.0, historyWhPerKm = 14.0)

    @Test
    fun measuredBeforeHistoryBeforeDefault() {
        assertEquals(18.0 to Consumption.Ride, consumption(AppPreferences(), measured))
        assertEquals(14.0 to Consumption.History, consumption(AppPreferences(), measured.copy(rideWhPerKm = null)))
        assertEquals(15.0 to Consumption.Default, consumption(AppPreferences(), RouteState()))
    }

    @Test
    fun enteredOnlyWhenOverrideIsOnAndFilledIn() {
        assertEquals(22.0 to Consumption.Entered, consumption(AppPreferences(routeOverride = true, routeWhPerKm = 22f), measured))
        assertEquals(18.0 to Consumption.Ride, consumption(AppPreferences(routeOverride = false, routeWhPerKm = 22f), measured))
        assertEquals(18.0 to Consumption.Ride, consumption(AppPreferences(routeOverride = true, routeWhPerKm = null), measured))
    }
}

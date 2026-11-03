package app.foctuner.logs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripsTest {
    private val start = 1_700_000_000_000L

    /** One controller riding at [speed] m/s for [seconds], a sample a second, continuing from [from]. */
    private fun ride(tracker: TripTracker, from: TripSample, speed: Float, seconds: Int): TripSample {
        var s = from
        repeat(seconds) {
            s = s.copy(
                timeMs = s.timeMs + 1000,
                speed = speed,
                distance = s.distance + abs(speed),
                wattHours = s.wattHours + if (speed > 0) 0.2f else 0f,
                wattHoursCharged = s.wattHoursCharged + if (speed < 0) 0.1f else 0f,
                uptimeMs = s.uptimeMs + 1000,
            )
            tracker.accept(s)
        }
        return s
    }

    private fun abs(v: Float) = kotlin.math.abs(v)

    private val idle = TripSample(start, 0f, 100f, 5f, 1f, 60_000, 12_000_000)

    @Test
    fun aRideBetweenTwoStopsIsOneTripWithItsDistanceEnergyAndTopSpeed() {
        val tracker = TripTracker()
        tracker.accept(idle)
        var s = ride(tracker, idle, 5f, 60)
        s = ride(tracker, s, 8f, 30)
        ride(tracker, s, 0f, 130)

        val trip = tracker.ledger.trips.single()
        assertEquals(60 * 5 + 30 * 8.0, trip.distance, 1e-6)
        assertEquals(90 * 0.2, trip.whUsed, 1e-4)
        assertEquals(8.0, trip.maxSpeed)
        assertEquals(90_000L, trip.durationMs)
        assertEquals(90_000L, trip.movingMs)
        assertEquals(start + 1000 - 1000, trip.startMs - 0)
        assertNull(tracker.current)
    }

    @Test
    fun aShortStopDoesNotSplitATripButARegenSpellCountsAsRegenerated() {
        val tracker = TripTracker()
        tracker.accept(idle)
        var s = ride(tracker, idle, 6f, 40)
        s = ride(tracker, s, 0f, 60)
        s = ride(tracker, s, 6f, 40)
        s = ride(tracker, s, -2f, 10)
        ride(tracker, s, 0f, 125)

        val trip = tracker.ledger.trips.single()
        assertEquals(40 * 6 + 40 * 6 + 10 * 2.0, trip.distance, 1e-6)
        assertEquals(1.0, trip.whRegen, 1e-4)
    }

    @Test
    fun pushingTheVehicleABitOrCreepingIsNotATrip() {
        val tracker = TripTracker()
        tracker.accept(idle)
        var s = ride(tracker, idle, 1.5f, 2)
        s = ride(tracker, s, 0f, 200)
        s = ride(tracker, s, 3f, 8)
        ride(tracker, s, 0f, 200)

        assertTrue(tracker.ledger.trips.isEmpty())
        assertEquals(12_000_000 + 2 * 1.5 + 8 * 3.0, tracker.ledger.lifetime.distance, 1e-6)
    }

    @Test
    fun theTripInProgressIsVisibleAndFinishingKeepsIt() {
        val tracker = TripTracker()
        tracker.accept(idle)
        ride(tracker, idle, 5f, 60)

        assertEquals(5.0, assertNotNull(tracker.current).maxSpeed)
        assertTrue(tracker.ledger.trips.isEmpty())

        val ledger = tracker.finish()

        assertEquals(1, ledger.trips.size)
        assertNull(tracker.current)
    }

    @Test
    fun aGapInTheSamplesEndsTheTripWhereTheMotionStoppedAndARestartAddsNothing() {
        val tracker = TripTracker()
        tracker.accept(idle)
        val last = ride(tracker, idle, 5f, 60)

        // The app was away for ten minutes and the controller restarted meanwhile: its counters are lower.
        tracker.accept(last.copy(timeMs = last.timeMs + 600_000, speed = 0f, distance = 10f, wattHours = 0f, wattHoursCharged = 0f, uptimeMs = 5_000))

        val trip = tracker.ledger.trips.single()
        assertEquals(60 * 5.0, trip.distance, 1e-6)
        assertEquals(last.timeMs, trip.endMs)
        assertEquals(12_000_000 + 300.0, tracker.ledger.lifetime.distance, 1e-6)
    }

    @Test
    fun theLifetimeStartsAtTheControllersOdometerOnceAndKeepsGrowingFromIt() {
        val tracker = TripTracker()
        tracker.accept(idle)
        assertEquals(12_000_000.0, tracker.ledger.lifetime.distance)

        val again = TripTracker(ledger = tracker.ledger)
        again.accept(idle.copy(odometer = 5))
        assertEquals(12_000_000.0, again.ledger.lifetime.distance)
    }

    @Test
    fun theLedgerSurvivesBeingWrittenAndRead() {
        val tracker = TripTracker()
        tracker.accept(idle)
        ride(tracker, ride(tracker, idle, 4f, 60), 0f, 130)
        val ledger = tracker.ledger

        assertEquals(ledger, TripLedgerCodec.decode(TripLedgerCodec.encode(ledger)))
        assertNull(TripLedgerCodec.decode("not a ledger"))
        assertEquals(ledger.lifetime, TripLedgerCodec.decode(TripLedgerCodec.encode(ledger) + "trip;broken\nsomething else\n")?.lifetime)
    }
}

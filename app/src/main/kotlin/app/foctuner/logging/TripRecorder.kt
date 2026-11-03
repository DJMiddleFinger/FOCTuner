package app.foctuner.logging

import android.content.Context
import app.foctuner.logs.TripLedger
import app.foctuner.logs.TripLedgerCodec
import app.foctuner.logs.TripProgress
import app.foctuner.logs.TripSample
import app.foctuner.logs.TripTracker
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.VescException
import app.foctuner.vesc.setupValues
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The trip history and lifetime totals of the connected controller, as the Trips tab shows them. */
data class TripsState(
    val uuid: String? = null,
    val ledger: TripLedger = TripLedger(),
    val current: TripProgress? = null,
    /** The controller's own odometer in metres, as of the last sample. */
    val controllerOdometer: Long? = null,
)

/** One text file per controller UUID in the app's private storage, so the history outlives firmware updates and reflashes. */
class TripStore(context: Context) {
    private val dir = File(context.filesDir, "trips").also { it.mkdirs() }

    fun load(uuid: String): TripLedger = try {
        file(uuid).takeIf { it.isFile }?.readText()?.let(TripLedgerCodec::decode) ?: TripLedger()
    } catch (e: IOException) {
        TripLedger()
    }

    fun save(uuid: String, ledger: TripLedger) {
        try {
            file(uuid).writeText(TripLedgerCodec.encode(ledger))
        } catch (e: IOException) {
            // Storage is full or gone; the next save tries again.
        }
    }

    private fun file(uuid: String) = File(dir, uuid.filter { it.isLetterOrDigit() } + ".txt")
}

object TripHub {
    private val _state = MutableStateFlow(TripsState())
    val state: StateFlow<TripsState> = _state.asStateFlow()

    @Volatile
    internal var recorder: TripRecorder? = null

    internal fun publish(state: TripsState) {
        _state.value = state
    }

    /** Makes the controller's odometer the app's lifetime distance. */
    fun adoptControllerOdometer() = recorder?.adopt()
}

/**
 * Watches the vehicle for as long as the connection lasts: once a second the vehicle-wide values go to a
 * [TripTracker], which finds trips by motion and keeps the lifetime totals. The ledger is saved every
 * [SAVE_EVERY_MS] while it changes, when a trip ends and when the connection does. Idle while [paused] (a firmware upload).
 */
class TripRecorder(
    private val client: VescClient,
    private val uuid: String,
    private val store: TripStore,
    private val paused: Flow<Boolean>,
) {
    private val tracker = TripTracker(ledger = store.load(uuid))
    private val adoptPending = AtomicBoolean(false)
    private var odometer: Long? = null

    fun adopt() {
        adoptPending.set(true)
    }

    suspend fun run() {
        TripHub.recorder = this
        publish()
        var saved = tracker.ledger
        var savedAt = System.currentTimeMillis()
        try {
            while (client.connected.value) {
                if (!paused.first()) {
                    try {
                        val v = client.setupValues()
                        odometer = v.odometer
                        if (adoptPending.getAndSet(false)) tracker.adoptOdometer(v.odometer)
                        val ledger = tracker.accept(TripSample(System.currentTimeMillis(), v.speed, v.distanceAbs, v.wattHours, v.wattHoursCharged, v.uptimeMs, v.odometer))
                        publish()
                        val now = System.currentTimeMillis()
                        if (ledger.trips.size != saved.trips.size || (ledger != saved && now - savedAt >= SAVE_EVERY_MS)) {
                            store.save(uuid, ledger)
                            saved = ledger
                            savedAt = now
                        }
                    } catch (e: VescException) {
                        // A missed sample is one second of a trip; the tracker treats a long silence as a gap.
                    }
                }
                delay(SAMPLE_MS)
            }
        } finally {
            withContext(NonCancellable) {
                store.save(uuid, tracker.finish())
                TripHub.publish(TripsState(uuid, tracker.ledger, null, odometer))
                if (TripHub.recorder === this@TripRecorder) TripHub.recorder = null
            }
        }
    }

    private fun publish() = TripHub.publish(TripsState(uuid, tracker.ledger, tracker.current, odometer))

    private companion object {
        const val SAMPLE_MS = 1_000L
        const val SAVE_EVERY_MS = 30_000L
    }
}

package app.foctuner.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold

/**
 * Turns a stream of sightings (advertisements and beacons repeat, devices come and go) into the list of
 * devices currently around: one entry per [LinkDevice.key] with its latest data, dropped once it has not
 * been seen for [ttlMillis]. Order of first appearance is kept. Emits on every change, RSSI included.
 */
fun Flow<LinkDevice>.visibleDevices(
    ttlMillis: Long = 6_000,
    now: () -> Long = System::currentTimeMillis,
): Flow<List<LinkDevice>> {
    val clockTicks = flow {
        while (true) {
            delay(ttlMillis / 2)
            emit(null)
        }
    }
    return merge(map<LinkDevice, LinkDevice?> { it }, clockTicks)
        .runningFold(emptyMap<String, Pair<LinkDevice, Long>>()) { seen, sighting ->
            val t = now()
            val current = seen.filterValues { (_, lastSeen) -> t - lastSeen < ttlMillis }
            if (sighting == null) current else current + (sighting.key to (sighting to t))
        }
        .map { seen -> seen.values.map { it.first } }
        .distinctUntilChanged()
}

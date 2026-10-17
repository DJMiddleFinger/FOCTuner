package app.foctuner.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.foctuner.transport.LinkDevice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

/**
 * [AppPreferences], the recent-devices list and the last CAN selection per controller, in one DataStore file.
 * Instances are cheap: they all share the application's store.
 */
class PreferencesStore(context: Context) {
    private val store = context.applicationContext.dataStore

    private val data: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val preferences: Flow<AppPreferences> = data.map(::read)

    val recentDevices: Flow<List<RecentDevice>> = data.map { RecentDevices.decode(it[RECENT].orEmpty()) }

    suspend fun current(): AppPreferences = preferences.first()

    suspend fun update(change: (AppPreferences) -> AppPreferences) {
        store.edit { write(it, change(read(it))) }
    }

    suspend fun connected(device: LinkDevice, at: Long) = editRecent { RecentDevices.connected(it, device, at) }

    suspend fun togglePin(key: String) = editRecent { RecentDevices.togglePin(it, key) }

    suspend fun forget(key: String) = editRecent { RecentDevices.forget(it, key) }

    suspend fun canTarget(localUuid: String): CanTarget? = CanTargets.decode(data.first()[LAST_CAN].orEmpty())[localUuid]

    /** Remembers [target] as selected behind [localUuid]; null when the attached controller itself is selected. */
    suspend fun setCanTarget(localUuid: String, target: CanTarget?) {
        store.edit { it[LAST_CAN] = CanTargets.encode(CanTargets.with(CanTargets.decode(it[LAST_CAN].orEmpty()), localUuid, target)) }
    }

    private suspend fun editRecent(change: (List<RecentDevice>) -> List<RecentDevice>) {
        store.edit { it[RECENT] = RecentDevices.encode(change(RecentDevices.decode(it[RECENT].orEmpty()))) }
    }

    private fun read(p: Preferences): AppPreferences {
        val defaults = AppPreferences()
        return AppPreferences(
            units = p[UNITS]?.let { name -> Units.entries.firstOrNull { it.name == name } } ?: defaults.units,
            stopSeconds = p[STOP_SECONDS] ?: defaults.stopSeconds,
            realtimeHz = p[REALTIME_HZ] ?: defaults.realtimeHz,
            replMaxLines = p[REPL_MAX_LINES] ?: defaults.replMaxLines,
            keepScreenOn = p[KEEP_SCREEN_ON] ?: defaults.keepScreenOn,
            scanCanOnConnect = p[SCAN_CAN] ?: defaults.scanCanOnConnect,
            reconnectLastCan = p[RECONNECT_CAN] ?: defaults.reconnectLastCan,
            introAccepted = p[INTRO_ACCEPTED] ?: defaults.introAccepted,
        )
    }

    private fun write(p: MutablePreferences, prefs: AppPreferences) {
        p[UNITS] = prefs.units.name
        p[STOP_SECONDS] = prefs.stopSeconds
        p[REALTIME_HZ] = prefs.realtimeHz
        p[REPL_MAX_LINES] = prefs.replMaxLines
        p[KEEP_SCREEN_ON] = prefs.keepScreenOn
        p[SCAN_CAN] = prefs.scanCanOnConnect
        p[RECONNECT_CAN] = prefs.reconnectLastCan
        p[INTRO_ACCEPTED] = prefs.introAccepted
    }

    private companion object {
        val UNITS = stringPreferencesKey("units")
        val STOP_SECONDS = intPreferencesKey("stop_seconds")
        val REALTIME_HZ = intPreferencesKey("realtime_hz")
        val REPL_MAX_LINES = intPreferencesKey("repl_max_lines")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SCAN_CAN = booleanPreferencesKey("scan_can_on_connect")
        val RECONNECT_CAN = booleanPreferencesKey("reconnect_last_can")
        val INTRO_ACCEPTED = intPreferencesKey("intro_accepted")
        val RECENT = stringPreferencesKey("recent_devices")
        val LAST_CAN = stringPreferencesKey("last_can")
    }
}

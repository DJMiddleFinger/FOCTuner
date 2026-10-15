package app.foctuner.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.foctuner.R
import app.foctuner.transport.Transport
import app.foctuner.ui.controllers.CanDevice
import app.foctuner.ui.controllers.ControllersState
import app.foctuner.ui.overview.OverviewState
import app.foctuner.ui.settings.SettingsModel
import app.foctuner.ui.shell.SessionHeaderState
import app.foctuner.ui.terminal.LineKind
import app.foctuner.ui.terminal.TerminalLine
import app.foctuner.ui.terminal.TerminalState
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.VescClient
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescTimeoutException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/** Attempt in progress or the reason the last one failed, merged into the connect screen's state. */
data class ConnectAttempt(val key: String? = null, val failure: String? = null)

/**
 * The open connection and everything the connected screens show. Lives as long as the activity; each
 * connection gets its own child scope, so disconnecting cancels polling, terminal and CAN work at once.
 */
class SessionViewModel(application: Application) : AndroidViewModel(application) {
    private class Active(
        val client: VescClient,
        val scope: CoroutineScope,
        val info: FirmwareInfo,
        val name: String,
        val linkLabel: String,
    )

    private val session = MutableStateFlow<Active?>(null)
    private val selectedCanId = MutableStateFlow<Int?>(null)

    private val _attempt = MutableStateFlow(ConnectAttempt())
    val attempt: StateFlow<ConnectAttempt> = _attempt.asStateFlow()

    private val _overview = MutableStateFlow<OverviewState?>(null)
    val overview: StateFlow<OverviewState?> = _overview.asStateFlow()

    private val _terminal = MutableStateFlow(TerminalState())
    val terminal: StateFlow<TerminalState> = _terminal.asStateFlow()

    private val _controllers = MutableStateFlow(ControllersState())
    val controllers: StateFlow<ControllersState> = _controllers.asStateFlow()

    val header: StateFlow<SessionHeaderState?> = combine(session, _overview, selectedCanId) { active, overview, target ->
        active?.let {
            SessionHeaderState(
                deviceName = it.name,
                hardwareName = it.info.hardwareName,
                firmware = it.info.version,
                linkLabel = it.linkLabel,
                live = overview != null && overview.staleSeconds == 0,
                targetCanId = target,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The selected controller's motor and app settings. */
    val settings = SettingsModel(
        viewModelScope,
        controller = { session.value?.let(::target) },
        firmware = { session.value?.info?.version },
        string = { id, args -> string(id, *args) },
    )

    private var nextLineId = 0L

    /** Opens the link, checks a controller answers, then makes it the current session. */
    fun connect(key: String, name: String, linkLabel: String, demo: Boolean = false, open: suspend () -> Transport) {
        if (_attempt.value.key != null) return
        _attempt.value = ConnectAttempt(key)
        viewModelScope.launch {
            val scope = CoroutineScope(viewModelScope.coroutineContext + SupervisorJob(viewModelScope.coroutineContext[Job]))
            try {
                val client = VescClient(open(), scope)
                val info = client.firmwareInfo()
                begin(Active(client, scope, info, name, linkLabel), demo)
                _attempt.value = ConnectAttempt()
            } catch (e: VescTimeoutException) {
                scope.cancel()
                _attempt.value = ConnectAttempt(failure = string(R.string.connect_failed_no_reply, name))
            } catch (e: IOException) {
                scope.cancel()
                _attempt.value = ConnectAttempt(failure = e.message ?: string(R.string.connect_failed_no_reply, name))
            } catch (e: VescException) {
                scope.cancel()
                _attempt.value = ConnectAttempt(failure = e.message ?: string(R.string.connect_failed_no_reply, name))
            }
        }
    }

    fun dismissFailure() {
        _attempt.update { it.copy(failure = null) }
    }

    fun disconnect() {
        val active = session.value ?: return
        session.value = null
        settings.reset()
        viewModelScope.launch {
            active.client.close()
            active.scope.cancel()
        }
    }

    fun onTerminalInput(text: String) {
        _terminal.update { it.copy(input = text) }
    }

    fun sendCommand(command: String) {
        val active = session.value ?: return
        _terminal.update {
            it.copy(
                lines = it.lines + TerminalLine(nextLineId++, command, LineKind.Command),
                input = "",
                history = (listOf(command) + (it.history - command)).take(HISTORY_SIZE),
                waiting = true,
            )
        }
        active.scope.launch {
            val reply = try {
                target(active).terminal(command).trimEnd().lines().map { TerminalLine(nextLineId++, it, LineKind.Output) }
            } catch (e: VescException) {
                listOf(TerminalLine(nextLineId++, string(R.string.terminal_no_reply), LineKind.Error))
            }
            _terminal.update { it.copy(lines = (it.lines + reply).takeLast(MAX_LINES), waiting = false) }
        }
    }

    fun clearTerminal() {
        _terminal.update { it.copy(lines = emptyList()) }
    }

    fun scanCan() {
        val active = session.value ?: return
        if (_controllers.value.scanning) return
        _controllers.update { it.copy(scanning = true) }
        active.scope.launch {
            val localId = answerOrNull { active.client.values().controllerId } ?: 0
            val local = CanDevice(localId, local = true, active.info.hardwareName, active.info.version, active.info.firmwareName)
            val others = (answerOrNull { active.client.pingCan() } ?: emptyList()).mapNotNull { id ->
                answerOrNull { active.client.can(id).firmwareInfo() }?.let { CanDevice(id, local = false, it.hardwareName, it.version, it.firmwareName) }
            }
            _controllers.value = ControllersState(
                devices = listOf(local) + others,
                selectedCanId = selectedCanId.value ?: localId,
                scanned = true,
            )
        }
    }

    fun select(device: CanDevice) {
        if ((if (device.local) null else device.canId) != selectedCanId.value) settings.reset()
        selectedCanId.value = if (device.local) null else device.canId
        _controllers.update { it.copy(selectedCanId = device.canId) }
    }

    private fun begin(active: Active, demo: Boolean) {
        selectedCanId.value = null
        settings.reset()
        _overview.value = null
        _terminal.value = TerminalState()
        _controllers.value = ControllersState()
        session.value = active
        active.scope.launch { poll(active) }
        active.scope.launch {
            active.client.connected.first { !it }
            if (session.value === active) {
                session.value = null
                _attempt.value = ConnectAttempt(failure = string(R.string.connect_link_dropped, active.name))
            }
        }
        if (demo) active.scope.launch { demoRide(active.client.motor) }
        scanCan()
    }

    private suspend fun poll(active: Active) {
        selectedCanId.collectLatest { canId ->
            val controller = canId?.let(active.client::can) ?: active.client
            val trends = Trends(TREND_CAPACITY)
            var lastReply = TimeSource.Monotonic.markNow()
            coroutineScope {
                launch {
                    while (true) {
                        delay(1_000)
                        val quiet = lastReply.elapsedNow().inWholeSeconds.toInt()
                        if (quiet > 0) _overview.update { it?.copy(staleSeconds = quiet) }
                    }
                }
                try {
                    controller.realtime(POLL_PERIOD).collect { values ->
                        lastReply = TimeSource.Monotonic.markNow()
                        trends.add(values.voltageInput, values.currentInput, values.voltageInput * values.currentInput)
                        _overview.value = OverviewState(
                            values = values,
                            voltageTrend = trends.voltage.toList(),
                            currentTrend = trends.current.toList(),
                            powerTrend = trends.power.toList(),
                            trendCapacity = TREND_CAPACITY,
                            trendSeconds = TREND_SECONDS,
                        )
                    }
                } catch (e: VescException) {
                    disconnect()
                } catch (e: IOException) {
                    disconnect()
                }
            }
        }
    }

    private fun target(active: Active): VescController = selectedCanId.value?.let(active.client::can) ?: active.client

    private suspend fun <T> answerOrNull(request: suspend () -> T): T? = try {
        request()
    } catch (e: VescException) {
        null
    }

    private fun string(id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private class Trends(private val capacity: Int) {
        val voltage = ArrayDeque<Float>(capacity)
        val current = ArrayDeque<Float>(capacity)
        val power = ArrayDeque<Float>(capacity)

        fun add(v: Float, i: Float, p: Float) {
            for ((buffer, sample) in listOf(voltage to v, current to i, power to p)) {
                if (buffer.size == capacity) buffer.removeFirst()
                buffer.addLast(sample)
            }
        }
    }

    private companion object {
        val POLL_PERIOD = 100.milliseconds
        const val TREND_SECONDS = 60
        const val TREND_CAPACITY = TREND_SECONDS * 10
        const val HISTORY_SIZE = 20
        const val MAX_LINES = 2_000
    }
}

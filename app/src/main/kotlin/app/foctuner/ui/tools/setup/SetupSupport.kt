package app.foctuner.ui.tools.setup

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.config.PayloadException
import app.foctuner.config.SignatureMismatchException
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.HardwareType
import app.foctuner.vesc.VescController
import app.foctuner.vesc.VescException
import app.foctuner.vesc.VescLinkException
import app.foctuner.vesc.VescTimeoutException
import app.foctuner.vesc.canVescs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

enum class FailureKind { NoReply, LinkLost, Layout, Refused }

data class Failure(val kind: FailureKind, val detail: String? = null)

fun failureOf(e: Exception): Failure = when (e) {
    is VescTimeoutException -> Failure(FailureKind.NoReply)
    is VescLinkException -> Failure(FailureKind.LinkLost)
    is SignatureMismatchException -> Failure(FailureKind.Layout)
    is PayloadException, is VescException -> Failure(FailureKind.Refused, e.message)
    else -> Failure(FailureKind.LinkLost, e.message)
}

sealed interface RunState {
    data object Idle : RunState
    data class Running(val started: TimeMark, val spins: Boolean) : RunState
    data object Stopped : RunState
    data class Failed(val failure: Failure) : RunState
}

/**
 * Runs one long controller job at a time for a tool (a detection, a config write) in the connection's scope, so it
 * survives rotation. [stop] gives up waiting and, for jobs that spin a motor, sends the emergency stop.
 */
class SetupRunner(private val context: ToolContext) {
    private val _state = MutableStateFlow<RunState>(RunState.Idle)
    val state: StateFlow<RunState> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun start(spins: Boolean, block: suspend () -> Unit) {
        if (busy) return
        _state.value = RunState.Running(TimeSource.Monotonic.markNow(), spins)
        job = context.scope.launch {
            _state.value = try {
                block()
                RunState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                RunState.Failed(failureOf(e))
            } catch (e: PayloadException) {
                RunState.Failed(failureOf(e))
            }
        }
    }

    fun stop() {
        val running = _state.value as? RunState.Running ?: return
        job?.cancel()
        _state.value = RunState.Stopped
        if (running.spins) {
            context.scope.launch {
                try {
                    context.client.emergencyStop()
                } catch (e: IOException) {
                    _state.value = RunState.Failed(failureOf(e))
                }
            }
        }
    }
}

/** Defaults of the bundled definitions matching the controller's firmware, the base for reading its configs. */
suspend fun ToolContext.baseConfig(kind: ConfigKind): Config = withContext(Dispatchers.Default) {
    val version = firmware.version.takeIf { it in BundledDefinitions.versions } ?: BundledDefinitions.versions.last()
    Config.defaults(BundledDefinitions.load(version, kind))
}

/** A motor controller a bus-wide wizard works on: the connected one ([canId] null) or one on its CAN bus. */
data class SetupTarget(val controller: VescController, val canId: Int?, val hardwareName: String)

/** The connected controller (unless it isn't a motor controller) and every motor controller on its CAN bus. */
suspend fun ToolContext.scanTargets(): List<SetupTarget> {
    val local = client.firmwareInfo()
    val targets = mutableListOf<SetupTarget>()
    if (local.hardwareType == HardwareType.VESC) targets += SetupTarget(client, null, local.hardwareName)
    for ((id, info) in client.canVescs()) targets += SetupTarget(client.can(id), id, info.hardwareName)
    return targets
}

/** A number typed with either decimal separator; null when it isn't one. */
fun parseNumber(text: String): Float? = text.trim().replace(',', '.').toFloatOrNull()?.takeIf { it.isFinite() }

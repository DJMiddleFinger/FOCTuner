package app.foctuner.ui.tools.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.OutlineButton
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.disableAppOutput
import app.foctuner.vesc.updateConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

@Immutable
data class DirectionState(val targets: List<SetupTarget>? = null, val inverted: Map<Int?, Boolean> = emptyMap())

/** VESC Tool's direction setup: per controller, a short test run either way and the "inverted" setting. */
class DirectionModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(DirectionState())
    val state: StateFlow<DirectionState> = _state.asStateFlow()
    val runner = SetupRunner(context)

    /** Uses [known] (from the FOC wizard) or scans the bus, then reads each controller's direction setting. */
    fun load(known: List<SetupTarget>?) = runner.start(spins = false) {
        val targets = known?.takeIf { it.isNotEmpty() } ?: context.scanTargets()
        val base = context.baseConfig(ConfigKind.MCCONF)
        val inverted = targets.associate { it.canId to it.controller.readConfig(ConfigKind.MCCONF, base).bool("m_invert_direction") }
        _state.value = DirectionState(targets, inverted)
    }

    fun setInverted(target: SetupTarget, inverted: Boolean) = runner.start(spins = false) {
        val stored = target.controller.updateConfig(ConfigKind.MCCONF, context.baseConfig(ConfigKind.MCCONF)) {
            it.with("m_invert_direction", inverted)
        }
        _state.update { it.copy(inverted = it.inverted + (target.canId to stored.bool("m_invert_direction"))) }
    }

    /**
     * Like VESC Tool: app output off on the bus for the test, then the duty ramps to ±[TEST_DUTY] over the first
     * half of [TEST_TIME] and holds, sent with a short lease so a stalled app can't leave the motor running.
     */
    fun test(target: SetupTarget, forward: Boolean) = runner.start(spins = true) {
        val motor = target.controller.motor
        target.controller.disableAppOutput(TEST_TIME, forwardCan = true)
        delay(100.milliseconds)
        val started = TimeSource.Monotonic.markNow()
        try {
            while (started.elapsedNow() < TEST_TIME) {
                val ramp = 2 * TEST_DUTY * (started.elapsedNow() / TEST_TIME).toFloat()
                val duty = minOf(ramp, TEST_DUTY)
                motor.setDuty(if (forward) duty else -duty, holdFor = LEASE)
                delay(COMMAND_PERIOD)
            }
        } finally {
            withContext(NonCancellable) {
                try {
                    motor.release()
                } catch (e: IOException) {
                    // The lease and the firmware's timeout release it.
                }
            }
        }
    }

    private companion object {
        const val TEST_DUTY = 0.1f
        val TEST_TIME = 2.seconds
        val COMMAND_PERIOD = 40.milliseconds
        val LEASE = 300.milliseconds
    }
}

@Composable
fun DirectionTool(context: ToolContext) {
    Column(Modifier.verticalScroll(rememberScrollState())) { DirectionPanel(context, emptyList()) }
}

/** The controllers of the bus with their test buttons and inverted switches; [known] skips the scan. */
@Composable
fun DirectionPanel(context: ToolContext, known: List<SetupTarget>) {
    val model = context.model { DirectionModel(context) }
    val state by model.state.collectAsStateWithLifecycle()
    val run by model.runner.state.collectAsStateWithLifecycle()
    val busy = run is RunState.Running
    var test by remember { mutableStateOf<Pair<SetupTarget, Boolean>?>(null) }
    LaunchedEffect(model, known) { if (!model.runner.busy) model.load(known) }

    Text(stringResource(R.string.setup_direction_intro), Modifier.padding(Spacing.x2), color = FocTheme.colors.textSecondary, style = FocTheme.type.supporting)
    state.targets.orEmpty().forEach { target ->
        SectionHeader(targetName(target))
        SwitchRow(
            stringResource(R.string.setup_inverted),
            state.inverted[target.canId] == true,
            { model.setInverted(target, it) },
            !busy,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.row)
                .padding(horizontal = Spacing.x2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.setup_test_run), Modifier.weight(1f), color = FocTheme.colors.text, style = FocTheme.type.body)
            OutlineButton(stringResource(R.string.setup_reverse), { test = target to false }, enabled = !busy)
            OutlineButton(stringResource(R.string.setup_forward), { test = target to true }, Modifier.padding(start = Spacing.x1), enabled = !busy)
        }
    }
    RunStatus(run, stringResource(R.string.setup_working), model.runner::stop)
    test?.let { (target, forward) ->
        SpinDialog(stringResource(R.string.setup_direction_warning), { test = null; model.test(target, forward) }, { test = null })
    }
}

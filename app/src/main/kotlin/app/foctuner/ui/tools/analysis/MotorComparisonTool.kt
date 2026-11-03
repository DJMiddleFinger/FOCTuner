package app.foctuner.ui.tools.analysis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.foctuner.R
import app.foctuner.config.ConfigBackup
import app.foctuner.config.ConfigKind
import app.foctuner.ui.components.Hairline
import app.foctuner.ui.components.Notice
import app.foctuner.ui.components.NoticeTone
import app.foctuner.ui.components.SectionHeader
import app.foctuner.ui.theme.FocTheme
import app.foctuner.ui.theme.Spacing
import app.foctuner.ui.tools.ToolContext
import app.foctuner.ui.tools.configtools.ConfigStorage
import app.foctuner.ui.tools.lisp.PlotSeries
import app.foctuner.ui.tools.lisp.ScriptPlot
import app.foctuner.ui.tools.lisp.ScriptPlotChart
import app.foctuner.ui.tools.parts.ChoiceRow
import app.foctuner.ui.tools.parts.ReadingRow
import app.foctuner.ui.tools.setup.parseNumber
import app.foctuner.vesc.DriveSetup
import app.foctuner.vesc.MotorCalculator
import app.foctuner.vesc.MotorConstants
import app.foctuner.vesc.OperatingPoint
import app.foctuner.vesc.readMatchingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.DateFormat
import java.util.Date

@Immutable
data class ComparisonState(
    val current: MotorConstants? = null,
    val backups: List<ConfigBackup> = emptyList(),
    /** Motor B: a backup's motor, or null to compare the connected motor with itself under setup B. */
    val other: Pair<ConfigBackup, MotorConstants>? = null,
    val failed: String? = null,
)

/** VESC Tool's Motor Comparison: two motors (or one motor in two setups) through the same sweep, from their constants. */
class MotorComparisonModel(private val context: ToolContext, private val storage: ConfigStorage) {
    private val _state = MutableStateFlow(ComparisonState())
    val state: StateFlow<ComparisonState> = _state.asStateFlow()

    suspend fun load() {
        try {
            val mcconf = context.controller.readMatchingConfig(ConfigKind.MCCONF, context.firmware.version)
            val backups = withContext(Dispatchers.IO) { storage.backups.list().filter { ConfigKind.MCCONF in it.kinds } }
            _state.update { it.copy(current = MotorConstants.from(mcconf), backups = backups, failed = null) }
        } catch (e: IOException) {
            _state.update { it.copy(failed = e.message ?: e.javaClass.simpleName) }
        }
    }

    suspend fun choose(backup: ConfigBackup?) {
        if (backup == null) {
            _state.update { it.copy(other = null) }
            return
        }
        val base = app.foctuner.config.Config.defaults(app.foctuner.config.BundledDefinitions.load(app.foctuner.vesc.bundledSeriesFor(backup.firmware), ConfigKind.MCCONF))
        val loaded = withContext(Dispatchers.IO) { runCatching { storage.backups.load(backup, ConfigKind.MCCONF, base)?.config }.getOrNull() }
        _state.update { if (loaded != null) it.copy(other = backup to MotorConstants.from(loaded)) else it.copy(failed = "The backup couldn't be read") }
    }
}

private enum class Quantity { Efficiency, Loss, Current, Vbus, PowerIn }

@Composable
fun MotorComparisonTool(context: ToolContext) {
    val android = LocalContext.current
    val model = context.model { MotorComparisonModel(context, ConfigStorage.of(android)) }
    val state by model.state.collectAsStateWithLifecycle()
    LaunchedEffect(model) { if (state.current == null) model.load() }
    var sweep by rememberSaveable { mutableStateOf(MotorCalculator.Sweep.Torque) }
    var quantity by rememberSaveable { mutableStateOf(Quantity.Efficiency) }
    var torque by rememberSaveable { mutableStateOf("5") }
    var rpm by rememberSaveable { mutableStateOf("3000") }
    var vbus by rememberSaveable { mutableStateOf("48") }
    var gearingA by rememberSaveable { mutableStateOf("1") }
    var gearingB by rememberSaveable { mutableStateOf("1") }
    var choice by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(choice, state.backups) { model.choose(state.backups.firstOrNull { it.id == choice }) }
    val field = Modifier.fillMaxWidth().padding(horizontal = Spacing.x2)
    val number = KeyboardOptions(keyboardType = KeyboardType.Decimal)

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            stringResource(R.string.compare_intro),
            Modifier.padding(horizontal = Spacing.x2, vertical = Spacing.x1),
            style = FocTheme.type.supporting,
            color = FocTheme.colors.textSecondary,
        )
        state.failed?.let { Notice(stringResource(R.string.compare_failed), body = it, tone = NoticeTone.Warning) }
        val a = state.current ?: return@Column
        SectionHeader(stringResource(R.string.compare_motor_b))
        val dates = DateFormat.getDateInstance(DateFormat.MEDIUM)
        ChoiceRow(
            listOf<String?>(null) + state.backups.map { it.id },
            choice,
            { id -> state.backups.firstOrNull { it.id == id }?.let { b -> b.name.ifBlank { "${b.hardware} ${dates.format(Date(b.created))}" } } ?: stringResource(R.string.compare_same_motor) },
            { choice = it },
        )
        val b = state.other?.second ?: a
        ConstantsRows(a, b)
        SectionHeader(stringResource(R.string.compare_setup))
        OutlinedTextField(gearingA, { gearingA = it }, field, singleLine = true, label = { Text(stringResource(R.string.compare_gearing_a)) }, keyboardOptions = number)
        OutlinedTextField(gearingB, { gearingB = it }, field, singleLine = true, label = { Text(stringResource(R.string.compare_gearing_b)) }, keyboardOptions = number)
        SectionHeader(stringResource(R.string.compare_sweep))
        ChoiceRow(listOf(MotorCalculator.Sweep.Torque, MotorCalculator.Sweep.Rpm, MotorCalculator.Sweep.Vbus), sweep, { stringResource(sweepLabel(it)) }, { sweep = it })
        OutlinedTextField(torque, { torque = it }, field, singleLine = true, label = { Text(stringResource(R.string.compare_torque)) }, keyboardOptions = number)
        if (sweep != MotorCalculator.Sweep.Vbus) {
            OutlinedTextField(rpm, { rpm = it }, field, singleLine = true, label = { Text(stringResource(R.string.compare_rpm)) }, keyboardOptions = number)
        } else {
            OutlinedTextField(vbus, { vbus = it }, field, singleLine = true, label = { Text(stringResource(R.string.compare_vbus)) }, keyboardOptions = number)
        }
        ChoiceRow(Quantity.entries, quantity, { stringResource(quantityLabel(it)) }, { quantity = it })
        val t = parseNumber(torque)?.toDouble()
        val r = parseNumber(rpm)?.toDouble()
        val v = parseNumber(vbus)?.toDouble()
        val ga = parseNumber(gearingA)?.toDouble()?.takeIf { it > 0 }
        val gb = parseNumber(gearingB)?.toDouble()?.takeIf { it > 0 }
        val plot = remember(a, b, sweep, quantity, t, r, v, ga, gb) {
            if (t == null || r == null || v == null || ga == null || gb == null || t <= 0 || r <= 0 || a.fluxLinkage <= 0 || b.fluxLinkage <= 0) {
                null
            } else {
                comparisonPlot(a, b, DriveSetup(gearing = ga), DriveSetup(gearing = gb), sweep, quantity, t, r, v)
            }
        }
        Hairline()
        if (plot == null) {
            Notice(stringResource(R.string.compare_need_values), body = stringResource(R.string.compare_need_values_body))
        } else {
            ScriptPlotChart(plot, Modifier.padding(vertical = Spacing.x2))
        }
    }
}

@Composable
private fun ConstantsRows(a: MotorConstants, b: MotorConstants) {
    fun pair(x: Double, y: Double, scale: Double, digits: Int) = "%.${digits}f / %.${digits}f".format(x * scale, y * scale)
    ReadingRow(stringResource(R.string.compare_resistance), pair(a.resistance, b.resistance, 1000.0, 2), "mΩ")
    ReadingRow(stringResource(R.string.compare_inductance), pair(a.inductance, b.inductance, 1e6, 1), "µH")
    ReadingRow(stringResource(R.string.compare_flux), pair(a.fluxLinkage, b.fluxLinkage, 1000.0, 2), "mWb")
    ReadingRow(stringResource(R.string.compare_poles), "${a.poles} / ${b.poles}")
}

private fun comparisonPlot(
    a: MotorConstants, b: MotorConstants, setupA: DriveSetup, setupB: DriveSetup,
    sweep: MotorCalculator.Sweep, quantity: Quantity, torque: Double, rpm: Double, vbus: Double,
): ScriptPlot {
    fun run(m: MotorConstants, s: DriveSetup) = MotorCalculator(m, s).sweep(sweep, torque = torque, rpm = rpm, points = 100, vbus = vbus)
    fun value(p: OperatingPoint): Float = when (quantity) {
        Quantity.Efficiency -> (p.efficiency * 100).toFloat()
        Quantity.Loss -> p.lossTotal.toFloat()
        Quantity.Current -> p.current.toFloat()
        Quantity.Vbus -> p.vbusMin.toFloat()
        Quantity.PowerIn -> p.powerIn.toFloat()
    }
    fun series(name: String, points: List<Pair<Double, OperatingPoint>>) =
        PlotSeries(name, FloatArray(points.size) { points[it].first.toFloat() }, FloatArray(points.size) { value(points[it].second) })
    val unit = when (quantity) {
        Quantity.Efficiency -> "%"
        Quantity.Loss, Quantity.PowerIn -> "W"
        Quantity.Current -> "A"
        Quantity.Vbus -> "V"
    }
    val x = if (sweep == MotorCalculator.Sweep.Rpm) "RPM" else "Nm"
    return ScriptPlot(x, unit, listOf(series("A", run(a, setupA)), series("B", run(b, setupB))))
}

private fun sweepLabel(s: MotorCalculator.Sweep): Int = when (s) {
    MotorCalculator.Sweep.Torque -> R.string.compare_sweep_torque
    MotorCalculator.Sweep.Rpm -> R.string.compare_sweep_rpm
    else -> R.string.compare_sweep_vbus
}

private fun quantityLabel(q: Quantity): Int = when (q) {
    Quantity.Efficiency -> R.string.compare_q_efficiency
    Quantity.Loss -> R.string.compare_q_loss
    Quantity.Current -> R.string.compare_q_current
    Quantity.Vbus -> R.string.compare_q_vbus
    Quantity.PowerIn -> R.string.compare_q_power
}

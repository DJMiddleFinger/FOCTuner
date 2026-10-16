package app.foctuner.ui.tools.bms

import androidx.compose.runtime.Immutable
import app.foctuner.ui.tools.ToolContext
import app.foctuner.vesc.BmsValues
import app.foctuner.vesc.HardwareType
import app.foctuner.vesc.HumidityTemperature
import app.foctuner.vesc.VescException
import app.foctuner.vesc.bmsForceBalance
import app.foctuner.vesc.bmsResetCounters
import app.foctuner.vesc.bmsSetChargeAllowed
import app.foctuner.vesc.bmsValues
import app.foctuner.vesc.bmsZeroCurrentOffset
import app.foctuner.vesc.externalHumidityTemperature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The BMS commands VESC Tool's BMS page offers, each sent only after the user confirms it. */
enum class BmsCommand { BalanceOn, BalanceOff, AllowCharging, DisableCharging, ResetAh, ResetWh, ZeroCurrent }

@Immutable
data class BmsState(
    val values: BmsValues? = null,
    /** Only VESC BMS hardware has the external sensor. */
    val external: HumidityTemperature? = null,
    val failed: Boolean = false,
    /** The last command went out; the BMS doesn't acknowledge, so the values show whether it took. */
    val sent: BmsCommand? = null,
)

/** VESC Tool's BMS page for the selected controller (a VESC BMS, or a motor controller relaying its BMS). */
class BmsModel(private val context: ToolContext) {
    private val _state = MutableStateFlow(BmsState())
    val state: StateFlow<BmsState> = _state.asStateFlow()
    private val isBms = context.firmware.hardwareType == HardwareType.VESC_BMS

    fun send(command: BmsCommand) {
        context.scope.launch {
            try {
                val c = context.controller
                when (command) {
                    BmsCommand.BalanceOn -> c.bmsForceBalance(true)
                    BmsCommand.BalanceOff -> c.bmsForceBalance(false)
                    BmsCommand.AllowCharging -> c.bmsSetChargeAllowed(true)
                    BmsCommand.DisableCharging -> c.bmsSetChargeAllowed(false)
                    BmsCommand.ResetAh -> c.bmsResetCounters(ampHours = true, wattHours = false)
                    BmsCommand.ResetWh -> c.bmsResetCounters(ampHours = false, wattHours = true)
                    BmsCommand.ZeroCurrent -> c.bmsZeroCurrentOffset()
                }
                _state.update { it.copy(sent = command) }
            } catch (e: VescException) {
                _state.update { it.copy(failed = true) }
            }
        }
    }

    suspend fun refresh() {
        try {
            val values = context.controller.bmsValues()
            val external = if (isBms) context.controller.externalHumidityTemperature() else null
            _state.update { it.copy(values = values, external = external, failed = false) }
        } catch (e: VescException) {
            _state.update { it.copy(failed = true) }
        }
    }
}

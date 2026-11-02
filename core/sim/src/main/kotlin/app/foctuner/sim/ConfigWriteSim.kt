package app.foctuner.sim

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigCodec
import app.foctuner.config.ConfigKind
import app.foctuner.config.ParamValue
import app.foctuner.config.PayloadException
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId.COMM_SET_MCCONF_TEMP_SETUP
import kotlin.math.PI
import kotlin.math.abs

/**
 * Motor config writes as `commands.c` handles them on a 60_MK6 (`hw_60_core.h` limits): COMM_SET_MCCONF keeps the
 * stored FOC offsets unless `foc_offsets_cal_mode` allows writing them and clamps to the hardware's limits
 * (`commands_apply_mcconf_hw_limits`); COMM_SET_MCCONF_TEMP(_SETUP) changes the riding limits, converting speeds with
 * the motor's wheel and gearing and passing them on to the other motor, which stands in for the CAN bus.
 */
internal class ConfigWriteSim(private val vesc: SimulatedVesc) {
    fun setMcconf(motor: MotorModel, packet: ByteArray) {
        val written = try {
            ConfigCodec.deserialize(definition, packet.copyOfRange(1, packet.size))
        } catch (e: PayloadException) {
            vesc.storeConfig(motor, ConfigKind.MCCONF, packet)
            return
        }
        val old = vesc.config(motor, ConfigKind.MCCONF)
        val kept = if (written.int("foc_offsets_cal_mode") and 2L == 0L) {
            OFFSETS.fold(written) { c, name -> c.with(name, old[name]) }
        } else {
            written
        }
        vesc.storeConfig(motor, ConfigKind.MCCONF, byteArrayOf(packet[0]) + ConfigCodec.serialize(limited(kept)))
    }

    fun setTemporary(motor: MotorModel, r: ByteReader, packetId: Int, others: List<MotorModel>) {
        r.u8() // store: the simulator doesn't reboot, so stored and temporary limits behave the same
        val forward = r.u8() != 0
        val ack = r.u8() != 0
        val divide = r.u8() != 0
        var c = vesc.config(motor, ConfigKind.MCCONF)
        val scales = Pair(r.f32Auto().toDouble().coerceIn(0.0, 1.0), r.f32Auto().toDouble().coerceIn(0.0, 1.0))
        val factor = if (packetId == COMM_SET_MCCONF_TEMP_SETUP.id) {
            (c.int("si_motor_poles") / 2.0) * 60.0 * c.double("si_gear_ratio") / (c.double("si_wheel_diameter") * PI)
        } else {
            1.0
        }
        val erpm = Pair(r.f32Auto() * factor, r.f32Auto() * factor)
        val duty = Pair(r.f32Auto().toDouble(), r.f32Auto().toDouble())
        val controllers = if (divide) 1 + others.size else 1
        val watts = Pair(r.f32Auto().toDouble() / controllers, r.f32Auto().toDouble() / controllers)
        val inCurrent = if (r.remaining >= 8) Pair(r.f32Auto().toDouble(), r.f32Auto().toDouble()) else null
        val apply = { config: Config ->
            var x = config.with("l_current_min_scale", scales.first).with("l_current_max_scale", scales.second)
                .with("l_min_erpm", erpm.first).with("l_max_erpm", erpm.second)
                .with("l_min_duty", duty.first).with("l_max_duty", duty.second)
                .with("l_watt_min", watts.first).with("l_watt_max", watts.second)
            if (inCurrent != null) x = x.with("l_in_current_min", inCurrent.first).with("l_in_current_max", inCurrent.second)
            limited(x)
        }
        c = apply(c)
        vesc.replaceConfig(motor, ConfigKind.MCCONF, c)
        if (forward) others.forEach { vesc.replaceConfig(it, ConfigKind.MCCONF, apply(vesc.config(it, ConfigKind.MCCONF))) }
        if (ack) vesc.reply(byteArrayOf(packetId.toByte()))
    }

    private fun limited(config: Config): Config {
        var c = LIMITS.entries.fold(config) { acc, (name, range) ->
            when (val v = acc[name]) {
                is ParamValue.DoubleValue -> acc.with(name, v.value.coerceIn(range))
                is ParamValue.IntValue -> acc.with(name, v.value.coerceIn(range.start.toLong(), range.endInclusive.toLong()))
                else -> acc
            }
        }
        val slStart = c.double("foc_sl_erpm") * 0.9
        if (abs(c.double("foc_sl_erpm_start")) > slStart) c = c.with("foc_sl_erpm_start", if (c.double("foc_sl_erpm_start") < 0) -slStart else slStart)
        return c
    }

    private val definition get() = BundledDefinitions.load(CONFIG_VERSION, ConfigKind.MCCONF)

    private companion object {
        const val CONFIG_VERSION = "7.01"

        val OFFSETS = listOf("foc_offsets_current", "foc_offsets_voltage", "foc_offsets_voltage_undriven")
            .flatMap { prefix -> (0..2).map { "${prefix}__$it" } }

        val LIMITS = mapOf(
            "l_current_max_scale" to 0.0..1.0,
            "l_current_min_scale" to 0.0..1.0,
            "l_erpm_start" to 0.0..1.0,
            "foc_overmod_factor" to 1.0..1.5,
            "l_current_max" to -120.0..120.0,
            "l_current_min" to -120.0..120.0,
            "foc_hfi_amb_current" to -120.0..120.0,
            "l_in_current_max" to -120.0..120.0,
            "l_in_current_min" to -120.0..120.0,
            "l_abs_current_max" to 0.0..160.0,
            "l_max_vin" to 6.0..57.0,
            "l_min_vin" to 6.0..57.0,
            "l_max_erpm" to -200e3..200e3,
            "l_min_erpm" to -200e3..200e3,
            "l_min_duty" to 0.0..0.1,
            "l_max_duty" to 0.0..0.99,
            "l_temp_fet_start" to -40.0..110.0,
            "l_temp_fet_end" to -40.0..110.0,
        )
    }
}

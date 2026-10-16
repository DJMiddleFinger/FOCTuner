package app.foctuner.sim

import app.foctuner.config.Config
import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_ADC
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_CHUK
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_PPM
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * A receiver and throttles that someone keeps moving, for the mapping pages: every [CYCLE_SECONDS] the RC stick rests
 * at center, goes to min, to max and back, and the throttles are squeezed and released. Like the firmware, an input only reads while its app is selected
 * (`app_to_use`), and the decoded value follows the mapping stored in the app config.
 */
internal object DecodedInputsSim {
    fun ppm(app: Config, uptime: Duration): ByteArray {
        val w = ByteWriter().u8(COMM_GET_DECODED_PPM.id)
        if (app.int("app_to_use") !in PPM_APPS) return w.i32(0).i32(0).toByteArray()
        val stick = stick(uptime)
        val pulse = PPM_CENTER + stick * (if (stick < 0) PPM_CENTER - PPM_MIN else PPM_MAX - PPM_CENTER)
        val start = app.double("app_ppm_conf.pulse_start").toFloat()
        val end = app.double("app_ppm_conf.pulse_end").toFloat()
        val center = app.double("app_ppm_conf.pulse_center").toFloat()
        val value = if (pulse >= center) (pulse - center) / (end - center) else (pulse - center) / (center - start)
        return w.f32(value.coerceIn(-1f, 1f), 1e6f).f32(pulse, 1e6f).toByteArray()
    }

    /** A thumb throttle on ADC1 that rests at its minimum, and a brake lever on ADC2 pulled half a cycle later. */
    fun adc(app: Config, uptime: Duration): ByteArray {
        val w = ByteWriter().u8(COMM_GET_DECODED_ADC.id)
        if (app.int("app_to_use") !in ADC_APPS) return w.i32(0).i32(0).i32(0).i32(0).toByteArray()
        val voltage = ADC_MIN + throttle(uptime) * (ADC_MAX - ADC_MIN)
        val voltage2 = ADC2_MIN + throttle(uptime + (CYCLE_SECONDS / 2).seconds) * (ADC2_MAX - ADC2_MIN)
        val value = mapped(voltage, app.double("app_adc_conf.voltage_start"), app.double("app_adc_conf.voltage_end"))
        val value2 = mapped(voltage2, app.double("app_adc_conf.voltage2_start"), app.double("app_adc_conf.voltage2_end"))
        return w.f32(value, 1e6f).f32(voltage, 1e6f).f32(value2, 1e6f).f32(voltage2, 1e6f).toByteArray()
    }

    fun nunchuk(uptime: Duration): ByteArray {
        val y = sin(2 * PI * uptime.toDouble(DurationUnit.SECONDS) / CYCLE_SECONDS).toFloat()
        return ByteWriter().u8(COMM_GET_DECODED_CHUK.id).f32(y, 1e6f).toByteArray()
    }

    /** −1..1: rest, to min, through to max, back to rest, rest. */
    private fun stick(uptime: Duration): Float {
        val t = (uptime.toDouble(DurationUnit.SECONDS) % CYCLE_SECONDS).toFloat()
        return when {
            t < 2f -> 0f
            t < 4f -> -(t - 2f) / 2f
            t < 6f -> -1f + (t - 4f)
            t < 8f -> 1f - (t - 6f) / 2f
            else -> 0f
        }
    }

    /** 0..1: rest, squeeze, hold, release, rest. */
    private fun throttle(uptime: Duration): Float {
        val t = (uptime.toDouble(DurationUnit.SECONDS) % CYCLE_SECONDS).toFloat()
        return when {
            t < 3f -> 0f
            t < 6f -> (t - 3f) / 3f
            t < 8f -> 1f
            t < 10f -> 1f - (t - 8f) / 2f
            else -> 0f
        }
    }

    private fun mapped(voltage: Float, start: Double, end: Double): Float =
        if (end > start) ((voltage - start) / (end - start)).toFloat().coerceIn(0f, 1f) else 0f

    const val CYCLE_SECONDS = 12.0
    private val PPM_APPS = listOf(1L, 4L)
    private val ADC_APPS = listOf(2L, 5L)
    private const val PPM_MIN = 1.05f
    private const val PPM_CENTER = 1.52f
    private const val PPM_MAX = 1.95f
    private const val ADC_MIN = 0.86f
    private const val ADC_MAX = 3.12f
    private const val ADC2_MIN = 0.84f
    private const val ADC2_MAX = 2.95f
}

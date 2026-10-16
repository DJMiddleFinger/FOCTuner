package app.foctuner.vesc

import app.foctuner.config.Config

/**
 * The span a throttle has been moved through, for mapping PPM pulses or ADC voltages the way VESC Tool's mapping
 * pages do: [min] and [max] follow the extremes, [center] the latest reading while it is in the middle half of the
 * span (where a throttle rests when let go), otherwise the midpoint.
 */
class InputRange {
    var min = 0f
        private set
    var max = 0f
        private set
    var center = 0f
        private set
    private var empty = true

    val span: Float get() = max - min

    fun add(value: Float) {
        if (empty) {
            min = value
            max = value
            empty = false
        }
        min = minOf(min, value)
        max = maxOf(max, value)
        val pos = value - min
        center = if (pos > span / 4 && pos < span * 3 / 4) value else min + span / 2
    }

    fun reset() {
        min = 0f
        max = 0f
        center = 0f
        empty = true
    }
}

/** VESC Tool's test for a PPM mapping: the stick was moved through at least 0.4 ms. */
val InputRange.validPpm: Boolean get() = span > 0.4f

fun Config.withPpmMapping(pulse: InputRange): Config =
    with("app_ppm_conf.pulse_start", pulse.min.toDouble())
        .with("app_ppm_conf.pulse_end", pulse.max.toDouble())
        .with("app_ppm_conf.pulse_center", pulse.center.toDouble())

fun Config.withAdcMapping(voltage: InputRange, voltage2: InputRange): Config =
    with("app_adc_conf.voltage_start", voltage.min.toDouble())
        .with("app_adc_conf.voltage_end", voltage.max.toDouble())
        .with("app_adc_conf.voltage_center", voltage.center.toDouble())
        .with("app_adc_conf.voltage2_start", voltage2.min.toDouble())
        .with("app_adc_conf.voltage2_end", voltage2.max.toDouble())

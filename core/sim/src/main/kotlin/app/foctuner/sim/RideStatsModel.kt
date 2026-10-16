package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_STATS
import kotlin.math.PI
import kotlin.math.abs

/** Time-weighted averages and peaks like the firmware's mc_interface stats, reported as COMM_GET_STATS. */
internal class RideStatsModel {
    private var seconds = 0.0
    private val sums = DoubleArray(5)
    private val peaks = DoubleArray(5)

    fun add(dt: Float, erpm: Float, power: Float, current: Float, tempFet: Float, tempMotor: Float) {
        val speed = abs(erpm) / POLE_PAIRS / 60.0 * PI * WHEEL_DIAMETER_M
        val samples = doubleArrayOf(speed, power.toDouble(), abs(current).toDouble(), tempFet.toDouble(), tempMotor.toDouble())
        seconds += dt
        for (i in samples.indices) {
            sums[i] += samples[i] * dt
            peaks[i] = maxOf(peaks[i], samples[i])
        }
    }

    fun reset() {
        seconds = 0.0
        sums.fill(0.0)
        peaks.fill(0.0)
    }

    fun reply(): ByteArray {
        val w = ByteWriter().u8(COMM_GET_STATS.id).u32(ALL_FIELDS)
        for (i in sums.indices) {
            w.f32Auto(if (seconds > 0) (sums[i] / seconds).toFloat() else 0f)
            w.f32Auto(peaks[i].toFloat())
        }
        return w.f32Auto(seconds.toFloat()).toByteArray()
    }

    private companion object {
        const val ALL_FIELDS = 0x7FFL
        const val POLE_PAIRS = 7
        const val WHEEL_DIAMETER_M = 0.083
    }
}

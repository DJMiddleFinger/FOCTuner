package app.foctuner.vesc

import app.foctuner.config.BundledDefinitions
import app.foctuner.config.Config
import app.foctuner.config.ConfigKind
import app.foctuner.protocol.ByteReader
import app.foctuner.protocol.CommPacketId.COMM_APP_DISABLE_OUTPUT
import app.foctuner.sim.SimulatedVesc
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DetectionTest {
    private val motor = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.MCCONF))
    private val app = Config.defaults(BundledDefinitions.load("7.01", ConfigKind.APPCONF))

    private fun TestScope.client() = VescClient(SimulatedVesc(testTimeSource), backgroundScope)

    private suspend fun TestScope.timed(block: suspend () -> Unit): Long {
        val started = currentTime
        block()
        return currentTime - started
    }

    @Test
    fun measuresResistanceAndInductanceOnceTheMeasurementIsOver() = runTest {
        val client = client()
        lateinit var rl: ResistanceInductance
        assertEquals(3_500, timed { rl = client.measureResistanceInductance() })
        assertTrue(rl.ok)
        assertEquals(0.030f, rl.resistance, 1e-5f)
        assertEquals(28e-6f, rl.inductance, 1e-8f)
        assertEquals(4.2e-6f, rl.ldLqDiff, 1e-8f)
    }

    @Test
    fun fluxLinkageNeedsDriveOrATurningMotor() = runTest {
        val client = client()
        val driven = client.measureFluxLinkage(current = 10f, erpmPerSecond = 2000f, duty = 0.3f, resistance = 0.03f, inductance = 28e-6f)
        assertTrue(driven.ok)
        assertEquals(0.0344f, driven.linkage, 1e-6f)
        assertNull(driven.encoder, "no encoder on the simulated motor")

        val passive = client.measureFluxLinkage(current = 0f, erpmPerSecond = 2000f, duty = 0.3f, resistance = 0.03f, inductance = 28e-6f)
        assertFalse(passive.ok, "nothing turns the motor")
    }

    @Test
    fun hallTableOnTheSensoredMotorOnly() = runTest {
        val client = client()
        lateinit var halls: FocHallTable
        assertEquals(11_800, timed { halls = client.detectFocHalls(10f) })
        assertTrue(halls.ok)
        assertEquals(listOf(255, 9, 76, 43, 143, 176, 109, 255), halls.table)

        val sensorless = client.can(1).detectFocHalls(10f)
        assertFalse(sensorless.ok)
        assertEquals(List(8) { 255 }, sensorless.table)
    }

    @Test
    fun hallDetectionGetsNoUsableReplyUnlessThePortIsInHallMode() = runTest {
        val client = client()
        client.writeConfig(ConfigKind.MCCONF, motor.with("m_sensor_port_mode", 2L))
        assertEquals(25_000, timed { assertFailsWith<VescTimeoutException> { client.detectFocHalls(10f) } })
    }

    @Test
    fun encoderDetectionNeedsAConfiguredEncoder() = runTest {
        val client = client()
        assertNull(client.detectEncoder(10f))

        client.writeConfig(ConfigKind.MCCONF, motor.with("m_sensor_port_mode", 2L))
        lateinit var encoder: EncoderParams
        assertEquals(35_000, timed { encoder = assertNotNullResult(client.detectEncoder(10f)) })
        assertEquals(7f, encoder.ratio)
        assertEquals(172.4f, encoder.offset, 1e-3f)
        assertFalse(encoder.inverted)

        val linkage = client.measureFluxLinkage(10f, 2000f, 0.3f, 0.03f, 28e-6f)
        assertEquals(encoder, linkage.encoder, "read along the way")
    }

    @Test
    fun bldcDetectionReadsTheHallsWhereThereAreAny() = runTest {
        val client = client()
        val sensored = client.detectBldc(current = 5f, minErpm = 450f, lowDuty = 0.05f)
        assertTrue(sensored.ok)
        assertEquals(HallSensors.Detected, sensored.halls)
        assertEquals(listOf(-1, 1, 3, 2, 5, 6, 4, -1), sensored.hallTable)

        assertEquals(HallSensors.Failed, client.can(1).detectBldc(5f, 450f, 0.05f).halls)
        assertFalse(client.detectBldc(current = 0.1f, minErpm = 450f, lowDuty = 0.05f).ok, "too little current to start")
    }

    @Test
    fun applyAllFocStoresBothMotorsAndBracketsItWithAppOutputDisabled() = runTest {
        val link = RecordingTransport(SimulatedVesc(testTimeSource))
        val client = VescClient(link, backgroundScope)
        lateinit var result: FocDetectionResult
        assertEquals(30_100, timed { result = client.detectApplyAllFoc(detectCan = true, maxPowerLoss = 120f, minCurrentIn = -20f, maxCurrentIn = 30f) })
        assertTrue(result.ok)
        assertEquals(DetectedSensors.Hall, result.sensors)

        val first = client.readConfig(ConfigKind.MCCONF, motor)
        assertEquals(sqrt(120.0 / 0.03 / 1.5), first.double("l_current_max"), 1e-2)
        assertEquals(-first.double("l_current_max"), first.double("l_current_min"), 1e-6)
        assertEquals(0.03, first.double("foc_motor_r"), 1e-5)
        assertEquals(0.0344, first.double("foc_motor_flux_linkage"), 1e-6)
        assertEquals(0.028, first.double("foc_current_kp"), 1e-5)
        assertEquals(-20.0, first.double("l_in_current_min"), 1e-3)
        assertEquals(30.0, first.double("l_in_current_max"), 1e-3)
        assertEquals(motor.double("foc_openloop_rpm"), first.double("foc_openloop_rpm"), 1e-3, "0 keeps the default")
        assertEquals(2L, first.int("foc_sensor_mode"), "hall")
        assertTrue(first.bool("foc_temp_comp"))

        val second = client.can(1).readConfig(ConfigKind.MCCONF, motor)
        assertEquals(0L, second.int("foc_sensor_mode"), "sensorless")
        assertEquals(sqrt(120.0 / 0.031 / 1.5), second.double("l_current_max"), 1e-2)
        assertEquals(0b1111L, client.readConfig(ConfigKind.APPCONF, app).int("can_status_msgs_r1"))

        val appOutput = link.sent(COMM_APP_DISABLE_OUTPUT.id).map { ByteReader(it, 1).run { u8() to i32() } }
        assertEquals(listOf(1 to 180_000, 1 to 0), appOutput)
    }

    @Test
    fun applyAllFocReportsWhyItFailed() = runTest {
        val result = client().detectApplyAllFoc(detectCan = false, maxPowerLoss = 0f)
        assertFalse(result.ok)
        assertEquals(FocDetectionFailure.FluxLinkage, result.failure)

        assertEquals(FaultCode.DRV, FocDetectionResult(-100 + FaultCode.DRV.ordinal).fault)
        assertEquals(FocDetectionFailure.Fault, FocDetectionResult(-100 + FaultCode.UNDER_VOLTAGE.ordinal).failure)
        assertEquals(FocDetectionFailure.CanTimeout, FocDetectionResult(-50).failure)
        assertEquals(DetectedSensors.Encoder, FocDetectionResult(2).sensors)
    }

    @Test
    fun aControllerThatNeverAnswersTimesOutAfterTheLongestMeasurement() = runTest {
        val client = client()
        assertEquals(10_000, timed { assertFailsWith<VescTimeoutException> { client.can(7).measureResistanceInductance() } })
    }

    @Test
    fun imuCalibrationKeepsTheGivenYawAndAppliesToTheAppConfig() = runTest {
        val client = client()
        lateinit var cal: ImuCalibration
        assertEquals(4_500, timed { cal = client.calibrateImu(yaw = 90f) })
        assertEquals(90f, cal.yaw, 1e-3f)
        assertEquals(1.83f, cal.roll, 1e-4f)
        assertEquals(3, cal.gyroOffsets.size)

        val stored = client.updateConfig(ConfigKind.APPCONF, app) { it.withImuCalibration(cal) }
        assertEquals(90.0, stored.double("imu_conf.rot_yaw"), 1e-3)
        assertEquals(cal.gyroOffsets[1].toDouble(), stored.double("imu_conf.gyro_offsets__1"), 1e-4)
    }

    @Test
    fun theSecondMotorOfADualBoardIsTheOnlyVescOnTheSimulatedBus() = runTest {
        assertEquals(listOf(1), client().canVescs().map { it.first })
    }

    private fun <T : Any> assertNotNullResult(value: T?): T = kotlin.test.assertNotNull(value)
}

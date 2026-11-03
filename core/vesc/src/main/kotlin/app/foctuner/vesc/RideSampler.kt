package app.foctuner.vesc

/**
 * Reads what a ride log needs: COMM_GET_VALUES, COMM_GET_VALUES_SETUP and the IMU. The realtime values are what makes
 * a sample (null when the controller doesn't answer); the others are left out if they fail, and the IMU is not asked
 * again after it didn't answer once, since a controller without one would cost a timeout on every sample.
 */
class RideSampler(private val controller: VescController) {
    private var imuAnswers = true

    suspend fun sample(): RideSample? {
        val values = try {
            controller.values()
        } catch (e: VescTimeoutException) {
            return null
        }
        val setup = try {
            controller.setupValues()
        } catch (e: VescTimeoutException) {
            null
        }
        val imu = if (!imuAnswers) null else try {
            controller.imu(IMU_FIELDS)
        } catch (e: VescTimeoutException) {
            imuAnswers = false
            null
        }
        return RideSample(values, setup, imu)
    }

    private companion object {
        /** Roll, pitch, yaw, acceleration and angular rate. */
        const val IMU_FIELDS = 0x1FF
    }
}

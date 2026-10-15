package app.foctuner.ui.session

import app.foctuner.vesc.MotorControl
import kotlinx.coroutines.delay

/**
 * Demo mode drives the simulated motor through a short ride on repeat (pull away, cruise, brake, coast) so
 * every overview value moves. It goes through the same protocol path as a real controller.
 */
internal suspend fun demoRide(motor: MotorControl) {
    while (true) {
        motor.setCurrent(28f)
        delay(3_000)
        motor.setCurrent(9f)
        delay(5_000)
        motor.setBrakeCurrent(22f)
        delay(2_500)
        motor.release()
        delay(2_500)
    }
}

package app.foctuner.ui.session

import app.foctuner.vesc.MotorControl
import app.foctuner.vesc.VescException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest

/**
 * Demo mode drives the simulated motor through a short ride on repeat (pull away, cruise, brake, coast) so
 * every overview value moves. It goes through the same protocol path as a real controller.
 *
 * The ride stops and releases the motor while [held] is true, so it never fights a tool that drives the motor
 * itself. A refused command (the STOP button's lockout) just waits a second and carries on.
 */
internal suspend fun demoRide(motor: MotorControl, held: StateFlow<Boolean>) {
    held.collectLatest { hold ->
        if (hold) {
            try {
                motor.release()
            } catch (e: VescException) {
                // The STOP lockout or a dropped link already leaves the motor released.
            }
            return@collectLatest
        }
        while (true) {
            try {
                motor.setCurrent(28f)
                delay(3_000)
                motor.setCurrent(9f)
                delay(5_000)
                motor.setBrakeCurrent(22f)
                delay(2_500)
                motor.release()
                delay(2_500)
            } catch (e: VescException) {
                delay(1_000)
            }
        }
    }
}

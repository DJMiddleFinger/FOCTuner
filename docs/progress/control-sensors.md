# Motor control and sensors: progress

Branch `claude/feat-control-sensors-dde2a3`. Scope: manual motor control (duty, current, brake, RPM, position, handbrake, relative current, servo, STOP/ESTOP, rotor position display), setup values and odometer, IMU, BMS, GNSS, IO board, power switch, external humidity/temperature. Files: `core/vesc/.../vesc/<Area>.kt`, `core/sim/.../sim/<Area>Sim.kt`, `app/.../ui/tools/<area>/`, `strings_<area>.xml`, this file.

## Done

- Read every payload layout, scale and command sequence below from the sources (read 2026-10-16): `vedderb/bldc` master (`comm/commands.c`, `datatypes.h`, `motor/mc_interface.c`, `main.c`, `bms.c`, `imu/imu.c`, `timeout.c`, `util/buffer.c`), `vedderb/vesc_tool` master (`commands.cpp`, `mainwindow.cpp/.ui`, `pages/pagertdata.cpp/.ui`, `pages/pageimu.cpp`, `pages/pagebms.cpp`, `widgets/vesc3dview.cpp`, `mobile/Controls.qml`, `RtDataIMU.qml`, `RtDataSetup.qml`, `BMS.qml`), `vedderb/vesc_bms_fw` master (`commands.c`).
- No code yet: the session stopped at the usage guard (5-hour window at 87%) before the first edit.

## Protocol reference

Big-endian. `f16(s)` / `f32(s)` = int16 / int32 of value × s, truncated; `f32Auto` = `ByteWriter.f32Auto`; `i64(s)` = int64 of value × s. None of the set commands below get a reply.

### Motor commands

| Command | Payload | Notes |
|---|---|---|
| `COMM_SET_POS` | i32 degrees × 1e6 | `timeout_reset`. VESC Tool range 0..360°, step 0.1 |
| `COMM_SET_HANDBRAKE` | f32(1e3) amps | `timeout_reset`. FOC: open-loop current vector that holds the rotor; BLDC/DC: brake current |
| `COMM_SET_CURRENT_REL` | f32(1e5), −1..1 | `timeout_reset`. Firmware multiplies by `l_current_max` when the sign matches the duty (or \|duty\| < 0.02), else by \|`l_current_min`\|, and sets a 0.1 s current-off delay |
| `COMM_SET_SERVO_POS` | f16(1e3), 0..1 | `pwm_servo_set_servo_out`. No `timeout_reset`; the command timeout doesn't touch the servo and there is no release, so it doesn't belong under the motor keepalive |
| `COMM_SET_DETECT` | u8 `disp_pos_mode` | 0 NONE, 1 INDUCTANCE, 2 OBSERVER, 3 ENCODER, 4 PID_POS, 5 PID_POS_ERROR, 6 ENCODER_OBSERVER_ERROR, 7 HALL_OBSERVER_ERROR. Stored for any motor type; on BLDC only, NONE releases the motor and INDUCTANCE starts inductance detection (`timeout_reset`) |
| `COMM_ROTOR_POSITION` (unsolicited) | i32 degrees × 1e5 | Sent every 10 ms by `main.c`'s periodic thread while the mode isn't NONE (OBSERVER and the two observer-error modes on FOC only), and while detecting. Comes back through CAN forwarding like any reply |
| `COMM_MOTOR_ESTOP` | u16 ms | `mc_interface_ignore_input_both(ms)` (both motors of a dual board; counted down in the 1 kHz timer) then `release_motor_override_both`. Motor commands are ignored meanwhile (`mc_interface_try_input`); `COMM_ALIVE` still resets the timeout |

VESC Tool's stop button (`MainWindow::on_stopButton_clicked`, also the Esc key): `setCurrent(0)`, then `motorEstop(estop_ms)` (setting, default 5000, 0 = skip), stops experiments, unchecks "send alive". It goes to the selected controller only, like every other command.

VESC Tool control defaults (`mainwindow.ui`): duty 0.20 (−1..1, step 0.01), current 3 A (±5000, 0.5), brake 3 A (±500, 0.5), speed 5000 ERPM (±400000, 100), handbrake 3 A (±500, 0.5), position 0° (0..360, 0.1). Desktop buttons latch (keepalive on until STOP); keyboard Up/Down hold = ±current, PgDn hold = brake, key release = current 0. Mobile `Controls.qml`: tabs Current / Duty / RPM / Position; REV, FWD and the brake button ("Brake", "0 Duty (brake)", "0 ERPM (brake)") run while pressed and send current 0 on release unless "Continue after release" is checked; a separate Release button; COMM_ALIVE every 200 ms while the dialog is open.

### Values

- `COMM_GET_VALUES_SELECTIVE`: request u32 mask; reply echoes the u32 mask, then only the masked fields of COMM_GET_VALUES. Bit n = the n-th field in `RealtimeValues.parse` order (bit 18 is all three MOSFET temps, 19 vd, 20 vq, 21 status byte).
- `COMM_GET_VALUES_SETUP` / `_SELECTIVE` (selective: u32 mask in the request, echoed): 0 temp MOSFET f16(1e1), 1 temp motor f16(1e1), 2 motor current total f32(1e2), 3 input current total f32(1e2), 4 duty f16(1e3), 5 ERPM f32(1), 6 speed m/s f32(1e3), 7 input voltage f16(1e1), 8 battery level 0..1 f16(1e3), 9 Ah f32(1e4), 10 Ah charged f32(1e4), 11 Wh f32(1e4), 12 Wh charged f32(1e4), 13 distance m f32(1e3), 14 absolute distance m f32(1e3), 15 PID position f32(1e6), 16 fault u8, 17 controller id u8, 18 number of controllers u8, 19 battery Wh left f32(1e3), 20 odometer m u32, 21 uptime ms u32. Currents, Ah and Wh are summed over CAN controllers heard in the last 0.1 s. Speed and distance come from `si_wheel_diameter`, `si_gear_ratio`, `si_motor_poles`; battery level from `si_battery_type`, `si_battery_cells`, `si_battery_ah`.
- Mobile `RtDataSetup.qml` derives: trip = absolute distance; Wh/km = (Wh − Wh charged) / trip km; instantaneous Wh/km = input power / speed km/h, clamped ±60 and low-passed; range = battery Wh left / Wh/km; ride time = uptime; odometer km = odometer / 1000. Imperial factor 0.621371192.
- `COMM_SET_ODOMETER`: u32 meters (also `timeout_reset`). VESC Tool: `setOdometer(round(km × 1000))`.

### IMU

`COMM_GET_IMU_DATA`: request u16 mask (full 0xFFFF); reply u16 mask, then per set bit an f32Auto: 0..2 roll/pitch/yaw (rad), 3..5 acceleration (g), 6..8 gyro (°/s), 9..11 magnetometer (µT, per `pageimu` labels), 12..15 quaternion q0..q3 (q0 = w); then u8 controller id (VESC Tool reads it only if present). `pageimu`: 500-sample plots, 3D view fed RPY in degrees, "use yaw" checkbox (yaw 0 otherwise). `Vesc3DView` after a 180° flip about Y and Z rotates yaw about Y, pitch about X, roll about Z.

### BMS

- `COMM_BMS_GET_VALUES` (no payload): f32(1e6) total V, f32(1e6) charge V, f32(1e6) current, f32(1e6) IC current, f32(1e3) Ah counter, f32(1e3) Wh counter, u8 cells, cells × f16(1e3) V, cells × u8 balancing, u8 sensors, sensors × f16(1e2) °C, f16(1e2) IC temp, f16(1e2) humidity-sensor temp, f16(1e2) humidity %, f16(1e2) highest cell temp, then optional (parse while bytes remain, as VESC Tool does): f16(1e3) SoC 0..1, f16(1e3) SoH, u8 CAN id, 4 × f32Auto (Ah charged total, Wh charged total, Ah discharged total, Wh discharged total), f16(1e-1) pressure (raw × 10 = Pa), u8 data version, NUL-terminated status string. A temperature ≤ −280 °C means no sensor. Data version 1 labels the first temps IC, Cell Min, Cell Max, Mosfet, Ambient, then T1, T2, ...
- A motor controller answers from the BMS it hears on CAN (`bms.c`); a VESC BMS answers itself.
- Set commands (the motor controller forwards them over CAN when its BMS type is VESC and the BMS was heard recently): `SET_CHARGE_ALLOWED` u8; `SET_BALANCE_OVERRIDE` u8 cell, u8 override; `RESET_COUNTERS` u8 ah, u8 wh; `FORCE_BALANCE` u8 on; `ZERO_CURRENT_OFFSET` no payload (runs in the BMS's blocking thread).
- VESC Tool UI (`pagebms`, `BMS.qml`): Balance on / off, Allow / Disable charging, Reset Ah, Reset Wh, Zero current offset (balance override is not in the UI). Cells as bars (mobile: horizontal, 3.0..4.2 V, balancing cells orange). Table: total V, cell min / max / difference, charge V, current, IC current, Ah, Wh, power (IC current × total V), SoC, SoH, highest cell temp, PCB temp (data v1: max of humidity-sensor temp, temps[0], temps[3]), humidity with its temp, pressure, the four totals, status.
- `COMM_GET_EXT_HUM_TMP` (VESC BMS firmware only): reply f32(1e2) humidity %, f32(1e2) °C. VESC Tool has no parser for it.

### GNSS, power switch, IO board

- `COMM_GET_GNSS`: request u16 mask (full 0x3FF); reply u32 mask, then: 0 latitude i64(1e16) deg, 1 longitude i64(1e16), 2 height m f32Auto, 3 speed m/s f32Auto, 4 HDOP f32Auto, 5 ms since midnight i32, 6 year i16, 7 month u8, 8 day u8, 9 age s f32Auto.
- `COMM_PSW_GET_STATUS`: request u8 by-id, i16 id or index; no reply when there is no such switch; reply i16 CAN id, i16 number of switches, f32Auto age s, f32Auto V in, f32Auto V out, f32Auto temp, u8 output on, u8 precharge on, u8 discharge on. List all by index 0 until the count.
- `COMM_PSW_SWITCH`: i16 id, u8 on, u8 plot (log the precharge curve).
- `COMM_IO_BOARD_GET_ALL`: request i16 CAN id; no reply when nothing was heard from that board; reply i16 id, then blocks until the end: type u8 1 (ADC 1-4) f32Auto age + 4 × f16(1e2) V, type 2 (ADC 5-8) the same, type 3 (digital) f32Auto age + u32 high + u32 low word, bit i = input i of 64. Firmware quirk: types 2 and 3 report the ADC 1-4 age.
- `COMM_IO_BOARD_SET_PWM`: i16 id, i16 channel, f32Auto duty 0..1. `COMM_IO_BOARD_SET_DIGITAL`: i16 id, i16 channel, u8 on.

## Next

1. Client (`core/vesc`), one file per area, extension functions like `RideStats.kt`, timeouts as constants in the file:
   - `MotorControl.kt` (shared, minimal change): `setPosition(degrees)`, `setHandbrake(amps)`, `setCurrentRelative(fraction)` through `session.motorCommand`, so they get the keepalive and the release on cancel/close. Payload builders next to the existing ones in `Requests`.
   - `Servo.kt`: `setServo(position 0..1)` via `session.send`, outside the keepalive (see the table).
   - `EmergencyStop.kt`: `emergencyStop(duration = 5.seconds)`: `motor.release()` (ends our keepalive, sends current 0 like VESC Tool), then COMM_MOTOR_ESTOP with the duration in ms clamped to 1..65535.
   - `RotorPosition.kt`: `setPositionDisplay(mode)` (enum of the 8 modes) and `rotorPositions(): Flow<Float>` from `session.incoming` filtered on COMM_ROTOR_POSITION (subscribe before sending the mode).
   - `SelectiveValues.kt`: `values(mask)` for COMM_GET_VALUES_SELECTIVE returning `RealtimeValues` with unmasked fields 0 (and the mask).
   - `SetupValues.kt`: data class + `setupValues()`, `setupValues(mask)`, `setOdometer(meters: Long)`.
   - `Imu.kt`: `ImuData` (roll/pitch/yaw rad, accel g, gyro °/s, mag µT, quaternion, controllerId) + `imu(mask = 0xFFFF)`.
   - `Bms.kt`: `BmsValues` + `bmsValues()`, `bmsSetChargeAllowed`, `bmsSetBalanceOverride(cell, on)`, `bmsResetCounters(ah, wh)`, `bmsForceBalance(on)`, `bmsZeroCurrentOffset()`, `externalHumidityTemperature()`.
   - `Gnss.kt`, `PowerSwitch.kt` (status by index/by id, list, switch), `IoBoard.kt` (get all, set PWM, set digital).
2. Simulator (`core/sim`): `ImuSim.kt` (pitch follows motor acceleration, roll sways slowly, yaw integrates from speed; quaternion from the same RPY; accel = gravity rotated into the body frame), `BmsSim.kt` (12 cells from the existing `Battery` charge with a little spread, balancing the highest cells while above 4.1 V, 4 temps, SoC = charge), `GnssSim.kt` (fixed fix that moves with distance), setup values + odometer (odometer stored, advances with absolute distance; speed from 7 pole pairs, 83 mm wheel like `RideStatsModel`), `EstopSim.kt` (release both motors, ignore motor commands for N ms on the board), position and handbrake drives, a power switch at CAN id 30 and an IO board at CAN id 40 reached through COMM_PSW_* / COMM_IO_BOARD_*.
   - Decide with the lead (or note in the report): the rules allow only dispatch lines in `SimulatedVesc.handle`, but the models need per-instance state and ESTOP has to gate `command()`. Smallest change: one `internal val` per model in `SimulatedVesc` and one guard line in `command()`; position/handbrake need two new `Drive` cases in `MotorModel.kt`.
3. Tests (`core/vesc`, `runTest` + `testTimeSource`): each parser against the sim; selective masks; ESTOP releases a running motor, the sim ignores commands for N ms and accepts them after; `setPosition`/`setHandbrake`/`setCurrentRelative` keep the motor alive past the 1 s timeout and are released by `release()`, `close()` and scope cancellation; rotor position stream starts and stops with the mode; odometer round trip.
4. UI (`app/.../ui/tools/<area>/`, registered in `Tools.kt`):
   - `control/`: arm step that says what will happen (motor spins while a button is held; secure it, keep clear), mode tabs Duty / Current / Brake / RPM / Position / Handbrake (+ relative current), value field with VESC Tool's defaults, hold-to-run buttons (FWD/REV where it applies; press = command, release or leaving the page = `release()`), optional "Keep running after release" behind a confirmation, live readouts (duty, current, ERPM, temps from `values()`), a big STOP always visible that calls `emergencyStop(5.seconds)` and disables every control with a 5 s countdown. Rotor position dial with the mode buttons.
   - `setup/`: speed, trip, odometer (with set dialog in km), Wh/km (total and instantaneous), battery %, range, Wh left, ride time, number of controllers.
   - `imu/`: Canvas attitude view (projected wireframe board + horizon line, "use yaw" toggle), RPY/accel/gyro/mag/quaternion readouts, Sparklines.
   - `bms/`: cell bars (min/max marked, balancing shown), temps with labels, SoC/SoH, current, power, counters, status; Balance on/off, charging allow/disable, reset Ah/Wh, zero current offset, each with a confirmation.
   - `gnss/`, `io/` (IO board ADC/digital readouts, PWM and digital outputs), `psw/` (list, status, on/off with confirmation), external humidity/temp on the BMS page.
5. Run `./gradlew :core:vesc:test :core:sim:test :app:assembleDebug`, commit, update this file.

## Unverified

- Everything above is read from source; nothing has been run against hardware. The BMS, GNSS, IO-board and power-switch replies depend on devices on the CAN bus that the simulator will only imitate.

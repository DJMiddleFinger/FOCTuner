# Setup wizards, motor detection and input setup: progress

Owner: the setup-wizards side session, branch `claude/feat-setup-wizards-2a0370`. Scope and rules: the shared `feature-session-rules.md`.

## Done

`./gradlew :core:vesc:test :core:sim:test :app:assembleDebug` green (63 tests in core:vesc and core:sim). Main merged at 8c70d3c (emergency stop, leases). Not run on the emulator or hardware.

Sources: `vedderb/bldc` master (`comm/commands.c`, `conf_general.c`, `motor/mcpwm_foc.c`, `imu/imu.c`) and `vedderb/vesc_tool` master (`commands.cpp`, `utility.cpp`, mobile `SetupWizardFoc/Input`, `DetectFoc*`, `DetectBldc`, `DirectionSetup`, `PpmMap`, `AdcMap`, desktop `widgets/detect*.cpp`, `detectallfocdialog.cpp`), read 2026-10-16. Checked against the gap audit, section 1 (S1-S17) and 0.2.

### Client (`core:vesc`)

- `Detection.kt`: `detectApplyAllFoc` (all six arguments, CAN detection, `FocDetectionResult` with sensors / failure / fault, brackets the run with `COMM_APP_DISABLE_OUTPUT` 180 s on the bus and re-enables it even when cancelled), `measureResistanceInductance`, `measureFluxLinkage` (openloop, with the encoder values the firmware reads along the way), `detectFocHalls`, `detectEncoder` (null without a configured encoder), `detectBldc` (signed hall result), `disableAppOutput` / `enableAppOutput`. All run in `session.blocking`; timeouts are the firmware's worst case with margin: apply-all 180 s, R/L 10 s, flux linkage 30 s, hall 25 s, encoder 90 s, BLDC 30 s (VESC Tool: 180 / 8 / 12 / 25 / 50 s).
- `DecodedInputs.kt`: `decodedPpm`, `decodedAdc`, `decodedNunchuk`. `ImuCalibration.kt`: `calibrateImu(yaw)` (10 s) and `Config.withImuCalibration`.
- `SetupConfig.kt`: `updateConfig` (read, change, write, read back), `canVescs()` (ping, then keep `HW_TYPE_VESC`), `focGains` (VESC Tool's kp = L/T, ki = R/T, gain = 1e-3/λ²), `withMotorParameters`, `withFocHallTable`, `withEncoder`, `withBldcParams`, `withBatteryCut` (VESC Tool's per-cell values). `InputMapping.kt`: `InputRange` (VESC Tool's min/max/center rule), `validPpm`, `withPpmMapping`, `withAdcMapping`.

### Simulator (`core:sim`)

- `SimulatedVesc` changes beyond dispatch lines (please review when merging): a scheduling helper (`later(delay) { }`, delivered from `received` by waiting on the collector's clock: virtual under `runTest`, real in the app), `config(motor, kind)` and `replaceConfig(motor, kind, config)` helpers, one `detection` val. `SimulatedVescTest` now uses `COMM_GET_DECODED_BALANCE` as its unsupported command, since PPM is answered.
- `DetectionSim.kt`: one blocking job per board (others dropped, like the firmware), replies after realistic virtual time (R/L 3.5 s, hall 11.8 s, encoder 35 s, BLDC 9 s, flux 4.7 s + ramp, apply-all 28 s + 2 s, IMU 4.5 s). First motor has halls, second is sensorless. Apply-all stores both motors' mcconf like `conf_general.c`, sets `can_status_msgs_r1` and sends the unsolicited appconf/mcconf before the result. Hall detection with the port not in hall mode reproduces the firmware's broken reply (id 255).
- `DecodedInputsSim.kt`: a 12 s cycle of an RC stick (1.05/1.52/1.95 ms) and two throttles, read only while the matching app is selected, mapped with the stored config.

### UI (`app/.../ui/tools/setup/`, `strings_setup.xml`, 10 entries in `Tools.kt`)

- **Motor setup (FOC)**: scan (attached controller if it's a VESC, plus CAN VESCs), optional factory defaults on all, usage and motor presets (VESC Tool's tables, override), battery (cell guess from pack voltage, optional battery current limits with VESC Tool's warning), gearing and wheel, detection across the bus with progress, elapsed time and Stop, per-controller summary, direction.
- **FOC detection** (R/L, flux linkage incl. passive at 0 A, gains with time constant, encoder from the λ run, temp-comp base from motor temperature), **hall**, **encoder**, **BLDC** (warns when coupling > 900) detection, each with Apply that writes the motor config after a confirmation.
- **Motor direction**: every controller on the bus, inverted switch, forward/reverse test (app output off 2 s, duty ramp to ±0.1 with a 300 ms lease, release in `finally`).
- **Input setup**: controller, type (PPM / NRF on UART / ADC / nunchuk), bus-wide input reset keeping CAN ids, mapping with control off, PPM capture stored when valid, the input's settings via the Settings dialog, Finish writes.
- **PPM mapping**, **ADC mapping** (standalone, live at 20 Hz, warns when the app isn't selected), **IMU calibration** (yaw input, firmware calibration, write to app config).
- Every motor-moving action has a confirmation saying what will happen; every write has one too. Stop calls `VescClient.emergencyStop()`; the UI says it is unverified whether that interrupts a running detection.

Deviations from VESC Tool, on purpose: hall and BLDC tables apply all 8 entries (mobile VESC Tool writes 0..6); BLDC hall result read signed (VESC Tool reads it unsigned, so its error texts never show); with "detect all motors on the CAN bus" off, the 6 V detection cutoff and the post-detection settings only go to the first controller (VESC Tool changes every CAN controller anyway); the ADC capture isn't stored automatically on Next (same as VESC Tool) but PPM is.

## Next

1. Emulator pass (demo mode): every tool, both themes, font scale 1.6. Check the wizard's footer with the keyboard up, long enum dialogs, and that the Stop row and notices read well.
2. `emergencyStop` only reaches the attached controller (and motors this client drives). A detection running on a selected CAN controller isn't stopped; a `VescController`-level ESTOP (forwarded) would fix it. Needs a core change (lead / vesc-client).
3. NRF pairing (`COMM_NRF_START_PAIRING`) for the input wizard's NRF path; today it tells the user to pair with VESC Tool. Belongs with the connection extras (audit section 10).
4. Full IMU wizard (audit S15: detector, profiles, gyro/accel/orientation calibration from live data) once control-sensors' `COMM_GET_IMU_DATA` is merged; it also needs `COMM_SET_APPCONF_NO_STORE` for trial configs.
5. A config write from these tools leaves the Settings screen's copy stale until it is read again; consider a shared "config changed" signal.
6. Not built (audit): first-run intro and disclaimer (S16), desktop "Setup Other Motors" wizard with its sensor-mode table (S12), FOC page helper buttons (S17: open-loop presets, measure offsets, PID position offset), `COMM_SET_BATTERY_CUT` (we use read-modify-write per controller instead, which works on all firmware).
7. Tests for the UI models need an app test source set (none yet).

## Unverified

- Nothing has run on hardware. Payloads, scales and durations come from the firmware and VESC Tool source; the simulator is written from the same reading, so a mistake would be shared.
- Whether `COMM_MOTOR_ESTOP` interrupts a detection in progress (the detection loops re-command the motor every step).
- The hall reply bug (id 255 when the port isn't hall) is from reading `commands.c`.
- Timeouts longer than VESC Tool's (R/L 10 s, flux 30 s, encoder 90 s, BLDC 30 s) are estimates from the firmware loops.
- Apply-all on a bus with several controllers: renumbering of `controller_id` and the 180 s CAN wait are only reasoned from `conf_general.c`.

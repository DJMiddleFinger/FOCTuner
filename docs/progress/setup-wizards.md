# Setup wizards, motor detection and input setup: progress

Owner: the setup-wizards side session, branch `claude/feat-setup-wizards-2a0370`. Scope and rules: the shared `feature-session-rules.md` (client in `core/vesc`, sim in `core/sim`, UI in `app/.../ui/tools/setup/`, strings in `strings_setup.xml`).

## Done

Research only. The session stopped at the usage guard (5-hour window at 90%) before writing code; nothing in the code base changed yet.

Read on 2026-10-16 from `vedderb/bldc` master (`comm/commands.c`, `conf_general.c`, `motor/mcpwm_foc.c`, `imu/imu.c`, `datatypes.h`) and `vedderb/vesc_tool` master (`commands.cpp`, `utility.cpp`, `vescinterface.cpp`, mobile `SetupWizardFoc`, `SetupWizardInput`, `SetupWizardIMU`, `DetectFocParam`, `DetectFocHall`, `DetectFocEncoder`, `DetectBldc`, `DirectionSetup`, `PpmMap`, `AdcMap`, desktop `widgets/detectfoc*.cpp`, `detectbldc.cpp`, `detectallfocdialog.cpp`). The findings below are what the build needs; re-read a source only to confirm a detail.

### Protocol

Big-endian. `f32(x, s)` is int32 `x * s`, `f32auto` is the firmware's auto-scaled float. Every `COMM_DETECT_*` and `COMM_GET_IMU_CALIBRATION` runs in the firmware's blocking thread (dropped while another blocking command runs), so wrap them in `session.blocking { }`. Their replies come from that thread through the same reply path, so CAN-forwarded requests get their reply like any other.

| Command | Request | Reply | Firmware duration | Timeout to use |
|---|---|---|---|---|
| `COMM_DETECT_APPLY_ALL_FOC` 58 | u8 detect_can, f32 max_power_loss 1e3, f32 min_current_in 1e3, f32 max_current_in 1e3, f32 openloop_rpm 1e3, f32 sl_erpm 1e3 (0 = firmware default for the last four) | i16 result | up to 180 s waiting for CAN results | 180 s (VESC Tool) |
| `COMM_DETECT_MOTOR_R_L` 25 | none | f32 r 1e6 (Ω), f32 l 1e3 (µH), f32 ld_lq_diff 1e3 (µH, missing on old fw); all 0 on fault | about 3 to 4 s | 10 s (VESC Tool 8 s) |
| `COMM_DETECT_MOTOR_FLUX_LINKAGE_OPENLOOP` 57 | f32 current 1e3, f32 erpm_per_sec 1e3, f32 duty 1e3, f32 r 1e6 (Ω), f32 l 1e8 (H) | f32 linkage 1e7 (Wb), f32 enc_offset 1e6, f32 enc_ratio 1e6, u8 enc_inverted | worst case 5 s fault wait + 1 + 0.2 + 1 + 15 s ramp + 1 + 1 + 0.5 s | 30 s (VESC Tool 12 s) |
| `COMM_DETECT_MOTOR_FLUX_LINKAGE` 26 | f32 current 1e3, f32 min_rpm 1e3, f32 duty 1e3, f32 r 1e6 | f32 linkage 1e7 | legacy, BLDC based; VESC Tool's UI no longer uses it | 30 s |
| `COMM_DETECT_ENCODER` 27 | f32 current 1e3 | f32 offset 1e6, f32 ratio 1e6, u8 inverted; offset 1001 = no encoder configured | tens of seconds, grows with the ratio (offset sweep 3 x ratio steps of 0.5 s, twice; index search up to 15 s) | 90 s (VESC Tool 50 s) |
| `COMM_DETECT_HALL_FOC` 28 | f32 current 1e3 | u8[8] table (0..199, 255 = invalid), u8 res (0 = success) | 1 s ramp + 2 x 3 x 360 x 5 ms, about 12 s | 25 s (VESC Tool) |
| `COMM_DETECT_MOTOR_PARAM` 24 (BLDC) | f32 current 1e3, f32 min_rpm 1e3 (ERPM), f32 low_duty 1e3 | f32 cycle_int_limit 1e3, f32 coupling_k 1e3, i8[8] hall_table, i8 hall_res | up to about 25 s | 30 s |
| `COMM_GET_IMU_CALIBRATION` 90 | f32 yaw 1e3 (deg) | 9 x f32 1e6: rot_roll, rot_pitch, rot_yaw, accel_offsets 0..2, gyro_offsets 0..2 | about 4.5 s, board level and still | 10 s |
| `COMM_GET_DECODED_PPM` 31 | none | i32 value x 1e6 (−1..1), i32 last pulse length in ms x 1e6 | not blocking | default |
| `COMM_GET_DECODED_ADC` 32 | none | i32 level x 1e6, voltage x 1e6, level2 x 1e6, voltage2 x 1e6 | not blocking | default |
| `COMM_GET_DECODED_CHUK` 33 | none | i32 y x 1e6 | not blocking | default |
| `COMM_APP_DISABLE_OUTPUT` 63 | u8 fwd_can, i32 ms (0 enables again, −1 forever) | none | | send only |

Details that matter:

- **Apply all FOC result codes**: 0 sensorless, 1 hall, 2 AS5047 encoder (single-motor hardware only); −1 persistent fault; −10 flux linkage failed; −50 CAN detection timed out; −51 CAN detection failed; −100 + fault code for a fault during detection (VESC Tool's `Utility::detectAllFoc` has a reason string for every fault, reuse our `fault_*` strings).
- **What apply-all writes** (and stores in flash, both motors of a dual board): `l_in_current_min/max`, `foc_openloop_rpm`, `foc_sl_erpm` from the arguments (firmware defaults for 0), then `l_current_max = i_max`, `l_current_min = −i_max`, `l_abs_current_max = 1.5 i_max` (hardware-truncated), `motor_type` FOC, `foc_motor_r`, `foc_motor_l`, `foc_motor_ld_lq_diff`, `foc_motor_flux_linkage`, encoder offset/ratio/inverted when found, `foc_temp_comp_base_temp` and `foc_temp_comp = true` when the motor temp reads above −10 °C, `foc_current_kp = L / 1 ms`, `foc_current_ki = R / 1 ms`, `foc_observer_gain = 1e-3 / λ² * 1e6`, sensor mode from the sensor step. `i_max = sqrt(max_power_loss / R / 1.5)`. Hall detection runs at `i_max / 3`. On success it also stores the appconf with `controller_id` bumped by 2 if a CAN controller has the same id and `can_status_msgs_r1 = 0b1111`, sending COMM_GET_APPCONF (only when changed) and COMM_GET_MCCONF unsolicited (1 s apart) before the result. Simplest client: read both configs after the result instead of parsing those.
- VESC Tool brackets apply-all with `COMM_APP_DISABLE_OUTPUT` (fwd_can, 180000 ms), a 100 ms pause, and re-enables with 0 afterwards. The max-power-loss halving it does for firmware < 5.03 doesn't apply to 7.x.
- **Hall FOC firmware bug**: when `m_sensor_port_mode` is not HALL the firmware's reply `memset`s over its own id byte, so the packet has id 255 and the request times out. Check the sensor port mode before sending and say so instead.
- **BLDC hall_res** is signed: 0 ok, −1 failed, −2 WS2811 enabled, −3 encoder enabled. VESC Tool reads it unsigned (bug). Both results 0 = detection failed.
- Mobile VESC Tool applies only `foc_hall_table__0..6` and `hall_table__0..6` (loop bug); desktop applies all 8. Apply all 8.
- Openloop flux reply: encoder fields are valid only when both are ≥ 0 (desktop `mEncoderValuesOk`). Current 0 means passive measurement: the motor must be spun externally.
- `COMM_SET_MCCONF_TEMP(_SETUP)` 48/49 is not used by any wizard (they write the full mcconf). Layout if ever needed: u8 store, u8 forward_can, u8 ack, u8 divide_by_controllers, f32auto current_min_scale, current_max_scale, erpm min, max (m/s for `_SETUP`), duty min, max, watt min, max, optional in_current min, max; empty ack of the same id when ack is set.
- **Stop**: a running detection can't be aborted by a command; the firmware loop only exits on a fault. Before building the Stop button, read the case just above the blocking list in `commands.c` (around line 1680: `mc_interface_ignore_input_both(ms); mc_interface_release_motor_override_both();`, very likely `COMM_MOTOR_ESTOP` 159) and `mc_interface_release_motor_override` to see whether it interrupts a detection. The vesc-client progress file also lists `emergencyStop(ms)` as its next item; coordinate so it isn't built twice.

### VESC Tool flows to copy

**FOC setup wizard** (mobile `SetupWizardFoc.qml`; desktop `DetectAllFocDialog` is the same flow):

0. On open: list CAN VESCs (`pingCan`, then firmware info, keep `HW_TYPE_VESC`). Ask "restore this VESC and all VESCs on the CAN bus to default settings?"; yes reads the default mcconf and appconf of each and writes them.
1. Usage presets (duty_start, fault_stop_ms, bms.limit_mode, cautious battery cut, HFI start ok): Generic (1.0, 500, 3, yes, no), E-Skate (0.85, 50, 3, yes, yes), Balance (1.0, 50, 0, no, no), Propeller (1.0, 500, 3, yes, no). "Override (advanced)" edits `l_duty_start`, `m_fault_stop_time_ms`, `bms.limit_mode`.
2. Motor presets (max power loss W, openloop ERPM, sensorless ERPM, HFI start ok, poles): Mini outrunner ~75 g (20, 1400, 4000, no, 14); Small outrunner ~200 g (50, 1400, 4000, no, 14); Medium outrunner ~750 g (120, 700, 4000, yes, 14); Large outrunner ~2000 g (400, 700, 4000, no, 14); Small inrunner ~200 g (50, 1400, 4000, no, 2); Medium inrunner ~750 g (140, 1400, 4000, no, 4); Large inrunner ~2000 g (400, 1000, 4000, no, 4); E-bike DD hub motor ~6 kg (150, 300, 2000, no, 46); EDF inrunner small ~200 g (110, 1400, 4000, no, 6). Override ranges: loss 0..5000 W, ERPMs 0..50000, poles 2..512 step 2. Leaving the page warns that a motor chosen much larger than the real one can be destroyed by detection (Yes/Cancel), then guesses cells from `v_in`: 39..51 V → 12, else `ceil(v_in / 4.2)`.
3. Battery: `si_battery_type`, `si_battery_cells`, `si_battery_ah`; advanced battery current regen (≤ 0) and max (≥ 0), 0 = defaults. Leaving without the advanced limits warns that battery current is then limited only by the cutoff voltage, and that limits are per VESC.
4. Setup: direct drive or motor pulley (13) / wheel pulley (36), 1..999 → `si_gear_ratio = wheel / motor`; `si_wheel_diameter`; under "only change if needed": `si_motor_poles`, `m_motor_temp_sens_type`, `m_ntc_motor_beta`. "Run Detection" confirms "This is going to spin up all motors. Make sure that nothing is in the way" with "Detect all motors over CAN bus" (on by default).
   Run: write the local mcconf with the edits; set `l_battery_cut_start/end` to 6 V on this and every CAN VESC (read, change, write each); copy `m_motor_temp_sens_type` and `m_ntc_motor_beta` to the CAN VESCs; apply-all. On success: battery cut from type and cells on all (type 0 Li-ion: cautious 3.4/3.0 V per cell, else 2.7/2.5; type 1 LiFePO4: 2.9/2.6 or 2.5/2.2; other types unchanged), then copy `l_duty_start` to all, plus `foc_openloop_rpm / 2` if `foc_temp_comp` is on, plus `foc_sensor_mode = 4` (HFI start) if sensorless and both presets allow HFI start. Summary per controller: CAN id, `l_current_max`, R mΩ, L µH, Lq−Ld µH, λ mWb, temp comp, sensors.
5. Direction (also standalone, `DirectionSetup.qml`): one row per VESC (this one and CAN), "Inverted" switch = `m_invert_direction` (read, change, write), FWD/REV test: `COMM_APP_DISABLE_OUTPUT` 2000 ms with fwd_can, 100 ms pause, duty ramps to ±0.1 over the first second and holds for the second, then current 0. Use `MotorControl.setDuty` (keepalive) and `release()`.

**Standalone detection** (mobile dialogs plus the desktop extras):

- FOC parameters: I = `l_current_max / 3`, D 0.3, ω 2000 ERPM/s, T 1000 µs. R/L warns "noises but no rotation"; λ warns that the motor spins (or, at I = 0, must be driven externally). kp = L / T, ki = R / T, gain = 1e-3 / λ². Apply writes `foc_motor_r`, `foc_motor_l`, `foc_motor_ld_lq_diff`, `foc_motor_flux_linkage`, `foc_current_kp`, `foc_current_ki`, `foc_observer_gain` (gain x 1e6), `foc_temp_comp_base_temp` from the motor temperature when above 1 °C, and the encoder values from the λ reply when valid. A value counts as measured when > 1e-10.
- Hall (FOC): I 10 A, "turns the motor slowly"; apply `foc_hall_table__0..7`.
- Encoder: I 10 A; offset > 1000 → "encoder support is not enabled in the motor settings"; apply `foc_encoder_offset`, `foc_encoder_ratio`, `foc_encoder_inverted`.
- BLDC: I 5 A, D 0.05, ω 450 ERPM; shows integrator limit, coupling, hall table or the hall_res reason; apply `sl_bemf_coupling_k`, `sl_cycle_int_limit`, `hall_table__0..7` when hall_res is 0.

**Input wizard** (`SetupWizardInput.qml`): Connection → Type → Mapping → Setup.

- Connection: which VESC the receiver is wired to (this one or a CAN VESC). Next targets it and resets the input of this and every CAN VESC: read appconf, keep `controller_id`, `can_status_msgs_r1`, `can_status_msgs_r2`, read the default appconf, put those back, write.
- Type: PPM receiver (`app_to_use` 4, PPM and UART), NRF remote (3, UART, needs pairing), ADC throttle (5, ADC and UART), Nyko Kama nunchuk (6, "will disable UART").
- Entering Mapping: `app_ppm_conf.ctrl_type` 0, `app_adc_conf.ctrl_type` 0, `app_to_use`, `multi_esc` true in ppm/adc/chuk, write. PPM shows the PPM map, ADC the ADC map plus `voltage_inverted` and `voltage2_inverted`; NRF and nunchuk skip to Setup (NRF starts pairing, `COMM_NRF_START_PAIRING`, check which session owns it). The maps reset 1.5 s after entering.
- Maps (also standalone tools): poll every 50 ms. PPM: min and max pulse length; center = current value when inside the middle half of the range, else the midpoint; valid when max − min > 0.4 ms; apply `app_ppm_conf.pulse_start/end/center`. ADC: same for voltage, plus min/max of voltage 2; apply `app_adc_conf.voltage_start/end/center`, `voltage2_start/end`. Live bars: PPM −1..1, ADC 0..1 (both channels).
- Entering Setup: apply the PPM map if valid, set ppm ctrl_type 3 and adc ctrl_type 1, write, then show editors. PPM: `ctrl_type`, `median_filter`, `safe_start`, `ramp_time_pos`, `ramp_time_neg`, `pid_max_erpm`, `max_erpm_for_dir`, `smart_rev_max_duty`, `smart_rev_ramp_time`, and under "Multiple VESCs over CAN bus" `tc`, `tc_max_diff`. Nunchuk/NRF (`app_chuk_conf.`): `ctrl_type`, `ramp_time_pos`, `ramp_time_neg`, `stick_erpm_per_s_in_cc`, `hyst`, `use_smart_rev`, `smart_rev_max_duty`, `smart_rev_ramp_time`, `tc`, `tc_max_diff`. ADC: `ctrl_type`, `use_filter`, `safe_start`, `cc_button_inverted`, `rev_button_inverted`, `ramp_time_pos`, `ramp_time_neg`, `tc`, `tc_max_diff`. Finish writes the appconf. Going back into Mapping sets both ctrl_types to 0 and writes again.

**IMU**: VESC Tool's own `SetupWizardIMU` works from `COMM_GET_IMU_DATA` (owned by the motor-control/IMU/BMS session). Here only the firmware calibration: level the board, enter yaw, run `COMM_GET_IMU_CALIBRATION`, show the 9 values, apply to `imu_conf.rot_roll/rot_pitch/rot_yaw`, `imu_conf.accel_offsets__0..2`, `imu_conf.gyro_offsets__0..2` and write the appconf. VESC Tool itself never sends this command; only package QML does.

## Next

1. `core/vesc/.../Detection.kt`: result types and `VescController` extensions for every row of the table, timeouts as constants with the reason from the table, `session.blocking { session.request(forwarded(payload), id, timeout) { ... } }`; `disableAppOutput(ms, fwdCan)` with `session.send`. `ImuCalibration.kt` and `DecodedInputs.kt` for the rest.
2. Simulator `core/sim/.../DetectionSim.kt`: replies after a virtual-time delay. `SimulatedVesc` answers synchronously and only advances time on requests, so it needs a small scheduling helper (due time + action, delivered from `received` by waiting in the collector's context, which is virtual under `runTest` and real in the app). That is a change beyond the one dispatch line per command: keep it minimal and report it to the lead. Apply-all computes R, L, λ from the motor model's constants and stores mcconf (and appconf when the id changes) through the existing config store; hall replies like the firmware (id 255 when the sensor port isn't hall); encoder returns offset 1001 unless an encoder port mode is set; decoded PPM/ADC follow a deterministic sweep over uptime so mapping tests see min, max and center.
3. Tests in `core/vesc` for each function against the simulator (virtual time, check the delay is honoured and the timeout fires when the sim stays silent), plus apply-all on the CAN motor.
4. UI under `ui/tools/setup/`: FOC setup wizard, FOC detection, hall, encoder, BLDC, direction, input wizard, PPM map, ADC map, IMU calibration; `Tools.kt` entries; `strings_setup.xml`. Every spin gets the confirmation ("the motor will spin, wheel off the ground") and a Stop (see the Stop note above).
5. Build and test (`./gradlew :core:vesc:test :core:sim:test :app:assembleDebug`), update this file and PARITY section 2, commit.

## Unverified

- Everything: nothing is implemented, and the findings come from reading source, not hardware. The timeouts chosen above are estimates from the firmware loops.

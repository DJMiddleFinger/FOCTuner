# Analysis tools (wave 3): progress

Branch `claude/amazing-franklin-p8khnz`. Roadmap row `analysis`. Sources: vesc_tool master `ed54d60` (`pages/pagesampleddata.cpp`, `pages/pageexperiments.cpp`, `pages/pagemotorcomparison.{h,cpp}`, `pages/pagecananalyzer.cpp`, `commands.cpp`), bldc master (`comm/commands.c` COMM_SAMPLE_PRINT / COMM_CAN_FWD_FRAME, `motor/mc_interface.c` `mc_interface_sample_print_data`, `send_sample_block`).

## Done

`./gradlew test :app:assembleDebug :app:lintDebug` green. No emulator or controller run.

- **Sampled data** (`core/vesc` `SampledData.kt`, Tools > Operations): COMM_SAMPLE_PRINT in the modes VESC Tool offers (now, from start, around start, before a fault, last again), 1 to 1000 samples, decimation 1 to 255; samples are matched by the index the firmware sends since 5.x and lost ones are asked for one by one (`DEBUG_SAMPLING_SEND_SINGLE_SAMPLE`). Plots phase currents with the total, and phase voltages with the input voltage, over time from the reported switching frequency; CSV export. Raw (ADC count) sampling is in the client but not offered in the UI.
- **Experiments** (`Experiment.kt`, Tools > Operations): duty, current or ERPM steps with a hold time; every command carries a lease; realtime values sampled every 100 ms; the motor is released when the plan ends, on failure, on Stop and when the page is left. Unlike VESC Tool the last step (the "to" value) is held too. Plots current, power, speed, duty, temperatures, voltage; CSV export. Counts as a motor-driving tool (pauses the demo ride).
- **Motor comparison** (`MotorComparison.kt`, Tools > Operations): VESC Tool's PMSM model (`MotorData::update`: saliency, MTPA, field weakening, temperature rise, gearing and its efficiency, motor count, no-load torque) and its torque, speed, constant-power and voltage-limited sweeps. The screen compares the connected motor (A) with a backup's motor or the same motor (B), each with its own gearing, plotting efficiency, losses, current, needed voltage or input power.
- **CAN analyzer** (Tools > Hardware): switches the connected controller to Comm Bridge without storing it, lists relayed frames (4000 max, newest first, pause, clear), sends typed frames (hex id, standard or extended, up to 8 hex bytes) and restores the previous mode on stop or when the page is left.
- **Simulator**: `SamplingSim` (balanced three-phase samples from the motor state at 30 kHz, start trigger waits for a drive command, single-sample resend, droppable indices for tests). The CAN bridge simulation came with connectivity (`RadioSim`).
- Tests: `SampledDataTest` (2), `ExperimentTest` (2), `MotorComparisonTest` (5, checked against hand calculations), `AnalysisFormatsTest` (2, app).

## Next

1. Emulator pass in demo mode: record samples while Control drives the motor, run a short duty sweep, compare the motor with a backup, start the CAN analyzer (the sim relays a status frame every 100 ms and echoes 0x123).
2. Hardware: sample at start-up on a real motor and compare with VESC Tool's plot; check the 1000-sample transfer time over BLE.
3. VESC Tool extras not done: FIR filtering and FFT of samples, Victron meter input in Experiments, the QML scripted tests and the propeller sweep of Motor comparison, CAN analyzer log saving.
4. Experiments stop on rotation because leaving the page stops them; keep the run in the model if that annoys.

## Unverified

- Sample scaling on real hardware (the firmware scales currents and voltages per board).
- How the firmware paces 1000 sample packets over BLE: losses are recovered one by one, which is slow if many drop.

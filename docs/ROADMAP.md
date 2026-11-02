# Roadmap: everything VESC Tool does better, and what it should do better

Started 2026-11-02, after the app was tested on real hardware. Sources: the comparison of VESC Tool and FOCTuner, and the list of VESC Tool improvements, from the same day.

## Decisions (user, 2026-11-02)

- Sessions commit on their own feature branch at every green step; the lead merges verified branches into `main`. Nothing is pushed or released without asking.
- Firmware downloads come from the VESC Project archive (`home.vedder.se`, as VESC Tool does), checked before flashing: hardware name inside the image, CRC, size.
- Package screens are native: package settings through the settings editors, plus a native Refloat screen. No embedded QML runtime.

## Not doable inside an Android app

- Desktop and iOS versions, Bluetooth on Windows.
- Vendor manuals and tutorials written against VESC Tool.
- Protocol fixes that need firmware changes in `vedderb/bldc`: a sender id on CAN-forwarded replies, an end marker for terminal output, a "busy" reply instead of silently dropped commands, a detection abort, an explicit error for a config write with the wrong layout, firmware-served motor/app definitions. The app keeps working around these; they can go upstream as PRs.

## Wave 0: integrate (lead)

Merge `config-tools`, `connection-prefs` and `packages-custom` into `main` and fit them to the VESC Tool navigation.

Status 2026-11-02: `config-tools` and `packages-custom` are merged (`dd05693`, 235 tests green). `connection-prefs` is not: merging it conflicts in `app/build.gradle.kts`, `ui/FocApp.kt` (recent devices on the connect route vs the new tab navigation), `ui/session/SessionViewModel.kt` and `ui/tools/Tools.kt` (its new tools vs the Start-page sections). Resume there, then start wave 1.

## Wave 1: safety and data

| Session | Scope |
|---|---|
| `config-safety` | Automatic backup before every write, with history and one-tap undo. VESC Tool XML backup and restore, all CAN controllers. Profiles including battery settings. Multi-settings across CAN. Warnings for values that are dangerous together (battery cutoff vs cell count, currents vs hardware limits, temperature start vs end, ...). Search across all settings. Sliders keep their range while dragging. |
| `logging` | Download logs stored on the controller (`COMM_LOG_*`, `COMM_FILE_*`) with progress. Background ride logging with phone GPS (foreground service). Log browser and analysis: plots and the GPS track. CSV/GPX export. Lifetime odometer and trip history. |
| `firmware-dl` | Firmware archive download with cache and pre-flash checks. BMS and ESP32 images. Resume an interrupted upload after the link drops. Recovery guidance when only the bootloader is left. Firmware changelog. Pause polling and block navigation while uploading. |

## Wave 2: reach

| Session | Scope |
|---|---|
| `packages` | Package store (vesc_pkg index), install and uninstall, package settings pages through the settings engine, a native Refloat screen. QML-only screens say what they would show. |
| `definitions-help` | Bundle definitions for firmware 3.55 to 6.02. For firmware the app doesn't bundle, fetch VESC Tool's definitions from GitHub and check them against the controller's signature. Plain-language help and typical values for the most used settings. |
| `connectivity` | VESC Remote quick pair (NRF). Bluetooth module setup (name, PIN). TCP bridge to desktop VESC Tool and TCP hub. Automatic reconnect. Connection diagnostics (permissions, Bluetooth off, app port set to ADC). A "release motor" stop next to the emergency stop. |

## Wave 3: specialist tools

| Session | Scope |
|---|---|
| `analysis` | Sampled data (scope), experiments and plots (`COMM_PLOT_*`), motor comparison, CAN analyzer. |
| `programmers` | SWD programmer (`COMM_BM_*`), ESP32 / VESC Express programmer, display tool. |

## How every session works

Its own worktree branch off `main`; commits at every green step; `docs/progress/<name>.md` with Done / Next / Unverified; stops at 75% of the 5-hour usage window on a green build. The lead verifies, merges into `main` and reports.

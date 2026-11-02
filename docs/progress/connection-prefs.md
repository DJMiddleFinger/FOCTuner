# Connection extras, preferences, About: progress

Branch `claude/feat-connection-prefs-af161b`. Scope: audit sections 10 (X1-X8, N1-N4), 12 (M1, M2) and S16.
Stopped at the usage guard (5-hour window at 90%) after the first commit; everything below "Next" is not started.

## Done

`./gradlew :core:vesc:test :core:sim:test :app:assembleDebug :app:testDebugUnitTest` green (6 new JVM tests in `app/src/test/.../prefs/RecentDevicesTest.kt`). Nothing has run on the emulator or a phone.

- **Preferences store** (`app/foctuner/prefs/`): Jetpack DataStore 1.1.1 (`gradle/libs.versions.toml`, `app/build.gradle.kts`), one file `app_preferences`. `AppPreferences` (units, STOP lockout seconds, overview rate, console lines, keep screen on, scan CAN on connect, reselect last CAN, accepted intro version), `PreferencesStore` (flows + edits; IOException on read falls back to defaults), `RecentDevices` and `CanTargets` (pure list logic and a tab/newline text encoding with escaping, unit-tested).
- **Preferences screen** (M1) `ui/prefs/PreferencesScreen.kt`, `PreferencesViewModel` (activity-scoped; `appPreferences()` reads them from any composable). Wired:
  - Units → ride dashboard (its own unit switch now writes the preference; `DistanceUnit` replaced by `prefs.Units`) and statistics speed.
  - STOP lockout (default 5 s, 0 = release only, as VESC Tool's `estop_ms`) → `ControlModel.stop(lockout)` → `VescClient.emergencyStop`.
  - Overview rate (2/5/10/20/50 Hz, default 10) → `SessionViewModel.poll`; trend buffer is 60 s at the chosen rate.
  - LispBM console lines (default 5000, VESC Tool's `replMaxLines`) → `LispModel.maxLines`.
  - Keep screen on while connected (default on, VESC Tool mobile) → `view.keepScreenOn` in `FocApp`.
  - Scan CAN on connect (default on, N1) and reselect the last CAN controller (default on, VESC Tool 6.05 `reconnectLastCan`): `SessionViewModel` stores attached UUID → (CAN id, CAN UUID) on every selection; on connect it asks that id for COMM_FW_VERSION and selects it if the UUID matches.
- **Recent and pinned devices** (ui progress Next 1): `ConnectViewModel` persists every successful connection (not USB: its device path changes per plug), pin toggle wired, long-press removes with a confirmation, a recent entry not seen by the scan reconnects from the stored kind/id. Recent rows use their own LazyColumn keys so a device in both lists doesn't crash.
- **About** (M2) `ui/about/AboutScreen.kt`: version + git commit (`BuildConfig.GIT_COMMIT` from `git rev-parse` at configure time), GPL-3.0-or-later notice with the full text (`assets/licenses/GPL-3.0.txt`, a copy of LICENSE), source link, "Based on VESC Tool by Benjamin Vedder" with upstream commit f1e15cb and the bldc repo, not-affiliated/trademark note, no-warranty and safety text, bundled software with licence texts (VESC Tool definitions/bootloaders GPL, LispBM examples GPL notice, IBM Plex OFL, AndroidX/Kotlin/DataStore Apache-2.0 with `assets/licenses/Apache-2.0.txt`).
- **First-run notice** (S16) `ui/about/IntroScreen.kt`, own words: purpose, safety, no warranty, not affiliated. Shown until accepted and again when `INTRO_VERSION` is raised; Quit finishes the activity.
- Navigation: connect screen has an "App" section (Preferences, About) opening full-screen pages (`ui/about/AppPage.kt`); the Tools tab lists both at the end of the System section.
- Shared-file changes, all minimal: `FocApp.kt`, `ConnectScreen.kt` (+3 parameters), `DeviceRow.kt` (`onLongClick`), `ConnectViewModel.kt`, `SessionViewModel.kt` (`connect(onConnected)`, poll rate, CAN reselect, scan pref), `Tools.kt` (2 entries), `ControlModel/ControlTool`, `LispModel/LispTool`, `RideModel/RideTool`, `StatsTool`. Hand-over note for siblings: `connection-prefs-notes.md` in the shared notes folder.

## Next (in order)

Payloads below are from `vedderb/bldc` `comm/commands.c` and VESC Tool f1e15cb (`commands.cpp`, `mobile/BleSetupDialog.qml`, `mobile/NrfPair.qml`, `mobile/StartPage.qml`, `pages/pagecananalyzer.cpp`, `utility.cpp`); local copies in `/private/tmp/claude-501/-Users-stevehardman-FOCTuner/86bd5744-6724-400a-bbda-993ccbe6ca55/scratchpad/audit/{src,bldc}` while they last.

1. **Client** `core/vesc/.../BleModule.kt`, `NrfPairing.kt`, `CanBus.kt` (extension functions, RideStats pattern):
   - `COMM_SET_BLE_NAME` (87) `[str name]`, `COMM_SET_BLE_PIN` (88) `[str pin]`: no reply, `session.send`. Firmware passes them to the nRF UART module (`commands_send_packet_nrf`). VESC Tool: name max 25 chars, under 2 chars = module default name; PIN digits only, left-padded with zeros to 6, empty = PIN off; sends name then PIN, then "Apply && Reset NRF". Offer only when `FirmwareInfo.nrfFlags` bit 0 (name) and bit 1 (PIN) are both set (else VESC Tool shows the old pairing dialog, X3).
   - `COMM_NRF_START_PAIRING` (37) `[i32 ms]`: immediate reply `[i8 0 = STARTED]`, later `1 = OK` or `2 = FAIL` (window ran out). VESC Tool mobile window 1-30 s, default 10 s; if neither arrives within window + 2 s it says the hardware probably can't pair a VESC Remote. Subscribe to `session.incoming` before sending; replies through CAN carry no sender.
   - `COMM_CAN_UPDATE_BAUD_ALL` (158) `[i16 kbits][i16 delay ms = 1000]`, reply `[u8 ok]`, 4 s timeout, runs in the firmware's blocking thread (wrap in `session.blocking`). Valid kbits: 10, 20, 50, 75, 100, 125, 250, 500, 1000 (`comm_can_kbits_to_baud`). Firmware broadcasts the change 10 times 50 ms apart, switches after the delay and stores `can_baud_rate`. Firmware 6.06+. Re-read appconf 1 s later.
   - `COMM_SET_CAN_MODE` (89) `[u8 store][u8 ack][u8 mode]`, reply (if ack) is the bare id. Modes: 0 VESC, 1 UAVCAN, 2 Comm Bridge, 3 Unused, 4 VESC+UAVCAN. VESC Tool never sends it (it edits `can_mode` in appconf); anything but VESC/VESC+UAVCAN cuts CAN forwarding to and through that controller.
2. **Simulator** `core/sim/.../RadioSim.kt`, `CanBusSim.kt`: store BLE name/PIN, pairing reply STARTED then `later(…)` OK; baud reply ok for valid kbits and update appconf `can_baud_rate` via `replaceConfig`; CAN mode updates `can_mode` and acks. One dispatch line each in `SimulatedVesc.handle`. Consider reporting NRF flags 3 in the sim's COMM_FW_VERSION so the BLE tool is reachable in demo mode (one byte; needs the lead's OK, the rules allow only dispatch lines there).
3. **Tests** in `core/vesc` against the sim with virtual time: name/PIN payloads, PIN padding, pairing OK/FAIL/no-answer, baud ok/invalid/timeout, CAN mode ack.
4. **UI** with confirmations that say what happens: `ui/tools/radio/` (Bluetooth module: name + PIN, warn that the module restarts and a Bluetooth link drops; Remote pairing: window choice, countdown, result and VESC Tool's help in own words), `ui/tools/canbus/` (current `can_mode`/`can_baud_rate` from appconf via `baseConfig` + `readConfig`; baud change with VESC Tool's warning that the bus may be unusable until reconfigured by hand; mode change with the forwarding warning). Strings `strings_radio.xml` / `strings_canbus.xml`, prefixes `radio_` / `canbus_`. Register in `Tools.kt` (pairing in Setup, the other two in Hardware).
5. N2 CAN frame analyzer (`COMM_CAN_FWD_FRAME` 85: `[u32 id][i8 extended][data ≤ 8]`, frames pushed back with the same id; needs `can_mode` = Comm Bridge; table capped at 4000 rows).
6. X3 BLE pairing list: at least explain the disconnect when a paired controller (`FirmwareInfo.pairingDone`) refuses this phone.
7. X8: retry an unintended BLE disconnect every 1 s up to 5 times; per-address names. X10 one-tap reconnect to the last device (the recent list covers most of it).
8. X4 phone TCP bridge (server on 65102 + 1 s UDP beacon `name::ip::port` to 65109; needs a foreground service) and X5 TCP hub client (`VESCTOOL:<id>:<password>\n\0` login, cleartext: warn; keep the password in Keystore-backed storage). Not small: separate sessions.
9. The setup wizards' STOP (`ui/tools/setup/SetupSupport.kt`) still uses the client's default 5 s; pass `appPreferences().stopDuration` there too.
10. Lead decisions: Preferences/About sit in the Tools "System" section titled "Scripts and firmware"; rename the section or add an "App" section. Siblings that bundle new third-party files should add a row to `ThirdParty` in `AboutScreen.kt`.

## Unverified

- No emulator or device run: first-run notice → connect screen flow, Quit, recent list persistence across restarts, long-press removal, keep-screen-on, About links and licence reflow (GPL text has indented headings that become plain paragraphs).
- Reconnecting a BLE recent entry that wasn't seen by the current scan (`Links.open` with only the stored MAC) and a TCP entry whose address changed.
- CAN reselect on a real bus: a stale target id that now belongs to a different controller is rejected by the UUID check, but a target that doesn't answer costs one request timeout (1 s) before the CAN scan starts.
- `BuildConfig.GIT_COMMIT` is computed at configuration time; a build from a source tarball shows only the version.

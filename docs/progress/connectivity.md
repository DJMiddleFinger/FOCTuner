# Connectivity (wave 2): progress

Branch `claude/amazing-franklin-p8khnz`. Roadmap row `connectivity`. Sources: vesc_tool master `ed54d60` (`mobile/BleSetupDialog.qml`, `mobile/NrfPair.qml`, `mobile/TcpBox.qml`, `mobile/TcpHubBox.qml`, `tcpserversimple.cpp`, `tcphub.cpp`, `vescinterface.cpp`), bldc master (`comm/commands.c`, `comm/comm_can.c`, `applications/app.c`, `datatypes.h`).

## Done

`./gradlew test :app:assembleDebug :app:lintDebug` green (lint: warnings only). Nothing has run on an emulator or a phone: this session had no emulator (no KVM).

- **Client** (`core/vesc`):
  - `Radio.kt`: `setBleName` (max 25 chars, under 2 = module default), `setBlePin` (digits, zero-padded to 6, empty = off), `FirmwareInfo.bleModuleConfigurable` (NRF flags bits 0 and 1), `pairNrfRemote(window)` -> `Paired` / `TimedOut` / `NotSupported` (no "started" within the request timeout) / `NoAnswer` (started, then nothing within window + 2 s), `uartPortServed(app_to_use)` (UART, PPM+UART, ADC+UART only, per `app.c`).
  - `CanBus.kt`: `CanBaud` (kbit/s <-> appconf value), `CanMode`, `updateCanBaudAll` (blocking thread, 4 s timeout), `setCanMode(store, ack)`, `CanFrame`, `sendCanFrame`, `canFrames` flow (COMM_CAN_FWD_FRAME; needs Comm Bridge mode).
  - `VescClient.releaseAll()`: releases every motor this session drives plus the attached one, no lockout.
  - `PacketBridge.kt`: `VescClient.bridge(peer)` relays packets both ways until the peer closes (VESC Tool's TCP server behaviour, our own replies go to the peer too).
- **Transport**: `TcpTransport.accept(server)`, `TcpTransport.registerWithHub(host, id, password)` (`VESC:<id>:<pass>\n`, port 65101), `TcpBeacon.format` / `broadcast`.
- **Simulator** (`RadioSim.kt`): BLE name/PIN stored, pairing "started" then "paired" 3 s in (or "failed" for a shorter window), baud change on both motors, CAN mode, Comm Bridge relaying a CAN status 1 frame every 100 ms and an echo device at 0x123 -> 0x124. NRF flags now 3 so the Bluetooth page is usable in demo mode.
- **UI**:
  - Tools, Configuration: **Bluetooth and remote** (`ui/tools/radio/`): UART warning from `app_to_use`, name + PIN with confirmation saying the module restarts and the link drops, pairing window 5/10/20/30 s with a countdown and the four outcomes in plain words.
  - Tools, Hardware: **CAN bus** (`ui/tools/canbus/`): current rate and mode from appconf, change the rate for the whole bus (confirmation lists what drops off), change the mode (warns when it stops VESC forwarding).
  - Tools, Operations: **Share with VESC Tool** (`ui/tools/bridge/`): TCP server (default 65102) with the 1 s UDP beacon VESC Tool lists, or hub registration (default veschub.vedder.se:65101) with a cleartext warning; packet counters. Host, port and id are remembered; the password isn't stored.
  - Control: RELEASE beside STOP (fires on finger down, no lockout).
  - Session: a link that drops outside a firmware update is reopened for 6 s, 1 s apart (`UpdateStage.LinkLost`, "Connection lost" notice, Disconnect stays available). Demo mode still ends at once.
- Tests: `ConnectivityTest` (8: PIN/name rules, UART rule, name/PIN sent, pairing ok/timeout, not supported / no answer on a scripted transport, baud on both motors, CAN mode + frames + echo + quiet after leaving bridge mode, releaseAll without lockout), `BridgeTest` (2: a second client reaches the sim and its CAN peer through a loopback TCP bridge while the first keeps working; hub login line and argument checks).

## Next

1. Emulator pass: the three new tools in demo mode (light and dark), RELEASE on Control, a forced link drop to see the reconnect notice and its end state.
2. Hardware: rename a VESC with the built-in nRF51 module and pair a VESC Remote; bridge to desktop VESC Tool over WiFi and run a config read/write and a detection through it.
3. Keep the bridge alive in the background (the ride logging foreground service is the model) if users bridge while the screen is off.
4. Android 17 (`targetSdk` 37) needs `ACCESS_LOCAL_NETWORK` for the server and the beacon (see links.md Next 4).
5. X3: explain the disconnect when a paired controller refuses this phone (`FirmwareInfo.pairingDone`).

## Unverified

- The beacon goes to 255.255.255.255; some WiFi networks and Android versions drop limited broadcasts, VESC Tool sends to `QHostAddress::Broadcast` the same way.
- Whether the firmware forwards COMM_NRF_START_PAIRING replies through CAN with the right timing (they carry no sender).
- Auto reconnect over BLE without a fresh scan: `Links.open` with the stored address is what the firmware relink already relies on.

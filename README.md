# FOCTuner

An open-source Android app for configuring and tuning VESC-compatible motor controllers, built to replace VESC Tool completely: first-time setup, firmware, motor detection, inputs, tuning, logging, scripting.

Status: **Phase 0 (foundations)**. The protocol layer and a bare Compose app exist; nothing talks to a controller yet. See [docs/PARITY.md](docs/PARITY.md) for what "replace VESC Tool" means feature by feature and [docs/SPIKES.md](docs/SPIKES.md) for what we've learned about the protocol and config format.

Not affiliated with VESC or Benjamin Vedder. "VESC" is a registered trademark of Benjamin Vedder; this app is a compatible third-party tool.

## Layout

| Module | What it is |
|---|---|
| `core:protocol` | Packet framing, CRC16, big-endian buffer helpers, `COMM_*` ids. Pure JVM. |
| `core:transport` | `Transport` interface (BLE, USB, TCP implementations come later). |
| `core:config` | Parameter definitions, config (de)serialization (empty so far). |
| `core:vesc` | Session, CAN routing, command API (empty so far). |
| `core:sim` | Fake controller for tests and demo mode (empty so far). |
| `app` | Android app (Jetpack Compose). |
| `tools/` | `gen_comm_ids.py` regenerates `CommPacketId.kt` from the firmware's `datatypes.h`. |

## Build and test

Needs JDK 17+ and the Android SDK (platform 37, set `sdk.dir` in `local.properties`).

```bash
./gradlew test               # unit tests (protocol)
./gradlew :app:assembleDebug # debug APK in app/build/outputs/apk/debug/
```

## License

GPL-3.0-or-later, see [LICENSE](LICENSE). Parts are derived from VESC Tool and the VESC firmware, both GPL-3.0.

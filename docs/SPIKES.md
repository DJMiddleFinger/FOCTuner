# Phase 0 spikes

Findings that shape the design. Source: `vedderb/vesc_tool` at commit `b5fcf93` and `vedderb/bldc` `master`, both read on 2026-10-14.

## 1. Packet framing and byte encoding: done

Ported from `comm/packet.c`, `util/crc.c`, `util/buffer.c` into `core/protocol` (21 unit tests, all passing).

- Frame: `[start 2|3|4][length 1-3 bytes][payload][CRC16 hi][CRC16 lo][0x03]`, CRC-16/XMODEM over the payload. Shortest length encoding is mandatory.
- Known frames reproduced: `COMM_FW_VERSION` = `02 01 00 00 00 03`, `COMM_GET_VALUES` = `02 01 04 40 84 03`.
- All numbers are big endian. `float32_auto` is, for every normal float, just the IEEE-754 bits (firmware flushes values below 1.5e-38 to 0).
- **Found by a test:** after a frame whose CRC is wrong, its trailing `0x03` looks like the start of a 16-bit-length frame and the decoder would wait for ~500+ bytes, losing the next good packet. The firmware hides this because its max payload is 512 (the false length is always larger). Our decoder accepts big packets, so it skips a whole frame when only the CRC fails. A bad stop byte still resyncs one byte at a time. Callers must also call `PacketDecoder.reset()` after a link idle timeout.
- `CommPacketId` is generated from the firmware header by `tools/gen_comm_ids.py` (157 commands, ids up to 159; 153-155 are reserved upstream).

## 2. Config definitions and signature: understood, not yet implemented

VESC Tool keeps one set of XML files per firmware series in `res/config/<version>/`:

| File | Content |
|---|---|
| `parameters_mcconf.xml` | `<Params>` (every motor parameter), `<SerOrder>` (the order they go on the wire), `<Grouping>` (the settings UI layout: groups > subgroups > parameter names) |
| `parameters_appconf.xml` | same structure for app config |
| `info.xml` | firmware info text |

Each `<Params>` entry has: `longName`, `type`, `transmittable`, `description` (HTML), `cDefine`, defaults, min/max/step, `suffix`, `editorScale`, `editAsPercentage`, `showDisplay`, `vTx`, `vTxDoubleScale`, and `enumNames` for enums.

- `type`: 0 undefined, 1 double, 2 int, 3 string, 4 enum, 5 bool, 6 bitfield.
- `vTx` (wire encoding): 1 uint8, 2 int8, 3 uint16, 4 int16, 5 uint32, 6 int32, 7 double16 (`value * vTxDoubleScale` as int16), 8 double32 (as int32), 9 double32_auto. Enum, bool and bitfield are always int8. Strings are NUL-terminated.
- The wire form of a config is `uint32 signature` followed by every parameter in `<SerOrder>` order.
- **Signature** = CRC32C (reflected poly `0x82F63B78`, init `0xFFFFFFFF`; confirm the final inversion when implementing) over the UTF-8 string built, in serialization order, from: parameter name + `type` + `vTx` + all enum names. If the signature in a packet differs from the one computed from our bundled XML, the layout differs and we must not read or write it.
- The 7.01 motor config has 214 parameters: 141 double, 34 int, 24 enum, 8 bool, 4 string, 3 bitfield. Of the 175 numeric ones, 72 use double16, 69 double32_auto, 20 uint8, 11 int8, and 3 other widths.

Consequences for `core:config`:
1. It can be driven entirely from these XML files: no hand-written field list. The same engine renders the settings UI from `<Grouping>`.
2. Compatibility check = compute the signature from the bundled XML for the connected firmware series and compare with the one the device sends. A mismatch blocks writes and offers the right definitions or a firmware update.
3. The XML files are GPL-3.0 and ~400 KB each. Bundle only the series we certify (7.01, 7.00, 6.06, 6.05 first) and fetch others on demand.
4. `description` is Qt rich-text HTML; render it as plain text/limited HTML in Compose.

Open question: device-supplied custom config XML (`COMM_GET_CUSTOM_CONFIG_XML`) probably uses the same format; verify in Phase 6.

## 3. Transports: not done

BLE (Nordic UART service), USB-OTG CDC-ACM and TCP need a real controller and phone to validate. Planned for the start of Phase 1; the `Transport` interface in `core/transport` is a provisional shape.

## 4. QML package UIs: not done

VESC Tool's own phone UI is Qt/QML (`mobile/*.qml`, `qmlui.cpp`), and packages can ship a QML UI. Whether to embed a QML runtime, translate common packages natively, or both is still open; decide at the start of Phase 6 after listing which packages users actually install.

## Corrections to the project plan

- Current firmware series is **7.x** (7.00 released 2026-05-15, 7.01 in development), not 6.x. Certify 7.x and 6.05/6.06 first.
- VESC Tool's mobile UI already includes profiles, multi-setting apply, stats, 3D view, LispBM, packages and BMS. Desktop-only: sampled data, experiments, log analysis, motor comparison, display tool, SWD/ESP programming, CAN analyzer. Those are where "never open VESC Tool" is hardest.

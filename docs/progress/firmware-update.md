# Firmware update: progress

Owner: the firmware side session. Scope: `core/vesc/.../vesc/firmware/`, `core/sim/.../FirmwareSim.kt`, `app/.../ui/tools/firmware/`, `strings_firmware.xml`, this file.

Sources read (2026-10-16): vesc_tool master `f1e15cb` (`pages/pagefirmware.cpp`, `mobile/FwUpdate.qml`, `mobile/fwhelper.cpp`, `vescinterface.cpp` fwUpload/fwErase*/downloadFw*, `commands.cpp`, `hexfile.cpp`, `utility.cpp` removeFirmwareHeader, `vbytearray.cpp`), bldc master (`comm/commands.c`, `flash_helper.c`), bldc-bootloader master (`main.c`, heatshrink config and decoder). Notes with more detail: `firmware-update-findings.md` in the shared notes folder.

## What VESC Tool does

- **Header**: the update buffer gets `u32 size` + `u16 CRC16` (XMODEM, same as packets) + data. The bootloader checks the CRC before touching the running app, so an incomplete or corrupt upload leaves the old firmware running.
- **Heatshrink** (window 13, lookahead 5) when `393208 < size < 700000`; size gets `0xCC << 24`. Every 6.x/7.x STM32 image is 524280 bytes (the whole app area), so this is the normal path, and LZO is off then. More than 393208 after compression: refuse.
- **Erase**: `COMM_ERASE_NEW_APP [u32 size]`, reply `[ok]`, 20 s timeout. Erasing waits for the motors to release (fails after 3 s).
- **Write**: 384-byte chunks, chunks of only 0xFF skipped. `COMM_WRITE_NEW_APP_DATA [u32 offset][data]` or `_LZO [u32 offset][u16 raw len][lzo]` when `lzo + 2 < raw`. Reply is always `COMM_WRITE_NEW_APP_DATA [ok][u32 offset]`. 3 attempts x 3 s, retried only on timeout. A failed LZO chunk is rewritten raw; more than 3 such failures in a row turn LZO off. The firmware ignores `lzo1x_decompress_safe`'s result.
- **Feature gates** (by connected firmware): all-CAN 3.45, erase bootloader 3.59, LZO 3.63 and HW_TYPE_VESC only, `_HW` variants 5.02.
- **Jump**: `COMM_JUMP_TO_BOOTLOADER`, then 500 ms, then VESC Tool disconnects. "Reboots within 10 seconds; do NOT remove power."
- **Bootloader**: `COMM_ERASE_BOOTLOADER`, then write (no header, no jump) at offset 3*128K (VESC), 0x1E000 (BMS and `hm1`), 0xE000 (other custom modules). VESC Tool uploads the bootloader before the firmware whenever erase is supported (mobile aborts the firmware if the bootloader failed; desktop carries on).
- **All CAN**: `_ALL_CAN` variants on the attached controller, which relays with "no reply". Refused while forwarding to a CAN device, and unless every HW_TYPE_VESC device on the bus reports the same hardware name. Non-VESC targets use the `_ALL_CAN_HW` variants with a `[u8 hw type][hw name\0]` prefix.
- **Files**: `.bin` and `.hex` only, at most 8,000,000 bytes. Intel HEX is flattened from the lowest address with 0xFF in gaps. A `.bin` that already carries a valid header is unwrapped first.
- **Hardware**: every image from 3.55 to 7.01 contains `org.vesc.<HW_NAME>\0`; that is how we check custom files.
- **Downloads** (not used, see below): since 7.00 VESC Tool fetches Qt RCC bundles over plain HTTP from home.vedder.se (`res_fw_<ver>.rcc`, archive `res_fw.rcc`); bootloaders and BMS firmware ship inside VESC Tool's own repo (`res/bootloaders*`).

## Done

`./gradlew :core:vesc:test :core:sim:test :app:assembleDebug` is green.

- **Client** (`core/vesc/.../vesc/firmware/`): `updateFirmware()` on any `VescController` returns a progress `Flow` (Preparing, Erasing, Writing with byte counts, Installing, then Restarted / LinkClosed / NoReply / BootloaderWritten) or throws `FirmwareUpdateException` (failure kind + whether the bootloader region was hit). VESC Tool's sequence and timeouts; replies to writes are matched by the offset they echo, so a late reply can't confirm the wrong chunk. The bootloader part runs to the end once started (NonCancellable); the firmware part is cancellable between chunks; the jump can't be cancelled. After the jump it polls COMM_FW_VERSION for up to 45 s and reports what came back (and every CAN device after an all-CAN update). `firmwareBuild()` reads COMM_FW_INFO.
- **Codecs**: `Lzo1x` (checked both ways against lzo-java, a translation of liblzo, test dependency only), `Heatshrink` (hand-built bit vectors; on real 7.01 images it reaches 352,423 of the 393,208 allowed bytes, the same as a greedy reference encoder), `IntelHex`.
- **Images**: `FirmwareImage.fromFile` (.bin/.hex, 8 MB limit, header unwrap, embedded `org.vesc.<hw>` name, verified on six real images from 3.55 to 7.01). Hardware mismatch or unknown hardware is refused unless the caller passes `allowHardwareMismatch`.
- **Bootloaders**: the five VESC Tool bootloaders are bundled as resources (`core/vesc/src/main/resources/app/foctuner/vesc/firmware/`), from vedderb/vesc_tool `f1e15cb`, SHA-256 checked on load; `Bootloaders.forController()` picks them like VESC Tool.
- **Simulator** (`core/sim/FirmwareSim.kt`): update buffer + bootloader sector with NOR write semantics, LZO writes, header/CRC check on jump, silent 8 s while installing (2 s and back to the old version if the header is bad, forever if the bootloader sector is blank), installed version/hardware from the image, COMM_FW_INFO.
- **Tests** (`core/vesc/src/test/.../firmware/`): header/CRC reassembled from the writes, LZO and heatshrink paths, 20-byte BLE writes, hardware mismatch refused (and the override), unknown hardware refused, cancellation before the jump, bootloader first, bootloader only, lost reply retried, corrupted upload rejected by the bootloader, CAN forwarding, all-CAN, all-CAN refused for mixed hardware, bootloader picking.
- **UI** (`ui/tools/firmware/`, "Firmware" in Tools): installed firmware (version, beta, hardware, type, build hash, UUID), file picker (Storage Access Framework, .bin/.hex), hardware check with an explicit "I'm sure" override, optional bootloader first, bootloader-only upload, this controller or the whole bus, confirmation dialog listing what happens and what failure means, progress with percent and Cancel (only while cancellable), result notices, back navigation blocked while running.

Changes outside my area (minimal): `SimulatedVesc` (one field, the `answering` guard in `handle`, dispatch lines, version from `FirmwareSim`, KDoc), `core/sim/build.gradle.kts` (`implementation(project(":core:vesc"))` for the decoders, replacing the test-only dependency), `gradle/libs.versions.toml` + `core/vesc/build.gradle.kts` (lzo-core test dependency), `Tools.kt` (entry + branch). The manifest already had INTERNET; nothing downloads yet.

## Next

1. Download source, only once the user agrees to use VESC Project servers (lead is asking): VESC Tool's bundles are Qt RCC v3 over plain HTTP at `http://home.vedder.se/vesc_fw_archive/`, the server honours `Range`, so fetch the 24-byte header, then the names+tree at the end (12 KB for `res_fw_7.01.rcc`, 52 KB for the 643 MB archive), then single files; data offsets are relative to the header's data offset, each entry is u32 size + bytes (flag 1 = qCompress). Paths and hardware matching are in the findings note. Android needs a network security config for cleartext to that host. Plug it in as another way to produce a `FirmwareImage` with `hardware` from the directory name, and also require the embedded name to match it (guards against a wrong offset). The upload code doesn't change.
2. Session-level (lead): pause the 10 Hz overview polling during an upload (VESC Tool stops all polling); block the header Disconnect and tab switching while writing; when the link drops after the jump (always on USB) the Connect screen should say the controller is installing an update, since the tool's result is gone with the connection.
3. BMS firmware (`res/firmwares_bms` in the vesc_tool repo) and ESP32 images for custom modules, with the download source.
4. Changelog view (VESC Tool shows the firmware CHANGELOG.md from the bundle).

## Unverified

- Nothing has run on hardware or the emulator. Payloads come from VESC Tool and the firmware source; the simulator is written from the same reading, so a shared misreading would pass the tests. First hardware run: install the same version over USB with VESC Tool's file, then over BLE, then an all-CAN update on a dual board.
- `_ALL_CAN_HW` variants (BMS / VESC Express attached) are built from `commands.cpp` only; nothing tests them against a device.
- The restart timeout (45 s) and the sim's install time are guesses; VESC Tool only says "within 10 seconds".
- Heatshrink margin: 7.01 images compress to ~352 KB of 393 KB allowed; bigger future images could hit the limit for us and VESC Tool alike.

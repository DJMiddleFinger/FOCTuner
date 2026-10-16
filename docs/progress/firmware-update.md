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

- `Lzo1x` (greedy LZO1X-1 encoder, `lzo1x_decompress_safe`-style decoder), `Heatshrink` (encoder/decoder with the bootloader's parameters), `IntelHex`, `FirmwareImage` (file rules, header stripping, embedded hardware name), `UpdateBuffer` (header, compression choice, bootloader offsets), `FirmwareBuild` (COMM_FW_INFO), `updateFirmware()` (progress flow, hardware check, all-CAN check, bootloader then firmware, retries, LZO fallback, jump, restart check). Compiles; no tests yet.

## Next

1. Merge main. Tests for Lzo1x (against lzo-java as an independent reference), Heatshrink (hand-built vectors + round trip), IntelHex, FirmwareImage.
2. `core/sim/FirmwareSim.kt`: update buffer and bootloader sector, erase/write/LZO, header CRC check on jump, silent while "rebooting", reports the installed version; COMM_FW_INFO.
3. Client tests against the sim: header/CRC, BLE-sized chunks, hardware mismatch refused, cancellation, all-CAN, bootloader.
4. Image sources behind one interface: local file (SAF) and the bootloaders from the vesc_tool repo. No home.vedder.se downloads until the lead says so.
5. UI in `ui/tools/firmware/`, registered in `Tools.kt`.

## Unverified

- Nothing has run on hardware.

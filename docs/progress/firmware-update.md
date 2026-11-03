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


### Wave 1 `firmware-dl` (branch `claude/firmware-dl-cdb5e8`)

Archive client, `core/vesc/.../vesc/firmware/archive/` (all unit-tested, `./gradlew :core:vesc:test`):

- `RccIndex`: Qt rcc v2/v3 table of contents. Header (24 bytes) says where names + tree start; they are the last 12 KB (release bundle) / 52 KB (archive) of a 100-650 MB file. Bounds-checked, depth and entry limits, qCompress inflate (zlib Adler-32 checked). Tested against the real indexes of `res_fw_7.01.rcc`, `res_fw.rcc` and `res_fw_esp32.rcc` (fixtures in `core/vesc/src/test/resources/.../archive/`, fetched 2026-11-02) and against bundles built by `core:sim`'s `RccWriter`.
- `HttpFetcher` / `UrlConnectionFetcher`: GET with Range, requires 206 for a range (a server that answers 200 is refused before the body is read: it would be the whole bundle), no redirects, plain HTTP only to `home.vedder.se`, size cap, cancel closes the connection.
- `FirmwareArchive`: `listing(target)` (versions newest first, `VESC_default.bin` first within a version; release bundles `res_fw_<ver>.rcc` found from the host's directory index plus `res_fw.rcc` for 3.55-7.00; `_o_` directories match each hardware; ESP images from `res_fw_esp32.rcc`; BMS from the VESC Tool repo over HTTPS via GitHub's contents API; falls back to the saved images when offline, `incomplete` when only some bundles answered), `download(entry, target)` as a Flow of progress then the checked image, `changelog()`.
- Checks (`FirmwareArchive.verify`, also run on cached images): hardware directory contains the controller's hardware name, embedded `org.vesc.<name>` must exist for motor controllers and match the directory (catches a wrong offset), size/blank checks as for picked files. BMS and Express images carry no such name; if one is present it must match. `updateFirmware` still refuses on mismatch.
- `FirmwareCache`: images + `.meta` (size, CRC16, SHA-256, rechecked on every read; damaged copy = missing), atomic writes, bundle index (header + tail, reused while the 24-byte header is unchanged), changelog text.
- `Changelog`: parses `### <ver>` / `#### <date>` sections and picks what changed between two versions. The changelog is NOT in the rcc bundles; it is `res/firmwares/CHANGELOG.md` in the VESC Tool repo.
- Manifest: `network_security_config.xml` allows cleartext for `home.vedder.se` only.
- `core:sim`: `RccWriter`, `SimulatedArchiveHost` (in-memory HttpFetcher with Range, failure injection, request log), `DemoArchive.host()` (7.01, 7.00, 6.06, 6.05 for any hardware, BMS, Express, changelog) for demo mode.

Resume, session lock and UI (committed, `./gradlew test :app:testDebugUnitTest :app:assembleDebug` green):

- **Resume** (`FirmwareUpdate.kt`): `UploadTracker` turns the progress events into an `UploadCheckpoint` (image SHA-256, hardware, UUID, buffer size, offset; none until the erase is confirmed, cleared at `Installing`). `updateFirmware(..., resume = checkpoint)` skips bootloader and erase, checks image, hardware name and UUID (`FirmwareFailure.CannotResume`), refuses all-CAN, writes from the offset. `core:sim`: `SimulatedVesc.dropLink()` / `reattach()` (same controller state, new link), `dropLinkDuringUpload(bytes)`, `firmwareErases`, `firmwareWriteOffsets`. `ResumeTest` covers continue-without-erase, identity refusals, a partial image someone else replaced (bootloader refuses, old firmware runs), no checkpoint before the erase.
- **Session** (`ui/session/UpdateLock.kt`, small additive edits in `SessionViewModel`, `ConnectedShell`, `SessionHeaderState`, `FocApp`, `ToolContext`): while an upload runs the 10 Hz overview polling is off, tabs / CAN button / Disconnect / CAN selection / back-to-disconnect are locked with a banner saying why, and the screen stays on. After the jump the link closes and the Connect screen says the controller is installing (`connect_link_dropped_installing`) instead of "lost the connection".
- **UI** (`ui/tools/firmware/`: `FirmwareArchiveSection.kt`, `FirmwareStorage.kt`, `FirmwareModel.kt`, strings in `strings_firmware.xml`): Download section (latest, older versions, saved copies marked, progress with Cancel, "removes the hardware's protective limits" on `_no_hw_limits` builds, HTTP note), source row, "What's new" dialog (changelog between the installed and the chosen version), interrupted-upload notice with Resume / Start over and what is safe, the checkpoint is also saved to disk (`filesDir/firmware/checkpoint.properties`) and the matching downloaded image is reloaded from the cache on the next visit, "Recovery help" dialog (wait, power-cycle, old version means refused, bootloader LED codes, STM32 ROM bootloader / SWD / ESP32 notes). Demo mode (`ToolContext.demo`) uses `DemoArchive`.

Findings from reading bldc / bldc-bootloader (2026-11-02), used by the resume and recovery work:

- `flash_helper_write_new_app_data` programs byte by byte and returns the first flash error; rewriting a chunk with the same bytes is what VESC Tool's retry already does, so continuing from the last acknowledged chunk is safe. There is no read-back command, so a resumed image is only proven by the bootloader's CRC check, which leaves the old firmware running if it fails.
- A second `COMM_ERASE_NEW_APP` just erases the sectors again (needs the motors released, 3 s). The new-app sectors 8-10 are the same flash as the LispBM constants, the QML UI and the LispBM code, so any erase of a full-size image removes an installed script and package.
- The bootloader (`bldc-bootloader/main.c`) has no communication code. After the jump it checks the header CRC (red LED blinks: 3 too large, 4 empty, 5 CRC mismatch, then it starts the old app), erases the whole application area and writes the new one, retrying 5 times (red 1 each; red 8 at the end means a programmer is needed). Power loss in that window leaves no application: only the STM32's ROM DFU (BOOT0) or SWD can recover it.
- All-CAN uploads can't be resumed: the CAN devices don't acknowledge their relayed writes.

## Next

What is left of wave 1 `firmware-dl`, most important first:

1. **Emulator check** (not done, usage ran out): install, demo mode, Tools > Firmware: list, download with progress, Use, What's new, Install, resume path, Recovery help; dark and light; font scale 1.6; TalkBack labels of the download rows; screenshots to `docs/screenshots/firmware-dl-*.png`. There is no way yet to trigger a dropped link in demo mode: add one (for example a hidden "drop the link at 40 %" switch on the demo session that calls `SimulatedVesc.dropLinkDuringUpload`), otherwise the resume UI can only be seen with a real board or a debugger.
2. **Reconnect inside the session** (scope item 6, last bullet; only the Connect-screen message exists): keep the `open` lambda from `SessionViewModel.connect` in `Active`; when the link drops while `updateLock` is held, reopen it (demo mode must reattach the same `SimulatedVesc`: `reattach()` exists, but `FocApp` creates a new `SimulatedVesc()` per connect) and swap `session` / `_tools` without resetting terminal, overview and the selected CAN id, then show the new version in the firmware tool. Until then the user reconnects by hand; the saved checkpoint still offers Resume (and reloads the image from the cache).
3. **Tests that are missing**: `UrlConnectionFetcher` (use `com.sun.net.httpserver` in a JVM test: 206 required for Range, 200 refused, redirects refused, size cap, cancel closes the socket); the model logic in `FirmwareModel` (archive state, checkpoint save / clear, lock hold / release) is not unit-tested because the class still takes a `ContentResolver` in `openFile`; split the file reading out so the rest can run on the JVM.
4. A BMS or VESC Express update through the tool has only been exercised through `FirmwareArchive` tests; the `_ALL_CAN_HW` upload variants are still untested against a device.
5. Possible cleanups: show the file size of each archive entry (needs the 4-byte prefix per entry, one small Range request each), mark the entry that matches the installed version, a "saved images" manager (`FirmwareCache.size()` and `remove()` exist).

Not part of this wave: SWD / ESP32 programmers (wave 3 `programmers`), custom module bootloaders.

## Unverified

- Nothing has run on hardware or the emulator. Payloads come from VESC Tool and the firmware source; the simulator is written from the same reading, so a shared misreading would pass the tests. First hardware run: install the same version over USB with VESC Tool's file, then over BLE, then an all-CAN update on a dual board.
- `_ALL_CAN_HW` variants (BMS / VESC Express attached) are built from `commands.cpp` only; nothing tests them against a device.
- The restart timeout (45 s) and the sim's install time are guesses; VESC Tool only says "within 10 seconds".
- Heatshrink margin: 7.01 images compress to ~352 KB of 393 KB allowed; bigger future images could hit the limit for us and VESC Tool alike.
- The archive client has only run against the simulated host and the real *indexes*. No image has been fetched from `home.vedder.se` by the app; the offsets and the 4-byte size prefix were checked by hand once (`60_MK6/VESC_default.bin` in `res_fw_7.01.rcc`: 524280 bytes, contains `org.vesc.60_MK6`).
- Downloads from `home.vedder.se` are plain HTTP, so they are not authenticated: the checks catch a wrong or damaged file, not one swapped on the way. That is VESC Tool's trade-off and the user accepted it (2026-11-02).
- The GitHub contents API (BMS directory listing) is rate limited for anonymous use (60 per hour per address); a failed listing falls back to a directory named like the BMS's hardware.
- The recovery copy (LED codes, BOOT0 / SWD) comes from reading the bootloader source, not from a bricked board.


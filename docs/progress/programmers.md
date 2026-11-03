# Programmers (wave 3): progress

Branch `claude/amazing-franklin-p8khnz`. Roadmap row `programmers`. Sources: vesc_tool master `ed54d60` (`pages/pageswdprog.cpp`, `vescinterface.cpp` `swdEraseFlash` / `swdUploadFw`, `pages/pageespprog.cpp`, `esp32/` (esp-serial-flasher), `pages/pagedisplaytool.cpp`), bldc master (`comm/commands.c` COMM_BM_*).

## Done

`./gradlew test :app:assembleDebug :app:lintDebug` green. No emulator, controller, nRF module or ESP32 has been programmed by this code.

- **SWD programmer** (`core/vesc` `SwdProgrammer.kt`, `SwdImages.kt`; Tools > Hardware): pin mapping (SWD header or the board's built-in nRF), connect with VESC Tool's target names and flash start, erase all (20 s), 400-byte writes with 3 tries and optional read-back, memory read, reboot, disconnect, all in the firmware's blocking thread; VESC Tool's error wording per code. VESC Tool's nRF images per target are listed and fetched from its repository (`res/other_fw/`, HTTPS) on demand instead of bundled (2.4 MB). `.bin` files go to the start of flash, `.hex` segments to their own addresses (`IntelHex.segments`). LZO-compressed writes are not used (plain writes work on every firmware with the probe; slower).
- **ESP32 programmer** (`core/vesc` `esp/EspLoader.kt`; connect screen > App, and Tools > Hardware): Espressif's ROM bootloader protocol without the flasher stub: SLIP, the USB-serial and classic DTR/RTS reset sequences, sync, chip detection by magic register (ESP32, S2, C3, S3, C2, C6), SPI attach, flash id read for the size (C3/S3/C2/C6; 4 MB otherwise), SPI parameters, 1 KB flash blocks with checksum and 3 tries, MD5 check of every image, erase by flash-begin, reset. Images: the VESC Express builds in the firmware archive's `res_fw_esp32.rcc` for the detected chip (bootloader, partition table, app; `FirmwareArchive.expressBuilds` / `expressImages`), or three picked files; VESC Tool's offsets (0x0, 0x8000, 0x20000; LispBM 0x340000 / 512 KB, QML 0x3C0000 / 128 KB). `UsbTransport` drives DTR/RTS (CDC SET_CONTROL_LINE_STATE). A simulated VESC Express (`EspRomSim`) is listed for trying it without hardware.
- **Display fonts** (`DisplayFont.kt`; Tools > Hardware): VESC Tool's display tool font export: glyphs rendered with Android's sans, serif or mono (bold optional), sized by ascent like VESC Tool, 1 bit per pixel or 2 with antialiasing, 95 ASCII characters or digits only, preview sheet, saved as `.bin`.
- **Simulator**: `SwdSim` (an nRF52832 512K on the SWD header, NOR-flash bit-clearing writes), `EspRomSim`, a partition table in the demo archive's Express build.
- Tests: `SwdTest` (3), `EspLoaderTest` (4: SLIP, flash + MD5 + corruption caught, archive builds flashed, no sync without download mode), `DisplayFontTest` (3).

## Next

1. Hardware, in this order: SWD-flash an nRF52832 BLE module from a VESC 6 with the probe; flash a VESC Express over its USB port from a phone (USB-OTG), including one that no longer boots.
2. LZO writes for SWD where the firmware supports `COMM_BM_WRITE_FLASH_LZO` (lzo compression would need a library; VESC Tool uses lzokay).
3. STM32 targets: VESC Tool offers the VESC firmware plus bootloader per hardware from its bundled firmwares; here only files are offered.
4. ESP: baud rate switch to 460800 for USB-to-UART bridges (the Express's own USB port doesn't care), and the flasher stub for speed.
5. Display tool: VESC Tool also has image editors and overlay layers; only the font export is done.
6. The nRF UICR read/write buttons of VESC Tool's SWD page.

## Unverified

- Whether Android's CDC driver path delivers DTR/RTS fast enough for the ESP32-C3 USB-serial reset sequence; the timings follow VESC Tool's (100 ms steps).
- SPI attach with config 0 assumes the default flash pins, true for ESP32-C3/S3 modules; boards with flash on other pins would need the eFuse-derived value VESC Tool reads for classic ESP32.
- USB-to-serial chips that aren't CDC-ACM (CP210x, CH340, FTDI) are not supported by `UsbTransport`.

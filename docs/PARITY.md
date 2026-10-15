# VESC Tool parity checklist

Audited from `vedderb/vesc_tool` at commit `b5fcf93` (2026-10-14, changelog head: 7.01 in development, 7.00 released 2026-05-15). Source of truth for what "parity" means. Tick a row only after it works on a real controller and the result matches VESC Tool.

How to read it:
- **VESC Tool source** is the desktop page (`pages/*.cpp`) or mobile screen (`mobile/*.qml`) the feature comes from. "Mobile" means VESC Tool's own phone UI already has it; "Desktop only" means it never made it to the phone, so those are where our app has to beat the mobile app, not just match it.
- **Protocol** lists the `COMM_*` commands we expect to need. Names are from the firmware's `datatypes.h`; the mapping from page to commands is inferred from page names and the changelog and must be confirmed when the feature is built.
- **Phase** refers to the roadmap in the project plan.

Config definitions: VESC Tool ships them as XML per firmware series under `res/config/<version>/` (`parameters_mcconf.xml`, `parameters_appconf.xml`, `info.xml`, roughly 250-400 KB each). Series present: 3.55-3.66, 4.00-4.02, 5.00-5.03, 6.00, 6.02, 6.05, 6.06, 7.00, 7.01. **We target 7.x (current) and 6.05/6.06 first.**

## 1. Connection

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| BLE scan/connect, preferred device | Mobile `ConnectScreen`, `BleSetupDialog`, `bleuart.cpp` | (transport) | 1 | ☐ |
| USB serial | Desktop `pageconnection` | (transport) | 1 | ☐ |
| TCP / WiFi / UDP broadcast listener | Mobile `TcpBox`, `tcphub.cpp`, `udpserversimple.cpp` | (transport) | 1 | ☐ |
| TCP hub / bridge to desktop | Mobile `TcpHubBox` | (transport) | 1 | ☐ |
| CAN device list and forwarding | Mobile `CanScreen`, desktop `pagecontrollers` | `COMM_PING_CAN`, `COMM_FORWARD_CAN` | 1 | ☐ |
| Reconnect to last CAN device | Changelog 6.05 | `COMM_FORWARD_CAN` | 1 | ☐ |
| Poll manager (stable polling) | Changelog 7.01 | `COMM_GET_VALUES*` | 1 | ☐ |
| BLE module name/PIN, nRF pairing | `BleSetupDialog`, `NrfPair`, desktop `pageappnrf` | `COMM_SET_BLE_NAME`, `COMM_SET_BLE_PIN`, `COMM_NRF_START_PAIRING`, `COMM_EXT_NRF_*` | 7 | ☐ |
| CAN baud update | Desktop `pagecontrollers` | `COMM_CAN_UPDATE_BAUD_ALL`, `COMM_SET_CAN_MODE` | 4 | ☐ |

## 2. Setup wizards

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| Startup / board setup flow | Desktop `startupwizard`, `boardsetupwindow`, mobile `SetupWizardIntro` | various | 3 | ☐ |
| FOC motor wizard (usage, limits, detect, apply) | `setupwizardmotor`, mobile `SetupWizardFoc` | `COMM_DETECT_MOTOR_R_L`, `COMM_DETECT_MOTOR_FLUX_LINKAGE(_OPENLOOP)`, `COMM_DETECT_MOTOR_PARAM`, `COMM_DETECT_APPLY_ALL_FOC` | 3 | ☐ |
| Detect FOC params / hall / encoder (standalone) | Mobile `DetectFocParam`, `DetectFocHall`, `DetectFocEncoder` | `COMM_DETECT_HALL_FOC`, `COMM_DETECT_ENCODER` | 3 | ☐ |
| BLDC detection | Mobile `DetectBldc` | `COMM_DETECT_*`, `COMM_SET_DETECT` | 3 | ☐ |
| Input / app wizard (PPM, ADC, UART, remote) | `setupwizardapp`, mobile `SetupWizardInput`, `PpmMap`, `AdcMap` | `COMM_GET_DECODED_PPM/ADC/CHUK`, `COMM_SET_APPCONF` | 3 | ☐ |
| IMU wizard | Mobile `SetupWizardIMU` | `COMM_GET_IMU_DATA`, `COMM_GET_IMU_CALIBRATION` | 3 | ☐ |
| Direction setup | Mobile `DirectionSetup` | `COMM_SET_MCCONF` | 3 | ☐ |
| Setup calculators | Desktop `pagesetupcalculators` | none (local) | 3 | ☐ |
| Sanity-check motor params on write | Changelog 6.05 | none (local) | 2 | ☐ |

## 3. Motor configuration

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| Motor settings pages (general, FOC, BLDC, DC, GPD, additional info) | Desktop `pagemotor`, `pagemotorsettings`, `pagefoc`, `pagebldc`, `pagedc`, `pagegpd`, `pagemotorinfo`; mobile `ConfigPageMotor` | `COMM_GET_MCCONF`, `COMM_SET_MCCONF`, `COMM_GET_MCCONF_DEFAULT` | 2 | ☐ |
| Parameter editors (int, double, enum, bool, bitfield, string, separator) | Mobile `ParamEdit*` | none | 2 | ☐ |
| Temporary (non-stored) config | Firmware | `COMM_SET_MCCONF_TEMP(_SETUP)`, `COMM_GET_MCCONF_TEMP`, `COMM_SET_APPCONF_NO_STORE` | 2 | ☐ |
| Config backup / restore, XML save/load | Changelog 3.01/6.00/6.02 | (local files) | 2 | ☐ |
| Config caching (avoid writing defaults by accident) | Changelog 7.01 | none | 2 | ☐ |
| Motor comparison tool | Desktop `pagemotorcomparison` | none (local) | 8 | ☐ |
| Multi-setting apply across CAN | Mobile `MultiSettings` | `COMM_FORWARD_CAN` + config | 4 | ☐ |
| Profiles | Mobile `Profiles`, `ProfileEditor`, `ProfileDisplay` | config writes | 2 | ☐ |

## 4. App configuration

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| App general, PPM, ADC, UART, Nunchuk, PAS, IMU/balance, nRF, settings | Desktop `pageapp*`; mobile `ConfigPageApp` | `COMM_GET_APPCONF`, `COMM_SET_APPCONF`, `COMM_GET_APPCONF_DEFAULT` | 2 | ☐ |
| Custom (hardware/package) config pages from device | Desktop `pagecustomconfig`, mobile `ConfigPageCustom` | `COMM_GET_CUSTOM_CONFIG_XML`, `COMM_GET_CUSTOM_CONFIG(_DEFAULT)`, `COMM_SET_CUSTOM_CONFIG` | 6 | ☐ |
| Custom config + QML UI cache | Changelog 6.05 | `COMM_GET_QML_UI_HW/APP` | 6 | ☐ |

## 5. Real-time data and analysis

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| Realtime data, gauges, stats | Desktop `pagertdata`; mobile `RtData`, `RtDataSetup`, `CustomGauge(V2)`, `StatPage` | `COMM_GET_VALUES(_SETUP)(_SELECTIVE)`, `COMM_GET_STATS`, `COMM_RESET_STATS` | 1 | ☐ |
| Realtime IMU / balance data, 3D view | Desktop `pageimu`; mobile `RtDataIMU`, `Vesc3DView` | `COMM_GET_IMU_DATA`, `COMM_GET_DECODED_BALANCE` | 3 | ☐ |
| Manual motor control (duty, current, brake, RPM, position, handbrake, servo) | Desktop `pagertdata`, mobile `Controls` | `COMM_SET_DUTY/CURRENT/CURRENT_BRAKE/RPM/POS/HANDBRAKE/SERVO_POS`, `COMM_ALIVE` | 1 | ☐ |
| Stop button / ESC key disables commands for 5 s | Changelog 6.05/6.06 | `COMM_MOTOR_ESTOP` | 1 | ☐ |
| Sampled data (scope), load CSV | Desktop `pagesampleddata` | `COMM_SAMPLE_PRINT` | 5 | ☐ |
| Experiments / plots | Desktop `pageexperiments` | `COMM_EXPERIMENT_SAMPLE`, `COMM_PLOT_*` | 5 | ☐ |
| Data analysis page | Desktop `pagedataanalysis` | (local) | 5 | ☐ |
| Terminal and debug print | Desktop `pageterminal`, `pagedebugprint`; mobile `Terminal` | `COMM_TERMINAL_CMD(_SYNC)`, `COMM_PRINT` | 1 | ☐ |
| CAN analyzer | Desktop `pagecananalyzer` | `COMM_CAN_FWD_FRAME` | 7 | ☐ |
| Log writer / reader / analysis | Mobile `logwriter`, `logreader`, `LogBox`; desktop `pageloganalysis` | `COMM_LOG_*`, `COMM_FILE_*` | 5 | ☐ |
| Rotor position | Firmware | `COMM_ROTOR_POSITION` | 5 | ☐ |

## 6. Firmware and flashing

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| Firmware update (bundled/custom file, archive) | Desktop `pagefirmware`; mobile `FwUpdate`, `fwhelper.cpp` | `COMM_JUMP_TO_BOOTLOADER`, `COMM_ERASE_NEW_APP`, `COMM_WRITE_NEW_APP_DATA(_LZO)` | 4 | ☐ |
| Firmware downloaded on demand (no longer bundled) | Changelog 7.00 | (HTTPS) | 4 | ☐ |
| Update all CAN devices (and bootloader) | Changelog 3.01 | `COMM_*_ALL_CAN(_LZO)`, `COMM_ERASE_BOOTLOADER(_ALL_CAN)` | 4 | ☐ |
| Hardware-specific update commands | Firmware | `COMM_*_HW` | 4 | ☐ |
| ESP32 / VESC Express programming | Desktop `pageespprog` | (ESP serial protocol) | 4 | ☐ |
| SWD programming (nRF etc.) | Desktop `pageswdprog` | `COMM_BM_*` | 7 | ☐ |
| Firmware info | Firmware | `COMM_FW_INFO`, `COMM_FW_VERSION` | 1 | ☐ |

## 7. Scripting and packages

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| LispBM editor, REPL, upload/run/stop, stats | Desktop `pagelisp`, `pagescripting`; mobile `Lisp` | `COMM_LISP_*` | 6 | ☐ |
| Package store and install/uninstall | Desktop `pagevescpackage`; mobile `Packages` | `COMM_LISP_*`, `COMM_QMLUI_*`, `COMM_FILE_*` | 6 | ☐ |
| Package store filter box | Changelog 7.01 | (HTTPS) | 6 | ☐ |
| QML UI from firmware/packages | Mobile `qmlui.cpp` | `COMM_GET_QML_UI_HW/APP`, `COMM_QMLUI_ERASE/WRITE` | 6 (spike in 0) | ☐ |
| Device file system | Firmware | `COMM_FILE_LIST/READ/WRITE/MKDIR/REMOVE` | 6 | ☐ |

## 8. Other hardware

| Feature | VESC Tool source | Protocol | Phase | Done |
|---|---|---|---|---|
| BMS (cells, temps, balance, charge control, self-test) | Desktop `pagebms`; mobile `BMS` | `COMM_BMS_*` | 7 | ☐ |
| Display tool | Desktop `pagedisplaytool`, `display_tool/` | (local/serial) | 8 | ☐ |
| IO board, power switch, humidity/temp sensor, GNSS | Firmware | `COMM_IO_BOARD_*`, `COMM_PSW_*`, `COMM_GET_EXT_HUM_TMP`, `COMM_GET_GNSS` | 7 | ☐ |
| Odometer | Firmware | `COMM_SET_ODOMETER` | 5 | ☐ |

## Lessons from VESC Tool's own changelog (design inputs)

- 7.01: "Config caching to reduce the probability of accidentally writing the default config" and "Improved motor and app config write reliability." Writing a wrong config is a real hazard; our write path needs a read-back verify and an automatic pre-write backup.
- 7.00: firmware is no longer bundled and is downloaded on demand. We need the same (smaller APK) plus an offline cache.
- 6.05: sanity-check motor parameters on write; custom config and QML cache for faster connect; reconnect to last CAN device.
- 6.06: motor commands disabled for 5 s after the stop button (configurable).

## Not yet audited

Page-by-page parameter lists (inside the XML definitions), exact command sequences for each page, and VESC Tool's behavior details. Each is audited at the start of the phase that implements it, from the page's `.cpp`/`.qml` in the same commit as above.

# Logging: progress

Branch `claude/logging-32fb77`. Scope: wave 1 `logging` from `docs/ROADMAP.md`. Files: `core/logs` (pure JVM), `core/vesc/.../vesc/{LogStream,ControllerLog,RideSampling,RideSampler,RideRecorder,DeviceFileDownload}.kt`, `core/sim/.../sim/{LogSim,StoredRides}.kt`, `app/.../logging/`, `app/.../ui/tools/logs/`, `strings_logging.xml`, this file.

## What the sources say (read first)

- `COMM_LOG_*` is not "log the controller's realtime values". It is a stream from a producer to a logger, no reply. The producer is a LispBM script on a VESC or Express (`log-start`, `log-config-field`, `log-send-f32`, logger id -1 = VESC Tool, a CAN id = that device); the logger is a VESC Express with an SD card (`main/log.c`) or VESC Tool (`commands.cpp` signals, `pageloganalysis.cpp`). The STM32 firmware (`bldc/comm/commands.c`) does not handle the five commands at all (it only has the ids; `comm/log.c` is the sender).
- Express logger (`vesc_express/main/log.c`): `START` = i16 field count, f32Auto rate, u8 append_time, u8 append_gnss, u8 append_gnss_time; ignored while a log runs; makes `<card>/log_can/` and opens `log_NNN_YY-MM-DD_hh-mm-ss.csv` (NNN = highest + 1, date from the GNSS receiver, or `log_NNN.csv` when none and no GNSS flags; with GNSS flags it waits for a valid date) on its next tick. `CONFIG_FIELD` = i16 index, key\0 name\0 unit\0, u8 precision, u8 relative, u8 timestamp (key ≤ 24, name ≤ 29, unit ≤ 9 bytes, ≤ 120 fields). `DATA_F32/F64` = i16 first field, then f32Auto / f64Auto each. `STOP` closes the file. Each row has `%.{precision}f` for fields updated since the last row, empty cells for the others, then `;t_day` (and GNSS columns) after the last field.
- File format (VESC Tool `PageLogAnalysis::openLog/saveCsv`): header cells `key:name:unit:precision:isRelative:isTimestamp` joined by `;`, rows of numbers joined by `;`; an empty cell repeats the previous value; a row with the wrong cell count is skipped. Keys the analysis understands: `t_day` (s, UTC time of day), `t_day_pos` (fix time), `gnss_lat/lon/alt`, `gnss_h_acc` (> 0 = valid), `kmh_vesc`, `kmh_gnss`, the list in `core/logs/LogTable.kt` `LogKeys`. VESC Tool's own desktop "log to file" writes a different 55-column headerless format (`ms_today;input_voltage;...`); the analysis converts it with the same keys. This app does not read that format yet.
- `COMM_FILE_READ` (Express `commands.c`): request path\0 + i32 offset; reply i32 offset, i32 file size, up to 400 bytes (about 3.9 kB over Wi-Fi). It seeks, so a download can resume. The firmware caches one open file by *offset only*: a read at the offset it expects continues the cached file even if the path differs. A resume therefore reads offset 0 of the right file first (`downloadFile`).
- vesc_tool issues: #415 "download express logfiles on mobile", #421 "show progress when downloading multiple log files" (per-file progress only today).

## Done

(see Next for what is left; this section is updated per increment)

- `core:logs` (pure JVM, 40 tests): `LogTable`/`LogField`/`LogKeys`, `LogCsv` (VESC Tool format, read and write), `stats` (min/max/mean along the plotted line) and `decimate` (min/max per bucket), `GpsTrack` (fix filtering like the analysis page, flat-map projection, distance), `Gpx`, `LogNames`, `TripTracker`/`TripLedgerCodec` (trips from motion, lifetime totals), `LogCapture`/`LogEvent`, `TransferQueue`/`runTransfers`/`ThroughputMeter`.
- `core:vesc`: `logStart/logStop/logConfigField/logSendF32/logSendF64`, `logEvents()`, `streamedLogs()` (a script's stream becomes a log), `logToController()` (the app writes a log on the Express's card), `downloadFile()` with resume, `listLogFiles()`, `RideSampler`, `RideColumns` (VESC Tool's columns), `RideRecorder`.
- `core:sim`: `LogSim` (Express logger, lazily written rows), three stored rides in `/log_can` (`StoredRides`, no GNSS columns); the old `/logs` sample folder is gone (`PackagesTest` follows).

## Next

1. App: manifest (foreground service types, notifications, location), `RideLogService`, phone location source, log storage.
2. App UI: Logs tool (record, phone and controller lists, downloads with per-file and overall progress), analysis screen (plots, cursor, stats, GPS track), CSV/GPX export.
3. Trips: tracker running on every connection, store per UUID, "Trips" sub-tab in RT Data, odometer sync.
4. Emulator pass (dark/light, font 1.6), screenshots `docs/screenshots/logging-*.png`.

## Unverified

- Nothing has run against a real Express or phone GPS yet; the simulator was written from the same reading of the sources.

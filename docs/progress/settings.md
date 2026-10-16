# Settings (every VESC Tool setting, VESC Tool's sections): progress

Requested 2026-10-15: the app must expose every setting VESC Tool has, sectioned into the same categories. Built by the vesc-client session with the user's go-ahead, touching `core:config`, `core:vesc`, `core:sim` and `app/.../ui` (the config and UI sessions were idle).

## Done

`./gradlew :core:config:test :core:vesc:test :core:sim:test` green; `./gradlew :app:assembleDebug` green; checked on the Pixel 7 API 35 emulator in demo mode (dark).

- **Sections** (`core:config` `ConfigSections.kt`): `ConfigDefinition.sections()` = VESC Tool's `<Grouping>` plus a final "Other" group for what the grouping leaves out. `ConfigSectionsTest` fails if any setting of either 7.01 definition has no section.
  - Motor: General (General, Sensors, Current, Voltage, RPM, Wattage, Temperature, BMS, Advanced), BLDC, DC, FOC (General, Sensorless, Hall Sensors, Encoder, HFI, VSS, Filters, Offsets, Field Weakening, Advanced), PID Controllers, Additional Info, **Other** (`l_max_erpm_fbrake_cc`, `sl_max_fullbreak_current_dir_change`, `motor_description`, `motor_quality_description`, which VESC Tool's grouping doesn't place).
  - App: General, PPM, ADC, UART, VESC Remote, NRF, IMU, PAS, exactly VESC Tool's.
  - Left out: only appconf `new_parameter`, an untyped placeholder in VESC Tool's XML that is never sent to the controller.
- **Transfer** (`core:vesc`): `VescController.readConfig(kind, base)`, `readDefaultConfig(kind, base)`, `writeConfig(kind, config)` (COMM_GET/SET_MCCONF/APPCONF and the `_DEFAULT` reads), forwarded for CAN controllers, `configWriteTimeout` 5 s for the flash write. Tests: round trip, VESC-Tool-only fields kept from the base, per-motor configs on a dual board, wrong layout refused on read (`SignatureMismatchException`) and ignored on write (timeout plus the firmware's printed warning).
- **Simulator** (`core:sim`): stores an mcconf and appconf per motor (7.01 defaults, `controller_id` set per motor), answers get, get-default and set, and rejects other layouts like the firmware.
- **UI** (`app/.../ui/settings/`): Settings destination (second in the nav). Motor / App tabs, the section list with setting counts and pending-change counts, a page per subgroup with VESC Tool's separators, one row per setting (value scaled by `editorScale` / percentage with its suffix and decimals; switches for bools), and an edit dialog per type (number with range check via `ConfigDefinition.validate`, enum radio list, bitfield checkboxes, text) that also shows VESC Tool's description, the value on the controller, and the parameter name. Pending edits are marked on rows; action bar: Read, Defaults (firmware defaults as edits), Revert, Write (n). Write validates everything, writes, then reads back what the firmware applied. Selecting another CAN controller or reconnecting resets the settings. A layout mismatch shows a notice and blocks reading and writing.

- **Firmware 6.05, 6.06, 7.00 and 7.01** (2026-10-16, lead session): definitions for all four are bundled (copied from VESC Tool `res/config/` at `b5fcf93`), and `BundledDefinitionsTest` checks every signature against `confgenerator.h` on the firmware's release branches. The settings pick the series matching the reported firmware version; if the controller's config signature says otherwise (test builds, forks, a CAN controller on other firmware), `BundledDefinitions.versionWithSignature` finds the bundled layout that matches and the read is retried with it. Only a layout we don't bundle is blocked.

## Next

1. Emulator pass in light mode, at font scale 1.6 and on a tablet width; check the edit dialog with the keyboard up and long enum lists (e.g. `app_to_use`).
2. Confirm before writing (list of changes, from `Config.diff`), and confirm before Defaults.
3. Search across all settings (VESC Tool mobile has none; desktop has a filter).
4. Save and load config backups (VESC Tool XML) so the VESC-Tool-only fields (motor brand, description, quality) persist; today they live only in memory for the session.
5. Unit tests for `ParamFormat.kt` (scale, percentage, parse with comma decimals, bitfield names) once the app module has a test source set.

## Unverified

- Nothing has been written to a real controller. Write and read-back are tested only against the simulator, which uses the same definitions, so a layout mistake would be shared.
- Descriptions are rendered with `Html.fromHtml` after dropping the Qt `<head>`; images and tables in some descriptions were not checked.
- The decimal keyboard on some devices may lack a minus key, which negative settings (e.g. `l_current_min`) need.

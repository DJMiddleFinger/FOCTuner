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

- **Charts, review and clarity** (2026-11-01): charts of what the settings do above the settings they show (current limits, battery voltage, ERPM, MOSFET and motor temperature, duty cycle, field weakening, PPM mapping, throttle curve and ramping for PPM, ADC and VESC Remote), drawn with the controller's values dashed under them while edits are pending, and previewed live in the edit dialog. Write first lists every change (on the controller → new) for review. Pending rows say what the value was. The section list marks pages with a chart.
- **Percentages fixed** (`ParamFormat.kt`, tested in `ParamFormatTest`): `editAsPercentage` doubles are a whole percentage of max(|min|, |max|) without `editorScale`, as VESC Tool's ParamEditDouble shows them. Before, Acceleration Temperature Decrease showed 1500 % (15 %), ADC Throttle Expo −50 % (−10 %), and ERPM Limit Start 80.00 %.
- **Input wizard** (ADC): listed `cc_button_inverted` and `rev_button_inverted`, which no bundled firmware has (6.05–7.01 use the `buttons` bitfield), so its setup page crashed; it now lists `buttons`, and settings a firmware lacks are left out.

## Next

1. Emulator pass at font scale 1.6 and on a tablet width; check the edit dialog with the keyboard up and long enum lists (e.g. `app_to_use`).
2. Confirm before Defaults (Write already reviews every change).
3. Search across all settings (VESC Tool mobile has none; desktop has a filter).
4. Save and load config backups (VESC Tool XML) so the VESC-Tool-only fields (motor brand, description, quality) persist; today they live only in memory for the session.
5. More `ParamFormat.kt` tests: parse with comma decimals, bitfield names.
6. Charts for BMS limits and the ADC voltage mapping (it depends on the control type and its buttons). Check the duty cycle and field weakening charts against firmware behavior on hardware.

## Unverified

- The charts follow the firmware source (`utils_math.c`, `mc_interface.c` update_override_limits, `mcpwm_foc.c` field weakening, `servo_dec.c`/`app_ppm.c`); none has been compared against a real controller's behavior. The current scale settings (`l_current_max_scale` etc.) are shown on the current limits bars but not applied to the derating charts.

- Nothing has been written to a real controller. Write and read-back are tested only against the simulator, which uses the same definitions, so a layout mistake would be shared.
- Descriptions are rendered with `Html.fromHtml` after dropping the Qt `<head>`; images and tables in some descriptions were not checked.
- The decimal keyboard on some devices may lack a minus key, which negative settings (e.g. `l_current_min`) need.

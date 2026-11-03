# Config safety (wave 1): progress

Branch `claude/config-safety-bab75b`. Scope: `docs/ROADMAP.md` wave 1 `config-safety`. Second run stopped at the usage guard (75% of the 5-hour window) with scope items 1, 3, 4, 5 and most of 2 done; the three Start-page tools are left. Then main (logging and firmware-dl merged, `f509b63`) was merged in (`03dd4bb`), green; stopped again at 82% of a 90% guard before starting the tools.

## Done

`./gradlew test :app:testDebugUnitTest :app:assembleDebug` green at every commit.

- **Rules for dangerous combinations** (`core:config` `ConfigRules.kt`, `ConfigRulesTest`): `ConfigWarning` is data: the settings it reads (`params`), the rows that show it (`rows`, narrower where the inputs aren't worth changing), the condition, and its VESC Tool/firmware source in KDoc. `Config.writeWarnings(limits: HardwareLimits?)` evaluates them; `byParam()` maps rows to warnings; `batteryVoltage()` (cells × the per-cell range in `si_battery_type`'s enum name) and `reachableErpm()` (FOC, full battery, max duty, overmodulation, flux linkage). Rules: VESC Tool 6.05's three, battery cutoff order / below empty / above full / full battery above `l_max_vin` / cutoff end below `l_min_vin`, FET and motor temperature order, duty order, unreachable ERPM, and current/voltage/ERPM/duty above `HardwareLimits`.
- **Warnings in the UI**: `ConfigPaneState.warnings` (from the edited config); rows list theirs under the name in the warning color (`WarningText.kt` fills in volts, cells and ERPM); the review dialog shows a "Check before writing" band above the changes. Strings in `res/values/strings_safety.xml`.
- **Sliders and chart handles keep their range** while held: `NumberTracks.track(..., held)`; `NumberSlider` holds the track during drags and while −/+ is pressed (widens after release, or at once when a held step leaves it); chart handles keep their snap grid until released (`ChartBlock(onRelease)`). `ParamRangeTest.aHeldTrackKeepsItsScaleUntilReleased`.
- **Backed-up writes** (`core:vesc` `ConfigBackups.kt` `writeWithBackup`, `ConfigSafetyTest`): reads both configs fresh, saves them as an automatic backup (`AUTOMATIC_BACKUPS` = 10 kept per UUID), applies only the edited settings over the fresh read (a change made elsewhere since the read survives), checked write, motor config first. `Config.restoredFrom(saved)` (`ConfigWriteCheck.kt`) takes a backup's values only where they differ beyond VESC Tool's tolerance, so the XML's 6 significant digits don't show up as changes or cost precision; `restoreConfigs` uses it too. `ConfigBackupStore.save` refuses an empty UUID.
- **Settings model** (`SettingsModel.kt`): gets the selected controller's `FirmwareInfo` (`SessionViewModel.targetFirmware`, CAN targets included) and `ConfigStorage` (`ui/tools/configtools/ConfigStorage.kt`: backups, `ConfigCache`, `profiles.properties`, one per process). Read base = this session's read, else the cache, else defaults; cache saved after every read and write. Write → `writeWithBackup` → notice "Written…" with **Undo**, or the settings the controller didn't take (by long name). Undo and History restores build a `RestoreReview` (per config: stored → restored, changes, warnings) and write only after the review dialog is confirmed, through the same backed-up write.
- **History page** (`HistoryPage.kt`, settings ⋮ → History): the selected UUID's backups newest first (automatic ones "Before a write"), date and firmware, what restoring would change now ("Restoring changes 3 settings: …" / "Same as on the controller now"), Restore (review first), Delete (confirm).
- **Files** (`ConfigFiles.kt`, settings ⋮ → Save to file / Load from file): VESC Tool XML through the system picker; Save writes the edited config; Load reads over the stored config into pending edits (tolerant merge) and lists file notes (config version, unknown names, missing settings, out-of-range values). The action bar is now Read · Revert · Write · ⋮ (Defaults moved into ⋮).
- **Search** (`SettingsSearch.kt`, `SettingsSearchTest`): search button at the end of the group picker; searches both configs (long name, name, description with HTML stripped; every word must match; long-name matches first). Picking a result jumps to its group/subgroup, switches Motor Cfg ↔ App Cfg when needed (`SettingsScreen(onShow)`, `SettingsState.jump`), scrolls to the row and flashes it.
- **Fixed: `ConfigXml.read` failed on Android.** Android's SAX parser doesn't recognize `disallow-doctype-decl`, so every XML read threw on devices (cache, backups, loaded files) while JVM tests passed. A DOCTYPE is now refused by a check before parsing.
- **Checked on Pixel_7_API_35** in demo mode: Cutoff End warning on its row (dark), review dialog with warnings, write → Undo notice → undo review → value back (dark), History listing, search over both configs and the jump from Motor Cfg into App Cfg › General (light). Screenshots `docs/screenshots/config-safety-warning-dark.png`, `-review-dark.png`, `-undo-dark.png`, `-search-dark.png`, `-jump-light.png`, `-history-light.png`. The emulator was left in dark mode.

## Done (wave 1 finish, lead, 2026-11-03, branch `claude/wave1-finish`)

- **Backups tool** (`ui/tools/configtools/BackupsTool.kt`, Start › Configuration): Back up all (optional name) and Restore all (newest named/manual backup per UUID on the bus, confirm listing each controller's backup; each controller gets an automatic backup first), results per controller, all saved backups across controllers with Delete / Delete all. Restoring one backup with a review stays in Settings › History.
- **Profiles tool** (`ProfilesTool.kt`): list with the in-use mark from a fresh mcconf read, Add (from the current limits) / Edit (name, speeds in the app's units, motor/brake current %, power and regen W, empty = no limit), Delete, Use until restart / Use permanently (confirm with the values).
- **Multi settings tool** (`MultiSettingsTool.kt`): tabs per `MultiSettingGroup`, `ParamRow`s seeded from the selected controller's mcconf, "Write <group> to all" (the whole group as shown, as VESC Tool does) with a confirm listing the controllers; per controller an automatic backup, then `writeMotorParams`; results per controller.
- `ConfigStorage.base(uuid)` gives the cache-backed base the tools pass to `backUpConfigs`. DESIGN.md has the settings page conventions.
- Checked on Pixel_7_API_35 in demo mode (attached + CAN 1): back up all, restore all, add and apply a profile (shows "in use"), write Limits to all. Screenshots `docs/screenshots/config-tools-{backups,profiles,multi}-dark.png`.

## Left out on purpose

- `HardwareLimits` still has no source, so the four hardware rules never fire; the firmware clamps anyway and the write notice lists what it changed. Add a per-hardware table from bldc `hwconf/**` if users ask for warnings before the write.
- Battery cutoff and input currents in profiles (vesc_tool #432), profile reordering, per-backup XML export (Settings › Save to file exports the edited config), Kp/Ki from Ts in Multi settings.

## Unverified

- Battery chemistry ranges come from the firmware's enum names; `mc_interface_get_battery_level` uses 3.2 V/cell as empty for Li-ion. `ERPM_UNREACHABLE` ignores field weakening.
- Save to file and Load from file were not run on the emulator (system picker); the reading path is the one backups use, which was checked.
- Switching the system theme while connected went back to Connect once; another session had just installed its build at the same time, so the cause isn't clear.
- The −/+ hold (track kept while pressed) is checked by unit test only; on the emulator each tap stepped once.
- TalkBack wasn't run on the new rows, dialogs, History page or search.

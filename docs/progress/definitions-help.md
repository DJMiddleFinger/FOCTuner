# Definitions and help (wave 2): progress

Branch `claude/amazing-franklin-p8khnz`. Roadmap row `definitions-help`. Sources: vesc_tool master `ed54d60` (`res/config/*`, `configparams.cpp`), bldc `confgenerator.h` at the tags 3.55 to 5.02 and the branches release_5_03, release_6_00, release_6_02.

## Done

`./gradlew test :app:assembleDebug` green. No emulator or controller run.

- **Bundled definitions**: every series VESC Tool ships, 3.55 to 7.01 (25 series, `core/config/src/main/resources/config/`), copied unchanged. The APK grows by about 0.9 MB compressed.
- **Signatures**: `BundledDefinitionsTest` checks all 25 against the firmware's `MCCONF_SIGNATURE` / `APPCONF_SIGNATURE`. Two differ: VESC Tool's 3.58 and 3.62 app definitions are newer than the firmware tags; the tags' layouts are 3.57's and 3.61's, which the signature fallback finds.
- **Old-file quirks**, read the way VESC Tool reads them: before 4.01 the hall table default is -1 on a uint8 wire (taken as 255, what VESC Tool sends); 5.00 and 5.01 write one default as "1,000" (Qt's `toInt` gives 0, so do we).
- **Unbundled firmware** (`core/vesc` `RemoteDefinitions.kt`): on a layout mismatch with no bundled match, Settings fetches `res/config/<version>/parameters_<kind>.xml` from VESC Tool's repository (raw.githubusercontent.com, HTTPS, no redirects), uses it only if its signature equals the controller's, keeps it in `filesDir/definitions/<version>/` and registers it (`BundledDefinitions.register`) so the other tools' signature fallback finds it too. Kept files are registered again on the next mismatch; damaged ones are deleted. Error messages say whether the phone was offline, VESC Tool has no file for that version, or the file doesn't match (test or custom build).
- **Help** (`ui/settings/help/SettingHelp.kt`, `strings_help.xml`): plain-language summary and typical value for 47 of the most used motor and app settings, shown in the setting dialog above VESC Tool's description. `SettingHelpTest` checks every name exists in the 7.01 definitions.

## Next

1. Emulator pass: open a few helped settings (light/dark, large font) and check the dialog still scrolls cleanly with the chart.
2. Search could match the help text too (`SettingsSearch.kt` takes a description function).
3. Ask a rider or two to read the typical values; they are conservative ranges, not tuning advice for a specific build.
4. Older firmware (before 5.x) may lack commands other screens use; that is unchecked.

## Unverified

- No controller on firmware older than 6.05 has been read with the new definitions.
- The download path has only run against a fake fetcher; the real GitHub fetch is the same `UrlConnectionFetcher` the firmware archive uses.

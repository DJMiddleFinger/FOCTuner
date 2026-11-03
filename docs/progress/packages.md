# Packages (wave 2): progress

Branch `claude/amazing-franklin-p8khnz`. Roadmap row `packages`. Sources: vesc_tool master `ed54d60` (`codeloader.cpp` `downloadPackageArchive` / `reloadPackageArchive` / `shouldShowPackage`, `pages/pagevescpackage.cpp`, `pages/pagecustomconfig.cpp`), vesc_pkg master (every `pkgdesc.qml`, `refloat/src/main.c`, `state.c`, `footpad_sensor.c`).

## Done

`./gradlew test :app:assembleDebug :app:lintDebug` green. No emulator or controller run.

- **Store** (`core/vesc` `PackageStore.kt`): `http://home.vedder.se/vesc_pkg/vesc_pkg_all.rcc` (about 1 MB, plain HTTP as in VESC Tool) fetched whole, read with the firmware archive's `RccIndex`, every `/vesc_packages/<folder>/*.vescpkg` parsed (Qt-compressed entries inflated, zstd entries skipped), `lib_*` folders marked as libraries, sorted by name, kept in `filesDir/packages/` for offline use.
- **Compatibility** (`PackageCompatibility.kt`): each package's `isCompatible(fwRxParams)` from `pkgdesc.qml` runs in a small evaluator for the JavaScript the packages use (var, assignment, if/else, return, `== != === !== && || !`, `fwRxParams.hw / fwName / major / minor / hwTypeStr()`, `toLowerCase / toUpperCase`). Untaken branches aren't evaluated. Anything else is `Unknown` and the package stays visible with "not checked". Checked once against the live store (2026-11-03, 41 packages): every installable package gets a definite verdict that matches its source, for a 60_MK6, a VESC Express and a VBMS32.
- **Packages tool** (Tools, Operations): filter (name and description), "show packages for other hardware", "show libraries", refresh with the time of the kept store, open a `.vescpkg` file, uninstall the current package (confirmation). Package page: compatibility notice, what it contains (LispBM, a VESC Tool screen this app doesn't run), the Markdown description, Install with a confirmation that says what is replaced and where the store came from, and the install steps with progress.
- **Package settings** (Tools, Configuration): every custom config the controller reports, definition read from the controller, edited with Settings' `ParamRow` / `ParamDialog`; Read, Defaults, Revert, Write (confirmation lists the changes), then a read-back listing values the package didn't take.
- **Refloat** (Tools, Operations; `core/vesc` `Refloat.kt`): INFO v2 (name, version), the legacy realtime data (`COMMAND_GET_RTDATA`: state, tiltback reason, footpad and its two ADCs, pitch, roll, balance current, all setpoints, beep reason), LOCK and HANDTEST, each with a confirmation. Polled at 10 Hz. Links to Package settings for the tune; the package page links to this screen.
- **Simulator**: `DemoPackages` (a three-package store for demo mode) and `RefloatSim` (INFO, realtime, lock, hand test on custom app data starting with 101; other custom app data is still echoed).
- Tests: `PackageStoreTest` (3: real pkgdesc scripts, unsupported constructs, bundle read/keep/offline), `RefloatTest` (2), `MarkdownTest` (2, app).

## Next

1. Emulator pass in demo mode: store list, install Demo Lights and watch the steps, Package settings with the sim's demo custom config, Refloat screen.
2. Hardware: install Refloat from the store on a real controller, edit its tune in Package settings, compare the Refloat screen with VESC Tool's.
3. Refloat's newer realtime data (`COMMAND_REALTIME_DATA` with its ID list) carries more (speed, battery, motor temps); the legacy command was chosen because every Refloat and Float version answers it.
4. LispBM `pkg::` imports in the editor can now resolve from the kept store.
5. Cache custom config definitions per controller (VESC Tool 6.05 does) to skip the download on every open.

## Unverified

- Refloat's reply layout is read from its source; no Refloat board has answered this app yet.
- Packages whose QML is the main feature (dashboards for displays) install fine but show nothing here beyond their settings.

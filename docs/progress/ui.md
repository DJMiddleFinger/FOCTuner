# UI: progress

Owner paths: `app/src/main/kotlin/app/foctuner/ui/**`, `MainActivity.kt`, `app/src/main/res/**`, `app/src/main/assets/licenses/`, `docs/DESIGN.md`.

## Done

- Design system: `ui/theme/` (FocColors, FocType with bundled IBM Plex, FocTheme mapping every Material role, Spacing, preview annotations). Tokens and rules in `docs/DESIGN.md`. Contrast checked (AA) for both themes.
- Resources: Plex TTFs (`res/font/`), OFL license (`assets/licenses/`), window themes `Theme.FOCTuner` (values + values-night), adaptive launcher icon (three phase currents; mipmap-anydpi-v26: plain mipmap-anydpi fails AAPT linking here, so the ObsoleteSdkInt warning stays), icon set `ic_nav_*`, `ic_pin*`, `ic_copy`, `ic_enter`, `ic_history`, all copy in `strings.xml` incl. every FaultCode.
- Components: `ui/components/` Readout, Numbers (fixed decimals, real minus), Sparkline (Canvas), SectionHeader/Hairline, Notice, buttons.
- Screens (stateless, state class + callbacks, previews dark/light/large-font, tablet for overview): `ui/connect/ConnectScreen`, `ui/overview/OverviewScreen` (+ FaultStrings), `ui/terminal/TerminalScreen`, `ui/controllers/ControllersScreen`, shell `ui/shell/ConnectedShell` (header, bottom bar, rail ≥600dp).
- Manifest `<application>` theme/icon/roundIcon are owned by this session (lead, 2026-10-15); set to `@style/Theme.FOCTuner` and `@mipmap/ic_launcher`. Lint: 0 errors; NonObservableLocale fixed by upper-casing with `LocalConfiguration.current.locales[0]`.

- Wiring: `ui/session/SessionViewModel` (connect with firmware check, 10 Hz polling with 60 s trends and staleness, terminal, CAN ping + firmware info per id, controller selection, link-drop handling), `ui/session/DemoRide` (drives the simulated motor in a loop through the real protocol), `ui/connect/ConnectViewModel` over `link.Links` (permission/enabled status, 15 s scan, `LinkDevice` → `DeviceEntry`, connect by address), root `ui/FocApp.kt` (connect route with permission and Bluetooth-enable launchers, connected route with BackHandler), `MainActivity` (edge-to-edge, FocTheme).
- Emulator (Pixel 7, API 35) pass in dark and light: Connect, Overview (demo on SimulatedVesc, values and trends move), Terminal (hw_status answered by the simulator), Controllers (two controllers from the simulated dual board). Screenshots in `docs/screenshots/`. Fixed after looking: tighter tracking on mono readouts, CAN section label.

## Next

1. Recent and pinned devices: persist successful connections (SharedPreferences: kind, id, name, last connected, pinned), fill `ConnectState.recent`, wire `onTogglePin` (currently a no-op in `FocApp.kt`), connect from a recent entry by rebuilding the `LinkDevice` from the stored kind/id.
2. Check the large-font and tablet layouts on the emulator (`adb shell settings put system font_scale 1.6`; a 10" AVD or `wm size 1600x2560`), and the IME + bottom bar behavior on the terminal screen.
3. Check on a cold start in dark mode that the window background is graphite, not white (manifest now uses `Theme.FOCTuner`). The launcher icon has been checked in the app drawer.
4. Unit tests for `Numbers.kt` (rounding to zero, minus sign, locale grouping) and the trend buffer.
5. Real hardware: confirm `RealtimeValues.timedOut` behavior, BLE scan results and failure copy.

## Open questions

- `app/foctuner/AppGraph.kt` is ours if a shared object becomes necessary. Not created yet: the two ViewModels are activity-scoped AndroidViewModels and own `Links`/`VescClient` themselves. Add it when a second consumer (e.g. a foreground service) needs the session.
- `RealtimeValues.timedOut` is true whenever no remote is sending, so it is shown as a quiet caption on the Motor header, not a warning. Confirm on hardware.

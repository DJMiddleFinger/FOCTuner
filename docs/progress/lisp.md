# LispBM scripting: progress

Owner: the lisp-editor side session. Branch `claude/feat-lisp-editor-1889c4` (main merged at 8c70d3c).

## Done

Client (`core/vesc`), all against bldc master `commands.c`, `lispif.c`, `flash_helper.c` and vesc_tool master `codeloader.cpp`, `commands.cpp`:
- `LispCode.kt`: VESC Tool's stored format: `imports()` (same line rules as `getImportFromLine`), `reduce()`, `pack()` (u16 flags, code, 0, i16 count, tag/offset/length table, 4-byte aligned zero-padded files, offsets from after the flags), `image()` (u32 length without flags + CRC16 over flags and code), `unpack()`, `maxImageSize()` (128 KiB − 6 on STM32, 8 MiB − 6 otherwise).
- `Lisp.kt`: `eraseLisp`, `uploadLisp` (erase size + 100, 384-byte chunks, 1 s × 5 tries), `readLisp` (0-length size probe so code under 10 bytes reads too, then 400-byte chunks, 1.5 s × 5), `setLispRunning`, `lispStats(allBindings)`, `sendLispRepl`, `streamLisp(code, mode)` with the firmware's result codes as messages, `lispPrints`, `REPL_SPACING`. Write/read/stream replies are matched by their offset, so a late reply to an earlier try is skipped instead of shifting every later chunk.
- `ScriptPlot.kt`: `plotEvents` (COMM_PLOT_INIT / ADD_GRAPH / SET_GRAPH / DATA).

Simulator (`core/sim`): `LispSim.kt` (128 KiB flash with header/CRC check and bit-clearing writes, start/pause, stats with no reply before start, 300-byte binding limit and vt filter, REPL drop within 0.5 s, `:help` `:info` `:env` `:reset` `:pause` `:continue`, streaming with retransmit dedupe), `LispEval.kt` (numbers, arithmetic, def, print, plot extensions). One instance per board. `SimulatedVesc`: one `lisp` field and dispatch lines.

UI (`app/.../ui/tools/lisp/`, Tools tab entry "LispBM scripting", strings in `strings_lisp.xml`):
- `LispWorkspace` (activity-scoped ViewModel, so unsaved code survives a dropped link; drafts in `filesDir/lisp-drafts`, tab index and settings in SharedPreferences "lisp"): tabs, recent files (30, persisted URI grants), examples, auto-run, reduce, stream mode, font size, imports folder, poll mode/rate, packing with imports read from a picked folder (SAF tree). `pkg::`/`pkg@` imports are refused with a message (no package store yet).
- `LispModel` (per connection): console capped at 5000 lines, REPL queue spaced for the firmware's 0.5 s rule, upload/stream/read/erase with progress and cancel, run/stop, stats polling (skipped during transfers), binding plots (last 5 s), script plot buffer (2000 points per graph, published every 100 ms).
- Editor (`CodeEditor`, `LispSyntax`, `LispHighlighting`, `CodeEdits`): `BasicTextField` + memoized `VisualTransformation`; per-line tokens cached by line text (strings/comments end at the line end like VESC Tool's highlighter); keywords/constants/builtins from VESC Tool's lisp.xml (`assets/lisp/symbols.txt`), numbers with LispBM suffixes, strings, comments; matching bracket and find matches drawn behind the text (no restyle on cursor moves); line numbers with the current line marked; no wrapping, sideways scroll; auto-indent; completion strip; find/replace; comment toggle; duplicate lines; undo/redo (1 s bursts); text size; VESC Tool's Ctrl shortcuts.
- Panes: Editor (tabs, actions Upload/Stream/Run/Stop), Console (prints, REPL with history, `:help`, copy, clear), Stats (poll Off/VT/All, rate, refresh, CPU/heap/memory, bindings, tap to plot, script plot), Files (recent with filter/remove/clear, 66 examples). Menu: new/open/save/save as/close, undo/redo, find, comment, duplicate, evaluate selection (REPL, or stream when over 400 chars), text size, read (opens a tab, offers to save imports to a folder), erase, code size (with/without reduction), imports folder, toggles, stream modes, help.
- Confirmations say what happens for upload (and run), run, stream, erase, closing an unsaved tab, clearing recent.
- Examples and symbols bundled from vesc_tool f1e15cb (GPL-3.0, compatible with our GPL-3.0-or-later), notice in `assets/licenses/VESC-Tool-LispBM.txt`.

Tests: `LispCodeTest` (7), `LispTest` (11), `ScriptPlotTest` (1) in core/vesc; `LispSyntaxTest` (9) and `LispEditorTimingTest` (1) in app (adds `kotlin-test-junit` to the app's test dependencies).

Measured (JVM, `app/build/reports/lisp-editor-timing.txt`, 3000 lines / 90 KB from the examples): cold tokenize + style 1.2 ms, a keystroke with the line cache 0.2 ms, bracket match 1.4 µs.

## Next (in order)

1. Emulator/device pass (lead): open the tool on the simulator, upload an example, run, REPL `(+ 1 2)`, stats with polling, stream with each mode, read back, erase; check dark/light and large font. Then a 3000-line file (paste an example repeatedly): typing and scrolling must stay smooth; the text layout of the whole field per keystroke is the unmeasured part. If it lags, split layout by paragraph (one `BasicTextField` per top-level form in a `LazyColumn`) or move to the experimental `TextFieldState` + `OutputTransformation.addStyle` path.
2. Lint (`./gradlew :app:lintDebug`) and fix anything in `ui/tools/lisp`.
3. Package imports (`pkg::name@file.vescpkg`, `pkg@file.vescpkg`) once the package store exists (`codeloader.cpp` `unpackVescPackage` + `lispUnpackImports`).
4. Script plot extras VESC Tool has: history limit control, per-graph visibility, scatter mode, CSV export.
5. Movable tabs and a file-changed-on-disk reload prompt (desktop VESC Tool) if users ask.

## Unverified

- No real controller yet; the sim is written from the same reading of the firmware, so a misreading would be shared.
- VESC Express (ESP32) erase/read behaviour not read (its commands.c moved); the erase size is assumed to matter there.
- SAF behaviour across providers (Drive, USB) for save "wt" mode, persisted grants and tree import resolution.
- The sim's REPL float format (`42.5f32`) is approximate.
- COMM_LISP_RMSG is not sent by VESC Tool's Lisp page (only by QML apps), so it isn't implemented.

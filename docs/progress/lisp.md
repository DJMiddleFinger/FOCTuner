# LispBM scripting: progress

Owner: the lisp-editor side session. Branch `claude/feat-lisp-editor-1889c4`.

## Done

- `core/vesc/LispCode.kt`: VESC Tool's code format (codeloader.cpp). `imports()` (same line rules as `getImportFromLine`), `reduce()`, `pack()` (u16 flags, code, 0, i16 import count, tag/offset/length table, 4-byte-aligned zero-padded files, offsets counted from after the flags), `image()` (u32 length without flags + CRC16 over flags and code), `unpack()` (packed or stored form), `maxImageSize()` (128 KiB − 6 on STM32, 8 MiB − 6 otherwise).
- `core/vesc/Lisp.kt`: `eraseLisp(size)`, `uploadLisp(packed, hardwareType, onProgress)` (erase size + 100, 384-byte chunks, 1 s × 5 tries), `readLisp(onProgress)` (0-length size probe, then 400-byte chunks, 1.5 s × 5 tries), `setLispRunning()`, `lispStats(allBindings)`, `sendLispRepl()`, `streamLisp(code, mode)` (3 modes, firmware result codes as messages), `lispPrints` flow. Write/read/stream replies are matched by the offset they carry, so a late reply to an earlier try is skipped.
- `core/sim/LispSim.kt` + `LispEval.kt`: 128 KiB flash area with header/CRC check, start/pause, stats (no reply until started, 300-byte binding limit, vt filter), REPL (drops lines within 0.5 s of the last expression, `:help` `:info` `:env` `:reset` `:pause` `:continue`, tiny evaluator: numbers, arithmetic, def, print), streaming. One instance for both motors (one firmware). `SimulatedVesc`: one `lisp` field plus dispatch lines.
- Tests: `LispCodeTest` (7), `LispTest` (11) against the simulator, including BLE-sized chunks and the CAN-forwarded second motor. `./gradlew :core:vesc:test :core:sim:test` green.

## Next (in order)

1. UI `app/.../ui/tools/lisp/`, registered in `Tools.kt`, strings in `strings_lisp.xml`. Model `LispModel(context)` holding editor text, file uri, dirty flag, console lines (cap 5000 like VESC Tool's `scripting/replMaxLines`), stats, poll mode (off / vt / all) and rate (1..?, default 10 Hz, skip a poll while one is in flight). REPL sends must be spaced by `REPL_SPACING` (queue them).
2. Editor: `BasicTextField` + `VisualTransformation` (or `OutputTransformation`) highlighting keywords/labels/globals/builtins from VESC Tool's `QCodeEditor/resources/languages/lisp.xml` (606 names in 4 sections: Keyword, Label, Global, Type), numbers, strings, `;` comments, bracket matching for `()`, `[]`, `{}`; line-number gutter; Plex Mono. Highlight only visible lines or cache per line; measure a 3000-line file.
3. Files: SAF open/save (`.lbm`, `.lisp`), recent list (filter, remove, clear) in SharedPreferences with persisted URI permissions; imports resolved relative to a user-picked folder (OpenDocumentTree); `pkg::`/`pkg@` imports need the package store (not built) and should say so.
4. Examples: VESC Tool's 66 `res/LispBM/Examples/*.lbm` are GPL-3.0 (repo license, no per-file headers), compatible with our GPL-3.0-or-later; bundle under `assets/lisp/examples/` with a NOTICE naming the source commit.
5. Actions as VESC Tool: Upload (confirm: replaces stored code and stops the running script; auto-run option; Reduce option; progress + cancel), Stream (mode picker), Read (offer to save imports), Erase (confirm), Run, Stop, Info (size and size after reduction), REPL with history and `:help`, clear console, stats (CPU/heap/mem bars, bindings with values; tap to plot the last 5 s like the Binding Plot tab).
6. Optional: the Lisp page's Experiment Plot tab (COMM_PLOT_INIT/DATA/ADD_GRAPH/SET_GRAPH, parsed in VESC Tool commands.cpp) overlaps PARITY's "Experiments / plots" row; agree with the lead who builds it.

## Unverified

- No real controller yet. Layouts are from bldc master `commands.c`, `lispif.c`, `flash_helper.c` and vesc_tool master `codeloader.cpp`/`commands.cpp` (read 2026-10-16); the sim is written from the same reading.
- VESC Express (ESP32) erase/read behaviour not read (its `main/commands.c` path moved); the size argument to erase is assumed to matter there.
- The REPL's float format in the sim (`42.5f32`) is approximate.
- COMM_LISP_RMSG is not sent by VESC Tool's Lisp page (only by QML apps), so it is not implemented.

# Config engine (`core:config`) progress

Owner: the config-engine side session. Scope: `core/config/**` and this file. No git state changes.

## Done

`./gradlew :core:config:test`: 34 tests pass (10 definition, 9 codec, 2 firmware layout, 8 validation, 5 diff). Whole-project `./gradlew test` also passes. All five increments of the brief are implemented; what is left is the report below.

**Increment 1: definitions, parser, signature**

- 7.01 `parameters_mcconf.xml` / `parameters_appconf.xml` copied byte-for-byte from `vedderb/vesc_tool` commit `b5fcf9358ee346f8508dd7a8586d33920e378723` into `core/config/src/main/resources/config/7.01/` (sha256 mcconf `18fa1528…5333`, appconf `54119948…8fb4`).
- Models (`ParamDefinition.kt`): `ParamType` 0-6, `WireType` 0-9, `ParamValue` (double / long / text), `ParamDefinition`, `ParamGroup` > `ParamSubgroup` > `GroupEntry` (`Param` or `Separator`).
- `ConfigXmlParser`: SAX, because StAX (`javax.xml.stream`) does not exist on Android. Strict like VESC Tool: unknown elements and bad numbers throw `ConfigDefinitionException`; DOCTYPE is rejected.
- `ConfigDefinition` validates everything that affects the wire layout at construction: duplicate params, unknown or repeated names in SerOrder, (type, vTx) pairs with no encoding, unknown names in the grouping.
- `Crc32c` and `ConfigDefinition.signature`. The final inversion is confirmed against `Utility::crc32c`, which returns `~crc`.
- `BundledDefinitions.load("7.01", ConfigKind.MCCONF | APPCONF)`, cached.
- `Config`: immutable value set, `Config.defaults(definition)`, `with(...)` edits that check the value kind.

**Increment 2: wire codec** (`WireFormat.kt`, `ConfigCodec.kt`)

- `ConfigCodec.serialize(config)`, `deserialize(definition, payload, base)`, `signatureOf(payload)`; `SignatureMismatchException` (has `expected` and `actual`) and `PayloadException` (truncated, unterminated string, trailing bytes).
- Scaled doubles round half away from zero in double precision (VESC Tool's `VByteArray`), not the truncating float cast of the firmware's `buffer.c`. `float32_auto` flushes `|v| < 1.5e-38` to 0 on the double, as VESC Tool does.
- Enum / bool are read as int8, bitfield as unsigned (matches `setParamSerial`). Ints are `Long` so uint32 stays unsigned (VESC Tool wraps it into a negative `int`).
- `serialize` throws `IllegalArgumentException("<param>: <reason>")` for a value that does not fit its wire type (int16 overflow, NaN, NUL in a string, ...) instead of wrapping silently like VESC Tool.
- Tests: every wire type against hand-computed bytes (checked with a Python `struct` reference), defaults round-trip byte-identical for mcconf and appconf, edited values round-trip, non-serialized params keep the base value, wrong-signature / truncated / oversized / unterminated payloads rejected, unencodable values rejected, and a literal port of `vbAppendDouble32Auto` (with `frexpf`) compared with `ByteWriter.f32Auto` on 2000 random doubles.

**Increment 3: independent goldens**

- Signature: the 7.01 definitions give mcconf `471314414` and appconf `2802465283`, equal to `MCCONF_SIGNATURE` / `APPCONF_SIGNATURE` in the firmware's generated `confgenerator.h` (`vedderb/bldc`, `confgenerator.c` last changed in `e22727a69cbc`, 2026-10-02). This checks the string construction, the CRC and the inversion.
- Layout: `core/config/tools/gen_config_layout.py` extracts (field, kind, scale) from the firmware's generated `confgenerator_deserialize_*` into `src/test/resources/layout/7.01/{mcconf,appconf}.layout`. `FirmwareLayoutTest` checks our serialize order, width, signedness (for ints) and scale against it for all 205 + 123 fields, and pins the payload sizes: **492 bytes** (mcconf) and **290 bytes** (appconf), summed from the firmware layout independently of the Kotlin code.

**Increment 4: validation** (`ConfigValidation.kt`)

- `ConfigDefinition.validate(name, value): ValidationIssue?` and `Config.validate(): List<ValidationIssue>`. Kinds: `OUT_OF_RANGE` (min/max of doubles and ints, NaN), `INVALID_OPTION` (enum index without a name, bool not 0/1, bitfield bits that do not exist), `TOO_LONG` (string over `maxLength`; 0 means unlimited), `NOT_ENCODABLE` (inside min..max but does not fit the wire type; only checked for serialized params).
- `ParamDefinition.clamp(value)`: nearest allowed value, NaN becomes the default; it ignores the wire type.
- All XML defaults validate clean, for both configs.

**Increment 5: diff** (`ConfigDiff.kt`)

- `Config.diff(other): List<ParamChange>` (name, from, to) in definition order. Serialized params are equal when they encode to the same bytes; unencodable or non-serialized params compare by value. Configs of different definitions throw `IllegalArgumentException`. VESC Tool's `checkDifference` uses an epsilon (1e-4, 1e-2 for double16); this is deliberately stricter and exact about what would change on the wire.

**Module README**: `core/config/README.md` (usage, GPL-3.0 attribution and commit of the bundled XML, how to add a series and regenerate the layout goldens).

## Next

For the lead (all outside `core/config/**`, so not done by this session):

1. Merge the findings below into `docs/SPIKES.md` section 2 and flip its heading from "understood, not yet implemented".
2. Add one attribution line to the top-level README (the XML files are vesc_tool's, GPL-3.0; details are in `core/config/README.md`) and list `core:config` as implemented.
3. Decide whether `core/config/tools/gen_config_layout.py` should move to the root `tools/` next to `gen_comm_ids.py`; if so update the path in `core/config/README.md` and the script's docstring.
4. Follow-ups that belong to later phases, not started: a device-side check that compares `ConfigCodec.signatureOf(reply)` with `definition.signature` in `core:vesc`; bundling 7.00, 6.06 and 6.05 definitions (each needs its own layout golden); a localized/plain-text rendering of `description` HTML for the UI; caching the parsed definitions if startup time on a phone matters.

## Unverified

- Byte-identity with a real controller: no captured `COMM_GET_MCCONF` packet or VESC Tool generated blob has been found or tested. The signature constants and the firmware serializer layout above are the closest independent references. The actual numeric encodings (rounding, float32_auto) are covered by unit tests against hand-computed and ported-algorithm values, not by firmware output.
- Whether the bundled 7.01 definitions match released 7.01 firmware: they come from vesc_tool `master` (the `res/config/7.01` files were last touched 2026-10-02 and 2026-10-14, while 7.01 is still in development).
- Behaviour on Android: SAX is available there, but the parser has only run on a desktop JVM, and resource loading from the APK has not been tried. Parsing the two 400 KB / 275 KB files at app start has not been timed on a phone.

## Findings that differ from `docs/SPIKES.md` section 2 (for the lead to merge; that file is not mine to edit)

- Only 205 of the 214 motor params are serialized; `motor_brand`, `motor_model`, `motor_weight`, `motor_sensor_type`, `motor_description`, `motor_quality_bearings`, `motor_quality_magnets`, `motor_quality_construction` and `motor_quality_description` are stored in the XML/file configs only (all 4 strings are among them). The app config has 123 of 124 serialized; `new_parameter` (type 0) is a leftover.
- `<Grouping>` entries starting with `::sep::` are headings, not parameters (16 per file). 13 motor params appear in more than one subgroup, and two serialized params (`l_max_erpm_fbrake_cc`, `sl_max_fullbreak_current_dir_change`) are in no subgroup.
- Array parameters are named `hall_table__0` and nested ones `app_ppm_conf.ctrl_type` in the XML (the C fields are `hall_table[0]`, `app_ppm_conf.ctrl_type`).
- The signature is confirmed: CRC32C is inverted at the end. Defaults are the `valDouble` / `valInt` / `valString` tags (there is no "default" tag).
- VESC Tool and the firmware disagree on rounding: `VByteArray::vbAppendDouble16/32` round half away from zero in double, `buffer_append_float16/32` truncate a float. So a value written by VESC Tool can differ by one wire unit from what the firmware would write for the same number. Reading is the same for both.
- Four serialized params declare a min/max the wire type cannot carry, so a large value would wrap in VESC Tool's `(qint16)` / `(qint32)` casts (read from `vbytearray.cpp`; not tried in VESC Tool itself): `foc_hfi_gain` (max 99, double16 scale 1000: limit 32.767), `foc_hfi_hyst` (max 500, scale 100: limit 327.67), `m_encoder_sincos_phase_correction` (±45, scale 1000: limit ±32.767) and `app_adc_conf.update_rate_hz` (max 100000 on uint16: limit 65535). `serialize` throws and `validate` reports `NOT_ENCODABLE` for these. The UI should show the tighter limit; the firmware cannot receive more.
- All XML defaults lie inside their own min/max, every enum default is a valid index, and bitfields all have 8 bit names.
- Whitespace in the XML is significant for `suffix` (e.g. `" A"`), so the parser does not trim strings.

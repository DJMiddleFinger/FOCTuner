# core:config

Data-driven motor and app config for VESC firmware: parses VESC Tool's parameter XML, computes the config signature, and converts between a `Config` and the bytes of `COMM_GET_MCCONF` / `COMM_SET_MCCONF` (and the app config equivalents). Pure JVM, depends only on `core:protocol`.

```kotlin
val def = BundledDefinitions.load("7.01", ConfigKind.MCCONF)
val config = ConfigCodec.deserialize(def, payloadAfterCommandId)   // SignatureMismatchException if the layout differs
val edited = config.with("l_current_max", 80.0)
val problems = edited.validate()                                   // ranges, options, wire fit
val changes = config.diff(edited)
val bytes = ConfigCodec.serialize(edited)
```

Compare `ConfigCodec.signatureOf(payload)` with `def.signature` before trusting a device: a different value means a different parameter layout, and the payload must be neither read nor written with this definition.

## Bundled definitions

`src/main/resources/config/7.01/parameters_mcconf.xml` and `parameters_appconf.xml` are copied unchanged from [vedderb/vesc_tool](https://github.com/vedderb/vesc_tool) (`res/config/7.01/`, commit `b5fcf9358ee346f8508dd7a8586d33920e378723`), copyright Benjamin Vedder, GPL-3.0-or-later, like the rest of this project.

To add a firmware series: copy its two files to `config/<series>/`, add the series to `BundledDefinitions.versions`, and regenerate the layout goldens (below) so `FirmwareLayoutTest` covers it.

## Tests against the firmware

`src/test/resources/layout/7.01/*.layout` is the wire layout of the firmware's own generated serializer. Regenerate with a checkout of `vedderb/bldc`:

```bash
python3 core/config/tools/gen_config_layout.py ../bldc/confgenerator.c core/config/src/test/resources/layout/7.01 "vedderb/bldc <commit>"
```

`ConfigDefinitionTest` also pins the 7.01 signatures to the `MCCONF_SIGNATURE` / `APPCONF_SIGNATURE` constants in the same firmware's `confgenerator.h`.

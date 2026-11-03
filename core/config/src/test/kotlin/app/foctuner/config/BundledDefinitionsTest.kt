package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundledDefinitionsTest {
    // MCCONF_SIGNATURE and APPCONF_SIGNATURE in confgenerator.h on vedderb/bldc: the tags 3.55 to 5.02 and the
    // release branches 5.03, 6.00 and 6.02 (read 2026-11-03), release_6_05, release_6_06, release_7_00 and master for
    // 7.01 (read 2026-10-16).
    private val firmwareSignatures = mapOf(
        "3.55" to (503309878L to 725102789L),
        "3.56" to (503309878L to 725102789L),
        "3.57" to (503309878L to 1962523771L),
        "3.58" to (503309878L to 1962523771L),
        "3.59" to (503309878L to 2074596585L),
        "3.60" to (503309878L to 543202478L),
        "3.61" to (503309878L to 583966563L),
        "3.62" to (503309878L to 583966563L),
        "3.63" to (3875075384L to 783041200L),
        "3.64" to (793836781L to 783041200L),
        "3.65" to (2967846088L to 783041200L),
        "3.66" to (1671830952L to 783041200L),
        "4.00" to (657785302L to 511269002L),
        "4.01" to (3632471335L to 2964134872L),
        "4.02" to (3632471335L to 1232755601L),
        "5.00" to (3698540221L to 2460147246L),
        "5.01" to (3698540221L to 2460147246L),
        "5.02" to (2211848314L to 3264926020L),
        "5.03" to (2525666056L to 3733512279L),
        "6.00" to (776184161L to 486554156L),
        "6.02" to (776184161L to 486554156L),
        "6.05" to (1065524471L to 2099347128L),
        "6.06" to (788332866L to 2099347128L),
        "7.00" to (1470992211L to 2384027283L),
        "7.01" to (471314414L to 2802465283L),
    )

    @Test
    fun everyBundledSeriesMatchesItsFirmware() {
        assertEquals(firmwareSignatures.keys.toList(), BundledDefinitions.versions)
        val mismatches = firmwareSignatures.flatMap { (version, signatures) ->
            listOfNotNull(
                "$version mcconf".takeIf { signatures.first != BundledDefinitions.load(version, ConfigKind.MCCONF).signature },
                "$version appconf".takeIf { signatures.second != BundledDefinitions.load(version, ConfigKind.APPCONF).signature },
            )
        }
        // VESC Tool's 3.58 and 3.62 app definitions are newer than the firmware tags; the tags' layouts are those of
        // 3.57 and 3.61, so a controller on either is still read through the signature fallback.
        assertEquals(listOf("3.58 appconf", "3.62 appconf"), mismatches)
        assertEquals("3.57", BundledDefinitions.versionWithSignature(ConfigKind.APPCONF, firmwareSignatures.getValue("3.58").second))
        assertEquals("3.61", BundledDefinitions.versionWithSignature(ConfigKind.APPCONF, firmwareSignatures.getValue("3.62").second))
    }

    @Test
    fun everyBundledSeriesRoundTripsItsDefaults() {
        for (version in BundledDefinitions.versions) {
            for (kind in ConfigKind.entries) {
                val definition = BundledDefinitions.load(version, kind)
                val wire = ConfigCodec.serialize(Config.defaults(definition))
                assertContentEquals(wire, ConfigCodec.serialize(ConfigCodec.deserialize(definition, wire)), "$version $kind")
            }
        }
    }

    @Test
    fun a606ConfigIsRefusedBy701DefinitionsAndFoundBySignature() {
        val wire = ConfigCodec.serialize(Config.defaults(BundledDefinitions.load("6.06", ConfigKind.MCCONF)))
        val mismatch = assertFailsWith<SignatureMismatchException> {
            ConfigCodec.deserialize(BundledDefinitions.load("7.01", ConfigKind.MCCONF), wire)
        }
        assertEquals("6.06", BundledDefinitions.versionWithSignature(ConfigKind.MCCONF, mismatch.actual))
    }

    @Test
    fun sharedLayoutsResolveToTheNewestSeries() {
        assertEquals("6.06", BundledDefinitions.versionWithSignature(ConfigKind.APPCONF, 2099347128L))
        assertEquals(null, BundledDefinitions.versionWithSignature(ConfigKind.MCCONF, 12345L))
    }
}

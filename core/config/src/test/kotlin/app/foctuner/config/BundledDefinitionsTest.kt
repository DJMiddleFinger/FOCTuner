package app.foctuner.config

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BundledDefinitionsTest {
    // MCCONF_SIGNATURE and APPCONF_SIGNATURE in confgenerator.h on vedderb/bldc's release branches
    // (release_6_05, release_6_06, release_7_00) and master for 7.01, read 2026-10-16.
    private val firmwareSignatures = mapOf(
        "6.05" to (1065524471L to 2099347128L),
        "6.06" to (788332866L to 2099347128L),
        "7.00" to (1470992211L to 2384027283L),
        "7.01" to (471314414L to 2802465283L),
    )

    @Test
    fun everyBundledSeriesMatchesItsFirmware() {
        assertEquals(firmwareSignatures.keys.toList(), BundledDefinitions.versions)
        for ((version, signatures) in firmwareSignatures) {
            assertEquals(signatures.first, BundledDefinitions.load(version, ConfigKind.MCCONF).signature, "$version mcconf")
            assertEquals(signatures.second, BundledDefinitions.load(version, ConfigKind.APPCONF).signature, "$version appconf")
        }
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

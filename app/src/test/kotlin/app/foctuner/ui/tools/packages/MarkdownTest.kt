package app.foctuner.ui.tools.packages

import app.foctuner.sim.DemoPackages
import app.foctuner.vesc.FirmwareInfo
import app.foctuner.vesc.PackageCompatibility.Verdict
import app.foctuner.vesc.PackageStore
import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownTest {
    @Test
    fun readsTheMarkdownPackagesUse() {
        val md = """
            # Refloat
            A **balance** package. See [the docs](https://example.org) ![logo](logo.png)
            for details.

            - first
            * second
            1. third

            ```
            (def a 1)
            ```
        """.trimIndent()
        assertEquals(
            listOf(
                MdBlock.Heading("Refloat"),
                MdBlock.Paragraph("A balance package. See the docs (https://example.org) for details."),
                MdBlock.Bullet("first"),
                MdBlock.Bullet("second"),
                MdBlock.Bullet("third"),
                MdBlock.Code("(def a 1)"),
            ),
            parseMarkdown(md),
        )
        assertEquals("A balance package. See the docs (https://example.org) for details.", firstParagraph(md, ""))
    }

    @Test
    fun theDemoStoreHasOnePackageForTheSimulator() {
        val rows = PackageStore.parse(DemoPackages.bundle())
        assertEquals(listOf("Demo Dash", "Demo Library", "Demo Lights"), rows.map { it.pkg.name })
        val sim = FirmwareInfo(7, 1, "60_MK6", "")
        assertEquals(listOf(Verdict.Incompatible, Verdict.Unknown, Verdict.Compatible), rows.map { it.compatibility(sim) })
    }
}

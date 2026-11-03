package app.foctuner.vesc.firmware.archive

import app.foctuner.sim.DemoArchive
import app.foctuner.sim.SimulatedArchiveHost
import app.foctuner.vesc.HardwareType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareArchiveTest {
    private val directory: File = createTempDirectory("firmware-cache").toFile()
    private val cache = FirmwareCache(directory)
    private val mk6 = ArchiveTarget("60_MK6", HardwareType.VESC)
    private val image701 = DemoArchive.image("60_MK6", "7.01")
    private val image605 = DemoArchive.image("60_MK6", "6.05")

    private val host = SimulatedArchiveHost().apply {
        addBundle(RealBundles.RELEASE, RealBundles.bundle(RealBundles.RELEASE, mapOf("/res/firmwares/60_MK6/VESC_default.bin" to image701)))
        addBundle(RealBundles.ARCHIVE, RealBundles.bundle(RealBundles.ARCHIVE, mapOf("/fw_archive/6.05/60_MK6/VESC_default.bin" to image605)))
    }
    private val archive = FirmwareArchive(host, cache)

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun listsEveryVersionNewestFirstFromTheIndexesAlone() = runTest {
        val listing = archive.listing(mk6)
        val versions = listing.entries.mapNotNull { it.version }

        assertEquals("7.01", versions.first())
        assertEquals(versions.sortedWith { a, b -> Changelog.compareVersions(b, a) }, versions)
        assertTrue(listOf("7.00", "6.06", "6.05").all { it in versions })
        val newest = listing.entries.filter { it.version == "7.01" }
        assertEquals(listOf("VESC_default.bin", "VESC_default_no_hw_limits.bin"), newest.map { it.file })
        assertTrue(newest.first().standard)
        assertTrue(newest.last().withoutLimits)
        assertFalse(listing.offline || listing.incomplete)
        assertTrue(host.servedBytes < 80_000, "served ${host.servedBytes} bytes to list the versions")
        assertTrue(host.requests.all { it.range != null || it.url == ArchiveUrls().archive }, "no whole bundle was requested")
    }

    @Test
    fun matchesDirectoriesThatStandForSeveralHardwares() = runTest {
        for (name in listOf("410", "411", "412")) {
            val entries = archive.listing(ArchiveTarget(name, HardwareType.VESC)).entries
            assertTrue(entries.isNotEmpty(), name)
            assertTrue(entries.all { it.hardware == listOf("410", "411", "412") || it.hardware == listOf(name) || name in it.hardware }, name)
        }
        assertTrue(archive.listing(ArchiveTarget("no_such_board", HardwareType.VESC)).entries.isEmpty())
    }

    @Test
    fun downloadsWithProgressChecksTheImageAndSavesIt() = runTest {
        val entry = archive.listing(mk6).entries.first()
        val events = archive.download(entry, mk6).toList()

        val progress = events.filterIsInstance<ArchiveDownload.Progress>()
        assertEquals(null, progress.first().total)
        assertEquals(image701.size.toLong(), progress.last().total)
        assertEquals(image701.size.toLong(), progress.last().received)
        assertEquals(progress.map { it.received }.sorted(), progress.map { it.received })
        val done = events.last() as ArchiveDownload.Done
        assertContentEquals(image701, done.image.image.bytes)
        assertEquals(listOf("60_MK6"), done.image.image.hardware)
        assertFalse(done.image.fromCache)
        assertEquals(app.foctuner.protocol.Crc16.compute(image701), done.image.crc16)
        assertTrue(archive.isSaved(entry))
        assertTrue(host.requests.none { it.served > 400_000 })
    }

    @Test
    fun aSavedImageNeedsNoConnection() = runTest {
        val entry = archive.listing(mk6).entries.first()
        archive.download(entry, mk6).toList()
        val requests = host.requests.size

        host.offline = true
        val done = archive.download(entry, mk6).toList().single() as ArchiveDownload.Done
        assertTrue(done.image.fromCache)
        assertContentEquals(image701, done.image.image.bytes)
        assertEquals(requests, host.requests.size)

        val listing = archive.listing(mk6)
        assertTrue(listing.offline)
        assertEquals(listOf(entry.key), listing.entries.map { it.key })
    }

    @Test
    fun aDamagedSavedImageIsFetchedAgain() = runTest {
        val entry = archive.listing(mk6).entries.first()
        archive.download(entry, mk6).toList()
        val saved = File(directory, "images/${entry.key}")
        saved.writeBytes(saved.readBytes().also { it[100] = (it[100] + 1).toByte() })

        val done = archive.download(entry, mk6).toList().last() as ArchiveDownload.Done
        assertFalse(done.image.fromCache)
        assertContentEquals(image701, done.image.image.bytes)
        assertContentEquals(image701, saved.readBytes())
    }

    @Test
    fun cancellingADownloadLeavesNothingBehind() = runTest {
        host.chunkDelay = kotlin.time.Duration.parse("2ms")
        val entry = archive.listing(mk6).entries.first()
        archive.download(entry, mk6).first { it is ArchiveDownload.Progress && it.received > 0 }

        assertFalse(archive.isSaved(entry))
        assertTrue(cache.saved("60_MK6").isEmpty())
        assertTrue(File(directory, "images").walkTopDown().none { it.isFile })
    }

    @Test
    fun refusesImagesForOtherHardwareOrThatDontNameTheirs() = runTest {
        val entry = archive.listing(mk6).entries.first()
        val wrongController = ArchiveTarget("75_300", HardwareType.VESC)
        assertEquals(ArchiveException.Reason.HardwareMismatch, assertFailsWith<ArchiveException> { FirmwareArchive.verify(entry, wrongController, image701) }.reason)

        val foreign = DemoArchive.image("75_300", "7.01")
        assertEquals(ArchiveException.Reason.HardwareMismatch, assertFailsWith<ArchiveException> { FirmwareArchive.verify(entry, mk6, foreign) }.reason)

        val anonymous = Random(1).nextBytes(4096)
        assertEquals(ArchiveException.Reason.Damaged, assertFailsWith<ArchiveException> { FirmwareArchive.verify(entry, mk6, anonymous) }.reason)
        assertEquals(ArchiveException.Reason.Damaged, assertFailsWith<ArchiveException> { FirmwareArchive.verify(entry, mk6, ByteArray(4096) { 0xFF.toByte() }) }.reason)
        assertEquals(ArchiveException.Reason.TooLarge, assertFailsWith<ArchiveException> { FirmwareArchive.verify(entry, mk6, ByteArray(8_000_001) { 1 }) }.reason)
        assertEquals(listOf("60_MK6"), FirmwareArchive.verify(entry, mk6, image701).hardware)
    }

    @Test
    fun aWrongImageUnderTheRightNameIsRefusedAndNotSaved() = runTest {
        val wrong = DemoArchive.image("75_300", "7.01")
        host.addBundle(
            RealBundles.RELEASE,
            RealBundles.bundle(RealBundles.RELEASE, mapOf("/res/firmwares/60_MK6/VESC_default.bin" to wrong)),
        )
        val entry = archive.listing(mk6).entries.first()
        val failure = assertFailsWith<ArchiveException> { archive.download(entry, mk6).toList() }

        assertEquals(ArchiveException.Reason.HardwareMismatch, failure.reason)
        assertFalse(archive.isSaved(entry))
    }

    @Test
    fun aServerWithoutRangeSupportIsNeverAskedForAWholeBundle() = runTest {
        host.ignoreRange = true
        val listing = archive.listing(mk6)

        assertTrue(listing.offline)
        assertTrue(listing.entries.isEmpty())
        assertEquals(0, host.servedBytes - host.requests.filter { it.url == ArchiveUrls().archive }.sumOf { it.served })
    }

    @Test
    fun oneUnreachableBundleLeavesTheRestListed() = runTest {
        host.failing += ArchiveUrls().archive + RealBundles.ARCHIVE
        val listing = archive.listing(mk6)

        assertTrue(listing.incomplete)
        assertFalse(listing.offline)
        assertEquals(listOf("7.01"), listing.entries.mapNotNull { it.version }.distinct())
    }

    @Test
    fun aRebuiltBundleIsReadAgainNotTrustedFromTheCache() = runTest {
        archive.listing(mk6)
        val before = host.requests.count { it.range?.endsWith("-") == true }
        archive.listing(mk6)
        assertEquals(before, host.requests.count { it.range?.endsWith("-") == true }, "unchanged bundles reuse the saved index")

        host.addBundle(RealBundles.RELEASE, RealBundles.bundle(RealBundles.RELEASE, emptyMap()).let { old ->
            val moved = RealBundles.header(RealBundles.RELEASE).also { it[11] = (it[11] + 1).toByte() }
            SimulatedArchiveHost.Sparse(old.length, mapOf(0L to moved))
        })
        val listing = runCatching { archive.listing(mk6) }
        assertTrue(listing.isSuccess)
        assertNull(listing.getOrThrow().entries.firstOrNull { it.version == "7.01" })
    }

    @Test
    fun bmsFirmwareComesFromTheRepositoryAndKeepsItsChecks() = runTest {
        val demo = DemoArchive.host()
        val unnamed = Random(2).nextBytes(8192)
        demo.addRepositoryFile("res/firmwares_bms/18s_light_o_18s_light_lmp/vesc_default.bin", unnamed)
        val bms = FirmwareArchive(demo, cache)

        val entry = bms.listing(ArchiveTarget("18s_light_lmp", HardwareType.VESC_BMS)).entries.single()
        assertEquals(ArchiveOrigin.Repository, entry.origin)
        assertEquals(listOf("18s_light", "18s_light_lmp"), entry.hardware)
        val done = bms.download(entry, ArchiveTarget("18s_light_lmp", HardwareType.VESC_BMS)).toList().last() as ArchiveDownload.Done
        assertContentEquals(unnamed, done.image.image.bytes)

        val named = bms.listing(ArchiveTarget(DemoArchive.BMS, HardwareType.VESC_BMS)).entries.single()
        assertEquals(ArchiveException.Reason.HardwareMismatch, assertFailsWith<ArchiveException> {
            bms.download(named, ArchiveTarget(DemoArchive.BMS, HardwareType.VESC_BMS)).toList()
            FirmwareArchive.verify(named, ArchiveTarget(DemoArchive.BMS, HardwareType.VESC_BMS), DemoArchive.image("other", "1.00"))
        }.reason)
        assertTrue(bms.listing(ArchiveTarget("nothing", HardwareType.VESC_BMS)).entries.isEmpty())
    }

    @Test
    fun vescExpressImagesComeFromTheRealEspBundleLayout() = runTest {
        val express = DemoArchive.image("VESC Express T", "6.05", size = 64 * 1024)
        host.addBundle(RealBundles.EXPRESS, RealBundles.bundle(RealBundles.EXPRESS, mapOf("/res/firmwares_esp/esp32c3/VESC Express T/vesc_express.bin" to express)))
        val target = ArchiveTarget("VESC Express T", HardwareType.CUSTOM_MODULE)

        val entries = archive.listing(target).entries
        assertEquals(listOf("vesc_express.bin"), entries.map { it.file })
        assertEquals(ArchiveOrigin.Express, entries.single().origin)
        val done = archive.download(entries.single(), target).toList().last() as ArchiveDownload.Done
        assertContentEquals(express, done.image.image.bytes)
        assertTrue(archive.listing(ArchiveTarget("Unknown module", HardwareType.CUSTOM_MODULE)).entries.isEmpty())
    }

    @Test
    fun theChangelogIsKeptForWhenThereIsNoConnection() = runTest {
        val demo = DemoArchive.host()
        val online = FirmwareArchive(demo, cache)
        val text = assertNotNull(online.changelog())
        assertTrue(text.startsWith("### 7.01"))

        demo.offline = true
        assertEquals(text, online.changelog())
        assertNull(FirmwareArchive(demo, FirmwareCache(Files.createTempDirectory("empty").toFile())).changelog())
    }
}

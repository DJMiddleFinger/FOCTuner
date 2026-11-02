package app.foctuner.prefs

import app.foctuner.transport.LinkDevice
import app.foctuner.transport.LinkKind
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentDevicesTest {
    private val board = LinkDevice(LinkKind.BLE, "C4:2B:91:0E:7A:3D", "Board")
    private val express = LinkDevice.tcp("192.168.4.1")

    @Test
    fun newestFirstAndReconnectingMovesToTheTop() {
        var list = RecentDevices.connected(emptyList(), board, at = 1)
        list = RecentDevices.connected(list, express, at = 2)
        assertEquals(listOf(express.key, board.key), list.map { it.key })

        list = RecentDevices.connected(list, board.copy(name = "Renamed"), at = 3)
        assertEquals(listOf(board.key, express.key), list.map { it.key })
        assertEquals("Renamed", list.first().name)
    }

    @Test
    fun pinnedStayFirstAndSurviveTheCap() {
        var list = RecentDevices.togglePin(RecentDevices.connected(emptyList(), board, at = 0), board.key)
        for (i in 1..10) list = RecentDevices.connected(list, LinkDevice.tcp("10.0.0.$i"), at = i.toLong())
        assertEquals(board.key, list.first().key)
        assertEquals(1 + RecentDevices.MAX_UNPINNED, list.size)

        list = RecentDevices.connected(list, board, at = 20)
        assertEquals(true, list.first().pinned)
    }

    @Test
    fun usbIsNotKept() {
        val usb = LinkDevice(LinkKind.USB, "/dev/bus/usb/001/004", "VESC")
        assertEquals(emptyList(), RecentDevices.connected(emptyList(), usb, at = 0))
    }

    @Test
    fun encodingSurvivesTabsNewlinesAndBackslashes() {
        val odd = LinkDevice(LinkKind.BLUETOOTH_CLASSIC, "00:11:22:33:44:55", "a\tb\\n\nc\\")
        val list = RecentDevices.togglePin(RecentDevices.connected(listOf(), odd, at = 42), odd.key)
        assertEquals(list, RecentDevices.decode(RecentDevices.encode(list)))
    }

    @Test
    fun unreadableLinesAreSkipped() {
        val text = RecentDevices.encode(RecentDevices.connected(emptyList(), board, at = 5)) + "\nWIFI\tx\t1\t0\tname\ngarbage"
        assertEquals(listOf(board.key), RecentDevices.decode(text).map { it.key })
        assertEquals(emptyList(), RecentDevices.decode(""))
    }

    @Test
    fun canTargetsRoundTripAndForget() {
        var targets = CanTargets.with(emptyMap(), "AABB", CanTarget(12, "CCDD"))
        targets = CanTargets.with(targets, "EEFF", CanTarget(3, "0011"))
        assertEquals(targets, CanTargets.decode(CanTargets.encode(targets)))
        assertEquals(CanTarget(12, "CCDD"), targets["AABB"])
        assertEquals(setOf("EEFF"), CanTargets.with(targets, "AABB", null).keys)
    }
}

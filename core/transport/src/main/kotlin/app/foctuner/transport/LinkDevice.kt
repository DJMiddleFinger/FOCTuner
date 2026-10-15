package app.foctuner.transport

enum class LinkKind { BLE, USB, BLUETOOTH_CLASSIC, TCP }

/**
 * Something a VESC can be reached through, as found by scanning or entered by hand.
 *
 * [id] identifies it within its [kind]: MAC address for Bluetooth, the system device path for USB,
 * `host:port` for TCP. [rssi] is only known for BLE.
 */
data class LinkDevice(
    val kind: LinkKind,
    val id: String,
    val name: String,
    val rssi: Int? = null,
) {
    val key: String get() = "$kind/$id"

    companion object {
        fun tcp(host: String, port: Int = TcpTransport.DEFAULT_PORT, name: String = host) =
            LinkDevice(LinkKind.TCP, "$host:$port", name)
    }
}

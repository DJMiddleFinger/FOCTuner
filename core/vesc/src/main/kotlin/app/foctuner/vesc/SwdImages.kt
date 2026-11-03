package app.foctuner.vesc

import app.foctuner.vesc.firmware.archive.HttpFetcher

/** A firmware image VESC Tool offers for an SWD target (`PageSwdProg::bmConnRes`), from its `res/other_fw/`. */
data class SwdImage(val name: String, val file: String)

/** The nRF images VESC Tool lists for each target; STM32 targets are programmed from a file here. */
object SwdImages {
    const val BASE_URL = "https://raw.githubusercontent.com/vedderb/vesc_tool/master/res/other_fw"

    fun forTarget(target: SwdTarget): List<SwdImage> = when (target.code) {
        2, 3 -> listOf(
            SwdImage("BLE, 16 MHz crystal, RX 1 TX 2 LED 3", "nrf51_vesc_ble_16k_16m_rx1_tx2_led3.bin"),
            SwdImage("BLE, 16 MHz crystal, RX 11 TX 9 LED 3", "nrf51_vesc_ble_16k_16m_rx11_tx9_led3.bin"),
        )
        4 -> listOf(
            SwdImage("BLE, 16 MHz crystal, RX 11 TX 9 LED 3", "nrf51_vesc_ble_32k_16m_rx11_tx9_led3.bin"),
            SwdImage("BLE TRAMPA, 32 MHz crystal, RX 2 TX 1 LED 3", "nrf51_vesc_ble_32k_32m_rx2_tx1_led3.bin"),
            SwdImage("BLE VESC built-in, 32 MHz crystal, RX 1 TX 2 LED 3", "nrf51_vesc_ble_32k_32m_rx1_tx2_led3.bin"),
            SwdImage("Remote Trampa MT, 32 MHz crystal", "nrf51_remote_mt_32k_32m.bin"),
        )
        5, 6, 7 -> listOf(
            SwdImage("BLE TRAMPA, RX 7 TX 6 LED 8", "nrf52832_vesc_ble_rx7_tx6_led8.bin"),
            SwdImage("BLE VESC built-in, RX 6 TX 7 LED 8", "nrf52832_vesc_ble_rx6_tx7_led8.bin"),
        )
        8 -> listOf(
            SwdImage("BLE Sparkfun Mini, RX 11 TX 8 LED 7", "nrf52840_vesc_ble_rx11_tx8_led7.bin"),
            SwdImage("VESC HD built-in, RX 26 TX 25 LED 27", "nrf52840_vesc_ble_rx26_tx25_led27.bin"),
            SwdImage("Wand remote", "nrf52840_stick_remote.bin"),
            SwdImage("Wand remote, magnetic throttle", "nrf52840_wand_mag.bin"),
            SwdImage("Stormcore built-in, RX 31 TX 30 LED 5", "nrf52840_stormcore_ble_rx31_tx30_led5.bin"),
        )
        else -> emptyList()
    }

    /** Downloads [image] from VESC Tool's repository. */
    suspend fun fetch(fetcher: HttpFetcher, image: SwdImage, onProgress: (Int) -> Unit = {}): ByteArray =
        fetcher.get("$BASE_URL/${image.file}", maxBytes = MAX_BYTES, onProgress = onProgress)

    private const val MAX_BYTES = 2 * 1024 * 1024
}

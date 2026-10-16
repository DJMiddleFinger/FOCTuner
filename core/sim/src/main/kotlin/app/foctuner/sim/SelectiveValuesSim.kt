package app.foctuner.sim

import app.foctuner.protocol.ByteWriter
import app.foctuner.protocol.CommPacketId.COMM_GET_VALUES_SELECTIVE

/**
 * COMM_GET_VALUES_SELECTIVE from a full COMM_GET_VALUES reply ([full], id byte first): the mask, then only the
 * masked fields, as the firmware's shared handler writes them.
 */
internal fun selectiveValues(full: ByteArray, mask: Long): ByteArray {
    val w = ByteWriter().u8(COMM_GET_VALUES_SELECTIVE.id).u32(mask)
    var offset = 1
    VALUE_FIELD_SIZES.forEachIndexed { bit, size ->
        if (mask and (1L shl bit) != 0L) w.bytes(full, offset, size)
        offset += size
    }
    return w.toByteArray()
}

/** Bytes per COMM_GET_VALUES field in wire order; bit 18 is the three per-MOSFET temperatures. */
private val VALUE_FIELD_SIZES = intArrayOf(2, 2, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4, 4, 1, 4, 1, 6, 4, 4, 1)

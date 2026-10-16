package app.foctuner.vesc

import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_ADC
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_CHUK
import app.foctuner.protocol.CommPacketId.COMM_GET_DECODED_PPM

/** What the PPM app reads: [value] −1..1 after the configured mapping, and the raw pulse length in ms. */
data class DecodedPpm(val value: Float, val pulseMs: Float)

/** What the ADC app reads on both inputs: values after the configured mapping (0..1) and the raw voltages. */
data class DecodedAdc(val value: Float, val voltage: Float, val value2: Float, val voltage2: Float)

suspend fun VescController.decodedPpm(): DecodedPpm =
    session.request(forwarded(Requests.bare(COMM_GET_DECODED_PPM)), COMM_GET_DECODED_PPM) { r ->
        DecodedPpm(r.f32(1e6f), r.f32(1e6f))
    }

suspend fun VescController.decodedAdc(): DecodedAdc =
    session.request(forwarded(Requests.bare(COMM_GET_DECODED_ADC)), COMM_GET_DECODED_ADC) { r ->
        DecodedAdc(r.f32(1e6f), r.f32(1e6f), r.f32(1e6f), r.f32(1e6f))
    }

/** The nunchuk app's stick position, −1..1. */
suspend fun VescController.decodedNunchuk(): Float =
    session.request(forwarded(Requests.bare(COMM_GET_DECODED_CHUK)), COMM_GET_DECODED_CHUK) { r -> r.f32(1e6f) }

package com.emg.classifier.processing

import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG             = "PacketParser"
private const val EXPECTED_SIZE   = 64
private const val HEADER_SIZE     = 4
private const val SAMPLES_PER_PKT = 10
private const val NUM_CHANNELS    = 3

/**
 * Cabecera extraída de cada paquete BLE.
 *
 * @param packetId  Identificador de tipo (byte 0)
 * @param counter   Contador incremental (byte 1) — permite detectar pérdidas
 * @param timestamp Timestamp uint16 en milisegundos (bytes 2-3, little-endian)
 */
data class PacketHeader(
    val packetId:  Int,
    val counter:   Int,
    val timestamp: Int
)

/**
 * Resultado del parsing de un paquete BLE.
 *
 * @param header  Cabecera del paquete
 * @param samples Array de tamaño [SAMPLES_PER_PKT × NUM_CHANNELS] con las muestras
 *                ordenadas como [s0_ch0, s0_ch1, s0_ch2, s1_ch0, …]
 */
data class ParsedPacket(
    val header:  PacketHeader,
    val samples: FloatArray   // tamaño: 10 × 3 = 30
) {
    /** Devuelve la muestra [sampleIndex] como FloatArray de 3 canales */
    fun getSample(sampleIndex: Int): FloatArray {
        val offset = sampleIndex * NUM_CHANNELS
        return floatArrayOf(samples[offset], samples[offset + 1], samples[offset + 2])
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedPacket) return false
        return header == other.header && samples.contentEquals(other.samples)
    }
    override fun hashCode() = 31 * header.hashCode() + samples.contentHashCode()
}

/**
 * Parsea un ByteArray recibido por BLE al formato interno de muestras EMG.
 *
 * Formato de entrada (64 bytes):
 *   Byte 0:    ID de paquete (uint8)
 *   Byte 1:    contador (uint8)
 *   Byte 2-3:  timestamp uint16 (little-endian)
 *   Bytes 4-63: 10 × (ch0_int16 + ch1_int16 + ch2_int16), little-endian
 *
 * @return ParsedPacket si el paquete es válido, null si hay error de formato
 */
fun parsePacket(raw: ByteArray): ParsedPacket? {
    if (raw.size != EXPECTED_SIZE) {
        Log.w(TAG, "Tamaño inesperado: ${raw.size} bytes (esperado $EXPECTED_SIZE)")
        return null
    }

    val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)

    // ── Header ────────────────────────────────────────────────────────────────
    val packetId  = buf.get().toInt() and 0xFF   // uint8
    val counter   = buf.get().toInt() and 0xFF   // uint8
    val timestamp = buf.short.toInt() and 0xFFFF // uint16

    val header = PacketHeader(packetId, counter, timestamp)

    // ── Datos: 10 muestras × 3 canales × int16 ───────────────────────────────
    val totalSamples = SAMPLES_PER_PKT * NUM_CHANNELS
    val samples = FloatArray(totalSamples)

    for (i in 0 until totalSamples) {
        samples[i] = buf.short.toFloat()  // int16 → Float
    }

    return ParsedPacket(header, samples)
}

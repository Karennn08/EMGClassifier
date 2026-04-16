package com.emg.classifier.processing

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val NUM_CHANNELS = 3

/**
 * Buffer circular thread-safe para almacenar muestras EMG.
 * Cada "muestra" es un FloatArray de NUM_CHANNELS valores.
 *
 * @param capacity Número máximo de muestras a almacenar (mínimo 300)
 */
class CircularBuffer(val capacity: Int = 300) {

    init {
        require(capacity >= 300) { "Capacidad mínima requerida: 300 muestras" }
    }

    // Array de arrays: cada posición almacena los 3 canales de una muestra
    private val buffer = Array(capacity) { FloatArray(NUM_CHANNELS) }
    private var writeIndex = 0    // próxima posición de escritura
    private var size       = 0    // número de muestras válidas actuales
    private val lock       = ReentrantLock()

    /** Número de muestras actualmente almacenadas */
    val count: Int get() = lock.withLock { size }

    /**
     * Agrega una muestra de NUM_CHANNELS valores al buffer.
     * Si el buffer está lleno, sobreescribe la muestra más antigua (FIFO).
     *
     * @param sample FloatArray de tamaño NUM_CHANNELS
     */
    fun add(sample: FloatArray) {
        require(sample.size == NUM_CHANNELS) {
            "Se esperan $NUM_CHANNELS canales, recibidos ${sample.size}"
        }
        lock.withLock {
            sample.copyInto(buffer[writeIndex])
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    /**
     * Agrega múltiples muestras de un ParsedPacket al buffer.
     * Más eficiente que llamar add() de forma individual.
     */
    fun addAll(packet: ParsedPacket) {
        lock.withLock {
            for (i in 0 until 10) {            // SAMPLES_PER_PKT = 10
                val offset = i * NUM_CHANNELS
                packet.samples.copyInto(
                    destination      = buffer[writeIndex],
                    destinationOffset = 0,
                    startIndex       = offset,
                    endIndex         = offset + NUM_CHANNELS
                )
                writeIndex = (writeIndex + 1) % capacity
                if (size < capacity) size++
            }
        }
    }

    /**
     * Devuelve las últimas [windowSize] muestras como Array<FloatArray>.
     * El índice 0 corresponde a la muestra más antigua de la ventana.
     *
     * @param windowSize Número de muestras a extraer (≤ capacity)
     * @return Array de FloatArray[NUM_CHANNELS], o null si no hay suficientes datos
     */
    fun getWindow(windowSize: Int): Array<FloatArray>? {
        require(windowSize > 0 && windowSize <= capacity) {
            "windowSize debe estar entre 1 y $capacity"
        }
        return lock.withLock {
            if (size < windowSize) return@withLock null

            val window = Array(windowSize) { FloatArray(NUM_CHANNELS) }
            // Posición de inicio en el buffer circular
            val startIndex = (writeIndex - windowSize + capacity) % capacity

            for (i in 0 until windowSize) {
                val srcIdx = (startIndex + i) % capacity
                buffer[srcIdx].copyInto(window[i])
            }
            window
        }
    }

    /** Vacía el buffer */
    fun clear() {
        lock.withLock {
            writeIndex = 0
            size       = 0
        }
    }
}

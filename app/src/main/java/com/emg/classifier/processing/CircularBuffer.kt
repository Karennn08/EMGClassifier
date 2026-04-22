package com.emg.classifier.processing

import com.emg.classifier.ml.EmgConfig
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Buffer circular thread-safe para muestras EMG.
 *
 * Cada posición almacena una muestra = FloatArray(3) con los 3 canales.
 * Cuando está lleno, sobrescribe la muestra más antigua (FIFO).
 */
class CircularBuffer(val capacity: Int = 256) {

    private val data       = Array(capacity) { FloatArray(EmgConfig.NUM_CHANNELS) }
    private var writeIndex = 0
    private var size       = 0
    private val lock       = ReentrantLock()

    /** Número de muestras actualmente almacenadas */
    val count: Int get() = lock.withLock { size }

    /** Agrega una muestra de 3 canales */
    fun add(sample: FloatArray) {
        lock.withLock {
            sample.copyInto(data[writeIndex])
            writeIndex = (writeIndex + 1) % capacity
            if (size < capacity) size++
        }
    }

    /**
     * Devuelve las últimas [windowSize] muestras en orden cronológico.
     * Retorna null si no hay suficientes muestras todavía.
     */
    fun getWindow(windowSize: Int): Array<FloatArray>? {
        return lock.withLock {
            if (size < windowSize) return@withLock null

            val window     = Array(windowSize) { FloatArray(EmgConfig.NUM_CHANNELS) }
            val startIndex = (writeIndex - windowSize + capacity) % capacity

            for (i in 0 until windowSize) {
                val src = (startIndex + i) % capacity
                data[src].copyInto(window[i])
            }
            window
        }
    }

    fun clear() = lock.withLock { writeIndex = 0; size = 0 }
}

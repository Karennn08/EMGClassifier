package com.emg.classifier.processing

import kotlin.math.abs
import kotlin.math.sqrt

private const val NUM_CHANNELS  = 3
private const val WINDOW_SIZE   = 100  // muestras por ventana

// ═════════════════════════════════════════════════════════════════════════════
// PREPROCESAMIENTO
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Resultado del preprocesamiento de una ventana.
 *
 * @param rectified  Array[windowSize][3] con muestras rectificadas (valor absoluto)
 * @param rms        FloatArray[3] con RMS de cada canal sobre la ventana
 */
data class PreprocessedWindow(
    val rectified: Array<FloatArray>,
    val rms:       FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PreprocessedWindow) return false
        return rms.contentEquals(other.rms)
    }
    override fun hashCode() = rms.contentHashCode()
}

/**
 * Aplica preprocesamiento EMG estándar sobre una ventana de muestras:
 *   1. Rectificación: x → |x|
 *   2. RMS por canal: sqrt(mean(x²))
 *
 * @param window Array[WINDOW_SIZE][NUM_CHANNELS] de muestras brutas
 * @return PreprocessedWindow con señal rectificada y vector RMS
 */
fun preprocess(window: Array<FloatArray>): PreprocessedWindow {
    val n = window.size

    // Acumuladores para RMS
    val sumSq = FloatArray(NUM_CHANNELS) { 0f }

    // Rectificación en lugar (no crea array adicional innecesario)
    val rectified = Array(n) { i ->
        FloatArray(NUM_CHANNELS) { ch ->
            val v = abs(window[i][ch])
            sumSq[ch] += v * v
            v
        }
    }

    // RMS: sqrt(mean(x²))
    val rms = FloatArray(NUM_CHANNELS) { ch -> sqrt(sumSq[ch] / n) }

    return PreprocessedWindow(rectified, rms)
}

// ═════════════════════════════════════════════════════════════════════════════
// VENTANEO
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Gestiona la extracción de ventanas con overlap desde el CircularBuffer.
 * Emite una nueva ventana cada vez que hay suficientes muestras nuevas.
 *
 * Estrategia de overlap:
 *   - Ventana de 100 muestras
 *   - Paso (step): 50 muestras → 50% de overlap
 *   El buffer siempre tiene las últimas 300+ muestras, por lo que
 *   getWindow(WINDOW_SIZE) siempre extrae las más recientes.
 *
 * Uso típico en un coroutine:
 *   while (isActive) {
 *       windowing.onNewSamples(10) { window ->
 *           val preprocessed = preprocess(window)
 *           mlHelper.infer(preprocessed)
 *       }
 *       delay(10)
 *   }
 */
class Windowing(
    private val buffer:     CircularBuffer,
    private val windowSize: Int = WINDOW_SIZE,
    private val stepSize:   Int = 50           // muestras nuevas entre ventanas
) {
    private var samplesSinceLastWindow = 0

    /**
     * Informa al windowing que se acaban de agregar [newSamples] muestras.
     * Si se alcanza el stepSize, extrae una ventana y llama a [onWindow].
     *
     * @param newSamples Número de muestras recién agregadas al buffer
     * @param onWindow   Lambda que recibe la ventana lista para procesar
     */
    fun onNewSamples(newSamples: Int, onWindow: (Array<FloatArray>) -> Unit) {
        samplesSinceLastWindow += newSamples

        if (samplesSinceLastWindow >= stepSize) {
            samplesSinceLastWindow = 0
            val window = buffer.getWindow(windowSize)
            if (window != null) {
                onWindow(window)
            }
        }
    }

    /** Reinicia el contador (ej: al reconectar BLE) */
    fun reset() { samplesSinceLastWindow = 0 }
}

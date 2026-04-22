package com.emg.classifier.processing

import com.emg.classifier.ml.EmgConfig
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Extracción de features EMG.
 *
 * Réplica EXACTA de train_emg.py → extract_features_window()
 *
 * Entrada:
 *   window[i] = FloatArray(3) = [ch0, ch1, ch2] para la muestra i
 *   Tamaño: WIN_SAMPLES = 30
 *
 * Salida:
 *   FloatArray(12) normalizado con Z-score listo para TFLite
 *   Layout: [rms0, mav0, zc0, wl0, rms1, mav1, zc1, wl1, rms2, mav2, zc2, wl2]
 */
fun extractAndNormalize(window: Array<FloatArray>): FloatArray {
    val n     = window.size   // WIN_SAMPLES = 30
    val feats = FloatArray(EmgConfig.FEATURE_VEC_LEN)

    for (ch in 0 until EmgConfig.NUM_CHANNELS) {
        val base = ch * EmgConfig.FEATURES_PER_CHANNEL

        // ── F0: RMS = sqrt(mean(x²)) ──────────────────────────────────────────
        var sumSq = 0.0
        for (i in 0 until n) sumSq += window[i][ch].toDouble() * window[i][ch].toDouble()
        feats[base + 0] = sqrt(sumSq / n).toFloat()

        // ── F1: MAV = mean(|x|) ───────────────────────────────────────────────
        var sumAbs = 0.0
        for (i in 0 until n) sumAbs += abs(window[i][ch].toDouble())
        feats[base + 1] = (sumAbs / n).toFloat()

        // ── F2: ZC — cruces de cero con banda muerta ──────────────────────────
        // Réplica de zero_crossing() en train_emg.py:
        //   signs = sign(x); diffs = diff(signs)
        //   valid = |x[i]| + |x[i+1]| > threshold
        //   zc = sum( |diffs| > 0 AND valid )
        var zc = 0
        val th = EmgConfig.ZC_THRESHOLD
        for (i in 0 until n - 1) {
            val cur  = window[i][ch]
            val next = window[i + 1][ch]
            val signChange = (cur >= 0f && next < 0f) || (cur < 0f && next >= 0f)
            val aboveThreshold = abs(cur) + abs(next) > th
            if (signChange && aboveThreshold) zc++
        }
        feats[base + 2] = zc.toFloat()

        // ── F3: WL = sum(|x[i+1] - x[i]|) ────────────────────────────────────
        var wl = 0.0
        for (i in 0 until n - 1)
            wl += abs(window[i + 1][ch] - window[i][ch]).toDouble()
        feats[base + 3] = wl.toFloat()
    }

    // ── Normalización Z-score con constantes fijas de entrenamiento ───────────
    // x_norm = (x - mu) / sigma
    val normFeats = FloatArray(feats.size)
    for (i in feats.indices) {
        normFeats[i] = (feats[i] - EmgConfig.NORM_MU[i]) / EmgConfig.NORM_SIGMA[i]
    }

    return normFeats
}

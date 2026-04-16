package com.emg.classifier.ml

import android.content.Context
import android.util.Log

private const val TAG = "MLHelper"

enum class GestureClass(val label: String) {
    RELAJADO("Mano relajada"),
    PUNIO   ("Puño cerrado"),
    PULGAR  ("Pulgar arriba");

    companion object {
        fun fromIndex(idx: Int) = values().getOrElse(idx) { RELAJADO }
    }
}

data class PredictionResult(
    val predictedClass: GestureClass,
    val probabilities:  FloatArray
) {
    val confidence: Float get() = probabilities[predictedClass.ordinal]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PredictionResult) return false
        return predictedClass == other.predictedClass &&
                probabilities.contentEquals(other.probabilities)
    }
    override fun hashCode() = 31 * predictedClass.hashCode() + probabilities.contentHashCode()
}

class MLHelper(context: Context) {

    fun infer(window: Array<FloatArray>): PredictionResult {
        val fakeClass = GestureClass.values()[(System.currentTimeMillis() / 2000 % 3).toInt()]
        val probs = when (fakeClass) {
            GestureClass.RELAJADO -> floatArrayOf(0.85f, 0.10f, 0.05f)
            GestureClass.PUNIO    -> floatArrayOf(0.05f, 0.90f, 0.05f)
            GestureClass.PULGAR   -> floatArrayOf(0.05f, 0.05f, 0.90f)
        }
        Log.d(TAG, "Predicción simulada: $fakeClass")
        return PredictionResult(fakeClass, probs)
    }

    fun close() {
        Log.d(TAG, "MLHelper cerrado")
    }
}
package com.emg.classifier.ml

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

private const val TAG = "MLHelper"

/**
 * Resultado de inferencia.
 *
 * @param classIndex  Índice predicho (0–6) dentro de CLASS_LABELS
 * @param classLabel  Etiqueta numérica real de NinaPro (0, 5, 6, 13, 14, 15, 16)
 * @param className   Nombre legible del gesto
 * @param probabilities Vector softmax de 7 probabilidades
 * @param confidence  Probabilidad de la clase predicha
 */
data class PredictionResult(
    val classIndex:    Int,
    val classLabel:    Int,
    val className:     String,
    val probabilities: FloatArray,
    val confidence:    Float
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PredictionResult) return false
        return classIndex == other.classIndex &&
                probabilities.contentEquals(other.probabilities)
    }
    override fun hashCode() = 31 * classIndex + probabilities.contentHashCode()
    override fun toString() = "$className (${(confidence * 100).toInt()}%)"
}

/**
 * Carga el modelo TFLite y ejecuta inferencia.
 *
 * Input:  FloatArray[12] — features normalizadas de Preprocessor
 * Output: FloatArray[7]  — probabilidades softmax por clase
 */
class MLHelper(private val context: Context) {

    private var interpreter: Interpreter? = null

    // Buffers pre-alocados para no generar GC durante inferencia
    private val inputBuffer  = ByteBuffer
        .allocateDirect(EmgConfig.FEATURE_VEC_LEN * Float.SIZE_BYTES)
        .apply { order(ByteOrder.nativeOrder()) }

    private val outputBuffer = ByteBuffer
        .allocateDirect(EmgConfig.NUM_CLASSES * Float.SIZE_BYTES)
        .apply { order(ByteOrder.nativeOrder()) }

    init {
        loadModel()
    }

    private fun loadModel() {
        try {
            val fd  = context.assets.openFd(EmgConfig.MODEL_ASSET)
            val buf = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            val opts = Interpreter.Options().apply { setNumThreads(2) }
            interpreter = Interpreter(buf, opts)
            Log.d(TAG, "Modelo cargado OK — input ${interpreter!!.getInputTensor(0).shape().contentToString()}" +
                    " output ${interpreter!!.getOutputTensor(0).shape().contentToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error cargando modelo: ${e.message}")
        }
    }

    /**
     * Ejecuta inferencia sobre el vector de features ya normalizado.
     *
     * @param normFeatures FloatArray[12] — salida de extractAndNormalize()
     */
    fun infer(normFeatures: FloatArray): PredictionResult {
        val interp = interpreter ?: return defaultResult()

        inputBuffer.rewind()
        normFeatures.forEach { inputBuffer.putFloat(it) }

        outputBuffer.rewind()
        try {
            interp.run(inputBuffer, outputBuffer)
        } catch (e: Exception) {
            Log.e(TAG, "Error en inferencia: ${e.message}")
            return defaultResult()
        }

        outputBuffer.rewind()
        val probs = FloatArray(EmgConfig.NUM_CLASSES) { outputBuffer.float }

        val bestIdx = probs.indices.maxByOrNull { probs[it] } ?: 0
        val result = PredictionResult(
            classIndex    = bestIdx,
            classLabel    = EmgConfig.CLASS_LABELS[bestIdx],
            className     = EmgConfig.CLASS_NAMES[bestIdx],
            probabilities = probs,
            confidence    = probs[bestIdx]
        )

        Log.d(TAG, "Predicción: $result | probs=${probs.map { "%.2f".format(it) }}")
        return result
    }

    private fun defaultResult() = PredictionResult(
        classIndex    = 0,
        classLabel    = 0,
        className     = "REST",
        probabilities = FloatArray(EmgConfig.NUM_CLASSES) { if (it == 0) 1f else 0f },
        confidence    = 1f
    )

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}

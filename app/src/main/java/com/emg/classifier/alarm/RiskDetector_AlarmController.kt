package com.emg.classifier.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.emg.classifier.ml.GestureClass
import com.emg.classifier.ml.PredictionResult

private const val TAG = "Alarm"

// ═════════════════════════════════════════════════════════════════════════════
// DETECCIÓN DE RIESGO
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Detecta situaciones de riesgo basadas en el historial de predicciones.
 *
 * Criterio actual:
 *   - Clase PUNIO sostenida durante más de RISK_DURATION_MS milisegundos
 *
 * Se puede ampliar fácilmente con otras reglas (p. ej. combinaciones de gestos).
 */
class RiskDetector(
    private val riskClass:      GestureClass = GestureClass.PUNIO,
    private val riskDurationMs: Long         = 500L
) {
    private var riskStartTime: Long = 0L
    private var inRiskState:   Boolean = false

    /**
     * Evalúa si el nuevo resultado de predicción constituye un evento de riesgo.
     *
     * @param result  Resultado de la última inferencia
     * @param nowMs   Tiempo actual en milisegundos (System.currentTimeMillis())
     * @return true si se debe activar la alarma, false en caso contrario
     */
    fun evaluate(result: PredictionResult, nowMs: Long = System.currentTimeMillis()): Boolean {
        return if (result.predictedClass == riskClass) {
            if (!inRiskState) {
                // Inicio de secuencia de riesgo
                inRiskState   = true
                riskStartTime = nowMs
                false
            } else {
                // Continúa en estado de riesgo: verificar duración
                (nowMs - riskStartTime) >= riskDurationMs
            }
        } else {
            // Salió del estado de riesgo
            inRiskState = false
            false
        }
    }

    fun reset() {
        inRiskState   = false
        riskStartTime = 0L
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// CONTROLADOR DE ALARMA
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Activa/desactiva la alarma de riesgo mediante vibración y/o sonido.
 *
 * Requiere en AndroidManifest.xml:
 *   <uses-permission android:name="android.permission.VIBRATE"/>
 */
class AlarmController(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
            ?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var mediaPlayer: MediaPlayer? = null
    private var alarmActive = false

    /**
     * Activa la alarma (vibración + sonido).
     * Llama de forma segura si ya está activa (no duplica).
     */
    fun activate() {
        if (alarmActive) return
        alarmActive = true
        Log.w(TAG, "¡ALARMA DE RIESGO ACTIVADA!")

        triggerVibration()
        triggerSound()
    }

    /**
     * Desactiva la alarma.
     */
    fun deactivate() {
        if (!alarmActive) return
        alarmActive = false
        Log.d(TAG, "Alarma desactivada")

        vibrator?.cancel()

        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
    }

    val isActive: Boolean get() = alarmActive

    // ── Vibración ─────────────────────────────────────────────────────────────

    private fun triggerVibration() {
        vibrator ?: run {
            Log.w(TAG, "Vibrador no disponible")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Patrón: 0 ms espera → 300 ms vibración → 200 ms pausa → 300 ms vibración
            val timings    = longArrayOf(0, 300, 200, 300)
            val amplitudes = intArrayOf(0, 200, 0, 255)
            vibrator.vibrate(
                VibrationEffect.createWaveform(timings, amplitudes, -1) // -1 = no repetir
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 300, 200, 300), -1)
        }
    }

    // ── Sonido ────────────────────────────────────────────────────────────────

    private fun triggerSound() {
        try {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, alarmUri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo sonido de alarma: ${e.message}")
        }
    }

    fun release() {
        deactivate()
    }
}

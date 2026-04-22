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

private const val TAG = "AlarmController"

/**
 * Activa/desactiva vibración y sonido de alarma.
 * Se activa cuando el modelo predice una clase de riesgo durante
 * al menos RISK_WINDOW_COUNT ventanas consecutivas.
 */
class AlarmController(private val context: Context) {

    // Número de ventanas consecutivas de clase de riesgo antes de activar la alarma
    private val RISK_WINDOW_COUNT = 3

    private var riskCount    = 0
    private var alarmActive  = false

    val isActive: Boolean get() = alarmActive

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var player: MediaPlayer? = null

    /**
     * Llama en cada ventana procesada con el índice de clase predicho.
     * @param isRiskClass true si la clase actual está en ALARM_CLASS_INDICES
     * @return true si la alarma está activa ahora
     */
    fun evaluate(isRiskClass: Boolean): Boolean {
        if (isRiskClass) {
            riskCount++
            if (riskCount >= RISK_WINDOW_COUNT && !alarmActive) {
                activate()
            }
        } else {
            riskCount = 0
            if (alarmActive) deactivate()
        }
        return alarmActive
    }

    private fun activate() {
        alarmActive = true
        Log.w(TAG, "ALARMA activada")
        vibrate()
        playSound()
    }

    fun deactivate() {
        if (!alarmActive) return
        alarmActive = false
        riskCount   = 0
        Log.d(TAG, "Alarma desactivada")
        vibrator?.cancel()
        player?.stop(); player?.release(); player = null
    }

    private fun vibrate() {
        vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0L, 400L, 200L, 400L),
                    intArrayOf(0, 255, 0, 200),
                    -1
                )
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0L, 400L, 200L, 400L), -1)
        }
    }

    private fun playSound() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = false
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reproduciendo sonido: ${e.message}")
        }
    }

    fun reset() { deactivate() }

    fun release() { deactivate() }
}

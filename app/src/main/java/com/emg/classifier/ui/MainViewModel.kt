package com.emg.classifier.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emg.classifier.alarm.AlarmController
import com.emg.classifier.alarm.RiskDetector
import com.emg.classifier.ble.BLEManager
import com.emg.classifier.ble.BleState
import com.emg.classifier.ml.GestureClass
import com.emg.classifier.ml.MLHelper
import com.emg.classifier.ml.PredictionResult
import com.emg.classifier.processing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Estado de la UI consolidado.
 */
data class UiState(
    val bleStatus:     String       = "Desconectado",
    val isConnected:   Boolean      = false,
    val prediction:    GestureClass = GestureClass.RELAJADO,
    val probabilities: FloatArray   = floatArrayOf(1f, 0f, 0f),
    val alarmActive:   Boolean      = false,
    val packetsRx:     Int          = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiState) return false
        return isConnected == other.isConnected &&
               prediction  == other.prediction  &&
               alarmActive == other.alarmActive  &&
               packetsRx   == other.packetsRx    &&
               probabilities.contentEquals(other.probabilities)
    }
    override fun hashCode() = 31 * prediction.hashCode() + probabilities.contentHashCode()
}

/**
 * ViewModel principal: orquesta BLE → Parser → Buffer → Windowing →
 * Preprocessing → ML → RiskDetector → Alarm.
 *
 * Toda la lógica de procesamiento corre en Dispatchers.Default
 * para no bloquear el hilo principal.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    // ── Dependencias ──────────────────────────────────────────────────────────
    val bleManager    = BLEManager(application)
    private val mlHelper    = MLHelper(application)
    private val buffer      = CircularBuffer(capacity = 512)
    private val windowing   = Windowing(buffer)
    private val riskDetect  = RiskDetector()
    val alarmCtrl     = AlarmController(application)

    // ── Estado de UI ──────────────────────────────────────────────────────────
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var packetsReceived = 0

    init {
        observeBleState()
        observeRawPackets()
    }

    // ── Observar estado BLE ───────────────────────────────────────────────────

    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.bleState.collect { state ->
                val (statusStr, connected) = when (state) {
                    is BleState.Idle       -> "Desconectado"    to false
                    is BleState.Scanning   -> "Escaneando..."   to false
                    is BleState.Connecting -> "Conectando..."   to false
                    is BleState.Connected  -> "Conectado"       to true
                    is BleState.Error      -> "Error: ${state.message}" to false
                }
                _uiState.update { it.copy(bleStatus = statusStr, isConnected = connected) }

                // Al desconectar: resetear detección de riesgo y alarma
                if (!connected) {
                    riskDetect.reset()
                    alarmCtrl.deactivate()
                    windowing.reset()
                    _uiState.update { it.copy(alarmActive = false) }
                }
            }
        }
    }

    // ── Pipeline de procesamiento ─────────────────────────────────────────────

    private fun observeRawPackets() {
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.rawPackets
                .filterNotNull()
                .collect { rawBytes ->
                    processPacket(rawBytes)
                }
        }
    }

    private fun processPacket(rawBytes: ByteArray) {
        // 1. Parsear
        val packet = parsePacket(rawBytes) ?: return
        packetsReceived++

        // 2. Agregar al buffer circular
        buffer.addAll(packet)

        // 3. Ventaneo: notificar que llegaron 10 muestras nuevas
        windowing.onNewSamples(10) { window ->
            // 4. Preprocesamiento
            val preprocessed = preprocess(window)

            // 5. Inferencia ML
            val result = mlHelper.infer(preprocessed.rectified)

            // 6. Detección de riesgo
            val isRisk = riskDetect.evaluate(result)

            // 7. Alarma
            if (isRisk && !alarmCtrl.isActive) {
                alarmCtrl.activate()
            } else if (!isRisk && alarmCtrl.isActive) {
                // Solo desactivar si la clase cambió (evita flicker)
                if (result.predictedClass != GestureClass.PUNIO) {
                    alarmCtrl.deactivate()
                }
            }

            // 8. Actualizar UI (volver al hilo principal via StateFlow)
            _uiState.update { current ->
                current.copy(
                    prediction    = result.predictedClass,
                    probabilities = result.probabilities,
                    alarmActive   = alarmCtrl.isActive,
                    packetsRx     = packetsReceived
                )
            }
        }
    }

    // ── Acciones públicas ─────────────────────────────────────────────────────

    fun startScan() {
        buffer.clear()
        windowing.reset()
        riskDetect.reset()
        packetsReceived = 0
        bleManager.startScan()
    }

    fun disconnect() {
        bleManager.disconnect()
        alarmCtrl.deactivate()
    }

    override fun onCleared() {
        super.onCleared()
        bleManager.disconnect()
        mlHelper.close()
        alarmCtrl.release()
    }
}

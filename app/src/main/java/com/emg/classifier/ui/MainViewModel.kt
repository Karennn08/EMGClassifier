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

data class UiState(
    val bleStatus:     String       = "Desconectado",
    val isConnected:   Boolean      = false,
    val prediction:    GestureClass = GestureClass.RELAJADO,
    val probabilities: FloatArray   = floatArrayOf(1f, 0f, 0f),
    val alarmActive:   Boolean      = false,
    val packetsRx:     Int          = 0,
    val lastRawHex:    String       = ""   // muestra los últimos bytes recibidos en UI
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiState) return false
        return isConnected == other.isConnected &&
                prediction  == other.prediction  &&
                alarmActive == other.alarmActive  &&
                packetsRx   == other.packetsRx    &&
                lastRawHex  == other.lastRawHex   &&
                probabilities.contentEquals(other.probabilities)
    }
    override fun hashCode() = 31 * prediction.hashCode() + probabilities.contentHashCode()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val bleManager   = BLEManager(application)
    private val mlHelper   = MLHelper(application)
    private val buffer     = CircularBuffer(capacity = 512)
    private val windowing  = Windowing(buffer)
    private val riskDetect = RiskDetector()
    val alarmCtrl    = AlarmController(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var packetsReceived = 0

    init {
        observeBleState()
        observeRawPackets()
    }

    private fun observeBleState() {
        viewModelScope.launch {
            bleManager.bleState.collect { state ->
                val (statusStr, connected) = when (state) {
                    is BleState.Idle       -> "Desconectado"             to false
                    is BleState.Scanning   -> "Escaneando..."            to false
                    is BleState.Connecting -> "Conectando..."            to false
                    is BleState.Connected  -> "Conectado"                to true
                    is BleState.Error      -> "Error: ${state.message}"  to false
                }
                _uiState.update { it.copy(bleStatus = statusStr, isConnected = connected) }
                if (!connected) {
                    riskDetect.reset()
                    alarmCtrl.deactivate()
                    windowing.reset()
                    _uiState.update { it.copy(alarmActive = false) }
                }
            }
        }
    }

    private fun observeRawPackets() {
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.rawPackets
                .filterNotNull()
                .collect { rawBytes -> processPacket(rawBytes) }
        }
    }

    private fun processPacket(rawBytes: ByteArray) {
        packetsReceived++

        // Representación hex para mostrar en UI (debug)
        val hexStr = rawBytes.joinToString(" ") { "%02X".format(it) }

        // Inferencia simple basada en los bytes recibidos (sin modelo TFLite)
        val result = inferFromRawBytes(rawBytes)

        // Detección de riesgo
        val isRisk = riskDetect.evaluate(result)
        if (isRisk && !alarmCtrl.isActive) alarmCtrl.activate()
        else if (!isRisk && alarmCtrl.isActive) alarmCtrl.deactivate()

        _uiState.update { current ->
            current.copy(
                prediction    = result.predictedClass,
                probabilities = result.probabilities,
                alarmActive   = alarmCtrl.isActive,
                packetsRx     = packetsReceived,
                lastRawHex    = hexStr
            )
        }

        // También alimenta el pipeline de procesamiento si quieres usar windowing
        val packet = parsePacket(rawBytes)
        if (packet != null) {
            buffer.addAll(packet)
            windowing.onNewSamples(10) { window ->
                val preprocessed = preprocess(window)
                val mlResult = mlHelper.infer(preprocessed.rectified)
                _uiState.update { it.copy(
                    prediction    = mlResult.predictedClass,
                    probabilities = mlResult.probabilities
                )}
            }
        }
    }

    /**
     * Inferencia simple sin modelo TFLite.
     * Reglas basadas en el primer byte del paquete recibido desde nRF Connect:
     *   byte[0] == 0x01  →  Mano relajada
     *   byte[0] == 0x02  →  Puño cerrado  (activa alarma si se sostiene)
     *   byte[0] == 0x03  →  Pulgar arriba
     *   cualquier otro   →  Mano relajada
     *
     * Cuando uses nRF Connect en el iPhone, envía manualmente estos bytes:
     *   01        → simula mano relajada
     *   02        → simula puño cerrado
     *   03        → simula pulgar arriba
     */
    private fun inferFromRawBytes(bytes: ByteArray): PredictionResult {
        val firstByte = bytes.firstOrNull()?.toInt()?.and(0xFF) ?: 0
        return when (firstByte) {
            0x02 -> PredictionResult(
                GestureClass.PUNIO,
                floatArrayOf(0.05f, 0.90f, 0.05f)
            )
            0x03 -> PredictionResult(
                GestureClass.PULGAR,
                floatArrayOf(0.05f, 0.05f, 0.90f)
            )
            else -> PredictionResult(
                GestureClass.RELAJADO,
                floatArrayOf(0.85f, 0.10f, 0.05f)
            )
        }
    }

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

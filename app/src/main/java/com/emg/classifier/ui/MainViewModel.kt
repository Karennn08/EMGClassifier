package com.emg.classifier.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.emg.classifier.alarm.AlarmController
import com.emg.classifier.ble.BLEManager
import com.emg.classifier.ble.BleStatus
import com.emg.classifier.ml.EmgConfig
import com.emg.classifier.ml.MLHelper
import com.emg.classifier.ml.PredictionResult
import com.emg.classifier.processing.CircularBuffer
import com.emg.classifier.processing.extractAndNormalize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

private const val TAG = "MainViewModel"

// ─── Estado de la UI ──────────────────────────────────────────────────────────

data class UiState(
    val bleStatus:     String          = "Desconectado",
    val isConnected:   Boolean         = false,
    val className:     String          = "REST",
    val confidence:    Float           = 0f,
    val probabilities: FloatArray      = FloatArray(EmgConfig.NUM_CLASSES),
    val alarmActive:   Boolean         = false,
    val samplesInBuf:  Int             = 0,
    val packetsRx:     Int             = 0
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UiState) return false
        return isConnected  == other.isConnected  &&
                className    == other.className    &&
                alarmActive  == other.alarmActive  &&
                packetsRx    == other.packetsRx    &&
                probabilities.contentEquals(other.probabilities)
    }
    override fun hashCode() = className.hashCode()
}

// ─── ViewModel ────────────────────────────────────────────────────────────────

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val bleManager = BLEManager(app)

    private val mlHelper      = MLHelper(app)
    private val buffer        = CircularBuffer(capacity = 256)
    private val alarmCtrl     = AlarmController(app)

    private val _ui = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _ui.asStateFlow()

    // Acumulador: cuántas muestras nuevas desde la última ventana emitida
    private var samplesSinceLastWindow = 0
    private var packetsReceived        = 0

    init {
        observeBleStatus()
        observePackets()
    }

    // ── Observar estado BLE ───────────────────────────────────────────────────

    private fun observeBleStatus() {
        viewModelScope.launch {
            bleManager.status.collect { status ->
                val label = when (status) {
                    is BleStatus.Idle       -> "Desconectado"
                    is BleStatus.Scanning   -> "Escaneando..."
                    is BleStatus.Connecting -> "Conectando..."
                    is BleStatus.Connected  -> "Conectado ✓"
                    is BleStatus.Error      -> "Error: ${status.msg}"
                }
                val connected = status is BleStatus.Connected
                if (!connected) {
                    alarmCtrl.reset()
                    buffer.clear()
                    samplesSinceLastWindow = 0
                }
                _ui.update { it.copy(bleStatus = label, isConnected = connected) }
            }
        }
    }

    // ── Pipeline principal ────────────────────────────────────────────────────
    // BLE callback → parse 6 bytes → buffer → ventaneo → features → TFLite → alarma

    private fun observePackets() {
        viewModelScope.launch(Dispatchers.Default) {
            bleManager.packets
                .filterNotNull()
                .collect { raw -> processPacket(raw) }
        }
    }

    /**
     * Procesa un paquete de 6 bytes del ESP32.
     *
     * Formato (Esp32_stream.ino):
     *   [ch1_L, ch1_H, ch2_L, ch2_H, ch3_L, ch3_H]
     *   int16 little-endian, valor = int16 / SCALE_FACTOR
     */
    private fun processPacket(raw: ByteArray) {
        if (raw.size != 6) {
            Log.w(TAG, "Paquete descartado: ${raw.size} bytes (esperado 6)")
            return
        }

        packetsReceived++

        // ── 1. Parsear 3 × int16 little-endian → float ────────────────────────
        val ch1 = ((raw[1].toInt() shl 8) or (raw[0].toInt() and 0xFF)).toShort()
        val ch2 = ((raw[3].toInt() shl 8) or (raw[2].toInt() and 0xFF)).toShort()
        val ch3 = ((raw[5].toInt() shl 8) or (raw[4].toInt() and 0xFF)).toShort()

        val sample = floatArrayOf(
            ch1 / EmgConfig.SCALE_FACTOR,
            ch2 / EmgConfig.SCALE_FACTOR,
            ch3 / EmgConfig.SCALE_FACTOR
        )

        // ── 2. Acumular en buffer circular ────────────────────────────────────
        buffer.add(sample)
        samplesSinceLastWindow++

        // ── 3. Emitir ventana cada STEP_SAMPLES muestras nuevas ───────────────
        // Ventana: 30 muestras — Step: 15 muestras (50 % overlap)
        if (samplesSinceLastWindow >= EmgConfig.STEP_SAMPLES) {
            samplesSinceLastWindow = 0
            val window = buffer.getWindow(EmgConfig.WIN_SAMPLES) ?: return

            // ── 4. Extraer 12 features + normalización Z-score ────────────────
            val normFeatures = extractAndNormalize(window)

            // ── 5. Inferencia TFLite ──────────────────────────────────────────
            val result = mlHelper.infer(normFeatures)

            // ── 6. Alarma si clase de riesgo (WristFlex/WristExt) ─────────────
            val isRisk    = result.classIndex in EmgConfig.ALARM_CLASS_INDICES
            val alarmNow  = alarmCtrl.evaluate(isRisk)

            // ── 7. Actualizar UI ──────────────────────────────────────────────
            _ui.update {
                it.copy(
                    className     = result.className,
                    confidence    = result.confidence,
                    probabilities = result.probabilities,
                    alarmActive   = alarmNow,
                    samplesInBuf  = buffer.count,
                    packetsRx     = packetsReceived
                )
            }
        }
    }

    // ── Acciones públicas ─────────────────────────────────────────────────────

    fun startScan() {
        buffer.clear()
        samplesSinceLastWindow = 0
        packetsReceived        = 0
        alarmCtrl.reset()
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

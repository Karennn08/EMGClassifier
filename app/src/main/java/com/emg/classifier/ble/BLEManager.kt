package com.emg.classifier.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private const val TAG = "BLEManager"

private val SERVICE_UUID        = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
private val CHARACTERISTIC_UUID = UUID.fromString("abcdefab-1234-5678-1234-abcdefabcdef")
private val CCCD_UUID           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Estados posibles de la conexión BLE */
sealed class BleState {
    object Idle        : BleState()
    object Scanning    : BleState()
    object Connecting  : BleState()
    object Connected   : BleState()
    data class Error(val message: String) : BleState()
}

/**
 * Gestiona el ciclo de vida completo de BLE:
 * escaneo → conexión GATT → suscripción a notificaciones → recepción de datos.
 *
 * Uso:
 *   bleManager.startScan()
 *   bleManager.rawPackets.collect { packet -> … }
 */
@SuppressLint("MissingPermission")
class BLEManager(private val context: Context) {

    // ── Flujos públicos ────────────────────────────────────────────────────────
    private val _bleState   = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState

    /** Emite cada ByteArray recibido por notificación BLE */
    private val _rawPackets = MutableStateFlow<ByteArray?>(null)
    val rawPackets: StateFlow<ByteArray?> = _rawPackets

    // ── Objetos BLE internos ──────────────────────────────────────────────────
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter  = bluetoothManager.adapter
    private var bleScanner:  BluetoothLeScanner? = null
    private var gatt:        BluetoothGatt?       = null
    private val mainHandler  = Handler(Looper.getMainLooper())

    // ── Timeout de escaneo ─────────────────────────────────────────────────────
    private val SCAN_TIMEOUT_MS = 10_000L

    // ═════════════════════════════════════════════════════════════════════════
    // ESCANEO
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Inicia el escaneo BLE filtrando por SERVICE_UUID.
     * Se detiene automáticamente al encontrar el dispositivo o tras SCAN_TIMEOUT_MS.
     */
    fun startScan() {
        if (!bluetoothAdapter.isEnabled) {
            _bleState.value = BleState.Error("Bluetooth desactivado")
            return
        }
        bleScanner = bluetoothAdapter.bluetoothLeScanner
        if (bleScanner == null) {
            _bleState.value = BleState.Error("BLE no disponible")
            return
        }

        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(android.os.ParcelUuid(SERVICE_UUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        _bleState.value = BleState.Scanning
        Log.d(TAG, "Escaneo iniciado...")

        bleScanner!!.startScan(listOf(scanFilter), scanSettings, scanCallback)

        // Detener escaneo tras timeout
        mainHandler.postDelayed({
            if (_bleState.value is BleState.Scanning) {
                stopScan()
                _bleState.value = BleState.Error("Dispositivo no encontrado")
                Log.w(TAG, "Escaneo timeout")
            }
        }, SCAN_TIMEOUT_MS)
    }

    fun stopScan() {
        bleScanner?.stopScan(scanCallback)
        bleScanner = null
        Log.d(TAG, "Escaneo detenido")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d(TAG, "Dispositivo encontrado: ${result.device.address}")
            stopScan()
            connectToDevice(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            _bleState.value = BleState.Error("Error de escaneo: $errorCode")
            Log.e(TAG, "Escaneo fallido: $errorCode")
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONEXIÓN GATT
    // ═════════════════════════════════════════════════════════════════════════

    private fun connectToDevice(device: BluetoothDevice) {
        _bleState.value = BleState.Connecting
        Log.d(TAG, "Conectando a ${device.address}...")
        // connectGatt en el hilo principal para evitar bugs en algunos fabricantes
        mainHandler.post {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _bleState.value = BleState.Idle
        Log.d(TAG, "Desconectado manualmente")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CALLBACKS GATT
    // ═════════════════════════════════════════════════════════════════════════

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "GATT conectado, descubriendo servicios...")
                    _bleState.value = BleState.Connected
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "GATT desconectado (status=$status)")
                    _bleState.value = BleState.Error("Desconectado (status=$status)")
                    gatt.close()
                    this@BLEManager.gatt = null
                }
                else -> {
                    Log.e(TAG, "Estado GATT inesperado: status=$status, state=$newState")
                    _bleState.value = BleState.Error("Error GATT status=$status")
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.value = BleState.Error("Descubrimiento de servicios fallido")
                return
            }
            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                _bleState.value = BleState.Error("Servicio EMG no encontrado")
                return
            }
            val characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (characteristic == null) {
                _bleState.value = BleState.Error("Característica EMG no encontrada")
                return
            }
            subscribeToNotifications(gatt, characteristic)
        }

        @Deprecated("Deprecated in API 33, kept for backward compat")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                _rawPackets.value = characteristic.value?.copyOf()
            }
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                _rawPackets.value = value.copyOf()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notificaciones activadas correctamente")
            } else {
                _bleState.value = BleState.Error("Error al activar notificaciones: $status")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SUSCRIPCIÓN A NOTIFICACIONES (CCCD)
    // ═════════════════════════════════════════════════════════════════════════

    private fun subscribeToNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        // Activar notificaciones localmente
        gatt.setCharacteristicNotification(characteristic, true)

        // Escribir en el descriptor CCCD para activar notificaciones en el servidor
        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            _bleState.value = BleState.Error("Descriptor CCCD no encontrado")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        Log.d(TAG, "Suscripción a notificaciones enviada")
    }
}

package com.emg.classifier.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

private const val TAG = "BLEManager"

// UUIDs del ESP32 (deben coincidir con Esp32_stream.ino)
private val SERVICE_UUID        = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
private val CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
private val CCCD_UUID           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

private const val SCAN_TIMEOUT_MS = 15_000L

sealed class BleStatus {
    object Idle       : BleStatus()
    object Scanning   : BleStatus()
    object Connecting : BleStatus()
    object Connected  : BleStatus()
    data class Error(val msg: String) : BleStatus()
}

@SuppressLint("MissingPermission")
class BLEManager(private val context: Context) {

    private val _status = MutableStateFlow<BleStatus>(BleStatus.Idle)
    val status: StateFlow<BleStatus> = _status

    /** Emite cada paquete crudo de 6 bytes recibido por BLE Notify */
    private val _packets = MutableStateFlow<ByteArray?>(null)
    val packets: StateFlow<ByteArray?> = _packets

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE)
            as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    // ── Escaneo ───────────────────────────────────────────────────────────────

    fun startScan() {
        if (!adapter.isEnabled) { _status.value = BleStatus.Error("Bluetooth desactivado"); return }

        val scanner = adapter.bluetoothLeScanner
            ?: run { _status.value = BleStatus.Error("Scanner no disponible"); return }

        _status.value = BleStatus.Scanning
        Log.d(TAG, "Iniciando escaneo...")

        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            scanCallback
        )

        handler.postDelayed({
            if (_status.value is BleStatus.Scanning) {
                scanner.stopScan(scanCallback)
                _status.value = BleStatus.Error("Dispositivo no encontrado (timeout)")
            }
        }, SCAN_TIMEOUT_MS)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val uuids = result.scanRecord?.serviceUuids
            if (uuids != null && uuids.any { it.uuid == SERVICE_UUID }) {
                Log.d(TAG, "ESP32 encontrado: ${result.device.name} — conectando...")
                adapter.bluetoothLeScanner?.stopScan(this)
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _status.value = BleStatus.Error("Escaneo falló: $errorCode")
        }
    }

    // ── Conexión GATT ─────────────────────────────────────────────────────────

    private fun connect(device: BluetoothDevice) {
        _status.value = BleStatus.Connecting
        handler.post {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _status.value = BleStatus.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "Conectado — descubriendo servicios...")
                    _status.value = BleStatus.Connected
                    g.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Desconectado (status=$status)")
                    _status.value = BleStatus.Error("Desconectado")
                    g.close(); gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _status.value = BleStatus.Error("Servicios no encontrados"); return
            }
            val chr = g.getService(SERVICE_UUID)?.getCharacteristic(CHARACTERISTIC_UUID)
            if (chr == null) {
                _status.value = BleStatus.Error("Characteristic no encontrada"); return
            }
            enableNotifications(g, chr)
        }

        // API 33+
        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic, value: ByteArray
        ) {
            if (chr.uuid == CHARACTERISTIC_UUID) _packets.value = value.copyOf()
        }

        // API < 33
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, chr: BluetoothGattCharacteristic
        ) {
            if (chr.uuid == CHARACTERISTIC_UUID) _packets.value = chr.value?.copyOf()
        }

        override fun onDescriptorWrite(g: BluetoothGatt, desc: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) Log.d(TAG, "Notificaciones activadas OK")
            else Log.e(TAG, "Error activando notificaciones: $status")
        }
    }

    private fun enableNotifications(g: BluetoothGatt, chr: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(chr, true)
        val cccd = chr.getDescriptor(CCCD_UUID) ?: run {
            Log.w(TAG, "CCCD no encontrado"); return
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            g.writeDescriptor(cccd)
        }
    }
}

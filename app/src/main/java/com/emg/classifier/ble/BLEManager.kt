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
private val SERVICE_UUID        = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
private val CHARACTERISTIC_UUID = UUID.fromString("abcdefab-1234-5678-1234-abcdefabcdef")
private val CCCD_UUID           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

sealed class BleState {
    object Idle        : BleState()
    object Scanning    : BleState()
    object Connecting  : BleState()
    object Connected   : BleState()
    data class Error(val message: String) : BleState()
}

@SuppressLint("MissingPermission")
class BLEManager(private val context: Context) {

    private val _bleState   = MutableStateFlow<BleState>(BleState.Idle)
    val bleState: StateFlow<BleState> = _bleState

    private val _rawPackets = MutableStateFlow<ByteArray?>(null)
    val rawPackets: StateFlow<ByteArray?> = _rawPackets

    private val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var gatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())

    fun startScan() {
        if (!adapter.isEnabled) {
            _bleState.value = BleState.Error("Bluetooth desactivado")
            return
        }
        _bleState.value = BleState.Scanning
        Log.d(TAG, "Iniciando escaneo BLE...")

        val scanner = adapter.bluetoothLeScanner ?: run {
            _bleState.value = BleState.Error("Scanner no disponible")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)

        handler.postDelayed({
            if (_bleState.value is BleState.Scanning) {
                scanner.stopScan(scanCallback)
                _bleState.value = BleState.Error("Dispositivo no encontrado (timeout)")
                Log.w(TAG, "Escaneo timeout")
            }
        }, 15_000)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Sin nombre"
            Log.d(TAG, "Encontrado: $name — ${device.address}")

            val uuids = result.scanRecord?.serviceUuids
            if (uuids != null && uuids.any { it.uuid == SERVICE_UUID }) {
                Log.d(TAG, "Servicio EMG encontrado en $name — conectando...")
                adapter.bluetoothLeScanner?.stopScan(this)
                connectTo(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _bleState.value = BleState.Error("Escaneo fallido: código $errorCode")
        }
    }

    private fun connectTo(device: BluetoothDevice) {
        _bleState.value = BleState.Connecting
        handler.post {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        _bleState.value = BleState.Idle
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when {
                newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS -> {
                    Log.d(TAG, "Conectado — descubriendo servicios...")
                    _bleState.value = BleState.Connected
                    gatt.discoverServices()
                }
                newState == BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.w(TAG, "Desconectado (status=$status)")
                    _bleState.value = BleState.Error("Desconectado")
                    gatt.close()
                    this@BLEManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _bleState.value = BleState.Error("Servicios no encontrados")
                return
            }
            Log.d(TAG, "Servicios encontrados:")
            gatt.services.forEach { svc ->
                Log.d(TAG, "  Servicio: ${svc.uuid}")
                svc.characteristics.forEach { chr ->
                    Log.d(TAG, "    Characteristic: ${chr.uuid} props=${chr.properties}")
                }
            }
            val characteristic = gatt
                .getService(SERVICE_UUID)
                ?.getCharacteristic(CHARACTERISTIC_UUID)

            if (characteristic == null) {
                _bleState.value = BleState.Error("Characteristic no encontrada")
                Log.e(TAG, "Characteristic $CHARACTERISTIC_UUID no encontrada")
                return
            }
            subscribeToNotifications(gatt, characteristic)
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                val data = characteristic.value ?: return
                Log.d(TAG, "Datos (${data.size}b): ${data.toHexString()}")
                _rawPackets.value = data.copyOf()
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == CHARACTERISTIC_UUID) {
                Log.d(TAG, "Datos (${value.size}b): ${value.toHexString()}")
                _rawPackets.value = value.copyOf()
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Notificaciones activadas OK")
            } else {
                Log.e(TAG, "Error descriptor write: $status")
            }
        }
    }

    private fun subscribeToNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.w(TAG, "CCCD no encontrado — continuando sin él")
            return
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        Log.d(TAG, "CCCD enviado")
    }

    private fun ByteArray.toHexString() =
        joinToString(" ") { "%02X".format(it) }
}

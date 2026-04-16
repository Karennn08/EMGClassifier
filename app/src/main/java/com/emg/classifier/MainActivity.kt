package com.emg.classifier

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.emg.classifier.ml.GestureClass
import com.emg.classifier.ui.MainViewModel   // ← este es el clave
import com.emg.classifier.ui.UiState         // ← y este
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Actividad principal.
 *
 * Layout esperado (res/layout/activity_main.xml):
 *   - TextView  id="tvBleStatus"
 *   - TextView  id="tvPrediction"
 *   - TextView  id="tvProbabilities"
 *   - TextView  id="tvPacketCount"
 *   - TextView  id="tvAlarm"           (rojo cuando alarma activa)
 *   - ProgressBar id="pbRelaxed"       (probabilidad clase 0)
 *   - ProgressBar id="pbFist"          (probabilidad clase 1)
 *   - ProgressBar id="pbThumb"         (probabilidad clase 2)
 *   - Button    id="btnConnect"
 *   - Button    id="btnDisconnect"
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    // ── Vistas ────────────────────────────────────────────────────────────────
    private lateinit var tvBleStatus:     TextView
    private lateinit var tvPrediction:    TextView
    private lateinit var tvProbabilities: TextView
    private lateinit var tvPacketCount:   TextView
    private lateinit var tvAlarm:         TextView
    private lateinit var pbRelaxed:       ProgressBar
    private lateinit var pbFist:          ProgressBar
    private lateinit var pbThumb:         ProgressBar
    private lateinit var btnConnect:      Button
    private lateinit var btnDisconnect:   Button

    // ── Permisos BLE ──────────────────────────────────────────────────────────
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startScan()
        } else {
            Toast.makeText(this, "Permisos BLE requeridos", Toast.LENGTH_LONG).show()
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupButtons()
        observeState()

        // TEST TEMPORAL — borra esto después
        lifecycleScope.launch {
            while (true) {
                val fakeClass = com.emg.classifier.ml.GestureClass.values()[
                    (System.currentTimeMillis() / 2000 % 3).toInt()
                ]
                val probs = when (fakeClass) {
                    com.emg.classifier.ml.GestureClass.RELAJADO -> floatArrayOf(0.85f, 0.10f, 0.05f)
                    com.emg.classifier.ml.GestureClass.PUNIO    -> floatArrayOf(0.05f, 0.90f, 0.05f)
                    com.emg.classifier.ml.GestureClass.PULGAR   -> floatArrayOf(0.05f, 0.05f, 0.90f)
                }
                updateUI(UiState(
                    bleStatus = "Test activo",
                    isConnected = true,
                    prediction = fakeClass,
                    probabilities = probs,
                    alarmActive = fakeClass == com.emg.classifier.ml.GestureClass.PUNIO,
                    packetsRx = (System.currentTimeMillis() / 100 % 9999).toInt()
                ))
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    private fun bindViews() {
        tvBleStatus     = findViewById(R.id.tvBleStatus)
        tvPrediction    = findViewById(R.id.tvPrediction)
        tvProbabilities = findViewById(R.id.tvProbabilities)
        tvPacketCount   = findViewById(R.id.tvPacketCount)
        tvAlarm         = findViewById(R.id.tvAlarm)
        pbRelaxed       = findViewById(R.id.pbRelaxed)
        pbFist          = findViewById(R.id.pbFist)
        pbThumb         = findViewById(R.id.pbThumb)
        btnConnect      = findViewById(R.id.btnConnect)
        btnDisconnect   = findViewById(R.id.btnDisconnect)
    }

    private fun setupButtons() {
        btnConnect.setOnClickListener { requestBlePermissionsAndConnect() }
        btnDisconnect.setOnClickListener { viewModel.disconnect() }
    }

    // ── Observar estado de UI ─────────────────────────────────────────────────

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                updateUI(state)
            }
        }
    }

    private fun updateUI(state: UiState) {
        // Estado BLE
        tvBleStatus.text = "BLE: ${state.bleStatus}"
        tvBleStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.isConnected) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )

        // Predicción
        tvPrediction.text = "Gesto: ${state.prediction.label}"

        // Probabilidades (texto)
        val p = state.probabilities
        tvProbabilities.text = String.format(
            "Relajado: %.1f%%  Puño: %.1f%%  Pulgar: %.1f%%",
            p[0] * 100, p[1] * 100, p[2] * 100
        )

        // Barras de progreso (0–100)
        pbRelaxed.progress = (p.getOrElse(0) { 0f } * 100).toInt()
        pbFist.progress    = (p.getOrElse(1) { 0f } * 100).toInt()
        pbThumb.progress   = (p.getOrElse(2) { 0f } * 100).toInt()

        // Contador de paquetes
        tvPacketCount.text = "Paquetes recibidos: ${state.packetsRx}"

        // Indicador de alarma
        if (state.alarmActive) {
            tvAlarm.text = "⚠ ALARMA DE RIESGO ACTIVA"
            tvAlarm.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            tvAlarm.visibility = android.view.View.VISIBLE
        } else {
            tvAlarm.visibility = android.view.View.GONE
        }

        // Habilitar/deshabilitar botones
        btnConnect.isEnabled    = !state.isConnected
        btnDisconnect.isEnabled = state.isConnected
    }

    // ── Gestión de permisos ───────────────────────────────────────────────────

    private fun requestBlePermissionsAndConnect() {
        val required = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: permisos BLE propios
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            ).forEach { perm ->
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    required.add(perm)
                }
            }
        } else {
            // Android < 12: necesita ACCESS_FINE_LOCATION para BLE
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                required.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (required.isEmpty()) {
            viewModel.startScan()
        } else {
            permissionLauncher.launch(required.toTypedArray())
        }
    }
}

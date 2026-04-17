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
import com.emg.classifier.ui.MainViewModel
import com.emg.classifier.ui.UiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()

    private lateinit var tvBleStatus:     TextView
    private lateinit var tvPrediction:    TextView
    private lateinit var tvProbabilities: TextView
    private lateinit var tvPacketCount:   TextView
    private lateinit var tvAlarm:         TextView
    private lateinit var tvRawHex:        TextView
    private lateinit var pbRelaxed:       ProgressBar
    private lateinit var pbFist:          ProgressBar
    private lateinit var pbThumb:         ProgressBar
    private lateinit var btnConnect:      Button
    private lateinit var btnDisconnect:   Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) {
            viewModel.startScan()
        } else {
            Toast.makeText(this, "Permisos BLE necesarios para conectar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupButtons()
        observeState()
    }

    private fun bindViews() {
        tvBleStatus     = findViewById(R.id.tvBleStatus)
        tvPrediction    = findViewById(R.id.tvPrediction)
        tvProbabilities = findViewById(R.id.tvProbabilities)
        tvPacketCount   = findViewById(R.id.tvPacketCount)
        tvAlarm         = findViewById(R.id.tvAlarm)
        tvRawHex        = findViewById(R.id.tvRawHex)
        pbRelaxed       = findViewById(R.id.pbRelaxed)
        pbFist          = findViewById(R.id.pbFist)
        pbThumb         = findViewById(R.id.pbThumb)
        btnConnect      = findViewById(R.id.btnConnect)
        btnDisconnect   = findViewById(R.id.btnDisconnect)
    }

    private fun setupButtons() {
        btnConnect.setOnClickListener {
            Toast.makeText(this, "Buscando dispositivo BLE...", Toast.LENGTH_SHORT).show()
            requestPermissionsAndScan()
        }
        btnDisconnect.setOnClickListener {
            viewModel.disconnect()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.uiState.collectLatest { state -> updateUI(state) }
        }
    }

    private fun updateUI(state: UiState) {
        tvBleStatus.text = "BLE: ${state.bleStatus}"
        tvBleStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.isConnected) android.R.color.holo_green_dark
                else android.R.color.holo_red_dark
            )
        )

        tvPrediction.text = "Gesto: ${state.prediction.label}"

        val p = state.probabilities
        tvProbabilities.text = String.format(
            "Relajado: %.0f%%  Puño: %.0f%%  Pulgar: %.0f%%",
            p.getOrElse(0){0f} * 100,
            p.getOrElse(1){0f} * 100,
            p.getOrElse(2){0f} * 100
        )

        pbRelaxed.progress = (p.getOrElse(0){0f} * 100).toInt()
        pbFist.progress    = (p.getOrElse(1){0f} * 100).toInt()
        pbThumb.progress   = (p.getOrElse(2){0f} * 100).toInt()

        tvPacketCount.text = "Paquetes: ${state.packetsRx}"

        // Muestra los bytes crudos recibidos (útil para verificar nRF Connect)
        tvRawHex.text = if (state.lastRawHex.isNotEmpty())
            "Hex: ${state.lastRawHex}" else "Hex: (esperando datos...)"

        if (state.alarmActive) {
            tvAlarm.visibility = android.view.View.VISIBLE
        } else {
            tvAlarm.visibility = android.view.View.GONE
        }

        btnConnect.isEnabled    = !state.isConnected
        btnDisconnect.isEnabled =  state.isConnected
    }

    private fun requestPermissionsAndScan() {
        val required = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .forEach { required.add(it) }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                required.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (required.isEmpty()) viewModel.startScan()
        else permissionLauncher.launch(required.toTypedArray())
    }
}

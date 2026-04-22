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
import com.emg.classifier.ml.EmgConfig
import com.emg.classifier.ui.MainViewModel
import com.emg.classifier.ui.UiState
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: MainViewModel by viewModels()

    // Vistas
    private lateinit var tvBleStatus:  TextView
    private lateinit var tvClass:      TextView
    private lateinit var tvConfidence: TextView
    private lateinit var tvPackets:    TextView
    private lateinit var tvAlarm:      TextView
    private lateinit var tvProbs:      TextView
    private lateinit var btnConnect:   Button
    private lateinit var btnDisconnect:Button

    // Una barra de progreso por clase (7 clases)
    private lateinit var progressBars: List<ProgressBar>

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) vm.startScan()
        else Toast.makeText(this, "Permisos BLE requeridos", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        setupButtons()
        observeState()
    }

    private fun bindViews() {
        tvBleStatus   = findViewById(R.id.tvBleStatus)
        tvClass       = findViewById(R.id.tvClass)
        tvConfidence  = findViewById(R.id.tvConfidence)
        tvPackets     = findViewById(R.id.tvPackets)
        tvAlarm       = findViewById(R.id.tvAlarm)
        tvProbs       = findViewById(R.id.tvProbs)
        btnConnect    = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        progressBars = listOf(
            findViewById(R.id.pb0),  // REST
            findViewById(R.id.pb1),  // Palmar (5)
            findViewById(R.id.pb2),  // Lateral (6)
            findViewById(R.id.pb3),  // WristFlex (13)
            findViewById(R.id.pb4),  // WristExt (14)
            findViewById(R.id.pb5),  // RadialDev (15)
            findViewById(R.id.pb6)   // UlnarDev (16)
        )
    }

    private fun setupButtons() {
        btnConnect.setOnClickListener { requestPermissionsAndScan() }
        btnDisconnect.setOnClickListener { vm.disconnect() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            vm.uiState.collectLatest { state -> render(state) }
        }
    }

    private fun render(s: UiState) {
        // BLE status
        tvBleStatus.text = "BLE: ${s.bleStatus}"
        tvBleStatus.setTextColor(ContextCompat.getColor(this,
            if (s.isConnected) android.R.color.holo_green_dark
            else android.R.color.holo_red_dark))

        // Predicción
        tvClass.text      = "Gesto: ${s.className}"
        tvConfidence.text = "Confianza: ${(s.confidence * 100).toInt()} %"
        tvPackets.text    = "Paquetes: ${s.packetsRx}  |  Buffer: ${s.samplesInBuf} muestras"

        // Probabilidades en texto
        val sb = StringBuilder()
        EmgConfig.CLASS_NAMES.forEachIndexed { i, name ->
            val pct = (s.probabilities.getOrElse(i) { 0f } * 100).toInt()
            sb.append("$name: $pct%  ")
        }
        tvProbs.text = sb.toString().trim()

        // Barras de progreso
        progressBars.forEachIndexed { i, pb ->
            pb.progress = (s.probabilities.getOrElse(i) { 0f } * 100).toInt()
        }

        // Alarma
        tvAlarm.visibility = if (s.alarmActive)
            android.view.View.VISIBLE else android.view.View.GONE

        // Botones
        btnConnect.isEnabled    = !s.isConnected
        btnDisconnect.isEnabled =  s.isConnected
    }

    private fun requestPermissionsAndScan() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                .forEach { needed.add(it) }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isEmpty()) vm.startScan()
        else permLauncher.launch(needed.toTypedArray())
    }
}

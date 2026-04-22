package com.emg.classifier.ml

/**
 * Constantes del pipeline EMG.
 *
 * CRÍTICO: estos valores deben coincidir exactamente con train_emg.py
 *
 * Dataset: NinaPro DB1 — FS = 100 Hz
 * Ventana: 300 ms → 30 muestras
 * Overlap: 50 % → step = 15 muestras
 * Canales: índices 5, 8, 9 del dataset (3 canales)
 * Features: RMS, MAV, ZC, WL por canal → 4 × 3 = 12 features
 * Clases:   REST(0), 5, 6, 13, 14, 15, 16 → 7 clases en total
 */
object EmgConfig {

    // ── Señal ─────────────────────────────────────────────────────────────────
    const val FS_HZ          = 100
    const val NUM_CHANNELS   = 3
    const val SCALE_FACTOR   = 10000f   // int16 → float: valor / SCALE_FACTOR

    // ── Ventaneo ──────────────────────────────────────────────────────────────
    const val WIN_SAMPLES    = 30       // 300 ms × 100 Hz = 30 muestras
    const val STEP_SAMPLES   = 15       // 50 % overlap

    // ── Features ──────────────────────────────────────────────────────────────
    const val FEATURES_PER_CHANNEL = 4  // RMS, MAV, ZC, WL
    const val FEATURE_VEC_LEN      = NUM_CHANNELS * FEATURES_PER_CHANNEL  // 12
    const val ZC_THRESHOLD         = 1e-4f  // banda muerta para Zero Crossing

    // ── Modelo ────────────────────────────────────────────────────────────────
    const val MODEL_ASSET    = "model.tflite"
    const val NUM_CLASSES    = 7

    /**
     * Etiquetas de clase en el orden que devuelve el modelo.
     * LabelEncoder de sklearn ordena las clases numéricamente: [0, 5, 6, 13, 14, 15, 16]
     */
    val CLASS_LABELS = intArrayOf(0, 5, 6, 13, 14, 15, 16)

    val CLASS_NAMES = arrayOf(
        "REST",        // clase 0
        "Palmar",      // clase 5
        "Lateral",     // clase 6
        "WristFlex",   // clase 13
        "WristExt",    // clase 14
        "RadialDev",   // clase 15
        "UlnarDev"     // clase 16
    )

    /**
     * Clases que activan la alarma (flexión/extensión de muñeca).
     * Índices dentro de CLASS_LABELS: 3=WristFlex(13), 4=WristExt(14)
     */
    val ALARM_CLASS_INDICES = setOf(3, 4)  // WristFlex, WristExt

    // ── Normalización Z-score (cargados de norm_mu.npy / norm_sigma.npy) ─────
    // Orden: [rms_ch0, mav_ch0, zc_ch0, wl_ch0,
    //         rms_ch1, mav_ch1, zc_ch1, wl_ch1,
    //         rms_ch2, mav_ch2, zc_ch2, wl_ch2]
    val NORM_MU = floatArrayOf(
        0.20076707f, 0.19448505f, 0.00066950f, 0.27533001f,
        0.40609491f, 0.39228168f, 0.00066950f, 0.55981463f,
        0.54502803f, 0.53249162f, 0.00013390f, 0.63888168f
    )

    val NORM_SIGMA = floatArrayOf(
        0.39625412f, 0.39020935f, 0.03565474f, 0.43801525f,
        0.66936463f, 0.65917939f, 0.03658119f, 0.75504935f,
        0.72087574f, 0.71360654f, 0.01636366f, 0.70498335f
    )
}

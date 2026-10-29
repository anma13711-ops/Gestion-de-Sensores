package com.anmaoff.gestindesensores

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    companion object {
        private const val GRAVEDAD = 9.81f
        private const val DESVIACION_MAXIMA = 8f   // se usa para escalar la barra de intensidad
    }

    private lateinit var sensorManager: SensorManager
    private var acelerometro: Sensor? = null
    private var giroscopio: Sensor? = null

    // Hilo secundario: los eventos del sensor NO se procesan en el hilo principal
    private var sensorThread: HandlerThread? = null
    private var pausado = false

    // Filtros: acelerómetro (m/s²) y giroscopio (rad/s)
    private val filtroAcel = SensorFilter(0.15f, 100)
    private val filtroGiro = SensorFilter(0.05f, 100)

    private lateinit var tvDisponibilidad: TextView
    private lateinit var tvAcel: TextView
    private lateinit var tvGiro: TextView
    private lateinit var tvEstado: TextView
    private lateinit var tvStats: TextView
    private lateinit var tvLive: TextView
    private lateinit var pbIntensidad: ProgressBar
    private lateinit var btnPausa: Button
    private lateinit var btnReiniciar: Button
    private lateinit var swFiltro: Switch
    private lateinit var btnServicio: Button

    // Permiso de notificaciones (obligatorio desde Android 13)
    private val permisoNotificaciones =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            iniciarServicio()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDisponibilidad = findViewById(R.id.tvDisponibilidad)
        tvAcel = findViewById(R.id.tvAcel)
        tvGiro = findViewById(R.id.tvGiro)
        tvEstado = findViewById(R.id.tvEstado)
        tvStats = findViewById(R.id.tvStats)
        tvLive = findViewById(R.id.tvLive)
        pbIntensidad = findViewById(R.id.pbIntensidad)
        btnPausa = findViewById(R.id.btnPausa)
        btnReiniciar = findViewById(R.id.btnReiniciar)
        swFiltro = findViewById(R.id.swFiltro)
        btnServicio = findViewById(R.id.btnServicio)

        // 1) Verificar disponibilidad del hardware (físico o virtual)
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        giroscopio = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        mostrarDisponibilidad()

        // Activar/desactivar el filtro para comparar el comportamiento
        swFiltro.isChecked = true
        swFiltro.setOnCheckedChangeListener { _, activo ->
            filtroAcel.setEnabled(activo)
            filtroGiro.setEnabled(activo)
        }

        // Pausar / reanudar la escucha de sensores (ahorra batería)
        btnPausa.setOnClickListener {
            pausado = !pausado
            if (pausado) {
                detenerSensores()
                btnPausa.text = "Reanudar"
                tvLive.text = "■ PAUSADO"
            } else {
                iniciarSensores()
                btnPausa.text = "Pausar"
                tvLive.text = "● EN VIVO"
            }
        }

        // Reiniciar contadores y pantalla
        btnReiniciar.setOnClickListener {
            filtroAcel.reset()
            filtroGiro.reset()
            tvEstado.text = "—"
            pbIntensidad.progress = 0
            actualizarEstadisticas()
        }

        // Servicio en segundo plano (sigue activo aunque cierres la app)
        btnServicio.setOnClickListener {
            if (SensorService.activo) detenerServicio() else solicitarPermisoEIniciar()
        }
    }

    private fun solicitarPermisoEIniciar() {
        val necesitaPermiso = Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        if (necesitaPermiso) {
            permisoNotificaciones.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            iniciarServicio()
        }
    }

    private fun iniciarServicio() {
        ContextCompat.startForegroundService(this, Intent(this, SensorService::class.java))
        actualizarBotonServicio(true)
    }

    private fun detenerServicio() {
        stopService(Intent(this, SensorService::class.java))
        actualizarBotonServicio(false)
    }

    private fun actualizarBotonServicio(activo: Boolean) {
        btnServicio.text = if (activo) "Detener monitoreo en segundo plano"
        else "Iniciar monitoreo en segundo plano"
    }

    private fun mostrarDisponibilidad() {
        val a = acelerometro?.let { "Acelerómetro: DISPONIBLE (${it.name})" }
            ?: "Acelerómetro: NO disponible"
        val g = giroscopio?.let { "Giroscopio: DISPONIBLE (${it.name})" }
            ?: "Giroscopio: NO disponible"
        tvDisponibilidad.text = "$a\n$g"
        if (acelerometro == null) tvAcel.text = "Sin sensor"
        if (giroscopio == null) tvGiro.text = "Sin sensor"
    }

    // 2) Habilitar listeners en un hilo secundario
    private fun iniciarSensores() {
        if (sensorThread != null) return
        val hilo = HandlerThread("RunTrackSensores").also { it.start() }
        sensorThread = hilo
        val handler = Handler(hilo.looper)

        acelerometro?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
        giroscopio?.let {
            sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
    }

    // 3) Detener listeners: evita consumo de batería innecesario
    private fun detenerSensores() {
        sensorManager.unregisterListener(listener)
        sensorThread?.quitSafely()
        sensorThread = null
    }

    override fun onResume() {
        super.onResume()
        if (!pausado) iniciarSensores()
        actualizarBotonServicio(SensorService.activo)
    }

    override fun onPause() {
        super.onPause()
        detenerSensores()
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]

            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (!filtroAcel.accept(event.values, event.timestamp)) return
                    val desviacion = calcularDesviacion(x, y, z)
                    val estado = clasificarMovimiento(desviacion)
                    val intensidad = ((desviacion / DESVIACION_MAXIMA) * 100f)
                        .coerceIn(0f, 100f).toInt()
                    runOnUiThread {
                        tvAcel.text = String.format(
                            Locale.US, "X %.2f\nY %.2f\nZ %.2f", x, y, z
                        )
                        tvEstado.text = estado
                        pbIntensidad.progress = intensidad
                        actualizarEstadisticas()
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (!filtroGiro.accept(event.values, event.timestamp)) return
                    runOnUiThread {
                        tvGiro.text = String.format(
                            Locale.US, "X %.2f\nY %.2f\nZ %.2f", x, y, z
                        )
                        actualizarEstadisticas()
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Desviación de la magnitud de la aceleración respecto a la gravedad. */
    private fun calcularDesviacion(x: Float, y: Float, z: Float): Float {
        val magnitud = sqrt(x * x + y * y + z * z)
        return abs(magnitud - GRAVEDAD)
    }

    private fun clasificarMovimiento(desviacion: Float): String = when {
        desviacion < 1.0f -> "Reposo"
        desviacion < 4.0f -> "Movimiento ligero (caminata)"
        else -> "Movimiento intenso (carrera)"
    }

    private fun actualizarEstadisticas() {
        tvStats.text = String.format(
            Locale.US,
            "Acelerómetro → recibidas: %d | mostradas: %d | descartadas: %d\n" +
                    "Giroscopio → recibidas: %d | mostradas: %d | descartadas: %d",
            filtroAcel.received, filtroAcel.accepted, filtroAcel.discarded,
            filtroGiro.received, filtroGiro.accepted, filtroGiro.discarded
        )
    }
}
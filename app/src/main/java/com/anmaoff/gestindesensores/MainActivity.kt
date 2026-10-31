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
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var acelerometro: Sensor? = null
    private var giroscopio: Sensor? = null

    // Hilo secundario: los eventos de los sensores NO se procesan en el hilo principal
    private var hilo: HandlerThread? = null

    // Filtros: acelerómetro (m/s²) y giroscopio (rad/s)
    private val filtroAcel = SensorFilter(0.15f, 100)
    private val filtroGiro = SensorFilter(0.05f, 100)

    private lateinit var tvDisponibilidad: TextView
    private lateinit var tvAcel: TextView
    private lateinit var tvGiro: TextView
    private lateinit var tvStats: TextView
    private lateinit var swFiltro: SwitchCompat
    private lateinit var btnServicio: Button

    // Permiso de notificaciones (obligatorio desde Android 13)
    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { iniciarServicio() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvDisponibilidad = findViewById(R.id.tvDisponibilidad)
        tvAcel = findViewById(R.id.tvAcel)
        tvGiro = findViewById(R.id.tvGiro)
        tvStats = findViewById(R.id.tvStats)
        swFiltro = findViewById(R.id.swFiltro)
        btnServicio = findViewById(R.id.btnServicio)

        // 1) Verificar la disponibilidad de los sensores
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        giroscopio = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        mostrarDisponibilidad()

        // Activar o desactivar el filtro para comparar el comportamiento
        swFiltro.setOnCheckedChangeListener { _, activo ->
            filtroAcel.enabled = activo
            filtroGiro.enabled = activo
            filtroAcel.reset()
            filtroGiro.reset()
            actualizarEstadisticas()
        }

        // Botón para iniciar o detener la tarea en segundo plano
        btnServicio.setOnClickListener {
            if (SensorService.activo) {
                stopService(Intent(this, SensorService::class.java))
                actualizarBoton(false)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                pedirPermiso.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                iniciarServicio()
            }
        }
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

    // 2) Registrar los listeners en un hilo secundario
    private fun iniciarSensores() {
        if (hilo != null) return
        val h = HandlerThread("RunTrackSensores").also { it.start() }
        hilo = h
        val handler = Handler(h.looper)
        acelerometro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
        giroscopio?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
    }

    // 3) Detener los listeners: evita consumo de batería innecesario
    private fun detenerSensores() {
        sensorManager.unregisterListener(this)
        hilo?.quitSafely()
        hilo = null
    }

    override fun onResume() {
        super.onResume()
        iniciarSensores()
        actualizarBoton(SensorService.activo)
    }

    override fun onPause() {
        super.onPause()
        detenerSensores()
    }

    // Se ejecuta en el hilo secundario cada vez que un sensor entrega una lectura
    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val texto = String.format(Locale.US, "X: %.2f   Y: %.2f   Z: %.2f", x, y, z)

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Si el filtro la descarta, no se actualiza la pantalla
                if (!filtroAcel.accept(event.values, event.timestamp)) return
                runOnUiThread {
                    tvAcel.text = texto
                    actualizarEstadisticas()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!filtroGiro.accept(event.values, event.timestamp)) return
                runOnUiThread {
                    tvGiro.text = texto
                    actualizarEstadisticas()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun actualizarEstadisticas() {
        tvStats.text = String.format(
            Locale.US,
            "Acelerómetro → recibidas: %d | mostradas: %d | descartadas: %d\n" +
                    "Giroscopio → recibidas: %d | mostradas: %d | descartadas: %d",
            filtroAcel.received, filtroAcel.accepted, filtroAcel.discarded,
            filtroGiro.received, filtroGiro.accepted, filtroGiro.discarded
        )
    }

    private fun iniciarServicio() {
        ContextCompat.startForegroundService(this, Intent(this, SensorService::class.java))
        actualizarBoton(true)
    }

    private fun actualizarBoton(activo: Boolean) {
        btnServicio.text = if (activo) "Detener monitoreo en segundo plano"
        else "Iniciar monitoreo en segundo plano"
    }
}
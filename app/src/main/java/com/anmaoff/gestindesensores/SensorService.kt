package com.anmaoff.gestindesensores

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Servicio en primer plano: sigue escuchando acelerómetro y giroscopio aunque la app esté cerrada.
 * La notificación permanente muestra las lecturas de los sensores y se lanza una alerta
 * cuando se detecta movimiento intenso.
 */
class SensorService : Service(), SensorEventListener {

    companion object {
        const val CANAL_MONITOR = "runtrack_monitor"
        const val CANAL_ALERTA = "runtrack_alerta"
        const val ID_MONITOR = 1
        const val ID_ALERTA = 2
        const val ACCION_DETENER = "com.anmaoff.gestindesensores.DETENER"

        private const val GRAVEDAD = 9.81f
        private const val UMBRAL_INTENSO = 4f
        private const val ESPERA_ALERTAS_MS = 30_000L        // evita avisar sin parar
        private const val ESPERA_NOTIFICACION_MS = 1_000L    // actualiza la notificación 1 vez por segundo

        @Volatile
        var activo = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var hilo: HandlerThread? = null

    private val filtroAcel = SensorFilter(0.15f, 100)
    private val filtroGiro = SensorFilter(0.05f, 100)

    private var ultimaAlerta = 0L
    private var ultimaActualizacion = 0L
    private var textoAcel = "Acelerómetro: esperando datos..."
    private var textoGiro = "Giroscopio: esperando datos..."

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        crearCanales()
        activo = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Un servicio en primer plano debe mostrar su notificación de inmediato
        startForeground(ID_MONITOR, notificacionMonitor())
        iniciarSensores()
        return START_STICKY
    }

    private fun iniciarSensores() {
        if (hilo != null) return
        val acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val giroscopio = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (acelerometro == null && giroscopio == null) return

        val h = HandlerThread("RunTrackServicio").also { it.start() }
        hilo = h
        val handler = Handler(h.looper)

        acelerometro?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
        giroscopio?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, handler)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                if (!filtroAcel.accept(event.values, event.timestamp)) return
                textoAcel = String.format(
                    Locale.US, "Acelerómetro (m/s²): X %.1f  Y %.1f  Z %.1f", x, y, z
                )

                val desviacion = abs(sqrt(x * x + y * y + z * z) - GRAVEDAD)
                if (desviacion >= UMBRAL_INTENSO) {
                    val ahora = SystemClock.elapsedRealtime()
                    if (ahora - ultimaAlerta >= ESPERA_ALERTAS_MS) {
                        ultimaAlerta = ahora
                        getSystemService(NotificationManager::class.java)
                            .notify(ID_ALERTA, notificacionAlerta())
                    }
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!filtroGiro.accept(event.values, event.timestamp)) return
                textoGiro = String.format(
                    Locale.US, "Giroscopio (rad/s): X %.2f  Y %.2f  Z %.2f", x, y, z
                )
            }
            else -> return
        }
        actualizarNotificacion()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Refresca la notificación permanente, máximo una vez por segundo. */
    private fun actualizarNotificacion() {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaActualizacion < ESPERA_NOTIFICACION_MS) return
        ultimaActualizacion = ahora
        if (activo) {
            getSystemService(NotificationManager::class.java)
                .notify(ID_MONITOR, notificacionMonitor())
        }
    }

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun notificacionMonitor(): Notification {
        val detener = PendingIntent.getService(
            this, 1,
            Intent(this, SensorService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL_MONITOR)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("RunTrack monitoreando")
            .setContentText(textoAcel)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$textoAcel\n$textoGiro"))
            .setContentIntent(abrirApp())
            .addAction(0, "Detener", detener)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notificacionAlerta(): Notification =
        NotificationCompat.Builder(this, CANAL_ALERTA)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("¡Ritmo intenso detectado!")
            .setContentText("RunTrack detectó movimiento de carrera.")
            .setContentIntent(abrirApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun crearCanales() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CANAL_MONITOR, "Monitoreo de sensores", NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CANAL_ALERTA, "Alertas de movimiento", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    override fun onDestroy() {
        activo = false
        sensorManager.unregisterListener(this)
        hilo?.quitSafely()
        hilo = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
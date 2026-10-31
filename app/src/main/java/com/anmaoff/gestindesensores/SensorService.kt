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

/**
 * Servicio en primer plano: sigue leyendo el acelerómetro y el giroscopio
 * aunque la app esté cerrada, y muestra las lecturas en una notificación.
 */
class SensorService : Service(), SensorEventListener {

    companion object {
        const val CANAL = "runtrack_monitor"
        const val ID_NOTIFICACION = 1
        const val ACCION_DETENER = "com.anmaoff.gestindesensores.DETENER"

        @Volatile
        var activo = false
            private set
    }

    private lateinit var sensorManager: SensorManager
    private var hilo: HandlerThread? = null

    private val filtroAcel = SensorFilter(0.15f, 100)
    private val filtroGiro = SensorFilter(0.05f, 100)

    private var textoAcel = "Acelerómetro: esperando datos..."
    private var textoGiro = "Giroscopio: esperando datos..."
    private var ultimaActualizacion = 0L

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        crearCanal()
        activo = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Botón "Detener" de la notificación
        if (intent?.action == ACCION_DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Un servicio en primer plano debe mostrar su notificación de inmediato
        startForeground(ID_NOTIFICACION, crearNotificacion())
        iniciarSensores()
        return START_STICKY
    }

    private fun iniciarSensores() {
        if (hilo != null) return
        val acelerometro = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val giroscopio = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        if (acelerometro == null && giroscopio == null) return

        // Hilo secundario para no bloquear el hilo principal
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
                textoAcel = String.format(Locale.US, "Acelerómetro (m/s²): X %.1f  Y %.1f  Z %.1f", x, y, z)
            }
            Sensor.TYPE_GYROSCOPE -> {
                if (!filtroGiro.accept(event.values, event.timestamp)) return
                textoGiro = String.format(Locale.US, "Giroscopio (rad/s): X %.2f  Y %.2f  Z %.2f", x, y, z)
            }
            else -> return
        }
        actualizarNotificacion()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Refresca la notificación como máximo una vez por segundo. */
    private fun actualizarNotificacion() {
        val ahora = SystemClock.elapsedRealtime()
        if (ahora - ultimaActualizacion < 1000L) return
        ultimaActualizacion = ahora
        if (activo) {
            getSystemService(NotificationManager::class.java)
                .notify(ID_NOTIFICACION, crearNotificacion())
        }
    }

    // La clase SensorService es la que construye y dispara la notificación
    private fun crearNotificacion(): Notification {
        val abrirApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val detener = PendingIntent.getService(
            this, 1,
            Intent(this, SensorService::class.java).setAction(ACCION_DETENER),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("RunTrack monitoreando")
            .setContentText(textoAcel)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$textoAcel\n$textoGiro"))
            .setContentIntent(abrirApp)
            .addAction(0, "Detener", detener)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun crearCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(CANAL, "Monitoreo de sensores", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(canal)
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
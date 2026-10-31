package com.anmaoff.gestindesensores

import kotlin.math.abs

/**
 * Filtro de eventos de un sensor.
 * Descarta las lecturas que llegan muy seguidas (menos del intervalo mínimo)
 * y las lecturas duplicadas (ningún eje cambió lo suficiente).
 */
class SensorFilter(private val minDelta: Float, minIntervalMs: Long) {

    private val minIntervalNs = minIntervalMs * 1_000_000L
    private var ultima: FloatArray? = null
    private var ultimoTiempo = 0L

    // Si es false, el filtro deja pasar todas las lecturas
    var enabled = true

    var received = 0
        private set
    var accepted = 0
        private set
    val discarded: Int
        get() = received - accepted

    /** Devuelve true si la lectura se debe mostrar y false si se descarta. */
    @Synchronized
    fun accept(values: FloatArray, timestampNs: Long): Boolean {
        received++
        val previa = ultima
        if (enabled && previa != null) {
            // 1) Control de frecuencia: ignora lecturas muy seguidas
            if (timestampNs - ultimoTiempo < minIntervalNs) return false

            // 2) Control de duplicados: ignora si ningún eje cambió lo suficiente
            var cambio = false
            for (i in 0..2) {
                if (abs(values[i] - previa[i]) >= minDelta) {
                    cambio = true
                    break
                }
            }
            if (!cambio) return false
        }
        ultima = values.clone()
        ultimoTiempo = timestampNs
        accepted++
        return true
    }

    /** Pone en cero los contadores y la última lectura. */
    @Synchronized
    fun reset() {
        ultima = null
        ultimoTiempo = 0L
        received = 0
        accepted = 0
    }
}
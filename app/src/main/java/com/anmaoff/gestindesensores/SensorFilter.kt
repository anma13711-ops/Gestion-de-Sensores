package com.anmaoff.gestindesensores

import kotlin.math.abs

/**
 * Filtro de eventos de sensor.
 * Descarta lecturas duplicadas (cambio menor al umbral) y lecturas
 * demasiado seguidas (menor al intervalo mínimo), para cuidar batería y UI.
 */
class SensorFilter(private val minDelta: Float, minIntervalMs: Long) {

    private val minIntervalNs = minIntervalMs * 1_000_000L
    private var last: FloatArray? = null
    private var lastTs = 0L
    private var enabled = true

    var received = 0
        private set
    var accepted = 0
        private set
    val discarded: Int
        @Synchronized get() = received - accepted

    /** Devuelve true si la lectura debe procesarse y mostrarse. */
    @Synchronized
    fun accept(values: FloatArray, timestampNs: Long): Boolean {
        received++

        val previa = last
        if (enabled && previa != null) {
            // 1) Control de frecuencia: ignora lecturas muy seguidas
            if (timestampNs - lastTs < minIntervalNs) return false

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

        last = values.clone()
        lastTs = timestampNs
        accepted++
        return true
    }

    @Synchronized
    fun setEnabled(valor: Boolean) {
        enabled = valor
        reset()
    }

    @Synchronized
    fun reset() {
        last = null
        lastTs = 0L
        received = 0
        accepted = 0
    }
}
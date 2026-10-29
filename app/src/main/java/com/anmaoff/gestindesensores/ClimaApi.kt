package com.anmaoff.gestindesensores

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Consulta el clima actual en la API pública Open-Meteo (servicio en la nube, sin API key).
 * Se ejecuta en un hilo aparte para no bloquear la interfaz.
 * Un corredor necesita saber temperatura y viento antes de salir a entrenar.
 */
object ClimaApi {

    // Coordenadas de ejemplo: cámbialas por las de tu ciudad
    private const val LATITUD = 21.1236
    private const val LONGITUD = -101.6860

    fun consultar(alTerminar: (String) -> Unit) {
        Thread {
            val resultado = try {
                val url = URL(
                    "https://api.open-meteo.com/v1/forecast" +
                            "?latitude=$LATITUD&longitude=$LONGITUD" +
                            "&current=temperature_2m,wind_speed_10m"
                )
                val conexion = url.openConnection() as HttpURLConnection
                conexion.connectTimeout = 5000
                conexion.readTimeout = 5000
                val json = conexion.inputStream.bufferedReader().use { it.readText() }
                conexion.disconnect()

                val actual = JSONObject(json).getJSONObject("current")
                val temperatura = actual.getDouble("temperature_2m")
                val viento = actual.getDouble("wind_speed_10m")
                String.format(Locale.US, "%.1f °C, viento %.0f km/h", temperatura, viento)
            } catch (e: Exception) {
                "clima no disponible"
            }
            alTerminar(resultado)
        }.start()
    }
}
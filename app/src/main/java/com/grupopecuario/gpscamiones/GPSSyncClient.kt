package com.grupopecuario.gpscamiones

import android.content.Context
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GPSSyncClient(context: Context) {
    companion object {
        const val KEY_SERVER_URL = "sync_server_url"
        const val KEY_API_KEY = "sync_api_key"
        const val KEY_PROVIDER_UNIT_ID = "sync_provider_unit_id"
        const val KEY_PLATE = "sync_plate"
        const val DEFAULT_SERVER_URL = "https://distribucionrutas.duckdns.org/api/mobile/positions/batch"
    }

    private val appContext = context.applicationContext
    private val database = GPSDatabaseHelper.getInstance(appContext)
    private val prefs = appContext.getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)

    fun sincronizarPendientes(): Int {
        val apiKey = prefs.getString(KEY_API_KEY, "")?.trim().orEmpty()
        val providerUnitId = prefs.getInt(KEY_PROVIDER_UNIT_ID, 0)
        val plate = prefs.getString(KEY_PLATE, "")?.trim().orEmpty()
        val serverUrl = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL)?.trim().orEmpty()
        if (apiKey.isEmpty() || providerUnitId < 1 || plate.isEmpty() || !serverUrl.startsWith("https://")) {
            return 0
        }
        val pending = database.obtenerPendientesSincronizacion(50)
        if (pending.isEmpty()) return 0

        val points = JSONArray()
        pending.forEach { item ->
            points.put(JSONObject().apply {
                put("id", item.id)
                put("latitude", item.latitud)
                put("longitude", item.longitud)
                put("accuracyMeters", item.precision.toDouble())
                put("speedMetersPerSecond", item.velocidad?.toDouble() ?: JSONObject.NULL)
                put("gpsTimestamp", item.fechaGPS)
                put("recordedTimestamp", item.fechaRegistro)
                put("mock", item.esSimulada)
            })
        }
        val body = JSONObject().apply {
            put("deviceId", Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID))
            put("providerUnitId", providerUnitId)
            put("plate", plate.uppercase())
            put("points", points)
        }.toString()

        val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", apiKey)
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w("GPSCamiones", "Servidor de sincronización respondió HTTP $code")
                0
            } else {
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val ids = JSONObject(response).optJSONArray("processedIds") ?: JSONArray()
                val processed = buildList {
                    for (index in 0 until ids.length()) add(ids.getString(index))
                }
                database.marcarSincronizados(processed)
                processed.size
            }
        } catch (error: Exception) {
            Log.w("GPSCamiones", "Sincronización pendiente: ${error.javaClass.simpleName}")
            0
        } finally {
            connection.disconnect()
        }
    }
}

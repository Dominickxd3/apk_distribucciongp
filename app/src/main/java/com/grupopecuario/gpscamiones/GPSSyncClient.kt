package com.grupopecuario.gpscamiones

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GPSSyncClient(context: Context) {
    companion object {
        const val DEFAULT_SERVER_URL =
            "https://distribucionrutas.duckdns.org/api/mobile/positions/batch"
    }

    private val appContext = context.applicationContext
    private val database = GPSDatabaseHelper.getInstance(appContext)
    private val config = ConfigManager.getInstance(appContext)
    private val authManager = AuthManager.getInstance(appContext)

    fun sincronizarPendientes(): Int {
        val session = authManager.getCurrentSession()
        val token = session.token
        val providerUnitId = config.providerUnitId
        val plate = config.nombreDispositivo.trim().uppercase()
        val serverUrl = config.servidorSyncUrl.trim().ifBlank { DEFAULT_SERVER_URL }

        if (
            token.isNullOrBlank() ||
            !session.isAuthenticated ||
            providerUnitId < 1 ||
            plate.isEmpty() ||
            !urlPermitida(serverUrl)
        ) {
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
            put("plate", plate)
            put("points", points)
        }.toString()

        val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            if (code !in 200..299) {
                if (code == 401 || code == 403) {
                    // El token expiró o fue revocado: se cierra la sesión para
                    // que la app vuelva a pedir credenciales.
                    authManager.logout()
                    Log.w("GPSCamiones", "Token de sesión rechazado (HTTP $code); se requiere iniciar sesión de nuevo")
                } else {
                    Log.w("GPSCamiones", "Servidor de sincronización respondió HTTP $code")
                }
                0
            } else {
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val ids = JSONObject(response).optJSONArray("processedIds") ?: JSONArray()
                val processed = buildList {
                    for (index in 0 until ids.length()) add(ids.getString(index))
                }
                database.marcarSincronizados(processed)
                if (processed.isNotEmpty()) {
                    config.fechaUltimaSincronizacionMs = System.currentTimeMillis()
                }
                processed.size
            }
        } catch (error: Exception) {
            Log.w("GPSCamiones", "Sincronización pendiente: ${error.javaClass.simpleName}")
            0
        } finally {
            connection.disconnect()
        }
    }

    /**
     * En producción solo se permite HTTPS. En compilaciones de depuración se
     * acepta HTTP para poder probar contra un servidor local con `adb reverse`.
     */
    private fun urlPermitida(serverUrl: String): Boolean {
        if (serverUrl.startsWith("https://")) return true

        val depurable =
            (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return depurable && serverUrl.startsWith("http://")
    }
}

package com.grupopecuario.gpscamiones

import android.content.Context
import android.util.Log
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.config.ConfigManager
import com.grupopecuario.gpscamiones.operacion.OperacionRepository
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

class GPSSyncClient(context: Context) {
    companion object {
        const val DEFAULT_SERVER_URL =
            "https://distribucionrutas.duckdns.org/api/mobile/positions/batch"

        /** Tope de lotes por vaciado (50 posiciones c/u ≈ 6 h de recorrido sin señal). */
        private const val MAX_LOTES_POR_VACIADO = 60

        /** Evita dos vaciados en paralelo (llegada de señal + nueva posición). */
        private val vaciando = AtomicBoolean(false)
    }

    private val appContext = context.applicationContext
    private val database = GPSDatabaseHelper.getInstance(appContext)
    private val config = ConfigManager.getInstance(appContext)
    private val authManager = AuthManager.getInstance(appContext)

    private data class Credenciales(val token: String)

    private fun credenciales(): Credenciales? {
        val session = authManager.getCurrentSession()
        val token = session.token
        if (token.isNullOrBlank() || !session.isAuthenticated) return null
        return Credenciales(token)
    }

    /**
     * Sube todo lo acumulado en el celular (posiciones y eventos), lote por lote,
     * hasta vaciar la cola o perder la conexión. Llamar en hilo de fondo.
     * Devuelve cuántos registros confirmó la web.
     */
    fun vaciarPendientes(): Int {
        if (!vaciando.compareAndSet(false, true)) return 0
        var total = 0
        try {
            for (lote in 0 until MAX_LOTES_POR_VACIADO) {
                val subidos = sincronizarPendientes()
                total += subidos
                if (subidos == 0) break
            }
            for (lote in 0 until MAX_LOTES_POR_VACIADO) {
                val subidos = sincronizarEventos()
                total += subidos
                if (subidos == 0) break
            }
        } finally {
            vaciando.set(false)
        }
        if (total > 0) Log.i("GPSCamiones", "Sincronización: $total registros confirmados por la web")
        return total
    }

    /** Sube un lote de posiciones. Devuelve cuántas confirmó la web (0 = nada más que hacer). */
    fun sincronizarPendientes(): Int {
        val cred = credenciales() ?: return 0
        val serverUrl = config.servidorSyncUrl.trim().ifBlank { DEFAULT_SERVER_URL }
        if (!urlPermitida(serverUrl)) return 0

        val pending = database.obtenerPendientesSincronizacion(50)
        if (pending.isEmpty()) return 0

        // Se envía un lote por camión: las posiciones viejas conservan el camión
        // que tenía el celular cuando se registraron.
        val actualId = config.providerUnitId
        val actualPlaca = config.nombreDispositivo.trim().uppercase()
        val grupos = pending.groupBy {
            (it.providerUnitId ?: actualId) to (it.placa ?: actualPlaca)
        }

        var procesados = 0
        for ((vehiculo, items) in grupos) {
            val (providerUnitId, plate) = vehiculo
            if (providerUnitId < 1 || plate.isBlank()) continue

            val points = JSONArray()
            items.forEach { item ->
                points.put(JSONObject().apply {
                    put("id", item.id)
                    put("latitude", item.latitud)
                    put("longitude", item.longitud)
                    put("accuracyMeters", item.precision.toDouble())
                    put("speedMetersPerSecond", item.velocidad?.toDouble() ?: JSONObject.NULL)
                    put("gpsTimestamp", item.fechaGPS)
                    put("recordedTimestamp", item.fechaRegistro)
                    put("mock", item.esSimulada)
                    put("recordedOffline", item.sinConexion)
                })
            }
            val body = JSONObject().apply {
                put("deviceId", config.androidId)
                put("providerUnitId", providerUnitId)
                put("plate", plate)
                put("points", points)
            }

            val ids = enviar(serverUrl, cred, body) ?: break
            database.marcarSincronizados(ids)
            procesados += ids.size
        }
        if (procesados > 0) config.fechaUltimaSincronizacionMs = System.currentTimeMillis()
        return procesados
    }

    /** Sube un lote de eventos de paradas y geocercas. */
    fun sincronizarEventos(): Int {
        val cred = credenciales() ?: return 0
        val serverUrl = OperacionRepository.urlBase(config) + "/api/mobile/events/batch"
        if (!urlPermitida(serverUrl)) return 0

        val pendientes = database.obtenerEventosPendientes(100)
        if (pendientes.isEmpty()) return 0

        var procesados = 0
        for ((vehiculo, items) in pendientes.groupBy { it.providerUnitId to it.placa }) {
            val events = JSONArray()
            items.forEach { ev ->
                val obj = try { JSONObject(ev.datosJson) } catch (e: Exception) { JSONObject() }
                obj.put("id", ev.id)
                obj.put("type", ev.tipo)
                obj.put("timestamp", ev.fechaUtc)
                obj.put("latitude", ev.latitud)
                obj.put("longitude", ev.longitud)
                obj.put("recordedOffline", ev.sinConexion)
                if (ev.tipoParada != null) obj.put("stopType", ev.tipoParada)
                events.put(obj)
            }
            val body = JSONObject().apply {
                put("deviceId", config.androidId)
                put("providerUnitId", vehiculo.first)
                put("plate", vehiculo.second)
                put("events", events)
            }
            val ids = enviar(serverUrl, cred, body) ?: break
            database.marcarEventosSincronizados(ids)
            procesados += ids.size
        }
        return procesados
    }

    /** POST del lote; devuelve los ids confirmados o null si falló la conexión o el servidor. */
    private fun enviar(serverUrl: String, cred: Credenciales, body: JSONObject): List<String>? {
        val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer ${cred.token}")
        }
        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                if (code == 401 || code == 403) {
                    // El token expiró o fue revocado: se cierra la sesión para
                    // que la app vuelva a pedir credenciales.
                    authManager.logout()
                    Log.w("GPSCamiones", "Token de sesión rechazado (HTTP $code); se requiere iniciar sesión de nuevo")
                } else {
                    Log.w("GPSCamiones", "Servidor ($serverUrl) respondió HTTP $code")
                }
                null
            } else {
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val ids = JSONObject(response).optJSONArray("processedIds") ?: JSONArray()
                buildList { for (index in 0 until ids.length()) add(ids.getString(index)) }
            }
        } catch (error: Exception) {
            Log.w("GPSCamiones", "Sincronización pendiente: ${error.javaClass.simpleName}")
            null
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

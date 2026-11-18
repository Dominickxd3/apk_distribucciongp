package com.grupopecuario.gpscamiones.operacion

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Descarga desde la web la configuración operativa de este celular (camión
 * asignado, ruta del día, paradas, geocercas y umbrales) y la guarda en local
 * para seguir funcionando sin señal.
 *
 * Contrato: GET {base}/api/mobile/device/config  (ver docs/API_MOVIL.md)
 */
class OperacionRepository private constructor(context: Context) {

    companion object {
        private const val TAG = "GPSCamiones"
        private const val PREFS = "gps_operacion"
        private const val KEY_JSON = "config_json"
        private const val KEY_DESCARGADA_MS = "config_descargada_ms"
        private const val KEY_ULTIMO_INTENTO_MS = "config_ultimo_intento_ms"
        private const val KEY_ULTIMO_ERROR = "config_ultimo_error"
        private const val KEY_RUTA_VISITADAS = "ruta_visitadas_id"
        private const val KEY_VISITADAS = "ruta_paradas_visitadas"

        /** Cada cuánto se vuelve a consultar la web mientras hay internet. */
        const val INTERVALO_REFRESCO_MS = 10 * 60 * 1000L

        @Volatile
        private var instance: OperacionRepository? = null

        fun getInstance(context: Context): OperacionRepository =
            instance ?: synchronized(this) {
                instance ?: OperacionRepository(context.applicationContext).also { instance = it }
            }

        fun hayInternet(context: Context): Boolean {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }

        /** Raíz del API a partir de la URL configurada (o la de producción). */
        fun urlBase(config: ConfigManager): String {
            val configurado = config.servidorSyncUrl.trim()
            val base = configurado.ifBlank { "https://distribucionrutas.duckdns.org" }
            return if (base.contains("/api/")) base.substringBefore("/api/") else base.trimEnd('/')
        }

        fun parsear(json: JSONObject, descargadaMs: Long): ConfiguracionOperativa {
            val vehiculo = json.optJSONObject("vehicle")?.let {
                VehiculoAsignado(
                    providerUnitId = it.optInt("providerUnitId", 0),
                    placa = it.optString("plate"),
                    nombre = it.optString("name").ifBlank { it.optString("plate") }
                )
            }?.takeIf { it.providerUnitId > 0 }

            val ruta = json.optJSONObject("route")?.let { r ->
                val paradas = r.optJSONArray("stops") ?: JSONArray()
                RutaAsignada(
                    id = r.optLong("id"),
                    nombre = r.optString("name"),
                    fecha = r.optString("date"),
                    paradas = (0 until paradas.length()).map { i ->
                        val p = paradas.getJSONObject(i)
                        ParadaRuta(
                            id = p.optLong("id"),
                            orden = p.optInt("order", i + 1),
                            nombre = p.optString("name"),
                            latitud = p.getDouble("latitude"),
                            longitud = p.getDouble("longitude"),
                            radioMetros = p.optDouble("radiusMeters", 60.0)
                        )
                    }.sortedBy { it.orden }
                )
            }

            val lista = json.optJSONArray("geofences") ?: JSONArray()
            val geocercas = (0 until lista.length()).mapNotNull { i ->
                val g = lista.getJSONObject(i)
                val forma = when (g.optString("shape").lowercase()) {
                    "polygon" -> {
                        val pts = g.optJSONArray("points") ?: return@mapNotNull null
                        Forma.Poligono((0 until pts.length()).map { k ->
                            val par = pts.getJSONArray(k)
                            par.getDouble(0) to par.getDouble(1)
                        }).takeIf { it.vertices.size >= 3 }
                    }
                    else -> if (g.has("latitude") && g.has("longitude")) {
                        Forma.Circulo(
                            g.getDouble("latitude"),
                            g.getDouble("longitude"),
                            g.optDouble("radiusMeters", 100.0)
                        )
                    } else null
                } ?: return@mapNotNull null
                Geocerca(
                    id = g.optString("id"),
                    nombre = g.optString("name"),
                    tipo = TipoGeocerca.desde(g.optString("type")),
                    forma = forma
                )
            }

            val u = json.optJSONObject("thresholds")
            val d = UmbralesParada()
            val umbrales = if (u == null) d else UmbralesParada(
                velocidadDetenidoMps = u.optDouble("stopSpeedMps", d.velocidadDetenidoMps),
                radioParadaMetros = u.optDouble("stopRadiusMeters", d.radioParadaMetros),
                radioSalidaMetros = u.optDouble("stopExitRadiusMeters", d.radioSalidaMetros),
                minimoParadaSegundos = u.optLong("stopMinSeconds", d.minimoParadaSegundos),
                maximoTraficoSegundos = u.optLong("trafficMaxSeconds", d.maximoTraficoSegundos),
                minimoEntregaSegundos = u.optLong("deliveryMinSeconds", d.minimoEntregaSegundos),
                precisionMaximaMetros = u.optDouble("maxAccuracyMeters", d.precisionMaximaMetros)
            )

            return ConfiguracionOperativa(
                asignado = json.optBoolean("assigned", vehiculo != null) && vehiculo != null,
                vehiculo = vehiculo,
                ruta = ruta,
                geocercas = geocercas,
                umbrales = umbrales,
                version = json.optString("configVersion"),
                descargadaMs = descargadaMs
            )
        }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val config = ConfigManager.getInstance(appContext)
    private val auth = AuthManager.getInstance(appContext)

    @Volatile
    private var cache: ConfiguracionOperativa? = null

    /** Configuración vigente: la última descargada o la guardada en el celular. */
    fun actual(): ConfiguracionOperativa {
        cache?.let { return it }
        val guardada = prefs.getString(KEY_JSON, null)
        val cfg = try {
            if (guardada.isNullOrBlank()) ConfiguracionOperativa.VACIA
            else parsear(JSONObject(guardada), prefs.getLong(KEY_DESCARGADA_MS, 0L))
        } catch (e: Exception) {
            Log.w(TAG, "Configuración operativa local inválida; se ignora", e)
            ConfiguracionOperativa.VACIA
        }
        cache = cfg
        return cfg
    }

    val ultimoError: String
        get() = prefs.getString(KEY_ULTIMO_ERROR, "") ?: ""

    fun debeRefrescar(ahora: Long = System.currentTimeMillis()): Boolean =
        ahora - prefs.getLong(KEY_ULTIMO_INTENTO_MS, 0L) >= INTERVALO_REFRESCO_MS

    /**
     * Consulta la web. Debe llamarse en un hilo de fondo. Si falla se conserva la
     * configuración anterior, así el celular sigue reconociendo ruta y geocercas.
     */
    @Synchronized
    fun refrescar(): Boolean {
        prefs.edit().putLong(KEY_ULTIMO_INTENTO_MS, System.currentTimeMillis()).apply()
        val session = auth.getCurrentSession()
        val token = session.token
        if (!session.isAuthenticated || token.isNullOrBlank()) {
            registrarError("Sin sesión iniciada")
            return false
        }
        val url = urlBase(config) + "/api/mobile/device/config?deviceId=" +
            java.net.URLEncoder.encode(config.androidId, "UTF-8")

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Device-Id", config.androidId)
            setRequestProperty("X-Device-Model", config.modeloHardware)
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                if (code == 401 || code == 403) auth.logout()
                registrarError("La web respondió HTTP $code")
                return false
            }
            val texto = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val ahora = System.currentTimeMillis()
            val nueva = parsear(JSONObject(texto), ahora)
            prefs.edit()
                .putString(KEY_JSON, texto)
                .putLong(KEY_DESCARGADA_MS, ahora)
                .putString(KEY_ULTIMO_ERROR, "")
                .apply()
            cache = nueva

            // La asignación de la web manda sobre el nombre escrito en el celular
            val v = nueva.vehiculo
            if (nueva.asignado && v != null) {
                config.guardarAsignacionWeb(v.providerUnitId, v.placa, v.nombre)
            } else {
                config.guardarAsignacionWeb(0, "", "")
            }
            Log.i(TAG, "Configuración operativa actualizada: camión=${v?.placa}, ruta=${nueva.ruta?.nombre}, geocercas=${nueva.geocercas.size}")
            true
        } catch (e: Exception) {
            registrarError("Sin conexión con la web (${e.javaClass.simpleName})")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun registrarError(mensaje: String) {
        Log.w(TAG, "Configuración operativa: $mensaje")
        prefs.edit().putString(KEY_ULTIMO_ERROR, mensaje).apply()
    }

    // ---- Avance de la ruta (paradas visitadas) ----

    fun paradasVisitadas(): Set<Long> {
        val ruta = actual().ruta ?: return emptySet()
        if (prefs.getLong(KEY_RUTA_VISITADAS, -1L) != ruta.id) return emptySet()
        return prefs.getStringSet(KEY_VISITADAS, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()
    }

    fun marcarParadaVisitada(paradaId: Long) {
        val ruta = actual().ruta ?: return
        val actuales = paradasVisitadas().map { it.toString() }.toMutableSet()
        actuales += paradaId.toString()
        prefs.edit()
            .putLong(KEY_RUTA_VISITADAS, ruta.id)
            .putStringSet(KEY_VISITADAS, actuales)
            .apply()
    }
}

package com.grupopecuario.gpscamiones.operacion

import android.content.Context
import android.location.Location
import android.util.Log
import com.grupopecuario.gpscamiones.GPSDatabaseHelper
import com.grupopecuario.gpscamiones.RegistroEvento
import com.grupopecuario.gpscamiones.config.ConfigManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Recibe cada lectura GPS del servicio, detecta paradas y entradas/salidas de
 * geocercas, y guarda los eventos en SQLite para subirlos cuando haya señal.
 * Se usa siempre desde el mismo hilo (el executor del servicio).
 */
class OperacionTracker private constructor(context: Context) {

    companion object {
        private const val PREFS = "gps_operacion_estado"
        private const val KEY_DETECTOR = "detector"
        private const val KEY_DENTRO = "geocercas_dentro"

        @Volatile
        private var instance: OperacionTracker? = null

        fun getInstance(context: Context): OperacionTracker =
            instance ?: synchronized(this) {
                instance ?: OperacionTracker(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val db = GPSDatabaseHelper.getInstance(appContext)
    private val config = ConfigManager.getInstance(appContext)
    private val operacion = OperacionRepository.getInstance(appContext)

    private val detector = DetectorParadas(leerEstadoDetector())
    private val monitor = MonitorGeocercas(
        prefs.getStringSet(KEY_DENTRO, emptySet()).orEmpty()
    )

    /** Momento en que empezó la parada actual, o null si el camión está en marcha. */
    @Volatile
    var detenidoDesdeMs: Long? = detector.detenidoDesdeMs
        private set

    @Volatile
    var geocercasActuales: Set<String> = monitor.idsDentro
        private set

    fun procesar(location: Location, sinConexion: Boolean) {
        val cfg = operacion.actual()
        val punto = PuntoGps(
            latitud = location.latitude,
            longitud = location.longitude,
            precisionMetros = location.accuracy.toDouble(),
            velocidadMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            fechaMs = location.time
        )
        val geocercas = cfg.geocercasEfectivas

        val eventos = monitor.procesar(punto, geocercas, cfg.umbrales) +
            detector.procesar(punto, geocercas, cfg.umbrales)

        for (evento in eventos) {
            try {
                db.guardarEvento(aRegistro(evento, cfg, sinConexion))
                if (evento is EventoOperativo.ParadaFinalizada && evento.tipo == TipoParada.POSIBLE_ENTREGA) {
                    evento.geocerca?.paradaRutaId?.let { operacion.marcarParadaVisitada(it) }
                }
                Log.i("GPSCamiones", "Evento operativo: $evento")
            } catch (e: Exception) {
                Log.e("GPSCamiones", "No se pudo guardar el evento operativo", e)
            }
        }

        detenidoDesdeMs = detector.detenidoDesdeMs
        geocercasActuales = monitor.idsDentro
        guardarEstado()
    }

    private fun aRegistro(
        evento: EventoOperativo,
        cfg: ConfiguracionOperativa,
        sinConexion: Boolean
    ): RegistroEvento {
        val datos = JSONObject()
        cfg.ruta?.let { datos.put("routeId", it.id) }
        var tipoParada: String? = null
        val tipo = when (evento) {
            is EventoOperativo.ParadaIniciada -> "STOP_START"
            is EventoOperativo.ParadaFinalizada -> {
                tipoParada = evento.tipo.name
                datos.put("startTimestamp", evento.inicioMs)
                datos.put("endTimestamp", evento.fechaMs)
                datos.put("durationSeconds", evento.duracionSegundos)
                evento.geocerca?.let { g ->
                    if (g.paradaRutaId != null) datos.put("routeStopId", g.paradaRutaId)
                    else datos.put("geofenceId", g.id)
                    datos.put("placeName", g.nombre)
                }
                "STOP_END"
            }
            is EventoOperativo.EntradaGeocerca -> {
                ponerGeocerca(datos, evento.geocerca)
                "GEOFENCE_ENTER"
            }
            is EventoOperativo.SalidaGeocerca -> {
                ponerGeocerca(datos, evento.geocerca)
                "GEOFENCE_EXIT"
            }
        }
        return RegistroEvento(
            id = UUID.randomUUID().toString(),
            tipo = tipo,
            fechaUtc = evento.fechaMs,
            latitud = evento.latitud,
            longitud = evento.longitud,
            tipoParada = tipoParada,
            datosJson = datos.toString(),
            providerUnitId = config.providerUnitId,
            placa = config.nombreDispositivo.trim().uppercase(),
            sinConexion = sinConexion
        )
    }

    private fun ponerGeocerca(datos: JSONObject, g: Geocerca) {
        if (g.paradaRutaId != null) datos.put("routeStopId", g.paradaRutaId)
        else datos.put("geofenceId", g.id)
        datos.put("geofenceType", g.tipo.name)
        datos.put("placeName", g.nombre)
    }

    // ---- Persistencia del estado para sobrevivir reinicios del servicio ----

    private fun guardarEstado() {
        val e = detector.estado
        val json = JSONArray().apply {
            put(e.candidatoInicioMs); put(e.candidatoLat); put(e.candidatoLon)
            put(e.detenido); put(e.inicioParadaMs); put(e.ultimoQuietoMs)
            put(e.centroLat); put(e.centroLon); put(e.puntosEnParada)
            put(e.ultimoLat); put(e.ultimoLon); put(e.ultimoMs)
        }
        prefs.edit()
            .putString(KEY_DETECTOR, json.toString())
            .putStringSet(KEY_DENTRO, monitor.idsDentro)
            .apply()
    }

    private fun leerEstadoDetector(): EstadoDetector {
        val texto = prefs.getString(KEY_DETECTOR, null) ?: return EstadoDetector()
        return try {
            val a = JSONArray(texto)
            EstadoDetector(
                candidatoInicioMs = a.getLong(0), candidatoLat = a.getDouble(1), candidatoLon = a.getDouble(2),
                detenido = a.getBoolean(3), inicioParadaMs = a.getLong(4), ultimoQuietoMs = a.getLong(5),
                centroLat = a.getDouble(6), centroLon = a.getDouble(7), puntosEnParada = a.getInt(8),
                ultimoLat = a.getDouble(9), ultimoLon = a.getDouble(10), ultimoMs = a.getLong(11)
            )
        } catch (e: Exception) {
            EstadoDetector()
        }
    }
}

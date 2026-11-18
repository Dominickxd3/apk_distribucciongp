package com.grupopecuario.gpscamiones.operacion

/** Eventos que el celular genera a partir del GPS y que se sincronizan con la web. */
sealed class EventoOperativo {
    abstract val fechaMs: Long
    abstract val latitud: Double
    abstract val longitud: Double

    data class ParadaIniciada(
        override val fechaMs: Long,
        override val latitud: Double,
        override val longitud: Double
    ) : EventoOperativo()

    data class ParadaFinalizada(
        val inicioMs: Long,
        override val fechaMs: Long,
        override val latitud: Double,
        override val longitud: Double,
        val tipo: TipoParada,
        val geocerca: Geocerca?
    ) : EventoOperativo() {
        val duracionSegundos: Long get() = (fechaMs - inicioMs) / 1000
    }

    data class EntradaGeocerca(
        val geocerca: Geocerca,
        override val fechaMs: Long,
        override val latitud: Double,
        override val longitud: Double
    ) : EventoOperativo()

    data class SalidaGeocerca(
        val geocerca: Geocerca,
        override val fechaMs: Long,
        override val latitud: Double,
        override val longitud: Double
    ) : EventoOperativo()
}

/**
 * Estado del detector. Se guarda en disco para no perder una parada en curso
 * si Android reinicia el servicio.
 */
data class EstadoDetector(
    val candidatoInicioMs: Long = 0L,
    val candidatoLat: Double = 0.0,
    val candidatoLon: Double = 0.0,
    val detenido: Boolean = false,
    val inicioParadaMs: Long = 0L,
    val ultimoQuietoMs: Long = 0L,
    val centroLat: Double = 0.0,
    val centroLon: Double = 0.0,
    val puntosEnParada: Int = 0,
    val ultimoLat: Double = 0.0,
    val ultimoLon: Double = 0.0,
    val ultimoMs: Long = 0L
)

/**
 * Detecta cuándo el camión se detiene y lo clasifica al reanudar la marcha:
 *
 *  - El camión debe quedar por debajo de [UmbralesParada.velocidadDetenidoMps]
 *    y dentro de [UmbralesParada.radioParadaMetros] durante al menos
 *    [UmbralesParada.minimoParadaSegundos] para que exista una parada.
 *  - La parada termina cuando se aleja más de [UmbralesParada.radioSalidaMetros]
 *    del centro, o cuando va claramente rápido y ya salió del radio de parada.
 *  - Al terminar se clasifica (ver [clasificar]): POSIBLE_ENTREGA si fue en un cliente o
 *    parada de la ruta, BASE si fue en el almacén, TRÁFICO si fue corta fuera de
 *    geocercas, NO_PROGRAMADA si fue larga fuera de geocercas.
 */
class DetectorParadas(estadoInicial: EstadoDetector = EstadoDetector()) {

    var estado: EstadoDetector = estadoInicial
        private set

    val detenidoDesdeMs: Long?
        get() = if (estado.detenido) estado.inicioParadaMs else null

    fun procesar(
        punto: PuntoGps,
        geocercas: List<Geocerca>,
        umbrales: UmbralesParada
    ): List<EventoOperativo> {
        if (punto.precisionMetros > umbrales.precisionMaximaMetros) return emptyList()
        val e = estado
        if (e.ultimoMs > 0 && punto.fechaMs <= e.ultimoMs) return emptyList() // lectura repetida o desordenada

        val velocidad = punto.velocidadMps ?: velocidadCalculada(e, punto)
        val eventos = mutableListOf<EventoOperativo>()
        var nuevo = e.copy(ultimoLat = punto.latitud, ultimoLon = punto.longitud, ultimoMs = punto.fechaMs)

        if (e.detenido) {
            val distancia = Geo.distanciaMetros(punto.latitud, punto.longitud, e.centroLat, e.centroLon)
            val arranco = distancia > umbrales.radioSalidaMetros ||
                (velocidad >= umbrales.velocidadDetenidoMps * 2 && distancia > umbrales.radioParadaMetros)

            if (arranco) {
                val fin = if (e.ultimoQuietoMs > 0) e.ultimoQuietoMs else punto.fechaMs
                val (tipo, geocerca) = clasificar(
                    duracionSegundos = (fin - e.inicioParadaMs) / 1000,
                    lat = e.centroLat,
                    lon = e.centroLon,
                    geocercas = geocercas,
                    umbrales = umbrales
                )
                eventos += EventoOperativo.ParadaFinalizada(
                    inicioMs = e.inicioParadaMs,
                    fechaMs = fin,
                    latitud = e.centroLat,
                    longitud = e.centroLon,
                    tipo = tipo,
                    geocerca = geocerca
                )
                nuevo = nuevo.copy(
                    detenido = false, inicioParadaMs = 0L, ultimoQuietoMs = 0L, puntosEnParada = 0,
                    candidatoInicioMs = 0L
                )
            } else if (velocidad < umbrales.velocidadDetenidoMps) {
                nuevo = if (distancia <= umbrales.radioParadaMetros) {
                    // Promedio móvil del centro para compensar la deriva del GPS
                    val n = e.puntosEnParada + 1
                    nuevo.copy(
                        centroLat = e.centroLat + (punto.latitud - e.centroLat) / n,
                        centroLon = e.centroLon + (punto.longitud - e.centroLon) / n,
                        puntosEnParada = n,
                        ultimoQuietoMs = punto.fechaMs
                    )
                } else {
                    nuevo.copy(ultimoQuietoMs = punto.fechaMs)
                }
            }
            // Si ya avanza pero aún no sale del radio, la parada no se alarga
        } else {
            val quieto = velocidad < umbrales.velocidadDetenidoMps
            if (!quieto) {
                nuevo = nuevo.copy(candidatoInicioMs = 0L)
            } else {
                val fueraDelCandidato = e.candidatoInicioMs > 0 &&
                    Geo.distanciaMetros(punto.latitud, punto.longitud, e.candidatoLat, e.candidatoLon) >
                    umbrales.radioParadaMetros
                if (e.candidatoInicioMs == 0L || fueraDelCandidato) {
                    nuevo = nuevo.copy(
                        candidatoInicioMs = punto.fechaMs,
                        candidatoLat = punto.latitud,
                        candidatoLon = punto.longitud
                    )
                } else if ((punto.fechaMs - e.candidatoInicioMs) / 1000 >= umbrales.minimoParadaSegundos) {
                    nuevo = nuevo.copy(
                        detenido = true,
                        inicioParadaMs = e.candidatoInicioMs,
                        ultimoQuietoMs = punto.fechaMs,
                        centroLat = (e.candidatoLat + punto.latitud) / 2,
                        centroLon = (e.candidatoLon + punto.longitud) / 2,
                        puntosEnParada = 2,
                        candidatoInicioMs = 0L
                    )
                    eventos += EventoOperativo.ParadaIniciada(
                        fechaMs = e.candidatoInicioMs,
                        latitud = nuevo.centroLat,
                        longitud = nuevo.centroLon
                    )
                }
            }
        }

        estado = nuevo
        return eventos
    }

    private fun velocidadCalculada(e: EstadoDetector, punto: PuntoGps): Double {
        if (e.ultimoMs <= 0) return 0.0
        val segundos = (punto.fechaMs - e.ultimoMs) / 1000.0
        if (segundos <= 0) return 0.0
        return Geo.distanciaMetros(e.ultimoLat, e.ultimoLon, punto.latitud, punto.longitud) / segundos
    }

    companion object {
        /** Margen de tolerancia del GPS al decidir si la parada fue dentro de una geocerca. */
        private const val MARGEN_GEOCERCA_M = 15.0

        fun clasificar(
            duracionSegundos: Long,
            lat: Double,
            lon: Double,
            geocercas: List<Geocerca>,
            umbrales: UmbralesParada
        ): Pair<TipoParada, Geocerca?> {
            val dentro = geocercas.filter { it.contiene(lat, lon, MARGEN_GEOCERCA_M) }
            val base = dentro.firstOrNull { it.tipo == TipoGeocerca.BASE }
            if (base != null) return TipoParada.BASE to base

            // Si hay varias paradas de ruta cerca, se elige la más cercana
            val entrega = dentro.filter { it.esPuntoDeEntrega }.minByOrNull { g ->
                when (val f = g.forma) {
                    is Forma.Circulo -> Geo.distanciaMetros(lat, lon, f.latitud, f.longitud)
                    is Forma.Poligono -> 0.0
                }
            }
            if (entrega != null && duracionSegundos >= umbrales.minimoEntregaSegundos) {
                return TipoParada.POSIBLE_ENTREGA to entrega
            }
            if (duracionSegundos < umbrales.maximoTraficoSegundos) {
                return TipoParada.TRAFICO to dentro.firstOrNull()
            }
            return TipoParada.NO_PROGRAMADA to dentro.firstOrNull()
        }
    }
}

/**
 * Detecta entradas y salidas de geocercas. Exige dos lecturas seguidas para
 * confirmar cada cambio y usa un margen al salir, para que el ruido del GPS en
 * el borde no genere entradas/salidas falsas.
 */
class MonitorGeocercas(
    dentroInicial: Set<String> = emptySet()
) {
    private val dentro = dentroInicial.toMutableSet()
    private val pendientes = mutableMapOf<String, Int>()

    val idsDentro: Set<String> get() = dentro.toSet()

    fun procesar(
        punto: PuntoGps,
        geocercas: List<Geocerca>,
        umbrales: UmbralesParada
    ): List<EventoOperativo> {
        if (punto.precisionMetros > umbrales.precisionMaximaMetros) return emptyList()
        val vigentes = geocercas.associateBy { it.id }
        dentro.retainAll(vigentes.keys) // geocercas que la web eliminó
        pendientes.keys.retainAll(vigentes.keys)

        val eventos = mutableListOf<EventoOperativo>()
        for (g in geocercas) {
            val estaba = g.id in dentro
            val cambia = if (estaba) {
                !g.contiene(punto.latitud, punto.longitud, MARGEN_SALIDA_M)
            } else {
                g.contiene(punto.latitud, punto.longitud)
            }
            if (!cambia) {
                pendientes.remove(g.id)
                continue
            }
            val confirmaciones = (pendientes[g.id] ?: 0) + 1
            if (confirmaciones < LECTURAS_PARA_CONFIRMAR) {
                pendientes[g.id] = confirmaciones
                continue
            }
            pendientes.remove(g.id)
            if (estaba) {
                dentro.remove(g.id)
                eventos += EventoOperativo.SalidaGeocerca(g, punto.fechaMs, punto.latitud, punto.longitud)
            } else {
                dentro.add(g.id)
                eventos += EventoOperativo.EntradaGeocerca(g, punto.fechaMs, punto.latitud, punto.longitud)
            }
        }
        return eventos
    }

    companion object {
        const val MARGEN_SALIDA_M = 20.0
        const val LECTURAS_PARA_CONFIRMAR = 2
    }
}

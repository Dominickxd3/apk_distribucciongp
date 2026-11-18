package com.grupopecuario.gpscamiones.operacion

/**
 * Modelos de la configuración operativa que la web administrable asigna a este
 * celular: qué camión es, qué ruta sigue, sus paradas y las geocercas vigentes.
 * Son Kotlin puro (sin Android) para poder probarlos con pruebas unitarias.
 */

data class VehiculoAsignado(
    val providerUnitId: Int,
    val placa: String,
    val nombre: String
)

/** Parada planificada de la ruta del día (cliente, bodega, punto de entrega). */
data class ParadaRuta(
    val id: Long,
    val orden: Int,
    val nombre: String,
    val latitud: Double,
    val longitud: Double,
    val radioMetros: Double
)

data class RutaAsignada(
    val id: Long,
    val nombre: String,
    val fecha: String,
    val paradas: List<ParadaRuta>
)

enum class TipoGeocerca {
    /** Almacén / base de la empresa: detenerse aquí es carga, descarga o fin de turno. */
    BASE,
    /** Cliente o punto de entrega: detenerse aquí cuenta como entrega. */
    CLIENTE,
    /** Zona en la que solo interesa saber cuándo entra y sale el camión. */
    ZONA,
    /** Zona prohibida o de riesgo: la web puede alertar al entrar. */
    RESTRINGIDA;

    companion object {
        fun desde(valor: String?): TipoGeocerca =
            entries.firstOrNull { it.name.equals(valor?.trim(), ignoreCase = true) } ?: ZONA
    }
}

sealed class Forma {
    data class Circulo(val latitud: Double, val longitud: Double, val radioMetros: Double) : Forma()
    /** Vértices como pares (latitud, longitud), en orden. */
    data class Poligono(val vertices: List<Pair<Double, Double>>) : Forma()
}

data class Geocerca(
    val id: String,
    val nombre: String,
    val tipo: TipoGeocerca,
    val forma: Forma,
    /** Si la geocerca proviene de una parada de la ruta, su id. */
    val paradaRutaId: Long? = null
) {
    fun contiene(latitud: Double, longitud: Double, margenMetros: Double = 0.0): Boolean =
        Geo.contiene(forma, latitud, longitud, margenMetros)

    val esPuntoDeEntrega: Boolean
        get() = tipo == TipoGeocerca.CLIENTE || paradaRutaId != null
}

/**
 * Umbrales para detectar y clasificar paradas. La web puede enviarlos para
 * ajustarlos sin publicar otra versión de la app.
 */
data class UmbralesParada(
    /** Por debajo de esta velocidad el camión se considera quieto (1.5 m/s ≈ 5 km/h). */
    val velocidadDetenidoMps: Double = 1.5,
    /** Radio en el que debe mantenerse para seguir "en la misma parada". */
    val radioParadaMetros: Double = 40.0,
    /** Distancia desde el centro de la parada que indica que ya arrancó. */
    val radioSalidaMetros: Double = 60.0,
    /** Tiempo mínimo quieto para registrar una parada (semáforos cortos no cuentan). */
    val minimoParadaSegundos: Long = 60,
    /** Una parada fuera de geocercas más corta que esto se atribuye al tráfico. */
    val maximoTraficoSegundos: Long = 180,
    /** Tiempo mínimo dentro de un punto de entrega para contarla como entrega. */
    val minimoEntregaSegundos: Long = 120,
    /** Lecturas con peor precisión se ignoran para paradas y geocercas. */
    val precisionMaximaMetros: Double = 50.0
)

data class ConfiguracionOperativa(
    val asignado: Boolean,
    val vehiculo: VehiculoAsignado?,
    val ruta: RutaAsignada?,
    val geocercas: List<Geocerca>,
    val umbrales: UmbralesParada,
    val version: String,
    val descargadaMs: Long
) {
    /** Geocercas de la web más un círculo por cada parada de la ruta. */
    val geocercasEfectivas: List<Geocerca> by lazy {
        geocercas + ruta?.paradas.orEmpty().map { parada ->
            Geocerca(
                id = "parada-${parada.id}",
                nombre = parada.nombre,
                tipo = TipoGeocerca.CLIENTE,
                forma = Forma.Circulo(parada.latitud, parada.longitud, parada.radioMetros),
                paradaRutaId = parada.id
            )
        }
    }

    companion object {
        val VACIA = ConfiguracionOperativa(
            asignado = false,
            vehiculo = null,
            ruta = null,
            geocercas = emptyList(),
            umbrales = UmbralesParada(),
            version = "",
            descargadaMs = 0L
        )
    }
}

/** Punto GPS simplificado que consumen el detector de paradas y el de geocercas. */
data class PuntoGps(
    val latitud: Double,
    val longitud: Double,
    val precisionMetros: Double,
    /** Velocidad reportada por el GPS; null si el proveedor no la entrega. */
    val velocidadMps: Double?,
    val fechaMs: Long
)

enum class TipoParada {
    /**
     * Quieto dentro de un cliente / parada de ruta el tiempo suficiente. Es solo
     * una inferencia por ubicación y tiempo: NO prueba que el pedido se entregó.
     * La confirmación debe venir del conductor, del cliente o del ERP.
     */
    POSIBLE_ENTREGA,
    /** Detención corta fuera de geocercas: semáforo, congestión. */
    TRAFICO,
    /** Detención larga fuera de geocercas: almuerzo, avería, entrega no planificada. */
    NO_PROGRAMADA,
    /** Detención en almacén / base. */
    BASE
}

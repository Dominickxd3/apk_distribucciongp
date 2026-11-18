package com.grupopecuario.gpscamiones.operacion

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    private const val RADIO_TIERRA_M = 6_371_000.0

    /** Distancia en metros entre dos coordenadas (fórmula de Haversine). */
    fun distanciaMetros(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * RADIO_TIERRA_M * asin(min(1.0, sqrt(a)))
    }

    /**
     * Indica si el punto está dentro de la forma. Con [margenMetros] positivo la
     * forma se "agranda" (útil para no marcar salida por ruido del GPS).
     */
    fun contiene(forma: Forma, lat: Double, lon: Double, margenMetros: Double = 0.0): Boolean =
        when (forma) {
            is Forma.Circulo ->
                distanciaMetros(lat, lon, forma.latitud, forma.longitud) <= forma.radioMetros + margenMetros
            is Forma.Poligono ->
                dentroDePoligono(forma.vertices, lat, lon) ||
                    (margenMetros > 0 && distanciaAlBorde(forma.vertices, lat, lon) <= margenMetros)
        }

    /** Ray casting sobre lat/lon; preciso para geocercas de pocos kilómetros. */
    fun dentroDePoligono(vertices: List<Pair<Double, Double>>, lat: Double, lon: Double): Boolean {
        if (vertices.size < 3) return false
        var dentro = false
        var j = vertices.size - 1
        for (i in vertices.indices) {
            val (latI, lonI) = vertices[i]
            val (latJ, lonJ) = vertices[j]
            if ((latI > lat) != (latJ > lat) &&
                lon < (lonJ - lonI) * (lat - latI) / (latJ - latI) + lonI
            ) {
                dentro = !dentro
            }
            j = i
        }
        return dentro
    }

    /** Distancia aproximada (m) del punto al borde más cercano del polígono. */
    fun distanciaAlBorde(vertices: List<Pair<Double, Double>>, lat: Double, lon: Double): Double {
        if (vertices.isEmpty()) return Double.MAX_VALUE
        // Proyección equirectangular local alrededor del punto
        val mPorGradoLat = 111_320.0
        val mPorGradoLon = 111_320.0 * cos(Math.toRadians(lat))
        fun x(p: Pair<Double, Double>) = (p.second - lon) * mPorGradoLon
        fun y(p: Pair<Double, Double>) = (p.first - lat) * mPorGradoLat

        var mejor = Double.MAX_VALUE
        for (i in vertices.indices) {
            val a = vertices[i]
            val b = vertices[(i + 1) % vertices.size]
            val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
            val dx = bx - ax; val dy = by - ay
            val largo2 = dx * dx + dy * dy
            val t = if (largo2 == 0.0) 0.0 else ((-ax) * dx + (-ay) * dy) / largo2
            val tc = t.coerceIn(0.0, 1.0)
            val px = ax + tc * dx; val py = ay + tc * dy
            mejor = min(mejor, sqrt(px * px + py * py))
        }
        return mejor
    }
}

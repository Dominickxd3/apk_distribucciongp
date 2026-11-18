package com.grupopecuario.gpscamiones.operacion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorParadasTest {

    private val umbrales = UmbralesParada()
    private val baseLat = -12.046374
    private val baseLon = -77.042793

    /** Desplaza la latitud ~[metros] hacia el norte. */
    private fun norte(metros: Double) = baseLat + metros / 111_320.0

    private fun punto(seg: Long, lat: Double = baseLat, vel: Double? = 0.0, precision: Double = 5.0) =
        PuntoGps(lat, baseLon, precision, vel, 1_700_000_000_000L + seg * 1000)

    private val cliente = Geocerca(
        id = "parada-7", nombre = "Bodega Don Lucho", tipo = TipoGeocerca.CLIENTE,
        forma = Forma.Circulo(baseLat, baseLon, 60.0), paradaRutaId = 7
    )
    private val almacen = Geocerca(
        id = "1", nombre = "Almacén central", tipo = TipoGeocerca.BASE,
        forma = Forma.Circulo(baseLat, baseLon, 150.0)
    )

    /** Simula: quieto [segundosQuieto] segundos y luego arranca y se aleja. */
    private fun simular(segundosQuieto: Long, geocercas: List<Geocerca>): List<EventoOperativo> {
        val d = DetectorParadas()
        val eventos = mutableListOf<EventoOperativo>()
        var t = 0L
        while (t <= segundosQuieto) {
            eventos += d.procesar(punto(t), geocercas, umbrales); t += 10
        }
        for (i in 1..5) {
            eventos += d.procesar(punto(t, lat = norte(30.0 * i), vel = 8.0), geocercas, umbrales); t += 10
        }
        return eventos
    }

    @Test
    fun semaforoCortoNoGeneraParada() {
        val eventos = simular(40, emptyList())
        assertTrue(eventos.isEmpty())
    }

    @Test
    fun detencionDeDosMinutosFueraDeGeocercaEsTrafico() {
        val fin = simular(120, emptyList()).filterIsInstance<EventoOperativo.ParadaFinalizada>().single()
        assertEquals(TipoParada.TRAFICO, fin.tipo)
        assertEquals(120, fin.duracionSegundos)
    }

    @Test
    fun detencionLargaFueraDeGeocercaEsNoProgramada() {
        val fin = simular(600, emptyList()).filterIsInstance<EventoOperativo.ParadaFinalizada>().single()
        assertEquals(TipoParada.NO_PROGRAMADA, fin.tipo)
    }

    @Test
    fun detencionEnClienteEsEntregaYApuntaALaParadaDeRuta() {
        val eventos = simular(300, listOf(cliente))
        assertEquals(1, eventos.filterIsInstance<EventoOperativo.ParadaIniciada>().size)
        val fin = eventos.filterIsInstance<EventoOperativo.ParadaFinalizada>().single()
        assertEquals(TipoParada.POSIBLE_ENTREGA, fin.tipo)
        assertEquals(7L, fin.geocerca?.paradaRutaId)
    }

    @Test
    fun detencionCortaEnClienteSigueSiendoTrafico() {
        val fin = simular(90, listOf(cliente)).filterIsInstance<EventoOperativo.ParadaFinalizada>().single()
        assertEquals(TipoParada.TRAFICO, fin.tipo)
    }

    @Test
    fun detencionEnAlmacenEsBase() {
        val fin = simular(900, listOf(almacen, cliente)).filterIsInstance<EventoOperativo.ParadaFinalizada>().single()
        assertEquals(TipoParada.BASE, fin.tipo)
    }

    @Test
    fun derivaDelGpsEstandoQuietoNoTerminaLaParada() {
        val d = DetectorParadas()
        for (t in 0L..120L step 10) d.procesar(punto(t), emptyList(), umbrales)
        // Deriva de ±25 m con velocidad fantasma baja
        val eventos = d.procesar(punto(130, lat = norte(25.0), vel = 0.8), emptyList(), umbrales)
        assertTrue(eventos.isEmpty())
        assertTrue(d.detenidoDesdeMs != null)
    }

    @Test
    fun lecturasImprecisasSeIgnoran() {
        val d = DetectorParadas()
        for (t in 0L..300L step 10) d.procesar(punto(t, precision = 120.0), emptyList(), umbrales)
        assertNull(d.detenidoDesdeMs)
    }

    @Test
    fun estadoRestauradoContinuaLaParada() {
        val d1 = DetectorParadas()
        for (t in 0L..200L step 10) d1.procesar(punto(t), emptyList(), umbrales)
        val d2 = DetectorParadas(d1.estado) // como si el servicio se hubiera reiniciado
        var fin: EventoOperativo.ParadaFinalizada? = null
        for (i in 1..5) {
            fin = fin ?: d2.procesar(punto(200 + i * 10L, lat = norte(45.0 * i), vel = 9.0), emptyList(), umbrales)
                .filterIsInstance<EventoOperativo.ParadaFinalizada>().firstOrNull()
        }
        assertEquals(200, fin!!.duracionSegundos)
    }

    @Test
    fun monitorConfirmaEntradaYSalidaConDosLecturas() {
        val m = MonitorGeocercas()
        val zona = listOf(cliente)
        assertTrue(m.procesar(punto(0), zona, umbrales).isEmpty())
        val entrada = m.procesar(punto(10), zona, umbrales)
        assertTrue(entrada.single() is EventoOperativo.EntradaGeocerca)

        // A 70 m (dentro del margen de salida de 60+20 m) sigue adentro
        assertTrue(m.procesar(punto(20, lat = norte(70.0)), zona, umbrales).isEmpty())
        assertTrue(m.procesar(punto(30, lat = norte(70.0)), zona, umbrales).isEmpty())

        assertTrue(m.procesar(punto(40, lat = norte(200.0)), zona, umbrales).isEmpty())
        val salida = m.procesar(punto(50, lat = norte(200.0)), zona, umbrales)
        assertTrue(salida.single() is EventoOperativo.SalidaGeocerca)
        assertFalse("parada-7" in m.idsDentro)
    }

    @Test
    fun poligonoContienePuntoInterior() {
        val cuadrado = listOf(
            -12.0 to -77.0, -12.0 to -77.01, -12.01 to -77.01, -12.01 to -77.0
        )
        assertTrue(Geo.dentroDePoligono(cuadrado, -12.005, -77.005))
        assertFalse(Geo.dentroDePoligono(cuadrado, -12.02, -77.005))
        // Justo afuera pero dentro del margen
        assertTrue(Geo.contiene(Forma.Poligono(cuadrado), -12.0101, -77.005, margenMetros = 20.0))
    }
}

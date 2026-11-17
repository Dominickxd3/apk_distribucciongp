package com.grupopecuario.gpscamiones

import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class GPSPhase1UnitTest {

    @Test
    fun testConstantesEstadosOperativos() {
        assertEquals("DETENIDO", GPSLocationService.ESTADO_DETENIDO)
        assertEquals("INICIANDO", GPSLocationService.ESTADO_INICIANDO)
        assertEquals("INICIADO", GPSLocationService.ESTADO_INICIADO)
        assertEquals("ESPERANDO_UBICACION", GPSLocationService.ESTADO_ESPERANDO_UBICACION)
        assertEquals("UBICACION_RECIENTE", GPSLocationService.ESTADO_UBICACION_RECIENTE)
        assertEquals("PERMISOS_INSUFICIENTES", GPSLocationService.ESTADO_PERMISOS_INSUFICIENTES)
        assertEquals("ERROR_RECUPERACION", GPSLocationService.ESTADO_ERROR_RECUPERACION)
        assertEquals("ERROR_PROVEEDOR", GPSLocationService.ESTADO_ERROR_PROVEEDOR)
    }

    @Test
    fun testConstantesAccionesIntent() {
        assertEquals("com.grupopecuario.gpscamiones.START", GPSLocationService.ACTION_START)
        assertEquals("com.grupopecuario.gpscamiones.STOP", GPSLocationService.ACTION_STOP)
        assertEquals("iniciado_por_boot", GPSLocationService.EXTRA_INICIADO_POR_BOOT)
    }

    @Test
    fun testModeloUltimaUbicacionYConversionVelocidad() {
        val ubicacion = UltimaUbicacion(
            latitud = -12.046374,
            longitud = -77.042793,
            precision = 4.5f,
            fechaGPS = 1700000000000L,
            fechaRegistro = 1700000001000L,
            velocidad = 20.0f, // 20 m/s = 72 km/h
            esSimulada = false,
            sincronizado = false
        )

        assertEquals(-12.046374, ubicacion.latitud, 0.000001)
        assertEquals(-77.042793, ubicacion.longitud, 0.000001)
        assertEquals(4.5f, ubicacion.precision, 0.01f)
        assertNotNull(ubicacion.velocidad)
        val velocidadKmH = ubicacion.velocidad!! * 3.6f
        assertEquals(72.0f, velocidadKmH, 0.01f)
        assertFalse(ubicacion.esSimulada)
        assertFalse(ubicacion.sincronizado)
    }

    @Test
    fun testFormatoFechaZonaHorariaLima() {
        val timestamp = 1700000000000L // UTC: 2023-11-14 22:13:20 UTC -> Lima (UTC-5): 2023-11-14 17:13:20
        val formato = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale("es", "PE")).apply {
            timeZone = TimeZone.getTimeZone("America/Lima")
        }

        val fechaFormateada = formato.format(Date(timestamp))
        assertEquals("14/11/2023 17:13:20", fechaFormateada)
    }
}

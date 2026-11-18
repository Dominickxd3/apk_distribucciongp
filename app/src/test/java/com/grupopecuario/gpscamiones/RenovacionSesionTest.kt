package com.grupopecuario.gpscamiones

import com.grupopecuario.gpscamiones.auth.AuthRepository
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenovacionSesionTest {
    private val hora = 60 * 60 * 1000L
    private val emitido = 1_000_000_000_000L
    private val vence = emitido + 720 * hora // 30 días

    @Test
    fun noRenuevaAntesDeLaMitadDeLaVigencia() {
        assertFalse(AuthRepository.necesitaRenovar(emitido, vence, emitido + 100 * hora))
    }

    @Test
    fun renuevaPasadaLaMitad() {
        assertTrue(AuthRepository.necesitaRenovar(emitido, vence, emitido + 361 * hora))
    }

    @Test
    fun renuevaSiYaVencio() {
        // Celular apagado varios días: al encender debe renovar solo
        assertTrue(AuthRepository.necesitaRenovar(emitido, vence, vence + 5 * 24 * hora))
    }

    @Test
    fun tokenDeDoceHorasSeRenuevaALasSeisHoras() {
        val venceCorto = emitido + 12 * hora
        assertFalse(AuthRepository.necesitaRenovar(emitido, venceCorto, emitido + 5 * hora))
        assertTrue(AuthRepository.necesitaRenovar(emitido, venceCorto, emitido + 6 * hora))
    }

    @Test
    fun sinFechaDeVencimientoNoRenueva() {
        assertFalse(AuthRepository.necesitaRenovar(emitido, 0L, emitido + 1000 * hora))
    }
}

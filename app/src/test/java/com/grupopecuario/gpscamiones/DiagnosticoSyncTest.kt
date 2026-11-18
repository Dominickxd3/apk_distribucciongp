package com.grupopecuario.gpscamiones

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticoSyncTest {

    @Test
    fun clasificaCodigosHttp() {
        assertEquals(DiagnosticoSync.Estado.OK, DiagnosticoSync.clasificarHttp(200))
        assertEquals(DiagnosticoSync.Estado.SESION_RECHAZADA, DiagnosticoSync.clasificarHttp(401))
        assertEquals(DiagnosticoSync.Estado.SESION_RECHAZADA, DiagnosticoSync.clasificarHttp(403))
        assertEquals(DiagnosticoSync.Estado.DATOS_RECHAZADOS, DiagnosticoSync.clasificarHttp(400))
        assertEquals(DiagnosticoSync.Estado.ERROR_HTTP, DiagnosticoSync.clasificarHttp(500))
        assertEquals(DiagnosticoSync.Estado.ERROR_HTTP, DiagnosticoSync.clasificarHttp(502))
        assertEquals(DiagnosticoSync.Estado.ERROR_HTTP, DiagnosticoSync.clasificarHttp(503))
    }

    @Test
    fun soloOkNoBloqueaElEnvio() {
        DiagnosticoSync.Estado.entries.forEach { estado ->
            assertEquals(estado != DiagnosticoSync.Estado.OK, estado.bloqueaEnvio)
        }
    }

    @Test
    fun huellaDeTokenEsEstableYNoExponeElToken() {
        val token = "eyJzdWIiOiJhZG1pbiJ9.firma"
        val huella = DiagnosticoSync.huellaToken(token)
        assertEquals(64, huella.length)
        assertEquals(huella, DiagnosticoSync.huellaToken(token))
        assertNotEquals(huella, DiagnosticoSync.huellaToken("$token-otro"))
        assertFalse(huella.contains("firma"))
        assertTrue(huella.all { it in '0'..'9' || it in 'a'..'f' })
    }
}

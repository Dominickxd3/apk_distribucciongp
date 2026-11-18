package com.grupopecuario.gpscamiones

import android.content.Context
import android.util.Log
import java.security.MessageDigest

/**
 * Último resultado de la comunicación con la web, para que el conductor y el
 * administrador vean por qué las posiciones no se están enviando.
 */
object DiagnosticoSync {

    enum class Estado(val bloqueaEnvio: Boolean) {
        OK(false),
        /** No hay sesión guardada en el celular. */
        SIN_SESION(true),
        /** El token venció (AUTH_TOKEN_TTL_HOURS en la web). */
        SESION_EXPIRADA(true),
        /** La web respondió 401/403 a este token. */
        SESION_RECHAZADA(true),
        SIN_INTERNET(true),
        /** Sin respuesta del servidor: DNS, tiempo agotado, certificado TLS. */
        ERROR_RED(true),
        /** La web rechazó el contenido del lote (HTTP 400/422). */
        DATOS_RECHAZADOS(true),
        /** Otro error del servidor (5xx, 404...). */
        ERROR_HTTP(true),
        /** HTTP 2xx pero sin confirmar ninguno de los ids enviados. */
        SIN_CONFIRMACION(true),
        URL_NO_PERMITIDA(true);
    }

    data class Resultado(
        val estado: Estado,
        val detalle: String,
        val fechaMs: Long,
        val codigoHttp: Int
    )

    private const val PREFS = "gps_sync_diagnostico"
    private const val KEY_ESTADO = "estado"
    private const val KEY_DETALLE = "detalle"
    private const val KEY_FECHA = "fecha_ms"
    private const val KEY_HTTP = "codigo_http"
    private const val KEY_ULTIMO_OK = "ultimo_ok_ms"
    private const val KEY_TOKEN_RECHAZADO = "token_rechazado_sha256"

    /** Estado según el código HTTP de la web. Función pura para pruebas. */
    fun clasificarHttp(codigo: Int): Estado = when (codigo) {
        in 200..299 -> Estado.OK
        401, 403 -> Estado.SESION_RECHAZADA
        400, 413, 422 -> Estado.DATOS_RECHAZADOS
        else -> Estado.ERROR_HTTP
    }

    fun huellaToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun registrar(context: Context, estado: Estado, detalle: String, codigoHttp: Int = 0) {
        val ahora = System.currentTimeMillis()
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ESTADO, estado.name)
            .putString(KEY_DETALLE, detalle.take(300))
            .putLong(KEY_FECHA, ahora)
            .putInt(KEY_HTTP, codigoHttp)
        if (estado == Estado.OK) editor.putLong(KEY_ULTIMO_OK, ahora)
        editor.apply()
        val mensaje = "Sincronización [$estado${if (codigoHttp > 0) " HTTP $codigoHttp" else ""}]: $detalle"
        if (estado == Estado.OK) Log.i("GPSCamiones", mensaje) else Log.w("GPSCamiones", mensaje)
    }

    fun leer(context: Context): Resultado? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nombre = prefs.getString(KEY_ESTADO, null) ?: return null
        val estado = Estado.entries.firstOrNull { it.name == nombre } ?: return null
        return Resultado(
            estado = estado,
            detalle = prefs.getString(KEY_DETALLE, "") ?: "",
            fechaMs = prefs.getLong(KEY_FECHA, 0L),
            codigoHttp = prefs.getInt(KEY_HTTP, 0)
        )
    }

    fun ultimoOkMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_ULTIMO_OK, 0L)

    /**
     * Guarda la huella del token que la web rechazó para no reintentar con él en
     * cada lectura GPS. Al iniciar sesión de nuevo el token cambia y se reintenta.
     * La sesión NO se borra: así el usuario ve el motivo en vez de un cierre silencioso.
     */
    fun marcarTokenRechazado(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TOKEN_RECHAZADO, huellaToken(token)).apply()
    }

    fun tokenFueRechazado(context: Context, token: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN_RECHAZADO, null) == huellaToken(token)

    /** Texto corto para la notificación y la pantalla de Inicio. */
    fun mensajeCorto(r: Resultado): String = when (r.estado) {
        Estado.OK -> "Envío a la web al día"
        Estado.SIN_SESION -> "Sin sesión: inicia sesión para enviar posiciones"
        Estado.SESION_EXPIRADA -> "Sesión vencida: vuelve a iniciar sesión"
        Estado.SESION_RECHAZADA -> "La web rechazó la sesión: vuelve a iniciar sesión"
        Estado.SIN_INTERNET -> "Sin internet: se enviará al recuperar señal"
        Estado.ERROR_RED -> "No se pudo conectar con la web"
        Estado.DATOS_RECHAZADOS -> "La web rechazó los datos enviados"
        Estado.ERROR_HTTP -> "Error del servidor web"
        Estado.SIN_CONFIRMACION -> "La web no confirmó las posiciones"
        Estado.URL_NO_PERMITIDA -> "URL del servidor no permitida"
    }
}

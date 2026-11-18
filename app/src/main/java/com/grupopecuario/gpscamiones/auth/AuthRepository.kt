package com.grupopecuario.gpscamiones.auth

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.grupopecuario.gpscamiones.config.ConfigManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class AuthRepository(private val context: Context) {

    private val authManager = AuthManager.getInstance(context)
    private val config = ConfigManager.getInstance(context)

    fun login(
        usuario: String,
        clave: String,
        onResult: (AuthResult) -> Unit
    ) {
        val trimmedUser = usuario.trim()
        val trimmedPass = clave.trim()

        if (trimmedUser.isBlank() || trimmedPass.isBlank()) {
            onResult(AuthResult.Failure("Por favor ingresa usuario y contraseña."))
            return
        }

        val url = loginUrl()
        if (url == null) {
            val resultado = loginLocal(trimmedUser, trimmedPass)
            onResult(resultado)
            return
        }

        Thread {
            val result = requestLogin(trimmedUser, trimmedPass, url)
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    /**
     * Validación local para cuando no hay URL de servidor configurada.
     * Permite acceso de demostración con credenciales conocidas; en producción
     * el administrador debe configurar la URL del servidor real.
     */
    private fun loginLocal(usuario: String, clave: String): AuthResult {
        val credencialesValidas = when {
            usuario == "admin" && clave == "admin123" -> Role.ADMIN
            usuario == "supervisor" && clave == "super123" -> Role.ADMIN
            else -> null
        }

        if (credencialesValidas == null) {
            return AuthResult.Failure(
                "Usuario o contraseña incorrectos.\n\n" +
                "Sin servidor configurado solo se permite acceso de demostración. " +
                "Configura la URL del servidor en Administración → Configuración."
            )
        }

        val session = UserSession(
            userId = usuario,
            username = usuario,
            email = "",
            displayName = if (usuario == "admin") "Administrador" else "Supervisor",
            role = credencialesValidas,
            token = "",
            loginTimestamp = System.currentTimeMillis(),
            isLocalSession = true,
            expiresAtMs = System.currentTimeMillis() + 8 * 60 * 60 * 1000L // 8 horas
        )
        authManager.saveSession(session)
        return AuthResult.Success(session)
    }

    private fun requestLogin(usuario: String, clave: String, urlStr: String): AuthResult {
        val url = URL(urlStr)
        val deviceId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: config.deviceId

        val body = JSONObject().apply {
            put("username", usuario)
            put("password", clave)
            put("deviceId", deviceId)
        }.toString()

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }

        return try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                AuthResult.Failure(mensajeDeError(response, code))
            } else {
                crearSesion(JSONObject(response)).also { resultado ->
                    if (resultado is AuthResult.Success) {
                        // Con el token nuevo: registrar el celular y subir lo acumulado
                        Thread {
                            try {
                                com.grupopecuario.gpscamiones.operacion.OperacionRepository.getInstance(context).refrescar()
                                com.grupopecuario.gpscamiones.GPSSyncClient(context).vaciarPendientes()
                            } catch (e: Exception) {
                                android.util.Log.w("GPSCamiones", "Sincronización tras el login falló", e)
                            }
                        }.start()
                    }
                }
            }
        } catch (error: Exception) {
            AuthResult.Failure(
                "No se pudo conectar con el servidor. Revisa tu conexión e inténtalo de nuevo."
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun crearSesion(json: JSONObject): AuthResult {
        val user = json.getJSONObject("user")
        val username = user.optString("username").trim()
        val fullName = user.optString("fullName").trim().ifBlank { username }

        val session = UserSession(
            userId = username,
            username = username,
            email = user.optString("email"),
            displayName = fullName,
            role = if (user.optBoolean("isAdmin")) Role.ADMIN else Role.CONDUCTOR,
            token = json.getString("token"),
            loginTimestamp = System.currentTimeMillis(),
            isLocalSession = false,
            expiresAtMs = fechaExpiracionMs(json.optString("expiresAt"))
        )

        authManager.saveSession(session)
        return AuthResult.Success(session)
    }

    private fun fechaExpiracionMs(valor: String): Long {
        if (valor.isBlank()) return 0L

        return try {
            java.time.Instant.parse(valor).toEpochMilli()
        } catch (error: Exception) {
            0L
        }
    }

    private fun mensajeDeError(response: String, code: Int): String {
        val message = try {
            JSONObject(response).optString("message")
        } catch (error: Exception) {
            ""
        }

        return when {
            message.isNotBlank() -> message
            code == 401 -> "Usuario o contraseña incorrectos."
            code == 429 -> "Demasiados intentos. Espera un momento antes de volver a intentar."
            else -> "No se pudo iniciar sesión (HTTP $code)."
        }
    }

    /**
     * Construye la URL de login.
     * Prioridad:
     *   1. URL configurada manualmente en Administración → Configuración
     *   2. Servidor corporativo por defecto (distribucionrutas.duckdns.org)
     * Devuelve null solo si no hay ninguna URL disponible.
     */
    private fun loginUrl(): String? {
        val configurado = config.servidorSyncUrl.trim()
        val base = when {
            configurado.isNotBlank() -> configurado
            else -> DEFAULT_BASE_URL
        }

        val raiz = if (base.contains("/api/")) {
            base.substringBefore("/api/")
        } else {
            base.trimEnd('/')
        }
        return "$raiz/api/mobile/auth/login"
    }

    companion object {
        /** Servidor corporativo de producción */
        private const val DEFAULT_BASE_URL = "https://distribucionrutas.duckdns.org"
    }

    fun logout() {
        authManager.logout()
    }
}

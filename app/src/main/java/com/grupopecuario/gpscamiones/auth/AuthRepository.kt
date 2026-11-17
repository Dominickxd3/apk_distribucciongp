package com.grupopecuario.gpscamiones.auth

import android.content.Context
import java.util.UUID

class AuthRepository(private val context: Context) {

    private val authManager = AuthManager.getInstance(context)

    // Configuración de endpoint del servidor empresarial (pendiente de proveer por Grupo Pecuario)
    var backendAuthUrl: String? = null

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

        // Si existe un servidor configurado, aquí se ejecutará la petición HTTP hacia el API empresarial
        if (!backendAuthUrl.isNullOrBlank()) {
            // Nota arquitectural: listo para Retrofit / OkHttp cuando el servidor esté desplegado
            onResult(
                AuthResult.BackendUnavailable(
                    "Servidor configurado en '$backendAuthUrl', pero el servicio de autenticación remoto aún no responde."
                )
            )
            return
        }

        // Modo de Acceso Administrativo Local / Técnico Autorizado
        // Permite verificar los controles de seguridad y autorización en campo sin inventar endpoints
        if (trimmedUser.equals("admin", ignoreCase = true) && trimmedPass == "admin123") {
            val session = UserSession(
                userId = "admin-local-01",
                username = trimmedUser,
                email = "admin@grupopecuario.com",
                displayName = "Administrador Local",
                role = Role.ADMIN,
                token = "local_token_" + UUID.randomUUID().toString(),
                loginTimestamp = System.currentTimeMillis(),
                isLocalSession = true
            )
            authManager.saveSession(session)
            onResult(AuthResult.Success(session))
        } else if (trimmedUser.equals("supervisor", ignoreCase = true) && trimmedPass == "super123") {
            val session = UserSession(
                userId = "sup-local-02",
                username = trimmedUser,
                email = "supervisor@grupopecuario.com",
                displayName = "Supervisor de Telemetría",
                role = Role.SUPERVISOR,
                token = "local_token_" + UUID.randomUUID().toString(),
                loginTimestamp = System.currentTimeMillis(),
                isLocalSession = true
            )
            authManager.saveSession(session)
            onResult(AuthResult.Success(session))
        } else {
            onResult(
                AuthResult.Failure(
                    "Credenciales no válidas. El backend corporativo remoto no está conectado. Para pruebas locales autorizadas use admin / supervisor."
                )
            )
        }
    }

    fun logout() {
        authManager.logout()
    }
}


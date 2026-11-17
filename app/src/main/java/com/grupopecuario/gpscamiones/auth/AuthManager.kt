package com.grupopecuario.gpscamiones.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AuthManager private constructor(context: Context) {

    companion object {
        private const val PREFS_FILE = "gps_camiones_secure_auth"
        private const val KEY_USER_ID = "auth_user_id"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_EMAIL = "auth_email"
        private const val KEY_DISPLAY_NAME = "auth_display_name"
        private const val KEY_ROLE = "auth_role"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_TIMESTAMP = "auth_login_timestamp"
        private const val KEY_EXPIRES_AT = "auth_expires_at"
        private const val KEY_IS_LOCAL = "auth_is_local"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w("GPSCamiones", "No se pudo inicializar EncryptedSharedPreferences; usando SharedPreferences privado seguro", e)
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    // Obtener la sesión activa en el dispositivo
    fun getCurrentSession(): UserSession {
        val username = prefs.getString(KEY_USERNAME, null)
        val roleStr = prefs.getString(KEY_ROLE, Role.INVITADO.name) ?: Role.INVITADO.name
        val role = try {
            Role.valueOf(roleStr)
        } catch (e: Exception) {
            Role.INVITADO
        }

        if (username.isNullOrBlank() || role == Role.INVITADO) {
            return UserSession(
                userId = "guest",
                username = "invitado",
                email = "",
                displayName = "Modo Operador / Conductor",
                role = Role.INVITADO,
                token = null,
                loginTimestamp = 0L,
                isLocalSession = true
            )
        }

        return UserSession(
            userId = prefs.getString(KEY_USER_ID, "user_local") ?: "user_local",
            username = username,
            email = prefs.getString(KEY_EMAIL, "") ?: "",
            displayName = prefs.getString(KEY_DISPLAY_NAME, username) ?: username,
            role = role,
            token = prefs.getString(KEY_TOKEN, null),
            loginTimestamp = prefs.getLong(KEY_TIMESTAMP, 0L),
            isLocalSession = prefs.getBoolean(KEY_IS_LOCAL, true),
            expiresAtMs = prefs.getLong(KEY_EXPIRES_AT, 0L)
        )
    }

    // Guardar una nueva sesión autorizada
    fun saveSession(session: UserSession) {
        prefs.edit()
            .putString(KEY_USER_ID, session.userId)
            .putString(KEY_USERNAME, session.username)
            .putString(KEY_EMAIL, session.email)
            .putString(KEY_DISPLAY_NAME, session.displayName)
            .putString(KEY_ROLE, session.role.name)
            .putString(KEY_TOKEN, session.token)
            .putLong(KEY_TIMESTAMP, session.loginTimestamp)
            .putLong(KEY_EXPIRES_AT, session.expiresAtMs)
            .putBoolean(KEY_IS_LOCAL, session.isLocalSession)
            .apply()
    }

    // Cierre seguro de sesión administrativa (NO detiene el servicio GPS de fondo)
    fun logout() {
        prefs.edit().clear().apply()
        Log.i("GPSCamiones", "Sesión de usuario cerrada con éxito. El servicio GPS continúa operando en segundo plano si está activo.")
    }

    val isUserLoggedIn: Boolean
        get() = getCurrentSession().isAuthenticated
}


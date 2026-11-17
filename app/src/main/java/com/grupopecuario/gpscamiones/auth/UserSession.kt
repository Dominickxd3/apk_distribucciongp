package com.grupopecuario.gpscamiones.auth

data class UserSession(
    val userId: String,
    val username: String,
    val email: String,
    val displayName: String,
    val role: Role,
    val token: String?,
    val loginTimestamp: Long,
    val isLocalSession: Boolean = false,
    /** Momento de expiración del token emitido por el API (0 = sin dato). */
    val expiresAtMs: Long = 0L
) {
    val isAuthenticated: Boolean
        get() = role != Role.INVITADO &&
            !token.isNullOrBlank() &&
            (expiresAtMs <= 0L || System.currentTimeMillis() < expiresAtMs)
}

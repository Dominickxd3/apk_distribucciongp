package com.grupopecuario.gpscamiones.auth

data class UserSession(
    val userId: String,
    val username: String,
    val email: String,
    val displayName: String,
    val role: Role,
    val token: String?,
    val loginTimestamp: Long,
    val isLocalSession: Boolean = false
) {
    val isAuthenticated: Boolean
        get() = role != Role.INVITADO && token != null
}


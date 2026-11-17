package com.grupopecuario.gpscamiones.auth

sealed class AuthResult {
    data class Success(val session: UserSession) : AuthResult()
    data class Failure(val errorMessage: String) : AuthResult()
    data class BackendUnavailable(val detalle: String) : AuthResult()
    object Loading : AuthResult()
}


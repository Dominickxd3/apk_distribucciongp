package com.grupopecuario.gpscamiones.auth

enum class Role(val displayName: String, val level: Int) {
    ADMIN("Administrador Corporativo", 100),
    SUPERVISOR("Supervisor de Telemetría", 50),
    CONDUCTOR("Conductor / Auxiliar de Ruta", 20),
    INVITADO("Modo Conductor (Solo Lectura)", 0);

    // Permisos granulares de seguridad
    fun canControlTracking(): Boolean = level >= 50
    fun canEditVehicleConfig(): Boolean = level >= 100
    fun canViewSystemDiagnostics(): Boolean = level >= 20
    fun canForceDatabaseActions(): Boolean = level >= 100
}


package com.grupopecuario.gpscamiones.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

class ConfigManager private constructor(context: Context) {

    companion object {
        private const val PREFS_CONFIG = "gps_camiones_device_config"
        private const val KEY_DEVICE_ID = "cfg_device_id"
        private const val KEY_PLACA = "cfg_placa_vehiculo"
        private const val KEY_CONDUCTOR = "cfg_nombre_conductor"
        private const val KEY_EMPRESA = "cfg_empresa"
        private const val KEY_SYNC_URL = "cfg_sync_url"
        private const val KEY_ULTIMA_SYNC = "cfg_ultima_sync_ms"
        private const val KEY_INTERVALO_GPS = "cfg_intervalo_gps_s"

        @Volatile
        private var instance: ConfigManager? = null

        fun getInstance(context: Context): ConfigManager {
            return instance ?: synchronized(this) {
                instance ?: ConfigManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)

    init {
        // Asegurar ID de dispositivo único y persistente
        if (!prefs.contains(KEY_DEVICE_ID)) {
            val nuevoId = "DEV-" + UUID.randomUUID().toString().substring(0, 8).uppercase()
            prefs.edit().putString(KEY_DEVICE_ID, nuevoId).apply()
        }
    }

    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "DEV-UNKNOWN") ?: "DEV-UNKNOWN"

    var placaVehiculo: String
        get() = prefs.getString(KEY_PLACA, "CAM-101") ?: "CAM-101"
        set(value) = prefs.edit().putString(KEY_PLACA, value).apply()

    var nombreConductor: String
        get() = prefs.getString(KEY_CONDUCTOR, "Conductor de Ruta") ?: "Conductor de Ruta"
        set(value) = prefs.edit().putString(KEY_CONDUCTOR, value).apply()

    var empresa: String
        get() = prefs.getString(KEY_EMPRESA, "Grupo Pecuario") ?: "Grupo Pecuario"
        set(value) = prefs.edit().putString(KEY_EMPRESA, value).apply()

    var servidorSyncUrl: String
        get() = prefs.getString(KEY_SYNC_URL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SYNC_URL, value).apply()

    var fechaUltimaSincronizacionMs: Long
        get() = prefs.getLong(KEY_ULTIMA_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_ULTIMA_SYNC, value).apply()

    var intervaloSegundos: Int
        get() = prefs.getInt(KEY_INTERVALO_GPS, 10)
        set(value) = prefs.edit().putInt(KEY_INTERVALO_GPS, value).apply()

    val modeloHardware: String
        get() = "${Build.MANUFACTURER.uppercase()} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
}


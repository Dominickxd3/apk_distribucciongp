package com.grupopecuario.gpscamiones.config

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.util.UUID

class ConfigManager private constructor(context: Context) {

    companion object {
        private const val PREFS_CONFIG = "gps_camiones_device_config"
        private const val KEY_DEVICE_ID = "cfg_device_id"
        private const val KEY_DEVICE_NAME = "cfg_device_name"
        private const val KEY_CONDUCTOR = "cfg_nombre_conductor"
        private const val KEY_EMPRESA = "cfg_empresa"
        private const val NOMBRE_POR_DEFECTO = "CAM-101"
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

    /**
     * Nombre corto que identifica al camión que envía las posiciones.
     * Se limita a 15 caracteres porque dbo.Vehiculos.placa es varchar(15)
     * y el backend solo acepta letras, números, espacio y guion.
     */
    var nombreDispositivo: String
        get() = prefs.getString(KEY_DEVICE_NAME, NOMBRE_POR_DEFECTO)
            ?: NOMBRE_POR_DEFECTO
        set(value) = prefs.edit()
            .putString(KEY_DEVICE_NAME, normalizarNombreDispositivo(value))
            .apply()

    /**
     * Identificador numérico para dbo.Vehiculos.id_unidad_proveedor.
     * Se deriva del nombre del dispositivo para que el mismo camión conserve
     * su identificador aunque se reinstale la app, y para que dos teléfonos
     * con el mismo nombre reporten sobre el mismo vehículo. El rango
     * 900,000,000–999,999,998 no choca con los IDs del proveedor GPS real
     * (que hoy van de 1000 a 2500).
     */
    val providerUnitId: Int
        get() {
            val crc = java.util.zip.CRC32()
            crc.update(nombreDispositivo.toByteArray(Charsets.UTF_8))
            return 900_000_000 + (crc.value % 99_999_999L).toInt()
        }

    var placaVehiculo: String
        get() = nombreDispositivo
        set(value) {
            nombreDispositivo = value
        }

    var nombreConductor: String
        get() = prefs.getString(KEY_CONDUCTOR, "Operador") ?: "Operador"
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

    private fun normalizarNombreDispositivo(valor: String): String {
        return valor.uppercase()
            .replace(Regex("[^A-Z0-9 -]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(15)
            .ifBlank { NOMBRE_POR_DEFECTO }
    }
}


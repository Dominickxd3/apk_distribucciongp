package com.grupopecuario.gpscamiones

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class GPSLocationService : Service() {

    companion object {
        const val ACTION_START = "com.grupopecuario.gpscamiones.START"
        const val ACTION_STOP = "com.grupopecuario.gpscamiones.STOP"

        const val PREFS = "gps_config"
        const val KEY_ENABLED = "seguimiento_habilitado"
        const val KEY_ESTADO = "estado_servicio"
        const val KEY_ESTADO_DETALLE = "estado_detalle"
        const val KEY_ULTIMO_CAMBIO_ESTADO_MS = "ultimo_cambio_estado_ms"
        const val KEY_ULTIMA_UBICACION_MS = "ultima_ubicacion_ms"

        // Estados operativos del seguimiento
        const val ESTADO_DETENIDO = "DETENIDO"
        const val ESTADO_INICIANDO = "INICIANDO"
        const val ESTADO_INICIADO = "INICIADO"
        const val ESTADO_ESPERANDO_UBICACION = "ESPERANDO_UBICACION"
        const val ESTADO_UBICACION_RECIENTE = "UBICACION_RECIENTE"
        const val ESTADO_PERMISOS_INSUFICIENTES = "PERMISOS_INSUFICIENTES"
        const val ESTADO_ERROR_RECUPERACION = "ERROR_RECUPERACION"
        const val ESTADO_ERROR_PROVEEDOR = "ERROR_PROVEEDOR"

        const val EXTRA_INICIADO_POR_BOOT = "iniciado_por_boot"

        const val CHANNEL_ID = "gps_tracking"
        const val NOTIFICATION_ID = 1001

        // Bandera volátil en memoria para verificación en tiempo real desde la UI del mismo proceso
        @Volatile
        var isServiceRunning: Boolean = false
            internal set

        @Volatile
        var ultimaUbicacionRecibidaMs: Long = 0L
            internal set
    }

    private val gps by lazy {
        LocationServices.getFusedLocationProviderClient(this)
    }

    private val database by lazy {
        GPSDatabaseHelper.getInstance(applicationContext)
    }

    private val syncClient by lazy { GPSSyncClient(applicationContext) }

    private var executor: ExecutorService? = null

    @Volatile
    private var seguimientoIniciado = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations
            if (locations.isEmpty()) return

            val ahora = System.currentTimeMillis()
            ultimaUbicacionRecibidaMs = ahora

            val ultima = locations.last()
            actualizarEstado(
                ESTADO_UBICACION_RECIENTE,
                "Fijación GPS activa (${ultima.latitude}, ${ultima.longitude}) ±${ultima.accuracy}m",
                guardarTimestampUbicacion = true
            )

            // Actualizar el texto de la notificación con la última lectura
            actualizarNotificacion("Rastreo activo: últimas coordenadas registradas")

            val exec = executor
            if (exec != null && !exec.isShutdown) {
                for (location in locations) {
                    exec.execute {
                        try {
                            database.guardarUbicacion(location)
                            syncClient.sincronizarPendientes()
                            Log.d("GPSCamiones", "Ubicación guardada en SQLite: ${location.time}")
                        } catch (e: Exception) {
                            Log.e("GPSCamiones", "Error al guardar ubicación en SQLite", e)
                        }
                    }
                }
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            val disponible = availability.isLocationAvailable
            Log.d("GPSCamiones", "Disponibilidad del proveedor GPS: $disponible")

            if (!disponible) {
                actualizarEstado(
                    ESTADO_ESPERANDO_UBICACION,
                    "Buscando satélites GPS / Señal temporalmente no disponible"
                )
                actualizarNotificacion("Buscando señal satelital GPS...")
            } else {
                actualizarEstado(
                    ESTADO_ESPERANDO_UBICACION,
                    "Señal GPS recuperada, esperando fijación de coordenadas"
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        executor = Executors.newSingleThreadExecutor()
        crearCanalNotificacion()
        Log.i("GPSCamiones", "GPSLocationService creado")
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        isServiceRunning = true

        // 1. Manejo explícito de la acción STOP
        if (intent?.action == ACTION_STOP) {
            Log.i("GPSCamiones", "Recibido ACTION_STOP en onStartCommand. Deteniendo servicio.")
            detenerSeguimientoCompleto(desactivarPorUsuario = true)
            stopSelf()
            return START_NOT_STICKY
        }

        // 2. Verificar si el usuario mantiene autorizado el seguimiento
        val habilitado = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

        if (!habilitado) {
            Log.i("GPSCamiones", "Seguimiento no habilitado en SharedPreferences. Deteniendo servicio.")
            detenerSeguimientoCompleto(desactivarPorUsuario = true)
            stopSelf()
            return START_NOT_STICKY
        }

        // 3. Verificar permisos de ubicación
        if (!tienePermiso()) {
            Log.e("GPSCamiones", "Permisos de ubicación insuficientes en onStartCommand")
            actualizarEstado(
                ESTADO_PERMISOS_INSUFICIENTES,
                "Permisos de ubicación revocados o faltantes"
            )
            detenerSeguimientoCompleto(desactivarPorUsuario = false)
            stopSelf()
            return START_NOT_STICKY
        }

        // 4. Iniciar Foreground Service con notificación continua
        try {
            val notification = crearNotificacion("Seguimiento de ubicación activo")

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(
                    NOTIFICATION_ID,
                    notification
                )
            }

            actualizarEstado(
                ESTADO_INICIADO,
                "Servicio en primer plano activo"
            )

            // 5. Iniciar la suscripción a FusedLocationProviderClient
            iniciarGPS()

        } catch (e: Exception) {
            Log.e("GPSCamiones", "Excepción al iniciar Foreground Service", e)
            actualizarEstado(
                ESTADO_ERROR_RECUPERACION,
                "Android impidió el Foreground Service: ${e.javaClass.simpleName} - ${e.message}"
            )
            detenerSeguimientoCompleto(desactivarPorUsuario = false)
            stopSelf()
            return START_NOT_STICKY
        }

        return START_STICKY
    }

    private fun tienePermiso(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        // El producto requiere telemetría precisa; una ubicación aproximada no es suficiente.
        return fine
    }

    @Synchronized
    private fun iniciarGPS() {
        if (seguimientoIniciado) {
            Log.i("GPSCamiones", "GPS ya se encuentra escuchando actualizaciones. Se omite registro duplicado.")
            return
        }

        if (!tienePermiso()) {
            Log.e("GPSCamiones", "Permisos GPS faltantes al iniciar GPS")
            actualizarEstado(
                ESTADO_PERMISOS_INSUFICIENTES,
                "Permisos faltantes para iniciar el proveedor GPS"
            )
            stopSelf()
            return
        }

        val request = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            10000L
        )
            .setMinUpdateIntervalMillis(5000L)
            .setMaxUpdateDelayMillis(0L)
            .build()

        try {
            seguimientoIniciado = true
            actualizarEstado(
                ESTADO_ESPERANDO_UBICACION,
                "Solicitud GPS enviada, esperando primera fijación satelital"
            )

            gps.requestLocationUpdates(
                request,
                callback,
                Looper.getMainLooper()
            ).addOnSuccessListener {
                Log.i("GPSCamiones", "Suscripción a LocationUpdates exitosa")
            }.addOnFailureListener { error ->
                Log.e("GPSCamiones", "Error solicitando actualizaciones de ubicación", error)
                seguimientoIniciado = false

                if (error is SecurityException) {
                    actualizarEstado(
                        ESTADO_PERMISOS_INSUFICIENTES,
                        "Permisos de ubicación revocados: ${error.message}"
                    )
                    stopSelf()
                } else {
                    // Error del proveedor o Google Play Services no fatal: registrar sin destruir inmediatamente
                    actualizarEstado(
                        ESTADO_ERROR_PROVEEDOR,
                        "Error temporal del proveedor GPS: ${error.message}"
                    )
                }
            }

        } catch (e: SecurityException) {
            seguimientoIniciado = false
            Log.e("GPSCamiones", "SecurityException al solicitar ubicación", e)
            actualizarEstado(
                ESTADO_PERMISOS_INSUFICIENTES,
                "Permiso de ubicación revocado por el sistema: ${e.message}"
            )
            stopSelf()
        } catch (e: Exception) {
            seguimientoIniciado = false
            Log.e("GPSCamiones", "Error inesperado al solicitar ubicación", e)
            actualizarEstado(
                ESTADO_ERROR_PROVEEDOR,
                "Error inesperado al conectar GPS: ${e.message}"
            )
        }
    }

    private fun detenerSeguimientoCompleto(desactivarPorUsuario: Boolean) {
        if (desactivarPorUsuario) {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, false)
                .putString(KEY_ESTADO, ESTADO_DETENIDO)
                .putString(KEY_ESTADO_DETALLE, "Seguimiento detenido por el usuario")
                .putLong(KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())
                .apply()
        }

        if (seguimientoIniciado) {
            try {
                gps.removeLocationUpdates(callback)
            } catch (e: Exception) {
                Log.e("GPSCamiones", "Error al remover actualizaciones GPS", e)
            }
            seguimientoIniciado = false
        }

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun actualizarEstado(
        estado: String,
        detalle: String,
        guardarTimestampUbicacion: Boolean = false
    ) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_ESTADO, estado)
            .putString(KEY_ESTADO_DETALLE, detalle)
            .putLong(KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())

        if (guardarTimestampUbicacion) {
            editor.putLong(KEY_ULTIMA_UBICACION_MS, System.currentTimeMillis())
        }

        editor.apply()
    }

    private fun crearCanalNotificacion() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Seguimiento GPS de camiones",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notificación persistente de telemetría y ubicación continua de camiones"
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun crearNotificacion(subtexto: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("GPSCamiones - Telemetría Activa")
            .setContentText(subtexto)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun actualizarNotificacion(subtexto: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        try {
            manager.notify(NOTIFICATION_ID, crearNotificacion(subtexto))
        } catch (e: Exception) {
            Log.w("GPSCamiones", "No se pudo actualizar la notificación: ${e.message}")
        }
    }

    override fun onDestroy() {
        isServiceRunning = false

        if (seguimientoIniciado) {
            try {
                gps.removeLocationUpdates(callback)
            } catch (e: Exception) {
                Log.e("GPSCamiones", "Error al remover callbacks de ubicación en onDestroy", e)
            }
            seguimientoIniciado = false
        }

        executor?.shutdown()
        executor = null

        val habilitado = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

        if (!habilitado) {
            actualizarEstado(
                ESTADO_DETENIDO,
                "Servicio finalizado y recursos liberados"
            )
        }

        Log.i("GPSCamiones", "GPSLocationService destruido y recursos liberados.")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

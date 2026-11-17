package com.grupopecuario.gpscamiones

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val CANAL_AVISOS = "gps_avisos_reinicio"
        private const val NOTIFICACION_REINICIO_ID = 2001
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {
        val accion = intent.action
        Log.i("GPSCamiones", "BootReceiver interceptó evento: $accion")

        if (accion != Intent.ACTION_BOOT_COMPLETED &&
            accion != "android.intent.action.QUICKBOOT_POWERON" &&
            accion != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val prefs = context.getSharedPreferences(
            GPSLocationService.PREFS,
            Context.MODE_PRIVATE
        )

        // 1. Verificar si el usuario autorizó previamente el seguimiento
        val habilitado = prefs.getBoolean(
            GPSLocationService.KEY_ENABLED,
            false
        )

        if (!habilitado) {
            Log.i("GPSCamiones", "Seguimiento no estaba habilitado antes del reinicio. No se ejecuta acción.")
            return
        }

        // 2. Verificar permisos de ubicación vigentes
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val background = Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine || !background) {
            Log.w("GPSCamiones", "Permisos de ubicación revocados o no disponibles tras reinicio.")
            prefs.edit()
                .putString(
                    GPSLocationService.KEY_ESTADO,
                    GPSLocationService.ESTADO_PERMISOS_INSUFICIENTES
                )
                .putString(
                    GPSLocationService.KEY_ESTADO_DETALLE,
                    "Permiso de ubicación precisa o en segundo plano faltante tras reinicio ($accion)"
                )
                .putLong(
                    GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS,
                    System.currentTimeMillis()
                )
                .apply()

            mostrarNotificacionRecuperacion(
                context,
                "Permisos GPS requeridos",
                "GPSCamiones necesita ubicación precisa y 'Permitir todo el tiempo'. Toca aquí para configurarlo."
            )
            return
        }

        // 3. Intento controlado de recuperación del servicio
        try {
            val serviceIntent = Intent(
                context,
                GPSLocationService::class.java
            ).apply {
                action = GPSLocationService.ACTION_START
                putExtra(GPSLocationService.EXTRA_INICIADO_POR_BOOT, true)
            }

            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )

            prefs.edit()
                .putString(
                    GPSLocationService.KEY_ESTADO,
                    GPSLocationService.ESTADO_INICIANDO
                )
                .putString(
                    GPSLocationService.KEY_ESTADO_DETALLE,
                    "Recuperación iniciada tras evento del sistema ($accion)"
                )
                .putLong(
                    GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS,
                    System.currentTimeMillis()
                )
                .apply()

            Log.i("GPSCamiones", "Foreground Service iniciado exitosamente desde BootReceiver tras $accion")

        } catch (e: Exception) {
            Log.e("GPSCamiones", "Android impidió el reinicio automático del servicio GPS tras $accion", e)

            val motivo = "${e.javaClass.simpleName}: ${e.message ?: "Restricción de inicio en segundo plano"}"

            prefs.edit()
                .putString(
                    GPSLocationService.KEY_ESTADO,
                    GPSLocationService.ESTADO_ERROR_RECUPERACION
                )
                .putString(
                    GPSLocationService.KEY_ESTADO_DETALLE,
                    "Android bloqueó el inicio directo tras reinicio: $motivo"
                )
                .putLong(
                    GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS,
                    System.currentTimeMillis()
                )
                .apply()

            // 4. Mecanismo de recuperación autorizado cuando Android bloquea el inicio directo
            mostrarNotificacionRecuperacion(
                context,
                "Seguimiento GPS pausado tras reinicio",
                "El sistema pausó el rastreo tras reiniciar. Toca aquí para reanudar el GPS del camión."
            )
        }
    }

    private fun mostrarNotificacionRecuperacion(
        context: Context,
        titulo: String,
        mensaje: String
    ) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CANAL_AVISOS,
                "Avisos de recuperación GPS",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notificaciones para reanudar el seguimiento tras el reinicio del teléfono"
            }
            manager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("auto_iniciar", true)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICACION_REINICIO_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notificacion = NotificationCompat.Builder(context, CANAL_AVISOS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(titulo)
            .setContentText(mensaje)
            .setStyle(NotificationCompat.BigTextStyle().bigText(mensaje))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            manager.notify(NOTIFICACION_REINICIO_ID, notificacion)
        } catch (e: SecurityException) {
            Log.w("GPSCamiones", "No se pudo emitir notificación de recuperación por falta de permiso POST_NOTIFICATIONS", e)
        }
    }
}

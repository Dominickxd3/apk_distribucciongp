package com.grupopecuario.gpscamiones

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Vigilante: cada ~15 min comprueba que el servicio GPS siga vivo y, si Android
 * lo cerró y no lo revivió, lo vuelve a iniciar. No necesita que nadie abra la app.
 * Android solo permite reiniciarlo desde segundo plano si la app está exenta de la
 * optimización de batería; si no lo está, el intento falla y queda registrado.
 */
class VigilanteGPS : BroadcastReceiver() {

    companion object {
        private const val ACCION = "com.grupopecuario.gpscamiones.VIGILANTE"
        private const val INTERVALO_MS = 15 * 60 * 1000L

        fun sinRestriccionDeBateria(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)
                ?.isIgnoringBatteryOptimizations(context.packageName) == true

        /** Programa (o reprograma) la revisión periódica. Idempotente. */
        fun programar(context: Context) {
            val alarmas = context.getSystemService(AlarmManager::class.java) ?: return
            val intent = PendingIntent.getBroadcast(
                context, 0,
                Intent(context, VigilanteGPS::class.java).setAction(ACCION),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmas.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + INTERVALO_MS,
                INTERVALO_MS,
                intent
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACCION) return
        val prefs = context.getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(GPSLocationService.KEY_ENABLED, false)) return
        if (GPSLocationService.isServiceRunning) return

        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GPSLocationService::class.java).setAction(GPSLocationService.ACTION_START)
            )
            Log.i("GPSCamiones", "Vigilante: el servicio GPS estaba detenido y se reinició")
        } catch (e: Exception) {
            Log.w("GPSCamiones", "Vigilante: Android no permitió reiniciar el GPS (batería restringida: ${!sinRestriccionDeBateria(context)})", e)
            prefs.edit()
                .putString(GPSLocationService.KEY_ESTADO, GPSLocationService.ESTADO_ERROR_RECUPERACION)
                .putString(
                    GPSLocationService.KEY_ESTADO_DETALLE,
                    "El vigilante no pudo reiniciar el GPS: ${e.javaClass.simpleName}. Quita la restricción de batería."
                )
                .putLong(GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())
                .apply()
        }
    }
}

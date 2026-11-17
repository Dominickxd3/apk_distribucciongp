package com.grupopecuario.gpscamiones

import android.app.Application
import android.util.Log
import org.maplibre.android.MapLibre

class GPSCamionesApp : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            // Inicializar el SDK nativo de MapLibre para el contexto de la aplicación
            MapLibre.getInstance(this)
            Log.i("GPSCamiones", "MapLibre Native inicializado correctamente")
        } catch (e: Exception) {
            Log.e("GPSCamiones", "Error al inicializar MapLibre Native: ${e.message}", e)
        }
    }
}


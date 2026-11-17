package com.grupopecuario.gpscamiones.ui

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.grupopecuario.gpscamiones.GPSDatabaseHelper
import com.grupopecuario.gpscamiones.R
import com.grupopecuario.gpscamiones.RegistroUbicacion
import com.grupopecuario.gpscamiones.UltimaUbicacion
import com.grupopecuario.gpscamiones.config.ConfigManager
import com.grupopecuario.gpscamiones.databinding.FragmentMapBinding
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import org.maplibre.android.annotations.Polyline
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MapFragment : Fragment() {

    private var _binding: FragmentMapBinding? = null
    private val binding get() = _binding!!

    private var mapLibreMap: MapLibreMap? = null
    private val executor = Executors.newSingleThreadExecutor()

    // Anotaciones en el mapa
    private var markerActual: Marker? = null
    private var circuloPrecision: Polygon? = null
    private var lineaTrayectoria: Polyline? = null

    // Control de seguimiento de cámara
    private var isFollowingUser: Boolean = true
    private var ultimaPosicionConocida: LatLng? = null

    private val formatoLima = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.mapView.onCreate(savedInstanceState)

        configurarInteracciones()
        inicializarMapa()
    }

    private fun configurarInteracciones() {
        val config = ConfigManager.getInstance(requireContext())
        binding.tvMapTitle.text = "Camión: ${config.nombreDispositivo}"

        // Botón FAB para recentrar
        binding.fabRecenter.setOnClickListener {
            isFollowingUser = true
            actualizarModoCamaraUI()
            centrarEnUltimaPosicion(animar = true)
        }

        // Botón FAB para alternar modo de seguimiento
        binding.fabToggleTrack.setOnClickListener {
            isFollowingUser = !isFollowingUser
            actualizarModoCamaraUI()
            if (isFollowingUser) {
                centrarEnUltimaPosicion(animar = true)
            }
        }
    }

    private fun actualizarModoCamaraUI() {
        if (_binding == null) return
        val context = context ?: return
        if (isFollowingUser) {
            binding.tvMapModeBadge.text = "Seguimiento Fijo"
            binding.tvMapModeBadge.setBackgroundResource(R.drawable.badge_status_active)
            binding.tvMapModeBadge.setTextColor(ContextCompat.getColor(context, R.color.status_active_text))
            binding.fabToggleTrack.setImageResource(R.drawable.ic_gps_fixed)
        } else {
            binding.tvMapModeBadge.text = "Exploración Libre"
            binding.tvMapModeBadge.setBackgroundResource(R.drawable.badge_status_neutral)
            binding.tvMapModeBadge.setTextColor(ContextCompat.getColor(context, R.color.status_neutral_text))
            binding.fabToggleTrack.setImageResource(R.drawable.ic_my_location)
        }
    }

    private fun inicializarMapa() {
        binding.mapView.getMapAsync { map ->
            mapLibreMap = map

            // Estilo cartográfico confiable y compatible (MapLibre Demo Tiles / Carto)
            val styleUrl = "https://tiles.openfreemap.org/styles/liberty"

            map.setStyle(Style.Builder().fromUri(styleUrl)) { style ->
                Log.i("GPSCamiones", "Estilo de mapa cargado exitosamente")
                binding.cardMapOfflineWarning.visibility = View.GONE
                actualizarMapaConDatosSQLite()
            }

            // Detección de gestos manuales del usuario para no forzar la cámara
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    if (isFollowingUser) {
                        isFollowingUser = false
                        actualizarModoCamaraUI()
                    }
                }
            }

            binding.mapView.addOnDidFailLoadingMapListener { errorMessage ->
                Log.w("GPSCamiones", "Fallo al cargar teselas de mapa: $errorMessage")
                activity?.runOnUiThread {
                    if (_binding != null) {
                        binding.cardMapOfflineWarning.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    fun actualizarMapaConDatosSQLite() {
        val ctx = context ?: return
        val map = mapLibreMap ?: return

        executor.execute {
            try {
                val db = GPSDatabaseHelper.getInstance(ctx)
                val ultima = db.obtenerUltimaUbicacion()
                val trayectoria = db.obtenerTrayectoriaReciente(150)

                activity?.runOnUiThread {
                    if (_binding == null || mapLibreMap == null) return@runOnUiThread

                    if (ultima != null) {
                        val posLatLng = LatLng(ultima.latitud, ultima.longitud)
                        ultimaPosicionConocida = posLatLng

                        actualizarTarjetaTelemetria(ultima)
                        actualizarMarcadorYPrecision(posLatLng, ultima)
                        actualizarTrayectoriaPolyline(trayectoria)

                        if (isFollowingUser) {
                            centrarEnUltimaPosicion(animar = false)
                        }
                    } else {
                        binding.tvMapCoords.text = "Sin posiciones registradas en SQLite"
                        binding.tvMapSubtitle.text = "Esperando la primera fijación GPS..."
                    }
                }
            } catch (e: Exception) {
                Log.e("GPSCamiones", "Error al consultar telemetría para mapa", e)
            }
        }
    }

    private fun centrarEnUltimaPosicion(animar: Boolean) {
        val map = mapLibreMap ?: return
        val pos = ultimaPosicionConocida ?: return

        val cameraPosition = CameraPosition.Builder()
            .target(pos)
            .zoom(16.0)
            .build()

        if (animar) {
            map.animateCamera(CameraUpdateFactory.newCameraPosition(cameraPosition), 800)
        } else {
            map.moveCamera(CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    private fun actualizarTarjetaTelemetria(ultima: UltimaUbicacion) {
        val ctx = context ?: return
        binding.tvMapCoords.text = String.format(
            Locale.US,
            "Lat: %.6f   Lon: %.6f",
            ultima.latitud,
            ultima.longitud
        )

        val velKmH = if (ultima.velocidad != null) ultima.velocidad * 3.6f else 0.0f
        binding.tvMapSpeed.text = String.format(Locale.US, "Velocidad: %.1f km/h", velKmH)
        binding.tvMapAccuracy.text = String.format(Locale.US, "Precisión: ±%.1f m", ultima.precision)
        binding.tvMapTime.text = formatoLima.format(Date(ultima.fechaGPS)) + " (Lima)"

        binding.tvMapSubtitle.text = "Última fijación registrada"

        if (ultima.esSimulada) {
            binding.tvMapMockBadge.text = "Ubicación Simulada"
            binding.tvMapMockBadge.setBackgroundResource(R.drawable.badge_status_danger)
            binding.tvMapMockBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_danger_text))
        } else {
            binding.tvMapMockBadge.text = "GPS Real"
            binding.tvMapMockBadge.setBackgroundResource(R.drawable.badge_status_active)
            binding.tvMapMockBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_active_text))
        }
    }

    private fun actualizarMarcadorYPrecision(posLatLng: LatLng, ultima: UltimaUbicacion) {
        val map = mapLibreMap ?: return

        val titulo = if (ultima.esSimulada) "Camión (Posición Simulada)" else "Camión - GPS Real"
        val snippet = String.format(
            Locale.US,
            "Precisión: ±%.1f m | Vel: %.1f km/h",
            ultima.precision,
            (ultima.velocidad ?: 0f) * 3.6f
        )

        // 1. Marcador
        markerActual?.let { map.removeMarker(it) }
        markerActual = map.addMarker(
            MarkerOptions()
                .position(posLatLng)
                .title(titulo)
                .snippet(snippet)
        )

        // 2. Círculo de Precisión estimada en metros
        circuloPrecision?.let { map.removePolygon(it) }
        if (ultima.precision > 0f) {
            val puntosCirculo = calcularPuntosCirculo(posLatLng, ultima.precision.toDouble())
            val fillColor = if (ultima.esSimulada) {
                Color.argb(35, 220, 38, 38)
            } else {
                Color.argb(35, 21, 52, 82)
            }
            val strokeColor = if (ultima.esSimulada) {
                Color.argb(120, 220, 38, 38)
            } else {
                Color.argb(120, 21, 52, 82)
            }

            circuloPrecision = map.addPolygon(
                PolygonOptions()
                    .addAll(puntosCirculo)
                    .fillColor(fillColor)
                    .strokeColor(strokeColor)
            )
        }
    }

    private fun actualizarTrayectoriaPolyline(trayectoria: List<RegistroUbicacion>) {
        val map = mapLibreMap ?: return

        lineaTrayectoria?.let { map.removePolyline(it) }

        if (trayectoria.size >= 2) {
            val listaLatLng = trayectoria.map { LatLng(it.latitud, it.longitud) }
            lineaTrayectoria = map.addPolyline(
                PolylineOptions()
                    .addAll(listaLatLng)
                    .color(Color.rgb(21, 52, 82))
                    .width(4.5f)
            )
        }
    }

    private fun calcularPuntosCirculo(centro: LatLng, radioMetros: Double, numPuntos: Int = 36): List<LatLng> {
        val puntos = ArrayList<LatLng>(numPuntos)
        val radioTierra = 6371000.0
        val latRad = Math.toRadians(centro.latitude)
        val lonRad = Math.toRadians(centro.longitude)
        val d = radioMetros / radioTierra

        for (i in 0 until numPuntos) {
            val rumbo = Math.toRadians((i * 360.0) / numPuntos)
            val pLat = Math.asin(Math.sin(latRad) * Math.cos(d) + Math.cos(latRad) * Math.sin(d) * Math.cos(rumbo))
            val pLon = lonRad + Math.atan2(Math.sin(rumbo) * Math.sin(d) * Math.cos(latRad), Math.cos(d) - Math.sin(latRad) * Math.sin(pLat))
            puntos.add(LatLng(Math.toDegrees(pLat), Math.toDegrees(pLon)))
        }
        return puntos
    }

    // Ciclo de vida obligatorio de MapView
    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
        actualizarMapaConDatosSQLite()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        binding.mapView.onStop()
        super.onStop()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onDestroyView() {
        binding.mapView.onDestroy()
        super.onDestroyView()
        _binding = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }
}


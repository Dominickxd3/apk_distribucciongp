package com.grupopecuario.gpscamiones.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.grupopecuario.gpscamiones.GPSDatabaseHelper
import com.grupopecuario.gpscamiones.GPSLocationService
import com.grupopecuario.gpscamiones.MainActivity
import com.grupopecuario.gpscamiones.R
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.auth.Role
import com.grupopecuario.gpscamiones.config.ConfigManager
import com.grupopecuario.gpscamiones.databinding.FragmentDashboardBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    private val executor = Executors.newSingleThreadExecutor()
    private var isExpandedDiagnostic = false
    private val placa = mutableStateOf("")

    private val formatoLima = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.dashboardHeader.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.dashboardHeader.setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = colorResource(R.color.primary_petrol_dark),
                    contentColor = colorResource(R.color.white)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("GPSCamiones", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (placa.value.isBlank()) "Sin vehículo asignado" else "Camión ${placa.value}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        configurarInteracciones()
        actualizarDatos()
    }

    private fun configurarInteracciones() {
        val act = activity as? MainActivity

        // Botón Iniciar Seguimiento
        binding.btnActivarSeguimiento.setOnClickListener {
            act?.solicitarInicioSeguimiento()
        }

        // Botón Detener Seguimiento (claramente disponible y con verificación de rol)
        binding.btnDetenerSeguimiento.setOnClickListener {
            val session = AuthManager.getInstance(requireContext()).getCurrentSession()
            if (!session.isAuthenticated) {
                act?.exigirSesionIniciada()
            } else if (session.role.canControlTracking()) {
                act?.confirmarYDetenerSeguimiento()
            } else {
                // Si es un conductor o usuario sin permisos, solicitar autenticación de supervisor
                act?.mostrarDialogoAutorizacionDetencion()
            }
        }

        // Botón Actualizar Información
        binding.btnActualizarAhora.setOnClickListener {
            actualizarDatos()
            Toast.makeText(requireContext(), "Actualizando telemetría...", Toast.LENGTH_SHORT).show()
        }

        // Acceso directo a Mapa
        binding.btnVerEnMapa.setOnClickListener {
            act?.navegarAMapa()
        }

        // Copiar coordenadas al portapapeles
        binding.btnCopiarCoordenadas.setOnClickListener {
            val coords = binding.tvCoordenadas.text.toString()
            if (coords.isNotEmpty() && !coords.contains("--")) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Coordenadas GPS", coords)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), "Coordenadas copiadas al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }

        // Acordeón Diagnóstico Técnico
        binding.layoutDiagnosticoHeader.setOnClickListener {
            isExpandedDiagnostic = !isExpandedDiagnostic
            binding.layoutDiagnosticoDetalle.visibility = if (isExpandedDiagnostic) View.VISIBLE else View.GONE
            binding.ivChevronDiagnostico.setImageResource(
                if (isExpandedDiagnostic) R.drawable.ic_expand_less else R.drawable.ic_expand_more
            )
        }
    }

    fun actualizarDatos() {
        val ctx = context ?: return
        placa.value = ConfigManager.getInstance(ctx).placaVehiculo
        val act = activity as? MainActivity ?: return

        executor.execute {
            try {
                val db = GPSDatabaseHelper.getInstance(ctx)
                val total = db.contarUbicaciones()
                val pendientes = db.contarPendientes()
                val ultima = db.obtenerUltimaUbicacion()

                val prefs = ctx.getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)
                val habilitado = prefs.getBoolean(GPSLocationService.KEY_ENABLED, false)
                val estadoServicio = prefs.getString(
                    GPSLocationService.KEY_ESTADO,
                    GPSLocationService.ESTADO_DETENIDO
                ) ?: GPSLocationService.ESTADO_DETENIDO

                val estadoDetalle = prefs.getString(GPSLocationService.KEY_ESTADO_DETALLE, "") ?: ""
                val ultimaUbicacionMs = prefs.getLong(GPSLocationService.KEY_ULTIMA_UBICACION_MS, 0L)
                val ultimoIntervalo = prefs.getLong(GPSLocationService.KEY_ULTIMO_INTERVALO_SIN_GPS_MS, 0L)

                val enMemoria = GPSLocationService.isServiceRunning
                val tieneFino = act.tienePermisoUbicacion()
                val tieneBackground = act.tienePermisoBackground()
                val tieneNotif = act.tienePermisoNotificaciones()

                val ahora = System.currentTimeMillis()
                val segundosDesdeUltima = if (ultimaUbicacionMs > 0) {
                    (ahora - ultimaUbicacionMs) / 1000
                } else -1L

                // Determinar estado semántico y colores
                val (badgeTexto, badgeBgRes, badgeTextColorRes, descTexto) = when {
                    !tieneFino -> Quadruple(
                        "PERMISOS DENEGADOS",
                        R.drawable.badge_status_danger,
                        R.color.status_danger_text,
                        "Android requiere el permiso de ubicación precisa para capturar telemetría."
                    )
                    enMemoria && segundosDesdeUltima in 0..25 -> Quadruple(
                        "SEGUIMIENTO ACTIVO",
                        R.drawable.badge_status_active,
                        R.color.status_active_text,
                        "Fijación satelital activa. Capturando y guardando coordenadas en SQLite."
                    )
                    enMemoria -> Quadruple(
                        "ESPERANDO GPS",
                        R.drawable.badge_status_warning,
                        R.color.status_warning_text,
                        "Servicio activo en memoria. Buscando satélites o esperando nueva fijación."
                    )
                    habilitado && !enMemoria -> Quadruple(
                        "SERVICIO DETENIDO POR SO",
                        R.drawable.badge_status_danger,
                        R.color.status_danger_text,
                        "El sistema operativo pausó el servicio. Presiona Activar para reanudar el rastreo."
                    )
                    else -> Quadruple(
                        "SEGUIMIENTO DETENIDO",
                        R.drawable.badge_status_neutral,
                        R.color.status_neutral_text,
                        "Seguimiento inactivo. Presiona 'Activar Seguimiento' para comenzar la ruta."
                    )
                }

                act.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    // 1. Estado Principal
                    binding.tvBadgeEstado.text = badgeTexto
                    binding.tvBadgeEstado.setBackgroundResource(badgeBgRes)
                    binding.tvBadgeEstado.setTextColor(ContextCompat.getColor(ctx, badgeTextColorRes))
                    binding.tvEstadoDescripcion.text = descTexto

                    binding.tvTiempoUltimaPosicion.text = when {
                        segundosDesdeUltima in 0..60 -> "Hace ${segundosDesdeUltima}s"
                        segundosDesdeUltima > 60 -> "Hace ${segundosDesdeUltima / 60}m"
                        else -> "Sin señal reciente"
                    }

                    // 2. Velocidad y Precisión
                    if (ultima != null) {
                        val velKmH = if (ultima.velocidad != null) ultima.velocidad * 3.6f else 0.0f
                        val velMs = ultima.velocidad ?: 0.0f
                        binding.tvMetricaVelocidad.text = String.format(Locale.US, "%.1f km/h", velKmH)
                        binding.tvMetricaVelocidadMs.text = String.format(Locale.US, "%.1f m/s", velMs)
                        binding.tvMetricaPrecision.text = String.format(Locale.US, "±%.1f m", ultima.precision)

                        binding.tvCoordenadas.text = String.format(
                            Locale.US,
                            "Lat: %.6f\nLon: %.6f",
                            ultima.latitud,
                            ultima.longitud
                        )

                        binding.tvFechaGps.text = formatoLima.format(Date(ultima.fechaGPS))
                        binding.tvFechaSqlite.text = formatoLima.format(Date(ultima.fechaRegistro))

                        if (ultima.esSimulada) {
                            binding.tvBadgeMock.text = "Simulada (Mock)"
                            binding.tvBadgeMock.setBackgroundResource(R.drawable.badge_status_danger)
                            binding.tvBadgeMock.setTextColor(ContextCompat.getColor(ctx, R.color.status_danger_text))
                        } else {
                            binding.tvBadgeMock.text = "GPS Real"
                            binding.tvBadgeMock.setBackgroundResource(R.drawable.badge_status_active)
                            binding.tvBadgeMock.setTextColor(ContextCompat.getColor(ctx, R.color.status_active_text))
                        }
                    } else {
                        binding.tvMetricaVelocidad.text = "0.0 km/h"
                        binding.tvMetricaVelocidadMs.text = "0.0 m/s"
                        binding.tvMetricaPrecision.text = "±-- m"
                        binding.tvCoordenadas.text = "Sin posiciones registradas"
                        binding.tvFechaGps.text = "--/--/---- --:--:--"
                        binding.tvFechaSqlite.text = "--/--/---- --:--:--"
                        binding.tvBadgeMock.text = "Sin datos"
                        binding.tvBadgeMock.setBackgroundResource(R.drawable.badge_status_neutral)
                        binding.tvBadgeMock.setTextColor(ContextCompat.getColor(ctx, R.color.status_neutral_text))
                    }

                    // 3. Indicadores SQLite
                    binding.tvSqliteTotal.text = total.toString()
                    binding.tvSqlitePendientes.text = pendientes.toString()

                    // 4. Estado de botones de control
                    binding.btnActivarSeguimiento.isEnabled = !enMemoria
                    binding.btnDetenerSeguimiento.isEnabled = enMemoria || habilitado

                    // 5. Diagnóstico Técnico Expandible
                    binding.tvDiagServicioMemoria.text = "• Servicio activo en memoria: ${if (enMemoria) "Sí (Corriendo)" else "No (Inactivo)"}"
                    binding.tvDiagSeguimientoHabilitado.text = "• Seguimiento habilitado (Config): ${if (habilitado) "Sí" else "No"}"
                    binding.tvDiagEstadoReportado.text = "• Estado reportado: $estadoServicio"
                    binding.tvDiagDetalleReportado.text = buildString {
                        append("• Detalle del proveedor: $estadoDetalle")
                        if (habilitado && segundosDesdeUltima >= 60) {
                            append("\n• Sin nueva ubicación desde hace ${segundosDesdeUltima / 60} min")
                        }
                        if (ultimoIntervalo > 0) {
                            append("\n• Último intervalo entre lecturas: ${ultimoIntervalo / 1000} s")
                        }
                    }
                    binding.tvDiagPermisoFino.text = "• Permiso ubicación precisa: ${if (tieneFino) "Concedido" else "DENEGADO"}"
                    binding.tvDiagPermisoBackground.text = "• Permiso segundo plano: ${if (tieneBackground) "Todo el tiempo (Habilitado)" else "Solo con app en pantalla"}"
                    binding.tvDiagPermisoNotificaciones.text = "• Permiso notificaciones: ${if (tieneNotif) "Concedido" else "No concedido"}"
                    binding.tvDiagMock.text = "• Detección de ubicación simulada: ${if (ultima?.esSimulada == true) "SÍ (Detectada)" else "Inactiva (GPS Real)"}"
                }
            } catch (e: Exception) {
                // Log and silently handle
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}

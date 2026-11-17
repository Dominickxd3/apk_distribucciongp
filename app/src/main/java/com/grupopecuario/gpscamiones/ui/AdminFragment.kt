package com.grupopecuario.gpscamiones.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.grupopecuario.gpscamiones.GPSDatabaseHelper
import com.grupopecuario.gpscamiones.MainActivity
import com.grupopecuario.gpscamiones.R
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.auth.Role
import com.grupopecuario.gpscamiones.config.ConfigManager
import com.grupopecuario.gpscamiones.databinding.FragmentAdminBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class AdminFragment : Fragment() {

    private var _binding: FragmentAdminBinding? = null
    private val binding get() = _binding!!

    private val executor = Executors.newSingleThreadExecutor()

    private val formatoLima = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAdminBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        configurarInteracciones()
        actualizarDatos()
    }

    private fun configurarInteracciones() {
        val act = activity as? MainActivity

        // Iniciar Sesión Administrativa
        binding.btnAdminLogin.setOnClickListener {
            act?.abrirDialogoLogin()
        }

        // Cerrar Sesión
        binding.btnAdminLogout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Cerrar Sesión")
                .setMessage("¿Deseas cerrar la sesión administrativa? El servicio GPS en segundo plano continuará operando normalmente.")
                .setPositiveButton("Cerrar Sesión") { _, _ ->
                    AuthManager.getInstance(requireContext()).logout()
                    actualizarDatos()
                    Toast.makeText(requireContext(), "Sesión cerrada", Toast.LENGTH_SHORT).show()
                    act?.exigirSesionIniciada()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Modificar Asignación de Vehículo (Protegido por Rol)
        binding.btnAdminEditAssignment.setOnClickListener {
            val session = AuthManager.getInstance(requireContext()).getCurrentSession()
            if (session.role.canEditVehicleConfig()) {
                val dialog = VehicleAssignmentDialog.newInstance {
                    actualizarDatos()
                    Toast.makeText(requireContext(), "Asignación actualizada", Toast.LENGTH_SHORT).show()
                }
                dialog.show(parentFragmentManager, VehicleAssignmentDialog.TAG)
            } else {
                AlertDialog.Builder(requireContext())
                    .setTitle("Acceso Restringido")
                    .setMessage("Se requieren permisos de Administrador para cambiar el nombre del dispositivo. Por favor inicia sesión.")
                    .setPositiveButton("Iniciar Sesión") { _, _ ->
                        act?.abrirDialogoLogin()
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
        }

        // Botón Actualizar SQLite Stats
        binding.btnAdminRefreshDb.setOnClickListener {
            actualizarEstadisticasDB()
            Toast.makeText(requireContext(), "Estadísticas actualizadas", Toast.LENGTH_SHORT).show()
        }

        // Botón Abrir Ajustes de Android
        binding.btnAdminOpenSettings.setOnClickListener {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", requireContext().packageName, null)
            }
            startActivity(intent)
        }
    }

    fun actualizarDatos() {
        val ctx = context ?: return
        val act = activity as? MainActivity ?: return

        // 1. Sesión y Rol
        val session = AuthManager.getInstance(ctx).getCurrentSession()
        val config = ConfigManager.getInstance(ctx)

        binding.tvAdminSessionUser.text = if (session.isAuthenticated) session.displayName else "Sin sesión"
        binding.tvAdminSessionRole.text = if (session.isAuthenticated) {
            "Conectado como ${session.role.displayName} (${session.username})"
        } else {
            "Inicia sesión con tu usuario del ERP"
        }

        binding.tvAdminRoleBadge.text = session.role.name
        when (session.role) {
            Role.ADMIN -> {
                binding.tvAdminRoleBadge.setBackgroundResource(R.drawable.badge_status_active)
                binding.tvAdminRoleBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_active_text))
            }
            Role.SUPERVISOR -> {
                binding.tvAdminRoleBadge.setBackgroundResource(R.drawable.badge_status_warning)
                binding.tvAdminRoleBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_warning_text))
            }
            else -> {
                binding.tvAdminRoleBadge.setBackgroundResource(R.drawable.badge_status_neutral)
                binding.tvAdminRoleBadge.setTextColor(ContextCompat.getColor(ctx, R.color.status_neutral_text))
            }
        }

        if (session.isAuthenticated) {
            binding.btnAdminLogin.visibility = View.GONE
            binding.btnAdminLogout.visibility = View.VISIBLE
        } else {
            binding.btnAdminLogin.visibility = View.VISIBLE
            binding.btnAdminLogout.visibility = View.GONE
        }

        // 2. Hardware y Asignación
        binding.tvAdminDeviceId.text = "• ID Dispositivo: ${config.deviceId}"
        binding.tvAdminTruckPlate.text = "• Dispositivo / Camión: ${config.nombreDispositivo}"
        binding.tvAdminDriverName.visibility = View.GONE
        binding.tvAdminHardwareModel.text = "• Dispositivo Móvil: ${config.modeloHardware}"

        // 3. Sincronización
        if (config.fechaUltimaSincronizacionMs > 0) {
            binding.tvAdminLastSyncDate.text = formatoLima.format(Date(config.fechaUltimaSincronizacionMs))
        } else {
            binding.tvAdminLastSyncDate.text = "Aún sin sincronizaciones confirmadas"
        }

        // 4. Permisos de Android
        val tieneFino = act.tienePermisoUbicacion()
        val tieneBg = act.tienePermisoBackground()
        val tieneNotif = act.tienePermisoNotificaciones()

        binding.tvAdminPermFine.text = "• Ubicación precisa (Fine): ${if (tieneFino) "Concedido" else "DENEGADO"}"
        binding.tvAdminPermFine.setTextColor(ContextCompat.getColor(ctx, if (tieneFino) R.color.status_active_text else R.color.status_danger_text))

        binding.tvAdminPermBg.text = "• Ubicación segundo plano: ${if (tieneBg) "Todo el tiempo (Concedido)" else "Solo en uso (Se recomienda 'Permitir todo el tiempo')"}"
        binding.tvAdminPermBg.setTextColor(ContextCompat.getColor(ctx, if (tieneBg) R.color.status_active_text else R.color.status_warning_text))

        binding.tvAdminPermNotif.text = "• Notificaciones continuas: ${if (tieneNotif) "Concedido" else "No concedido"}"
        binding.tvAdminPermNotif.setTextColor(ContextCompat.getColor(ctx, if (tieneNotif) R.color.status_active_text else R.color.status_warning_text))

        // 5. Estadísticas SQLite
        actualizarEstadisticasDB()
    }

    private fun actualizarEstadisticasDB() {
        val ctx = context ?: return

        executor.execute {
            try {
                val db = GPSDatabaseHelper.getInstance(ctx)
                val stats = db.obtenerEstadisticasDB()

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    binding.tvSqliteStatsTotal.text = "• Total posiciones registradas: ${stats.totalRegistros}"
                    binding.tvSqliteStatsPendientes.text = "• Pendientes de subida a web: ${stats.pendientesSync}"

                    val primerStr = if (stats.primerRegistroUtc != null) {
                        formatoLima.format(Date(stats.primerRegistroUtc))
                    } else "--"
                    binding.tvSqliteStatsPrimer.text = "• Primer registro histórico: $primerStr (Lima)"

                    val ultimoStr = if (stats.ultimoRegistroUtc != null) {
                        formatoLima.format(Date(stats.ultimoRegistroUtc))
                    } else "--"
                    binding.tvSqliteStatsUltimo.text = "• Último registro almacenado: $ultimoStr (Lima)"

                    binding.tvSqliteStatsWal.text = "• Modo WAL (Write-Ahead Logging): ${if (stats.esModoWAL) "Habilitado" else "Estándar"}"
                }
            } catch (e: Exception) {
                // Silently handle
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


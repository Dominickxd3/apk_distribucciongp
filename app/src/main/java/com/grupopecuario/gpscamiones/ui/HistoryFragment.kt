package com.grupopecuario.gpscamiones.ui

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.grupopecuario.gpscamiones.GPSDatabaseHelper
import com.grupopecuario.gpscamiones.R
import com.grupopecuario.gpscamiones.databinding.FragmentHistoryBinding
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class HistoryFragment : Fragment() {

    private var _binding: FragmentHistoryBinding? = null
    private val binding get() = _binding!!

    private val adapter = HistoryAdapter()
    private val executor = Executors.newSingleThreadExecutor()

    private var fechaSeleccionadaCal: Calendar = Calendar.getInstance(TimeZone.getTimeZone("America/Lima"))

    private val formatoFechaDisplay = SimpleDateFormat("EEEE dd/MM/yyyy", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    private val formatoHora = SimpleDateFormat("HH:mm", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHistoryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvHistory.layoutManager = LinearLayoutManager(requireContext())
        binding.rvHistory.adapter = adapter

        configurarFiltros()
        cargarHistorialParaFecha(fechaSeleccionadaCal)
    }

    private fun configurarFiltros() {
        binding.toggleGroupFiltros.check(R.id.btn_filter_today)

        binding.btnFilterToday.setOnClickListener {
            fechaSeleccionadaCal = Calendar.getInstance(TimeZone.getTimeZone("America/Lima"))
            binding.tvHistoryBadgeDay.text = "HOY"
            cargarHistorialParaFecha(fechaSeleccionadaCal)
        }

        binding.btnFilterYesterday.setOnClickListener {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Lima")).apply {
                add(Calendar.DAY_OF_YEAR, -1)
            }
            fechaSeleccionadaCal = cal
            binding.tvHistoryBadgeDay.text = "AYER"
            cargarHistorialParaFecha(fechaSeleccionadaCal)
        }

        binding.btnFilterDatePicker.setOnClickListener {
            val año = fechaSeleccionadaCal.get(Calendar.YEAR)
            val mes = fechaSeleccionadaCal.get(Calendar.MONTH)
            val dia = fechaSeleccionadaCal.get(Calendar.DAY_OF_MONTH)

            val dpd = DatePickerDialog(
                requireContext(),
                { _, y, m, d ->
                    val cal = Calendar.getInstance(TimeZone.getTimeZone("America/Lima")).apply {
                        set(Calendar.YEAR, y)
                        set(Calendar.MONTH, m)
                        set(Calendar.DAY_OF_MONTH, d)
                    }
                    fechaSeleccionadaCal = cal
                    binding.tvHistoryBadgeDay.text = String.format(Locale.US, "%02d/%02d", d, m + 1)
                    cargarHistorialParaFecha(fechaSeleccionadaCal)
                },
                año,
                mes,
                dia
            )
            dpd.show()
        }
    }

    fun cargarHistorialParaFecha(cal: Calendar) {
        val ctx = context ?: return

        // Calcular límites de inicio y fin de día en hora Lima (convertido a UTC millis)
        val calInicio = cal.clone() as Calendar
        calInicio.set(Calendar.HOUR_OF_DAY, 0)
        calInicio.set(Calendar.MINUTE, 0)
        calInicio.set(Calendar.SECOND, 0)
        calInicio.set(Calendar.MILLISECOND, 0)
        val inicioUtc = calInicio.timeInMillis

        val calFin = cal.clone() as Calendar
        calFin.set(Calendar.HOUR_OF_DAY, 23)
        calFin.set(Calendar.MINUTE, 59)
        calFin.set(Calendar.SECOND, 59)
        calFin.set(Calendar.MILLISECOND, 999)
        val finUtc = calFin.timeInMillis

        val fechaTexto = formatoFechaDisplay.format(Date(inicioUtc)).replaceFirstChar { it.uppercase() }
        binding.tvHistorySelectedDate.text = "Auditoría del $fechaTexto"

        executor.execute {
            try {
                val db = GPSDatabaseHelper.getInstance(ctx)
                val registros = db.obtenerRegistrosPorRango(inicioUtc, finUtc, 500)

                // Detectar lagunas temporales (> 5 minutos entre capturas)
                val itemsFinales = mutableListOf<HistoryItem>()
                val umbralSaltoMs = 5 * 60 * 1000L

                for (i in registros.indices) {
                    val regActual = registros[i]
                    itemsFinales.add(HistoryItem.Location(regActual))

                    if (i < registros.size - 1) {
                        val regAnterior = registros[i + 1]
                        val diferenciaMs = regActual.fechaGPS - regAnterior.fechaGPS
                        if (diferenciaMs > umbralSaltoMs) {
                            val minutos = diferenciaMs / (60 * 1000L)
                            itemsFinales.add(HistoryItem.Gap(minutos))
                        }
                    }
                }

                // Cálculo de KPIs de la jornada
                val totalPuntos = registros.size
                val rangoHorario = if (registros.isNotEmpty()) {
                    val horaInicio = formatoHora.format(Date(registros.last().fechaGPS))
                    val horaFin = formatoHora.format(Date(registros.first().fechaGPS))
                    "$horaInicio - $horaFin"
                } else {
                    "--:-- - --:--"
                }

                val maxVelMps = registros.mapNotNull { it.velocidad }.maxOrNull() ?: 0.0f
                val maxVelKmH = maxVelMps * 3.6f

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread

                    binding.tvKpiTotalPuntos.text = totalPuntos.toString()
                    binding.tvKpiRangoHorario.text = rangoHorario
                    binding.tvKpiVelMaxima.text = String.format(Locale.US, "%.1f km/h", maxVelKmH)

                    if (registros.isEmpty()) {
                        binding.layoutHistoryEmpty.visibility = View.VISIBLE
                        binding.rvHistory.visibility = View.GONE
                    } else {
                        binding.layoutHistoryEmpty.visibility = View.GONE
                        binding.rvHistory.visibility = View.VISIBLE
                        adapter.submitList(itemsFinales)
                    }
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

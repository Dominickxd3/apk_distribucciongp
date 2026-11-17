package com.grupopecuario.gpscamiones.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.grupopecuario.gpscamiones.R
import com.grupopecuario.gpscamiones.RegistroUbicacion
import com.grupopecuario.gpscamiones.databinding.ItemHistoryGapBinding
import com.grupopecuario.gpscamiones.databinding.ItemHistoryLocationBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed class HistoryItem {
    data class Location(val registro: RegistroUbicacion) : HistoryItem()
    data class Gap(val minutosSalto: Long) : HistoryItem()
}

class HistoryAdapter(
    private var items: List<HistoryItem> = emptyList()
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_LOCATION = 1
        private const val TYPE_GAP = 2
    }

    private val formatoHora = SimpleDateFormat("HH:mm:ss", Locale.forLanguageTag("es-PE")).apply {
        timeZone = TimeZone.getTimeZone("America/Lima")
    }

    fun submitList(nuevosItems: List<HistoryItem>) {
        items = nuevosItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is HistoryItem.Location -> TYPE_LOCATION
            is HistoryItem.Gap -> TYPE_GAP
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_LOCATION) {
            val binding = ItemHistoryLocationBinding.inflate(inflater, parent, false)
            LocationViewHolder(binding)
        } else {
            val binding = ItemHistoryGapBinding.inflate(inflater, parent, false)
            GapViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is HistoryItem.Location -> (holder as LocationViewHolder).bind(item.registro)
            is HistoryItem.Gap -> (holder as GapViewHolder).bind(item.minutosSalto)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class LocationViewHolder(private val binding: ItemHistoryLocationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(registro: RegistroUbicacion) {
            val context = itemView.context
            val horaTexto = formatoHora.format(Date(registro.fechaGPS))
            binding.tvItemHora.text = "$horaTexto (Lima)"

            binding.tvItemCoords.text = String.format(
                Locale.US,
                "Lat: %.6f   Lon: %.6f",
                registro.latitud,
                registro.longitud
            )

            val velKmH = if (registro.velocidad != null) {
                String.format(Locale.US, "%.1f km/h", registro.velocidad * 3.6f)
            } else {
                "0.0 km/h"
            }
            binding.tvItemVelocidad.text = "Vel: $velKmH"
            binding.tvItemPrecision.text = String.format(Locale.US, "Precisión: ±%.1f m", registro.precision)

            if (registro.esSimulada) {
                binding.tvItemMock.text = "Simulada (Mock)"
                binding.tvItemMock.setTextColor(ContextCompat.getColor(context, R.color.status_danger_text))
                binding.tvItemMock.setBackgroundResource(R.drawable.badge_status_danger)
            } else {
                binding.tvItemMock.text = "GPS Real"
                binding.tvItemMock.setTextColor(ContextCompat.getColor(context, R.color.status_active_text))
                binding.tvItemMock.setBackgroundResource(R.drawable.badge_status_active)
            }

            if (registro.sincronizado) {
                binding.tvItemSync.text = "Sincronizado"
                binding.tvItemSync.setTextColor(ContextCompat.getColor(context, R.color.status_active_text))
                binding.tvItemSync.setBackgroundResource(R.drawable.badge_status_active)
            } else {
                binding.tvItemSync.text = "Pendiente Sync"
                binding.tvItemSync.setTextColor(ContextCompat.getColor(context, R.color.status_warning_text))
                binding.tvItemSync.setBackgroundResource(R.drawable.badge_status_warning)
            }
        }
    }

    inner class GapViewHolder(private val binding: ItemHistoryGapBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(minutos: Long) {
            binding.tvGapMessage.text = "⚠️ Salto de telemetría de $minutos min sin registros continuos"
        }
    }
}


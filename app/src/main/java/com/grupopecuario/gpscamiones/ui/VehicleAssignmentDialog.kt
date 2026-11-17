package com.grupopecuario.gpscamiones.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.grupopecuario.gpscamiones.config.ConfigManager
import com.grupopecuario.gpscamiones.databinding.DialogVehicleAssignmentBinding

class VehicleAssignmentDialog : DialogFragment() {

    private var _binding: DialogVehicleAssignmentBinding? = null
    private val binding get() = _binding!!

    private var onSavedListener: (() -> Unit)? = null

    fun setOnSavedListener(listener: () -> Unit) {
        this.onSavedListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogVehicleAssignmentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val config = ConfigManager.getInstance(requireContext())
        binding.etPlate.setText(config.placaVehiculo)
        binding.etDriver.setText(config.nombreConductor)

        binding.btnCancelAssignment.setOnClickListener {
            dismiss()
        }

        binding.btnSaveAssignment.setOnClickListener {
            val plate = binding.etPlate.text?.toString()?.trim().orEmpty()
            val driver = binding.etDriver.text?.toString()?.trim().orEmpty()

            if (plate.isNotEmpty()) {
                config.placaVehiculo = plate
            }
            if (driver.isNotEmpty()) {
                config.nombreConductor = driver
            }

            onSavedListener?.invoke()
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "VehicleAssignmentDialog"

        fun newInstance(onSaved: () -> Unit): VehicleAssignmentDialog {
            return VehicleAssignmentDialog().apply {
                setOnSavedListener(onSaved)
            }
        }
    }
}


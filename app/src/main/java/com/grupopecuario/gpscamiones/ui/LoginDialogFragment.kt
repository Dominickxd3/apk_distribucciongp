package com.grupopecuario.gpscamiones.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.grupopecuario.gpscamiones.auth.AuthRepository
import com.grupopecuario.gpscamiones.auth.AuthResult
import com.grupopecuario.gpscamiones.databinding.DialogLoginBinding

class LoginDialogFragment : DialogFragment() {

    private var _binding: DialogLoginBinding? = null
    private val binding get() = _binding!!

    private var onLoginSuccessListener: (() -> Unit)? = null

    fun setOnLoginSuccessListener(listener: () -> Unit) {
        this.onLoginSuccessListener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogLoginBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val authRepository = AuthRepository(requireContext())

        binding.btnCancelLogin.setOnClickListener {
            dismiss()
        }

        binding.btnSubmitLogin.setOnClickListener {
            val user = binding.etUsername.text?.toString().orEmpty()
            val pass = binding.etPassword.text?.toString().orEmpty()

            binding.tvLoginError.visibility = View.GONE
            binding.progressLogin.visibility = View.VISIBLE
            binding.btnSubmitLogin.isEnabled = false

            authRepository.login(user, pass) { result ->
                binding.progressLogin.visibility = View.GONE
                binding.btnSubmitLogin.isEnabled = true

                when (result) {
                    is AuthResult.Success -> {
                        onLoginSuccessListener?.invoke()
                        dismiss()
                    }
                    is AuthResult.Failure -> {
                        binding.tvLoginError.text = result.errorMessage
                        binding.tvLoginError.visibility = View.VISIBLE
                    }
                    is AuthResult.BackendUnavailable -> {
                        binding.tvLoginError.text = result.detalle
                        binding.tvLoginError.visibility = View.VISIBLE
                    }
                    AuthResult.Loading -> {
                        binding.progressLogin.visibility = View.VISIBLE
                    }
                }
            }
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
        const val TAG = "LoginDialogFragment"

        fun newInstance(onSuccess: () -> Unit): LoginDialogFragment {
            return LoginDialogFragment().apply {
                setOnLoginSuccessListener(onSuccess)
            }
        }
    }
}


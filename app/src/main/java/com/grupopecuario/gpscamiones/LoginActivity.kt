package com.grupopecuario.gpscamiones

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.auth.AuthRepository
import com.grupopecuario.gpscamiones.auth.AuthResult
import com.grupopecuario.gpscamiones.databinding.ActivityLoginBinding

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var authRepository: AuthRepository
    private var estaAutenticando = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si ya hay sesión válida, ir directo al dashboard
        if (AuthManager.getInstance(this).isUserLoggedIn) {
            irAlDashboard()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        authRepository = AuthRepository(this)

        configurarVentana()
        configurarCampos()
        configurarBoton()
    }

    private fun configurarVentana() {
        // Fondo azul petróleo en status bar
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
        }

        @Suppress("DEPRECATION")
        window.statusBarColor = ContextCompat.getColor(this, R.color.primary_petrol)

        // Padding para no solapar el status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(0, sb.top, 0, nb.bottom)
            insets
        }
    }

    private fun configurarCampos() {
        // Limpiar error al escribir
        binding.etUsuario.setOnFocusChangeListener { _, _ -> ocultarError() }
        binding.etClave.setOnFocusChangeListener { _, _ -> ocultarError() }

        // Acción "Done" en teclado → intentar login
        binding.etClave.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                intentarLogin()
                true
            } else false
        }
    }

    private fun configurarBoton() {
        binding.btnIngresar.setOnClickListener {
            intentarLogin()
        }
    }

    private fun intentarLogin() {
        if (estaAutenticando) return

        val usuario = binding.etUsuario.text?.toString()?.trim().orEmpty()
        val clave = binding.etClave.text?.toString()?.trim().orEmpty()

        // Validación básica local antes de llamar a la red
        if (usuario.isBlank()) {
            binding.tilUsuario.error = "Ingresa tu usuario"
            binding.etUsuario.requestFocus()
            return
        }
        if (clave.isBlank()) {
            binding.tilClave.error = "Ingresa tu contraseña"
            binding.etClave.requestFocus()
            return
        }

        binding.tilUsuario.error = null
        binding.tilClave.error = null
        ocultarError()
        cerrarTeclado()
        mostrarCargando(true)

        authRepository.login(usuario, clave) { resultado ->
            mostrarCargando(false)
            when (resultado) {
                is AuthResult.Success -> irAlDashboard()
                is AuthResult.Failure -> mostrarError(resultado.errorMessage)
                is AuthResult.BackendUnavailable -> mostrarError(resultado.detalle)
                is AuthResult.Loading -> { /* no-op */ }
            }
        }
    }

    private fun irAlDashboard() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun mostrarError(mensaje: String) {
        binding.tvError.text = mensaje
        binding.tvError.visibility = View.VISIBLE
    }

    private fun ocultarError() {
        binding.tvError.visibility = View.GONE
        binding.tilUsuario.error = null
        binding.tilClave.error = null
    }

    private fun mostrarCargando(cargando: Boolean) {
        estaAutenticando = cargando
        binding.btnIngresar.isEnabled = !cargando
        binding.btnIngresar.alpha = if (cargando) 0.6f else 1.0f
        binding.layoutCargando.visibility = if (cargando) View.VISIBLE else View.GONE
        binding.etUsuario.isEnabled = !cargando
        binding.etClave.isEnabled = !cargando
    }

    private fun cerrarTeclado() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // Bloquear el botón Atrás — el login es obligatorio
    @Deprecated("Required for API < 33")
    override fun onBackPressed() {
        // No hacer nada — el usuario debe ingresar
    }
}

package com.grupopecuario.gpscamiones

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import com.grupopecuario.gpscamiones.auth.AuthManager
import com.grupopecuario.gpscamiones.databinding.ActivityMainBinding
import com.grupopecuario.gpscamiones.ui.AdminFragment
import com.grupopecuario.gpscamiones.ui.DashboardFragment
import com.grupopecuario.gpscamiones.ui.HistoryFragment
import com.grupopecuario.gpscamiones.ui.LoginDialogFragment
import com.grupopecuario.gpscamiones.ui.MapFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val handler = Handler(Looper.getMainLooper())
    private var esperandoAjustesBackground = false

    // Fragmentos principales
    private val dashboardFragment by lazy { DashboardFragment() }
    private val mapFragment by lazy { MapFragment() }
    private val historyFragment by lazy { HistoryFragment() }
    private val adminFragment by lazy { AdminFragment() }

    private var activeFragment: Fragment? = null

    // Temporizador de refresco periódico (~5 segundos según requerimiento)
    private val actualizadorPeriodico = object : Runnable {
        override fun run() {
            refrescarPantallaActiva()
            handler.postDelayed(this, 5000L)
        }
    }

    // Contrato para permisos de ubicación en primer plano
    private val permisosGPS = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { resultados ->
        val fino = resultados[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val aproximado = resultados[Manifest.permission.ACCESS_COARSE_LOCATION] ?: false

        if (fino) {
            evaluarPermisoSegundoPlanoEIniciar()
        } else if (aproximado) {
            mostrarDialogoExplicativo(
                "Ubicación precisa necesaria",
                "Activa la ubicación precisa para registrar correctamente el recorrido del camión.",
                abrirAjustes = true
            )
        } else {
            mostrarDialogoExplicativo(
                "Permiso de Ubicación Necesario",
                "GPSCamiones necesita acceso a la ubicación precisa para registrar el recorrido del camión.",
                abrirAjustes = true
            )
        }
    }

    // Contrato para permiso de ubicación en segundo plano (Android 10+)
    private val permisoBackground = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        Log.i("GPSCamiones", "Resultado permiso ACCESS_BACKGROUND_LOCATION: $concedido")
        activarSeguimientoDirecto()
    }

    // Contrato para permiso de notificaciones (Android 13+)
    private val permisoNotificacion = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) {
            Log.w("GPSCamiones", "Permiso de notificaciones denegado por el usuario")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configurarVentanaEInsets()
        configurarNavegacion(savedInstanceState)
        solicitarPermisoNotificaciones()
        procesarIntentoAutoInicio(intent)
        exigirSesionIniciada()
    }

    /**
     * Verifica sesión al retomar la activity (ej. después de cerrar sesión desde Admin).
     * Si no hay sesión válida, redirige al LoginActivity.
     */
    fun exigirSesionIniciada() {
        if (AuthManager.getInstance(this).isUserLoggedIn) return
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    /** Refresca la pantalla visible después de un login correcto. */
    fun sesionIniciada() {
        refrescarPantallaActiva()
    }

    private fun configurarVentanaEInsets() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = ContextCompat.getColor(this, R.color.bg_light)
            window.navigationBarColor = ContextCompat.getColor(this, R.color.bg_surface)
        }

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true // Iconos oscuros sobre fondo claro minimalista
            isAppearanceLightNavigationBars = true // Iconos oscuros en barra de navegación clara
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())

            val topInset = maxOf(statusBar.top, cutout.top)
            val bottomInset = maxOf(navBar.bottom, cutout.bottom)

            // Espaciado dinámico superior para no superponer la barra de estado o notch
            binding.navHostContainer.setPadding(0, topInset, 0, 0)

            // Espaciado dinámico inferior para no superponer la píldora de gestos de Android
            binding.bottomNavigation.setPadding(0, 0, 0, bottomInset)

            insets
        }
    }

    private fun configurarNavegacion(savedInstanceState: Bundle?) {
        // Inicializar fragmentos en FragmentManager
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.nav_host_container, dashboardFragment, "dashboard")
                .add(R.id.nav_host_container, mapFragment, "map").hide(mapFragment)
                .add(R.id.nav_host_container, historyFragment, "history").hide(historyFragment)
                .add(R.id.nav_host_container, adminFragment, "admin").hide(adminFragment)
                .commit()
            activeFragment = dashboardFragment
        }

        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_dashboard -> {
                    cambiarFragmento(dashboardFragment)
                    true
                }
                R.id.menu_map -> {
                    cambiarFragmento(mapFragment)
                    true
                }
                R.id.menu_history -> {
                    cambiarFragmento(historyFragment)
                    true
                }
                R.id.menu_admin -> {
                    cambiarFragmento(adminFragment)
                    true
                }
                else -> false
            }
        }
    }

    private fun cambiarFragmento(nuevo: Fragment) {
        if (activeFragment == nuevo) return

        val transaction = supportFragmentManager.beginTransaction()
        activeFragment?.let { transaction.hide(it) }
        transaction.show(nuevo).commit()
        activeFragment = nuevo

        refrescarPantallaActiva()
    }

    fun navegarAMapa() {
        binding.bottomNavigation.selectedItemId = R.id.menu_map
    }

    fun abrirDialogoLogin() {
        val dialog = LoginDialogFragment.newInstance(
            onSuccess = {
                refrescarPantallaActiva()
                adminFragment.actualizarDatos()
                Toast.makeText(this, "Autenticación exitosa", Toast.LENGTH_SHORT).show()
            }
        )
        dialog.show(supportFragmentManager, LoginDialogFragment.TAG)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        procesarIntentoAutoInicio(intent)
    }

    private fun procesarIntentoAutoInicio(intent: Intent?) {
        val autoIniciar = intent?.getBooleanExtra("auto_iniciar", false) ?: false
        if (autoIniciar) {
            Log.i("GPSCamiones", "MainActivity abierta desde aviso de recuperación. Intentando auto-inicio.")
            if (tienePermisoUbicacion()) {
                activarSeguimientoDirecto()
            } else {
                solicitarInicioSeguimiento()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        handler.post(actualizadorPeriodico)
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(GPSLocationService.KEY_ENABLED, false) &&
            (!tienePermisoUbicacion() || !tienePermisoBackground() || !tienePermisoNotificaciones())
        ) {
            esperandoAjustesBackground = false
            mostrarDialogoExplicativo(
                "Revisa los permisos de seguimiento",
                "En Ajustes, activa Ubicación precisa, Permitir todo el tiempo para reanudar después de reiniciar y las Notificaciones para ver los avisos del GPS.",
                abrirAjustes = true
            )
            return
        }
        if (esperandoAjustesBackground) {
            esperandoAjustesBackground = false
            activarSeguimientoDirecto()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(actualizadorPeriodico)
        super.onStop()
    }

    private fun refrescarPantallaActiva() {
        when (activeFragment) {
            dashboardFragment -> dashboardFragment.actualizarDatos()
            mapFragment -> mapFragment.actualizarMapaConDatosSQLite()
            adminFragment -> adminFragment.actualizarDatos()
        }
    }

    // Verificación de Permisos
    fun tienePermisoUbicacion(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun tienePermisoBackground(): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun tienePermisoNotificaciones(): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun solicitarPermisoNotificaciones() {
        if (Build.VERSION.SDK_INT >= 33 && !tienePermisoNotificaciones()) {
            permisoNotificacion.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Flujo de inicio de seguimiento
    fun solicitarInicioSeguimiento() {
        if (tienePermisoUbicacion()) {
            evaluarPermisoSegundoPlanoEIniciar()
        } else {
            permisosGPS.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun evaluarPermisoSegundoPlanoEIniciar() {
        if (Build.VERSION.SDK_INT >= 29 && !tienePermisoBackground()) {
            AlertDialog.Builder(this)
                .setTitle("Recuperación y Rastreo Continuo")
                .setMessage(
                    "Para que el rastreo continúe con la pantalla bloqueada o tras un reinicio del camión, se recomienda autorizar la ubicación 'Permitir todo el tiempo'.\n\n¿Deseas configurarlo ahora?"
                )
                .setPositiveButton("Configurar") { _, _ ->
                    if (Build.VERSION.SDK_INT == 29) {
                        permisoBackground.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    } else {
                        esperandoAjustesBackground = true
                        startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                        )
                    }
                }
                .setNegativeButton("Continuar en primer plano") { _, _ ->
                    activarSeguimientoDirecto()
                }
                .setCancelable(false)
                .show()
        } else {
            activarSeguimientoDirecto()
        }
    }

    private fun activarSeguimientoDirecto() {
        val prefs = getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(GPSLocationService.KEY_ENABLED, true)
            .putString(GPSLocationService.KEY_ESTADO, GPSLocationService.ESTADO_INICIANDO)
            .putString(GPSLocationService.KEY_ESTADO_DETALLE, "Inicio solicitado por el usuario")
            .putLong(GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())
            .apply()

        try {
            val intent = Intent(this, GPSLocationService::class.java).apply {
                action = GPSLocationService.ACTION_START
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "Servicio GPS iniciado en primer plano", Toast.LENGTH_SHORT).show()
        } catch (error: Exception) {
            Log.e("GPSCamiones", "Error al iniciar Foreground Service", error)
            prefs.edit()
                .putBoolean(GPSLocationService.KEY_ENABLED, false)
                .putString(GPSLocationService.KEY_ESTADO, GPSLocationService.ESTADO_ERROR_RECUPERACION)
                .putString(GPSLocationService.KEY_ESTADO_DETALLE, "Error al iniciar: ${error.message}")
                .putLong(GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())
                .apply()
            Toast.makeText(this, "Error al iniciar servicio: ${error.message}", Toast.LENGTH_LONG).show()
        }
        refrescarPantallaActiva()
    }

    // Detención autorizada del servicio
    fun confirmarYDetenerSeguimiento() {
        AlertDialog.Builder(this)
            .setTitle("Detener Seguimiento GPS")
            .setMessage("¿Estás seguro de que deseas detener la telemetría del camión? Se pausará el guardado de posiciones en la base de datos.")
            .setPositiveButton("Sí, Detener") { _, _ ->
                ejecutarDetencionSeguimiento()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    fun mostrarDialogoAutorizacionDetencion() {
        AlertDialog.Builder(this)
            .setTitle("Autorización Requerida")
            .setMessage("Para evitar interrupciones de telemetría no autorizadas por conductores o terceros, se requiere autorización de Supervisor o Administrador para detener el rastreo.")
            .setPositiveButton("Ingresar Credenciales") { _, _ ->
                abrirDialogoLogin()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun ejecutarDetencionSeguimiento() {
        val prefs = getSharedPreferences(GPSLocationService.PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(GPSLocationService.KEY_ENABLED, false)
            .putString(GPSLocationService.KEY_ESTADO, GPSLocationService.ESTADO_DETENIDO)
            .putString(GPSLocationService.KEY_ESTADO_DETALLE, "Detenido por usuario autorizado")
            .putLong(GPSLocationService.KEY_ULTIMO_CAMBIO_ESTADO_MS, System.currentTimeMillis())
            .apply()

        val stopIntent = Intent(this, GPSLocationService::class.java).apply {
            action = GPSLocationService.ACTION_STOP
        }

        try {
            startService(stopIntent)
        } catch (e: Exception) {
            stopService(stopIntent)
        }

        Toast.makeText(this, "Seguimiento GPS detenido", Toast.LENGTH_SHORT).show()
        refrescarPantallaActiva()
    }

    private fun mostrarDialogoExplicativo(
        titulo: String,
        mensaje: String,
        abrirAjustes: Boolean = false
    ) {
        val builder = AlertDialog.Builder(this)
            .setTitle(titulo)
            .setMessage(mensaje)
            .setPositiveButton("Aceptar") { _, _ ->
                if (abrirAjustes) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                }
            }

        if (abrirAjustes) {
            builder.setNegativeButton("Cancelar", null)
        }
        builder.show()
    }

    override fun onDestroy() {
        handler.removeCallbacks(actualizadorPeriodico)
        super.onDestroy()
    }
}

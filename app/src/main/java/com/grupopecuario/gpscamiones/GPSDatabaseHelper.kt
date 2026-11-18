package com.grupopecuario.gpscamiones

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.location.Location
import androidx.core.location.LocationCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class GPSDatabaseHelper(context: Context) :
    SQLiteOpenHelper(
        context.applicationContext,
        "gps_camiones.db",
        null,
        3
    ) {

    companion object {
        private const val TABLA = "ubicaciones"
        private const val TABLA_EVENTOS = "eventos"
        // Solo se borra lo que la web ya confirmó; se conserva 3 días como respaldo.
        // Lo no enviado nunca se borra, aunque pasen días sin señal.
        private const val RETENCION_DIAS = 3L
        private const val MAXIMO_REGISTROS = 100_000
        private const val INTERVALO_LIMPIEZA = 100
        private val insercionesDesdeLimpieza = AtomicInteger(0)

        @Volatile
        private var instance: GPSDatabaseHelper? = null

        fun getInstance(context: Context): GPSDatabaseHelper {
            return instance ?: synchronized(this) {
                instance ?: GPSDatabaseHelper(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    // Crear la base de datos cuando no existe
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ubicaciones (
                id TEXT PRIMARY KEY,
                latitud REAL NOT NULL,
                longitud REAL NOT NULL,
                precision_m REAL NOT NULL,
                fecha_gps_utc INTEGER NOT NULL,
                fecha_registro_utc INTEGER NOT NULL,
                velocidad_m_s REAL,
                es_simulada INTEGER NOT NULL,
                sincronizado INTEGER NOT NULL DEFAULT 0,
                provider_unit_id INTEGER,
                placa TEXT,
                sin_conexion INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        crearIndices(db)
        crearTablaEventos(db)
    }

    // No eliminar ni modificar registros existentes
    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        if (oldVersion < 2) {
            crearIndices(db)
        }
        if (oldVersion < 3) {
            // Las posiciones antiguas quedan sin camión (NULL) y se envían con el actual
            db.execSQL("ALTER TABLE $TABLA ADD COLUMN provider_unit_id INTEGER")
            db.execSQL("ALTER TABLE $TABLA ADD COLUMN placa TEXT")
            db.execSQL("ALTER TABLE $TABLA ADD COLUMN sin_conexion INTEGER NOT NULL DEFAULT 0")
            crearTablaEventos(db)
        }
    }

    /**
     * Eventos operativos (inicio/fin de parada, entrada/salida de geocerca).
     * Igual que las posiciones, se guardan primero aquí y se suben cuando hay señal.
     */
    private fun crearTablaEventos(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLA_EVENTOS (
                id TEXT PRIMARY KEY,
                tipo TEXT NOT NULL,
                fecha_utc INTEGER NOT NULL,
                latitud REAL NOT NULL,
                longitud REAL NOT NULL,
                tipo_parada TEXT,
                datos_json TEXT NOT NULL,
                provider_unit_id INTEGER NOT NULL,
                placa TEXT NOT NULL,
                sin_conexion INTEGER NOT NULL DEFAULT 0,
                sincronizado INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_eventos_sync ON $TABLA_EVENTOS(sincronizado, fecha_utc)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_eventos_fecha ON $TABLA_EVENTOS(fecha_utc DESC)")
    }

    fun guardarEvento(evento: RegistroEvento) {
        val valores = ContentValues().apply {
            put("id", evento.id)
            put("tipo", evento.tipo)
            put("fecha_utc", evento.fechaUtc)
            put("latitud", evento.latitud)
            put("longitud", evento.longitud)
            put("tipo_parada", evento.tipoParada)
            put("datos_json", evento.datosJson)
            put("provider_unit_id", evento.providerUnitId)
            put("placa", evento.placa)
            put("sin_conexion", if (evento.sinConexion) 1 else 0)
            put("sincronizado", 0)
        }
        writableDatabase.insertWithOnConflict(TABLA_EVENTOS, null, valores, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun obtenerEventosPendientes(limite: Int = 100): List<RegistroEvento> {
        val lista = mutableListOf<RegistroEvento>()
        readableDatabase.query(
            TABLA_EVENTOS,
            arrayOf("id", "tipo", "fecha_utc", "latitud", "longitud", "tipo_parada", "datos_json", "provider_unit_id", "placa", "sin_conexion"),
            "sincronizado = 0", null, null, null,
            "fecha_utc ASC, rowid ASC",
            limite.coerceIn(1, 200).toString()
        ).use { c ->
            while (c.moveToNext()) {
                lista += RegistroEvento(
                    id = c.getString(0),
                    tipo = c.getString(1),
                    fechaUtc = c.getLong(2),
                    latitud = c.getDouble(3),
                    longitud = c.getDouble(4),
                    tipoParada = if (c.isNull(5)) null else c.getString(5),
                    datosJson = c.getString(6),
                    providerUnitId = c.getInt(7),
                    placa = c.getString(8),
                    sinConexion = c.getInt(9) == 1
                )
            }
        }
        return lista
    }

    fun marcarEventosSincronizados(ids: List<String>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.chunked(100).forEach { lote ->
                val placeholders = lote.joinToString(",") { "?" }
                writableDatabase.execSQL(
                    "UPDATE $TABLA_EVENTOS SET sincronizado = 1 WHERE id IN ($placeholders)",
                    lote.toTypedArray()
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun contarEventosPendientes(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLA_EVENTOS WHERE sincronizado = 0", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }

    /** Paradas finalizadas desde [desdeUtc], agrupadas por tipo (POSIBLE_ENTREGA, TRAFICO...). */
    fun contarParadasPorTipo(desdeUtc: Long): Map<String, Int> {
        val resultado = mutableMapOf<String, Int>()
        readableDatabase.rawQuery(
            """
            SELECT tipo_parada, COUNT(*) FROM $TABLA_EVENTOS
            WHERE tipo = 'STOP_END' AND fecha_utc >= ? AND tipo_parada IS NOT NULL
            GROUP BY tipo_parada
            """.trimIndent(),
            arrayOf(desdeUtc.toString())
        ).use { c ->
            while (c.moveToNext()) resultado[c.getString(0)] = c.getInt(1)
        }
        return resultado
    }

    private fun crearIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ubicaciones_fecha ON $TABLA(fecha_gps_utc DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ubicaciones_sync_fecha ON $TABLA(sincronizado, fecha_gps_utc)")
    }

    // Guardar una nueva ubicación GPS
    fun guardarUbicacion(
        location: Location,
        providerUnitId: Int? = null,
        placa: String? = null,
        sinConexion: Boolean = false
    ): Long {
        val valores = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
            // Camión al que pertenecía el celular al momento de la lectura: si la web
            // reasigna el celular mientras estaba sin señal, no se mezclan recorridos.
            if (providerUnitId != null) put("provider_unit_id", providerUnitId) else putNull("provider_unit_id")
            if (placa != null) put("placa", placa) else putNull("placa")
            put("sin_conexion", if (sinConexion) 1 else 0)
            put("latitud", location.latitude)
            put("longitud", location.longitude)
            put("precision_m", location.accuracy)
            // Timestamp original de Android
            put("fecha_gps_utc", location.time)
            // Momento en que se registra en SQLite
            put("fecha_registro_utc", System.currentTimeMillis())
            // Velocidad en metros por segundo
            if (location.hasSpeed()) {
                put("velocidad_m_s", location.speed)
            } else {
                putNull("velocidad_m_s")
            }
            // Detección de ubicación simulada
            put("es_simulada", if (LocationCompat.isMock(location)) 1 else 0)
            // Pendiente de sincronización con la web
            put("sincronizado", 0)
        }

        val id = writableDatabase.insertOrThrow(
            TABLA,
            null,
            valores
        )
        if (insercionesDesdeLimpieza.incrementAndGet() >= INTERVALO_LIMPIEZA) {
            insercionesDesdeLimpieza.set(0)
            limpiarHistorial()
        }
        return id
    }

    private fun limpiarHistorial() {
        val limiteFecha = System.currentTimeMillis() - RETENCION_DIAS * 24 * 60 * 60 * 1000
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLA, "sincronizado = 1 AND fecha_registro_utc < ?", arrayOf(limiteFecha.toString()))
            writableDatabase.delete(TABLA_EVENTOS, "sincronizado = 1 AND fecha_utc < ?", arrayOf(limiteFecha.toString()))
            writableDatabase.execSQL(
                """
                DELETE FROM $TABLA
                WHERE sincronizado = 1 AND rowid IN (
                    SELECT rowid FROM $TABLA
                    ORDER BY fecha_gps_utc DESC, rowid DESC
                    LIMIT -1 OFFSET $MAXIMO_REGISTROS
                )
                """.trimIndent()
            )
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // Contar todas las ubicaciones almacenadas
    fun contarUbicaciones(): Int {
        readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM $TABLA",
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    // Contar registros pendientes de sincronización
    fun contarPendientes(): Int {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM $TABLA
            WHERE sincronizado = 0
            """.trimIndent(),
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    fun obtenerPendientesSincronizacion(limite: Int = 50): List<RegistroUbicacion> {
        val lista = mutableListOf<RegistroUbicacion>()
        readableDatabase.query(
            TABLA,
            arrayOf("id", "latitud", "longitud", "precision_m", "fecha_gps_utc", "fecha_registro_utc", "velocidad_m_s", "es_simulada", "sincronizado", "provider_unit_id", "placa", "sin_conexion"),
            "sincronizado = 0",
            null,
            null,
            null,
            "fecha_gps_utc ASC, rowid ASC",
            limite.coerceIn(1, 100).toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista.add(
                    RegistroUbicacion(
                        id = cursor.getString(0),
                        latitud = cursor.getDouble(1),
                        longitud = cursor.getDouble(2),
                        precision = cursor.getFloat(3),
                        fechaGPS = cursor.getLong(4),
                        fechaRegistro = cursor.getLong(5),
                        velocidad = if (cursor.isNull(6)) null else cursor.getFloat(6),
                        esSimulada = cursor.getInt(7) == 1,
                        sincronizado = cursor.getInt(8) == 1,
                        providerUnitId = if (cursor.isNull(9)) null else cursor.getInt(9),
                        placa = if (cursor.isNull(10)) null else cursor.getString(10),
                        sinConexion = cursor.getInt(11) == 1
                    )
                )
            }
        }
        return lista
    }

    fun marcarSincronizados(ids: List<String>) {
        if (ids.isEmpty()) return
        writableDatabase.beginTransaction()
        try {
            ids.chunked(100).forEach { lote ->
                val placeholders = lote.joinToString(",") { "?" }
                writableDatabase.execSQL(
                    "UPDATE $TABLA SET sincronizado = 1 WHERE id IN ($placeholders)",
                    lote.toTypedArray()
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    // Obtener la última ubicación registrada
    fun obtenerUltimaUbicacion(): UltimaUbicacion? {
        readableDatabase.rawQuery(
            """
            SELECT
                latitud,
                longitud,
                precision_m,
                fecha_gps_utc,
                fecha_registro_utc,
                velocidad_m_s,
                es_simulada,
                sincronizado
            FROM $TABLA
            ORDER BY fecha_gps_utc DESC, rowid DESC
            LIMIT 1
            """.trimIndent(),
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null

            return UltimaUbicacion(
                latitud = cursor.getDouble(0),
                longitud = cursor.getDouble(1),
                precision = cursor.getFloat(2),
                fechaGPS = cursor.getLong(3),
                fechaRegistro = cursor.getLong(4),
                velocidad = if (cursor.isNull(5)) null else cursor.getFloat(5),
                esSimulada = cursor.getInt(6) == 1,
                sincronizado = cursor.getInt(7) == 1
            )
        }
    }

    // Obtener las últimas ubicaciones para trayectoria en mapa (orden cronológico ascendente)
    fun obtenerTrayectoriaReciente(limite: Int = 100): List<RegistroUbicacion> {
        val lista = mutableListOf<RegistroUbicacion>()
        readableDatabase.rawQuery(
            """
            SELECT id, latitud, longitud, precision_m, fecha_gps_utc, fecha_registro_utc, velocidad_m_s, es_simulada, sincronizado
            FROM (
                SELECT rowid, id, latitud, longitud, precision_m, fecha_gps_utc, fecha_registro_utc, velocidad_m_s, es_simulada, sincronizado
                FROM $TABLA
                ORDER BY fecha_gps_utc DESC, rowid DESC
                LIMIT ?
            )
            ORDER BY fecha_gps_utc ASC, rowid ASC
            """.trimIndent(),
            arrayOf(limite.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista.add(
                    RegistroUbicacion(
                        id = cursor.getString(0),
                        latitud = cursor.getDouble(1),
                        longitud = cursor.getDouble(2),
                        precision = cursor.getFloat(3),
                        fechaGPS = cursor.getLong(4),
                        fechaRegistro = cursor.getLong(5),
                        velocidad = if (cursor.isNull(6)) null else cursor.getFloat(6),
                        esSimulada = cursor.getInt(7) == 1,
                        sincronizado = cursor.getInt(8) == 1
                    )
                )
            }
        }
        return lista
    }

    // Obtener registros para el módulo de Historial con filtrado por rango de fechas (UTC millis)
    fun obtenerRegistrosPorRango(
        inicioUtc: Long,
        finUtc: Long,
        limite: Int = 300
    ): List<RegistroUbicacion> {
        val lista = mutableListOf<RegistroUbicacion>()
        readableDatabase.rawQuery(
            """
            SELECT id, latitud, longitud, precision_m, fecha_gps_utc, fecha_registro_utc, velocidad_m_s, es_simulada, sincronizado
            FROM $TABLA
            WHERE fecha_gps_utc >= ? AND fecha_gps_utc <= ?
            ORDER BY fecha_gps_utc DESC, rowid DESC
            LIMIT ?
            """.trimIndent(),
            arrayOf(inicioUtc.toString(), finUtc.toString(), limite.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                lista.add(
                    RegistroUbicacion(
                        id = cursor.getString(0),
                        latitud = cursor.getDouble(1),
                        longitud = cursor.getDouble(2),
                        precision = cursor.getFloat(3),
                        fechaGPS = cursor.getLong(4),
                        fechaRegistro = cursor.getLong(5),
                        velocidad = if (cursor.isNull(6)) null else cursor.getFloat(6),
                        esSimulada = cursor.getInt(7) == 1,
                        sincronizado = cursor.getInt(8) == 1
                    )
                )
            }
        }
        return lista
    }

    // Contar registros dentro de un rango específico de fechas
    fun contarRegistrosPorRango(inicioUtc: Long, finUtc: Long): Int {
        readableDatabase.rawQuery(
            """
            SELECT COUNT(*)
            FROM $TABLA
            WHERE fecha_gps_utc >= ? AND fecha_gps_utc <= ?
            """.trimIndent(),
            arrayOf(inicioUtc.toString(), finUtc.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst()) return 0
            return cursor.getInt(0)
        }
    }

    // Estadísticas detalladas de la base de datos para diagnóstico y módulo SQLite
    fun obtenerEstadisticasDB(): DBEstadisticas {
        var total = 0
        var pendientes = 0
        var sincronizados = 0
        var minFecha: Long? = null
        var maxFecha: Long? = null

        readableDatabase.rawQuery(
            """
            SELECT 
                COUNT(*),
                SUM(CASE WHEN sincronizado = 0 THEN 1 ELSE 0 END),
                SUM(CASE WHEN sincronizado = 1 THEN 1 ELSE 0 END),
                MIN(fecha_gps_utc),
                MAX(fecha_gps_utc)
            FROM $TABLA
            """.trimIndent(),
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                total = cursor.getInt(0)
                pendientes = if (cursor.isNull(1)) 0 else cursor.getInt(1)
                sincronizados = if (cursor.isNull(2)) 0 else cursor.getInt(2)
                minFecha = if (cursor.isNull(3)) null else cursor.getLong(3)
                maxFecha = if (cursor.isNull(4)) null else cursor.getLong(4)
            }
        }

        return DBEstadisticas(
            totalRegistros = total,
            pendientesSync = pendientes,
            sincronizados = sincronizados,
            primerRegistroUtc = minFecha,
            ultimoRegistroUtc = maxFecha,
            esModoWAL = readableDatabase.isWriteAheadLoggingEnabled
        )
    }
}

// Modelo de datos de la última posición GPS (conservado para compatibilidad exacta)
data class UltimaUbicacion(
    val latitud: Double,
    val longitud: Double,
    val precision: Float,
    val fechaGPS: Long,
    val fechaRegistro: Long,
    val velocidad: Float?,
    val esSimulada: Boolean,
    val sincronizado: Boolean
)

// Modelo general de un registro de telemetría GPS
data class RegistroUbicacion(
    val id: String,
    val latitud: Double,
    val longitud: Double,
    val precision: Float,
    val fechaGPS: Long,
    val fechaRegistro: Long,
    val velocidad: Float?,
    val esSimulada: Boolean,
    val sincronizado: Boolean,
    val providerUnitId: Int? = null,
    val placa: String? = null,
    val sinConexion: Boolean = false
)

// Evento operativo pendiente de subir a la web (parada o geocerca)
data class RegistroEvento(
    val id: String,
    /** STOP_START, STOP_END, GEOFENCE_ENTER, GEOFENCE_EXIT */
    val tipo: String,
    val fechaUtc: Long,
    val latitud: Double,
    val longitud: Double,
    /** POSIBLE_ENTREGA, TRAFICO, NO_PROGRAMADA, BASE (solo en STOP_END) */
    val tipoParada: String?,
    /** Campos adicionales en JSON, se reenvían tal cual a la web */
    val datosJson: String,
    val providerUnitId: Int,
    val placa: String,
    val sinConexion: Boolean
)

// Resumen estadístico del almacenamiento SQLite
data class DBEstadisticas(
    val totalRegistros: Int,
    val pendientesSync: Int,
    val sincronizados: Int,
    val primerRegistroUtc: Long?,
    val ultimoRegistroUtc: Long?,
    val esModoWAL: Boolean
)

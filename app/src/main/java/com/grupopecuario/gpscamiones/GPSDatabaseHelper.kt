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
        2
    ) {

    companion object {
        private const val TABLA = "ubicaciones"
        private const val RETENCION_DIAS = 30L
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
                sincronizado INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        crearIndices(db)
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
    }

    private fun crearIndices(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ubicaciones_fecha ON $TABLA(fecha_gps_utc DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_ubicaciones_sync_fecha ON $TABLA(sincronizado, fecha_gps_utc)")
    }

    // Guardar una nueva ubicación GPS
    fun guardarUbicacion(location: Location): Long {
        val valores = ContentValues().apply {
            put("id", UUID.randomUUID().toString())
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
            writableDatabase.delete(TABLA, "fecha_registro_utc < ?", arrayOf(limiteFecha.toString()))
            writableDatabase.execSQL(
                """
                DELETE FROM $TABLA
                WHERE rowid IN (
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
            arrayOf("id", "latitud", "longitud", "precision_m", "fecha_gps_utc", "fecha_registro_utc", "velocidad_m_s", "es_simulada", "sincronizado"),
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
                        sincronizado = cursor.getInt(8) == 1
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
    val sincronizado: Boolean
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

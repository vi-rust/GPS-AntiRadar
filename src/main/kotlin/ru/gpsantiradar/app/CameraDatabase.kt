package ru.gpsantiradar.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.*
import java.io.IOException
import java.io.InputStream
import kotlin.math.*

class CameraDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cameras (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,type INTEGER NOT NULL, dir_type INTEGER NOT NULL, direction REAL NOT NULL,distance_meters INTEGER NOT NULL, reverse_distance_meters INTEGER NOT NULL,angle_degrees REAL NOT NULL, rank REAL NOT NULL, newbie INTEGER NOT NULL,speed_rules TEXT NOT NULL)")
        db.execSQL("CREATE INDEX cameras_bounds ON cameras(lat, lon)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS cameras")
        db.execSQL("DROP TABLE IF EXISTS metadata")
        onCreate(db)
    }

    @Throws(IOException::class)
    fun importRadarBase(input: InputStream): RadarBaseParser.Result {
        val db = writableDatabase
        db.beginTransactionNonExclusive()
        try {
            db.delete("cameras", null, null)
            db.delete("metadata", null, null)
            val insert = db.compileStatement("INSERT INTO cameras(id,lat,lon,type,dir_type,direction,distance_meters,reverse_distance_meters,angle_degrees,rank,newbie,speed_rules) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
            val result = try {
                RadarBaseParser.parse(input) { point ->
                    insert.clearBindings()
                    insert.bindLong(1, point.id); insert.bindDouble(2, point.latitude); insert.bindDouble(3, point.longitude)
                    insert.bindLong(4, point.type.toLong()); insert.bindLong(5, point.dirType.toLong()); insert.bindDouble(6, point.direction.toDouble())
                    insert.bindLong(7, point.distanceMeters.toLong()); insert.bindLong(8, point.reverseDistanceMeters.toLong())
                    insert.bindDouble(9, point.angleDegrees.toDouble()); insert.bindDouble(10, point.rank.toDouble())
                    insert.bindLong(11, if (point.newbie) 1 else 0); insert.bindString(12, point.speedRules); insert.executeInsert()
                }
            } finally { insert.close() }
            putMetadata(db, "schema", result.schemaVersion)
            putMetadata(db, "export_date", result.exportDate)
            putMetadata(db, "build", result.build?.toString() ?: "")
            putMetadata(db, "coordinates_corrected", if (result.coordinatesCorrected) "1" else "0")
            db.setTransactionSuccessful()
            return result
        } finally { db.endTransaction() }
    }

    fun exportDate(): String = readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='export_date'", null).use {
        if (it.moveToFirst()) it.getString(0) else ""
    }
    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM cameras", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }
    fun nearby(lat: Double, lon: Double, radiusMeters: Double): List<CameraPoint>? {
        val latDelta = radiusMeters / 111320.0
        val cosLatitude = max(0.15, cos(Math.toRadians(lat)))
        val lonDelta = radiusMeters / (111320.0 * cosLatitude)
        val args = arrayOf((lat - latDelta).toString(), (lat + latDelta).toString(), (lon - lonDelta).toString(), (lon + lonDelta).toString())
        return try { query(cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", args) }
        catch (_: SQLiteDatabaseLockedException) { null }
        catch (_: SQLiteTableLockedException) { null }
    }
    fun bounds(): DoubleArray? = readableDatabase.rawQuery("SELECT MIN(lat),MAX(lat),MIN(lon),MAX(lon) FROM cameras", null).use {
        if (!it.moveToFirst() || it.isNull(0)) null else doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
    }
    fun withinBounds(south: Double, north: Double, west: Double, east: Double, limit: Int): List<CameraPoint> = query(
        cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT ?",
        arrayOf(south.toString(), north.toString(), west.toString(), east.toString(), limit.toString())
    )
    private fun query(sql: String, args: Array<String>): List<CameraPoint> = readableDatabase.rawQuery(sql, args).use { cursor ->
        buildList { while (cursor.moveToNext()) add(readCamera(cursor)) }
    }

    companion object {
        private const val DB_NAME = "speedcams.db"
        private const val DB_VERSION = 4
        private fun putMetadata(db: SQLiteDatabase, key: String, value: String?) = db.insertOrThrow(
            "metadata", null, ContentValues().apply { put("key", key); put("value", value ?: "") }
        )
        private fun cameraColumns() = "SELECT id,lat,lon,type,dir_type,direction,distance_meters,reverse_distance_meters,angle_degrees,rank,newbie,speed_rules "
        private fun readCamera(c: Cursor) = CameraPoint().apply {
            id = c.getLong(0); latitude = c.getDouble(1); longitude = c.getDouble(2); type = c.getInt(3)
            dirType = c.getInt(4); direction = c.getFloat(5); distanceMeters = c.getInt(6); reverseDistanceMeters = c.getInt(7)
            angleDegrees = c.getFloat(8); rank = c.getFloat(9); newbie = c.getInt(10) != 0; speedRules = c.getString(11)
        }
    }
}

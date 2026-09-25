package ru.gpsantiradar.app

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.*
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import kotlin.math.*

class CameraDatabase(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION),
    Closeable {
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        createRadarBaseTables(db)
        createUserObjectsTable(db)
    }

    private fun createRadarBaseTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE cameras (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,type INTEGER NOT NULL, dir_type INTEGER NOT NULL, direction REAL NOT NULL,distance_meters INTEGER NOT NULL, reverse_distance_meters INTEGER NOT NULL,angle_degrees REAL NOT NULL, rank REAL NOT NULL, newbie INTEGER NOT NULL,speed_rules TEXT NOT NULL)")
        db.execSQL("CREATE INDEX cameras_bounds ON cameras(lat, lon)")
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
    }

    private fun createUserObjectsTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS user_objects (id INTEGER PRIMARY KEY AUTOINCREMENT, lat REAL NOT NULL, lon REAL NOT NULL,type INTEGER NOT NULL, dir_type INTEGER NOT NULL, direction REAL NOT NULL,distance_meters INTEGER NOT NULL, reverse_distance_meters INTEGER NOT NULL,angle_degrees REAL NOT NULL, rank REAL NOT NULL, newbie INTEGER NOT NULL,speed_rules TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS user_objects_bounds ON user_objects(lat, lon)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 4) {
            db.execSQL("DROP TABLE IF EXISTS cameras")
            db.execSQL("DROP TABLE IF EXISTS metadata")
            createRadarBaseTables(db)
        }
        if (oldVersion < 5) createUserObjectsTable(db)
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Recover databases whose version was advanced before the optional table was created.
        if (!db.isReadOnly) createUserObjectsTable(db)
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
    fun count(): Int = readableDatabase.rawQuery(
        "SELECT (SELECT COUNT(*) FROM cameras) + (SELECT COUNT(*) FROM user_objects)",
        null,
    ).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun userObjectCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM user_objects", null).use {
        if (it.moveToFirst()) it.getInt(0) else 0
    }

    fun addUserObject(point: CameraPoint): CameraPoint {
        validateUserObject(point)
        val values = cameraValues(point)
        val localId = writableDatabase.insertOrThrow("user_objects", null, values)
        return point.apply {
            id = USER_OBJECT_ID_BASE + localId
            userDefined = true
        }
    }

    fun updateUserObject(point: CameraPoint): Boolean {
        validateUserObject(point)
        val localId = point.id - USER_OBJECT_ID_BASE
        if (!point.userDefined || localId <= 0L) return false
        return writableDatabase.update(
            "user_objects",
            cameraValues(point),
            "id=?",
            arrayOf(localId.toString()),
        ) > 0
    }

    fun deleteUserObject(id: Long): Boolean {
        val localId = id - USER_OBJECT_ID_BASE
        if (localId <= 0L) return false
        return writableDatabase.delete("user_objects", "id=?", arrayOf(localId.toString())) > 0
    }

    fun userObjects(): List<CameraPoint> = query(
        userObjectColumns() + "FROM user_objects ORDER BY id DESC",
        emptyArray(),
        true,
    )

    fun nearby(lat: Double, lon: Double, radiusMeters: Double): List<CameraPoint>? {
        val latDelta = radiusMeters / 111320.0
        val cosLatitude = max(0.15, cos(Math.toRadians(lat)))
        val lonDelta = radiusMeters / (111320.0 * cosLatitude)
        val args = arrayOf((lat - latDelta).toString(), (lat + latDelta).toString(), (lon - lonDelta).toString(), (lon + lonDelta).toString())
        return try {
            query(userObjectColumns() + "FROM user_objects WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", args, true) +
                query(cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?", args, false)
        }
        catch (_: SQLiteDatabaseLockedException) { null }
        catch (_: SQLiteTableLockedException) { null }
    }
    fun bounds(): DoubleArray? = readableDatabase.rawQuery(
        "SELECT MIN(lat),MAX(lat),MIN(lon),MAX(lon) FROM (SELECT lat,lon FROM cameras UNION ALL SELECT lat,lon FROM user_objects)",
        null,
    ).use {
        if (!it.moveToFirst() || it.isNull(0)) null else doubleArrayOf(it.getDouble(0), it.getDouble(1), it.getDouble(2), it.getDouble(3))
    }
    fun withinBounds(south: Double, north: Double, west: Double, east: Double, limit: Int): List<CameraPoint> {
        if (limit <= 0) return emptyList()
        val boundsArgs = arrayOf(south.toString(), north.toString(), west.toString(), east.toString())
        val userObjects = query(
            userObjectColumns() + "FROM user_objects WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT $limit",
            boundsArgs,
            true,
        )
        val remaining = limit - userObjects.size
        if (remaining <= 0) return userObjects
        return userObjects + query(
            cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT $remaining",
            boundsArgs,
            false,
        )
    }

    private fun query(sql: String, args: Array<String>, userDefined: Boolean): List<CameraPoint> =
        readableDatabase.rawQuery(sql, args).use { cursor ->
            buildList { while (cursor.moveToNext()) add(readCamera(cursor, userDefined)) }
        }

    private fun cameraValues(point: CameraPoint) = ContentValues().apply {
        put("lat", point.latitude); put("lon", point.longitude); put("type", point.type)
        put("dir_type", point.dirType); put("direction", point.direction)
        put("distance_meters", point.distanceMeters); put("reverse_distance_meters", point.reverseDistanceMeters)
        put("angle_degrees", point.angleDegrees); put("rank", point.rank)
        put("newbie", if (point.newbie) 1 else 0); put("speed_rules", point.speedRules)
    }

    private fun validateUserObject(point: CameraPoint) {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0) { "Invalid latitude" }
        require(point.longitude.isFinite() && point.longitude in -180.0..180.0) { "Invalid longitude" }
        require(point.type >= 0) { "Invalid object type" }
        require(point.dirType in 0..4) { "Invalid direction type" }
        require(point.direction.isFinite() && point.direction >= 0f && point.direction < 360f) { "Invalid direction" }
        require(point.distanceMeters in 50..1500) { "Invalid alert distance" }
        require(point.reverseDistanceMeters in 0..1500) { "Invalid reverse distance" }
        require(point.angleDegrees.isFinite() && point.angleDegrees in 0f..180f) { "Invalid sector angle" }
    }

    companion object {
        private const val DB_NAME = "speedcams.db"
        private const val DB_VERSION = 5
        private const val USER_OBJECT_ID_BASE = 4_000_000_000_000_000_000L
        private fun putMetadata(db: SQLiteDatabase, key: String, value: String?) = db.insertOrThrow(
            "metadata", null, ContentValues().apply { put("key", key); put("value", value ?: "") }
        )
        private fun cameraColumns() = "SELECT id,lat,lon,type,dir_type,direction,distance_meters,reverse_distance_meters,angle_degrees,rank,newbie,speed_rules "
        private fun userObjectColumns() = "SELECT id+$USER_OBJECT_ID_BASE,lat,lon,type,dir_type,direction,distance_meters,reverse_distance_meters,angle_degrees,rank,newbie,speed_rules "
        private fun readCamera(c: Cursor, isUserDefined: Boolean) = CameraPoint().apply {
            id = c.getLong(0); latitude = c.getDouble(1); longitude = c.getDouble(2); type = c.getInt(3)
            dirType = c.getInt(4); direction = c.getFloat(5); distanceMeters = c.getInt(6); reverseDistanceMeters = c.getInt(7)
            angleDegrees = c.getFloat(8); rank = c.getFloat(9); newbie = c.getInt(10) != 0; speedRules = c.getString(11)
            userDefined = isUserDefined
        }
    }
}

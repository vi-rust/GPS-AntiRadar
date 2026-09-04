package ru.gpsantiradar.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
import android.database.sqlite.SQLiteTableLockedException;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public final class CameraDatabase extends SQLiteOpenHelper {
    private static final String DB_NAME = "speedcams.db";
    private static final int DB_VERSION = 4;

    public CameraDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE cameras (" +
                "id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL," +
                "type INTEGER NOT NULL, dir_type INTEGER NOT NULL, direction REAL NOT NULL," +
                "distance_meters INTEGER NOT NULL, reverse_distance_meters INTEGER NOT NULL," +
                "angle_degrees REAL NOT NULL, rank REAL NOT NULL, newbie INTEGER NOT NULL," +
                "speed_rules TEXT NOT NULL)");
        db.execSQL("CREATE INDEX cameras_bounds ON cameras(lat, lon)");
        db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS cameras");
        db.execSQL("DROP TABLE IF EXISTS metadata");
        onCreate(db);
    }

    public RadarBaseParser.Result importRadarBase(InputStream input) throws IOException {
        final SQLiteDatabase db = getWritableDatabase();
        db.beginTransactionNonExclusive();
        try {
            db.delete("cameras", null, null);
            db.delete("metadata", null, null);
            final SQLiteStatement insert = db.compileStatement(
                    "INSERT INTO cameras(id,lat,lon,type,dir_type,direction,distance_meters," +
                            "reverse_distance_meters,angle_degrees,rank,newbie,speed_rules)" +
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)");
            RadarBaseParser.Result result;
            try {
                result = RadarBaseParser.parse(input, point -> {
                    insert.clearBindings();
                    insert.bindLong(1, point.id);
                    insert.bindDouble(2, point.latitude);
                    insert.bindDouble(3, point.longitude);
                    insert.bindLong(4, point.type);
                    insert.bindLong(5, point.dirType);
                    insert.bindDouble(6, point.direction);
                    insert.bindLong(7, point.distanceMeters);
                    insert.bindLong(8, point.reverseDistanceMeters);
                    insert.bindDouble(9, point.angleDegrees);
                    insert.bindDouble(10, point.rank);
                    insert.bindLong(11, point.newbie ? 1 : 0);
                    insert.bindString(12, point.speedRules == null ? "" : point.speedRules);
                    insert.executeInsert();
                });
            } finally {
                insert.close();
            }
            putMetadata(db, "schema", result.schemaVersion);
            putMetadata(db, "export_date", result.exportDate);
            putMetadata(db, "build", result.build == null ? "" : result.build.toString());
            putMetadata(db, "coordinates_corrected",
                    result.coordinatesCorrected ? "1" : "0");
            db.setTransactionSuccessful();
            return result;
        } finally {
            db.endTransaction();
        }
    }

    private static void putMetadata(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key);
        values.put("value", value == null ? "" : value);
        db.insertOrThrow("metadata", null, values);
    }

    public String exportDate() {
        Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT value FROM metadata WHERE key='export_date'", null);
        try { return cursor.moveToFirst() ? cursor.getString(0) : ""; }
        finally { cursor.close(); }
    }

    public int count() {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM cameras", null);
        try { return cursor.moveToFirst() ? cursor.getInt(0) : 0; }
        finally { cursor.close(); }
    }

    public List<CameraPoint> nearby(double lat, double lon, double radiusMeters) {
        double latDelta = radiusMeters / 111320.0;
        double cos = Math.max(0.15, Math.cos(Math.toRadians(lat)));
        double lonDelta = radiusMeters / (111320.0 * cos);
        String[] args = { Double.toString(lat - latDelta), Double.toString(lat + latDelta),
                Double.toString(lon - lonDelta), Double.toString(lon + lonDelta) };
        try {
            return query(cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? " +
                    "AND lon BETWEEN ? AND ?", args);
        } catch (SQLiteDatabaseLockedException | SQLiteTableLockedException contention) {
            return null;
        }
    }

    public double[] bounds() {
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT MIN(lat),MAX(lat),MIN(lon),MAX(lon) FROM cameras", null);
        try {
            if (!c.moveToFirst() || c.isNull(0)) return null;
            return new double[]{c.getDouble(0), c.getDouble(1), c.getDouble(2), c.getDouble(3)};
        } finally { c.close(); }
    }

    public List<CameraPoint> withinBounds(double south, double north,
                                          double west, double east, int limit) {
        String[] args = { Double.toString(south), Double.toString(north),
                Double.toString(west), Double.toString(east), Integer.toString(limit) };
        return query(cameraColumns() + "FROM cameras WHERE lat BETWEEN ? AND ? " +
                "AND lon BETWEEN ? AND ? LIMIT ?", args);
    }

    private List<CameraPoint> query(String sql, String[] args) {
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        List<CameraPoint> result = new ArrayList<>();
        try { while (c.moveToNext()) result.add(readCamera(c)); }
        finally { c.close(); }
        return result;
    }

    private static String cameraColumns() {
        return "SELECT id,lat,lon,type,dir_type,direction,distance_meters," +
                "reverse_distance_meters,angle_degrees,rank,newbie,speed_rules ";
    }

    private static CameraPoint readCamera(Cursor c) {
        CameraPoint point = new CameraPoint();
        point.id = c.getLong(0);
        point.latitude = c.getDouble(1);
        point.longitude = c.getDouble(2);
        point.type = c.getInt(3);
        point.dirType = c.getInt(4);
        point.direction = c.getFloat(5);
        point.distanceMeters = c.getInt(6);
        point.reverseDistanceMeters = c.getInt(7);
        point.angleDegrees = c.getFloat(8);
        point.rank = c.getFloat(9);
        point.newbie = c.getInt(10) != 0;
        point.speedRules = c.getString(11);
        return point;
    }
}

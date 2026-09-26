package ru.gpsantiradar.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CameraDatabaseUserObjectsTest {
    private lateinit var context: Context

    @Before
    fun clearDatabase() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun cleanUpDatabase() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun userObjectParticipatesInQueriesAndCanBeDeleted() {
        CameraDatabase(context).use { database ->
            val saved = database.addUserObject(userCamera())

            assertTrue(saved.userDefined)
            assertEquals(1, userObjects(database).size)
            assertEquals(
                saved.id,
                database.nearby(56.84, 60.61, 1000.0)!!.single { it.userDefined }.id,
            )
            assertEquals(
                saved.id,
                database.withinBounds(56.0, 57.0, 60.0, 61.0, 10).single { it.userDefined }.id,
            )
            saved.type = 2
            saved.distanceMeters = 800
            assertTrue(database.updateUserObject(saved))
            val updated = userObjects(database).single()
            assertEquals(2, updated.type)
            assertEquals(800, updated.distanceMeters)
            assertTrue(database.deleteUserObject(saved.id))
            assertFalse(database.deleteUserObject(saved.id))
            assertTrue(userObjects(database).isEmpty())
        }
    }

    @Test
    fun radarBaseImportDoesNotReplaceUserObjects() {
        CameraDatabase(context).use { database ->
            val saved = database.addUserObject(userCamera())
            database.importRadarBase(ByteArrayInputStream(RADAR_BASE.toByteArray(StandardCharsets.UTF_8)))

            assertEquals(2, database.count())
            val userObject = userObjects(database).single()
            assertEquals(saved.id, userObject.id)
            assertTrue(userObject.userDefined)
        }
    }

    @Test
    fun openingVersionFiveDatabaseRepairsMissingUserObjectsTable() {
        val path = context.getDatabasePath(DATABASE_NAME)
        path.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { database ->
            database.execSQL("CREATE TABLE cameras (id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL,type INTEGER NOT NULL, dir_type INTEGER NOT NULL, direction REAL NOT NULL,distance_meters INTEGER NOT NULL, reverse_distance_meters INTEGER NOT NULL,angle_degrees REAL NOT NULL, rank REAL NOT NULL, newbie INTEGER NOT NULL,speed_rules TEXT NOT NULL)")
            database.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            database.version = 5
        }

        CameraDatabase(context).use { database ->
            assertEquals(0, database.count())
        }
    }

    private fun userCamera() = CameraPoint().apply {
        latitude = 56.84
        longitude = 60.61
        type = 1
        dirType = 0
        distanceMeters = 500
        speedRules = SpeedControlRules.encode(60, false, -1, -1, 0, 0, SpeedControlRules.CAR)
    }

    private fun userObjects(database: CameraDatabase) =
        database.withinBounds(-90.0, 90.0, -180.0, 180.0, 100).filter { it.userDefined }

    companion object {
        private const val DATABASE_NAME = "speedcams.db"
        private const val RADAR_BASE =
            "{\"meta\":{\"ver\":\"3.0\",\"build\":5000,\"exportDate\":\"2026-09-24T00:00:00Z\"}," +
                "\"objects\":[{\"id\":10,\"lat\":56.85,\"lng\":60.62,\"type\":1," +
                "\"dirType\":0,\"dir\":0,\"distance\":400,\"speedControls\":[]}]}"
    }
}

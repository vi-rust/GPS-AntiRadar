package ru.gpsantiradar.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UserCameraDefaultsTest {
    @Test
    fun longPressDefaultsUseTappedCoordinatesAndGpsHeading() {
        val point = UserCameraDefaults.create(54.609042, 56.151002, 107, 371.5f)

        assertEquals(54.609042, point.latitude, 0.0)
        assertEquals(56.151002, point.longitude, 0.0)
        assertEquals(107, point.type)
        assertEquals(1, point.dirType)
        assertEquals(11.5f, point.direction, 0f)
        assertEquals(300, point.distanceMeters)
        assertEquals(0, point.reverseDistanceMeters)
        assertEquals(30f, point.angleDegrees, 0f)
        assertTrue(point.speedRules.isEmpty())
    }

    @Test
    fun everyNamedRadarBaseTypeIsAvailableToUser() {
        val types = RadarBaseTypes.allTypes()

        assertEquals(types.size, types.distinct().size)
        assertTrue(types.contains(0))
        assertTrue(types.contains(714))
        assertTrue(types.all { !RadarBaseTypes.name(it).startsWith("Объект типа") })
    }

    @Test
    fun userHintDoesNotHaveTechnicalOwnerLabel() {
        val point = UserCameraDefaults.create(54.0, 56.0, 1, 90f).apply { userDefined = true }

        assertTrue(!CameraHintFormatter.format(point).contains("[Мой объект]"))
    }

    @Test
    fun userHintContainsEveryEditableParameter() {
        val point = UserCameraDefaults.create(54.609042, 56.151002, 1, 91f).apply {
            reverseDistanceMeters = 150
            angleDegrees = 45f
            speedRules = SpeedControlRules.encode(
                70, false, -1, -1, 0, 0, SpeedControlRules.CAR,
            )
        }
        val hint = CameraHintFormatter.format(point)

        assertTrue(hint.contains("Тип объекта: ${point.typeName()}"))
        assertTrue(hint.contains("Координаты: 54.609042, 56.151002"))
        assertTrue(hint.contains("Направленность: ${point.directionName()}"))
        assertTrue(hint.contains("Направление: 91°"))
        assertTrue(hint.contains("Дистанция оповещения: 300 м"))
        assertTrue(hint.contains("Обратная дистанция: 150 м"))
        assertTrue(hint.contains("Угол сектора: 45°"))
        assertTrue(hint.contains("Ограничение скорости: 70 км/ч"))
    }

    @Test
    fun detachedCopyCanChangeCoordinatesWithoutMutatingRenderedObject() {
        val rendered = UserCameraDefaults.create(54.609042, 56.151002, 1, 90f).apply { id = -7 }
        val edited = rendered.detachedCopy().apply {
            latitude = 54.7
            type = 107
        }

        assertEquals(-7, edited.id)
        assertEquals(54.609042, rendered.latitude, 0.0)
        assertEquals(1, rendered.type)
        assertEquals(54.7, edited.latitude, 0.0)
        assertEquals(107, edited.type)
    }
}

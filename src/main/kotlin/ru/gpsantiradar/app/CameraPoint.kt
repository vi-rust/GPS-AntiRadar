package ru.gpsantiradar.app

/** One RadarBase object stored in the local database. */
class CameraPoint {
    var id: Long = 0
    var latitude: Double = 0.0
    var longitude: Double = 0.0
    var type: Int = 0
    /** RadarBase direction kind: 0 all, 1 oncoming, 2 both ways, 3 rear, 4 front+rear. */
    var dirType: Int = 0
    /** Direction of the controlled traffic flow, in degrees. */
    var direction: Float = 0f
    var distanceMeters: Int = 0
    var reverseDistanceMeters: Int = 0
    /** Full RadarBase sector angle, in degrees. */
    var angleDegrees: Float = 0f
    var rank: Float = 0f
    var newbie: Boolean = false
    /** Compact representation of RadarBase speedControls. */
    var speedRules: String = ""

    fun currentSpeedLimit(): Int = SpeedControlRules.currentCarLimit(speedRules, System.currentTimeMillis())
    fun isObservation(): Boolean = type == 15
    fun isFake(): Boolean = type == 16
    fun isAverageSpeed(): Boolean = type == 41 || type == 42 || type == 43
    fun isCameraOrControl(): Boolean = RadarBaseTypes.isCameraOrControl(type)
    fun isRoadObject(): Boolean = !isCameraOrControl()
    fun hasReverseZone(): Boolean = reverseDistanceMeters > 0 && (dirType == 2 || dirType == 3 || dirType == 4)
    fun typeName(): String = RadarBaseTypes.name(type)

    fun directionName(): String = when (dirType) {
        0 -> "во всех направлениях"
        1 -> "навстречу потоку"
        2 -> "два встречных направления"
        3 -> "в спину потоку"
        4 -> "в лицо и в спину потоку"
        else -> "направление не указано"
    }
}

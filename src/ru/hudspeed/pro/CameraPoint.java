package ru.gpsantiradar.app;

/** One RadarBase object stored in the local database. */
public final class CameraPoint {
    public long id;
    public double latitude;
    public double longitude;
    public int type;
    /** RadarBase direction kind: 0 all, 1 oncoming, 2 both ways, 3 rear, 4 front+rear. */
    public int dirType;
    /** Direction of the controlled traffic flow, in degrees. */
    public float direction;
    public int distanceMeters;
    public int reverseDistanceMeters;
    /** Full RadarBase sector angle, in degrees. */
    public float angleDegrees;
    public float rank;
    public boolean newbie;
    /** Compact representation of RadarBase speedControls. */
    public String speedRules = "";

    public CameraPoint() {}

    public int currentSpeedLimit() {
        return SpeedControlRules.currentCarLimit(speedRules, System.currentTimeMillis());
    }

    public boolean isObservation() { return type == 15; }
    public boolean isFake() { return type == 16; }
    public boolean isAverageSpeed() { return type == 41 || type == 42 || type == 43; }
    public boolean isCameraOrControl() { return RadarBaseTypes.isCameraOrControl(type); }
    public boolean isRoadObject() { return !isCameraOrControl(); }

    public boolean hasReverseZone() {
        return reverseDistanceMeters > 0 && (dirType == 2 || dirType == 3 || dirType == 4);
    }

    public String typeName() { return RadarBaseTypes.name(type); }

    public String directionName() {
        switch (dirType) {
            case 0: return "во всех направлениях";
            case 1: return "навстречу потоку";
            case 2: return "два встречных направления";
            case 3: return "в спину потоку";
            case 4: return "в лицо и в спину потоку";
            default: return "направление не указано";
        }
    }
}

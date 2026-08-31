package ru.gpsantiradar.app;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ParserGeoTest {
    public static void main(String[] args) throws Exception {
        String json = "{\"meta\":{\"ver\":\"3.0\",\"build\":3995,"
                + "\"exportDate\":\"2026-08-28T05:05:00Z\"},"
                + "\"objects\":["
                + "{\"id\":8,\"lat\":45.32478,\"lng\":37.72181,\"type\":1,"
                + "\"dirType\":1,\"dir\":165,\"distance\":400,\"revDistance\":250,"
                + "\"angle\":15,\"rank\":-0.275,\"isNewbie\":false,\"units\":\"kph\","
                + "\"speedControls\":[{\"speed\":110,\"from\":[],\"to\":[],"
                + "\"weekdays\":[],\"months\":[],\"vehicles\":[\"car\"],\"type\":\"current\"},"
                + "{\"speed\":90,\"from\":[],\"to\":[],\"weekdays\":[],\"months\":[],"
                + "\"vehicles\":[\"truck\"],\"type\":\"current\"}]},"
                + "{\"id\":9,\"lat\":55.7,\"lng\":37.6,\"type\":15,\"dirType\":0,"
                + "\"dir\":0,\"distance\":300,\"revDistance\":100,\"angle\":18,"
                + "\"speedControls\":[],\"units\":\"kph\",\"isNewbie\":false},"
                + "{\"id\":10,\"lat\":56.8,\"lng\":60.6,\"type\":16,\"dirType\":2,"
                + "\"dir\":90,\"distance\":500,\"revDistance\":450,\"angle\":10,"
                + "\"speedControls\":[],\"units\":\"kph\",\"isNewbie\":true},"
                + "{\"id\":11,\"lat\":56.8,\"lng\":60.61,\"type\":62,\"dirType\":0,"
                + "\"dir\":0,\"distance\":100,\"speedControls\":[]}] }";

        List<CameraPoint> points = new ArrayList<>();
        RadarBaseParser.Result parsed = RadarBaseParser.parse(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)), points::add);
        check(parsed.count == 4 && points.size() == 4, "all RadarBase object types imported");
        CameraPoint stationary = points.get(0);
        check(stationary.id == 8 && stationary.type == 1, "RadarBase id and type");
        float expectedShift = coordinateShift(3995, 165f);
        check((float) stationary.latitude == (float) 45.32478 - expectedShift
                        && (float) stationary.longitude == (float) 37.72181 - expectedShift,
                "RadarBase coordinate correction");
        check(parsed.build != null && parsed.build == 3995 && parsed.coordinatesCorrected,
                "RadarBase build metadata");
        check(stationary.dirType == 1 && stationary.direction == 165,
                "RadarBase traffic direction");
        check(stationary.distanceMeters == 400 && stationary.reverseDistanceMeters == 250,
                "RadarBase distances");
        check(stationary.angleDegrees == 15, "RadarBase sector angle");
        check(stationary.currentSpeedLimit() == 110, "car-specific speed control");
        check(points.get(1).isObservation() && !points.get(1).isFake(),
                "explicit observation type");
        check(points.get(2).isFake() && points.get(2).hasReverseZone(),
                "fake and bidirectional type");
        check(points.get(2).typeName().equals("Муляж камеры"), "RadarBase type names");
        check(points.get(3).isRoadObject() && points.get(3).typeName().equals("Неровность"),
                "road object classification and name");

        String explicitUnknown = "{\"objects\":[{\"id\":12,\"lat\":55.0,\"lng\":37.0,"
                + "\"type\":0,\"dir\":0,\"speedControls\":[]}]}";
        List<CameraPoint> unknown = new ArrayList<>();
        RadarBaseParser.parse(new ByteArrayInputStream(
                explicitUnknown.getBytes(StandardCharsets.UTF_8)), unknown::add);
        check(unknown.size() == 1 && unknown.get(0).type == 0,
                "explicit type zero imported");

        String noBuild = "{\"meta\":{\"ver\":\"3.0\",\"build\":\"invalid\"},"
                + "\"objects\":[{\"id\":1,\"lat\":55.5,\"lng\":37.5,\"type\":1,"
                + "\"dir\":90,\"speedControls\":[]}]}";
        List<CameraPoint> uncorrected = new ArrayList<>();
        RadarBaseParser.Result noBuildResult = RadarBaseParser.parse(new ByteArrayInputStream(
                noBuild.getBytes(StandardCharsets.UTF_8)), uncorrected::add);
        check(noBuildResult.build == null && !noBuildResult.coordinatesCorrected,
                "invalid build disables correction");
        check(uncorrected.get(0).latitude == 55.5 && uncorrected.get(0).longitude == 37.5,
                "coordinates unchanged without build");

        boolean oldFormatRejected = false;
        try {
            RadarBaseParser.parse(new ByteArrayInputStream(
                    "2|Radars|1251\n18059|RU-4|51.6|39.1|".getBytes(StandardCharsets.UTF_8)),
                    point -> {});
        } catch (IOException expected) {
            oldFormatRejected = true;
        }
        check(oldFormatRejected, "old export format must not be supported");

        double distance = Geo.distanceMeters(56.8, 60.6, 56.8, 60.61);
        check(distance > 600 && distance < 650, "distance");
        check(Geo.angleDifference(350, 10) == 20, "angle wrap");
        check(StrelkaAlertAlgorithm.spokenDistance(951) == 1000
                        && StrelkaAlertAlgorithm.spokenDistance(950) == 900
                        && StrelkaAlertAlgorithm.spokenDistance(15) == 10,
                "Strelka distance voice thresholds");
        check(StrelkaAlertAlgorithm.beepIntervalMillis(701) == 6000L
                        && StrelkaAlertAlgorithm.beepIntervalMillis(150) == 1000L,
                "Strelka beep cadence");
        check(StrelkaAlertAlgorithm.beepVolume(300) == 1f
                        && StrelkaAlertAlgorithm.beepVolume(1000) == 0.05f,
                "Strelka beep volume ramp");

        if (args.length > 0) {
            final int[] count = {0};
            RadarBaseParser.Result real;
            try (FileInputStream input = new FileInputStream(args[0])) {
                real = RadarBaseParser.parse(input, point -> count[0]++);
            }
            check(real.count == count[0] && real.count > 150000, "real RadarBase count");
            check(real.schemaVersion.startsWith("3."), "real RadarBase schema");
            check(real.build != null && real.coordinatesCorrected,
                    "real RadarBase coordinates corrected");
            System.out.println("Real file: " + real.count + " objects");
        }
        System.out.println("ParserGeoTest: OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static float coordinateShift(int build, float direction) {
        int phase = build * build;
        phase *= 3;
        float key = (float) (Math.sin((double) build) * Math.cos((double) phase)
                / 180.0 * 0.2);
        return (direction + 360f) * key;
    }
}

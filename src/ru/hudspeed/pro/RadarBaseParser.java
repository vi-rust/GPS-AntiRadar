package ru.gpsantiradar.app;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Streaming parser for RadarBase main_extended.json schema 3.x. */
public final class RadarBaseParser {
    public interface RecordConsumer { void accept(CameraPoint point) throws IOException; }

    public static final class Result {
        public final int count;
        public final String schemaVersion;
        public final String exportDate;
        public final Integer build;
        public final boolean coordinatesCorrected;

        Result(int count, String schemaVersion, String exportDate,
               Integer build, boolean coordinatesCorrected) {
            this.count = count;
            this.schemaVersion = schemaVersion;
            this.exportDate = exportDate;
            this.build = build;
            this.coordinatesCorrected = coordinatesCorrected;
        }
    }

    private RadarBaseParser() {}

    public static Result parse(InputStream input, RecordConsumer consumer) throws IOException {
        JsonReader reader = new JsonReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String version = "";
        String exportDate = "";
        Integer build = null;
        int count = 0;
        boolean objectsSeen = false;
        boolean coordinatesCorrected = false;
        try {
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if ("meta".equals(name)) {
                    Meta meta = readMeta(reader);
                    version = meta.version;
                    exportDate = meta.exportDate;
                    build = meta.build;
                } else if ("objects".equals(name)) {
                    objectsSeen = true;
                    reader.beginArray();
                    while (reader.hasNext()) {
                        CameraPoint point = readObject(reader, build);
                        if (build != null) coordinatesCorrected = true;
                        if (point != null) {
                            consumer.accept(point);
                            count++;
                        }
                    }
                    reader.endArray();
                } else {
                    reader.skipValue();
                }
            }
            reader.endObject();
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Повреждённый JSON RadarBase", error);
        }
        if (!objectsSeen || count == 0) throw new IOException("В JSON нет объектов RadarBase");
        if (!version.isEmpty() && !version.startsWith("3.")) {
            throw new IOException("Неподдерживаемая схема RadarBase " + version);
        }
        return new Result(count, version, exportDate, build, coordinatesCorrected);
    }

    private static Meta readMeta(JsonReader reader) throws IOException {
        Meta meta = new Meta();
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            if ("ver".equals(name)) meta.version = nextString(reader);
            else if ("exportDate".equals(name)) meta.exportDate = nextString(reader);
            else if ("build".equals(name)) meta.build = readBuild(reader);
            else reader.skipValue();
        }
        reader.endObject();
        return meta;
    }

    private static Integer readBuild(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null; }
        if (reader.peek() != JsonToken.NUMBER && reader.peek() != JsonToken.STRING) {
            reader.skipValue();
            return null;
        }
        try {
            return Integer.valueOf(reader.nextString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static CameraPoint readObject(JsonReader reader, Integer build) throws IOException {
        CameraPoint point = new CameraPoint();
        boolean hasId = false;
        boolean hasLat = false;
        boolean hasLon = false;
        boolean hasType = false;
        boolean hasDirection = false;
        float rawDirection = 0f;
        String units = "kph";
        List<Rule> rules = new ArrayList<>(2);
        reader.beginObject();
        while (reader.hasNext()) {
            String name = reader.nextName();
            switch (name) {
                case "id": point.id = nextLong(reader); hasId = true; break;
                case "lat": point.latitude = nextDouble(reader); hasLat = true; break;
                case "lng": point.longitude = nextDouble(reader); hasLon = true; break;
                case "type": point.type = nextInt(reader); hasType = true; break;
                case "dirType": point.dirType = nextInt(reader); break;
                case "dir": rawDirection = (float) nextDouble(reader); hasDirection = true; break;
                case "distance": point.distanceMeters = Math.max(0, nextInt(reader)); break;
                case "revDistance": point.reverseDistanceMeters = Math.max(0, nextInt(reader)); break;
                case "angle": point.angleDegrees = (float) nextDouble(reader); break;
                case "rank": point.rank = (float) nextDouble(reader); break;
                case "isNewbie": point.newbie = nextBoolean(reader); break;
                case "units": units = nextString(reader); break;
                case "speedControls": readRules(reader, rules); break;
                default: reader.skipValue(); break;
            }
        }
        reader.endObject();
        if (build != null && hasLat && hasLon && hasDirection) {
            applyCoordinateCorrection(point, rawDirection, build);
        }
        if (!hasId || !hasLat || !hasLon || !hasType || point.id <= 0 || point.type < 0
                || Math.abs(point.latitude) > 90 || Math.abs(point.longitude) > 180) return null;
        point.direction = normalize(rawDirection);
        point.dirType = Math.max(0, Math.min(4, point.dirType));
        if (point.angleDegrees <= 0 || point.angleDegrees > 180) point.angleDegrees = 15f;
        point.speedRules = encodeRules(rules, units);
        return point;
    }

    static void applyCoordinateCorrection(CameraPoint point, float objectDirection, int build) {
        int phase = build * build;
        phase *= 3;
        float key = (float) (Math.sin((double) build)
                * Math.cos((double) phase)
                / 180.0
                * 0.2);
        float latitude = (float) point.latitude;
        float longitude = (float) point.longitude;
        float shift = (objectDirection + 360f) * key;
        latitude -= shift;
        longitude -= shift;
        point.latitude = latitude;
        point.longitude = longitude;
    }

    private static void readRules(JsonReader reader, List<Rule> rules) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return; }
        reader.beginArray();
        while (reader.hasNext()) {
            Rule rule = new Rule();
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                switch (name) {
                    case "speed": rule.speed = nextInt(reader); break;
                    case "type": rule.average = "average".equalsIgnoreCase(nextString(reader)); break;
                    case "from": rule.fromMinute = readTime(reader); break;
                    case "to": rule.toMinute = readTime(reader); break;
                    case "weekdays": rule.weekdayMask = readNumberMask(reader, 7); break;
                    case "months": rule.monthMask = readNumberMask(reader, 12); break;
                    case "vehicles": rule.vehicleMask = readVehicleMask(reader); break;
                    default: reader.skipValue(); break;
                }
            }
            reader.endObject();
            if (rule.speed > 0) rules.add(rule);
        }
        reader.endArray();
    }

    private static int readTime(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return -1; }
        int hour = -1;
        int minute = 0;
        int index = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            int value = nextInt(reader);
            if (index == 0) hour = value;
            else if (index == 1) minute = value;
            index++;
        }
        reader.endArray();
        return hour < 0 ? -1 : Math.max(0, Math.min(1439, hour * 60 + minute));
    }

    private static int readNumberMask(JsonReader reader, int size) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0; }
        int mask = 0;
        reader.beginArray();
        while (reader.hasNext()) {
            int value = nextInt(reader);
            if (value >= 1 && value <= size) mask |= 1 << (value - 1);
            else if (value >= 0 && value < size) mask |= 1 << value;
        }
        reader.endArray();
        return mask;
    }

    private static int readVehicleMask(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0; }
        int mask = 0;
        reader.beginArray();
        while (reader.hasNext()) mask |= SpeedControlRules.vehicleMask(nextString(reader));
        reader.endArray();
        return mask;
    }

    private static String encodeRules(List<Rule> rules, String units) {
        StringBuilder result = new StringBuilder();
        boolean mph = "mph".equalsIgnoreCase(units);
        for (Rule rule : rules) {
            int speed = mph ? (int) Math.round(rule.speed * 1.609344) : rule.speed;
            if (result.length() > 0) result.append(';');
            result.append(SpeedControlRules.encode(speed, rule.average, rule.fromMinute,
                    rule.toMinute, rule.weekdayMask, rule.monthMask, rule.vehicleMask));
        }
        return result.toString();
    }

    private static String nextString(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return ""; }
        return reader.nextString();
    }

    private static double nextDouble(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0; }
        return reader.nextDouble();
    }

    private static int nextInt(JsonReader reader) throws IOException {
        return (int) Math.round(nextDouble(reader));
    }

    private static long nextLong(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0; }
        return reader.nextLong();
    }

    private static boolean nextBoolean(JsonReader reader) throws IOException {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return false; }
        if (reader.peek() == JsonToken.BOOLEAN) return reader.nextBoolean();
        return Boolean.parseBoolean(reader.nextString());
    }

    private static float normalize(float angle) {
        float result = angle % 360f;
        return result < 0 ? result + 360f : result;
    }

    private static final class Rule {
        int speed;
        boolean average;
        int fromMinute = -1;
        int toMinute = -1;
        int weekdayMask;
        int monthMask;
        int vehicleMask;
    }

    private static final class Meta {
        String version = "";
        String exportDate = "";
        Integer build;
    }
}

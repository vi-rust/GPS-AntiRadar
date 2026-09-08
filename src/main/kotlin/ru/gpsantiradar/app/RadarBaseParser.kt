package ru.gpsantiradar.app

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import kotlin.math.*

/** Streaming parser for RadarBase main_extended.json schema 3.x. */
object RadarBaseParser {
    fun interface RecordConsumer {
        @Throws(IOException::class)
        fun accept(point: CameraPoint)
    }

    class Result internal constructor(
        val count: Int,
        val schemaVersion: String,
        val exportDate: String,
        val build: Int?,
        val coordinatesCorrected: Boolean
    )

    @Throws(IOException::class)
    fun parse(input: InputStream, consumer: RecordConsumer): Result {
        val reader = JsonReader(InputStreamReader(input, StandardCharsets.UTF_8))
        var version = ""
        var exportDate = ""
        var build: Int? = null
        var count = 0
        var objectsSeen = false
        var coordinatesCorrected = false
        try {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "meta" -> readMeta(reader).also {
                        version = it.version
                        exportDate = it.exportDate
                        build = it.build
                    }
                    "objects" -> {
                        objectsSeen = true
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val point = readObject(reader, build)
                            if (build != null) coordinatesCorrected = true
                            if (point != null) {
                                consumer.accept(point)
                                count++
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        } catch (error: IllegalStateException) {
            throw IOException("Повреждённый JSON RadarBase", error)
        } catch (error: NumberFormatException) {
            throw IOException("Повреждённый JSON RadarBase", error)
        }
        if (!objectsSeen || count == 0) throw IOException("В JSON нет объектов RadarBase")
        if (version.isNotEmpty() && !version.startsWith("3.")) throw IOException("Неподдерживаемая схема RadarBase $version")
        return Result(count, version, exportDate, build, coordinatesCorrected)
    }

    @Throws(IOException::class)
    private fun readMeta(reader: JsonReader): Meta {
        val meta = Meta()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "ver" -> meta.version = nextString(reader)
                "exportDate" -> meta.exportDate = nextString(reader)
                "build" -> meta.build = readBuild(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return meta
    }

    @Throws(IOException::class)
    private fun readBuild(reader: JsonReader): Int? {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return null }
        if (reader.peek() != JsonToken.NUMBER && reader.peek() != JsonToken.STRING) { reader.skipValue(); return null }
        return try { reader.nextString().toInt() } catch (_: NumberFormatException) { null }
    }

    @Throws(IOException::class)
    private fun readObject(reader: JsonReader, build: Int?): CameraPoint? {
        val point = CameraPoint()
        var hasId = false
        var hasLat = false
        var hasLon = false
        var hasType = false
        var hasDirection = false
        var rawDirection = 0f
        var units = "kph"
        val rules = ArrayList<Rule>(2)
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> { point.id = nextLong(reader); hasId = true }
                "lat" -> { point.latitude = nextDouble(reader); hasLat = true }
                "lng" -> { point.longitude = nextDouble(reader); hasLon = true }
                "type" -> { point.type = nextInt(reader); hasType = true }
                "dirType" -> point.dirType = nextInt(reader)
                "dir" -> { rawDirection = nextDouble(reader).toFloat(); hasDirection = true }
                "distance" -> point.distanceMeters = max(0, nextInt(reader))
                "revDistance" -> point.reverseDistanceMeters = max(0, nextInt(reader))
                "angle" -> point.angleDegrees = nextDouble(reader).toFloat()
                "rank" -> point.rank = nextDouble(reader).toFloat()
                "isNewbie" -> point.newbie = nextBoolean(reader)
                "units" -> units = nextString(reader)
                "speedControls" -> readRules(reader, rules)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (build != null && hasLat && hasLon && hasDirection) applyCoordinateCorrection(point, rawDirection, build)
        if (!hasId || !hasLat || !hasLon || !hasType || point.id <= 0 || point.type < 0 ||
            abs(point.latitude) > 90 || abs(point.longitude) > 180) return null
        point.direction = normalize(rawDirection)
        point.dirType = point.dirType.coerceIn(0, 4)
        if (point.angleDegrees <= 0 || point.angleDegrees > 180) point.angleDegrees = 15f
        point.speedRules = encodeRules(rules, units)
        return point
    }

    fun applyCoordinateCorrection(point: CameraPoint, objectDirection: Float, build: Int) {
        var phase = build * build
        phase *= 3
        val key = (sin(build.toDouble()) * cos(phase.toDouble()) / 180.0 * 0.2).toFloat()
        var latitude = point.latitude.toFloat()
        var longitude = point.longitude.toFloat()
        val shift = (objectDirection + 360f) * key
        latitude -= shift
        longitude -= shift
        point.latitude = latitude.toDouble()
        point.longitude = longitude.toDouble()
    }

    @Throws(IOException::class)
    private fun readRules(reader: JsonReader, rules: MutableList<Rule>) {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return }
        reader.beginArray()
        while (reader.hasNext()) {
            val rule = Rule()
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "speed" -> rule.speed = nextInt(reader)
                    "type" -> rule.average = nextString(reader).equals("average", true)
                    "from" -> rule.fromMinute = readTime(reader)
                    "to" -> rule.toMinute = readTime(reader)
                    "weekdays" -> rule.weekdayMask = readNumberMask(reader, 7)
                    "months" -> rule.monthMask = readNumberMask(reader, 12)
                    "vehicles" -> rule.vehicleMask = readVehicleMask(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (rule.speed > 0) rules += rule
        }
        reader.endArray()
    }

    @Throws(IOException::class)
    private fun readTime(reader: JsonReader): Int {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return -1 }
        var hour = -1
        var minute = 0
        var index = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val value = nextInt(reader)
            if (index == 0) hour = value else if (index == 1) minute = value
            index++
        }
        reader.endArray()
        return if (hour < 0) -1 else (hour * 60 + minute).coerceIn(0, 1439)
    }

    @Throws(IOException::class)
    private fun readNumberMask(reader: JsonReader, size: Int): Int {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0 }
        var mask = 0
        reader.beginArray()
        while (reader.hasNext()) {
            val value = nextInt(reader)
            if (value in 1..size) mask = mask or (1 shl value - 1)
            else if (value in 0 until size) mask = mask or (1 shl value)
        }
        reader.endArray()
        return mask
    }

    @Throws(IOException::class)
    private fun readVehicleMask(reader: JsonReader): Int {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return 0 }
        var mask = 0
        reader.beginArray()
        while (reader.hasNext()) mask = mask or SpeedControlRules.vehicleMask(nextString(reader))
        reader.endArray()
        return mask
    }

    private fun encodeRules(rules: List<Rule>, units: String): String = buildString {
        val mph = units.equals("mph", true)
        rules.forEach { rule ->
            val speed = if (mph) (rule.speed * 1.609344).roundToInt() else rule.speed
            if (isNotEmpty()) append(';')
            append(SpeedControlRules.encode(speed, rule.average, rule.fromMinute, rule.toMinute,
                rule.weekdayMask, rule.monthMask, rule.vehicleMask))
        }
    }

    @Throws(IOException::class)
    private fun nextString(reader: JsonReader): String = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); "" } else reader.nextString()
    @Throws(IOException::class)
    private fun nextDouble(reader: JsonReader): Double = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); 0.0 } else reader.nextDouble()
    @Throws(IOException::class)
    private fun nextInt(reader: JsonReader) = nextDouble(reader).roundToInt()
    @Throws(IOException::class)
    private fun nextLong(reader: JsonReader): Long = if (reader.peek() == JsonToken.NULL) { reader.nextNull(); 0L } else reader.nextLong()
    @Throws(IOException::class)
    private fun nextBoolean(reader: JsonReader): Boolean {
        if (reader.peek() == JsonToken.NULL) { reader.nextNull(); return false }
        return if (reader.peek() == JsonToken.BOOLEAN) reader.nextBoolean() else reader.nextString().toBoolean()
    }
    private fun normalize(angle: Float): Float { val result = angle % 360f; return if (result < 0) result + 360f else result }
    private class Rule(var speed: Int = 0, var average: Boolean = false, var fromMinute: Int = -1,
        var toMinute: Int = -1, var weekdayMask: Int = 0, var monthMask: Int = 0, var vehicleMask: Int = 0)
    private class Meta(var version: String = "", var exportDate: String = "", var build: Int? = null)
}

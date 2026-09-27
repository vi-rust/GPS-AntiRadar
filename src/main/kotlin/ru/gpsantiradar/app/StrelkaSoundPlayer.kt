package ru.gpsantiradar.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.roundToInt

/** Plays the same composable voice fragments and queue order as Strelka HUD. */
class StrelkaSoundPlayer(context: Context) {
    private val context = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val queue = ArrayDeque<Clip>()
    private val decodedClips = mutableMapOf<String, PcmClip>()
    private var playbackWorker: Thread? = null
    private var activeTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var released = false

    @Synchronized
    fun announce(`object`: CameraPoint, distanceMeters: Int, multipleActiveObjects: Boolean) {
        if (released) return
        if (!multipleActiveObjects) add("alert.mp3", 1f)
        add(typeClip(`object`.type), 1f)
        val limit = `object`.currentSpeedLimit()
        if (limit in SPOKEN_SPEEDS) {
            val prefix = if (`object`.type == 1 || `object`.type == 5) "na_" else "ogr_"
            add("$prefix$limit.mp3", 1f)
        }
        val spokenDistance = StrelkaAlertAlgorithm.spokenDistance(distanceMeters)
        if (spokenDistance > 0) add("distance-$spokenDistance.mp3", 1f)
        when (`object`.dirType) {
            3 -> add("rear_mode.mp3", 1f)
            4 -> add("rear_and_front_mode.mp3", 1f)
        }
        when (`object`.type) {
            41 -> add("average_speed_section_start.mp3", 1f)
            42 -> add("average_speed_section_finish.mp3", 1f)
        }
        startWorkerIfNeeded()
    }

    @Synchronized
    fun beepIfIdle(distanceMeters: Int): Boolean {
        if (released || playbackWorker != null || queue.isNotEmpty()) return false
        add("beep.mp3", StrelkaAlertAlgorithm.beepVolume(distanceMeters))
        startWorkerIfNeeded()
        return true
    }

    @Synchronized
    fun objectFinished(`object`: CameraPoint) {
        if (released) return
        if (`object`.isCameraOrControl() && !`object`.isAverageSpeed()) {
            add("cam_stop_voice.mp3", 1f)
            startWorkerIfNeeded()
        }
    }

    private fun typeClip(type: Int): String {
        val name = "cam-type-$type.mp3"
        return try {
            context.assets.openFd("sounds/$name").use { name }
        } catch (_: IOException) {
            "cam-type-0.mp3"
        }
    }

    private fun add(name: String, volume: Float) {
        queue.add(Clip(name, volume))
    }

    private fun startWorkerIfNeeded() {
        if (released || playbackWorker != null || queue.isEmpty()) return
        val worker = Thread(::playbackLoop, "strelka-audio")
        playbackWorker = worker
        worker.start()
    }

    private fun playbackLoop() {
        var track: AudioTrack? = null
        var framesWritten = 0L
        try {
            while (!Thread.currentThread().isInterrupted) {
                val request = synchronized(this) { queue.poll() } ?: break
                val decoded = decodedClip(request.name) ?: continue
                if (decoded.sampleRate != OUTPUT_SAMPLE_RATE || decoded.channelCount != OUTPUT_CHANNEL_COUNT) {
                    continue
                }
                if (track == null) {
                    requestAudioFocus()
                    track = createAudioTrack()
                    synchronized(this) {
                        if (released) return
                        activeTrack = track
                    }
                    framesWritten += writeSilence(track, AUDIO_ROUTE_WARMUP_MS)
                    // Preload the route warmup before play(). Short alerts such as beep.mp3 do
                    // not fill a large Automotive output buffer on their own and otherwise never
                    // cross the HAL's playback-start threshold.
                    track.play()
                }
                framesWritten += writeClip(track, decoded, request.volume)
            }
            if (track != null && !Thread.currentThread().isInterrupted) {
                // The useful audio and silence share one PCM stream. The Automotive HAL therefore
                // cannot close a short MP3 before its buffered ending reaches the amplifier.
                framesWritten += writeSilence(track, PLAYBACK_TAIL_DURATION_MS)
                waitUntilPlayed(track, framesWritten)
                sleepInterruptibly(AUDIO_FOCUS_RELEASE_DELAY_MS)
            }
        } catch (_: IOException) {
            // A broken asset or output route must not stop tracking.
        } catch (_: IllegalArgumentException) {
            // The device rejected its advertised output format.
        } catch (_: IllegalStateException) {
            // The output route disappeared while it was playing.
        } finally {
            val ownedTrack = synchronized(this) {
                if (activeTrack === track) {
                    activeTrack = null
                    true
                } else {
                    false
                }
            }
            if (ownedTrack) releaseTrack(track)
            abandonAudioFocus()
            synchronized(this) {
                if (playbackWorker === Thread.currentThread()) playbackWorker = null
                startWorkerIfNeeded()
            }
        }
    }

    @Throws(IOException::class)
    private fun decodedClip(name: String): PcmClip? {
        decodedClips[name]?.let { return it }
        val decoded = try {
            decodeMp3(name)
        } catch (_: RuntimeException) {
            null
        }
        if (decoded != null) decodedClips[name] = decoded
        return decoded
    }

    @Throws(IOException::class)
    private fun decodeMp3(name: String): PcmClip =
        context.assets.openFd("sounds/$name").use { source ->
            val extractor = MediaExtractor()
            var decoder: MediaCodec? = null
            try {
                extractor.setDataSource(source.fileDescriptor, source.startOffset, source.length)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
                        ?.startsWith("audio/") == true
                } ?: throw IOException("No audio track in $name")
                extractor.selectTrack(trackIndex)
                val sourceFormat = extractor.getTrackFormat(trackIndex)
                val mime = sourceFormat.getString(MediaFormat.KEY_MIME)
                    ?: throw IOException("No MIME type in $name")
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(sourceFormat, null, null, 0)
                decoder.start()

                val output = ByteArrayOutputStream()
                val info = MediaCodec.BufferInfo()
                var inputEnded = false
                var outputEnded = false
                var outputFormat = sourceFormat
                while (!outputEnded && !Thread.currentThread().isInterrupted) {
                    if (!inputEnded) {
                        val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val input = decoder.getInputBuffer(inputIndex)
                                ?: throw IOException("No decoder input buffer for $name")
                            input.clear()
                            val size = extractor.readSampleData(input, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputEnded = true
                            } else {
                                decoder.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    size,
                                    max(0L, extractor.sampleTime),
                                    0,
                                )
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = decoder.outputFormat
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            if (info.size > 0 &&
                                info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                            ) {
                                val buffer = decoder.getOutputBuffer(outputIndex)
                                    ?: throw IOException("No decoder output buffer for $name")
                                val bytes = ByteArray(info.size)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                buffer.get(bytes)
                                output.write(bytes)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
                if (Thread.currentThread().isInterrupted) throw IOException("Decode interrupted")
                val encoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
                if (encoding != AudioFormat.ENCODING_PCM_16BIT) {
                    throw IOException("Unsupported PCM encoding $encoding in $name")
                }
                PcmClip(
                    output.toByteArray(),
                    outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                    outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                )
            } finally {
                if (decoder != null) {
                    try {
                        decoder.stop()
                    } catch (_: RuntimeException) {
                        // The codec did not reach the started state.
                    }
                    decoder.release()
                }
                extractor.release()
            }
        }

    private fun createAudioTrack(): AudioTrack {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(OUTPUT_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minimum = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val warmupBufferSize = (
            OUTPUT_SAMPLE_RATE * OUTPUT_FRAME_SIZE_BYTES * AUDIO_ROUTE_WARMUP_MS / 1000L
        ).toInt()
        val bufferSize = max(minimum, warmupBufferSize)
        val track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            releaseTrack(track)
            throw IllegalStateException("AudioTrack initialization failed")
        }
        return track
    }

    @Throws(IOException::class)
    private fun writeClip(track: AudioTrack, clip: PcmClip, volume: Float): Long {
        val bytes = if (volume >= 0.999f) clip.pcm else scalePcm16(clip.pcm, volume)
        writeFully(track, bytes)
        return bytes.size / OUTPUT_FRAME_SIZE_BYTES.toLong()
    }

    @Throws(IOException::class)
    private fun writeSilence(track: AudioTrack, durationMs: Long): Long {
        val frames = OUTPUT_SAMPLE_RATE * durationMs / 1000L
        val bytes = ByteArray((frames * OUTPUT_FRAME_SIZE_BYTES).toInt())
        writeFully(track, bytes)
        return frames
    }

    @Throws(IOException::class)
    private fun writeFully(track: AudioTrack, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size && !Thread.currentThread().isInterrupted) {
            val written = track.write(
                bytes,
                offset,
                bytes.size - offset,
                AudioTrack.WRITE_BLOCKING,
            )
            if (written <= 0) throw IOException("AudioTrack write failed: $written")
            offset += written
        }
        if (offset < bytes.size) throw IOException("AudioTrack write interrupted")
    }

    private fun waitUntilPlayed(track: AudioTrack, targetFrames: Long) {
        val startedAt = System.nanoTime()
        val expectedMs = targetFrames * 1000L / OUTPUT_SAMPLE_RATE
        val timeoutMs = expectedMs + PLAYBACK_DRAIN_MARGIN_MS
        while (!Thread.currentThread().isInterrupted) {
            val playedFrames = track.playbackHeadPosition.toLong() and UINT32_MASK
            if (playedFrames >= targetFrames) return
            if ((System.nanoTime() - startedAt) / 1_000_000L >= timeoutMs) return
            sleepInterruptibly(PLAYBACK_POSITION_POLL_MS)
        }
    }

    private fun scalePcm16(input: ByteArray, volume: Float): ByteArray {
        val gain = volume.coerceIn(0f, 1f)
        val output = ByteArray(input.size)
        var index = 0
        while (index + 1 < input.size) {
            val raw = (input[index].toInt() and 0xff) or (input[index + 1].toInt() shl 8)
            val scaled = (raw.toShort().toInt() * gain).roundToInt().coerceIn(-32768, 32767)
            output[index] = (scaled and 0xff).toByte()
            output[index + 1] = (scaled shr 8).toByte()
            index += 2
        }
        return output
    }

    private fun sleepInterruptibly(durationMs: Long) {
        try {
            Thread.sleep(durationMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    @Synchronized
    private fun requestAudioFocus() {
        if (audioFocusHeld) return
        val manager = audioManager ?: run {
            audioFocusHeld = true
            return
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .build()
        focusRequest = request
        audioFocusHeld = manager.requestAudioFocus(request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    @Synchronized
    private fun abandonAudioFocus() {
        val manager = audioManager ?: run {
            audioFocusHeld = false
            focusRequest = null
            return
        }
        val request = focusRequest
        if (audioFocusHeld && request != null) manager.abandonAudioFocusRequest(request)
        focusRequest = null
        audioFocusHeld = false
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        queue.clear()
        val worker = playbackWorker
        playbackWorker = null
        worker?.interrupt()
        val track = activeTrack
        activeTrack = null
        releaseTrack(track)
        abandonAudioFocus()
    }

    private fun releaseTrack(track: AudioTrack?) {
        track ?: return
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: RuntimeException) {
            // The track may already have been released by service shutdown.
        }
        try {
            track.release()
        } catch (_: RuntimeException) {
            // The track is already released.
        }
    }

    private data class Clip(val name: String, val volume: Float)
    private data class PcmClip(val pcm: ByteArray, val sampleRate: Int, val channelCount: Int)

    companion object {
        private const val OUTPUT_SAMPLE_RATE = 44_100
        private const val OUTPUT_CHANNEL_COUNT = 1
        private const val OUTPUT_FRAME_SIZE_BYTES = 2
        private const val AUDIO_ROUTE_WARMUP_MS = 100L
        private const val PLAYBACK_TAIL_DURATION_MS = 100L
        private const val AUDIO_FOCUS_RELEASE_DELAY_MS = 100L
        private const val PLAYBACK_POSITION_POLL_MS = 10L
        private const val PLAYBACK_DRAIN_MARGIN_MS = 2000L
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val UINT32_MASK = 0xffff_ffffL
        private val SPOKEN_SPEEDS = setOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
    }
}

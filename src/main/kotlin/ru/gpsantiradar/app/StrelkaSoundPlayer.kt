package ru.gpsantiradar.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.IOException
import java.util.ArrayDeque

/** Plays the same composable voice fragments and queue order as Strelka HUD. */
class StrelkaSoundPlayer(context: Context) :
    MediaPlayer.OnCompletionListener,
    MediaPlayer.OnErrorListener,
    MediaPlayer.OnPreparedListener {
    private val context = context.applicationContext
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Clip>()
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioFocusHeld = false
    private var startScheduled = false
    private var released = false
    private val scheduledStart = Runnable {
        synchronized(this) {
            startScheduled = false
            startNextClip()
        }
    }
    private val scheduledFocusAbandon = Runnable {
        synchronized(this) {
            if (player == null && queue.isEmpty() && !startScheduled) abandonAudioFocus()
        }
    }

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
        playNextIfIdle()
    }

    @Synchronized
    fun beepIfIdle(distanceMeters: Int): Boolean {
        if (released || player != null || queue.isNotEmpty() || startScheduled) return false
        add("beep.mp3", StrelkaAlertAlgorithm.beepVolume(distanceMeters))
        playNextIfIdle()
        return true
    }

    @Synchronized
    fun objectFinished(`object`: CameraPoint) {
        if (released) return
        if (`object`.isCameraOrControl() && !`object`.isAverageSpeed()) {
            add("cam_stop_voice.mp3", 1f)
            playNextIfIdle(AUDIO_ROUTE_WARMUP_MS)
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

    private fun requestAudioFocus() {
        if (audioFocusHeld) return
        val manager = audioManager ?: run {
            audioFocusHeld = true
            return
        }
        if (Build.VERSION.SDK_INT >= 26) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes)
                .build()
            focusRequest = request
            audioFocusHeld = manager.requestAudioFocus(request) ==
                AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioFocusHeld = manager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: run {
            audioFocusHeld = false
            focusRequest = null
            return
        }
        if (!audioFocusHeld) {
            focusRequest = null
            return
        }
        val request = focusRequest
        if (Build.VERSION.SDK_INT >= 26 && request != null) {
            manager.abandonAudioFocusRequest(request)
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(null)
        }
        audioFocusHeld = false
    }

    @Synchronized
    private fun playNextIfIdle(warmupMs: Long = 0L) {
        if (released || player != null || startScheduled) return
        mainHandler.removeCallbacks(scheduledFocusAbandon)
        if (queue.isEmpty()) {
            scheduleAudioFocusAbandon()
            return
        }
        val acquiredNow = !audioFocusHeld
        requestAudioFocus()
        if (acquiredNow && warmupMs > 0L) {
            startScheduled = true
            mainHandler.postDelayed(scheduledStart, warmupMs)
        } else {
            startNextClip()
        }
    }

    private fun startNextClip() {
        if (released || player != null) return
        val clip = queue.poll() ?: run {
            scheduleAudioFocusAbandon()
            return
        }
        val next = MediaPlayer()
        player = next
        next.setAudioAttributes(audioAttributes)
        next.setVolume(clip.volume, clip.volume)
        next.setOnCompletionListener(this)
        next.setOnErrorListener(this)
        next.setOnPreparedListener(this)
        try {
            context.assets.openFd("sounds/${clip.name}").use { source ->
                next.setDataSource(source.fileDescriptor, source.startOffset, source.length)
                next.prepareAsync()
            }
        } catch (_: IOException) {
            releaseCurrent()
            playNextIfIdle()
        } catch (_: RuntimeException) {
            releaseCurrent()
            playNextIfIdle()
        }
    }

    private fun scheduleAudioFocusAbandon() {
        mainHandler.removeCallbacks(scheduledFocusAbandon)
        mainHandler.postDelayed(scheduledFocusAbandon, AUDIO_FOCUS_RELEASE_DELAY_MS)
    }

    @Synchronized
    override fun onPrepared(mediaPlayer: MediaPlayer) {
        if (released || mediaPlayer !== player) {
            releasePlayer(mediaPlayer)
            return
        }
        try {
            mediaPlayer.start()
        } catch (_: IllegalStateException) {
            onError(mediaPlayer, 0, 0)
        }
    }

    @Synchronized
    override fun onCompletion(mediaPlayer: MediaPlayer) {
        if (mediaPlayer !== player) {
            releasePlayer(mediaPlayer)
            return
        }
        releaseCurrent()
        playNextIfIdle()
    }

    @Synchronized
    override fun onError(mediaPlayer: MediaPlayer, what: Int, extra: Int): Boolean {
        if (mediaPlayer !== player) {
            releasePlayer(mediaPlayer)
            return true
        }
        releaseCurrent()
        playNextIfIdle()
        return true
    }

    @Synchronized
    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacks(scheduledStart)
        mainHandler.removeCallbacks(scheduledFocusAbandon)
        startScheduled = false
        queue.clear()
        releaseCurrent()
        abandonAudioFocus()
    }

    private fun releaseCurrent() {
        val current = player ?: return
        releasePlayer(current)
        player = null
    }

    private fun releasePlayer(current: MediaPlayer) {
        try {
            current.release()
        } catch (_: RuntimeException) {
            // The player is already released.
        }
    }

    private data class Clip(val name: String, val volume: Float)

    companion object {
        private const val AUDIO_ROUTE_WARMUP_MS = 400L
        private const val AUDIO_FOCUS_RELEASE_DELAY_MS = 250L
        private val SPOKEN_SPEEDS = setOf(20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130)
    }
}

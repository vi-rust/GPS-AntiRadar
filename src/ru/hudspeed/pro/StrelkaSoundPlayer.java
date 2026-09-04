package ru.gpsantiradar.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Plays the same composable voice fragments and queue order as Strelka HUD. */
public final class StrelkaSoundPlayer implements MediaPlayer.OnCompletionListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnPreparedListener {
    private static final Set<Integer> SPOKEN_SPEEDS = new HashSet<>(Arrays.asList(
            20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 130));

    private final Context context;
    private final AudioManager audioManager;
    private final AudioAttributes audioAttributes;
    private final ArrayDeque<Clip> queue = new ArrayDeque<>();
    private MediaPlayer player;
    private AudioFocusRequest focusRequest;

    public StrelkaSoundPlayer(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
    }

    public synchronized void announce(CameraPoint object, int distanceMeters,
                                      boolean multipleActiveObjects) {
        if (!multipleActiveObjects) add("alert.mp3", 1f);
        add(typeClip(object.type), 1f);
        int limit = object.currentSpeedLimit();
        if (SPOKEN_SPEEDS.contains(limit)) {
            add((object.type == 1 || object.type == 5 ? "na_" : "ogr_")
                    + limit + ".mp3", 1f);
        }
        int spokenDistance = StrelkaAlertAlgorithm.spokenDistance(distanceMeters);
        if (spokenDistance > 0) add("distance-" + spokenDistance + ".mp3", 1f);
        if (object.dirType == 3) add("rear_mode.mp3", 1f);
        else if (object.dirType == 4) add("rear_and_front_mode.mp3", 1f);
        if (object.type == 41) add("average_speed_section_start.mp3", 1f);
        else if (object.type == 42) add("average_speed_section_finish.mp3", 1f);
        playNextIfIdle();
    }

    public synchronized boolean beepIfIdle(int distanceMeters) {
        if (player != null || !queue.isEmpty()) return false;
        add("beep.mp3", StrelkaAlertAlgorithm.beepVolume(distanceMeters));
        playNextIfIdle();
        return true;
    }

    public synchronized void objectFinished(CameraPoint object) {
        if (object.isCameraOrControl() && !object.isAverageSpeed()) {
            add("cam_stop_voice.mp3", 1f);
            playNextIfIdle();
        }
    }

    private String typeClip(int type) {
        String name = "cam-type-" + type + ".mp3";
        try (AssetFileDescriptor ignored = context.getAssets().openFd("sounds/" + name)) {
            return name;
        } catch (IOException ignored) {
            return "cam-type-0.mp3";
        }
    }

    private void add(String name, float volume) {
        queue.add(new Clip(name, volume));
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest = new AudioFocusRequest.Builder(
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttributes).build();
            audioManager.requestAudioFocus(focusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        }
    }

    private void abandonAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) {
            audioManager.abandonAudioFocusRequest(focusRequest);
            focusRequest = null;
        } else {
            audioManager.abandonAudioFocus(null);
        }
    }

    private synchronized void playNextIfIdle() {
        if (player != null) return;
        Clip clip = queue.poll();
        if (clip == null) {
            abandonAudioFocus();
            return;
        }
        requestAudioFocus();
        MediaPlayer next = new MediaPlayer();
        player = next;
        next.setAudioAttributes(audioAttributes);
        next.setVolume(clip.volume, clip.volume);
        next.setOnCompletionListener(this);
        next.setOnErrorListener(this);
        next.setOnPreparedListener(this);
        try (AssetFileDescriptor source = context.getAssets().openFd("sounds/" + clip.name)) {
            next.setDataSource(source.getFileDescriptor(), source.getStartOffset(), source.getLength());
            next.prepareAsync();
        } catch (IOException | RuntimeException error) {
            releaseCurrent();
            playNextIfIdle();
        }
    }

    @Override public void onPrepared(MediaPlayer mediaPlayer) {
        try {
            mediaPlayer.start();
        } catch (IllegalStateException error) {
            onError(mediaPlayer, 0, 0);
        }
    }

    @Override public synchronized void onCompletion(MediaPlayer mediaPlayer) {
        releaseCurrent();
        playNextIfIdle();
    }

    @Override public synchronized boolean onError(MediaPlayer mediaPlayer, int what, int extra) {
        releaseCurrent();
        playNextIfIdle();
        return true;
    }

    public synchronized void release() {
        queue.clear();
        releaseCurrent();
        abandonAudioFocus();
    }

    private void releaseCurrent() {
        if (player == null) return;
        try {
            player.release();
        } catch (RuntimeException ignored) {}
        player = null;
    }

    private static final class Clip {
        final String name;
        final float volume;

        Clip(String name, float volume) {
            this.name = name;
            this.volume = volume;
        }
    }
}

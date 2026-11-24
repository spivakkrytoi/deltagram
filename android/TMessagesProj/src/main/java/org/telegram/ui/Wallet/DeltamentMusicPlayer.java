package org.telegram.ui.Wallet;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * App-wide DeltaGram music playback. The sheet owns the UI, this object owns the
 * ExoPlayer, so closing the sheet no longer stops the track.
 */
public final class DeltamentMusicPlayer {

    public interface Listener {
        default void onTrackChanged(DeltamentSoundCloudClient.Track track) {
        }

        default void onPlaybackStateChanged(boolean playing, boolean prepared) {
        }

        default void onPlaybackError(String message) {
        }
    }

    private static volatile DeltamentMusicPlayer instance;

    public static DeltamentMusicPlayer getInstance() {
        DeltamentMusicPlayer local = instance;
        if (local == null) {
            synchronized (DeltamentMusicPlayer.class) {
                local = instance;
                if (local == null) {
                    local = new DeltamentMusicPlayer();
                    instance = local;
                }
            }
        }
        return local;
    }

    public final ArrayList<DeltamentSoundCloudClient.Track> queue = new ArrayList<>();
    public int queueIndex = -1;
    public DeltamentSoundCloudClient.Track currentTrack;

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private ExoPlayer player;
    private boolean prepared, playing, loading;
    private int loadGeneration;
    private float speed;

    private DeltamentMusicPlayer() {
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public boolean isPlaying() {
        return player != null && prepared && player.isPlaying();
    }

    public boolean isPrepared() {
        return prepared;
    }

    public boolean hasTrack() {
        return currentTrack != null;
    }

    public boolean isLoading() {
        return loading;
    }

    /** Current playback position in ms, or 0 when nothing is prepared yet. */
    public long getPositionMs() {
        try {
            return player != null && prepared ? player.getCurrentPosition() : 0L;
        } catch (Throwable e) {
            return 0L;
        }
    }

    /** Track duration in ms: from the player when ready, otherwise the metadata. */
    public long getDurationMs() {
        try {
            if (player != null && prepared) {
                final long duration = player.getDuration();
                if (duration > 0 && duration != C.TIME_UNSET) {
                    return duration;
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return currentTrack != null ? Math.max(0L, currentTrack.durationMs) : 0L;
    }

    /** Seeks inside the current track; ignored until it is prepared. */
    public void seekTo(long positionMs) {
        try {
            if (player != null && prepared) {
                final long duration = getDurationMs();
                final long target = duration > 0
                        ? Math.max(0L, Math.min(positionMs, duration))
                        : Math.max(0L, positionMs);
                player.seekTo(target);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Rewinds to the start, or plays the previous track from the queue. */
    public void previous() {        if (getPositionMs() > 3000L) {
            seekTo(0L);
            return;
        }
        if (queueIndex > 0) {
            playAt(queueIndex - 1);
        } else {
            seekTo(0L);
        }
    }

    /** Playback speed, clamped to 0.5x..2x and remembered across launches. */
    public float getSpeed() {
        if (speed <= 0f) {
            try {
                speed = prefs().getFloat("speed", 1f);
            } catch (Throwable e) {
                speed = 1f;
            }
            if (speed < 0.5f || speed > 2f) {
                speed = 1f;
            }
        }
        return speed;
    }

    public void setSpeed(float value) {
        final float clamped = Math.max(0.5f, Math.min(2f, value));
        speed = clamped;
        try {
            prefs().edit().putFloat("speed", clamped).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
        applySpeed();
        notifyState();
    }

    private SharedPreferences prefs() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("deltagram_music", Context.MODE_PRIVATE);
    }

    private void applySpeed() {
        try {
            if (player != null) {
                player.setPlaybackSpeed(getSpeed());
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void play(DeltamentSoundCloudClient.Track track) {
        if (track == null) {
            return;
        }
        int existing = -1;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id == track.id) {
                existing = i;
                break;
            }
        }
        if (existing < 0) {
            queue.add(track);
            existing = queue.size() - 1;
        }
        playAt(existing);
    }

    public void playAt(int index) {
        if (index < 0 || index >= queue.size()) {
            return;
        }
        queueIndex = index;
        currentTrack = queue.get(index);
        loading = true;
        prepared = false;
        playing = false;
        notifyTrack();
        final int generation = ++loadGeneration;
        DeltamentSoundCloudClient.resolveStreamUrl(currentTrack, new DeltamentSoundCloudClient.Callback<String>() {
            @Override
            public void onSuccess(String url) {
                if (generation == loadGeneration) {
                    start(url);
                }
            }

            @Override
            public void onError(String message) {
                if (generation != loadGeneration) {
                    return;
                }
                loading = false;
                prepared = false;
                playing = false;
                notifyState();
                notifyError(message);
            }
        });
    }

    public void toggle() {
        if (player == null || !prepared) {
            if (currentTrack != null) {
                play(currentTrack);
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.play();
            }
            playing = player.isPlaying();
            notifyState();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    public void next() {
        if (queueIndex + 1 < queue.size()) {
            playAt(queueIndex + 1);
        } else {
            playing = false;
            prepared = false;
            loading = false;
            notifyState();
        }
    }

    /** Stops playback but keeps the queue, so the user can reopen and replay. */
    public void stop() {
        ++loadGeneration;
        loading = false;
        releasePlayer();
        currentTrack = null;
        queueIndex = -1;
        notifyTrack();
        notifyState();
    }

    private void start(String url) {
        releasePlayer();
        loading = false;
        try {
            final Context context = ApplicationLoader.applicationContext;
            player = new ExoPlayer.Builder(context).build();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(), true);
            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        prepared = true;
                        playing = player.isPlaying();
                        notifyState();
                    } else if (state == Player.STATE_ENDED) {
                        next();
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    prepared = false;
                    playing = false;
                    notifyState();
                    notifyError(DeltamentSoundCloudClient.ERROR_STREAM);
                }
            });
            player.setMediaItem(MediaItem.fromUri(url));
            player.setPlaybackSpeed(getSpeed());
            player.prepare();
            player.play();
        } catch (Throwable e) {
            FileLog.e(e);
            releasePlayer();
            notifyError(DeltamentSoundCloudClient.ERROR_STREAM);
        }
    }

    private void releasePlayer() {
        if (player != null) {
            try {
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
        prepared = false;
        playing = false;
    }

    private void notifyTrack() {
        for (Listener listener : listeners) {
            listener.onTrackChanged(currentTrack);
        }
    }

    private void notifyState() {
        for (Listener listener : listeners) {
            listener.onPlaybackStateChanged(playing, prepared);
        }
    }

    private void notifyError(String message) {
        for (Listener listener : listeners) {
            listener.onPlaybackError(message);
        }
    }
}

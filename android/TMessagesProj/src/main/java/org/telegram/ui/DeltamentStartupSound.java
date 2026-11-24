package org.telegram.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

/**
 * DeltaGram startup jingle: a short "hello" played once when the app is opened
 * from the launcher. Enabled by default and can be turned off in the settings.
 *
 * <p>The sound is only started from {@code LaunchActivity.onCreate}, and the
 * activity is a {@code singleTask}, so it never fires when the app is merely
 * brought back from the background or when a notification opens a chat.</p>
 */
public final class DeltamentStartupSound {

    private static final String PREF_STARTUP_SOUND = "dg_startup_sound";
    /** Guards against a duplicated start while the process is still warming up. */
    private static final long REPEAT_GUARD_MS = 4000L;

    private static MediaPlayer player;
    private static long lastStartedAt;
    private static boolean startedInProcess;

    private DeltamentStartupSound() {
    }

    /** Whether the launch jingle is allowed to play. Default: on. */
    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_STARTUP_SOUND, true);
    }

    public static void setEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit()
                .putBoolean(PREF_STARTUP_SOUND, enabled).apply();
    }

    /**
     * Plays the jingle for a fresh app start. Safe to call more than once:
     * repeated calls in the same process are ignored.
     */
    public static void playOnLaunch(Context context) {
        if (context == null || !isEnabled()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        if (startedInProcess && now - lastStartedAt < REPEAT_GUARD_MS) {
            return;
        }
        startedInProcess = true;
        lastStartedAt = now;
        play(context.getApplicationContext(), false);
    }

    /** Plays the jingle right away, used as feedback in the settings. */
    public static void playPreview(Context context) {
        play(context, true);
    }

    private static void play(Context context, boolean preview) {
        if (context == null) {
            return;
        }
        try {
            final AudioManager audioManager =
                    (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            final int ringerMode = audioManager == null
                    ? AudioManager.RINGER_MODE_NORMAL : audioManager.getRingerMode();
            // Never make noise when the phone is explicitly silent, otherwise
            // the jingle follows the normal media volume.
            if (ringerMode == AudioManager.RINGER_MODE_SILENT && !preview) {
                return;
            }
            release();

            final MediaPlayer mediaPlayer = new MediaPlayer();
            final AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            mediaPlayer.setAudioAttributes(attributes);

            try (AssetFileDescriptor afd =
                         context.getResources().openRawResourceFd(R.raw.deltagram_startup)) {
                if (afd == null) {
                    mediaPlayer.release();
                    return;
                }
                mediaPlayer.setDataSource(afd.getFileDescriptor(),
                        afd.getStartOffset(), afd.getLength());
            }

            mediaPlayer.setVolume(0.85f, 0.85f);
            mediaPlayer.setOnCompletionListener(mp -> release(mp));
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                release(mp);
                return true;
            });
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                } catch (Exception e) {
                    release(mp);
                }
            });
            player = mediaPlayer;
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            release();
        }
    }

    private static void release() {
        release(player);
    }

    private static void release(MediaPlayer mediaPlayer) {
        if (mediaPlayer == null) {
            return;
        }
        if (player == mediaPlayer) {
            player = null;
        }
        try {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
        } catch (Exception ignored) {
        }
        try {
            mediaPlayer.release();
        } catch (Exception ignored) {
        }
    }
}

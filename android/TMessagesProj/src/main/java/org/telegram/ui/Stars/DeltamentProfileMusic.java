package org.telegram.ui.Stars;

import org.telegram.messenger.MessagesController;
import org.telegram.ui.Wallet.DeltamentMusicPlayer;
import org.telegram.ui.Wallet.DeltamentSoundCloudClient;

/**
 * DeltaGram "musical profile" helpers: the switch that enables it, the physics
 * switch, and a beat clock derived from the position of the track that the
 * built-in music player is currently streaming.
 *
 * <p>The beat itself is analytical, not sampled: Android only exposes PCM data
 * through {@code Visualizer}, which requires the dangerous RECORD_AUDIO
 * permission, so instead the profile pulses on a stable per-track tempo grid
 * (BPM derived from the track id). That keeps the whole thing permission free
 * and completely frame-rate independent.</p>
 */
public final class DeltamentProfileMusic {

    /** Beat-reactive spheres + visualizer around the avatar. */
    private static final String PREF_MUSIC = "dg_profile_music";
    /** Collisions / repulsion / finger vortex for the gift bubbles. */
    private static final String PREF_PHYSICS = "dg_bubble_physics";
    /** The "Мяууу" companion that flies around the profile. */
    private static final String PREF_CAT = "dg_profile_cat";
    /** Paimon brings one pinned gift to the centre once in a while. */
    private static final String PREF_GIFT_OF_DAY = "dg_gift_of_day";
    /** How often the "gift of the day" ritual repeats, in minutes. */
    private static final String PREF_GIFT_INTERVAL = "dg_gift_of_day_interval";
    /** User-drawn glowing links between the gift bubbles. */
    private static final String PREF_CONSTELLATIONS = "dg_profile_constellations";

    /** Default repeat time of the ritual: twice a day. */
    public static final int GIFT_INTERVAL_DEFAULT = 12 * 60;
    public static final int GIFT_INTERVAL_MIN = 5;
    public static final int GIFT_INTERVAL_MAX = 7 * 24 * 60;

    private DeltamentProfileMusic() {
    }

    public static boolean isCatEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_CAT, false);
    }

    public static void setCatEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_CAT, enabled).apply();
    }

    public static boolean isMusicEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_MUSIC, true);
    }

    public static void setMusicEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_MUSIC, enabled).apply();
    }

    public static boolean isGiftOfDayEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_GIFT_OF_DAY, true);
    }

    public static void setGiftOfDayEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_GIFT_OF_DAY, enabled).apply();
    }

    /** Repeat interval of the ritual, in minutes, clamped to a sane range. */
    public static int getGiftOfDayIntervalMinutes() {
        int minutes = MessagesController.getGlobalMainSettings()
                .getInt(PREF_GIFT_INTERVAL, GIFT_INTERVAL_DEFAULT);
        if (minutes < GIFT_INTERVAL_MIN || minutes > GIFT_INTERVAL_MAX) {
            minutes = GIFT_INTERVAL_DEFAULT;
        }
        return minutes;
    }

    public static void setGiftOfDayIntervalMinutes(int minutes) {
        if (minutes < GIFT_INTERVAL_MIN) {
            minutes = GIFT_INTERVAL_MIN;
        } else if (minutes > GIFT_INTERVAL_MAX) {
            minutes = GIFT_INTERVAL_MAX;
        }
        MessagesController.getGlobalMainSettings().edit()
                .putInt(PREF_GIFT_INTERVAL, minutes).apply();
    }

    public static boolean isConstellationsEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_CONSTELLATIONS, true);
    }

    public static void setConstellationsEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_CONSTELLATIONS, enabled).apply();
    }

    public static boolean isPhysicsEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(PREF_PHYSICS, true);
    }

    public static void setPhysicsEnabled(boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREF_PHYSICS, enabled).apply();
    }

    public static boolean isPlaying() {
        try {
            return DeltamentMusicPlayer.getInstance().isPlaying();
        } catch (Throwable ignore) {
            return false;
        }
    }

    public static DeltamentSoundCloudClient.Track getTrack() {
        try {
            return DeltamentMusicPlayer.getInstance().currentTrack;
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static long getPositionMs() {
        try {
            return DeltamentMusicPlayer.getInstance().getPositionMs();
        } catch (Throwable ignore) {
            return 0L;
        }
    }

    /** Stable tempo estimate for the current track, in BPM. */
    public static float getBpm() {
        final DeltamentSoundCloudClient.Track track = getTrack();
        final long seed = track == null || track.id == 0L ? 424242L : Math.abs(track.id);
        return 96f + (seed % 45L);
    }

    /** Sharp attack / exponential decay kick, 0..1, sampled analytically. */
    public static float getBeat() {
        if (!isMusicEnabled() || !isPlaying()) {
            return 0f;
        }
        final double beats = getPositionMs() / 60000.0 * getBpm();
        final double frac = beats - Math.floor(beats);
        final int indexInBar = (int) (Math.floor(beats) % 4L);
        // 4/4 pattern: strong downbeat, softer on 3, light on 2 and 4.
        final float accent = indexInBar == 0 ? 1f : (indexInBar == 2 ? 0.9f : 0.64f);
        return (float) Math.exp(-frac * 6.4) * accent;
    }

    /** Smoother energy envelope used by the visualizer bars, 0..1. */
    public static float getLevel() {
        if (!isMusicEnabled() || !isPlaying()) {
            return 0f;
        }
        final double beats = getPositionMs() / 60000.0 * getBpm();
        final float slow = 0.5f + 0.5f * (float) Math.sin(beats * Math.PI / 6.0);
        return clamp01(0.16f + 0.58f * getBeat() + 0.34f * slow);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}

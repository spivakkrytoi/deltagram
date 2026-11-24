package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Floating "now playing" capsule shown above the main tab bar.
 *
 * <p>It mirrors the app-wide {@link DeltamentMusicPlayer}: artwork, title,
 * artist, a slim progress line and play/pause + next controls. Tapping the
 * capsule (anywhere but the buttons) opens the full now-playing panel of
 * {@link DeltamentMusicSheet} with the seek bar and live lyrics.</p>
 */
public class DeltamentMiniPlayerView extends FrameLayout {

    private static final int COLOR_BG = 0xF21C1C1E;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_SECONDARY = 0xB3FFFFFF;
    private static final int COLOR_BLUE = 0xFF3B7DF6;

    private final DeltamentMusicPlayer music = DeltamentMusicPlayer.getInstance();
    private final Runnable onOpen;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView artwork;
    private TextView titleView;
    private TextView artistView;
    private ImageView playButton;
    private View progressTrack;
    private View progressFill;

    private boolean bound;
    private long boundTrackId = Long.MIN_VALUE;
    private boolean lastPlaying;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateProgress();
            updatePlayIcon();
            handler.postDelayed(this, 500);
        }
    };

    private final DeltamentMusicPlayer.Listener playerListener = new DeltamentMusicPlayer.Listener() {
        @Override
        public void onTrackChanged(DeltamentSoundCloudClient.Track track) {
            bind(track);
        }

        @Override
        public void onPlaybackStateChanged(boolean playing, boolean prepared) {
            updatePlayIcon();
            updateProgress();
        }

        @Override
        public void onPlaybackError(String message) {
            updatePlayIcon();
        }
    };

    public DeltamentMiniPlayerView(Context context, Runnable onOpen) {
        super(context);
        this.onOpen = onOpen;
        build();
        music.addListener(playerListener);
        bind(music.currentTrack);
    }

    /** Re-evaluates the "show capsule" preference, e.g. after leaving Settings. */
    public void refreshVisibility() {
        bound = false;
        boundTrackId = Long.MIN_VALUE;
        bind(music.currentTrack);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        music.addListener(playerListener);
        bind(music.currentTrack);
        handler.removeCallbacks(ticker);
        if (music.currentTrack != null) {
            handler.post(ticker);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(ticker);
        music.removeListener(playerListener);
    }

    // ------------------------------------------------------------------- build

    private void build() {
        final Context context = getContext();

        final GradientDrawable background = new GradientDrawable();
        background.setColor(COLOR_BG);
        background.setCornerRadius(dp(20));
        background.setStroke(dp(1), 0x24FFFFFF);
        setBackground(background);
        setClipToOutline(true);
        setClickable(true);
        setOnClickListener(v -> {
            if (onOpen != null && music.currentTrack != null) {
                onOpen.run();
            }
        });

        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(6), dp(8), dp(10));
        addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.MATCH_PARENT, Gravity.BOTTOM));

        final FrameLayout artworkHolder = new FrameLayout(context);
        final GradientDrawable artworkBackground = new GradientDrawable();
        artworkBackground.setColor(0xFF3A3A3C);
        artworkBackground.setCornerRadius(dp(12));
        artworkHolder.setBackground(artworkBackground);
        artworkHolder.setClipToOutline(true);
        final ImageView note = new ImageView(context);
        note.setImageResource(R.drawable.large_notes);
        note.setAlpha(0.55f);
        note.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        artworkHolder.addView(note, LayoutHelper.createFrame(dp(22), dp(22), Gravity.CENTER));
        artwork = new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artworkHolder.addView(artwork, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        row.addView(artworkHolder, LayoutHelper.createLinear(44, 44));

        final LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(10), 0, dp(6), 0);
        titleView = new TextView(context);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTextSize(14);
        titleView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(titleView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        artistView = new TextView(context);
        artistView.setTextColor(COLOR_SECONDARY);
        artistView.setTextSize(12);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(artistView, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        playButton = new ImageView(context);
        playButton.setScaleType(ImageView.ScaleType.CENTER);
        playButton.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        playButton.setBackground(ripple(dp(18)));
        playButton.setOnClickListener(v -> music.toggle());
        row.addView(playButton, LayoutHelper.createLinear(36, 36));

        final ImageView next = new ImageView(context);
        next.setImageResource(R.drawable.ic_action_next);
        next.setScaleType(ImageView.ScaleType.CENTER);
        next.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        next.setBackground(ripple(dp(18)));
        next.setOnClickListener(v -> music.next());
        row.addView(next, LayoutHelper.createLinear(32, 36));

        progressTrack = new View(context);
        progressTrack.setBackgroundColor(0x24FFFFFF);
        addView(progressTrack, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, 2, Gravity.BOTTOM));

        progressFill = new View(context);
        progressFill.setBackgroundColor(COLOR_BLUE);
        addView(progressFill, LayoutHelper.createFrame(0, 2, Gravity.BOTTOM));
    }

    // ----------------------------------------------------------------- binding

    private void bind(DeltamentSoundCloudClient.Track track) {
        if (!MessagesController.getGlobalMainSettings().getBoolean("dg_music_capsule", true)) {
            handler.removeCallbacks(ticker);
            animate().cancel();
            setVisibility(GONE);
            setAlpha(0f);
            return;
        }
        final long trackId = track == null ? Long.MIN_VALUE : track.id;
        if (bound && trackId == boundTrackId) {
            updatePlayIcon();
            return;
        }
        bound = true;
        boundTrackId = trackId;

        if (track == null) {
            handler.removeCallbacks(ticker);
            animate().cancel();
            animate().alpha(0f).scaleX(0.96f).scaleY(0.96f).setDuration(160)
                    .withEndAction(() -> setVisibility(GONE)).start();
            return;
        }

        titleView.setText(TextUtils.isEmpty(track.title) ? "DeltaGram Music" : track.title);
        artistView.setText(TextUtils.isEmpty(track.artist) ? "SoundCloud" : track.artist);
        if (TextUtils.isEmpty(track.artworkUrl)) {
            artwork.setTag(null);
            artwork.setImageDrawable(null);
        } else {
            artwork.setImageDrawable(null);
            DeltamentArtwork.load(track.artworkUrl, artwork);
        }
        updatePlayIcon();
        updateProgress();

        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setScaleX(0.94f);
            setScaleY(0.94f);
            setVisibility(VISIBLE);
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).start();
        }
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void updatePlayIcon() {
        if (playButton == null) {
            return;
        }
        final boolean playing = music.isPlaying();
        if (playing != lastPlaying || playButton.getDrawable() == null) {
            lastPlaying = playing;
            playButton.setImageResource(playing
                    ? R.drawable.ic_action_pause
                    : R.drawable.ic_action_play);
        }
    }

    private void updateProgress() {
        if (progressFill == null || progressTrack == null) {
            return;
        }
        final long duration = music.getDurationMs();
        final float fraction = duration > 0
                ? Math.max(0f, Math.min(1f, music.getPositionMs() / (float) duration))
                : 0f;
        final int width = (int) (progressTrack.getWidth() * fraction);
        final LayoutParams params = (LayoutParams) progressFill.getLayoutParams();
        if (params.width != width) {
            params.width = width;
            progressFill.setLayoutParams(params);
        }
    }

    // ------------------------------------------------------------------ helpers

    private RippleDrawable ripple(float radius) {
        final GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), null, mask);
    }
}

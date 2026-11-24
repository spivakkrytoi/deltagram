package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Full-height "now playing" panel inside the DeltaGram music sheet: big
 * artwork, a draggable seek bar and time-synced lyrics that follow playback.
 *
 * <p>Lyrics come from LRCLIB (see {@link DeltamentLyrics}); when no synced
 * version exists the plain text is shown without highlighting.</p>
 */
public class DeltamentNowPlayingView extends LinearLayout {

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF8E8E93;
    private static final int COLOR_FIELD = 0xFF2C2C2E;
    private static final int COLOR_BLUE = 0xFF3B7DF6;
    private static final int COLOR_LYRIC_DIM = 0x66FFFFFF;
    private static final int COLOR_PLAY_ON_LIGHT = 0xFF6B1622;

    private final DeltamentMusicPlayer music;
    private final Runnable onCollapse;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private ImageView cover;
    private TextView titleView;
    private TextView artistView;
    private SeekBar seekBar;
    private TextView elapsedView;
    private TextView totalView;
    private ImageView playButton;
    private LinearLayout lyricsContainer;
    private ScrollView lyricsScroll;
    private TextView lyricsStatus;

    private final ArrayList<DeltamentLyrics.Line> lines = new ArrayList<>();
    private final ArrayList<TextView> lineViews = new ArrayList<>();
    private boolean syncedLyrics;
    private int activeLine = -1;
    private int lyricsGeneration;
    private boolean userSeeking;
    private boolean lastPlaying;
    private boolean bound;
    private long boundTrackId = Long.MIN_VALUE;

    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
    private final ArrayList<TextView> speedChips = new ArrayList<>();

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
            handler.postDelayed(this, 200);
        }
    };

    private final DeltamentMusicPlayer.Listener playerListener = new DeltamentMusicPlayer.Listener() {
        @Override
        public void onTrackChanged(DeltamentSoundCloudClient.Track track) {
            bindTrack(track);
        }

        @Override
        public void onPlaybackStateChanged(boolean playing, boolean prepared) {
            updatePlayIcon();
            if (prepared) {
                updateTotal();
            }
        }

        @Override
        public void onPlaybackError(String message) {
            updatePlayIcon();
            if (lyricsStatus != null) {
                lyricsStatus.setText(message);
            }
        }
    };

    public DeltamentNowPlayingView(Context context, DeltamentMusicPlayer music, Runnable onCollapse) {
        super(context);
        this.music = music;
        this.onCollapse = onCollapse;
        setOrientation(VERTICAL);
        build();
        music.addListener(playerListener);
        bindTrack(music.currentTrack);
    }

    public void onShown() {
        bindTrack(music.currentTrack);
        updateTotal();
        updatePlayIcon();
        updateSpeedChips();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    public void onHidden() {
        handler.removeCallbacks(ticker);
    }

    /** Detaches from the player; called when the host sheet is dismissed. */
    public void release() {
        handler.removeCallbacksAndMessages(null);
        music.removeListener(playerListener);
        lyricsGeneration++;
    }

    // ------------------------------------------------------------------- build

    private void build() {
        final Context context = getContext();

        // Top row: collapse and a small label.
        final LinearLayout top = new LinearLayout(context);
        top.setOrientation(HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        addView(top, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));

        final TextView collapse = new TextView(context);
        collapse.setText("⌄");
        collapse.setTextSize(26);
        collapse.setTextColor(COLOR_TEXT);
        collapse.setGravity(Gravity.CENTER);
        collapse.setBackground(ripple(COLOR_FIELD, dp(18)));
        collapse.setOnClickListener(v -> {
            if (onCollapse != null) {
                onCollapse.run();
            }
        });
        pressable(collapse);
        top.addView(collapse, LayoutHelper.createLinear(38, 38));

        final TextView label = new TextView(context);
        label.setText("СЕЙЧАС ИГРАЕТ");
        label.setTextSize(11);
        label.setLetterSpacing(0.16f);
        label.setTextColor(COLOR_TEXT_SECONDARY);
        label.setGravity(Gravity.CENTER);
        top.addView(label, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Keeps the label visually centred against the collapse button.
        final View spacer = new View(context);
        top.addView(spacer, LayoutHelper.createLinear(38, 38));

        // Artwork.
        final FrameLayout artwork = new FrameLayout(context);
        final GradientDrawable artworkBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3A3A3C, 0xFF242426});
        artworkBackground.setCornerRadius(dp(24));
        artwork.setBackground(artworkBackground);
        artwork.setClipToOutline(true);
        final ImageView note = new ImageView(context);
        note.setImageResource(R.drawable.large_notes);
        note.setAlpha(0.32f);
        note.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        artwork.addView(note, LayoutHelper.createFrame(dp(72), dp(72), Gravity.CENTER));
        cover = new ImageView(context);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.addView(cover, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(artwork, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 196, 0f, 0, 6, 0, 8));

        titleView = new TextView(context);
        titleView.setTextSize(22);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        artistView = new TextView(context);
        artistView.setTextSize(14);
        artistView.setTextColor(0xB3FFFFFF);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        artistView.setPadding(0, dp(4), 0, 0);
        addView(artistView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        seekBar = new SeekBar(context);
        seekBar.setMax(1);
        if (seekBar.getProgressDrawable() != null) {
            seekBar.getProgressDrawable().setColorFilter(
                    new PorterDuffColorFilter(0xCCFFFFFF, PorterDuff.Mode.SRC_IN));
        }
        if (seekBar.getThumb() != null) {
            seekBar.getThumb().setColorFilter(
                    new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        }
        seekBar.setPadding(dp(4), 0, dp(4), 0);
        addView(seekBar, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 6, 0, 0));

        final LinearLayout times = new LinearLayout(context);
        times.setOrientation(HORIZONTAL);
        elapsedView = new TextView(context);
        elapsedView.setText("0:00");
        elapsedView.setTextSize(12);
        elapsedView.setTextColor(COLOR_TEXT_SECONDARY);
        totalView = new TextView(context);
        totalView.setText("0:00");
        totalView.setTextSize(12);
        totalView.setTextColor(COLOR_TEXT_SECONDARY);
        times.addView(elapsedView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        times.addView(new View(context), LayoutHelper.createLinear(0, 1, 1f));
        times.addView(totalView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        addView(times, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Transport.
        final LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(6), 0, dp(6));

        final ImageView previous = transportButton(context, R.drawable.ic_action_previous);
        previous.setOnClickListener(v -> music.previous());
        controls.addView(previous, LayoutHelper.createLinear(dp(38), dp(38), 0, 0, 18, 0, 18));

        playButton = transportButton(context, R.drawable.ic_action_play);
        final GradientDrawable playBackground = new GradientDrawable();
        playBackground.setShape(GradientDrawable.OVAL);
        playBackground.setColor(COLOR_TEXT);
        playButton.setBackground(playBackground);
        playButton.setColorFilter(new PorterDuffColorFilter(COLOR_PLAY_ON_LIGHT, PorterDuff.Mode.SRC_IN));
        playButton.setOnClickListener(v -> music.toggle());
        playButton.setPadding(dp(14), dp(14), dp(14), dp(14));
        controls.addView(playButton, LayoutHelper.createLinear(dp(54), dp(54)));

        final ImageView next = transportButton(context, R.drawable.ic_action_next);
        next.setOnClickListener(v -> music.next());
        controls.addView(next, LayoutHelper.createLinear(dp(38), dp(38), 0, 18, 0, 18, 0));

        addView(controls, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        // Playback speed: 0.5x .. 2x, remembered by DeltamentMusicPlayer.
        final LinearLayout speedRow = new LinearLayout(context);
        speedRow.setOrientation(HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER);
        speedRow.setPadding(0, dp(2), 0, dp(6));
        addView(speedRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        for (int i = 0; i < SPEEDS.length; i++) {
            final float value = SPEEDS[i];
            final TextView chip = new TextView(context);
            chip.setText(speedLabel(value));
            chip.setTextSize(12);
            chip.setGravity(Gravity.CENTER);
            chip.setOnClickListener(v -> {
                music.setSpeed(value);
                updateSpeedChips();
            });
            speedRow.addView(chip, LayoutHelper.createLinear(0, 28, 1f, 2, 0, 2, 0));
            speedChips.add(chip);
        }
        updateSpeedChips();

        // Lyrics header.
        final LinearLayout lyricsHeader = new LinearLayout(context);
        lyricsHeader.setOrientation(HORIZONTAL);
        lyricsHeader.setGravity(Gravity.CENTER_VERTICAL);
        final TextView lyricsLabel = new TextView(context);
        lyricsLabel.setText("ТЕКСТ");
        lyricsLabel.setTextSize(11);
        lyricsLabel.setLetterSpacing(0.16f);
        lyricsLabel.setTextColor(COLOR_TEXT_SECONDARY);
        lyricsHeader.addView(lyricsLabel, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));
        final TextView source = new TextView(context);
        source.setText("LRCLIB");
        source.setTextSize(10);
        source.setLetterSpacing(0.1f);
        source.setTextColor(0x40FFFFFF);
        lyricsHeader.addView(source, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        lyricsHeader.setPadding(0, dp(4), 0, dp(4));
        addView(lyricsHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 28));

        final FrameLayout lyricsArea = new FrameLayout(context);
        lyricsScroll = new ScrollView(context);
        lyricsScroll.setVerticalScrollBarEnabled(false);
        lyricsScroll.setFillViewport(false);
        lyricsContainer = new LinearLayout(context);
        lyricsContainer.setOrientation(VERTICAL);
        lyricsContainer.setPadding(0, dp(4), 0, dp(20));
        lyricsScroll.addView(lyricsContainer, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        lyricsArea.addView(lyricsScroll, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        lyricsStatus = new TextView(context);
        lyricsStatus.setTextSize(15);
        lyricsStatus.setTextColor(COLOR_LYRIC_DIM);
        lyricsStatus.setGravity(Gravity.CENTER);
        lyricsStatus.setPadding(dp(18), dp(18), dp(18), dp(18));
        lyricsArea.addView(lyricsStatus, LayoutHelper.createFrame(
                LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        addView(lyricsArea, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    music.seekTo(progress);
                    elapsedView.setText(formatTime(progress));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                userSeeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                userSeeking = false;
            }
        });
    }

    private ImageView transportButton(Context context, int res) {
        final ImageView button = new ImageView(context);
        button.setImageResource(res);
        button.setScaleType(ImageView.ScaleType.CENTER);
        button.setColorFilter(new PorterDuffColorFilter(COLOR_TEXT, PorterDuff.Mode.SRC_IN));
        button.setBackground(ripple(COLOR_FIELD, dp(22)));
        pressable(button);
        return button;
    }

    // ------------------------------------------------------------------ binding

    private void bindTrack(DeltamentSoundCloudClient.Track track) {
        if (titleView == null) {
            return;
        }
        final long trackId = track == null ? Long.MIN_VALUE : track.id;
        if (bound && trackId == boundTrackId) {
            return;
        }
        bound = true;
        boundTrackId = trackId;
        if (track == null) {
            titleView.setText("Ничего не играет");
            artistView.setText("Выбери трек в списке");
            cover.setImageDrawable(null);
            seekBar.setMax(1);
            seekBar.setProgress(0);
            totalView.setText("0:00");
            elapsedView.setText("0:00");
            showLyricsPlaceholder("Текст появится, когда заиграет трек");
            return;
        }
        titleView.setText(track.title);
        artistView.setText(track.artist);
        seekBar.setMax(1);
        seekBar.setProgress(0);
        elapsedView.setText("0:00");
        updateTotal();
        if (TextUtils.isEmpty(track.artworkUrl)) {
            cover.setTag(null);
            cover.setImageDrawable(null);
        } else {
            cover.setImageDrawable(null);
            DeltamentArtwork.load(track.artworkUrl, cover);
        }
        loadLyrics(track);
    }

    private void updateTotal() {
        if (totalView == null) {
            return;
        }
        final long duration = music.getDurationMs();
        if (duration > 0) {
            final int value = (int) Math.min(Integer.MAX_VALUE, duration);
            if (seekBar.getMax() != value) {
                seekBar.setMax(value);
            }
            totalView.setText(formatTime(value));
        }
    }

    private void updatePlayIcon() {
        if (playButton == null) {
            return;
        }
        final boolean playing = music.isPlaying();
        if (playing != lastPlaying) {
            lastPlaying = playing;
            playButton.setImageResource(playing ? R.drawable.ic_action_pause : R.drawable.ic_action_play);
            pop(playButton);
        }
    }

    private void updateSpeedChips() {
        if (speedChips.isEmpty()) {
            return;
        }
        final float current = music.getSpeed();
        for (int i = 0; i < speedChips.size(); i++) {
            final boolean active = Math.abs(SPEEDS[i] - current) < 0.01f;
            final TextView chip = speedChips.get(i);
            chip.setTextColor(active ? COLOR_TEXT : COLOR_TEXT_SECONDARY);
            chip.setBackground(rounded(active ? COLOR_BLUE : COLOR_FIELD, dp(10)));
            chip.setTypeface(Typeface.create(Typeface.DEFAULT,
                    active ? Typeface.BOLD : Typeface.NORMAL));
        }
    }

    private static String speedLabel(float value) {
        if (value == Math.round(value)) {
            return ((int) value) + "×";
        }
        if (value == 0.75f) {
            return "0.75×";
        }
        if (value == 1.25f) {
            return "1.25×";
        }
        return String.format(Locale.US, "%s×", String.valueOf(value));
    }

    // ------------------------------------------------------------------- lyrics

    private void loadLyrics(DeltamentSoundCloudClient.Track track) {
        final int generation = ++lyricsGeneration;
        lines.clear();
        lineViews.clear();
        lyricsContainer.removeAllViews();
        syncedLyrics = false;
        activeLine = -1;
        showLyricsPlaceholder("Ищу текст…");
        DeltamentLyrics.fetch(track, result -> {
            if (generation != lyricsGeneration) {
                return;
            }
            renderLyrics(result);
        });
    }

    private void renderLyrics(DeltamentLyrics.Lyrics lyrics) {
        lyricsContainer.removeAllViews();
        lines.clear();
        lineViews.clear();
        activeLine = -1;
        if (lyrics == null || !lyrics.found) {
            showLyricsPlaceholder("Текст не найден. Если он есть в базе LRCLIB, появится здесь автоматически");
            return;
        }
        if (lyrics.instrumental) {
            showLyricsPlaceholder("Инструментальный трек — без текста");
            return;
        }
        lyricsScroll.setVisibility(VISIBLE);
        lyricsStatus.setVisibility(GONE);
        lines.addAll(lyrics.lines);
        syncedLyrics = lyrics.synced;
        for (int i = 0; i < lines.size(); i++) {
            final TextView lineView = new TextView(getContext());
            lineView.setText(lines.get(i).text);
            lineView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            lineView.setTextColor(syncedLyrics ? COLOR_LYRIC_DIM : 0xCCFFFFFF);
            lineView.setGravity(Gravity.CENTER);
            lineView.setLineSpacing(dp(3), 1f);
            lineView.setPadding(dp(6), dp(7), dp(6), dp(7));
            if (syncedLyrics) {
                // Like in Yandex Music: tap a line to jump to that moment.
                final long lineTime = lines.get(i).timeMs;
                lineView.setOnClickListener(v -> music.seekTo(lineTime));
            }
            lyricsContainer.addView(lineView, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            lineViews.add(lineView);
        }
        updateActiveLine(true);
    }

    private void showLyricsPlaceholder(String text) {
        lyricsStatus.setText(text);
        lyricsStatus.setVisibility(VISIBLE);
        lyricsScroll.setVisibility(GONE);
        syncedLyrics = false;
        lines.clear();
        lineViews.clear();
        lyricsContainer.removeAllViews();
    }

    private void updateActiveLine(boolean force) {
        if (!syncedLyrics || lineViews.isEmpty()) {
            return;
        }
        final long position = music.getPositionMs() + 120L;
        int index = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).timeMs <= position) {
                index = i;
            } else {
                break;
            }
        }
        if (index == activeLine && !force) {
            return;
        }
        final int previous = activeLine;
        activeLine = index;
        if (previous >= 0 && previous < lineViews.size()) {
            final TextView old = lineViews.get(previous);
            old.setTextColor(COLOR_LYRIC_DIM);
            old.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        }
        if (index >= 0 && index < lineViews.size()) {
            final TextView current = lineViews.get(index);
            current.setTextColor(COLOR_TEXT);
            current.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            lyricsScroll.post(() -> {
                final int y = current.getTop()
                        - (lyricsScroll.getHeight() - current.getHeight()) / 2;
                lyricsScroll.smoothScrollTo(0, Math.max(0, y));
            });
        }
    }

    // -------------------------------------------------------------------- tick

    private void tick() {
        if (music.isPrepared()) {
            final long position = music.getPositionMs();
            if (!userSeeking) {
                seekBar.setProgress((int) Math.min(Integer.MAX_VALUE, position));
                elapsedView.setText(formatTime((int) position));
            }
            updateTotal();
        }
        updatePlayIcon();
        updateActiveLine(false);
    }

    // ------------------------------------------------------------------ helpers

    private static String formatTime(int ms) {
        final int totalSeconds = Math.max(0, ms) / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private static GradientDrawable rounded(int color, float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private static Drawable ripple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(fill, radius), null);
    }

    private static void pressable(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f).setDuration(240).start();
                    break;
            }
            return false;
        });
    }

    private static void pop(View view) {
        view.animate().cancel();
        view.setScaleX(0.85f);
        view.setScaleY(0.85f);
        view.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
    }
}

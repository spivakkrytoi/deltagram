package org.telegram.ui.Wallet;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * DeltaGram music sheet. Reproduces the reference layout: close button, centred
 * title, queue action, rounded search field, three segmented switches and a
 * result list. SoundCloud is the default source; "Из чатов" searches music in
 * the user's messages through the regular MTProto global search.
 */
public class DeltamentMusicSheet extends BottomSheet {

    private static final int COLOR_BG = 0xFF1C1C1E;
    private static final int COLOR_FIELD = 0xFF2C2C2E;
    private static final int COLOR_BLUE = 0xFF3B7DF6;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_SECONDARY = 0xFF8E8E93;
    private static final int COLOR_STATUS = 0xFFE8E8EA;

    private static final int SOURCE_SOUNDCLOUD = 0;
    private static final int SOURCE_CHATS = 1;

    private static final int TYPE_TRACKS = 0;
    private static final int TYPE_ALBUMS = 1;

    private static final int MODE_SEARCH = 0;
    private static final int MODE_LIKED = 1;
    private static final int MODE_FOR_YOU = 2;

    private static final String PREFS_LIKES = "deltament_soundcloud_likes";
    private static final String KEY_LIKES = "tracks";

    private final Context context;
    private final int currentAccount;

    private LinearLayout root;
    private TextView titleView;
    private TextView queueButton;
    private EditText searchField;
    private SegmentRow sourceRow;
    private SegmentRow typeRow;
    private SegmentRow modeRow;
    private TextView statusView;
    private LinearLayout listContainer;
    private ScrollView scrollView;
    private LinearLayout miniPlayer;
    private TextView miniControl;
    private TextView miniTitle;
    private View searchContainer;
    private View headerView;
    private DeltamentNowPlayingView nowPlayingView;
    private boolean playerExpanded;
    private boolean openPlayerRequested;

    private final ArrayList<DeltamentSoundCloudClient.Track> visible = new ArrayList<>();
    private final DeltamentMusicPlayer music = DeltamentMusicPlayer.getInstance();
    private ArrayList<DeltamentSoundCloudClient.Track> queue;

    private boolean queueMode;

    private int source = SOURCE_SOUNDCLOUD;
    private int type = TYPE_TRACKS;
    private int mode = MODE_SEARCH;
    private int loadGeneration;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable searchRunnable = this::reload;

    public DeltamentMusicSheet(Context context) {
        super(context, false);
        this.context = context;
        this.currentAccount = UserConfig.selectedAccount;

        setBackgroundColor(COLOR_BG);
        setApplyTopPadding(false);
        setApplyBottomPadding(true);
        setCanDismissWithSwipe(true);
        setDimBehindAlpha(75);
        // The sheet has a search field, so the window must accept IME focus.
        // Without this the keyboard never opens because BottomSheet was created
        // with needFocus=false and sets FLAG_ALT_FOCUSABLE_IM.
        setFocusable(true);

        final int screenHeight = AndroidUtilities.displaySize.y;
        final int sheetHeight = Math.max(dp(340), Math.min(dp(700), screenHeight - dp(90)));

        root = new FixedHeightLinearLayout(context, sheetHeight);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(6));
        setCustomView(root);

        buildHeader();
        buildSearch();
        buildSegments();
        buildStatus();
        buildList();
        buildMiniPlayer();

        this.queue = music.queue;
        music.addListener(musicListener);
        syncMiniFromPlayer();

        setOnDismissListener(new Runnable() {
            @Override
            public void run() {
                // Playback lives in DeltamentMusicPlayer, so closing the sheet
                // keeps the track running; just stop listening to its updates.
                music.removeListener(musicListener);
                handler.removeCallbacksAndMessages(null);
                if (nowPlayingView != null) {
                    nowPlayingView.release();
                }
            }
        });

        DeltamentSoundCloudClient.preload(new DeltamentSoundCloudClient.Callback<Boolean>() {            @Override
            public void onSuccess(Boolean result) {
                if (source == SOURCE_SOUNDCLOUD && mode == MODE_SEARCH && visible.isEmpty() && TextUtils.isEmpty(query())) {
                    statusView.setText("введите запрос");
                }
            }

            @Override
            public void onError(String message) {
                if (source == SOURCE_SOUNDCLOUD && mode == MODE_SEARCH && visible.isEmpty() && TextUtils.isEmpty(query())) {
                    statusView.setText(message);
                }
            }
        });
    }

    // ---------------------------------------------------------------- ui build

    /** Opens the sheet directly on the full now-playing panel. */
    public DeltamentMusicSheet openPlayer() {
        openPlayerRequested = true;
        return this;
    }

    @Override
    public void show() {
        super.show();
        if (openPlayerRequested) {
            openPlayerRequested = false;
            AndroidUtilities.runOnUIThread(this::expandPlayer);
        }
    }

    private void buildHeader() {
        final FrameLayout header = new FrameLayout(context);
        headerView = header;
        root.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        final TextView close = new TextView(context);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(COLOR_TEXT);
        close.setGravity(Gravity.CENTER);
        close.setBackground(ripple(COLOR_FIELD, dp(18)));
        close.setOnClickListener(v -> dismiss());
        pressable(close);
        header.addView(close, LayoutHelper.createFrame(36, 36, Gravity.START | Gravity.CENTER_VERTICAL));

        titleView = new TextView(context);
        titleView.setText("Музыка");
        titleView.setTextSize(18);
        titleView.setTextColor(COLOR_TEXT);
        titleView.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        titleView.setSingleLine(true);
        titleView.setGravity(Gravity.CENTER);
        header.addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        queueButton = new TextView(context);
        queueButton.setText("Очередь");
        queueButton.setTextSize(15);
        queueButton.setTextColor(COLOR_BLUE);
        queueButton.setGravity(Gravity.CENTER);
        queueButton.setPadding(dp(10), dp(6), dp(2), dp(6));
        queueButton.setOnClickListener(v -> setQueueMode(!queueMode));
        pressable(queueButton);
        header.addView(queueButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 36, Gravity.END | Gravity.CENTER_VERTICAL));
    }

    private void buildSearch() {
        final LinearLayout container = new LinearLayout(context);
        searchContainer = container;
        container.setOrientation(LinearLayout.VERTICAL);
        root.addView(container, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 8, 0, 0));

        searchField = new EditText(context);
        searchField.setHint("Поиск");
        searchField.setTextSize(16);
        searchField.setTextColor(COLOR_TEXT);
        searchField.setHintTextColor(COLOR_TEXT_SECONDARY);
        searchField.setSingleLine(true);
        searchField.setInputType(InputType.TYPE_CLASS_TEXT);
        searchField.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchField.setBackground(rounded(COLOR_FIELD, dp(24)));
        searchField.setPadding(dp(20), 0, dp(20), 0);
        searchField.setGravity(Gravity.CENTER_VERTICAL);
        searchField.setFocusableInTouchMode(true);
        searchField.setCursorVisible(true);
        searchField.setOnClickListener(v -> {
            searchField.requestFocus();
            AndroidUtilities.showKeyboard(searchField);
        });
        searchField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || event != null && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER) {
                searchField.clearFocus();
                AndroidUtilities.hideKeyboard(searchField);
                return true;
            }
            return false;
        });
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                handler.removeCallbacks(searchRunnable);
                handler.postDelayed(searchRunnable, 350);
            }
        });
        container.addView(searchField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
    }

    private void buildSegments() {
        sourceRow = new SegmentRow(context, new String[]{"SoundCloud", "Из чатов"});
        sourceRow.setSelected(source, false);
        sourceRow.setListener(index -> {
            if (source == index) {
                return;
            }
            source = index;
            reload();
        });
        root.addView(sourceRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 0, 10, 0, 0));

        typeRow = new SegmentRow(context, new String[]{"Треки", "Альбомы"});
        typeRow.setSelected(type, false);
        typeRow.setListener(index -> {
            if (type == index) {
                return;
            }
            type = index;
            if (mode != MODE_SEARCH) {
                mode = MODE_SEARCH;
                modeRow.setSelected(mode, false);
            }
            reload();
        });
        root.addView(typeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 0, 10, 0, 0));

        modeRow = new SegmentRow(context, new String[]{"Поиск", "Лайкнутые", "Для вас"});
        modeRow.setSelected(mode, false);
        modeRow.setListener(index -> {
            if (mode == index) {
                return;
            }
            mode = index;
            reload();
        });
        root.addView(modeRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 0, 10, 0, 0));
    }

    private void buildStatus() {
        statusView = new TextView(context);
        statusView.setTextSize(14);
        statusView.setTextColor(COLOR_STATUS);
        statusView.setPadding(dp(8), dp(12), dp(8), dp(4));
        statusView.setText("");
        root.addView(statusView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
    }

    private void buildList() {
        scrollView = new ScrollView(context);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(false);
        listContainer = new LinearLayout(context);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(listContainer, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        root.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
    }

    private void buildMiniPlayer() {
        miniPlayer = new LinearLayout(context);
        miniPlayer.setOrientation(LinearLayout.HORIZONTAL);
        miniPlayer.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.setBackground(rounded(COLOR_FIELD, dp(16)));
        miniPlayer.setPadding(dp(6), dp(6), dp(6), dp(6));
        miniPlayer.setVisibility(View.GONE);
        // Tapping the bar (anywhere but the transport/close buttons) opens the
        // full now-playing panel with the seek bar and live lyrics.
        miniPlayer.setOnClickListener(v -> expandPlayer());
        pressable(miniPlayer);
        root.addView(miniPlayer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 54, 0, 0, 8, 0, 0));

        miniControl = new TextView(context);
        miniControl.setText("▶");
        miniControl.setTextSize(18);
        miniControl.setTextColor(COLOR_TEXT);
        miniControl.setGravity(Gravity.CENTER);
        miniControl.setBackground(ripple(COLOR_FIELD, dp(20)));
        miniControl.setOnClickListener(v -> togglePlayback());
        pressable(miniControl);
        miniPlayer.addView(miniControl, LayoutHelper.createLinear(40, 40));

        miniTitle = new TextView(context);
        miniTitle.setTextSize(14);
        miniTitle.setTextColor(COLOR_TEXT);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniTitle.setPadding(dp(4), 0, dp(4), 0);
        miniPlayer.addView(miniTitle, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        // Explicit entry point to the lyrics/seek panel; the whole bar is
        // tappable too, but the pill makes the feature discoverable.
        final TextView lyricsButton = new TextView(context);
        lyricsButton.setText("Текст");
        lyricsButton.setTextSize(12);
        lyricsButton.setTextColor(COLOR_BLUE);
        lyricsButton.setGravity(Gravity.CENTER);
        lyricsButton.setBackground(ripple(COLOR_FIELD, dp(14)));
        lyricsButton.setPadding(dp(10), dp(4), dp(10), dp(4));
        lyricsButton.setOnClickListener(v -> expandPlayer());
        pressable(lyricsButton);
        miniPlayer.addView(lyricsButton, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, 0, 0, 0, 4, 0));

        final TextView close = new TextView(context);
        close.setText("✕");
        close.setTextSize(14);
        close.setTextColor(COLOR_TEXT_SECONDARY);
        close.setGravity(Gravity.CENTER);
        close.setOnClickListener(v -> stopPlayback());
        pressable(close);
        miniPlayer.addView(close, LayoutHelper.createLinear(36, 36));
    }

    // -------------------------------------------------------- now playing view

    /** Expands the mini player into the full-height "now playing" panel. */
    private void expandPlayer() {
        if (music.currentTrack == null) {
            return;
        }
        if (nowPlayingView == null) {
            nowPlayingView = new DeltamentNowPlayingView(context, music, this::collapsePlayer);
            root.addView(nowPlayingView, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, 0, 1f));
        }
        playerExpanded = true;
        headerView.setVisibility(View.GONE);
        searchContainer.setVisibility(View.GONE);
        sourceRow.setVisibility(View.GONE);
        typeRow.setVisibility(View.GONE);
        modeRow.setVisibility(View.GONE);
        statusView.setVisibility(View.GONE);
        scrollView.setVisibility(View.GONE);
        miniPlayer.setVisibility(View.GONE);
        searchField.clearFocus();
        AndroidUtilities.hideKeyboard(searchField);
        nowPlayingView.setVisibility(View.VISIBLE);
        nowPlayingView.onShown();
    }

    /** Returns from the now-playing panel to the search/queue list. */
    private void collapsePlayer() {
        if (!playerExpanded) {
            return;
        }
        playerExpanded = false;
        if (nowPlayingView != null) {
            nowPlayingView.onHidden();
            nowPlayingView.setVisibility(View.GONE);
        }
        headerView.setVisibility(View.VISIBLE);
        statusView.setVisibility(View.VISIBLE);
        scrollView.setVisibility(View.VISIBLE);
        miniPlayer.setVisibility(music.currentTrack != null ? View.VISIBLE : View.GONE);
        final int listModeVisibility = queueMode ? View.GONE : View.VISIBLE;
        searchContainer.setVisibility(listModeVisibility);
        sourceRow.setVisibility(listModeVisibility);
        typeRow.setVisibility(listModeVisibility);
        modeRow.setVisibility(listModeVisibility);
    }

    private static GradientDrawable rounded(int color, float radius) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /** Rounded background with a touch ripple, used for every tappable surface. */
    private static Drawable ripple(int fill, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x40FFFFFF), rounded(fill, radius), null);
    }

    /**
     * Small "press" feedback: the view shrinks a little on touch down and
     * springs back with a slight overshoot on release. Returns false so the
     * regular click listener still fires.
     */
    private static void pressable(View view) {
        view.setOnTouchListener((v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().cancel();
                    v.animate().scaleX(0.9f).scaleY(0.9f)
                            .setDuration(90)
                            .setInterpolator(new DecelerateInterpolator())
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().cancel();
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(240)
                            .setInterpolator(new OvershootInterpolator(2.5f))
                            .start();
                    break;
            }
            return false;
        });
    }

    /** Pops an icon after its glyph changed (play/pause, like/unlike). */
    private static void pop(View view) {
        view.animate().cancel();
        view.setScaleX(0.7f);
        view.setScaleY(0.7f);
        view.animate().scaleX(1f).scaleY(1f)
                .setDuration(220)
                .setInterpolator(new OvershootInterpolator(3f))
                .start();
    }

    private void showMiniPlayerAnimated() {
        if (playerExpanded) {
            return;
        }
        if (miniPlayer.getVisibility() != View.VISIBLE) {
            miniPlayer.setVisibility(View.VISIBLE);
            miniPlayer.setAlpha(0f);
            miniPlayer.setTranslationY(dp(14));
        }
        miniPlayer.animate().cancel();
        miniPlayer.animate().alpha(1f).translationY(0f)
                .setDuration(220)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    // --------------------------------------------------------------- behaviour

    private String query() {
        return searchField.getText() == null ? "" : searchField.getText().toString().trim();
    }

    private void reload() {
        if (queueMode) {
            return;
        }
        handler.removeCallbacks(searchRunnable);
        final int generation = ++loadGeneration;
        final String query = query();

        if (source == SOURCE_CHATS) {
            if (TextUtils.isEmpty(query)) {
                setStatus("введите запрос");
                renderTracks(new ArrayList<>());
                return;
            }
            searchChats(query, generation);
            return;
        }

        if (mode == MODE_LIKED) {
            final ArrayList<DeltamentSoundCloudClient.Track> liked = readLiked();
            if (!TextUtils.isEmpty(query)) {
                final String lower = query.toLowerCase();
                for (int i = liked.size() - 1; i >= 0; i--) {
                    final DeltamentSoundCloudClient.Track track = liked.get(i);
                    if (!track.title.toLowerCase().contains(lower) && !track.artist.toLowerCase().contains(lower)) {
                        liked.remove(i);
                    }
                }
            }
            renderTracks(liked);
            setStatus(liked.isEmpty() ? "нет лайкнутых треков" : "");
            return;
        }

        if (mode == MODE_FOR_YOU) {
            setStatus("загрузка…");
            DeltamentSoundCloudClient.charts(new ListCallback(generation));
            return;
        }

        if (TextUtils.isEmpty(query)) {
            setStatus("введите запрос");
            renderTracks(new ArrayList<>());
            return;
        }

        setStatus("загрузка…");
        if (type == TYPE_ALBUMS) {
            DeltamentSoundCloudClient.searchAlbums(query, new ListCallback(generation));
        } else {
            DeltamentSoundCloudClient.searchTracks(query, new ListCallback(generation));
        }
    }

    private class ListCallback implements DeltamentSoundCloudClient.Callback<ArrayList<DeltamentSoundCloudClient.Track>> {
        private final int generation;

        ListCallback(int generation) {
            this.generation = generation;
        }

        @Override
        public void onSuccess(ArrayList<DeltamentSoundCloudClient.Track> result) {
            if (generation != loadGeneration || queueMode) {
                return;
            }
            renderTracks(result);
            setStatus(result.isEmpty() ? "ничего не найдено" : "");
        }

        @Override
        public void onError(String message) {
            if (generation != loadGeneration || queueMode) {
                return;
            }
            renderTracks(new ArrayList<>());
            setStatus(message);
        }
    }

    private void setStatus(String text) {
        final String next = text == null ? "" : text;
        if (!next.equals(statusView.getText().toString())) {
            statusView.animate().cancel();
            statusView.animate().alpha(0f).setDuration(80).withEndAction(() -> {
                statusView.setText(next);
                statusView.animate().alpha(1f).setDuration(140).start();
            }).start();
        }
    }

    private void renderTracks(ArrayList<DeltamentSoundCloudClient.Track> tracks) {
        visible.clear();
        visible.addAll(tracks);
        listContainer.removeAllViews();
        for (DeltamentSoundCloudClient.Track track : tracks) {
            listContainer.addView(createTrackRow(track));
        }
    }

    private View createTrackRow(DeltamentSoundCloudClient.Track track) {
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setBackground(ripple(0x00000000, dp(12)));
        row.setOnClickListener(v -> {
            if (track.album) {
                openAlbum(track);
            } else if (music.currentTrack != null && music.currentTrack.id == track.id) {
                // Tapping the track that is already playing opens the player.
                expandPlayer();
            } else {
                playTrack(track);
            }
        });

        final ImageView artwork = new ImageView(context);
        artwork.setScaleType(ImageView.ScaleType.CENTER_CROP);
        artwork.setBackground(rounded(COLOR_FIELD, dp(10)));
        artwork.setClipToOutline(true);
        row.addView(artwork, LayoutHelper.createLinear(46, 46));
        loadArtwork(track.artworkUrl, artwork);

        final LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setPadding(dp(12), 0, dp(8), 0);
        row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

        final TextView title = new TextView(context);
        title.setText(track.title);
        title.setTextSize(15);
        title.setTextColor(COLOR_TEXT);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        textColumn.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        final TextView subtitle = new TextView(context);
        subtitle.setText(track.subtitle());
        subtitle.setTextSize(13);
        subtitle.setTextColor(COLOR_TEXT_SECONDARY);
        subtitle.setSingleLine(true);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        subtitle.setPadding(0, dp(2), 0, 0);
        textColumn.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (track.album) {
            final TextView arrow = new TextView(context);
            arrow.setText("›");
            arrow.setTextSize(22);
            arrow.setTextColor(COLOR_TEXT_SECONDARY);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, LayoutHelper.createLinear(28, 36));
        } else {
            final TextView like = new TextView(context);
            final boolean liked = isLiked(track.id);
            like.setText(liked ? "♥" : "♡");
            like.setTextSize(18);
            like.setTextColor(liked ? COLOR_BLUE : COLOR_TEXT_SECONDARY);
            like.setGravity(Gravity.CENTER);
            like.setOnClickListener(v -> {
                toggleLike(track);
                final boolean nowLiked = isLiked(track.id);
                like.setText(nowLiked ? "♥" : "♡");
                like.setTextColor(nowLiked ? COLOR_BLUE : COLOR_TEXT_SECONDARY);
                pop(like);
            });
            pressable(like);
            row.addView(like, LayoutHelper.createLinear(36, 36));

            final TextView duration = new TextView(context);
            duration.setText(formatDuration(track.durationMs));
            duration.setTextSize(12);
            duration.setTextColor(COLOR_TEXT_SECONDARY);
            duration.setGravity(Gravity.CENTER);
            row.addView(duration, LayoutHelper.createLinear(44, 36));
        }
        return row;
    }

    private static String formatDuration(long durationMs) {
        if (durationMs <= 0) {
            return "";
        }
        final long totalSeconds = durationMs / 1000;
        final long minutes = totalSeconds / 60;
        final long seconds = totalSeconds % 60;
        return minutes + ":" + (seconds < 10 ? "0" : "") + seconds;
    }

    private void openAlbum(DeltamentSoundCloudClient.Track album) {
        final int generation = ++loadGeneration;
        setStatus("загрузка альбома…");
        DeltamentSoundCloudClient.albumTracks(album.id, new DeltamentSoundCloudClient.Callback<ArrayList<DeltamentSoundCloudClient.Track>>() {
            @Override
            public void onSuccess(ArrayList<DeltamentSoundCloudClient.Track> result) {
                if (generation != loadGeneration || queueMode) {
                    return;
                }
                renderTracks(result);
                setStatus(result.isEmpty() ? "альбом пуст" : album.title + " · " + album.artist);
            }

            @Override
            public void onError(String message) {
                if (generation != loadGeneration || queueMode) {
                    return;
                }
                setStatus(message);
            }
        });
    }

    // --------------------------------------------------------------- chat search

    private void searchChats(String query, int generation) {
        setStatus("загрузка…");
        final TLRPC.TL_messages_searchGlobal request = new TLRPC.TL_messages_searchGlobal();
        request.q = query;
        request.limit = 40;
        request.filter = new TLRPC.TL_inputMessagesFilterMusic();
        request.flags |= 1;
        request.folder_id = 0;
        request.offset_id = 0;
        request.offset_rate = 0;
        request.offset_peer = new TLRPC.TL_inputPeerEmpty();

        ConnectionsManager.getInstance(currentAccount).sendRequestTyped(request, AndroidUtilities::runOnUIThread, (res, error) -> {
            if (generation != loadGeneration || queueMode) {
                return;
            }
            if (error != null || res == null) {
                setStatus("не удалось найти музыку в чатах");
                renderTracks(new ArrayList<>());
                return;
            }
            final MessagesController messagesController = MessagesController.getInstance(currentAccount);
            messagesController.putUsers(res.users, false);
            messagesController.putChats(res.chats, false);

            final ArrayList<DeltamentSoundCloudClient.Track> tracks = new ArrayList<>();
            final ArrayList<MessageObject> messageObjects = new ArrayList<>();
            for (int i = 0; i < res.messages.size(); i++) {
                final TLRPC.Message message = res.messages.get(i);
                final MessageObject messageObject = new MessageObject(currentAccount, message, false, true);
                if (!messageObject.isMusic() || messageObject.getDocument() == null) {
                    continue;
                }
                final DeltamentSoundCloudClient.Track track = new DeltamentSoundCloudClient.Track();
                track.id = messageObject.getId();
                track.title = messageObject.getMusicTitle();
                track.artist = messageObject.getMusicAuthor();
                track.durationMs = (long) messageObject.getDuration() * 1000L;
                if (TextUtils.isEmpty(track.title)) {
                    track.title = "Аудио";
                }
                tracks.add(track);
                messageObjects.add(messageObject);
            }
            renderChatTracks(tracks, messageObjects, query, res.next_rate, res.count);
            setStatus(tracks.isEmpty() ? "в чатах ничего не найдено" : "");
        });
    }

    private void renderChatTracks(ArrayList<DeltamentSoundCloudClient.Track> tracks,
                                  ArrayList<MessageObject> messageObjects,
                                  String query, int nextRate, int totalCount) {
        visible.clear();
        visible.addAll(tracks);
        listContainer.removeAllViews();
        for (int i = 0; i < tracks.size(); i++) {
            final DeltamentSoundCloudClient.Track track = tracks.get(i);
            final MessageObject messageObject = messageObjects.get(i);
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            row.setBackground(ripple(0x00000000, dp(12)));
            row.setOnClickListener(v -> {
                final MediaController.PlaylistGlobalSearchParams params =
                        new MediaController.PlaylistGlobalSearchParams(query, 0, 0, 0,
                                new FiltersView.MediaFilterData(0, 0, new TLRPC.TL_inputMessagesFilterMusic(),
                                        FiltersView.FILTER_TYPE_MUSIC));
                params.endReached = false;
                params.nextSearchRate = nextRate;
                params.totalCount = totalCount;
                params.folderId = 0;
                final ArrayList<MessageObject> playlist = new ArrayList<>(messageObjects);
                MediaController.getInstance().setPlaylist(playlist, messageObject, 0, params);
            });

            final View placeholder = new View(context);
            placeholder.setBackground(rounded(COLOR_FIELD, dp(10)));
            row.addView(placeholder, LayoutHelper.createLinear(46, 46));

            final LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            textColumn.setPadding(dp(12), 0, dp(8), 0);
            row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            final TextView title = new TextView(context);
            title.setText(track.title);
            title.setTextSize(15);
            title.setTextColor(COLOR_TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView subtitle = new TextView(context);
            subtitle.setText(track.artist);
            subtitle.setTextSize(13);
            subtitle.setTextColor(COLOR_TEXT_SECONDARY);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView duration = new TextView(context);
            duration.setText(formatDuration(track.durationMs));
            duration.setTextSize(12);
            duration.setTextColor(COLOR_TEXT_SECONDARY);
            duration.setGravity(Gravity.CENTER);
            row.addView(duration, LayoutHelper.createLinear(48, 36));

            listContainer.addView(row);
        }
    }

    // ------------------------------------------------------------------ playback

    private final DeltamentMusicPlayer.Listener musicListener = new DeltamentMusicPlayer.Listener() {
        @Override
        public void onTrackChanged(DeltamentSoundCloudClient.Track track) {
            if (track == null) {
                hideMiniPlayerAnimated();
                miniTitle.setText("");
                return;
            }
            miniTitle.setText(track.title);
            showMiniPlayerAnimated();
            updateMiniControl();
            setStatus("загрузка трека…");
        }

        @Override
        public void onPlaybackStateChanged(boolean playing, boolean prepared) {
            updateMiniControl();
            if (prepared) {
                setStatus("");
            }
        }

        @Override
        public void onPlaybackError(String message) {
            updateMiniControl();
            setStatus(message);
        }
    };

    private void updateMiniControl() {
        if (miniControl == null) {
            return;
        }
        final String next = music.isLoading() ? "…" : (music.isPlaying() ? "⏸" : "▶");
        if (!next.contentEquals(miniControl.getText())) {
            miniControl.setText(next);
            pop(miniControl);
        }
    }

    private void syncMiniFromPlayer() {
        final DeltamentSoundCloudClient.Track track = music.currentTrack;
        if (track == null) {
            miniPlayer.setVisibility(View.GONE);
            return;
        }
        miniTitle.setText(track.title);
        miniPlayer.setVisibility(View.VISIBLE);
        miniPlayer.setAlpha(1f);
        miniPlayer.setTranslationY(0f);
        updateMiniControl();
    }

    private void playTrack(DeltamentSoundCloudClient.Track track) {
        music.play(track);
    }

    private void togglePlayback() {
        music.toggle();
    }

    private void playNext() {
        music.next();
    }

    private void stopPlayback() {
        music.stop();
        hideMiniPlayerAnimated();
    }

    private void hideMiniPlayerAnimated() {
        if (miniPlayer.getVisibility() == View.VISIBLE) {
            miniPlayer.animate().cancel();
            miniPlayer.animate().alpha(0f).translationY(dp(10))
                    .setDuration(160)
                    .withEndAction(() -> {
                        miniPlayer.setVisibility(View.GONE);
                        miniPlayer.setAlpha(1f);
                        miniPlayer.setTranslationY(0f);
                    })
                    .start();
        }
    }

    // -------------------------------------------------------------------- queue

    private void setQueueMode(boolean enabled) {
        if (queueMode == enabled) {
            return;
        }
        queueMode = enabled;
        handler.removeCallbacks(searchRunnable);
        if (enabled) {
            titleView.setText("Очередь");
            queueButton.setText("Готово");
            searchContainer.setVisibility(View.GONE);
            sourceRow.setVisibility(View.GONE);
            typeRow.setVisibility(View.GONE);
            modeRow.setVisibility(View.GONE);
            renderQueue();
        } else {
            titleView.setText("Музыка");
            queueButton.setText("Очередь");
            searchContainer.setVisibility(View.VISIBLE);
            sourceRow.setVisibility(View.VISIBLE);
            typeRow.setVisibility(View.VISIBLE);
            modeRow.setVisibility(View.VISIBLE);
            reload();
        }
    }

    private void renderQueue() {
        listContainer.removeAllViews();
        if (queue.isEmpty()) {
            setStatus("очередь пуста");
            return;
        }
        setStatus("");
        for (int i = 0; i < queue.size(); i++) {
            final int rowIndex = i;
            final DeltamentSoundCloudClient.Track track = queue.get(i);
            final LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(10), dp(8), dp(10));
            if (rowIndex == music.queueIndex) {
                row.setBackground(ripple(0xFF2A2A2D, dp(12)));
            } else {
                row.setBackground(ripple(0x00000000, dp(12)));
            }
            row.setOnClickListener(v -> {
                if (rowIndex == music.queueIndex) {
                    expandPlayer();
                } else {
                    music.playAt(indexOfTrack(track));
                    renderQueue();
                }
            });

            final LinearLayout textColumn = new LinearLayout(context);
            textColumn.setOrientation(LinearLayout.VERTICAL);
            row.addView(textColumn, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            final TextView title = new TextView(context);
            title.setText(track.title);
            title.setTextSize(15);
            title.setTextColor(i == music.queueIndex ? COLOR_BLUE : COLOR_TEXT);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView subtitle = new TextView(context);
            subtitle.setText(track.artist);
            subtitle.setTextSize(13);
            subtitle.setTextColor(COLOR_TEXT_SECONDARY);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            textColumn.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            final TextView remove = new TextView(context);
            remove.setText("✕");
            remove.setTextSize(14);
            remove.setTextColor(COLOR_TEXT_SECONDARY);
            remove.setGravity(Gravity.CENTER);
            remove.setOnClickListener(v -> {
                final int index = indexOfTrack(track);
                if (index >= 0) {
                    queue.remove(index);
                    if (index < music.queueIndex) {
                        music.queueIndex--;
                    } else if (index == music.queueIndex) {
                        music.stop();
                        miniPlayer.setVisibility(View.GONE);
                    }
                }
                renderQueue();
            });
            pressable(remove);
            row.addView(remove, LayoutHelper.createLinear(36, 36));
            listContainer.addView(row);
        }
    }

    private int indexOfTrack(DeltamentSoundCloudClient.Track track) {
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).id == track.id) {
                return i;
            }
        }
        return -1;
    }

    // -------------------------------------------------------------------- likes

    private SharedPreferences prefs() {
        final Context appContext = ApplicationLoader.applicationContext;
        if (appContext == null) {
            return null;
        }
        return appContext.getSharedPreferences(PREFS_LIKES, Context.MODE_PRIVATE);
    }

    private ArrayList<DeltamentSoundCloudClient.Track> readLiked() {
        final ArrayList<DeltamentSoundCloudClient.Track> tracks = new ArrayList<>();
        final SharedPreferences preferences = prefs();
        if (preferences == null) {
            return tracks;
        }
        final String raw = preferences.getString(KEY_LIKES, null);
        if (raw == null) {
            return tracks;
        }
        try {
            final JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                final JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                final DeltamentSoundCloudClient.Track track = new DeltamentSoundCloudClient.Track();
                track.id = object.optLong("id");
                track.title = object.optString("title", "");
                track.artist = object.optString("artist", "");
                track.artworkUrl = object.optString("artwork", null);
                track.durationMs = object.optLong("duration");
                tracks.add(track);
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return tracks;
    }

    private boolean isLiked(long id) {
        for (DeltamentSoundCloudClient.Track track : readLiked()) {
            if (track.id == id) {
                return true;
            }
        }
        return false;
    }

    private void toggleLike(DeltamentSoundCloudClient.Track track) {
        final ArrayList<DeltamentSoundCloudClient.Track> liked = readLiked();
        boolean removed = false;
        for (Iterator<DeltamentSoundCloudClient.Track> iterator = liked.iterator(); iterator.hasNext(); ) {
            if (iterator.next().id == track.id) {
                iterator.remove();
                removed = true;
                break;
            }
        }
        if (!removed) {
            liked.add(0, track);
        }
        final SharedPreferences preferences = prefs();
        if (preferences == null) {
            return;
        }
        try {
            final JSONArray array = new JSONArray();
            for (DeltamentSoundCloudClient.Track item : liked) {
                final JSONObject object = new JSONObject();
                object.put("id", item.id);
                object.put("title", item.title);
                object.put("artist", item.artist);
                if (item.artworkUrl != null) {
                    object.put("artwork", item.artworkUrl);
                }
                object.put("duration", item.durationMs);
                array.put(object);
            }
            preferences.edit().putString(KEY_LIKES, array.toString()).apply();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ----------------------------------------------------------------- artwork

    private void loadArtwork(String url, ImageView imageView) {
        DeltamentArtwork.load(url, imageView);
    }

    // ------------------------------------------------------------- segment view

    private static class FixedHeightLinearLayout extends LinearLayout {

        private final int fixedHeight;

        FixedHeightLinearLayout(Context context, int fixedHeight) {
            super(context);
            this.fixedHeight = fixedHeight;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(fixedHeight, MeasureSpec.EXACTLY));
        }
    }

    private static class SegmentRow extends View {

        interface Listener {
            void onSelected(int index);
        }

        private final String[] labels;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint selectedTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private int selected;
        private float selectedAnim;
        private ValueAnimator selectedAnimator;
        private Listener listener;

        SegmentRow(Context context, String[] labels) {
            super(context);
            this.labels = labels;
            final float density = context.getResources().getDisplayMetrics().density;
            radius = 21 * density;
            backgroundPaint.setColor(COLOR_FIELD);
            selectedPaint.setColor(COLOR_BLUE);
            textPaint.setColor(COLOR_TEXT_SECONDARY);
            textPaint.setTextSize(15 * density);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
            selectedTextPaint.setColor(COLOR_TEXT);
            selectedTextPaint.setTextSize(15 * density);
            selectedTextPaint.setTextAlign(Paint.Align.CENTER);
            selectedTextPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        }

        void setListener(Listener listener) {
            this.listener = listener;
        }

        int getSelected() {
            return selected;
        }

        void setSelected(int index, boolean notify) {
            final int next = Math.max(0, Math.min(labels.length - 1, index));
            if (next != selected) {
                if (selectedAnimator != null) {
                    selectedAnimator.cancel();
                }
                if (isAttachedToWindow()) {
                    selectedAnimator = ValueAnimator.ofFloat(selectedAnim, next);
                    selectedAnimator.setDuration(220);
                    selectedAnimator.setInterpolator(new DecelerateInterpolator());
                    selectedAnimator.addUpdateListener(animation -> {
                        selectedAnim = (float) animation.getAnimatedValue();
                        invalidate();
                    });
                    selectedAnimator.start();
                } else {
                    selectedAnim = next;
                }
            } else {
                selectedAnim = next;
            }
            selected = next;
            invalidate();
            if (notify && listener != null) {
                listener.onSelected(selected);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            final int width = MeasureSpec.getSize(widthMeasureSpec);
            final int height = (int) (42 * getResources().getDisplayMetrics().density);
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float width = getWidth();
            final float height = getHeight();
            final float inset = 2f * getResources().getDisplayMetrics().density;
            canvas.drawRoundRect(0, 0, width, height, radius, radius, backgroundPaint);

            final float segment = width / labels.length;
            final float left = selectedAnim * segment;
            final float right = left + segment;
            canvas.drawRoundRect(left + inset, inset, right - inset, height - inset,
                    (height - inset * 2) / 2f, (height - inset * 2) / 2f, selectedPaint);

            final float centerY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f;
            for (int i = 0; i < labels.length; i++) {
                final Paint paint = i == selected ? selectedTextPaint : textPaint;
                canvas.drawText(labels[i], segment * i + segment / 2f, centerY, paint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                animate().cancel();
                animate().scaleX(0.97f).scaleY(0.97f)
                        .setDuration(90)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                animate().cancel();
                animate().scaleX(1f).scaleY(1f)
                        .setDuration(240)
                        .setInterpolator(new OvershootInterpolator(2f))
                        .start();
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                final int index = Math.max(0, Math.min(labels.length - 1,
                        (int) (event.getX() / (getWidth() / (float) labels.length))));
                if (index != selected) {
                    setSelected(index, true);
                }
                performClick();
                return true;
            }
            return event.getAction() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}

package org.telegram.ui.Wallet;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lyrics for the DeltaGram music player.
 *
 * <p>SoundCloud does not ship lyrics, so they are looked up on LRCLIB, a free
 * community database that returns both plain lyrics and time-synced LRC. The
 * result is cached in memory and delivered on the UI thread.</p>
 */
public final class DeltamentLyrics {

    private static final String UA = "DeltaGram/1.0 (Android)";
    private static final Pattern TIME_TAG =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?\\]");
    private static final HashMap<String, Lyrics> cache = new HashMap<>();
    private static final Object lock = new Object();

    private DeltamentLyrics() {
    }

    public interface Callback {
        void onResult(Lyrics lyrics);
    }

    public static final class Line {
        public final long timeMs;
        public final String text;

        Line(long timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    public static final class Lyrics {
        public boolean found;
        public boolean synced;
        public boolean instrumental;
        public final ArrayList<Line> lines = new ArrayList<>();
    }

    /**
     * Looks the track up on LRCLIB. The callback always receives a non-null
     * object: {@code found == false} means "lyrics are not available".
     */
    public static void fetch(DeltamentSoundCloudClient.Track track, Callback callback) {
        if (track == null || callback == null) {
            return;
        }
        final String key = cacheKey(track);
        synchronized (lock) {
            final Lyrics cached = cache.get(key);
            if (cached != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(cached));
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            final Lyrics lyrics = load(track);
            synchronized (lock) {
                cache.put(key, lyrics);
            }
            AndroidUtilities.runOnUIThread(() -> callback.onResult(lyrics));
        });
    }

    private static String cacheKey(DeltamentSoundCloudClient.Track track) {
        return track.id + "|" + track.artist + "|" + track.title + "|" + track.durationMs;
    }

    private static Lyrics load(DeltamentSoundCloudClient.Track track) {
        Lyrics result = null;
        try {
            final String query = (track.artist + " " + track.title).trim();
            if (!query.isEmpty()) {
                final String url = "https://lrclib.net/api/search?q="
                        + URLEncoder.encode(query, "UTF-8");
                final JSONArray array = new JSONArray(request(url));
                final JSONObject best = pickBest(array, track);
                if (best != null) {
                    result = parse(best);
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return result != null ? result : new Lyrics();
    }

    /**
     * Picks the result that matches the track best: same artist and title,
     * close duration, and synced lyrics are preferred over plain ones.
     */
    private static JSONObject pickBest(JSONArray array, DeltamentSoundCloudClient.Track track) {
        if (array == null || array.length() == 0) {
            return null;
        }
        final String artist = track.artist == null ? "" : track.artist.toLowerCase(Locale.ROOT);
        final String title = track.title == null ? "" : track.title.toLowerCase(Locale.ROOT);
        JSONObject best = null;
        int bestScore = Integer.MIN_VALUE;
        for (int i = 0; i < array.length(); i++) {
            final JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            if (item.optBoolean("instrumental")) {
                continue;
            }
            final String itemArtist = item.optString("artistName", "").toLowerCase(Locale.ROOT);
            final String itemTitle = item.optString("trackName", "").toLowerCase(Locale.ROOT);
            if (text(item, "syncedLyrics").isEmpty() && text(item, "plainLyrics").isEmpty()) {
                continue;
            }
            int score = 0;
            if (!artist.isEmpty() && !itemArtist.isEmpty()
                    && (itemArtist.contains(artist) || artist.contains(itemArtist))) {
                score += 4;
            }
            if (!title.isEmpty() && !itemTitle.isEmpty()
                    && (itemTitle.contains(title) || title.contains(itemTitle))) {
                score += 4;
            }
            final long durationMs = item.optLong("duration") * 1000L;
            if (track.durationMs > 0 && durationMs > 0) {
                final long diff = Math.abs(durationMs - track.durationMs);
                if (diff < 15000) {
                    score += 3;
                } else if (diff > 45000) {
                    score -= 3;
                }
            }
            if (!text(item, "syncedLyrics").isEmpty()) {
                score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                best = item;
            }
        }
        return best;
    }

    private static Lyrics parse(JSONObject item) {
        final Lyrics lyrics = new Lyrics();
        lyrics.instrumental = item.optBoolean("instrumental");
        final String synced = text(item, "syncedLyrics");
        if (!synced.isEmpty()) {
            final String[] rows = synced.split("\\r?\\n");
            for (String row : rows) {
                final Matcher matcher = TIME_TAG.matcher(row);
                final ArrayList<Long> times = new ArrayList<>();
                int textStart = 0;
                while (matcher.find()) {
                    final long minutes = Long.parseLong(matcher.group(1));
                    final long seconds = Long.parseLong(matcher.group(2));
                    final String fraction = matcher.group(3);
                    long millis = 0;
                    if (fraction != null) {
                        if (fraction.length() == 1) {
                            millis = Long.parseLong(fraction) * 100L;
                        } else if (fraction.length() == 2) {
                            millis = Long.parseLong(fraction) * 10L;
                        } else {
                            millis = Long.parseLong(fraction);
                        }
                    }
                    times.add((minutes * 60L + seconds) * 1000L + millis);
                    textStart = matcher.end();
                }
                if (times.isEmpty() || textStart >= row.length()) {
                    continue;
                }
                final String text = row.substring(textStart).trim();
                if (text.isEmpty()) {
                    continue;
                }
                for (Long time : times) {
                    lyrics.lines.add(new Line(time, text));
                }
            }
            if (!lyrics.lines.isEmpty()) {
                Collections.sort(lyrics.lines, (a, b) -> Long.compare(a.timeMs, b.timeMs));
                lyrics.synced = true;
                lyrics.found = true;
                return lyrics;
            }
        }
        final String plain = text(item, "plainLyrics");
        if (!plain.isEmpty()) {
            final String[] rows = plain.split("\\r?\\n");
            for (String row : rows) {
                if (!row.trim().isEmpty()) {
                    lyrics.lines.add(new Line(-1L, row.trim()));
                }
            }
        }
        lyrics.found = !lyrics.lines.isEmpty() || lyrics.instrumental;
        return lyrics;
    }

    /** Reads a string field, treating JSON nulls and the literal "null" as empty. */
    private static String text(JSONObject object, String key) {
        final String value = object.optString(key, "");
        if (value == null) {
            return "";
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equals(trimmed) ? "" : trimmed;
    }

    private static String request(String url) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("User-Agent", UA);
            connection.setRequestProperty("Accept", "application/json");
            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return "[]";
            }
            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"))) {
                final StringBuilder builder = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line);
                }
                return builder.toString();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}

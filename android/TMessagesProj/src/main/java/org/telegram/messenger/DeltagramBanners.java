/*
 * DeltaGram profile banners.
 *
 * A banner is an uploaded image served by Deltament at
 * /api/banners/{userId}?v={token}. The public registry maps user ids to the
 * current token, so clients can show the same cover behind an avatar without
 * touching MTProto.
 */

package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeltagramBanners {

    public static final String BASE_URL = "https://dltagram.duckdns.org";
    public static final String REGISTRY_URL = BASE_URL + "/api/banners/registry";

    private static final long REFRESH_INTERVAL = 10 * 60 * 1000L;
    private static final String USER_AGENT = "DeltaGramAndroid";

    private static final Object lock = new Object();
    private static final Map<Long, String> banners = new HashMap<>();
    private static final Map<Long, String> pendingUploads = new HashMap<>();
    private static final Map<Long, Long> pendingUploadTimes = new HashMap<>();
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };
    private static final Set<String> loading = new HashSet<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static volatile boolean started;
    private static volatile boolean inFlight;

    private DeltagramBanners() {
    }

    public static void ensureStarted() {
        if (started) {
            return;
        }
        synchronized (lock) {
            if (started) {
                return;
            }
            started = true;
        }
        handler.post(DeltagramBanners::refresh);
    }

    /**
     * @return the banner URL for a user, or null when they have no banner.
     */
    public static String urlFor(long userId) {
        if (userId == 0) {
            return null;
        }
        ensureStarted();
        String token;
        synchronized (lock) {
            token = banners.get(userId);
        }
        if (TextUtils.isEmpty(token)) {
            return null;
        }
        return BASE_URL + "/api/banners/" + userId + "?v=" + token;
    }

    /**
     * Returns a decoded banner immediately when cached, otherwise starts a
     * background download and calls {@code onLoaded} on the UI thread.
     */
    public static Bitmap bitmapFor(long userId, Runnable onLoaded) {
        String url = urlFor(userId);
        if (url == null) {
            return null;
        }
        String key = userId + ":" + url;
        synchronized (lock) {
            Bitmap cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
            if (!loading.add(key)) {
                return null;
            }
        }
        executor.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream stream = connection.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(stream);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final Bitmap result = bitmap;
            synchronized (lock) {
                loading.remove(key);
                if (result != null) {
                    cache.put(key, result);
                }
            }
            if (result != null && onLoaded != null) {
                handler.post(onLoaded);
            }
        });
        return null;
    }

    public static void refreshNow() {
        handler.post(DeltagramBanners::refresh);
    }

    /** Make a successful upload visible immediately, before the registry poll. */
    public static void setUploaded(long userId, String token, byte[] image) {
        if (userId <= 0 || TextUtils.isEmpty(token)) return;
        String url = BASE_URL + "/api/banners/" + userId + "?v=" + token;
        Bitmap bitmap = BitmapFactory.decodeByteArray(image, 0, image.length);
        synchronized (lock) {
            banners.put(userId, token);
            pendingUploads.put(userId, token);
            pendingUploadTimes.put(userId, System.currentTimeMillis());
            if (bitmap != null) cache.put(userId + ":" + url, bitmap);
        }
        refreshNow();
    }

    private static void refresh() {
        if (inFlight) {
            return;
        }
        inFlight = true;
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(REGISTRY_URL).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Accept", "application/json");
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                    return;
                }
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }
                JSONObject root = new JSONObject(body.toString());
                JSONObject list = root.optJSONObject("banners");
                Map<Long, String> next = new HashMap<>();
                if (list != null) {
                    java.util.Iterator<String> keys = list.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        String token = list.optString(key, "");
                        if (TextUtils.isEmpty(token)) {
                            continue;
                        }
                        try {
                            next.put(Long.parseLong(key), token);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                synchronized (lock) {
                    // A registry request started before the upload may return
                    // after it and otherwise erase the newly visible cover.
                    java.util.Iterator<Map.Entry<Long, String>> pending = pendingUploads.entrySet().iterator();
                    while (pending.hasNext()) {
                        Map.Entry<Long, String> entry = pending.next();
                        Long uploadedAt = pendingUploadTimes.get(entry.getKey());
                        if (entry.getValue().equals(next.get(entry.getKey()))
                                || uploadedAt == null || System.currentTimeMillis() - uploadedAt > 2 * 60 * 1000L) {
                            pending.remove();
                            pendingUploadTimes.remove(entry.getKey());
                        } else {
                            next.put(entry.getKey(), entry.getValue());
                        }
                    }
                    banners.clear();
                    banners.putAll(next);
                }
                handler.post(() -> NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.updateInterfaces, 0));
            } catch (Throwable ignore) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                inFlight = false;
                handler.postDelayed(DeltagramBanners::refresh, REFRESH_INTERVAL);
            }
        });
    }
}

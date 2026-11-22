package org.telegram.messenger;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Public badge lookup as a fallback for stale MTProto profile caches. */
public final class DeltagramVerifications {
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Map<Long, Long> icons = new HashMap<>();
    private static final Bitmap[] bundled = new Bitmap[7];
    private static volatile boolean loading;
    private static volatile long lastLoaded;

    private DeltagramVerifications() {}

    // These seven documents are seeded from the same WebP files on the server.
    // Render them locally in profile headers: a missing/stale custom-emoji
    // document cache must not make an assigned third-party badge invisible.
    public static Drawable bundledIcon(Context context, long documentId) {
        String[] files = {"major", "business", "diamond", "crown", "star", "partner", "deltagram"};
        long index = documentId - 5310000000000000111L;
        if (context == null || index < 0 || index >= files.length) return null;
        try {
            Bitmap bitmap = bundled[(int) index];
            if (bitmap == null) {
                try (InputStream stream = context.getAssets().open("verification_icons/" + files[(int) index] + ".webp")) {
                    bitmap = BitmapFactory.decodeStream(stream);
                }
                bundled[(int) index] = bitmap;
            }
            if (bitmap == null) return null;
            return new BitmapDrawable(context.getResources(), bitmap);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long iconFor(long userId) {
        if (userId <= 0) return 0;
        if (!loading && System.currentTimeMillis() - lastLoaded > 60000) refresh();
        synchronized (icons) {
            Long icon = icons.get(userId);
            return icon == null ? 0 : icon;
        }
    }

    private static void refresh() {
        loading = true;
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://dltagram.duckdns.org/api/verifications/registry").openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return;
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) body.append(line);
                }
                JSONObject list = new JSONObject(body.toString()).optJSONObject("icons");
                Map<Long, Long> next = new HashMap<>();
                if (list != null) {
                    java.util.Iterator<String> keys = list.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        try {
                            long id = Long.parseLong(key);
                            long icon = list.optLong(key);
                            if (icon > 0) next.put(id, icon);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                synchronized (icons) {
                    icons.clear();
                    icons.putAll(next);
                    lastLoaded = System.currentTimeMillis();
                }
                handler.post(() -> {
                    for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces,
                                MessagesController.UPDATE_MASK_NAME);
                    }
                });
            } catch (Exception ignored) {
                // The native server-projected badge still works while offline.
            } finally {
                if (connection != null) connection.disconnect();
                loading = false;
            }
        }, "DeltaGramVerifications").start();
    }
}

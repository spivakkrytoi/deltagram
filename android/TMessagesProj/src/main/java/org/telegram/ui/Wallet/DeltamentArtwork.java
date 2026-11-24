package org.telegram.ui.Wallet;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.LruCache;
import android.widget.ImageView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Small shared cache/loader for the SoundCloud cover images. */
public final class DeltamentArtwork {

    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private DeltamentArtwork() {
    }

    public static void load(String url, ImageView imageView) {
        if (imageView == null) {
            return;
        }
        if (TextUtils.isEmpty(url)) {
            imageView.setImageDrawable(null);
            return;
        }
        imageView.setTag(url);
        final Bitmap cached = cache.get(url);
        if (cached != null) {
            imageView.setImageBitmap(cached);
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(20000);
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    try (InputStream input = connection.getInputStream()) {
                        bitmap = BitmapFactory.decodeStream(input);
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            final Bitmap result = bitmap;
            if (result != null) {
                cache.put(url, result);
                AndroidUtilities.runOnUIThread(() -> {
                    if (url.equals(imageView.getTag())) {
                        imageView.setImageBitmap(result);
                    }
                });
            }
        });
    }
}

package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Public DeltaGram card profile: balances visible to every client, the chosen
 * card colour/NFT, P2P transfers and the profile banner upload.
 */
public final class DeltagramCards {

    public static final String BASE_URL = "https://dltagram.duckdns.org";
    private static final String USER_AGENT = "DeltaGramAndroid";
    private static final int TIMEOUT_MS = 15000;

    public interface Callback<T> {
        void onResult(T value, String error);
    }

    public static class PublicCard {
        public long userId;
        public String name = "";
        public String username = "";
        public long ton;
        public long stars;
        public String tonAddress = "";
        public int colorId = -1;
        public long nftRowId;
        public String nftSlug = "";
        public int nftNum;
        public float nftX = 0.5f;
        public float nftY = 0.5f;
        public float nftScale = 0.231f;
        public float nftRotation;

        public String nftUrl() {
            if (nftRowId <= 0 || TextUtils.isEmpty(nftSlug) || nftNum <= 0) {
                return null;
            }
            String base = nftSlug.toLowerCase(Locale.ROOT).trim();
            String suffix = "-" + nftNum;
            if (base.endsWith(suffix)) {
                base = base.substring(0, base.length() - suffix.length());
            }
            base = base.replaceAll("[^a-z0-9]", "");
            if (base.isEmpty()) {
                return null;
            }
            return BASE_URL + "/api/gift-art/" + base + "-" + nftNum + ".webp";
        }
    }

    public static class Nft {
        public long rowId;
        public String title = "";
        public String url = "";
    }

    public static class CardTransaction {
        public long id;
        public String kind = "";
        public long amount;
        public String reason = "";
        public String title = "";
        public long created;
        public long peerId;
        public String peerName = "";
        public String peerUsername = "";
    }

    public static void history(int account, Callback<List<CardTransaction>> callback) {
        get(account, "/api/cards/history", json -> {
            List<CardTransaction> entries = new ArrayList<>();
            JSONArray rows = json.optJSONArray("entries");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row == null) continue;
                    CardTransaction entry = new CardTransaction();
                    entry.id = row.optLong("id");
                    entry.kind = row.optString("kind");
                    entry.amount = row.optLong("amount");
                    entry.reason = row.optString("reason");
                    entry.title = row.optString("title");
                    entry.created = row.optLong("created");
                    entry.peerId = row.optLong("peer_id");
                    entry.peerName = row.optString("peer_name");
                    entry.peerUsername = row.optString("peer_username");
                    entries.add(entry);
                }
            }
            return entries;
        }, callback);
    }

    public static class Prefs {
        public int colorId = -1;
        public long nftRowId;
        public float nftX = 0.5f;
        public float nftY = 0.5f;
        public float nftScale = 0.231f;
        public float nftRotation;
    }

    private static final Map<Long, PublicCard> cards = new HashMap<>();
    private static volatile long registryAt;
    private static final List<Runnable> listeners = new ArrayList<>();
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private DeltagramCards() {
    }

    public static PublicCard cardFor(long userId) {
        synchronized (cards) {
            return cards.get(userId);
        }
    }

    /** Registers a one-shot callback fired when a fresh registry arrives. */
    public static void addListener(Runnable runnable) {
        synchronized (listeners) {
            if (!listeners.contains(runnable)) {
                listeners.add(runnable);
            }
        }
    }

    public static void removeListener(Runnable runnable) {
        synchronized (listeners) {
            listeners.remove(runnable);
        }
    }

    public static void refreshRegistry(int account) {
        get(account, "/api/cards/registry", json -> {
            List<PublicCard> parsed = new ArrayList<>();
            JSONArray arr = json.optJSONArray("cards");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    PublicCard card = parseCard(arr.optJSONObject(i));
                    if (card.userId != 0) {
                        parsed.add(card);
                    }
                }
            }
            synchronized (cards) {
                cards.clear();
                for (PublicCard card : parsed) {
                    cards.put(card.userId, card);
                }
                registryAt = System.currentTimeMillis();
            }
            handler.post(() -> {
                List<Runnable> copy;
                synchronized (listeners) {
                    copy = new ArrayList<>(listeners);
                }
                for (Runnable runnable : copy) {
                    runnable.run();
                }
            });
            return parsed;
        }, (value, error) -> {
        });
    }

    public static void prefs(int account, Callback<Prefs> callback) {
        get(account, "/api/cards/prefs", json -> {
            Prefs p = new Prefs();
            p.colorId = json.optInt("color_id", -1);
            p.nftRowId = json.optLong("nft_row_id");
            p.nftX = (float) json.optDouble("nft_x", 0.5);
            p.nftY = (float) json.optDouble("nft_y", 0.5);
            p.nftScale = (float) json.optDouble("nft_scale", 0.231);
            p.nftRotation = (float) json.optDouble("nft_rotation", 0);
            return p;
        }, callback);
    }

    public static void savePrefs(int account, int colorId, long nftRowId, float nftX, float nftY, float nftScale, float nftRotation, Callback<Prefs> callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("color_id", colorId);
            body.put("nft_row_id", nftRowId);
            body.put("nft_x", nftX);
            body.put("nft_y", nftY);
            body.put("nft_scale", nftScale);
            body.put("nft_rotation", nftRotation);
        } catch (Exception ignored) {
        }
        post(account, "/api/cards/prefs", body, json -> {
            Prefs p = new Prefs();
            JSONObject prefs = json.optJSONObject("prefs");
            if (prefs != null) {
                p.colorId = prefs.optInt("color_id", -1);
                p.nftRowId = prefs.optLong("nft_row_id");
            }
            refreshRegistry(account);
            return p;
        }, callback);
    }

    public static void nfts(int account, Callback<List<Nft>> callback) {
        get(account, "/api/cards/nfts", json -> {
            List<Nft> out = new ArrayList<>();
            JSONArray arr = json.optJSONArray("nfts");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o == null) continue;
                    Nft n = new Nft();
                    n.rowId = o.optLong("row_id");
                    n.title = o.optString("title");
                    n.url = o.optString("url");
                    if (n.rowId > 0) out.add(n);
                }
            }
            return out;
        }, callback);
    }

    public static void setAddress(int account, String address, Callback<PublicCard> callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("address", address);
        } catch (Exception ignored) {
        }
        post(account, "/api/cards/address", body, json -> {
            PublicCard card = parseCard(json.optJSONObject("card"));
            if (card.userId != 0) {
                synchronized (cards) {
                    cards.put(card.userId, card);
                }
            }
            refreshRegistry(account);
            return card;
        }, callback);
    }

    public static void send(int account, String username, String kind, long amount, Callback<PublicCard> callback) {
        JSONObject body = new JSONObject();
        try {
            body.put("to_username", username);
            body.put("kind", kind);
            body.put("amount", amount);
        } catch (Exception ignored) {
        }
        post(account, "/api/cards/send", body, json -> {
            PublicCard card = parseCard(json.optJSONObject("card"));
            if (card.userId != 0) {
                synchronized (cards) {
                    cards.put(card.userId, card);
                }
            }
            refreshRegistry(account);
            return card;
        }, callback);
    }

    public static void uploadBanner(int account, byte[] data, String mime, Callback<String> callback) {
        request(account, "POST", "/api/cards/banner", data, mime, json -> json.optString("token"), callback);
    }

    private static PublicCard parseCard(JSONObject json) {
        PublicCard card = new PublicCard();
        if (json == null) return card;
        card.userId = json.optLong("user_id");
        card.name = json.optString("name");
        card.username = json.optString("username");
        card.ton = json.optLong("ton_nanoton");
        card.stars = json.optLong("stars");
        card.tonAddress = json.optString("ton_address");
        card.colorId = json.optInt("color_id", -1);
        card.nftRowId = json.optLong("nft_row_id");
        card.nftSlug = json.optString("nft_slug");
        card.nftNum = json.optInt("nft_num");
        card.nftX = (float) json.optDouble("nft_x", 0.5);
        card.nftY = (float) json.optDouble("nft_y", 0.5);
        card.nftScale = (float) json.optDouble("nft_scale", 0.231);
        card.nftRotation = (float) json.optDouble("nft_rotation", 0);
        return card;
    }

    private interface Parser<T> {
        T parse(JSONObject json) throws Exception;
    }

    private static void get(int account, String path, Parser<?> parser, Callback<?> callback) {
        request(account, "GET", path, null, null, parser, callback);
    }

    private static void post(int account, String path, JSONObject body, Parser<?> parser, Callback<?> callback) {
        byte[] data = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        request(account, "POST", path, data, "application/json; charset=utf-8", parser, callback);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void request(int account, String method, String path, byte[] body, String contentType, Parser parser, Callback callback) {
        final String authorization = DeltagramNativeAuth.authorization(account, method, path, body);
        if (authorization == null) {
            handler.post(() -> callback.onResult(null, "Не удалось подписать запрос: сессия ещё не готова"));
            return;
        }
        new Thread(() -> {
            JSONObject result = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(BASE_URL + path);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "application/json");
                if (authorization != null) {
                    conn.setRequestProperty("Authorization", authorization);
                }
                if (body != null) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", contentType == null ? "application/octet-stream" : contentType);
                    conn.setFixedLengthStreamingMode(body.length);
                    try (OutputStream out = conn.getOutputStream()) {
                        out.write(body);
                    }
                }
                int status = conn.getResponseCode();
                InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String text = read(stream);
                JSONObject json = TextUtils.isEmpty(text) ? new JSONObject() : new JSONObject(text);
                if (status >= 400) {
                    error = json.optString("message", json.optString("error", "HTTP " + status));
                } else {
                    result = json;
                }
            } catch (Exception e) {
                FileLog.e(e);
                error = e.getMessage() == null ? "network" : e.getMessage();
            } finally {
                if (conn != null) conn.disconnect();
            }
            final JSONObject json = result;
            final String err = error;
            handler.post(() -> {
                if (err != null) {
                    callback.onResult(null, err);
                    return;
                }
                try {
                    callback.onResult(parser.parse(json), null);
                } catch (Exception e) {
                    FileLog.e(e);
                    callback.onResult(null, e.getMessage());
                }
            });
        }, "DeltaGramCards").start();
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }
}

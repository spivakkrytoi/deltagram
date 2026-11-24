package org.telegram.ui.Plugins;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.DeltamentStartupSound;
import org.telegram.ui.Stars.DeltamentBubbleSkins;
import org.telegram.ui.Stars.DeltamentProfileMusic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Exports and imports every DeltaGram preference in one JSON file.
 *
 * <p>The file contains all {@code deltagram_*} / {@code dg_*} entries of the
 * main settings, the chat-list swipe action, the music player settings and the
 * installed plugins (their enabled flag, their {@code settings.json} and the
 * {@code .plugin} file itself), so a backup can be restored on another
 * device.</p>
 */
public final class DeltaGramBackup {

    public static final int SCHEMA = 1;

    /** An unexpected plugin is not a reason to lose the whole backup. */
    private static final long MAX_PLUGIN_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SETTINGS_BYTES = 512L * 1024;
    private static final long MAX_BACKUP_BYTES = 64L * 1024 * 1024;
    private static final String MUSIC_PREFS = "deltagram_music";

    public static final class Result {
        public int preferenceCount;
        public int musicCount;
        public int pluginCount;
        public int pluginSettings;
        public final ArrayList<String> skippedPlugins = new ArrayList<>();
    }

    private DeltaGramBackup() {
    }

    public static String suggestedFileName() {
        final String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        return "deltagram-settings-" + stamp + ".json";
    }

    // ---------------------------------------------------------------- export

    public static void exportTo(Context context, Uri uri) throws Exception {
        final JSONObject root = new JSONObject();
        root.put("schema", SCHEMA);
        root.put("app", BuildVars.BUILD_VERSION_STRING);
        root.put("createdAt", System.currentTimeMillis());
        root.put("prefs", encodePrefs(MessagesController.getGlobalMainSettings(), true));
        root.put("music", encodePrefs(context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE), false));

        final DeltamentPluginManager manager = DeltamentPluginManager.getInstance();
        manager.init();
        final JSONArray plugins = new JSONArray();
        for (DeltamentPluginInfo info : manager.getPlugins()) {
            final JSONObject entry = new JSONObject();
            entry.put("id", info.id);
            entry.put("name", info.name);
            entry.put("version", info.version);
            entry.put("enabled", info.enabled);
            entry.put("python", info.python);

            final File file = info.file;
            if (file != null && file.isFile() && file.length() <= MAX_PLUGIN_BYTES) {
                entry.put("file", file.getName());
                entry.put("size", file.length());
                entry.put("data", Base64.encodeToString(readBytes(file), Base64.NO_WRAP));
            }

            final File settings = new File(new File(manager.getPluginsDir(), info.id), "settings.json");
            if (settings.isFile() && settings.length() <= MAX_SETTINGS_BYTES) {
                try {
                    entry.put("settings", new JSONObject(new String(readBytes(settings), "UTF-8")));
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }

            // Preferences of DEX plugins (and of the Python host, if a plugin
            // used DeltamentPluginContext#getPrefs()).
            final SharedPreferences pluginPrefs = context.getSharedPreferences(
                    "deltagram_plugin_" + info.id, Context.MODE_PRIVATE);
            if (!pluginPrefs.getAll().isEmpty()) {
                entry.put("contextPrefs", encodePrefs(pluginPrefs, false));
            }
            plugins.put(entry);
        }
        root.put("plugins", plugins);

        final OutputStream out = context.getContentResolver().openOutputStream(uri, "wt");
        if (out == null) {
            throw new Exception("не удалось открыть файл для записи");
        }
        try {
            out.write(root.toString(2).getBytes("UTF-8"));
            out.flush();
        } finally {
            out.close();
        }
    }

    private static JSONObject encodePrefs(SharedPreferences prefs, boolean onlyDeltaGram) throws Exception {
        final JSONObject out = new JSONObject();
        final java.util.Map<String, ?> all = prefs.getAll();
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            if (onlyDeltaGram && !isDeltaGramKey(entry.getKey())) {
                continue;
            }
            final JSONObject value = encodeValue(entry.getValue());
            if (value != null) {
                out.put(entry.getKey(), value);
            }
        }
        return out;
    }

    private static JSONObject encodeValue(Object value) throws Exception {
        final JSONObject out = new JSONObject();
        if (value instanceof Boolean) {
            out.put("t", "b");
            out.put("v", ((Boolean) value).booleanValue());
        } else if (value instanceof Integer) {
            out.put("t", "i");
            out.put("v", ((Integer) value).intValue());
        } else if (value instanceof Long) {
            out.put("t", "l");
            out.put("v", ((Long) value).longValue());
        } else if (value instanceof Float) {
            out.put("t", "f");
            out.put("v", ((Float) value).doubleValue());
        } else if (value instanceof String) {
            out.put("t", "s");
            out.put("v", value);
        } else if (value instanceof Set) {
            final JSONArray list = new JSONArray();
            for (Object item : (Set<?>) value) {
                list.put(String.valueOf(item));
            }
            out.put("t", "ss");
            out.put("v", list);
        } else {
            return null;
        }
        return out;
    }

    // ---------------------------------------------------------------- import

    public static Result importFrom(Context context, Uri uri) throws Exception {
        final JSONObject root = new JSONObject(new String(readAll(context, uri), "UTF-8"));
        if (root.optInt("schema", -1) != SCHEMA) {
            throw new Exception("это не резервная копия DeltaGram или её формат новее");
        }

        final Result result = new Result();
        final SharedPreferences main = MessagesController.getGlobalMainSettings();
        final SharedPreferences.Editor editor = main.edit();

        // A restore replaces the current DeltaGram state instead of merging
        // with it, so toggles removed from the backup do not linger.
        for (String key : main.getAll().keySet()) {
            if (isDeltaGramKey(key)) {
                editor.remove(key);
            }
        }

        final JSONObject prefs = root.optJSONObject("prefs");
        if (prefs != null) {
            for (Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                final String key = it.next();
                if (!isDeltaGramKey(key)) {
                    continue;
                }
                if (putValue(editor, key, prefs.optJSONObject(key))) {
                    result.preferenceCount++;
                }
            }
        }
        editor.apply();

        final JSONObject music = root.optJSONObject("music");
        if (music != null) {
            final SharedPreferences.Editor musicEditor =
                    context.getSharedPreferences(MUSIC_PREFS, Context.MODE_PRIVATE).edit();
            for (Iterator<String> it = music.keys(); it.hasNext(); ) {
                final String key = it.next();
                if (putValue(musicEditor, key, music.optJSONObject(key))) {
                    result.musicCount++;
                }
            }
            musicEditor.apply();
        }

        applyRuntimeSettings(main);
        restorePlugins(context, root.optJSONArray("plugins"), result);
        return result;
    }

    private static boolean putValue(SharedPreferences.Editor editor, String key, JSONObject value) {
        if (value == null) {
            return false;
        }
        try {
            final String type = value.optString("t");
            if ("b".equals(type)) {
                editor.putBoolean(key, value.optBoolean("v"));
            } else if ("i".equals(type)) {
                editor.putInt(key, value.optInt("v"));
            } else if ("l".equals(type)) {
                editor.putLong(key, value.optLong("v"));
            } else if ("f".equals(type)) {
                editor.putFloat(key, (float) value.optDouble("v"));
            } else if ("s".equals(type)) {
                editor.putString(key, value.optString("v"));
            } else if ("ss".equals(type)) {
                final JSONArray list = value.optJSONArray("v");
                final Set<String> set = new HashSet<>();
                if (list != null) {
                    for (int i = 0; i < list.length(); i++) {
                        set.add(list.optString(i));
                    }
                }
                editor.putStringSet(key, set);
            } else {
                return false;
            }
            return true;
        } catch (Throwable e) {
            FileLog.e(e);
            return false;
        }
    }

    /** SharedConfig keeps the values in memory, so refresh them right away. */
    private static void applyRuntimeSettings(SharedPreferences prefs) {
        SharedConfig.keepDeletedMessages = prefs.getBoolean("deltagram_keep_deleted", SharedConfig.keepDeletedMessages);
        SharedConfig.hidePhoneNumber = prefs.getBoolean("deltagram_hide_phone", SharedConfig.hidePhoneNumber);
        SharedConfig.showIdAndDc = prefs.getBoolean("deltagram_show_id", SharedConfig.showIdAndDc);
        SharedConfig.disableNumberRounding = prefs.getBoolean("deltagram_disable_rounding", SharedConfig.disableNumberRounding);
        SharedConfig.bubbleRadius = prefs.getInt("deltagram_bubble_radius", SharedConfig.bubbleRadius);
        SharedConfig.cardCurrency = prefs.getString("deltagram_card_currency", SharedConfig.cardCurrency);
        if (prefs.contains("ChatSwipeAction")) {
            SharedConfig.updateChatListSwipeSetting(prefs.getInt("ChatSwipeAction", -1));
        }
        if (prefs.contains("dg_bubble_skin")) {
            DeltamentBubbleSkins.set(prefs.getInt("dg_bubble_skin", DeltamentBubbleSkins.GLASS));
        }
        if (prefs.contains("dg_startup_sound")) {
            DeltamentStartupSound.setEnabled(prefs.getBoolean("dg_startup_sound", true));
        }
        if (prefs.contains("dg_profile_music")) {
            DeltamentProfileMusic.setMusicEnabled(prefs.getBoolean("dg_profile_music", true));
        }
        if (prefs.contains("dg_bubble_physics")) {
            DeltamentProfileMusic.setPhysicsEnabled(prefs.getBoolean("dg_bubble_physics", true));
        }
        if (prefs.contains("dg_profile_cat")) {
            DeltamentProfileMusic.setCatEnabled(prefs.getBoolean("dg_profile_cat", false));
        }
        if (prefs.contains("dg_gift_of_day")) {
            DeltamentProfileMusic.setGiftOfDayEnabled(prefs.getBoolean("dg_gift_of_day", true));
        }
        if (prefs.contains("dg_gift_of_day_interval")) {
            DeltamentProfileMusic.setGiftOfDayIntervalMinutes(
                    prefs.getInt("dg_gift_of_day_interval", DeltamentProfileMusic.GIFT_INTERVAL_DEFAULT));
        }
        if (prefs.contains("dg_profile_constellations")) {
            DeltamentProfileMusic.setConstellationsEnabled(prefs.getBoolean("dg_profile_constellations", true));
        }
    }

    private static void restorePlugins(Context context, JSONArray plugins, Result result) {
        final DeltamentPluginManager manager = DeltamentPluginManager.getInstance();
        manager.init();
        final File dir = manager.getPluginsDir();

        // Release the old DEX / Python modules before their files are replaced.
        for (DeltamentPluginInfo info : manager.getPlugins()) {
            manager.unload(info);
        }

        if (plugins != null) {
            for (int i = 0; i < plugins.length(); i++) {
                final JSONObject entry = plugins.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                final String id = sanitizeId(entry.optString("id"));
                final String name = entry.optString("name", id);
                if (id.isEmpty()) {
                    continue;
                }
                try {
                    final String data = entry.optString("data", "");
                    final File dest = new File(dir, id + ".plugin");
                    if (!data.isEmpty()) {
                        final byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                        if (bytes.length > MAX_PLUGIN_BYTES) {
                            result.skippedPlugins.add(name + ": файл слишком большой");
                            continue;
                        }
                        final File temp = new File(dir, id + ".plugin.tmp");
                        writeBytes(temp, bytes);
                        if (dest.exists() && !dest.delete()) {
                            temp.delete();
                            result.skippedPlugins.add(name + ": не удалось заменить файл");
                            continue;
                        }
                        if (!temp.renameTo(dest)) {
                            temp.delete();
                            result.skippedPlugins.add(name + ": не удалось сохранить файл");
                            continue;
                        }
                    } else if (!dest.isFile()) {
                        result.skippedPlugins.add(name + ": в копии нет файла плагина");
                        continue;
                    }

                    final JSONObject settings = entry.optJSONObject("settings");
                    final JSONObject contextPrefs = entry.optJSONObject("contextPrefs");
                    boolean restoredSettings = false;
                    if (settings != null) {
                        final File pluginDir = new File(dir, id);
                        if (pluginDir.exists() || pluginDir.mkdirs()) {
                            writeBytes(new File(pluginDir, "settings.json"),
                                    settings.toString().getBytes("UTF-8"));
                            restoredSettings = true;
                        }
                    }
                    if (contextPrefs != null) {
                        final SharedPreferences.Editor pluginEditor = context.getSharedPreferences(
                                "deltagram_plugin_" + id, Context.MODE_PRIVATE).edit();
                        pluginEditor.clear();
                        for (Iterator<String> it = contextPrefs.keys(); it.hasNext(); ) {
                            final String key = it.next();
                            putValue(pluginEditor, key, contextPrefs.optJSONObject(key));
                        }
                        pluginEditor.apply();
                        restoredSettings = true;
                    }
                    if (restoredSettings) {
                        result.pluginSettings++;
                    }
                    result.pluginCount++;
                } catch (Throwable e) {
                    FileLog.e(e);
                    result.skippedPlugins.add(name + ": " + e.getMessage());
                }
            }
        }

        // The enabled flags were restored above, so reload everything using
        // the new files and settings.
        manager.reload();
    }

    // ---------------------------------------------------------------- helpers

    private static boolean isDeltaGramKey(String key) {
        return key != null && (key.startsWith("deltagram_")
                || key.startsWith("dg_")
                || key.equals("ChatSwipeAction"));
    }

    private static String sanitizeId(String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static byte[] readAll(Context context, Uri uri) throws Exception {
        final InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("не удалось открыть файл");
        }
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            long total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > MAX_BACKUP_BYTES) {
                    throw new Exception("файл слишком большой для резервной копии");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        try (FileInputStream in = new FileInputStream(file)) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
            out.flush();
        }
    }
}

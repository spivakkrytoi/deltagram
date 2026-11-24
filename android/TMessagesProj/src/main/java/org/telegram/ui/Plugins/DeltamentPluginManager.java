package org.telegram.ui.Plugins;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/**
 * Installs, enables and loads DeltaGram plugins.
 *
 * <p>Format: a {@code .plugin} file is a zip with {@code manifest.json} and
 * {@code classes.dex} in its root.</p>
 *
 * <pre>
 * {
 *   "id": "hello",
 *   "name": "Hello plugin",
 *   "version": "1.0",
 *   "author": "@someone",
 *   "description": "optional",
 *   "entry": "com.example.HelloPlugin"
 * }
 * </pre>
 */
public final class DeltamentPluginManager {

    public interface Listener {
        void onPluginsChanged();
    }

    private static final String PREFS_KEY = "dg_plugin_enabled_";
    private static final DeltamentPluginManager INSTANCE = new DeltamentPluginManager();

    public static DeltamentPluginManager getInstance() {
        return INSTANCE;
    }

    private final ArrayList<DeltamentPluginInfo> plugins = new ArrayList<>();
    private final HashMap<String, DeltamentPlugin> loaded = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean initialized;

    private DeltamentPluginManager() {
    }

    public File getPluginsDir() {
        final File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "dg_plugins");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public void addListener(Listener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        for (Listener listener : listeners) {
            try {
                listener.onPluginsChanged();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    /** Scans the plugin folder and loads everything that is enabled. */
    public synchronized void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        rescan();
        for (DeltamentPluginInfo info : new ArrayList<>(plugins)) {
            if (info.enabled) {
                load(info);
            }
        }
    }

    /** Unloads everything, rescans and loads the enabled plugins again. */
    public synchronized void reload() {
        for (DeltamentPluginInfo info : new ArrayList<>(plugins)) {
            unload(info);
        }
        rescan();
        for (DeltamentPluginInfo info : new ArrayList<>(plugins)) {
            if (info.enabled) {
                load(info);
            }
        }
        notifyChanged();
    }

    public synchronized void rescan() {
        plugins.clear();
        final File[] files = getPluginsDir().listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase().endsWith(".plugin")) {
                    continue;
                }
                DeltamentPluginInfo info = parse(file);
                if (info != null) {
                    info.enabled = isEnabled(info.id);
                    info.loaded = loaded.containsKey(info.id);
                    plugins.add(info);
                }
            }
        }
        notifyChanged();
    }

    public synchronized List<DeltamentPluginInfo> getPlugins() {
        return new ArrayList<>(plugins);
    }

    private DeltamentPluginInfo parse(File file) {
        try (ZipFile zip = new ZipFile(file)) {
            final JSONObject json = readManifest(zip);
            if (json == null) {
                FileLog.e("dg-plugin: " + file.getName() + " has no manifest.json");
                return null;
            }
            final String id = sanitizeId(json.optString("id", ""));
            final String entry = json.optString("entry", "");
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(entry)) {
                FileLog.e("dg-plugin: " + file.getName() + " needs id and entry");
                return null;
            }
            if (zip.getEntry("classes.dex") == null) {
                FileLog.e("dg-plugin: " + file.getName() + " has no classes.dex");
                return null;
            }
            return new DeltamentPluginInfo(
                    id,
                    json.optString("name", id),
                    json.optString("version", "1.0"),
                    json.optString("author", ""),
                    json.optString("description", ""),
                    entry,
                    file);
        } catch (java.util.zip.ZipException notDex) {
            return parsePython(file);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** exteraGram-style plugin: a single Python file with __id__ and BasePlugin. */
    private DeltamentPluginInfo parsePython(File file) {
        final String text = readText(file);
        if (!text.contains("BasePlugin")) {
            return null;
        }
        final String id = sanitizeId(first(text, "__id__\\s*=\\s*[\"']([^\"']+)[\"']"));
        if (TextUtils.isEmpty(id)) {
            return null;
        }
        final String name = first(text, "__name__\\s*=\\s*[\"']([^\"']+)[\"']");
        final DeltamentPluginInfo info = new DeltamentPluginInfo(
                id,
                TextUtils.isEmpty(name) ? id : name,
                first(text, "__version__\\s*=\\s*[\"']([^\"']+)[\"']"),
                first(text, "__author__\\s*=\\s*[\"']([^\"']+)[\"']"),
                first(text, "__description__\\s*=\\s*[\"']([^\"']+)[\"']"),
                "",
                file);
        info.python = true;
        return info;
    }

    private static String first(String text, String regex) {
        final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String readText(File file) {
        try {
            final byte[] bytes = new byte[(int) Math.min(file.length(), 256 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int read = 0;
                int chunk;
                while (read < bytes.length && (chunk = in.read(bytes, read, bytes.length - read)) > 0) {
                    read += chunk;
                }
                return new String(bytes, 0, read, "UTF-8");
            }
        } catch (Throwable e) {
            return "";
        }
    }

    private static JSONObject readManifest(ZipFile zip) {
        final ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            final byte[] buffer = new byte[Math.max(256, (int) entry.getSize())];
            int read = 0;
            int chunk;
            while (read < buffer.length && (chunk = in.read(buffer, read, buffer.length - read)) > 0) {
                read += chunk;
            }
            return new JSONObject(new String(buffer, 0, Math.max(read, 0), "UTF-8"));
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Copies a picked .plugin file into the plugin folder and enables it. */
    public synchronized DeltamentPluginInfo install(Context context, Uri uri) throws Exception {
        final File dir = getPluginsDir();
        final File temp = new File(dir, "incoming.tmp");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) {
                throw new IOException("cannot open the picked file");
            }
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }

        String id;
        try (ZipFile zip = new ZipFile(temp)) {
            final JSONObject json = readManifest(zip);
            if (json == null || zip.getEntry("classes.dex") == null) {
                temp.delete();
                throw new IOException("это не .plugin: нет manifest.json или classes.dex");
            }
            id = sanitizeId(json.optString("id", ""));
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(json.optString("entry", ""))) {
                temp.delete();
                throw new IOException("в manifest.json нет id или entry");
            }
        } catch (java.util.zip.ZipException pythonPlugin) {
            final DeltamentPluginInfo parsed = parsePython(temp);
            if (parsed == null) {
                temp.delete();
                throw new IOException("это не .plugin: нужен manifest.json + classes.dex "
                        + "или Python-плагин с BasePlugin");
            }
            id = parsed.id;
        }

        final File dest = new File(dir, id + ".plugin");
        if (dest.exists()) {
            dest.delete();
        }
        if (!temp.renameTo(dest)) {
            temp.delete();
            throw new IOException("не удалось сохранить " + dest.getName());
        }

        setEnabled(id, true);
        rescan();
        final DeltamentPluginInfo info = find(id);
        if (info != null) {
            load(info);
        }
        notifyChanged();
        return info;
    }

    private DeltamentPluginInfo find(String id) {
        if (id == null) {
            return null;
        }
        for (DeltamentPluginInfo info : plugins) {
            if (id.equals(info.id)) {
                return info;
            }
        }
        return null;
    }

    public synchronized boolean isEnabled(String id) {
        return MessagesController.getGlobalMainSettings().getBoolean(PREFS_KEY + id, true);
    }

    public synchronized void setEnabled(String id, boolean enabled) {
        MessagesController.getGlobalMainSettings().edit().putBoolean(PREFS_KEY + id, enabled).apply();
    }

    public synchronized void setPluginEnabled(DeltamentPluginInfo info, boolean enabled) {
        if (info == null) {
            return;
        }
        info.enabled = enabled;
        setEnabled(info.id, enabled);
        if (enabled) {
            load(info);
        } else {
            unload(info);
        }
        notifyChanged();
    }

    public synchronized boolean load(DeltamentPluginInfo info) {
        if (info == null) {
            return false;
        }
        if (loaded.containsKey(info.id)) {
            info.loaded = true;
            return true;
        }
        if (info.python) {
            final String error = pythonLoad(info);
            info.loaded = TextUtils.isEmpty(error);
            info.error = info.loaded ? null : error;
            if (info.loaded) {
                FileLog.d("[dg-plugin] loaded python plugin " + info.id);
            }
            return info.loaded;
        }
        try {
            final File codeCache = new File(ApplicationLoader.applicationContext.getCodeCacheDir(), "dg_plugins");
            codeCache.mkdirs();
            final DexClassLoader loader = new DexClassLoader(
                    info.file.getAbsolutePath(),
                    codeCache.getAbsolutePath(),
                    null,
                    DeltamentPlugin.class.getClassLoader());
            final Class<?> cls = Class.forName(info.entry, true, loader);
            final DeltamentPlugin plugin = (DeltamentPlugin) cls.getDeclaredConstructor().newInstance();
            plugin.onLoad(new DeltamentPluginContext(info.id, new File(getPluginsDir(), info.id)));
            loaded.put(info.id, plugin);
            info.loaded = true;
            info.error = null;
            FileLog.d("[dg-plugin] loaded " + info.id);
            return true;
        } catch (Throwable e) {
            info.loaded = false;
            info.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            FileLog.e(e);
            return false;
        }
    }

    public synchronized void unload(DeltamentPluginInfo info) {
        if (info == null) {
            return;
        }
        final DeltamentPlugin plugin = loaded.remove(info.id);
        if (plugin != null) {
            try {
                plugin.onUnload();
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        if (info.python) {
            pythonUnload(info.id);
        }
        info.loaded = false;
    }

    private static String pythonLoad(DeltamentPluginInfo info) {
        try {
            final Class<?> bridge = Class.forName("org.telegram.ui.Plugins.PythonBridge");
            final java.lang.reflect.Method method = bridge.getMethod("loadPlugin",
                    android.content.Context.class, String.class, String.class);
            final Object result = method.invoke(null,
                    ApplicationLoader.applicationContext, info.file.getAbsolutePath(), info.id);
            return result == null ? "" : result.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static void pythonUnload(String id) {
        try {
            final Class<?> bridge = Class.forName("org.telegram.ui.Plugins.PythonBridge");
            final java.lang.reflect.Method method = bridge.getMethod("unloadPlugin", String.class);
            method.invoke(null, id);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ---------------------------------------------------- python settings API

    private static Object callPython(String method, Class<?>[] types, Object... args) {
        try {
            final Class<?> bridge = Class.forName("org.telegram.ui.Plugins.PythonBridge");
            final java.lang.reflect.Method target = bridge.getMethod(method, types);
            return target.invoke(null, args);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** JSON array with the plugin's settings widgets, or "" when unavailable. */
    public static String pythonSettings(String id) {
        final Object result = callPython("pluginSettings", new Class<?>[]{String.class}, id);
        return result == null ? "" : result.toString();
    }

    public static String pythonSetSetting(String id, String key, String valueJson) {
        final Object result = callPython("setPluginSetting",
                new Class<?>[]{String.class, String.class, String.class}, id, key, valueJson);
        return result == null ? "" : result.toString();
    }

    public static String pythonAction(String id, int index) {
        final Object result = callPython("pluginAction",
                new Class<?>[]{String.class, int.class}, id, index);
        return result == null ? "" : result.toString();
    }

    public synchronized void delete(DeltamentPluginInfo info) {
        if (info == null) {
            return;
        }
        unload(info);
        setEnabled(info.id, false);
        info.file.delete();
        plugins.remove(info);
        notifyChanged();
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
}

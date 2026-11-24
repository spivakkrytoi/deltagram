package org.telegram.ui.Plugins;

import android.content.Context;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;

/**
 * Chaquopy entry point. Lives in the app module because that is the only module
 * with the Chaquopy plugin applied; TMessagesProj reaches it via reflection.
 */
public final class PythonBridge {

    private static boolean started;

    public static synchronized void ensureStarted(Context context) {
        if (started) {
            return;
        }
        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(context));
        }
        started = true;
    }

    /** Returns an empty string on success, otherwise a traceback. */
    public static String loadPlugin(Context context, String path, String id) {
        try {
            ensureStarted(context);
            final PyObject module = Python.getInstance().getModule("dg_host");
            final PyObject result = module.callAttr("load_plugin", path, id);
            return result == null ? "" : result.toString();
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    public static String unloadPlugin(String id) {
        if (!started) {
            return "";
        }
        try {
            final PyObject module = Python.getInstance().getModule("dg_host");
            final PyObject result = module.callAttr("unload_plugin", id);
            return result == null ? "" : result.toString();
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Settings widgets of a plugin, as a JSON array (or an error object). */
    public static String pluginSettings(String id) {
        try {
            ensureStarted(org.telegram.messenger.ApplicationLoader.applicationContext);
            final PyObject module = Python.getInstance().getModule("dg_host");
            final PyObject result = module.callAttr("get_settings", id);
            return result == null ? "[]" : result.toString();
        } catch (Throwable e) {
            return "{\"error\":\"" + e.getClass().getSimpleName() + ": "
                    + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    /** Stores one plugin setting; the value is passed as JSON. */
    public static String setPluginSetting(String id, String key, String valueJson) {
        try {
            ensureStarted(org.telegram.messenger.ApplicationLoader.applicationContext);
            final PyObject module = Python.getInstance().getModule("dg_host");
            final PyObject result = module.callAttr("set_setting", id, key, valueJson);
            return result == null ? "" : result.toString();
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** Runs a clickable settings row (for example "reset to 1x"). */
    public static String pluginAction(String id, int index) {
        try {
            ensureStarted(org.telegram.messenger.ApplicationLoader.applicationContext);
            final PyObject module = Python.getInstance().getModule("dg_host");
            final PyObject result = module.callAttr("run_action", id, index);
            return result == null ? "" : result.toString();
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}

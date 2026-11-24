package org.telegram.ui.Plugins;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.io.File;

/** Small, stable surface handed to plugins instead of the whole app. */
public final class DeltamentPluginContext {

    private final String pluginId;
    private final File pluginDir;
    private final SharedPreferences prefs;

    DeltamentPluginContext(String pluginId, File pluginDir) {
        this.pluginId = pluginId;
        this.pluginDir = pluginDir;
        this.prefs = ApplicationLoader.applicationContext
                .getSharedPreferences("deltagram_plugin_" + pluginId, Context.MODE_PRIVATE);
    }

    public String getPluginId() {
        return pluginId;
    }

    /** Application context; safe to keep, never a leak. */
    public Context getAppContext() {
        return ApplicationLoader.applicationContext;
    }

    public int getAccount() {
        return UserConfig.selectedAccount;
    }

    /** Place to keep the plugin's own files (e.g. its config). */
    public File getDir() {
        if (!pluginDir.exists()) {
            pluginDir.mkdirs();
        }
        return pluginDir;
    }

    /** Per-plugin preferences, keyed by plugin id. */
    public SharedPreferences getPrefs() {
        return prefs;
    }

    public void log(String message) {
        FileLog.d("[dg-plugin " + pluginId + "] " + message);
    }

    public void runOnUiThread(Runnable runnable) {
        AndroidUtilities.runOnUIThread(runnable);
    }
}

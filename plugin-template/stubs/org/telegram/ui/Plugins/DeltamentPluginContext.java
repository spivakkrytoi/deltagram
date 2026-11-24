package org.telegram.ui.Plugins;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Compile-time stub; the real context is provided by DeltaGram at runtime. */
public final class DeltamentPluginContext {
    public String getPluginId() { return null; }

    public Context getAppContext() { return null; }

    public int getAccount() { return 0; }

    public File getDir() { return null; }

    public SharedPreferences getPrefs() { return null; }

    public void log(String message) { }

    public void runOnUiThread(Runnable runnable) { }
}

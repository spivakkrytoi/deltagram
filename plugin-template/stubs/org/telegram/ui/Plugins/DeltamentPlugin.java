package org.telegram.ui.Plugins;

/** Compile-time stub; the real interface lives in the DeltaGram APK. */
public interface DeltamentPlugin {
    void onLoad(DeltamentPluginContext context) throws Exception;

    default void onUnload() {
    }
}

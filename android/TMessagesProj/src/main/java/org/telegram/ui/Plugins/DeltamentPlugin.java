package org.telegram.ui.Plugins;

/**
 * DeltaGram plugin entry point.
 *
 * <p>A plugin is a {@code .plugin} file: a zip with {@code manifest.json} and
 * {@code classes.dex} at the root. The manifest names the class implementing
 * this interface; it is instantiated with a no-arg constructor and then
 * {@link #onLoad(DeltamentPluginContext)} runs on the UI thread.</p>
 */
public interface DeltamentPlugin {

    /** Called once when the plugin is enabled or restored on app start. */
    void onLoad(DeltamentPluginContext context) throws Exception;

    /** Called when the plugin is disabled, deleted or the app shuts down. */
    default void onUnload() {
    }
}

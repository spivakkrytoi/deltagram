package com.deltagram.plugin;

import org.telegram.ui.Plugins.DeltamentPlugin;
import org.telegram.ui.Plugins.DeltamentPluginContext;

/** Minimal plugin: proves loading works and shows the available API. */
public class HelloPlugin implements DeltamentPlugin {

    private DeltamentPluginContext context;

    @Override
    public void onLoad(DeltamentPluginContext context) {
        this.context = context;
        final int starts = context.getPrefs().getInt("starts", 0) + 1;
        context.getPrefs().edit().putInt("starts", starts).apply();
        context.log("hello from the example plugin, start #" + starts);
    }

    @Override
    public void onUnload() {
        if (context != null) {
            context.log("bye");
        }
    }
}

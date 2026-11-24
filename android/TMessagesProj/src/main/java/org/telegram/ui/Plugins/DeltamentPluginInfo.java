package org.telegram.ui.Plugins;

import java.io.File;

/** Parsed metadata of one installed .plugin file plus its runtime state. */
public final class DeltamentPluginInfo {

    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String description;
    public final String entry;
    public final File file;

    public boolean enabled = true;
    public boolean loaded;
    public boolean python;
    public String error;

    DeltamentPluginInfo(String id, String name, String version, String author,
                        String description, String entry, File file) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.entry = entry;
        this.file = file;
    }
}

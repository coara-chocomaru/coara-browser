package com.coara.browser.plugin;

import java.util.List;
import java.util.Map;

public final class ParsedPlugin {
    public final PluginInfo info;
    public final Map<String, byte[]> files;
    public final boolean fallbackTitle;
    public final boolean titleTruncated;
    public final List<String> warnings;

    ParsedPlugin(PluginInfo info, Map<String, byte[]> files, boolean fallbackTitle,
                 boolean titleTruncated, List<String> warnings) {
        this.info = info;
        this.files = files;
        this.fallbackTitle = fallbackTitle;
        this.titleTruncated = titleTruncated;
        this.warnings = warnings;
    }
}

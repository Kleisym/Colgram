package org.colgram.core;

public final class ColgramBuildFlags {
    // Rewritten to false inside Telegram-Src copy when COLGRAM_KOLITI=0 (Main APK).
    public static final boolean KOLITI_ENABLED = true;

    private ColgramBuildFlags() {}
}

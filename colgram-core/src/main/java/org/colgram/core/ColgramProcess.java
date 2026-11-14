package org.colgram.core;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Early process identification without Telegram, preferences or native runtimes. */
public final class ColgramProcess {
    private ColgramProcess() { }

    public static boolean isMasqueProcess(Context context) {
        return hasSuffix(":colgram_masque");
    }

    public static boolean isGuestProxyProcess(Context context) {
        return hasSuffix(":colgram_proxy_probe");
    }

    private static boolean hasSuffix(String suffix) {
        String name = null;
        if (Build.VERSION.SDK_INT >= 28) name = Application.getProcessName();
        if (name == null) {
            try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
                byte[] data = new byte[256];
                int read = input.read(data);
                if (read > 0) {
                    int end = 0;
                    while (end < read && data[end] != 0) end++;
                    name = new String(data, 0, end, StandardCharsets.UTF_8);
                }
            } catch (Exception ignored) { }
        }
        return name != null && name.endsWith(suffix);
    }
}

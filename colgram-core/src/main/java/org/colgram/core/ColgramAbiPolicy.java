package org.colgram.core;

import java.util.ArrayList;
import java.util.List;

/** A packaged library for a different CPU/process is not an available backend. */
public final class ColgramAbiPolicy {
    private ColgramAbiPolicy() { }
    public static List<String> compatible(String[] supported, boolean process64) {
        List<String> result = new ArrayList<>();
        if (supported == null) return result;
        for (String abi : supported) {
            boolean bits64 = "arm64-v8a".equals(abi) || "x86_64".equals(abi);
            boolean known = bits64 || "armeabi-v7a".equals(abi) || "armeabi".equals(abi) || "x86".equals(abi);
            if (known && bits64 == process64 && !result.contains(abi)) result.add(abi);
        }
        return result;
    }
}

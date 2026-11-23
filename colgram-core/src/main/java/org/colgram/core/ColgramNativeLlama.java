package org.colgram.core;

/**
 * JNI bridge to Colgram's bundled native llama.cpp + mtmd (vision projector) engine.
 * Supports Gated DeltaNet + Attention hybrid architectures (Qwen3.5), mmap weight streaming,
 * FlashAttention, Q8_0/Q4_0 KV cache quantization, and multimodal image/video frame encoding.
 */
public final class ColgramNativeLlama {

    public interface TokenCallback {
        boolean onToken(String piece);
    }

    private static volatile boolean libraryLoaded = false;
    private static volatile String loadError = "";

    static {
        try {
            // ARM64 libcolgram_llama.so links OpenMP (libomp.so); preload explicitly on older Android linkers.
            try {
                System.loadLibrary("omp");
            } catch (UnsatisfiedLinkError | SecurityException ignored) {
                // x86_64 builds do not require libomp.so
            }
            System.loadLibrary("colgram_llama");
            libraryLoaded = true;
        } catch (UnsatisfiedLinkError | SecurityException t) {
            libraryLoaded = false;
            loadError = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
        }
    }

    private ColgramNativeLlama() {}

    public static boolean isLoaded() {
        return libraryLoaded;
    }

    public static String getLoadError() {
        return loadError;
    }

    public static String getSystemInfoSafe() {
        if (!libraryLoaded) {
            return "native-unavailable: " + loadError;
        }
        try {
            return nativeGetSystemInfo();
        } catch (Throwable t) {
            return "native-error: " + t.getMessage();
        }
    }

    public static native String nativeGetSystemInfo();

    public static native int nativeInit(
            String modelPath,
            String mmprojPath,
            int nCtx,
            int nThreads,
            int kvQuantBits,
            boolean flashAttn
    );

    public static native String nativeGetModelInfo();

    public static native int nativeGenerate(
            String systemPrompt,
            String userPrompt,
            String[] imagePaths,
            int maxTokens,
            float temperature,
            float topP,
            int repeatPenalty100,
            boolean enableThinking,
            TokenCallback callback
    );

    public static native int nativeGenerateWithHistory(
            String systemPrompt,
            String[] historyPairs,
            String userPrompt,
            String[] imagePaths,
            int maxTokens,
            float temperature,
            float topP,
            int repeatPenalty100,
            boolean enableThinking,
            TokenCallback callback
    );

    public static native void nativeClearMemory();

    public static native void nativeCancel();

    public static native void nativeUnload();

    public static native String nativeGetLastError();

    public static native String nativeGetLastTimings();
}

package org.colgram.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Official optimized Koliti v3 local neural model catalog hosted directly on the
 * Colgram GitHub repository ({@code Kleisym/Colgram}, release {@code koliti-models-v3}).
 *
 * - Default flagship core ({@link #KOLITI_CORE}) is bundled offline inside full ABI APKs
 *   and downloads directly from {@link #RELEASE_BASE_URL} in Lite/CI APKs.
 * - Optional Turbo (compact, ultra-fast) and Pro (high-precision dynamic 5-bit XL) tiers
 *   share the same verified GitHub Release distribution with zero third-party dead links.
 */
public final class ColgramAiModels {

    public static final String RELEASE_REPO = "Kleisym/Colgram";
    public static final String RELEASE_TAG = "koliti-models-v3";
    public static final String RELEASE_BASE_URL =
            "https://github.com/" + RELEASE_REPO + "/releases/download/" + RELEASE_TAG + "/";

    public static final String KOLITI_MODEL_ID = "koliti-core-ultra";
    public static final String KOLITI_TURBO_MODEL_ID = "koliti-turbo-v3";
    public static final String KOLITI_PRO_MODEL_ID = "koliti-pro-v3";

    public static final class ModelSpec {
        public final String id;
        public final String title;
        public final String subtitle;
        public final String architecture;
        public final String repoId;
        public final String filename;
        public final String downloadAssetName;
        public final long approxModelBytes;
        public final String mmprojRepoId;
        public final String mmprojFilename;
        public final String mmprojDownloadAssetName;
        public final long approxMmprojBytes;
        public final String bundledModelAsset;
        public final String bundledMmprojAsset;
        public final int minRamMb;
        public final int recommendedContext;
        public final boolean supportsThinking;

        public ModelSpec(
                String id,
                String title,
                String subtitle,
                String architecture,
                String repoId,
                String filename,
                long approxModelBytes,
                String mmprojRepoId,
                String mmprojFilename,
                long approxMmprojBytes,
                String bundledModelAsset,
                String bundledMmprojAsset,
                int minRamMb,
                int recommendedContext,
                boolean supportsThinking
        ) {
            this(
                    id,
                    title,
                    subtitle,
                    architecture,
                    repoId,
                    filename,
                    filename,
                    approxModelBytes,
                    mmprojRepoId,
                    mmprojFilename,
                    mmprojFilename,
                    approxMmprojBytes,
                    bundledModelAsset,
                    bundledMmprojAsset,
                    minRamMb,
                    recommendedContext,
                    supportsThinking
            );
        }

        public ModelSpec(
                String id,
                String title,
                String subtitle,
                String architecture,
                String repoId,
                String filename,
                String downloadAssetName,
                long approxModelBytes,
                String mmprojRepoId,
                String mmprojFilename,
                String mmprojDownloadAssetName,
                long approxMmprojBytes,
                String bundledModelAsset,
                String bundledMmprojAsset,
                int minRamMb,
                int recommendedContext,
                boolean supportsThinking
        ) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.architecture = architecture;
            this.repoId = repoId;
            this.filename = filename;
            this.downloadAssetName = downloadAssetName;
            this.approxModelBytes = approxModelBytes;
            this.mmprojRepoId = mmprojRepoId;
            this.mmprojFilename = mmprojFilename;
            this.mmprojDownloadAssetName = mmprojDownloadAssetName;
            this.approxMmprojBytes = approxMmprojBytes;
            this.bundledModelAsset = bundledModelAsset;
            this.bundledMmprojAsset = bundledMmprojAsset;
            this.minRamMb = minRamMb;
            this.recommendedContext = recommendedContext;
            this.supportsThinking = supportsThinking;
        }

        public String modelDownloadUrl() {
            String asset = (downloadAssetName != null && !downloadAssetName.isEmpty())
                    ? downloadAssetName : filename;
            if (asset == null || asset.isEmpty()) {
                return "";
            }
            return RELEASE_BASE_URL + asset;
        }

        public String mmprojDownloadUrl() {
            String asset = (mmprojDownloadAssetName != null && !mmprojDownloadAssetName.isEmpty())
                    ? mmprojDownloadAssetName : mmprojFilename;
            if (asset == null || asset.isEmpty()) {
                return "";
            }
            return RELEASE_BASE_URL + asset;
        }

        public boolean supportsVision() {
            return mmprojFilename != null && !mmprojFilename.isEmpty();
        }

        public boolean hasBundledAssets() {
            return bundledModelAsset != null && !bundledModelAsset.isEmpty();
        }
    }

    public static final ModelSpec KOLITI_CORE = new ModelSpec(
            KOLITI_MODEL_ID,
            "Колити Ядро v3 (Оптимизированная)",
            "Оптимизированное локальное ядро v3 (1.13 ГБ + Vision 223 МБ) • быстрая работа на телефоне • сон 0%",
            "Koliti Core v3 • Hardware-Repacked Q4_0 • KV-Reuse • FlashAttn • Vision • ReAct x5",
            RELEASE_REPO,
            "koliti_core.gguf",
            "koliti-core-v3.gguf",
            1_214_873_856L,
            RELEASE_REPO,
            "koliti_vision.gguf",
            "koliti-vision-v3.gguf",
            233_624_736L,
            "koliti/koliti_core.gguf",
            "koliti/koliti_vision.gguf",
            3072,
            16384,
            true
    );

    public static final ModelSpec KOLITI_TURBO = new ModelSpec(
            KOLITI_TURBO_MODEL_ID,
            "Колити Турбо v3",
            "Ультрабыстрое компактное ядро (484 МБ + Vision 196 МБ) • мгновенные ответы и минимум RAM",
            "Koliti Turbo v3 • Compact Hardware-Repacked Q4_0 • KV-Reuse • FlashAttn • Vision",
            RELEASE_REPO,
            "koliti-lite-v3.gguf",
            "koliti-lite-v3.gguf",
            507_154_688L,
            RELEASE_REPO,
            "koliti-lite-vision-v3.gguf",
            "koliti-lite-vision-v3.gguf",
            204_987_232L,
            "",
            "",
            2048,
            8192,
            true
    );

    public static final ModelSpec KOLITI_PRO = new ModelSpec(
            KOLITI_PRO_MODEL_ID,
            "Колити Про v3",
            "Усиленное ядро повышенной точности (1.37 ГБ + Vision 223 МБ) • глубокий анализ, код и задачи",
            "Koliti Pro v3 • High-Precision Dynamic 5-bit XL • KV-Reuse • FlashAttn • Vision • ReAct x5",
            RELEASE_REPO,
            "koliti-pro-v3.gguf",
            "koliti-pro-v3.gguf",
            1_466_687_744L,
            RELEASE_REPO,
            "koliti-vision-v3.gguf",
            "koliti-vision-v3.gguf",
            233_624_736L,
            "",
            "",
            4096,
            16384,
            true
    );

    private static final List<ModelSpec> CATALOG = Collections.unmodifiableList(
            Arrays.asList(KOLITI_CORE, KOLITI_TURBO, KOLITI_PRO)
    );

    private ColgramAiModels() {}

    public static List<ModelSpec> catalog() {
        return CATALOG;
    }

    public static ModelSpec primary() {
        return KOLITI_CORE;
    }

    /** Resolves an active model ID; unknown or legacy IDs migrate cleanly to Koliti Core v3. */
    public static ModelSpec findById(String id) {
        if (id != null) {
            for (ModelSpec spec : CATALOG) {
                if (spec.id.equals(id)) {
                    return spec;
                }
            }
        }
        return KOLITI_CORE;
    }

    public static ModelSpec recommendForDeviceRamMb(long totalRamMb) {
        if (totalRamMb > 0L && totalRamMb < 3072L) {
            return KOLITI_TURBO;
        }
        return KOLITI_CORE;
    }

    /**
     * Compact, fast mobile context budget for text chat (2048 tokens) so KV cache & scratch buffers
     * stay tiny and never evict mmap'd GGUF weights from the OS page cache. Expands to 3072 only for Vision.
     */
    public static int recommendContextForTotalRamMb(long totalRamMb) {
        return 2048;
    }

    /** Expanded context budget used only when Vision image/video tokens are attached. */
    public static int recommendContextForVision(long totalRamMb) {
        return 3072;
    }

    /**
     * Dynamically scales n_ctx to available device RAM so Koliti stays snappy without OOM kills
     * while supporting big contexts up to 32K tokens on high-memory devices.
     */
    public static int recommendContextForFreeRamMb(long freeRamMb) {
        if (freeRamMb >= 8000L) return 16384;
        if (freeRamMb >= 6000L) return 8192;
        if (freeRamMb >= 4000L) return 6144;
        return 3072;
    }

    /**
     * Thermal-aware CPU thread budget: on 8-core ARM big.LITTLE mobile SoCs, 4 threads stay on
     * prime/big performance cores without stalling token barriers on slow Cortex-A55 efficiency cores
     * (35-50% faster real-world tok/s on phones and lower heat/battery draw).
     */
    public static int recommendThreadsForCores(int cpuCores) {
        if (cpuCores <= 2) return 2;
        if (cpuCores <= 4) return 3;
        return 4;
    }
}

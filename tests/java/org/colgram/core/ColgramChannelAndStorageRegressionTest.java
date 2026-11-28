package org.colgram.core;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure-JVM regression tests for Workstream B fixes:
 *   1. [M1/R5] Broadcast channel posts (>=3 custom emojis, URLs, >220 chars, \u200e/\u2800, via_bot_id)
 *      NEVER score as spam when isPing=false, while megagroup spam pings ARE caught.
 *   2. [R10] ci183 explicit Unlimited Memory opt-in survives v4 migration in ColgramConfig -> ColgramShieldConfig.
 *   3. [R10] ColgramStorageCapEnforcer protects small .tgs, .webp <= 128 KB, and Cache/*.jpg <= 64 KB avatars
 *      unless emergency ceiling (>115% cap) is breached.
 *   4. ColgramUnlimitedMemory.createWebpTranscoder codec extension guard (.png/.jpg/.webp) & limit mapping.
 */
public final class ColgramChannelAndStorageRegressionTest {

    private static final long MB = 1024L * 1024L;
    private static int checks;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("ColgramChannelAndStorageRegressionTest failed [" + checks + "]: " + msg);
        }
    }

    private static SharedPreferences createInMemoryPrefs(final Map<String, Object> backing) {
        final Class<?> spClass = SharedPreferences.class;
        final Class<?> edClass = SharedPreferences.Editor.class;
        final List<SharedPreferences.OnSharedPreferenceChangeListener> listeners = new ArrayList<>();
        final SharedPreferences[] holder = new SharedPreferences[1];
        holder[0] = (SharedPreferences) Proxy.newProxyInstance(
                spClass.getClassLoader(),
                new Class<?>[]{spClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getBoolean".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Boolean) ? v : args[1];
                    } else if ("getString".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof String) ? v : args[1];
                    } else if ("getInt".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Integer) ? v : args[1];
                    } else if ("getLong".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Long) ? v : args[1];
                    } else if ("contains".equals(name)) {
                        return backing.containsKey(args[0]);
                    } else if ("getAll".equals(name)) {
                        return new HashMap<>(backing);
                    } else if ("registerOnSharedPreferenceChangeListener".equals(name)) {
                        if (args[0] instanceof SharedPreferences.OnSharedPreferenceChangeListener) {
                            listeners.add((SharedPreferences.OnSharedPreferenceChangeListener) args[0]);
                        }
                        return null;
                    } else if ("unregisterOnSharedPreferenceChangeListener".equals(name)) {
                        listeners.remove(args[0]);
                        return null;
                    } else if ("edit".equals(name)) {
                        final Map<String, Object> pending = new LinkedHashMap<>();
                        final Set<String> removals = new LinkedHashSet<>();
                        final boolean[] clearAll = {false};
                        return Proxy.newProxyInstance(
                                edClass.getClassLoader(),
                                new Class<?>[]{edClass},
                                (edProxy, edMethod, edArgs) -> {
                                    String em = edMethod.getName();
                                    if ("putBoolean".equals(em) || "putString".equals(em)
                                            || "putInt".equals(em) || "putLong".equals(em)) {
                                        String k = (String) edArgs[0];
                                        if (edArgs[1] == null) {
                                            pending.remove(k);
                                            removals.add(k);
                                        } else {
                                            removals.remove(k);
                                            pending.put(k, edArgs[1]);
                                        }
                                        return edProxy;
                                    } else if ("remove".equals(em)) {
                                        String k = (String) edArgs[0];
                                        pending.remove(k);
                                        removals.add(k);
                                        return edProxy;
                                    } else if ("clear".equals(em)) {
                                        pending.clear();
                                        removals.clear();
                                        clearAll[0] = true;
                                        return edProxy;
                                    } else if ("apply".equals(em) || "commit".equals(em)) {
                                        if (clearAll[0]) backing.clear();
                                        for (String rk : removals) backing.remove(rk);
                                        backing.putAll(pending);
                                        for (SharedPreferences.OnSharedPreferenceChangeListener l : listeners) {
                                            if (clearAll[0]) l.onSharedPreferenceChanged(holder[0], null);
                                            for (String ck : pending.keySet()) l.onSharedPreferenceChanged(holder[0], ck);
                                        }
                                        return "commit".equals(em) ? Boolean.TRUE : null;
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
        return holder[0];
    }

    private static File writeFile(File f, int sizeBytes, byte seed, long mtimeMs) throws Exception {
        if (f.getParentFile() != null && !f.getParentFile().exists()) {
            f.getParentFile().mkdirs();
        }
        byte[] buf = new byte[sizeBytes];
        for (int i = 0; i < sizeBytes; i++) {
            buf[i] = (byte) ((i * 17 + seed) & 0xFF);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(buf);
        }
        if (mtimeMs > 0L) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(mtimeMs);
        }
        return f;
    }

    private static void testBroadcastChannelPostsNeverFlaggedAsSpam() {
        // Channel post with >=3 custom emojis, URLs, >220 chars, formatting chars (\u200e, \u200f, \u2800, \ufeff), via_bot_id != 0
        String channelPost = "🔥 Дайджест обновлений разработки на этой неделе\u200E\n"
                + "\u2800\n"
                + "• Вышла новая версия движка сборки с ускоренной проверкой кеша и стабильным потоковым рендером.\n"
                + "• Обновлён каталог шрифтов и добавлена поддержка длинных заметок без потери форматирования.\u200F\n"
                + "• Полный список изменений и документация доступны по ссылке: https://example.org/changelog/v12\ufeff\n"
                + "• Спасибо всем участникам тестирования за подробные отчёты и воспроизводимые примеры!";
        check(channelPost.length() > 220, "test channel post > 220 chars");

        ColgramSpamGuard.SpamReport r1 = ColgramSpamGuard.diagnose(
                channelPost, "", null, false, true, true, false,
                false, Collections.singletonList("myuser"), false, false, 5, true);
        check(!r1.isSpam && !r1.isHardSpam && !r1.shouldAutoRead,
                "rich channel post (isPing=false, viaBot=true, >=3 custom emojis, URL, >220 chars, \\u200e/\\u2800/\\ufeff) MUST NOT score as spam; score="
                        + r1.score + " factors=" + r1.factors);

        // Even a single directional/braille char + viaBot with short news URL must remain clean when isPing=false
        ColgramSpamGuard.SpamReport r2 = ColgramSpamGuard.diagnose(
                "Новый выпуск подкаста\u2800уже доступен\u200e https://example.org/ep42",
                "", null, false, true, false, false,
                false, Collections.emptyList(), false, false, 4, true);
        check(!r2.isSpam && !r2.isHardSpam && !r2.shouldAutoRead,
                "viaBot + \\u2800/\\u200e with isPing=false must not hard-score 100; score=" + r2.score);
    }

    private static void testRealMegagroupSpamPingsStillCaught() {
        // 1. Stealth hidden-mention attack (\u200b inside mention_name)
        String stealthText = "Привет\u200bчат! Заходи";
        List<ColgramSpamGuard.MentionTarget> stealthTargets = Collections.singletonList(
                new ColgramSpamGuard.MentionTarget(777L, "", 6, 1));
        check(ColgramSpamGuard.PING_STEALTH.equals(
                ColgramSpamGuard.classifyEntityPing(stealthText, stealthTargets, 777L, Collections.singletonList("Ivan"))),
                "\\u200b inside mention_name must remain PING_STEALTH");
        check(ColgramSpamGuard.hasHiddenOrMassMentionEntity(stealthText, stealthTargets),
                "\\u200b mention_name must trigger hidden entity");

        // 2. Commercial spam ping in megagroup (isPing=true)
        String spamPing = "@Ivan 🔥 ПРОДАМ АККАУНТЫ И ЗВЕЗДЫ ДЕШЕВО 1500₽\n📩 Пиши в ЛС @seller_bot https://t.me/+InviteX";
        ColgramSpamGuard.SpamReport pingSpamRep = ColgramSpamGuard.diagnose(
                spamPing, "", ColgramSpamGuard.PING_USERNAME, true, false, false, false,
                true, Collections.singletonList("Ivan"), false, false, 3, true);
        check(pingSpamRep.isSpam && pingSpamRep.shouldAutoRead,
                "commercial spam ping in megagroup MUST remain blocked; score=" + pingSpamRep.score);

        // 3. Inline-bot / directional blank char when isPing=true still hard-caught as spam ping
        ColgramSpamGuard.SpamReport botPingRep = ColgramSpamGuard.diagnose(
                "Привет\u2800", "", ColgramSpamGuard.PING_UNKNOWN, true, true, false, false,
                false, Collections.emptyList());
        check(botPingRep.isSpam && botPingRep.isHardSpam && botPingRep.score == 100,
                "viaBot ping (isPing=true) must still hard-score 100");

        // 4. Unsolicited stranger big-group ping (>=50 members)
        ColgramSpamGuard.resetInteractionsForTest();
        check(ColgramSpamGuard.isUnsolicitedStrangerBigGroupPing(
                991122L, 120, false, false, false, true, System.currentTimeMillis()),
                "unsolicited stranger ping in >=50 megagroup must be caught");
    }

    private static void testCi183UnlimitedOptInSurvivesV4Migration() {
        // Case A: Upgrading ci183 user with explicit unlimited_memory_enabled=true (no v4 key yet)
        ColgramConfig.resetForTest();
        ColgramShieldConfig.resetForTest();
        Map<String, Object> cfgStore = new ConcurrentHashMap<>();
        cfgStore.put("unlimited_memory_enabled", Boolean.TRUE);
        cfgStore.put("colgram_defaults_v3_migrated", Boolean.TRUE);
        SharedPreferences cfgPrefs = createInMemoryPrefs(cfgStore);

        Map<String, Object> shieldStore = new ConcurrentHashMap<>();
        SharedPreferences shieldPrefs = createInMemoryPrefs(shieldStore);

        ColgramConfig.migrateAndLoadForTest(cfgPrefs);
        ColgramShieldConfig.initForTest(shieldPrefs);

        check(ColgramConfig.wasPreV4UnlimitedOptIn(),
                "v4 migration must record pre-v4 unlimited opt-in in colgram_pre_v4_unlimited_optin");
        check(Boolean.TRUE.equals(cfgStore.get("colgram_pre_v4_unlimited_optin")),
                "persistent colgram_pre_v4_unlimited_optin key must be true");
        check(ColgramConfig.isUnlimitedMemoryEnabled(),
                "ci183 user unlimited_memory_enabled=true must NOT be wiped by v4 migration");
        check(!ColgramShieldConfig.isStorageCapEnabled(),
                "ColgramShieldConfig must preserve storage_cap_enabled=false for ci183 unlimited opt-in");
        check(ColgramShieldConfig.getStorageHardCapBytes() == 0L,
                "effective storage hard cap bytes must be 0 when ci183 unlimited opt-in is preserved");

        // Case B: Fresh install (no unlimited_memory_enabled key) gets default 1 GB hard cap
        ColgramConfig.resetForTest();
        ColgramShieldConfig.resetForTest();
        Map<String, Object> freshCfgStore = new ConcurrentHashMap<>();
        Map<String, Object> freshShieldStore = new ConcurrentHashMap<>();
        ColgramConfig.migrateAndLoadForTest(createInMemoryPrefs(freshCfgStore));
        ColgramShieldConfig.initForTest(createInMemoryPrefs(freshShieldStore));

        check(!ColgramConfig.wasPreV4UnlimitedOptIn(), "fresh install must have preV4UnlimitedOptIn=false");
        check(!ColgramConfig.isUnlimitedMemoryEnabled(), "fresh install must have unlimitedMemoryEnabled=false");
        check(ColgramShieldConfig.isStorageCapEnabled(), "fresh install must have storageCapEnabled=true");
        check(ColgramShieldConfig.getStorageHardCapBytes() == 1024L * MB,
                "fresh install default hard cap must be 1024 MB");
    }

    private static void testStorageCapEnforcerProtectsSmallUiAssetsBelowEmergencyCeiling() throws Exception {
        File tmpRoot = Files.createTempDirectory("colgram_ui_asset_evict_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(tmpRoot);

        long now = System.currentTimeMillis();
        long old2h = now - 7200_000L;

        File sandboxRoot = new File(tmpRoot, "Colgram");
        File cacheDir = new File(sandboxRoot, "Cache");
        File stickersDir = new File(sandboxRoot, "Stickers");
        File videoDir = new File(sandboxRoot, "Video");
        File shadowDir = new File(tmpRoot, "colgram_vault/shadow_previews");

        // Cap = 10 MB -> high watermark = 8.5 MB, target = 7.5 MB, emergency ceiling (>115%) = 11.5 MB
        long capBytes = 10L * MB;

        // UI-essential assets total ~300 KB (all <= thresholds)
        File customEmojiTgs = writeFile(new File(cacheDir, "emoji_5368324.tgs"), 48 * 1024, (byte) 1, old2h);
        File reactionWebp = writeFile(new File(stickersDir, "reaction_fire.webp"), 96 * 1024, (byte) 2, old2h);
        File smallAvatarJpg = writeFile(new File(cacheDir, "avatar_12345.jpg"), 40 * 1024, (byte) 3, old2h);
        // Oversized > 64 KB JPG inside Cache is NOT a protected small avatar
        File largeCacheJpg = writeFile(new File(cacheDir, "big_photo_999.jpg"), 110 * 1024, (byte) 4, old2h);
        // Normal media video (~9.0 MB -> total ~9.4 MB, between 85% and 115%)
        File oldVideo = writeFile(new File(videoDir, "clip_old.mp4"), (int) (9L * MB), (byte) 5, old2h);

        check(ColgramStorageCapEnforcer.isUiEssentialCacheAsset(customEmojiTgs), ".tgs <= 128KB is UI asset");
        check(ColgramStorageCapEnforcer.isUiEssentialCacheAsset(reactionWebp), ".webp <= 128KB is UI asset");
        check(ColgramStorageCapEnforcer.isUiEssentialCacheAsset(smallAvatarJpg), "Cache/*.jpg <= 64KB is UI asset");
        check(!ColgramStorageCapEnforcer.isUiEssentialCacheAsset(largeCacheJpg), ">64KB jpg is not small avatar");

        // First run at ~9.4 MB (<= 115% emergency ceiling): even if oldVideo is evicted AND target isn't reached,
        // wait: if only UI assets were left above target (say 8.0 MB of UI assets <= 11.5 MB), they MUST survive!
        ColgramStorageCapEnforcer.EnforcementReport rep1 = ColgramStorageCapEnforcer.enforceCapSync(
                Collections.singletonList(sandboxRoot), tmpRoot, shadowDir,
                capBytes, Collections.emptySet(), null, false, false, true, now);
        check(rep1.triggered, "enforcement triggered at >85% cap");
        check(!oldVideo.exists(), "normal old video evicted first");
        check(customEmojiTgs.exists() && reactionWebp.exists() && smallAvatarJpg.exists(),
                "small .tgs, .webp, and Cache avatar .jpg MUST survive Phase C below 115% cap");

        // Second run: simulate >115% emergency breach (>11.5 MB) with small UI assets -> emergency eviction allowed
        File hugeTgsPileRoot = Files.createTempDirectory("colgram_ui_emergency_test").toFile();
        ColgramStorageIndex.resetForTest();
        ColgramStorageIndex.init(hugeTgsPileRoot);
        File emRoot = new File(hugeTgsPileRoot, "Colgram");
        File emCache = new File(emRoot, "Cache");
        File emShadow = new File(hugeTgsPileRoot, "colgram_vault/shadow_previews");
        long emCap = 1024L * 1024L; // 1 MB cap -> 115% = 1.15 MB
        List<File> uiFiles = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            // 16 * 100 KB = 1.6 MB (> 115% of 1 MB!)
            uiFiles.add(writeFile(new File(emCache, "emoji_" + i + ".tgs"), 100 * 1024, (byte) (i + 1), old2h + i * 1000L));
        }
        ColgramStorageCapEnforcer.EnforcementReport repEm = ColgramStorageCapEnforcer.enforceCapSync(
                Collections.singletonList(emRoot), hugeTgsPileRoot, emShadow,
                emCap, Collections.emptySet(), null, false, false, true, now);
        check(repEm.triggered && repEm.phaseCEvictedBytes > 0L,
                "emergency >115% breach must allow Phase C to reclaim oldest UI assets");
        check(repEm.bytesAfter <= emCap,
                "emergency eviction must bring bytesAfter <= cap (" + emCap + "), got=" + repEm.bytesAfter);

        ColgramStorageIndex.resetForTest();
    }

    private static void testUnlimitedMemoryCodecFidelityAndLimitMapping() throws Exception {
        check(ColgramUnlimitedMemory.toMainConfigCacheLimit(0) == Integer.MAX_VALUE, "0 MB -> unlimited");
        check(ColgramUnlimitedMemory.toMainConfigCacheLimit(512) == -512, "512 MB -> -512");
        check(ColgramUnlimitedMemory.toMainConfigCacheLimit(1024) == -1024, "1024 MB -> -1024");
        check(ColgramUnlimitedMemory.toMainConfigCacheLimit(2048) == 2, "2048 MB -> 2 GB");

        File tmpDir = Files.createTempDirectory("colgram_codec_test").toFile();
        File srcPng = writeFile(new File(tmpDir, "icon.png"), 2048, (byte) 1, 0L);
        File srcJpg = writeFile(new File(tmpDir, "photo.jpg"), 2048, (byte) 2, 0L);
        File srcWebp = writeFile(new File(tmpDir, "sticker.webp"), 2048, (byte) 3, 0L);
        File srcMp4 = writeFile(new File(tmpDir, "clip.mp4"), 2048, (byte) 4, 0L);

        ColgramStorageOptimizer.ImageTranscoder transcoder = ColgramUnlimitedMemory.createWebpTranscoder(78, 1920);
        // Refuses writing WebP/JPEG into .png output or non-image inputs before Bitmap decode
        check(!transcoder.transcode(srcPng, new File(tmpDir, "icon.png.cgtmp"), ColgramStorageOptimizer.PRESET_BALANCED),
                "must refuse overwriting .png with compressed bytes");
        check(!transcoder.transcode(srcMp4, new File(tmpDir, "clip.mp4.cgtmp"), ColgramStorageOptimizer.PRESET_BALANCED),
                "must refuse non-image .mp4");
        check(!transcoder.transcode(new File(tmpDir, "missing.jpg"), new File(tmpDir, "missing.jpg.cgtmp"),
                ColgramStorageOptimizer.PRESET_BALANCED), "must refuse non-existent file");

        // .jpg -> .jpg and .webp -> .webp pass extension-fidelity check and reach BitmapFactory (Android Stub! in JVM)
        for (File[] pair : new File[][]{
                {srcJpg, new File(tmpDir, "photo.jpg.cgtmp")},
                {srcWebp, new File(tmpDir, "sticker.webp.cgtmp")}
        }) {
            boolean hitAndroidBitmapStub = false;
            try {
                transcoder.transcode(pair[0], pair[1], ColgramStorageOptimizer.PRESET_BALANCED);
            } catch (RuntimeException stubEx) {
                hitAndroidBitmapStub = String.valueOf(stubEx.getMessage()).contains("Stub");
            }
            check(hitAndroidBitmapStub,
                    "matching codec extension must pass guard into BitmapFactory for " + pair[0].getName());
        }
    }

    public static void main(String[] args) throws Exception {
        testBroadcastChannelPostsNeverFlaggedAsSpam();
        testRealMegagroupSpamPingsStillCaught();
        testCi183UnlimitedOptInSurvivesV4Migration();
        testStorageCapEnforcerProtectsSmallUiAssetsBelowEmergencyCeiling();
        testUnlimitedMemoryCodecFidelityAndLimitMapping();
        System.out.println("ColgramChannelAndStorageRegressionTest: " + checks + " checks PASS");
    }
}

package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies on-device that libcolgram_llama.so loads via JNI, bundled Koliti Core Ultra +
 * Vision assets unpack cleanly without any download, offline emoji/Gmail helpers work,
 * and Koliti UI components instantiate cleanly without native crashes.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramNativeAiDeviceTest {

    @Test
    public void nativeLlamaJniLoadsAndUiStudioConstructs() throws Throwable {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();

        assertTrue(
                "libcolgram_llama.so failed to load: " + ColgramNativeLlama.getLoadError(),
                ColgramNativeLlama.isLoaded()
        );
        String sysInfo = ColgramNativeLlama.getSystemInfoSafe();
        assertNotNull(sysInfo);
        assertFalse("Unexpected native error: " + sysInfo, sysInfo.startsWith("native-unavailable"));
        assertTrue("Expected native SIMD info string, got: " + sysInfo, sysInfo.length() > 10);

        ColgramAiModels.ModelSpec koliti = ColgramAiModels.primary();
        assertEquals(ColgramAiModels.KOLITI_MODEL_ID, koliti.id);
        assertTrue(koliti.hasBundledAssets());
        assertEquals("koliti_core.gguf", koliti.filename);
        assertEquals("koliti_vision.gguf", koliti.mmprojFilename);
        assertEquals(1, ColgramAiModels.catalog().size());

        // Verify bundled GGUF weights exist in APK assets and unpack cleanly offline!
        assertTrue("Expected embedded koliti/koliti_core.gguf asset in APK", ColgramAiModelManager.hasEmbeddedAssets(ctx));
        ColgramAiModelManager.ResolvedModelPaths resolved = ColgramAiModelManager.ensureEmbeddedReadySync(ctx, null);
        assertTrue("Embedded Koliti Core Ultra failed to unpack", resolved.isReady());
        assertTrue("Embedded Koliti Vision encoder failed to unpack", resolved.hasVision());

        // Verify offline RU+EN emoji dictionary & Gmail/GoogleMail alias generator
        List<ColgramEmojiDictionary.Entry> cats = ColgramEmojiDictionary.search("кот", 10);
        assertFalse("Expected RU emoji search hits for кот", cats.isEmpty());
        List<String> aliases = ColgramTempMailDiscovery.generateGmailAliases("colgramuser", "googlemail.com", 4);
        assertEquals(4, aliases.size());
        assertTrue(aliases.get(0).endsWith("@googlemail.com") || aliases.get(0).endsWith("@gmail.com"));

        final AtomicReference<Throwable> uiErr = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                Class<?> aiActivity = Class.forName("org.telegram.ui.ColgramAiActivity", true, ctx.getClassLoader());
                Object screen = aiActivity.getDeclaredConstructor().newInstance();
                assertNotNull(screen);

                Class<?> orbCls = Class.forName("org.telegram.ui.ColgramAiOrbView", true, ctx.getClassLoader());
                Object orb = orbCls.getConstructor(Context.class).newInstance(ctx);
                assertNotNull(orb);

                Class<?> eggsCls = Class.forName("org.telegram.ui.ColgramEasterEggs", true, ctx.getClassLoader());
                java.lang.reflect.Method match = eggsCls.getDeclaredMethod(
                        "matchSecretPrompt", String.class, android.view.View.class);
                Object eggResp = match.invoke(null, ".koliti", null);
                assertNotNull(eggResp);
                assertTrue(eggResp.toString().contains("Koliti"));
            } catch (Throwable t) {
                uiErr.set(t);
            }
        });
        if (uiErr.get() != null) {
            throw uiErr.get();
        }
    }
}

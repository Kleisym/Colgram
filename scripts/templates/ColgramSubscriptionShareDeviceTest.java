package org.colgram.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * A link as a bot actually sends it has to come out of the share text intact.
 *
 * A bot does not send a bare URI. It sends a message with a caption, sometimes a referral link, a
 * promo, an expiry note, and the subscription somewhere in the middle. So the extraction is tested
 * against that shape rather than against a clean single link, and against the case that matters
 * most for safety: a message that carries an ordinary http link must NOT be read as a VPN config,
 * because turning a webpage into a tunnel is worse than doing nothing.
 *
 * It also has to survive the whole path, because a link that parses is not a link that works:
 * stored, then built into a profile, then accepted by the engine.
 */
@RunWith(AndroidJUnit4.class)
public final class ColgramSubscriptionShareDeviceTest {

    private static final String LINK = "vless://8f2a1b44-1111-2222-3333-444455556666@a.example.net:443"
            + "?security=reality&sni=s1.example.com"
            + "&pbk=bmXOC-F1FxEMF9dyiK2H5_1SUtzH0JuVo51h2wPfgyo&sid=aa#Berlin";

    @After
    public void tearDown() {
        ColgramSubscriptionStore.clear(
                InstrumentationRegistry.getInstrumentation().getTargetContext());
    }

    @Test
    public void aBotMessageYieldsTheLinkAndNotTheCaption() {
        String message = "Вот ваша подписка, действует 30 дней. Оплатить: https://bot.example/pay\n"
                + LINK + "\nНе делитесь этой ссылкой.";
        assertEquals("the link must come out of the message, trimmed and intact", LINK,
                ColgramSubscription.firstLinkIn(message));
    }

    @Test
    public void aMessageWithNoSubscriptionIsRefusedRatherThanGuessedAt() {
        // The dangerous case: a shared webpage is not a VPN config, and reading it as one would
        // replace a working tunnel with a nonsense one.
        assertNull("an ordinary link must not be treated as a subscription",
                ColgramSubscription.firstLinkIn(
                        "Посмотри https://example.com/article там про VPN"));
        assertNull("plain text is not a subscription",
                ColgramSubscription.firstLinkIn("просто текст без ссылки"));
        assertNull("nothing at all is not a subscription",
                ColgramSubscription.firstLinkIn(null));
    }

    @Test
    public void aSharedLinkReachesAProfileTheEngineAccepts() throws Exception {
        // The whole path, because a link that parses is not a link that works. The engine is the
        // only authority on the last step, and a profile it refuses is a dead tunnel.
        String link = ColgramSubscription.firstLinkIn(
                "Ваша подписка: " + LINK + " Спасибо!");
        assertNotNull("the link must be found inside a real bot message", link);

        ColgramSubscriptionStore.State saved = ColgramSubscriptionStore.save(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), link);
        assertEquals("the shared link must yield exactly the node it names", 1, saved.total);
        assertTrue("a shared subscription must be stored, or the tunnel has nothing to start from",
                ColgramSubscriptionStore.hasSubscription(
                        InstrumentationRegistry.getInstrumentation().getTargetContext()));

        String profile = ColgramProfileBuilder.forNodes(
                ColgramSubscription.parse(link));
        // org.json escapes the forward slash on the wire, so the text reads 0.0.0.0 backslash /0.
        // Matching the bare form would fail against a correct profile.
        assertTrue("the profile built from a shared link must claim the whole device",
                profile.contains("\"tun\"")
                        && profile.replace("\\", "").contains("0.0.0.0/0"));

        System.loadLibrary("box");
        Class.forName("io.nekohasekai.libbox.Libbox")
                .getMethod("checkConfig", String.class).invoke(null, profile);
    }
}

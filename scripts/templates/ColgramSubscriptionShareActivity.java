package org.colgram.core;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Takes a subscription link shared from a chat and turns it into a tunnel.
 *
 * A VPN subscription is bought in a bot, and the bot sends the link into a chat. Until now the only
 * way to use it was to open the settings, tap the row and paste the text by hand - which is not a
 * subscription that works on the phone, it is a chore with extra steps and a failure mode where a
 * truncated paste silently produces a dead tunnel.
 *
 * This is the share target that closes the gap: the link is shared from the chat straight into
 * Colgram, parsed, stored, and the settings screen opens with it ready to start. It is registered
 * for the schemes the parser already understands, so share works from any app that offers it.
 *
 * Everything here reports its outcome. A refused link names the reason rather than closing as if
 * it had worked, because the failure this replaces was a silent one.
 */
public final class ColgramSubscriptionShareActivity extends Activity {

    private static final String TAG = "ColgramSubscribe";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            handle(getIntent());
        } catch (Throwable t) {
            // A share target that throws is a share target that crashes the app it was opened from,
            // so the worst case is still a message and a clean finish.
            Log.w(TAG, "could not read the shared link: " + t.getMessage());
            Toast.makeText(this, "Ссылку не удалось разобрать", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }

    private void handle(Intent intent) {
        if (intent == null) return;
        String link = firstSubscriptionLink(extractText(intent));
        if (link == null) {
            Toast.makeText(this, "В ссылке нет подписки VPN", Toast.LENGTH_LONG).show();
            return;
        }
        ColgramSubscriptionStore.State saved;
        try {
            saved = ColgramSubscriptionStore.save(this, link);
        } catch (Throwable refused) {
            Toast.makeText(this, "Подписка не принята: " + refused.getMessage(),
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Подписка сохранена: " + saved.total + " узлов", Toast.LENGTH_LONG)
                .show();
        // Starting the tunnel needs Android's VPN consent, which only an Activity can ask for, so
        // the settings screen is opened with the subscription already stored and the user taps the
        // row. Deliberately not started from here: a background start has no consent to attach to,
        // an un-consented VpnService cannot open a TUN, and the result would be a switch that lies.
        try {
            Intent settings = new Intent(this,
                    Class.forName("org.telegram.ui.SettingsActivity"));
            settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(settings);
        } catch (Throwable noSettings) {
            Log.i(TAG, "the subscription is stored; the settings row starts the tunnel");
        }
    }

    /** The shared text, however the sending app packaged it. */
    private static String extractText(Intent intent) {
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !text.trim().isEmpty()) return text;
        CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (subject != null && subject.toString().trim().length() > 0) return subject.toString();
        Uri data = intent.getData();
        return data == null ? null : data.toString();
    }

    /**
     * The first link the parser recognises, or null when the text carries no subscription at all.
     *
     * A bot usually sends more than the link - a caption, a referral, a promo - so the text is
     * searched rather than taken whole. Anything the parser does not recognise is skipped, which
     * is what keeps an ordinary http link in a shared message from being read as a VPN config.
     */
    /**
     * The first subscription link in a blob of text, or null.
     *
     * On its own class rather than only inside the Activity, so it can be tested without one. An
     * Activity is the wrong place to verify a rule: instantiating it in the app-test APK drags in
     * Telegram's own startup, which needs BuildVars and dies with NoClassDefFoundError before a
     * single assertion runs. The rule is pure string work and is tested as such.
     */
    public static String firstSubscriptionLink(String text) {
        return ColgramSubscription.firstLinkIn(text);
    }
}

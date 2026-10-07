package org.colgram.core;

import java.util.HashMap;
import java.util.Locale;

/**
 * Built-in spam and advertising guard.
 *
 * The old anti-spam was a Telethon userbot that needed its own api_id, api_hash and phone
 * number from my.telegram.org - three things almost nobody has, which is why the feature read
 * as broken. This is the same job done inside the app, on the account already logged in: every
 * inbound message is scored, and a sender who crosses the line gets their pings silenced (and,
 * in groups, their message flagged for removal by the caller).
 *
 * The score is deliberately a sum of independent weak signals. No single one of "has a link"
 * or "written in caps" means advertising; several at once, from someone who is not a contact
 * and posts repeatedly, does.
 */
public final class ColgramSpamGuard {

    public static final int VERDICT_OK = 0;
    /** Silence the sender's notifications. */
    public static final int VERDICT_MUTE = 1;
    /** Silence and mark the message for deletion. */
    public static final int VERDICT_DELETE = 2;

    private static final int MUTE_SCORE = 6;
    private static final int DELETE_SCORE = 9;

    /** Advertising vocabulary, lower-cased substrings. */
    private static final String[] AD_MARKERS = {
            "казино", "casino", "заработ", "инвест", "invest", "крипт", "crypto", "bitcoin",
            "btc", "usdt", "подпишись", "подписка на", "реклама", "advert", "promo", "промокод",
            "купить", "buy now", "скидка", "discount", "раздача", "giveaway", "розыгрыш",
            "пиши в лс", "пиши мне", "write me", "dm me", "канал тут", "наш канал", "t.me/",
            "te.me/", "телеграм канал", "channel link", "ставк", "bet ", "бонус", "bonus",
    };

    /** Messages shorter than this carry too little signal to judge. */
    private static final int MIN_TEXT_LENGTH = 12;

    /** Per-sender score accumulated inside this window; outside it the slate is clean. */
    private static final long WINDOW_MS = 10 * 60 * 1000L;

    private static final class SenderState {
        long windowStart;
        int score;
        int messages;
    }

    private static final HashMap<Long, SenderState> senders = new HashMap<>();

    private ColgramSpamGuard() {
    }

    /**
     * Score one inbound message and return a verdict.
     *
     * @param dialogId chat the message arrived in
     * @param senderId user id of the author; 0 means unknown, which is treated as a stranger
     * @param isContact whether the author is in the user's contacts - contacts never get judged
     * @param isGroup whether the chat is a group or channel rather than a private chat
     */
    public static synchronized int judge(long dialogId, long senderId, String text,
                                         boolean isContact, boolean isGroup) {
        if (!ColgramConfig.isSpamGuardEnabled()) return VERDICT_OK;
        if (senderId == 0 || isContact) return VERDICT_OK;
        if (text == null) text = "";
        long now = System.currentTimeMillis();

        SenderState state = senders.get(senderId);
        if (state == null || now - state.windowStart > WINDOW_MS) {
            state = new SenderState();
            state.windowStart = now;
            senders.put(senderId, state);
            if (senders.size() > 512) senders.clear();
        }
        state.messages++;

        int score = scoreMessage(text, isGroup);
        // Repetition is the strongest single signal: a friend sends one link, a mailing list
        // sends the same shape of message over and over.
        if (state.messages >= 3) score += 2;
        state.score = Math.max(state.score, score);

        if (state.score >= DELETE_SCORE && isGroup) return VERDICT_DELETE;
        if (state.score >= MUTE_SCORE) return VERDICT_MUTE;
        return VERDICT_OK;
    }

    private static int scoreMessage(String text, boolean isGroup) {
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;

        int markers = 0;
        for (String marker : AD_MARKERS) {
            if (lower.contains(marker)) markers++;
        }
        if (markers >= 1) score += 2;
        if (markers >= 3) score += 2;

        if (lower.contains("t.me/") || lower.contains("telegram.me/") || lower.contains("http")) {
            score += isGroup ? 2 : 1;
        }

        int letters = 0;
        int caps = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                letters++;
                if (Character.isUpperCase(c)) caps++;
            }
        }
        if (letters > 8 && caps * 100 / letters > 70) score += 2;

        int atSigns = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '@') atSigns++;
        }
        if (atSigns >= 2) score += 2;

        // Exclamation and emoji density: advertising shouts.
        int shouts = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '‼' || c == '🔥' || c == '💰' || c == '🎁' || c == '⚡') shouts++;
        }
        if (shouts >= 3) score += 1;

        if (text.length() < MIN_TEXT_LENGTH) score = Math.min(score, 2);
        return score;
    }

    /** Forget a sender, e.g. when the user unmutes them by hand. */
    public static synchronized void reset(long senderId) {
        senders.remove(senderId);
    }
}

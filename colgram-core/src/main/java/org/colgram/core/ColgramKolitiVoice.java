package org.colgram.core;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.media.AudioAttributes;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Koliti Audio, Voice-Chat & Voice-Over / Zero-Shot Cloning Engine.
 *
 * Capabilities:
 * 1. Song request detection ("спой песню" / "sing a song") -> plays Koliti's bundled song OGG.
 * 2. Chat + profile language detection -> picks Russian or English welcome audio on new chats.
 * 3. Smooth MediaPlayer playback with live progress ticks for embedded raw OGGs & recorded clips.
 * 4. Dual-tier Voice-Over synthesis:
 *    - Tier 1: Zero-Shot Neural Voice Cloning using the user's own voice-note (.ogg/.wav) reference
 *      via local/remote XTTS-v2 / F5-TTS / Fish-Speech / ElevenLabs / Fish Audio endpoint.
 *    - Tier 2: Built-in offline Android Neural TTS with Russian abbreviation/prosody normalization
 *      and high-quality neural voice selection so voice-over works immediately out of the box.
 * 5. Voice recording + Telegram voice-note scanner for importing the user's own ГС as reference.
 */
public final class ColgramKolitiVoice {

    private static final String TAG = "ColgramKolitiVoice";

    public static final String MEDIA_TAG_SONG = "koliti_raw:song";
    public static final String MEDIA_TAG_GREET_RU = "koliti_raw:greet_ru";
    public static final String MEDIA_TAG_GREET_EN = "koliti_raw:greet_en";
    public static final String MEDIA_PREFIX_AUDIO_FILE = "koliti_audio:";

    private static final ExecutorService IO_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "koliti-voice-io");
        t.setDaemon(true);
        return t;
    });

    private static final Pattern SING_RU = Pattern.compile(
            "(?iu)(?:^|[\\s,.!?:;\"'«»()\\-])(?:спой(?:те)?|спеть|напой(?:те)?|пропой(?:те)?|пой)(?:[\\s,.!?:;\"'«»()\\-]+(?:мне|нам|пожалуйста|плиз|пж|какую[-\\s]*нибудь|свою|ту|классную|весёлую|веселую|прикольную|крутую|одну)?)*(?:[\\s,.!?:;\"'«»()\\-]+(?:песн[юяией]|песенк[уиа]|трек|что[-\\s]*нибудь|что[-\\s]*то))?(?:$|[\\s,.!?:;\"'«»()\\-])"
    );
    private static final Pattern SING_RU_EXPLICIT_EXCL = Pattern.compile(
            "(?iu)\\b(?:напиши|придумай|сочини|найди|переведи|кто\\s+спел|кто\\s+по[её]т|как\\s+называется|текст\\s+песни|аккорды|минусовк)\\b"
    );
    private static final Pattern SING_EN = Pattern.compile(
            "(?iu)(?:^|\\s)(?:sing(?:\\s+me|\\s+us)?(?:\\s+a|\\s+your|\\s+some)?\\s+(?:song|tune|something|please)|can\\s+you\\s+sing|will\\s+you\\s+sing|sing\\s+please)(?:$|[\\s,.!?])"
    );
    private static final Pattern SING_EN_EXCL = Pattern.compile(
            "(?iu)\\b(?:write\\s+(?:a\\s+)?song|lyrics\\s+(?:for|of)|who\\s+sang|what\\s+song)\\b"
    );

    private static final Pattern MD_CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern MD_INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MD_URL = Pattern.compile("https?://\\S+");
    private static final Pattern MD_LINKS = Pattern.compile("\\[([^\\]]+)]\\([^)]+\\)");
    private static final Pattern MD_FORMAT = Pattern.compile("[*_~#>]+");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    public interface PlaybackListener {
        void onStateChanged(String key, boolean playing, int positionMs, int durationMs);
    }

    public interface VoiceSynthesisCallback {
        void onReady(boolean usedCloneServer, File audioFile, String statusNote);
    }

    private static final Object PLAYER_LOCK = new Object();
    private static MediaPlayer activePlayer;
    private static String activeKey = "";
    private static PlaybackListener activeListener;
    private static Handler mainHandler;
    private static Runnable progressTicker;

    private static final Object TTS_LOCK = new Object();
    private static TextToSpeech ttsEngine;
    private static volatile boolean ttsReady = false;
    private static String pendingTtsText;
    private static String pendingTtsLang;
    private static Context pendingTtsCtx;

    private static final AtomicInteger SPEAK_GEN_SEQ = new AtomicInteger(0);
    private static final Object SHERPA_LOCK = new Object();
    private static Object sherpaTtsInstance;
    private static String sherpaLoadedLang = "";
    private static Runnable sherpaIdleUnloadTask;
    private static final Object ASR_LOCK = new Object();
    private static Object sherpaAsrInstance;
    private static Runnable asrIdleUnloadTask;
    private static final ExecutorService ASR_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "koliti-asr-io");
        t.setDaemon(true);
        return t;
    });
    private static volatile String cachedRefSig = "";
    private static volatile ColgramVoiceTimbreDsp.VoiceProfile cachedRefProfile;

    public interface TranscriptionCallback {
        void onResult(String recognizedText);
    }

    public interface SpeakingStateListener {
        void onSpeakingChanged(boolean active, String statusLabel);
    }

    private static volatile SpeakingStateListener speakingStateListener;
    private static volatile boolean ttsPlaybackActive = false;

    public static void setSpeakingStateListener(SpeakingStateListener listener) {
        speakingStateListener = listener;
    }

    public static boolean isSpeakingNow() {
        return ttsPlaybackActive;
    }

    private static void notifySpeakingState(boolean active, String label) {
        ttsPlaybackActive = active;
        SpeakingStateListener l = speakingStateListener;
        if (l != null) {
            handler().post(() -> {
                try { l.onSpeakingChanged(active, label == null ? "" : label); } catch (Throwable ignored) {}
            });
        }
    }

    private static final Object REC_LOCK = new Object();
    private static MediaRecorder activeRecorder;
    private static File activeRecordFile;

    private ColgramKolitiVoice() {}

    // =========================================================================
    // Pure-JVM Testable Detection & Speech Normalization Logic
    // =========================================================================

    /**
     * Returns true when the user is explicitly asking Koliti to sing a song.
     * Avoids false positives on requests like "write song lyrics" or "who sang this song".
     */
    public static boolean isSingSongRequest(String query) {
        if (query == null) return false;
        String q = query.trim();
        if (q.isEmpty() || q.length() > 140) return false;
        String low = q.toLowerCase(Locale.ROOT);

        if (SING_RU_EXPLICIT_EXCL.matcher(low).find() || SING_EN_EXCL.matcher(low).find()) {
            return false;
        }
        if (low.contains("спой") || low.contains("спеть") || low.contains("напой")
                || low.contains("пропой") || low.equals("пой") || low.startsWith("пой ")) {
            // Require either explicit "спой"/"напой"/"пропой" or song noun with "спеть"/"пой"
            if (low.contains("спой") || low.contains("напой") || low.contains("пропой")) {
                return true;
            }
            if (low.contains("песн") || low.contains("песен") || low.contains("трек")) {
                return true;
            }
            return SING_RU.matcher(low).find();
        }
        if (low.contains("sing")) {
            if (low.equals("sing") || low.equals("sing!") || low.equals("sing please")) return true;
            return SING_EN.matcher(low).find();
        }
        return false;
    }

    /**
     * Infers the user's primary communication language ("ru" or "en") from:
     * 1) User's own outgoing chat messages (3x weight),
     * 2) Recent messages across their chats (1x weight),
     * 3) User's Telegram profile name / bio (2x weight),
     * 4) App / device locale as final tiebreaker.
     */
    public static String detectPreferredLanguageCode(
            List<String> userOwnMessages,
            List<String> otherMessages,
            String profileText,
            String appLocale
    ) {
        long cyrScore = 0L;
        long latScore = 0L;

        if (userOwnMessages != null) {
            for (String m : userOwnMessages) {
                long[] counts = countScriptLetters(m);
                cyrScore += counts[0] * 3L;
                latScore += counts[1] * 3L;
            }
        }
        if (otherMessages != null) {
            for (String m : otherMessages) {
                long[] counts = countScriptLetters(m);
                cyrScore += counts[0];
                latScore += counts[1];
            }
        }
        if (profileText != null && !profileText.trim().isEmpty()) {
            long[] counts = countScriptLetters(profileText);
            cyrScore += counts[0] * 2L;
            latScore += counts[1] * 2L;
        }

        if (cyrScore > latScore) {
            return "ru";
        }
        if (latScore > cyrScore && latScore >= 4L) {
            return "en";
        }
        String loc = appLocale == null ? "" : appLocale.toLowerCase(Locale.ROOT);
        if (loc.startsWith("ru") || loc.startsWith("uk") || loc.startsWith("be") || loc.startsWith("kk")) {
            return "ru";
        }
        if (loc.startsWith("en")) {
            return "en";
        }
        return cyrScore > 0L ? "ru" : "en";
    }

    private static long[] countScriptLetters(String text) {
        long cyr = 0L;
        long lat = 0L;
        if (text != null) {
            int limit = Math.min(text.length(), 400);
            for (int i = 0; i < limit; i++) {
                char c = text.charAt(i);
                if ((c >= 'А' && c <= 'я') || c == 'Ё' || c == 'ё' || c == 'І' || c == 'і' || c == 'Ї' || c == 'ї') {
                    cyr++;
                } else if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')) {
                    lat++;
                }
            }
        }
        return new long[]{cyr, lat};
    }

    /**
     * Cleans Markdown/LaTeX/URLs and expands Russian slang & abbreviations so both
     * neural voice-cloning models and offline TTS place stress and pauses naturally.
     */
    public static String prepareSpeechText(String rawText, String langCode) {
        if (rawText == null) return "";
        boolean ru = "ru".equalsIgnoreCase(langCode) || countScriptLetters(rawText)[0] > countScriptLetters(rawText)[1];
        String s = rawText.replace("\r\n", "\n");
        s = MD_CODE_BLOCK.matcher(s).replaceAll(ru ? " код на экране. " : " code shown on screen. ");
        s = MD_LINKS.matcher(s).replaceAll("$1");
        s = MD_URL.matcher(s).replaceAll(ru ? " ссылка " : " link ");
        s = MD_INLINE_CODE.matcher(s).replaceAll("$1");
        s = MD_FORMAT.matcher(s).replaceAll(" ");
        s = s.replace("•", ", ").replace("—", ", ").replace("→", ru ? " равно " : " to ");
        if (ru) {
            // Natural reading of currency & equations for Russian neural TTS
            s = s.replaceAll("\\$\\s*(\\d+(?:[.,]\\d+)?)", "$1 доллара");
            s = s.replaceAll("(\\d+)\\s*=", "$1 равно");
            s = s.replace("=", " равно ");
            s = s.replace("+", " плюс ");
            s = s.replace("×", " умножить на ");
            s = s.replace("÷", " разделить на ");
            // Expand common Russian chat abbreviations for crisp natural prosody
            s = s.replaceAll("(?iu)\\bи\\s+т\\.?\\s*д\\.?\\b", "и так далее");
            s = s.replaceAll("(?iu)\\bи\\s+т\\.?\\s*п\\.?\\b", "и тому подобное");
            s = s.replaceAll("(?iu)\\bт\\.?\\s*д\\.?\\b", "так далее");
            s = s.replaceAll("(?iu)\\bт\\.?\\s*е\\.?\\b", "то есть");
            s = s.replaceAll("(?iu)\\bгс\\b", "голосовое сообщение");
            s = s.replaceAll("(?iu)\\bсоо\\b", "сообщение");
            s = s.replaceAll("(?iu)\\bтг\\b", "Телеграм");
            s = s.replaceAll("(?iu)\\bии\\b", "искусственный интеллект");
            s = s.replaceAll("(?iu)\\bкрч\\b", "короче");
            s = s.replaceAll("(?iu)\\bспс\\b", "спасибо");
            s = s.replaceAll("(?iu)\\bпж\\b", "пожалуйста");
            s = s.replaceAll("(?iu)\\bок\\b", "окей");
            s = s.replaceAll("(?iu)\\bkoliti\\b", "Колити");
            s = s.replaceAll("(?iu)\\bcolgram\\b", "Колграм");
            s = s.replace("%", " процентов");
        } else {
            s = s.replaceAll("\\$\\s*(\\d+(?:[.,]\\d+)?)", "$1 dollars");
            s = s.replace("=", " equals ");
            s = s.replace("+", " plus ");
            s = s.replaceAll("(?iu)\\bkoliti\\b", "Koliti");
            s = s.replaceAll("(?iu)\\bcolgram\\b", "Colgram");
            s = s.replace("%", " percent");
        }
        // Strip surrogate emoji pairs & decorative symbols that cause TTS glitches
        StringBuilder clean = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isSurrogate(c)) continue;
            if (c == '\n') {
                clean.append(". ");
            } else if (Character.isLetterOrDigit(c) || " .,!?:;()-'\"".indexOf(c) >= 0) {
                clean.append(c);
            } else {
                clean.append(' ');
            }
        }
        String out = MULTI_SPACE.matcher(clean.toString()).replaceAll(" ").trim();
        out = out.replaceAll("\\s+([.,!?:;])", "$1");
        out = out.replaceAll("(\\.\\s*){2,}", ". ");
        if (out.length() > 1400) {
            int cut = out.lastIndexOf('.', 1400);
            out = cut > 400 ? out.substring(0, cut + 1) : out.substring(0, 1400);
        }
        return out.trim();
    }

    public static String formatDurationMs(int ms) {
        int sec = Math.max(0, ms / 1000);
        int m = sec / 60;
        int s = sec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }

    // =========================================================================
    // Android Audio Playback Engine (Raw OGG Resources & Audio Files)
    // =========================================================================

    private static Handler handler() {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        return mainHandler;
    }

    public static boolean isPlaying(String key) {
        synchronized (PLAYER_LOCK) {
            return activePlayer != null && activeKey.equals(key) && safeIsPlaying(activePlayer);
        }
    }

    private static boolean safeIsPlaying(MediaPlayer mp) {
        try {
            return mp != null && mp.isPlaying();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void stopPlayback() {
        PlaybackListener prevListener;
        String prevKey;
        synchronized (PLAYER_LOCK) {
            stopTickerLocked();
            prevListener = activeListener;
            prevKey = activeKey;
            activeListener = null;
            activeKey = "";
            if (activePlayer != null) {
                try {
                    if (activePlayer.isPlaying()) activePlayer.stop();
                } catch (Throwable ignored) {}
                try {
                    activePlayer.release();
                } catch (Throwable ignored) {}
                activePlayer = null;
            }
        }
        if (prevListener != null && prevKey != null && !prevKey.isEmpty()) {
            final PlaybackListener l = prevListener;
            final String k = prevKey;
            handler().post(() -> l.onStateChanged(k, false, 0, 0));
        }
    }

    public static void toggleRawPlayback(Context ctx, String key, int rawResId, PlaybackListener listener) {
        if (ctx == null || rawResId == 0) return;
        synchronized (PLAYER_LOCK) {
            if (activePlayer != null && activeKey.equals(key)) {
                try {
                    if (activePlayer.isPlaying()) {
                        activePlayer.pause();
                        stopTickerLocked();
                        int pos = safeCurrentPosition(activePlayer);
                        int dur = safeDuration(activePlayer);
                        activeListener = listener;
                        if (listener != null) {
                            handler().post(() -> listener.onStateChanged(key, false, pos, dur));
                        }
                        return;
                    } else {
                        activeListener = listener;
                        activePlayer.start();
                        startTickerLocked();
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        playRawResource(ctx, key, rawResId, listener);
    }

    public static void playRawResource(Context ctx, String key, int rawResId, PlaybackListener listener) {
        if (ctx == null || rawResId == 0) return;
        stopSpeaking();
        stopPlayback();
        MediaPlayer mp = null;
        try {
            AssetFileDescriptor afd = ctx.getApplicationContext().getResources().openRawResourceFd(rawResId);
            if (afd == null) return;
            try {
                mp = new MediaPlayer();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    mp.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                }
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                try {
                    afd.close();
                } catch (Throwable ignored) {}
            }
            mp.prepare();
            attachAndStartPlayer(mp, key, listener);
            mp = null;
        } catch (Throwable err) {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Throwable ignored) {}
            }
            Log.w(TAG, "playRawResource failed for " + key + ": " + err.getMessage());
            if (listener != null) {
                handler().post(() -> listener.onStateChanged(key, false, 0, 0));
            }
        }
    }

    public static void toggleFilePlayback(String key, String filePath, PlaybackListener listener) {
        if (filePath == null || filePath.trim().isEmpty()) return;
        synchronized (PLAYER_LOCK) {
            if (activePlayer != null && activeKey.equals(key)) {
                try {
                    if (activePlayer.isPlaying()) {
                        activePlayer.pause();
                        stopTickerLocked();
                        int pos = safeCurrentPosition(activePlayer);
                        int dur = safeDuration(activePlayer);
                        activeListener = listener;
                        if (listener != null) {
                            handler().post(() -> listener.onStateChanged(key, false, pos, dur));
                        }
                        return;
                    } else {
                        activeListener = listener;
                        activePlayer.start();
                        startTickerLocked();
                        return;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        playAudioFile(key, filePath, listener);
    }

    public static void playAudioFile(String key, String filePath, PlaybackListener listener) {
        if (filePath == null || !new File(filePath).isFile()) return;
        if (key == null || !key.startsWith("tts_")) {
            stopSpeaking();
        }
        stopPlayback();
        MediaPlayer mp = null;
        try {
            mp = new MediaPlayer();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mp.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            mp.setDataSource(filePath);
            mp.prepare();
            attachAndStartPlayer(mp, key, listener);
            mp = null;
        } catch (Throwable err) {
            if (mp != null) {
                try {
                    mp.release();
                } catch (Throwable ignored) {}
            }
            Log.w(TAG, "playAudioFile failed for " + filePath + ": " + err.getMessage());
            if (listener != null) {
                handler().post(() -> listener.onStateChanged(key, false, 0, 0));
            }
        }
    }

    private static void attachAndStartPlayer(MediaPlayer mp, String key, PlaybackListener listener) {
        int duration = safeDuration(mp);
        mp.setOnCompletionListener(finished -> {
            PlaybackListener cb;
            synchronized (PLAYER_LOCK) {
                if (activePlayer != finished) return;
                stopTickerLocked();
                cb = activeListener;
                activePlayer = null;
                activeKey = "";
                activeListener = null;
            }
            try { finished.release(); } catch (Throwable ignored) {}
            if (cb != null) {
                handler().post(() -> cb.onStateChanged(key, false, 0, duration));
            }
        });
        synchronized (PLAYER_LOCK) {
            activePlayer = mp;
            activeKey = key;
            activeListener = listener;
            mp.start();
            startTickerLocked();
        }
        if (listener != null) {
            handler().post(() -> listener.onStateChanged(key, true, 0, duration));
        }
    }

    private static void startTickerLocked() {
        stopTickerLocked();
        progressTicker = new Runnable() {
            @Override
            public void run() {
                MediaPlayer mp;
                PlaybackListener cb;
                String k;
                synchronized (PLAYER_LOCK) {
                    mp = activePlayer;
                    cb = activeListener;
                    k = activeKey;
                }
                if (mp == null || !safeIsPlaying(mp)) return;
                int pos = safeCurrentPosition(mp);
                int dur = safeDuration(mp);
                if (cb != null) {
                    cb.onStateChanged(k, true, pos, dur);
                }
                handler().postDelayed(this, 60L);
            }
        };
        handler().postDelayed(progressTicker, 60L);
    }

    private static void stopTickerLocked() {
        if (progressTicker != null) {
            handler().removeCallbacks(progressTicker);
            progressTicker = null;
        }
    }

    private static int safeCurrentPosition(MediaPlayer mp) {
        try {
            return mp != null ? Math.max(0, mp.getCurrentPosition()) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int safeDuration(MediaPlayer mp) {
        try {
            return mp != null ? Math.max(0, mp.getDuration()) : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // =========================================================================
    // Dual-Tier Voice-Over & Neural Voice Cloning Engine
    // =========================================================================

    public static void stopSpeaking() {
        SPEAK_GEN_SEQ.incrementAndGet();
        synchronized (PLAYER_LOCK) {
            if (activeKey != null && activeKey.startsWith("tts_")) {
                stopPlayback();
            }
        }
        synchronized (TTS_LOCK) {
            pendingTtsText = null;
            if (ttsEngine != null) {
                try {
                    ttsEngine.stop();
                } catch (Throwable ignored) {}
            }
        }
        notifySpeakingState(false, "");
    }

    public static void stopAll() {
        stopSpeaking();
        stopPlayback();
        stopRecording();
    }

    public static String getVoiceEngineSummary(Context ctx) {
        if (ctx == null) return "Офлайн-нейросеть (Koliti Voice)";
        String ep = ColgramAiConfig.getVoiceCloneEndpoint(ctx);
        String key = ColgramAiConfig.getVoiceCloneApiKey(ctx);
        if (!ep.isEmpty() || !key.isEmpty()) {
            return "Клонирование (сервер)";
        }
        String refPath = ColgramAiConfig.getVoiceReferencePath(ctx);
        if (!refPath.isEmpty() && new File(refPath).isFile()) {
            return "Нейросеть офлайн · тембр вашего ГС";
        }
        return "Нейросеть офлайн · тембр голоса Koliti";
    }

    /**
     * Releases the offline neural TTS ONNX runtime session to free RAM when idle.
     */
    public static void unloadNeuralTtsAsync() {
        IO_EXEC.execute(() -> {
            synchronized (SHERPA_LOCK) {
                releaseSherpaLocked();
            }
        });
    }

    private static void releaseSherpaLocked() {
        if (sherpaTtsInstance != null) {
            try {
                sherpaTtsInstance.getClass().getMethod("release").invoke(sherpaTtsInstance);
            } catch (Throwable ignored) {}
            sherpaTtsInstance = null;
            sherpaLoadedLang = "";
            Log.i(TAG, "Offline neural TTS released (0 MB RAM)");
        }
    }

    private static void scheduleSherpaIdleUnload() {
        handler().post(() -> {
            if (sherpaIdleUnloadTask != null) {
                handler().removeCallbacks(sherpaIdleUnloadTask);
            }
            sherpaIdleUnloadTask = ColgramKolitiVoice::unloadNeuralTtsAsync;
            handler().postDelayed(sherpaIdleUnloadTask, 180_000L);
        });
    }

    /**
     * Pre-warms offline neural TTS + user voice profile in background while Koliti LLM is generating,
     * eliminating cold model-load latency when the answer finishes.
     */
    public static void warmUpNeuralTtsAsync(Context ctx, String preferredLang, int defaultVoiceSampleRawId) {
        if (ctx == null || !ColgramAiConfig.isVoiceOverEnabled(ctx)) return;
        final Context app = ctx.getApplicationContext();
        if (ColgramAiConfig.hasVoiceCloneServer(app)) return;
        IO_EXEC.execute(() -> {
            try {
                File refFile = resolveOrExportVoiceReference(app, defaultVoiceSampleRawId);
                ColgramVoiceTimbreDsp.VoiceProfile prof = getOrAnalyzeReferenceProfile(refFile);
                boolean isRu = !"en".equalsIgnoreCase(preferredLang);
                String voiceKey = selectSherpaVoiceKey(isRu, prof);
                ensureSherpaTtsLoadedLocked(app, voiceKey);
                scheduleSherpaIdleUnload();
            } catch (Throwable ignored) {}
        });
    }

    private static String selectSherpaVoiceKey(boolean isRu, ColgramVoiceTimbreDsp.VoiceProfile refProfile) {
        if (!isRu) return "en_ryan";
        // Match user's natural vocal register: lighter youthful voices (>=145 Hz) start from ru_RU-denis-medium
        // so pitch+formant morph stays crystal-clear & close to the user's real voice; deep baritones use dmitri.
        if (refProfile != null && refProfile.isValid() && refProfile.medianF0Hz < 145f) {
            return "ru_dmitri";
        }
        return "ru_denis";
    }

    /**
     * Splits text into natural sentence chunks so sentence #1 starts speaking in <1s while sentence #2+
     * synthesize seamlessly in the background.
     */
    public static List<String> splitIntoSpeechChunks(String cleanSpeech) {
        List<String> out = new ArrayList<>();
        if (cleanSpeech == null || cleanSpeech.trim().isEmpty()) return out;
        String s = cleanSpeech.trim();
        if (s.length() <= 165) {
            out.add(s);
            return out;
        }
        StringBuilder cur = new StringBuilder();
        int maxFirst = 145;
        int maxNext = 240;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            cur.append(c);
            boolean endPunct = (c == '.' || c == '!' || c == '?');
            int cap = out.isEmpty() ? maxFirst : maxNext;
            if (endPunct && cur.length() >= 28 && (i + 1 >= s.length() || s.charAt(i + 1) == ' ')) {
                String piece = cur.toString().trim();
                if (!piece.isEmpty()) out.add(piece);
                cur.setLength(0);
            } else if (cur.length() >= cap && (c == ',' || c == ';' || c == ':' || c == ' ')) {
                String piece = cur.toString().trim();
                if (!piece.isEmpty()) out.add(piece);
                cur.setLength(0);
            }
        }
        String tail = cur.toString().trim();
        if (!tail.isEmpty()) {
            if (!out.isEmpty() && tail.length() < 18) {
                int last = out.size() - 1;
                out.set(last, out.get(last) + " " + tail);
            } else {
                out.add(tail);
            }
        }
        return out;
    }

    /**
     * Speaks Koliti's reply through a fast streaming 3-tier pipeline:
     * - Tier 1: External zero-shot voice cloning server (XTTS-v2 / F5-TTS / Fish / ElevenLabs), if configured.
     * - Tier 2: Built-in 100% offline Sherpa-ONNX Neural VITS TTS (dual-register RU + EN)
     *           with streaming sentence queue + 10-band LTAS/WSOLA User-Voice Timbre Cloning!
     * - Tier 3: System Android TTS fallback (only if Tier 2 unavailable).
     */
    public static void speakReplyAsync(
            Context ctx,
            String rawText,
            String preferredLang,
            int defaultVoiceSampleRawId,
            VoiceSynthesisCallback callback
    ) {
        if (ctx == null || rawText == null || rawText.trim().isEmpty()) return;
        final Context app = ctx.getApplicationContext();

        // Pick speech language from the ACTUAL reply script first so Russian text always uses RU stress
        // and English text always uses EN pronunciation, with preferredLang as tiebreaker.
        long[] scriptCounts = countScriptLetters(rawText);
        final String lang;
        if (scriptCounts[0] >= 3 && scriptCounts[0] >= scriptCounts[1]) {
            lang = "ru";
        } else if (scriptCounts[1] >= 6 && scriptCounts[1] > scriptCounts[0] * 2L) {
            lang = "en";
        } else if (preferredLang != null && !preferredLang.isEmpty()) {
            lang = preferredLang;
        } else {
            lang = scriptCounts[0] >= scriptCounts[1] ? "ru" : "en";
        }

        final String cleanSpeech = prepareSpeechText(rawText, lang);
        if (cleanSpeech.isEmpty()) return;

        final int mySeq = SPEAK_GEN_SEQ.incrementAndGet();
        stopPlayback();
        notifySpeakingState(true, "Синтез голоса…");

        final String endpoint = ColgramAiConfig.getVoiceCloneEndpoint(app);
        final String apiKey = ColgramAiConfig.getVoiceCloneApiKey(app);
        final String voiceId = ColgramAiConfig.getVoiceCloneVoiceId(app);

        IO_EXEC.execute(() -> {
            if (SPEAK_GEN_SEQ.get() != mySeq) return;

            File refFile = resolveOrExportVoiceReference(app, defaultVoiceSampleRawId);

            // Tier 1: External Zero-Shot Voice Clone server (if user configured one)
            if (!endpoint.isEmpty() || !apiKey.isEmpty()) {
                File synth = synthesizeViaVoiceCloneApi(app, endpoint, apiKey, voiceId, cleanSpeech, lang, refFile);
                if (SPEAK_GEN_SEQ.get() != mySeq) return;
                if (synth != null && synth.isFile() && synth.length() > 128) {
                    playSynthesizedVoiceFile(mySeq, synth, true, "Клон голоса", callback);
                    return;
                }
            }

            // Tier 2: Built-in 100% Offline Neural VITS + Streaming Sentence Queue + User-Voice Timbre DSP
            boolean hasCustomRef = !ColgramAiConfig.getVoiceReferencePath(app).isEmpty()
                    && new File(ColgramAiConfig.getVoiceReferencePath(app)).isFile();
            String label = hasCustomRef ? "Ваш голос (тембр ГС)" : "Ваш голос (нейросеть)";
            List<String> chunks = splitIntoSpeechChunks(cleanSpeech);
            if (!chunks.isEmpty()) {
                File firstWav = synthesizeViaLocalSherpaNeural(app, chunks.get(0), lang, refFile);
                if (SPEAK_GEN_SEQ.get() != mySeq) return;
                if (firstWav != null && firstWav.isFile() && firstWav.length() > 256) {
                    if (chunks.size() == 1) {
                        playSynthesizedVoiceFile(mySeq, firstWav, true, label, callback);
                    } else {
                        streamSentenceQueue(app, mySeq, firstWav, chunks, 1, lang, refFile, label, callback);
                    }
                    return;
                }
            }

            // Tier 3: System Android TTS fallback (only if offline neural assets/JNI unavailable)
            handler().post(() -> {
                if (SPEAK_GEN_SEQ.get() != mySeq) return;
                boolean started = speakViaAndroidTts(app, cleanSpeech, lang);
                if (!started) {
                    notifySpeakingState(false, "");
                }
                if (callback != null) {
                    callback.onReady(false, null, started ? "Системная озвучка" : "Озвучка недоступна на устройстве");
                }
            });
        });
    }

    private static void streamSentenceQueue(
            final Context app,
            final int expectedSeq,
            final File firstWav,
            final List<String> chunks,
            final int nextChunkIdx,
            final String lang,
            final File refFile,
            final String label,
            final VoiceSynthesisCallback callback
    ) {
        final java.util.concurrent.LinkedBlockingQueue<File> readyQueue = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicBoolean producerDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean waitingForNext = new java.util.concurrent.atomic.AtomicBoolean(false);

        final Runnable[] playNextRef = new Runnable[1];
        playNextRef[0] = () -> {
            if (SPEAK_GEN_SEQ.get() != expectedSeq) return;
            File next = readyQueue.poll();
            if (next != null) {
                waitingForNext.set(false);
                String key = "tts_" + next.getName();
                playAudioFile(key, next.getAbsolutePath(), (k, playing, posMs, durMs) -> {
                    if (!playing && SPEAK_GEN_SEQ.get() == expectedSeq) {
                        playNextRef[0].run();
                    }
                });
            } else if (producerDone.get()) {
                notifySpeakingState(false, "");
            } else {
                waitingForNext.set(true);
            }
        };

        // Start playing sentence #1 IMMEDIATELY while IO_EXEC synthesizes sentence #2+ in background!
        handler().post(() -> {
            if (SPEAK_GEN_SEQ.get() != expectedSeq) return;
            notifySpeakingState(true, label);
            if (callback != null) {
                callback.onReady(true, firstWav, label);
            }
            String key = "tts_" + firstWav.getName();
            playAudioFile(key, firstWav.getAbsolutePath(), (k, playing, posMs, durMs) -> {
                if (!playing && SPEAK_GEN_SEQ.get() == expectedSeq) {
                    playNextRef[0].run();
                }
            });
        });

        for (int idx = nextChunkIdx; idx < chunks.size(); idx++) {
            if (SPEAK_GEN_SEQ.get() != expectedSeq) break;
            File wav = synthesizeViaLocalSherpaNeural(app, chunks.get(idx), lang, refFile);
            if (SPEAK_GEN_SEQ.get() != expectedSeq) break;
            if (wav != null && wav.isFile() && wav.length() > 256) {
                readyQueue.offer(wav);
                if (waitingForNext.compareAndSet(true, false)) {
                    handler().post(playNextRef[0]);
                }
            }
        }
        producerDone.set(true);
        if (waitingForNext.compareAndSet(true, false)) {
            handler().post(playNextRef[0]);
        }
    }

    private static void playSynthesizedVoiceFile(
            final int expectedSeq,
            final File wavFile,
            final boolean usedNeural,
            final String label,
            final VoiceSynthesisCallback callback
    ) {
        handler().post(() -> {
            if (SPEAK_GEN_SEQ.get() != expectedSeq) return;
            notifySpeakingState(true, label);
            final String key = "tts_" + wavFile.getName();
            playAudioFile(key, wavFile.getAbsolutePath(), (k, playing, posMs, durMs) -> {
                if (!playing && SPEAK_GEN_SEQ.get() == expectedSeq) {
                    notifySpeakingState(false, "");
                }
            });
            if (callback != null) {
                callback.onReady(usedNeural, wavFile, label);
            }
        });
    }

    private static boolean ensureSherpaTtsLoadedLocked(Context app, String voiceKey) throws Exception {
        synchronized (SHERPA_LOCK) {
            if (sherpaTtsInstance != null && voiceKey.equals(sherpaLoadedLang)) {
                return true;
            }
            File engineDir = ensureSherpaTtsExtracted(app);
            if (engineDir == null) return false;
            String onnxName;
            String tokName;
            if ("en_ryan".equals(voiceKey)) {
                onnxName = "en_US-ryan-medium.onnx";
                tokName = "en_tokens.txt";
            } else if ("ru_denis".equals(voiceKey) && new File(engineDir, "ru_RU-denis-medium.onnx").isFile()) {
                onnxName = "ru_RU-denis-medium.onnx";
                tokName = "ru_denis_tokens.txt";
            } else {
                onnxName = "ru_RU-dmitri-medium.onnx";
                tokName = "ru_tokens.txt";
            }
            File onnxFile = new File(engineDir, onnxName);
            File tokensFile = new File(engineDir, tokName);
            File espeakDir = new File(engineDir, "espeak-ng-data");
            if (!onnxFile.isFile() || !tokensFile.isFile() || !espeakDir.isDirectory()) {
                Log.w(TAG, "Missing extracted Sherpa TTS files for " + voiceKey);
                return false;
            }
            releaseSherpaLocked();
            long t0 = System.currentTimeMillis();
            int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            Class<?> vitsCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig");
            Object vits = vitsCls.getDeclaredConstructor().newInstance();
            vitsCls.getMethod("setModel", String.class).invoke(vits, onnxFile.getAbsolutePath());
            vitsCls.getMethod("setTokens", String.class).invoke(vits, tokensFile.getAbsolutePath());
            vitsCls.getMethod("setDataDir", String.class).invoke(vits, espeakDir.getAbsolutePath());

            Class<?> modelCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsModelConfig");
            Object modelCfg = modelCls.getDeclaredConstructor().newInstance();
            modelCls.getMethod("setVits", vitsCls).invoke(modelCfg, vits);
            modelCls.getMethod("setNumThreads", int.class).invoke(modelCfg, threads);
            modelCls.getMethod("setProvider", String.class).invoke(modelCfg, "cpu");

            Class<?> cfgCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTtsConfig");
            Object cfg = cfgCls.getDeclaredConstructor().newInstance();
            cfgCls.getMethod("setModel", modelCls).invoke(cfg, modelCfg);
            cfgCls.getMethod("setMaxNumSentences", int.class).invoke(cfg, 1);

            Class<?> ttsCls = Class.forName("com.k2fsa.sherpa.onnx.OfflineTts");
            sherpaTtsInstance = ttsCls.getDeclaredConstructor(AssetManager.class, cfgCls).newInstance(null, cfg);
            sherpaLoadedLang = voiceKey;
            Log.i(TAG, "Loaded offline neural TTS (" + voiceKey + ", threads=" + threads + ") in " + (System.currentTimeMillis() - t0) + "ms");
            return true;
        }
    }

    /**
     * Synthesizes natural Russian/English speech 100% offline using bundled Sherpa-ONNX Piper VITS
     * neural models and morphs its vocal pitch/formants/spectral envelope to match the user's
     * recorded voice reference (ГС) via ColgramVoiceTimbreDsp.
     */
    private static File synthesizeViaLocalSherpaNeural(Context app, String text, String lang, File refFile) {
        try {
            // Analyze or reuse cached user vocal profile FIRST so we pick the closest Russian register!
            ColgramVoiceTimbreDsp.VoiceProfile refProfile = getOrAnalyzeReferenceProfile(refFile);
            boolean isRu = !"en".equalsIgnoreCase(lang);
            String voiceKey = selectSherpaVoiceKey(isRu, refProfile);

            float[] rawSamples;
            int sampleRate;
            synchronized (SHERPA_LOCK) {
                if (!ensureSherpaTtsLoadedLocked(app, voiceKey)) {
                    return null;
                }
                float speed = Math.max(0.80f, Math.min(1.45f, ColgramAiConfig.getVoiceSpeechRate(app)));
                long tGen = System.currentTimeMillis();
                Object genAudio = sherpaTtsInstance.getClass()
                        .getMethod("generate", String.class, int.class, float.class)
                        .invoke(sherpaTtsInstance, text, 0, speed);
                if (genAudio == null) return null;
                rawSamples = (float[]) genAudio.getClass().getMethod("getSamples").invoke(genAudio);
                sampleRate = (Integer) genAudio.getClass().getMethod("getSampleRate").invoke(genAudio);
                Log.i(TAG, "Neural TTS (" + voiceKey + ") synthesized " + (rawSamples != null ? rawSamples.length : 0)
                        + " samples @" + sampleRate + "Hz in " + (System.currentTimeMillis() - tGen) + "ms");
            }
            scheduleSherpaIdleUnload();

            if (rawSamples == null || rawSamples.length < 256 || sampleRate < 8000) {
                return null;
            }

            float[] morphed = ColgramVoiceTimbreDsp.matchTimbreToProfile(
                    rawSamples,
                    sampleRate,
                    refProfile,
                    0.86f, // tight natural lock to the user's vocal pitch & formant register
                    0.72f  // 10-band LTAS vocal-tract spectral envelope cloning
            );

            File outDir = new File(app.getFilesDir(), "koliti_tts");
            if (!outDir.exists()) outDir.mkdirs();
            pruneOldFiles(outDir, 16);
            File wavOut = new File(outDir, "tts_" + System.currentTimeMillis() + ".wav");
            ColgramVoiceTimbreDsp.writeWav16Bit(morphed, sampleRate, wavOut);
            Log.i(TAG, "Voice-matched WAV ready: " + wavOut.getName() + " (" + wavOut.length() + " B, voice=" + voiceKey
                    + ", refF0=" + (refProfile != null ? String.format(Locale.US, "%.1fHz", refProfile.medianF0Hz) : "none") + ")");
            return wavOut;
        } catch (Throwable err) {
            Log.w(TAG, "synthesizeViaLocalSherpaNeural failed: " + err.getMessage(), err);
            return null;
        }
    }

    private static File ensureSherpaTtsExtracted(Context app) {
        File dir = new File(app.getFilesDir(), "koliti_tts_engine/v2");
        File marker = new File(dir, ".ready");
        if (marker.isFile()
                && new File(dir, "ru_RU-denis-medium.onnx").isFile()
                && new File(dir, "ru_RU-dmitri-medium.onnx").isFile()
                && new File(dir, "espeak-ng-data").isDirectory()) {
            return dir;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) return null;
            AssetManager am = app.getAssets();
            String[] directAssets = {
                    "ru_RU-denis-medium.onnx",
                    "ru_denis_tokens.txt",
                    "ru_RU-dmitri-medium.onnx",
                    "ru_tokens.txt",
                    "en_US-ryan-medium.onnx",
                    "en_tokens.txt"
            };
            byte[] buf = new byte[32768];
            for (String name : directAssets) {
                File dst = new File(dir, name);
                if (dst.isFile() && dst.length() > 16) continue;
                try (InputStream is = am.open("koliti_tts/" + name);
                     BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst), 32768)) {
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    bos.flush();
                }
            }
            File espeakDir = new File(dir, "espeak-ng-data");
            if (!espeakDir.isDirectory() || espeakDir.list() == null || espeakDir.list().length < 5) {
                try (InputStream raw = am.open("koliti_tts/espeak-ng-data.zip");
                     ZipInputStream zis = new ZipInputStream(new BufferedInputStream(raw, 32768))) {
                    ZipEntry entry;
                    String canonicalRoot = dir.getCanonicalPath() + File.separator;
                    while ((entry = zis.getNextEntry()) != null) {
                        File target = new File(dir, entry.getName());
                        if (!target.getCanonicalPath().startsWith(canonicalRoot)) {
                            zis.closeEntry();
                            continue;
                        }
                        if (entry.isDirectory()) {
                            //noinspection ResultOfMethodCallIgnored
                            target.mkdirs();
                        } else {
                            File p = target.getParentFile();
                            if (p != null && !p.exists()) {
                                //noinspection ResultOfMethodCallIgnored
                                p.mkdirs();
                            }
                            try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(target), 16384)) {
                                int n;
                                while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                                bos.flush();
                            }
                        }
                        zis.closeEntry();
                    }
                }
            }
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write('1');
            }
            return dir;
        } catch (Throwable err) {
            Log.w(TAG, "ensureSherpaTtsExtracted failed: " + err.getMessage());
            return null;
        }
    }

    private static ColgramVoiceTimbreDsp.VoiceProfile getOrAnalyzeReferenceProfile(File refFile) {
        if (refFile == null || !refFile.isFile() || refFile.length() < 256) return null;
        String sig = refFile.getAbsolutePath() + "|" + refFile.length() + "|" + refFile.lastModified();
        if (sig.equals(cachedRefSig) && cachedRefProfile != null) {
            return cachedRefProfile;
        }
        DecodedPcm decoded = decodeAudioFileToMonoFloat(refFile, 8);
        if (decoded == null || decoded.samples == null || decoded.samples.length < 1024) {
            return null;
        }
        ColgramVoiceTimbreDsp.VoiceProfile profile = ColgramVoiceTimbreDsp.analyzeProfile(decoded.samples, decoded.sampleRate);
        if (profile.isValid()) {
            cachedRefSig = sig;
            cachedRefProfile = profile;
            Log.i(TAG, "Analyzed user voice reference: F0=" + String.format(Locale.US, "%.1fHz", profile.medianF0Hz)
                    + " voicedFrames=" + profile.voicedFrames + " file=" + refFile.getName());
            return profile;
        }
        return null;
    }

    private static final class DecodedPcm {
        final float[] samples;
        final int sampleRate;
        DecodedPcm(float[] samples, int sampleRate) {
            this.samples = samples;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * Decodes any Android-supported audio file (.ogg/Opus, .m4a/AAC, .mp3, .wav) to mono float PCM
     * for vocal timbre profiling (capped at `maxSeconds` for instant zero-lag execution).
     */
    private static DecodedPcm decodeAudioFileToMonoFloat(File audioFile, int maxSeconds) {
        if (audioFile == null || !audioFile.isFile()) return null;
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(audioFile.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) return null;
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 48000;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int maxMonoSamples = Math.max(16000, sampleRate * maxSeconds);
            float[] outBuf = new float[maxMonoSamples];
            int outPos = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean sawInputEos = false;
            boolean sawOutputEos = false;
            int guardLoops = 0;

            while (!sawOutputEos && outPos < maxMonoSamples && guardLoops++ < 2500) {
                if (!sawInputEos) {
                    int inIdx = codec.dequeueInputBuffer(8000);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        if (inBuf != null) {
                            int sz = extractor.readSampleData(inBuf, 0);
                            if (sz < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                                sawInputEos = true;
                            } else {
                                long pts = extractor.getSampleTime();
                                codec.queueInputBuffer(inIdx, 0, sz, pts, 0);
                                extractor.advance();
                            }
                        }
                    }
                }
                int outIdx = codec.dequeueOutputBuffer(info, 8000);
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        ByteBuffer ob = codec.getOutputBuffer(outIdx);
                        if (ob != null) {
                            ob.position(info.offset);
                            ob.limit(info.offset + info.size);
                            ByteBuffer slice = ob.slice().order(ByteOrder.LITTLE_ENDIAN);
                            int totalShorts = info.size / 2;
                            int ch = Math.max(1, channels);
                            int frames = totalShorts / ch;
                            for (int f = 0; f < frames && outPos < maxMonoSamples; f++) {
                                float sum = 0f;
                                for (int c = 0; c < ch; c++) {
                                    sum += slice.getShort() / 32768f;
                                }
                                outBuf[outPos++] = sum / ch;
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEos = true;
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat nf = codec.getOutputFormat();
                    if (nf.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (nf.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    }
                }
            }
            if (outPos < 1024) return null;
            return new DecodedPcm(Arrays.copyOf(outBuf, outPos), sampleRate);
        } catch (Throwable err) {
            Log.w(TAG, "decodeAudioFileToMonoFloat failed for " + audioFile.getName() + ": " + err.getMessage());
            return null;
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (extractor != null) {
                try { extractor.release(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean speakViaAndroidTts(Context app, String text, String langCode) {
        stopPlayback();
        synchronized (TTS_LOCK) {
            if (ttsEngine == null) {
                pendingTtsText = text;
                pendingTtsLang = langCode;
                pendingTtsCtx = app;
                ttsReady = false;
                try {
                    ttsEngine = new TextToSpeech(app, status -> {
                        synchronized (TTS_LOCK) {
                            if (status == TextToSpeech.SUCCESS) {
                                ttsReady = true;
                                if (pendingTtsText != null) {
                                    String t = pendingTtsText;
                                    String l = pendingTtsLang;
                                    Context c = pendingTtsCtx;
                                    pendingTtsText = null;
                                    dispatchAndroidTtsLocked(c, t, l);
                                }
                            } else {
                                ttsReady = false;
                                notifySpeakingState(false, "");
                            }
                        }
                    });
                    return true;
                } catch (Throwable err) {
                    ttsReady = false;
                    return false;
                }
            }
            if (!ttsReady) {
                pendingTtsText = text;
                pendingTtsLang = langCode;
                pendingTtsCtx = app;
                return false;
            }
            return dispatchAndroidTtsLocked(app, text, langCode);
        }
    }

    private static boolean dispatchAndroidTtsLocked(Context app, String text, String langCode) {
        if (ttsEngine == null) return false;
        try {
            Locale target = "ru".equalsIgnoreCase(langCode) ? new Locale("ru", "RU") : Locale.US;
            int res = ttsEngine.setLanguage(target);
            if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                int fallbackRes = ttsEngine.setLanguage(Locale.getDefault());
                if (fallbackRes == TextToSpeech.LANG_MISSING_DATA || fallbackRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    return false;
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                try {
                    Set<Voice> voices = ttsEngine.getVoices();
                    if (voices != null) {
                        Voice best = null;
                        int bestScore = -1;
                        String wantLang = target.getLanguage();
                        for (Voice v : voices) {
                            if (v == null || v.getLocale() == null) continue;
                            if (!wantLang.equalsIgnoreCase(v.getLocale().getLanguage())) continue;
                            int score = v.getQuality();
                            if (!v.isNetworkConnectionRequired()) score += 50;
                            if (v.getName() != null && v.getName().toLowerCase(Locale.ROOT).contains("network")) score -= 20;
                            if (score > bestScore) {
                                bestScore = score;
                                best = v;
                            }
                        }
                        if (best != null) {
                            ttsEngine.setVoice(best);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            ttsEngine.setSpeechRate(ColgramAiConfig.getVoiceSpeechRate(app));
            ttsEngine.setPitch(ColgramAiConfig.getVoicePitch(app));
            int speakRc;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                speakRc = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "koliti_" + System.currentTimeMillis());
            } else {
                //noinspection deprecation
                speakRc = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
            return speakRc == TextToSpeech.SUCCESS;
        } catch (Throwable err) {
            Log.w(TAG, "Android TTS dispatch failed: " + err.getMessage());
            return false;
        }
    }

    /**
     * Calls a zero-shot voice cloning backend:
     * - ElevenLabs: https://api.elevenlabs.io/v1/text-to-speech/{voice_id}
     * - Fish Audio: https://api.fish.audio/v1/tts
     * - Self-hosted XTTS-v2 / F5-TTS / Fish-Speech server: multipart POST with (text, language, speaker_wav)
     */
    private static File synthesizeViaVoiceCloneApi(
            Context app,
            String endpoint,
            String apiKey,
            String voiceId,
            String text,
            String lang,
            File refAudioFile
    ) {
        HttpURLConnection conn = null;
        try {
            String urlStr = endpoint;
            boolean isElevenLabs = false;
            boolean isFishAudio = false;
            if (urlStr.isEmpty() && !apiKey.isEmpty()) {
                if (!voiceId.isEmpty() && voiceId.length() >= 18) {
                    urlStr = "https://api.elevenlabs.io/v1/text-to-speech/" + voiceId;
                    isElevenLabs = true;
                } else {
                    urlStr = "https://api.fish.audio/v1/tts";
                    isFishAudio = true;
                }
            } else {
                String low = urlStr.toLowerCase(Locale.US);
                isElevenLabs = low.contains("elevenlabs.io");
                isFishAudio = low.contains("fish.audio");
            }
            if (urlStr.isEmpty()) return null;

            File outDir = new File(app.getFilesDir(), "koliti_tts");
            if (!outDir.exists()) outDir.mkdirs();
            pruneOldFiles(outDir, 15);

            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(28000);
            conn.setDoOutput(true);
            conn.setRequestMethod("POST");

            if (isElevenLabs) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "audio/mpeg");
                if (!apiKey.isEmpty()) conn.setRequestProperty("xi-api-key", apiKey);
                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("model_id", "eleven_multilingual_v2");
                JSONObject vs = new JSONObject();
                vs.put("stability", 0.55);
                vs.put("similarity_boost", 0.85);
                body.put("voice_settings", vs);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    dos.write(payload);
                    dos.flush();
                }
            } else if (isFishAudio) {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "audio/mpeg");
                if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("format", "mp3");
                if (!voiceId.isEmpty()) body.put("reference_id", voiceId);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    dos.write(payload);
                    dos.flush();
                }
            } else if (refAudioFile != null && refAudioFile.isFile()) {
                // Multipart upload for local/remote XTTS-v2, F5-TTS, or Fish-Speech clone server
                String boundary = "----ColgramKolitiVoice" + UUID.randomUUID().toString().replace("-", "");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    writeMultipartField(dos, boundary, "text", text);
                    writeMultipartField(dos, boundary, "language", lang);
                    if (!voiceId.isEmpty()) {
                        writeMultipartField(dos, boundary, "voice_id", voiceId);
                    }
                    writeMultipartFile(dos, boundary, "speaker_wav", refAudioFile);
                    dos.writeBytes("--" + boundary + "--\r\n");
                    dos.flush();
                }
            } else {
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                if (!apiKey.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                JSONObject body = new JSONObject();
                body.put("text", text);
                body.put("language", lang);
                if (!voiceId.isEmpty()) body.put("voice", voiceId);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    dos.write(payload);
                    dos.flush();
                }
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                String ctype = conn.getContentType();
                String ext = (ctype != null && ctype.toLowerCase(Locale.US).contains("wav")) ? ".wav"
                        : ((ctype != null && ctype.toLowerCase(Locale.US).contains("ogg")) ? ".ogg" : ".mp3");
                File out = new File(outDir, "tts_" + System.currentTimeMillis() + ext);
                try (InputStream is = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(out)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                }
                if (out.length() > 128) return out;
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            } else {
                Log.w(TAG, "Voice clone server HTTP " + code);
            }
        } catch (Throwable err) {
            Log.w(TAG, "synthesizeViaVoiceCloneApi error: " + err.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        return null;
    }

    private static void writeMultipartField(DataOutputStream dos, String boundary, String name, String value) throws Exception {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        dos.write(value.getBytes(StandardCharsets.UTF_8));
        dos.writeBytes("\r\n");
    }

    private static void writeMultipartFile(DataOutputStream dos, String boundary, String fieldName, File file) throws Exception {
        dos.writeBytes("--" + boundary + "\r\n");
        dos.writeBytes("Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + file.getName() + "\"\r\n");
        dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = fis.read(buf)) != -1) dos.write(buf, 0, n);
        }
        dos.writeBytes("\r\n");
    }

    public static File resolveOrExportVoiceReference(Context app, int defaultRawId) {
        if (app == null) return null;
        String custom = ColgramAiConfig.getVoiceReferencePath(app);
        if (!custom.isEmpty()) {
            File f = new File(custom);
            if (f.isFile() && f.length() > 256) return f;
        }
        if (defaultRawId == 0) return null;
        File dir = new File(app.getFilesDir(), "koliti_voice_ref");
        if (!dir.exists()) dir.mkdirs();
        File fallback = new File(dir, "default_voice_ref_v3.ogg");
        if (fallback.isFile() && fallback.length() > 256) return fallback;
        try (InputStream is = app.getResources().openRawResource(defaultRawId);
             FileOutputStream fos = new FileOutputStream(fallback)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            if (fallback.length() > 256) return fallback;
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // 100% Offline Neural Speech-to-Text (Sherpa-ONNX Vosk RU Zipformer)
    // =========================================================================

    public static void unloadOfflineAsrAsync() {
        ASR_EXEC.execute(() -> {
            synchronized (ASR_LOCK) {
                if (sherpaAsrInstance != null) {
                    try {
                        sherpaAsrInstance.getClass().getMethod("release").invoke(sherpaAsrInstance);
                    } catch (Throwable ignored) {}
                    sherpaAsrInstance = null;
                    Log.i(TAG, "Offline ASR released (0 MB RAM)");
                }
            }
        });
    }

    private static void scheduleAsrIdleUnload() {
        handler().post(() -> {
            if (asrIdleUnloadTask != null) {
                handler().removeCallbacks(asrIdleUnloadTask);
            }
            asrIdleUnloadTask = ColgramKolitiVoice::unloadOfflineAsrAsync;
            handler().postDelayed(asrIdleUnloadTask, 90_000L);
        });
    }

    private static File ensureSherpaAsrExtracted(Context app) {
        File dir = new File(app.getFilesDir(), "koliti_asr_engine/v1");
        File marker = new File(dir, ".ready");
        if (marker.isFile()
                && new File(dir, "encoder.int8.onnx").isFile()
                && new File(dir, "decoder.onnx").isFile()
                && new File(dir, "joiner.int8.onnx").isFile()
                && new File(dir, "tokens.txt").isFile()) {
            return dir;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) return null;
            AssetManager am = app.getAssets();
            String[] assets = {"encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx", "tokens.txt"};
            byte[] buf = new byte[32768];
            for (String name : assets) {
                File dst = new File(dir, name);
                if (dst.isFile() && dst.length() > 16) continue;
                try (InputStream is = am.open("koliti_asr/" + name);
                     BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(dst), 32768)) {
                    int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                    bos.flush();
                }
            }
            try (FileOutputStream fos = new FileOutputStream(marker)) {
                fos.write('1');
            }
            return dir;
        } catch (Throwable err) {
            Log.w(TAG, "ensureSherpaAsrExtracted failed: " + err.getMessage());
            return null;
        }
    }

    /**
     * Pre-extracts and warms up the offline Vosk Zipformer RU ASR recognizer in background while
     * the user is recording a voice message so transcription finishes in ~150-300ms after release!
     */
    public static void warmUpOfflineAsrAsync(Context ctx) {
        if (ctx == null) return;
        final Context app = ctx.getApplicationContext();
        ASR_EXEC.execute(() -> {
            try {
                File asrDir = ensureSherpaAsrExtracted(app);
                if (asrDir == null) return;
                synchronized (ASR_LOCK) {
                    if (sherpaAsrInstance != null) return;
                    long t0 = System.currentTimeMillis();
                    int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                    Class<?> transCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig");
                    Object trans = transCls.getDeclaredConstructor().newInstance();
                    transCls.getMethod("setEncoder", String.class).invoke(trans, new File(asrDir, "encoder.int8.onnx").getAbsolutePath());
                    transCls.getMethod("setDecoder", String.class).invoke(trans, new File(asrDir, "decoder.onnx").getAbsolutePath());
                    transCls.getMethod("setJoiner", String.class).invoke(trans, new File(asrDir, "joiner.int8.onnx").getAbsolutePath());

                    Class<?> modelCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig");
                    Object modelCfg = modelCls.getDeclaredConstructor().newInstance();
                    modelCls.getMethod("setTransducer", transCls).invoke(modelCfg, trans);
                    modelCls.getMethod("setTokens", String.class).invoke(modelCfg, new File(asrDir, "tokens.txt").getAbsolutePath());
                    modelCls.getMethod("setNumThreads", int.class).invoke(modelCfg, threads);
                    modelCls.getMethod("setProvider", String.class).invoke(modelCfg, "cpu");

                    Class<?> cfgCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig");
                    Object cfg = cfgCls.getDeclaredConstructor().newInstance();
                    cfgCls.getMethod("setModelConfig", modelCls).invoke(cfg, modelCfg);

                    Class<?> recCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer");
                    sherpaAsrInstance = recCls.getDeclaredConstructor(AssetManager.class, cfgCls).newInstance(null, cfg);
                    Log.i(TAG, "Pre-warmed offline RU ASR (Vosk Zipformer) in " + (System.currentTimeMillis() - t0) + "ms");
                }
                scheduleAsrIdleUnload();
            } catch (Throwable ignored) {}
        });
    }

    /**
     * Transcribes a recorded voice note (.m4a/.ogg/.wav) 100% offline using bundled Sherpa-ONNX
     * Vosk Zipformer RU ASR and delivers the recognized Russian text on the main thread.
     */
    public static void transcribeAudioFileAsync(Context ctx, File audioFile, TranscriptionCallback callback) {
        if (callback == null) return;
        if (ctx == null || audioFile == null || !audioFile.isFile()) {
            handler().post(() -> callback.onResult(""));
            return;
        }
        final Context app = ctx.getApplicationContext();
        ASR_EXEC.execute(() -> {
            String transcript = "";
            try {
                DecodedPcm decoded = decodeAudioFileToMonoFloat(audioFile, 45);
                if (decoded != null && decoded.samples != null && decoded.samples.length >= 1024) {
                    float[] pcm16k = ColgramVoiceTimbreDsp.resampleToRate(decoded.samples, decoded.sampleRate, 16000);
                    File asrDir = ensureSherpaAsrExtracted(app);
                    if (asrDir != null) {
                        synchronized (ASR_LOCK) {
                            if (sherpaAsrInstance == null) {
                                long t0 = System.currentTimeMillis();
                                int threads = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
                                Class<?> transCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig");
                                Object trans = transCls.getDeclaredConstructor().newInstance();
                                transCls.getMethod("setEncoder", String.class).invoke(trans, new File(asrDir, "encoder.int8.onnx").getAbsolutePath());
                                transCls.getMethod("setDecoder", String.class).invoke(trans, new File(asrDir, "decoder.onnx").getAbsolutePath());
                                transCls.getMethod("setJoiner", String.class).invoke(trans, new File(asrDir, "joiner.int8.onnx").getAbsolutePath());

                                Class<?> modelCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineModelConfig");
                                Object modelCfg = modelCls.getDeclaredConstructor().newInstance();
                                modelCls.getMethod("setTransducer", transCls).invoke(modelCfg, trans);
                                modelCls.getMethod("setTokens", String.class).invoke(modelCfg, new File(asrDir, "tokens.txt").getAbsolutePath());
                                modelCls.getMethod("setNumThreads", int.class).invoke(modelCfg, threads);
                                modelCls.getMethod("setProvider", String.class).invoke(modelCfg, "cpu");

                                Class<?> cfgCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizerConfig");
                                Object cfg = cfgCls.getDeclaredConstructor().newInstance();
                                cfgCls.getMethod("setModelConfig", modelCls).invoke(cfg, modelCfg);

                                Class<?> recCls = Class.forName("com.k2fsa.sherpa.onnx.OnlineRecognizer");
                                sherpaAsrInstance = recCls.getDeclaredConstructor(AssetManager.class, cfgCls).newInstance(null, cfg);
                                Log.i(TAG, "Loaded offline RU ASR (Vosk Zipformer) in " + (System.currentTimeMillis() - t0) + "ms");
                            }

                            long tDec = System.currentTimeMillis();
                            Class<?> recCls = sherpaAsrInstance.getClass();
                            Object stream = recCls.getMethod("createStream", String.class).invoke(sherpaAsrInstance, "");
                            Class<?> streamCls = stream.getClass();
                            String raw = "";
                            try {
                                streamCls.getMethod("acceptWaveform", float[].class, int.class).invoke(stream, pcm16k, 16000);
                                // Append 0.6s trailing silence to flush final Zipformer frames cleanly
                                streamCls.getMethod("acceptWaveform", float[].class, int.class).invoke(stream, new float[9600], 16000);
                                streamCls.getMethod("inputFinished").invoke(stream);
                                java.lang.reflect.Method isReadyM = recCls.getMethod("isReady", streamCls);
                                java.lang.reflect.Method decodeM = recCls.getMethod("decode", streamCls);
                                int guard = 0;
                                while (Boolean.TRUE.equals(isReadyM.invoke(sherpaAsrInstance, stream)) && guard++ < 5000) {
                                    decodeM.invoke(sherpaAsrInstance, stream);
                                }
                                Object resObj = recCls.getMethod("getResult", streamCls).invoke(sherpaAsrInstance, stream);
                                raw = resObj != null ? (String) resObj.getClass().getMethod("getText").invoke(resObj) : "";
                            } finally {
                                try {
                                    streamCls.getMethod("release").invoke(stream);
                                } catch (Throwable ignored) {}
                            }
                            transcript = raw == null ? "" : raw.trim();
                            Log.i(TAG, "Offline ASR transcribed (" + pcm16k.length + " samples in "
                                    + (System.currentTimeMillis() - tDec) + "ms): \"" + transcript + "\"");
                        }
                        scheduleAsrIdleUnload();
                    }
                }
            } catch (Throwable err) {
                Log.w(TAG, "transcribeAudioFileAsync failed: " + err.getMessage(), err);
            }
            final String outText = transcript;
            handler().post(() -> callback.onResult(outText));
        });
    }

    // =========================================================================
    // Voice Recording & Telegram Voice-Note (ГС) Discovery
    // =========================================================================

    public static boolean startRecording(File outputFile) {
        if (outputFile == null) return false;
        stopPlayback();
        stopSpeaking();
        stopRecording();
        synchronized (REC_LOCK) {
            MediaRecorder rec = null;
            try {
                File parent = outputFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                rec = new MediaRecorder();
                rec.setAudioSource(MediaRecorder.AudioSource.MIC);
                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                rec.setAudioEncodingBitRate(96000);
                rec.setAudioSamplingRate(44100);
                rec.setOutputFile(outputFile.getAbsolutePath());
                rec.prepare();
                rec.start();
                activeRecorder = rec;
                activeRecordFile = outputFile;
                return true;
            } catch (Throwable err) {
                if (rec != null) {
                    try {
                        rec.release();
                    } catch (Throwable ignored) {}
                }
                Log.w(TAG, "startRecording failed: " + err.getMessage());
                activeRecorder = null;
                activeRecordFile = null;
                return false;
            }
        }
    }

    public static File stopRecording() {
        synchronized (REC_LOCK) {
            if (activeRecorder == null) return null;
            File out = activeRecordFile;
            try {
                activeRecorder.stop();
            } catch (Throwable ignored) {}
            try {
                activeRecorder.release();
            } catch (Throwable ignored) {}
            activeRecorder = null;
            activeRecordFile = null;
            if (out != null && out.isFile() && out.length() > 512) {
                return out;
            }
            return null;
        }
    }

    public static boolean isRecording() {
        synchronized (REC_LOCK) {
            return activeRecorder != null;
        }
    }

    /**
     * Scans Colgram / Telegram local audio caches for the user's recent voice notes (.ogg/.m4a/.opus)
     * so they can pick one of their own ГС as a voice-clone reference in one tap.
     */
    public static List<File> findRecentTelegramVoiceNotes(Context ctx, int maxCount) {
        if (ctx == null) return Collections.emptyList();
        List<File> found = new ArrayList<>();
        List<File> dirs = new ArrayList<>();
        File extFiles = ctx.getExternalFilesDir(null);
        if (extFiles != null) {
            dirs.add(new File(extFiles, "Telegram/Telegram Audio"));
            dirs.add(new File(extFiles, "Telegram/Telegram Voice"));
            dirs.add(extFiles);
        }
        File extCache = ctx.getExternalCacheDir();
        if (extCache != null) dirs.add(extCache);
        dirs.add(new File(ctx.getFilesDir(), "koliti_voice_ref"));
        dirs.add(ctx.getCacheDir());

        for (File d : dirs) {
            scanAudioDir(d, found, 0);
        }
        Collections.sort(found, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        if (found.size() > maxCount) {
            return new ArrayList<>(found.subList(0, maxCount));
        }
        return found;
    }

    private static void scanAudioDir(File dir, List<File> out, int depth) {
        if (dir == null || !dir.isDirectory() || depth > 2 || out.size() >= 60) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (f.isDirectory()) {
                scanAudioDir(f, out, depth + 1);
            } else if (f.isFile() && f.length() >= 4096 && f.length() <= 15 * 1024 * 1024) {
                String n = f.getName().toLowerCase(Locale.US);
                if (n.endsWith(".ogg") || n.endsWith(".opus") || n.endsWith(".wav") || n.endsWith(".m4a")) {
                    out.add(f);
                }
            }
        }
    }

    private static void pruneOldFiles(File dir, int maxFiles) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length <= maxFiles) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            int toDelete = files.length - maxFiles;
            for (int i = 0; i < toDelete; i++) {
                //noinspection ResultOfMethodCallIgnored
                files[i].delete();
            }
        } catch (Throwable ignored) {}
    }
}

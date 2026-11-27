package org.colgram.core;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 3-tier local voice/round-video transcription without Telegram Premium.
 *
 * Tier 1: Reflective invocation of ColgramKolitiVoice.transcribeAudioFileAsync (offline Sherpa ASR).
 *         Zero compile-time imports of Koliti classes so --no-koliti builds remain cleanly decoupled.
 * Tier 2: Android 13+ (API 33+) on-device SpeechRecognizer fed via ParcelFileDescriptor PCM pipe.
 * Tier 3: Honest non-blocking HUD notice when neither offline engine is available.
 */
public final class ColgramLocalStt {

    private static final String TAG = "ColgramLocalStt";
    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int MAX_DECODE_SECONDS = 90;

    public interface SttCallback {
        void onResult(boolean ok, String text, String sourceOrReason);
    }

    private static final ExecutorService STT_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "colgram-local-stt");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private ColgramLocalStt() {}

    public static boolean isKolitiOfflineAsrPresent() {
        if (!ColgramBuildFlags.KOLITI_ENABLED) return false;
        try {
            Class.forName("org.colgram.core.ColgramKolitiVoice");
            Class.forName("org.colgram.core.ColgramKolitiVoice$TranscriptionCallback");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void transcribeFileAsync(Context ctx, File audioFile, SttCallback callback) {
        if (callback == null) return;
        if (ctx == null || audioFile == null || !audioFile.isFile() || audioFile.length() <= 0L) {
            deliverMain(() -> callback.onResult(false, "", "Файл аудио ещё не загружен"));
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;

        if (isKolitiOfflineAsrPresent()) {
            try {
                Class<?> voiceCls = Class.forName("org.colgram.core.ColgramKolitiVoice");
                Class<?> cbCls = Class.forName("org.colgram.core.ColgramKolitiVoice$TranscriptionCallback");
                Method m = voiceCls.getMethod("transcribeAudioFileAsync", Context.class, File.class, cbCls);
                Object proxy = Proxy.newProxyInstance(
                        cbCls.getClassLoader(),
                        new Class<?>[]{cbCls},
                        (p, method, args) -> {
                            if ("onResult".equals(method.getName())) {
                                String recognized = (args != null && args.length > 0 && args[0] instanceof String)
                                        ? ((String) args[0]).trim() : "";
                                if (!recognized.isEmpty()) {
                                    deliverMain(() -> callback.onResult(true, recognized, "koliti_offline"));
                                } else {
                                    tryAndroidOnDeviceOrNotify(appCtx, audioFile, callback);
                                }
                            }
                            return null;
                        }
                );
                m.invoke(null, appCtx, audioFile, proxy);
                return;
            } catch (Throwable err) {
                Log.w(TAG, "Reflective Koliti STT failed, falling back: " + err.getMessage());
            }
        }

        tryAndroidOnDeviceOrNotify(appCtx, audioFile, callback);
    }

    private static void tryAndroidOnDeviceOrNotify(Context appCtx, File audioFile, SttCallback callback) {
        if (Build.VERSION.SDK_INT >= 33 && isOnDeviceRecognizerAvailable(appCtx)) {
            STT_EXEC.execute(() -> runApi33OnDeviceStt(appCtx, audioFile, callback));
            return;
        }
        deliverMain(() -> {
            String reason = ColgramBuildFlags.KOLITI_ENABLED
                    ? "Не удалось распознать речь локально"
                    : "Для офлайн-расшифровки без Premium нужна сборка Koliti AI или Android 13+";
            ColgramUiBridge.postNotice(appCtx, "Локальная расшифровка недоступна", reason, true);
            callback.onResult(false, "", reason);
        });
    }

    private static boolean isOnDeviceRecognizerAvailable(Context ctx) {
        if (Build.VERSION.SDK_INT < 33 || ctx == null) return false;
        try {
            return SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void runApi33OnDeviceStt(Context appCtx, File audioFile, SttCallback callback) {
        if (Build.VERSION.SDK_INT < 33) {
            deliverMain(() -> callback.onResult(false, "", "unsupported_sdk"));
            return;
        }
        final byte[] pcm16k = decodeToPcm16kMonoBytes(audioFile, MAX_DECODE_SECONDS);
        if (pcm16k == null || pcm16k.length < 1024) {
            deliverMain(() -> {
                String msg = "Не удалось декодировать аудиодорожку";
                ColgramUiBridge.postNotice(appCtx, "Локальная расшифровка", msg, true);
                callback.onResult(false, "", msg);
            });
            return;
        }

        deliverMain(() -> {
            ParcelFileDescriptor readEnd = null;
            ParcelFileDescriptor writeEnd = null;
            try {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                readEnd = pipe[0];
                writeEnd = pipe[1];
            } catch (Throwable err) {
                callback.onResult(false, "", "pipe_failed");
                return;
            }

            final ParcelFileDescriptor finalRead = readEnd;
            final ParcelFileDescriptor finalWrite = writeEnd;
            final AtomicBoolean finished = new AtomicBoolean(false);
            final SpeechRecognizer recognizer;
            try {
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appCtx);
            } catch (Throwable err) {
                closeQuietly(finalRead);
                closeQuietly(finalWrite);
                String msg = "Системный офлайн-распознаватель недоступен";
                ColgramUiBridge.postNotice(appCtx, "Локальная расшифровка", msg, true);
                callback.onResult(false, "", msg);
                return;
            }

            final Handler handler = new Handler(Looper.getMainLooper());
            final Runnable timeoutTask = () -> {
                if (finished.compareAndSet(false, true)) {
                    try { recognizer.cancel(); } catch (Throwable ignored) {}
                    try { recognizer.destroy(); } catch (Throwable ignored) {}
                    closeQuietly(finalRead);
                    callback.onResult(false, "", "timeout");
                }
            };
            handler.postDelayed(timeoutTask, 45_000L);

            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}

                @Override
                public void onError(int error) {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutTask);
                        try { recognizer.destroy(); } catch (Throwable ignored) {}
                        closeQuietly(finalRead);
                        String msg = "Скачайте офлайн-пакет языка в настройках Google/Android Speech (код " + error + ")";
                        ColgramUiBridge.postNotice(appCtx, "Офлайн STT Android", msg, true);
                        callback.onResult(false, "", msg);
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacks(timeoutTask);
                        try { recognizer.destroy(); } catch (Throwable ignored) {}
                        closeQuietly(finalRead);
                        ArrayList<String> list = results != null
                                ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) : null;
                        String text = (list != null && !list.isEmpty() && list.get(0) != null)
                                ? list.get(0).trim() : "";
                        if (!text.isEmpty()) {
                            callback.onResult(true, text, "android_ondevice");
                        } else {
                            callback.onResult(false, "", "empty");
                        }
                    }
                }
            });

            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag());
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, finalRead);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
            intent.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, TARGET_SAMPLE_RATE);

            STT_EXEC.execute(() -> {
                try (OutputStream os = new ParcelFileDescriptor.AutoCloseOutputStream(finalWrite)) {
                    int off = 0;
                    int chunk = 4096;
                    while (off < pcm16k.length && !finished.get()) {
                        int n = Math.min(chunk, pcm16k.length - off);
                        os.write(pcm16k, off, n);
                        off += n;
                    }
                    os.flush();
                } catch (Throwable ignored) {
                }
            });

            try {
                recognizer.startListening(intent);
            } catch (Throwable err) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacks(timeoutTask);
                    try { recognizer.destroy(); } catch (Throwable ignored) {}
                    closeQuietly(finalRead);
                    callback.onResult(false, "", "start_failed");
                }
            }
        });
    }

    private static byte[] decodeToPcm16kMonoBytes(File audioFile, int maxSec) {
        MediaExtractor extractor = null;
        MediaCodec codec = null;
        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(audioFile.getAbsolutePath());
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = f;
                    break;
                }
            }
            if (audioTrack < 0 || format == null) return null;
            extractor.selectTrack(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int srcRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : TARGET_SAMPLE_RATE;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? Math.max(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)) : 1;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            ByteArrayOutputStream rawMono = new ByteArrayOutputStream(65536);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            int maxMonoSamples = Math.max(TARGET_SAMPLE_RATE, srcRate * maxSec);
            int collectedSamples = 0;

            while (!outputDone && collectedSamples < maxMonoSamples) {
                if (!inputDone) {
                    int inIdx = codec.dequeueInputBuffer(10_000L);
                    if (inIdx >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIdx);
                        int sampleSize = inBuf != null ? extractor.readSampleData(inBuf, 0) : -1;
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIdx = codec.dequeueOutputBuffer(info, 10_000L);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outFmt = codec.getOutputFormat();
                    if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    }
                    if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = Math.max(1, outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                } else if (outIdx >= 0) {
                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] chunk = new byte[info.size];
                        outBuf.get(chunk);
                        ByteBuffer le = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
                        int frameCount = (chunk.length / 2) / channels;
                        for (int f = 0; f < frameCount && collectedSamples < maxMonoSamples; f++) {
                            int sum = 0;
                            for (int c = 0; c < channels; c++) {
                                sum += le.getShort();
                            }
                            short mono = (short) (sum / channels);
                            rawMono.write(mono & 0xFF);
                            rawMono.write((mono >>> 8) & 0xFF);
                            collectedSamples++;
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            byte[] srcBytes = rawMono.toByteArray();
            if (srcRate == TARGET_SAMPLE_RATE || srcBytes.length < 4) {
                return srcBytes;
            }
            return resampleMono16(srcBytes, srcRate, TARGET_SAMPLE_RATE);
        } catch (Throwable err) {
            Log.w(TAG, "decodeToPcm16kMonoBytes failed: " + err.getMessage());
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

    private static byte[] resampleMono16(byte[] srcBytes, int srcRate, int dstRate) {
        int srcCount = srcBytes.length / 2;
        if (srcCount < 2 || srcRate <= 0 || dstRate <= 0) return srcBytes;
        int dstCount = Math.max(1, (int) Math.round(srcCount * ((double) dstRate / srcRate)));
        byte[] out = new byte[dstCount * 2];
        double ratio = (double) (srcCount - 1) / Math.max(1, dstCount - 1);
        for (int i = 0; i < dstCount; i++) {
            double pos = i * ratio;
            int idx0 = (int) pos;
            int idx1 = Math.min(srcCount - 1, idx0 + 1);
            double frac = pos - idx0;
            short s0 = (short) ((srcBytes[idx0 * 2] & 0xFF) | (srcBytes[idx0 * 2 + 1] << 8));
            short s1 = (short) ((srcBytes[idx1 * 2] & 0xFF) | (srcBytes[idx1 * 2 + 1] << 8));
            short interp = (short) Math.round(s0 + (s1 - s0) * frac);
            out[i * 2] = (byte) (interp & 0xFF);
            out[i * 2 + 1] = (byte) ((interp >>> 8) & 0xFF);
        }
        return out;
    }

    private static void deliverMain(Runnable r) {
        try {
            Looper main = Looper.getMainLooper();
            if (main == null || Looper.myLooper() == main) {
                r.run();
            } else {
                new Handler(main).post(r);
            }
        } catch (Throwable ignored) {
            r.run();
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd != null) {
            try { pfd.close(); } catch (Throwable ignored) {}
        }
    }
}

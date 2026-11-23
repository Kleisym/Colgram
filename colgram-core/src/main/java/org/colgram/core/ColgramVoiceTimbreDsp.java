package org.colgram.core;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Pure-Java On-Device Voice Timbre Analyzer & WSOLA Formant/Pitch/Spectral Matcher.
 *
 * Extracts the user's vocal profile (median fundamental pitch F0 + 8-band vocal tract LTAS
 * spectral envelope) from their recorded voice note (ГС) and morphs neural TTS PCM output
 * toward the user's vocal register and resonance while preserving natural Russian stress
 * and exact speech tempo (zero phase-vocoder metallic buzz).
 */
public final class ColgramVoiceTimbreDsp {

    public static final int BAND_COUNT = 10;
    private static final float[] BAND_CENTERS_HZ = {
            115f, 195f, 330f, 560f, 940f, 1520f, 2380f, 3550f, 5250f, 7600f
    };
    private static final float[] BAND_Q = {
            1.1f, 1.2f, 1.3f, 1.3f, 1.4f, 1.4f, 1.5f, 1.5f, 1.4f, 1.2f
    };

    public static final class VoiceProfile {
        public final float medianF0Hz;
        public final float[] bandDb;
        public final int voicedFrames;

        public VoiceProfile(float medianF0Hz, float[] bandDb, int voicedFrames) {
            this.medianF0Hz = medianF0Hz;
            this.bandDb = bandDb != null && bandDb.length == BAND_COUNT
                    ? Arrays.copyOf(bandDb, BAND_COUNT)
                    : new float[BAND_COUNT];
            this.voicedFrames = voicedFrames;
        }

        public boolean isValid() {
            return voicedFrames >= 4 && medianF0Hz >= 75f && medianF0Hz <= 380f;
        }
    }

    private ColgramVoiceTimbreDsp() {}

    /**
     * Analyzes mono float PCM [-1, 1] and extracts median F0 and normalized 8-band spectral shape.
     */
    public static VoiceProfile analyzeProfile(float[] pcm, int sampleRate) {
        if (pcm == null || pcm.length < sampleRate / 5 || sampleRate < 8000) {
            return new VoiceProfile(0f, new float[BAND_COUNT], 0);
        }

        int frameLen = Math.max(256, (sampleRate * 32) / 1000); // 32ms window
        int hop = frameLen / 2;
        int minLag = Math.max(20, sampleRate / 380);
        int maxLag = Math.min(frameLen - 8, sampleRate / 75);
        if (maxLag <= minLag) {
            return new VoiceProfile(0f, new float[BAND_COUNT], 0);
        }

        // Global active energy reference
        double sumSq = 0.0;
        for (float v : pcm) sumSq += (double) v * v;
        double globalRms = Math.sqrt(sumSq / pcm.length);
        double energyFloor = Math.max(0.012, globalRms * 0.35);

        int maxFrames = Math.max(1, (pcm.length - frameLen) / hop + 2);
        float[] f0List = new float[maxFrames];
        int f0Count = 0;

        double[] bandEnergySum = new double[BAND_COUNT];
        int voicedFrames = 0;

        float[] win = new float[frameLen];
        for (int i = 0; i < frameLen; i++) {
            win[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (frameLen - 1)));
        }

        // Precompute Goertzel coefficients for the 8 vocal-tract bands
        double[] gCoeff = new double[BAND_COUNT];
        for (int b = 0; b < BAND_COUNT; b++) {
            double freq = Math.min(BAND_CENTERS_HZ[b], sampleRate * 0.46);
            double omega = 2.0 * Math.PI * freq / sampleRate;
            gCoeff[b] = 2.0 * Math.cos(omega);
        }

        float[] buf = new float[frameLen];
        for (int pos = 0; pos + frameLen <= pcm.length; pos += hop) {
            double e = 0.0;
            double mean = 0.0;
            for (int i = 0; i < frameLen; i++) mean += pcm[pos + i];
            mean /= frameLen;
            for (int i = 0; i < frameLen; i++) {
                float s = (float) (pcm[pos + i] - mean);
                buf[i] = s * win[i];
                e += (double) s * s;
            }
            double rms = Math.sqrt(e / frameLen);
            if (rms < energyFloor) continue;

            // Normalized autocorrelation for pitch F0
            double r0 = 0.0;
            for (int i = 0; i < frameLen; i++) r0 += (double) buf[i] * buf[i];
            if (r0 <= 1e-9) continue;

            int bestLag = -1;
            double bestCorr = 0.42; // require clear harmonic voicing
            for (int lag = minLag; lag <= maxLag; lag++) {
                double r = 0.0;
                int lim = frameLen - lag;
                for (int i = 0; i < lim; i++) {
                    r += (double) buf[i] * buf[i + lag];
                }
                double norm = r / r0;
                if (norm > bestCorr) {
                    bestCorr = norm;
                    bestLag = lag;
                }
            }
            if (bestLag > 0) {
                if (f0Count < f0List.length) {
                    f0List[f0Count++] = (float) sampleRate / (float) bestLag;
                }
                voicedFrames++;

                // Accumulate 8-band Goertzel spectral envelope over voiced frames
                for (int b = 0; b < BAND_COUNT; b++) {
                    double coeff = gCoeff[b];
                    double s1 = 0.0, s2 = 0.0;
                    for (int i = 0; i < frameLen; i++) {
                        double s0 = buf[i] + coeff * s1 - s2;
                        s2 = s1;
                        s1 = s0;
                    }
                    double power = s1 * s1 + s2 * s2 - coeff * s1 * s2;
                    bandEnergySum[b] += Math.max(1e-12, power) / (r0 + 1e-9);
                }
            }
        }

        if (f0Count < 4) {
            return new VoiceProfile(0f, new float[BAND_COUNT], voicedFrames);
        }

        Arrays.sort(f0List, 0, f0Count);
        float medianF0 = f0List[f0Count / 2];

        float[] bandDb = new float[BAND_COUNT];
        double meanDb = 0.0;
        for (int b = 0; b < BAND_COUNT; b++) {
            double avg = bandEnergySum[b] / voicedFrames;
            bandDb[b] = (float) (10.0 * Math.log10(avg + 1e-12));
            meanDb += bandDb[b];
        }
        meanDb /= BAND_COUNT;
        for (int b = 0; b < BAND_COUNT; b++) {
            bandDb[b] -= (float) meanDb; // relative spectral tilt/envelope independent of volume
        }
        return new VoiceProfile(medianF0, bandDb, voicedFrames);
    }

    /**
     * Morphs synthesized neural TTS audio toward the user's vocal profile:
     * 1) Smooth pitch + vocal-tract formant shift via cubic resample + WSOLA tempo restoration.
     * 2) 8-band LTAS spectral envelope transfer via cascaded biquad peaking filters.
     * 3) Studio loudness normalization + soft tanh true-peak limiting.
     */
    public static float[] matchTimbreToProfile(
            float[] synthPcm,
            int sampleRate,
            VoiceProfile targetProfile,
            float pitchStrength,
            float eqStrength
    ) {
        if (synthPcm == null || synthPcm.length < 256) return synthPcm;
        float[] work = Arrays.copyOf(synthPcm, synthPcm.length);

        if (targetProfile != null && targetProfile.isValid()) {
            VoiceProfile srcProfile = analyzeProfile(work, sampleRate);
            if (srcProfile.isValid()) {
                // Psychoacoustic pitch+formant ratio: tightly matches the user's vocal register & formants
                float rawRatio = targetProfile.medianF0Hz / srcProfile.medianF0Hz;
                float clampedRaw = clamp(rawRatio, 0.68f, 2.05f);
                float blendExp = clamp(pitchStrength, 0.0f, 1.0f);
                float pitchFactor = clamp((float) Math.pow(clampedRaw, blendExp), 0.76f, 1.56f);

                if (Math.abs(pitchFactor - 1.0f) >= 0.02f) {
                    work = pitchFormantShiftWsola(work, sampleRate, pitchFactor);
                }

                // Re-analyze spectral envelope after pitch shift and clone 10-band vocal tract formants
                VoiceProfile postProfile = analyzeProfile(work, sampleRate);
                if (postProfile.isValid() && eqStrength > 0.01f) {
                    float eqGainScale = clamp(eqStrength, 0f, 1f);
                    for (int b = 0; b < BAND_COUNT; b++) {
                        float freq = BAND_CENTERS_HZ[b];
                        if (freq >= sampleRate * 0.44f) continue;
                        float diffDb = (targetProfile.bandDb[b] - postProfile.bandDb[b]) * eqGainScale;
                        diffDb = clamp(diffDb, -6.5f, 6.5f);
                        if (Math.abs(diffDb) >= 0.40f) {
                            applyBiquadPeakingInPlace(work, sampleRate, freq, diffDb, BAND_Q[b]);
                        }
                    }
                }
            }
        }

        // Clean high-pass rumble guard (75 Hz) + broadcast RMS loudness + soft limiter
        applyHighPassInPlace(work, sampleRate, 75f);
        normalizeAndLimitInPlace(work, 0.19f, 0.90f);
        return work;
    }

    /**
     * Shifts pitch AND vocal-tract formants cleanly by `pitchFactor` (>1 higher/lighter, <1 deeper/warmer)
     * while preserving exact original speech duration via WSOLA time-scale modification.
     */
    public static float[] pitchFormantShiftWsola(float[] pcm, int sampleRate, float pitchFactor) {
        if (pcm == null || pcm.length < 512 || Math.abs(pitchFactor - 1.0f) < 0.01f) {
            return pcm;
        }
        // Step 1: Time-stretch by `pitchFactor` using WSOLA (so length becomes len * pitchFactor)
        float[] stretched = wsolaTimeStretch(pcm, sampleRate, pitchFactor);
        // Step 2: Resample back from `stretched.length` -> `pcm.length` (raises pitch/formants by `pitchFactor`)
        return resampleLinear(stretched, pcm.length);
    }

    /**
     * Waveform Similarity Overlap-Add (WSOLA) time-scale modification.
     * scale > 1.0 makes audio longer (slower); scale < 1.0 makes audio shorter (faster).
     * Pure time-domain pitch-period alignment -> zero metallic phase-vocoder artifacts!
     */
    public static float[] wsolaTimeStretch(float[] in, int sampleRate, float scale) {
        if (in == null || in.length < 512 || Math.abs(scale - 1.0f) < 0.01f) {
            return in;
        }
        int winLen = Math.max(256, (sampleRate * 26) / 1000); // 26ms Hann window
        if ((winLen & 1) != 0) winLen++;
        int synthHop = winLen / 2; // 50% overlap-add (constant unity sum for Hann!)
        int searchMax = Math.max(32, (sampleRate * 6) / 1000); // +/-6ms correlation search

        int outLen = Math.max(winLen, Math.round(in.length * scale));
        float[] out = new float[outLen + winLen];

        float[] hann = new float[winLen];
        for (int i = 0; i < winLen; i++) {
            hann[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / winLen));
        }

        // Copy initial frame directly
        int firstCopy = Math.min(winLen, in.length);
        for (int i = 0; i < firstCopy; i++) {
            out[i] = in[i] * hann[i];
        }

        int prevAnalysisPos = 0;
        int outPos = synthHop;
        while (outPos + winLen <= outLen) {
            int idealInPos = Math.round(outPos / scale);
            if (idealInPos + winLen >= in.length) break;

            // Natural continuation reference from previous analysis frame
            int refStart = prevAnalysisPos + synthHop;
            int bestPos = idealInPos;
            if (refStart + synthHop <= in.length) {
                int lo = Math.max(0, idealInPos - searchMax);
                int hi = Math.min(in.length - winLen, idealInPos + searchMax);
                double bestScore = Double.NEGATIVE_INFINITY;
                // Subsample by 2 for 2x faster cross-correlation with identical phase lock
                for (int cand = lo; cand <= hi; cand += 2) {
                    double dot = 0.0;
                    double normA = 1e-9;
                    double normB = 1e-9;
                    for (int k = 0; k < synthHop; k += 2) {
                        double a = in[refStart + k];
                        double b = in[cand + k];
                        dot += a * b;
                        normA += a * a;
                        normB += b * b;
                    }
                    double corr = dot / Math.sqrt(normA * normB);
                    if (corr > bestScore) {
                        bestScore = corr;
                        bestPos = cand;
                    }
                }
            }

            for (int i = 0; i < winLen; i++) {
                out[outPos + i] += in[bestPos + i] * hann[i];
            }
            prevAnalysisPos = bestPos;
            outPos += synthHop;
        }

        return Arrays.copyOf(out, outLen);
    }

    /**
     * Resamples mono float PCM from srcSampleRate to dstSampleRate (e.g. 44100/48000 -> 16000 for offline ASR).
     */
    public static float[] resampleToRate(float[] in, int srcSampleRate, int dstSampleRate) {
        if (in == null || in.length < 2 || srcSampleRate <= 0 || dstSampleRate <= 0 || srcSampleRate == dstSampleRate) {
            return in;
        }
        int targetLen = Math.max(1, (int) Math.round((double) in.length * dstSampleRate / srcSampleRate));
        return resampleLinear(in, targetLen);
    }

    private static float[] resampleLinear(float[] in, int targetLen) {
        if (in == null || in.length < 2 || targetLen <= 0) return in;
        float[] out = new float[targetLen];
        double step = (double) (in.length - 1) / (double) Math.max(1, targetLen - 1);
        for (int i = 0; i < targetLen; i++) {
            double idx = i * step;
            int i0 = (int) idx;
            int i1 = Math.min(in.length - 1, i0 + 1);
            float frac = (float) (idx - i0);
            out[i] = in[i0] + (in[i1] - in[i0]) * frac;
        }
        return out;
    }

    private static void applyBiquadPeakingInPlace(float[] data, int sr, float f0, float gainDb, float q) {
        double A = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2.0 * Math.PI * f0 / sr;
        double cosW = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * q);
        double b0 = 1.0 + alpha * A;
        double b1 = -2.0 * cosW;
        double b2 = 1.0 - alpha * A;
        double a0 = 1.0 + alpha / A;
        double a1 = -2.0 * cosW;
        double a2 = 1.0 - alpha / A;
        applyBiquadInPlace(data, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static void applyHighPassInPlace(float[] data, int sr, float cutoffHz) {
        double w0 = 2.0 * Math.PI * cutoffHz / sr;
        double cosW = Math.cos(w0);
        double alpha = Math.sin(w0) / (2.0 * 0.7071);
        double b0 = (1.0 + cosW) * 0.5;
        double b1 = -(1.0 + cosW);
        double b2 = (1.0 + cosW) * 0.5;
        double a0 = 1.0 + alpha;
        double a1 = -2.0 * cosW;
        double a2 = 1.0 - alpha;
        applyBiquadInPlace(data, b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
    }

    private static void applyBiquadInPlace(float[] x, double b0, double b1, double b2, double a1, double a2) {
        double z1 = 0.0, z2 = 0.0;
        for (int i = 0; i < x.length; i++) {
            double in = x[i];
            double out = b0 * in + z1;
            z1 = b1 * in - a1 * out + z2;
            z2 = b2 * in - a2 * out;
            x[i] = (float) out;
        }
    }

    private static void normalizeAndLimitInPlace(float[] x, float targetRms, float ceiling) {
        double activeSq = 0.0;
        int activeCount = 0;
        for (float v : x) {
            if (Math.abs(v) > 0.015f) {
                activeSq += (double) v * v;
                activeCount++;
            }
        }
        if (activeCount < 32) {
            activeSq = 0.0;
            for (float v : x) activeSq += (double) v * v;
            activeCount = x.length;
        }
        double rms = Math.sqrt(activeSq / Math.max(1, activeCount));
        float gain = rms > 1e-4 ? clamp((float) (targetRms / rms), 0.35f, 3.2f) : 1.0f;
        for (int i = 0; i < x.length; i++) {
            double v = (x[i] * gain) / ceiling;
            x[i] = (float) (ceiling * Math.tanh(v));
        }
        // Subtle 12ms fade-in/out to prevent clicks
        int fade = Math.min(240, x.length / 10);
        for (int i = 0; i < fade; i++) {
            float g = (float) i / (float) Math.max(1, fade);
            x[i] *= g;
            x[x.length - 1 - i] *= g;
        }
    }

    /**
     * Writes mono 16-bit PCM WAV file playable universally by Android MediaPlayer.
     */
    public static void writeWav16Bit(float[] pcm, int sampleRate, File outFile) throws IOException {
        if (pcm == null || pcm.length == 0 || outFile == null) {
            throw new IOException("Empty PCM audio");
        }
        File parent = outFile.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        int numSamples = pcm.length;
        int dataBytes = numSamples * 2;
        int byteRate = sampleRate * 2;

        try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(outFile), 16384)) {
            bos.write(new byte[]{'R', 'I', 'F', 'F'});
            writeIntLE(bos, 36 + dataBytes);
            bos.write(new byte[]{'W', 'A', 'V', 'E'});
            bos.write(new byte[]{'f', 'm', 't', ' '});
            writeIntLE(bos, 16);
            writeShortLE(bos, 1); // PCM
            writeShortLE(bos, 1); // mono
            writeIntLE(bos, sampleRate);
            writeIntLE(bos, byteRate);
            writeShortLE(bos, 2); // block align
            writeShortLE(bos, 16); // bits per sample
            bos.write(new byte[]{'d', 'a', 't', 'a'});
            writeIntLE(bos, dataBytes);

            for (int i = 0; i < numSamples; i++) {
                float clamped = clamp(pcm[i], -1.0f, 1.0f);
                int s = Math.round(clamped * 32767f);
                bos.write(s & 0xFF);
                bos.write((s >> 8) & 0xFF);
            }
            bos.flush();
        }
    }

    private static void writeIntLE(BufferedOutputStream os, int v) throws IOException {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
        os.write((v >> 16) & 0xFF);
        os.write((v >> 24) & 0xFF);
    }

    private static void writeShortLE(BufferedOutputStream os, int v) throws IOException {
        os.write(v & 0xFF);
        os.write((v >> 8) & 0xFF);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

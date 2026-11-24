"""
Colgram 12.10.6 — Studio 48kHz Stereo Soundtrack & Frame-Accurate SFX Generator
Generates public/colgram-soundtrack.wav (30.00s exact, 48kHz, 16-bit stereo PCM)
featuring:
  - 112 BPM (14 bars exact = 30.000s) warm Apple-keynote chill-electronic Rhodes groove
  - Algorithmic stereo studio reverb & gentle bus compression/mastering (-13 LUFS, peak < -1.2 dBFS)
  - Frame-accurate SFX synced to every scene cut, UI animation, and the 21.0s–25.5s Cola Pour
    (crystal glass set-down, resonant ice clinks, Helmholtz pitch-rising liquid pour, fizz sizzle)
"""

from __future__ import annotations

import math
import os
import wave
from pathlib import Path
import numpy as np
from scipy import signal

SR = 48000
DURATION_S = 30.0
N_SAMPLES = int(SR * DURATION_S)  # 1,440,000 samples exact
BPM = 112.0
BEAT_S = 60.0 / BPM              # 0.5357142857s
BAR_S = BEAT_S * 4.0             # 2.1428571428s (14 bars = 30.000000s exact!)

RNG = np.random.default_rng(20261124)


def midi_to_hz(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))


def add_stereo(buf: np.ndarray, sig: np.ndarray, start_s: float, pan: float = 0.0, gain: float = 1.0) -> None:
    """Mix mono or stereo signal into buf (shape [N_SAMPLES, 2]) at exact timestamp."""
    idx = int(round(start_s * SR))
    if idx >= N_SAMPLES:
        return
    if sig.ndim == 1:
        # Constant-power pan law (-1 left .. +1 right)
        angle = (pan + 1.0) * (math.pi / 4.0)
        gl = math.cos(angle) * gain
        gr = math.sin(angle) * gain
        start_src = 0
        if idx < 0:
            start_src = -idx
            idx = 0
        end = min(N_SAMPLES, idx + len(sig) - start_src)
        n = end - idx
        if n <= 0:
            return
        seg = sig[start_src:start_src + n]
        buf[idx:end, 0] += seg * gl
        buf[idx:end, 1] += seg * gr
    else:
        start_src = 0
        if idx < 0:
            start_src = -idx
            idx = 0
        end = min(N_SAMPLES, idx + sig.shape[0] - start_src)
        n = end - idx
        if n <= 0:
            return
        buf[idx:end, 0] += sig[start_src:start_src + n, 0] * gain
        buf[idx:end, 1] += sig[start_src:start_src + n, 1] * gain


def butter_filter(x: np.ndarray, cutoff, btype: str = "low", order: int = 2) -> np.ndarray:
    nyq = SR * 0.5
    if isinstance(cutoff, (tuple, list)):
        wn = [max(10.0, c) / nyq for c in cutoff]
    else:
        wn = min(0.98, max(10.0, cutoff) / nyq)
    sos = signal.butter(order, wn, btype=btype, output="sos")
    return signal.sosfilt(sos, x)


def make_studio_reverb_ir(rt60: float = 1.55) -> tuple[np.ndarray, np.ndarray]:
    """Generate a lush, smooth velvet stereo studio room impulse response."""
    n = int(SR * rt60)
    t = np.arange(n, dtype=np.float64) / SR
    decay = np.exp(-6.9 * t / rt60)
    ir_l = RNG.standard_normal(n) * decay
    ir_r = RNG.standard_normal(n) * decay
    # Smooth pre-delay & warm high-frequency air damping
    pre = int(0.018 * SR)
    ir_l[:pre] = 0.0
    ir_r[:pre] = 0.0
    ir_l = butter_filter(ir_l, [120.0, 5200.0], btype="band", order=2)
    ir_r = butter_filter(ir_r, [120.0, 4900.0], btype="band", order=2)
    # Normalize IR energy
    norm = np.sqrt(np.mean(ir_l ** 2 + ir_r ** 2)) + 1e-12
    ir_l /= norm * 16.0
    ir_r /= norm * 16.0
    return ir_l, ir_r


def apply_stereo_reverb(buf: np.ndarray, ir_l: np.ndarray, ir_r: np.ndarray, wet: float = 0.24) -> np.ndarray:
    wet_l = signal.fftconvolve(buf[:, 0], ir_l, mode="full")[:N_SAMPLES]
    wet_r = signal.fftconvolve(buf[:, 1], ir_r, mode="full")[:N_SAMPLES]
    out = buf.copy()
    out[:, 0] += wet_l * wet
    out[:, 1] += wet_r * wet
    return out


# =============================================================================
# 1. MUSICAL INSTRUMENTS (Warm Apple-Commercial Rhodes / Chill-Electronic)
# =============================================================================

def synth_rhodes_note(freq: float, dur_s: float, velocity: float = 0.8) -> np.ndarray:
    """Warm FM electric piano (Fender Rhodes style: mellow body + glassy bell tine)."""
    n = int(SR * (dur_s + 0.6))
    t = np.arange(n, dtype=np.float64) / SR
    # Smooth attack to avoid clicks
    attack = np.minimum(1.0, t / 0.008)
    body_env = attack * np.exp(-t * 1.45)
    tine_env = attack * np.exp(-t * 11.5)
    # Release fade after note duration
    rel_mask = t > dur_s
    rel_env = np.ones_like(t)
    rel_env[rel_mask] = np.exp(-(t[rel_mask] - dur_s) * 9.0)
    env = body_env * rel_env

    # Classic Rhodes FM tine overtone (ratio 7 or 14 lightly modulated) + warm 2nd harmonic
    mod = 0.32 * velocity * tine_env * np.sin(2.0 * math.pi * freq * 7.0 * t)
    sig = (
        0.74 * np.sin(2.0 * math.pi * freq * t + mod)
        + 0.19 * np.sin(2.0 * math.pi * freq * 2.0 * t) * np.exp(-t * 2.6)
        + 0.08 * np.sin(2.0 * math.pi * freq * 3.0 * t) * np.exp(-t * 5.2)
    )
    # Subtle warm stereo tremolo feel handled at chord level
    return sig * env * velocity


def synth_pad_chord(midis: list[int], dur_s: float) -> np.ndarray:
    """Silky warm analog stereo pad swelling gently behind the chords."""
    n = int(SR * (dur_s + 0.8))
    t = np.arange(n, dtype=np.float64) / SR
    fade_in = np.minimum(1.0, t / 0.45) ** 1.6
    fade_out = np.ones_like(t)
    rel = t > dur_s
    fade_out[rel] = np.exp(-(t[rel] - dur_s) * 3.5)
    env = fade_in * fade_out

    out = np.zeros((n, 2), dtype=np.float64)
    for idx, m in enumerate(midis):
        f = midi_to_hz(m)
        # Gentle detuned pair for lush stereo width
        ph1 = RNG.uniform(0, 2 * math.pi)
        ph2 = RNG.uniform(0, 2 * math.pi)
        v_l = (
            0.65 * np.sin(2 * math.pi * (f * 0.9988) * t + ph1)
            + 0.25 * np.sin(2 * math.pi * (f * 2.0 * 0.999) * t + ph1)
        )
        v_r = (
            0.65 * np.sin(2 * math.pi * (f * 1.0012) * t + ph2)
            + 0.25 * np.sin(2 * math.pi * (f * 2.0 * 1.001) * t + ph2)
        )
        out[:, 0] += v_l
        out[:, 1] += v_r
    out /= max(1, len(midis))
    out[:, 0] = butter_filter(out[:, 0] * env, 1850.0, "low", 2)
    out[:, 1] = butter_filter(out[:, 1] * env, 1850.0, "low", 2)
    return out


def synth_kick() -> np.ndarray:
    """Warm, round modern Apple-commercial sub-kick (tight, zero harsh click)."""
    n = int(SR * 0.28)
    t = np.arange(n, dtype=np.float64) / SR
    # Smooth exponential pitch sweep 145 Hz -> 46 Hz
    freq = 46.0 + 99.0 * np.exp(-t * 36.0)
    phase = 2.0 * math.pi * np.cumsum(freq) / SR
    env = np.minimum(1.0, t / 0.0025) * np.exp(-t * 12.5)
    body = np.sin(phase) * env
    # Tiny warm beater thump
    thump = butter_filter(RNG.standard_normal(n) * np.exp(-t * 95.0), 750.0, "low", 2) * 0.14
    return np.tanh((body + thump) * 1.35) * 0.88


def synth_rim_snap() -> np.ndarray:
    """Crisp organic wooden rimshot + delicate airy snap."""
    n = int(SR * 0.14)
    t = np.arange(n, dtype=np.float64) / SR
    tone = (
        np.sin(2 * math.pi * 410.0 * t) * np.exp(-t * 62.0) * 0.55
        + np.sin(2 * math.pi * 1680.0 * t) * np.exp(-t * 115.0) * 0.35
    )
    snap = butter_filter(RNG.standard_normal(n), [1600.0, 7500.0], "band", 2) * np.exp(-t * 42.0) * 0.42
    return (tone + snap) * 0.72


def synth_hat(open_hat: bool = False) -> np.ndarray:
    """Silky crisp closed or lightly open hi-hat."""
    dur = 0.16 if open_hat else 0.048
    n = int(SR * dur)
    t = np.arange(n, dtype=np.float64) / SR
    decay = 24.0 if open_hat else 92.0
    env = np.minimum(1.0, t / 0.0015) * np.exp(-t * decay)
    noise = butter_filter(RNG.standard_normal(n), [6800.0, 15500.0], "band", 2)
    return noise * env * (0.34 if open_hat else 0.24)


def synth_sub_bass(midi: int, dur_s: float) -> np.ndarray:
    """Smooth round sub-bass with gentle warm 2nd harmonic."""
    n = int(SR * (dur_s + 0.08))
    t = np.arange(n, dtype=np.float64) / SR
    f = midi_to_hz(midi)
    env = np.minimum(1.0, t / 0.014) * np.exp(-t * 1.6)
    rel = t > dur_s
    env[rel] *= np.exp(-(t[rel] - dur_s) * 28.0)
    sig = 0.84 * np.sin(2 * math.pi * f * t) + 0.16 * np.sin(2 * math.pi * 2 * f * t)
    return np.tanh(sig * 1.15) * env * 0.58


def synth_kalimba_pluck(midi: int, dur_s: float = 0.55) -> np.ndarray:
    """Delicate warm glassy kalimba / marimba pluck."""
    n = int(SR * dur_s)
    t = np.arange(n, dtype=np.float64) / SR
    f = midi_to_hz(midi)
    env = np.minimum(1.0, t / 0.004) * np.exp(-t * 7.8)
    sig = (
        0.78 * np.sin(2 * math.pi * f * t)
        + 0.16 * np.sin(2 * math.pi * f * 2.98 * t) * np.exp(-t * 24.0)
        + 0.06 * np.sin(2 * math.pi * f * 5.4 * t) * np.exp(-t * 48.0)
    )
    return sig * env * 0.42


# =============================================================================
# 2. FRAME-ACCURATE FOLEY & UI SOUND EFFECTS
# =============================================================================

def sfx_velvet_whoosh(dur_s: float = 0.48, f_start: float = 240.0, f_peak: float = 1650.0) -> np.ndarray:
    """Silky, non-harsh stereo airy whoosh for smooth scene transitions."""
    n = int(SR * dur_s)
    t = np.arange(n, dtype=np.float64) / SR
    x = t / dur_s
    env = np.sin(math.pi * x) ** 1.85
    # Chunked resonant bandpass sweep
    noise_l = RNG.standard_normal(n)
    noise_r = RNG.standard_normal(n)
    out = np.zeros((n, 2), dtype=np.float64)
    block = 512
    for i in range(0, n, block):
        j = min(n, i + block)
        xm = (i + j) * 0.5 / n
        fc = f_start + (f_peak - f_start) * (math.sin(math.pi * xm) ** 1.4)
        bw = fc * 0.65
        sos = signal.butter(2, [max(60.0, fc - bw * 0.5) / (SR * 0.5), min(14000.0, fc + bw * 0.5) / (SR * 0.5)], btype="band", output="sos")
        out[i:j, 0] = signal.sosfilt(sos, noise_l[i:j])
        out[i:j, 1] = signal.sosfilt(sos, noise_r[i:j])
    # Gentle low-pass smoothing to prevent block-edge clicks
    out[:, 0] = butter_filter(out[:, 0] * env, 4800.0, "low", 2)
    out[:, 1] = butter_filter(out[:, 1] * env, 4800.0, "low", 2)
    peak = np.max(np.abs(out)) + 1e-9
    return (out / peak) * 0.24


def sfx_glass_pop(freq_start: float = 420.0, freq_end: float = 760.0, dur_s: float = 0.13) -> np.ndarray:
    """Crisp Telegram-style smooth bubble/glass pop."""
    n = int(SR * dur_s)
    t = np.arange(n, dtype=np.float64) / SR
    freq = freq_start + (freq_end - freq_start) * (1.0 - np.exp(-t * 32.0))
    phase = 2 * math.pi * np.cumsum(freq) / SR
    env = np.minimum(1.0, t / 0.004) * np.exp(-t * 28.0)
    return (np.sin(phase) + 0.22 * np.sin(phase * 2.0) * np.exp(-t * 50.0)) * env * 0.45


def sfx_ui_click() -> np.ndarray:
    """Minimal tactile Apple-style switch click."""
    n = int(SR * 0.045)
    t = np.arange(n, dtype=np.float64) / SR
    sig = (
        np.sin(2 * math.pi * 1350.0 * t) * np.exp(-t * 180.0) * 0.6
        + np.sin(2 * math.pi * 2850.0 * t) * np.exp(-t * 260.0) * 0.4
    )
    return sig * np.minimum(1.0, t / 0.0008) * 0.36


def sfx_chime_interval(midis: list[int], stagger_s: float = 0.095, gain: float = 0.32) -> np.ndarray:
    """Warm glassy bell confirmation chime."""
    total_s = len(midis) * stagger_s + 0.95
    out = np.zeros(int(SR * total_s), dtype=np.float64)
    for i, m in enumerate(midis):
        f = midi_to_hz(m)
        note_s = 0.85
        n = int(SR * note_s)
        t = np.arange(n, dtype=np.float64) / SR
        env = np.minimum(1.0, t / 0.005) * np.exp(-t * 4.8)
        sig = (
            0.74 * np.sin(2 * math.pi * f * t)
            + 0.18 * np.sin(2 * math.pi * f * 2.0 * t) * np.exp(-t * 9.0)
            + 0.08 * np.sin(2 * math.pi * f * 3.01 * t) * np.exp(-t * 16.0)
        ) * env
        idx = int(i * stagger_s * SR)
        out[idx:idx + n] += sig
    return out * gain


def sfx_crystal_glass_place() -> np.ndarray:
    """Solid weighty crystal tumbler placed softly onto dark obsidian bar surface."""
    n = int(SR * 0.36)
    t = np.arange(n, dtype=np.float64) / SR
    # Soft damped low table thud
    thud_f = 115.0 + 75.0 * np.exp(-t * 55.0)
    thud = np.sin(2 * math.pi * np.cumsum(thud_f) / SR) * np.minimum(1.0, t / 0.003) * np.exp(-t * 28.0) * 0.55
    # Delicate crystal tumbler body resonance
    ring = (
        0.24 * np.sin(2 * math.pi * 1620.0 * t) * np.exp(-t * 16.0)
        + 0.14 * np.sin(2 * math.pi * 2740.0 * t) * np.exp(-t * 24.0)
        + 0.08 * np.sin(2 * math.pi * 4380.0 * t) * np.exp(-t * 36.0)
    ) * np.minimum(1.0, t / 0.0015)
    return (thud + ring) * 0.62


def sfx_ice_clink(pitch_mult: float = 1.0, damped_in_liquid: bool = False) -> np.ndarray:
    """Authentic crystal-glass & ice-cube resonant 'tink-clink' using inharmonic modal partials."""
    dur = 0.24 if damped_in_liquid else 0.32
    n = int(SR * dur)
    t = np.arange(n, dtype=np.float64) / SR
    d = 1.65 if damped_in_liquid else 1.0
    # Real glass + ice modal frequencies (slightly inharmonic!)
    partials = [
        (2140.0 * pitch_mult, 1.00, 22.0 * d),
        (3590.0 * pitch_mult, 0.72, 30.0 * d),
        (5180.0 * pitch_mult, 0.48, 42.0 * d),
        (6940.0 * pitch_mult, 0.26, 60.0 * d),
        (9450.0 * pitch_mult, 0.12, 90.0 * d),
    ]
    sig = np.zeros(n, dtype=np.float64)
    for freq, amp, dec in partials:
        if freq < SR * 0.45:
            sig += amp * np.sin(2 * math.pi * freq * t + RNG.uniform(0, 0.4)) * np.exp(-t * dec)
    # Crisp 2ms contact click transient
    click = butter_filter(RNG.standard_normal(n), [2600.0, 9500.0], "band", 2) * np.exp(-t * 420.0) * 0.55
    env = np.minimum(1.0, t / 0.0009)
    sig = (sig * 0.38 + click) * env
    if damped_in_liquid:
        sig = butter_filter(sig, 4600.0, "low", 2)
    return sig * 0.68


def sfx_cola_pour_and_fizz() -> tuple[np.ndarray, np.ndarray]:
    """
    Physically modeled 48kHz stereo Cola Pour into a crystal tumbler (21.0s – 25.55s)
    plus lush carbonated effervescent fizz sizzle tail (21.2s – 29.4s)!

    Uses time-varying quarter-wave cavity resonance rising smoothly from 380 Hz -> 1180 Hz
    as the glass fills 0% -> 78%, layered with micro-bubble 'glug' droplets and ASMR fizz crackle.
    Returns (pour_stereo[21.0s..25.8s], fizz_stereo[21.1s..29.5s]).
    """
    # --- A. Pitch-rising liquid pour (duration 4.8s starting at 21.0s) ---
    pour_dur = 4.80
    n_p = int(SR * pour_dur)
    t_p = np.arange(n_p, dtype=np.float64) / SR

    # Fill curve matching ColaGlassPour.tsx (hits bottom at +0.26s, fills 0 -> 0.78 by +4.55s)
    fill = np.clip((t_p - 0.20) / 4.35, 0.0, 1.0)
    fill_eased = 1.0 - (1.0 - fill) ** 1.35

    # Pour amplitude envelope (smooth ramp-in + organic flow modulation + silky taper at 4.35..4.68s)
    env_in = np.clip(t_p / 0.28, 0.0, 1.0) ** 1.4
    env_out = np.clip((4.65 - t_p) / 0.42, 0.0, 1.0) ** 1.5
    flow_mod = 1.0 + 0.09 * np.sin(2 * math.pi * 3.4 * t_p) + 0.06 * np.sin(2 * math.pi * 7.1 * t_p + 1.2)
    pour_env = env_in * env_out * flow_mod

    # 3 time-varying biquad/SVF cavity resonators via smooth overlap-add blocks
    raw_l = RNG.standard_normal(n_p)
    raw_r = RNG.standard_normal(n_p)
    cavity_l = np.zeros(n_p, dtype=np.float64)
    cavity_r = np.zeros(n_p, dtype=np.float64)

    hop = 256
    win_len = 512
    hann = np.hanning(win_len)
    for start in range(0, n_p - win_len, hop):
        mid = start + win_len // 2
        f_prog = fill_eased[mid]
        # Primary rising Helmholtz / quarter-wave air-column resonance: 380 Hz -> 1180 Hz!
        f1 = 380.0 * ((1180.0 / 380.0) ** f_prog)
        # Second rising glass overtone: 820 Hz -> 2350 Hz
        f2 = 820.0 * ((2350.0 / 820.0) ** f_prog)
        # Warm liquid body splash band: 210 Hz -> 420 Hz
        f3 = 210.0 + 210.0 * f_prog

        def res_band(x_seg: np.ndarray, fc: float, q: float) -> np.ndarray:
            b, a = signal.iirpeak(min(0.45, fc / (SR * 0.5)), Q=q)
            return signal.lfilter(b, a, x_seg)

        seg_l = raw_l[start:start + win_len]
        seg_r = raw_r[start:start + win_len]

        mix_l = (
            1.15 * res_band(seg_l, f1, 5.2)
            + 0.65 * res_band(seg_l, f2, 4.4)
            + 0.48 * res_band(seg_l, f3, 2.2)
        )
        mix_r = (
            1.15 * res_band(seg_r, f1 * 1.012, 5.2)
            + 0.65 * res_band(seg_r, f2 * 0.991, 4.4)
            + 0.48 * res_band(seg_r, f3, 2.2)
        )
        cavity_l[start:start + win_len] += mix_l * hann
        cavity_r[start:start + win_len] += mix_r * hann

    # Layer ~190 individual rising-pitch liquid bubble "bloop/glug" micro-droplets!
    bloops = np.zeros((n_p, 2), dtype=np.float64)
    n_bloops = 210
    bloop_times = np.sort(RNG.uniform(0.18, 4.48, n_bloops))
    for bt in bloop_times:
        f_prog = float(np.clip((bt - 0.20) / 4.35, 0.0, 1.0))
        base_f = (340.0 * ((1240.0 / 340.0) ** f_prog)) * RNG.uniform(0.78, 1.38)
        bdur = RNG.uniform(0.018, 0.048)
        nb = int(SR * bdur)
        tb = np.arange(nb, dtype=np.float64) / SR
        # Real Minnaert bubble physics: tiny upward chirp!
        bf = base_f * (1.0 + 0.42 * (tb / bdur) ** 1.3)
        b_sig = np.sin(2 * math.pi * np.cumsum(bf) / SR) * np.sin(math.pi * tb / bdur) ** 1.4
        amp = RNG.uniform(0.08, 0.24)
        pan = RNG.uniform(-0.35, 0.35)
        idx = int(bt * SR)
        end = min(n_p, idx + nb)
        seg = b_sig[:end - idx] * amp
        bloops[idx:end, 0] += seg * (0.7 - pan * 0.3)
        bloops[idx:end, 1] += seg * (0.7 + pan * 0.3)

    # Smooth silky liquid stream surface hiss (bandpass 1.4k..5.5k)
    stream_l = butter_filter(RNG.standard_normal(n_p), [1300.0, 5200.0], "band", 2) * 0.22
    stream_r = butter_filter(RNG.standard_normal(n_p), [1350.0, 5400.0], "band", 2) * 0.22

    pour_stereo = np.zeros((n_p, 2), dtype=np.float64)
    pour_stereo[:, 0] = (cavity_l * 0.85 + stream_l) * pour_env + bloops[:, 0] * pour_env
    pour_stereo[:, 1] = (cavity_r * 0.85 + stream_r) * pour_env + bloops[:, 1] * pour_env
    peak_p = np.max(np.abs(pour_stereo)) + 1e-9
    pour_stereo = (pour_stereo / peak_p) * 0.56

    # --- B. Fizzy Carbonation Sizzle & Sparkling Effervescent Bubble Crackle (8.3s, 21.15s..29.45s) ---
    fizz_dur = 8.30
    n_f = int(SR * fizz_dur)
    t_f = np.arange(n_f, dtype=np.float64) / SR

    # Lush active sizzle during pour (0..4.5s), then realistic delicate chilled cola fizz tail (4.5..8.3s)
    fizz_in = np.clip(t_f / 0.55, 0.0, 1.0) ** 1.3
    fizz_body = np.where(
        t_f < 4.55,
        0.52 + 0.48 * np.clip((t_f - 0.3) / 3.8, 0.0, 1.0),
        np.exp(-(t_f - 4.55) * 0.62) * 0.92,
    )
    fizz_fade_end = np.clip((fizz_dur - t_f) / 0.85, 0.0, 1.0)
    fizz_env = fizz_in * fizz_body * fizz_fade_end

    # Silky fine carbonation hiss (4.2kHz .. 15kHz)
    hiss_l = butter_filter(RNG.standard_normal(n_f), [4200.0, 14800.0], "band", 2)
    hiss_r = butter_filter(RNG.standard_normal(n_f), [4400.0, 15200.0], "band", 2)

    # Sparkling individual micro-bubble pops (thousands of crisp 2-4ms high-frequency crackles!)
    crackle_l = np.zeros(n_f, dtype=np.float64)
    crackle_r = np.zeros(n_f, dtype=np.float64)
    n_pops = 1900
    pop_times = RNG.uniform(0.05, fizz_dur - 0.15, n_pops)
    for pt in pop_times:
        idx = int(pt * SR)
        plen = RNG.integers(64, 190)
        end = min(n_f, idx + plen)
        m = end - idx
        tp = np.arange(m, dtype=np.float64) / SR
        pf = RNG.uniform(2800.0, 11800.0)
        psig = np.sin(2 * math.pi * pf * tp) * np.exp(-tp * RNG.uniform(650.0, 1500.0))
        pamp = RNG.uniform(0.18, 0.85) * (1.2 if pt < 4.7 else 0.72)
        if RNG.random() < 0.5:
            crackle_l[idx:end] += psig * pamp
            crackle_r[idx:end] += psig * pamp * 0.35
        else:
            crackle_r[idx:end] += psig * pamp
            crackle_l[idx:end] += psig * pamp * 0.35

    fizz_stereo = np.zeros((n_f, 2), dtype=np.float64)
    fizz_stereo[:, 0] = (hiss_l * 0.42 + crackle_l * 0.58) * fizz_env
    fizz_stereo[:, 1] = (hiss_r * 0.42 + crackle_r * 0.58) * fizz_env
    peak_f = np.max(np.abs(fizz_stereo)) + 1e-9
    fizz_stereo = (fizz_stereo / peak_f) * 0.36

    return pour_stereo, fizz_stereo


# =============================================================================
# 3. FULL 30-SECOND ARRANGEMENT & STUDIO MASTERING
# =============================================================================

def build_music_bed() -> np.ndarray:
    """Compose the 14-bar (30.00s exact @ 112 BPM) warm Apple-keynote chill-electronic soundtrack."""
    dry_music = np.zeros((N_SAMPLES, 2), dtype=np.float64)
    reverb_send = np.zeros((N_SAMPLES, 2), dtype=np.float64)

    # 14-bar chord progression in lush D Major 9 / B Minor 9 (warm, bright, modern, classy)
    # Each entry: (bar_idx, chord_midis, bass_midi, dur_bars, energy_level)
    progression = [
        # Bars 0-1 (0.00s - 4.29s): Scene 1 Cold Open — warm airy Rhodes intro
        (0, [50, 57, 61, 64, 66], 38, 1.0, 0.65),  # Dmaj9
        (1, [47, 54, 57, 61, 62], 35, 1.0, 0.75),  # Bm9
        # Bars 2-5 (4.29s - 12.86s): Scenes 2 & 3 — confident groove
        (2, [43, 54, 57, 59, 62], 43, 1.0, 1.00),  # Gmaj9
        (3, [42, 52, 57, 61, 64], 42, 1.0, 1.00),  # F#m7(11)
        (4, [40, 50, 55, 59, 62], 40, 1.0, 1.00),  # Em9
        (5, [45, 52, 55, 59, 62], 45, 1.0, 1.00),  # A13sus4
        # Bars 6-8 (12.86s - 19.29s): Scene 3 climax & Scene 4 Showcase
        (6, [50, 57, 61, 64, 66], 38, 1.0, 1.00),  # Dmaj9
        (7, [47, 54, 57, 61, 66], 35, 1.0, 1.00),  # Bm9
        (8, [43, 54, 57, 59, 64], 43, 1.0, 0.95),  # Gmaj13
        # Bars 9-11 (19.29s - 25.71s): Scene 5 Cola Pour — stripped back & spacious!
        (9,  [40, 50, 55, 59, 62], 40, 1.0, 0.70), # Em9
        (10, [45, 52, 55, 59, 62], 45, 1.0, 0.68), # A13sus4
        (11, [43, 54, 57, 59, 62], 43, 1.0, 0.75), # Gmaj9
        # Bars 12-13 (25.71s - 30.00s): Scene 5 Grand Warm Finale Resolution!
        (12, [45, 52, 57, 61, 64], 45, 0.5, 0.95), # A9sus -> A7
        (12.5, [50, 57, 61, 64, 66, 69], 38, 1.5, 1.05),  # Lush Dmaj9 resolve!
    ]

    for bar_idx, notes, bass_m, dur_bars, energy in progression:
        t0 = bar_idx * BAR_S
        dur_s = dur_bars * BAR_S
        # Gently rolled warm Rhodes chord voicing (18ms human strum!)
        for k, midi in enumerate(notes):
            vel = (0.34 if k == 0 else 0.25) * energy
            sig = synth_rhodes_note(midi_to_hz(midi), dur_s * 0.96, velocity=vel)
            pan = -0.34 + 0.68 * (k / max(1, len(notes) - 1))
            add_stereo(reverb_send, sig, t0 + k * 0.018, pan=pan, gain=0.72)

        # Warm stereo pad bed underneath
        pad = synth_pad_chord(notes[1:], dur_s * 0.98)
        add_stereo(reverb_send, pad, t0, gain=0.22 * energy)

        # Syncopated soft Rhodes re-voicing pulse on beat 2.5 during active groove bars (2..8)
        if 2 <= bar_idx <= 8:
            t_sync = t0 + BEAT_S * 2.5
            for k, midi in enumerate(notes[1:]):
                sig = synth_rhodes_note(midi_to_hz(midi), BEAT_S * 1.15, velocity=0.16 * energy)
                pan = 0.28 - 0.56 * (k / max(1, len(notes) - 2))
                add_stereo(reverb_send, sig, t_sync + k * 0.014, pan=pan, gain=0.58)

        # Warm round sub-bass
        if bar_idx >= 1 and bar_idx < 12.5:
            # Beat 1 root
            bass1 = synth_sub_bass(bass_m, BEAT_S * 1.85)
            add_stereo(dry_music, bass1, t0, pan=0.0, gain=0.42 * energy)
            # Syncopated beat 2.5 & 3.5 during groove (bars 2..8)
            if 2 <= bar_idx <= 8:
                bass2 = synth_sub_bass(bass_m + 7, BEAT_S * 0.75)
                add_stereo(dry_music, bass2, t0 + BEAT_S * 2.5, pan=0.0, gain=0.30 * energy)
                bass3 = synth_sub_bass(bass_m, BEAT_S * 0.75)
                add_stereo(dry_music, bass3, t0 + BEAT_S * 3.5, pan=0.0, gain=0.34 * energy)
        elif bar_idx == 12.5:
            # Warm resolved finale sub-bass hold
            bass_fin = synth_sub_bass(38, BAR_S * 1.45)
            add_stereo(dry_music, bass_fin, t0, pan=0.0, gain=0.46)

    # Tasteful sparse kalimba-bell melodic motif (subtle Apple-keynote signature!)
    melody = [
        # Scene 1 airy sparkle
        (0.50, 74, 0.20, -0.25),
        (1.00, 73, 0.18, 0.25),
        (1.50, 69, 0.22, -0.15),
        # Scene 2 upbeat groove motif
        (2.00, 71, 0.26, -0.28),
        (2.375, 74, 0.22, 0.22),
        (2.75, 76, 0.28, -0.18),
        (3.25, 73, 0.24, 0.26),
        (3.75, 69, 0.22, -0.20),
        (4.00, 71, 0.26, 0.20),
        (4.50, 74, 0.24, -0.25),
        (5.25, 76, 0.26, 0.28),
        (5.75, 73, 0.22, -0.15),
        # Scene 3 & 4 warm echo
        (6.00, 74, 0.28, -0.22),
        (6.50, 78, 0.24, 0.25),
        (7.25, 76, 0.22, -0.28),
        (7.75, 73, 0.22, 0.20),
        (8.00, 71, 0.24, -0.24),
        (8.50, 74, 0.20, 0.24),
        # Finale warm resolving arpeggio (bar 12.5 = 26.78s)
        (12.50, 73, 0.26, -0.30),
        (12.625, 74, 0.28, -0.10),
        (12.75, 78, 0.30, 0.12),
        (13.00, 81, 0.26, 0.32),
    ]
    for bar_pos, midi, g, pan in melody:
        add_stereo(reverb_send, synth_kalimba_pluck(midi, 0.65), bar_pos * BAR_S, pan=pan, gain=g)

    # Pre-synthesize crisp percussion
    kick = synth_kick()
    rim = synth_rim_snap()
    hat_c = synth_hat(False)
    hat_o = synth_hat(True)

    for bar in range(14):
        bt0 = bar * BAR_S
        # Groove active from Bar 1.5 (soft build) through Bar 8, gentle heartbeat in Bar 9,
        # silent percussion during main cola pour (Bars 10-11), soft warm resolve hit on Bar 12.5!
        if 2 <= bar <= 8:
            # Four-on-the-floor with warm chill-electronic syncopation
            for beat in [0.0, 1.0, 2.0, 3.0]:
                add_stereo(dry_music, kick, bt0 + beat * BEAT_S, pan=0.0, gain=0.44)
            # Soft ghost kick on beat 2.5 every odd bar
            if bar % 2 == 1:
                add_stereo(dry_music, kick, bt0 + 2.5 * BEAT_S, pan=0.0, gain=0.20)
            # Crisp rim-snap on beats 2 and 4
            add_stereo(reverb_send, rim, bt0 + 1.0 * BEAT_S, pan=0.06, gain=0.34)
            add_stereo(reverb_send, rim, bt0 + 3.0 * BEAT_S, pan=-0.06, gain=0.36)
            # Silky swung 8th/16th hi-hats
            for step in range(8):
                swing = 0.018 if step % 2 == 1 else 0.0
                t_hat = bt0 + step * (BEAT_S * 0.5) + swing
                is_open = (step == 7 and bar % 2 == 1)
                vel = 0.26 if step % 2 == 0 else 0.17
                pan_h = 0.22 if step % 2 == 0 else -0.22
                add_stereo(dry_music, hat_o if is_open else hat_c, t_hat, pan=pan_h, gain=vel)
        elif bar == 1:
            # Gentle intro pulse on beats 2 & 4
            add_stereo(dry_music, kick, bt0 + 2.0 * BEAT_S, pan=0.0, gain=0.22)
            add_stereo(dry_music, kick, bt0 + 3.0 * BEAT_S, pan=0.0, gain=0.26)
            add_stereo(reverb_send, rim, bt0 + 3.0 * BEAT_S, pan=0.05, gain=0.22)
        elif bar == 9:
            # Soft transition tap into Scene 5 glass entry
            add_stereo(dry_music, kick, bt0, pan=0.0, gain=0.30)
            add_stereo(reverb_send, rim, bt0 + 1.0 * BEAT_S, pan=0.05, gain=0.18)
        elif bar == 12:
            # Soft resolving heartbeat as Colgram reveal blooms
            add_stereo(dry_music, kick, bt0 + 2.0 * BEAT_S, pan=0.0, gain=0.32)

    # Apply lush stereo studio room reverb to harmonic & rim bus
    ir_l, ir_r = make_studio_reverb_ir(rt60=1.55)
    music = dry_music + apply_stereo_reverb(reverb_send, ir_l, ir_r, wet=0.34)

    # Smooth musical ducking + gentle warm low-pass during the Cola Pour (20.4s .. 25.8s)
    # so the crystal ice clinks, rising liquid pour & sparkling fizz shine effortlessly!
    t = np.arange(N_SAMPLES, dtype=np.float64) / SR
    duck_down = np.clip((t - 20.2) / 0.85, 0.0, 1.0)
    duck_up = np.clip((t - 25.4) / 0.95, 0.0, 1.0)
    pour_window = duck_down * (1.0 - duck_up)  # 0 outside pour, 1 inside pour
    music_gain = 1.0 - 0.56 * pour_window       # ~ -7 dB smooth duck during pour

    # Warm low-pass blend during pour window so high-frequency fizz never fights music
    music_lpf = np.column_stack([
        butter_filter(music[:, 0], 1750.0, "low", 2),
        butter_filter(music[:, 1], 1750.0, "low", 2),
    ])
    music = music * (1.0 - 0.65 * pour_window[:, None]) + music_lpf * (0.65 * pour_window[:, None])
    music *= music_gain[:, None]

    # Smooth velvet fade-in (0.0..0.45s) and resolving fade-out (28.7..29.95s)
    fade_in = np.clip(t / 0.45, 0.0, 1.0) ** 1.4
    fade_out = np.clip((29.92 - t) / 1.25, 0.0, 1.0) ** 1.6
    music *= (fade_in * fade_out)[:, None]

    return music


def build_sfx_track() -> np.ndarray:
    """Assemble all frame-accurate synchronized SFX across Scenes 1–5."""
    sfx = np.zeros((N_SAMPLES, 2), dtype=np.float64)

    # --- Scene 1 (0.0s – 4.5s): Cold Open ---
    # Soft airy warm riser + champagne shimmer + gentle glassy pop on title/phone reveal
    add_stereo(sfx, sfx_velvet_whoosh(0.95, 180.0, 1250.0), 0.05, gain=0.68)
    add_stereo(sfx, sfx_glass_pop(360.0, 680.0, 0.16), 0.32, pan=-0.12, gain=0.42)
    add_stereo(sfx, sfx_chime_interval([74, 81], 0.11, gain=0.18), 0.55, pan=0.16, gain=0.75)

    # --- Scene 2 (4.5s – 9.5s): Встроенный обход блокировок ---
    # Smooth filtered whoosh on slide transition (4.30s..4.70s)
    add_stereo(sfx, sfx_velvet_whoosh(0.52, 260.0, 1780.0), 4.28, gain=0.90)
    # Crisp tactile switch click as phone settles
    add_stereo(sfx, sfx_ui_click(), 4.72, pan=-0.28, gain=0.85)
    # Bright positive two-note confirmation chime on "Подключено · Без VPN"
    add_stereo(sfx, sfx_chime_interval([76, 83], 0.095, gain=0.28), 4.92, pan=0.20, gain=0.95)

    # --- Scene 3 (9.5s – 14.5s): Колити ИИ в чатах ---
    # Gentle cross-dissolve whoosh
    add_stereo(sfx, sfx_velvet_whoosh(0.46, 220.0, 1380.0), 9.30, gain=0.72)
    # Soft bubble message-receive pop when user message slides in (frame 558 + 22 = 580 -> 9.67s)
    add_stereo(sfx, sfx_glass_pop(400.0, 740.0, 0.13), 9.68, pan=0.26, gain=0.72)
    # Subtle soft typing pulses during typing indicator (frames 616..668 -> 10.27s..11.13s)
    for dt_s in [10.35, 10.60, 10.85]:
        add_stereo(sfx, sfx_ui_click(), dt_s, pan=0.22, gain=0.38)
    # Warm 3-note reply chime when Koliti responds with animated audio wave (frame 558 + 114 = 672 -> 11.20s)
    add_stereo(sfx, sfx_chime_interval([73, 76, 80], 0.085, gain=0.26), 11.22, pan=0.24, gain=0.95)
    # Quiet check-tick on "✓ Тёплая тема включена" (frame 558 + 150 = 708 -> 11.80s)
    add_stereo(sfx, sfx_ui_click(), 11.82, pan=0.24, gain=0.52)

    # --- Scene 4 (14.5s – 19.5s): Память, Призрак и Стиль ---
    # Gentle smooth whoosh
    add_stereo(sfx, sfx_velvet_whoosh(0.46, 220.0, 1400.0), 14.30, gain=0.72)
    # 3 ascending crystalline UI glass ticks synced exactly to the 3 feature entrances (+0.5s apart)
    # Scene 4 starts at frame 858 (14.30s); delays 16f (14.57s), 46f (15.07s), 76f (15.57s)
    add_stereo(sfx, sfx_chime_interval([78], 0.05, gain=0.24), 14.58, pan=0.14, gain=0.82)
    add_stereo(sfx, sfx_ui_click(), 14.58, pan=0.14, gain=0.45)
    add_stereo(sfx, sfx_chime_interval([81], 0.05, gain=0.24), 15.08, pan=0.18, gain=0.84)
    add_stereo(sfx, sfx_ui_click(), 15.08, pan=0.18, gain=0.45)
    add_stereo(sfx, sfx_chime_interval([85], 0.05, gain=0.26), 15.58, pan=0.22, gain=0.88)
    add_stereo(sfx, sfx_ui_click(), 15.58, pan=0.22, gain=0.45)

    # --- Scene 5 (19.5s – 30.0s): THEMATIC COLA POUR SHOWSTOPPER! ---
    # Smooth airy fade transition
    add_stereo(sfx, sfx_velvet_whoosh(0.55, 200.0, 1320.0), 19.30, gain=0.68)

    # Crystal glass placement (19.78s) + 3 authentic ice cube clinks settling into glass!
    add_stereo(sfx, sfx_crystal_glass_place(), 19.76, pan=-0.04, gain=0.92)
    add_stereo(sfx, sfx_ice_clink(pitch_mult=0.96, damped_in_liquid=False), 20.14, pan=-0.10, gain=0.82)
    add_stereo(sfx, sfx_ice_clink(pitch_mult=1.11, damped_in_liquid=False), 20.40, pan=0.06, gain=0.88)
    add_stereo(sfx, sfx_ice_clink(pitch_mult=1.03, damped_in_liquid=False), 20.66, pan=-0.02, gain=0.76)

    # Physically modeled pitch-rising Cola Pour (21.00s..25.65s) + Sparkling Fizz Sizzle (21.15s..29.45s)
    pour_stereo, fizz_stereo = sfx_cola_pour_and_fizz()
    add_stereo(sfx, pour_stereo, 21.00, gain=1.08)
    add_stereo(sfx, fizz_stereo, 21.15, gain=0.98)

    # Subtle buoyant ice-against-crystal clinks mid-pour as liquid lifts the cubes!
    add_stereo(sfx, sfx_ice_clink(pitch_mult=1.06, damped_in_liquid=True), 22.62, pan=-0.08, gain=0.68)
    add_stereo(sfx, sfx_ice_clink(pitch_mult=0.93, damped_in_liquid=True), 23.95, pan=0.05, gain=0.62)
    add_stereo(sfx, sfx_ice_clink(pitch_mult=1.14, damped_in_liquid=True), 25.10, pan=-0.16, gain=0.50)

    # Gentle glide whoosh as chilled glass moves smoothly left (local 336..424 -> 24.9s..26.3s)
    add_stereo(sfx, sfx_velvet_whoosh(0.62, 190.0, 1180.0), 25.20, gain=0.58)

    # Playful warm Utya Duck + Colgram reveal sparkle chime (local 374 -> 25.53s)
    add_stereo(sfx, sfx_glass_pop(520.0, 880.0, 0.14), 25.62, pan=0.22, gain=0.48)
    add_stereo(sfx, sfx_chime_interval([74, 78, 81, 86], 0.085, gain=0.24), 25.78, pan=0.24, gain=0.92)

    # Subtle short studio room sheen on SFX so foley sits naturally inside the same acoustic space
    ir_l, ir_r = make_studio_reverb_ir(rt60=0.82)
    return apply_stereo_reverb(sfx, ir_l, ir_r, wet=0.16)


def measure_bs1770_lufs(stereo: np.ndarray) -> float:
    """Exact ITU-R BS.1770-4 gated integrated loudness at 48 kHz in LUFS."""
    b1 = np.array([1.53512485958697, -2.69169618940638, 1.19839281085285])
    a1 = np.array([1.0, -1.69065929318241, 0.73248077421585])
    b2 = np.array([1.0, -2.0, 1.0])
    a2 = np.array([1.0, -1.99004745483398, 0.99007225036621])

    kw_l = signal.lfilter(b2, a2, signal.lfilter(b1, a1, stereo[:, 0]))
    kw_r = signal.lfilter(b2, a2, signal.lfilter(b1, a1, stereo[:, 1]))

    blk = int(0.4 * SR)
    hop = int(0.1 * SR)
    z = np.array([
        np.mean(kw_l[i:i + blk] ** 2) + np.mean(kw_r[i:i + blk] ** 2)
        for i in range(0, len(kw_l) - blk, hop)
    ])
    l_blk = -0.691 + 10.0 * np.log10(z + 1e-12)
    g1 = z[l_blk > -70.0]
    if len(g1) == 0:
        return -70.0
    l_rel = -0.691 + 10.0 * math.log10(float(np.mean(g1)) + 1e-12) - 10.0
    g2 = z[l_blk > l_rel]
    if len(g2) == 0:
        g2 = g1
    return -0.691 + 10.0 * math.log10(float(np.mean(g2)) + 1e-12)


def master_stereo(music: np.ndarray, sfx: np.ndarray, target_lufs: float = -13.2) -> np.ndarray:
    """Clean headroom-staged studio mastering: preserve full transient crest factor, exact BS.1770-4 norm, -1.2 dBTP soft ceiling."""
    # 1. Stage each stem to clean peak headroom BEFORE mixing (zero pre-clipping!)
    music_peak = float(np.max(np.abs(music))) + 1e-9
    sfx_peak = float(np.max(np.abs(sfx))) + 1e-9
    music_staged = (music / music_peak) * 0.52
    sfx_staged = (sfx / sfx_peak) * 0.62

    mix = music_staged + sfx_staged

    # 2. Clean 26 Hz subsonic DC-block highpass
    out = np.column_stack([
        butter_filter(mix[:, 0], 26.0, "high", 2),
        butter_filter(mix[:, 1], 26.0, "high", 2),
    ])

    # 3. Normalize to target exact ITU-R BS.1770-4 integrated loudness (-13.2 LUFS)
    cur_lufs = measure_bs1770_lufs(out)
    gain_db = target_lufs - cur_lufs
    out *= 10.0 ** (gain_db / 20.0)

    # 4. Transparent soft-knee true-peak limiter ceiling at -1.2 dBFS (linear 0.871)
    # Only gently kisses isolated transient peaks above -3.5 dBFS (0.67), preserving open natural dynamics!
    ceiling = 10.0 ** (-1.2 / 20.0)
    knee = 0.68
    abs_x = np.abs(out)
    over = abs_x > knee
    if np.any(over):
        excess = abs_x[over] - knee
        headroom = ceiling - knee
        compressed = knee + headroom * np.tanh(excess / headroom)
        out[over] = np.sign(out[over]) * compressed

    out = np.clip(out, -ceiling, ceiling)

    # 5. Clean 15ms micro-fades at exact start and end for zero clicks
    n_fade = int(0.015 * SR)
    ramp = np.linspace(0.0, 1.0, n_fade)
    out[:n_fade] *= ramp[:, None]
    out[-n_fade:] *= ramp[::-1, None]
    return out


def save_wav_16bit(path: Path, stereo: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.round(np.clip(stereo, -1.0, 1.0) * 32767.0).astype("<i2")
    with wave.open(str(path), "wb") as wf:
        wf.setnchannels(2)
        wf.setsampwidth(2)
        wf.setframerate(SR)
        wf.writeframes(pcm.tobytes())


def main() -> None:
    print("Synthesizing 112 BPM (14 bars = 30.00s) warm Apple-keynote soundtrack...")
    music = build_music_bed()
    print("Synthesizing frame-accurate UI + Crystal Glass + Cola Pour + Fizz SFX...")
    sfx = build_sfx_track()

    print("Headroom-staging & mastering 48kHz stereo track to -13.2 LUFS (BS.1770-4)...")
    mastered = master_stereo(music, sfx, target_lufs=-13.2)

    peak_dbfs = 20.0 * math.log10(float(np.max(np.abs(mastered))) + 1e-12)
    lufs = measure_bs1770_lufs(mastered)

    out_path = Path(__file__).resolve().parent / "public" / "colgram-soundtrack.wav"
    save_wav_16bit(out_path, mastered)
    size_mb = os.path.getsize(out_path) / (1024 * 1024)
    print(f"Wrote {out_path} ({size_mb:.2f} MB)")
    print(f"Exact BS.1770-4 Integrated Loudness: {lufs:.2f} LUFS | Sample Peak: {peak_dbfs:.2f} dBFS")


if __name__ == "__main__":
    main()

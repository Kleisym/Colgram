import React from 'react';
import {
  AbsoluteFill,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
import { COLORS, FONT_MONO, FONT_SANS, SPRING_BOUNCY, SPRING_SMOOTH, SPRING_SNAPPY } from '../theme';
import { SceneBackdrop } from '../components/SceneBackdrop';
import { GlassCard } from '../components/GlassCard';
import {
  CheckCircleIcon,
  GithubIcon,
  PaletteIcon,
  ShieldCheckIcon,
  SparklesIcon,
} from '../components/BrandIcons';

const AERO_PRESETS = [
  { name: 'Obsidian Emerald', bg: '#071412', accent: '#10B981', glow: '#34D399' },
  { name: 'Crimson Noir', bg: '#18090F', accent: '#E11D48', glow: '#FB7185' },
  { name: 'Aero Cyan', bg: '#071520', accent: '#06B6D4', glow: '#22D3EE' },
  { name: 'Midnight Glass', bg: '#0C1224', accent: '#3B82F6', glow: '#60A5FA' },
  { name: 'OLED Void', bg: '#050608', accent: '#F43F5E', glow: '#FDA4AF' },
  { name: 'Violet Pulse', bg: '#120C22', accent: '#8B5CF6', glow: '#A78BFA' },
  { name: 'Amber Cola', bg: '#1A1108', accent: '#F59E0B', glow: '#FBBF24' },
  { name: 'Porcelain Frost', bg: '#17202C', accent: '#38BDF8', glow: '#E0F2FE' },
];

export const Scene6Outro: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  // Phase A (0 - 135f): Aero Design Studio showcase
  // Phase B (130 - 300f): Grand GitHub Release Finale Lockup
  const studioEnter = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });
  const studioExit = interpolate(frame, [118, 142], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const studioExitScale = interpolate(frame, [118, 142], [1, 0.94], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const activePresetIdx = Math.floor(frame / 14) % AERO_PRESETS.length;
  const activePreset = AERO_PRESETS[activePresetIdx];

  const finaleEnter = spring({ frame: frame - 134, fps, config: SPRING_SMOOTH });
  const ctaPop = spring({ frame: frame - 156, fps, config: SPRING_BOUNCY });
  const badgesPop = spring({ frame: frame - 174, fps, config: SPRING_SNAPPY });

  const ringAngle = frame * 0.9;
  const finaleGlow = 0.78 + 0.22 * Math.sin(frame * 0.08);

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={frame < 134 ? activePreset.accent : COLORS.emerald}
        accentSecondary={COLORS.cyan}
        accentTertiary={COLORS.crimson}
        seed={606}
        chapterIndex="06 / 06"
        chapterLabel={frame < 134 ? 'AERO DESIGN STUDIO' : 'GITHUB RELEASE'}
      />

      {/* PHASE A: AERO DESIGN STUDIO (0s -> 2.3s of Scene 6) */}
      {frame < 146 && (
        <AbsoluteFill
          style={{
            padding: '86px 76px 56px 76px',
            display: 'flex',
            flexDirection: 'column',
            justifyContent: 'space-between',
            opacity: studioExit,
            transform: `scale(${studioExitScale})`,
          }}
        >
          {/* Header */}
          <div
            style={{
              display: 'flex',
              alignItems: 'flex-end',
              justifyContent: 'space-between',
              opacity: interpolate(studioEnter, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${interpolate(studioEnter, [0, 1], [20, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              })}px)`,
            }}
          >
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <PaletteIcon size={40} color={activePreset.glow} />
                <h2
                  style={{
                    margin: 0,
                    fontSize: 54,
                    fontWeight: 900,
                    letterSpacing: '-0.03em',
                    color: COLORS.textPrimary,
                  }}
                >
                  Aero Design Studio
                </h2>
              </div>
              <span style={{ fontSize: 22, color: COLORS.textSecondary }}>
                8 пресетов Aero Glass · 60fps Видео-обои · Настраиваемый плавающий QuickBar
              </span>
            </div>

            <div
              style={{
                padding: '10px 18px',
                borderRadius: 14,
                backgroundColor: `${activePreset.accent}22`,
                border: `1px solid ${activePreset.glow}66`,
                fontFamily: FONT_MONO,
                fontSize: 15,
                fontWeight: 800,
                color: activePreset.glow,
              }}
            >
              PRESET: {activePreset.name.toUpperCase()}
            </div>
          </div>

          {/* 8 Aero Glass Theme Presets Grid (4x2) */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(4, 1fr)',
              gap: 20,
              marginTop: 10,
            }}
          >
            {AERO_PRESETS.map((preset, idx) => {
              const cardSpring = spring({
                frame: frame - (8 + idx * 5),
                fps,
                config: SPRING_SNAPPY,
              });
              const isSelected = idx === activePresetIdx;
              return (
                <div
                  key={preset.name}
                  style={{
                    padding: '30px 26px',
                    borderRadius: 24,
                    background: `linear-gradient(145deg, ${preset.bg} 0%, rgba(10, 14, 22, 0.94) 100%)`,
                    border: isSelected
                      ? `2px solid ${preset.glow}`
                      : `1px solid ${preset.accent}44`,
                    boxShadow: isSelected
                      ? `0 20px 44px rgba(0,0,0,0.55), 0 0 34px ${preset.accent}55, inset 0 1px 0 rgba(255,255,255,0.28)`
                      : '0 14px 32px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.1)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 24,
                    opacity: interpolate(cardSpring, [0, 1], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    }),
                    transform: `translateY(${interpolate(cardSpring, [0, 1], [24, 0], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    })}px) scale(${isSelected ? 1.03 : 1})`,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <div
                      style={{
                        width: 44,
                        height: 44,
                        borderRadius: 14,
                        background: `linear-gradient(135deg, ${preset.accent}, ${preset.glow})`,
                        boxShadow: `0 0 22px ${preset.accent}88`,
                      }}
                    />
                    <span
                      style={{
                        fontFamily: FONT_MONO,
                        fontSize: 12,
                        fontWeight: 700,
                        color: isSelected ? preset.glow : COLORS.textMuted,
                      }}
                    >
                      0{idx + 1} / 08
                    </span>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                    <span style={{ fontSize: 21, fontWeight: 800, color: COLORS.textPrimary }}>
                      {preset.name}
                    </span>
                    <div style={{ display: 'flex', gap: 6 }}>
                      <span
                        style={{
                          height: 6,
                          width: '62%',
                          borderRadius: 99,
                          backgroundColor: preset.accent,
                        }}
                      />
                      <span
                        style={{
                          height: 6,
                          width: '28%',
                          borderRadius: 99,
                          backgroundColor: 'rgba(255,255,255,0.22)',
                        }}
                      />
                    </div>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Live Floating QuickBar + 60fps Video Wallpapers Strip */}
          <GlassCard
            delay={30}
            accent={activePreset.accent}
            glow
            style={{
              padding: '22px 30px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 24,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
              <SparklesIcon size={28} color={activePreset.glow} />
              <div>
                <div style={{ fontSize: 21, fontWeight: 800, color: COLORS.textPrimary }}>
                  Настраиваемый QuickBar + 60fps Видео-обои
                </div>
                <div style={{ fontSize: 15.5, color: COLORS.textSecondary }}>
                  Быстрые действия в одно касание прямо под поиском чатов + экспорт тем в JSON
                </div>
              </div>
            </div>

            {/* Authentic Colgram QuickBar Chips */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
              {[
                { label: 'Невидимка ВКЛ', color: COLORS.crimson },
                { label: 'Обход L1+L2', color: COLORS.emeraldBright },
                { label: 'Koliti AI', color: COLORS.amberBright },
                { label: '60fps Обои', color: activePreset.glow },
              ].map((chip) => (
                <div
                  key={chip.label}
                  style={{
                    padding: '10px 18px',
                    borderRadius: 999,
                    backgroundColor: `${chip.color}1C`,
                    border: `1.5px solid ${chip.color}70`,
                    fontSize: 15,
                    fontWeight: 700,
                    color: COLORS.textPrimary,
                    whiteSpace: 'nowrap',
                  }}
                >
                  {chip.label}
                </div>
              ))}
            </div>
          </GlassCard>
        </AbsoluteFill>
      )}

      {/* PHASE B: GRAND GITHUB RELEASE FINALE (130 -> 300f) */}
      {frame >= 126 && (
        <AbsoluteFill
          style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            padding: '70px 110px',
            opacity: interpolate(finaleEnter, [0, 1], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
            transform: `scale(${interpolate(finaleEnter, [0, 1], [0.92, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            })})`,
          }}
        >
          {/* Central Luminous Release Stage Card */}
          <div
            style={{
              position: 'relative',
              width: '100%',
              maxWidth: 1380,
              padding: '64px 72px',
              borderRadius: 38,
              background:
                'linear-gradient(155deg, rgba(16, 25, 40, 0.88) 0%, rgba(7, 11, 19, 0.94) 100%)',
              border: '1.5px solid rgba(52, 211, 153, 0.36)',
              boxShadow: `0 36px 90px rgba(0, 0, 0, 0.72), 0 0 80px rgba(16, 185, 129, ${0.22 * finaleGlow}), inset 0 1px 0 rgba(255,255,255,0.22)`,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              gap: 30,
              textAlign: 'center',
            }}
          >
            {/* Colgram Emblem + Orbiting Koliti Mini Badge */}
            <div style={{ position: 'relative', width: 150, height: 150, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <svg
                width="168"
                height="168"
                viewBox="0 0 168 168"
                style={{ position: 'absolute', transform: `rotate(${ringAngle}deg)` }}
              >
                <defs>
                  <linearGradient id="outroRingGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" stopColor={COLORS.emeraldBright} />
                    <stop offset="50%" stopColor={COLORS.cyanBright} />
                    <stop offset="100%" stopColor={COLORS.crimson} />
                  </linearGradient>
                </defs>
                <circle
                  cx="84"
                  cy="84"
                  r="78"
                  fill="none"
                  stroke="url(#outroRingGrad)"
                  strokeWidth="3"
                  strokeDasharray="180 50 90 50"
                />
              </svg>

              <div
                style={{
                  width: 126,
                  height: 126,
                  borderRadius: 34,
                  background: 'linear-gradient(145deg, #161C27, #070A10)',
                  border: '1.5px solid rgba(255,255,255,0.24)',
                  boxShadow: '0 18px 46px rgba(244, 63, 94, 0.35)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Img
                  src={staticFile('colgram-logo.png')}
                  style={{ width: 96, height: 96, objectFit: 'contain' }}
                />
              </div>

              {/* Mini Koliti Avatar Corner Orb */}
              <div
                style={{
                  position: 'absolute',
                  right: -8,
                  bottom: -4,
                  width: 54,
                  height: 54,
                  borderRadius: 999,
                  overflow: 'hidden',
                  border: `2.5px solid ${COLORS.amberBright}`,
                  boxShadow: '0 8px 24px rgba(245, 158, 11, 0.5)',
                }}
              >
                <Img
                  src={staticFile('koliti-avatar.png')}
                  style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                />
              </div>
            </div>

            {/* Grand Headline */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 12, alignItems: 'center' }}>
              <h1
                style={{
                  margin: 0,
                  fontSize: 78,
                  fontWeight: 900,
                  letterSpacing: '-0.035em',
                  lineHeight: 1.04,
                  background: 'linear-gradient(180deg, #FFFFFF 20%, #D1FAE5 70%, #67E8F9 100%)',
                  WebkitBackgroundClip: 'text',
                  WebkitTextFillColor: 'transparent',
                }}
              >
                Colgram 12.10.6 · Уже на GitHub
              </h1>
              <span style={{ fontSize: 25, color: COLORS.textSecondary, fontWeight: 500 }}>
                Без блокировок · С локальным ИИ Koliti · Бесконечная память · Абсолютная приватность
              </span>
            </div>

            {/* Luminous GitHub URL Pill CTA */}
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 18,
                padding: '20px 40px',
                borderRadius: 999,
                background:
                  'linear-gradient(135deg, rgba(16, 185, 129, 0.22) 0%, rgba(6, 182, 212, 0.24) 100%)',
                border: '2px solid rgba(52, 211, 153, 0.68)',
                boxShadow: `0 18px 46px rgba(16, 185, 129, ${0.32 * finaleGlow}), inset 0 1px 0 rgba(255,255,255,0.28)`,
                transform: `scale(${interpolate(ctaPop, [0, 1], [0.85, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                })})`,
                opacity: interpolate(ctaPop, [0, 1], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                }),
              }}
            >
              <GithubIcon size={34} color="#FFFFFF" />
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 34,
                  fontWeight: 800,
                  letterSpacing: '0.01em',
                  color: '#FFFFFF',
                }}
              >
                github.com/Kleisym/Colgram
              </span>
            </div>

            {/* Bottom Assurance Badges */}
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 24,
                marginTop: 4,
                opacity: interpolate(badgesPop, [0, 1], [0, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                }),
              }}
            >
              {[
                'L1 + L2 Zero-Click Bypass',
                'Qwen3.5-2B Offline Core',
                'SHA-256 Unlimited Vault',
                'Zero Telemetry APK',
              ].map((b) => (
                <div
                  key={b}
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    fontFamily: FONT_MONO,
                    fontSize: 15,
                    fontWeight: 600,
                    color: COLORS.textSecondary,
                  }}
                >
                  <CheckCircleIcon size={18} color={COLORS.emeraldBright} />
                  <span>{b}</span>
                </div>
              ))}
            </div>
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};

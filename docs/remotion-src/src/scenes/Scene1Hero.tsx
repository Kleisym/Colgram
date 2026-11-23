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
import { PhoneMockup3D } from '../components/PhoneMockup3D';
import { DatabaseIcon, ShieldCheckIcon, SparklesIcon } from '../components/BrandIcons';

interface Scene1HeroProps {
  isPoster?: boolean;
}

export const Scene1Hero: React.FC<Scene1HeroProps> = ({ isPoster = false }) => {
  const rawFrame = useCurrentFrame();
  const { fps } = useVideoConfig();
  // When rendered as a static poster Still, freeze at the peak hero moment (frame 120)
  const frame = isPoster ? 120 : rawFrame;

  const badgeProgress = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });
  const titleProgress = spring({ frame: frame - 12, fps, config: SPRING_SMOOTH });
  const subtitleProgress = spring({ frame: frame - 24, fps, config: SPRING_SMOOTH });
  const orbProgress = spring({ frame: frame - 26, fps, config: SPRING_BOUNCY });
  const chipTopProgress = spring({ frame: frame - 34, fps, config: SPRING_SNAPPY });

  const ringRotation = frame * 0.7;
  const pulseGlow = 0.72 + 0.28 * Math.sin(frame * 0.08);
  const orbFloatY = Math.sin(frame * 0.055 + 1.2) * 9;
  const chipFloatY = Math.cos(frame * 0.048) * 6;

  const pills = [
    {
      label: 'L1 + L2 Zero-Click Bypass',
      sub: 'Без внешнего VPN',
      color: COLORS.emerald,
      icon: <ShieldCheckIcon size={22} color={COLORS.emeraldBright} />,
      delay: 34,
    },
    {
      label: 'Koliti AI · Qwen3.5-2B',
      sub: '100% локально',
      color: COLORS.amber,
      icon: <SparklesIcon size={22} color={COLORS.amberBright} />,
      delay: 44,
    },
    {
      label: '∞ Хранилище + Privacy',
      sub: 'SHA-256 · Anti-Delete',
      color: COLORS.cyan,
      icon: <DatabaseIcon size={22} color={COLORS.cyanBright} />,
      delay: 54,
    },
  ];

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={COLORS.emerald}
        accentSecondary={COLORS.cyan}
        accentTertiary={COLORS.crimson}
        seed={101}
        chapterIndex="01 / 06"
        chapterLabel="HERO REVEAL"
        showHud={!isPoster}
      />

      {/* Main split hero container */}
      <AbsoluteFill
        style={{
          display: 'flex',
          flexDirection: 'row',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '84px 96px 64px 96px',
          gap: 56,
        }}
      >
        {/* LEFT COLUMN — Emblem, Kinetic Typography & Feature Pills */}
        <div
          style={{
            flex: 1,
            maxWidth: 1030,
            display: 'flex',
            flexDirection: 'column',
            gap: 28,
            zIndex: 10,
          }}
        >
          {/* Aero Glass Emblem + Release Badge */}
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 24,
              opacity: interpolate(badgeProgress, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${interpolate(badgeProgress, [0, 1], [22, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              })}px)`,
            }}
          >
            {/* Glowing Aero Glass Squircle Emblem */}
            <div
              style={{
                position: 'relative',
                width: 96,
                height: 96,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              {/* Rotating orbital ring */}
              <svg
                width="116"
                height="116"
                viewBox="0 0 116 116"
                style={{
                  position: 'absolute',
                  transform: `rotate(${ringRotation}deg)`,
                }}
              >
                <defs>
                  <linearGradient id="heroRingGrad" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" stopColor={COLORS.emeraldBright} />
                    <stop offset="50%" stopColor={COLORS.cyanBright} />
                    <stop offset="100%" stopColor={COLORS.crimson} />
                  </linearGradient>
                </defs>
                <rect
                  x="4"
                  y="4"
                  width="108"
                  height="108"
                  rx="30"
                  fill="none"
                  stroke="url(#heroRingGrad)"
                  strokeWidth="2.4"
                  strokeDasharray="190 60 70 60"
                  opacity={0.85}
                />
              </svg>
              <div
                style={{
                  width: 88,
                  height: 88,
                  borderRadius: 24,
                  overflow: 'hidden',
                  background: 'linear-gradient(145deg, #161B26 0%, #07090E 100%)',
                  border: '1px solid rgba(255,255,255,0.2)',
                  boxShadow: `0 12px 34px rgba(244, 63, 94, ${0.32 * pulseGlow}), inset 0 1px 0 rgba(255,255,255,0.28)`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <Img
                  src={staticFile('colgram-logo.png')}
                  style={{ width: 76, height: 76, objectFit: 'contain' }}
                />
              </div>
            </div>

            {/* Live Release Pill */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              <div
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 10,
                  padding: '8px 18px',
                  borderRadius: 999,
                  background: 'rgba(16, 185, 129, 0.14)',
                  border: '1px solid rgba(52, 211, 153, 0.42)',
                  boxShadow: '0 0 24px rgba(16, 185, 129, 0.18)',
                  width: 'fit-content',
                }}
              >
                <span
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: 999,
                    backgroundColor: COLORS.emeraldBright,
                    boxShadow: `0 0 12px ${COLORS.emeraldBright}`,
                    opacity: pulseGlow,
                  }}
                />
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 15,
                    fontWeight: 700,
                    letterSpacing: '0.12em',
                    color: COLORS.emeraldBright,
                  }}
                >
                  OFFICIAL RELEASE · ANDROID ARM64 / X86_64
                </span>
              </div>
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 14,
                  color: COLORS.textMuted,
                  letterSpacing: '0.06em',
                }}
              >
                ZERO TELEMETRY · AERO GLASS ENGINE · OPEN SOURCE
              </span>
            </div>
          </div>

          {/* Kinetic Hero Title: COLGRAM 12.10.6 */}
          <div
            style={{
              display: 'flex',
              alignItems: 'baseline',
              flexWrap: 'wrap',
              gap: 24,
              opacity: interpolate(titleProgress, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${interpolate(titleProgress, [0, 1], [34, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              })}px)`,
            }}
          >
            <h1
              style={{
                margin: 0,
                fontSize: 118,
                fontWeight: 900,
                letterSpacing: '-0.04em',
                lineHeight: 0.96,
                background: 'linear-gradient(180deg, #FFFFFF 15%, #D5F3FF 70%, #67E8F9 100%)',
                WebkitBackgroundClip: 'text',
                WebkitTextFillColor: 'transparent',
                filter: 'drop-shadow(0 12px 32px rgba(6, 182, 212, 0.25))',
              }}
            >
              COLGRAM
            </h1>
            <div
              style={{
                padding: '10px 24px',
                borderRadius: 18,
                background:
                  'linear-gradient(135deg, rgba(16, 185, 129, 0.22) 0%, rgba(6, 182, 212, 0.22) 100%)',
                border: '1.5px solid rgba(52, 211, 153, 0.55)',
                boxShadow: '0 0 36px rgba(16, 185, 129, 0.26), inset 0 1px 0 rgba(255,255,255,0.22)',
                fontFamily: FONT_MONO,
                fontSize: 48,
                fontWeight: 800,
                letterSpacing: '-0.02em',
                color: COLORS.emeraldBright,
                lineHeight: 1.05,
              }}
            >
              12.10.6
            </div>
          </div>

          {/* Crisp Russian Subtitle */}
          <p
            style={{
              margin: 0,
              fontSize: 33,
              fontWeight: 500,
              lineHeight: 1.38,
              color: COLORS.textSecondary,
              maxWidth: 940,
              opacity: interpolate(subtitleProgress, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${interpolate(subtitleProgress, [0, 1], [24, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              })}px)`,
            }}
          >
            Telegram нового поколения ·{' '}
            <span style={{ color: COLORS.emeraldBright, fontWeight: 700 }}>Без блокировок</span> · С{' '}
            <span style={{ color: COLORS.amberBright, fontWeight: 700 }}>локальным ИИ</span> и{' '}
            <span style={{ color: COLORS.cyanBright, fontWeight: 700 }}>бесконечной памятью</span>
          </p>

          {/* 3 Staggered Aero Glass Feature Cards */}
          <div style={{ display: 'flex', gap: 18, marginTop: 8 }}>
            {pills.map((pill) => {
              const p = spring({ frame: frame - pill.delay, fps, config: SPRING_SNAPPY });
              return (
                <div
                  key={pill.label}
                  style={{
                    flex: 1,
                    padding: '18px 20px',
                    borderRadius: 20,
                    background:
                      'linear-gradient(155deg, rgba(18, 28, 44, 0.84) 0%, rgba(10, 15, 24, 0.88) 100%)',
                    border: `1px solid ${pill.color}48`,
                    boxShadow: `0 16px 36px rgba(0,0,0,0.45), inset 0 1px 0 rgba(255,255,255,0.14)`,
                    display: 'flex',
                    alignItems: 'center',
                    gap: 14,
                    opacity: interpolate(p, [0, 1], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    }),
                    transform: `translateY(${interpolate(p, [0, 1], [26, 0], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    })}px)`,
                  }}
                >
                  <div
                    style={{
                      width: 44,
                      height: 44,
                      borderRadius: 14,
                      backgroundColor: `${pill.color}1F`,
                      border: `1px solid ${pill.color}55`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    {pill.icon}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
                    <span
                      style={{
                        fontSize: 17,
                        fontWeight: 700,
                        color: COLORS.textPrimary,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {pill.label}
                    </span>
                    <span
                      style={{
                        fontSize: 14,
                        fontWeight: 500,
                        color: COLORS.textMuted,
                        whiteSpace: 'nowrap',
                      }}
                    >
                      {pill.sub}
                    </span>
                  </div>
                </div>
              );
            })}
          </div>

          {/* Poster-only GitHub CTA footer so the static thumbnail looks complete */}
          {isPoster && (
            <div
              style={{
                marginTop: 8,
                display: 'inline-flex',
                alignItems: 'center',
                gap: 14,
                padding: '12px 22px',
                borderRadius: 14,
                background: 'rgba(15, 23, 38, 0.85)',
                border: '1px solid rgba(34, 211, 238, 0.35)',
                width: 'fit-content',
              }}
            >
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 20,
                  fontWeight: 700,
                  color: COLORS.cyanBright,
                }}
              >
                github.com/Kleisym/Colgram
              </span>
              <span style={{ color: 'rgba(255,255,255,0.28)' }}>·</span>
              <span style={{ fontSize: 17, fontWeight: 600, color: COLORS.textSecondary }}>
                1080p60 Release Showcase
              </span>
            </div>
          )}
        </div>

        {/* RIGHT COLUMN — 3D Floating Smartphone with Real Colgram UI + Orbiting Koliti Orb */}
        <div
          style={{
            position: 'relative',
            width: 500,
            height: 860,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <PhoneMockup3D
            width={390}
            height={810}
            rotateY={-12}
            rotateX={4}
            delay={isPoster ? -100 : 8}
            glowColor={COLORS.emerald}
            floatAmplitude={isPoster ? 0 : 9}
          >
            <Img
              src={staticFile('screen-network-top.jpg')}
              style={{
                width: '100%',
                height: '100%',
                objectFit: 'cover',
                objectPosition: 'top center',
              }}
            />
          </PhoneMockup3D>

          {/* Top-right floating live telemetry chip */}
          <div
            style={{
              position: 'absolute',
              top: 34,
              right: -54,
              padding: '12px 18px',
              borderRadius: 16,
              background: 'rgba(8, 17, 26, 0.92)',
              border: '1px solid rgba(52, 211, 153, 0.55)',
              boxShadow: '0 16px 36px rgba(0,0,0,0.65), 0 0 24px rgba(16, 185, 129, 0.28)',
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              zIndex: 35,
              opacity: interpolate(chipTopProgress, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${(isPoster ? 0 : chipFloatY) + interpolate(chipTopProgress, [0, 1], [-18, 0], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' })}px)`,
            }}
          >
            <span
              style={{
                width: 10,
                height: 10,
                borderRadius: 999,
                backgroundColor: COLORS.emeraldBright,
                boxShadow: `0 0 12px ${COLORS.emeraldBright}`,
              }}
            />
            <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 13,
                  fontWeight: 700,
                  color: COLORS.emeraldBright,
                  letterSpacing: '0.06em',
                }}
              >
                WSS · АКТИВЕН
              </span>
              <span style={{ fontFamily: FONT_MONO, fontSize: 12, color: COLORS.textSecondary }}>
                354 мс · DoH ВКЛ
              </span>
            </div>
          </div>

          {/* Floating Glowing Koliti AI Orb (anchored cleanly left of phone without covering screen text) */}
          <div
            style={{
              position: 'absolute',
              bottom: 68,
              left: -178,
              display: 'flex',
              flexDirection: 'row',
              alignItems: 'center',
              gap: 14,
              zIndex: 36,
              opacity: interpolate(orbProgress, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${isPoster ? 0 : orbFloatY}px) scale(${interpolate(orbProgress, [0, 1], [0.6, 1], { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' })})`,
            }}
          >
            {/* Orb Glass Tag on the LEFT side so phone text stays 100% unobstructed */}
            <div
              style={{
                padding: '11px 18px',
                borderRadius: 16,
                background: 'rgba(13, 18, 28, 0.92)',
                border: '1px solid rgba(251, 191, 36, 0.5)',
                boxShadow: '0 14px 32px rgba(0, 0, 0, 0.65)',
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'flex-end',
                gap: 2,
              }}
            >
              <span
                style={{
                  fontSize: 15.5,
                  fontWeight: 800,
                  color: COLORS.amberBright,
                  whiteSpace: 'nowrap',
                }}
              >
                Koliti AI Orb
              </span>
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 12,
                  color: COLORS.textSecondary,
                  whiteSpace: 'nowrap',
                }}
              >
                Qwen3.5-2B · Offline
              </span>
            </div>

            <div
              style={{
                position: 'relative',
                width: 108,
                height: 108,
                borderRadius: 999,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                boxShadow: `0 18px 44px rgba(245, 158, 11, 0.45), 0 0 38px rgba(244, 63, 94, 0.38)`,
              }}
            >
              <svg
                width="116"
                height="116"
                viewBox="0 0 116 116"
                style={{
                  position: 'absolute',
                  transform: `rotate(${-ringRotation * 1.4}deg)`,
                }}
              >
                <defs>
                  <linearGradient id="kolitiOrbRing" x1="0%" y1="0%" x2="100%" y2="100%">
                    <stop offset="0%" stopColor={COLORS.amberBright} />
                    <stop offset="55%" stopColor={COLORS.crimson} />
                    <stop offset="100%" stopColor={COLORS.cyanBright} />
                  </linearGradient>
                </defs>
                <circle
                  cx="58"
                  cy="58"
                  r="54"
                  fill="none"
                  stroke="url(#kolitiOrbRing)"
                  strokeWidth="3"
                  strokeDasharray="130 35 60 35"
                />
              </svg>
              <Img
                src={staticFile('koliti-avatar.png')}
                style={{
                  width: 96,
                  height: 96,
                  borderRadius: 999,
                  objectFit: 'cover',
                  border: '2.5px solid rgba(255, 255, 255, 0.35)',
                }}
              />
            </div>
          </div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

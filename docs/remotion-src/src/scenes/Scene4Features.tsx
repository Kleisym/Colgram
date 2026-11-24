import React from 'react';
import { AbsoluteFill, interpolate, useCurrentFrame } from 'remotion';
import { COLORS, EASE_OUT_CUBIC, FONT_SANS, revealMotion } from '../theme';
import { TelegramLottie } from '../components/TelegramLottie';

interface FeatureItem {
  title: string;
  subtitle: string;
  accent: string;
  delayFrames: number;
  icon: React.ReactNode;
}

const FEATURES: FeatureItem[] = [
  {
    title: '∞ Память',
    subtitle: 'Сжатие без потерь и чистая галерея',
    accent: COLORS.caramelAmber,
    delayFrames: 16,
    icon: (
      <svg width="30" height="30" viewBox="0 0 28 28" fill="none">
        <path
          d="M9.2 18.2C6.4 18.2 4.2 16.3 4.2 14C4.2 11.7 6.4 9.8 9.2 9.8C11.4 9.8 12.9 11.4 14 14C15.1 16.6 16.6 18.2 18.8 18.2C21.6 18.2 23.8 16.3 23.8 14C23.8 11.7 21.6 9.8 18.8 9.8C16.6 9.8 15.1 11.4 14 14C12.9 16.6 11.4 18.2 9.2 18.2Z"
          stroke={COLORS.caramelAmber}
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    ),
  },
  {
    title: 'Режим призрака',
    subtitle: 'Видит удалённые сообщения и скрывает онлайн',
    accent: COLORS.colaRed,
    delayFrames: 46, // +0.5s after item 1
    icon: (
      <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
        <path
          d="M6.5 22.5V12.5C6.5 8.35 9.85 5 14 5C18.15 5 21.5 8.35 21.5 12.5V22.5L18.5 20.2L16 22.5L14 20.5L12 22.5L9.5 20.2L6.5 22.5Z"
          stroke={COLORS.colaRed}
          strokeWidth="2.1"
          strokeLinejoin="round"
        />
        <circle cx="11.5" cy="12.8" r="1.4" fill={COLORS.colaRed} />
        <circle cx="16.5" cy="12.8" r="1.4" fill={COLORS.colaRed} />
      </svg>
    ),
  },
  {
    title: 'Живые обои и стиль',
    subtitle: 'Умный контраст и плавные темы',
    accent: COLORS.champagne,
    delayFrames: 76, // +0.5s after item 2
    icon: (
      <svg width="28" height="28" viewBox="0 0 28 28" fill="none">
        <path
          d="M14 4.5L16.3 11.1L23 13.5L16.3 15.9L14 22.5L11.7 15.9L5 13.5L11.7 11.1L14 4.5Z"
          stroke={COLORS.champagne}
          strokeWidth="2"
          strokeLinejoin="round"
        />
        <circle cx="21" cy="7" r="1.5" fill={COLORS.caramelAmber} />
      </svg>
    ),
  },
];

export const Scene4Features: React.FC = () => {
  const frame = useCurrentFrame();

  const leftReveal = revealMotion(frame, 8, 42, 26);
  const lineProgress = interpolate(frame, [28, 72], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });

  return (
    <AbsoluteFill
      style={{
        fontFamily: FONT_SANS,
        padding: '0 148px',
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 96,
      }}
    >
      {/* Left Anchor: Stealth Utya Duck + Quiet Editorial Lead */}
      <div
        style={{
          width: 480,
          flexShrink: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          opacity: leftReveal.opacity,
          transform: `translateY(${leftReveal.translateY}px)`,
        }}
      >
        <div style={{ marginBottom: 28 }}>
          <TelegramLottie
            name="utyan_private"
            size={232}
            playbackRate={0.85}
          />
        </div>

        <h2
          style={{
            margin: 0,
            fontSize: 64,
            fontWeight: 800,
            lineHeight: 1.08,
            letterSpacing: '-0.036em',
            color: COLORS.textPrimary,
          }}
        >
          Продумано
          <br />
          до мелочей
        </h2>

        <div
          style={{
            marginTop: 30,
            width: 64 * lineProgress,
            height: 3.5,
            borderRadius: 999,
            background: `linear-gradient(90deg, ${COLORS.colaRed}, ${COLORS.caramelAmber})`,
            opacity: lineProgress,
          }}
        />
      </div>

      {/* Right Column: Spacious 3-Item Frosted Glass Editorial Showcase */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          gap: 28,
          maxWidth: 1020,
        }}
      >
        {FEATURES.map((item) => {
          const motion = revealMotion(frame, item.delayFrames, 40, 24);
          return (
            <div
              key={item.title}
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 32,
                padding: '34px 42px',
                borderRadius: 28,
                backgroundColor: COLORS.glassFillSubtle,
                border: `1px solid ${COLORS.glassBorder}`,
                boxShadow:
                  '0 24px 56px rgba(0, 0, 0, 0.34), inset 0 1px 0 rgba(255, 255, 255, 0.1)',
                opacity: motion.opacity,
                transform: `translateY(${motion.translateY}px)`,
              }}
            >
              {/* Clean Translucent Icon Tile */}
              <div
                style={{
                  width: 68,
                  height: 68,
                  borderRadius: 20,
                  backgroundColor: 'rgba(255, 248, 244, 0.05)',
                  border: '1px solid rgba(255, 248, 244, 0.1)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                  boxShadow: `0 10px 26px rgba(0, 0, 0, 0.32)`,
                }}
              >
                {item.icon}
              </div>

              {/* Clean Typography Hierarchy */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                <div
                  style={{
                    fontSize: 38,
                    fontWeight: 700,
                    letterSpacing: '-0.025em',
                    color: COLORS.textPrimary,
                    lineHeight: 1.15,
                  }}
                >
                  {item.title}
                </div>
                <div
                  style={{
                    fontSize: 24,
                    fontWeight: 400,
                    letterSpacing: '-0.008em',
                    color: COLORS.textSecondary,
                    lineHeight: 1.35,
                  }}
                >
                  {item.subtitle}
                </div>
              </div>
            </div>
          );
        })}
      </div>
    </AbsoluteFill>
  );
};

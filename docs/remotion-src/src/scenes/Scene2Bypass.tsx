import React from 'react';
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { COLORS, EASE_OUT_CUBIC, FONT_SANS, revealMotion } from '../theme';
import { StudioPhone } from '../components/StudioPhone';
import { TelegramLottie } from '../components/TelegramLottie';

export const Scene2Bypass: React.FC = () => {
  const frame = useCurrentFrame();

  const statusReveal = revealMotion(frame, 14, 38, 18);
  const titleReveal = revealMotion(frame, 24, 42, 26);
  const subReveal = revealMotion(frame, 38, 42, 20);
  const lineProgress = interpolate(frame, [52, 92], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });

  // Gentle pulse for the clean green live status dot
  const dotPulse = 0.65 + Math.sin(frame * 0.1) * 0.35;
  const dotHaloScale = 1 + Math.sin(frame * 0.1) * 0.55;

  // Ultra-subtle tactile scroll inside the real Colgram phone screen
  const screenPanY = interpolate(frame, [24, 300], [0, -16], {
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
      {/* Left Column: Real Colgram Phone Screenshot inside Minimal Studio Bezel */}
      <div
        style={{
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          paddingLeft: 20,
        }}
      >
        <StudioPhone
          width={392}
          height={804}
          delay={4}
          enterDistanceY={42}
          showDynamicIsland={false}
          ambientGlow="rgba(52, 211, 153, 0.14)"
        >
          <div
            style={{
              position: 'relative',
              width: '100%',
              height: '100%',
              overflow: 'hidden',
              backgroundColor: '#131A22',
            }}
          >
            <Img
              src={staticFile('screen-network-top.jpg')}
              style={{
                width: '100%',
                height: '106%',
                objectFit: 'cover',
                objectPosition: 'top center',
                transform: `translateY(${screenPanY}px)`,
              }}
            />
            {/* Soft dark bottom fade so the screenshot integrates seamlessly */}
            <div
              style={{
                position: 'absolute',
                left: 0,
                right: 0,
                bottom: 0,
                height: 54,
                background:
                  'linear-gradient(180deg, transparent 0%, rgba(12, 16, 22, 0.85) 100%)',
                pointerEvents: 'none',
              }}
            />
          </div>
        </StudioPhone>
      </div>

      {/* Right Column: Spacious Left-Aligned Editorial Layout */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          maxWidth: 960,
        }}
      >
        {/* Utya Duck (Cool Shades) + Live Status Dot */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 28,
            marginBottom: 34,
            opacity: statusReveal.opacity,
            transform: `translateY(${statusReveal.translateY}px)`,
          }}
        >
          <TelegramLottie
            name="utyan_private"
            size={168}
            playbackRate={0.85}
          />

          <div
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 14,
              padding: '13px 24px',
              borderRadius: 999,
              backgroundColor: COLORS.glassFill,
              border: `1px solid ${COLORS.glassBorder}`,
              boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.1)',
            }}
          >
            <div
              style={{
                position: 'relative',
                width: 12,
                height: 12,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}
            >
              <div
                style={{
                  position: 'absolute',
                  width: 12,
                  height: 12,
                  borderRadius: '50%',
                  backgroundColor: COLORS.mintConnected,
                  opacity: 0.32 * dotPulse,
                  transform: `scale(${dotHaloScale * 1.45})`,
                }}
              />
              <div
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: '50%',
                  backgroundColor: COLORS.mintConnected,
                  boxShadow: `0 0 10px rgba(52, 211, 153, 0.65)`,
                }}
              />
            </div>
            <span
              style={{
                fontSize: 22,
                fontWeight: 500,
                color: COLORS.textPrimary,
                letterSpacing: '-0.01em',
              }}
            >
              Подключено · Без VPN
            </span>
          </div>
        </div>

        {/* Crisp Display Headline */}
        <h2
          style={{
            margin: 0,
            fontSize: 98,
            fontWeight: 800,
            lineHeight: 1.05,
            letterSpacing: '-0.038em',
            color: COLORS.textPrimary,
            opacity: titleReveal.opacity,
            transform: `translateY(${titleReveal.translateY}px)`,
          }}
        >
          Без блокировок
        </h2>

        {/* Short Single-Line Editorial Subtitle */}
        <p
          style={{
            margin: '26px 0 0 0',
            fontSize: 31,
            fontWeight: 400,
            lineHeight: 1.4,
            letterSpacing: '-0.014em',
            color: COLORS.textSecondary,
            maxWidth: 960,
            opacity: subReveal.opacity,
            transform: `translateY(${subReveal.translateY}px)`,
          }}
        >
          Мгновенная загрузка на Wi-Fi и мобильном интернете
        </p>

        {/* Subtle Cola Accent Hairline */}
        <div
          style={{
            marginTop: 40,
            width: 72 * lineProgress,
            height: 3.5,
            borderRadius: 999,
            background: `linear-gradient(90deg, ${COLORS.colaRed}, ${COLORS.caramelAmber})`,
            opacity: lineProgress,
          }}
        />
      </div>
    </AbsoluteFill>
  );
};

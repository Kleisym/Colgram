import React from 'react';
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { COLORS, EASE_APPLE, EASE_OUT_CUBIC, FONT_SANS, revealMotion } from '../theme';
import { StudioPhone } from '../components/StudioPhone';
import { TelegramLottie } from '../components/TelegramLottie';

export const Scene1Hero: React.FC = () => {
  const frame = useCurrentFrame();

  const iconReveal = revealMotion(frame, 8, 38, 22);
  const titleReveal = revealMotion(frame, 18, 42, 28);
  const subReveal = revealMotion(frame, 34, 42, 22);
  const lineProgress = interpolate(frame, [46, 86], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const duckReveal = revealMotion(frame, 28, 48, 26);

  // Subtle internal shimmer inside the frosted glass phone preview
  const sheenY = interpolate(frame, [0, 282], [-220, 620]);

  return (
    <AbsoluteFill
      style={{
        fontFamily: FONT_SANS,
        padding: '0 148px',
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
      }}
    >
      {/* Left Editorial Column */}
      <div
        style={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          maxWidth: 940,
          zIndex: 4,
        }}
      >
        {/* Minimal App Icon + Quiet Brand Mark */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 20,
            opacity: iconReveal.opacity,
            transform: `translateY(${iconReveal.translateY}px)`,
            marginBottom: 34,
          }}
        >
          <div
            style={{
              width: 72,
              height: 72,
              borderRadius: 20,
              backgroundColor: '#0A0606',
              border: `1px solid ${COLORS.glassBorder}`,
              boxShadow:
                '0 18px 40px rgba(0,0,0,0.48), inset 0 1px 0 rgba(255,255,255,0.18)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              overflow: 'hidden',
            }}
          >
            <Img
              src={staticFile('colgram-logo.png')}
              style={{
                width: 58,
                height: 58,
                objectFit: 'contain',
                mixBlendMode: 'lighten',
              }}
            />
          </div>
          <span
            style={{
              fontSize: 17,
              fontWeight: 500,
              letterSpacing: '0.2em',
              textTransform: 'uppercase',
              color: COLORS.textMuted,
            }}
          >
            Новый взгляд на мессенджер
          </span>
        </div>

        {/* Display Title */}
        <h1
          style={{
            margin: 0,
            fontSize: 128,
            fontWeight: 800,
            lineHeight: 1.0,
            letterSpacing: '-0.04em',
            color: COLORS.textPrimary,
            opacity: titleReveal.opacity,
            transform: `translateY(${titleReveal.translateY}px)`,
            textShadow: '0 24px 56px rgba(0, 0, 0, 0.45)',
          }}
        >
          Colgram
        </h1>

        {/* Clean Single-Line Subtitle */}
        <p
          style={{
            margin: '26px 0 0 0',
            fontSize: 38,
            fontWeight: 400,
            lineHeight: 1.35,
            letterSpacing: '-0.015em',
            color: COLORS.textSecondary,
            opacity: subReveal.opacity,
            transform: `translateY(${subReveal.translateY}px)`,
          }}
        >
          Telegram, который просто работает
        </p>

        {/* Quiet Cola Red Hairline Accent */}
        <div
          style={{
            marginTop: 36,
            width: 76 * lineProgress,
            height: 3.5,
            borderRadius: 999,
            background: `linear-gradient(90deg, ${COLORS.colaRed}, ${COLORS.caramelAmber})`,
            opacity: lineProgress,
          }}
        />

        {/* Calm Expressive Utya Duck Resting Cleanly Below Typography */}
        <div
          style={{
            marginTop: 42,
            display: 'flex',
            alignItems: 'center',
            gap: 22,
            opacity: duckReveal.opacity,
            transform: `translateY(${duckReveal.translateY}px)`,
          }}
        >
          <TelegramLottie
            name="utyan_saved_messages"
            size={196}
            playbackRate={0.85}
          />
        </div>
      </div>

      {/* Right Column: Sleek Frosted Glass Phone Frame Rising Gently */}
      <div
        style={{
          position: 'relative',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          paddingRight: 24,
        }}
      >
        <StudioPhone
          width={388}
          height={790}
          delay={10}
          enterDistanceY={86}
          frostedShell
          showDynamicIsland
          ambientGlow="rgba(229, 57, 70, 0.22)"
        >
          {/* Minimal Translucent Frosted Interior */}
          <div
            style={{
              position: 'relative',
              width: '100%',
              height: '100%',
              background:
                'linear-gradient(168deg, rgba(255,248,244,0.08) 0%, rgba(26,14,12,0.58) 52%, rgba(14,8,7,0.82) 100%)',
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              overflow: 'hidden',
            }}
          >
            {/* Slow soft studio sheen gliding down the frosted screen */}
            <div
              style={{
                position: 'absolute',
                left: -120,
                right: -120,
                top: sheenY,
                height: 210,
                background:
                  'linear-gradient(180deg, transparent 0%, rgba(255,248,244,0.06) 50%, transparent 100%)',
                transform: 'rotate(-14deg)',
                pointerEvents: 'none',
              }}
            />

            {/* Soft Warm Backlight Orb inside frosted glass */}
            <div
              style={{
                position: 'absolute',
                width: 260,
                height: 260,
                borderRadius: '50%',
                background:
                  'radial-gradient(circle, rgba(229,57,70,0.22) 0%, rgba(245,158,11,0.08) 52%, transparent 74%)',
                filter: 'blur(26px)',
              }}
            />

            {/* Center Minimalist Emblem inside Frosted Phone */}
            <div
              style={{
                position: 'relative',
                width: 112,
                height: 112,
                borderRadius: 30,
                backgroundColor: '#0A0606',
                border: `1px solid ${COLORS.glassBorder}`,
                boxShadow:
                  '0 24px 48px rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255,255,255,0.16)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
                overflow: 'hidden',
                transform: `scale(${interpolate(frame, [20, 90], [0.92, 1], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                  easing: EASE_APPLE,
                })})`,
              }}
            >
              <Img
                src={staticFile('colgram-logo.png')}
                style={{ width: 84, height: 84, objectFit: 'contain', mixBlendMode: 'lighten' }}
              />
            </div>

            <div
              style={{
                marginTop: 28,
                fontSize: 24,
                fontWeight: 600,
                letterSpacing: '-0.02em',
                color: COLORS.textPrimary,
                opacity: 0.9,
              }}
            >
              Colgram
            </div>
            <div
              style={{
                marginTop: 8,
                fontSize: 15,
                fontWeight: 400,
                color: COLORS.textMuted,
              }}
            >
              Быстрый. Чистый. Твой.
            </div>

            {/* Minimal skeleton glass rows hinting at upcoming interface */}
            <div
              style={{
                position: 'absolute',
                bottom: 58,
                left: 34,
                right: 34,
                display: 'flex',
                flexDirection: 'column',
                gap: 14,
                opacity: 0.42,
              }}
            >
              {[1, 0.72, 0.52].map((w, idx) => (
                <div
                  key={idx}
                  style={{
                    height: 12,
                    width: `${w * 100}%`,
                    borderRadius: 999,
                    background: 'rgba(255, 248, 244, 0.16)',
                  }}
                />
              ))}
            </div>
          </div>
        </StudioPhone>
      </div>
    </AbsoluteFill>
  );
};

import React from 'react';
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { COLORS, EASE_APPLE, EASE_OUT_CUBIC, FONT_SANS, revealMotion } from '../theme';
import { ColaGlassPour } from '../components/ColaGlassPour';
import { TelegramLottie } from '../components/TelegramLottie';

interface Scene5ColaFinaleProps {
  /** If true, renders directly at the crisp peak finale state for the Poster still */
  isPoster?: boolean;
}

export const Scene5ColaFinale: React.FC<Scene5ColaFinaleProps> = ({ isPoster = false }) => {
  const rawFrame = useCurrentFrame();
  // Peak finale moment: global 28.5s = local frame 552
  const frame = isPoster ? 552 : rawFrame;

  // Center-stage poise during pour (19.5s–25.0s), then silky glide left to welcome Colgram reveal!
  const glassSlideLeft = interpolate(frame, [336, 424], [240, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });

  // Finale reveal enters gracefully right as the cola pour finishes & glass glides into place
  const duckReveal = revealMotion(frame, 372, 44, 30);
  const logoReveal = revealMotion(frame, 384, 42, 22);
  const titleReveal = revealMotion(frame, 394, 46, 28);
  const taglineReveal = revealMotion(frame, 414, 44, 22);
  const urlReveal = revealMotion(frame, 434, 44, 18);

  const lineProgress = interpolate(frame, [426, 472], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });

  const tableFade = interpolate(frame, [10, 75], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });

  const duckScale = interpolate(duckReveal.progress, [0, 1], [0.88, 1]);

  return (
    <AbsoluteFill
      style={{
        fontFamily: FONT_SANS,
        overflow: 'hidden',
      }}
    >
      {/* Subtle Dark Obsidian Studio Surface Horizon grounding the chilled glass */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          top: 808,
          bottom: 0,
          background:
            'linear-gradient(180deg, rgba(255, 244, 232, 0.048) 0%, rgba(12, 7, 6, 0.72) 8%, rgba(8, 5, 4, 0.92) 100%)',
          borderTop: '1px solid rgba(255, 245, 235, 0.08)',
          opacity: tableFade,
          pointerEvents: 'none',
        }}
      />

      {/* Hero Crystal Tumbler Glass + Live Physics Cola Pour */}
      <div
        style={{
          position: 'absolute',
          left: 118,
          top: 38,
          width: 1180,
          height: 1000,
          transform: `translateX(${glassSlideLeft.toFixed(1)}px)`,
          zIndex: 3,
          pointerEvents: 'none',
        }}
      >
        <ColaGlassPour overrideFrame={isPoster ? 552 : undefined} />
      </div>

      {/* Right Editorial Finale Lockup (arrives cleanly after pour settles at 25.5s) */}
      <div
        style={{
          position: 'absolute',
          left: 876,
          right: 136,
          top: 0,
          bottom: 68,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          justifyContent: 'center',
          zIndex: 6,
        }}
      >
        {/* Top Row: Seamless Colgram Emblem + Happy Playful Utya Duck */}
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 26,
            marginBottom: 22,
          }}
        >
          <div
            style={{
              width: 84,
              height: 84,
              borderRadius: 24,
              backgroundColor: '#0A0606',
              border: `1px solid ${COLORS.glassBorder}`,
              boxShadow:
                '0 22px 48px rgba(0, 0, 0, 0.52), inset 0 1px 0 rgba(255, 255, 255, 0.18)',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              overflow: 'hidden',
              opacity: logoReveal.opacity,
              transform: `translateY(${logoReveal.translateY}px)`,
            }}
          >
            <Img
              src={staticFile('colgram-logo.png')}
              style={{
                width: 68,
                height: 68,
                objectFit: 'contain',
                mixBlendMode: 'lighten',
              }}
            />
          </div>

          <div
            style={{
              opacity: duckReveal.opacity,
              transform: `translateY(${duckReveal.translateY}px) scale(${duckScale})`,
            }}
          >
            <TelegramLottie
              name="utyan_newborn"
              size={168}
              playbackRate={0.9}
            />
          </div>
        </div>

        {/* Crisp Apple-Keynote Display Title */}
        <h1
          style={{
            margin: 0,
            fontSize: 122,
            fontWeight: 800,
            lineHeight: 1.0,
            letterSpacing: '-0.04em',
            color: COLORS.textPrimary,
            opacity: titleReveal.opacity,
            transform: `translateY(${titleReveal.translateY}px)`,
            textShadow: '0 24px 56px rgba(0, 0, 0, 0.48)',
          }}
        >
          Colgram
        </h1>

        {/* Thematic Refresh Tagline */}
        <p
          style={{
            margin: '24px 0 0 0',
            fontSize: 42,
            fontWeight: 500,
            lineHeight: 1.3,
            letterSpacing: '-0.018em',
            color: COLORS.champagne,
            opacity: taglineReveal.opacity,
            transform: `translateY(${taglineReveal.translateY}px)`,
          }}
        >
          Освежи свой Telegram
        </p>

        {/* Signature Cola Red -> Caramel Amber Accent Line */}
        <div
          style={{
            marginTop: 34,
            width: 84 * lineProgress,
            height: 3.5,
            borderRadius: 999,
            background: `linear-gradient(90deg, ${COLORS.colaRed}, ${COLORS.caramelAmber})`,
            opacity: lineProgress,
          }}
        />

        {/* Clean Repository Link */}
        <div
          style={{
            marginTop: 30,
            display: 'inline-flex',
            alignItems: 'center',
            gap: 14,
            padding: '14px 26px',
            borderRadius: 999,
            backgroundColor: COLORS.glassFillSubtle,
            border: `1px solid ${COLORS.glassBorder}`,
            boxShadow: 'inset 0 1px 0 rgba(255, 255, 255, 0.1)',
            opacity: urlReveal.opacity,
            transform: `translateY(${urlReveal.translateY}px)`,
          }}
        >
          <span
            style={{
              fontSize: 25,
              fontWeight: 500,
              letterSpacing: '0.005em',
              color: 'rgba(250, 247, 245, 0.84)',
            }}
          >
            github.com/Kleisym/Colgram
          </span>
        </div>
      </div>
    </AbsoluteFill>
  );
};

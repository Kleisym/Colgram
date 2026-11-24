import React, { useMemo } from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { COLORS, createRng, VIDEO_HEIGHT, VIDEO_WIDTH } from '../theme';

interface AmbientBubble {
  id: number;
  x: number;
  startY: number;
  radius: number;
  speedY: number;
  swayAmp: number;
  swayFreq: number;
  phase: number;
  opacity: number;
  warmTint: boolean;
  softBokeh: boolean;
}

interface ColaAtmosphereProps {
  /** Optional warm spotlight shift (0..1) for subtle depth across the film */
  spotX?: number;
  spotY?: number;
  bubbleCount?: number;
}

export const ColaAtmosphere: React.FC<ColaAtmosphereProps> = ({
  spotX = 0.5,
  spotY = 0.46,
  bubbleCount = 44,
}) => {
  const frame = useCurrentFrame();

  const bubbles = useMemo<AmbientBubble[]>(() => {
    const rand = createRng(20261124);
    const list: AmbientBubble[] = [];
    for (let i = 0; i < bubbleCount; i++) {
      const isBokeh = i < 8;
      list.push({
        id: i,
        x: 60 + rand() * (VIDEO_WIDTH - 120),
        startY: rand() * (VIDEO_HEIGHT + 160),
        radius: isBokeh ? 7 + rand() * 8 : 1.8 + rand() * 3.8,
        speedY: isBokeh ? 0.35 + rand() * 0.35 : 0.55 + rand() * 0.95,
        swayAmp: 6 + rand() * 14,
        swayFreq: 0.012 + rand() * 0.016,
        phase: rand() * Math.PI * 2,
        opacity: isBokeh ? 0.09 + rand() * 0.07 : 0.13 + rand() * 0.15,
        warmTint: rand() > 0.38,
        softBokeh: isBokeh,
      });
    }
    return list;
  }, [bubbleCount]);

  // Very gentle breathing motion of the backlit espresso-amber vignette
  const driftX = spotX * 100 + Math.sin(frame * 0.008) * 3.5;
  const driftY = spotY * 100 + Math.cos(frame * 0.006) * 2.5;
  const cherryDriftX = 76 + Math.cos(frame * 0.007) * 4;
  const cherryDriftY = 26 + Math.sin(frame * 0.009) * 3;

  return (
    <AbsoluteFill
      style={{
        backgroundColor: COLORS.bgObsidian,
        overflow: 'hidden',
      }}
    >
      {/* Warm espresso-obsidian radial studio lighting */}
      <AbsoluteFill
        style={{
          background: `
            radial-gradient(ellipse 74% 68% at ${driftX}% ${driftY}%, #23130F 0%, #160C0A 46%, #0D0908 100%)
          `,
        }}
      />

      {/* Whisper-subtle backlit caramel & cola-cherry ambient glow */}
      <AbsoluteFill
        style={{
          background: `
            radial-gradient(circle 680px at ${cherryDriftX}% ${cherryDriftY}%, rgba(229, 57, 70, 0.075) 0%, transparent 72%),
            radial-gradient(circle 760px at ${100 - cherryDriftX}% 72%, rgba(245, 158, 11, 0.065) 0%, transparent 72%)
          `,
        }}
      />

      {/* Gentle rising champagne / chilled cola micro-bubbles */}
      <svg
        width={VIDEO_WIDTH}
        height={VIDEO_HEIGHT}
        viewBox={`0 0 ${VIDEO_WIDTH} ${VIDEO_HEIGHT}`}
        style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
      >
        <defs>
          <filter id="colaBokehBlur" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="4.5" />
          </filter>
          <radialGradient id="bubbleWarmGrad" cx="35%" cy="30%" r="65%">
            <stop offset="0%" stopColor="#FFF7ED" stopOpacity="0.95" />
            <stop offset="48%" stopColor="#F59E0B" stopOpacity="0.45" />
            <stop offset="100%" stopColor="#E53946" stopOpacity="0.0" />
          </radialGradient>
          <radialGradient id="bubblePearlGrad" cx="35%" cy="30%" r="65%">
            <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.9" />
            <stop offset="55%" stopColor="#FDE6C4" stopOpacity="0.32" />
            <stop offset="100%" stopColor="#FDE6C4" stopOpacity="0.0" />
          </radialGradient>
        </defs>

        {bubbles.map((b) => {
          const span = VIDEO_HEIGHT + 160;
          const rawY = b.startY - frame * b.speedY;
          const wrappedY = (((rawY % span) + span) % span) - 80;
          const cx = b.x + Math.sin(frame * b.swayFreq + b.phase) * b.swayAmp;

          // Fade gracefully near very bottom and very top edges
          const edgeFade =
            wrappedY < 80
              ? Math.max(0, (wrappedY + 40) / 120)
              : wrappedY > VIDEO_HEIGHT - 80
                ? Math.max(0, (VIDEO_HEIGHT + 40 - wrappedY) / 120)
                : 1;
          const finalOpacity = b.opacity * edgeFade;

          if (finalOpacity <= 0.005) return null;

          return (
            <g
              key={b.id}
              opacity={finalOpacity}
              filter={b.softBokeh ? 'url(#colaBokehBlur)' : undefined}
            >
              <circle
                cx={cx}
                cy={wrappedY}
                r={b.radius}
                fill={b.warmTint ? 'url(#bubbleWarmGrad)' : 'url(#bubblePearlGrad)'}
                stroke={b.softBokeh ? 'none' : 'rgba(253, 230, 196, 0.42)'}
                strokeWidth={b.softBokeh ? 0 : 0.7}
              />
              {!b.softBokeh && b.radius >= 3.0 && (
                <circle
                  cx={cx - b.radius * 0.3}
                  cy={wrappedY - b.radius * 0.3}
                  r={b.radius * 0.25}
                  fill="#FFFFFF"
                  opacity={0.75}
                />
              )}
            </g>
          );
        })}
      </svg>

      {/* Soft cinematic edge vignette */}
      <AbsoluteFill
        style={{
          background:
            'radial-gradient(ellipse 84% 82% at 50% 50%, transparent 56%, rgba(6, 3, 2, 0.58) 100%)',
          pointerEvents: 'none',
        }}
      />
    </AbsoluteFill>
  );
};

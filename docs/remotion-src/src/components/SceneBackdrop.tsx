import React, { useMemo } from 'react';
import { AbsoluteFill, useCurrentFrame } from 'remotion';
import { COLORS, FONT_MONO, FONT_SANS, generateParticles, VIDEO_HEIGHT } from '../theme';

interface SceneBackdropProps {
  accentPrimary: string;
  accentSecondary: string;
  accentTertiary?: string;
  seed: number;
  chapterIndex: string;
  chapterLabel: string;
  showHud?: boolean;
}

export const SceneBackdrop: React.FC<SceneBackdropProps> = ({
  accentPrimary,
  accentSecondary,
  accentTertiary = COLORS.crimson,
  seed,
  chapterIndex,
  chapterLabel,
  showHud = true,
}) => {
  const frame = useCurrentFrame();

  const particles = useMemo(
    () => generateParticles(38, seed, [accentPrimary, accentSecondary, '#FFFFFF']),
    [seed, accentPrimary, accentSecondary],
  );

  // Smooth orbital drift for background aurora blobs
  const orb1X = 18 + Math.sin(frame * 0.011 + seed) * 6;
  const orb1Y = 22 + Math.cos(frame * 0.009 + seed) * 7;
  const orb2X = 82 + Math.cos(frame * 0.01 + seed * 2) * 7;
  const orb2Y = 74 + Math.sin(frame * 0.012 + seed) * 6;
  const orb3X = 52 + Math.sin(frame * 0.008 + seed * 3) * 10;
  const orb3Y = 88 + Math.cos(frame * 0.01 + seed) * 5;

  return (
    <AbsoluteFill
      style={{
        backgroundColor: COLORS.bgDeep,
        overflow: 'hidden',
        fontFamily: FONT_SANS,
      }}
    >
      {/* Base obsidian mesh */}
      <AbsoluteFill
        style={{
          background: `
            radial-gradient(circle at ${orb1X}% ${orb1Y}%, ${accentPrimary}2E 0%, transparent 48%),
            radial-gradient(circle at ${orb2X}% ${orb2Y}%, ${accentSecondary}26 0%, transparent 50%),
            radial-gradient(circle at ${orb3X}% ${orb3Y}%, ${accentTertiary}16 0%, transparent 45%),
            linear-gradient(155deg, #04060A 0%, #080E18 52%, #05080F 100%)
          `,
        }}
      />

      {/* Crisp geometric cyber-grid with radial mask */}
      <AbsoluteFill
        style={{
          backgroundImage: `
            linear-gradient(rgba(255, 255, 255, 0.032) 1px, transparent 1px),
            linear-gradient(90deg, rgba(255, 255, 255, 0.032) 1px, transparent 1px)
          `,
          backgroundSize: '64px 64px',
          backgroundPosition: `${(frame * 0.25) % 64}px ${(frame * 0.15) % 64}px`,
          WebkitMaskImage: 'radial-gradient(ellipse 85% 80% at 50% 50%, black 35%, transparent 100%)',
          maskImage: 'radial-gradient(ellipse 85% 80% at 50% 50%, black 35%, transparent 100%)',
        }}
      />

      {/* Subtle diagonal specular beam */}
      <AbsoluteFill
        style={{
          background: `linear-gradient(125deg, transparent 35%, ${accentPrimary}0C 49%, ${accentSecondary}0A 52%, transparent 65%)`,
          transform: `translateX(${Math.sin(frame * 0.014) * 60}px)`,
        }}
      />

      {/* Deterministic glowing data particles */}
      <svg
        width="1920"
        height="1080"
        viewBox="0 0 1920 1080"
        style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
      >
        {particles.map((p) => {
          const yPos = ((p.y - frame * p.speedY) % (VIDEO_HEIGHT + 80) + (VIDEO_HEIGHT + 80)) % (VIDEO_HEIGHT + 80) - 40;
          const xPos = p.x + Math.sin(frame * 0.02 + p.phase) * p.driftX;
          const pulse = 0.65 + 0.35 * Math.sin(frame * 0.05 + p.phase);
          return (
            <circle
              key={p.id}
              cx={xPos}
              cy={yPos}
              r={p.size}
              fill={p.tint}
              fillOpacity={p.opacity * pulse}
            />
          );
        })}
      </svg>

      {/* Soft cinematic vignette */}
      <AbsoluteFill
        style={{
          background:
            'radial-gradient(ellipse 92% 90% at 50% 50%, transparent 56%, rgba(2, 4, 8, 0.68) 100%)',
          pointerEvents: 'none',
        }}
      />

      {/* Top minimal cinema HUD */}
      {showHud && (
        <div
          style={{
            position: 'absolute',
            top: 30,
            left: 68,
            right: 68,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            pointerEvents: 'none',
            zIndex: 40,
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 12,
              padding: '7px 16px',
              borderRadius: 999,
              backgroundColor: 'rgba(10, 16, 28, 0.65)',
              border: '1px solid rgba(255, 255, 255, 0.11)',
              boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.12)',
            }}
          >
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: 999,
                backgroundColor: accentPrimary,
                boxShadow: `0 0 12px ${accentPrimary}`,
              }}
            />
            <span
              style={{
                fontFamily: FONT_MONO,
                fontSize: 13,
                fontWeight: 700,
                letterSpacing: '0.12em',
                color: accentPrimary,
              }}
            >
              {chapterIndex}
            </span>
            <span style={{ color: 'rgba(255,255,255,0.25)', fontSize: 13 }}>/</span>
            <span
              style={{
                fontFamily: FONT_MONO,
                fontSize: 13,
                fontWeight: 500,
                letterSpacing: '0.08em',
                color: COLORS.textSecondary,
                textTransform: 'uppercase',
              }}
            >
              {chapterLabel}
            </span>
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              gap: 10,
              fontFamily: FONT_MONO,
              fontSize: 13,
              fontWeight: 600,
              letterSpacing: '0.1em',
              color: COLORS.textMuted,
            }}
          >
            <span style={{ color: COLORS.textPrimary }}>COLGRAM</span>
            <span style={{ color: COLORS.crimson }}>12.10.6</span>
          </div>
        </div>
      )}
    </AbsoluteFill>
  );
};

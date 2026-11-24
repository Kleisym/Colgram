import React from 'react';
import { interpolate, useCurrentFrame } from 'remotion';
import { EASE_APPLE } from '../theme';

interface StudioPhoneProps {
  children: React.ReactNode;
  width?: number;
  height?: number;
  delay?: number;
  enterDistanceY?: number;
  frostedShell?: boolean;
  showDynamicIsland?: boolean;
  ambientGlow?: string;
  style?: React.CSSProperties;
}

export const StudioPhone: React.FC<StudioPhoneProps> = ({
  children,
  width = 390,
  height = 800,
  delay = 0,
  enterDistanceY = 54,
  frostedShell = false,
  showDynamicIsland = true,
  ambientGlow = 'rgba(229, 57, 70, 0.16)',
  style,
}) => {
  const frame = useCurrentFrame();

  const enter = interpolate(frame, [delay, delay + 44], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });

  const opacity = interpolate(enter, [0, 0.55, 1], [0, 0.92, 1]);
  const translateY = (1 - enter) * enterDistanceY + Math.sin(frame * 0.032) * 3.5;
  const scale = interpolate(enter, [0, 1], [0.96, 1]);

  return (
    <div
      style={{
        position: 'relative',
        width,
        height,
        opacity,
        transform: `translateY(${translateY}px) scale(${scale})`,
        ...style,
      }}
    >
      {/* Soft warm backlit studio halo (restraint, zero neon) */}
      <div
        style={{
          position: 'absolute',
          inset: -44,
          borderRadius: 84,
          background: `radial-gradient(ellipse 65% 60% at 50% 48%, ${ambientGlow} 0%, transparent 72%)`,
          filter: 'blur(34px)',
          pointerEvents: 'none',
        }}
      />

      {/* Minimal Matte Titanium / Frosted Obsidian Frame */}
      <div
        style={{
          position: 'relative',
          width: '100%',
          height: '100%',
          borderRadius: 52,
          padding: 11,
          background: frostedShell
            ? 'linear-gradient(155deg, rgba(255,248,244,0.16) 0%, rgba(255,245,238,0.05) 45%, rgba(255,245,238,0.09) 100%)'
            : 'linear-gradient(155deg, #2D2624 0%, #171312 38%, #0E0B0A 78%, #261E1C 100%)',
          boxShadow: frostedShell
            ? `
              0 46px 96px rgba(0, 0, 0, 0.58),
              inset 0 1.5px 0 rgba(255, 255, 255, 0.24),
              0 0 0 1px rgba(255, 248, 244, 0.14)
            `
            : `
              0 52px 110px rgba(0, 0, 0, 0.72),
              0 16px 36px rgba(0, 0, 0, 0.48),
              0 0 0 1px rgba(255, 248, 244, 0.16),
              inset 0 1.5px 1px rgba(255, 255, 255, 0.22)
            `,
          backdropFilter: frostedShell ? 'blur(24px)' : undefined,
        }}
      >
        {/* Subtle side hardware buttons */}
        {!frostedShell && (
          <>
            <div
              style={{
                position: 'absolute',
                right: -3,
                top: 170,
                width: 3,
                height: 76,
                borderRadius: '0 2px 2px 0',
                background: 'linear-gradient(180deg, #3A3230, #1A1514)',
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: -3,
                top: 148,
                width: 3,
                height: 44,
                borderRadius: '2px 0 0 2px',
                background: 'linear-gradient(180deg, #3A3230, #1A1514)',
              }}
            />
            <div
              style={{
                position: 'absolute',
                left: -3,
                top: 206,
                width: 3,
                height: 44,
                borderRadius: '2px 0 0 2px',
                background: 'linear-gradient(180deg, #3A3230, #1A1514)',
              }}
            />
          </>
        )}

        {/* Inner Display Cavity */}
        <div
          style={{
            position: 'relative',
            width: '100%',
            height: '100%',
            borderRadius: 42,
            overflow: 'hidden',
            backgroundColor: frostedShell ? 'rgba(20, 13, 11, 0.42)' : '#121720',
            boxShadow: 'inset 0 0 0 1px rgba(255, 255, 255, 0.08)',
          }}
        >
          {children}

          {/* Minimal top camera pill */}
          {showDynamicIsland && (
            <div
              style={{
                position: 'absolute',
                top: 12,
                left: '50%',
                transform: 'translateX(-50%)',
                width: 84,
                height: 22,
                borderRadius: 999,
                backgroundColor: frostedShell ? 'rgba(255,255,255,0.12)' : '#090A0D',
                boxShadow: frostedShell ? undefined : '0 0 0 1px rgba(255,255,255,0.06)',
                zIndex: 30,
                pointerEvents: 'none',
              }}
            />
          )}

          {/* Subtle studio diagonal glass reflection */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'linear-gradient(130deg, rgba(255, 255, 255, 0.09) 0%, rgba(255, 255, 255, 0.02) 28%, transparent 48%, transparent 82%, rgba(255, 248, 244, 0.03) 100%)',
              pointerEvents: 'none',
              zIndex: 25,
            }}
          />
        </div>
      </div>
    </div>
  );
};

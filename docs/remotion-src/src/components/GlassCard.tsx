import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { COLORS, SPRING_SNAPPY } from '../theme';

interface GlassCardProps {
  children: React.ReactNode;
  delay?: number;
  accent?: string;
  style?: React.CSSProperties;
  glow?: boolean;
  sheen?: boolean;
  translateFromY?: number;
}

export const GlassCard: React.FC<GlassCardProps> = ({
  children,
  delay = 0,
  accent = COLORS.emerald,
  style,
  glow = false,
  sheen = true,
  translateFromY = 26,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const progress = spring({
    frame: frame - delay,
    fps,
    config: SPRING_SNAPPY,
  });

  const opacity = interpolate(progress, [0, 1], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const translateY = interpolate(progress, [0, 1], [translateFromY, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const scale = interpolate(progress, [0, 1], [0.96, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const sheenX = interpolate(frame - delay, [10, 65], [-120, 220], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <div
      style={{
        position: 'relative',
        borderRadius: 22,
        background: `linear-gradient(155deg, rgba(19, 29, 46, 0.82) 0%, rgba(10, 15, 25, 0.86) 100%)`,
        border: `1px solid ${glow ? `${accent}55` : COLORS.borderGlass}`,
        boxShadow: glow
          ? `0 20px 50px rgba(0, 0, 0, 0.5), 0 0 36px ${accent}20, inset 0 1px 0 rgba(255, 255, 255, 0.16)`
          : `0 18px 44px rgba(0, 0, 0, 0.45), inset 0 1px 0 rgba(255, 255, 255, 0.12)`,
        overflow: 'hidden',
        opacity,
        transform: `translateY(${translateY}px) scale(${scale})`,
        ...style,
      }}
    >
      {/* Top accent specular edge */}
      <div
        style={{
          position: 'absolute',
          top: 0,
          left: 20,
          right: 20,
          height: 1.5,
          background: `linear-gradient(90deg, transparent 0%, ${accent}99 50%, transparent 100%)`,
          pointerEvents: 'none',
        }}
      />

      {/* One-shot glass light gleam on entrance */}
      {sheen && (
        <div
          style={{
            position: 'absolute',
            top: -40,
            bottom: -40,
            width: 140,
            left: `${sheenX}%`,
            transform: 'skewX(-22deg)',
            background:
              'linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.08), transparent)',
            pointerEvents: 'none',
          }}
        />
      )}

      {children}
    </div>
  );
};

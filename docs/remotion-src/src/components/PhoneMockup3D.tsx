import React from 'react';
import { interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { COLORS, SPRING_SMOOTH } from '../theme';

interface PhoneMockup3DProps {
  children: React.ReactNode;
  width?: number;
  height?: number;
  rotateY?: number;
  rotateX?: number;
  delay?: number;
  glowColor?: string;
  floatAmplitude?: number;
  showNotch?: boolean;
}

export const PhoneMockup3D: React.FC<PhoneMockup3DProps> = ({
  children,
  width = 372,
  height = 776,
  rotateY = -12,
  rotateX = 4,
  delay = 4,
  glowColor = COLORS.emerald,
  floatAmplitude = 8,
  showNotch = false,
}) => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const enter = spring({
    frame: frame - delay,
    fps,
    config: SPRING_SMOOTH,
  });

  const opacity = interpolate(enter, [0, 0.5, 1], [0, 0.9, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const enterY = interpolate(enter, [0, 1], [70, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const enterRotY = interpolate(enter, [0, 1], [rotateY - 14, rotateY], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const floatY = Math.sin(frame * 0.045) * floatAmplitude;
  const subtleTiltY = enterRotY + Math.sin(frame * 0.028) * 2.2;
  const subtleTiltX = rotateX + Math.cos(frame * 0.032) * 1.4;

  return (
    <div
      style={{
        position: 'relative',
        width,
        height,
        perspective: 1400,
        opacity,
        transform: `translateY(${enterY + floatY}px)`,
      }}
    >
      {/* Back ambient halo */}
      <div
        style={{
          position: 'absolute',
          inset: -28,
          borderRadius: 68,
          background: `radial-gradient(circle at 50% 45%, ${glowColor}44 0%, transparent 70%)`,
          filter: 'blur(26px)',
          pointerEvents: 'none',
        }}
      />

      {/* 3D Titanium Chassis */}
      <div
        style={{
          position: 'relative',
          width: '100%',
          height: '100%',
          borderRadius: 46,
          padding: 10,
          background:
            'linear-gradient(145deg, #334155 0%, #1E293B 25%, #0F172A 68%, #283548 100%)',
          boxShadow: `
            0 36px 80px rgba(0, 0, 0, 0.72),
            0 0 0 1px rgba(255, 255, 255, 0.16),
            inset 0 2px 3px rgba(255, 255, 255, 0.28),
            inset 0 -2px 4px rgba(0, 0, 0, 0.65)
          `,
          transform: `rotateY(${subtleTiltY}deg) rotateX(${subtleTiltX}deg)`,
          transformStyle: 'preserve-3d',
        }}
      >
        {/* Inner OLED Screen */}
        <div
          style={{
            position: 'relative',
            width: '100%',
            height: '100%',
            borderRadius: 37,
            overflow: 'hidden',
            backgroundColor: '#0A0D12',
            boxShadow: 'inset 0 0 0 1px rgba(255,255,255,0.08)',
          }}
        >
          {children}

          {/* Optional punch-hole camera */}
          {showNotch && (
            <div
              style={{
                position: 'absolute',
                top: 12,
                left: '50%',
                transform: 'translateX(-50%)',
                width: 78,
                height: 22,
                borderRadius: 999,
                backgroundColor: '#05070A',
                border: '1px solid rgba(255,255,255,0.08)',
                zIndex: 30,
              }}
            />
          )}

          {/* Subtle glass reflection overlay */}
          <div
            style={{
              position: 'absolute',
              inset: 0,
              background:
                'linear-gradient(128deg, rgba(255,255,255,0.09) 0%, transparent 34%, transparent 72%, rgba(255,255,255,0.04) 100%)',
              pointerEvents: 'none',
              zIndex: 25,
            }}
          />
        </div>
      </div>
    </div>
  );
};

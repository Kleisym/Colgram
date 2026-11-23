import React from 'react';
import { AbsoluteFill, interpolate, useCurrentFrame } from 'remotion';
import { linearTiming, TransitionSeries } from '@remotion/transitions';
import { fade } from '@remotion/transitions/fade';
import { slide } from '@remotion/transitions/slide';
import { wipe } from '@remotion/transitions/wipe';
import {
  COLORS,
  SCENE_DURATIONS,
  TRANSITION_FRAMES,
  VIDEO_DURATION_FRAMES,
} from './theme';
import { Scene1Hero } from './scenes/Scene1Hero';
import { Scene2Bypass } from './scenes/Scene2Bypass';
import { Scene3Koliti } from './scenes/Scene3Koliti';
import { Scene4Storage } from './scenes/Scene4Storage';
import { Scene5Privacy } from './scenes/Scene5Privacy';
import { Scene6Outro } from './scenes/Scene6Outro';

export const ColgramRelease: React.FC = () => {
  const frame = useCurrentFrame();

  const progressPct = interpolate(frame, [0, VIDEO_DURATION_FRAMES - 1], [0, 100], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const transitionTiming = linearTiming({ durationInFrames: TRANSITION_FRAMES });

  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bgDeep }}>
      <TransitionSeries>
        {/* Scene 1: 0s - 5s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.hero}>
          <Scene1Hero />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={slide({ direction: 'from-right' })}
          timing={transitionTiming}
        />

        {/* Scene 2: 5s - 12s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.bypass}>
          <Scene2Bypass />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={wipe({ direction: 'from-left' })}
          timing={transitionTiming}
        />

        {/* Scene 3: 12s - 19s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.koliti}>
          <Scene3Koliti />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={slide({ direction: 'from-bottom' })}
          timing={transitionTiming}
        />

        {/* Scene 4: 19s - 26s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.storage}>
          <Scene4Storage />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition presentation={fade()} timing={transitionTiming} />

        {/* Scene 5: 26s - 31s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.privacy}>
          <Scene5Privacy />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition presentation={fade()} timing={transitionTiming} />

        {/* Scene 6: 31s - 36s */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.outro}>
          <Scene6Outro />
        </TransitionSeries.Sequence>
      </TransitionSeries>

      {/* Ultra-slim luminous cinema timeline progress bar at bottom edge */}
      <div
        style={{
          position: 'absolute',
          left: 0,
          right: 0,
          bottom: 0,
          height: 4,
          backgroundColor: 'rgba(255, 255, 255, 0.08)',
          zIndex: 100,
          pointerEvents: 'none',
        }}
      >
        <div
          style={{
            width: `${progressPct}%`,
            height: '100%',
            background: `linear-gradient(90deg, ${COLORS.emeraldBright}, ${COLORS.cyanBright}, ${COLORS.crimson})`,
            boxShadow: `0 0 12px ${COLORS.cyanBright}`,
          }}
        />
      </div>
    </AbsoluteFill>
  );
};

export const ColgramPoster: React.FC = () => {
  return <Scene1Hero isPoster />;
};

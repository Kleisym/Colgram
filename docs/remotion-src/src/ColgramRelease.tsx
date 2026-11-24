import React from 'react';
import { AbsoluteFill, Audio, interpolate, Sequence, staticFile, useCurrentFrame } from 'remotion';
import { linearTiming, TransitionSeries } from '@remotion/transitions';
import { fade } from '@remotion/transitions/fade';
import { slide } from '@remotion/transitions/slide';
import {
  COLORS,
  EASE_IN_OUT_CUBIC,
  SCENE_DURATIONS,
  TRANSITION_FRAMES,
  VIDEO_DURATION_FRAMES,
} from './theme';
import { ColaAtmosphere } from './components/ColaAtmosphere';
import { Scene1Hero } from './scenes/Scene1Hero';
import { Scene2Bypass } from './scenes/Scene2Bypass';
import { Scene3Koliti } from './scenes/Scene3Koliti';
import { Scene4Features } from './scenes/Scene4Features';
import { Scene5ColaFinale } from './scenes/Scene5ColaFinale';

export const ColgramRelease: React.FC = () => {
  const frame = useCurrentFrame();

  // Gentle warm studio spotlight shift across the 30s film
  const spotX = interpolate(
    frame,
    [0, 450, 900, 1350, VIDEO_DURATION_FRAMES],
    [0.54, 0.34, 0.62, 0.50, 0.38],
    { extrapolateLeft: 'clamp', extrapolateRight: 'clamp' },
  );

  const smoothTiming = linearTiming({
    durationInFrames: TRANSITION_FRAMES,
    easing: EASE_IN_OUT_CUBIC,
  });

  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bgObsidian }}>
      {/* Studio 48kHz Stereo Soundtrack + Frame-Accurate UI & Cola Pour Foley SFX */}
      <Audio src={staticFile('colgram-soundtrack.wav')} volume={1} />

      {/* Continuous Iced Cola Espresso Studio Backdrop & Gentle Rising Micro-Bubbles */}
      <ColaAtmosphere spotX={spotX} spotY={0.46} />

      <TransitionSeries>
        {/* Scene 1 (0.0s – 4.5s): Cold Open — "Colgram" */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.hero}>
          <Scene1Hero />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={slide({ direction: 'from-right' })}
          timing={smoothTiming}
        />

        {/* Scene 2 (4.5s – 9.5s): Встроенный обход блокировок */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.bypass}>
          <Scene2Bypass />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={fade()}
          timing={smoothTiming}
        />

        {/* Scene 3 (9.5s – 14.5s): Колити ИИ в чатах */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.koliti}>
          <Scene3Koliti />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={fade()}
          timing={smoothTiming}
        />

        {/* Scene 4 (14.5s – 19.5s): Память, Призрак и Стиль */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.features}>
          <Scene4Features />
        </TransitionSeries.Sequence>

        <TransitionSeries.Transition
          presentation={fade()}
          timing={smoothTiming}
        />

        {/* Scene 5 (19.5s – 30.0s): Thematic Showstopper Finale — Pouring Cola Into Glass */}
        <TransitionSeries.Sequence durationInFrames={SCENE_DURATIONS.finale}>
          <Scene5ColaFinale />
        </TransitionSeries.Sequence>
      </TransitionSeries>
    </AbsoluteFill>
  );
};

/**
 * Crisp 1920x1080 Poster showcasing the Thematic Iced Cola Glass + Colgram Reveal
 * rendered at the exact peak settled finale state.
 */
export const ColgramPoster: React.FC = () => {
  return (
    <AbsoluteFill style={{ backgroundColor: COLORS.bgObsidian }}>
      <Sequence from={-552}>
        <ColaAtmosphere spotX={0.36} spotY={0.48} />
        <Scene5ColaFinale isPoster />
      </Sequence>
    </AbsoluteFill>
  );
};

import React from 'react';
import { Composition, Still } from 'remotion';
import { ColgramPoster, ColgramRelease } from './ColgramRelease';
import { VIDEO_DURATION_FRAMES, VIDEO_FPS, VIDEO_HEIGHT, VIDEO_WIDTH } from './theme';

export const RemotionRoot: React.FC = () => {
  return (
    <>
      <Composition
        id="ColgramRelease"
        component={ColgramRelease}
        durationInFrames={VIDEO_DURATION_FRAMES}
        fps={VIDEO_FPS}
        width={VIDEO_WIDTH}
        height={VIDEO_HEIGHT}
      />
      <Still
        id="ColgramPoster"
        component={ColgramPoster}
        width={VIDEO_WIDTH}
        height={VIDEO_HEIGHT}
      />
    </>
  );
};

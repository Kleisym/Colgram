import React, { useEffect, useState } from 'react';
import { Lottie, type LottieAnimationData } from '@remotion/lottie';
import {
  cancelRender,
  continueRender,
  delayRender,
  staticFile,
} from 'remotion';

export type TelegramLottieName =
  | 'utyan_streaming'
  | 'utyan_gigagroup'
  | 'utyan_schedule'
  | 'utyan_saved_messages'
  | 'utyan_cache'
  | 'utyan_gallery'
  | 'utyan_private'
  | 'utyan_passcode'
  | 'utyan_draw'
  | 'utyan_call'
  | 'utyan_newborn'
  | 'utyan_empty'
  | 'wallet_allset'
  | 'wallet_perfect'
  | 'wallet_congrats'
  | 'emoji_stars'
  | 'star_reaction'
  | 'star_reaction_effect2'
  | 'media_forbidden';

const jsonCache = new Map<string, LottieAnimationData>();
const inflightCache = new Map<string, Promise<LottieAnimationData>>();

function fetchLottie(name: TelegramLottieName): Promise<LottieAnimationData> {
  const cached = jsonCache.get(name);
  if (cached) {
    return Promise.resolve(cached);
  }
  const existing = inflightCache.get(name);
  if (existing) {
    return existing;
  }
  const promise = fetch(staticFile(`lottie/${name}.json`))
    .then((res) => {
      if (!res.ok) {
        throw new Error(`Failed to fetch lottie/${name}.json (${res.status})`);
      }
      return res.json();
    })
    .then((data: LottieAnimationData) => {
      jsonCache.set(name, data);
      inflightCache.delete(name);
      return data;
    });
  inflightCache.set(name, promise);
  return promise;
}

export interface TelegramLottieProps {
  name: TelegramLottieName;
  size?: number;
  loop?: boolean;
  playbackRate?: number;
  flipX?: boolean;
  style?: React.CSSProperties;
}

export const TelegramLottie: React.FC<TelegramLottieProps> = ({
  name,
  size = 200,
  loop = true,
  playbackRate = 0.9,
  flipX = false,
  style,
}) => {
  const [data, setData] = useState<LottieAnimationData | null>(
    () => jsonCache.get(name) ?? null,
  );

  useEffect(() => {
    if (jsonCache.has(name)) {
      setData(jsonCache.get(name)!);
      return;
    }
    const handle = delayRender(`Loading Telegram Lottie ${name}`);
    fetchLottie(name)
      .then((json) => {
        setData(json);
        continueRender(handle);
      })
      .catch((err) => {
        cancelRender(err);
      });
  }, [name]);

  if (!data) {
    return null;
  }

  return (
    <div
      style={{
        width: size,
        height: size,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        transform: flipX ? 'scaleX(-1)' : undefined,
        filter: 'drop-shadow(0 16px 32px rgba(0, 0, 0, 0.48))',
        ...style,
      }}
    >
      <Lottie
        animationData={data}
        loop={loop}
        playbackRate={playbackRate}
        style={{ width: '100%', height: '100%' }}
      />
    </div>
  );
};

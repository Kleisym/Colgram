import { loadFont as loadInter } from '@remotion/google-fonts/Inter';
import { loadFont as loadJetBrainsMono } from '@remotion/google-fonts/JetBrainsMono';

const interLoaded = loadInter('normal', {
  weights: ['400', '500', '600', '700', '800', '900'],
  subsets: ['cyrillic', 'latin'],
});

const monoLoaded = loadJetBrainsMono('normal', {
  weights: ['500', '700'],
  subsets: ['cyrillic', 'latin'],
});

export const FONT_SANS = `${interLoaded.fontFamily}, 'Segoe UI', system-ui, -apple-system, sans-serif`;
export const FONT_MONO = `${monoLoaded.fontFamily}, 'Cascadia Code', Consolas, monospace`;

export const VIDEO_WIDTH = 1920;
export const VIDEO_HEIGHT = 1080;
export const VIDEO_FPS = 60;
export const VIDEO_DURATION_FRAMES = 2160; // 36.0s at 60fps
export const TRANSITION_FRAMES = 18; // 0.3s

// Scene durations in TransitionSeries:
// 318 + 438 + 438 + 438 + 318 + 300 - (5 * 18) = 2250 - 90 = 2160 frames
export const SCENE_DURATIONS = {
  hero: 318, // 0s - 5s
  bypass: 438, // 5s - 12s
  koliti: 438, // 12s - 19s
  storage: 438, // 19s - 26s
  privacy: 318, // 26s - 31s
  outro: 300, // 31s - 36s
} as const;

export const COLORS = {
  bgDeep: '#05070C',
  bgSlate: '#0A101B',
  surfaceGlass: 'rgba(13, 20, 32, 0.72)',
  surfaceGlassBright: 'rgba(20, 31, 48, 0.80)',
  borderGlass: 'rgba(255, 255, 255, 0.12)',
  borderBright: 'rgba(255, 255, 255, 0.22)',
  emerald: '#10B981',
  emeraldBright: '#34D399',
  cyan: '#06B6D4',
  cyanBright: '#22D3EE',
  crimson: '#F43F5E',
  crimsonDeep: '#E11D48',
  amber: '#F59E0B',
  amberBright: '#FBBF24',
  violet: '#8B5CF6',
  violetBright: '#A78BFA',
  textPrimary: '#F8FAFC',
  textSecondary: '#CBD5E1',
  textMuted: '#94A3B8',
} as const;

export const SPRING_SNAPPY = { damping: 20, stiffness: 180, mass: 0.9 };
export const SPRING_SMOOTH = { damping: 24, stiffness: 120, mass: 1.0 };
export const SPRING_BOUNCY = { damping: 14, stiffness: 150, mass: 0.85 };

export interface ParticleSpec {
  id: number;
  x: number;
  y: number;
  size: number;
  speedY: number;
  driftX: number;
  phase: number;
  opacity: number;
  tint: string;
}

export function generateParticles(count: number, seed: number, tints: string[]): ParticleSpec[] {
  const result: ParticleSpec[] = [];
  let s = seed >>> 0;
  const next = () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
  for (let i = 0; i < count; i++) {
    result.push({
      id: i,
      x: next() * VIDEO_WIDTH,
      y: next() * VIDEO_HEIGHT,
      size: 2 + next() * 4.5,
      speedY: 0.35 + next() * 1.1,
      driftX: 14 + next() * 34,
      phase: next() * Math.PI * 2,
      opacity: 0.16 + next() * 0.45,
      tint: tints[i % tints.length],
    });
  }
  return result;
}

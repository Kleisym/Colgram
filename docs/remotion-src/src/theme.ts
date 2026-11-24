import { Easing, interpolate } from 'remotion';
import { loadFont as loadInter } from '@remotion/google-fonts/Inter';

const interLoaded = loadInter('normal', {
  weights: ['400', '500', '600', '700', '800'],
  subsets: ['cyrillic', 'latin'],
});

export const FONT_SANS = `${interLoaded.fontFamily}, -apple-system, BlinkMacSystemFont, 'SF Pro Display', 'Segoe UI', system-ui, sans-serif`;

export const VIDEO_WIDTH = 1920;
export const VIDEO_HEIGHT = 1080;
export const VIDEO_FPS = 60;
export const VIDEO_DURATION_FRAMES = 1800; // 30.00s exact at 60fps
export const TRANSITION_FRAMES = 24; // 0.40s buttery smooth transition

// 5 scenes: 282 + 324 + 324 + 324 + 642 = 1896; minus 4 * 24 = 1800 frames (30.00s exact!)
// Scene mid-transition timestamps:
//   Scene 1 (Cold Open):    0.0s – 4.5s  (frames 0..282)
//   Scene 2 (Bypass):       4.5s – 9.5s  (frames 258..582)
//   Scene 3 (Koliti AI):    9.5s – 14.5s (frames 558..882)
//   Scene 4 (Features):    14.5s – 19.5s (frames 858..1182)
//   Scene 5 (Cola Finale): 19.5s – 30.0s (frames 1158..1800)
export const SCENE_DURATIONS = {
  hero: 282,
  bypass: 324,
  koliti: 324,
  features: 324,
  finale: 642,
} as const;

export const COLORS = {
  // Cohesive "Iced Cola" Minimalist Studio Palette
  bgObsidian: '#0D0908',
  bgEspresso: '#180E0C',
  bgMahogany: '#24130F',

  // Signature Cola & Amber Accents
  colaRed: '#E53946',
  colaRuby: '#9B1C24',
  colaDark: '#230805',
  caramelAmber: '#F59E0B',
  amberWarm: '#D97706',
  champagne: '#FDE6C4',
  foamCream: '#F4DFC4',

  // Status Mint (used solely for live connection dot)
  mintConnected: '#34D399',

  // Frosted Ice Glass Surfaces
  glassFill: 'rgba(255, 255, 255, 0.055)',
  glassFillSubtle: 'rgba(255, 248, 244, 0.038)',
  glassBorder: 'rgba(255, 248, 244, 0.12)',
  glassHighlight: 'rgba(255, 255, 255, 0.18)',

  // Clean Off-White Editorial Typography
  textPrimary: '#FAF7F5',
  textSecondary: 'rgba(250, 247, 245, 0.70)',
  textMuted: 'rgba(250, 247, 245, 0.46)',
} as const;

// Smooth Apple-Keynote easing curves (zero harsh bounce or slam)
export const EASE_OUT_CUBIC = Easing.out(Easing.cubic);
export const EASE_IN_OUT_CUBIC = Easing.inOut(Easing.cubic);
export const EASE_APPLE = Easing.bezier(0.22, 1, 0.36, 1);

export function createRng(seed: number): () => number {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

export function revealMotion(
  frame: number,
  startFrame: number,
  durationFrames = 36,
  distanceY = 26,
): { opacity: number; translateY: number; progress: number } {
  const progress = interpolate(frame, [startFrame, startFrame + durationFrames], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });
  return {
    progress,
    opacity: interpolate(progress, [0, 0.65, 1], [0, 0.92, 1]),
    translateY: (1 - progress) * distanceY,
  };
}

import React, { useMemo } from 'react';
import { Easing, interpolate, useCurrentFrame } from 'remotion';
import { createRng, EASE_APPLE, EASE_OUT_CUBIC } from '../theme';

// Crystal tumbler geometry in SVG local space (viewBox="0 0 1180 1000")
const CX = 390;
const RIM_Y = 236;
const RIM_RY = 23;
const OUTER_TOP_HALF = 166; // x: 224..556
const BASE_Y = 768;
const BASE_RY = 22;
const OUTER_BOT_HALF = 128; // x: 262..518

const INNER_TOP_Y = 240;
const INNER_TOP_HALF = 152; // x: 238..542
const INNER_BOT_Y = 716; // thick 52px refractive crystal glass base below this
const INNER_BOT_HALF = 116; // x: 274..506
const INNER_BOT_RY = 18;

const POUR_START = 102; // 21.0s global (1158 + 102 = 1260)
const POUR_HIT_BOTTOM = 118; // stream tip touches bottom & liquid begins rising
const POUR_TAPER_START = 348; // stream tail detaches from top-right
const POUR_END = 374; // 25.5s global (1158 + 372 = 1530)

function innerHalfWidthAt(y: number): number {
  const t = Math.max(0, Math.min(1, (y - INNER_TOP_Y) / (INNER_BOT_Y - INNER_TOP_Y)));
  return INNER_TOP_HALF + (INNER_BOT_HALF - INNER_TOP_HALF) * t;
}

function outerHalfWidthAt(y: number): number {
  const t = Math.max(0, Math.min(1, (y - RIM_Y) / (BASE_Y - RIM_Y)));
  return OUTER_TOP_HALF + (OUTER_BOT_HALF - OUTER_TOP_HALF) * t;
}

function cubicBezier(
  t: number,
  p0: [number, number],
  p1: [number, number],
  p2: [number, number],
  p3: [number, number],
): [number, number] {
  const u = 1 - t;
  const tt = t * t;
  const uu = u * u;
  const uuu = uu * u;
  const ttt = tt * t;
  return [
    uuu * p0[0] + 3 * uu * t * p1[0] + 3 * u * tt * p2[0] + ttt * p3[0],
    uuu * p0[1] + 3 * uu * t * p1[1] + 3 * u * tt * p2[1] + ttt * p3[1],
  ];
}

function cubicBezierTangent(
  t: number,
  p0: [number, number],
  p1: [number, number],
  p2: [number, number],
  p3: [number, number],
): [number, number] {
  const u = 1 - t;
  return [
    3 * u * u * (p1[0] - p0[0]) +
      6 * u * t * (p2[0] - p1[0]) +
      3 * t * t * (p3[0] - p2[0]),
    3 * u * u * (p1[1] - p0[1]) +
      6 * u * t * (p2[1] - p1[1]) +
      3 * t * t * (p3[1] - p2[1]),
  ];
}

interface FizzBubble {
  id: number;
  xFrac: number;
  phaseY: number;
  radius: number;
  speed: number;
  wobbleAmp: number;
  wobbleFreq: number;
  wobblePhase: number;
  opacity: number;
}

interface FoamBubble {
  id: number;
  xFrac: number;
  yOffsetFrac: number;
  rx: number;
  ry: number;
  shade: number;
  opacity: number;
  phase: number;
}

interface SplashDrop {
  id: number;
  period: number;
  offset: number;
  vx: number;
  vy: number;
  radius: number;
}

interface CondensationDrop {
  id: number;
  xFrac: number;
  y: number;
  rx: number;
  ry: number;
  delay: number;
  creepSpeed: number;
}

interface IceCubeSpec {
  id: number;
  size: number;
  restX: number;
  restY: number;
  floatX: number;
  floatOffset: number;
  restAngle: number;
  floatAngle: number;
  phase: number;
  depthOpacity: number;
}

// 3 translucent crystal ice cubes spread naturally across the full glass width
const ICE_CUBES: IceCubeSpec[] = [
  {
    id: 0,
    size: 80,
    restX: 326,
    restY: 668,
    floatX: 310,
    floatOffset: 24,
    restAngle: -20,
    floatAngle: -16,
    phase: 0.4,
    depthOpacity: 0.9,
  },
  {
    id: 1,
    size: 78,
    restX: 452,
    restY: 662,
    floatX: 466,
    floatOffset: 28,
    restAngle: 18,
    floatAngle: 20,
    phase: 2.3,
    depthOpacity: 0.94,
  },
  {
    id: 2,
    size: 82,
    restX: 390,
    restY: 602,
    floatX: 388,
    floatOffset: 10,
    restAngle: 8,
    floatAngle: -7,
    phase: 4.5,
    depthOpacity: 1.0,
  },
];

const IceCubeGraphic: React.FC<{
  size: number;
  submergedRatio: number;
  id: number;
  depthOpacity: number;
}> = ({ size, submergedRatio, id, depthOpacity }) => {
  const half = size / 2;
  const r = size * 0.22;
  const inset = size * 0.14;
  const innerSize = size - inset * 2;
  const warmMix = Math.max(0, Math.min(1, submergedRatio));

  return (
    <g opacity={depthOpacity}>
      {/* Soft ambient shadow beneath cube */}
      <rect
        x={-half + 3}
        y={-half + 5}
        width={size}
        height={size}
        rx={r}
        fill={`rgba(18, 4, 2, ${0.14 + warmMix * 0.24})`}
      />

      {/* Main Translucent Frosted Crystal Body */}
      <rect
        x={-half}
        y={-half}
        width={size}
        height={size}
        rx={r}
        fill={`url(#iceBodyGrad-${id})`}
        stroke={`rgba(255, 250, 245, ${0.52 - warmMix * 0.08})`}
        strokeWidth="1.5"
      />

      {/* Warm Backlit Cola-Amber Refraction inside Submerged Ice */}
      {warmMix > 0.02 && (
        <rect
          x={-half + 2}
          y={-half + 2}
          width={size - 4}
          height={size - 4}
          rx={r - 1.5}
          fill={`url(#iceColaTintGrad-${id})`}
          opacity={warmMix * 0.58}
        />
      )}

      {/* Inner Bevelled Crystal Facet */}
      <rect
        x={-innerSize / 2}
        y={-innerSize / 2}
        width={innerSize}
        height={innerSize}
        rx={r * 0.62}
        fill="rgba(255, 255, 255, 0.09)"
        stroke="rgba(255, 255, 255, 0.30)"
        strokeWidth="1.15"
      />

      {/* Diagonal Internal Frost Refraction Plane */}
      <path
        d={`M ${-half + r * 0.8} ${-half + 5} L ${half - r * 0.4} ${-half + 5} L ${-half + 5} ${half - r * 0.5} Z`}
        fill="rgba(255, 255, 255, 0.18)"
      />

      {/* Glossy Top-Left Specular Edge Catchlight */}
      <path
        d={`M ${-half + r} ${-half + 4.5} L ${half - r * 1.1} ${-half + 4.5}`}
        stroke="rgba(255, 255, 255, 0.88)"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
      <path
        d={`M ${-half + 4.5} ${-half + r} L ${-half + 4.5} ${half - r * 1.25}`}
        stroke="rgba(255, 255, 255, 0.54)"
        strokeWidth="1.8"
        strokeLinecap="round"
      />

      {/* Warm Backlit Caramel Bottom Rim Catchlight */}
      <path
        d={`M ${-half + r * 1.15} ${half - 4.5} L ${half - r} ${half - 4.5}`}
        stroke={
          warmMix > 0.15
            ? `rgba(245, 158, 11, ${0.32 + warmMix * 0.46})`
            : 'rgba(255, 255, 255, 0.28)'
        }
        strokeWidth="2.0"
        strokeLinecap="round"
      />
    </g>
  );
};

interface ColaGlassPourProps {
  overrideFrame?: number;
}

export const ColaGlassPour: React.FC<ColaGlassPourProps> = ({ overrideFrame }) => {
  const rawFrame = useCurrentFrame();
  const frame = overrideFrame ?? rawFrame;

  const fizzBubbles = useMemo<FizzBubble[]>(() => {
    const rand = createRng(77421);
    return Array.from({ length: 68 }, (_, id) => ({
      id,
      xFrac: (rand() * 2 - 1) * 0.87,
      phaseY: rand(),
      radius: 1.4 + rand() * 3.1,
      speed: 0.0045 + rand() * 0.0085,
      wobbleAmp: 2.2 + rand() * 5.2,
      wobbleFreq: 0.05 + rand() * 0.06,
      wobblePhase: rand() * Math.PI * 2,
      opacity: 0.34 + rand() * 0.48,
    }));
  }, []);

  // Organic multi-scale micro-foam bubbles (tiny + medium elliptical puffs for real creamy froth!)
  const foamBubbles = useMemo<FoamBubble[]>(() => {
    const rand = createRng(99123);
    return Array.from({ length: 64 }, (_, id) => {
      const isTiny = id >= 28;
      const baseR = isTiny ? 2.4 + rand() * 4.2 : 5.5 + rand() * 8.5;
      return {
        id,
        xFrac: (rand() * 2 - 1) * 0.94,
        yOffsetFrac: rand(),
        rx: baseR * (1.0 + rand() * 0.32),
        ry: baseR * (0.72 + rand() * 0.25),
        shade: rand(),
        opacity: isTiny ? 0.52 + rand() * 0.38 : 0.62 + rand() * 0.28,
        phase: rand() * Math.PI * 2,
      };
    });
  }, []);

  const splashDrops = useMemo<SplashDrop[]>(() => {
    const rand = createRng(44129);
    return Array.from({ length: 18 }, (_, id) => ({
      id,
      period: 28 + Math.floor(rand() * 22),
      offset: Math.floor(rand() * 36),
      vx: (1.2 + rand() * 3.2) * (id % 2 === 0 ? 1 : -1),
      vy: -(5.4 + rand() * 4.4),
      radius: 2.0 + rand() * 2.8,
    }));
  }, []);

  const condensationDrops = useMemo<CondensationDrop[]>(() => {
    const rand = createRng(55319);
    return Array.from({ length: 36 }, (_, id) => ({
      id,
      xFrac: (rand() * 2 - 1) * 0.85,
      y: 368 + rand() * 330,
      rx: 2.1 + rand() * 3.2,
      ry: 2.8 + rand() * 4.8,
      delay: 205 + rand() * 165,
      creepSpeed: id < 5 ? 0.06 + rand() * 0.045 : 0.008 * rand(),
    }));
  }, []);

  // 1. Glass smooth entrance (19.3s – 20.8s -> local 6..78)
  const glassEnter = interpolate(frame, [6, 78], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });
  const glassTranslateY = (1 - glassEnter) * 58;
  const glassOpacity = interpolate(glassEnter, [0, 0.5, 1], [0, 0.9, 1]);
  const glassScale = interpolate(glassEnter, [0, 1], [0.95, 1]);

  // 2. Cola Fill Progress (0% -> 78% from localFrame 118..374)
  const fillProgress = interpolate(frame, [POUR_HIT_BOTTOM, POUR_END], [0, 0.78], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.bezier(0.3, 0.05, 0.32, 1),
  });
  const surfaceY = INNER_BOT_Y - fillProgress * (INNER_BOT_Y - INNER_TOP_Y);

  // 3. Pour Stream Head & Tail along smooth parabolic gravity curve starting well off-frame!
  const streamHead = interpolate(frame, [POUR_START, POUR_HIT_BOTTOM], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.in(Easing.quad),
  });
  const streamTail = interpolate(frame, [POUR_TAPER_START, POUR_END], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: Easing.in(Easing.quad),
  });
  const isStreamVisible = streamHead > 0.005 && streamTail < 0.995;

  const pourRampIn = interpolate(frame, [POUR_HIT_BOTTOM, POUR_HIT_BOTTOM + 24], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const pourDecayOut = interpolate(frame, [POUR_TAPER_START + 8, POUR_END + 68], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const pourEnergy = pourRampIn * pourDecayOut;
  const activeImpact =
    streamHead >= 0.96 && streamTail < 0.94
      ? interpolate(streamTail, [0.75, 0.94], [1, 0], {
          extrapolateLeft: 'clamp',
          extrapolateRight: 'clamp',
        })
      : 0;

  // 4. Dual-Frequency Wave Meniscus Function
  const waveAmp = fillProgress > 0.01 ? 1.5 + pourEnergy * 6.8 : 0;
  const waveY = (x: number, phaseOffset = 0): number => {
    const nx = (x - CX) / 140;
    const w1 = Math.sin(nx * 3.7 + frame * 0.145 + phaseOffset) * waveAmp * 0.64;
    const w2 =
      Math.sin(nx * 7.4 - frame * 0.235 + phaseOffset * 1.45) * waveAmp * 0.36;
    const distFromStream = (x - 392) / 34;
    const impactDip =
      activeImpact *
      Math.exp(-distFromStream * distFromStream) *
      (4.2 + Math.sin(frame * 0.36) * 2.2);
    return surfaceY + w1 + w2 + impactDip;
  };

  const liquidPaths = useMemo(() => {
    if (fillProgress <= 0.003) {
      return { frontBody: '', backWave: '', surfaceHighlight: '' };
    }
    const steps = 38;
    const leftEdge = CX - innerHalfWidthAt(surfaceY) - 10;
    const rightEdge = CX + innerHalfWidthAt(surfaceY) + 10;

    const frontPts: [number, number][] = [];
    const backPts: [number, number][] = [];

    for (let i = 0; i <= steps; i++) {
      const t = i / steps;
      const x = leftEdge + (rightEdge - leftEdge) * t;
      frontPts.push([x, waveY(x, 0)]);
      backPts.push([x, waveY(x, 2.15) - 7.5]);
    }

    const frontTop = frontPts
      .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
      .join(' ');
    const frontBody = `${frontTop} L ${rightEdge.toFixed(1)} 755 L ${leftEdge.toFixed(1)} 755 Z`;

    const backTop = backPts
      .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
      .join(' ');
    const backWave = `${backTop} L ${rightEdge.toFixed(1)} ${(surfaceY + 40).toFixed(1)} L ${leftEdge.toFixed(1)} ${(surfaceY + 40).toFixed(1)} Z`;

    return { frontBody, backWave, surfaceHighlight: frontTop };
  }, [fillProgress, frame, surfaceY, activeImpact, pourEnergy]);

  // 5. Creamy Frothy Cola Foam Head (builds lushly during pour & gently settles!)
  const foamGrowth = interpolate(frame, [POUR_HIT_BOTTOM + 6, POUR_TAPER_START], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const foamSettle = interpolate(frame, [POUR_END - 8, POUR_END + 165], [1, 0.48], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const foamThickness = foamGrowth * 42 * foamSettle;

  const foamBandPath = useMemo(() => {
    if (foamThickness < 1.2) return '';
    const steps = 36;
    const leftEdge = CX - innerHalfWidthAt(surfaceY - foamThickness) - 8;
    const rightEdge = CX + innerHalfWidthAt(surfaceY - foamThickness) + 8;

    const topPts: string[] = [];
    const botPts: string[] = [];

    for (let i = 0; i <= steps; i++) {
      const t = i / steps;
      const x = leftEdge + (rightEdge - leftEdge) * t;
      // Natural slightly higher meniscus cling near glass edges + lively impact mound in center
      const edgeMeniscus = Math.pow(Math.abs(t * 2 - 1), 3) * 4.5;
      const centerBump =
        Math.exp(-Math.pow((x - 392) / 62, 2)) * (5 + activeImpact * 12);
      const frothWave =
        Math.sin(t * Math.PI * 7 + frame * 0.08) * (1.8 + pourEnergy * 2.5) +
        Math.cos(t * Math.PI * 13 - frame * 0.11) * 1.4;
      const wy = waveY(x, 0);
      const topY = wy - foamThickness - centerBump - edgeMeniscus + frothWave;
      const botY = wy + 13 + Math.sin(t * Math.PI * 5 + 1.1) * 2.5;
      topPts.push(`${i === 0 ? 'M' : 'L'} ${x.toFixed(1)} ${topY.toFixed(1)}`);
      botPts.unshift(`L ${x.toFixed(1)} ${botY.toFixed(1)}`);
    }
    return `${topPts.join(' ')} ${botPts.join(' ')} Z`;
  }, [foamThickness, frame, surfaceY, activeImpact, pourEnergy]);

  // 6. Silky Arched Cola Pour Stream Ribbon (starts well off top-right viewport!)
  const streamGeometry = useMemo(() => {
    if (!isStreamVisible) return null;

    // p0 at y=-140 sits well above the 1080p viewport top edge (zero flat cropped top!)
    const p0: [number, number] = [1060, -140];
    const p1: [number, number] = [565, -45];
    const p2: [number, number] = [414, 118];
    const p3: [number, number] = [392, Math.min(INNER_BOT_Y - 10, surfaceY + 4)];

    const samples = 48;
    const leftOuter: [number, number][] = [];
    const rightOuter: [number, number][] = [];
    const leftCore: [number, number][] = [];
    const rightCore: [number, number][] = [];
    const specPts: [number, number][] = [];

    for (let i = 0; i <= samples; i++) {
      const frac = i / samples;
      const t = streamTail + (streamHead - streamTail) * frac;
      const [px, py] = cubicBezier(t, p0, p1, p2, p3);
      const [tx, ty] = cubicBezierTangent(t, p0, p1, p2, p3);
      const len = Math.hypot(tx, ty) || 1;
      const nx = -ty / len;
      const ny = tx / len;

      const baseHalfW = 16.0 - t * 6.4;
      const ripple = Math.sin(t * 24 - frame * 0.42) * 1.1;
      // Smooth rounded plunge taper at the impact tip AND smooth tail taper on cutoff
      const tipTaper =
        streamHead < 0.99
          ? Math.sin(Math.min(1, (1 - frac) * 6) * (Math.PI / 2))
          : frac > 0.94
            ? 0.55 + 0.45 * ((1 - frac) / 0.06)
            : 1;
      const tailTaper =
        streamTail > 0.01 ? Math.sin(Math.min(1, frac * 5.5) * (Math.PI / 2)) : 1;
      const halfW = Math.max(1.4, (baseHalfW + ripple) * tipTaper * tailTaper);
      const coreHalfW = halfW * 0.60;

      leftOuter.push([px + nx * halfW, py + ny * halfW]);
      rightOuter.push([px - nx * halfW, py - ny * halfW]);
      leftCore.push([px + nx * coreHalfW, py + ny * coreHalfW]);
      rightCore.push([px - nx * coreHalfW, py - ny * coreHalfW]);
      specPts.push([px + nx * (halfW * 0.42), py + ny * (halfW * 0.42)]);
    }

    const buildRibbon = (l: [number, number][], r: [number, number][]) => {
      const fwd = l
        .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
        .join(' ');
      const bwd = [...r]
        .reverse()
        .map((p) => `L ${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
        .join(' ');
      return `${fwd} ${bwd} Z`;
    };

    const specPath = specPts
      .map((p, idx) => `${idx === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`)
      .join(' ');

    const tailPos = cubicBezier(streamTail, p0, p1, p2, p3);

    return {
      outerPath: buildRibbon(leftOuter, rightOuter),
      corePath: buildRibbon(leftCore, rightCore),
      specPath,
      tailPos,
    };
  }, [isStreamVisible, streamHead, streamTail, surfaceY, frame]);

  // 7. Buoyant Ice Cube Physics (ALL 3 visible, naturally rising & rocking!)
  const computedCubes = ICE_CUBES.map((cube) => {
    const floatTargetY = surfaceY + cube.floatOffset;
    const liftDistance = Math.max(0, cube.restY - floatTargetY);
    const buoyancy = Math.max(0, Math.min(1, liftDistance / 36));
    const smoothBuoy = buoyancy * buoyancy * (3 - 2 * buoyancy);

    const bobAmp = smoothBuoy * (2.6 + pourEnergy * 5.8);
    const bobY = Math.sin(frame * 0.085 + cube.phase) * bobAmp;
    const rawY = cube.restY - liftDistance * smoothBuoy + bobY;
    const y = Math.min(cube.restY, Math.max(INNER_TOP_Y + 46, rawY));

    const driftX =
      smoothBuoy * Math.sin(frame * 0.048 + cube.phase) * (2.6 + pourEnergy * 4.8);
    const maxSafeHalf = innerHalfWidthAt(y) - cube.size * 0.54;
    const rawX = cube.restX + (cube.floatX - cube.restX) * smoothBuoy + driftX;
    const x = Math.max(CX - maxSafeHalf, Math.min(CX + maxSafeHalf, rawX));

    const rockAmp = smoothBuoy * (2.4 + pourEnergy * 5.2);
    const angle =
      cube.restAngle +
      (cube.floatAngle - cube.restAngle) * smoothBuoy +
      Math.sin(frame * 0.068 + cube.phase * 1.3) * rockAmp;

    const submergedRatio = Math.max(
      0,
      Math.min(1, (y + cube.size * 0.35 - surfaceY) / (cube.size * 0.72)),
    );

    return { ...cube, x, y, angle, submergedRatio };
  });

  // 8. Glass Contour Paths
  const innerCavityPath = `
    M ${CX - INNER_TOP_HALF} ${INNER_TOP_Y}
    L ${CX + INNER_TOP_HALF} ${INNER_TOP_Y}
    L ${CX + INNER_BOT_HALF} ${INNER_BOT_Y}
    Q ${CX} ${INNER_BOT_Y + INNER_BOT_RY * 1.45} ${CX - INNER_BOT_HALF} ${INNER_BOT_Y}
    Z
  `;

  const outerGlassPath = `
    M ${CX - OUTER_TOP_HALF} ${RIM_Y}
    L ${CX - OUTER_BOT_HALF} ${BASE_Y}
    Q ${CX} ${BASE_Y + BASE_RY * 1.55} ${CX + OUTER_BOT_HALF} ${BASE_Y}
    L ${CX + OUTER_TOP_HALF} ${RIM_Y}
    Z
  `;

  const crystalBasePath = `
    M ${CX - INNER_BOT_HALF - 4} ${INNER_BOT_Y - 2}
    Q ${CX} ${INNER_BOT_Y + INNER_BOT_RY * 1.45} ${CX + INNER_BOT_HALF + 4} ${INNER_BOT_Y - 2}
    L ${CX + OUTER_BOT_HALF} ${BASE_Y}
    Q ${CX} ${BASE_Y + BASE_RY * 1.55} ${CX - OUTER_BOT_HALF} ${BASE_Y}
    Z
  `;

  return (
    <svg
      width={1180}
      height={1000}
      viewBox="0 0 1180 1000"
      style={{
        overflow: 'visible',
        pointerEvents: 'none',
      }}
    >
      <defs>
        <clipPath id="colaInnerClip">
          <path d={innerCavityPath} />
        </clipPath>

        <clipPath id="colaLiquidOnlyClip">
          {liquidPaths.frontBody ? (
            <path d={liquidPaths.frontBody} />
          ) : (
            <rect x="0" y="0" width="0" height="0" />
          )}
        </clipPath>

        <radialGradient id="glassBacklightGrad" cx="50%" cy="54%" r="50%">
          <stop offset="0%" stopColor="#D97706" stopOpacity={0.14 + fillProgress * 0.25} />
          <stop offset="48%" stopColor="#B91C1C" stopOpacity={0.08 + fillProgress * 0.18} />
          <stop offset="100%" stopColor="#0D0908" stopOpacity="0" />
        </radialGradient>

        <radialGradient id="tableCausticGrad" cx="50%" cy="50%" r="50%">
          <stop
            offset="0%"
            stopColor={fillProgress > 0.08 ? '#F59E0B' : '#FFF7ED'}
            stopOpacity={0.12 + fillProgress * 0.36}
          />
          <stop offset="55%" stopColor="#B91C1C" stopOpacity={0.05 + fillProgress * 0.22} />
          <stop offset="100%" stopColor="#000000" stopOpacity="0" />
        </radialGradient>

        <linearGradient id="glassBackWallGrad" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="rgba(255, 248, 244, 0.15)" />
          <stop offset="16%" stopColor="rgba(255, 248, 244, 0.04)" />
          <stop offset="50%" stopColor="rgba(255, 248, 244, 0.015)" />
          <stop offset="84%" stopColor="rgba(255, 248, 244, 0.045)" />
          <stop offset="100%" stopColor="rgba(255, 248, 244, 0.14)" />
        </linearGradient>

        <linearGradient id="colaLiquidHorizGrad" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#C2410C" stopOpacity="0.96" />
          <stop offset="13%" stopColor="#6D160C" stopOpacity="0.98" />
          <stop offset="34%" stopColor="#2A0805" stopOpacity="0.99" />
          <stop offset="52%" stopColor="#1B0503" stopOpacity="0.99" />
          <stop offset="72%" stopColor="#340B07" stopOpacity="0.98" />
          <stop offset="89%" stopColor="#8A2010" stopOpacity="0.97" />
          <stop offset="100%" stopColor="#D97706" stopOpacity="0.95" />
        </linearGradient>

        <linearGradient id="colaLiquidVertGlow" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" stopColor="#F59E0B" stopOpacity="0.44" />
          <stop offset="18%" stopColor="#B91C1C" stopOpacity="0.22" />
          <stop offset="68%" stopColor="#1C0503" stopOpacity="0.08" />
          <stop offset="100%" stopColor="#D97706" stopOpacity="0.38" />
        </linearGradient>

        <linearGradient id="streamOuterGrad" x1="100%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" stopColor="#D97706" stopOpacity="0.92" />
          <stop offset="45%" stopColor="#9A2012" stopOpacity="0.96" />
          <stop offset="100%" stopColor="#C2410C" stopOpacity="0.96" />
        </linearGradient>
        <linearGradient id="streamCoreGrad" x1="100%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" stopColor="#450F08" stopOpacity="0.96" />
          <stop offset="55%" stopColor="#230604" stopOpacity="0.98" />
          <stop offset="100%" stopColor="#3B0B06" stopOpacity="0.95" />
        </linearGradient>

        <linearGradient id="colaFoamGrad" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop offset="0%" stopColor="#FCF4EA" stopOpacity="0.97" />
          <stop offset="38%" stopColor="#EBCAA2" stopOpacity="0.94" />
          <stop offset="74%" stopColor="#B86F3B" stopOpacity="0.78" />
          <stop offset="100%" stopColor="#5C1F0E" stopOpacity="0.0" />
        </linearGradient>

        <linearGradient id="crystalBaseGrad" x1="0%" y1="0%" x2="0%" y2="100%">
          <stop
            offset="0%"
            stopColor={fillProgress > 0.05 ? '#B45309' : '#FFFFFF'}
            stopOpacity={0.14 + fillProgress * 0.24}
          />
          <stop offset="48%" stopColor="rgba(255, 248, 244, 0.15)" />
          <stop offset="100%" stopColor="rgba(255, 248, 244, 0.28)" />
        </linearGradient>

        <linearGradient id="frontLeftReflection" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.30" />
          <stop offset="38%" stopColor="#FFF7ED" stopOpacity="0.12" />
          <stop offset="100%" stopColor="#FFFFFF" stopOpacity="0.0" />
        </linearGradient>
        <linearGradient id="frontRightReflection" x1="0%" y1="0%" x2="100%" y2="0%">
          <stop offset="0%" stopColor="#FFFFFF" stopOpacity="0.0" />
          <stop offset="68%" stopColor="#FDE6C4" stopOpacity="0.14" />
          <stop offset="100%" stopColor="#FFFFFF" stopOpacity="0.28" />
        </linearGradient>

        {[0, 1, 2].map((id) => (
          <React.Fragment key={id}>
            <linearGradient id={`iceBodyGrad-${id}`} x1="10%" y1="5%" x2="90%" y2="95%">
              <stop offset="0%" stopColor="rgba(255, 255, 255, 0.48)" />
              <stop offset="45%" stopColor="rgba(232, 242, 252, 0.22)" />
              <stop offset="100%" stopColor="rgba(195, 212, 230, 0.14)" />
            </linearGradient>
            <linearGradient id={`iceColaTintGrad-${id}`} x1="15%" y1="0%" x2="85%" y2="100%">
              <stop offset="0%" stopColor="#F59E0B" stopOpacity="0.28" />
              <stop offset="55%" stopColor="#9A2A12" stopOpacity="0.48" />
              <stop offset="100%" stopColor="#3B0B05" stopOpacity="0.58" />
            </linearGradient>
          </React.Fragment>
        ))}

        <filter id="softGlowFilter" x="-30%" y="-30%" width="160%" height="160%">
          <feGaussianBlur stdDeviation="16" />
        </filter>
        <filter id="foamSoftBlur" x="-15%" y="-30%" width="130%" height="160%">
          <feGaussianBlur stdDeviation="2.4" />
        </filter>
        <filter id="baseRefractBlur" x="-20%" y="-40%" width="140%" height="180%">
          <feGaussianBlur stdDeviation="2.8" />
        </filter>
      </defs>

      <g
        opacity={glassOpacity}
        transform={`translate(0, ${glassTranslateY.toFixed(1)}) translate(${CX}, ${BASE_Y}) scale(${glassScale.toFixed(4)}) translate(${-CX}, ${-BASE_Y})`}
      >
        {/* LAYER 1: Studio Backlight Aura & Grounded Obsidian Table Reflection */}
        <ellipse
          cx={CX}
          cy={520}
          rx={255}
          ry={310}
          fill="url(#glassBacklightGrad)"
          filter="url(#softGlowFilter)"
        />
        <ellipse
          cx={CX}
          cy={BASE_Y + 26}
          rx={158}
          ry={28}
          fill="rgba(0, 0, 0, 0.68)"
          filter="url(#softGlowFilter)"
        />
        <ellipse
          cx={CX}
          cy={BASE_Y + 24}
          rx={122}
          ry={20}
          fill="url(#tableCausticGrad)"
        />

        {/* LAYER 2: Back Glass Wall & Back Rim */}
        <path d={outerGlassPath} fill="url(#glassBackWallGrad)" />
        <path
          d={`M ${CX - OUTER_TOP_HALF} ${RIM_Y} A ${OUTER_TOP_HALF} ${RIM_RY} 0 0 1 ${CX + OUTER_TOP_HALF} ${RIM_Y}`}
          fill="none"
          stroke="rgba(255, 248, 244, 0.26)"
          strokeWidth="2"
        />
        <path
          d={`M ${CX - INNER_TOP_HALF} ${INNER_TOP_Y} L ${CX - INNER_BOT_HALF} ${INNER_BOT_Y}`}
          stroke="rgba(255, 248, 244, 0.1)"
          strokeWidth="1.2"
        />
        <path
          d={`M ${CX + INNER_TOP_HALF} ${INNER_TOP_Y} L ${CX + INNER_BOT_HALF} ${INNER_BOT_Y}`}
          stroke="rgba(255, 248, 244, 0.1)"
          strokeWidth="1.2"
        />

        {/* LAYER 3: Clipped Liquid Interior (Back Meniscus -> Cola -> ALL 3 Buoyant Ice Cubes -> Fizz -> Organic Foam) */}
        <g clipPath="url(#colaInnerClip)">
          {/* 3a. Back Meniscus Wave Crest */}
          {liquidPaths.backWave && (
            <path d={liquidPaths.backWave} fill="#5C160C" opacity={0.86} />
          )}

          {/* 3b. Main Backlit Sparkling Cola Liquid Body */}
          {liquidPaths.frontBody && (
            <>
              <path d={liquidPaths.frontBody} fill="url(#colaLiquidHorizGrad)" />
              <path d={liquidPaths.frontBody} fill="url(#colaLiquidVertGlow)" />
              <ellipse
                cx={CX}
                cy={surfaceY + 28}
                rx={innerHalfWidthAt(surfaceY) * 0.85}
                ry={32}
                fill="#F59E0B"
                opacity={0.14}
              />
            </>
          )}

          {/* 3c. ALL 3 Buoyant Translucent Ice Cubes (always visible & bobbing organically!) */}
          {computedCubes.map((cube) => (
            <g
              key={cube.id}
              transform={`translate(${cube.x.toFixed(1)}, ${cube.y.toFixed(1)}) rotate(${cube.angle.toFixed(2)})`}
            >
              <IceCubeGraphic
                size={cube.size}
                submergedRatio={cube.submergedRatio}
                id={cube.id}
                depthOpacity={cube.depthOpacity}
              />
            </g>
          ))}

          {/* 3d. Streaming Carbonation Fizz Bubbles inside Cola Liquid */}
          {fillProgress > 0.04 && (
            <g clipPath="url(#colaLiquidOnlyClip)">
              {fizzBubbles.map((b) => {
                const liquidSpan = Math.max(24, INNER_BOT_Y - surfaceY);
                const progress = (b.phaseY + frame * b.speed * (1 + pourEnergy * 0.9)) % 1;
                const by = INNER_BOT_Y - progress * liquidSpan;
                const halfW = innerHalfWidthAt(by) - 8;
                const bx =
                  CX +
                  b.xFrac * halfW +
                  Math.sin(frame * b.wobbleFreq + b.wobblePhase) * b.wobbleAmp;

                const fade =
                  progress < 0.08
                    ? progress / 0.08
                    : progress > 0.9
                      ? (1 - progress) / 0.1
                      : 1;
                const op = b.opacity * fade * (0.72 + pourEnergy * 0.28);

                return (
                  <g key={b.id} opacity={op}>
                    <circle
                      cx={bx}
                      cy={by}
                      r={b.radius}
                      fill="rgba(253, 230, 196, 0.22)"
                      stroke="rgba(255, 234, 200, 0.74)"
                      strokeWidth={0.8}
                    />
                    {b.radius > 2.2 && (
                      <circle
                        cx={bx - b.radius * 0.32}
                        cy={by - b.radius * 0.32}
                        r={b.radius * 0.28}
                        fill="#FFFFFF"
                        opacity={0.88}
                      />
                    )}
                  </g>
                );
              })}

              {/* Churned fine bubble plume beneath impact point */}
              {activeImpact > 0.05 &&
                Array.from({ length: 18 }, (_, idx) => {
                  const cycle = (frame * 0.045 + idx * 0.17) % 1;
                  const spread = Math.sin(idx * 2.7 + frame * 0.1) * 46 * cycle;
                  const plungeY =
                    surfaceY +
                    12 +
                    Math.sin(cycle * Math.PI) *
                      Math.min(136, (INNER_BOT_Y - surfaceY) * 0.75);
                  const r = 1.6 + (idx % 3) * 1.1;
                  return (
                    <circle
                      key={`plume-${idx}`}
                      cx={392 + spread}
                      cy={plungeY}
                      r={r}
                      fill="rgba(254, 243, 226, 0.68)"
                      opacity={activeImpact * (1 - cycle) * 0.78}
                    />
                  );
                })}
            </g>
          )}

          {/* 3e. Glowing Top Meniscus Rim Line */}
          {liquidPaths.surfaceHighlight && (
            <path
              d={liquidPaths.surfaceHighlight}
              fill="none"
              stroke="rgba(253, 224, 171, 0.46)"
              strokeWidth="2.0"
            />
          )}

          {/* 3f. Creamy Frothy Cola Foam Head (soft blurred base + multi-scale organic froth bubbles) */}
          {foamThickness >= 1.2 && (
            <g>
              {/* Soft creamy under-cloud so foam blends like real liquid froth */}
              <path
                d={foamBandPath}
                fill="url(#colaFoamGrad)"
                filter="url(#foamSoftBlur)"
              />
              <path d={foamBandPath} fill="url(#colaFoamGrad)" opacity={0.72} />

              {/* Multi-scale frothy elliptical bubbles hugging the collar */}
              {foamBubbles.map((puff) => {
                const halfW = innerHalfWidthAt(surfaceY - foamThickness * 0.45) - 3;
                const px = CX + puff.xFrac * halfW;
                const wy = waveY(px, 0);
                const centerLift =
                  Math.exp(-Math.pow((px - 392) / 62, 2)) * (5 + activeImpact * 11);
                const py =
                  wy -
                  foamThickness * (0.14 + puff.yOffsetFrac * 0.82) -
                  centerLift +
                  Math.sin(frame * 0.09 + puff.phase) * (1.1 + pourEnergy * 1.8);
                const scaleFactor = 0.56 + (foamThickness / 42) * 0.54;
                const rx = puff.rx * scaleFactor;
                const ry = puff.ry * scaleFactor;

                const fillColor =
                  puff.shade > 0.64
                    ? '#FCF4EA'
                    : puff.shade > 0.30
                      ? '#EDD2B0'
                      : '#CFA070';

                return (
                  <g key={puff.id} opacity={puff.opacity}>
                    <ellipse
                      cx={px}
                      cy={py}
                      rx={rx}
                      ry={ry}
                      fill={fillColor}
                      stroke="rgba(166, 98, 52, 0.18)"
                      strokeWidth="0.6"
                    />
                    {rx > 5.2 && (
                      <circle
                        cx={px - rx * 0.28}
                        cy={py - ry * 0.28}
                        r={Math.min(rx, ry) * 0.24}
                        fill="#FFFFFF"
                        opacity={0.72}
                      />
                    )}
                  </g>
                );
              })}
            </g>
          )}
        </g>

        {/* LAYER 3.5: Silky Arched Cola Pour Stream (enters seamlessly from off-frame top-right!) */}
        {streamGeometry && (
          <g>
            <path d={streamGeometry.outerPath} fill="url(#streamOuterGrad)" />
            <path d={streamGeometry.corePath} fill="url(#streamCoreGrad)" />
            <path
              d={streamGeometry.specPath}
              fill="none"
              stroke="rgba(255, 245, 228, 0.66)"
              strokeWidth="2.8"
              strokeLinecap="round"
            />
            {/* Lush frothy splash crown hugging where the stream plunges into the foam */}
            {activeImpact > 0.05 && (
              <g opacity={activeImpact}>
                <ellipse
                  cx={392}
                  cy={surfaceY - foamThickness * 0.42}
                  rx={24 + Math.sin(frame * 0.32) * 4}
                  ry={9}
                  fill="#FCF4EA"
                  filter="url(#foamSoftBlur)"
                  opacity={0.92}
                />
                <ellipse
                  cx={392}
                  cy={surfaceY - foamThickness * 0.48}
                  rx={14 + Math.cos(frame * 0.28) * 2.5}
                  ry={5.5}
                  fill="#FFFFFF"
                  opacity={0.75}
                />
              </g>
            )}
            {/* Trailing glossy droplets as pour tapers smoothly off */}
            {streamTail > 0.12 && streamTail < 0.92 && (
              <>
                <circle
                  cx={streamGeometry.tailPos[0] + 9}
                  cy={streamGeometry.tailPos[1] - 18}
                  r={3.2 * (1 - streamTail)}
                  fill="#D97706"
                  opacity={0.85}
                />
                <circle
                  cx={streamGeometry.tailPos[0] + 18}
                  cy={streamGeometry.tailPos[1] - 36}
                  r={2.1 * (1 - streamTail)}
                  fill="#F59E0B"
                  opacity={0.7}
                />
              </>
            )}
          </g>
        )}

        {/* LAYER 3.6: Live Parabolic Splash Droplets & Subtle Fizz Mist */}
        {activeImpact > 0.04 && (
          <g>
            {splashDrops.map((drop) => {
              const t = (frame + drop.offset) % drop.period;
              const life = t / drop.period;
              const dx = drop.vx * t * 1.85;
              const dy = drop.vy * t + 0.31 * t * t;
              const x = 392 + dx;
              const y = surfaceY - foamThickness * 0.55 + dy;
              if (y > surfaceY + 4 || Math.abs(x - CX) > OUTER_TOP_HALF - 8) {
                return null;
              }
              const op = activeImpact * Math.sin(life * Math.PI) * 0.92;
              return (
                <g key={drop.id} opacity={op}>
                  <circle cx={x} cy={y} r={drop.radius} fill="#D97706" />
                  <circle
                    cx={x - drop.radius * 0.3}
                    cy={y - drop.radius * 0.3}
                    r={drop.radius * 0.35}
                    fill="#FFF7ED"
                  />
                </g>
              );
            })}
          </g>
        )}

        {fillProgress > 0.5 && (
          <g>
            {Array.from({ length: 14 }, (_, idx) => {
              const t = (frame * 0.025 + idx * 0.19) % 1;
              const mx = CX + Math.sin(idx * 4.1) * 108;
              const my = surfaceY - foamThickness - 8 - t * 36;
              const op = Math.sin(t * Math.PI) * 0.44;
              return (
                <circle
                  key={`mist-${idx}`}
                  cx={mx}
                  cy={my}
                  r={1.3 + (idx % 2) * 0.8}
                  fill="#FDE6C4"
                  opacity={op}
                />
              );
            })}
          </g>
        )}

        {/* LAYER 4: Heavy Crystal Base, Front Studio Reflections & Glistening Chilled Condensation */}
        <path
          d={crystalBasePath}
          fill="url(#crystalBaseGrad)"
          stroke="rgba(255, 248, 244, 0.22)"
          strokeWidth="1.2"
        />
        {/* Soft optical amber refraction inside the thick crystal glass bottom */}
        <path
          d={`M ${CX - INNER_BOT_HALF + 12} ${INNER_BOT_Y + 9} Q ${CX} ${INNER_BOT_Y + 34} ${CX + INNER_BOT_HALF - 12} ${INNER_BOT_Y + 9}`}
          fill="none"
          stroke={
            fillProgress > 0.1
              ? `rgba(245, 158, 11, ${0.20 + fillProgress * 0.36})`
              : 'rgba(255, 255, 255, 0.24)'
          }
          strokeWidth="5"
          strokeLinecap="round"
          filter="url(#baseRefractBlur)"
        />
        <path
          d={`M ${CX - OUTER_BOT_HALF + 16} ${BASE_Y - 2} Q ${CX} ${BASE_Y + BASE_RY * 1.2} ${CX + OUTER_BOT_HALF - 16} ${BASE_Y - 2}`}
          fill="none"
          stroke="rgba(255, 252, 248, 0.44)"
          strokeWidth="2.4"
          strokeLinecap="round"
        />

        {/* Left Curved Studio Softbox Reflection Streak */}
        <path
          d={`
            M ${CX - OUTER_TOP_HALF + 12} ${RIM_Y + 14}
            L ${CX - OUTER_TOP_HALF + 44} ${RIM_Y + 18}
            L ${CX - OUTER_BOT_HALF + 34} ${BASE_Y - 22}
            L ${CX - OUTER_BOT_HALF + 10} ${BASE_Y - 26}
            Z
          `}
          fill="url(#frontLeftReflection)"
        />
        <path
          d={`M ${CX - OUTER_TOP_HALF + 6} ${RIM_Y + 8} L ${CX - OUTER_BOT_HALF + 5} ${BASE_Y - 10}`}
          stroke="rgba(255, 255, 255, 0.50)"
          strokeWidth="2"
          strokeLinecap="round"
        />

        {/* Right Warm Rim Reflection Streak */}
        <path
          d={`
            M ${CX + OUTER_TOP_HALF - 30} ${RIM_Y + 18}
            L ${CX + OUTER_TOP_HALF - 10} ${RIM_Y + 14}
            L ${CX + OUTER_BOT_HALF - 8} ${BASE_Y - 24}
            L ${CX + OUTER_BOT_HALF - 24} ${BASE_Y - 20}
            Z
          `}
          fill="url(#frontRightReflection)"
        />

        {/* Subtle Outer Crystal Silhouette Outline */}
        <path
          d={outerGlassPath}
          fill="none"
          stroke="rgba(255, 248, 244, 0.28)"
          strokeWidth="1.6"
        />

        {/* Crisp Front Top Rim Specular Ellipse Arc */}
        <path
          d={`M ${CX - OUTER_TOP_HALF} ${RIM_Y} A ${OUTER_TOP_HALF} ${RIM_RY} 0 0 0 ${CX + OUTER_TOP_HALF} ${RIM_Y}`}
          fill="none"
          stroke="rgba(255, 252, 248, 0.64)"
          strokeWidth="2.6"
        />
        <path
          d={`M ${CX - OUTER_TOP_HALF + 16} ${RIM_Y + 11} Q ${CX - 70} ${RIM_Y + 23} ${CX - 8} ${RIM_Y + 23}`}
          fill="none"
          stroke="rgba(255, 255, 255, 0.88)"
          strokeWidth="2.4"
          strokeLinecap="round"
        />

        {/* Glistening Chilled Exterior Condensation Beads (clearly crisp & tactile!) */}
        <g>
          {condensationDrops.map((d) => {
            const dy = d.y + Math.max(0, frame - d.delay) * d.creepSpeed;
            if (dy < surfaceY + 14 || dy > BASE_Y - 24) return null;
            const dropFade = interpolate(frame, [d.delay, d.delay + 50], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
              easing: EASE_OUT_CUBIC,
            });
            if (dropFade <= 0.01) return null;
            const dx = CX + d.xFrac * (outerHalfWidthAt(dy) - 9);

            return (
              <g key={d.id} opacity={dropFade * 0.88}>
                {/* Subtle dark underside crescent shadow */}
                <ellipse
                  cx={dx + 0.8}
                  cy={dy + 1.5}
                  rx={d.rx}
                  ry={d.ry}
                  fill="rgba(8, 2, 1, 0.56)"
                />
                {/* Translucent chilled water bead */}
                <ellipse
                  cx={dx}
                  cy={dy}
                  rx={d.rx}
                  ry={d.ry}
                  fill="rgba(255, 250, 244, 0.28)"
                  stroke="rgba(255, 252, 248, 0.48)"
                  strokeWidth="0.75"
                />
                {/* Crisp bright top-left specular pinpoint */}
                <circle
                  cx={dx - d.rx * 0.30}
                  cy={dy - d.ry * 0.30}
                  r={Math.max(0.95, d.rx * 0.36)}
                  fill="#FFFFFF"
                  opacity={0.96}
                />
              </g>
            );
          })}
        </g>
      </g>
    </svg>
  );
};

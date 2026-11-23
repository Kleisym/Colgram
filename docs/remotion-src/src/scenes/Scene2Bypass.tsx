import React from 'react';
import {
  AbsoluteFill,
  Img,
  interpolate,
  spring,
  staticFile,
  useCurrentFrame,
  useVideoConfig,
} from 'remotion';
import { COLORS, FONT_MONO, FONT_SANS, SPRING_SMOOTH, SPRING_SNAPPY } from '../theme';
import { SceneBackdrop } from '../components/SceneBackdrop';
import { GlassCard } from '../components/GlassCard';
import { PhoneMockup3D } from '../components/PhoneMockup3D';
import {
  BoltIcon,
  CheckCircleIcon,
  GlobeIcon,
  ShieldCheckIcon,
  SignalTowerIcon,
  WifiIcon,
} from '../components/BrandIcons';

export const Scene2Bypass: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const headerEnter = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });

  // Animated latency counters
  const l1Ms = Math.round(
    interpolate(frame, [38, 86], [0, 354], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  );
  const l2Ms = Math.round(
    interpolate(frame, [64, 112], [0, 440], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  );

  // Smooth switch between top and bottom real network settings screenshots on phone
  const screenSwitch = spring({
    frame: frame - 205,
    fps,
    config: SPRING_SMOOTH,
  });
  const topScreenY = interpolate(screenSwitch, [0, 1], [0, -100], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const bottomScreenY = interpolate(screenSwitch, [0, 1], [100, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  // Animated packet progress along L1 & L2 pipeline lanes (0..1 looping)
  const packet1A = ((frame * 0.014) % 1);
  const packet1B = (((frame * 0.014) + 0.5) % 1);
  const packet2A = (((frame * 0.012) + 0.25) % 1);
  const packet2B = (((frame * 0.012) + 0.75) % 1);

  const l2ActiveGlow = interpolate(frame, [52, 84], [0.25, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const extraModules = [
    {
      title: 'ByeDPI для всех приложений',
      detail: 'YouTube · Discord · Браузеры · Звонки',
      tag: 'NO EXTERNAL VPN',
      color: COLORS.emerald,
      icon: <ShieldCheckIcon size={22} color={COLORS.emeraldBright} />,
      delay: 86,
    },
    {
      title: 'Cloudflare WARP',
      detail: 'Встроенный Go MASQUE-туннель',
      tag: 'KEX X25519',
      color: COLORS.crimson,
      icon: <BoltIcon size={22} color={COLORS.crimson} />,
      delay: 100,
    },
    {
      title: 'VLESS / Sing-box',
      detail: 'Импорт подписок и ключей в 1 клик',
      tag: 'LIBBOX CORE',
      color: COLORS.violet,
      icon: <GlobeIcon size={22} color={COLORS.violetBright} />,
      delay: 114,
    },
  ];

  // Helper to evaluate point along simple 3-segment SVG polyline for smooth glowing packets
  const evalLanePoint = (t: number, yLane: number) => {
    // From x=215, y=82 -> x=300, y=yLane -> x=940, y=yLane -> x=1025, y=82
    if (t < 0.14) {
      const k = t / 0.14;
      return { x: 215 + (300 - 215) * k, y: 82 + (yLane - 82) * k };
    }
    if (t < 0.86) {
      const k = (t - 0.14) / 0.72;
      return { x: 300 + (940 - 300) * k, y: yLane };
    }
    const k = (t - 0.86) / 0.14;
    return { x: 940 + (1025 - 940) * k, y: yLane + (82 - yLane) * k };
  };

  const p1a = evalLanePoint(packet1A, 42);
  const p1b = evalLanePoint(packet1B, 42);
  const p2a = evalLanePoint(packet2A, 122);
  const p2b = evalLanePoint(packet2B, 122);

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={COLORS.emerald}
        accentSecondary={COLORS.cyan}
        accentTertiary={COLORS.amber}
        seed={202}
        chapterIndex="02 / 06"
        chapterLabel="L1 + L2 ZERO-CLICK BYPASS"
      />

      <AbsoluteFill
        style={{
          padding: '84px 68px 52px 68px',
          display: 'flex',
          flexDirection: 'column',
          gap: 22,
        }}
      >
        {/* Header Row */}
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-end',
            justifyContent: 'space-between',
            opacity: interpolate(headerEnter, [0, 1], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
            transform: `translateY(${interpolate(headerEnter, [0, 1], [22, 0], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            })}px)`,
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            <h2
              style={{
                margin: 0,
                fontSize: 52,
                fontWeight: 900,
                letterSpacing: '-0.03em',
                color: COLORS.textPrimary,
                lineHeight: 1.05,
              }}
            >
              Двухуровневый обход блокировок{' '}
              <span style={{ color: COLORS.emeraldBright }}>L1 + L2</span>
            </h2>
            <span style={{ fontSize: 22, color: COLORS.textSecondary, fontWeight: 500 }}>
              Zero-Click Bypass · Подключается в любой Wi-Fi и 4G/LTE сети без сторонних VPN
            </span>
          </div>

          <div
            style={{
              padding: '10px 18px',
              borderRadius: 14,
              background: 'rgba(16, 185, 129, 0.14)',
              border: '1px solid rgba(52, 211, 153, 0.45)',
              fontFamily: FONT_MONO,
              fontSize: 15,
              fontWeight: 700,
              color: COLORS.emeraldBright,
              letterSpacing: '0.06em',
            }}
          >
            127.0.0.1:9876 · АВТО-МАРШРУТИЗАЦИЯ
          </div>
        </div>

        {/* Main Body: Left Architecture (flex:1) + Right Phone Showcase */}
        <div style={{ flex: 1, display: 'flex', gap: 36, minHeight: 0 }}>
          {/* LEFT ARCHITECTURE COLUMN */}
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 18 }}>
            {/* Animated Dual-Layer Network Pipeline SVG Card */}
            <GlassCard delay={12} accent={COLORS.cyan} style={{ padding: '16px 20px', height: 196 }}>
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  marginBottom: 4,
                }}
              >
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 13,
                    fontWeight: 700,
                    letterSpacing: '0.1em',
                    color: COLORS.cyanBright,
                  }}
                >
                  LIVE PACKET PIPELINE · MULTI-LAYER DPI BYPASS
                </span>
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 12,
                    color: COLORS.textMuted,
                  }}
                >
                  TLS 1.3 · SNI SPOOF · 2B SOCKS5 SPLIT
                </span>
              </div>

              <svg width="100%" height="156" viewBox="0 0 1280 164">
                <defs>
                  <linearGradient id="lane1Grad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stopColor={COLORS.emerald} />
                    <stop offset="100%" stopColor={COLORS.cyanBright} />
                  </linearGradient>
                  <linearGradient id="lane2Grad" x1="0%" y1="0%" x2="100%" y2="0%">
                    <stop offset="0%" stopColor={COLORS.cyan} />
                    <stop offset="100%" stopColor={COLORS.amberBright} />
                  </linearGradient>
                </defs>

                {/* L1 Route Path */}
                <path
                  d="M 215 82 L 300 42 L 940 42 L 1025 82"
                  fill="none"
                  stroke="url(#lane1Grad)"
                  strokeWidth="3.5"
                  strokeOpacity={0.78}
                  strokeDasharray="10 6"
                  strokeDashoffset={-frame * 1.4}
                />

                {/* L2 Route Path */}
                <path
                  d="M 215 82 L 300 122 L 940 122 L 1025 82"
                  fill="none"
                  stroke="url(#lane2Grad)"
                  strokeWidth="3.5"
                  strokeOpacity={0.78 * l2ActiveGlow}
                  strokeDasharray="10 6"
                  strokeDashoffset={-frame * 1.1}
                />

                {/* Moving Glowing Packets on L1 */}
                {[p1a, p1b].map((pt, idx) => (
                  <g key={`p1-${idx}`}>
                    <circle cx={pt.x} cy={pt.y} r="10" fill={COLORS.emeraldBright} fillOpacity={0.28} />
                    <circle cx={pt.x} cy={pt.y} r="5" fill="#FFFFFF" />
                  </g>
                ))}

                {/* Moving Glowing Packets on L2 */}
                {[p2a, p2b].map((pt, idx) => (
                  <g key={`p2-${idx}`} opacity={l2ActiveGlow}>
                    <circle cx={pt.x} cy={pt.y} r="10" fill={COLORS.amberBright} fillOpacity={0.28} />
                    <circle cx={pt.x} cy={pt.y} r="5" fill="#FFFFFF" />
                  </g>
                ))}

                {/* Left Endpoint: Colgram Client */}
                <rect
                  x="8"
                  y="44"
                  width="208"
                  height="76"
                  rx="16"
                  fill="#0D1624"
                  stroke={COLORS.emeraldBright}
                  strokeWidth="1.6"
                />
                <text x="112" y="76" textAnchor="middle" fill="#F8FAFC" fontSize="18" fontWeight="800">
                  COLGRAM CORE
                </text>
                <text
                  x="112"
                  y="101"
                  textAnchor="middle"
                  fill={COLORS.emeraldBright}
                  fontSize="13"
                  fontFamily={FONT_MONO}
                  fontWeight="700"
                >
                  127.0.0.1:9876
                </text>

                {/* L1 Middle Processor Pill */}
                <rect
                  x="412"
                  y="16"
                  width="416"
                  height="52"
                  rx="14"
                  fill="#0B1E22"
                  stroke={COLORS.emerald}
                  strokeWidth="1.5"
                />
                <text
                  x="620"
                  y="39"
                  textAnchor="middle"
                  fill={COLORS.emeraldBright}
                  fontSize="14"
                  fontFamily={FONT_MONO}
                  fontWeight="700"
                >
                  L1 · Wi-Fi / L7 ТСПУ · WSS + Spoofed SNI
                </text>
                <text
                  x="620"
                  y="58"
                  textAnchor="middle"
                  fill="#CBD5E1"
                  fontSize="12.5"
                  fontFamily={FONT_MONO}
                >
                  max.ru / vk.com / gosuslugi.ru · DoH/DoT 853
                </text>

                {/* L2 Middle Processor Pill */}
                <rect
                  x="412"
                  y="96"
                  width="416"
                  height="52"
                  rx="14"
                  fill="#171925"
                  stroke={COLORS.amber}
                  strokeWidth="1.5"
                  opacity={l2ActiveGlow}
                />
                <text
                  x="620"
                  y="119"
                  textAnchor="middle"
                  fill={COLORS.amberBright}
                  fontSize="14"
                  fontFamily={FONT_MONO}
                  fontWeight="700"
                  opacity={l2ActiveGlow}
                >
                  L2 · 4G/LTE IP-Блок · Auto L3-Relay Cascade
                </text>
                <text
                  x="620"
                  y="138"
                  textAnchor="middle"
                  fill="#CBD5E1"
                  fontSize="12.5"
                  fontFamily={FONT_MONO}
                  opacity={l2ActiveGlow}
                >
                  2-Byte SOCKS5 CONNECT · In-Tunnel TLS Split
                </text>

                {/* Right Endpoint: Telegram DC */}
                <rect
                  x="1024"
                  y="44"
                  width="244"
                  height="76"
                  rx="16"
                  fill="#0D1624"
                  stroke={COLORS.cyanBright}
                  strokeWidth="1.6"
                />
                <text x="1146" y="76" textAnchor="middle" fill="#F8FAFC" fontSize="18" fontWeight="800">
                  TELEGRAM DC 1–5
                </text>
                <text
                  x="1146"
                  y="101"
                  textAnchor="middle"
                  fill={COLORS.cyanBright}
                  fontSize="13"
                  fontFamily={FONT_MONO}
                  fontWeight="700"
                >
                  7TL_PONG · CONNECTED
                </text>
              </svg>
            </GlassCard>

            {/* Two-Column Detailed L1 & L2 Breakdown Cards */}
            <div style={{ display: 'flex', gap: 18, flex: 1 }}>
              {/* LEVEL 1 CARD */}
              <GlassCard
                delay={26}
                accent={COLORS.emerald}
                glow
                style={{
                  flex: 1,
                  padding: '22px 24px',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                      <div
                        style={{
                          width: 44,
                          height: 44,
                          borderRadius: 13,
                          backgroundColor: 'rgba(16, 185, 129, 0.18)',
                          border: '1px solid rgba(52, 211, 153, 0.5)',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                        }}
                      >
                        <WifiIcon size={24} color={COLORS.emeraldBright} />
                      </div>
                      <div>
                        <div
                          style={{
                            fontFamily: FONT_MONO,
                            fontSize: 13,
                            fontWeight: 700,
                            color: COLORS.emeraldBright,
                            letterSpacing: '0.08em',
                          }}
                        >
                          УРОВЕНЬ 1 · WI-FI / L7 ТСПУ
                        </div>
                        <div style={{ fontSize: 22, fontWeight: 800, color: COLORS.textPrimary }}>
                          Локальный DPI-Обход
                        </div>
                      </div>
                    </div>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 11, marginTop: 4 }}>
                    {[
                      'TLS 1.3 WSS · Spoofed RU-Whitelist SNI (max.ru / vk.com / gosuslugi.ru)',
                      'Multi-Split ClientHello Desync — разрыв DPI-сигнатуры',
                      '3-Way DoH / DoT (порт 853) против DNS-блокировок',
                    ].map((item) => (
                      <div key={item} style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                        <div style={{ marginTop: 2, flexShrink: 0 }}>
                          <CheckCircleIcon size={18} color={COLORS.emeraldBright} />
                        </div>
                        <span style={{ fontSize: 16.5, lineHeight: 1.35, color: COLORS.textSecondary }}>
                          {item}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* Technical Tag Strip */}
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 6 }}>
                    {['SNI: max.ru', 'ClientHello Multi-Split', 'DoH + DoT 853'].map((tag) => (
                      <span
                        key={tag}
                        style={{
                          padding: '5px 11px',
                          borderRadius: 8,
                          backgroundColor: 'rgba(16, 185, 129, 0.12)',
                          border: '1px solid rgba(52, 211, 153, 0.34)',
                          fontFamily: FONT_MONO,
                          fontSize: 12.5,
                          fontWeight: 700,
                          color: COLORS.emeraldBright,
                        }}
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                </div>

                {/* Telemetry Result Pill */}
                <div
                  style={{
                    marginTop: 14,
                    padding: '12px 16px',
                    borderRadius: 14,
                    background: 'rgba(16, 185, 129, 0.14)',
                    border: '1px solid rgba(52, 211, 153, 0.4)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <span style={{ fontFamily: FONT_MONO, fontSize: 14, color: COLORS.textSecondary }}>
                    ВЕРИФИЦИРОВАНО
                  </span>
                  <span
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 20,
                      fontWeight: 800,
                      color: COLORS.emeraldBright,
                    }}
                  >
                    {l1Ms} мс · 7TL_pong
                  </span>
                </div>
              </GlassCard>

              {/* LEVEL 2 CARD */}
              <GlassCard
                delay={46}
                accent={COLORS.amber}
                glow
                style={{
                  flex: 1,
                  padding: '22px 24px',
                  display: 'flex',
                  flexDirection: 'column',
                  justifyContent: 'space-between',
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                    <div
                      style={{
                        width: 44,
                        height: 44,
                        borderRadius: 13,
                        backgroundColor: 'rgba(245, 158, 11, 0.18)',
                        border: '1px solid rgba(251, 191, 36, 0.5)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                      }}
                    >
                      <SignalTowerIcon size={24} color={COLORS.amberBright} />
                    </div>
                    <div>
                      <div
                        style={{
                          fontFamily: FONT_MONO,
                          fontSize: 13,
                          fontWeight: 700,
                          color: COLORS.amberBright,
                          letterSpacing: '0.08em',
                        }}
                      >
                        УРОВЕНЬ 2 · МОБИЛЬНЫЙ 4G/LTE
                      </div>
                      <div style={{ fontSize: 22, fontWeight: 800, color: COLORS.textPrimary }}>
                        Блок IP на сотовой вышке
                      </div>
                    </div>
                  </div>

                  <div style={{ display: 'flex', flexDirection: 'column', gap: 11, marginTop: 4 }}>
                    {[
                      'Auto L3-Relay Cascade — автоматический подбор живого релея',
                      '2-Byte Segmented SOCKS5 CONNECT — обход жёсткого фильтра',
                      'In-Tunnel TLS Split — стабильная доставка медиа и чатов',
                    ].map((item) => (
                      <div key={item} style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
                        <div style={{ marginTop: 2, flexShrink: 0 }}>
                          <CheckCircleIcon size={18} color={COLORS.amberBright} />
                        </div>
                        <span style={{ fontSize: 16.5, lineHeight: 1.35, color: COLORS.textSecondary }}>
                          {item}
                        </span>
                      </div>
                    ))}
                  </div>

                  {/* Technical Tag Strip */}
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 6 }}>
                    {['Auto L3-Relay', '2-Byte SOCKS5', 'In-Tunnel Split'].map((tag) => (
                      <span
                        key={tag}
                        style={{
                          padding: '5px 11px',
                          borderRadius: 8,
                          backgroundColor: 'rgba(245, 158, 11, 0.12)',
                          border: '1px solid rgba(251, 191, 36, 0.34)',
                          fontFamily: FONT_MONO,
                          fontSize: 12.5,
                          fontWeight: 700,
                          color: COLORS.amberBright,
                        }}
                      >
                        {tag}
                      </span>
                    ))}
                  </div>
                </div>

                {/* Telemetry Result Pill */}
                <div
                  style={{
                    marginTop: 14,
                    padding: '12px 16px',
                    borderRadius: 14,
                    background: 'rgba(245, 158, 11, 0.14)',
                    border: '1px solid rgba(251, 191, 36, 0.42)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                  }}
                >
                  <span style={{ fontFamily: FONT_MONO, fontSize: 14, color: COLORS.textSecondary }}>
                    4G/LTE КАСКАД
                  </span>
                  <span
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 20,
                      fontWeight: 800,
                      color: COLORS.amberBright,
                    }}
                  >
                    {l2Ms} мс · Авто-обход 10с
                  </span>
                </div>
              </GlassCard>
            </div>

            {/* Bottom 3 Built-In Transport Modules Strip */}
            <div style={{ display: 'flex', gap: 16, height: 112 }}>
              {extraModules.map((mod) => (
                <GlassCard
                  key={mod.title}
                  delay={mod.delay}
                  accent={mod.color}
                  style={{
                    flex: 1,
                    padding: '16px 18px',
                    display: 'flex',
                    alignItems: 'center',
                    gap: 14,
                  }}
                >
                  <div
                    style={{
                      width: 44,
                      height: 44,
                      borderRadius: 13,
                      backgroundColor: `${mod.color}20`,
                      border: `1px solid ${mod.color}55`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    {mod.icon}
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
                    <span
                      style={{
                        fontFamily: FONT_MONO,
                        fontSize: 11,
                        fontWeight: 700,
                        letterSpacing: '0.08em',
                        color: mod.color,
                      }}
                    >
                      {mod.tag}
                    </span>
                    <span
                      style={{
                        fontSize: 17,
                        fontWeight: 800,
                        color: COLORS.textPrimary,
                        whiteSpace: 'nowrap',
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                      }}
                    >
                      {mod.title}
                    </span>
                    <span style={{ fontSize: 13.5, color: COLORS.textMuted }}>{mod.detail}</span>
                  </div>
                </GlassCard>
              ))}
            </div>
          </div>

          {/* RIGHT COLUMN — Real Colgram Network Settings Phone Showcase */}
          <div
            style={{
              width: 392,
              display: 'flex',
              flexDirection: 'column',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 14,
              flexShrink: 0,
            }}
          >
            <PhoneMockup3D
              width={362}
              height={750}
              rotateY={-10}
              rotateX={3}
              delay={14}
              glowColor={frame < 210 ? COLORS.emerald : COLORS.cyan}
              floatAmplitude={6}
            >
              {/* Slide 1: Real top screenshot */}
              <div
                style={{
                  position: 'absolute',
                  inset: 0,
                  transform: `translateY(${topScreenY}%)`,
                }}
              >
                <Img
                  src={staticFile('screen-network-top.jpg')}
                  style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top' }}
                />
              </div>
              {/* Slide 2: Real bottom screenshot */}
              <div
                style={{
                  position: 'absolute',
                  inset: 0,
                  transform: `translateY(${bottomScreenY}%)`,
                }}
              >
                <Img
                  src={staticFile('screen-network-bottom.jpg')}
                  style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top' }}
                />
              </div>
            </PhoneMockup3D>

            <div
              style={{
                padding: '7px 16px',
                borderRadius: 999,
                background: 'rgba(13, 20, 32, 0.85)',
                border: '1px solid rgba(255,255,255,0.14)',
                fontFamily: FONT_MONO,
                fontSize: 13,
                fontWeight: 700,
                color: frame < 215 ? COLORS.emeraldBright : COLORS.cyanBright,
              }}
            >
              {frame < 215
                ? 'REAL UI · WSS АКТИВЕН + DoH'
                : 'REAL UI · BYEDPI + WARP + VLESS'}
            </div>
          </div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

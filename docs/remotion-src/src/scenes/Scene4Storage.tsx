import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { COLORS, FONT_MONO, FONT_SANS, SPRING_SMOOTH, SPRING_SNAPPY } from '../theme';
import { SceneBackdrop } from '../components/SceneBackdrop';
import { GlassCard } from '../components/GlassCard';
import { CheckCircleIcon, DatabaseIcon, LayersIcon, ShieldCheckIcon } from '../components/BrandIcons';

export const Scene4Storage: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const headerEnter = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });

  // Animated space benchmark numbers
  const legacyBar = interpolate(frame, [45, 115], [0, 100], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const colgramBar = interpolate(frame, [62, 132], [0, 33], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const legacyGb = interpolate(frame, [45, 115], [0, 18.4], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const colgramGb = interpolate(frame, [62, 132], [0, 6.1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });
  const savedPct = Math.round(
    interpolate(frame, [75, 140], [0, 67], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  );

  // Flowing packet progress into SHA-256 engine
  const flowT = (frame * 0.016) % 1;
  const flowT2 = (frame * 0.016 + 0.5) % 1;
  const hexPulse = 0.78 + 0.22 * Math.sin(frame * 0.1);

  const metrics = [
    {
      value: '∞',
      label: 'Безлимитный кэш',
      sub: 'Никакого автоудаления чатов и медиа',
      color: COLORS.cyanBright,
      delay: 12,
    },
    {
      value: '0',
      label: 'Дубликатов',
      sub: 'SHA-256 Content-Addressed Deduplication',
      color: COLORS.emeraldBright,
      delay: 24,
    },
    {
      value: '100%',
      label: '.nomedia Sandbox',
      sub: 'Никакого мусора в Галерее и Загрузках',
      color: COLORS.violetBright,
      delay: 36,
    },
  ];

  const pillars = [
    {
      title: '∞ Безлимитный кэш без автоудаления',
      desc: 'Полная локальная история переписок, фото, видео и голосовых всегда под рукой даже офлайн.',
      accent: COLORS.cyan,
      delay: 72,
    },
    {
      title: 'SHA-256 Content-Addressed Deduplication',
      desc: '0 дубликатов между чатами и пересылками — одинаковые файлы хранятся в единственном экземпляре.',
      accent: COLORS.emerald,
      delay: 84,
    },
    {
      title: 'EXIF-Safe ARGB_8888 Smart Compression + GZIP',
      desc: 'Чёткое сжатие без GPS-метаданных + компактный GZIP AI & Export Vault для архивов.',
      accent: COLORS.amber,
      delay: 96,
    },
    {
      title: '100% .nomedia Sandbox Isolation',
      desc: 'Изолированный контур Documents/Colgram/ — медиа никогда не засоряют системную Галерею.',
      accent: COLORS.violet,
      delay: 108,
    },
  ];

  // Evaluate incoming merge curve points
  const inputY = [46, 128, 210];

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={COLORS.cyan}
        accentSecondary={COLORS.emerald}
        accentTertiary={COLORS.violet}
        seed={404}
        chapterIndex="04 / 06"
        chapterLabel="UNLIMITED STORAGE & SHA-256"
      />

      <AbsoluteFill
        style={{
          padding: '82px 68px 48px 68px',
          display: 'flex',
          flexDirection: 'column',
          gap: 18,
        }}
      >
        {/* Header */}
        <div
          style={{
            display: 'flex',
            alignItems: 'flex-end',
            justifyContent: 'space-between',
            opacity: interpolate(headerEnter, [0, 1], [0, 1], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            }),
            transform: `translateY(${interpolate(headerEnter, [0, 1], [20, 0], {
              extrapolateLeft: 'clamp',
              extrapolateRight: 'clamp',
            })}px)`,
          }}
        >
          <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
            <h2
              style={{
                margin: 0,
                fontSize: 50,
                fontWeight: 900,
                letterSpacing: '-0.03em',
                color: COLORS.textPrimary,
                lineHeight: 1.05,
              }}
            >
              Бесконечное хранилище и{' '}
              <span style={{ color: COLORS.cyanBright }}>SHA-256 Дедупликация</span>
            </h2>
            <span style={{ fontSize: 21, color: COLORS.textSecondary }}>
              Умное сжатие ARGB_8888, GZIP-архивация и полная изоляция .nomedia Sandbox
            </span>
          </div>

          <div
            style={{
              padding: '9px 18px',
              borderRadius: 14,
              backgroundColor: 'rgba(6, 182, 212, 0.14)',
              border: '1px solid rgba(34, 211, 238, 0.45)',
              fontFamily: FONT_MONO,
              fontSize: 14.5,
              fontWeight: 700,
              color: COLORS.cyanBright,
            }}
          >
            CONTENT-ADDRESSED VAULT · GZIP + EXIF-SAFE
          </div>
        </div>

        {/* Top 3 Telemetry Hero Counters */}
        <div style={{ display: 'flex', gap: 20, height: 130 }}>
          {metrics.map((m) => (
            <GlassCard
              key={m.label}
              delay={m.delay}
              accent={m.color}
              glow
              style={{
                flex: 1,
                padding: '18px 26px',
                display: 'flex',
                alignItems: 'center',
                gap: 22,
              }}
            >
              <div
                style={{
                  fontFamily: m.value === '∞' ? FONT_SANS : FONT_MONO,
                  fontSize: m.value === '∞' ? 84 : 58,
                  fontWeight: 900,
                  lineHeight: 0.9,
                  color: m.color,
                  textShadow: `0 0 28px ${m.color}66`,
                  minWidth: m.value === '100%' ? 156 : 74,
                }}
              >
                {m.value}
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span style={{ fontSize: 23, fontWeight: 800, color: COLORS.textPrimary }}>
                  {m.label}
                </span>
                <span style={{ fontSize: 15.5, color: COLORS.textSecondary }}>{m.sub}</span>
              </div>
            </GlassCard>
          ))}
        </div>

        {/* Center Visual Pipeline + Space Compression Telemetry */}
        <div style={{ display: 'flex', gap: 20, height: 324 }}>
          {/* Left: Animated SHA-256 Deduplication Engine SVG */}
          <GlassCard
            delay={42}
            accent={COLORS.cyan}
            style={{
              flex: 1.55,
              padding: '18px 24px',
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <LayersIcon size={20} color={COLORS.cyanBright} />
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 13.5,
                    fontWeight: 700,
                    letterSpacing: '0.09em',
                    color: COLORS.cyanBright,
                  }}
                >
                  SHA-256 DEDUPLICATION PIPELINE · 3 ПЕРЕСЫЛКИ → 1 ФАЙЛ
                </span>
              </div>
              <span style={{ fontFamily: FONT_MONO, fontSize: 12.5, color: COLORS.emeraldBright }}>
                HASH: e3b0c44298fc...
              </span>
            </div>

            <svg width="100%" height="260" viewBox="0 0 1040 256">
              <defs>
                <linearGradient id="shaFlowGrad" x1="0%" y1="0%" x2="100%" y2="0%">
                  <stop offset="0%" stopColor={COLORS.cyanBright} />
                  <stop offset="100%" stopColor={COLORS.emeraldBright} />
                </linearGradient>
              </defs>

              {/* 3 Incoming Curves to SHA-256 Engine */}
              {inputY.map((y, idx) => (
                <g key={`in-${idx}`}>
                  <path
                    d={`M 276 ${y} C 365 ${y}, 375 128, 466 128`}
                    fill="none"
                    stroke="url(#shaFlowGrad)"
                    strokeWidth="3"
                    strokeOpacity={0.75}
                    strokeDasharray="8 6"
                    strokeDashoffset={-frame * 1.5}
                  />
                  {/* Moving packet dot along cubic approximation */}
                  {[flowT, flowT2].map((t, j) => {
                    const px = 276 + (466 - 276) * t;
                    const smoothK = t * t * (3 - 2 * t);
                    const py = y + (128 - y) * smoothK;
                    return (
                      <g key={`dot-${idx}-${j}`}>
                        <circle cx={px} cy={py} r="8" fill={COLORS.cyanBright} fillOpacity={0.3} />
                        <circle cx={px} cy={py} r="4" fill="#FFFFFF" />
                      </g>
                    );
                  })}
                </g>
              ))}

              {/* Outgoing Single Deduplicated Path */}
              <path
                d="M 656 128 L 764 128"
                fill="none"
                stroke={COLORS.emeraldBright}
                strokeWidth="4.5"
                strokeDasharray="10 6"
                strokeDashoffset={-frame * 1.8}
              />
              <circle
                cx={656 + (764 - 656) * flowT}
                cy={128}
                r="6"
                fill="#FFFFFF"
              />

              {/* 3 Incoming File Cards */}
              {[
                { label: 'Чат A · photo_4k.png', sub: '6.2 МБ · Оригинал', y: 18 },
                { label: 'Пересылка B · photo_4k.png', sub: '6.2 МБ · Дубликат', y: 100 },
                { label: 'Канал C · photo_4k.png', sub: '6.2 МБ · Дубликат', y: 182 },
              ].map((file, i) => (
                <g key={file.label}>
                  <rect
                    x="8"
                    y={file.y}
                    width="268"
                    height="56"
                    rx="14"
                    fill="#0D1625"
                    stroke={i === 0 ? COLORS.cyanBright : 'rgba(255,255,255,0.22)'}
                    strokeWidth="1.5"
                  />
                  <text x="24" y={file.y + 24} fill="#F8FAFC" fontSize="15" fontWeight="700">
                    {file.label}
                  </text>
                  <text
                    x="24"
                    y={file.y + 44}
                    fill={i === 0 ? COLORS.cyanBright : '#94A3B8'}
                    fontSize="12.5"
                    fontFamily={FONT_MONO}
                  >
                    {file.sub}
                  </text>
                </g>
              ))}

              {/* Center SHA-256 Hashing Hub */}
              <g>
                <rect
                  x="466"
                  y="62"
                  width="190"
                  height="132"
                  rx="24"
                  fill="#091D26"
                  stroke={COLORS.emeraldBright}
                  strokeWidth="2.4"
                  opacity={hexPulse}
                />
                <text
                  x="561"
                  y="108"
                  textAnchor="middle"
                  fill={COLORS.emeraldBright}
                  fontSize="24"
                  fontFamily={FONT_MONO}
                  fontWeight="900"
                >
                  SHA-256
                </text>
                <text
                  x="561"
                  y="134"
                  textAnchor="middle"
                  fill="#F8FAFC"
                  fontSize="14"
                  fontWeight="700"
                >
                  DEDUP + ARGB_8888
                </text>
                <text
                  x="561"
                  y="158"
                  textAnchor="middle"
                  fill="#94A3B8"
                  fontSize="12"
                  fontFamily={FONT_MONO}
                >
                  EXIF STRIP · GZIP
                </text>
              </g>

              {/* Output Single Canonical Blob */}
              <g>
                <rect
                  x="764"
                  y="54"
                  width="266"
                  height="148"
                  rx="20"
                  fill="#0A1E1C"
                  stroke={COLORS.emeraldBright}
                  strokeWidth="2"
                />
                <text
                  x="897"
                  y="92"
                  textAnchor="middle"
                  fill={COLORS.emeraldBright}
                  fontSize="14"
                  fontFamily={FONT_MONO}
                  fontWeight="800"
                >
                  1 CANONICAL BLOB
                </text>
                <text
                  x="897"
                  y="126"
                  textAnchor="middle"
                  fill="#FFFFFF"
                  fontSize="26"
                  fontWeight="900"
                >
                  2.1 МБ всего
                </text>
                <text
                  x="897"
                  y="154"
                  textAnchor="middle"
                  fill="#CBD5E1"
                  fontSize="13.5"
                >
                  3 ссылки · 0 дубликатов
                </text>
                <text
                  x="897"
                  y="180"
                  textAnchor="middle"
                  fill={COLORS.cyanBright}
                  fontSize="12"
                  fontFamily={FONT_MONO}
                  fontWeight="700"
                >
                  Documents/Colgram/.nomedia
                </text>
              </g>
            </svg>
          </GlassCard>

          {/* Right: Storage Space Savings Benchmark Card */}
          <GlassCard
            delay={56}
            accent={COLORS.emerald}
            glow
            style={{
              flex: 1,
              padding: '22px 26px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <DatabaseIcon size={22} color={COLORS.emeraldBright} />
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 13.5,
                    fontWeight: 700,
                    letterSpacing: '0.08em',
                    color: COLORS.emeraldBright,
                  }}
                >
                  SMART COMPRESSION VAULT
                </span>
              </div>
              <span
                style={{
                  padding: '5px 12px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(16, 185, 129, 0.2)',
                  border: '1px solid rgba(52, 211, 153, 0.5)',
                  fontFamily: FONT_MONO,
                  fontSize: 15,
                  fontWeight: 800,
                  color: COLORS.emeraldBright,
                }}
              >
                −{savedPct}% места
              </span>
            </div>

            <div style={{ display: 'flex', flexDirection: 'column', gap: 22 }}>
              {/* Bar 1: Standard Client */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 16 }}>
                  <span style={{ color: COLORS.textSecondary, fontWeight: 600 }}>
                    Обычный мессенджер (дубли кэша)
                  </span>
                  <span style={{ fontFamily: FONT_MONO, fontWeight: 800, color: COLORS.crimson }}>
                    {legacyGb.toFixed(1)} ГБ
                  </span>
                </div>
                <div
                  style={{
                    height: 22,
                    borderRadius: 999,
                    backgroundColor: 'rgba(255,255,255,0.08)',
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      width: `${legacyBar}%`,
                      height: '100%',
                      borderRadius: 999,
                      background: 'linear-gradient(90deg, #9F1239, #F43F5E)',
                    }}
                  />
                </div>
              </div>

              {/* Bar 2: Colgram Vault */}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 16 }}>
                  <span style={{ color: COLORS.textPrimary, fontWeight: 700 }}>
                    Colgram Vault (SHA-256 + GZIP)
                  </span>
                  <span style={{ fontFamily: FONT_MONO, fontWeight: 800, color: COLORS.emeraldBright }}>
                    {colgramGb.toFixed(1)} ГБ
                  </span>
                </div>
                <div
                  style={{
                    height: 22,
                    borderRadius: 999,
                    backgroundColor: 'rgba(255,255,255,0.08)',
                    overflow: 'hidden',
                  }}
                >
                  <div
                    style={{
                      width: `${colgramBar}%`,
                      height: '100%',
                      borderRadius: 999,
                      background: `linear-gradient(90deg, ${COLORS.emerald}, ${COLORS.cyanBright})`,
                      boxShadow: `0 0 18px ${COLORS.emerald}88`,
                    }}
                  />
                </div>
              </div>
            </div>

            <div
              style={{
                padding: '12px 16px',
                borderRadius: 14,
                backgroundColor: 'rgba(139, 92, 246, 0.14)',
                border: '1px solid rgba(167, 139, 250, 0.36)',
                display: 'flex',
                alignItems: 'center',
                gap: 12,
              }}
            >
              <ShieldCheckIcon size={22} color={COLORS.violetBright} />
              <span style={{ fontSize: 14.5, color: COLORS.textSecondary, lineHeight: 1.35 }}>
                <strong style={{ color: COLORS.textPrimary }}>EXIF-Safe ARGB_8888:</strong> GPS и
                метаданные камеры удаляются до сохранения и отправки.
              </span>
            </div>
          </GlassCard>
        </div>

        {/* Bottom 2x2 Architectural Pillars */}
        <div
          style={{
            flex: 1,
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: 16,
          }}
        >
          {pillars.map((p) => (
            <GlassCard
              key={p.title}
              delay={p.delay}
              accent={p.accent}
              style={{
                padding: '18px 22px',
                display: 'flex',
                alignItems: 'center',
                gap: 16,
              }}
            >
              <div style={{ flexShrink: 0 }}>
                <CheckCircleIcon size={26} color={p.accent} />
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                <span style={{ fontSize: 19, fontWeight: 800, color: COLORS.textPrimary }}>
                  {p.title}
                </span>
                <span style={{ fontSize: 15, color: COLORS.textSecondary, lineHeight: 1.34 }}>
                  {p.desc}
                </span>
              </div>
            </GlassCard>
          ))}
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

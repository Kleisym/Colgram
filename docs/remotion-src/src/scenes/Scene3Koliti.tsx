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
import { COLORS, FONT_MONO, FONT_SANS, SPRING_BOUNCY, SPRING_SNAPPY } from '../theme';
import { SceneBackdrop } from '../components/SceneBackdrop';
import { GlassCard } from '../components/GlassCard';
import { PhoneMockup3D } from '../components/PhoneMockup3D';
import {
  CheckCircleIcon,
  CpuIcon,
  EyeScanIcon,
  MicWaveIcon,
  SparklesIcon,
  TerminalIcon,
} from '../components/BrandIcons';

const FULL_AI_REPLY =
  'Готово! Распознал 4 позиции на сумму 2 840 ₽ (EXIF очищен). Сохранил итог в Избранное и включил режим Невидимки.';

export const Scene3Koliti: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const headerEnter = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });

  // Chat choreography inside phone
  const userMsgEnter = spring({ frame: frame - 36, fps, config: SPRING_SNAPPY });
  const thinkingVisible = frame >= 76 && frame < 118;
  const aiMsgEnter = spring({ frame: frame - 118, fps, config: SPRING_SNAPPY });
  const charsShown = Math.floor(
    interpolate(frame, [124, 245], [0, FULL_AI_REPLY.length], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  );
  const toolsEnter = spring({ frame: frame - 246, fps, config: SPRING_BOUNCY });
  const voiceCardEnter = spring({ frame: frame - 285, fps, config: SPRING_SNAPPY });

  const speedVal = interpolate(frame, [26, 90], [0, 14.8], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  const capabilities = [
    {
      title: 'Живой русский диалог',
      desc: 'Контекстное общение, краткие сводки переписок и черновики ответов прямо в чатах без интернета.',
      badge: 'OFFLINE CHAT',
      color: COLORS.amber,
      icon: <SparklesIcon size={24} color={COLORS.amberBright} />,
      delay: 42,
    },
    {
      title: 'Exif-Safe Vision',
      desc: 'Локальный анализ фото, чеков и документов с автоматическим удалением GPS и EXIF метаданных.',
      badge: 'ZERO LEAK VISION',
      color: COLORS.cyan,
      icon: <EyeScanIcon size={24} color={COLORS.cyanBright} />,
      delay: 56,
    },
    {
      title: 'Sherpa-ONNX Voice',
      desc: 'Мгновенная расшифровка голосовых сообщений и реалистичное клонирование тембра речи на устройстве.',
      badge: 'ASR + VOICE CLONE',
      color: COLORS.crimson,
      icon: <MicWaveIcon size={24} color={COLORS.crimson} />,
      delay: 70,
    },
    {
      title: 'Прямое управление приложением',
      desc: 'Выполнение инструментов Colgram голосом и текстом: Ghost Mode, темы Aero, поиск и отправка.',
      badge: 'AGENT TOOLS',
      color: COLORS.emerald,
      icon: <TerminalIcon size={24} color={COLORS.emeraldBright} />,
      delay: 84,
    },
  ];

  const waveformBars = Array.from({ length: 26 }, (_, i) => {
    const wave =
      0.32 +
      0.48 * Math.abs(Math.sin(frame * 0.16 + i * 0.45)) +
      0.2 * Math.abs(Math.cos(frame * 0.11 + i * 0.8));
    return Math.min(1, wave);
  });

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={COLORS.amber}
        accentSecondary={COLORS.crimson}
        accentTertiary={COLORS.cyan}
        seed={303}
        chapterIndex="03 / 06"
        chapterLabel="KOLITI AI · OFFLINE CORE"
      />

      <AbsoluteFill
        style={{
          padding: '84px 72px 54px 72px',
          display: 'flex',
          flexDirection: 'row',
          alignItems: 'center',
          gap: 54,
        }}
      >
        {/* LEFT COLUMN — Animated 3D Phone Running Koliti AI Live Chat */}
        <div
          style={{
            width: 446,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            flexShrink: 0,
          }}
        >
          <PhoneMockup3D
            width={412}
            height={834}
            rotateY={11}
            rotateX={3}
            delay={10}
            glowColor={COLORS.amber}
            floatAmplitude={7}
            showNotch
          >
            {/* Authentic Dark Colgram Koliti Sheet UI */}
            <div
              style={{
                width: '100%',
                height: '100%',
                background: 'linear-gradient(180deg, #10141D 0%, #0A0D13 100%)',
                display: 'flex',
                flexDirection: 'column',
                padding: '44px 18px 20px 18px',
                boxSizing: 'border-box',
                gap: 14,
              }}
            >
              {/* Chat Header */}
              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                  paddingBottom: 12,
                  borderBottom: '1px solid rgba(255,255,255,0.09)',
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', gap: 11 }}>
                  <div
                    style={{
                      width: 44,
                      height: 44,
                      borderRadius: 999,
                      border: `2px solid ${COLORS.amberBright}`,
                      overflow: 'hidden',
                      boxShadow: '0 0 18px rgba(245, 158, 11, 0.45)',
                    }}
                  >
                    <Img
                      src={staticFile('koliti-avatar.png')}
                      style={{ width: '100%', height: '100%', objectFit: 'cover' }}
                    />
                  </div>
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                    <span style={{ fontSize: 18, fontWeight: 800, color: COLORS.textPrimary }}>
                      Koliti AI
                    </span>
                    <span
                      style={{
                        fontFamily: FONT_MONO,
                        fontSize: 11,
                        color: COLORS.amberBright,
                        fontWeight: 600,
                      }}
                    >
                      Qwen3.5-2B · Текст, фото, голос
                    </span>
                  </div>
                </div>
                <div
                  style={{
                    padding: '4px 10px',
                    borderRadius: 999,
                    backgroundColor: 'rgba(16, 185, 129, 0.16)',
                    border: '1px solid rgba(52, 211, 153, 0.4)',
                    fontFamily: FONT_MONO,
                    fontSize: 10.5,
                    fontWeight: 700,
                    color: COLORS.emeraldBright,
                  }}
                >
                  OFFLINE
                </div>
              </div>

              {/* Welcome Pill */}
              <div
                style={{
                  padding: '10px 13px',
                  borderRadius: 14,
                  backgroundColor: 'rgba(255,255,255,0.045)',
                  border: '1px solid rgba(255,255,255,0.08)',
                  fontSize: 13.5,
                  lineHeight: 1.38,
                  color: COLORS.textSecondary,
                }}
              >
                Хей, привет! Я Koliti — твой локальный ИИ-помощник прямо в Colgram.
              </div>

              {/* User Prompt Bubble with Photo Vision Attachment */}
              <div
                style={{
                  alignSelf: 'flex-end',
                  maxWidth: '88%',
                  opacity: interpolate(userMsgEnter, [0, 1], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  }),
                  transform: `translateY(${interpolate(userMsgEnter, [0, 1], [18, 0], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  })}px)`,
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 8,
                  padding: '11px 14px',
                  borderRadius: '18px 18px 6px 18px',
                  background: 'linear-gradient(135deg, #E11D48 0%, #BE123C 100%)',
                  boxShadow: '0 10px 24px rgba(225, 29, 72, 0.34)',
                }}
              >
                {/* Mini Photo Card */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 9,
                    padding: '7px 10px',
                    borderRadius: 11,
                    backgroundColor: 'rgba(0, 0, 0, 0.28)',
                  }}
                >
                  <EyeScanIcon size={18} color="#FDE68A" />
                  <div style={{ display: 'flex', flexDirection: 'column' }}>
                    <span style={{ fontFamily: FONT_MONO, fontSize: 11.5, fontWeight: 700, color: '#FFF' }}>
                      check_2840.jpg
                    </span>
                    <span style={{ fontSize: 10.5, color: 'rgba(255,255,255,0.78)' }}>
                      Exif-Safe Vision · GPS удалён
                    </span>
                  </div>
                </div>
                <span style={{ fontSize: 14, lineHeight: 1.36, color: '#FFFFFF', fontWeight: 500 }}>
                  Разбери чек с фото, сохрани в Избранное и включи Невидимку
                </span>
              </div>

              {/* Thinking Wave Indicator */}
              {thinkingVisible && (
                <div
                  style={{
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '9px 14px',
                    borderRadius: 14,
                    backgroundColor: 'rgba(245, 158, 11, 0.12)',
                    border: '1px solid rgba(251, 191, 36, 0.34)',
                    width: 'fit-content',
                  }}
                >
                  {[0, 1, 2].map((dot) => {
                    const dotScale = 0.7 + 0.45 * Math.abs(Math.sin(frame * 0.22 + dot * 1.1));
                    return (
                      <span
                        key={dot}
                        style={{
                          width: 7,
                          height: 7,
                          borderRadius: 999,
                          backgroundColor: COLORS.amberBright,
                          transform: `scale(${dotScale})`,
                        }}
                      />
                    );
                  })}
                  <span style={{ fontFamily: FONT_MONO, fontSize: 11.5, color: COLORS.amberBright }}>
                    KleidiAI · DotProd inference...
                  </span>
                </div>
              )}

              {/* Koliti AI Typewriter Reply Bubble */}
              {frame >= 118 && (
                <div
                  style={{
                    alignSelf: 'flex-start',
                    width: '94%',
                    opacity: interpolate(aiMsgEnter, [0, 1], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    }),
                    transform: `translateY(${interpolate(aiMsgEnter, [0, 1], [16, 0], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    })}px)`,
                    padding: '13px 14px',
                    borderRadius: '18px 18px 18px 6px',
                    backgroundColor: '#161C28',
                    border: '1px solid rgba(251, 191, 36, 0.28)',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 10,
                  }}
                >
                  <div style={{ fontSize: 14, lineHeight: 1.42, color: COLORS.textPrimary }}>
                    {FULL_AI_REPLY.slice(0, charsShown)}
                    {charsShown < FULL_AI_REPLY.length && (
                      <span
                        style={{
                          display: 'inline-block',
                          width: 7,
                          height: 14,
                          marginLeft: 2,
                          backgroundColor: COLORS.amberBright,
                          verticalAlign: 'middle',
                        }}
                      />
                    )}
                  </div>

                  {/* Executed Tool Badges */}
                  {frame >= 246 && (
                    <div
                      style={{
                        display: 'flex',
                        flexWrap: 'wrap',
                        gap: 6,
                        opacity: interpolate(toolsEnter, [0, 1], [0, 1], {
                          extrapolateLeft: 'clamp',
                          extrapolateRight: 'clamp',
                        }),
                        transform: `scale(${interpolate(toolsEnter, [0, 1], [0.9, 1], {
                          extrapolateLeft: 'clamp',
                          extrapolateRight: 'clamp',
                        })})`,
                      }}
                    >
                      {[
                        { label: 'Vision OCR', color: COLORS.cyanBright },
                        { label: 'Ghost Mode: ВКЛ', color: COLORS.emeraldBright },
                        { label: 'Заметка сохранена', color: COLORS.amberBright },
                      ].map((t) => (
                        <span
                          key={t.label}
                          style={{
                            display: 'inline-flex',
                            alignItems: 'center',
                            gap: 5,
                            padding: '4px 9px',
                            borderRadius: 999,
                            backgroundColor: `${t.color}1C`,
                            border: `1px solid ${t.color}55`,
                            fontFamily: FONT_MONO,
                            fontSize: 10.5,
                            fontWeight: 700,
                            color: t.color,
                          }}
                        >
                          <CheckCircleIcon size={12} color={t.color} />
                          {t.label}
                        </span>
                      ))}
                    </div>
                  )}

                  <span style={{ fontFamily: FONT_MONO, fontSize: 10, color: COLORS.textMuted, whiteSpace: 'nowrap' }}>
                    Koliti Core · Q4_K_XL · 14.8 tok/s · 0 KB
                  </span>
                </div>
              )}

              {/* Live Sherpa-ONNX Voice Cloning & Transcription Bar */}
              {frame >= 285 && (
                <div
                  style={{
                    padding: '12px 14px',
                    borderRadius: 16,
                    background:
                      'linear-gradient(135deg, rgba(244, 63, 94, 0.16) 0%, rgba(245, 158, 11, 0.14) 100%)',
                    border: '1px solid rgba(244, 63, 94, 0.42)',
                    opacity: interpolate(voiceCardEnter, [0, 1], [0, 1], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    }),
                    transform: `translateY(${interpolate(voiceCardEnter, [0, 1], [16, 0], {
                      extrapolateLeft: 'clamp',
                      extrapolateRight: 'clamp',
                    })}px)`,
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 8,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
                      <MicWaveIcon size={16} color={COLORS.crimson} />
                      <span style={{ fontSize: 12.5, fontWeight: 700, color: COLORS.textPrimary, whiteSpace: 'nowrap' }}>
                        Sherpa-ONNX · Voice Clone
                      </span>
                    </div>
                    <span style={{ fontFamily: FONT_MONO, fontSize: 11, color: COLORS.amberBright, whiteSpace: 'nowrap', flexShrink: 0 }}>
                      RU · LIVE
                    </span>
                  </div>
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      height: 30,
                      gap: 3,
                    }}
                  >
                    {waveformBars.map((h, idx) => (
                      <div
                        key={idx}
                        style={{
                          flex: 1,
                          height: `${Math.round(h * 100)}%`,
                          borderRadius: 99,
                          background:
                            idx % 2 === 0
                              ? `linear-gradient(180deg, ${COLORS.amberBright}, ${COLORS.crimson})`
                              : `linear-gradient(180deg, ${COLORS.cyanBright}, ${COLORS.emerald})`,
                        }}
                      />
                    ))}
                  </div>
                </div>
              )}

              {/* Bottom Input Bar */}
              <div
                style={{
                  marginTop: 'auto',
                  padding: '10px 14px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(255,255,255,0.06)',
                  border: '1px solid rgba(255,255,255,0.12)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'space-between',
                }}
              >
                <span style={{ fontSize: 13.5, color: COLORS.textMuted }}>
                  Спросите Koliti о чём угодно...
                </span>
                <div
                  style={{
                    width: 30,
                    height: 30,
                    borderRadius: 999,
                    background: 'linear-gradient(135deg, #F43F5E, #E11D48)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <SparklesIcon size={15} color="#FFFFFF" />
                </div>
              </div>
            </div>
          </PhoneMockup3D>
        </div>

        {/* RIGHT COLUMN — Silicon Engine Core & 2x2 Capability Grid */}
        <div
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            gap: 22,
          }}
        >
          {/* Title Block */}
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 8,
              opacity: interpolate(headerEnter, [0, 1], [0, 1], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              }),
              transform: `translateY(${interpolate(headerEnter, [0, 1], [24, 0], {
                extrapolateLeft: 'clamp',
                extrapolateRight: 'clamp',
              })}px)`,
            }}
          >
            <h2
              style={{
                margin: 0,
                fontSize: 54,
                fontWeight: 900,
                letterSpacing: '-0.03em',
                lineHeight: 1.05,
                color: COLORS.textPrimary,
              }}
            >
              Koliti AI — умный{' '}
              <span
                style={{
                  background: `linear-gradient(90deg, ${COLORS.amberBright}, ${COLORS.crimson})`,
                  WebkitBackgroundClip: 'text',
                  WebkitTextFillColor: 'transparent',
                }}
              >
                офлайн-ассистент
              </span>
            </h2>
            <span style={{ fontSize: 22, color: COLORS.textSecondary, fontWeight: 500 }}>
              Встроен прямо в чаты Colgram · Полностью автономный интеллект без облаков и подписок
            </span>
          </div>

          {/* Native Core Silicon Spec Banner */}
          <GlassCard
            delay={18}
            accent={COLORS.amber}
            glow
            style={{
              padding: '22px 26px',
              display: 'flex',
              flexDirection: 'column',
              gap: 16,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                <div
                  style={{
                    width: 54,
                    height: 54,
                    borderRadius: 16,
                    background: 'linear-gradient(135deg, rgba(245,158,11,0.24), rgba(244,63,94,0.22))',
                    border: '1px solid rgba(251, 191, 36, 0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                    flexShrink: 0,
                  }}
                >
                  <CpuIcon size={28} color={COLORS.amberBright} />
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
                  <span
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 13,
                      fontWeight: 700,
                      letterSpacing: '0.1em',
                      color: COLORS.amberBright,
                    }}
                  >
                    UPGRADED IMATRIX NEURAL CORE
                  </span>
                  <span
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 28,
                      fontWeight: 800,
                      color: COLORS.textPrimary,
                      letterSpacing: '-0.02em',
                    }}
                  >
                    Qwen3.5-2B-UD-Q4_K_XL
                  </span>
                </div>
              </div>

              <div
                style={{
                  padding: '10px 18px',
                  borderRadius: 14,
                  backgroundColor: 'rgba(16, 185, 129, 0.14)',
                  border: '1px solid rgba(52, 211, 153, 0.45)',
                  textAlign: 'right',
                }}
              >
                <div
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 22,
                    fontWeight: 800,
                    color: COLORS.emeraldBright,
                  }}
                >
                  {speedVal.toFixed(1)} tok/s
                </div>
                <div style={{ fontFamily: FONT_MONO, fontSize: 11.5, color: COLORS.textSecondary }}>
                  0 БАЙТ В ОБЛАКО
                </div>
              </div>
            </div>

            {/* Hardware ISA Acceleration Pills */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
              {[
                { label: 'ARMv8.2 DotProd', color: COLORS.cyanBright },
                { label: 'Native FP16', color: COLORS.amberBright },
                { label: 'ARM KleidiAI Kernels', color: COLORS.emeraldBright },
                { label: 'imatrix Quantization', color: COLORS.crimson },
              ].map((isa) => (
                <span
                  key={isa.label}
                  style={{
                    padding: '7px 14px',
                    borderRadius: 10,
                    backgroundColor: `${isa.color}18`,
                    border: `1px solid ${isa.color}50`,
                    fontFamily: FONT_MONO,
                    fontSize: 14,
                    fontWeight: 700,
                    color: isa.color,
                  }}
                >
                  {isa.label}
                </span>
              ))}
            </div>
          </GlassCard>

          {/* 2x2 Capability Grid */}
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '1fr 1fr',
              gap: 18,
            }}
          >
            {capabilities.map((cap) => (
              <GlassCard
                key={cap.title}
                delay={cap.delay}
                accent={cap.color}
                style={{
                  padding: '22px 24px',
                  display: 'flex',
                  flexDirection: 'column',
                  gap: 11,
                }}
              >
                <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                  <div
                    style={{
                      width: 46,
                      height: 46,
                      borderRadius: 14,
                      backgroundColor: `${cap.color}1F`,
                      border: `1px solid ${cap.color}55`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                    }}
                  >
                    {cap.icon}
                  </div>
                  <span
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 11.5,
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      color: cap.color,
                      padding: '4px 10px',
                      borderRadius: 999,
                      backgroundColor: `${cap.color}16`,
                      border: `1px solid ${cap.color}40`,
                    }}
                  >
                    {cap.badge}
                  </span>
                </div>
                <div style={{ fontSize: 22, fontWeight: 800, color: COLORS.textPrimary }}>
                  {cap.title}
                </div>
                <div style={{ fontSize: 16, lineHeight: 1.4, color: COLORS.textSecondary }}>
                  {cap.desc}
                </div>
              </GlassCard>
            ))}
          </div>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

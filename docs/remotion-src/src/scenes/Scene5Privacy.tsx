import React from 'react';
import { AbsoluteFill, interpolate, spring, useCurrentFrame, useVideoConfig } from 'remotion';
import { COLORS, FONT_MONO, FONT_SANS, SPRING_BOUNCY, SPRING_SMOOTH, SPRING_SNAPPY } from '../theme';
import { SceneBackdrop } from '../components/SceneBackdrop';
import { GlassCard } from '../components/GlassCard';
import {
  CheckCircleIcon,
  GhostIcon,
  HistoryVaultIcon,
  LockIcon,
  MailShieldIcon,
} from '../components/BrandIcons';

export const Scene5Privacy: React.FC = () => {
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const headerEnter = spring({ frame: frame - 4, fps, config: SPRING_SNAPPY });

  // Anti-delete animation (message deleted at frame 62 -> intercepted and saved!)
  const deleteTrigger = spring({ frame: frame - 64, fps, config: SPRING_SMOOTH });
  const editHistoryPop = spring({ frame: frame - 92, fps, config: SPRING_BOUNCY });

  // Ghost Mode switches flipping ON sequentially
  const ghostToggles = [
    { label: 'Невидимый онлайн-статус', delay: 32 },
    { label: 'Чтение сообщений без галочек', delay: 54 },
    { label: 'Скрытие набора текста и просмотра историй', delay: 76 },
  ];

  // AES-256 PIN dots lighting up sequentially
  const pinDotsLit = Math.floor(
    interpolate(frame, [42, 108], [0, 6.99], {
      extrapolateLeft: 'clamp',
      extrapolateRight: 'clamp',
    }),
  );
  const vaultUnlocked = spring({ frame: frame - 110, fps, config: SPRING_BOUNCY });

  // Colemailer spam filter metric
  const spamRate = interpolate(frame, [52, 118], [0, 99.4], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
  });

  return (
    <AbsoluteFill style={{ fontFamily: FONT_SANS }}>
      <SceneBackdrop
        accentPrimary={COLORS.violet}
        accentSecondary={COLORS.crimson}
        accentTertiary={COLORS.emerald}
        seed={505}
        chapterIndex="05 / 06"
        chapterLabel="PRIVACY · GHOST · ANTI-DELETE"
      />

      <AbsoluteFill
        style={{
          padding: '84px 68px 50px 68px',
          display: 'flex',
          flexDirection: 'column',
          gap: 24,
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
              Приватность,{' '}
              <span style={{ color: COLORS.violetBright }}>Ghost Mode</span> и{' '}
              <span style={{ color: COLORS.crimson }}>Anti-Delete</span>
            </h2>
            <span style={{ fontSize: 22, color: COLORS.textSecondary }}>
              Бронированная защита переписок · Ноль телеметрии · Локальное шифрование и антиспам
            </span>
          </div>

          <div
            style={{
              padding: '10px 18px',
              borderRadius: 14,
              backgroundColor: 'rgba(139, 92, 246, 0.16)',
              border: '1px solid rgba(167, 139, 250, 0.45)',
              fontFamily: FONT_MONO,
              fontSize: 14.5,
              fontWeight: 700,
              color: COLORS.violetBright,
            }}
          >
            ZERO TELEMETRY · HARDWARE CLOAKING
          </div>
        </div>

        {/* 2x2 Rich Interactive Bento Grid */}
        <div
          style={{
            flex: 1,
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gridTemplateRows: '1fr 1fr',
            gap: 24,
          }}
        >
          {/* CARD 1 — Anti-Delete Vault */}
          <GlassCard
            delay={14}
            accent={COLORS.crimson}
            glow
            style={{
              padding: '26px 28px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              gap: 16,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div
                  style={{
                    width: 50,
                    height: 50,
                    borderRadius: 15,
                    backgroundColor: 'rgba(244, 63, 94, 0.18)',
                    border: '1px solid rgba(244, 63, 94, 0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <HistoryVaultIcon size={26} color={COLORS.crimson} />
                </div>
                <div>
                  <div
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 12.5,
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      color: COLORS.crimson,
                    }}
                  >
                    MESSAGE VAULT · SQLITE
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: COLORS.textPrimary }}>
                    Anti-Delete Vault
                  </div>
                </div>
              </div>
              <span
                style={{
                  padding: '5px 12px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(244, 63, 94, 0.18)',
                  border: '1px solid rgba(244, 63, 94, 0.45)',
                  fontFamily: FONT_MONO,
                  fontSize: 12,
                  fontWeight: 700,
                  color: '#FDA4AF',
                }}
              >
                TEXT + MEDIA + EDITS
              </span>
            </div>

            {/* Live Simulated Intercepted Message */}
            <div
              style={{
                padding: '16px 18px',
                borderRadius: 16,
                backgroundColor:
                  deleteTrigger > 0.35 ? 'rgba(244, 63, 94, 0.11)' : 'rgba(255, 255, 255, 0.05)',
                border:
                  deleteTrigger > 0.35
                    ? '1px solid rgba(244, 63, 94, 0.45)'
                    : '1px solid rgba(255, 255, 255, 0.12)',
                display: 'flex',
                flexDirection: 'column',
                gap: 10,
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: 17, fontWeight: 600, color: COLORS.textPrimary }}>
                  «Договор и реквизиты отправил в 18:40»
                </span>
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 12,
                    color: deleteTrigger > 0.35 ? '#FDA4AF' : COLORS.textMuted,
                    fontWeight: 700,
                  }}
                >
                  {deleteTrigger > 0.35 ? 'УДАЛЕНО · СОХРАНЕНО' : '18:40'}
                </span>
              </div>

              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 10,
                  opacity: interpolate(editHistoryPop, [0, 1], [0, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  }),
                }}
              >
                <span
                  style={{
                    padding: '4px 10px',
                    borderRadius: 8,
                    backgroundColor: 'rgba(244, 63, 94, 0.22)',
                    fontFamily: FONT_MONO,
                    fontSize: 12,
                    fontWeight: 700,
                    color: '#FECDD3',
                  }}
                >
                  История правок: v1 → v2
                </span>
                <span style={{ fontSize: 14, color: COLORS.textSecondary }}>
                  Перехват удаления сообщений и медиа собеседника
                </span>
              </div>
            </div>

            <div style={{ fontSize: 16.5, color: COLORS.textSecondary, lineHeight: 1.38 }}>
              Сохранение удалённых собеседником сообщений и медиа с полной хронологией всех правок.
            </div>
          </GlassCard>

          {/* CARD 2 — Ghost Mode */}
          <GlassCard
            delay={24}
            accent={COLORS.violet}
            glow
            style={{
              padding: '26px 28px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              gap: 14,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div
                  style={{
                    width: 50,
                    height: 50,
                    borderRadius: 15,
                    backgroundColor: 'rgba(139, 92, 246, 0.2)',
                    border: '1px solid rgba(167, 139, 250, 0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <GhostIcon size={26} color={COLORS.violetBright} />
                </div>
                <div>
                  <div
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 12.5,
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      color: COLORS.violetBright,
                    }}
                  >
                    STEALTH PROTOCOL
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: COLORS.textPrimary }}>
                    Ghost Mode (Невидимка)
                  </div>
                </div>
              </div>
              <span
                style={{
                  padding: '5px 12px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(139, 92, 246, 0.22)',
                  border: '1px solid rgba(167, 139, 250, 0.48)',
                  fontFamily: FONT_MONO,
                  fontSize: 12,
                  fontWeight: 700,
                  color: COLORS.violetBright,
                }}
              >
                1-TAP QUICKBAR
              </span>
            </div>

            {/* 3 Animated Toggle Switches */}
            <div style={{ display: 'flex', flexDirection: 'column', gap: 11 }}>
              {ghostToggles.map((item) => {
                const toggleProg = spring({
                  frame: frame - item.delay,
                  fps,
                  config: SPRING_SNAPPY,
                });
                const knobX = interpolate(toggleProg, [0, 1], [3, 25], {
                  extrapolateLeft: 'clamp',
                  extrapolateRight: 'clamp',
                });
                const isOn = toggleProg > 0.5;
                return (
                  <div
                    key={item.label}
                    style={{
                      padding: '10px 15px',
                      borderRadius: 13,
                      backgroundColor: 'rgba(255, 255, 255, 0.045)',
                      border: `1px solid ${isOn ? 'rgba(167, 139, 250, 0.36)' : 'rgba(255,255,255,0.08)'}`,
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                    }}
                  >
                    <span style={{ fontSize: 17, fontWeight: 600, color: COLORS.textPrimary }}>
                      {item.label}
                    </span>
                    <div
                      style={{
                        width: 48,
                        height: 26,
                        borderRadius: 999,
                        backgroundColor: isOn ? COLORS.violet : 'rgba(255,255,255,0.16)',
                        position: 'relative',
                        boxShadow: isOn ? `0 0 14px ${COLORS.violet}88` : 'none',
                        flexShrink: 0,
                      }}
                    >
                      <div
                        style={{
                          position: 'absolute',
                          top: 3,
                          left: knobX,
                          width: 20,
                          height: 20,
                          borderRadius: 999,
                          backgroundColor: '#FFFFFF',
                        }}
                      />
                    </div>
                  </div>
                );
              })}
            </div>
          </GlassCard>

          {/* CARD 3 — AES-256 Скрытые аккаунты */}
          <GlassCard
            delay={36}
            accent={COLORS.emerald}
            glow
            style={{
              padding: '26px 28px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              gap: 14,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div
                  style={{
                    width: 50,
                    height: 50,
                    borderRadius: 15,
                    backgroundColor: 'rgba(16, 185, 129, 0.18)',
                    border: '1px solid rgba(52, 211, 153, 0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <LockIcon size={26} color={COLORS.emeraldBright} />
                </div>
                <div>
                  <div
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 12.5,
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      color: COLORS.emeraldBright,
                    }}
                  >
                    ENCRYPTED MULTI-PROFILE
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: COLORS.textPrimary }}>
                    AES-256 Скрытые аккаунты
                  </div>
                </div>
              </div>
              <span
                style={{
                  padding: '5px 12px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(16, 185, 129, 0.18)',
                  border: '1px solid rgba(52, 211, 153, 0.45)',
                  fontFamily: FONT_MONO,
                  fontSize: 12,
                  fontWeight: 700,
                  color: COLORS.emeraldBright,
                }}
              >
                AES-256-GCM
              </span>
            </div>

            {/* Animated PIN Passcode Bar */}
            <div
              style={{
                padding: '16px 20px',
                borderRadius: 16,
                backgroundColor: 'rgba(16, 185, 129, 0.08)',
                border: '1px solid rgba(52, 211, 153, 0.32)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
                {[0, 1, 2, 3, 4, 5].map((idx) => {
                  const active = idx < pinDotsLit;
                  return (
                    <div
                      key={idx}
                      style={{
                        width: 18,
                        height: 18,
                        borderRadius: 999,
                        backgroundColor: active ? COLORS.emeraldBright : 'rgba(255,255,255,0.14)',
                        boxShadow: active ? `0 0 14px ${COLORS.emeraldBright}` : 'none',
                      }}
                    />
                  );
                })}
              </div>

              <div
                style={{
                  display: 'flex',
                  alignItems: 'center',
                  gap: 8,
                  opacity: interpolate(vaultUnlocked, [0, 1], [0.25, 1], {
                    extrapolateLeft: 'clamp',
                    extrapolateRight: 'clamp',
                  }),
                }}
              >
                <CheckCircleIcon size={20} color={COLORS.emeraldBright} />
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 14,
                    fontWeight: 700,
                    color: COLORS.emeraldBright,
                  }}
                >
                  СЕКРЕТНЫЙ ПРОФИЛЬ ЗАЩИЩЁН
                </span>
              </div>
            </div>

            <div style={{ fontSize: 16.5, color: COLORS.textSecondary, lineHeight: 1.38 }}>
              Изолированные профили с отдельным ПИН-кодом, шифрованием ключей и спуфингом устройства
              (Hardware Cloaking).
            </div>
          </GlassCard>

          {/* CARD 4 — Colemailer AI Anti-Spam + Временная почта */}
          <GlassCard
            delay={48}
            accent={COLORS.amber}
            glow
            style={{
              padding: '26px 28px',
              display: 'flex',
              flexDirection: 'column',
              justifyContent: 'space-between',
              gap: 14,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
                <div
                  style={{
                    width: 50,
                    height: 50,
                    borderRadius: 15,
                    backgroundColor: 'rgba(245, 158, 11, 0.18)',
                    border: '1px solid rgba(251, 191, 36, 0.5)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'center',
                  }}
                >
                  <MailShieldIcon size={26} color={COLORS.amberBright} />
                </div>
                <div>
                  <div
                    style={{
                      fontFamily: FONT_MONO,
                      fontSize: 12.5,
                      fontWeight: 700,
                      letterSpacing: '0.08em',
                      color: COLORS.amberBright,
                    }}
                  >
                    COLEMAILER ENGINE
                  </div>
                  <div style={{ fontSize: 26, fontWeight: 800, color: COLORS.textPrimary }}>
                    AI Anti-Spam + Временная почта
                  </div>
                </div>
              </div>
              <span
                style={{
                  fontFamily: FONT_MONO,
                  fontSize: 20,
                  fontWeight: 900,
                  color: COLORS.amberBright,
                }}
              >
                {spamRate.toFixed(1)}%
              </span>
            </div>

            {/* Disposable Mailbox Pill */}
            <div
              style={{
                padding: '14px 18px',
                borderRadius: 16,
                backgroundColor: 'rgba(245, 158, 11, 0.1)',
                border: '1px solid rgba(251, 191, 36, 0.36)',
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                gap: 12,
              }}
            >
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                <span style={{ fontFamily: FONT_MONO, fontSize: 11.5, color: COLORS.amberBright }}>
                  ВРЕМЕННЫЙ ЯЩИК · АВТО-ГЕНЕРАЦИЯ
                </span>
                <span
                  style={{
                    fontFamily: FONT_MONO,
                    fontSize: 18,
                    fontWeight: 700,
                    color: COLORS.textPrimary,
                  }}
                >
                  shield-94x@colemailer.temp
                </span>
              </div>
              <span
                style={{
                  padding: '6px 12px',
                  borderRadius: 10,
                  backgroundColor: 'rgba(16, 185, 129, 0.2)',
                  border: '1px solid rgba(52, 211, 153, 0.45)',
                  fontFamily: FONT_MONO,
                  fontSize: 12,
                  fontWeight: 700,
                  color: COLORS.emeraldBright,
                }}
              >
                СПАМ ОТСЕЧЁН
              </span>
            </div>

            <div style={{ fontSize: 16.5, color: COLORS.textSecondary, lineHeight: 1.38 }}>
              Локальный ИИ-фильтр Colemailer блокирует фишинг и спам, а встроенная временная почта
              защищает ваш реальный e-mail при регистрациях.
            </div>
          </GlassCard>
        </div>
      </AbsoluteFill>
    </AbsoluteFill>
  );
};

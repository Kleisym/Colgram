import React from 'react';
import { AbsoluteFill, Img, interpolate, staticFile, useCurrentFrame } from 'remotion';
import { COLORS, EASE_APPLE, EASE_OUT_CUBIC, FONT_SANS, revealMotion } from '../theme';
import { StudioPhone } from '../components/StudioPhone';
import { TelegramLottie } from '../components/TelegramLottie';

const WAVE_BAR_HEIGHTS = [
  10, 18, 28, 16, 32, 24, 14, 30, 36, 22, 16, 28, 34, 20, 14, 26, 18, 12,
];

export const Scene3Koliti: React.FC = () => {
  const frame = useCurrentFrame();

  const duckReveal = revealMotion(frame, 10, 38, 22);
  const titleReveal = revealMotion(frame, 20, 42, 26);
  const subReveal = revealMotion(frame, 34, 42, 20);
  const lineProgress = interpolate(frame, [48, 86], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });

  // Chat choreography inside the phone
  const userMsg = revealMotion(frame, 22, 30, 18);

  // Typing indicator appears softly between user message and Koliti reply
  const typingIn = interpolate(frame, [58, 72], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_APPLE,
  });
  const typingOut = interpolate(frame, [108, 122], [1, 0], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });
  const typingOpacity = typingIn * typingOut;

  const replyMsg = revealMotion(frame, 114, 34, 22);
  const confirmFade = interpolate(frame, [148, 174], [0, 1], {
    extrapolateLeft: 'clamp',
    extrapolateRight: 'clamp',
    easing: EASE_OUT_CUBIC,
  });

  return (
    <AbsoluteFill
      style={{
        fontFamily: FONT_SANS,
        padding: '0 148px',
        display: 'flex',
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        gap: 90,
      }}
    >
      {/* Left Column: Editorial Text + Listening/Reacting Utya Duck */}
      <div
        style={{
          flex: 1,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'flex-start',
          maxWidth: 880,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 24,
            marginBottom: 28,
            opacity: duckReveal.opacity,
            transform: `translateY(${duckReveal.translateY}px)`,
          }}
        >
          <TelegramLottie
            name="utyan_streaming"
            size={188}
            playbackRate={0.9}
          />
        </div>

        <h2
          style={{
            margin: 0,
            fontSize: 98,
            fontWeight: 800,
            lineHeight: 1.05,
            letterSpacing: '-0.038em',
            color: COLORS.textPrimary,
            opacity: titleReveal.opacity,
            transform: `translateY(${titleReveal.translateY}px)`,
          }}
        >
          Колити ИИ
        </h2>

        <p
          style={{
            margin: '26px 0 0 0',
            fontSize: 33,
            fontWeight: 400,
            lineHeight: 1.42,
            letterSpacing: '-0.012em',
            color: COLORS.textSecondary,
            maxWidth: 740,
            opacity: subReveal.opacity,
            transform: `translateY(${subReveal.translateY}px)`,
          }}
        >
          Умный помощник в чатах —
          <br />
          работает даже офлайн
        </p>

        <div
          style={{
            marginTop: 40,
            width: 72 * lineProgress,
            height: 3.5,
            borderRadius: 999,
            background: `linear-gradient(90deg, ${COLORS.colaRed}, ${COLORS.caramelAmber})`,
            opacity: lineProgress,
          }}
        />
      </div>

      {/* Right Column: Minimalist Clean Telegram Chat Mockup */}
      <div
        style={{
          flexShrink: 0,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          paddingRight: 16,
        }}
      >
        <StudioPhone
          width={436}
          height={804}
          delay={6}
          enterDistanceY={42}
          showDynamicIsland
          ambientGlow="rgba(245, 158, 11, 0.17)"
        >
          <div
            style={{
              width: '100%',
              height: '100%',
              display: 'flex',
              flexDirection: 'column',
              background:
                'linear-gradient(180deg, #171315 0%, #120F11 58%, #0E0B0C 100%)',
            }}
          >
            {/* Clean Telegram Top Bar */}
            <div
              style={{
                padding: '48px 22px 16px 22px',
                display: 'flex',
                alignItems: 'center',
                gap: 14,
                borderBottom: '1px solid rgba(255, 248, 244, 0.08)',
                backgroundColor: 'rgba(24, 18, 18, 0.72)',
              }}
            >
              <Img
                src={staticFile('koliti-avatar.png')}
                style={{
                  width: 46,
                  height: 46,
                  borderRadius: '50%',
                  objectFit: 'cover',
                  boxShadow: '0 4px 14px rgba(0,0,0,0.45)',
                }}
              />
              <div style={{ display: 'flex', flexDirection: 'column' }}>
                <span
                  style={{
                    fontSize: 19,
                    fontWeight: 600,
                    color: COLORS.textPrimary,
                    letterSpacing: '-0.01em',
                  }}
                >
                  Колити
                </span>
                <span
                  style={{
                    fontSize: 13.5,
                    fontWeight: 400,
                    color: 'rgba(245, 158, 11, 0.85)',
                    marginTop: 1,
                  }}
                >
                  умный ассистент · офлайн
                </span>
              </div>
            </div>

            {/* Chat Canvas */}
            <div
              style={{
                flex: 1,
                padding: '26px 20px',
                display: 'flex',
                flexDirection: 'column',
                gap: 18,
                position: 'relative',
              }}
            >
              {/* Quiet Date Separator */}
              <div
                style={{
                  alignSelf: 'center',
                  padding: '5px 14px',
                  borderRadius: 999,
                  backgroundColor: 'rgba(255, 248, 244, 0.06)',
                  fontSize: 12.5,
                  fontWeight: 500,
                  color: COLORS.textMuted,
                  marginBottom: 4,
                }}
              >
                Сегодня
              </div>

              {/* Incoming User Message (Voice + Request) */}
              <div
                style={{
                  alignSelf: 'flex-end',
                  maxWidth: '86%',
                  padding: '15px 18px 12px 18px',
                  borderRadius: '22px 22px 6px 22px',
                  background: 'linear-gradient(145deg, #B92B37 0%, #8C1D27 100%)',
                  boxShadow: '0 10px 24px rgba(0, 0, 0, 0.34)',
                  opacity: userMsg.opacity,
                  transform: `translateY(${userMsg.translateY}px)`,
                }}
              >
                {/* Mini voice strip inside user request */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 10,
                    marginBottom: 10,
                    paddingBottom: 10,
                    borderBottom: '1px solid rgba(255,255,255,0.16)',
                  }}
                >
                  <div
                    style={{
                      width: 30,
                      height: 30,
                      borderRadius: '50%',
                      backgroundColor: 'rgba(255,255,255,0.2)',
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'center',
                      flexShrink: 0,
                    }}
                  >
                    <svg width="11" height="12" viewBox="0 0 11 12" fill="none">
                      <path d="M2 1.5L9.5 6L2 10.5V1.5Z" fill="#FAF7F5" />
                    </svg>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 3, flex: 1 }}>
                    {[8, 15, 11, 20, 14, 18, 10, 16, 12, 19, 13, 9].map((h, i) => (
                      <div
                        key={i}
                        style={{
                          width: 3,
                          height: h,
                          borderRadius: 999,
                          backgroundColor: 'rgba(255,255,255,0.82)',
                        }}
                      />
                    ))}
                  </div>
                  <span style={{ fontSize: 12, color: 'rgba(255,255,255,0.78)' }}>
                    02:14
                  </span>
                </div>

                <div
                  style={{
                    fontSize: 16.5,
                    lineHeight: 1.42,
                    color: '#FAF7F5',
                    fontWeight: 400,
                  }}
                >
                  Перескажи коротко и включи тёплую тему
                </div>
                <div
                  style={{
                    marginTop: 6,
                    fontSize: 11.5,
                    color: 'rgba(255,255,255,0.68)',
                    textAlign: 'right',
                  }}
                >
                  18:34 ✓✓
                </div>
              </div>

              {/* Sleek Typing Indicator Dots (transitions smoothly out as reply arrives) */}
              {typingOpacity > 0.01 && (
                <div
                  style={{
                    position: 'absolute',
                    left: 20,
                    top: 238,
                    display: 'inline-flex',
                    alignItems: 'center',
                    gap: 8,
                    padding: '14px 18px',
                    borderRadius: '20px 20px 20px 6px',
                    backgroundColor: 'rgba(255, 248, 244, 0.075)',
                    border: '1px solid rgba(255, 248, 244, 0.1)',
                    opacity: typingOpacity,
                  }}
                >
                  {[0, 1, 2].map((dot) => {
                    const lift = Math.sin(frame * 0.22 - dot * 0.9) * 3.5;
                    const dotOp = 0.45 + (Math.sin(frame * 0.22 - dot * 0.9) + 1) * 0.27;
                    return (
                      <div
                        key={dot}
                        style={{
                          width: 8,
                          height: 8,
                          borderRadius: '50%',
                          backgroundColor: COLORS.champagne,
                          opacity: dotOp,
                          transform: `translateY(${lift}px)`,
                        }}
                      />
                    );
                  })}
                </div>
              )}

              {/* Koliti AI Clean Russian Reply with Smooth Audio Wave Indicator */}
              <div
                style={{
                  alignSelf: 'flex-start',
                  maxWidth: '90%',
                  padding: '16px 18px 14px 18px',
                  borderRadius: '22px 22px 22px 6px',
                  backgroundColor: 'rgba(255, 248, 244, 0.07)',
                  border: '1px solid rgba(255, 248, 244, 0.11)',
                  boxShadow: '0 14px 32px rgba(0, 0, 0, 0.34)',
                  opacity: replyMsg.opacity,
                  transform: `translateY(${replyMsg.translateY}px)`,
                }}
              >
                {/* Live Audio Wave Indicator */}
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    gap: 12,
                    marginBottom: 14,
                    padding: '10px 12px',
                    borderRadius: 14,
                    backgroundColor: 'rgba(245, 158, 11, 0.08)',
                    border: '1px solid rgba(245, 158, 11, 0.16)',
                  }}
                >
                  <div
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 3.5,
                      height: 36,
                    }}
                  >
                    {WAVE_BAR_HEIGHTS.map((baseH, idx) => {
                      const wave =
                        0.45 +
                        0.55 *
                          Math.abs(Math.sin(frame * 0.13 + idx * 0.52));
                      const h = Math.max(6, Math.round(baseH * wave));
                      return (
                        <div
                          key={idx}
                          style={{
                            width: 3.5,
                            height: h,
                            borderRadius: 999,
                            background: `linear-gradient(180deg, ${COLORS.caramelAmber}, ${COLORS.colaRed})`,
                          }}
                        />
                      );
                    })}
                  </div>
                  <span
                    style={{
                      marginLeft: 'auto',
                      fontSize: 12.5,
                      fontWeight: 500,
                      color: COLORS.champagne,
                      whiteSpace: 'nowrap',
                    }}
                  >
                    Кратко
                  </span>
                </div>

                {/* Summary text */}
                <div
                  style={{
                    fontSize: 16.5,
                    lineHeight: 1.46,
                    color: COLORS.textPrimary,
                    fontWeight: 400,
                  }}
                >
                  Встречу перенесли на <strong style={{ fontWeight: 600 }}>19:00</strong>,
                  макеты уже согласованы — можно выходить в релиз.
                </div>

                {/* Clean subtle action confirmation */}
                <div
                  style={{
                    marginTop: 12,
                    paddingTop: 10,
                    borderTop: '1px solid rgba(255, 248, 244, 0.08)',
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    opacity: confirmFade,
                  }}
                >
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <span
                      style={{
                        color: COLORS.mintConnected,
                        fontSize: 14,
                        fontWeight: 700,
                      }}
                    >
                      ✓
                    </span>
                    <span
                      style={{
                        fontSize: 14,
                        fontWeight: 500,
                        color: 'rgba(250, 247, 245, 0.82)',
                      }}
                    >
                      Тёплая тема включена
                    </span>
                  </div>
                  <span style={{ fontSize: 11.5, color: COLORS.textMuted }}>
                    18:34
                  </span>
                </div>
              </div>
            </div>

            {/* Minimal Bottom Chat Input Bar */}
            <div
              style={{
                padding: '14px 18px 26px 18px',
                borderTop: '1px solid rgba(255, 248, 244, 0.06)',
                display: 'flex',
                alignItems: 'center',
                gap: 12,
                backgroundColor: 'rgba(18, 14, 15, 0.86)',
              }}
            >
              <div
                style={{
                  flex: 1,
                  height: 42,
                  borderRadius: 999,
                  backgroundColor: 'rgba(255, 248, 244, 0.055)',
                  border: '1px solid rgba(255, 248, 244, 0.08)',
                  display: 'flex',
                  alignItems: 'center',
                  paddingLeft: 18,
                  fontSize: 15,
                  color: COLORS.textMuted,
                }}
              >
                Сообщение...
              </div>
              <div
                style={{
                  width: 42,
                  height: 42,
                  borderRadius: '50%',
                  background: `linear-gradient(145deg, ${COLORS.colaRed}, #A91E2A)`,
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexShrink: 0,
                }}
              >
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none">
                  <rect
                    x="9"
                    y="3"
                    width="6"
                    height="11"
                    rx="3"
                    stroke="#FAF7F5"
                    strokeWidth="2"
                  />
                  <path
                    d="M5 11C5 14.866 8.134 18 12 18C15.866 18 19 14.866 19 11"
                    stroke="#FAF7F5"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                  <path
                    d="M12 18V21"
                    stroke="#FAF7F5"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                </svg>
              </div>
            </div>
          </div>
        </StudioPhone>
      </div>
    </AbsoluteFill>
  );
};

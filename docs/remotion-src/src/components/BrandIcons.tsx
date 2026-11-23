import React from 'react';

interface IconProps {
  size?: number;
  color?: string;
  strokeWidth?: number;
}

export const ShieldCheckIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M12 2.5L4.5 5.8V11.6C4.5 16.5 7.7 20.9 12 22C16.3 20.9 19.5 16.5 19.5 11.6V5.8L12 2.5Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <path
      d="M8.8 12.2L11 14.4L15.4 9.8"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export const WifiIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M3 9.5C8 4.8 16 4.8 21 9.5"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <path
      d="M6.2 13C9.4 9.8 14.6 9.8 17.8 13"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <path
      d="M9.5 16.3C10.9 14.9 13.1 14.9 14.5 16.3"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <circle cx="12" cy="19.3" r="1.4" fill={color} />
  </svg>
);

export const SignalTowerIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M4.9 7.1C3.2 9.4 3.2 12.6 4.9 14.9M19.1 7.1C20.8 9.4 20.8 12.6 19.1 14.9"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <path
      d="M7.8 9.2C6.8 10.5 6.8 11.5 7.8 12.8M16.2 9.2C17.2 10.5 17.2 11.5 16.2 12.8"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <circle cx="12" cy="11" r="2" fill={color} />
    <path
      d="M12 13L8.5 21H15.5L12 13Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
  </svg>
);

export const GlobeIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="9.2" stroke={color} strokeWidth={strokeWidth} />
    <ellipse cx="12" cy="12" rx="4.2" ry="9.2" stroke={color} strokeWidth={strokeWidth} />
    <path d="M3.2 9.2H20.8M3.2 14.8H20.8" stroke={color} strokeWidth={strokeWidth} />
  </svg>
);

export const CpuIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <rect x="6.5" y="6.5" width="11" height="11" rx="2.2" stroke={color} strokeWidth={strokeWidth} />
    <rect x="9.5" y="9.5" width="5" height="5" rx="0.8" fill={color} fillOpacity={0.35} stroke={color} strokeWidth={1.5} />
    <path
      d="M9 3.5V6.5M15 3.5V6.5M9 17.5V20.5M15 17.5V20.5M3.5 9H6.5M3.5 15H6.5M17.5 9H20.5M17.5 15H20.5"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
  </svg>
);

export const EyeScanIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M2.5 12C4.6 7.8 8 5.8 12 5.8C16 5.8 19.4 7.8 21.5 12C19.4 16.2 16 18.2 12 18.2C8 18.2 4.6 16.2 2.5 12Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <circle cx="12" cy="12" r="3" stroke={color} strokeWidth={strokeWidth} />
  </svg>
);

export const MicWaveIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <rect x="9" y="3.5" width="6" height="10.5" rx="3" stroke={color} strokeWidth={strokeWidth} />
    <path
      d="M5.8 11.2C5.8 14.6 8.6 17.4 12 17.4C15.4 17.4 18.2 14.6 18.2 11.2M12 17.4V20.8M8.8 20.8H15.2"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
  </svg>
);

export const SparklesIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 1.9,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M12 3L13.9 8.7L19.6 10.6L13.9 12.5L12 18.2L10.1 12.5L4.4 10.6L10.1 8.7L12 3Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <path
      d="M18.5 15.5L19.3 17.7L21.5 18.5L19.3 19.3L18.5 21.5L17.7 19.3L15.5 18.5L17.7 17.7L18.5 15.5Z"
      fill={color}
    />
  </svg>
);

export const TerminalIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <rect x="3" y="4.5" width="18" height="15" rx="2.5" stroke={color} strokeWidth={strokeWidth} />
    <path
      d="M7.5 9.5L10.5 12L7.5 14.5M12.5 14.5H16.5"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export const DatabaseIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <ellipse cx="12" cy="6" rx="7.5" ry="2.8" stroke={color} strokeWidth={strokeWidth} />
    <path
      d="M4.5 6V12C4.5 13.6 7.9 14.8 12 14.8C16.1 14.8 19.5 13.6 19.5 12V6"
      stroke={color}
      strokeWidth={strokeWidth}
    />
    <path
      d="M4.5 12V18C4.5 19.6 7.9 20.8 12 20.8C16.1 20.8 19.5 19.6 19.5 18V12"
      stroke={color}
      strokeWidth={strokeWidth}
    />
  </svg>
);

export const LayersIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path d="M12 3.5L3 8.2L12 12.9L21 8.2L12 3.5Z" stroke={color} strokeWidth={strokeWidth} strokeLinejoin="round" />
    <path d="M3 12.2L12 16.9L21 12.2" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
    <path d="M3 16.2L12 20.9L21 16.2" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const LockIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <rect x="4.8" y="10.5" width="14.4" height="10.2" rx="2.4" stroke={color} strokeWidth={strokeWidth} />
    <path
      d="M8.2 10.5V7.5C8.2 5.4 9.9 3.7 12 3.7C14.1 3.7 15.8 5.4 15.8 7.5V10.5"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
    />
    <circle cx="12" cy="15.5" r="1.4" fill={color} />
  </svg>
);

export const GhostIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M5 20V11C5 7.1 8.1 4 12 4C15.9 4 19 7.1 19 11V20L16.5 18L14.2 20L12 18L9.8 20L7.5 18L5 20Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <circle cx="9.6" cy="10.8" r="1.2" fill={color} />
    <circle cx="14.4" cy="10.8" r="1.2" fill={color} />
  </svg>
);

export const HistoryVaultIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M4.2 12A7.8 7.8 0 1 0 6.8 6.2L4.2 8.6"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <path
      d="M4.2 4.8V8.6H8"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
    <path
      d="M12 8.2V12.4L14.8 14"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export const MailShieldIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <rect x="3" y="5" width="18" height="14" rx="2.5" stroke={color} strokeWidth={strokeWidth} />
    <path d="M4 6.5L12 12.6L20 6.5" stroke={color} strokeWidth={strokeWidth} strokeLinecap="round" strokeLinejoin="round" />
  </svg>
);

export const PaletteIcon: React.FC<IconProps> = ({
  size = 24,
  color = 'currentColor',
  strokeWidth = 2,
}) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M12 3.2C7.1 3.2 3.2 7.1 3.2 12C3.2 16.9 7.1 20.8 12 20.8C13.4 20.8 14.4 19.8 14.4 18.6C14.4 17.9 14.1 17.4 13.8 17C13.4 16.5 13.8 15.8 14.4 15.8H16.6C19.1 15.8 20.8 13.9 20.8 11.4C20.8 6.8 16.9 3.2 12 3.2Z"
      stroke={color}
      strokeWidth={strokeWidth}
      strokeLinejoin="round"
    />
    <circle cx="8" cy="10.2" r="1.2" fill={color} />
    <circle cx="11.8" cy="7.6" r="1.2" fill={color} />
    <circle cx="15.8" cy="9.8" r="1.2" fill={color} />
  </svg>
);

export const GithubIcon: React.FC<IconProps> = ({ size = 24, color = 'currentColor' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill={color}>
    <path
      fillRule="evenodd"
      clipRule="evenodd"
      d="M12 2C6.48 2 2 6.58 2 12.23C2 16.75 4.87 20.58 8.84 21.93C9.34 22.02 9.52 21.71 9.52 21.44C9.52 21.2 9.51 20.55 9.51 19.7C6.73 20.32 6.14 18.33 6.14 18.33C5.68 17.15 5.03 16.84 5.03 16.84C4.12 16.2 5.1 16.21 5.1 16.21C6.1 16.28 6.63 17.27 6.63 17.27C7.52 18.83 8.97 18.38 9.54 18.12C9.63 17.46 9.89 17.01 10.17 16.76C7.95 16.5 5.62 15.62 5.62 11.7C5.62 10.58 6.01 9.67 6.65 8.95C6.55 8.69 6.2 7.64 6.75 6.22C6.75 6.22 7.59 5.94 9.5 7.27C10.3 7.04 11.15 6.93 12 6.93C12.85 6.93 13.7 7.04 14.5 7.27C16.41 5.94 17.25 6.22 17.25 6.22C17.8 7.64 17.45 8.69 17.35 8.95C17.99 9.67 18.38 10.58 18.38 11.7C18.38 15.63 16.05 16.5 13.82 16.75C14.18 17.07 14.5 17.69 14.5 18.65C14.5 20.02 14.49 21.12 14.49 21.44C14.49 21.71 14.67 22.03 15.18 21.93C19.14 20.58 22 16.75 22 12.23C22 6.58 17.52 2 12 2Z"
    />
  </svg>
);

export const CheckCircleIcon: React.FC<IconProps> = ({ size = 20, color = '#10B981' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <circle cx="12" cy="12" r="9.5" fill={color} fillOpacity={0.18} stroke={color} strokeWidth={2} />
    <path
      d="M8.2 12.2L10.8 14.8L16 9.4"
      stroke={color}
      strokeWidth={2.2}
      strokeLinecap="round"
      strokeLinejoin="round"
    />
  </svg>
);

export const BoltIcon: React.FC<IconProps> = ({ size = 22, color = 'currentColor' }) => (
  <svg width={size} height={size} viewBox="0 0 24 24" fill="none">
    <path
      d="M13.2 2.5L5 13.2H11.6L10.4 21.5L19 10.6H12.4L13.2 2.5Z"
      fill={color}
      fillOpacity={0.22}
      stroke={color}
      strokeWidth={2}
      strokeLinejoin="round"
    />
  </svg>
);

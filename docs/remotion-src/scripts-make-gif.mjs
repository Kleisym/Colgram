import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ffmpeg = path.resolve(
  'node_modules/@remotion/compositor-win32-x64-msvc/ffmpeg.exe',
);
const mp4Path = path.resolve('../Colgram/docs/media/colgram-release.mp4');
const faststartTmp = path.resolve('preview-stills/colgram-release-faststart.mp4');
const distMp4Path = path.resolve('../Colgram/dist/colgram-release.mp4');
const gifPath = path.resolve('../Colgram/docs/media/colgram-release-preview.gif');
const palettePath = path.resolve('preview-stills/palette.png');

fs.mkdirSync(path.dirname(faststartTmp), { recursive: true });
fs.mkdirSync(path.dirname(distMp4Path), { recursive: true });

// Ensure MP4 moov atom is placed at the start (+faststart) for instant web/GitHub streaming playback
console.log('Remuxing MP4 losslessly with -movflags +faststart...');
execFileSync(
  ffmpeg,
  [
    '-y',
    '-i',
    mp4Path,
    '-c',
    'copy',
    '-movflags',
    '+faststart',
    faststartTmp,
  ],
  { stdio: 'pipe' },
);
fs.renameSync(faststartTmp, mp4Path);

fs.copyFileSync(mp4Path, distMp4Path);
console.log('Copied MP4 ->', distMp4Path, `(${fs.statSync(distMp4Path).size} bytes)`);

// Full 30s 768x432 preview GIF tuned cleanly under GitHub's 10 MB inline embed cap
const attempts = [
  { fps: 10, colors: 96, dither: 'bayer:bayer_scale=4' },
  { fps: 8, colors: 80, dither: 'bayer:bayer_scale=5' },
  { fps: 7, colors: 64, dither: 'bayer:bayer_scale=5' },
  { fps: 6, colors: 64, dither: 'none' },
];

for (const cfg of attempts) {
  console.log(`Generating 768x432 GIF @ ${cfg.fps}fps, ${cfg.colors} colors (${cfg.dither})...`);
  execFileSync(
    ffmpeg,
    [
      '-y',
      '-i',
      mp4Path,
      '-r',
      String(cfg.fps),
      '-vf',
      `scale=768:432:flags=lanczos,palettegen=max_colors=${cfg.colors}:stats_mode=diff`,
      palettePath,
    ],
    { stdio: 'pipe' },
  );

  execFileSync(
    ffmpeg,
    [
      '-y',
      '-i',
      mp4Path,
      '-i',
      palettePath,
      '-r',
      String(cfg.fps),
      '-lavfi',
      `scale=768:432:flags=lanczos[x];[x][1:v]paletteuse=dither=${cfg.dither}:diff_mode=rectangle`,
      '-loop',
      '0',
      gifPath,
    ],
    { stdio: 'pipe' },
  );

  const bytes = fs.statSync(gifPath).size;
  const mb = (bytes / (1024 * 1024)).toFixed(2);
  console.log(`GIF size: ${mb} MB (${bytes} bytes)`);
  if (bytes < 9.5 * 1024 * 1024) {
    console.log('GIF is cleanly under GitHub 10 MB inline limit!');
    break;
  }
}

if (fs.existsSync(palettePath)) {
  fs.unlinkSync(palettePath);
}

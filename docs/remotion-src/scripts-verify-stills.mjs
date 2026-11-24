import path from 'node:path';
import fs from 'node:fs';
import { bundle } from '@remotion/bundler';
import { renderStill, selectComposition } from '@remotion/renderer';

const outDir = path.resolve('preview-stills');
fs.mkdirSync(outDir, { recursive: true });

// Clear stale v2 stills so only fresh v3 cola-edition stills exist
for (const f of fs.readdirSync(outDir)) {
  if (f.endsWith('.png')) {
    fs.unlinkSync(path.join(outDir, f));
  }
}

console.log('Bundling Colgram Remotion v3 (Iced Cola Edition)...');
const bundleLocation = await bundle({
  entryPoint: path.resolve('src/index.ts'),
  publicDir: path.resolve('public'),
});

const posterComp = await selectComposition({
  serveUrl: bundleLocation,
  id: 'ColgramPoster',
});

const posterPath = path.resolve('../Colgram/docs/media/colgram-release-poster.png');
fs.mkdirSync(path.dirname(posterPath), { recursive: true });
console.log('Rendering ColgramPoster ->', posterPath);
await renderStill({
  composition: posterComp,
  serveUrl: bundleLocation,
  output: posterPath,
  imageFormat: 'png',
});

const releaseComp = await selectComposition({
  serveUrl: bundleLocation,
  id: 'ColgramRelease',
});

const framesToCheck = [
  { name: 'scene1-hero.png', frame: 120 },         // 2.0s  - Scene 1 Cold Open
  { name: 'scene2-bypass.png', frame: 420 },       // 7.0s  - Scene 2 Real Phone + Bypass
  { name: 'scene3-koliti-typing.png', frame: 642 }, // 10.7s - Scene 3 Typing Dots
  { name: 'scene3-koliti.png', frame: 750 },       // 12.5s - Scene 3 Koliti Reply + Wave
  { name: 'scene4-features.png', frame: 1050 },    // 17.5s - Scene 4 Memory / Ghost / Style
  { name: 'scene5-glass-ready.png', frame: 1236 }, // 20.6s - Scene 5 Empty Crystal Glass + Ice
  { name: 'scene5-pour-start.png', frame: 1320 },  // 22.0s - Scene 5 Pour Start + Arched Stream
  { name: 'scene5-mid-pour.png', frame: 1470 },    // 24.5s - Scene 5 Mid-Pour Ice Bob + Foam
  { name: 'scene5-finale.png', frame: 1710 },      // 28.5s - Scene 5 Finale Reveal
];

for (const item of framesToCheck) {
  const target = path.join(outDir, item.name);
  console.log(`Rendering frame ${item.frame} -> ${item.name}`);
  await renderStill({
    composition: releaseComp,
    serveUrl: bundleLocation,
    output: target,
    frame: item.frame,
    imageFormat: 'png',
  });
}

console.log('All v3 verification stills rendered successfully!');

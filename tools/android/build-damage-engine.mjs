import {mkdir} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {build} from 'esbuild';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const outputDir = path.join(repoRoot, 'android-app', 'app', 'src', 'main', 'assets');
const outputFile = path.join(outputDir, 'damage-engine.js');

await mkdir(outputDir, {recursive: true});
await build({
  entryPoints: [path.join(repoRoot, 'src', 'damage', 'androidBridge.ts')],
  outfile: outputFile,
  bundle: true,
  format: 'iife',
  platform: 'browser',
  target: ['chrome109'],
  charset: 'utf8',
  legalComments: 'linked',
  sourcemap: false,
  minify: true,
  logLevel: 'info',
});

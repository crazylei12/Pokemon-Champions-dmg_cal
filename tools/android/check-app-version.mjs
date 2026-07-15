import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const packageJson = JSON.parse(fs.readFileSync(path.join(repoRoot, 'package.json'), 'utf8'));
const packageLock = JSON.parse(fs.readFileSync(path.join(repoRoot, 'package-lock.json'), 'utf8'));
const semanticVersionPattern = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

if (!semanticVersionPattern.test(packageJson.version)) {
  throw new Error(`package.json version must be semantic, received: ${packageJson.version}`);
}
if (!Number.isSafeInteger(packageJson.androidVersionCode) || packageJson.androidVersionCode <= 0) {
  throw new Error('package.json androidVersionCode must be a positive integer');
}
if (packageLock.version !== packageJson.version || packageLock.packages?.['']?.version !== packageJson.version) {
  throw new Error('package-lock.json version does not match package.json');
}

console.log(`app-version-ok: ${packageJson.version} (${packageJson.androidVersionCode})`);

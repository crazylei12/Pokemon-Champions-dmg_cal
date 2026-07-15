import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const [nextVersion, nextCodeText] = process.argv.slice(2);
const semanticVersionPattern = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;
const nextCode = Number(nextCodeText);

if (!nextVersion || !semanticVersionPattern.test(nextVersion) || !Number.isSafeInteger(nextCode) || nextCode <= 0) {
  throw new Error('Usage: npm.cmd run version:set -- <semantic-version> <positive-version-code>');
}

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const packagePath = path.join(repoRoot, 'package.json');
const lockPath = path.join(repoRoot, 'package-lock.json');
const packageJson = JSON.parse(fs.readFileSync(packagePath, 'utf8'));
const packageLock = JSON.parse(fs.readFileSync(lockPath, 'utf8'));

if (nextCode <= packageJson.androidVersionCode) {
  throw new Error(`Android version code must increase beyond ${packageJson.androidVersionCode}`);
}

packageJson.version = nextVersion;
packageJson.androidVersionCode = nextCode;
packageLock.version = nextVersion;
packageLock.packages[''].version = nextVersion;

fs.writeFileSync(packagePath, `${JSON.stringify(packageJson, null, 2)}\n`);
fs.writeFileSync(lockPath, `${JSON.stringify(packageLock, null, 2)}\n`);
console.log(`Updated app version to ${nextVersion} (${nextCode})`);

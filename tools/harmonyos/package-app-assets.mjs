import { createHash } from 'node:crypto';
import { copyFile, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const configPath = path.join(repositoryRoot, 'config', 'harmonyos-app-build.json');
const outputRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'resources', 'rawfile', 'runtime');

async function sha256(filePath) {
  const bytes = await readFile(filePath);
  return createHash('sha256').update(bytes).digest('hex');
}

const config = JSON.parse(await readFile(configPath, 'utf8'));
await rm(outputRoot, { recursive: true, force: true });
await mkdir(outputRoot, { recursive: true });

const packagedAssets = [];
for (const asset of config.runtimeAssets) {
  const sourcePath = path.join(repositoryRoot, ...asset.source.split('/'));
  const actualHash = await sha256(sourcePath);
  if (actualHash !== asset.sha256) {
    throw new Error(`Source hash mismatch for ${asset.source}: expected ${asset.sha256}, got ${actualHash}`);
  }

  const packagePath = asset.packagePath.replace(/^runtime\//, '');
  const destinationPath = path.join(outputRoot, ...packagePath.split('/'));
  await mkdir(path.dirname(destinationPath), { recursive: true });
  await copyFile(sourcePath, destinationPath);

  const copiedHash = await sha256(destinationPath);
  if (copiedHash !== actualHash) {
    throw new Error(`Copied hash mismatch for ${asset.packagePath}`);
  }

  packagedAssets.push({
    source: asset.source,
    packagePath: asset.packagePath,
    bytes: (await stat(destinationPath)).size,
    sha256: copiedHash
  });
}

const manifest = {
  schemaVersion: 1,
  kind: 'HarmonyOSRuntimeAssetManifest',
  assets: packagedAssets
};
await writeFile(path.join(outputRoot, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

console.log(`Packaged ${packagedAssets.length} HarmonyOS runtime assets into ${path.relative(repositoryRoot, outputRoot)}.`);
for (const asset of packagedAssets) {
  console.log(`${asset.sha256}  ${asset.packagePath}  (${asset.bytes} bytes)`);
}

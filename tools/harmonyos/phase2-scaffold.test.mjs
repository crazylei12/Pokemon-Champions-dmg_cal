import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { access, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.join(repositoryRoot, ...relativePath.split('/')), 'utf8'));
}

async function sha256(relativePath) {
  const bytes = await readFile(path.join(repositoryRoot, ...relativePath.split('/')));
  return createHash('sha256').update(bytes).digest('hex');
}

test('formal Stage project has shared entry and native bridge', async () => {
  const required = [
    'harmonyos/app/build-profile.json5',
    'harmonyos/app/entry/build-profile.json5',
    'harmonyos/app/entry/src/main/module.json5',
    'harmonyos/app/entry/src/main/ets/entryability/EntryAbility.ets',
    'harmonyos/app/entry/src/main/ets/pages/Index.ets',
    'harmonyos/app/entry/src/main/cpp/CMakeLists.txt',
    'harmonyos/app/entry/src/main/cpp/napi_init.cpp'
  ];
  await Promise.all(required.map((relativePath) => access(path.join(repositoryRoot, ...relativePath.split('/')))));
});

test('standard and replay products preserve upgrade identity and differ only by feature gate', async () => {
  const config = await readJson('config/harmonyos-app-build.json');
  const buildProfile = await readJson('harmonyos/app/build-profile.json5');
  const appScope = await readJson('harmonyos/app/AppScope/app.json5');
  const standard = config.products.standard;
  const replay = config.products.replay;

  assert.equal(appScope.app.bundleName, config.bundleName);
  assert.equal(appScope.app.versionCode, config.versionCode);
  assert.equal(appScope.app.versionName, config.versionName);
  assert.equal(config.sdk, '6.1.1(24)');
  assert.equal(config.sdkBuild, '6.1.1.125');
  assert.equal(standard.replayEnabled, false);
  assert.equal(replay.replayEnabled, true);
  assert.notEqual(standard.artifactName, replay.artifactName);

  const products = new Map(buildProfile.app.products.map((product) => [product.name, product]));
  for (const productConfig of [standard, replay]) {
    const product = products.get(productConfig.hvigorProduct);
    assert.ok(product, `missing product ${productConfig.hvigorProduct}`);
    assert.equal(product.bundleName, config.bundleName);
    assert.equal(product.output.artifactName, productConfig.artifactName);
    assert.equal(product.buildOption.arkOptions.buildProfileFields.RELEASE_VARIANT, productConfig.releaseVariant);
    assert.equal(product.buildOption.arkOptions.buildProfileFields.REPLAY_ENABLED, productConfig.replayEnabled);
  }
});

test('all locked runtime sources and generated copies match SHA-256', async () => {
  const config = await readJson('config/harmonyos-app-build.json');
  const manifest = await readJson('harmonyos/app/entry/src/main/resources/rawfile/runtime/manifest.json');
  assert.equal(manifest.assets.length, config.runtimeAssets.length);

  for (const asset of config.runtimeAssets) {
    assert.equal(await sha256(asset.source), asset.sha256, asset.source);
    const generatedPath = `harmonyos/app/entry/src/main/resources/rawfile/${asset.packagePath}`;
    assert.equal(await sha256(generatedPath), asset.sha256, generatedPath);
    const entry = manifest.assets.find((candidate) => candidate.packagePath === asset.packagePath);
    assert.ok(entry, `missing manifest entry ${asset.packagePath}`);
    assert.equal(entry.sha256, asset.sha256);
    assert.equal(entry.bytes, (await stat(path.join(repositoryRoot, ...generatedPath.split('/')))).size);
  }
});

test('the product UI gates the replay entry directly from BuildProfile', async () => {
  const source = await readFile(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'ets', 'pages', 'Index.ets'), 'utf8');
  assert.match(source, /import \{ RELEASE_VARIANT, REPLAY_ENABLED \} from 'BuildProfile'/);
  assert.match(source, /if \(REPLAY_ENABLED\)/);
  assert.match(source, /title: '录屏模式'/);
});

import assert from 'node:assert/strict';
import {access, readFile} from 'node:fs/promises';
import {execFileSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(scriptDir, '..');
const file = (...parts) => path.join(root, ...parts);
const read = (...parts) => readFile(file(...parts), 'utf8');
const normalize = value => value.replace(/^\uFEFF/, '').replace(/\r\n/g, '\n').trimEnd();

const smogonUpstream = await read('external', 'smogon-damage-calc', 'LICENSE');
const smogonCopy = await read('third_party', 'licenses', 'smogon-damage-calc-MIT.txt');
assert.equal(
  normalize(smogonCopy),
  normalize(smogonUpstream),
  'The tracked Smogon license copy must exactly match the pinned submodule license.'
);

const smogonHead = execFileSync(
  'git',
  ['-C', file('external', 'smogon-damage-calc'), 'rev-parse', 'HEAD'],
  {encoding: 'utf8'}
).trim();
const modules = await read('.gitmodules');
assert.match(modules, /url\s*=\s*https:\/\/github\.com\/smogon\/damage-calc\.git/);

const pkmnManifest = JSON.parse(await read(
  'external',
  'smogon-damage-calc',
  'calc',
  'node_modules',
  '@pkmn',
  'dex',
  'package.json'
));
assert.equal(pkmnManifest.version, '0.10.5');
assert.equal(pkmnManifest.license, 'MIT');
assert.match(String(pkmnManifest.repository), /pkmn\/ps/);

const pkmnLicense = await read('third_party', 'licenses', 'pkmn-ps-MIT.txt');
assert.match(pkmnLicense, /Copyright \(c\) 2020-2026 pkmn contributors/);
assert.match(pkmnLicense, /permission notice shall be included in all/i);

const localizationLicense = await read(
  'src',
  'data',
  'localization',
  'sources',
  '42arch-pokemon-dataset-zh',
  'LICENSE'
);
assert.match(localizationLicense, /Copyright \(c\) 2024 42arch/);
assert.match(localizationLicense, /permission notice shall be included in all/i);
for (const snapshot of [
  'simple_pokedex.json',
  'move_list.json',
  'ability_list.json',
  'item_list.json',
]) {
  const tracked = execFileSync(
    'git',
    ['ls-files', '--', `src/data/localization/sources/42arch-pokemon-dataset-zh/${snapshot}`],
    {cwd: root, encoding: 'utf8'}
  ).trim();
  assert.equal(tracked, '', `Raw localization snapshot must not be published: ${snapshot}`);
}

const apacheLicense = await read('third_party', 'licenses', 'APACHE-2.0.txt');
assert.match(apacheLicense, /Apache License\s+Version 2\.0, January 2004/);
assert.match(apacheLicense, /END OF TERMS AND CONDITIONS/);

const pokeapiLicense = await read('third_party', 'licenses', 'pokeapi-BSD-3-Clause.txt');
assert.match(pokeapiLicense, /PokéAPI contributors/);
assert.match(pokeapiLicense, /Redistributions in binary form must reproduce/);

const pokeapiSpritesLicense = await read(
  'third_party',
  'licenses',
  'pokeapi-sprites-CC0-1.0.txt'
);
assert.match(pokeapiSpritesLicense, /distributed under CC0 1\.0 Universal/);
assert.match(pokeapiSpritesLicense, /All image contents within are Copyright The Pokémon Company/);

const presets = JSON.parse(await read('src', 'data', 'damage', 'champions-presets.json'));
assert.deepEqual(presets.licenseAssets, [
  'licenses/smogon-damage-calc-MIT.txt',
  'licenses/pkmn-ps-MIT.txt',
]);

const notices = await read('THIRD_PARTY_NOTICES.md');
for (const required of [
  smogonHead,
  '@pkmn/dex',
  '42arch',
  'PokeAPI',
  'AndroidX',
  'OpenCV',
  'ML Kit Terms of Service',
  'third_party/licenses',
]) {
  assert.ok(notices.includes(required), `THIRD_PARTY_NOTICES.md is missing: ${required}`);
}

const androidBuild = await read('android-app', 'app', 'build.gradle.kts');
for (const required of [
  'generated/legalAssets',
  'syncLegalAssets',
  'mlKitLicenseArtifacts',
  'third_party_licenses.txt',
]) {
  assert.ok(androidBuild.includes(required), `Android legal-asset packaging is missing: ${required}`);
}

const androidManifest = await read('android-app', 'app', 'src', 'main', 'AndroidManifest.xml');
for (const permission of [
  'android.permission.ACCESS_NETWORK_STATE',
  'android.permission.INTERNET',
]) {
  const permissionBlock = new RegExp(
    `<uses-permission[^>]+android:name="${permission.replaceAll('.', '\\.') }"[^>]+tools:node="remove"`,
    's'
  );
  assert.match(
    androidManifest,
    permissionBlock,
    `AndroidManifest.xml must remove transitive network permission: ${permission}`
  );
}

await assert.rejects(
  access(file('android-app', 'app', 'src', 'main', 'assets', 'licenses', 'smogon-damage-calc-LICENSE.txt')),
  undefined,
  'The stale hand-copied Android Smogon license must not remain.'
);

console.log(`Third-party license check passed: Smogon ${smogonHead.slice(0, 12)}, @pkmn/dex ${pkmnManifest.version}.`);

import assert from 'node:assert/strict';
import { readFile, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const descriptorPath = path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port',
  'cross-platform-golden.json');
const androidOutput = path.join(repositoryRoot, 'android-app', 'app', 'build',
  'cross-platform-golden', 'android.json');
const harmonyOutput = path.join(repositoryRoot, 'android-app', 'app', 'build',
  'cross-platform-golden', 'harmony.json');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

async function loadArkTs(entryPoint) {
  const output = await build({
    entryPoints: [entryPoint],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

async function loadArkTsSource(source) {
  const output = await build({
    stdin: { contents: source, resolveDir: repositoryRoot, loader: 'ts' },
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent'
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

function runAndroidGoldenExport() {
  const sharedAndroidTools = path.join(path.dirname(repositoryRoot),
    'pokemon-champions-assistant-main-safe-area', '.android-tools');
  const gradleEnvironment = {
    ...process.env,
    JAVA_HOME: path.join(sharedAndroidTools, 'jdk-17'),
    ANDROID_HOME: path.join(sharedAndroidTools, 'android-sdk'),
    ANDROID_USER_HOME: path.join(repositoryRoot, '.android-tools', 'android-user-home'),
    GRADLE_USER_HOME: path.join(repositoryRoot, '.android-tools', 'gradle-home'),
    CROSS_PLATFORM_GOLDEN_FIXTURE: descriptorPath,
    CROSS_PLATFORM_GOLDEN_OUTPUT: androidOutput
  };
  delete gradleEnvironment.ANDROID_SDK_ROOT;
  const gradleWrapper = path.join(repositoryRoot, 'android-app', 'gradlew.bat');
  const gradleCommand = `${gradleWrapper} :app:testDebugUnitTest --tests ` +
    'com.crazylei12.pokemonchampionsassistant.CrossPlatformGoldenExportTest --rerun-tasks';
  const result = spawnSync(process.env.ComSpec ?? 'cmd.exe', ['/d', '/s', '/c', gradleCommand], {
    cwd: path.join(repositoryRoot, 'android-app'),
    env: gradleEnvironment,
    encoding: 'utf8',
    maxBuffer: 32 * 1024 * 1024
  });
  if (result.error) throw result.error;
  if (result.status !== 0) {
    process.stdout.write(result.stdout ?? '');
    process.stderr.write(result.stderr ?? '');
    throw new Error(`Android golden exporter failed with exit code ${result.status}`);
  }
}

function clone(value) {
  return structuredClone(value);
}

await rm(androidOutput, { force: true });
await rm(harmonyOutput, { force: true });
runAndroidGoldenExport();

const descriptor = await readJson(descriptorPath);
const fixtureRoot = path.dirname(descriptorPath);
const backup = await readJson(path.resolve(fixtureRoot, descriptor.backupFixture));
const share = await readJson(path.resolve(fixtureRoot, descriptor.presetShareFixture));
const damageInput = descriptor.damageInput;
const storage = await loadArkTs(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main',
  'ets', 'storage', 'StorageContracts.ts'));
const battle = await loadArkTs(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main',
  'ets', 'domain', 'BattleSession.ts'));
const uiModels = await loadArkTsSource(`
  export { pokemonFromStored, profileFromStored }
    from './harmonyos/app/entry/src/main/ets/ui/AppUiModels.ts';
`);

const sharedPreset = share.userOpponentPresets.presets[0].preset;
assert.equal(sharedPreset.profileId, damageInput.presetProfileId);
sharedPreset.actualStats = clone(damageInput.presetActualStats);
const normalizedPresets = storage.parseOpponentPresetShareJson(JSON.stringify(share));
const normalizedShare = storage.buildOpponentPresetShareEnvelope(normalizedPresets,
  share.exportedAt, share.appVersion);

backup.data.userOpponentPresets = clone(normalizedPresets);
backup.data.currentBattleSession.calculationSelection = clone(damageInput.calculationSelection);
const validated = storage.validateAppBackupJson(JSON.stringify(backup));
const canonicalBackup = storage.buildAppBackupEnvelope(validated.envelope.data,
  backup.exportedAt, backup.appVersion);

const ownTeam = canonicalBackup.data.savedTeams.find((team) => team.savedTeamId === damageInput.ownTeamId);
assert.ok(ownTeam, 'The shared damage input must reference a team in the validated backup.');
assert.equal(ownTeam.pokemon.length, 6, 'The cross-platform golden must use a complete six-Pokemon team.');
for (const pokemon of ownTeam.pokemon) {
  assert.ok(pokemon.species?.canonicalId && pokemon.species?.showdownId,
    'Every team member must retain its stable species identity.');
  assert.equal(Object.keys(pokemon.actualStats ?? {}).length, 6,
    'Every team member must retain all six actual stats.');
  assert.ok((pokemon.moves ?? []).length > 0,
    'Every team member must retain at least one configured move.');
}
const session = canonicalBackup.data.currentBattleSession;
assert.equal(session.selectedOwnTeamId, ownTeam.savedTeamId);
const presetEntry = normalizedPresets.presets.find((entry) =>
  entry.preset.profileId === damageInput.presetProfileId);
assert.ok(presetEntry, 'The shared damage input must reference the normalized imported preset.');
assert.equal(Object.keys(presetEntry.preset.actualStats ?? {}).length, 6,
  'The imported preset must retain all six actual stats.');
assert.equal(Object.keys(presetEntry.preset.statPoints ?? {}).length, 6,
  'The imported preset must retain all six stat-point fields.');

const calculation = battle.normalizeBattleCalculation(session.calculationSelection,
  ownTeam.pokemon.length, session.opponentTeam.length);
assert.equal(calculation.ownSlot, damageInput.ownSlot);
assert.equal(calculation.opponentSlot, damageInput.opponentSlot);
const ownBuild = uiModels.pokemonFromStored(ownTeam.pokemon[damageInput.ownSlot]);
const opponentProfile = uiModels.profileFromStored(presetEntry);
const damageRequest = JSON.parse(battle.buildBattleDamageRequest({
  own: ownBuild,
  opponent: session.opponentTeam[damageInput.opponentSlot],
  preset: opponentProfile,
  legalMoves: opponentProfile.moves,
  calculation,
  allOwnMoves: damageInput.allOwnMoves,
  requestId: 'cross-platform-golden-volatile-id'
}));
delete damageRequest.requestId;

const harmonyResult = {
  backup: canonicalBackup,
  presetShare: normalizedShare,
  damageRequest
};
await writeFile(harmonyOutput, `${JSON.stringify(harmonyResult, null, 2)}\n`, 'utf8');
const androidResult = await readJson(androidOutput);

assert.deepEqual(androidResult.backup, harmonyResult.backup,
  'Android Kotlin and HarmonyOS ArkTS backup round trips diverged.');
assert.deepEqual(androidResult.presetShare, harmonyResult.presetShare,
  'Android Kotlin and HarmonyOS ArkTS preset round trips diverged.');
assert.deepEqual(androidResult.damageRequest, harmonyResult.damageRequest,
  'Android Kotlin and HarmonyOS ArkTS damage-request builders diverged.');

console.log('PASS cross-platform golden: Kotlin and ArkTS match for the complete backup, six-Pokemon team, preset, and damage request.');
console.log(`Android canonical output: ${androidOutput}`);
console.log(`HarmonyOS canonical output: ${harmonyOutput}`);

import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const sourceRoot = path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main');

async function loadModule(entryPoint, plugins = []) {
  const output = await build({
    entryPoints: [entryPoint],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent',
    loader: { '.ets': 'ts' },
    plugins
  });
  return import(`data:text/javascript;base64,${Buffer.from(output.outputFiles[0].text).toString('base64')}`);
}

const uiModelsPromise = loadModule(path.join(sourceRoot, 'ets', 'ui', 'AppUiModels.ts'));
const updateServicePromise = loadModule(path.join(sourceRoot, 'ets', 'services', 'UpdateService.ets'), [{
  name: 'stub-network-kit',
  setup(builder) {
    builder.onResolve({ filter: /^@kit\.NetworkKit$/ }, () => ({ path: 'network-kit', namespace: 'stub' }));
    builder.onLoad({ filter: /.*/, namespace: 'stub' }, () => ({
      contents: 'export const http = { RequestMethod: { GET: 0 }, createHttp() { throw new Error("not used"); } };',
      loader: 'js'
    }));
  }
}]);

function entity(entityType, showdownId, displayName = showdownId) {
  return { entityType, canonicalId: `${entityType}.${showdownId.toLowerCase()}`, showdownId, displayName };
}

function pokemon(speciesId, displayName, moveId) {
  return {
    species: entity('species', speciesId, displayName),
    level: 50,
    actualStats: { hp: 150, atk: 110, def: 100, spa: 120, spd: 100, spe: 105 },
    statPoints: { spa: 32, spe: 32 },
    ability: entity('ability', 'Static', '静电'),
    moves: [{ entity: entity('move', moveId, moveId), basePower: 90, type: 'Electric', source: 'MANUAL_OVERRIDE' }]
  };
}

test('formal HarmonyOS shell exposes all four product tabs and no debug vocabulary', async () => {
  const source = await readFile(path.join(sourceRoot, 'ets', 'pages', 'Index.ets'), 'utf8');
  for (const id of ['nav-home', 'nav-calculator', 'nav-battle', 'nav-settings', 'calculator-submit',
    'battle-page', 'settings-page', 'settings-check-update', 'settings-export-backup']) {
    assert.match(source, new RegExp(`['\"]${id}['\"]`));
  }
  for (const label of ['首页', '自由伤害计算', '对局助手', '设置与诊断']) assert.match(source, new RegExp(label));
  assert.doesNotMatch(source, /Photos|Top-3|\bROI\b|测试图片|调试页|占位页|stage5Verification/i);
});

test('manual calculator request preserves exact builds, direction and battle conditions', async () => {
  const models = await uiModelsPromise;
  const own = pokemon('Pikachu', '皮卡丘', 'Thunderbolt');
  const opponent = pokemon('Armarouge', '红莲铠骑', 'ArmorCannon');
  const base = {
    direction: 'OWN_TO_OPPONENT', selectedMoveId: 'Thunderbolt', battleType: 'DOUBLE', weather: 'Rain',
    terrain: 'Electric', ownReflect: false, ownLightScreen: false, opponentReflect: true,
    opponentLightScreen: true, critical: true, spread: true
  };
  const outgoing = JSON.parse(models.buildManualDamageRequest(own, opponent, base));
  assert.equal(outgoing.calculationDirection, 'OWN_TO_OPPONENT');
  assert.equal(outgoing.attacker.species.showdownId, 'Pikachu');
  assert.equal(outgoing.defenderProfileSet.profiles[0].actualStats.hp, 150);
  assert.equal(outgoing.moveSelection.moveId, 'Thunderbolt');
  assert.deepEqual(outgoing.battle.defenderSideConditions, { reflect: true, lightScreen: true });
  assert.equal(outgoing.battle.isSpreadMove, true);

  const incoming = JSON.parse(models.buildManualDamageRequest(own, opponent,
    { ...base, direction: 'OPPONENT_TO_OWN', selectedMoveId: 'ArmorCannon' }));
  assert.equal(incoming.calculationDirection, 'OPPONENT_TO_OWN');
  assert.equal(incoming.attackerProfileSet.profiles[0].moves[0].move.showdownId, 'ArmorCannon');
  assert.equal(incoming.defender.species.showdownId, 'Pikachu');
});

test('calculation output is localized into stable display fields', async () => {
  const models = await uiModelsPromise;
  const parsed = models.parseCalculationResult(JSON.stringify({
    ok: true,
    result: {
      calculationDirection: 'OWN_TO_OPPONENT',
      attackerSummary: { speciesName: '皮卡丘' },
      defenderIdentity: { species: { displayName: '红莲铠骑' } },
      moveResults: [{ moveName: '十万伏特', selectedProfileRange: {
        minPercent: 41.2, maxPercent: 48.8, minDamage: 62, maxDamage: 73
      }, koSummary: { text: 'guaranteed 3HKO' }, assumptions: ['雨天'] }],
      warnings: [{ code: 'EXAMPLE', message: '仅用于验证' }]
    }
  }));
  assert.equal(parsed.attacker, '皮卡丘');
  assert.equal(parsed.moves[0].koText, '必定 3 次击倒');
  assert.deepEqual(parsed.warnings, ['仅用于验证']);
});

test('team and preset helpers preserve edit validation and bilingual search', async () => {
  const models = await uiModelsPromise;
  const storedTeam = {
    savedTeamId: 'team-1', teamName: '原名', pokemon: Array.from({ length: 6 }, () => ({
      species: entity('species', 'Pikachu', '皮卡丘'), level: 50,
      actualStats: { hp: 150, atk: 110, def: 100, spa: 120, spd: 100, spe: 105 },
      ability: entity('ability', 'Static', '静电'), moves: [{ move: entity('move', 'Thunderbolt', '十万伏特') }]
    }))
  };
  assert.equal(models.teamWithName(storedTeam, '  新队伍  ').teamSlotName, '新队伍');
  assert.equal(models.toTeamDisplay(storedTeam).damageReady, true);
  assert.throws(() => models.teamWithName(storedTeam, '   '), /不能为空/);
  const preset = { speciesId: 'charizard', preset: { profileId: 'user.fast', profileName: '高速输出' } };
  const species = entity('species', 'Charizard', '喷火龙');
  assert.equal(models.presetSearchMatches(preset, species, '喷火'), true);
  assert.equal(models.presetSearchMatches(preset, species, 'char'), true);
  assert.equal(models.presetSearchMatches(preset, species, '高速'), true);
});

test('manual update selection follows semantic version and release channels', async () => {
  const updates = await updateServicePromise;
  assert.ok(updates.compareVersions('v1.2.0', '1.1.9') > 0);
  assert.ok(updates.compareVersions('1.2.0-beta.2', '1.2.0-beta.1') > 0);
  assert.ok(updates.compareVersions('1.2.0', '1.2.0-beta.9') > 0);
  const releases = [
    { tag_name: 'v1.2.0-beta.1', prerelease: true, assets: [] },
    { tag_name: 'v1.1.5', prerelease: false, assets: [
      { name: 'pokemon-champions-standard-arm64.hap', browser_download_url: 'standard' },
      { name: 'pokemon-champions-replay-arm64.hap', browser_download_url: 'replay' }
    ] }
  ];
  assert.equal(updates.selectNewestRelease(releases, 'stable').tagName, 'v1.1.5');
  assert.equal(updates.selectNewestRelease(releases, 'preview').tagName, 'v1.2.0-beta.1');
  assert.equal(updates.selectNewestRelease(releases, 'stable').replayPackageUrl, 'replay');
});

test('formal product permissions remain limited to Internet plus the Stage 6 floating entry', async () => {
  const profile = JSON.parse(await readFile(path.join(sourceRoot, 'module.json5'), 'utf8'));
  assert.deepEqual(profile.module.requestPermissions, [
    { name: 'ohos.permission.INTERNET' },
    { name: 'ohos.permission.SYSTEM_FLOAT_WINDOW' }
  ]);
});

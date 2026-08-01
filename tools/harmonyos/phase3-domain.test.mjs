import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '..', '..');
const fixtureRoot = path.join(repositoryRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0');

async function readJson(filePath) {
  return JSON.parse(await readFile(filePath, 'utf8'));
}

async function loadDomain() {
  const output = await build({
    entryPoints: [path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'ets', 'domain', 'index.ts')],
    bundle: true,
    format: 'esm',
    platform: 'neutral',
    target: 'es2021',
    write: false,
    logLevel: 'silent'
  });
  const source = output.outputFiles[0].text;
  return import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
}

async function loadEngine() {
  const source = await readFile(
    path.join(repositoryRoot, 'android-app', 'app', 'src', 'main', 'assets', 'damage-engine.js'),
    'utf8'
  );
  const context = { window: {}, console };
  vm.runInNewContext(source, context, { filename: 'damage-engine.js' });
  return context.window.PokemonChampionsDamageEngine;
}

function move(showdownId, basePower = 0, priority = 0) {
  return {
    entity: {
      entityType: 'move',
      canonicalId: `move.${showdownId.toLowerCase().replace(/[^a-z0-9]+/g, '')}`,
      showdownId,
      displayName: showdownId
    },
    basePower,
    priority
  };
}

function form(familyId, showdownId, configurationShareGroupId) {
  return {
    familyId,
    configurationShareGroupId,
    species: {
      entityType: 'species',
      canonicalId: `species.${showdownId.toLowerCase().replace(/[^a-z0-9]+/g, '')}`,
      showdownId,
      displayName: showdownId
    },
    baseStats: { hp: 100, atk: 100, def: 100, spa: 100, spd: 100, spe: 100 },
    abilities: [],
    learnableMoves: []
  };
}

function opponentOutputRequest(attacker, defender, moveId) {
  const profile = {
    profileId: 'exact-attacker',
    profileName: 'Synthetic exact attacker',
    source: 'MANUAL_CURRENT',
    isSelected: true,
    level: attacker.level,
    actualStats: attacker.actualStats,
    statPoints: attacker.statPoints,
    ability: attacker.ability,
    item: attacker.item,
    moves: attacker.moves.map((entry) => ({ ...entry, source: 'OPPONENT_LEGAL_MOVE_POOL' }))
  };
  return {
    requestId: 'harmony-opponent-output-regression',
    calculationDirection: 'OPPONENT_TO_OWN',
    attackerSide: 'OPPONENT',
    attackerIdentity: { species: attacker.species },
    attackerProfileSet: {
      attackerSpecies: attacker.species,
      selectedProfileId: profile.profileId,
      profiles: [profile]
    },
    attackerLegalMovePool: {
      species: attacker.species,
      rulesetVersion: 'synthetic-team-v1',
      learnableMoves: attacker.moves.map((entry) => entry.move),
      source: 'USER_PATCH'
    },
    defenderSide: 'OWN',
    defender,
    moveSelection: {
      mode: 'ONE_MOVE',
      moveId,
      source: 'OPPONENT_LEGAL_MOVE_POOL',
      legalMovePoolVersion: 'synthetic-team-v1'
    },
    battle: { battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE' },
    calculationMode: 'EXACT'
  };
}

const domainPromise = loadDomain();

test('stable IDs and localized names resolve without losing non-Latin text', async () => {
  const domain = await domainPromise;
  const entries = await readJson(path.join(repositoryRoot, 'src', 'data', 'localization', 'zh-Hans.json'));
  const byChinese = domain.findEntity(entries, 'species', '大嘴娃', 'zh-Hans');
  const byShowdown = domain.findEntity(entries, 'species', 'Mawile', 'zh-Hans');
  assert.equal(byChinese.canonicalId, 'species.mawile');
  assert.equal(byShowdown.canonicalId, 'species.mawile');
  assert.equal(byChinese.displayName, '大嘴娃');
  assert.equal(domain.normalizeCanonicalId('move', 'move.Play Rough'), 'move.playrough');
});

test('confirmed own moves remain first and are independent of an incomplete legal pool', async () => {
  const domain = await domainPromise;
  const configured = [move('Knock Off', 65), move('knock-off', 65), move('Protect')];
  const legal = [move('Flare Blitz', 120), move('Protect')];
  assert.deepEqual(
    domain.actualConfiguredMoves(configured).map((entry) => entry.entity.showdownId),
    ['Knock Off', 'Protect']
  );
  assert.deepEqual(
    domain.configuredMoveOptions(configured, legal).map((entry) => entry.entity.showdownId),
    ['Knock Off', 'Protect', 'Flare Blitz']
  );
  assert.deepEqual(
    domain.prioritizeLegalMoves(configured, legal).map((entry) => entry.entity.showdownId),
    ['Protect', 'Flare Blitz']
  );
  assert.equal(domain.chooseCompatibleMoveId(configured, 'knock-off', false), 'Knock Off');
});

test('form sharing, ability fallback, profile order, speed, and stat transformation match Android rules', async () => {
  const domain = await domainPromise;
  const slowbro = [form('slowbro', 'Slowbro'), form('slowbro', 'Slowbro-Galar'), form('slowbro', 'Slowbro-Mega')];
  const castform = [
    form('castform', 'Castform', 'battle.castform'),
    form('castform', 'Castform-Rainy', 'battle.castform'),
    form('castform', 'Castform-Snowy', 'battle.castform'),
    form('castform', 'Castform-Sunny', 'battle.castform')
  ];
  const rotom = [form('rotom', 'Rotom'), form('rotom', 'Rotom-Wash')];
  assert.deepEqual(domain.userOpponentPresetSharingForms(slowbro[0], slowbro).map((entry) => entry.species.showdownId),
    ['Slowbro', 'Slowbro-Mega']);
  assert.deepEqual(domain.userOpponentPresetSharingForms(slowbro[1], slowbro).map((entry) => entry.species.showdownId),
    ['Slowbro-Galar']);
  assert.equal(domain.userOpponentPresetSharingForms(castform[1], castform).length, 4);
  assert.deepEqual(domain.userOpponentPresetSharingForms(rotom[1], rotom).map((entry) => entry.species.showdownId),
    ['Rotom-Wash']);

  const hugePower = { entityType: 'ability', canonicalId: 'ability.hugepower', showdownId: 'Huge Power' };
  const intimidate = { entityType: 'ability', canonicalId: 'ability.intimidate', showdownId: 'Intimidate' };
  assert.equal(domain.defaultAbilityForTarget(intimidate, [hugePower], hugePower).showdownId, 'Huge Power');
  assert.deepEqual(domain.orderOpponentProfiles([{ profileId: 'user.1' }], [{ profileId: 'generated.1' }],
    [{ profileId: 'open.1' }]).map((entry) => entry.profileId), ['user.1', 'generated.1', 'open.1']);
  assert.deepEqual(domain.possibleSpeedRange(80), { minimum: 90, maximum: 145 });
  assert.equal(domain.isSpeedLinePriorityMove(move('Ice Shard', 40, 1)), true);
  assert.equal(domain.isSpeedLinePriorityMove(move('Protect', 0, 4)), false);

  const transformed = domain.transformActualStats(
    { hp: 157, atk: 150, def: 105, spa: 75, spd: 77, spe: 63 },
    { hp: 50, atk: 85, def: 85, spa: 55, spd: 55, spe: 50 },
    { hp: 50, atk: 105, def: 125, spa: 55, spd: 95, spe: 50 }
  );
  assert.deepEqual(transformed, { hp: 157, atk: 172, def: 145, spa: 75, spd: 117, spe: 63 });
});

test('battle defaults and supported condition enums match the Android state rules', async () => {
  const domain = await domainPromise;
  assert.deepEqual(domain.defaultBattleCondition(), {
    battleType: 'SINGLE',
    weather: 'NONE',
    terrain: 'NONE',
    attackerSideConditions: {},
    defenderSideConditions: {},
    isCritical: false,
    isSpreadMove: false
  });
  assert.equal(domain.normalizeBattleType('TRIPLE'), 'SINGLE');
  assert.equal(domain.normalizeWeather('Harsh Sunshine'), 'NONE');
  assert.equal(domain.normalizeTerrain('Electric'), 'Electric');
  const double = domain.withBattleTypeDefaults({
    battleType: 'SINGLE',
    attackerSideConditions: { helpingHand: true }
  }, 'DOUBLE');
  assert.equal(double.battleType, 'DOUBLE');
  assert.equal(double.isSpreadMove, true);
  assert.equal(double.attackerSideConditions.helpingHand, true);
  const single = domain.withBattleTypeDefaults(double, 'SINGLE');
  assert.equal(single.isSpreadMove, false);
  assert.equal(single.attackerSideConditions.helpingHand, false);
});

test('fixed offline engine matches the phase 0 projection 100 times and recovers after an error', async () => {
  const domain = await domainPromise;
  const engine = await loadEngine();
  const request = await readJson(path.join(fixtureRoot, 'damage-request.json'));
  const expected = await readJson(path.join(fixtureRoot, 'damage-response-projection.json'));
  const info = domain.parseEngineInfo(engine.getEngineInfo());
  assert.deepEqual({ version: info.version, generation: info.generation, offline: info.offline }, expected.engine);

  let reference;
  for (let index = 0; index < 100; index += 1) {
    const projection = domain.projectDamageResponse(engine.calculateDamage(JSON.stringify(request)));
    assert.equal(domain.isGoldenProjection(projection), true);
    const serialized = JSON.stringify(projection);
    reference ??= serialized;
    assert.equal(serialized, reference);
  }

  const invalid = JSON.parse(engine.calculateDamage('{'));
  assert.equal(invalid.ok, false);
  assert.equal(domain.isGoldenProjection(
    domain.projectDamageResponse(engine.calculateDamage(JSON.stringify(request)))), true);
});

test('fixed engine preserves bidirectional output, battle modifiers, and one-decimal projection precision', async () => {
  const engine = await loadEngine();
  const team = await readJson(path.join(repositoryRoot, 'test', 'fixtures', 'saved-team.synthetic.json'));
  const phase0 = await readJson(path.join(fixtureRoot, 'damage-request.json'));

  const opponentResponse = JSON.parse(engine.calculateDamage(JSON.stringify(
    opponentOutputRequest(team.pokemon[1], team.pokemon[0], 'move.flashcannon'))));
  assert.equal(opponentResponse.ok, true);
  assert.equal(opponentResponse.result.calculationDirection, 'OPPONENT_TO_OWN');
  assert.equal(opponentResponse.result.moveResults[0].moveSource, 'OPPONENT_LEGAL_MOVE_POOL');
  assert.deepEqual([
    opponentResponse.result.moveResults[0].selectedProfileRange.minPercent,
    opponentResponse.result.moveResults[0].selectedProfileRange.maxPercent
  ], [48.4, 57.3]);

  const conditionCases = [
    [{ battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE', defenderSideConditions: { reflect: true } },
      [16, 19, 9.9, 11.8, 9]],
    [{ battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE', isCritical: true },
      [48, 57, 29.8, 35.4, 3]],
    [{ battleType: 'DOUBLE', weather: 'NONE', terrain: 'NONE', isSpreadMove: true },
      [24, 28, 14.9, 17.4, 6]],
    [{ battleType: 'DOUBLE', weather: 'NONE', terrain: 'NONE', isCritical: true, isSpreadMove: true,
      attackerSideConditions: { helpingHand: true }, defenderSideConditions: { reflect: true } },
      [54, 63, 33.5, 39.1, 3]]
  ];
  for (const [battle, expected] of conditionCases) {
    const request = structuredClone(phase0);
    request.battle = battle;
    const response = JSON.parse(engine.calculateDamage(JSON.stringify(request)));
    const result = response.result.moveResults[0];
    assert.deepEqual([
      result.selectedProfileRange.minDamage,
      result.selectedProfileRange.maxDamage,
      result.selectedProfileRange.minPercent,
      result.selectedProfileRange.maxPercent,
      result.koSummary.hits
    ], expected);
    assert.equal(Number.isInteger(result.selectedProfileRange.minPercent * 10), true);
    assert.equal(Number.isInteger(result.selectedProfileRange.maxPercent * 10), true);
  }
});

test('the formal HAP has no network permission and its ArkWeb host is local-only', async () => {
  const moduleProfile = await readJson(path.join(repositoryRoot, 'harmonyos', 'app', 'entry', 'src', 'main', 'module.json5'));
  const host = await readFile(path.join(repositoryRoot, 'android-app', 'app', 'src', 'main', 'assets', 'engine-host.html'), 'utf8');
  assert.equal((moduleProfile.module.requestPermissions ?? []).some((permission) =>
    permission.name === 'ohos.permission.INTERNET'), false);
  assert.match(host, /<script src="damage-engine\.js"><\/script>/);
  assert.equal(/https?:\/\//i.test(host), false);
});

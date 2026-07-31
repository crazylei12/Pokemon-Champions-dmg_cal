import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {readFile, stat} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const fixtureDirectory = path.join(repoRoot, 'test', 'fixtures', 'harmonyos-port', 'phase0');
const statKeys = ['hp', 'atk', 'def', 'spa', 'spd', 'spe'];

async function readJson(relativePath) {
  return JSON.parse(await readFile(path.join(repoRoot, relativePath), 'utf8'));
}

async function readFixture(name) {
  return readJson(path.join('test', 'fixtures', 'harmonyos-port', 'phase0', name));
}

function sha256(bytes) {
  return createHash('sha256').update(bytes).digest('hex');
}

function assertEntity(entity, expectedType) {
  assert.equal(typeof entity, 'object');
  assert.equal(entity.entityType.toLowerCase(), expectedType.toLowerCase());
  assert.match(entity.canonicalId, new RegExp(`^${expectedType.toLowerCase()}\\.`));
  assert.ok(entity.showdownId.length > 0);
  assert.ok(entity.displayName.length > 0);
}

function assertStats(stats, {positive = false} = {}) {
  assert.equal(typeof stats, 'object');
  for (const key of statKeys) {
    assert.equal(Number.isInteger(stats[key]), true, `stat ${key} must be an integer`);
    assert.equal(positive ? stats[key] > 0 : stats[key] >= 0, true, `invalid stat ${key}`);
  }
}

function assertStoredPokemon(pokemon) {
  assertEntity(pokemon.species, 'species');
  assert.equal(Number.isInteger(pokemon.level), true);
  assert.equal(pokemon.level >= 1 && pokemon.level <= 100, true);
  assertStats(pokemon.actualStats, {positive: true});
  assertEntity(pokemon.ability, 'ability');
  assert.equal(Array.isArray(pokemon.moves), true);
  assert.equal(pokemon.moves.length >= 1 && pokemon.moves.length <= 4, true);
  for (const move of pokemon.moves) assertEntity(move.move, 'move');
}

function assertPresetRoot(root) {
  assert.equal(root.schemaVersion, 1);
  assert.equal(root.kind, 'OpponentUserPresets');
  assert.equal(root.presets.length <= 500, true);
  const ids = new Set();
  for (const entry of root.presets) {
    assert.ok(entry.speciesId.length > 0);
    const preset = entry.preset;
    assert.match(preset.profileId, /^user\./);
    assert.equal(ids.has(preset.profileId), false);
    ids.add(preset.profileId);
    assert.equal(preset.profileName.trim().length >= 1, true);
    assert.equal(preset.profileName.trim().length <= 24, true);
    assert.equal(preset.source, 'USER_SAVED');
    assert.equal(preset.level >= 1 && preset.level <= 100, true);
    assertStats(preset.statPoints);
    assert.equal(Array.isArray(preset.moves), true);
    assert.equal(preset.moves.length <= 4, true);
    for (const move of preset.moves) assertEntity(move.move, 'move');
  }
}

async function loadEngine() {
  const source = await readFile(
    path.join(repoRoot, 'android-app', 'app', 'src', 'main', 'assets', 'damage-engine.js'),
    'utf8',
  );
  const context = {window: {}, console};
  vm.runInNewContext(source, context, {filename: 'damage-engine.js'});
  return context.window.PokemonChampionsDamageEngine;
}

test('phase 0 baseline pins source revisions, assets, tools, and fixtures', async () => {
  const baseline = await readJson('config/harmonyos-phase0-baseline.json');
  assert.equal(baseline.schemaVersion, 1);
  assert.equal(baseline.kind, 'HarmonyOSPortPhase0Baseline');
  assert.equal(baseline.portPlan, 'docs/harmonyos_full_port_plan_zh.md');

  assert.equal(baseline.sourceBaselines.standard.branch, 'main');
  assert.equal(baseline.sourceBaselines.replay.branch, 'feature/battle-replay-phase-4');
  assert.equal(baseline.sourceBaselines.harmonyPort.branch, 'feature/harmonyos-port');
  for (const commit of [
    baseline.sourceBaselines.standard.commit,
    baseline.sourceBaselines.replay.commit,
    baseline.sourceBaselines.harmonyPort.startingCommit,
    baseline.engine.smogonSubmoduleCommit,
  ]) assert.match(commit, /^[0-9a-f]{40}$/);

  assert.deepEqual(
    [baseline.testBaseline.standardCurrentWorktreeJvmTests, baseline.testBaseline.replayCurrentWorktreeJvmTests],
    [112, 147],
  );
  assert.equal(baseline.testBaseline.nodeRegressionTests, 11);
  assert.equal(baseline.platformBaseline.harmony.api, 24);
  assert.equal(baseline.platformBaseline.harmony.landscapeResolution, '2772x1240');
  assert.equal(baseline.engine.id, 'pokemon-champions-smogon-0.11.0-3677e41');

  for (const sourceFile of baseline.sourceWorktreeState.files) {
    assert.match(sourceFile.sha256, /^[0-9a-f]{64}$/);
  }

  for (const asset of baseline.runtimeAssets) {
    const bytes = await readFile(path.join(repoRoot, asset.path));
    assert.equal(bytes.length, asset.bytes, `${asset.path} byte length drifted`);
    assert.equal(sha256(bytes), asset.sha256, `${asset.path} hash drifted`);
  }

  assert.equal(baseline.contractFixtures.length, 9);
  for (const fixture of baseline.contractFixtures) {
    assert.equal((await stat(path.join(repoRoot, fixture))).isFile(), true);
  }
});

test('feature matrix is complete, uniquely keyed, and remains unimplemented at phase 0', async () => {
  const matrix = await readJson('config/harmonyos-phase0-feature-matrix.json');
  assert.equal(matrix.schemaVersion, 1);
  assert.equal(matrix.kind, 'HarmonyOSPortFeatureMatrix');
  assert.deepEqual(matrix.statusValues, ['not_started', 'in_progress', 'implemented', 'accepted']);
  assert.equal(matrix.features.length, 66);

  const ids = matrix.features.map(feature => feature.id);
  assert.equal(new Set(ids).size, ids.length);
  const requiredAreas = [
    'app-shell', 'privacy', 'home', 'manual-calculator', 'damage', 'capture',
    'own-team-ocr', 'team-preview', 'battle-session', 'battle-panel', 'battle-hud',
    'opponent-presets', 'storage', 'update', 'replay', 'gallery-acceptance',
  ];
  const areas = new Set(matrix.features.map(feature => feature.area));
  for (const area of requiredAreas) assert.equal(areas.has(area), true, `missing area ${area}`);

  const requiredIds = [
    'APP-001', 'CALC-004', 'CALC-007', 'CAPTURE-001', 'OWN-006', 'PREVIEW-004',
    'PREVIEW-006', 'BATTLE-003', 'HUD-006', 'PRESET-001', 'STORE-003',
    'REPLAY-001', 'REPLAY-008', 'ACCEPT-001', 'ACCEPT-004',
  ];
  for (const id of requiredIds) assert.equal(ids.includes(id), true, `missing feature ${id}`);

  for (const feature of matrix.features) {
    assert.match(feature.id, /^[A-Z]+-[0-9]{3}$/);
    assert.ok(feature.name.trim().length > 0);
    assert.equal(feature.status, 'not_started');
    assert.equal(Number.isInteger(feature.plannedStage), true);
    assert.equal(feature.plannedStage >= 3 && feature.plannedStage <= 10, true);
    assert.equal(feature.source.length > 0, true);
    assert.equal(feature.source.every(source => typeof source === 'string' && source.length > 0), true);
    assert.ok(feature.acceptance.trim().length > 0);
    assert.equal(feature.variants.length >= 1 && feature.variants.length <= 2, true);
    assert.equal(feature.variants.every(variant => ['standard', 'replay'].includes(variant)), true);
    if (!feature.id.startsWith('REPLAY-') && feature.id !== 'ACCEPT-004') {
      assert.deepEqual(feature.variants, ['standard', 'replay']);
    }
  }
});

test('golden fixtures contain only synthetic JSON data', async () => {
  const baseline = await readJson('config/harmonyos-phase0-baseline.json');
  for (const relativePath of baseline.contractFixtures) {
    const source = await readFile(path.join(repoRoot, relativePath), 'utf8');
    assert.doesNotThrow(() => JSON.parse(source));
    assert.equal(/[A-Za-z]:[\\/]/.test(source), false, `${relativePath} contains a local path`);
    assert.equal(/\\Users\\/i.test(source), false, `${relativePath} contains a user path`);
    assert.equal(/\.(png|jpe?g|webp)/i.test(source), false, `${relativePath} references a raster sample`);
    assert.equal(/"(imageBytes|bitmap|base64)"\s*:/i.test(source), false, `${relativePath} embeds image data`);
  }
  assert.equal(path.basename(fixtureDirectory), 'phase0');
});

test('saved own-team fixture freezes six slots and Ditto correction exception', async () => {
  const team = await readFixture('saved-own-team.json');
  assert.equal(team.schemaVersion, 1);
  assert.equal(team.kind, 'SavedOwnTeam');
  assert.equal(team.damageReady, true);
  assert.equal(team.userConfirmed, true);
  assert.equal(team.members.length, 6);
  assert.deepEqual(team.members.map(member => member.slotIndex), [0, 1, 2, 3, 4, 5]);

  for (const member of team.members) {
    assertEntity(member.species, 'species');
    assertStats(member.actualStats, {positive: true});
    assertEntity(member.ability, 'ability');
    assertEntity(member.build.species, 'species');
    assertStats(member.build.actualStats, {positive: true});
    assertStats(member.build.statPoints);
    const moveIds = member.build.moves.map(entry => entry.move.canonicalId);
    assert.equal(new Set(moveIds).size, moveIds.length);
    const expectedMoveCount = member.species.canonicalId === 'species.ditto' ? 1 : 4;
    assert.equal(moveIds.length, expectedMoveCount);
  }
  assert.equal(team.members[0].build.moves[0].move.canonicalId, 'move.transform');
});

test('own-team import draft freezes two 2772x1240 pages and 42 fields per page', async () => {
  const draft = await readFixture('own-team-import-draft.json');
  assert.equal(draft.schemaVersion, 1);
  assert.equal(draft.kind, 'OwnTeamImportDraft');
  assert.equal(draft.moveItemPage.sceneType, 'OWN_TEAM_MOVE_ITEM');
  assert.equal(draft.statsPage.sceneType, 'OWN_TEAM_STATS');

  for (const page of [draft.moveItemPage, draft.statsPage]) {
    assert.deepEqual([page.image.width, page.image.height], [2772, 1240]);
    assert.equal(page.slots.length, 6);
    assert.deepEqual(page.slots.map(slot => slot.slotIndex), [0, 1, 2, 3, 4, 5]);
    assert.equal(page.recognition.total, 42);
  }

  for (let index = 0; index < 6; index += 1) {
    const moveSlot = draft.moveItemPage.slots[index];
    const statsSlot = draft.statsPage.slots[index];
    assert.equal(moveSlot.species.canonicalId, statsSlot.species.canonicalId);
    const expectedMoveCount = moveSlot.species.canonicalId === 'species.ditto' ? 1 : 4;
    assert.equal(moveSlot.moves.length, expectedMoveCount);
    assertStats(statsSlot.actualStats, {positive: true});
  }
});

test('team-preview fixture freezes 12 slots, viewport mapping, and confirmation thresholds', async () => {
  const preview = await readFixture('team-preview-recognition.json');
  assert.equal(preview.schemaVersion, 1);
  assert.equal(preview.kind, 'TeamPreviewRecognitionResult');
  assert.equal(preview.sceneType, 'TEAM_PREVIEW');
  assert.deepEqual([preview.imageSize.width, preview.imageSize.height], [2772, 1240]);
  assert.deepEqual(preview.roiMapping.gameViewport, {left: 284, top: 0, width: 2204, height: 1240});
  assert.equal(preview.ownTeamCandidates.length, 6);
  assert.equal(preview.opponentTeamCandidates.length, 6);

  const slots = [...preview.ownTeamCandidates, ...preview.opponentTeamCandidates];
  assert.equal(preview.performance.slots.length, 12);
  assert.equal(new Set(preview.performance.slots.map(slot => slot.roiId)).size, 12);
  for (const side of ['own', 'opponent']) {
    const sideSlots = slots.filter(slot => slot.side === side);
    assert.deepEqual(sideSlots.map(slot => slot.slotIndex), [0, 1, 2, 3, 4, 5]);
  }
  for (const slot of slots) {
    assert.equal(slot.roiId, `${slot.side}.slot${slot.slotIndex}`);
    assert.equal(slot.candidates.length >= 1 && slot.candidates.length <= 3, true);
    assert.equal(slot.selectedCandidate.canonicalId, slot.candidates[0].canonicalId);
    const expectedConfirmation =
      slot.selectedCandidate.confidence < 0.90 || slot.selectedCandidate.scoreMargin < 0.035;
    assert.equal(slot.requiresConfirmation, expectedConfirmation, slot.roiId);
  }
});

test('battle, preset-share, and backup fixtures preserve storage contracts', async () => {
  const session = await readFixture('battle-session.json');
  assert.equal(session.schemaVersion, 6);
  assert.equal(session.kind, 'BattleSession');
  assert.equal(session.opponentTeam.length, 6);
  assert.equal(session.selectedOwnTeamId, 'harmony-port-phase0-team');
  assert.equal(session.calculationSelection.direction, 'OWN_TO_OPPONENT');
  for (const key of [
    'opponentPresetIds', 'ownFormOverrides', 'opponentFormOverrides',
    'opponentManualOverrides', 'ownConditions', 'opponentConditions', 'speedLine', 'directHud',
  ]) assert.equal(typeof session.calculationSelection[key], 'object', key);
  assert.equal(JSON.stringify(session).includes(':null'), false);

  const presets = await readFixture('opponent-user-presets.json');
  assertPresetRoot(presets);
  assert.deepEqual(presets.presets.map(entry => entry.speciesId), ['charizard', 'azumarill']);

  const share = await readFixture('opponent-preset-share.json');
  assert.equal(share.schemaVersion, 1);
  assert.equal(share.kind, 'PokemonChampionsOpponentPresetShare');
  assertPresetRoot(share.userOpponentPresets);

  const backup = await readFixture('app-backup.json');
  assert.equal(backup.schemaVersion, 1);
  assert.equal(backup.kind, 'PokemonChampionsAssistantBackup');
  assert.equal(backup.data.savedTeams.length <= 100, true);
  const teamIds = new Set();
  for (const team of backup.data.savedTeams) {
    assert.match(team.savedTeamId, /^[A-Za-z0-9._-]{1,120}$/);
    assert.equal(teamIds.has(team.savedTeamId), false);
    teamIds.add(team.savedTeamId);
    const pokemon = team.pokemon ?? team.members;
    assert.equal(pokemon.length, 6);
    pokemon.forEach(assertStoredPokemon);
  }
  assert.equal(teamIds.has(backup.data.currentBattleSession.selectedOwnTeamId), true);
  assert.equal(backup.data.currentBattleSession.opponentTeam.length, 6);
  assertPresetRoot(backup.data.userOpponentPresets);
  assert.equal(['stable', 'preview'].includes(backup.data.updateChannel), true);
});

test('pinned Android damage engine reproduces the cross-platform golden projection', async () => {
  const engine = await loadEngine();
  assert.ok(engine);
  const request = await readFixture('damage-request.json');
  const expected = await readFixture('damage-response-projection.json');
  const info = JSON.parse(engine.getEngineInfo());
  const calculated = JSON.parse(engine.calculateDamage(JSON.stringify(request)));
  assert.equal(calculated.ok, true, JSON.stringify(calculated));

  const result = calculated.result;
  const move = result.moveResults[0];
  const range = move.selectedProfileRange;
  const projection = {
    engine: {
      version: info.version,
      generation: info.generation,
      offline: info.offline,
    },
    response: {
      ok: calculated.ok,
      requestId: result.requestId,
      calculationDirection: result.calculationDirection,
      attackerSide: result.attackerSide,
      attackerSpeciesId: result.attackerSummary.speciesId,
      defenderSide: result.defenderSide,
      defenderSpeciesId: result.defenderIdentity.species.canonicalId,
      selectedProfileId: result.selectedDefenderProfile.profileId,
      warnings: result.warnings.map(warning => warning.code),
      moveResults: [{
        moveId: move.moveId,
        moveSource: move.moveSource,
        moveCategory: move.moveCategory,
        minDamage: range.minDamage,
        maxDamage: range.maxDamage,
        minPercent: range.minPercent,
        maxPercent: range.maxPercent,
        koHits: move.koSummary.hits,
      }],
    },
  };

  assert.deepEqual(projection, expected);
});

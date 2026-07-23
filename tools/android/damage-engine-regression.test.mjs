import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const require = createRequire(import.meta.url);

const {Generations} = require(path.join(
  repoRoot,
  'external',
  'smogon-damage-calc',
  'calc',
  'dist',
  'data'
));
const {getModifiedStat} = require(path.join(
  repoRoot,
  'external',
  'smogon-damage-calc',
  'calc',
  'dist',
  'mechanics',
  'util'
));

async function loadFixture() {
  return JSON.parse(await readFile(
    path.join(repoRoot, 'test', 'fixtures', 'saved-team.synthetic.json'),
    'utf8'
  ));
}

async function loadEngine() {
  const source = await readFile(
    path.join(repoRoot, 'android-app', 'app', 'src', 'main', 'assets', 'damage-engine.js'),
    'utf8'
  );
  const context = {window: {}, console};
  vm.runInNewContext(source, context, {filename: 'damage-engine.js'});
  return context.window.PokemonChampionsDamageEngine;
}

function exactDefenderProfile(build) {
  return {
    profileId: 'exact-defender',
    profileName: 'Synthetic exact defender',
    source: 'MANUAL_CURRENT',
    isSelected: true,
    includedInEnvelope: true,
    level: build.level,
    actualStats: build.actualStats,
    statPoints: build.statPoints,
    ability: build.ability,
    item: build.item,
  };
}

function ownOutputRequest(attacker, defender, moveId) {
  return {
    requestId: 'android-own-output-regression',
    calculationDirection: 'OWN_TO_OPPONENT',
    attackerSide: 'OWN',
    attacker,
    defenderSide: 'OPPONENT',
    defenderIdentity: {species: defender.species},
    defenderProfileSet: {
      defenderSpecies: defender.species,
      selectedProfileId: 'exact-defender',
      profiles: [exactDefenderProfile(defender)],
    },
    moveSelection: {mode: 'ONE_MOVE', moveId},
    battle: {battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE'},
    calculationMode: 'EXACT',
  };
}

function opponentOutputRequest(attacker, defender, moveId, includePool = true) {
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
    moves: attacker.moves,
  };
  const request = {
    requestId: 'android-opponent-output-regression',
    calculationDirection: 'OPPONENT_TO_OWN',
    attackerSide: 'OPPONENT',
    attackerIdentity: {species: attacker.species},
    attackerProfileSet: {
      attackerSpecies: attacker.species,
      selectedProfileId: profile.profileId,
      profiles: [profile],
    },
    defenderSide: 'OWN',
    defender,
    moveSelection: {
      mode: 'ONE_MOVE',
      moveId,
      source: 'OPPONENT_LEGAL_MOVE_POOL',
      legalMovePoolVersion: 'synthetic-team-v1',
    },
    battle: {battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE'},
    calculationMode: 'EXACT',
  };
  if (includePool) {
    request.attackerLegalMovePool = {
      species: attacker.species,
      rulesetVersion: 'synthetic-team-v1',
      learnableMoves: attacker.moves.map(entry => entry.move),
      source: 'USER_PATCH',
    };
  }
  return request;
}

test('generated Android asset exposes engine metadata', async () => {
  const engine = await loadEngine();
  assert.ok(engine);
  const info = JSON.parse(engine.getEngineInfo());
  assert.equal(info.version, 'pokemon-champions-smogon-0.11.0-3677e41');
  assert.equal(info.generation, 'Champions');
  assert.equal(info.offline, true);
});

test('Champions stat drops use modern stat-stage rounding', () => {
  assert.equal(getModifiedStat(151, -1, Generations.get(0)), 100);
});

test('synthetic complete builds calculate fixed own-output damage', async () => {
  const engine = await loadEngine();
  const team = await loadFixture();
  const response = JSON.parse(engine.calculateDamage(
    JSON.stringify(ownOutputRequest(team.pokemon[0], team.pokemon[1], 'move.playrough'))
  ));

  assert.equal(response.ok, true);
  assert.equal(response.result.calculationDirection, 'OWN_TO_OPPONENT');
  assert.equal(response.result.moveResults.length, 1);
  assert.equal(response.result.moveResults[0].moveId, 'move.playrough');
  assert.deepEqual(
    [
      response.result.moveResults[0].selectedProfileRange.minPercent,
      response.result.moveResults[0].selectedProfileRange.maxPercent,
    ],
    [19.9, 23.6]
  );
});

test('all-move calculation keeps status moves as zero direct damage', async () => {
  const engine = await loadEngine();
  const team = await loadFixture();
  const request = ownOutputRequest(team.pokemon[0], team.pokemon[1], 'move.playrough');
  request.moveSelection = {mode: 'ALL_ATTACKER_MOVES'};
  const response = JSON.parse(engine.calculateDamage(JSON.stringify(request)));

  assert.equal(response.ok, true);
  assert.equal(response.result.moveResults.length, 4);
  const protect = response.result.moveResults.find(move => move.moveId === 'move.protect');
  assert.ok(protect);
  assert.deepEqual(
    [protect.selectedProfileRange.minPercent, protect.selectedProfileRange.maxPercent],
    [0, 0]
  );
  assert.equal(protect.koSummary.text, 'No direct damage.');
});

test('type immunity returns zero direct damage without a KO assertion', async () => {
  const engine = await loadEngine();
  const team = await loadFixture();
  const attacker = structuredClone(team.pokemon[0]);
  const defender = structuredClone(team.pokemon[1]);
  attacker.moves = [{
    move: {
      canonicalId: 'move.shadowclaw',
      showdownId: 'Shadow Claw',
      displayName: 'Shadow Claw',
    },
    source: 'OWN_BUILD',
  }];
  defender.species = {
    canonicalId: 'species.maushold',
    showdownId: 'Maushold',
    displayName: 'Maushold',
  };
  const response = JSON.parse(engine.calculateDamage(
    JSON.stringify(ownOutputRequest(attacker, defender, 'move.shadowclaw'))
  ));

  assert.equal(response.ok, true, JSON.stringify(response));
  assert.deepEqual(
    [
      response.result.moveResults[0].selectedProfileRange.minDamage,
      response.result.moveResults[0].selectedProfileRange.maxDamage,
    ],
    [0, 0]
  );
  assert.equal(response.result.moveResults[0].koSummary.text, 'No direct damage.');
});

test('synthetic complete builds calculate fixed opponent-to-own damage', async () => {
  const engine = await loadEngine();
  const team = await loadFixture();
  const response = JSON.parse(engine.calculateDamage(
    JSON.stringify(opponentOutputRequest(team.pokemon[1], team.pokemon[0], 'move.flashcannon'))
  ));

  assert.equal(response.ok, true);
  assert.equal(response.result.calculationDirection, 'OPPONENT_TO_OWN');
  assert.equal(response.result.moveResults.length, 1);
  assert.equal(response.result.moveResults[0].moveId, 'move.flashcannon');
  assert.deepEqual(
    [
      response.result.moveResults[0].selectedProfileRange.minPercent,
      response.result.moveResults[0].selectedProfileRange.maxPercent,
    ],
    [48.4, 57.3]
  );
});

test('opponent calculations preserve legal-move warnings', async () => {
  const engine = await loadEngine();
  const team = await loadFixture();
  const missingPool = JSON.parse(engine.calculateDamage(
    JSON.stringify(opponentOutputRequest(team.pokemon[1], team.pokemon[0], 'move.flashcannon', false))
  ));
  assert.ok(missingPool.result.warnings.some(warning => warning.code === 'LEGAL_MOVE_POOL_MISSING'));

  const missingMoveRequest = opponentOutputRequest(
    team.pokemon[1],
    team.pokemon[0],
    'move.flashcannon'
  );
  missingMoveRequest.moveSelection = {mode: 'ONE_MOVE'};
  const missingMove = JSON.parse(engine.calculateDamage(JSON.stringify(missingMoveRequest)));
  assert.ok(missingMove.result.warnings.some(warning => warning.code === 'NO_OPPONENT_MOVE_SELECTED'));
});

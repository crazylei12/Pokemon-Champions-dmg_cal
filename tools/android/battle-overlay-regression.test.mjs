import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import path from 'node:path';
import test from 'node:test';
import vm from 'node:vm';
import {fileURLToPath} from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');

async function readJson(...segments) {
  return JSON.parse(await readFile(path.join(repoRoot, ...segments), 'utf8'));
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

function selectedProfile(profile) {
  return {...profile, isSelected: true, includedInEnvelope: true};
}

test('Champions preset asset is complete enough for the battle overlay', async () => {
  const presets = await readJson('src', 'data', 'damage', 'champions-presets.json');
  assert.equal(presets.schemaVersion, 6);
  assert.equal(presets.learnsetSource, '@pkmn/mods/champions');
  assert.equal(presets.learnsetVersion, '0.10.11');
  assert.equal(presets.learnsetRulesetVersion, 'pkmn-mods-champions-0.10.11');
  assert.equal(presets.learnsetPoolSource, 'CHAMPIONS_SNAPSHOT');
  assert.equal(presets.learnsetDataDate, '2026-06-18');
  assert.ok(presets.speciesCount >= 160);
  assert.ok(presets.profileCount >= 640);
  assert.ok(presets.formGroupCount >= 90);
  assert.ok(presets.speciesFormCount >= 300);
  assert.equal(presets.natures.length, 25);
  const directionalNatures = presets.natures.filter(entry => entry.plus !== entry.minus);
  assert.equal(directionalNatures.length, 20);
  assert.equal(new Set(directionalNatures.map(entry => `${entry.plus}:${entry.minus}`)).size, 20);
  assert.equal(directionalNatures.find(entry => entry.plus === 'atk' && entry.minus === 'spa').nature.showdownId, 'Adamant');
  assert.equal(directionalNatures.find(entry => entry.plus === 'spd' && entry.minus === 'spa').nature.showdownId, 'Careful');
  assert.deepEqual(presets.speciesForms.filter(entry => entry.abilities.length === 0), []);
  assert.deepEqual(presets.speciesForms.filter(entry => entry.defaultAbility && !entry.abilities.some(
    ability => ability.showdownId === entry.defaultAbility.showdownId
  )), []);
  assert.deepEqual(presets.speciesForms.filter(entry => entry.learnableMoves.length === 0), []);
  assert.deepEqual(presets.speciesForms.filter(entry => entry.learnableMoves.some(
    move => !presets.moveTypes[move.move.showdownId.toLowerCase().replace(/[^a-z0-9]+/g, '')]
  )), []);
  assert.deepEqual(presets.speciesForms.filter(entry => entry.learnableMoves.some(
    move => move.source !== 'CHAMPIONS_SNAPSHOT'
  )), []);
  assert.equal(presets.movePriorities.protect, 4);
  assert.equal(presets.movePriorities.helpinghand, 5);
  assert.equal(presets.movePriorities.extremespeed, 2);
  assert.equal(presets.movePriorities.iceshard, 1);

  const mawileForms = presets.speciesForms.filter(entry => entry.familyId === 'mawile');
  assert.deepEqual(mawileForms.map(entry => entry.species.showdownId), ['Mawile', 'Mawile-Mega']);
  const mawile = mawileForms.find(entry => entry.species.showdownId === 'Mawile');
  const megaMawile = mawileForms.find(entry => entry.species.showdownId === 'Mawile-Mega');
  assert.equal(megaMawile.defaultAbility.showdownId, 'Huge Power');
  assert.deepEqual(mawile.abilities.map(entry => entry.showdownId), ['Hyper Cutter', 'Intimidate', 'Sheer Force']);
  assert.deepEqual(megaMawile.abilities.map(entry => entry.showdownId), ['Huge Power']);
  for (const move of ['Play Rough', 'Iron Head', 'Sucker Punch', 'Protect']) {
    assert.ok(mawile.learnableMoves.some(entry => entry.move.showdownId === move));
  }

  const staraptor = presets.speciesForms.find(entry => entry.species.showdownId === 'Staraptor');
  const staraptorMoves = new Set(staraptor.learnableMoves.map(entry => entry.move.showdownId));
  assert.ok(staraptorMoves.has('Blaze Kick'));
  assert.ok(!staraptorMoves.has('Toxic'));

  const armarougeForm = presets.speciesForms.find(entry => entry.species.showdownId === 'Armarouge');
  assert.ok(armarougeForm.learnableMoves.length >= 50);
  assert.equal(presets.moveTypes.armorcannon, 'Fire');
  assert.equal(presets.moveTypes.psychic, 'Psychic');
  assert.equal(
    new Set(armarougeForm.learnableMoves.map(entry => entry.move.showdownId)).size,
    armarougeForm.learnableMoves.length
  );

  const newMegaAbilityChecks = [
    ['Clefable-Mega', 'Magic Bounce'],
    ['Delphox-Mega', 'Levitate'],
    ['Excadrill-Mega', 'Piercing Drill'],
    ['Feraligatr-Mega', 'Dragonize'],
  ];
  for (const [species, ability] of newMegaAbilityChecks) {
    const form = presets.speciesForms.find(entry => entry.species.showdownId === species);
    assert.deepEqual(form.abilities.map(entry => entry.showdownId), [ability]);
  }

  const sinistcha = presets.species.find(entry => entry.species.showdownId === 'Sinistcha');
  assert.ok(sinistcha);
  assert.ok(sinistcha.profiles.length >= 5);
  assert.ok(sinistcha.profiles.some(profile => profile.moves.some(move => move.move.showdownId === 'Matcha Gotcha')));
  assert.equal(
    sinistcha.profiles.flatMap(profile => profile.moves).find(move => move.move.showdownId === 'Matcha Gotcha').basePower,
    80
  );
  assert.equal(presets.moveTypes.matchagotcha, 'Grass');
  assert.deepEqual(
    presets.species.find(entry => entry.species.showdownId === 'Armarouge').profiles[0].actualStats,
    {hp: 161, atk: 72, def: 120, spa: 194, spd: 100, spe: 127}
  );
});

test('manual Mega form uses the transformed species, stats, and ability', async () => {
  const engine = await loadEngine();
  const presets = await readJson('src', 'data', 'damage', 'champions-presets.json');
  const armarouge = presets.species.find(entry => entry.species.showdownId === 'Armarouge');
  const profile = selectedProfile(armarouge.profiles[0]);
  const mawileForms = presets.speciesForms.filter(entry => entry.familyId === 'mawile');
  const base = mawileForms.find(entry => entry.species.showdownId === 'Mawile');
  const mega = mawileForms.find(entry => entry.species.showdownId === 'Mawile-Mega');

  function request(form, actualStats, ability) {
    return {
      requestId: `battle-overlay-${form.species.showdownId}`,
      calculationDirection: 'OWN_TO_OPPONENT',
      attackerSide: 'OWN',
      defenderSide: 'OPPONENT',
      attacker: {
        species: form.species,
        level: 50,
        actualStats,
        ability,
        item: {entityType: 'item', canonicalId: 'item.mawilite', showdownId: 'Mawilite', displayName: '大嘴娃进化石'},
        moves: [{move: {entityType: 'move', canonicalId: 'move.playrough', showdownId: 'Play Rough', displayName: '嬉闹'}, source: 'OWN_BUILD'}],
      },
      defenderIdentity: {species: armarouge.species},
      defenderProfileSet: {
        defenderSpecies: armarouge.species,
        selectedProfileId: profile.profileId,
        profiles: [profile],
      },
      moveSelection: {mode: 'ONE_MOVE', moveId: 'Play Rough'},
      battle: {battleType: 'DOUBLE', weather: 'NONE', terrain: 'NONE'},
      calculationMode: 'TEMPLATE',
    };
  }

  const baseResult = JSON.parse(engine.calculateDamage(JSON.stringify(request(
    base,
    {hp: 157, atk: 150, def: 105, spa: 75, spd: 77, spe: 63},
    base.defaultAbility
  ))));
  const megaResult = JSON.parse(engine.calculateDamage(JSON.stringify(request(
    mega,
    {hp: 157, atk: 172, def: 145, spa: 75, spd: 117, spe: 63},
    mega.defaultAbility
  ))));
  assert.equal(baseResult.ok, true);
  assert.equal(megaResult.ok, true);
  assert.equal(megaResult.result.attackerSummary.speciesName, '超级大嘴娃');
  assert.ok(
    megaResult.result.moveResults[0].selectedProfileRange.maxDamage >
      baseResult.result.moveResults[0].selectedProfileRange.maxDamage
  );
});

test('confirmed opponent identity and preset calculate own output offline', async () => {
  const engine = await loadEngine();
  const ownTeam = await readJson('test', 'fixtures', 'saved-team.synthetic.json');
  const presets = await readJson('src', 'data', 'damage', 'champions-presets.json');
  const armarouge = presets.species.find(entry => entry.species.showdownId === 'Armarouge');
  const profile = selectedProfile(armarouge.profiles[0]);
  const attacker = ownTeam.pokemon[0];
  const request = {
    requestId: 'battle-overlay-own-output',
    calculationDirection: 'OWN_TO_OPPONENT',
    attackerSide: 'OWN',
    defenderSide: 'OPPONENT',
    attacker,
    defenderIdentity: {species: armarouge.species},
    defenderProfileSet: {
      defenderSpecies: armarouge.species,
      selectedProfileId: profile.profileId,
      profiles: [profile],
    },
    moveSelection: {mode: 'ONE_MOVE', moveId: attacker.moves[0].move.showdownId},
    battle: {battleType: 'DOUBLE', weather: 'NONE', terrain: 'NONE'},
    calculationMode: 'TEMPLATE',
  };
  const response = JSON.parse(engine.calculateDamage(JSON.stringify(request)));
  assert.equal(response.ok, true);
  assert.equal(response.result.calculationDirection, 'OWN_TO_OPPONENT');
  assert.equal(response.result.defenderIdentity.species.showdownId, 'Armarouge');
  assert.equal(response.result.selectedDefenderProfile.profileId, profile.profileId);
  assert.equal(response.result.moveResults.length, 1);
});

test('opponent preset moves stay first while the Champions learnset remains legal', async () => {
  const engine = await loadEngine();
  const ownTeam = await readJson('test', 'fixtures', 'saved-team.synthetic.json');
  const presets = await readJson('src', 'data', 'damage', 'champions-presets.json');
  const armarouge = presets.species.find(entry => entry.species.showdownId === 'Armarouge');
  const profile = selectedProfile(armarouge.profiles[0]);
  const form = presets.speciesForms.find(entry => entry.species.showdownId === 'Armarouge');
  const orderedMoveEntries = [...profile.moves, ...form.learnableMoves];
  const legalMoves = [...new Map(
    orderedMoveEntries.map(entry => [entry.move.showdownId, entry.move])
  ).values()];
  assert.deepEqual(
    legalMoves.slice(0, profile.moves.length).map(entry => entry.showdownId),
    profile.moves.map(entry => entry.move.showdownId)
  );
  assert.ok(legalMoves.length > profile.moves.length);
  const selectedMove = legalMoves[0];
  const request = {
    requestId: 'battle-overlay-opponent-output',
    calculationDirection: 'OPPONENT_TO_OWN',
    attackerSide: 'OPPONENT',
    defenderSide: 'OWN',
    attackerIdentity: {species: armarouge.species},
    attackerProfileSet: {
      attackerSpecies: armarouge.species,
      selectedProfileId: profile.profileId,
      profiles: [profile],
    },
    attackerLegalMovePool: {
      species: armarouge.species,
      rulesetVersion: 'pkmn-mods-champions-0.10.11',
      source: 'CHAMPIONS_SNAPSHOT',
      learnableMoves: legalMoves,
    },
    defender: ownTeam.pokemon[0],
    moveSelection: {
      mode: 'ONE_MOVE',
      moveId: selectedMove.showdownId,
      source: 'OPPONENT_LEGAL_MOVE_POOL',
      legalMovePoolVersion: 'pkmn-mods-champions-0.10.11',
    },
    battle: {battleType: 'DOUBLE', weather: 'NONE', terrain: 'NONE'},
    calculationMode: 'TEMPLATE',
  };
  const response = JSON.parse(engine.calculateDamage(JSON.stringify(request)));
  assert.equal(response.ok, true);
  assert.equal(response.result.calculationDirection, 'OPPONENT_TO_OWN');
  assert.equal(response.result.selectedAttackerProfile.profileId, profile.profileId);
  assert.equal(response.result.moveResults.length, 1);
  assert.equal(response.result.moveResults[0].moveSource, 'PROFILE_PRESET');
  assert.ok(!response.result.warnings.some(warning => warning.code === 'LEGAL_MOVE_POOL_MISSING'));
});

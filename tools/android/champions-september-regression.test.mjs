import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {championsDex, snapshot, officialItems} from '../champions-data.mjs';
import {resolveItem} from '../team-code-resolver/entity-map.mjs';
import {loadMoveCoverageInputs, validateMoveCoverage} from './validate-champions-moves.mjs';
import {loadCatalogCoverageInputs, validateCatalogCoverage} from './validate-champions-catalogs.mjs';
const require = createRequire(import.meta.url);
const {Generations} = require('../../external/smogon-damage-calc/calc/dist');
const presets = require('../../src/data/damage/champions-presets.json');
const context = {window: {}, console};
vm.runInNewContext(fs.readFileSync(new URL('../../android-app/app/src/main/assets/damage-engine.js', import.meta.url), 'utf8'), context);
const engine = context.window.PokemonChampionsDamageEngine;
test('all client entity catalogs, form parameters, presets and recognition identities are complete', () => {
  assert.deepEqual(validateCatalogCoverage(loadCatalogCoverageInputs()), {
    availableMoves: 512, clientForms: 396, assignedAbilities: 215, items: 166,
    natures: 25, battleTypes: 18, iconSpecies: 359,
  });
});

test('catalog guards reject missing abilities/items/icons and stale form parameters or preset abilities', () => {
  const input = loadCatalogCoverageInputs();
  for (const [name, pattern] of [['Thermal Exchange', /Missing Chinese ability/], ['Air Balloon', /Missing Chinese item/]]) {
    assert.throws(() => validateCatalogCoverage({...input, localization: input.localization.filter(row => row.showdownId !== name)}), pattern);
  }
  const numericMap = structuredClone(input.numericMap);
  delete numericMap.items[541];
  assert.throws(() => validateCatalogCoverage({...input, numericMap}), /Missing numeric item/);
  assert.throws(() => validateCatalogCoverage({...input, templates: input.templates.filter(row => row.showdownId !== 'Pawmot')}), /Missing\/mismatched recognition template/);
  const wrong = structuredClone(input.presets);
  wrong.speciesForms.find(row => row.species.showdownId === 'Aegislash-Shield').baseStats.atk = 140;
  wrong.speciesForms.find(row => row.species.showdownId === 'Baxcalibur-Mega').abilities.push({showdownId: 'Ice Body'});
  wrong.species.find(row => row.species.showdownId === 'Charizard-Mega-X').profiles[0].ability = {showdownId: 'Blaze'};
  assert.throws(() => validateCatalogCoverage({...input, presets: wrong}), /Wrong species stat: Aegislash-Shield atk/);
  assert.throws(() => validateCatalogCoverage({...input, presets: wrong}), /Wrong selectable abilities: Baxcalibur-Mega/);
  assert.throws(() => validateCatalogCoverage({...input, presets: wrong}), /Invalid preset ability: Charizard-Mega-X/);
  const localization = structuredClone(input.localization);
  localization.find(row => row.showdownId === 'Morpeko-Hangry').localizedNames['zh-Hans'] = ['莫鲁贝可-Hangry'];
  assert.throws(() => validateCatalogCoverage({...input, localization}), /Incomplete Chinese species: Morpeko-Hangry/);
  const templates = structuredClone(input.templates);
  templates[0].displayName = '过期名称';
  assert.throws(() => validateCatalogCoverage({...input, templates}), /Stale recognition label/);
});
test('all 512 client moves and all 396 client forms survive the entire generated data pipeline', () => {
  assert.deepEqual(validateMoveCoverage(loadMoveCoverageInputs()), {availableMoves: 512, clientForms: 396});
});

test('coverage validation detects upstream filtering, missing names, missing IDs and incorrect form pools', () => {
  const input = loadMoveCoverageInputs();
  assert.throws(() => validateMoveCoverage({...input,
    generation: {moves: {get: id => id === 'doubleshock' ? undefined : input.generation.moves.get(id)}},
  }), /Missing calculator move: Double Shock/);
  assert.throws(() => validateMoveCoverage({...input,
    localization: input.localization.filter(row => row.showdownId !== 'Revival Blessing'),
  }), /Missing Chinese move: Revival Blessing/);
  const numericMap = structuredClone(input.numericMap);
  delete numericMap.moves[892];
  assert.throws(() => validateMoveCoverage({...input, numericMap}), /Missing numeric move: 892 Double Shock/);
  const brokenPresets = structuredClone(input.presets);
  const pawmot = brokenPresets.speciesForms.find(row => row.species.showdownId === 'Pawmot');
  pawmot.learnableMoves = pawmot.learnableMoves.filter(row => row.move.showdownId !== 'Double Shock');
  assert.throws(() => validateMoveCoverage({...input, presets: brokenPresets}), /Missing learnable move: Pawmot doubleshock/);
  pawmot.learnableMoves.push({move: {showdownId: 'Pyro Ball'}});
  assert.throws(() => validateMoveCoverage({...input, presets: brokenPresets}), /Unexpected learnable move: Pawmot pyroball/);
});

test('exact client form pools preserve female Mega Meowstic and Hangry Morpeko differences', () => {
  const moves = name => new Set(presets.speciesForms.find(row => row.species.showdownId === name).learnableMoves.map(row => row.move.showdownId));
  const femaleMega = moves('Meowstic-F-Mega');
  assert.ok(femaleMega.has('Future Sight'));
  assert.ok(femaleMega.has('Extrasensory'));
  assert.ok(!femaleMega.has('Wish'));
  assert.ok(!moves('Morpeko-Hangry').has('Rising Voltage'));
  assert.ok(moves('Morpeko').has('Rising Voltage'));
  assert.ok(!moves('Pikachu').has('Spark'), 'Client available=0 moves must not leak from raw learnsets');
  assert.ok(moves('Baxcalibur-Mega').has('Glaive Rush'));
});
test('all 166 client v18 items reach calculator, Chinese selection/OCR and team-code mapping', () => {
  const names = new Map(require('../../src/data/localization/zh-Hans.json')
    .filter(row => row.entityType === 'item').map(row => [row.showdownId, row]));
  assert.equal(officialItems.entries.length, 166);
  for (const {number, showdownId} of officialItems.entries) {
    const item = championsDex.items.get(showdownId);
    assert.equal(item.exists, true, showdownId);
    assert.equal(item.isNonstandard, null, showdownId);
    assert.ok(Generations.get(0).items.get(item.id), `Missing calculator item ${showdownId}`);
    assert.equal(resolveItem(number), showdownId);
    assert.ok(names.get(showdownId)?.localizedNames['zh-Hans']?.some(name => /[\u4e00-\u9fff]/.test(name)), `Missing Chinese item ${showdownId}`);
  }
  for (const name of ['Rocky Helmet', 'Air Balloon', 'Eject Button', 'Red Card',
    'Electric Seed', 'Grassy Seed', 'Misty Seed', 'Psychic Seed', 'Normal Gem', 'Binding Band', 'Terrain Extender']) {
    assert.ok(officialItems.entries.some(row => row.showdownId === name), name);
  }
  assert.ok(championsDex.items.get('Eviolite').isNonstandard, 'Do not admit unrelated Gen 9 items');
  assert.ok(names.get('Rocky Helmet').aliases.includes('突突头盔'));
  assert.ok(names.get('Eject Button').aliases.includes('逃脱按钮'));
});
test('every legal species ability reaches calculator and Chinese OCR catalog', () => {
  const names = new Set(require('../../src/data/localization/zh-Hans.json').filter(row => row.entityType === 'ability').map(row => row.showdownId));
  const gen = Generations.get(0);
  for (const id of snapshot.legalSpecies) {
    for (const name of Object.values(championsDex.species.get(id).abilities)) {
      assert.ok(gen.abilities.get(name.toLowerCase().replace(/[^a-z0-9]/g, '')), `${id}: missing calculator ability ${name}`);
      assert.ok(names.has(name), `${id}: missing OCR ability ${name}`);
    }
  }
});
test('client v18 confirms Meteor Assault in Sirfetchd selectable move pool', () => {
  const form = presets.speciesForms.find(row => row.species.showdownId === "Sirfetch’d" || row.species.canonicalId === "species.sirfetchd");
  assert.ok(form);
  assert.ok(form.learnableMoves.some(row => row.move.showdownId === "Meteor Assault" && row.basePower === 170));
});
const ref = (entityType, showdownId) => ({entityType, canonicalId: `${entityType}.${showdownId.toLowerCase().replace(/[^a-z0-9]/g, '')}`, showdownId, displayName: showdownId});
function damage({species = 'Golisopod-Mega', ability = 'Tough Claws', move = 'Slash', attackerItem, defenderSpecies = 'Snorlax', defenderAbility = 'Immunity', defenderItem, defenderStatStages, battle = {}} = {}) {
  const result = JSON.parse(engine.calculateDamage(JSON.stringify({
    requestId: 'september-regression', calculationDirection: 'OWN_TO_OPPONENT', attackerSide: 'OWN', defenderSide: 'OPPONENT',
    attacker: {species: ref('species', species), ability: ref('ability', ability), item: attackerItem ? ref('item', attackerItem) : undefined, level: 50, actualStats: {hp: 200, atk: 150, def: 150, spa: 150, spd: 150, spe: 150}, moves: [{move: ref('move', move), source: 'OWN_BUILD'}]},
    defenderIdentity: {species: ref('species', defenderSpecies)},
    defenderProfileSet: {defenderSpecies: ref('species', defenderSpecies), selectedProfileId: 'exact', profiles: [{profileId: 'exact', profileName: 'Exact', source: 'MANUAL_CURRENT', isSelected: true, level: 50, ability: ref('ability', defenderAbility), item: defenderItem ? ref('item', defenderItem) : undefined, statStages: defenderStatStages, actualStats: {hp: 200, atk: 150, def: 150, spa: 150, spd: 150, spe: 150}}]},
    moveSelection: {mode: 'ONE_MOVE', moveId: move}, battle: {battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE', ...battle}, calculationMode: 'EXACT',
  })));
  assert.equal(result.ok, true, JSON.stringify(result));
  return result.result.moveResults[0].selectedProfileRange.maxDamage;
}

test('every client-assigned ability and held item reaches the packaged engine on both sides', () => {
  const {roster, items} = loadCatalogCoverageInputs();
  for (const row of roster.abilities) {
    const holder = roster.forms.find(form => form.abilityNumbers.includes(row.number)).showdownId;
    assert.ok(Number.isFinite(damage({species: holder, ability: row.showdownId})), `Attacking ability ${row.showdownId}`);
    assert.ok(Number.isFinite(damage({defenderSpecies: holder, defenderAbility: row.showdownId})), `Defending ability ${row.showdownId}`);
  }
  for (const row of items.entries) {
    assert.ok(Number.isFinite(damage({attackerItem: row.showdownId})), `Attacking item ${row.showdownId}`);
    assert.ok(Number.isFinite(damage({defenderItem: row.showdownId})), `Defending item ${row.showdownId}`);
  }
});

test('all ten previously missing moves calculate in the packaged Android engine', () => {
  assert.equal(damage({species: 'Pawmot', ability: 'Volt Absorb', move: 'Double Shock'}), 81);
  for (const move of ['Revival Blessing', 'Shift Gear', 'Court Change']) {
    assert.equal(damage({species: 'Pawmot', ability: 'Volt Absorb', move}), 0, `${move} has no direct damage`);
  }
  for (const [species, move] of [
    ['Rillaboom', 'Drum Beating'], ['Cinderace', 'Pyro Ball'], ['Toxtricity', 'Overdrive'],
    ['Pincurchin', 'Zing Zap'], ['Baxcalibur', 'Glaive Rush'], ['Mabosstiff', 'Jaw Lock'],
  ]) assert.ok(damage({species, move, ability: 'Illuminate'}) > 0, move);
  assert.ok(damage({species: 'Toxtricity', move: 'Overdrive', ability: 'Punk Rock'}) > damage({species: 'Toxtricity', move: 'Overdrive', ability: 'Illuminate'}));
  assert.ok(damage({species: 'Mabosstiff', move: 'Jaw Lock', ability: 'Strong Jaw'}) > damage({species: 'Mabosstiff', move: 'Jaw Lock', ability: 'Illuminate'}));
});

test('Punk Rock sound modifiers apply only to active abilities and respect Mold Breaker', () => {
  const sound = {species: 'Toxtricity', move: 'Overdrive', ability: 'Illuminate'};
  assert.equal(damage({...sound, defenderAbility: 'Punk Rock'}), Math.floor(damage(sound) / 2));
  assert.equal(damage({...sound, ability: 'Mold Breaker', defenderAbility: 'Punk Rock'}), damage(sound));
  assert.equal(damage({...sound, ability: 'Punk Rock', battle: {isNeutralizingGas: true}}), damage(sound));
  assert.equal(damage({...sound, defenderAbility: 'Punk Rock', battle: {isNeutralizingGas: true}}), damage(sound));
  assert.equal(damage({move: 'Surf', ability: 'Punk Rock'}), damage({move: 'Surf', ability: 'Illuminate'}));
  assert.equal(damage({move: 'Surf', defenderAbility: 'Punk Rock'}), damage({move: 'Surf', defenderAbility: 'Illuminate'}));
  assert.equal(damage({...sound, defenderAbility: 'Soundproof'}), 0);
});

test('generated Android engine applies Air Balloon immunity', () => {
  assert.ok(damage({move: 'Earthquake'}) > 0);
  assert.equal(damage({move: 'Earthquake', defenderItem: 'Air Balloon'}), 0);
  assert.ok(damage({move: 'Earthquake', defenderItem: 'Air Balloon', battle: {isGravity: true}}) > 0);
  assert.ok(damage({move: 'Earthquake', defenderItem: 'Air Balloon', battle: {isMagicRoom: true}}) > 0);
});

test('terrain seeds use manual stat stages without automatically adding another boost', () => {
  for (const [defenderItem, terrain, move] of [
    ['Electric Seed', 'Electric', 'Slash'], ['Grassy Seed', 'Grassy', 'Slash'],
    ['Misty Seed', 'Misty', 'Surf'], ['Psychic Seed', 'Psychic', 'Surf'],
  ]) {
    assert.equal(damage({defenderItem, move, battle: {terrain}}), damage({move, battle: {terrain}}), `${defenderItem} must not auto-boost`);
    const defenderStatStages = move === 'Slash' ? {def: 1} : {spd: 1};
    const manual = damage({defenderItem, move, defenderStatStages, battle: {terrain}});
    assert.ok(manual < damage({defenderItem, move, battle: {terrain}}), `${defenderItem} manual stage applies`);
    assert.equal(manual, damage({move, defenderStatStages, battle: {terrain}}), `${defenderItem} must not double-count manual stage`);
    assert.equal(damage({defenderItem, move}), damage({move}), `${defenderItem} without terrain`);
    assert.equal(damage({defenderItem, move, battle: {terrain, isMagicRoom: true}}), damage({move, battle: {terrain, isMagicRoom: true}}), `${defenderItem} in Magic Room`);
  }
});

test('generated Android engine applies Normal Gem only to Normal attacks while items are active', () => {
  assert.ok(damage({attackerItem: 'Normal Gem'}) > damage() * 1.2);
  assert.equal(damage({attackerItem: 'Normal Gem', move: 'Surf'}), damage({move: 'Surf'}));
  assert.equal(damage({attackerItem: 'Normal Gem', battle: {isMagicRoom: true}}), damage({battle: {isMagicRoom: true}}));
});

test('all current Showdown species and move powers reach calculator and UI data', () => {
  const gen = Generations.get(0);
  const forms = new Map(presets.speciesForms.map(f => [f.species.canonicalId.replace('species.', ''), f]));
  for (const id of snapshot.legalSpecies) {
    const localId = id === 'aegislash' ? 'aegislashshield' : id;
    const dex = championsDex.species.get(id);
    const form = forms.get(localId);
    assert.ok(form, `Missing form ${id}`);
    assert.ok(form.learnableMoves.length, `Empty learnset ${id}`);
    assert.equal(form.defaultAbility.showdownId, dex.abilities['0'], id);
    assert.equal(gen.species.get(localId).abilities[0], dex.abilities['0'], id);
    assert.deepEqual(gen.species.get(localId).baseStats, dex.baseStats, `${id} base stats`);
    for (const entry of form.learnableMoves) {
      assert.equal(entry.basePower, championsDex.moves.get(entry.move.showdownId).basePower, `${id}:${entry.move.showdownId}`);
    }
  }
  assert.equal(gen.moves.get('meteorassault').basePower, 170);
  assert.equal(gen.moves.get('slash').basePower, 80);
  assert.equal(Generations.get(9).moves.get('meteorassault').basePower, 150);
  assert.equal(Generations.get(9).moves.get('slash').basePower, 70);
  assert.equal(championsDex.moves.get('strengthsap').pp, 5);
  assert.equal(championsDex.moves.get('wish').pp, 5);
});

test('Meteor Assault uses 170 power in the generated Android engine', () => {
  // Atk=Def=150, level=50, no STAB, Snorlax weak to Fighting:
  // floor(floor(22 * 170) / 50) + 2 = 76, then 2x effectiveness.
  assert.equal(damage({species: 'Golisopod-Mega', ability: 'Illuminate', move: 'Meteor Assault'}), 152);
});

test('new Mega abilities affect the generated Android engine', () => {
  const neutral = damage({ability: 'Illuminate'});
  assert.ok(damage() > neutral * 1.2, 'Tough Claws must boost contact damage');
  assert.equal(damage({move: 'Surf'}), damage({move: 'Surf', ability: 'Illuminate'}));
  assert.ok(damage({species: 'Absol-Mega-Z', ability: 'Sharpness'}) > damage({species: 'Absol-Mega-Z', ability: 'Illuminate'}) * 1.4);
  assert.equal(damage({move: 'Earthquake', defenderSpecies: 'Garchomp-Mega-Z', defenderAbility: 'Levitate'}), 0);
  assert.ok(damage({move: 'Earthquake', ability: 'Mold Breaker', defenderSpecies: 'Garchomp-Mega-Z', defenderAbility: 'Levitate'}) > 0);
  const guard = {defenderSpecies: 'Lucario-Mega-Z', defenderAbility: 'Aura Guard'};
  const unguarded = {defenderSpecies: 'Lucario-Mega-Z', defenderAbility: 'Illuminate'};
  assert.ok(damage(guard) <= Math.ceil(damage(unguarded) / 2));
  assert.equal(damage({...guard, move: 'Flamethrower'}), damage({...unguarded, move: 'Flamethrower'}), 'Aura Guard must not inherit Fluffy fire weakness');
  assert.equal(damage({...guard, ability: 'Long Reach'}), damage({...unguarded, ability: 'Long Reach'}));
});

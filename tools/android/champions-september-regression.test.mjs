import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {championsDex, snapshot} from '../champions-data.mjs';
const require = createRequire(import.meta.url);
const {Generations} = require('../../external/smogon-damage-calc/calc/dist');
const presets = require('../../src/data/damage/champions-presets.json');
const context = {window: {}, console};
vm.runInNewContext(fs.readFileSync(new URL('../../android-app/app/src/main/assets/damage-engine.js', import.meta.url), 'utf8'), context);
const engine = context.window.PokemonChampionsDamageEngine;
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
function damage({species = 'Golisopod-Mega', ability = 'Tough Claws', move = 'Slash', defenderSpecies = 'Snorlax', defenderAbility = 'Immunity', battle = {}} = {}) {
  const result = JSON.parse(engine.calculateDamage(JSON.stringify({
    requestId: 'september-regression', calculationDirection: 'OWN_TO_OPPONENT', attackerSide: 'OWN', defenderSide: 'OPPONENT',
    attacker: {species: ref('species', species), ability: ref('ability', ability), level: 50, actualStats: {hp: 200, atk: 150, def: 150, spa: 150, spd: 150, spe: 150}, moves: [{move: ref('move', move), source: 'OWN_BUILD'}]},
    defenderIdentity: {species: ref('species', defenderSpecies)},
    defenderProfileSet: {defenderSpecies: ref('species', defenderSpecies), selectedProfileId: 'exact', profiles: [{profileId: 'exact', profileName: 'Exact', source: 'MANUAL_CURRENT', isSelected: true, level: 50, ability: ref('ability', defenderAbility), actualStats: {hp: 200, atk: 150, def: 150, spa: 150, spd: 150, spe: 150}}]},
    moveSelection: {mode: 'ONE_MOVE', moveId: move}, battle: {battleType: 'SINGLE', weather: 'NONE', terrain: 'NONE', ...battle}, calculationMode: 'EXACT',
  })));
  assert.equal(result.ok, true, JSON.stringify(result));
  return result.result.moveResults[0].selectedProfileRange.maxDamage;
}

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

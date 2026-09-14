import {createRequire} from 'node:module';
const require = createRequire(import.meta.url);
export const snapshot = require('../src/data/damage/champions-showdown-snapshot.json');
const packages = require('../package.json').devDependencies;
if (packages['@pkmn/dex'] !== snapshot.basePackages || packages['@pkmn/mods'] !== snapshot.basePackages) {
  throw new Error('Regenerate the Champions snapshot against the updated @pkmn package baseline.');
}
const {Dex} = require('@pkmn/dex');
const baseline = require('@pkmn/mods/champions');
const mod = {...baseline};
for (const [sourceKey, rows] of Object.entries(snapshot.mod)) {
  // @pkmn/dex calls Showdown's Pokedex table Species.
  const key = sourceKey === 'Pokedex' ? 'Species' : sourceKey;
  mod[key] = {...baseline[key], ...structuredClone(rows)};
}
// The client table includes held items still marked Past by the pinned upstream
// snapshot. Keep this verified source separate from the Showdown snapshot.
export const officialItems = require('../src/data/damage/champions-items.v18.json');
for (const {showdownId} of officialItems.entries) {
  const id = showdownId.toLowerCase().replace(/[^a-z0-9]/g, '');
  mod.Items[id] = {...mod.Items[id], inherit: true, isNonstandard: null};
}
// Availability and exact form learnsets come from the verified client, not the
// older upstream Past/Custom flags. Keep the pinned Showdown snapshot untouched.
export const officialMoves = require('../src/data/damage/champions-moves.v18.json');
const moveIdByNumber = new Map();
for (const {number, showdownId} of officialMoves.entries) {
  const move = Dex.moves.get(showdownId);
  if (!move.exists || move.num !== number) throw new Error(`Invalid client move mapping: ${number} ${showdownId}`);
  moveIdByNumber.set(number, move.id);
  mod.Moves[move.id] = {...mod.Moves[move.id], inherit: true, isNonstandard: null};
}
const clientLearnsets = new Map();
const normalize = value => value.toLowerCase().replace(/[^a-z0-9]/g, '');
for (const form of officialMoves.forms) {
  const id = normalize(form.showdownId);
  const moves = form.moveNumbers.map(number => {
    const move = moveIdByNumber.get(number);
    if (!move) throw new Error(`Unavailable move ${number} in client form ${form.clientFormId}`);
    return move;
  });
  if (clientLearnsets.has(id) && JSON.stringify(clientLearnsets.get(id)) !== JSON.stringify(moves)) {
    throw new Error(`Conflicting client learnsets for shared form ${form.showdownId}`);
  }
  clientLearnsets.set(id, moves);
  mod.Learnsets[id] = {learnset: Object.fromEntries(moves.map(move => [move, ['champions-master-data-v18']]))};
}
// The calculator uses Shield/Both while Showdown calls the shield form Aegislash.
for (const id of ['aegislash', 'aegislashshield']) {
  clientLearnsets.set(id, clientLearnsets.get('aegislashboth'));
  mod.Learnsets[id] = structuredClone(mod.Learnsets.aegislashboth);
}
export function clientMoveIds(speciesName) {
  const moves = clientLearnsets.get(normalize(speciesName));
  if (!moves?.length) throw new Error(`Missing client learnset for ${speciesName}; refresh the verified client catalog.`);
  return moves;
}
export const championsDex = Dex.mod('champions', mod);

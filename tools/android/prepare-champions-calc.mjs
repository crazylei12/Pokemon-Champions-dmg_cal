import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {championsDex, snapshot} from '../champions-data.mjs';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const calc = path.join(root, 'external/smogon-damage-calc/calc');
// Always start from the pinned, untouched upstream source. Generated dist is ignored.
fs.rmSync(path.join(calc, '.tsbuildinfo'), {force: true});
execFileSync(process.execPath, [path.join(calc, 'node_modules/typescript/bin/tsc'), '-p', calc], {stdio: 'inherit'});
const {SPECIES} = require(path.join(calc, 'dist/data/species.js'));
const {MOVES} = require(path.join(calc, 'dist/data/moves.js'));
const species = {};
for (const id of snapshot.legalSpecies) {
  const dex = championsDex.species.get(id);
  // The calculator represents this with its existing Shield/Both entries.
  if (id === 'aegislash') continue;
  const base = SPECIES[0][dex.name] || SPECIES[9][dex.name];
  if (!base) throw new Error(`Missing upstream species definition: ${dex.name}`);
  species[dex.name] = {...base, abilities: {0: dex.abilities['0']}};
  if (dex.otherFormes) species[dex.name].otherFormes = dex.otherFormes.filter(name => snapshot.legalSpecies.includes(championsDex.species.get(name).id));
}
const moves = {};
const flagMap = {contact: 'makesContact', punch: 'isPunch', bite: 'isBite', bullet: 'isBullet', sound: 'isSound', pulse: 'isPulse', slicing: 'isSlicing', wind: 'isWind'};
for (const dex of championsDex.moves.all()) {
  // championsDex explicitly admits all verified client moves. Keep upstream
  // compatibility entries for confirmed saved teams, without using them as proof
  // that a move is currently selectable in a species' client learnset.
  if (dex.isNonstandard && !MOVES[0][dex.name]) continue;
  const base = MOVES[0][dex.name] || MOVES[9][dex.name];
  if (!base) throw new Error(`Missing upstream move definition: ${dex.name}`);
  const entry = {...base, bp: dex.basePower, type: dex.type, category: dex.category, priority: dex.priority};
  for (const [flag, key] of Object.entries(flagMap)) entry[key] = !!dex.flags[flag];
  moves[dex.name] = entry;
}
const items = championsDex.items.all().filter(row => !row.isNonstandard).map(row => row.name);
const abilities = [...new Set(snapshot.legalSpecies.flatMap(id => Object.values(championsDex.species.get(id).abilities)))];
function insert(file, anchor, code) {
  const target = path.join(calc, 'dist/data', file);
  const source = fs.readFileSync(target, 'utf8');
  if (source.split(anchor).length !== 2) throw new Error(`Missing or ambiguous upstream data anchor: ${file}`);
  fs.writeFileSync(target, source.replace(anchor, `${code}\n${anchor}`));
}
insert('species.js', 'exports.SPECIES = [CHAMPIONS,', `Object.assign(CHAMPIONS, ${JSON.stringify(species)});`);
insert('moves.js', 'exports.MOVES = [CHAMPIONS,', `Object.assign(CHAMPIONS, ${JSON.stringify(moves)});`);
insert('items.js', 'exports.ITEMS = [CHAMPIONS,', `CHAMPIONS = Array.from(new Set(CHAMPIONS.concat(${JSON.stringify(items)})));`);
insert('abilities.js', 'exports.ABILITIES = [CHAMPIONS,', `CHAMPIONS.push(...${JSON.stringify(abilities)}.filter(name => !CHAMPIONS.includes(name)));`);
console.log(`Prepared Champions ${snapshot.dataDate}: ${Object.keys(species).length} species, ${Object.keys(moves).length} moves, ${items.length} items.`);

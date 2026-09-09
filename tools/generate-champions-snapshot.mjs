import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {execFileSync} from 'node:child_process';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {transformSync} from 'esbuild';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const checkout = path.resolve(process.argv[2] || '');
if (!process.argv[2]) throw new Error('Pass a clean Pokémon Showdown checkout to snapshot.');
if (execFileSync('git', ['status', '--porcelain'], {cwd: checkout, encoding: 'utf8'}).trim()) {
  throw new Error('Showdown snapshot requires a clean checkout.');
}
const revision = execFileSync('git', ['rev-parse', 'HEAD'], {cwd: checkout, encoding: 'utf8'}).trim();
function read(relative, key) {
  const source = fs.readFileSync(path.join(checkout, relative), 'utf8');
  const sandbox = {module: {exports: {}}};
  vm.runInNewContext(transformSync(source, {loader: 'ts', format: 'cjs'}).code, sandbox);
  // Consumers use metadata and learnsets, never simulator event functions.
  return JSON.parse(JSON.stringify(sandbox.module.exports[key]));
}
const {Dex} = require('@pkmn/dex');
const baseline = require('@pkmn/mods/champions');
const keys = {Moves: 'moves', Items: 'items', Abilities: 'abilities', FormatsData: 'formats-data', Learnsets: 'learnsets'};
const tables = Object.fromEntries(Object.entries(keys).map(([key, file]) => [key, read(`data/mods/champions/${file}.ts`, key)]));
const pokedex = read('data/pokedex.ts', 'Pokedex');
const baseAbilities = read('data/abilities.ts', 'Abilities');
const legalSpecies = Object.entries(tables.FormatsData)
  .filter(([, row]) => !row.isNonstandard && row.tier !== 'Illegal')
  .map(([id]) => id).sort();
const patch = {};
for (const [key, table] of Object.entries(tables)) {
  const previous = JSON.parse(JSON.stringify(baseline[key] || {}));
  patch[key] = Object.fromEntries(Object.entries(table).filter(([id, row]) => JSON.stringify(row) !== JSON.stringify(previous[id])));
}
patch.Pokedex = Object.fromEntries(legalSpecies.filter(id => pokedex[id]).map(id => [id, {...pokedex[id], isNonstandard: null}]));
for (const row of Object.values(patch.Pokedex)) {
  for (const name of Object.values(row.abilities || {})) {
    const id = name.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (!Dex.abilities.get(id).exists) patch.Abilities[id] = {...baseAbilities[id], isNonstandard: null};
  }
}
const output = {
  schemaVersion: 1,
  dataDate: '2026-09-09',
  source: 'https://github.com/smogon/pokemon-showdown',
  revision,
  basePackages: '0.10.11',
  legalSpecies,
  mod: patch,
};
const target = path.join(root, 'src/data/damage/champions-showdown-snapshot.json');
fs.writeFileSync(target, `${JSON.stringify(output, null, 2)}\n`);
console.log(JSON.stringify({revision, legalSpecies: legalSpecies.length, tables: Object.fromEntries(Object.entries(patch).map(([k,v]) => [k,Object.keys(v).length]))}));

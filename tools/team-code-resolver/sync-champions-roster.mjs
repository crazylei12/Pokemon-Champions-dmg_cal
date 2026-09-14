import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {championsDex} from '../champions-data.mjs';

const require = createRequire(import.meta.url);
const root = new URL('../../', import.meta.url);
const provenance = require('./data/champions-master-data.v18.provenance.json');
const forms = require('./data/champions-species-forms.v18.json');
const corrections = require('./data/champions-form-corrections.v18.json');
const mapUrl = new URL('tools/team-code-resolver/data/champions-entity-map.v18.json', root);
const map = JSON.parse(fs.readFileSync(mapUrl, 'utf8'));
if (!process.argv[2]) throw new Error('Pass the verified v18 client master-data directory.');
if ([provenance, forms, corrections, map].some(row => row.masterDataVersion !== 18)) throw new Error('Mismatched client versions.');
function readVerified(name) {
  const bytes = fs.readFileSync(path.join(process.argv[2], name));
  if (createHash('sha256').update(bytes).digest('hex') !== provenance.sha256[name]) throw new Error(`Client hash mismatch: ${name}`);
  return JSON.parse(bytes);
}
const personal = readVerified('personal.json');
const abilityRows = readVerified('tokusei.json');
const types = ['Normal', 'Fighting', 'Flying', 'Poison', 'Ground', 'Rock', 'Bug', 'Ghost', 'Steel', 'Fire', 'Water', 'Grass', 'Electric', 'Psychic', 'Ice', 'Dragon', 'Dark', 'Fairy'];
const abilityIds = new Set(abilityRows.map(row => Number(row.id)));
const namesByNumber = new Map(championsDex.abilities.all().map(row => [row.num, row.name]));
const used = [...new Set(personal.flatMap(row => [row.toku0, row.toku1, row.toku2].map(Number)))].sort((a, b) => a - b);
const abilities = used.map(number => {
  const showdownId = map.abilities[number] || namesByNumber.get(number);
  const ability = showdownId && championsDex.abilities.get(showdownId);
  if (!abilityIds.has(number) || !ability?.exists || ability.num !== number) throw new Error(`Unmapped client-used ability ${number}`);
  return {number, showdownId};
});
const byForm = new Map(forms.entries.map(row => [`${row.pokemonNumber}:${row.formNumber}`, row.speciesId]));
if (byForm.size !== personal.length || new Set(personal.map(row => row.id)).size !== personal.length) throw new Error('Incomplete or duplicate client form mapping.');
const entries = personal.map(row => {
  const key = `${Number(row.no)}:${Number(row.fo)}`;
  const showdownId = byForm.get(key);
  if (!showdownId || row.is_valid !== '1') throw new Error(`Missing or invalid client form ${key}`);
  const previous = map.species[key];
  const correction = corrections.entries[key];
  if (previous !== showdownId && !(correction?.before === previous && correction.after === showdownId)) {
    throw new Error(`Undocumented numeric form change: ${key} ${previous} -> ${showdownId}`);
  }
  return {
    clientFormId: row.id, pokemonNumber: Number(row.no), formNumber: Number(row.fo), showdownId,
    types: [...new Set([types[Number(row.type1)], types[Number(row.type2)]])],
    baseStats: {hp: Number(row.hp), atk: Number(row.atk), def: Number(row.def), spa: Number(row.spatk), spd: Number(row.spdef), spe: Number(row.agi)},
    weightkg: Number(row.weight) / 10,
    abilityNumbers: [row.toku0, row.toku1, row.toku2].map(Number),
  };
}).sort((a, b) => a.clientFormId.localeCompare(b.clientFormId));
const roster = {
  schemaVersion: 1, masterDataVersion: 18, clientVersion: provenance.clientVersion,
  sourceSha256: {personal: provenance.sha256['personal.json'], abilities: provenance.sha256['tokusei.json']},
  abilityPolicy: 'All distinct ability IDs actually assigned in the client personal table; dormant tokusei rows are not selectable abilities.',
  abilities, forms: entries,
};
for (const {number, showdownId} of abilities) map.abilities[number] = showdownId;
map.species = Object.fromEntries(forms.entries.map(row => [`${row.pokemonNumber}:${row.formNumber}`, row.speciesId]));
fs.writeFileSync(new URL('src/data/damage/champions-roster.v18.json', root), `${JSON.stringify(roster, null, 2)}\n`);
fs.writeFileSync(mapUrl, `${JSON.stringify(map)}\n`);
console.log(`Verified ${abilities.length} assigned abilities and ${entries.length} exact client forms.`);

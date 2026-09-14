import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {Dex} = require('@pkmn/dex');
const root = new URL('../../', import.meta.url);
const provenanceUrl = new URL('tools/team-code-resolver/data/champions-master-data.v18.provenance.json', root);
const mapUrl = new URL('tools/team-code-resolver/data/champions-entity-map.v18.json', root);
const provenance = JSON.parse(fs.readFileSync(provenanceUrl, 'utf8'));
const map = JSON.parse(fs.readFileSync(mapUrl, 'utf8'));
const forms = require('./data/champions-species-forms.v18.json');
if ([provenance, map, forms].some(data => data.masterDataVersion !== 18)) {
  throw new Error('Move, form and provenance catalogs must all use verified Master Data v18.');
}
if (!process.argv[2]) throw new Error('Pass the verified client v18 master-data directory.');
function verifiedTable(name) {
  const bytes = fs.readFileSync(path.join(process.argv[2], name));
  if (createHash('sha256').update(bytes).digest('hex') !== provenance.sha256[name]) {
    throw new Error(`Client ${name} hash does not match v18 provenance.`);
  }
  return JSON.parse(bytes);
}
const moves = verifiedTable('waza.json');
const learnsets = verifiedTable('waza_learn.json');
const personal = verifiedTable('personal.json');
const namesByNumber = new Map(Dex.moves.all().map(row => [row.num, row.name]));
const entries = moves.filter(row => row.available === '1').map(row => {
  const number = Number(row.id);
  const showdownId = map.moves[number] || namesByNumber.get(number);
  if (!showdownId) throw new Error(`Unmapped available client move ${number}`);
  const move = Dex.moves.get(showdownId);
  if (!move.exists || move.num !== number) throw new Error(`Invalid client move identity ${number}: ${showdownId}`);
  return {number, showdownId};
}).sort((a, b) => a.number - b.number);
const available = new Set(entries.map(row => row.number));
if (available.size !== entries.length || new Set(entries.map(row => row.showdownId)).size !== entries.length) {
  throw new Error('Duplicate available client move mapping.');
}
const formMap = new Map(forms.entries.map(row => [`${row.pokemonNumber}:${row.formNumber}`, row.speciesId]));
const personalById = new Map(personal.map(row => [row.id, row]));
if (learnsets.length !== personal.length || new Set(learnsets.map(row => row.id)).size !== personal.length) {
  throw new Error('Client learnsets must cover every personal row exactly once.');
}
const formEntries = learnsets.map(row => {
  const identity = personalById.get(row.id);
  const showdownId = identity && formMap.get(`${Number(identity.no)}:${Number(identity.fo)}`);
  if (!showdownId) throw new Error(`Unmapped client learnset form ${row.id}`);
  const numbers = row.waza.split(',').map(Number);
  for (const number of numbers) {
    if (!moves.some(move => Number(move.id) === number)) throw new Error(`Unknown move ${number} in ${row.id}`);
  }
  return {clientFormId: row.id, showdownId, moveNumbers: [...new Set(numbers.filter(number => available.has(number)))].sort((a, b) => a - b)};
}).sort((a, b) => a.clientFormId.localeCompare(b.clientFormId));
// Preserve numeric identities for already saved/confirmed teams, including older
// moves. Availability is a separate catalog, never inferred from that legacy map.
for (const {number, showdownId} of entries) {
  if (map.moves[number] && map.moves[number] !== showdownId) throw new Error(`Existing move mapping changed: ${number}`);
  map.moves[number] = showdownId;
}
const asset = {
  schemaVersion: 1, masterDataVersion: 18, clientVersion: provenance.clientVersion,
  sourceSha256: Object.fromEntries(['waza.json', 'waza_learn.json', 'personal.json'].map(name => [name, provenance.sha256[name]])),
  availabilityPolicy: 'waza.available=1; per-form waza_learn intersected with available move IDs.',
  entries, forms: formEntries,
};
fs.writeFileSync(new URL('src/data/damage/champions-moves.v18.json', root), `${JSON.stringify(asset, null, 2)}\n`);
fs.writeFileSync(mapUrl, `${JSON.stringify(map)}\n`);
provenance.coverage.moves = Object.keys(map.moves).length;
provenance.coverage.availableMoves = entries.length;
provenance.coverage.learnsetForms = formEntries.length;
fs.writeFileSync(provenanceUrl, `${JSON.stringify(provenance, null, 2)}\n`);
console.log(`Verified ${entries.length} available moves and ${formEntries.length} client form learnsets; ${provenance.coverage.moves} numeric mappings retained.`);

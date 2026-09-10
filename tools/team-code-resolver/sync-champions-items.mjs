import fs from 'node:fs';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';

const require = createRequire(import.meta.url);
const {Dex} = require('@pkmn/dex');
const root = new URL('../../', import.meta.url);
const provenanceUrl = new URL('tools/team-code-resolver/data/champions-master-data.v18.provenance.json', root);
const mapUrl = new URL('tools/team-code-resolver/data/champions-entity-map.v18.json', root);
const provenance = JSON.parse(fs.readFileSync(provenanceUrl, 'utf8'));
const map = JSON.parse(fs.readFileSync(mapUrl, 'utf8'));
if (!process.argv[2]) throw new Error('Pass the verified client v18 item.json path.');
const bytes = fs.readFileSync(process.argv[2]);
const sha256 = createHash('sha256').update(bytes).digest('hex');
if (sha256 !== provenance.sha256['item.json']) throw new Error('Client item table hash does not match v18 provenance.');
const namesByNumber = new Map(Dex.items.all().map(row => [row.num, row.name]));
const entries = JSON.parse(bytes).map(row => {
  const name = map.items[row.id] || namesByNumber.get(Number(row.id));
  if (!name) throw new Error(`Unmapped official item ${row.id}`);
  return {number: Number(row.id), showdownId: name};
}).sort((a, b) => a.number - b.number);
if (new Set(entries.map(row => row.number)).size !== entries.length ||
    new Set(entries.map(row => row.showdownId)).size !== entries.length) throw new Error('Duplicate official item mapping.');
for (const [number, name] of Object.entries(map.items)) {
  if (!entries.some(row => row.number === Number(number) && row.showdownId === name)) throw new Error(`Existing item mapping changed: ${number}`);
}
fs.writeFileSync(new URL('src/data/damage/champions-items.v18.json', root), `${JSON.stringify({
  schemaVersion: 1, masterDataVersion: 18, clientVersion: provenance.clientVersion,
  sourceSha256: sha256, entries,
}, null, 2)}\n`);
map.items = Object.fromEntries(entries.map(row => [row.number, row.showdownId]));
fs.writeFileSync(mapUrl, `${JSON.stringify(map)}\n`);
provenance.coverage.items = entries.length;
fs.writeFileSync(provenanceUrl, `${JSON.stringify(provenance, null, 2)}\n`);
console.log(`Verified and synchronized ${entries.length} official v18 items.`);

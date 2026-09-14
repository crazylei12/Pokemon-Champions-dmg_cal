import fs from "node:fs";
import path from "node:path";
import crypto from "node:crypto";
import { fileURLToPath } from "node:url";
import { championsDex as Dex } from "../champions-data.mjs";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const input = process.argv[2];
if (!input) throw new Error("Usage: node import-master-data.mjs <decrypted-master-data-directory>");
const read = name => JSON.parse(fs.readFileSync(path.join(input, name), "utf8"));
const meta = read("MdListMeta");
if (meta.ver !== 18) throw new Error("This importer has been verified against Master Data v18 only");
const forms = JSON.parse(fs.readFileSync(path.join(root, "tools/team-code-resolver/data/champions-species-forms.v18.json")));
const localization = JSON.parse(fs.readFileSync(path.join(root, "src/data/localization/zh-Hans.json")));
function numeric(kind, table, dex) {
  const rows = read(`${table}.json`);
  const official = new Set(rows.map(row => Number(row.id)));
  const result = {};
  for (const entry of localization.filter(entry => entry.entityType === kind)) {
    const entity = dex.get(entry.showdownId);
    if (kind === "move" && entity.id === "nomove") continue;
    if (!entity.exists || !official.has(entity.num)) throw new Error(`Missing official ${kind}: ${entry.showdownId}`);
    if (result[entity.num] && result[entity.num] !== entry.showdownId) throw new Error(`Duplicate ${kind} ID ${entity.num}`);
    result[entity.num] = entry.showdownId;
  }
  if (kind === "move") {
    const missing = rows.filter(row => row.available === '1' && !result[Number(row.id)]);
    if (missing.length) throw new Error(`Client moves missing from application catalog: ${missing.map(row => row.id).join(', ')}`);
  }
  const assigned = kind === 'ability' ? new Set(read('personal.json').flatMap(row => [row.toku0, row.toku1, row.toku2]).map(Number)) : null;
  const required = kind === 'item' ? rows : kind === 'ability' ? rows.filter(row => assigned.has(Number(row.id))) : [];
  const missing = required.filter(row => !result[Number(row.id)]);
  if (missing.length) throw new Error(`Client ${kind} missing from application catalog: ${missing.map(row => row.id).join(', ')}`);
  return result;
}
const previous = JSON.parse(fs.readFileSync(path.join(root, "tools/team-code-resolver/data/champions-entity-map.v17.json")));
const corrections = JSON.parse(fs.readFileSync(path.join(root, "tools/team-code-resolver/data/champions-form-corrections.v18.json")));
const asset = {
  schemaVersion: 1, masterDataVersion: 18,
  species: Object.fromEntries(forms.entries.map(row => [`${row.pokemonNumber}:${row.formNumber}`, row.speciesId])),
  moves: numeric("move", "waza", Dex.moves),
  abilities: numeric("ability", "tokusei", Dex.abilities),
  items: numeric("item", "item", Dex.items),
  natures: previous.natures,
};
for (const kind of ["species", "moves", "abilities", "items"]) {
  for (const [number, name] of Object.entries(previous[kind])) {
    const correction = kind === 'species' && corrections.entries[number];
    if (asset[kind][number] !== name && !(correction?.before === name && correction.after === asset[kind][number])) {
      throw new Error(`Unexpected existing ${kind} mapping change: ${number}`);
    }
  }
}
const hashes = Object.fromEntries(["MdListMeta", "personal.json", "waza.json", "tokusei.json", "item.json", "waza_learn.json"].map(name =>
  [name, crypto.createHash("sha256").update(fs.readFileSync(path.join(input, name))).digest("hex")],
));
const out = path.join(root, "tools/team-code-resolver/data");
fs.writeFileSync(path.join(out, "champions-entity-map.v18.json"), `${JSON.stringify(asset)}\n`);
fs.writeFileSync(path.join(out, "champions-master-data.v18.provenance.json"), `${JSON.stringify({
  masterDataVersion: 18, clientVersion: "1.2.0", capturedOn: "2026-09-09", sha256: hashes,
  coverage: {
    ...Object.fromEntries(["species", "moves", "abilities", "items"].map(kind => [kind, Object.keys(asset[kind]).length])),
    availableMoves: read('waza.json').filter(row => row.available === '1').length,
    learnsetForms: read('waza_learn.json').length,
  },
  scope: "All official form rows and available moves; compatible historical numeric IDs retained. Unknown IDs are rejected.",
  onlineLookupVerified: false,
}, null, 2)}\n`);
console.log("Imported v18", Object.fromEntries(["species", "moves", "abilities", "items"].map(kind => [kind, Object.keys(asset[kind]).length])));

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { Dex } from "@pkmn/dex";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..", "..");
const personalPath = process.argv[2];
const outputPath = process.argv[3] || path.join(
  root,
  "tools",
  "team-code-resolver",
  "data",
  "champions-species-forms.v17.json",
);

if (!personalPath) {
  throw new Error("Usage: node generate-species-map.mjs <decrypted-personal.json> [output.json]");
}

const TYPE_NAMES = [
  "Normal", "Fighting", "Flying", "Poison", "Ground", "Rock", "Bug", "Ghost", "Steel",
  "Fire", "Water", "Grass", "Electric", "Psychic", "Ice", "Dragon", "Dark", "Fairy",
];

// These rows are intentionally explicit: their game forms have identical battle
// data, so stats, typing, and abilities cannot distinguish them.
const IDENTICAL_FORM_OVERRIDES = {
  "666:0": "Vivillon",
  "666:1": "Vivillon",
  "666:2": "Vivillon",
  "666:3": "Vivillon",
  "666:4": "Vivillon",
  "666:5": "Vivillon",
  "666:6": "Vivillon",
  "666:7": "Vivillon",
  "666:8": "Vivillon",
  "666:9": "Vivillon",
  "666:10": "Vivillon",
  "666:11": "Vivillon",
  "666:12": "Vivillon",
  "666:13": "Vivillon",
  "666:14": "Vivillon",
  "666:15": "Vivillon",
  "666:16": "Vivillon",
  "666:17": "Vivillon",
  "666:18": "Vivillon-Fancy",
  "666:19": "Vivillon-Pokeball",
  "678:2": "Meowstic-M-Mega",
  "678:3": "Meowstic-F-Mega",
  "681:0": "Aegislash-Both",
  "778:0": "Mimikyu",
  "778:1": "Mimikyu-Busted",
  "855:0": "Polteageist",
  "855:1": "Polteageist-Antique",
  "877:0": "Morpeko",
  "877:1": "Morpeko-Hangry",
  "925:0": "Maushold-Four",
  "925:1": "Maushold",
  "1013:0": "Sinistcha",
  "1013:1": "Sinistcha-Masterpiece",
};

const localization = JSON.parse(fs.readFileSync(
  path.join(root, "src", "data", "localization", "zh-Hans.json"),
  "utf8",
));
const allowedSpecies = new Set(
  localization
    .filter((entry) => entry.entityType === "species")
    .map((entry) => normalizeId(entry.showdownId)),
);
const personalRows = JSON.parse(fs.readFileSync(personalPath, "utf8"));
const dexSpecies = Dex.species.all().filter(
  (species) => species.exists && species.num > 0 && allowedSpecies.has(species.id),
);

const entries = personalRows.map((row) => {
  const key = `${Number(row.no)}:${Number(row.fo)}`;
  const override = IDENTICAL_FORM_OVERRIDES[key];
  const matches = findMatches(row, dexSpecies);
  const speciesId = override || (matches.length === 1 ? matches[0].name : undefined);
  if (!speciesId) {
    throw new Error(`${key} did not map uniquely: ${matches.map((entry) => entry.name).join(", ") || "none"}`);
  }
  if (!allowedSpecies.has(normalizeId(speciesId))) {
    throw new Error(`${key} maps to an entity missing from zh-Hans.json: ${speciesId}`);
  }
  return { pokemonNumber: Number(row.no), formNumber: Number(row.fo), speciesId };
});

const duplicateKeys = entries.filter((entry, index) =>
  entries.findIndex((candidate) =>
    candidate.pokemonNumber === entry.pokemonNumber && candidate.formNumber === entry.formNumber
  ) !== index
);
if (duplicateKeys.length) throw new Error(`Duplicate game form keys: ${JSON.stringify(duplicateKeys)}`);
if (entries.length !== 361) throw new Error(`Expected 361 Champions form rows, found ${entries.length}`);

fs.mkdirSync(path.dirname(outputPath), { recursive: true });
fs.writeFileSync(outputPath, `${JSON.stringify({ schemaVersion: 1, masterDataVersion: 17, entries }, null, 2)}\n`);
process.stdout.write(`Wrote ${entries.length} form mappings to ${outputPath}\n`);

function findMatches(row, candidates) {
  const expectedStats = [row.hp, row.atk, row.def, row.spatk, row.spdef, row.agi].map(Number);
  const expectedTypes = unique([row.type1, row.type2].map(Number).map((id) => TYPE_NAMES[id]).filter(Boolean)).sort();
  const expectedAbilities = unique([row.toku0, row.toku1, row.toku2].map(Number).filter(Boolean));
  return candidates.filter((species) => {
    const stats = species.baseStats;
    const actualStats = [stats.hp, stats.atk, stats.def, stats.spa, stats.spd, stats.spe];
    const actualAbilities = Object.values(species.abilities || {}).map(
      (name) => Dex.abilities.get(name).num,
    );
    return species.num === Number(row.no) &&
      actualStats.every((value, index) => value === expectedStats[index]) &&
      JSON.stringify(species.types.slice().sort()) === JSON.stringify(expectedTypes) &&
      expectedAbilities.every((ability) => actualAbilities.includes(ability));
  });
}

function unique(values) {
  return [...new Set(values)];
}

function normalizeId(value) {
  return String(value).toLowerCase().replace(/[^a-z0-9]+/g, "");
}
